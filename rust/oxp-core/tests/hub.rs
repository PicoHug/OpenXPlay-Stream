// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use oxp_core::{model::*, *};
use std::{
    sync::{Arc, Mutex},
    time::Duration,
};
#[derive(Default)]
struct Host {
    caps: Mutex<Vec<OutputCapabilities>>,
    controls: Mutex<Vec<ControlEvent>>,
    restarts: Mutex<Vec<Origin>>,
}
impl OutputHost for Host {
    fn capabilities(&self, caps: OutputCapabilities) {
        self.caps.lock().unwrap().push(caps);
    }
    fn control(&self, e: ControlEvent) {
        self.controls.lock().unwrap().push(e);
    }
    fn stats(&self, _: OutputStats) {}
    fn request_restart(&self, origin: Origin) {
        self.restarts.lock().unwrap().push(origin);
    }
}
struct Output {
    id: String,
    host: Mutex<Option<Arc<dyn OutputHost>>>,
    events: Mutex<Vec<String>>,
    frames: Mutex<Vec<VideoFrame>>,
    contexts: Mutex<Vec<ContextUpdate>>,
    wants_context: bool,
    settings: Mutex<Vec<DisplaySettings>>,
    areas: Mutex<Vec<(u32, Option<ViewArea>)>>,
}
impl Output {
    fn new(id: &str) -> Arc<Self> {
        Arc::new(Self {
            id: id.into(),
            host: Mutex::new(None),
            events: Mutex::new(Vec::new()),
            frames: Mutex::new(Vec::new()),
            contexts: Mutex::new(Vec::new()),
            wants_context: false,
            settings: Mutex::new(Vec::new()),
            areas: Mutex::new(Vec::new()),
        })
    }
    fn with_context(id: &str) -> Arc<Self> {
        let mut output = Self::new(id);
        Arc::get_mut(&mut output).unwrap().wants_context = true;
        output
    }
    fn control(&self, e: ControlEvent) {
        self.host.lock().unwrap().as_ref().unwrap().control(e);
    }
    fn origin(&self) -> Origin {
        Origin {
            output_id: self.id.clone(),
            client_id: "client".into(),
        }
    }
    fn touch(&self, contacts: Vec<TouchContact>) {
        self.control(ControlEvent::Touch {
            screen: 0,
            contacts,
            origin: self.origin(),
        });
    }
}
impl ProjectionOutput for Output {
    fn on_display_settings(&self, settings: DisplaySettings) {
        self.settings.lock().unwrap().push(settings);
    }
    fn on_view_area(&self, screen: u32, area: Option<ViewArea>) {
        self.areas.lock().unwrap().push((screen, area));
    }
    fn wants_context(&self) -> bool {
        self.wants_context
    }
    fn id(&self) -> &str {
        &self.id
    }
    fn open(&self, h: Arc<dyn OutputHost>) -> Result<()> {
        *self.host.lock().unwrap() = Some(h);
        self.events.lock().unwrap().push("open".into());
        Ok(())
    }
    fn on_context(&self, update: ContextUpdate) {
        self.contexts.lock().unwrap().push(update);
    }
    fn on_session(&self, s: &SessionState, _: &SessionConfig) {
        self.events
            .lock()
            .unwrap()
            .push(format!("session:{}", s.id));
    }
    fn on_video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {
        self.events.lock().unwrap().push("video".into());
    }
    fn on_video_frame(&self, f: VideoFrame) {
        self.frames.lock().unwrap().push(f);
    }
    fn on_audio_format(&self, _: u32, _: Option<Arc<AudioFormat>>) {
        self.events.lock().unwrap().push("audio".into());
    }
    fn on_audio_packet(&self, p: AudioPacket) {
        self.events
            .lock()
            .unwrap()
            .push(format!("audio-packet:{}", p.pts_us));
    }
    fn on_microphone(&self, _: u32, _: Option<MicRequest>) {
        self.events.lock().unwrap().push("mic".into());
    }
    fn close(&self) {
        self.events.lock().unwrap().push("close".into());
    }
}
fn active(h: &Hub, id: i64) {
    h.session(&SessionState {
        id,
        phase: SessionPhase::Active,
        reason: None,
    });
}
fn frame(id: i64) -> VideoFrame {
    VideoFrame {
        screen: 0,
        session: id,
        data: Arc::from([1, 2]),
        pts_us: 0,
        key_frame: true,
        received_at_nanos: 0,
    }
}
fn format() -> Arc<VideoFormat> {
    let bytes = include_str!("../../../spec/vectors/avc-baseline-640x480.hex")
        .split_whitespace()
        .map(|s| u8::from_str_radix(s, 16).unwrap())
        .collect::<Vec<_>>();
    Arc::new(VideoFormat::create(0, VideoCodec::H264, bytes.into(), None).unwrap())
}
#[test]
fn fanout_late_join_and_session_fencing() {
    let host = Arc::new(Host::default());
    let h = Hub::new(host.clone());
    let a = Output::new("a");
    h.add_output(a.clone()).unwrap();
    active(&h, 10);
    h.video_format(0, Some(format()));
    h.audio_format(
        0,
        Some(Arc::new(AudioFormat {
            stream: 0,
            role: AudioRole::Media,
            codec: AudioCodec::Opus,
            sample_rate: 48000,
            channels: 2,
            config: None,
        })),
    );
    h.microphone(
        0,
        Some(MicRequest {
            stream: 0,
            codec: AudioCodec::Opus,
            sample_rate: 48000,
            channels: 1,
            frame_ms: 20,
        }),
    );
    let b = Output::new("b");
    h.add_output(b.clone()).unwrap();
    assert_eq!(
        *b.events.lock().unwrap(),
        ["open", "session:10", "video", "audio", "mic"]
    );
    assert_eq!(host.controls.lock().unwrap().len(), 1);
    let f = frame(10);
    h.video_frame(f.clone());
    assert!(Arc::ptr_eq(
        &a.frames.lock().unwrap()[0].data,
        &b.frames.lock().unwrap()[0].data
    ));
    active(&h, 11);
    active(&h, 9);
    h.video_frame(frame(10));
    h.video_frame(frame(11));
    assert_eq!(a.frames.lock().unwrap().len(), 2);
    let c = Output::new("c");
    h.add_output(c.clone()).unwrap();
    assert_eq!(*c.events.lock().unwrap(), ["open", "session:11"]);
    h.remove_output("b");
    h.video_frame(frame(11));
    assert_eq!(b.frames.lock().unwrap().len(), 2);
    assert_eq!(b.events.lock().unwrap().last().unwrap(), "close");
    assert!(h.add_output(a.clone()).is_err());
    h.close();
    h.video_frame(frame(11));
    assert_eq!(a.frames.lock().unwrap().len(), 3);
}
#[test]
fn complete_contact_sets_keys_forged_origins_and_removal() {
    let host = Arc::new(Host::default());
    let h = Hub::new(host.clone());
    let a = Output::new("a");
    let b = Output::new("b");
    h.add_output(a.clone()).unwrap();
    h.add_output(b.clone()).unwrap();
    let down = TouchContact::new(0, 0.5, 0.5, true).unwrap();
    let other = TouchContact {
        id: 1,
        ..down.clone()
    };
    a.touch(vec![down.clone(), other.clone()]);
    b.touch(vec![down.clone()]);
    b.control(ControlEvent::Key {
        code: KeyCode::Home,
        down: true,
        origin: b.origin(),
    });
    assert_eq!(host.controls.lock().unwrap().len(), 1);
    a.touch(vec![TouchContact {
        pressed: false,
        ..other
    }]); // Contact zero is omitted, so no contacts remain pressed.
    b.touch(vec![down.clone()]);
    assert_eq!(host.controls.lock().unwrap().len(), 3);
    b.touch(vec![]);
    a.control(ControlEvent::Key {
        code: KeyCode::Home,
        down: true,
        origin: a.origin(),
    });
    assert_eq!(host.controls.lock().unwrap().len(), 4);
    a.control(ControlEvent::Key {
        code: KeyCode::Home,
        down: true,
        origin: b.origin(),
    });
    assert_eq!(host.controls.lock().unwrap().len(), 4);
    h.remove_output("b");
    a.touch(vec![down]);
    assert_eq!(host.controls.lock().unwrap().len(), 5);
    b.touch(vec![]);
    assert_eq!(host.controls.lock().unwrap().len(), 5);
    h.close();
}
#[test]
fn timer_coalesces_without_media() {
    let host = Arc::new(Host::default());
    let h = Hub::new(host.clone());
    let a = Output::new("a");
    h.add_output(a.clone()).unwrap();
    for _ in 0..5 {
        a.control(ControlEvent::KeyFrameRequest {
            screen: 0,
            origin: a.origin(),
        });
    }
    a.control(ControlEvent::KeyFrameRequest {
        screen: 1,
        origin: a.origin(),
    });
    assert_eq!(host.controls.lock().unwrap().len(), 2);
    let start = std::time::Instant::now();
    while host.controls.lock().unwrap().len() != 3 && start.elapsed() < Duration::from_secs(2) {
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(host.controls.lock().unwrap().len(), 3);
    h.close();
}
#[test]
fn limiter_exact_deadlines_and_screen_independence() {
    let mut l = KeyframeLimiter::default();
    let request = |screen| ControlEvent::KeyFrameRequest {
        screen,
        origin: Origin {
            output_id: "a".into(),
            client_id: "b".into(),
        },
    };
    assert_eq!(l.request(request(0), 0).len(), 1);
    assert!(l.request(request(0), 100).is_empty());
    assert_eq!(l.request(request(1), 100).len(), 1);
    assert_eq!(l.next_deadline_nanos(), Some(250_000_000));
    assert!(l.poll(249_999_999).is_empty());
    assert_eq!(l.poll(250_000_000).len(), 1);
    assert!(l.poll(500_000_000).is_empty());
    l.clear();
    assert_eq!(l.next_deadline_nanos(), None);
}
#[test]
fn queue_gating_overflow_reordered_timestamps_and_sessions() {
    let mut q = FrameQueue::new(100, 8);
    let f = |session, pts, key, size| VideoFrame {
        pts_us: pts,
        key_frame: key,
        data: vec![1; size].into(),
        ..frame(session)
    };
    assert_eq!(q.offer(f(1, 0, false, 2)), (false, true));
    assert_eq!(q.offer(f(1, 1, false, 2)), (false, false));
    assert_eq!(q.offer(f(1, 2, true, 2)), (true, false));
    assert!(q.poll().unwrap().gap);
    q.offer(f(1, 3, false, 2));
    assert!(!q.poll().unwrap().gap);
    q.clear();
    q.offer(f(1, 0, true, 2));
    q.offer(f(1, 50, false, 2));
    q.offer(f(1, 90, true, 2));
    assert_eq!(q.offer(f(1, 130, false, 2)), (true, true));
    let key = q.poll().unwrap();
    assert!(key.gap && key.frame.key_frame && key.frame.pts_us == 90);
    assert!(q.duration_us() <= 100);
    q.clear();
    q.offer(f(1, 100, true, 2));
    assert_eq!(q.offer(f(1, 0, false, 2)), (false, true));
    assert_eq!(q.offer(f(2, 0, false, 2)), (false, true));
    assert_eq!(q.offer(f(1, 0, true, 2)), (false, false));
    assert_eq!(q.offer(f(2, 0, true, 9)), (false, false));
    assert_eq!(q.queued_bytes(), 0);
    q.offer(f(2, 1, true, 8));
    assert_eq!(q.queued_bytes(), 8);
}

#[test]
fn failed_output_is_removed_and_callbacks_can_reenter() {
    struct Broken {
        closed: Arc<std::sync::atomic::AtomicBool>,
    }
    impl ProjectionOutput for Broken {
        fn id(&self) -> &str {
            "broken"
        }
        fn open(&self, _: Arc<dyn OutputHost>) -> Result<()> {
            Ok(())
        }
        fn on_session(&self, _: &SessionState, _: &SessionConfig) {}
        fn on_video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {}
        fn on_video_frame(&self, _: VideoFrame) {
            panic!("broken output");
        }
        fn close(&self) {
            self.closed
                .store(true, std::sync::atomic::Ordering::Release);
        }
    }
    let h = Hub::new(Arc::new(Host::default()));
    let closed = Arc::new(std::sync::atomic::AtomicBool::new(false));
    h.add_output(Arc::new(Broken {
        closed: closed.clone(),
    }))
    .unwrap();
    let good = Output::new("good");
    h.add_output(good.clone()).unwrap();
    h.video_frame(frame(1));
    h.video_frame(frame(1));
    assert_eq!(good.frames.lock().unwrap().len(), 2);
    assert!(closed.load(std::sync::atomic::Ordering::Acquire));
    struct Reentrant(std::sync::Weak<Hub>);
    impl ProjectionOutput for Reentrant {
        fn id(&self) -> &str {
            "reentrant"
        }
        fn open(&self, _: Arc<dyn OutputHost>) -> Result<()> {
            Ok(())
        }
        fn on_session(&self, _: &SessionState, _: &SessionConfig) {}
        fn on_video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {}
        fn on_video_frame(&self, _: VideoFrame) {
            let h = self.0.upgrade().unwrap();
            let _ = h.session_config();
            h.remove_output("reentrant");
        }
        fn close(&self) {}
    }
    h.add_output(Arc::new(Reentrant(Arc::downgrade(&h))))
        .unwrap();
    h.video_frame(frame(1));
    assert_eq!(good.frames.lock().unwrap().len(), 3);
    h.close();
}

#[test]
fn primary_display_and_hardware_hevc_policy_are_per_screen() {
    use std::collections::BTreeMap;
    let host = Arc::new(Host::default());
    let h = Hub::new(host);
    let buffered = Output::new("buffered");
    let primary = Output::new("primary");
    let secondary = Output::new("secondary");
    for o in [&buffered, &primary, &secondary] {
        h.add_output(o.clone()).unwrap();
    }
    let caps = |width, hardware, latency| OutputCapabilities {
        prefers_h264: false,
        latency,
        decoders: vec![DecoderSupport {
            codec_string: "hvc1.1.6.L93".into(),
            hardware,
        }],
        displays: BTreeMap::from([(
            0,
            DisplayInfo {
                width_px: width,
                ..Default::default()
            },
        )]),
        touch: 10,
        keys: KeyCode::all(),
        audio: Default::default(),
        microphone: false,
    };
    buffered
        .host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps(1920, true, LatencyClass::Buffered));
    primary
        .host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps(800, true, LatencyClass::Interactive));
    secondary
        .host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps(1024, true, LatencyClass::Interactive));
    assert_eq!(h.session_config().screens[&0].width, 800);
    assert_eq!(h.session_config().screens[&0].codec, VideoCodec::H265);
    secondary
        .host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps(1024, false, LatencyClass::Interactive));
    assert_eq!(h.session_config().screens[&0].codec, VideoCodec::H264);
    h.remove_output("secondary");
    assert_eq!(h.session_config().screens[&0].codec, VideoCodec::H265);
    h.remove_output("primary");
    assert_eq!(h.session_config().screens[&0].width, 1920);
    h.input_capabilities(InputCapabilities {
        codecs: vec![VideoCodec::H264],
        ..Default::default()
    });
    assert_eq!(h.session_config().screens[&0].codec, VideoCodec::H264);
    h.close();
}

#[test]
fn microphone_first_sender_touch_priority_stop_and_removal() {
    let host = Arc::new(Host::default());
    let h = Hub::new(host.clone());
    let a = Output::new("a");
    let b = Output::new("b");
    h.add_output(a.clone()).unwrap();
    h.add_output(b.clone()).unwrap();
    active(&h, 1);
    let request = || {
        Some(MicRequest {
            stream: 2,
            codec: AudioCodec::PcmS16le,
            sample_rate: 16000,
            channels: 1,
            frame_ms: 20,
        })
    };
    let send = |o: &Output| {
        o.control(ControlEvent::MicPacket {
            stream: 2,
            data: vec![0; 640].into(),
            pts_us: 0,
            origin: o.origin(),
        })
    };
    let count = || {
        host.controls
            .lock()
            .unwrap()
            .iter()
            .filter(|e| matches!(e, ControlEvent::MicPacket { .. }))
            .count()
    };
    send(&a);
    assert_eq!(count(), 0);
    h.microphone(2, request());
    send(&a);
    send(&b);
    assert_eq!(count(), 1);
    b.touch(vec![]);
    send(&b);
    send(&a);
    assert_eq!(count(), 2);
    h.remove_output("b");
    send(&a);
    assert_eq!(count(), 3);
    h.microphone(2, None);
    send(&a);
    assert_eq!(count(), 3);
    h.microphone(2, request());
    send(&a);
    assert_eq!(count(), 4);
    active(&h, 2);
    send(&a);
    assert_eq!(count(), 4);
    h.close();
}

#[test]
fn audio_packet_then_immediate_null_keeps_delivery_order() {
    let h = Hub::new(Arc::new(Host::default()));
    let output = Output::new("audio");
    h.add_output(output.clone()).unwrap();
    active(&h, 1);
    output.events.lock().unwrap().clear();
    h.audio_format(
        1,
        Some(Arc::new(AudioFormat {
            stream: 1,
            role: AudioRole::Voice,
            codec: AudioCodec::PcmS16le,
            sample_rate: 16000,
            channels: 1,
            config: None,
        })),
    );
    h.audio_packet(AudioPacket {
        stream: 1,
        session: 1,
        data: vec![7; 640].into(),
        pts_us: 123,
    });
    h.audio_format(1, None);
    assert_eq!(
        *output.events.lock().unwrap(),
        ["audio", "audio-packet:123", "audio"]
    );
    h.close();
}

#[test]
fn frame_rate_policy_and_restart_forwarding_follow_the_primary_display() {
    let upstream = Arc::new(Host::default());
    let hub = Hub::new(upstream.clone());
    let a = Output::new("a");
    let b = Output::new("b");
    hub.add_output(a.clone()).unwrap();
    hub.add_output(b.clone()).unwrap();
    let caps = |preference| OutputCapabilities {
        prefers_h264: false,
        latency: LatencyClass::Interactive,
        decoders: Vec::new(),
        displays: [(
            0,
            DisplayInfo {
                frame_rate: preference,
                ..DisplayInfo::default()
            },
        )]
        .into(),
        touch: 0,
        keys: Default::default(),
        audio: Default::default(),
        microphone: false,
    };
    a.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps(FrameRatePreference::Auto));
    b.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps(FrameRatePreference::Fps30));
    for fps in (30..=60).step_by(5) {
        a.host.lock().unwrap().as_ref().unwrap().capabilities(caps(
            FrameRatePreference::from_wire(&format!("FPS_{fps}")).unwrap(),
        ));
        assert_eq!(hub.session_config().screens[&0].fps, fps as f64);
    }
    a.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .request_restart(a.origin());
    assert!(upstream.restarts.lock().unwrap().is_empty());
    hub.input_capabilities(InputCapabilities {
        reconfigure: ReconfigureCost::RestartSession,
        ..InputCapabilities::default()
    });
    b.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .request_restart(b.origin());
    assert!(upstream.restarts.lock().unwrap().is_empty());
    a.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .request_restart(Origin {
            output_id: "forged".into(),
            client_id: "client".into(),
        });
    assert!(upstream.restarts.lock().unwrap().is_empty());
    a.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .request_restart(a.origin());
    assert_eq!(upstream.restarts.lock().unwrap().len(), 1);
    hub.close();
}

#[test]
fn upstream_capabilities_preserve_hardware_hevc_across_codec_strings_and_avc_join() {
    let host = Arc::new(Host::default());
    let hub = Hub::new(host.clone());
    let a = Output::new("web");
    let b = Output::new("clink");
    hub.add_output(a.clone()).unwrap();
    let caps = |codec: &str| OutputCapabilities {
        prefers_h264: false,
        latency: LatencyClass::Interactive,
        decoders: vec![DecoderSupport {
            codec_string: codec.into(),
            hardware: true,
        }],
        displays: std::collections::BTreeMap::from([(0, DisplayInfo::default())]),
        touch: 0,
        keys: Default::default(),
        audio: Default::default(),
        microphone: false,
    };
    a.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps("hvc1.1.6.L120.90"));
    hub.add_output(b.clone()).unwrap();
    b.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps("hev1.1.6.L120.90"));
    assert!(host
        .caps
        .lock()
        .unwrap()
        .last()
        .unwrap()
        .decoders
        .iter()
        .all(|d| d.hardware));
    b.host
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .capabilities(caps("avc1.42E01F"));
    assert!(!host
        .caps
        .lock()
        .unwrap()
        .last()
        .unwrap()
        .decoders
        .iter()
        .any(|d| d.hardware && oxp_core::video_codec_policy::is_hevc(&d.codec_string)));
    hub.remove_output("clink");
    assert!(host
        .caps
        .lock()
        .unwrap()
        .last()
        .unwrap()
        .decoders
        .iter()
        .any(|d| d.hardware && oxp_core::video_codec_policy::is_hevc(&d.codec_string)));
    hub.close();
}
#[test]
fn queue_counts_backpressure_drops_and_applies_a_new_duration() {
    let mut q = FrameQueue::new(100, 1024);
    let f = |pts, key| VideoFrame {
        pts_us: pts,
        key_frame: key,
        data: vec![1; 2].into(),
        ..frame(1)
    };
    // Waiting for the first key frame is not back-pressure.
    assert_eq!(q.offer(f(0, false)), (false, true));
    assert_eq!(q.dropped(), 0);
    q.offer(f(10, true));
    q.offer(f(60, false));
    assert_eq!(q.offer(f(120, false)), (false, true));
    assert_eq!(q.dropped(), 3);
    assert_eq!(q.offer(f(130, false)), (false, false));
    assert_eq!(q.dropped(), 4);
    assert_eq!(q.offer(f(140, true)), (true, false));
    assert_eq!(q.offer(f(150, false)), (true, false));
    assert_eq!(q.dropped(), 4);
    // A new duration applies from the next offer and keeps the decoding history.
    q.set_max_duration_us(300);
    assert_eq!(q.max_duration_us(), 300);
    assert_eq!(q.offer(f(400, false)), (true, false));
    assert_eq!(q.len(), 3);
    q.set_max_duration_us(50);
    assert_eq!(q.offer(f(410, false)), (false, true));
    assert_eq!(q.dropped(), 8);
    q.clear();
    // An oversized frame counts itself and the frames it flushes.
    let mut q = FrameQueue::new(100, 8);
    q.offer(VideoFrame {
        data: vec![1; 4].into(),
        ..f(0, true)
    });
    assert_eq!(
        q.offer(VideoFrame {
            data: vec![1; 9].into(),
            ..f(1, false)
        }),
        (false, true)
    );
    assert_eq!(q.dropped(), 2);
    assert_eq!(q.offer(f(2, false)), (false, false));
    assert_eq!(q.offer(f(3, true)), (true, false));
    assert_eq!(q.offer(f(4, false)), (true, false));
    assert_eq!(q.dropped(), 3);
}

fn await_context(predicate: impl Fn() -> bool) {
    let until = std::time::Instant::now() + Duration::from_secs(3);
    while !predicate() {
        assert!(
            std::time::Instant::now() < until,
            "context delivery timed out"
        );
        std::thread::sleep(Duration::from_millis(2));
    }
}
#[test]
fn context_replay_dedup_clear_and_end() {
    let hub = Hub::new(Arc::new(Host::default()));
    let one = Output::with_context("context-one");
    hub.add_output(one.clone()).unwrap();
    await_context(|| one.contexts.lock().unwrap().len() == ContextUpdate::idle().len());
    let connected = ContextUpdate::Session(Some(SessionContext {
        carplay: Some(CarPlayState::Connected),
    }));
    hub.context(connected.clone());
    hub.context(connected.clone());
    await_context(|| one.contexts.lock().unwrap().len() == ContextUpdate::idle().len() + 1);
    let late = Output::with_context("context-late");
    hub.add_output(late.clone()).unwrap();
    await_context(|| late.contexts.lock().unwrap().len() == ContextUpdate::idle().len());
    assert!(late.contexts.lock().unwrap().contains(&connected));
    hub.context(ContextUpdate::Call(None));
    await_context(|| one.contexts.lock().unwrap().len() == ContextUpdate::idle().len() + 2);
    hub.session(&SessionState {
        id: 1,
        phase: SessionPhase::Ended,
        reason: None,
    });
    await_context(|| one.contexts.lock().unwrap().len() == ContextUpdate::idle().len() + 4);
    let idle = Output::with_context("context-idle");
    hub.add_output(idle.clone()).unwrap();
    await_context(|| idle.contexts.lock().unwrap().len() == ContextUpdate::idle().len());
    for u in ContextUpdate::idle() {
        assert!(idle.contexts.lock().unwrap().contains(&u));
    }
    hub.close();
}

#[test]
fn stalled_context_does_not_block_publisher_or_other_output() {
    struct Slow {
        entered: std::sync::mpsc::Sender<()>,
        release: Mutex<std::sync::mpsc::Receiver<()>>,
    }
    impl ProjectionOutput for Slow {
        fn wants_context(&self) -> bool {
            true
        }
        fn id(&self) -> &str {
            "slow-context"
        }
        fn open(&self, _: Arc<dyn OutputHost>) -> Result<()> {
            Ok(())
        }
        fn on_context(&self, _: ContextUpdate) {
            self.entered.send(()).unwrap();
            let _ = self.release.lock().unwrap().recv();
        }
        fn on_session(&self, _: &SessionState, _: &SessionConfig) {}
        fn on_video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {}
        fn on_video_frame(&self, _: VideoFrame) {}
        fn close(&self) {}
    }
    let hub = Hub::new(Arc::new(Host::default()));
    let (entered_tx, entered_rx) = std::sync::mpsc::channel();
    let (release_tx, release_rx) = std::sync::mpsc::channel();
    hub.add_output(Arc::new(Slow {
        entered: entered_tx,
        release: Mutex::new(release_rx),
    }))
    .unwrap();
    entered_rx.recv_timeout(Duration::from_secs(3)).unwrap();
    let fast = Output::with_context("fast-context");
    hub.add_output(fast.clone()).unwrap();
    await_context(|| fast.contexts.lock().unwrap().len() == ContextUpdate::idle().len());
    let update = ContextUpdate::Call(Some(CallContext {
        state: Some(CallState::Active),
    }));
    hub.context(update.clone());
    await_context(|| fast.contexts.lock().unwrap().contains(&update));
    hub.close();
    drop(release_tx);
}

#[test]
fn main_screen_preference_survives_hub_aggregation_and_updates_negotiation() {
    let host = Arc::new(Host::default());
    let hub = Hub::new(host.clone());
    let output = Output::new("web");
    hub.add_output(output.clone()).unwrap();
    let mut caps = OutputCapabilities {
        decoders: vec![DecoderSupport {
            codec_string: "hvc1.1.6.L120.B0".into(),
            hardware: true,
        }],
        displays: [(0, DisplayInfo::default())].into(),
        ..OutputCapabilities::default()
    };
    let downstream = output.host.lock().unwrap().clone().unwrap();
    downstream.capabilities(caps.clone());
    assert_eq!(hub.session_config().screens[&0].codec, VideoCodec::H265);
    caps.prefers_h264 = true;
    downstream.capabilities(caps.clone());
    assert!(host.caps.lock().unwrap().last().unwrap().prefers_h264);
    assert_eq!(hub.session_config().screens[&0].codec, VideoCodec::H264);
    caps.displays = [(1, DisplayInfo::default())].into();
    downstream.capabilities(caps.clone());
    assert!(!host.caps.lock().unwrap().last().unwrap().prefers_h264);
    caps.displays = [(0, DisplayInfo::default())].into();
    caps.prefers_h264 = false;
    downstream.capabilities(caps);
    assert_eq!(hub.session_config().screens[&0].codec, VideoCodec::H265);
    hub.close();
}

#[test]
fn settings_and_per_screen_areas_fanout_clear_and_replay() {
    let host = Arc::new(Host::default());
    let hub = Hub::new(host.clone());
    let first = Output::new("first");
    hub.add_output(first.clone()).unwrap();
    let settings = DisplaySettings {
        resolution: "native".into(),
        screen_width: 125,
        video_codec: "h265".into(),
    };
    let area = ViewArea {
        x: 10,
        y: 20,
        width: 100,
        height: 200,
        canvas_width: 800,
        canvas_height: 600,
    };
    hub.display_settings(settings.clone());
    hub.view_area(0, Some(area));
    hub.view_area(1, Some(area));
    hub.view_area(0, None);
    let late = Output::new("late");
    hub.add_output(late.clone()).unwrap();
    assert_eq!(*first.settings.lock().unwrap(), vec![settings.clone()]);
    assert_eq!(*late.settings.lock().unwrap(), vec![settings.clone()]);
    assert_eq!(
        *first.areas.lock().unwrap(),
        vec![(0, Some(area)), (1, Some(area)), (0, None)]
    );
    assert_eq!(
        *late.areas.lock().unwrap(),
        vec![(0, None), (1, Some(area))]
    );
    first.control(ControlEvent::DisplaySettings {
        settings: settings.clone(),
        origin: first.origin(),
    });
    assert_eq!(
        *host.controls.lock().unwrap(),
        vec![ControlEvent::DisplaySettings {
            settings,
            origin: first.origin()
        }]
    );
    hub.close();
}
#[test]
fn primary_main_screen_display_wins_as_a_complete_report() {
    let host = Arc::new(Host::default());
    let hub = Hub::new(host.clone());
    let buffered = Output::new("buffered");
    let primary = Output::new("primary");
    let secondary = Output::new("secondary");
    for output in [&buffered, &primary, &secondary] {
        hub.add_output(output.clone()).unwrap();
    }
    for (output, latency, fullscreen) in [
        (&buffered, LatencyClass::Buffered, Some(true)),
        (&primary, LatencyClass::Interactive, Some(false)),
        (&secondary, LatencyClass::Interactive, Some(true)),
    ] {
        let d = DisplayInfo {
            viewport: Some(Viewport {
                width_px: 1533,
                height_px: 907,
            }),
            fullscreen,
            fullscreen_preferred: Some(true),
            ..DisplayInfo::default()
        };
        output
            .host
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .capabilities(OutputCapabilities {
                latency,
                displays: [(0, d)].into_iter().collect(),
                ..OutputCapabilities::default()
            });
    }
    assert_eq!(
        host.caps.lock().unwrap().last().unwrap().displays[&0].fullscreen,
        Some(false)
    );
    hub.remove_output("primary");
    assert_eq!(
        host.caps.lock().unwrap().last().unwrap().displays[&0].fullscreen,
        Some(true)
    );
    hub.close();
}
