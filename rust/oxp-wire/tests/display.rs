// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

use oxp_wire::*;
use serde_json::{json, Value};
#[test]
fn strict_settings_area_and_optional_display_fields() {
    let settings = json!({"type":"display-settings","resolution":"auto","screenWidth":200,"videoCodec":"auto"});
    for (key, bad) in [
        ("resolution", json!("bad")),
        ("screenWidth", json!(150)),
        ("screenWidth", json!(200.5)),
        ("videoCodec", json!("H265")),
        ("videoCodec", Value::Null),
    ] {
        let mut v = settings.clone();
        v[key] = bad;
        assert!(validate_json(&v).is_err());
    }
    for key in ["resolution", "screenWidth", "videoCodec"] {
        let mut v = settings.clone();
        v.as_object_mut().unwrap().remove(key);
        assert!(validate_json(&v).is_err());
    }
    let area = json!({"type":"view-area","screen":0,"area":{"x":120,"y":40,"width":1440,"height":900,"canvasWidth":1920,"canvasHeight":1080}});
    assert!(validate_json(&area).is_ok());
    for (key, bad) in [
        ("x", json!(-1)),
        ("y", json!(181)),
        ("width", json!(0)),
        ("height", json!(0.5)),
        ("canvasWidth", json!(0)),
        ("canvasHeight", Value::Null),
        ("x", json!(2147483647)),
    ] {
        let mut v = area.clone();
        v["area"][key] = bad;
        assert!(validate_json(&v).is_err());
    }
    let mut v = area.clone();
    v["area"] = Value::Null;
    assert!(validate_json(&v).is_ok());
    for screen in [json!(-1), json!(256), json!(0.5)] {
        v["screen"] = screen;
        assert!(validate_json(&v).is_err());
    }
    let caps: Value = serde_json::from_str(include_str!(
        "../../../spec/vectors/wire/capabilities-viewport.json"
    ))
    .unwrap();
    for (key, bad) in [
        ("viewport", Value::Null),
        ("viewport", json!({"widthPx":0,"heightPx":720})),
        ("viewport", json!({"widthPx":1,"heightPx":1,"unknown":1})),
        ("fullscreen", json!(1)),
        ("fullscreenPreferred", Value::Null),
    ] {
        let mut v = caps.clone();
        v["displays"]["0"][key] = bad;
        assert!(validate_json(&v).is_err());
    }
}
#[test]
fn reader_dispatches_the_new_types_instead_of_skipping_them() {
    let messages = [
        json!({"type":"display-settings","resolution":"native","screenWidth":125,"videoCodec":"h265"}),
        json!({"type":"view-area","screen":2,"area":null}),
    ];
    let mut bytes = Vec::new();
    for v in &messages {
        write(&mut bytes, &Message::json(v.clone()).unwrap()).unwrap();
    }
    let mut reader = std::io::Cursor::new(bytes);
    for v in messages {
        assert_eq!(read(&mut reader).unwrap(), Some(Message::Json(v)));
    }
    assert!(read(&mut reader).unwrap().is_none());
}
