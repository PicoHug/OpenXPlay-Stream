// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16

//! Wraps any projection output so a main-screen stream its receiver cannot decode is transcoded
//! on the way in. The wrapped output only sees model formats and frames; the policy decides from
//! the capabilities that output reports.

use crate::{
    pacing::Watchdog, EncodedFrame, TranscodeConfig, TranscodeSupport, Transcoder,
    TranscoderFactory,
};
use oxp_core::{model::*, OutputHost, ProjectionOutput};
use oxp_media::transcode_policy::{self, CodecProfile, SinkVideo, SourceVideo, TranscodeDecision};
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, Weak,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

pub type Diagnostics = Arc<dyn Fn(&str) + Send + Sync>;

/// Only the main screen is transcoded; other screens pass through.
const SCREEN: u32 = 0;
/// Input waiting for the transcoder; beyond this it restarts at the next IDR instead of lagging.
const QUEUE_FRAMES: usize = 8;
const QUEUE_BYTES: usize = 8 << 20;
const STALL_LIMIT: Duration = Duration::from_secs(5);
const IDLE_POLL: Duration = Duration::from_millis(4);

pub struct TranscodingOutput {
    shared: Arc<Shared>,
}
struct Shared {
    weak: Weak<Shared>,
    inner: Arc<dyn ProjectionOutput>,
    factory: Arc<dyn TranscoderFactory>,
    support: Option<TranscodeSupport>,
    diagnostics: Diagnostics,
    state: Mutex<State>,
    ops: Mutex<VecDeque<Op>>,
    draining: AtomicBool,
}
#[derive(Default)]
struct State {
    host: Option<Arc<dyn OutputHost>>,
    closed: bool,
    sink: Option<SinkVideo>,
    source: Option<Arc<VideoFormat>>,
    /// None until the wrapped output reports capabilities; streams then pass through.
    decision: Option<TranscodeDecision>,
    pipeline: Option<Pipeline>,
    /// The last stopped worker; the next one waits for it to release the codec devices.
    retired: Option<JoinHandle<()>>,
    /// The pipeline for the current decision failed; frames pass through until it changes.
    failed: bool,
    /// Identifies the current pipeline so late output from a stopped one is discarded.
    revision: u64,
    delivered: Option<Arc<VideoFormat>>,
    reported: Option<String>,
    /// The wrapped output's last control origin, reused for the adapter's own requests.
    origin: Option<Origin>,
}
/// Video calls into the wrapped output are serialized through this queue, whichever thread
/// (hub delivery, wrapped output or transcoder worker) produces them.
enum Op {
    Session(SessionState, SessionConfig),
    Format(Option<Arc<VideoFormat>>),
    Frame(VideoFrame),
    Reconfigure,
    Encoded {
        revision: u64,
        format: Arc<VideoFormat>,
        frame: VideoFrame,
    },
    Failed {
        revision: u64,
        error: String,
    },
}

impl TranscodingOutput {
    /// Probes the factory once; `diagnostics` receives one line per decision change or failure.
    pub fn new(
        inner: Arc<dyn ProjectionOutput>,
        factory: Arc<dyn TranscoderFactory>,
        diagnostics: Diagnostics,
    ) -> Self {
        let support = factory.support();
        Self {
            shared: Arc::new_cyclic(|weak| Shared {
                weak: weak.clone(),
                inner,
                factory,
                support,
                diagnostics,
                state: Default::default(),
                ops: Default::default(),
                draining: AtomicBool::new(false),
            }),
        }
    }
}
impl ProjectionOutput for TranscodingOutput {
    fn id(&self) -> &str {
        self.shared.inner.id()
    }
    fn open(&self, host: Arc<dyn OutputHost>) -> oxp_core::Result<()> {
        {
            let mut st = self.shared.state.lock().unwrap();
            st.host = Some(host.clone());
            st.closed = false;
            // A reopened output negotiates with its receiver again.
            st.sink = None;
            st.origin = None;
        }
        self.shared.inner.open(Arc::new(Proxy {
            shared: self.shared.weak.clone(),
            upstream: host,
        }))
    }
    fn on_session(&self, state: &SessionState, config: &SessionConfig) {
        self.shared
            .submit(Op::Session(state.clone(), config.clone()));
    }
    fn on_video_format(&self, screen: u32, format: Option<Arc<VideoFormat>>) {
        if screen == SCREEN {
            self.shared.submit(Op::Format(format));
        } else {
            self.shared.inner.on_video_format(screen, format);
        }
    }
    fn on_video_frame(&self, frame: VideoFrame) {
        if frame.screen == SCREEN {
            self.shared.submit(Op::Frame(frame));
        } else {
            self.shared.inner.on_video_frame(frame);
        }
    }
    fn on_audio_format(&self, stream: u32, format: Option<Arc<AudioFormat>>) {
        self.shared.inner.on_audio_format(stream, format);
    }
    fn on_audio_packet(&self, packet: AudioPacket) {
        self.shared.inner.on_audio_packet(packet);
    }
    fn on_microphone(&self, stream: u32, request: Option<MicRequest>) {
        self.shared.inner.on_microphone(stream, request);
    }
    fn close(&self) {
        {
            let mut st = self.shared.state.lock().unwrap();
            st.closed = true;
            st.stop_pipeline();
            st.source = None;
            st.decision = None;
            st.reported = None;
            st.revision += 1;
        }
        self.shared.inner.close();
    }
}
impl Drop for TranscodingOutput {
    fn drop(&mut self) {
        self.shared.state.lock().unwrap().stop_pipeline();
    }
}

impl State {
    fn stop_pipeline(&mut self) {
        if let Some(pipeline) = self.pipeline.take() {
            self.retired = pipeline.stop();
        }
        self.delivered = None;
    }
}

impl Shared {
    fn submit(&self, op: Op) {
        self.ops.lock().unwrap().push_back(op);
        self.drain();
    }
    fn drain(&self) {
        loop {
            if self.draining.swap(true, Ordering::AcqRel) {
                return;
            }
            loop {
                let op = self.ops.lock().unwrap().pop_front();
                match op {
                    Some(op) => self.apply(op),
                    None => break,
                }
            }
            self.draining.store(false, Ordering::Release);
            if self.ops.lock().unwrap().is_empty() {
                return;
            }
        }
    }
    /// Runs with no lock held while calling the wrapped output, so it may re-enter the adapter.
    fn apply(&self, op: Op) {
        let mut st = self.state.lock().unwrap();
        if st.closed {
            return;
        }
        match op {
            Op::Frame(frame) => {
                if let Some(pipeline) = &st.pipeline {
                    pipeline.offer(frame);
                    return;
                }
                drop(st);
                self.inner.on_video_frame(frame);
            }
            Op::Encoded {
                revision,
                format,
                frame,
            } => {
                if st.revision != revision || st.pipeline.is_none() {
                    return;
                }
                let first = !st
                    .delivered
                    .as_ref()
                    .is_some_and(|f| Arc::ptr_eq(f, &format));
                if first {
                    // An encoder that ignored the target would leave the receiver black again.
                    let produced = CodecProfile::parse(&format.codec_string);
                    if !produced.is_some_and(|p| st.sink.as_ref().is_some_and(|s| s.accepts(&p))) {
                        drop(st);
                        let produced =
                            produced.map_or(format.codec_string.clone(), |p| p.to_string());
                        return self.apply(Op::Failed {
                            revision,
                            error: format!(
                                "encoder produced {produced}, which the receiver rejects"
                            ),
                        });
                    }
                    st.delivered = Some(format.clone());
                }
                drop(st);
                if first {
                    self.inner.on_video_format(SCREEN, Some(format));
                }
                self.inner.on_video_frame(frame);
            }
            Op::Format(None) => {
                st.stop_pipeline();
                st.source = None;
                st.decision = None;
                st.failed = false;
                st.reported = None;
                st.revision += 1;
                drop(st);
                self.inner.on_video_format(SCREEN, None);
            }
            Op::Format(Some(format)) => {
                if st.source.as_ref().is_some_and(|s| same_stream(s, &format)) {
                    // Inputs repeat an identical configuration, for example with every keyframe.
                    // Rebuilding would request another keyframe and loop; keep the pipeline.
                    if st.pipeline.is_some() {
                        return;
                    }
                    st.source = Some(format.clone());
                    drop(st);
                    self.inner.on_video_format(SCREEN, Some(format));
                    return;
                }
                st.source = Some(format);
                self.configure(st, true);
            }
            Op::Reconfigure => self.configure(st, false),
            Op::Failed { revision, error } => {
                if st.revision != revision || st.pipeline.is_none() {
                    return;
                }
                st.stop_pipeline();
                st.revision += 1;
                st.failed = true;
                let source = st.source.clone();
                drop(st);
                (self.diagnostics)(&format!("transcoding unavailable: {error}"));
                self.inner.on_video_format(SCREEN, source);
                self.request_key_frame();
            }
            Op::Session(state, config) => {
                if state.phase == SessionPhase::Ended {
                    st.stop_pipeline();
                    st.source = None;
                    st.decision = None;
                    st.failed = false;
                    st.reported = None;
                    st.revision += 1;
                }
                drop(st);
                self.inner.on_session(&state, &config);
            }
        }
    }
    /// Applies the policy to the current source. Without `force`, an unchanged decision keeps
    /// the running pipeline.
    fn configure(&self, mut st: std::sync::MutexGuard<'_, State>, force: bool) {
        let Some(source) = st.source.clone() else {
            return;
        };
        let video = source_video(&source);
        let decision = st.sink.as_ref().map(|sink| match &video {
            Some(video) => transcode_policy::decide(video, sink, self.support.as_ref()),
            None => TranscodeDecision::Unsupported {
                reason: "source-unknown",
            },
        });
        if !force && decision == st.decision {
            return;
        }
        st.stop_pipeline();
        st.revision += 1;
        st.decision = decision.clone();
        st.failed = false;
        let mut lines = Vec::new();
        if let (Some(decision), Some(video)) = (&decision, &video) {
            let line = transcode_policy::describe(video, decision);
            if st.reported.as_ref() != Some(&line) {
                st.reported = Some(line.clone());
                lines.push(line);
            }
        }
        let mut forward = Some(source.clone());
        if let Some(TranscodeDecision::Transcode { target, .. }) = decision {
            let config = TranscodeConfig { source, target };
            let previous = st.retired.take();
            match Pipeline::start(
                self.weak.clone(),
                st.revision,
                config,
                self.factory.clone(),
                previous,
            ) {
                Ok(pipeline) => {
                    st.pipeline = Some(pipeline);
                    // The wrapped output shows its placeholder until the encoder describes its stream.
                    forward = None;
                }
                Err(e) => {
                    st.failed = true;
                    lines.push(format!("transcoding unavailable: {e}"));
                }
            }
        }
        drop(st);
        for line in lines {
            (self.diagnostics)(&line);
        }
        self.inner.on_video_format(SCREEN, forward);
    }
    /// Asks the input for an IDR: a new decoder, a discontinuity or a fallback needs one.
    fn request_key_frame(&self) {
        let (host, origin) = {
            let st = self.state.lock().unwrap();
            if st.closed {
                return;
            }
            (st.host.clone(), st.origin.clone())
        };
        let origin = origin.unwrap_or_else(|| Origin {
            output_id: self.inner.id().into(),
            client_id: "transcoder".into(),
        });
        if let Some(host) = host {
            host.control(ControlEvent::KeyFrameRequest {
                screen: SCREEN,
                origin,
            });
        }
    }
}

fn same_stream(a: &VideoFormat, b: &VideoFormat) -> bool {
    a.codec == b.codec && a.config == b.config
}
fn source_video(format: &VideoFormat) -> Option<SourceVideo> {
    Some(SourceVideo {
        profile: CodecProfile::parse(&format.codec_string)?,
        width: format.width,
        height: format.height,
        fps: format.fps_hint,
    })
}
/// The receiver as the policy sees it: its decoders, bounded by its main display.
fn sink_video(caps: &OutputCapabilities) -> SinkVideo {
    let mut sink =
        SinkVideo::from_codec_strings(caps.decoders.iter().map(|d| d.codec_string.as_str()));
    if let Some(display) = caps.displays.get(&SCREEN) {
        sink.max_width = (display.width_px > 0).then_some(display.width_px);
        sink.max_height = (display.height_px > 0).then_some(display.height_px);
        sink.max_fps = (display.refresh_hz > 0.0).then_some(display.refresh_hz);
    }
    sink
}

/// The host handed to the wrapped output: capability reports drive the policy and keyframe
/// requests also reach the encoder while a pipeline runs.
struct Proxy {
    shared: Weak<Shared>,
    upstream: Arc<dyn OutputHost>,
}
impl OutputHost for Proxy {
    fn capabilities(&self, caps: OutputCapabilities) {
        if let Some(shared) = self.shared.upgrade() {
            let sink = sink_video(&caps);
            let changed = {
                let mut st = shared.state.lock().unwrap();
                let changed = st.sink.as_ref() != Some(&sink);
                st.sink = Some(sink);
                changed
            };
            if changed {
                shared.submit(Op::Reconfigure);
            }
        }
        self.upstream.capabilities(caps);
    }
    fn control(&self, event: ControlEvent) {
        if let Some(shared) = self.shared.upgrade() {
            let mut st = shared.state.lock().unwrap();
            st.origin = Some(origin(&event).clone());
            if let (ControlEvent::KeyFrameRequest { screen: SCREEN, .. }, Some(pipeline)) =
                (&event, &st.pipeline)
            {
                // The encoder makes the IDR. The request still reaches the input: an encoder only
                // emits a frame for new input, and a static screen sends none.
                pipeline.request_key_frame();
            }
        }
        self.upstream.control(event);
    }
    fn stats(&self, stats: OutputStats) {
        self.upstream.stats(stats);
    }
    fn request_restart(&self, origin: Origin) {
        self.upstream.request_restart(origin);
    }
}
fn origin(event: &ControlEvent) -> &Origin {
    match event {
        ControlEvent::DisplaySettings { origin, .. }
        | ControlEvent::Touch { origin, .. }
        | ControlEvent::Key { origin, .. }
        | ControlEvent::KeyFrameRequest { origin, .. }
        | ControlEvent::MicPacket { origin, .. } => origin,
    }
}

/// One transcoder session on its own worker thread.
struct Pipeline {
    control: Arc<PipelineControl>,
    worker: Option<JoinHandle<()>>,
}
struct PipelineControl {
    running: AtomicBool,
    key_frame: AtomicBool,
    input: Mutex<Input>,
}
struct Input {
    frames: VecDeque<VideoFrame>,
    bytes: usize,
    awaiting_key: bool,
    discontinuity: bool,
}
impl Pipeline {
    fn start(
        owner: Weak<Shared>,
        revision: u64,
        config: TranscodeConfig,
        factory: Arc<dyn TranscoderFactory>,
        previous: Option<JoinHandle<()>>,
    ) -> std::io::Result<Self> {
        let control = Arc::new(PipelineControl {
            running: AtomicBool::new(true),
            key_frame: AtomicBool::new(false),
            input: Mutex::new(Input {
                frames: VecDeque::with_capacity(QUEUE_FRAMES),
                bytes: 0,
                awaiting_key: true,
                discontinuity: false,
            }),
        });
        let worker_control = control.clone();
        let worker = thread::Builder::new()
            .name("oxp-transcode".into())
            .spawn(move || {
                if let Some(previous) = previous {
                    let _ = previous.join();
                }
                work(&worker_control, &owner, revision, &config, factory.as_ref());
            })?;
        Ok(Self {
            control,
            worker: Some(worker),
        })
    }
    fn offer(&self, frame: VideoFrame) {
        let mut input = self.control.input.lock().unwrap();
        if input.awaiting_key && !frame.key_frame {
            return;
        }
        if input.frames.len() >= QUEUE_FRAMES || input.bytes + frame.data.len() > QUEUE_BYTES {
            // The transcoder fell behind; restart at an IDR instead of adding latency.
            input.frames.clear();
            input.bytes = 0;
            input.discontinuity = true;
            if !frame.key_frame {
                input.awaiting_key = true;
                return;
            }
        }
        input.awaiting_key = false;
        input.bytes += frame.data.len();
        input.frames.push_back(frame);
    }
    fn request_key_frame(&self) {
        self.control.key_frame.store(true, Ordering::Release);
    }
    fn stop(mut self) -> Option<JoinHandle<()>> {
        self.control.running.store(false, Ordering::Release);
        self.worker.take()
    }
}
impl Drop for Pipeline {
    fn drop(&mut self) {
        self.control.running.store(false, Ordering::Release);
    }
}

fn work(
    control: &PipelineControl,
    owner: &Weak<Shared>,
    revision: u64,
    config: &TranscodeConfig,
    factory: &dyn TranscoderFactory,
) {
    let fail = |error: String| {
        if control.running.load(Ordering::Acquire) {
            if let Some(shared) = owner.upgrade() {
                shared.submit(Op::Failed { revision, error });
            }
        }
    };
    if !control.running.load(Ordering::Acquire) {
        return;
    }
    let mut transcoder = match factory.open(config) {
        Ok(t) => t,
        Err(e) => return fail(e.to_string()),
    };
    if !control.running.load(Ordering::Acquire) {
        return;
    }
    // The decoder starts at an IDR; ask for one now instead of waiting for the next periodic one.
    if let Some(shared) = owner.upgrade() {
        shared.request_key_frame();
    }
    if let Err(e) = pump(control, owner, revision, config, transcoder.as_mut()) {
        fail(e.to_string());
    }
}

fn pump(
    control: &PipelineControl,
    owner: &Weak<Shared>,
    revision: u64,
    config: &TranscodeConfig,
    transcoder: &mut dyn Transcoder,
) -> crate::Result<()> {
    let mut out = EncodedFrame::default();
    let mut described: Option<(Arc<[u8]>, Arc<VideoFormat>)> = None;
    // Session and receive time of frames inside the transcoder, matched by timestamp.
    const INSIDE: usize = 32;
    let mut inside: VecDeque<(i64, i64, i64)> = VecDeque::with_capacity(INSIDE);
    let mut session = 0;
    let mut pending: Option<VideoFrame> = None;
    let mut watchdog = Watchdog::new(STALL_LIMIT, Instant::now());
    while control.running.load(Ordering::Acquire) {
        if control.key_frame.swap(false, Ordering::AcqRel) {
            transcoder.force_key_frame()?;
        }
        let mut more = false;
        if pending.is_none() {
            let mut input = control.input.lock().unwrap();
            if std::mem::take(&mut input.discontinuity) {
                drop(input);
                transcoder.reset()?;
                inside.clear();
                if let Some(shared) = owner.upgrade() {
                    shared.request_key_frame();
                }
                continue;
            }
            pending = input.frames.pop_front();
            if let Some(f) = &pending {
                input.bytes -= f.data.len();
            }
            more = !input.frames.is_empty();
        }
        let mut blocked = false;
        if let Some(frame) = pending.take() {
            if transcoder.submit(&frame.data, frame.pts_us, frame.key_frame)? {
                watchdog.submitted(Instant::now());
                if inside.len() == INSIDE {
                    inside.pop_front();
                }
                inside.push_back((frame.pts_us, frame.session, frame.received_at_nanos));
                session = frame.session;
            } else {
                pending = Some(frame);
                blocked = true;
            }
        }
        let timeout = if more && !blocked {
            Duration::ZERO
        } else {
            IDLE_POLL
        };
        if !transcoder.poll(&mut out, timeout)? {
            if watchdog.stalled(blocked, Instant::now()) {
                return Err("transcoder stalled".into());
            }
            continue;
        }
        watchdog.output(Instant::now());
        let (Some(avcc), false) = (out.config.clone(), out.data.is_empty()) else {
            continue;
        };
        if described
            .as_ref()
            .is_none_or(|(c, _)| !Arc::ptr_eq(c, &avcc))
        {
            let format = VideoFormat::create(
                SCREEN,
                VideoCodec::H264,
                avcc.clone(),
                Some(config.target.fps as f64),
            )?;
            described = Some((avcc, Arc::new(format)));
        }
        let (session, received_at_nanos) =
            match inside.iter().position(|(pts, ..)| *pts == out.pts_us) {
                Some(i) => {
                    let (_, session, received) = inside[i];
                    inside.drain(..=i);
                    (session, received)
                }
                None => (session, oxp_core::monotonic_nanos()),
            };
        let frame = VideoFrame {
            screen: SCREEN,
            session,
            data: Arc::from(&out.data[..]),
            pts_us: out.pts_us,
            key_frame: out.key_frame,
            received_at_nanos,
        };
        let Some(shared) = owner.upgrade() else {
            return Ok(());
        };
        shared.submit(Op::Encoded {
            revision,
            format: described.as_ref().unwrap().1.clone(),
            frame,
        });
    }
    Ok(())
}
