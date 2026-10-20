// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use crate::{model::*, InputSink, KeyframeLimiter, ProjectionOutput, Result};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Condvar, Mutex, Weak,
    },
    time::{Duration, Instant},
};

type Call = Box<dyn FnOnce() + Send>;
struct Destination {
    output: Arc<dyn ProjectionOutput>,
    active: AtomicBool,
    context: Option<ContextDelivery>,
}
#[derive(Default)]
struct ContextPending {
    updates: BTreeMap<crate::ContextTopic, crate::ContextUpdate>,
    opened: bool,
    running: bool,
    close: bool,
    stopped: bool,
}
#[derive(Default)]
struct ContextDelivery {
    pending: Mutex<ContextPending>,
    wake: Condvar,
}
struct State {
    input: InputCapabilities,
    display_settings: Option<DisplaySettings>,
    view_areas: BTreeMap<u32, Option<ViewArea>>,
    context: BTreeMap<crate::ContextTopic, crate::ContextUpdate>,
    outputs: Vec<Arc<Destination>>,
    caps: BTreeMap<String, OutputCapabilities>,
    session: Option<SessionState>,
    video: BTreeMap<u32, Arc<VideoFormat>>,
    audio: BTreeMap<u32, Arc<AudioFormat>>,
    microphones: BTreeMap<u32, MicRequest>,
    last_touch: Option<Origin>,
    pressed: BTreeSet<(u32, u8)>,
    mic_owners: BTreeMap<u32, Origin>,
    mic_versions: BTreeMap<u32, u64>,
    limiter: KeyframeLimiter,
    closed: bool,
}
/// Serializes delivery without holding the state lock over arbitrary output or input callbacks.
/// The input is represented by one host callback, so Stream wire and in-process producers share it.
pub struct Hub {
    weak: Weak<Hub>,
    state: Mutex<State>,
    calls: Mutex<VecDeque<Call>>,
    draining: AtomicBool,
    generation: AtomicU64,
    upstream: Arc<dyn OutputHost>,
    clock: Instant,
}
impl Hub {
    pub fn new(upstream: Arc<dyn OutputHost>) -> Arc<Self> {
        let hub = Arc::new_cyclic(|weak| Self {
            weak: weak.clone(),
            state: Mutex::new(State {
                input: InputCapabilities::default(),
                display_settings: None,
                view_areas: BTreeMap::new(),
                context: crate::ContextUpdate::idle()
                    .into_iter()
                    .map(|u| (u.topic(), u))
                    .collect(),
                outputs: Vec::new(),
                caps: BTreeMap::new(),
                session: None,
                video: BTreeMap::new(),
                audio: BTreeMap::new(),
                microphones: BTreeMap::new(),
                last_touch: None,
                pressed: BTreeSet::new(),
                mic_owners: BTreeMap::new(),
                mic_versions: BTreeMap::new(),
                limiter: KeyframeLimiter::default(),
                closed: false,
            }),
            calls: Mutex::new(VecDeque::new()),
            draining: AtomicBool::new(false),
            generation: AtomicU64::new(0),
            upstream,
            clock: Instant::now(),
        });
        let weak = Arc::downgrade(&hub);
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_millis(5));
            let Some(h) = weak.upgrade() else { break };
            {
                let mut s = h.state.lock().unwrap();
                if s.closed {
                    break;
                }
                for e in s.limiter.poll(h.now()) {
                    if s.input.key_frame_requests {
                        h.forward(e);
                    }
                }
            }
            h.drain();
        });
        hub
    }
    fn now(&self) -> i64 {
        self.clock.elapsed().as_nanos().min(i64::MAX as u128) as i64
    }
    fn enqueue(&self, call: impl FnOnce() + Send + 'static) {
        self.calls.lock().unwrap().push_back(Box::new(call));
    }
    fn drain(&self) {
        loop {
            if self.draining.swap(true, Ordering::AcqRel) {
                return;
            }
            loop {
                let call = self.calls.lock().unwrap().pop_front();
                match call {
                    Some(c) => c(),
                    None => break,
                }
            }
            self.draining.store(false, Ordering::Release);
            if self.calls.lock().unwrap().is_empty() {
                return;
            }
        }
    }
    fn deliver(
        &self,
        d: &Arc<Destination>,
        call: impl FnOnce(&dyn ProjectionOutput) + Send + 'static,
    ) {
        let d = d.clone();
        let weak = self.weak.clone();
        self.enqueue(move || {
            if d.active.load(Ordering::Acquire)
                && std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    call(d.output.as_ref())
                }))
                .is_err()
            {
                if let Some(h) = weak.upgrade() {
                    h.remove_output(d.output.id());
                }
            }
        });
    }
    fn forward(&self, e: ControlEvent) {
        let u = self.upstream.clone();
        let weak = self.weak.clone();
        let epoch = self.generation.load(Ordering::Acquire);
        self.enqueue(move || {
            if weak
                .upgrade()
                .is_some_and(|h| h.generation.load(Ordering::Acquire) == epoch)
            {
                u.control(e);
            }
        });
    }
    pub fn add_output(self: &Arc<Self>, output: Arc<dyn ProjectionOutput>) -> Result<()> {
        let d = Arc::new(Destination {
            context: output.wants_context().then(ContextDelivery::default),
            output,
            active: AtomicBool::new(true),
        });
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return Err("Hub closed".into());
            }
            if s.outputs.iter().any(|v| v.output.id() == d.output.id()) {
                return Err("Duplicate output id".into());
            }
            if let Some(context) = &d.context {
                context.pending.lock().unwrap().updates = s.context.clone();
                let dest = d.clone();
                std::thread::Builder::new()
                    .name(format!("projection-context-{}", d.output.id()))
                    .spawn(move || run_context(&dest))?;
            }
            s.outputs.push(d.clone());
            let host = Arc::new(Host {
                hub: Arc::downgrade(self),
                destination: Arc::downgrade(&d),
            });
            let weak = Arc::downgrade(self);
            let dest = d.clone();
            self.enqueue(move || {
                if dest.active.load(Ordering::Acquire)
                    && !matches!(
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| dest
                            .output
                            .open(host))),
                        Ok(Ok(()))
                    )
                {
                    if let Some(h) = weak.upgrade() {
                        h.remove_output(dest.output.id());
                    }
                }
            });
            if d.context.is_some() {
                let dest = d.clone();
                self.enqueue(move || {
                    let context = dest.context.as_ref().unwrap();
                    let mut pending = context.pending.lock().unwrap();
                    pending.opened = true;
                    context.wake.notify_one();
                });
            }
            if let Some(settings) = s.display_settings.clone() {
                self.deliver(&d, move |o| o.on_display_settings(settings));
            }
            for (&screen, &area) in &s.view_areas {
                self.deliver(&d, move |o| o.on_view_area(screen, area));
            }
            if let Some(state) = s.session.clone() {
                let config = derive(&s);
                self.deliver(&d, move |o| o.on_session(&state, &config));
            }
            for (&screen, f) in &s.video {
                let f = f.clone();
                self.deliver(&d, move |o| o.on_video_format(screen, Some(f)));
            }
            for (&stream, f) in &s.audio {
                let f = f.clone();
                self.deliver(&d, move |o| o.on_audio_format(stream, Some(f)));
            }
            for (&stream, r) in &s.microphones {
                let r = r.clone();
                self.deliver(&d, move |o| o.on_microphone(stream, Some(r)));
            }
            for screen in s
                .video
                .keys()
                .copied()
                .filter(|_| s.input.key_frame_requests)
                .collect::<Vec<_>>()
            {
                let e = ControlEvent::KeyFrameRequest {
                    screen,
                    origin: Origin {
                        output_id: d.output.id().into(),
                        client_id: "hub".into(),
                    },
                };
                for e in s.limiter.request(e, self.now()) {
                    self.forward(e);
                }
            }
        }
        self.drain();
        Ok(())
    }
    pub fn remove_output(&self, id: &str) {
        {
            let mut s = self.state.lock().unwrap();
            let Some(i) = s.outputs.iter().position(|d| d.output.id() == id) else {
                return;
            };
            let d = s.outputs.remove(i);
            d.active.store(false, Ordering::Release);
            stop_context(&d);
            s.caps.remove(id);
            if s.last_touch.as_ref().is_some_and(|o| o.output_id == id) {
                s.last_touch = None;
                s.pressed.clear();
            }
            s.mic_owners.retain(|_, o| o.output_id != id);
            self.enqueue(move || {
                close_destination(&d);
            });
            self.report_caps(&s);
        }
        self.drain();
    }
    pub fn session_config(&self) -> SessionConfig {
        derive(&self.state.lock().unwrap())
    }
    pub fn close(&self) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            s.context = crate::ContextUpdate::idle()
                .into_iter()
                .map(|u| (u.topic(), u))
                .collect();
            s.closed = true;
            self.generation.fetch_add(1, Ordering::AcqRel);
            s.limiter.clear();
            for d in s.outputs.drain(..) {
                d.active.store(false, Ordering::Release);
                stop_context(&d);
                self.enqueue(move || close_destination(&d));
            }
        }
        self.drain();
    }
    fn set_context(s: &mut State, update: crate::ContextUpdate) {
        let topic = update.topic();
        if s.context.get(&topic) == Some(&update) {
            return;
        }
        s.context.insert(topic, update.clone());
        for d in &s.outputs {
            if let Some(context) = &d.context {
                let mut pending = context.pending.lock().unwrap();
                if !pending.stopped {
                    pending.updates.insert(topic, update.clone());
                    context.wake.notify_one();
                }
            }
        }
    }
    fn report_caps(&self, s: &State) {
        let caps: Vec<_> = s
            .outputs
            .iter()
            .filter_map(|d| s.caps.get(d.output.id()))
            .collect();
        let mut c = OutputCapabilities {
            prefers_h264: false,
            latency: LatencyClass::Interactive,
            decoders: Vec::new(),
            displays: BTreeMap::new(),
            touch: 0,
            keys: BTreeSet::new(),
            audio: BTreeSet::new(),
            microphone: false,
        };
        for v in &caps {
            for d in &v.decoders {
                if !c.decoders.iter().any(|x| x.codec_string == d.codec_string) {
                    c.decoders.push(DecoderSupport {
                        codec_string: d.codec_string.clone(),
                        hardware: caps
                            .iter()
                            .filter(|x| x.displays.contains_key(&0))
                            .all(|x| {
                                x.decoders.iter().any(|x| {
                                    (x.codec_string == d.codec_string
                                        || crate::video_codec_policy::is_hevc(&d.codec_string)
                                            && crate::video_codec_policy::is_hevc(&x.codec_string))
                                        && x.hardware
                                })
                            }),
                    });
                }
            }
            for (&screen, d) in &v.displays {
                c.displays.entry(screen).or_insert_with(|| d.clone());
            }
            c.touch = c.touch.max(v.touch);
            c.keys.extend(&v.keys);
            c.audio.extend(&v.audio);
            c.microphone |= v.microphone;
            c.prefers_h264 |= v.displays.contains_key(&0) && v.prefers_h264;
        }
        // Attachment order deterministically selects the first interactive main-screen report.
        // Its complete display (including unknown viewport/fullscreen values) wins.
        if let Some(primary) = caps
            .iter()
            .find(|c| c.latency == LatencyClass::Interactive && c.displays.contains_key(&0))
        {
            for (&screen, d) in &primary.displays {
                c.displays.insert(screen, d.clone());
            }
        }
        let u = self.upstream.clone();
        self.enqueue(move || u.capabilities(c));
    }
    fn control(&self, d: &Arc<Destination>, e: ControlEvent) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed
                || !d.active.load(Ordering::Acquire)
                || origin(&e).output_id != d.output.id()
            {
                return;
            }
            match &e {
                ControlEvent::DisplaySettings { settings, .. } => {
                    if settings.validate().is_ok() {
                        self.forward(e);
                    }
                }
                ControlEvent::KeyFrameRequest { .. } => {
                    if !s.input.key_frame_requests {
                        return;
                    }
                    for e in s.limiter.request(e, self.now()) {
                        self.forward(e);
                    }
                }
                ControlEvent::Touch {
                    screen,
                    contacts,
                    origin,
                } => {
                    if !s.input.touch
                        || (!s.pressed.is_empty() && s.last_touch.as_ref() != Some(origin))
                    {
                        return;
                    }
                    s.last_touch = Some(origin.clone());
                    s.pressed.retain(|(sc, _)| sc != screen);
                    s.pressed.extend(
                        contacts
                            .iter()
                            .filter(|c| c.pressed)
                            .map(|c| (*screen, c.id)),
                    );
                    self.forward(e);
                }
                ControlEvent::Key { origin, code, .. } => {
                    if s.input.keys.contains(code)
                        && s.last_touch.as_ref().is_none_or(|o| o == origin)
                    {
                        self.forward(e);
                    }
                }
                ControlEvent::MicPacket { stream, origin, .. } => {
                    if !s.input.microphone || !s.microphones.contains_key(stream) {
                        return;
                    }
                    if !s.mic_owners.contains_key(stream) || s.last_touch.as_ref() == Some(origin) {
                        s.mic_owners.insert(*stream, origin.clone());
                    }
                    if s.mic_owners.get(stream) == Some(origin) {
                        let stream = *stream;
                        let owner = origin.clone();
                        let version = s.mic_versions.get(&stream).copied().unwrap_or(0);
                        let epoch = self.generation.load(Ordering::Acquire);
                        let weak = self.weak.clone();
                        let upstream = self.upstream.clone();
                        let d = d.clone();
                        self.enqueue(move || {
                            let accepted = weak.upgrade().is_some_and(|h| {
                                let s = h.state.lock().unwrap();
                                !s.closed
                                    && h.generation.load(Ordering::Acquire) == epoch
                                    && d.active.load(Ordering::Acquire)
                                    && s.input.microphone
                                    && s.microphones.contains_key(&stream)
                                    && s.mic_versions.get(&stream).copied().unwrap_or(0) == version
                                    && s.mic_owners.get(&stream) == Some(&owner)
                            });
                            if accepted {
                                upstream.control(e);
                            }
                        });
                    }
                }
            }
        }
        self.drain();
    }
}
// Dropping the hub must also wake workers blocked on their condition variables.
impl Drop for Hub {
    fn drop(&mut self) {
        for d in &self.state.get_mut().unwrap().outputs {
            stop_context(d);
        }
    }
}

fn run_context(d: &Destination) {
    let context = d.context.as_ref().unwrap();
    loop {
        let update = {
            let mut pending = context.pending.lock().unwrap();
            loop {
                if !d.active.load(Ordering::Acquire) || pending.stopped {
                    return;
                }
                if pending.opened {
                    if let Some((_, update)) = pending.updates.pop_first() {
                        pending.running = true;
                        break update;
                    }
                }
                pending = context.wake.wait(pending).unwrap();
            }
        };
        let failed =
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| d.output.on_context(update)))
                .is_err();
        let close = {
            let mut pending = context.pending.lock().unwrap();
            pending.running = false;
            if failed {
                pending.stopped = true;
                pending.updates.clear();
            }
            std::mem::take(&mut pending.close)
        };
        if close {
            close_destination(d);
        }
        if failed {
            eprintln!(
                "Context delivery disabled for output {}: callback panicked",
                d.output.id()
            );
            return;
        }
    }
}

fn stop_context(d: &Destination) {
    if let Some(context) = &d.context {
        let mut pending = context.pending.lock().unwrap();
        pending.stopped = true;
        pending.updates.clear();
        context.wake.notify_one();
    }
}

// Closing never waits for a stalled context callback; that worker closes after its callback returns.
fn close_destination(d: &Destination) {
    if let Some(context) = &d.context {
        let mut pending = context.pending.lock().unwrap();
        pending.stopped = true;
        pending.updates.clear();
        context.wake.notify_one();
        if pending.running {
            pending.close = true;
            return;
        }
    }
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| d.output.close()));
}
fn origin(e: &ControlEvent) -> &Origin {
    match e {
        ControlEvent::DisplaySettings { origin, .. }
        | ControlEvent::Touch { origin, .. }
        | ControlEvent::Key { origin, .. }
        | ControlEvent::KeyFrameRequest { origin, .. }
        | ControlEvent::MicPacket { origin, .. } => origin,
    }
}
fn derive(s: &State) -> SessionConfig {
    let caps: Vec<_> = s
        .outputs
        .iter()
        .filter_map(|d| s.caps.get(d.output.id()))
        .collect();
    let primary = caps
        .iter()
        .find(|c| c.latency == LatencyClass::Interactive && !c.displays.is_empty());
    let screens = s.input.screens.clone();
    SessionConfig {
        screens: screens
            .into_iter()
            .map(|screen| {
                let viewers: Vec<_> = caps
                    .iter()
                    .filter(|c| c.displays.contains_key(&screen))
                    .collect();
                let display = primary
                    .and_then(|c| c.displays.get(&screen))
                    .or_else(|| viewers.first().and_then(|c| c.displays.get(&screen)));
                let hardware: Vec<_> = viewers
                    .iter()
                    .map(|c| {
                        c.decoders.iter().any(|d| {
                            d.hardware && crate::video_codec_policy::is_hevc(&d.codec_string)
                        })
                    })
                    .collect();
                let hevc = s.input.codecs.contains(&VideoCodec::H265)
                    && crate::video_codec_policy::resolve_with_preferences(
                        &hardware,
                        false,
                        screen == 0 && viewers.iter().any(|c| c.prefers_h264),
                    )
                    .codec
                        == VideoCodec::H265;
                (
                    screen,
                    ScreenConfig {
                        codec: if hevc {
                            VideoCodec::H265
                        } else {
                            VideoCodec::H264
                        },
                        width: display.map_or(1280, |d| d.width_px),
                        height: display.map_or(720, |d| d.height_px),
                        fps: display.map_or(60.0, |d| {
                            FrameRatePolicy::resolve(
                                d.frame_rate,
                                Some(d.refresh_hz),
                                d.sustained_fps,
                            )
                        }),
                    },
                )
            })
            .collect(),
    }
}
struct Host {
    hub: Weak<Hub>,
    destination: Weak<Destination>,
}
impl OutputHost for Host {
    fn capabilities(&self, c: OutputCapabilities) {
        let (Some(h), Some(d)) = (self.hub.upgrade(), self.destination.upgrade()) else {
            return;
        };
        {
            let mut s = h.state.lock().unwrap();
            if !d.active.load(Ordering::Acquire) {
                return;
            }
            s.caps.insert(d.output.id().into(), c);
            h.report_caps(&s);
        }
        h.drain();
    }
    fn control(&self, e: ControlEvent) {
        if let (Some(h), Some(d)) = (self.hub.upgrade(), self.destination.upgrade()) {
            h.control(&d, e);
        }
    }
    fn request_restart(&self, origin: Origin) {
        if let (Some(h), Some(d)) = (self.hub.upgrade(), self.destination.upgrade()) {
            {
                let s = h.state.lock().unwrap();
                let primary = s.outputs.iter().find(|o| {
                    s.caps.get(o.output.id()).is_some_and(|c| {
                        c.latency == LatencyClass::Interactive && !c.displays.is_empty()
                    })
                });
                if s.closed
                    || !d.active.load(Ordering::Acquire)
                    || origin.output_id != d.output.id()
                    || !primary.is_some_and(|p| Arc::ptr_eq(p, &d))
                    || s.input.reconfigure == ReconfigureCost::Unsupported
                {
                    return;
                }
                let u = h.upstream.clone();
                h.enqueue(move || u.request_restart(origin));
            }
            h.drain();
        }
    }
    fn stats(&self, stats: OutputStats) {
        if let (Some(h), Some(d)) = (self.hub.upgrade(), self.destination.upgrade()) {
            if d.active.load(Ordering::Acquire) && stats.origin.output_id == d.output.id() {
                let u = h.upstream.clone();
                h.enqueue(move || u.stats(stats));
                h.drain();
            }
        }
    }
}
impl InputSink for Hub {
    fn display_settings(&self, settings: DisplaySettings) {
        if settings.validate().is_err() {
            return;
        }
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            s.display_settings = Some(settings.clone());
            for d in &s.outputs {
                let settings = settings.clone();
                self.deliver(d, move |o| o.on_display_settings(settings));
            }
        }
        self.drain();
    }
    fn view_area(&self, screen: u32, area: Option<ViewArea>) {
        if screen > 255 || area.is_some_and(|a| a.validate().is_err()) {
            return;
        }
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            s.view_areas.insert(screen, area);
            for d in &s.outputs {
                self.deliver(d, move |o| o.on_view_area(screen, area));
            }
        }
        self.drain();
    }
    fn context(&self, update: crate::ContextUpdate) {
        let mut s = self.state.lock().unwrap();
        if s.closed {
            return;
        }
        Self::set_context(&mut s, update);
    }
    fn input_capabilities(&self, capabilities: InputCapabilities) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            if !capabilities.key_frame_requests {
                s.limiter.clear();
            }
            s.input = capabilities;
        }
        self.drain();
    }
    fn session(&self, state: &SessionState) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed || s.session.as_ref().is_some_and(|v| state.id < v.id) {
                return;
            }
            if s.session.as_ref().is_none_or(|v| state.id != v.id) {
                self.generation.fetch_add(1, Ordering::AcqRel);
                s.video.clear();
                s.audio.clear();
                s.microphones.clear();
                s.mic_owners.clear();
                s.last_touch = None;
                s.pressed.clear();
                s.limiter.clear();
            }
            if state.phase == SessionPhase::Ended {
                for update in crate::ContextUpdate::idle() {
                    Self::set_context(&mut s, update);
                }
            }
            s.session = Some(state.clone());
            let c = derive(&s);
            for d in &s.outputs {
                let state = state.clone();
                let c = c.clone();
                self.deliver(d, move |o| o.on_session(&state, &c));
            }
        }
        self.drain();
    }
    fn video_format(&self, screen: u32, format: Option<Arc<VideoFormat>>) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            assert!(format.as_ref().is_none_or(|f| f.screen == screen));
            if let Some(f) = &format {
                s.video.insert(screen, f.clone());
            } else {
                s.video.remove(&screen);
            }
            for d in &s.outputs {
                let f = format.clone();
                self.deliver(d, move |o| o.on_video_format(screen, f));
            }
        }
        self.drain();
    }
    fn video_frame(&self, frame: VideoFrame) {
        {
            let s = self.state.lock().unwrap();
            if s.closed || s.session.as_ref().is_some_and(|v| frame.session < v.id) {
                return;
            }
            for d in &s.outputs {
                let f = frame.clone();
                self.deliver(d, move |o| o.on_video_frame(f));
            }
        }
        self.drain();
    }
    fn audio_format(&self, stream: u32, format: Option<Arc<AudioFormat>>) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            if let Some(f) = &format {
                assert_eq!(f.stream, stream);
                s.audio.insert(stream, f.clone());
            } else {
                s.audio.remove(&stream);
            }
            for d in &s.outputs {
                let f = format.clone();
                self.deliver(d, move |o| o.on_audio_format(stream, f));
            }
        }
        self.drain();
    }
    fn audio_packet(&self, packet: AudioPacket) {
        {
            let s = self.state.lock().unwrap();
            if s.closed || s.session.as_ref().is_some_and(|v| packet.session < v.id) {
                return;
            }
            for d in &s.outputs {
                let p = packet.clone();
                self.deliver(d, move |o| o.on_audio_packet(p));
            }
        }
        self.drain();
    }
    fn microphone(&self, stream: u32, request: Option<MicRequest>) {
        {
            let mut s = self.state.lock().unwrap();
            if s.closed {
                return;
            }
            s.mic_owners.remove(&stream);
            let version = s.mic_versions.entry(stream).or_default();
            *version = version.wrapping_add(1);
            if let Some(r) = &request {
                assert_eq!(r.stream, stream);
                s.microphones.insert(stream, r.clone());
            } else {
                s.microphones.remove(&stream);
            }
            for d in &s.outputs {
                let r = request.clone();
                self.deliver(d, move |o| o.on_microphone(stream, r));
            }
        }
        self.drain();
    }
}

#[cfg(test)]
mod context_tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    struct Host;
    impl OutputHost for Host {
        fn capabilities(&self, _: OutputCapabilities) {}
        fn control(&self, _: ControlEvent) {}
        fn stats(&self, _: OutputStats) {}
    }
    #[derive(Default)]
    struct Output {
        opted_in: bool,
        fail: bool,
        contexts: AtomicUsize,
        frames: AtomicUsize,
        closed: AtomicBool,
    }
    impl ProjectionOutput for Output {
        fn id(&self) -> &str {
            "context-test"
        }
        fn open(&self, _: Arc<dyn OutputHost>) -> Result<()> {
            Ok(())
        }
        fn wants_context(&self) -> bool {
            self.opted_in
        }
        fn on_context(&self, _: crate::ContextUpdate) {
            self.contexts.fetch_add(1, Ordering::SeqCst);
            assert!(!self.fail, "broken context callback");
        }
        fn on_session(&self, _: &SessionState, _: &SessionConfig) {}
        fn on_video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {}
        fn on_video_frame(&self, _: VideoFrame) {
            self.frames.fetch_add(1, Ordering::SeqCst);
        }
        fn close(&self) {
            self.closed.store(true, Ordering::SeqCst);
        }
    }
    fn wait_until(predicate: impl Fn() -> bool) {
        let deadline = Instant::now() + Duration::from_secs(3);
        while !predicate() {
            assert!(Instant::now() < deadline, "context worker timed out");
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    #[test]
    fn default_output_has_no_context_state_or_worker() {
        let hub = Hub::new(Arc::new(Host));
        let output = Arc::new(Output::default());
        assert!(!output.wants_context());
        hub.add_output(output.clone()).unwrap();
        {
            let state = hub.state.lock().unwrap();
            let destination = &state.outputs[0];
            assert!(destination.context.is_none());
            // A spawned worker would retain a second strong destination reference.
            assert_eq!(Arc::strong_count(destination), 1);
        }
        hub.context(crate::ContextUpdate::Call(None));
        hub.session(&SessionState {
            id: 1,
            phase: SessionPhase::Ended,
            reason: None,
        });
        hub.close();
        assert_eq!(output.contexts.load(Ordering::SeqCst), 0);
        assert!(output.closed.load(Ordering::SeqCst));
    }
    #[test]
    fn context_panic_disables_only_context_and_keeps_video() {
        let hub = Hub::new(Arc::new(Host));
        let output = Arc::new(Output {
            opted_in: true,
            fail: true,
            ..Output::default()
        });
        hub.add_output(output.clone()).unwrap();
        let destination = hub.state.lock().unwrap().outputs[0].clone();
        wait_until(|| {
            destination
                .context
                .as_ref()
                .unwrap()
                .pending
                .lock()
                .unwrap()
                .stopped
        });
        for _ in 0..3 {
            hub.context(crate::ContextUpdate::Call(None));
            hub.video_frame(VideoFrame {
                screen: 0,
                session: 1,
                data: Arc::from([1]),
                pts_us: 0,
                key_frame: true,
                received_at_nanos: 0,
            });
        }
        assert_eq!(output.contexts.load(Ordering::SeqCst), 1);
        assert_eq!(output.frames.load(Ordering::SeqCst), 3);
        assert!(!output.closed.load(Ordering::SeqCst));
        assert!(destination
            .context
            .as_ref()
            .unwrap()
            .pending
            .lock()
            .unwrap()
            .updates
            .is_empty());
        hub.close();
        assert!(output.closed.load(Ordering::SeqCst));
    }
    #[test]
    fn sleeping_context_workers_exit_on_remove_close_and_drop() {
        for action in ["remove", "close", "drop"] {
            let hub = Hub::new(Arc::new(Host));
            let output = Arc::new(Output {
                opted_in: true,
                ..Output::default()
            });
            hub.add_output(output.clone()).unwrap();
            wait_until(|| output.contexts.load(Ordering::SeqCst) == ContextUpdate::idle().len());
            let destination = Arc::downgrade(&hub.state.lock().unwrap().outputs[0]);
            match action {
                "remove" => hub.remove_output(output.id()),
                "close" => hub.close(),
                _ => drop(hub),
            }
            // The worker's destination reference disappears even when no more events arrive.
            wait_until(|| destination.strong_count() == 0);
        }
    }
}
