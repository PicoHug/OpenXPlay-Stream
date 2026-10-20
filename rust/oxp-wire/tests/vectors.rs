// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use oxp_wire::*;
use serde_json::Value;
use std::{fs, io::Cursor, path::Path};

#[test]
fn shared_vectors() {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    // Web vectors belong to the Rust stack's own receiver; wire vectors stay shared.
    for (dir, root) in [
        ("wire", manifest.join("../../spec/vectors/wire")),
        ("web", manifest.join("../web-receiver/spec/vectors/web")),
    ] {
        for entry in fs::read_dir(root).unwrap() {
            let path = entry.unwrap().path();
            if !matches!(
                path.extension().and_then(|s| s.to_str()),
                Some("json" | "hex")
            ) {
                continue;
            }
            let bytes = fs::read(&path).unwrap();
            if path.extension().unwrap() == "hex" {
                let bytes: Vec<u8> = std::str::from_utf8(&bytes)
                    .unwrap()
                    .split_whitespace()
                    .map(|s| u8::from_str_radix(s, 16).unwrap())
                    .collect();
                let mut input = Cursor::new(&bytes);
                let message = read(&mut input).unwrap().unwrap();
                assert!(read(&mut input).unwrap().is_none());
                let mut output = Vec::new();
                write(&mut output, &message).unwrap();
                if let Message::Json(_) = message {
                    assert_eq!(
                        serde_json::from_slice::<Value>(&bytes[4..]).unwrap(),
                        serde_json::from_slice::<Value>(&output[4..]).unwrap(),
                        "{}",
                        path.display()
                    );
                } else {
                    assert_eq!(bytes, output, "{}", path.display());
                }
            } else {
                let tree: Value = serde_json::from_slice(&bytes).unwrap();
                let stream = dir == "wire"
                    || (tree["type"] == "display-settings" && tree.get("videoCodec").is_some())
                    || matches!(
                        tree["type"].as_str().unwrap(),
                        "restart"
                            | "session"
                            | "video-format"
                            | "audio-format"
                            | "mic"
                            | "keyframe"
                            | "stats"
                            | "error"
                    );
                if stream {
                    let m = decode(&bytes).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
                    assert_eq!(
                        tree,
                        serde_json::from_slice::<Value>(&encode(&m).unwrap()).unwrap()
                    );
                } else {
                    assert!(
                        decode(&bytes).is_err(),
                        "Web-only vector {}",
                        path.display()
                    );
                }
            }
        }
    }
}

#[test]
fn framing_and_malformed_messages() {
    assert!(read(&mut Cursor::new([])).unwrap().is_none());
    for bytes in [
        vec![1],
        vec![0, 0, 0, 0],
        vec![1, 0, 0, 1],
        vec![4, 0, 0, 0, 1],
    ] {
        assert!(read(&mut Cursor::new(bytes)).is_err());
    }
    for text in [
        r#"{"type":"hello","version":1,"role":"producer","name":"p","client":"c"}"#,
        r#"{"type":"client","client":""}"#,
        r#"{"type":"keyframe","screen":256}"#,
        r#"{"type":"keyframe","screen":0,"extra":true}"#,
        r#"{"type":"session","session":{"id":"01","phase":"ACTIVE"}}"#,
    ] {
        assert!(decode(text.as_bytes()).is_err());
    }
    assert!(decode_touch(&[2, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0]).is_err());
    assert!(decode_key(&[11, 0, 1]).is_err());
    assert!(decode_key(&[0, 0, 2]).is_err());
    let h = Header {
        kind: Kind::Video,
        stream: 0,
        flags: 0,
        sequence: u32::MAX,
        pts_us: i64::MIN,
        sender_clock_us: i64::MAX,
    };
    let m = Message::Binary {
        header: h,
        payload: vec![],
    };
    assert_eq!(decode(&encode(&m).unwrap()).unwrap(), m);
    let mut bytes = encode(&m).unwrap();
    bytes[3] = 1;
    assert!(decode(&bytes).is_err());
    bytes[3] = 0;
    bytes[2] = 4;
    assert!(decode(&bytes).is_err());
    assert!(encode(&Message::Binary {
        header: match m {
            Message::Binary { header, .. } => header,
            _ => unreachable!(),
        },
        payload: vec![0; MAX_PAYLOAD]
    })
    .is_err());
}

#[test]
fn controls_round_trip() {
    let c = vec![
        Contact {
            id: 0,
            x: 0.5,
            y: 0.25,
            pressed: true,
        },
        Contact {
            id: 9,
            x: 1.0,
            y: 0.0,
            pressed: false,
        },
    ];
    let bytes = encode_touch(&c).unwrap();
    assert_eq!(encode_touch(&decode_touch(&bytes).unwrap()).unwrap(), bytes);
    assert!(encode_touch(&[c[0].clone(), c[0].clone()]).is_err());
    for code in 0..=10 {
        for down in [false, true] {
            assert_eq!(
                decode_key(&encode_key(code, down).unwrap()).unwrap(),
                (code, down)
            );
        }
    }
}

#[test]
fn audio_and_microphone_models() {
    for codec in [AudioCodec::AacLc, AudioCodec::Opus, AudioCodec::PcmS16Le] {
        for role in [
            AudioRole::Media,
            AudioRole::Alert,
            AudioRole::Voice,
            AudioRole::Telephony,
            AudioRole::Other,
        ] {
            for config in [None, Some(vec![]), Some(vec![0x11, 0x90])] {
                let f = AudioFormat {
                    role,
                    codec,
                    sample_rate: 48000,
                    channels: 2,
                    config,
                };
                let Message::Json(v) = Message::audio_format(255, Some(&f)).unwrap() else {
                    panic!()
                };
                assert_eq!(decode_audio_format(&v).unwrap(), (255, Some(f)));
            }
        }
        let r = MicRequest {
            codec,
            sample_rate: 16000,
            channels: 1,
            frame_ms: 20,
        };
        let Message::Json(v) = Message::microphone(255, Some(&r)).unwrap() else {
            panic!()
        };
        assert_eq!(decode_microphone(&v).unwrap(), (255, Some(r)));
    }
    let Message::Json(v) = Message::audio_format(0, None).unwrap() else {
        panic!()
    };
    assert_eq!(decode_audio_format(&v).unwrap(), (0, None));
    let Message::Json(v) = Message::microphone(0, None).unwrap() else {
        panic!()
    };
    assert_eq!(decode_microphone(&v).unwrap(), (0, None));
    for (name, field) in [("audio-format", "format"), ("mic", "request")] {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../spec/vectors/wire");
        let v: Value =
            serde_json::from_slice(&fs::read(root.join(format!("{name}.json"))).unwrap()).unwrap();
        let mut bad = v.clone();
        bad["stream"] = serde_json::json!(256);
        assert!(Message::json(bad).is_err());
        for key in ["sampleRate", "channels"] {
            for value in [
                serde_json::json!(0),
                serde_json::json!(-1),
                serde_json::json!(1.5),
                serde_json::json!("48000"),
                Value::Null,
                serde_json::json!(2147483648u64),
            ] {
                let mut bad = v.clone();
                bad[field][key] = value;
                assert!(Message::json(bad).is_err());
            }
        }
        for (key, value) in [
            ("codec", serde_json::json!("FLAC")),
            ("extra", Value::Bool(true)),
        ] {
            let mut bad = v.clone();
            bad[field][key] = value;
            assert!(Message::json(bad).is_err());
        }
    }
    for text in [
        r#"{"type":"audio-format","stream":0,"format":{"role":"MEDIA","codec":"AAC_LC","sampleRate":48000,"channels":2,"config":"EZB="}}"#,
        r#"{"type":"audio-format","stream":0,"format":{"role":"BAD","codec":"AAC_LC","sampleRate":48000,"channels":2}}"#,
        r#"{"type":"mic","stream":0,"request":{"codec":"PCM_S16LE","sampleRate":16000,"channels":1,"frameMs":0}}"#,
        r#"{"type":"mic","stream":0,"request":{"codec":"PCM_S16LE","sampleRate":16000,"channels":1,"frameMs":1.5}}"#,
    ] {
        assert!(decode(text.as_bytes()).is_err());
    }
    for kind in [Kind::Audio, Kind::Microphone] {
        let m = Message::Binary {
            header: Header {
                kind,
                stream: 255,
                flags: 0,
                sequence: u32::MAX,
                pts_us: i64::MIN,
                sender_clock_us: i64::MAX,
            },
            payload: vec![0, 255, 128],
        };
        assert_eq!(decode(&encode(&m).unwrap()).unwrap(), m);
    }
}

#[test]
fn preferences_and_restart_are_strict() {
    let text = include_str!("../../../spec/vectors/wire/capabilities-preferences.json");
    let original: Value = serde_json::from_str(text).unwrap();
    for rate in [
        "AUTO", "FPS_30", "FPS_35", "FPS_40", "FPS_45", "FPS_50", "FPS_55", "FPS_60",
    ] {
        let mut v = original.clone();
        v["displays"]["0"]["frameRate"] = serde_json::json!(rate);
        validate_json(&v).unwrap();
    }
    for (field, bad) in [
        ("frameRate", serde_json::json!("FPS_120")),
        ("frameRate", Value::Null),
        ("frameRate", serde_json::json!(30)),
        ("sustainedFps", serde_json::json!(0)),
        ("sustainedFps", serde_json::json!(-1)),
        ("sustainedFps", Value::Null),
        ("sustainedFps", serde_json::json!("60")),
        ("unknown", serde_json::json!(true)),
    ] {
        let mut v = original.clone();
        v["displays"]["0"][field] = bad;
        assert!(validate_json(&v).is_err(), "{field}");
    }
    assert!(validate_json(&serde_json::json!({"type":"restart","screen":0})).is_err());
}

#[test]
fn codec_preference_is_optional_and_boolean() {
    let mut caps: Value =
        serde_json::from_str(include_str!("../../../spec/vectors/wire/capabilities.json")).unwrap();
    validate_json(&caps).unwrap();
    for value in [serde_json::json!(false), serde_json::json!(true)] {
        caps["prefersH264"] = value;
        validate_json(&caps).unwrap();
    }
    for value in [
        serde_json::json!(null),
        serde_json::json!(1),
        serde_json::json!("true"),
    ] {
        caps["prefersH264"] = value;
        assert!(validate_json(&caps).is_err());
    }
}
