// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use super::*;
use rustls_pki_types::{pem::PemObject, CertificateDer, ServerName};
use std::os::unix::net::UnixStream;
#[derive(Default)]
struct Host(Mutex<Vec<ControlEvent>>);
impl OutputHost for Host {
    fn capabilities(&self, _: OutputCapabilities) {}
    fn control(&self, e: ControlEvent) {
        self.0.lock().unwrap().push(e);
    }
    fn stats(&self, _: OutputStats) {}
}
/// Exercises the same TLS/WebSocket pump on a socket pair when a sandbox prohibits IP listeners.
#[test]
fn tls_socket_pair_protocol_pause_and_controls() {
    let root = std::env::temp_dir().join(format!("oxp-web-pair-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let tls = Tls::SelfSigned {
        state_dir: root.clone(),
        sans: Vec::new(),
    };
    let identity = tls::load(&tls).unwrap().unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(CertificateDer::from_pem_file(root.join("cert.pem")).unwrap())
        .unwrap();
    let client_config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        root.clone(),
        Pairing::Required("example-pairing-token".into()),
        tls,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    let host = Arc::new(Host::default());
    web.state.lock().unwrap().host = Some(host.clone());
    web.state
        .lock()
        .unwrap()
        .clients
        .insert("test".into(), Client::new(config.queue_duration_us));
    let run = Arc::new(Runtime {
        active: AtomicBool::new(true),
        bound: Mutex::new(None),
        listening: Condvar::new(),
        connections: AtomicUsize::new(0),
    });
    let (server, client) = UnixStream::pair().unwrap();
    server
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    client
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    let stream = rustls::StreamOwned::new(
        rustls::ServerConnection::new(identity.config).unwrap(),
        server,
    );
    let state = web.state.clone();
    let worker_run = run.clone();
    let worker = std::thread::spawn(move || {
        let mut stream = stream;
        let request = http::read(&mut stream).unwrap();
        assert_eq!(request.target, "/oxp/v0");
        http::upgrade(&request, &mut stream).unwrap();
        stream.sock.set_nonblocking(true).unwrap();
        stream.sock.set_read_timeout(None).unwrap();
        let mut ws = WebSocket::from_raw_socket(stream, Role::Server, None);
        client_loop(
            &mut ws,
            "test",
            &config,
            &state,
            &worker_run,
            &Mutex::new(HelloRateLimit::default()),
            "127.0.0.1".parse().unwrap(),
        )
        .unwrap();
    });
    let stream = rustls::StreamOwned::new(
        rustls::ClientConnection::new(
            Arc::new(client_config),
            ServerName::try_from("localhost").unwrap(),
        )
        .unwrap(),
        client,
    );
    let mut client = tungstenite::client("wss://localhost/oxp/v0", stream)
        .unwrap()
        .0;
    fn text<S: Read + Write>(ws: &mut WebSocket<S>, v: Value) {
        ws.send(Message::Text(v.to_string().into())).unwrap();
    }
    fn read<S: Read + Write>(ws: &mut WebSocket<S>, kind: &str) -> Value {
        let Message::Text(t) = ws.read().unwrap() else {
            panic!("Expected {kind}")
        };
        let v = json::parse(&t, Some(json::Direction::Relay)).unwrap();
        assert_eq!(v["type"], kind);
        v
    }
    text(&mut client, {
        let mut h: Value =
            serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
        h["audio"] = json!(["AAC_LC", "PCM_S16LE"]);
        h["microphone"] = json!(true);
        h
    });
    read(&mut client, "welcome");
    web.on_session(
        &SessionState {
            id: 42,
            phase: SessionPhase::Active,
            reason: None,
        },
        &SessionConfig::default(),
    );
    read(&mut client, "session");
    let bytes = include_str!("../../../spec/vectors/avc-baseline-640x480.hex")
        .split_whitespace()
        .map(|s| u8::from_str_radix(s, 16).unwrap())
        .collect::<Vec<_>>();
    web.on_video_format(
        0,
        Some(Arc::new(
            VideoFormat::create(0, VideoCodec::H264, bytes.into(), None).unwrap(),
        )),
    );
    read(&mut client, "video-format");
    let frame = |key, pts| VideoFrame {
        screen: 0,
        session: 42,
        data: Arc::from([0, 0, 0, 2, 0x65, 0x80]),
        pts_us: pts,
        key_frame: key,
        received_at_nanos: 1000,
    };
    web.on_video_frame(frame(false, 0));
    web.on_video_frame(frame(true, 10));
    let Message::Binary(b) = client.read().unwrap() else {
        panic!()
    };
    assert!(
        matches!(oxp_wire::decode(&b).unwrap(),oxp_wire::Message::Binary {header,..} if header.flags==3 && header.pts_us==10)
    );
    let touch = oxp_wire::Message::Binary {
        header: Header {
            kind: Kind::Touch,
            stream: 0,
            flags: 0,
            sequence: 0,
            pts_us: 0,
            sender_clock_us: 0,
        },
        payload: oxp_wire::encode_touch(&[oxp_wire::Contact {
            id: 0,
            x: 0.5,
            y: 0.5,
            pressed: true,
        }])
        .unwrap(),
    };
    client
        .send(Message::Binary(oxp_wire::encode(&touch).unwrap().into()))
        .unwrap();
    text(
        &mut client,
        json!({"type":"pause","screen":0,"paused":true}),
    );
    text(&mut client, json!({"type":"ping","t0Us":"9"}));
    let pong = read(&mut client, "pong");
    assert_eq!(pong["t0Us"], "9");
    assert!(
        pong["t2Us"].as_str().unwrap().parse::<i64>().unwrap()
            >= pong["t1Us"].as_str().unwrap().parse::<i64>().unwrap()
    );
    web.on_video_frame(frame(true, 20));
    text(&mut client, json!({"type":"ping","t0Us":"10"}));
    read(&mut client, "pong");
    let audio = Arc::new(AudioFormat {
        stream: 1,
        role: AudioRole::Voice,
        codec: AudioCodec::PcmS16le,
        sample_rate: 16000,
        channels: 1,
        config: None,
    });
    web.on_audio_format(1, Some(audio));
    read(&mut client, "audio-format");
    web.on_audio_packet(AudioPacket {
        stream: 1,
        session: 42,
        data: vec![7; 640].into(),
        pts_us: 1234,
    });
    web.on_audio_format(1, None);
    let Message::Binary(b) = client.read().unwrap() else {
        panic!("Missing audio while paused")
    };
    assert!(
        matches!(oxp_wire::decode(&b).unwrap(),oxp_wire::Message::Binary {header,payload} if header.kind==Kind::Audio && header.pts_us==1234 && payload==vec![7;640])
    );
    assert_eq!(read(&mut client, "audio-format")["format"], Value::Null);
    web.on_microphone(
        2,
        Some(MicRequest {
            stream: 2,
            codec: AudioCodec::PcmS16le,
            sample_rate: 16000,
            channels: 1,
            frame_ms: 20,
        }),
    );
    read(&mut client, "mic");
    let mic = oxp_wire::Message::Binary {
        header: Header {
            kind: Kind::Microphone,
            stream: 2,
            flags: 0,
            sequence: 0,
            pts_us: 9876,
            sender_clock_us: 0,
        },
        payload: vec![9; 640],
    };
    client
        .send(Message::Binary(oxp_wire::encode(&mic).unwrap().into()))
        .unwrap();
    text(&mut client, json!({"type":"ping","t0Us":"11"}));
    read(&mut client, "pong");
    assert!(host.0.lock().unwrap().iter().any(|e|matches!(e,ControlEvent::MicPacket {stream:2,pts_us:9876,data,..} if data.as_ref()==vec![9;640])));
    text(
        &mut client,
        json!({"type":"pause","screen":0,"paused":false}),
    );
    read(&mut client, "video-format");
    web.on_video_frame(frame(false, 30));
    web.on_video_frame(frame(true, 40));
    let Message::Binary(b) = client.read().unwrap() else {
        panic!()
    };
    assert!(
        matches!(oxp_wire::decode(&b).unwrap(),oxp_wire::Message::Binary {header,..} if header.flags==3 && header.pts_us==40)
    );
    assert!(host
        .0
        .lock()
        .unwrap()
        .iter()
        .any(|e| matches!(e,ControlEvent::Touch{contacts,..} if contacts.len()==1)));
    run.active.store(false, Ordering::Release);
    worker.join().unwrap();
    web.close();
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn audio_queue_bounds_generations_codec_filter_and_late_join() {
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        std::env::temp_dir(),
        Pairing::Required("token".into()),
        Tls::Disabled,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    web.state.lock().unwrap().host = Some(Arc::new(Host::default()));
    let state = SessionState {
        id: 1,
        phase: SessionPhase::Active,
        reason: None,
    };
    web.on_session(&state, &SessionConfig::default());
    let f = Arc::new(AudioFormat {
        stream: 0,
        role: AudioRole::Media,
        codec: AudioCodec::AacLc,
        sample_rate: 48000,
        channels: 2,
        config: Some(Arc::from([0x11, 0x90])),
    });
    web.on_audio_format(0, Some(f.clone()));
    web.on_microphone(
        2,
        Some(MicRequest {
            stream: 2,
            codec: AudioCodec::PcmS16le,
            sample_rate: 16000,
            channels: 1,
            frame_ms: 20,
        }),
    );
    web.state
        .lock()
        .unwrap()
        .clients
        .insert("late".into(), Client::new(config.queue_duration_us));
    let mut hello: Value =
        serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
    hello["audio"] = json!(["AAC_LC"]);
    hello["microphone"] = json!(true);
    handle(hello.clone(), "late", &config, &web.state, 0).unwrap();
    assert_eq!(
        web.state.lock().unwrap().clients["late"]
            .messages
            .iter()
            .map(|v| v["type"].as_str().unwrap())
            .collect::<Vec<_>>(),
        ["welcome", "audio-format", "mic"]
    );
    for n in 0..100 {
        web.on_audio_packet(AudioPacket {
            stream: 0,
            session: 1,
            data: Arc::from([1, 2, 3]),
            pts_us: n * 21333,
        });
    }
    let mut count = 0;
    {
        let mut s = web.state.lock().unwrap();
        let c = s.clients.get_mut("late").unwrap();
        while let Some((_, _)) = c.audio.poll() {
            count += 1;
        }
    }
    // 1.5 s of 1024-sample packets at 48 kHz.
    assert_eq!(count, 70);
    hello["audio"] = json!([]);
    hello["microphone"] = json!(false);
    handle(hello, "late", &config, &web.state, 0).unwrap();
    let s = web.state.lock().unwrap();
    let c = &s.clients["late"];
    assert_eq!(c.messages.back().unwrap()["request"], Value::Null);
    assert!(!c.supports(AudioCodec::AacLc));
    drop(s);
    let r = MicRequest {
        stream: 2,
        codec: AudioCodec::PcmS16le,
        sample_rate: 16000,
        channels: 1,
        frame_ms: 20,
    };
    assert!(valid_mic_packet(&r, 640));
    assert!(valid_mic_packet(&r, 1280));
    assert!(!valid_mic_packet(&r, 639));
    assert!(!valid_mic_packet(&r, 0));
}

#[test]
fn packet_then_immediate_null_and_replacement_keep_order() {
    let mut q = AudioQueue::default();
    let f = AudioFormat {
        stream: 1,
        role: AudioRole::Voice,
        codec: AudioCodec::PcmS16le,
        sample_rate: 16000,
        channels: 1,
        config: None,
    };
    let packet = |pts| AudioPacket {
        stream: 1,
        session: 1,
        data: vec![7; 640].into(),
        pts_us: pts,
    };
    let start = json::audio(1, Some(&f));
    let replacement = json::audio(
        1,
        Some(&AudioFormat {
            sample_rate: 8000,
            ..f.clone()
        }),
    );
    let end = json::audio(1, None);
    assert!(q.format(start.clone()));
    q.offer(packet(0), &f);
    assert!(q.format(replacement.clone()));
    q.offer(packet(20_000), &f);
    assert!(q.format(end.clone()));
    assert!(q.poll().is_none());
    assert_eq!(q.poll_format(), Some(start));
    assert_eq!(q.poll().unwrap().0.pts_us, 0);
    assert_eq!(q.poll_format(), Some(replacement));
    assert_eq!(q.poll().unwrap().0.pts_us, 20_000);
    assert_eq!(q.poll_format(), Some(end));
    assert!(q.poll().is_none());
    for n in 0..25 {
        q.offer(packet(n * 20_000), &f);
    }
    assert!(q.format(json::audio(1, None)));
    assert!(q.format(json::audio(1, Some(&f))));
    q.offer(packet(0), &f);
    for n in 0..25 {
        assert_eq!(q.poll().unwrap().0.pts_us, n * 20_000);
    }
    assert_eq!(q.poll_format(), Some(json::audio(1, None)));
    assert_eq!(q.poll_format(), Some(json::audio(1, Some(&f))));
    assert_eq!(q.poll().unwrap().0.pts_us, 0);
    assert!(q.poll().is_none());
}

#[test]
fn failed_hellos_close_with_policy_and_block_the_remote_ip() {
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        std::env::temp_dir(),
        Pairing::Required("00123456".into()),
        Tls::Disabled,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    let limit = web.hello_rate_limit.clone();
    let run = Arc::new(Runtime {
        active: AtomicBool::new(true),
        bound: Mutex::new(None),
        listening: Condvar::new(),
        connections: AtomicUsize::new(0),
    });
    // Socket pairs exercise authentication without requiring sandbox TCP binding permissions.
    for attempt in 0..7 {
        let id = format!("attempt-{attempt}");
        web.state
            .lock()
            .unwrap()
            .clients
            .insert(id.clone(), Client::new(config.queue_duration_us));
        let (server, client) = UnixStream::pair().unwrap();
        server.set_nonblocking(true).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let state = web.state.clone();
        let limit = limit.clone();
        let config = config.clone();
        let run = run.clone();
        let address = if attempt == 6 {
            "127.0.0.2"
        } else {
            "127.0.0.1"
        }
        .parse()
        .unwrap();
        let worker = std::thread::spawn(move || {
            let mut ws = WebSocket::from_raw_socket(server, Role::Server, None);
            client_loop(&mut ws, &id, &config, &state, &run, &limit, address).unwrap();
        });
        let mut ws = WebSocket::from_raw_socket(client, Role::Client, None);
        let mut hello: Value =
            serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
        hello["token"] = json!(if attempt < 5 { "wrong" } else { "00123456" });
        ws.send(Message::Text(hello.to_string().into())).unwrap();
        if attempt < 6 {
            assert!(
                matches!(ws.read().unwrap(), Message::Close(Some(c)) if c.code == CloseCode::Policy)
            );
        } else {
            let Message::Text(v) = ws.read().unwrap() else {
                panic!("Expected welcome for another address")
            };
            assert_eq!(
                json::parse(&v, Some(json::Direction::Relay)).unwrap()["type"],
                "welcome"
            );
            ws.close(None).unwrap();
        }
        worker.join().unwrap();
    }
}

#[test]
fn disabled_pairing_accepts_empty_and_arbitrary_tokens_even_for_a_blocked_ip() {
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        std::env::temp_dir(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    let limit = web.hello_rate_limit.clone();
    let run = Arc::new(Runtime {
        active: AtomicBool::new(true),
        bound: Mutex::new(None),
        listening: Condvar::new(),
        connections: AtomicUsize::new(0),
    });
    // Socket pairs exercise authentication without requiring sandbox TCP binding permissions.
    let address = "127.0.0.1".parse().unwrap();
    for _ in 0..5 {
        assert!(!limit
            .lock()
            .unwrap()
            .allow(address, Instant::now(), || false));
    }
    for attempt in 0..2 {
        let id = format!("attempt-{attempt}");
        web.state
            .lock()
            .unwrap()
            .clients
            .insert(id.clone(), Client::new(config.queue_duration_us));
        let (server, client) = UnixStream::pair().unwrap();
        server.set_nonblocking(true).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let state = web.state.clone();
        let limit = limit.clone();
        let config = config.clone();
        let worker_id = id.clone();
        let run = run.clone();
        let worker = std::thread::spawn(move || {
            let mut ws = WebSocket::from_raw_socket(server, Role::Server, None);
            client_loop(&mut ws, &worker_id, &config, &state, &run, &limit, address).unwrap();
        });
        let mut ws = WebSocket::from_raw_socket(client, Role::Client, None);
        let mut hello: Value =
            serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
        hello["token"] = json!(if attempt == 0 { "" } else { "arbitrary-code" });
        ws.send(Message::Text(hello.to_string().into())).unwrap();
        let Message::Text(v) = ws.read().unwrap() else {
            panic!("Expected welcome without pairing")
        };
        assert_eq!(
            json::parse(&v, Some(json::Direction::Relay)).unwrap()["type"],
            "welcome"
        );
        assert!(web.state.lock().unwrap().clients[&id]
            .hello
            .as_ref()
            .unwrap()
            .get("token")
            .is_none());
        ws.close(None).unwrap();
        worker.join().unwrap();
    }
}

#[test]
fn required_pairing_rejects_an_empty_code() {
    assert!(WebOutput::new(WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        std::env::temp_dir(),
        Pairing::Required(String::new()),
        Tls::Disabled,
    ))
    .is_err());
}

#[test]
fn receiver_diagnostics_are_optional_bounded_and_forwarded_once() {
    let reports = Arc::new(Mutex::new(Vec::new()));
    let captured = reports.clone();
    let mut config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        PathBuf::new(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    config.diagnostics = Some(Arc::new(move |id, value| {
        captured
            .lock()
            .unwrap()
            .push((id.to_owned(), value.clone()));
    }));
    let web = WebOutput::new(config.clone()).unwrap();
    web.state
        .lock()
        .unwrap()
        .clients
        .insert("test".into(), Client::new(config.queue_duration_us));
    let parse = |text| json::parse(text, Some(json::Direction::Client)).unwrap();
    handle(
        parse(include_str!("../../web-receiver/spec/vectors/web/hello.json")),
        "test",
        &config,
        &web.state,
        0,
    )
    .unwrap();
    // A receiver can report stats and request frames without ever sending receiver-info.
    handle(
        parse(include_str!("../../web-receiver/spec/vectors/web/stats.json")),
        "test",
        &config,
        &web.state,
        0,
    )
    .unwrap();
    handle(
        json!({"type":"keyframe","screen":0}),
        "test",
        &config,
        &web.state,
        0,
    )
    .unwrap();
    assert!(web.state.lock().unwrap().clients["test"]
        .receiver_info
        .is_none());
    let info = parse(include_str!("../../web-receiver/spec/vectors/web/receiver-info.json"));
    handle(info.clone(), "test", &config, &web.state, 0).unwrap();
    handle(info.clone(), "test", &config, &web.state, 0).unwrap();
    let mut stats = parse(include_str!(
        "../../web-receiver/spec/vectors/web/stats-diagnostics.json"
    ));
    stats["futureMetric"] = json!({"supported":true});
    json::validate(&stats, Some(json::Direction::Client)).unwrap();
    handle(stats.clone(), "test", &config, &web.state, 0).unwrap();
    let reports = reports.lock().unwrap();
    assert_eq!(reports.len(), 3);
    assert_eq!(reports[1], ("test".into(), info.clone()));
    stats["relayDroppedFrames"] = json!(0);
    assert_eq!(reports[2], ("test".into(), stats));
    assert_eq!(
        web.state.lock().unwrap().clients["test"].receiver_info,
        Some(info)
    );
    json::validate(
        &json!({"type":"receiver-info"}),
        Some(json::Direction::Client),
    )
    .unwrap();
    assert!(json::validate(
        &json!({"type":"receiver-info"}),
        Some(json::Direction::Relay)
    )
    .is_err());
    assert!(json::validate(
        &json!({"type":"receiver-info","userAgent":"字".repeat(22000)}),
        None
    )
    .is_err());
    assert!(json::validate(&json!({"type":"stats","wsBytesPerSecond":-1}), None).is_err());
}

#[test]
fn preferences_reach_the_host_and_only_primary_can_restart_with_a_cooldown() {
    #[derive(Default)]
    struct RecordingHost {
        caps: Mutex<Vec<OutputCapabilities>>,
        restarts: Mutex<Vec<Origin>>,
    }
    impl OutputHost for RecordingHost {
        fn capabilities(&self, c: OutputCapabilities) {
            self.caps.lock().unwrap().push(c);
        }
        fn request_restart(&self, origin: Origin) {
            self.restarts.lock().unwrap().push(origin);
        }
        fn control(&self, _: ControlEvent) {}
        fn stats(&self, _: OutputStats) {}
    }
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        PathBuf::new(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    let host = Arc::new(RecordingHost::default());
    web.state.lock().unwrap().host = Some(host.clone());
    for id in ["a", "b"] {
        web.state
            .lock()
            .unwrap()
            .clients
            .insert(id.into(), Client::new(config.queue_duration_us));
    }
    let mut hello: Value =
        serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
    for rate in (30..=60).step_by(5) {
        hello["displays"][0]["frameRate"] = json!(format!("FPS_{rate}"));
        handle(hello.clone(), "a", &config, &web.state, 0).unwrap();
        let caps = host.caps.lock().unwrap();
        assert_eq!(
            caps.last().unwrap().displays[&0].frame_rate.wire_name(),
            format!("FPS_{rate}")
        );
    }
    hello["displays"][0]["frameRate"] = json!("FPS_35");
    handle(hello, "b", &config, &web.state, 0).unwrap();
    assert_eq!(
        host.caps.lock().unwrap().last().unwrap().displays[&0].frame_rate,
        FrameRatePreference::Fps60
    );
    handle(json!({"type":"restart"}), "b", &config, &web.state, 0).unwrap();
    assert!(host.restarts.lock().unwrap().is_empty());
    for _ in 0..2 {
        handle(json!({"type":"restart"}), "a", &config, &web.state, 0).unwrap();
    }
    assert_eq!(host.restarts.lock().unwrap().len(), 1);
    web.state.lock().unwrap().last_restart = Some(Instant::now() - Duration::from_secs(10));
    handle(json!({"type":"restart"}), "a", &config, &web.state, 0).unwrap();
    assert_eq!(host.restarts.lock().unwrap().len(), 2);
}

#[test]
fn hevc_only_reaches_capable_clients_and_mixed_clients_force_avc() {
    struct CapsHost(Mutex<Vec<OutputCapabilities>>);
    impl OutputHost for CapsHost {
        fn capabilities(&self, caps: OutputCapabilities) {
            self.0.lock().unwrap().push(caps);
        }
        fn control(&self, _: ControlEvent) {}
        fn stats(&self, _: OutputStats) {}
    }
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        PathBuf::new(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    let host = Arc::new(CapsHost(Mutex::new(Vec::new())));
    web.state.lock().unwrap().host = Some(host.clone());
    let bytes: Vec<u8> = include_str!("../../../spec/vectors/hevc-main-1920x1080.hex")
        .split_whitespace()
        .map(|v| u8::from_str_radix(v, 16).unwrap())
        .collect();
    let format = Arc::new(VideoFormat::create(0, VideoCodec::H265, bytes.into(), None).unwrap());
    web.on_session(
        &SessionState {
            id: 1,
            phase: SessionPhase::Active,
            reason: None,
        },
        &SessionConfig {
            screens: BTreeMap::new(),
        },
    );
    web.on_video_format(0, Some(format.clone()));
    for (id, codec, hardware) in [
        ("a", "hvc1.1.6.L120.90", true),
        ("b", "hev1.1.6.L120.90", true),
        ("c", "avc1.42E01F", true),
    ] {
        web.state
            .lock()
            .unwrap()
            .clients
            .insert(id.into(), Client::new(config.queue_duration_us));
        let mut hello: Value =
            serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
        hello["decoders"] = json!([{"codecString":codec,"hardware":hardware}]);
        handle(hello, id, &config, &web.state, 0).unwrap();
        let caps = host.0.lock().unwrap();
        assert_eq!(
            caps.last()
                .unwrap()
                .decoders
                .iter()
                .any(|d| d.hardware && oxp_core::video_codec_policy::is_hevc(&d.codec_string)),
            id != "c"
        );
    }
    web.on_video_frame(VideoFrame {
        screen: 0,
        session: 1,
        data: Arc::from([0, 0, 0, 1, 0x26]),
        pts_us: 0,
        key_frame: true,
        received_at_nanos: 0,
    });
    let s = web.state.lock().unwrap();
    for (id, capable) in [("a", true), ("b", true), ("c", false)] {
        let client = &s.clients[id];
        let declaration = client
            .messages
            .iter()
            .find(|m| m["type"] == "video-format")
            .unwrap();
        assert_eq!(!declaration["format"].is_null(), capable);
        assert_eq!(client.queues.contains_key(&0), capable);
        if capable {
            assert_eq!(declaration["format"]["codecString"], format.codec_string);
        }
    }
    drop(s);
    let mut hello: Value =
        serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
    hello["decoders"] = json!([{"codecString":"hvc1.1.6.L120.90","hardware":false}]);
    handle(hello.clone(), "c", &config, &web.state, 0).unwrap();
    assert!(!web.state.lock().unwrap().clients["c"]
        .messages
        .back()
        .unwrap()["format"]
        .is_null());
    assert!(!host
        .0
        .lock()
        .unwrap()
        .last()
        .unwrap()
        .decoders
        .iter()
        .any(|d| d.hardware && oxp_core::video_codec_policy::is_hevc(&d.codec_string)));
    hello["decoders"] = json!([]);
    handle(hello, "a", &config, &web.state, 0).unwrap();
    let s = web.state.lock().unwrap();
    assert!(s.clients["a"].messages.back().unwrap()["format"].is_null());
    assert!(!s.clients["a"].queues.contains_key(&0));
}

fn avc_format() -> Arc<VideoFormat> {
    let bytes = include_str!("../../../spec/vectors/avc-baseline-640x480.hex")
        .split_whitespace()
        .map(|s| u8::from_str_radix(s, 16).unwrap())
        .collect::<Vec<_>>();
    Arc::new(VideoFormat::create(0, VideoCodec::H264, bytes.into(), None).unwrap())
}
fn hello_with(playback: Option<&str>) -> Value {
    let mut hello: Value =
        serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/hello.json")).unwrap();
    if let Some(mode) = playback {
        hello["playback"] = json!(mode);
    }
    hello
}

#[test]
fn playback_sets_each_clients_queue_span_and_a_repeated_hello_keeps_history() {
    assert_eq!(playback_queue_duration_us(Some("SPEED"), 100_000), 50_000);
    assert_eq!(playback_queue_duration_us(Some("SMART"), 100_000), 100_000);
    assert_eq!(playback_queue_duration_us(None, 100_000), 100_000);
    assert_eq!(
        playback_queue_duration_us(Some("QUALITY"), 100_000),
        300_000
    );
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        PathBuf::new(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    let web = WebOutput::new(config.clone()).unwrap();
    web.state.lock().unwrap().host = Some(Arc::new(Host::default()));
    web.on_session(
        &SessionState {
            id: 1,
            phase: SessionPhase::Active,
            reason: None,
        },
        &SessionConfig::default(),
    );
    web.on_video_format(0, Some(avc_format()));
    for (id, mode) in [
        ("speed", Some("SPEED")),
        ("smart", None),
        ("quality", Some("QUALITY")),
    ] {
        web.state
            .lock()
            .unwrap()
            .clients
            .insert(id.into(), Client::new(config.queue_duration_us));
        handle(hello_with(mode), id, &config, &web.state, 0).unwrap();
    }
    for (key, pts) in [
        (true, 0),
        (false, 40_000),
        (true, 60_000),
        (false, 90_000),
        (false, 120_000),
    ] {
        web.on_video_frame(VideoFrame {
            screen: 0,
            session: 1,
            data: Arc::from([0, 0, 0, 2, 0x65, 0x80]),
            pts_us: pts,
            key_frame: key,
            received_at_nanos: 0,
        });
    }
    {
        let s = web.state.lock().unwrap();
        // Speed (50 ms) loses everything up to the next key frame twice, Smart (100 ms) once.
        for (id, span, queued, dropped, request) in [
            ("speed", 50_000, 0, 5, true),
            ("smart", 100_000, 3, 2, true),
            ("quality", 300_000, 5, 0, false),
        ] {
            let c = &s.clients[id];
            assert_eq!(c.queues[&0].max_duration_us(), span, "{id}");
            assert_eq!(c.queues[&0].len(), queued, "{id}");
            assert_eq!(c.dropped_frames, dropped, "{id}");
            assert_eq!(c.keyframe_requests.contains(&0), request, "{id}");
            // Recovery requests no longer queue behind other messages for the socket.
            assert!(!c.messages.iter().any(|m| m["type"] == "request-keyframe"));
        }
    }
    handle(hello_with(Some("SPEED")), "smart", &config, &web.state, 0).unwrap();
    let s = web.state.lock().unwrap();
    assert_eq!(s.clients["smart"].queue_duration_us, 50_000);
    assert_eq!(s.clients["smart"].queues[&0].max_duration_us(), 50_000);
    assert_eq!(s.clients["smart"].queues[&0].len(), 3);
}

/// A socket whose peer stopped reading: every write would block, like a full TCP send buffer.
struct Pipe {
    written: Arc<Mutex<Vec<u8>>>,
    open: bool,
}
impl Read for Pipe {
    fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
        Err(io::ErrorKind::WouldBlock.into())
    }
}
impl Write for Pipe {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        if !self.open {
            return Err(io::ErrorKind::WouldBlock.into());
        }
        self.written.lock().unwrap().extend_from_slice(b);
        Ok(b.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
/// Unmasked server frames as (opcode, payload).
fn server_frames(mut bytes: &[u8]) -> Vec<(u8, Vec<u8>)> {
    let mut frames = Vec::new();
    while bytes.len() >= 2 {
        let opcode = bytes[0] & 15;
        let (len, header) = match bytes[1] & 127 {
            126 if bytes.len() >= 4 => (u16::from_be_bytes([bytes[2], bytes[3]]) as usize, 4),
            127 if bytes.len() >= 10 => (
                u64::from_be_bytes(bytes[2..10].try_into().unwrap()) as usize,
                10,
            ),
            126 | 127 => break,
            n => (n as usize, 2),
        };
        if bytes.len() < header + len {
            break;
        }
        frames.push((opcode, bytes[header..header + len].to_vec()));
        bytes = &bytes[header + len..];
    }
    frames
}

#[test]
fn slow_client_drops_its_own_frames_without_blocking_the_producer_or_other_clients() {
    #[derive(Default)]
    struct Requests(Mutex<Vec<String>>);
    impl OutputHost for Requests {
        fn capabilities(&self, _: OutputCapabilities) {}
        fn control(&self, e: ControlEvent) {
            if let ControlEvent::KeyFrameRequest { origin, .. } = e {
                self.0.lock().unwrap().push(origin.client_id);
            }
        }
        fn stats(&self, _: OutputStats) {}
    }
    let reports = Arc::new(Mutex::new(Vec::new()));
    let captured = reports.clone();
    let mut config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        PathBuf::new(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    config.diagnostics = Some(Arc::new(move |id, value| {
        captured
            .lock()
            .unwrap()
            .push((id.to_owned(), value.clone()));
    }));
    let web = WebOutput::new(config.clone()).unwrap();
    let host = Arc::new(Requests::default());
    web.state.lock().unwrap().host = Some(host.clone());
    web.on_session(
        &SessionState {
            id: 7,
            phase: SessionPhase::Active,
            reason: None,
        },
        &SessionConfig::default(),
    );
    web.on_video_format(0, Some(avc_format()));
    let run = Arc::new(Runtime {
        active: AtomicBool::new(true),
        bound: Mutex::new(None),
        listening: Condvar::new(),
        connections: AtomicUsize::new(0),
    });
    let fast = Arc::new(Mutex::new(Vec::new()));
    let mut workers = Vec::new();
    for (id, mode, open) in [("slow", "SPEED", false), ("fast", "QUALITY", true)] {
        web.state
            .lock()
            .unwrap()
            .clients
            .insert(id.into(), Client::new(config.queue_duration_us));
        handle(hello_with(Some(mode)), id, &config, &web.state, 0).unwrap();
        let written = if open {
            fast.clone()
        } else {
            Arc::new(Mutex::new(Vec::new()))
        };
        let (state, run, config) = (web.state.clone(), run.clone(), config.clone());
        workers.push(std::thread::spawn(move || {
            let mut ws = WebSocket::from_raw_socket(Pipe { written, open }, Role::Server, None);
            client_loop(
                &mut ws,
                id,
                &config,
                &state,
                &run,
                &Mutex::new(HelloRateLimit::default()),
                "127.0.0.1".parse().unwrap(),
            )
            .unwrap();
        }));
    }
    // 60 fps timestamps at eight times real time, with a key frame every 30 frames.
    let frames = 120;
    let mut slowest = Duration::ZERO;
    for i in 0..frames {
        let started = Instant::now();
        web.on_video_frame(VideoFrame {
            screen: 0,
            session: 7,
            data: vec![i as u8; 1200].into(),
            pts_us: i as i64 * 16_667,
            key_frame: i % 30 == 0,
            received_at_nanos: 0,
        });
        slowest = slowest.max(started.elapsed());
        std::thread::sleep(Duration::from_millis(2));
    }
    assert!(
        slowest < Duration::from_millis(50),
        "producer blocked for {slowest:?}"
    );
    let deadline = Instant::now() + Duration::from_secs(5);
    let video = loop {
        let video: Vec<(i64, u8)> = server_frames(&fast.lock().unwrap())
            .into_iter()
            .filter(|(opcode, _)| *opcode == 2)
            .map(|(_, payload)| match oxp_wire::decode(&payload).unwrap() {
                oxp_wire::Message::Binary { header, .. } => (header.pts_us, header.flags),
                _ => panic!("Expected binary video"),
            })
            .collect();
        if video.len() >= frames || Instant::now() > deadline {
            break video;
        }
        std::thread::sleep(Duration::from_millis(5));
    };
    // The other client receives every frame in order, without a gap after the first.
    assert_eq!(
        video.iter().map(|v| v.0).collect::<Vec<_>>(),
        (0..frames as i64).map(|i| i * 16_667).collect::<Vec<_>>()
    );
    assert!(video[1..].iter().all(|v| v.1 & 2 == 0));
    let deadline = Instant::now() + Duration::from_secs(5);
    while host.0.lock().unwrap().len() < frames / 30 && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
    {
        let s = web.state.lock().unwrap();
        let slow = &s.clients["slow"];
        assert!(
            slow.dropped_frames >= frames as u64 / 2,
            "{}",
            slow.dropped_frames
        );
        assert!(slow.queues[&0].duration_us() <= 50_000);
        assert_eq!(s.clients["fast"].dropped_frames, 0);
    }
    // One request per gap: the stalled client recovers at each key frame and overflows again.
    let requests = host.0.lock().unwrap().clone();
    assert_eq!(requests, vec!["slow".to_owned(); frames / 30]);
    let stats: Value =
        serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/stats.json")).unwrap();
    handle(stats, "slow", &config, &web.state, 0).unwrap();
    let dropped = web.state.lock().unwrap().clients["slow"].dropped_frames;
    assert_eq!(
        reports.lock().unwrap().last().unwrap().1["relayDroppedFrames"],
        json!(dropped)
    );
    run.active.store(false, Ordering::Release);
    for worker in workers {
        worker.join().unwrap();
    }
}

/// A TCP client that reads at most `bytes_per_second` until `fast` is set, like a browser behind a
/// slow link.
struct SlowReader {
    socket: TcpStream,
    bytes_per_second: f64,
    next_read: Instant,
    fast: bool,
    /// When the last socket read returned.
    returned: Instant,
    /// Each time the link stood still because the reading thread ran late, as (monotonic
    /// nanoseconds when it resumed, nanoseconds stalled). The link may go on at `next_read`, or
    /// once a read that waited for data returns; a later read means the thread was descheduled
    /// or still busy with earlier messages, which delays the relay's writes but is not its doing.
    stalls: Vec<(i64, i64)>,
}
impl SlowReader {
    fn new(socket: TcpStream, bytes_per_second: f64) -> Self {
        let now = Instant::now();
        Self {
            socket,
            bytes_per_second,
            next_read: now,
            fast: false,
            returned: now,
            stalls: Vec::new(),
        }
    }
}
impl Read for SlowReader {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        if !self.fast {
            if let Some(wait) = self.next_read.checked_duration_since(Instant::now()) {
                std::thread::sleep(wait);
            }
        }
        let started = Instant::now();
        let stalled = started.saturating_duration_since(self.next_read.max(self.returned));
        if !self.fast && !stalled.is_zero() {
            self.stalls
                .push((oxp_core::monotonic_nanos(), stalled.as_nanos() as i64));
        }
        let limit = b.len().min(2048);
        let read = self.socket.read(&mut b[..limit]);
        self.returned = Instant::now();
        let n = read?;
        self.next_read =
            self.next_read.max(started) + Duration::from_secs_f64(n as f64 / self.bytes_per_second);
        Ok(n)
    }
}
impl Write for SlowReader {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        self.socket.write(b)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.socket.flush()
    }
}

/// The kernel no longer hides a slow browser's backlog. The relay's share of a frame's latency is
/// the time from its offer until its last byte was written to the socket, plus the time the bytes
/// then unsent in the kernel take to drain over the link. It stays within the Smart queue budget plus
/// the frame's own transmission time and a small constant for the bounded kernel buffer. A 200 KB key
/// frame is still written completely, every frame the relay started arrives intact, and the stream
/// recovers at a later key frame. Time the test's threads measurably lose to a busy scheduler
/// during a frame's write extends that frame's bound, so a shared runner does not fail it.
///
/// Delivery times at the slow reader are not asserted: a slowly reading Darwin receiver withholds
/// window updates while it holds unread data, which stalls any sender for hundreds of milliseconds.
/// Head units run Linux or Android, which do not.
#[test]
fn slow_reader_latency_stays_within_the_queue_budget_and_a_large_key_frame_arrives_intact() {
    /// Bytes per second: a congested 4 Mbit/s link.
    const RATE: f64 = 500_000.0;
    const FRAMES: i64 = 180;
    const INTERVAL_US: i64 = 16_667;
    const LARGE_KEY: usize = 200 * 1024;
    let payload = |index: i64, size: usize| -> Vec<u8> {
        (0..size)
            .map(|j| (index as usize * 31 + j * 7) as u8)
            .collect()
    };
    // 60 fps at 900 KB/s, almost twice the link rate, with a key frame every 30 frames.
    let shape = |index: i64| match index {
        0 => (true, LARGE_KEY),
        i if i % 30 == 0 => (true, 30 * 1024),
        _ => (false, 15 * 1024),
    };
    let root = std::env::temp_dir().join(format!("oxp-web-slow-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let tls = Tls::SelfSigned {
        state_dir: root.clone(),
        sans: Vec::new(),
    };
    tls::load(&tls).unwrap().unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(CertificateDer::from_pem_file(root.join("cert.pem")).unwrap())
        .unwrap();
    let client_config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    let reports = Arc::new(Mutex::new(Vec::new()));
    let captured = reports.clone();
    let mut config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        root.clone(),
        Pairing::Disabled,
        tls,
    );
    config.diagnostics = Some(Arc::new(move |_, value| {
        captured.lock().unwrap().push(value.clone());
    }));
    let web = Arc::new(WebOutput::new(config.clone()).unwrap());
    web.open(Arc::new(Host::default())).unwrap();
    let address = web.await_listening(Duration::from_secs(5)).unwrap();
    web.on_session(
        &SessionState {
            id: 42,
            phase: SessionPhase::Active,
            reason: None,
        },
        &SessionConfig::default(),
    );
    web.on_video_format(0, Some(avc_format()));
    let socket = TcpStream::connect(address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    let reader = SlowReader::new(socket, RATE);
    let stream = rustls::StreamOwned::new(
        rustls::ClientConnection::new(
            Arc::new(client_config),
            ServerName::try_from("localhost").unwrap(),
        )
        .unwrap(),
        reader,
    );
    let mut ws = tungstenite::client(format!("wss://localhost:{}/oxp/v0", address.port()), stream)
        .unwrap()
        .0;
    ws.send(Message::Text(hello_with(None).to_string().into()))
        .unwrap();
    let mut id = String::new();
    for kind in ["welcome", "video-format"] {
        let Message::Text(t) = ws.read().unwrap() else {
            panic!("Expected {kind}")
        };
        let v = json::parse(&t, None).unwrap();
        assert_eq!(v["type"], kind);
        if kind == "welcome" {
            id = v["clientId"].as_str().unwrap().to_owned();
        }
    }
    ws.get_ref()
        .sock
        .socket
        .set_read_timeout(Some(Duration::from_secs(1)))
        .unwrap();
    // Sample the relay's kernel queue for this browser every millisecond.
    let server_socket = web.state.lock().unwrap().clients[&id]
        .socket
        .as_ref()
        .unwrap()
        .try_clone()
        .unwrap();
    let sampling = Arc::new(AtomicBool::new(true));
    let sampler = {
        let sampling = sampling.clone();
        std::thread::spawn(move || {
            let mut samples = Vec::new();
            while sampling.load(Ordering::Acquire) {
                let q = send_buffer::kernel_queue(&server_socket);
                samples.push((oxp_core::monotonic_nanos(), q));
                std::thread::sleep(Duration::from_millis(1));
            }
            samples
        })
    };
    let producer_web = web.clone();
    // The producer returns when it offered each frame.
    let producer = std::thread::spawn(move || {
        let start = Instant::now();
        let mut offers = Vec::new();
        for index in 0..FRAMES {
            let due = start + Duration::from_micros((index * INTERVAL_US) as u64);
            if let Some(wait) = due.checked_duration_since(Instant::now()) {
                std::thread::sleep(wait);
            }
            let (key_frame, size) = shape(index);
            let data = payload(index, size).into();
            let received_at_nanos = oxp_core::monotonic_nanos();
            offers.push(received_at_nanos);
            producer_web.on_video_frame(VideoFrame {
                screen: 0,
                session: 42,
                data,
                pts_us: index * INTERVAL_US,
                key_frame,
                received_at_nanos,
            });
        }
        offers
    });
    let started = Instant::now();
    let mut stats_sent = false;
    let mut delivered = Vec::new();
    loop {
        if !stats_sent && started.elapsed() > Duration::from_millis(1500) {
            // The relay's counters while the link is congested.
            stats_sent = true;
            let stats: Value =
                serde_json::from_str(include_str!("../../web-receiver/spec/vectors/web/stats.json")).unwrap();
            ws.send(Message::Text(stats.to_string().into())).unwrap();
        }
        if producer.is_finished() && !ws.get_ref().sock.fast {
            // Drain the rest at full speed; the stream ends once nothing arrives for a while.
            ws.get_mut().sock.fast = true;
            ws.get_ref()
                .sock
                .socket
                .set_read_timeout(Some(Duration::from_millis(500)))
                .unwrap();
        }
        let message = match ws.read() {
            Ok(m) => m,
            // Nothing arrived within the read timeout. While draining, the stream has ended.
            // Before that, the relay may have dropped the rest of a group of frames, or the
            // producer finished while this read waited: look again, and drain once it is done.
            Err(tungstenite::Error::Io(e))
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                if ws.get_ref().sock.fast {
                    break;
                }
                continue;
            }
            Err(e) => panic!("{e}"),
        };
        let Message::Binary(b) = message else {
            continue;
        };
        let oxp_wire::Message::Binary { header, payload: p } = oxp_wire::decode(&b).unwrap() else {
            panic!("Expected video")
        };
        assert_eq!(header.kind, Kind::Video);
        let index = header.pts_us / INTERVAL_US;
        assert_eq!(header.flags & 1 != 0, shape(index).0, "frame {index}");
        // Drops apply only to queued frames: whatever the relay started arrives complete.
        assert!(p == payload(index, shape(index).1), "frame {index} damaged");
        delivered.push(index);
    }
    let offers = producer.join().unwrap();
    sampling.store(false, Ordering::Release);
    let samples = sampler.join().unwrap();
    let stalls = std::mem::take(&mut ws.get_mut().sock.stalls);
    let written: Vec<(i64, i64, i64)> = web.state.lock().unwrap().clients[&id]
        .written
        .iter()
        .map(|&(_, pts, received_at, written_at)| (pts / INTERVAL_US, received_at, written_at))
        .collect();
    web.close();
    let _ = std::fs::remove_dir_all(&root);

    assert_eq!(delivered, written.iter().map(|w| w.0).collect::<Vec<_>>());
    assert_eq!(delivered.first(), Some(&0), "the 200 KB key frame");
    assert!(
        delivered.len() < FRAMES as usize,
        "the link was not congested"
    );
    // The large key frame drops at most its own group of frames: the stream recovers at a later key.
    assert!(
        delivered
            .windows(2)
            .any(|w| w[0] > 0 && shape(w[0]).0 && w[1] == w[0] + 1),
        "{delivered:?}"
    );
    let budget_ns = 100_000_000;
    // Unsent bytes allowed in the kernel: the configured limit, plus one segment that Linux may
    // queue beyond it. Linux checks the limit only before it starts a new segment, and later writes
    // fill the unsent tail segment up to the size goal without checking it again. The size goal is
    // half the receiver's largest window, at most one 64 KiB GSO segment; receive autotuning grows
    // this reader's window beyond 100 KB, so on loopback the segment reaches 55 KB.
    let kernel_bytes = config.unsent_limit_bytes as f64 + 64.0 * 1024.0;
    let mut kernel_seen = false;
    let sample_times: Vec<i64> = samples.iter().map(|(at, _)| *at).collect();
    for &(index, received_at, written_at) in &written {
        let unsent = samples
            .iter()
            .rev()
            .find(|(at, _)| *at <= written_at)
            .or(samples.first())
            .and_then(|(_, q)| *q)
            .map_or(0, |q| q.unsent.unwrap_or(q.queued));
        kernel_seen |= samples.iter().any(|(_, q)| q.is_some());
        let relay_ns = (written_at - received_at) as f64 + unsent as f64 * 1e9 / RATE;
        let own_ns = (shape(index).1 as f64 + kernel_bytes) * 1e9 / RATE;
        // The bound assumes a scheduler that runs every thread when it is due. On a shared
        // runner it may not, and the time the test's threads lose during this frame's write
        // delays it without being the relay's doing: the reader standing still holds up the
        // link; a late offer holds up the queue's eviction of frames older than the budget; and
        // the sampler, a 1 ms sleeper like the relay's writer (2 ms), shows how late due threads
        // run. On a quiet machine all three are small.
        let during = |at: i64| (received_at..=written_at).contains(&at);
        let stalled_ns: i64 = stalls
            .iter()
            .filter(|(at, _)| during(*at))
            .map(|(_, ns)| ns)
            .sum();
        let late = |times: &[i64], period: i64| -> i64 {
            times
                .windows(2)
                .filter(|w| during(w[1]))
                .map(|w| (w[1] - w[0] - period).max(0))
                .sum()
        };
        let late_offers_ns = late(&offers, INTERVAL_US * 1000);
        let lag_ns = late(&sample_times, 1_000_000);
        let lost_ns = stalled_ns + late_offers_ns + lag_ns;
        let bound_ns = (budget_ns + 2 * INTERVAL_US * 1000 + 50_000_000 + lost_ns) as f64 + own_ns;
        assert!(
            relay_ns <= bound_ns,
            "frame {index}: {:.0} ms in the relay ({:.0} ms to write, {unsent} bytes unsent), \
             bound {:.0} ms, of which {:.0} ms lost to the scheduler ({:.0} ms reader stalls, \
             {:.0} ms late offers, {:.0} ms sampler lag)",
            relay_ns / 1e6,
            (written_at - received_at) as f64 / 1e6,
            bound_ns / 1e6,
            lost_ns as f64 / 1e6,
            stalled_ns as f64 / 1e6,
            late_offers_ns as f64 / 1e6,
            lag_ns as f64 / 1e6
        );
    }
    let report = reports.lock().unwrap().last().cloned().unwrap();
    assert!(report["relayDroppedFrames"].as_u64().unwrap() > 0);
    assert!(report["relaySendLatencyMs"].as_f64().unwrap() >= 0.0);
    assert_eq!(report["relayKernelSendQueueBytes"].is_u64(), kernel_seen);
}
