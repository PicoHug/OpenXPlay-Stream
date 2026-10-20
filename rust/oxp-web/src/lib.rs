// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

// Only send_buffer may use unsafe code, for socket options that std does not expose.
#![deny(unsafe_code)]
mod audio_queue;
mod pairing;
mod plugins;
pub use plugins::PluginsConfig;
mod send_buffer;
use audio_queue::{valid_mic_packet, AudioQueue};
use pairing::{constant_time_token, HelloRateLimit};
pub mod http;
pub mod json;
pub mod tls;
use oxp_core::{model::*, FrameQueue, ProjectionOutput};
use oxp_wire::{Header, Kind, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    io::{self, Read, Write},
    net::{Shutdown, SocketAddr, TcpListener, TcpStream},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        Arc, Condvar, Mutex,
    },
    time::{Duration, Instant},
};
pub use tls::Tls;
use tungstenite::{
    protocol::{frame::coding::CloseCode, CloseFrame, Role, WebSocketConfig},
    Error, Message, WebSocket,
};
/// Whether browser clients must supply a pairing code in their hello.
#[derive(Clone)]
pub enum Pairing {
    /// Accept any syntactically valid hello without checking its token or rate limiting failures.
    Disabled,
    /// Require this nonempty code, with constant-time comparison and failed-hello rate limiting.
    Required(String),
}
#[derive(Clone)]
pub struct RelayInfo {
    pub name: String,
    pub version: String,
}
pub type Diagnostics = Arc<dyn Fn(&str, &Value) + Send + Sync>;
#[derive(Clone)]
pub struct WebOutputConfig {
    pub relay: Option<RelayInfo>,
    pub diagnostics: Option<Diagnostics>,
    pub plugins: Option<PluginsConfig>,
    pub bind: SocketAddr,
    pub static_root: PathBuf,
    pub pairing: Pairing,
    pub source_url: String,
    pub tls: Tls,
    pub max_clients: usize,
    /// Per-client video queue for Smart playback; Speed halves it and Quality triples it.
    pub queue_duration_us: i64,
    pub queue_bytes: usize,
    /// Encrypted bytes the kernel may hold unsent per client socket (TCP_NOTSENT_LOWAT) before no
    /// new frame starts, so a slow browser's backlog stays in the video queue above, where it is
    /// dropped to the next key frame, instead of in the kernel. A frame already started is always
    /// written completely. Linux and Android enforce it in write(); on Darwin the server checks
    /// writability first. Kernels without the option get SO_SNDBUF at twice this value. Zero keeps
    /// the operating system's default.
    pub unsent_limit_bytes: usize,
}
impl WebOutputConfig {
    pub fn new(bind: SocketAddr, static_root: PathBuf, pairing: Pairing, tls: Tls) -> Self {
        Self {
            relay: None,
            diagnostics: None,
            plugins: None,
            bind,
            static_root,
            pairing,
            source_url: json::PROJECT_URL.into(),
            tls,
            max_clients: 8,
            queue_duration_us: 100_000,
            queue_bytes: 8 * 1024 * 1024,
            // About 33 ms at 4 Mbit/s and one TLS record. Polling every 2 ms still refills the
            // socket at up to 8 MB/s, far beyond a 2.4 GHz link.
            unsent_limit_bytes: 16 * 1024,
        }
    }
}
/// Server queue span for a client's `hello.playback`: Speed 50 ms, Smart 100 ms and Quality 300 ms at
/// the default configuration. Frames beyond it are dropped to the next key frame for that client only.
fn playback_queue_duration_us(playback: Option<&str>, smart_us: i64) -> i64 {
    match playback {
        Some("SPEED") => smart_us / 2,
        Some("QUALITY") => smart_us.saturating_mul(3),
        _ => smart_us,
    }
}
/// One poll result: a control message, a video frame and an audio packet, each with its send time.
type Polled = (
    Option<Value>,
    Option<(oxp_core::QueuedFrame, u64)>,
    Option<(AudioPacket, u64)>,
);
struct Client {
    hello: Option<Value>,
    receiver_info: Option<Value>,
    audio: AudioQueue,
    messages: VecDeque<Value>,
    queues: BTreeMap<u32, FrameQueue>,
    queue_duration_us: i64,
    /// Video frames dropped for back-pressure since the client connected, across queue resets.
    dropped_frames: u64,
    /// Recovery requests, dispatched by the connection worker even while its socket is congested.
    keyframe_requests: BTreeSet<u32>,
    revisions: BTreeMap<u32, u64>,
    paused: BTreeSet<u32>,
    touched: BTreeSet<u32>,
    closing: bool,
    /// A handle on the client's TCP socket for kernel queue diagnostics.
    socket: Option<TcpStream>,
    /// Worst time from a video frame's receipt until its last byte was written to the socket, since
    /// the previous stats report.
    send_latency_us: Option<i64>,
    /// (screen, pts, receipt, last byte written) in monotonic nanoseconds for every frame.
    #[cfg(test)]
    written: Vec<(u32, i64, i64, i64)>,
}
impl Client {
    fn new(queue_duration_us: i64) -> Self {
        Self {
            hello: None,
            receiver_info: None,
            audio: AudioQueue::default(),
            messages: VecDeque::new(),
            queues: BTreeMap::new(),
            queue_duration_us,
            dropped_frames: 0,
            keyframe_requests: BTreeSet::new(),
            revisions: BTreeMap::new(),
            paused: BTreeSet::new(),
            touched: BTreeSet::new(),
            closing: false,
            socket: None,
            send_latency_us: None,
            #[cfg(test)]
            written: Vec::new(),
        }
    }
    fn set_playback(&mut self, playback: Option<&str>, smart_us: i64) {
        self.queue_duration_us = playback_queue_duration_us(playback, smart_us);
        for q in self.queues.values_mut() {
            q.set_max_duration_us(self.queue_duration_us);
        }
    }
    /// Takes the next item to send; video only while the socket may take another frame.
    fn poll(&mut self) -> Polled {
        let message = self
            .messages
            .pop_front()
            .or_else(|| self.audio.poll_format());
        let audio = if message.is_none() {
            self.audio.poll()
        } else {
            None
        };
        let frame = if message.is_none()
            && audio.is_none()
            && self.socket.as_ref().is_none_or(send_buffer::writable)
        {
            self.queues.iter_mut().find_map(|(screen, q)| {
                q.poll()
                    .map(|f| (f, self.revisions.get(screen).copied().unwrap_or(0)))
            })
        } else {
            None
        };
        (message, frame, audio)
    }
    fn supports_video(&self, format: &VideoFormat) -> bool {
        format.codec != VideoCodec::H265
            || self.hello.as_ref().is_some_and(|h| {
                h["decoders"].as_array().is_some_and(|ds| {
                    ds.iter().any(|d| {
                        d["codecString"]
                            .as_str()
                            .is_some_and(oxp_core::video_codec_policy::is_hevc)
                    })
                })
            })
    }
    fn video_format(&mut self, screen: u32, format: Option<&VideoFormat>) {
        self.invalidate(screen);
        let accepted = format.filter(|f| self.supports_video(f));
        self.json(json::video(screen, accepted));
    }
    fn supports(&self, codec: AudioCodec) -> bool {
        self.hello.as_ref().is_some_and(|h| {
            h["audio"]
                .as_array()
                .is_some_and(|a| a.iter().any(|v| v == json::audio_codec(codec)))
        })
    }
    fn audio_format(&mut self, v: Value) {
        if !self.audio.format(v) {
            self.closing = true;
        }
    }
    fn microphone(&self) -> bool {
        self.hello.as_ref().is_some_and(|h| h["microphone"] == true)
    }
    fn invalidate(&mut self, screen: u32) {
        self.queues.remove(&screen);
        let revision = self.revisions.entry(screen).or_default();
        *revision = revision.wrapping_add(1);
    }

    fn json(&mut self, v: Value) {
        if self.messages.len() >= 256 {
            self.closing = true;
            self.queues.clear();
            self.audio.clear();
        } else {
            self.messages.push_back(v);
        }
    }
}
struct State {
    host: Option<Arc<dyn OutputHost>>,
    session: SessionState,
    formats: BTreeMap<u32, Arc<VideoFormat>>,
    clients: BTreeMap<String, Client>,
    audio_formats: BTreeMap<u32, Arc<AudioFormat>>,
    microphones: BTreeMap<u32, MicRequest>,
    audio_received: u64,
    audio_reported_at: Instant,
    last_restart: Option<Instant>,
    receiver_build: Option<String>,
    display_settings: Option<DisplaySettings>,
    view_areas: BTreeMap<u32, Option<ViewArea>>,
    area_canvases: BTreeMap<u32, (u32, u32)>,
}
struct Runtime {
    active: AtomicBool,
    bound: Mutex<Option<std::result::Result<SocketAddr, String>>>,
    listening: Condvar,
    connections: AtomicUsize,
}
/// Browser transport. All network work belongs to the accept and connection threads.
pub struct WebOutput {
    plugins: Option<Arc<plugins::Plugins>>,
    config: WebOutputConfig,
    state: Arc<Mutex<State>>,
    runtime: Mutex<Option<Arc<Runtime>>>,
    hello_rate_limit: Arc<Mutex<HelloRateLimit>>,
}
impl WebOutput {
    pub fn new(config: WebOutputConfig) -> Result<Self> {
        if matches!(&config.pairing, Pairing::Required(token) if token.is_empty())
            || config.max_clients == 0
            || config.queue_duration_us < 0
            || config.queue_bytes == 0
            || !json::valid_url(&config.source_url)
        {
            return Err("Invalid web output configuration".into());
        }
        let plugins = config
            .plugins
            .clone()
            .map(plugins::Plugins::new)
            .transpose()?
            .map(Arc::new);
        let receiver_build = receiver_build(&config.static_root);
        Ok(Self {
            plugins,
            config,
            state: Arc::new(Mutex::new(State {
                host: None,
                session: SessionState {
                    id: 0,
                    phase: SessionPhase::Connecting,
                    reason: None,
                },
                formats: BTreeMap::new(),
                clients: BTreeMap::new(),
                audio_formats: BTreeMap::new(),
                microphones: BTreeMap::new(),
                audio_received: 0,
                audio_reported_at: Instant::now(),
                last_restart: None,
                receiver_build,
                display_settings: None,
                view_areas: BTreeMap::new(),
                area_canvases: BTreeMap::new(),
            })),
            runtime: Mutex::new(None),
            hello_rate_limit: Arc::new(Mutex::new(HelloRateLimit::default())),
        })
    }
    pub fn await_listening(&self, timeout: Duration) -> Result<SocketAddr> {
        let run = self
            .runtime
            .lock()
            .unwrap()
            .clone()
            .ok_or("Output not open")?;
        let b = run.bound.lock().unwrap();
        let (b, _) = run
            .listening
            .wait_timeout_while(b, timeout, |b| b.is_none())
            .unwrap();
        match b.as_ref() {
            Some(Ok(a)) => Ok(*a),
            Some(Err(e)) => Err(e.clone().into()),
            None => Err("Web bind timed out".into()),
        }
    }
}
impl ProjectionOutput for WebOutput {
    fn on_display_settings(&self, settings: DisplaySettings) {
        if settings.validate().is_err() {
            return;
        }
        let mut s = self.state.lock().unwrap();
        let first = s.display_settings.is_none();
        s.display_settings = Some(settings.clone());
        if first {
            announce_features(&self.config, &mut s);
            return;
        }
        for c in s.clients.values_mut().filter(|c| c.hello.is_some()) {
            c.json(oxp_wire::display_settings(&settings));
        }
    }
    fn on_view_area(&self, screen: u32, area: Option<ViewArea>) {
        if area.is_some_and(|a| a.validate().is_err()) {
            return;
        }
        let mut s = self.state.lock().unwrap();
        let first = !s.view_areas.contains_key(&screen);
        s.view_areas.insert(screen, area);
        if let Some(a) = area {
            s.area_canvases
                .insert(screen, (a.canvas_width, a.canvas_height));
        }
        if screen != 0 {
            return;
        }
        if first {
            announce_features(&self.config, &mut s);
            return;
        }
        if let Some(message) = page_area(&s) {
            for c in s.clients.values_mut().filter(|c| c.hello.is_some()) {
                c.json(message.clone());
            }
        }
    }
    fn wants_context(&self) -> bool {
        self.plugins.is_some()
    }
    fn on_context(&self, update: oxp_core::ContextUpdate) {
        if let Some(plugins) = &self.plugins {
            plugins.update(update);
        }
    }
    fn id(&self) -> &str {
        "web"
    }
    fn open(&self, host: Arc<dyn OutputHost>) -> Result<()> {
        let mut current = self.runtime.lock().unwrap();
        if current.is_some() {
            return Ok(());
        }
        let run = Arc::new(Runtime {
            active: AtomicBool::new(true),
            bound: Mutex::new(None),
            listening: Condvar::new(),
            connections: AtomicUsize::new(0),
        });
        self.state.lock().unwrap().host = Some(host);
        *current = Some(run.clone());
        let config = self.config.clone();
        let plugins = self.plugins.clone();
        let state = self.state.clone();
        let hello_rate_limit = self.hello_rate_limit.clone();
        std::thread::spawn(move || {
            report_caps(&state);
            let result = (|| -> Result<()> {
                let identity = tls::load(&config.tls)?;
                if let Some(i) = &identity {
                    eprintln!(
                        "{}",
                        json!({"type":"tls-fingerprint","algorithm":"SHA-256","fingerprint":i.fingerprint})
                    );
                }
                let listener = TcpListener::bind(config.bind)?;
                listener.set_nonblocking(true)?;
                let addr = listener.local_addr()?;
                *run.bound.lock().unwrap() = Some(Ok(addr));
                run.listening.notify_all();
                eprintln!(
                    "{}",
                    json!({"type":"web-listening","address":addr.to_string(),"tls":identity.is_some()})
                );
                while run.active.load(Ordering::Acquire) {
                    match listener.accept() {
                        Ok((socket, _)) => {
                            // BSD and macOS hand out accepted sockets that inherit the listener's
                            // non-blocking mode; connection handling uses blocking reads.
                            if socket.set_nonblocking(false).is_err() {
                                continue;
                            }
                            if run.connections.fetch_add(1, Ordering::AcqRel)
                                >= config.max_clients + if plugins.is_some() { 4 } else { 0 }
                            {
                                run.connections.fetch_sub(1, Ordering::AcqRel);
                                continue;
                            }
                            let run = run.clone();
                            let state = state.clone();
                            let config = config.clone();
                            let plugins = plugins.clone();
                            let hello_rate_limit = hello_rate_limit.clone();
                            let tls = identity.as_ref().map(|i| i.config.clone());
                            std::thread::spawn(move || {
                                let _ = connection(
                                    socket,
                                    &config,
                                    tls,
                                    &state,
                                    &run,
                                    &hello_rate_limit,
                                    plugins.as_deref(),
                                );
                                run.connections.fetch_sub(1, Ordering::AcqRel);
                            });
                        }
                        Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(5))
                        }
                        Err(e) => return Err(e.into()),
                    }
                }
                Ok(())
            })();
            if let Err(e) = result {
                *run.bound.lock().unwrap() = Some(Err(e.to_string()));
                run.listening.notify_all();
            }
        });
        Ok(())
    }
    fn on_session(&self, s: &SessionState, _: &SessionConfig) {
        let mut state = self.state.lock().unwrap();
        if state.host.is_none() || s.id < state.session.id {
            return;
        }
        if s.id != state.session.id || s.phase == SessionPhase::Ended {
            let audio = std::mem::take(&mut state.audio_formats);
            let microphones = std::mem::take(&mut state.microphones);
            for c in state.clients.values_mut() {
                for (&stream, f) in &audio {
                    if c.supports(f.codec) {
                        c.json(json::audio(stream, None));
                    }
                }
                for &stream in microphones.keys() {
                    if c.microphone() {
                        c.json(json::microphone(stream, None));
                    }
                }
                c.audio.clear();
            }
            state.formats.clear();
            for c in state.clients.values_mut() {
                c.queues.clear();
                for revision in c.revisions.values_mut() {
                    *revision = revision.wrapping_add(1);
                }
            }
        }
        state.session = s.clone();
        for c in state.clients.values_mut().filter(|c| c.hello.is_some()) {
            c.json(json!({"type":"session","session":json::session(s)}));
        }
    }
    fn on_video_format(&self, screen: u32, format: Option<Arc<VideoFormat>>) {
        let mut s = self.state.lock().unwrap();
        if s.host.is_none() {
            return;
        }
        if let Some(f) = &format {
            s.formats.insert(screen, f.clone());
        } else {
            s.formats.remove(&screen);
        }
        for c in s.clients.values_mut().filter(|c| c.hello.is_some()) {
            c.video_format(screen, format.as_deref());
        }
        if screen == 0 && s.view_areas.get(&0) == Some(&None) {
            if let Some(area) = page_area(&s) {
                for c in s.clients.values_mut().filter(|c| c.hello.is_some()) {
                    c.json(area.clone());
                }
            }
        }
    }
    fn on_video_frame(&self, frame: VideoFrame) {
        if frame.data.is_empty() {
            return;
        }
        let mut s = self.state.lock().unwrap();
        if frame.session != s.session.id
            || s.session.phase == SessionPhase::Ended
            || !s.formats.contains_key(&frame.screen)
        {
            return;
        }
        let format = s.formats[&frame.screen].clone();
        // Each client has its own bounded queue, so a slow socket drops its own frames and never this caller.
        for c in s.clients.values_mut().filter(|c| {
            c.hello.is_some()
                && !c.closing
                && !c.paused.contains(&frame.screen)
                && c.supports_video(&format)
        }) {
            c.revisions.entry(frame.screen).or_default();
            let duration = c.queue_duration_us;
            let q = c
                .queues
                .entry(frame.screen)
                .or_insert_with(|| FrameQueue::new(duration, self.config.queue_bytes));
            let dropped = q.dropped();
            let (_, request) = q.offer(frame.clone());
            c.dropped_frames += q.dropped() - dropped;
            // Dispatch via the connection worker, never call back into the hub under this lock.
            if request {
                c.keyframe_requests.insert(frame.screen);
            }
        }
    }
    fn on_audio_format(&self, stream: u32, format: Option<Arc<AudioFormat>>) {
        let mut s = self.state.lock().unwrap();
        if s.host.is_none() {
            return;
        }
        let old = s.audio_formats.remove(&stream);
        if let Some(f) = &format {
            s.audio_formats.insert(stream, f.clone());
        }
        for c in s.clients.values_mut() {
            if let Some(f) = format.as_ref().filter(|f| c.supports(f.codec)) {
                c.audio_format(json::audio(stream, Some(f)));
            } else if old.as_ref().is_some_and(|f| c.supports(f.codec)) {
                c.audio_format(json::audio(stream, None));
            }
        }
    }
    fn on_audio_packet(&self, p: AudioPacket) {
        let mut s = self.state.lock().unwrap();
        if s.host.is_none() || p.session != s.session.id || s.session.phase == SessionPhase::Ended {
            return;
        }
        let Some(f) = s.audio_formats.get(&p.stream).cloned() else {
            return;
        };
        for c in s
            .clients
            .values_mut()
            .filter(|c| !c.closing && c.supports(f.codec))
        {
            c.audio.offer(p.clone(), &f);
        }
        // Developer diagnostics: compare relay input with each client's queue every 5 s.
        s.audio_received += 1;
        let now = Instant::now();
        if now.duration_since(s.audio_reported_at) >= Duration::from_secs(5) {
            let clients: Vec<String> = s
                .clients
                .iter_mut()
                .map(|(id, c)| {
                    let line = format!("{id}:sent={},dropped={}", c.audio.sent, c.audio.dropped);
                    c.audio.sent = 0;
                    c.audio.dropped = 0;
                    line
                })
                .collect();
            let report = format!(
                "{{\"type\":\"audio-flow\",\"received\":{},\"clients\":\"{}\"}}",
                s.audio_received,
                clients.join(" ")
            );
            s.audio_received = 0;
            s.audio_reported_at = now;
            // A slow log reader must not hold the lock that the producer and every client share.
            drop(s);
            eprintln!("{report}");
        }
    }
    fn on_microphone(&self, stream: u32, request: Option<MicRequest>) {
        let mut s = self.state.lock().unwrap();
        if s.host.is_none() {
            return;
        }
        if let Some(r) = &request {
            s.microphones.insert(stream, r.clone());
        } else {
            s.microphones.remove(&stream);
        }
        for c in s.clients.values_mut().filter(|c| c.microphone()) {
            c.json(json::microphone(stream, request.as_ref()));
        }
    }
    fn close(&self) {
        if let Some(run) = self.runtime.lock().unwrap().take() {
            run.active.store(false, Ordering::Release);
            run.listening.notify_all();
        }
        let mut s = self.state.lock().unwrap();
        s.host = None;
        s.clients.clear();
        s.formats.clear();
        s.audio_formats.clear();
        s.microphones.clear();
        s.session = SessionState {
            id: 0,
            phase: SessionPhase::Connecting,
            reason: None,
        };
    }
}
impl Drop for WebOutput {
    fn drop(&mut self) {
        self.close();
    }
}
struct SocketIo {
    socket: TcpStream,
    deadline: Option<Instant>,
}
impl Read for SocketIo {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        if let Some(end) = self.deadline {
            let remaining = end
                .checked_duration_since(Instant::now())
                .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "Handshake deadline"))?;
            self.socket
                .set_read_timeout(Some(remaining.max(Duration::from_millis(1))))?;
        }
        self.socket.read(b)
    }
}
impl Write for SocketIo {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        self.socket.write(b)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.socket.flush()
    }
}
enum Transport {
    Plain(SocketIo),
    Secure(Box<rustls::StreamOwned<rustls::ServerConnection, SocketIo>>),
}
impl Transport {
    fn clear_deadline(&mut self) {
        match self {
            Self::Plain(s) => s.deadline = None,
            Self::Secure(s) => s.sock.deadline = None,
        }
    }
    fn close_write(&mut self) {
        if let Self::Secure(s) = self {
            s.conn.send_close_notify();
            let _ = s.flush();
        }
        let _ = self.socket().shutdown(Shutdown::Write);
    }
    fn socket(&self) -> &TcpStream {
        match self {
            Self::Plain(s) => &s.socket,
            Self::Secure(s) => &s.sock.socket,
        }
    }
}
impl Read for Transport {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Plain(s) => s.read(b),
            Self::Secure(s) => s.read(b),
        }
    }
}
impl Write for Transport {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        match self {
            Self::Plain(s) => s.write(b),
            Self::Secure(s) => s.write(b),
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        match self {
            Self::Plain(s) => s.flush(),
            Self::Secure(s) => s.flush(),
        }
    }
}
static NEXT_CLIENT: AtomicU64 = AtomicU64::new(1);
fn connection(
    socket: TcpStream,
    config: &WebOutputConfig,
    tls: Option<Arc<rustls::ServerConfig>>,
    state: &Arc<Mutex<State>>,
    run: &Arc<Runtime>,
    hello_rate_limit: &Mutex<HelloRateLimit>,
    plugins: Option<&plugins::Plugins>,
) -> Result<()> {
    let address = socket.peer_addr()?.ip();
    socket.set_nodelay(true)?;
    let send_bound = send_buffer::bound(&socket, config.unsent_limit_bytes);
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    let socket = SocketIo {
        socket,
        deadline: Some(Instant::now() + Duration::from_secs(5)),
    };
    let mut transport = if let Some(t) = tls {
        Transport::Secure(Box::new(rustls::StreamOwned::new(
            rustls::ServerConnection::new(t)?,
            socket,
        )))
    } else {
        Transport::Plain(socket)
    };
    let request = http::read(&mut transport);
    if let Ok(request) = &request {
        // With plugins off, /plugins/ paths take the ordinary route, exactly as before plugins existed.
        if plugins.is_some()
            && request
                .target
                .split('?')
                .next()
                .is_some_and(|p| p.starts_with("/plugins/"))
        {
            let Some(origin) = plugins::origin(
                !matches!(config.tls, Tls::Disabled),
                &request.headers["host"],
            ) else {
                http::error(&mut transport, 400);
                return Ok(());
            };
            if request.target.split('?').next() == Some("/plugins/channel")
                && request.method == "GET"
            {
                let Some(p) = plugins else {
                    http::error(&mut transport, 503);
                    return Ok(());
                };
                if let Err(status) = http::upgrade(request, &mut transport) {
                    http::error(&mut transport, status);
                    return Ok(());
                }
                let permit = p.reserve();
                // Active channels have their own cap; leave room for a takeover handshake.
                run.connections.fetch_sub(1, Ordering::AcqRel);
                let result = p.channel(transport, run, &permit);
                run.connections.fetch_add(1, Ordering::AcqRel);
                return result;
            }
            plugins::serve(plugins, request, &origin, &mut transport)
                .map_err(|e| format!("Plugin HTTP {e}"))?;
            transport.close_write();
            return Ok(());
        }
    }
    let mut _receiver_permit = None;
    let result = (|| -> std::result::Result<bool, u16> {
        let request = request?;
        if request.method != "GET" {
            return Err(405);
        }
        if request.target.split('?').next() == Some("/oxp/v0") {
            if let Some(p) = plugins {
                _receiver_permit = Some(p.reserve_receiver(config.max_clients).ok_or(503u16)?);
            }
            http::upgrade(&request, &mut transport)?;
            Ok(true)
        } else {
            http::serve(&request, &config.static_root, &mut transport)?;
            Ok(false)
        }
    })();
    match result {
        Err(status) => {
            http::error(&mut transport, status);
            transport.clear_deadline();
            transport.close_write();
            let _ = transport
                .socket()
                .set_read_timeout(Some(Duration::from_millis(500)));
            let start = Instant::now();
            let mut b = [0; 4096];
            let mut remaining = 65536;
            while remaining > 0 && start.elapsed() < Duration::from_millis(500) {
                match transport.read(&mut b) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => remaining -= n,
                }
            }
            return Ok(());
        }
        Ok(false) => {
            transport.close_write();
            return Ok(());
        }
        Ok(true) => {}
    }
    transport.clear_deadline();
    transport.socket().set_read_timeout(None)?;
    transport.socket().set_nonblocking(true)?;
    let mut ws = WebSocket::from_raw_socket(
        transport,
        Role::Server,
        Some(
            WebSocketConfig::default()
                .max_message_size(Some(1024 * 1024))
                .max_frame_size(Some(1024 * 1024))
                .max_write_buffer_size(16 * 1024 * 1024),
        ),
    );
    let id = format!(
        "browser-{:020}",
        NEXT_CLIENT.fetch_add(1, Ordering::Relaxed)
    );
    let mut log = json!({"type":"web-send-buffer","clientId":id});
    log.as_object_mut()
        .unwrap()
        .extend(send_bound.json().as_object().unwrap().clone());
    eprintln!("{log}");
    let mut client = Client::new(config.queue_duration_us);
    client.socket = ws.get_ref().socket().try_clone().ok();
    state.lock().unwrap().clients.insert(id.clone(), client);
    let result = client_loop(&mut ws, &id, config, state, run, hello_rate_limit, address);
    if result.is_err() {
        let _ = close(&mut ws, 1002);
    }
    let (host, touched) = {
        let mut s = state.lock().unwrap();
        let c = s.clients.remove(&id);
        (s.host.clone(), c.map(|c| c.touched).unwrap_or_default())
    };
    if let Some(host) = host {
        for screen in touched {
            host.control(ControlEvent::Touch {
                screen,
                contacts: Vec::new(),
                origin: Origin {
                    output_id: "web".into(),
                    client_id: id.clone(),
                },
            });
        }
    }
    report_caps(state);
    result
}
fn transient(e: &Error) -> bool {
    matches!(e,Error::Io(e) if matches!(e.kind(),io::ErrorKind::WouldBlock|io::ErrorKind::TimedOut))
}
fn send<S: Read + Write>(ws: &mut WebSocket<S>, message: Message) -> Result<()> {
    match ws.send(message) {
        Ok(()) => Ok(()),
        Err(e) if transient(&e) => Ok(()),
        Err(e) => Err(e.into()),
    }
}
fn client_loop<S: Read + Write>(
    ws: &mut WebSocket<S>,
    id: &str,
    config: &WebOutputConfig,
    state: &Arc<Mutex<State>>,
    run: &Runtime,
    hello_rate_limit: &Mutex<HelloRateLimit>,
    address: std::net::IpAddr,
) -> Result<()> {
    let clock = Instant::now();
    let mut sequences = [0u32; 256];
    let mut audio_sequences = [0u32; 256];
    // The video frame (screen, pts, receipt) whose bytes may still wait in user-space buffers.
    let mut unwritten = None;
    while run.active.load(Ordering::Acquire) {
        // Limit each batch so a continuously sending client cannot starve media or close handling.
        for _ in 0..32 {
            match ws.read() {
                Ok(Message::Text(t)) => {
                    let v = json::parse(&t, Some(json::Direction::Client))?;
                    let established = state
                        .lock()
                        .unwrap()
                        .clients
                        .get(id)
                        .is_some_and(|c| c.hello.is_some());
                    if !established && v["type"] != "hello" {
                        return close(ws, 1008);
                    }
                    if v["type"] == "hello" {
                        if let Pairing::Required(token) = &config.pairing {
                            if !hello_rate_limit.lock().unwrap().allow(
                                address,
                                Instant::now(),
                                || constant_time_token(token, v["token"].as_str().unwrap()),
                            ) {
                                return close(ws, 1008);
                            }
                        }
                    }
                    handle(v, id, config, state, oxp_core::monotonic_nanos() / 1000)?;
                }
                Ok(Message::Binary(b)) => {
                    let origin = Origin {
                        output_id: "web".into(),
                        client_id: id.into(),
                    };
                    let oxp_wire::Message::Binary { header, payload } = oxp_wire::decode(&b)?
                    else {
                        return close(ws, 1002);
                    };
                    let host = {
                        let mut s = state.lock().unwrap();
                        let c = s.clients.get_mut(id).ok_or("Client closed")?;
                        if c.hello.is_none() {
                            return close(ws, 1008);
                        }
                        if header.kind == Kind::Microphone
                            && (!c.microphone()
                                || !s
                                    .microphones
                                    .get(&(header.stream as u32))
                                    .is_some_and(|r| valid_mic_packet(r, payload.len())))
                        {
                            return close(ws, 1008);
                        }
                        let c = s.clients.get_mut(id).ok_or("Client closed")?;
                        if header.kind == Kind::Touch {
                            c.touched.insert(header.stream as u32);
                        }
                        s.host.clone()
                    };
                    let event = match header.kind {
                        Kind::Touch => ControlEvent::Touch {
                            screen: header.stream as u32,
                            contacts: oxp_wire::decode_touch(&payload)?
                                .into_iter()
                                .map(|c| TouchContact {
                                    id: c.id,
                                    x: c.x,
                                    y: c.y,
                                    pressed: c.pressed,
                                })
                                .collect(),
                            origin,
                        },
                        Kind::Key => {
                            let (code, down) = oxp_wire::decode_key(&payload)?;
                            ControlEvent::Key {
                                code: KeyCode::all()
                                    .into_iter()
                                    .find(|c| c.wire_code() == code)
                                    .unwrap(),
                                down,
                                origin,
                            }
                        }
                        Kind::Microphone => ControlEvent::MicPacket {
                            stream: header.stream as u32,
                            data: payload.into(),
                            pts_us: header.pts_us,
                            origin,
                        },
                        _ => return close(ws, 1008),
                    };
                    if let Some(host) = host {
                        host.control(event);
                    }
                }
                Ok(Message::Close(_)) => {
                    let _ = ws.flush();
                    return Ok(());
                }
                Ok(_) => {}
                Err(e) if transient(&e) => break,
                Err(e) => return Err(e.into()),
            }
        }
        let established = state
            .lock()
            .unwrap()
            .clients
            .get(id)
            .is_some_and(|c| c.hello.is_some());
        if !established && clock.elapsed() > Duration::from_secs(5) {
            return close(ws, 1008);
        }
        // A congested socket must not delay the request that recovers from its own drops.
        request_keyframes(id, state);
        // Keep unsent socket data to one selected frame; leave subsequent frames in FrameQueue.
        match ws.flush() {
            Ok(()) => written(id, state, unwritten.take()),
            Err(e) if transient(&e) => {
                std::thread::sleep(Duration::from_millis(2));
                continue;
            }
            Err(e) => return Err(e.into()),
        }
        for _ in 0..32 {
            match ws.flush() {
                Ok(()) => written(id, state, unwritten.take()),
                Err(e) if transient(&e) => break,
                Err(e) => return Err(e.into()),
            }
            let (message, frame, audio, closing) = {
                let mut s = state.lock().unwrap();
                let Some(c) = s.clients.get_mut(id) else {
                    return Ok(());
                };
                let (message, frame, audio) = c.poll();
                (message, frame, audio, c.closing)
            };
            if closing {
                return close(ws, 1009);
            }
            if let Some(mut v) = message {
                if v["type"] == "pong" {
                    v["t2Us"] = json!((oxp_core::monotonic_nanos() / 1000).to_string());
                }
                send(ws, Message::Text(json::encode(&v)?.into()))?;
            } else if let Some((f, revision)) = frame {
                // A pause or new format invalidates even a frame selected before the callback.
                if !state.lock().unwrap().clients.get(id).is_some_and(|c| {
                    c.revisions.get(&f.frame.screen) == Some(&revision)
                        && !c.paused.contains(&f.frame.screen)
                }) {
                    continue;
                }
                let screen = u8::try_from(f.frame.screen)?;
                let seq = &mut sequences[screen as usize];
                let h = Header {
                    kind: Kind::Video,
                    stream: screen,
                    flags: u8::from(f.frame.key_frame) | if f.gap { 2 } else { 0 },
                    sequence: *seq,
                    pts_us: f.frame.pts_us,
                    sender_clock_us: f.frame.received_at_nanos / 1000,
                };
                *seq = seq.wrapping_add(1);
                let mut bytes = oxp_wire::encode_header(&h)?.to_vec();
                bytes.extend_from_slice(&f.frame.data);
                send(ws, Message::Binary(bytes.into()))?;
                unwritten = Some((f.frame.screen, f.frame.pts_us, f.frame.received_at_nanos));
            } else if let Some((p, generation)) = audio {
                if state
                    .lock()
                    .unwrap()
                    .clients
                    .get(id)
                    .is_none_or(|c| c.audio.generation(p.stream) != generation)
                {
                    continue;
                }
                let stream = u8::try_from(p.stream)?;
                let seq = &mut audio_sequences[stream as usize];
                let h = Header {
                    kind: Kind::Audio,
                    stream,
                    flags: 0,
                    sequence: *seq,
                    pts_us: p.pts_us,
                    sender_clock_us: oxp_core::monotonic_nanos() / 1000,
                };
                *seq = seq.wrapping_add(1);
                let mut bytes = oxp_wire::encode_header(&h)?.to_vec();
                bytes.extend_from_slice(&p.data);
                send(ws, Message::Binary(bytes.into()))?;
            } else {
                break;
            }
        }
        match ws.flush() {
            Ok(()) => written(id, state, unwritten.take()),
            Err(e) if transient(&e) => {}
            Err(e) => return Err(e.into()),
        }
        std::thread::sleep(Duration::from_millis(2));
    }
    close(ws, 1001)
}
/// Records that a sent frame's last byte reached the socket: with the bounded kernel buffer, this
/// time plus the bounded unsent bytes is the relay's whole share of the frame's latency.
fn written(id: &str, state: &Mutex<State>, frame: Option<(u32, i64, i64)>) {
    let Some((_screen, _pts, received_at)) = frame else {
        return;
    };
    let now = oxp_core::monotonic_nanos();
    let latency_us = (now - received_at) / 1000;
    if let Some(c) = state.lock().unwrap().clients.get_mut(id) {
        c.send_latency_us = Some(c.send_latency_us.unwrap_or(0).max(latency_us));
        #[cfg(test)]
        c.written.push((_screen, _pts, received_at, now));
    }
}
/// One request per gap comes from the client's FrameQueue; the hub rate-limits requests per screen.
fn request_keyframes(id: &str, state: &Mutex<State>) {
    let (host, screens) = {
        let mut s = state.lock().unwrap();
        let host = s.host.clone();
        let Some(c) = s.clients.get_mut(id) else {
            return;
        };
        (host, std::mem::take(&mut c.keyframe_requests))
    };
    if let Some(h) = host {
        for screen in screens {
            h.control(ControlEvent::KeyFrameRequest {
                screen,
                origin: Origin {
                    output_id: "web".into(),
                    client_id: id.into(),
                },
            });
        }
    }
}
fn close<S: Read + Write>(ws: &mut WebSocket<S>, code: u16) -> Result<()> {
    let _ = ws.close(Some(CloseFrame {
        code: CloseCode::from(code),
        reason: "".into(),
    }));
    Ok(())
}
fn handle(
    v: Value,
    id: &str,
    config: &WebOutputConfig,
    state: &Arc<Mutex<State>>,
    now: i64,
) -> Result<()> {
    let origin = Origin {
        output_id: "web".into(),
        client_id: id.into(),
    };
    let host = state.lock().unwrap().host.clone();
    match v["type"].as_str().unwrap() {
        "hello" => {
            {
                let mut s = state.lock().unwrap();
                let session = json::session(&s.session);
                let receiver_build = s.receiver_build.clone();
                let formats = s.formats.clone();
                let audio = s.audio_formats.clone();
                let microphones = s.microphones.clone();
                let settings = s.display_settings.clone();
                let area = page_area(&s);
                let mut features = Vec::new();
                if settings.is_some() {
                    features.push("display-settings");
                }
                if s.view_areas.contains_key(&0) {
                    features.push("view-area");
                }
                let c = s.clients.get_mut(id).ok_or("Client closed")?;
                let first = c.hello.is_none();
                let mut hello = v;
                hello.as_object_mut().unwrap().remove("token");
                c.set_playback(hello["playback"].as_str(), config.queue_duration_us);
                let previous = c.hello.replace(hello);
                if first {
                    let welcome =
                        welcome_value(config, id, session, &features, receiver_build.as_deref());
                    c.json(welcome);
                    if let Some(settings) = settings {
                        c.json(oxp_wire::display_settings(&settings));
                    }
                    if let Some(area) = area {
                        c.json(area);
                    }
                }
                for (&screen, f) in &formats {
                    let before = f.codec != VideoCodec::H265
                        || previous.as_ref().is_some_and(|h| {
                            h["decoders"].as_array().unwrap().iter().any(|d| {
                                d["codecString"]
                                    .as_str()
                                    .is_some_and(oxp_core::video_codec_policy::is_hevc)
                            })
                        });
                    if first || before != c.supports_video(f) {
                        c.video_format(screen, Some(f));
                    }
                }
                for (&stream, f) in &audio {
                    let before = previous.as_ref().is_some_and(|h| {
                        h["audio"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .any(|v| v == json::audio_codec(f.codec))
                    });
                    let after = c.supports(f.codec);
                    if before != after {
                        let message = json::audio(stream, if after { Some(f) } else { None });
                        if first {
                            c.json(message);
                        } else {
                            c.audio_format(message);
                        }
                    }
                }
                let had_mic = previous.as_ref().is_some_and(|h| h["microphone"] == true);
                if had_mic != c.microphone() {
                    for (&stream, r) in &microphones {
                        let has_mic = c.microphone();
                        c.json(json::microphone(
                            stream,
                            if has_mic { Some(r) } else { None },
                        ));
                    }
                }
            }
            report_caps(state);
        }
        "display-settings" => {
            let allowed = {
                let s = state.lock().unwrap();
                s.display_settings.is_some()
                    && s.clients
                        .iter()
                        .find(|(_, c)| {
                            !c.closing
                                && c.hello.as_ref().is_some_and(|h| {
                                    h["displays"]
                                        .as_array()
                                        .unwrap()
                                        .iter()
                                        .any(|d| d["screen"] == 0)
                                })
                        })
                        .is_some_and(|(primary, _)| primary == id)
            };
            if allowed {
                if let Some(h) = host {
                    h.control(ControlEvent::DisplaySettings {
                        settings: {
                            let mut complete = v.clone();
                            if complete.get("videoCodec").is_none() {
                                complete["videoCodec"] = json!(
                                    state
                                        .lock()
                                        .unwrap()
                                        .display_settings
                                        .as_ref()
                                        .unwrap()
                                        .video_codec
                                );
                            }
                            oxp_wire::decode_display_settings(&complete)?
                        },
                        origin,
                    });
                }
            }
        }
        "fullscreen-exit" => {
            if let Some(callback) = &config.diagnostics {
                callback(id, &v);
            }
        }
        "pause" => {
            let screen = integer(&v["screen"]) as u32;
            let mut s = state.lock().unwrap();
            let format = s.formats.get(&screen).cloned();
            let c = s.clients.get_mut(id).ok_or("Client closed")?;
            if v["paused"] == true {
                c.paused.insert(screen);
                c.invalidate(screen);
            } else if c.paused.remove(&screen) {
                c.invalidate(screen);
                c.video_format(screen, format.as_deref());
            }
        }
        "keyframe" => {
            if let Some(h) = host {
                h.control(ControlEvent::KeyFrameRequest {
                    screen: integer(&v["screen"]) as u32,
                    origin,
                });
            }
        }
        "ping" => {
            if let Some(c) = state.lock().unwrap().clients.get_mut(id) {
                c.json(json!({"type":"pong","t0Us":v["t0Us"],"t1Us":now.to_string(),"t2Us":now.to_string()}));
            }
        }
        "receiver-info" => {
            let first = {
                let mut s = state.lock().unwrap();
                if let Some(c) = s.clients.get_mut(id) {
                    if c.receiver_info.is_none() {
                        c.receiver_info = Some(v.clone());
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            };
            if first {
                if let Some(callback) = &config.diagnostics {
                    callback(id, &v);
                }
            }
        }
        "stats" => {
            if let Some(callback) = &config.diagnostics {
                // The relay's own counters ride along with the receiver's report for this client.
                let (dropped, kernel, latency) =
                    state
                        .lock()
                        .unwrap()
                        .clients
                        .get_mut(id)
                        .map_or((0, None, None), |c| {
                            (
                                c.dropped_frames,
                                c.socket.as_ref().and_then(send_buffer::kernel_queue),
                                c.send_latency_us.take(),
                            )
                        });
                let mut report = v.clone();
                report["relayDroppedFrames"] = json!(dropped);
                if let Some(us) = latency {
                    report["relaySendLatencyMs"] = json!(us as f64 / 1000.0);
                }
                if let Some(k) = kernel {
                    // Bytes that left this queue but still wait in the kernel for this browser.
                    report["relayKernelSendQueueBytes"] = json!(k.queued);
                    if let Some(unsent) = k.unsent {
                        report["relayKernelUnsentBytes"] = json!(unsent);
                    }
                }
                callback(id, &report);
            }
            if let Some(h) = host {
                h.stats(OutputStats {
                    origin,
                    screen: integer(&v["screen"]) as u32,
                    decode_time_us: v["decodeMs"].as_f64().map(|n| (n * 1000.0) as i64),
                    decode_queue: integer(&v["decodeQueue"]).min(u32::MAX as u64) as u32,
                    dropped: integer(&v["dropped"]),
                    rendered: integer(&v["rendered"]),
                    audio_buffered_us: v["audioBufferedMs"].as_f64().map(|n| (n * 1000.0) as i64),
                    audio_underruns: v["audioUnderruns"].as_f64().unwrap_or(0.0) as u64,
                    end_to_end_latency_us: v["renderedLatencyMs"]
                        .as_f64()
                        .map(|n| (n * 1000.0) as i64),
                });
            }
        }
        "restart" => {
            let allowed = {
                let mut s = state.lock().unwrap();
                let primary = s
                    .clients
                    .iter()
                    .find(|(_, c)| {
                        !c.closing
                            && c.hello
                                .as_ref()
                                .is_some_and(|h| !h["displays"].as_array().unwrap().is_empty())
                    })
                    .map(|(id, _)| id.as_str());
                if primary == Some(id)
                    && s.last_restart
                        .is_none_or(|at| at.elapsed() >= Duration::from_secs(10))
                {
                    s.last_restart = Some(Instant::now());
                    true
                } else {
                    false
                }
            };
            if allowed {
                if let Some(h) = host {
                    h.request_restart(origin);
                }
            }
        }
        "pong" => {}
        _ => return Err("Wrong client message".into()),
    }
    Ok(())
}
fn report_caps(state: &Arc<Mutex<State>>) {
    let (host, caps) = {
        let s = state.lock().unwrap();
        let clients: Vec<_> = s
            .clients
            .values()
            .filter(|c| !c.closing)
            .filter_map(|c| c.hello.as_ref())
            .collect();
        let mut caps = OutputCapabilities {
            prefers_h264: false,
            latency: LatencyClass::Interactive,
            decoders: Vec::new(),
            displays: BTreeMap::new(),
            touch: 0,
            keys: BTreeSet::new(),
            audio: BTreeSet::new(),
            microphone: false,
        };
        for h in &clients {
            for d in h["decoders"].as_array().unwrap() {
                let codec = d["codecString"].as_str().unwrap();
                if !caps.decoders.iter().any(|d| d.codec_string == codec) {
                    caps.decoders.push(DecoderSupport {
                        codec_string: codec.into(),
                        hardware: clients
                            .iter()
                            .filter(|h| {
                                h["displays"]
                                    .as_array()
                                    .unwrap()
                                    .iter()
                                    .any(|d| d["screen"] == 0)
                            })
                            .all(|h| {
                                h["decoders"].as_array().unwrap().iter().any(|d| {
                                    (d["codecString"] == codec
                                        || oxp_core::video_codec_policy::is_hevc(codec)
                                            && d["codecString"]
                                                .as_str()
                                                .is_some_and(oxp_core::video_codec_policy::is_hevc))
                                        && d["hardware"] == true
                                })
                            }),
                    });
                }
            }
            for d in h["displays"].as_array().unwrap() {
                let px = |v: &Value| v.as_f64().unwrap().round().min(i32::MAX as f64) as u32;
                let a = &d["safeArea"];
                caps.displays
                    .entry(integer(&d["screen"]) as u32)
                    .or_insert_with(|| DisplayInfo {
                        viewport: d.get("viewport").map(|v| Viewport {
                            width_px: px(&v["width"]),
                            height_px: px(&v["height"]),
                        }),
                        fullscreen: d["fullscreen"].as_bool(),
                        fullscreen_preferred: d["fullscreenPreferred"].as_bool(),
                        width_px: px(&d["width"]),
                        height_px: px(&d["height"]),
                        density_dpi: (d["dpr"].as_f64().unwrap() * 160.0)
                            .round()
                            .min(i32::MAX as f64) as u32,
                        refresh_hz: d["refreshHz"].as_f64().unwrap(),
                        frame_rate: match d["frameRate"].as_str() {
                            Some("FPS_30") => FrameRatePreference::Fps30,
                            Some("FPS_35") => FrameRatePreference::Fps35,
                            Some("FPS_40") => FrameRatePreference::Fps40,
                            Some("FPS_45") => FrameRatePreference::Fps45,
                            Some("FPS_50") => FrameRatePreference::Fps50,
                            Some("FPS_55") => FrameRatePreference::Fps55,
                            Some("FPS_60") => FrameRatePreference::Fps60,
                            _ => FrameRatePreference::Auto,
                        },
                        sustained_fps: d["sustainedFps"].as_f64(),
                        safe_area: SafeArea {
                            left: px(&a["left"]),
                            top: px(&a["top"]),
                            right: px(&a["right"]),
                            bottom: px(&a["bottom"]),
                        },
                    });
            }
            caps.touch = caps.touch.max(integer(&h["maxTouchContacts"]) as u32);
            caps.microphone |= h["microphone"] == true;
            caps.prefers_h264 |= h["videoCodec"] == "H264"
                && h["displays"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|d| d["screen"] == 0);
            for a in h["audio"].as_array().unwrap() {
                caps.audio.insert(match a.as_str().unwrap() {
                    "AAC_LC" => AudioCodec::AacLc,
                    "OPUS" => AudioCodec::Opus,
                    _ => AudioCodec::PcmS16le,
                });
            }
        }
        (s.host.clone(), caps)
    };
    if let Some(h) = host {
        h.capabilities(caps);
    }
}

fn integer(v: &Value) -> u64 {
    v.as_f64().unwrap() as u64
}

fn page_area(s: &State) -> Option<Value> {
    let area = match *s.view_areas.get(&0)? {
        Some(area) => area,
        None => {
            let (width, height) = s
                .formats
                .get(&0)
                .map(|f| (f.width, f.height))
                .or_else(|| s.area_canvases.get(&0).copied())?;
            ViewArea {
                x: 0,
                y: 0,
                width,
                height,
                canvas_width: width,
                canvas_height: height,
            }
        }
    };
    let mut value = oxp_wire::area_value(area);
    value["type"] = json!("view-area");
    Some(value)
}
fn receiver_build(root: &std::path::Path) -> Option<String> {
    let mut file = std::fs::File::open(root.join("receiver-build.txt")).ok()?;
    let mut bytes = Vec::new();
    std::io::Read::by_ref(&mut file)
        .take(1024)
        .read_to_end(&mut bytes)
        .ok()?;
    let text = std::str::from_utf8(&bytes).ok()?.trim();
    (!text.is_empty() && text.len() <= 128 && !text.chars().any(char::is_control))
        .then(|| text.into())
}

fn welcome_value(
    config: &WebOutputConfig,
    id: &str,
    session: Value,
    features: &[&str],
    receiver_build: Option<&str>,
) -> Value {
    let mut value = json!({"type":"welcome","version":0,"clientId":id,"session":session,
        "sourceUrl":config.source_url,"attribution":"Powered by OpenXPlay.","projectUrl":json::PROJECT_URL});
    if !features.is_empty() {
        value["features"] = json!(features);
    }
    if config.plugins.is_some() {
        value["plugins"] = json!({"host":"/plugins/host/index.html"});
    }
    if let Some(build) = receiver_build {
        value["receiverBuild"] = json!(build);
    }
    if let Some(relay) = &config.relay {
        value["relay"] = json!({"name":relay.name,"version":relay.version});
    }
    value
}
// Inputs may announce support after a browser connects. Publish a fresh welcome and snapshot.
fn announce_features(config: &WebOutputConfig, s: &mut State) {
    let mut features = Vec::new();
    if s.display_settings.is_some() {
        features.push("display-settings");
    }
    if s.view_areas.contains_key(&0) {
        features.push("view-area");
    }
    let settings = s.display_settings.as_ref().map(oxp_wire::display_settings);
    let area = page_area(s);
    let session = json::session(&s.session);
    for (id, c) in s.clients.iter_mut().filter(|(_, c)| c.hello.is_some()) {
        c.json(welcome_value(
            config,
            id,
            session.clone(),
            &features,
            s.receiver_build.as_deref(),
        ));
        if let Some(settings) = &settings {
            c.json(settings.clone());
        }
        if let Some(area) = &area {
            c.json(area.clone());
        }
    }
}

#[cfg(test)]
mod display_tests;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod video_codec_tests;

#[cfg(test)]
mod send_order_tests {
    use super::*;

    #[test]
    fn messages_and_audio_formats_then_audio_before_large_video() {
        let mut client = Client {
            messages: VecDeque::from([json!({"type": "session"})]),
            revisions: BTreeMap::from([(0, 7)]),
            ..Client::new(100_000)
        };
        let format = AudioFormat {
            stream: 1,
            role: AudioRole::Media,
            codec: AudioCodec::AacLc,
            sample_rate: 48000,
            channels: 2,
            config: Some(Arc::from([0x11, 0x90])),
        };
        let first_format = json!({"type": "audio-format", "stream": 1});
        let next_format = json!({"type": "audio-format", "stream": 1, "format": null});
        assert!(client.audio.format(first_format.clone()));
        for pts_us in [0, 21_333] {
            client.audio.offer(
                AudioPacket {
                    stream: 1,
                    session: 1,
                    pts_us,
                    data: Arc::from([1; 32]),
                },
                &format,
            );
        }
        assert!(client.audio.format(next_format.clone()));
        let mut queue = FrameQueue::default();
        queue.offer(VideoFrame {
            screen: 0,
            session: 1,
            data: vec![1; 1024 * 1024].into(),
            pts_us: 0,
            key_frame: true,
            received_at_nanos: 0,
        });
        client.queues.insert(0, queue);
        let (message, video, audio) = client.poll();
        assert_eq!(message.unwrap()["type"], "session");
        assert!(video.is_none() && audio.is_none());
        let (message, video, audio) = client.poll();
        assert_eq!(message, Some(first_format));
        assert!(video.is_none() && audio.is_none());
        for pts in [0, 21_333] {
            let (message, video, audio) = client.poll();
            assert!(message.is_none() && video.is_none());
            assert_eq!(audio.unwrap().0.pts_us, pts);
            assert_eq!(client.queues[&0].len(), 1);
        }
        let (message, video, audio) = client.poll();
        assert_eq!(message, Some(next_format));
        assert!(video.is_none() && audio.is_none());
        let (message, video, audio) = client.poll();
        assert!(message.is_none() && audio.is_none());
        let (video, revision) = video.unwrap();
        assert_eq!(video.frame.data.len(), 1024 * 1024);
        assert_eq!(revision, 7);
        let (message, video, audio) = client.poll();
        assert!(message.is_none() && video.is_none() && audio.is_none());
    }
}
