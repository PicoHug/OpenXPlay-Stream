// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use crate::*;
use oxp_clink::{model::*, *};
use oxp_wire::{Header, Kind, Message, Result};
use serde_json::json;
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::SyncSender,
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
pub struct Pin {
    pub digits: Option<String>,
    pub file: Option<String>,
    pub stopped: Arc<AtomicBool>,
}
impl Pin {
    pub fn request_with(
        &self,
        id: &str,
        timeout: Duration,
        emit: impl Fn(serde_json::Value),
    ) -> Option<String> {
        if let Some(path) = &self.file {
            if let Err(e) = std::fs::remove_file(path) {
                if e.kind() != std::io::ErrorKind::NotFound {
                    emit(json!({"type":"error","message":"Cannot clear PIN file"}));
                    return None;
                }
            }
        }
        emit(json!({"type":"pin-request","client":id}));
        let pin = if let Some(path) = &self.file {
            let deadline = Instant::now() + timeout;
            loop {
                if self.stopped.load(Ordering::Acquire) {
                    return None;
                }
                if Instant::now() >= deadline {
                    emit(json!({"type":"pin-timeout","client":id}));
                    return None;
                }
                match std::fs::read_to_string(path) {
                    Ok(value) => {
                        if std::fs::remove_file(path).is_err() {
                            emit(json!({"type":"error","message":"Cannot delete PIN file"}));
                            return None;
                        }
                        break value.trim().to_owned();
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(_) => {
                        let _ = std::fs::remove_file(path);
                        emit(json!({"type":"error","message":"Cannot read PIN file"}));
                        return None;
                    }
                }
                std::thread::sleep(
                    Duration::from_millis(25)
                        .min(deadline.saturating_duration_since(Instant::now())),
                );
            }
        } else {
            self.digits.clone()?
        };
        if pin.is_empty() || !pin.bytes().all(|b| b.is_ascii_digit()) {
            emit(json!({"type":"error","message":"PIN must contain digits"}));
            return None;
        }
        Some(pin)
    }
}
impl PinProvider for Pin {
    fn request_pin(&self, id: &str) -> Option<String> {
        self.request_with(id, Duration::from_secs(120), event)
    }
}
pub fn link_event(state: oxp_link_linux::LinkEvent) -> serde_json::Value {
    use oxp_link_linux::LinkEvent;
    match state {
        LinkEvent::Scanning => json!({"type":"link","state":"scanning"}),
        LinkEvent::Connecting { address, name } => {
            json!({"type":"link","state":"connecting","headUnit":{"address":address,"name":name}})
        }
        LinkEvent::Joined {
            address,
            name,
            ssid,
            interface,
            addresses,
        } => {
            json!({"type":"link","state":"joined","headUnit":{"address":address,"name":name},"network":{"ssid":ssid,"interface":interface},"headUnitIp":addresses.head_unit_ipv4.to_string(),"localIp":addresses.local_ipv4.to_string()})
        }
        LinkEvent::Lost { reason } => json!({"type":"link","state":"lost","reason":reason}),
        LinkEvent::Diagnostic { reason } => {
            json!({"type":"diagnostic","message":format!("link {reason}")})
        }
    }
}
pub struct Listener;
impl ClinkListener for Listener {
    fn state(&self, s: ClinkState) {
        if s == ClinkState::Ended {
            // Callback events queued before the end stay ahead of it.
            EventLog::flush_global(Duration::from_millis(200));
        }
        event(json!({"type":"state","state":format!("{s:?}")}));
    }
    fn error(&self, e: &str) {
        event(json!({"type":"error","message":e}));
    }
    fn diagnostic(&self, message: &str) {
        event(json!({"type":"diagnostic","message":message}));
    }
    fn statistics(&self, sent: u64, dropped: u64) {
        event(json!({"type":"media-stats","sent":sent,"dropped":dropped}));
    }
    fn profile_mismatch(&self, input: &str, decoders: &[String]) {
        event(json!({"type":"profile-mismatch","input":input,"decoders":decoders}));
    }
}
pub struct Sink {
    pub tx: SyncSender<Message>,
    pub state: Mutex<(String, [u32; 256])>,
    pub clock: Instant,
    pub shutdown: Arc<dyn Fn() + Send + Sync>,
    pub failed: Arc<AtomicBool>,
    /// Callbacks may run on the producer's wire thread; events never wait for stderr there.
    pub log: Arc<EventLog>,
}
impl Sink {
    pub fn send(&self, m: Message) {
        if let Message::Json(v) = &m {
            self.log.try_log(v.clone());
        }
        if let Err(e) = self.tx.try_send(m) {
            self.log
                .try_log(json!({"type":"error","message":format!("Wire callback queue: {e}")}));
            self.failed.store(true, Ordering::Release);
            (self.shutdown)();
        }
    }
    fn binary(&self, s: &mut (String, [u32; 256]), kind: Kind, screen: u8, payload: Vec<u8>) {
        let seq = &mut s.1[screen as usize];
        let m = Message::Binary {
            header: Header {
                kind,
                stream: screen,
                flags: 0,
                sequence: *seq,
                pts_us: 0,
                sender_clock_us: self.clock.elapsed().as_micros() as i64,
            },
            payload,
        };
        *seq = seq.wrapping_add(1);
        self.send(m);
    }
    fn client(&self, s: &mut (String, [u32; 256]), id: &str) {
        if !id.is_empty() && s.0 != id {
            self.send(json_message(json!({"type":"client","client":id})));
            s.0 = id.into();
        }
    }
}
impl OutputSink for Sink {
    fn capabilities(&self, c: OutputCapabilities) {
        let displays: serde_json::Map<String, serde_json::Value> = c.displays.iter().map(|(s, d)| {
            let mut value = json!({"widthPx":d.width_px,"heightPx":d.height_px,"densityDpi":d.density_dpi,"refreshHz":d.refresh_hz,"safeArea":{"left":d.safe_area.left,"top":d.safe_area.top,"right":d.safe_area.right,"bottom":d.safe_area.bottom}});
            if d.frame_rate != FrameRatePreference::Auto { value["frameRate"] = json!(d.frame_rate.wire_name()); }
            if let Some(fps) = d.sustained_fps { value["sustainedFps"] = json!(fps); }
            if let Some(v) = &d.viewport { value["viewport"] = json!({"widthPx":v.width_px,"heightPx":v.height_px}); }
            if let Some(v) = d.fullscreen { value["fullscreen"] = json!(v); }
            if let Some(v) = d.fullscreen_preferred { value["fullscreenPreferred"] = json!(v); }
            (s.to_string(), value)
        }).collect();
        let mut value = json!({"type":"capabilities","latency":match c.latency{LatencyClass::Interactive=>"INTERACTIVE",LatencyClass::Buffered=>"BUFFERED"},"decoders":c.decoders.iter().map(|d|json!({"codecString":d.codec_string,"hardware":d.hardware})).collect::<Vec<_>>(),"displays":displays,"touch":c.touch,"keys":c.keys.iter().map(|k|k.wire_code()).collect::<Vec<_>>(),"audio":c.audio.iter().map(|a|match a{AudioCodec::AacLc=>"AAC_LC",AudioCodec::Opus=>"OPUS",AudioCodec::PcmS16le=>"PCM_S16LE"}).collect::<Vec<_>>(),"microphone":c.microphone});
        if c.prefers_h264 {
            value["prefersH264"] = json!(true);
        }
        self.send(json_message(value));
    }
    fn request_restart(&self, origin: Origin) {
        let mut s = self.state.lock().unwrap();
        self.client(&mut s, &origin.client_id);
        self.send(json_message(json!({"type":"restart"})));
    }
    fn control(&self, e: ControlEvent) {
        let mut s = self.state.lock().unwrap();

        match e {
            ControlEvent::DisplaySettings { settings, origin } => {
                self.client(&mut s, &origin.client_id);
                self.send(json_message(oxp_wire::display_settings(&settings)));
            }
            ControlEvent::Touch {
                screen,
                contacts,
                origin,
            } => {
                self.log.try_log(
                    json!({"type":"touch","screen":screen,"client":origin.client_id,"contacts":contacts.iter().map(|c|json!({"id":c.id,"x":c.x,"y":c.y,"pressed":c.pressed})).collect::<Vec<_>>()}),
                );
                self.client(&mut s, &origin.client_id);
                if let Ok(p) = oxp_wire::encode_touch(
                    &contacts
                        .iter()
                        .map(|c| oxp_wire::Contact {
                            id: c.id,
                            x: c.x,
                            y: c.y,
                            pressed: c.pressed,
                        })
                        .collect::<Vec<_>>(),
                ) {
                    self.binary(&mut s, Kind::Touch, screen as u8, p);
                }
            }
            ControlEvent::Key { code, down, origin } => {
                self.log.try_log(
                    json!({"type":"key","code":code.wire_code(),"down":down,"client":origin.client_id}),
                );
                self.client(&mut s, &origin.client_id);
                if let Ok(p) = oxp_wire::encode_key(code.wire_code(), down) {
                    self.binary(&mut s, Kind::Key, 0, p);
                }
            }
            ControlEvent::KeyFrameRequest { screen, origin } => {
                self.client(&mut s, &origin.client_id);
                self.send(json_message(json!({"type":"keyframe","screen":screen})));
            }
            ControlEvent::MicPacket {
                stream,
                data,
                pts_us,
                origin,
            } => {
                self.client(&mut s, &origin.client_id);
                if let Ok(stream) = u8::try_from(stream) {
                    let seq = &mut s.1[stream as usize];
                    let header = Header {
                        kind: Kind::Microphone,
                        stream,
                        flags: 0,
                        sequence: *seq,
                        pts_us,
                        sender_clock_us: oxp_core::monotonic_nanos() / 1000,
                    };
                    *seq = seq.wrapping_add(1);
                    self.send(Message::Binary {
                        header,
                        payload: data.to_vec(),
                    });
                }
            }
        }
    }
    fn stats(&self, s: OutputStats) {
        let mut state = self.state.lock().unwrap();
        self.client(&mut state, &s.origin.client_id);
        self.send(json_message(json!({"type":"stats","screen":s.screen,"decodeMs":s.decode_time_us.map(|n|n as f64/1000.0),"decodeQueue":s.decode_queue,"dropped":s.dropped,"rendered":s.rendered,"decodedLatencyMs":null,"renderedLatencyMs":s.end_to_end_latency_us.map(|n|n as f64/1000.0),"clockOffsetUs":null,"rttUs":null,"audioBufferedMs":s.audio_buffered_us.map(|n|n as f64/1000.0),"audioUnderruns":s.audio_underruns})));
    }
}
/// `--clink-transcode`: `auto` (default) transcodes streams the head unit cannot decode when a
/// hardware transcoder exists; `off` forwards every stream unchanged.
pub fn transcode_enabled(value: Option<&str>) -> Result<bool> {
    match value.unwrap_or("auto") {
        "auto" => Ok(true),
        "off" => Ok(false),
        _ => Err("--clink-transcode must be auto or off".into()),
    }
}
/// Wraps an output so decisions and failures appear as relay diagnostics. The factory is probed
/// here, at startup, and the devices it found are logged once.
pub fn with_transcoding(
    output: Arc<dyn oxp_core::ProjectionOutput>,
    factory: Arc<dyn oxp_transcode_linux::TranscoderFactory>,
    log: Arc<dyn Fn(&str) + Send + Sync>,
) -> Arc<dyn oxp_core::ProjectionOutput> {
    let output = oxp_transcode_linux::TranscodingOutput::new(output, factory.clone(), log.clone());
    log(&factory.describe());
    Arc::new(output)
}
pub fn diagnostic(message: &str) {
    event(json!({"type":"diagnostic","message":message}));
}
pub fn parse_ports(value: Option<&str>) -> Result<ClinkPorts> {
    let mut ports = ClinkPorts::default();
    let mut seen = std::collections::BTreeSet::new();
    if let Some(value) = value {
        for entry in value.split(',') {
            let (name, number) = entry.split_once('=').ok_or("Expected service=port")?;
            if !seen.insert(name) {
                return Err("Duplicate port service".into());
            }
            let port: u16 = number.parse()?;
            match name {
                "auth" => ports.auth = port,
                "control" => ports.control = port,
                "media" => ports.media = port,
                "cert" => ports.cert = port,
                "rtsp" => ports.rtsp = port,
                "uibc" => ports.uibc = port,
                "rtp" => ports.rtp = port,
                _ => return Err(format!("Unknown port service: {name}").into()),
            }
        }
    }
    ports.validate()?;
    Ok(ports)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::sync::atomic::AtomicUsize;
    static NEXT: AtomicUsize = AtomicUsize::new(0);

    #[test]
    fn wire_sink_preserves_preferences_and_orders_capabilities_before_restart() {
        let (tx, rx) = std::sync::mpsc::sync_channel(8);
        let sink = Sink {
            tx,
            state: Mutex::new(("default".into(), [0; 256])),
            clock: Instant::now(),
            shutdown: Arc::new(|| panic!("unexpected wire shutdown")),
            failed: Arc::new(AtomicBool::new(false)),
            log: EventLog::global().clone(),
        };
        for rate in (30..=60).step_by(5) {
            let preference = FrameRatePreference::from_wire(&format!("FPS_{rate}")).unwrap();
            sink.capabilities(OutputCapabilities {
                prefers_h264: rate == 35,
                latency: LatencyClass::Interactive,
                decoders: Vec::new(),
                displays: [(
                    0,
                    DisplayInfo {
                        frame_rate: preference,
                        sustained_fps: Some(55.0),
                        viewport: Some(Viewport {
                            width_px: 1533,
                            height_px: 907,
                        }),
                        fullscreen: Some(false),
                        fullscreen_preferred: Some(true),
                        ..DisplayInfo::default()
                    },
                )]
                .into(),
                touch: 0,
                keys: Default::default(),
                audio: Default::default(),
                microphone: false,
            });
            sink.request_restart(Origin {
                output_id: "web".into(),
                client_id: "browser".into(),
            });
            let Message::Json(caps) = rx.recv().unwrap() else {
                panic!("expected capabilities")
            };
            oxp_wire::validate_json(&caps).unwrap();
            assert_eq!(
                caps.get("prefersH264"),
                if rate == 35 { Some(&json!(true)) } else { None }
            );
            assert_eq!(caps["displays"]["0"]["frameRate"], format!("FPS_{rate}"));
            assert_eq!(caps["displays"]["0"]["sustainedFps"], 55.0);
            assert_eq!(
                caps["displays"]["0"]["viewport"],
                json!({"widthPx":1533,"heightPx":907})
            );
            assert_eq!(caps["displays"]["0"]["fullscreen"], false);
            assert_eq!(caps["displays"]["0"]["fullscreenPreferred"], true);
            let mut next = rx.recv().unwrap();
            if let Message::Json(v) = &next {
                if v["type"] == "client" {
                    next = rx.recv().unwrap();
                }
            }
            let Message::Json(restart) = next else {
                panic!("expected restart")
            };
            assert_eq!(restart, json!({"type":"restart"}));
        }
    }

    #[test]
    fn blocked_event_writer_cannot_stall_sink_send() {
        let (release, gate) = std::sync::mpsc::channel::<()>();
        let (started, writing) = std::sync::mpsc::channel();
        let lines = Arc::new(Mutex::new(Vec::new()));
        let written = lines.clone();
        // Stands in for a stderr whose reader stopped reading.
        let log = EventLog::spawn(8, move |v| {
            let _ = started.send(());
            let _ = gate.recv();
            written.lock().unwrap().push(v);
        })
        .unwrap();
        let (tx, rx) = std::sync::mpsc::sync_channel(4096);
        let sink = Sink {
            tx,
            state: Mutex::new(("default".into(), [0; 256])),
            clock: Instant::now(),
            shutdown: Arc::new(|| panic!("unexpected wire shutdown")),
            failed: Arc::new(AtomicBool::new(false)),
            log: log.clone(),
        };
        let keyframe = |n| json_message(json!({"type":"keyframe","screen":n}));
        sink.send(keyframe(0));
        writing.recv().unwrap();
        let start = Instant::now();
        for n in 1..1000 {
            sink.send(keyframe(n));
        }
        sink.control(ControlEvent::Touch {
            screen: 0,
            contacts: vec![TouchContact::new(0, 0.5, 0.25, true).unwrap()],
            origin: Origin {
                output_id: "web".into(),
                client_id: "browser".into(),
            },
        });
        assert!(
            start.elapsed() < Duration::from_secs(1),
            "a blocked event writer stalled send"
        );
        // Every message still reaches the wire: 1000 keyframes, the client switch and the touch.
        assert_eq!(1002, rx.try_iter().count());
        // Events: 1000 keyframes, the touch and the client switch; one in the writer, eight queued.
        assert_eq!(1002 - 9, log.dropped());
        drop(release);
        assert!(log.flush(Duration::from_secs(5)));
        let lines = lines.lock().unwrap();
        assert_eq!(10, lines.len());
        assert!((0..9).all(|n| lines[n] == json!({"type":"keyframe","screen":n})));
        assert_eq!(
            lines[9],
            json!({"type":"diagnostic","message":"event log dropped=993"})
        );
    }

    fn pin() -> Pin {
        Pin {
            digits: None,
            file: Some(
                std::env::temp_dir()
                    .join(format!(
                        "oxp-pin-{}-{}",
                        std::process::id(),
                        NEXT.fetch_add(1, Ordering::Relaxed)
                    ))
                    .to_string_lossy()
                    .into_owned(),
            ),
            stopped: Arc::new(AtomicBool::new(false)),
        }
    }
    #[test]
    fn pin_waits_for_new_file_and_deletes_it_after_read() {
        let pin = pin();
        let path = pin.file.as_ref().unwrap();
        std::fs::write(path, "stale").unwrap();
        let (tx, rx) = std::sync::mpsc::channel();
        let writer_path = path.clone();
        let writer = std::thread::spawn(move || {
            rx.recv().unwrap();
            assert!(!std::path::Path::new(&writer_path).exists());
            std::thread::sleep(Duration::from_millis(40));
            std::fs::write(writer_path, "001234\n").unwrap();
        });
        let events = RefCell::new(Vec::new());
        let result = pin.request_with("head-unit", Duration::from_secs(2), |v| {
            if v["type"] == "pin-request" {
                tx.send(()).unwrap();
            }
            events.borrow_mut().push(v);
        });
        writer.join().unwrap();
        assert_eq!(result.as_deref(), Some("001234"));
        assert!(!std::path::Path::new(path).exists());
        assert_eq!(
            *events.borrow(),
            vec![json!({"type":"pin-request","client":"head-unit"})]
        );
    }
    #[test]
    fn pin_timeout_emits_client_and_clears_stale_file() {
        let pin = pin();
        let path = pin.file.as_ref().unwrap();
        std::fs::write(path, "123456").unwrap();
        let events = RefCell::new(Vec::new());
        assert!(pin
            .request_with("unit", Duration::from_millis(30), |v| events
                .borrow_mut()
                .push(v))
            .is_none());
        assert!(!std::path::Path::new(path).exists());
        assert_eq!(
            *events.borrow(),
            vec![
                json!({"type":"pin-request","client":"unit"}),
                json!({"type":"pin-timeout","client":"unit"})
            ]
        );
    }
    #[test]
    fn pin_rejects_non_digits_and_deletes_file() {
        for value in ["", "12x4", "１２３４"] {
            let pin = pin();
            let path = pin.file.as_ref().unwrap();
            let events = RefCell::new(Vec::new());
            assert!(pin
                .request_with("unit", Duration::from_secs(1), |v| {
                    if v["type"] == "pin-request" {
                        std::fs::write(path, value).unwrap();
                    }
                    events.borrow_mut().push(v);
                })
                .is_none());
            assert!(!std::path::Path::new(path).exists());
            assert_eq!(
                events.borrow()[1],
                json!({"type":"error","message":"PIN must contain digits"})
            );
        }
    }
    #[test]
    fn pin_wait_is_interruptible_without_timeout_event() {
        let pin = pin();
        let events = RefCell::new(Vec::new());
        let start = Instant::now();
        assert!(pin
            .request_with("unit", Duration::from_secs(120), |v| {
                pin.stopped.store(true, Ordering::Release);
                events.borrow_mut().push(v);
            })
            .is_none());
        assert!(start.elapsed() < Duration::from_secs(1));
        assert_eq!(events.borrow().len(), 1);
    }
    #[test]
    fn explicit_pin_remains_supported() {
        let mut pin = pin();
        pin.file = None;
        pin.digits = Some("001234".into());
        assert_eq!(
            pin.request_with("unit", Duration::ZERO, |_| {}),
            Some("001234".into())
        );
    }
    #[test]
    fn transcoding_defaults_to_auto_and_wraps_with_diagnostics() {
        assert!(transcode_enabled(None).unwrap());
        assert!(transcode_enabled(Some("auto")).unwrap());
        assert!(!transcode_enabled(Some("off")).unwrap());
        assert!(transcode_enabled(Some("on")).is_err());
        struct Inner;
        impl oxp_core::ProjectionOutput for Inner {
            fn id(&self) -> &str {
                "clink"
            }
            fn open(&self, _: Arc<dyn OutputHost>) -> oxp_core::Result<()> {
                Ok(())
            }
            fn on_session(&self, _: &SessionState, _: &SessionConfig) {}
            fn on_video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {}
            fn on_video_frame(&self, _: VideoFrame) {}
            fn close(&self) {}
        }
        let lines = Arc::new(Mutex::new(Vec::<String>::new()));
        let log = lines.clone();
        let output = with_transcoding(
            Arc::new(Inner),
            Arc::new(oxp_transcode_linux::Unavailable("test host".into())),
            Arc::new(move |m: &str| log.lock().unwrap().push(m.into())),
        );
        assert_eq!(output.id(), "clink");
        assert_eq!(
            *lines.lock().unwrap(),
            vec!["video transcoder unavailable: test host".to_owned()]
        );
    }
    #[test]
    fn unsupported_band_reason_passes_through_unchanged() {
        assert_eq!(
            link_event(oxp_link_linux::LinkEvent::Lost {
                reason: "head_unit_band_unsupported:5180".into(),
            }),
            json!({"type":"link","state":"lost","reason":"head_unit_band_unsupported:5180"})
        );
    }
    #[test]
    fn link_events_have_only_contract_fields() {
        use oxp_link_linux::LinkEvent;
        let head = json!({"address":"AA:BB:CC:DD:EE:FF","name":"Head unit"});
        assert_eq!(
            link_event(LinkEvent::Scanning),
            json!({"type":"link","state":"scanning"})
        );
        assert_eq!(
            link_event(LinkEvent::Connecting {
                address: "AA:BB:CC:DD:EE:FF".into(),
                name: "Head unit".into()
            }),
            json!({"type":"link","state":"connecting","headUnit":head})
        );
        assert_eq!(
            link_event(LinkEvent::Joined {
                address: "AA:BB:CC:DD:EE:FF".into(),
                name: "Head unit".into(),
                ssid: "DIRECT-unit".into(),
                interface: "wlan0".into(),
                addresses: ClinkAddresses::new([192, 168, 49, 1].into(), [192, 168, 49, 2].into()),
            }),
            json!({"type":"link","state":"joined","headUnit":head,"network":{"ssid":"DIRECT-unit","interface":"wlan0"},"headUnitIp":"192.168.49.1","localIp":"192.168.49.2"})
        );
        assert_eq!(
            link_event(LinkEvent::Lost {
                reason: "network_link_lost".into()
            }),
            json!({"type":"link","state":"lost","reason":"network_link_lost"})
        );
    }
}
