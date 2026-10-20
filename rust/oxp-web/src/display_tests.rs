// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

use super::*;
#[derive(Default)]
struct Host {
    controls: Mutex<Vec<ControlEvent>>,
    caps: Mutex<Vec<OutputCapabilities>>,
}
impl OutputHost for Host {
    fn capabilities(&self, c: OutputCapabilities) {
        self.caps.lock().unwrap().push(c);
    }
    fn control(&self, e: ControlEvent) {
        self.controls.lock().unwrap().push(e);
    }
    fn stats(&self, _: OutputStats) {}
}
fn fixture() -> (WebOutput, Arc<Host>) {
    let config = WebOutputConfig::new(
        "127.0.0.1:0".parse().unwrap(),
        PathBuf::new(),
        Pairing::Disabled,
        Tls::Disabled,
    );
    let web = WebOutput::new(config).unwrap();
    let host = Arc::new(Host::default());
    web.state.lock().unwrap().host = Some(host.clone());
    (web, host)
}
fn hello(web: &WebOutput, id: &str) {
    web.state
        .lock()
        .unwrap()
        .clients
        .insert(id.into(), Client::new(100_000));
    let value: Value = serde_json::from_str(include_str!(
        "../../web-receiver/spec/vectors/web/hello-fullscreen-preferred.json"
    ))
    .unwrap();
    handle(value, id, &web.config, &web.state, 0).unwrap();
}
fn settings() -> DisplaySettings {
    DisplaySettings {
        resolution: "native".into(),
        screen_width: 125,
        video_codec: "auto".into(),
    }
}
fn area() -> ViewArea {
    ViewArea {
        x: 120,
        y: 40,
        width: 1440,
        height: 900,
        canvas_width: 1920,
        canvas_height: 1080,
    }
}
#[test]
fn support_is_declared_and_snapshots_replayed_with_welcome() {
    let (web, _) = fixture();
    web.on_display_settings(settings());
    web.on_view_area(0, Some(area()));
    hello(&web, "a");
    let s = web.state.lock().unwrap();
    let messages = &s.clients["a"].messages;
    assert_eq!(
        messages[0]["features"],
        json!(["display-settings", "view-area"])
    );
    assert_eq!(messages[1], oxp_wire::display_settings(&settings()));
    assert_eq!(messages[2]["x"], 120);
    for v in messages {
        json::validate(v, Some(json::Direction::Relay)).unwrap();
    }
}
#[test]
fn late_input_declaration_updates_connected_clients_and_clear_sends_whole_canvas() {
    let (web, _) = fixture();
    hello(&web, "a");
    web.state
        .lock()
        .unwrap()
        .clients
        .get_mut("a")
        .unwrap()
        .messages
        .clear();
    web.on_display_settings(settings());
    web.on_view_area(0, Some(area()));
    web.on_view_area(0, None);
    let s = web.state.lock().unwrap();
    let messages = &s.clients["a"].messages;
    assert!(messages.iter().any(
        |v| v["type"] == "welcome" && v["features"] == json!(["display-settings", "view-area"])
    ));
    let last = messages.back().unwrap();
    assert_eq!(
        last,
        &json!({"type":"view-area","x":0,"y":0,"width":1920,"height":1080,"canvasWidth":1920,"canvasHeight":1080})
    );
}
#[test]
fn primary_requests_are_forwarded_each_time_and_secondary_is_ignored() {
    let (web, host) = fixture();
    web.on_display_settings(settings());
    hello(&web, "a");
    hello(&web, "b");
    let value = oxp_wire::display_settings(&settings());
    for id in ["a", "a", "b", "a"] {
        handle(value.clone(), id, &web.config, &web.state, 0).unwrap();
    }
    let controls = host.controls.lock().unwrap();
    assert_eq!(controls.len(), 3);
    assert!(controls.iter().all(
        |e| matches!(e, ControlEvent::DisplaySettings { origin, .. } if origin.client_id == "a")
    ));
    let caps = host.caps.lock().unwrap();
    let d = &caps.last().unwrap().displays[&0];
    assert_eq!(
        d.viewport,
        Some(Viewport {
            width_px: 1533,
            height_px: 907
        })
    );
    assert_eq!(d.fullscreen, Some(false));
    assert_eq!(d.fullscreen_preferred, Some(true));
}
#[test]
fn legacy_client_settings_preserve_current_codec_but_relay_settings_require_it() {
    let (web, host) = fixture();
    let mut current = settings();
    current.video_codec = "h265".into();
    web.on_display_settings(current.clone());
    hello(&web, "a");
    let value = json!({"type":"display-settings","resolution":"720p","screenWidth":300});
    json::validate(&value, Some(json::Direction::Client)).unwrap();
    assert!(json::validate(&value, Some(json::Direction::Relay)).is_err());
    handle(value, "a", &web.config, &web.state, 0).unwrap();
    current.resolution = "720p".into();
    current.screen_width = 300;
    assert!(
        matches!(&host.controls.lock().unwrap()[0], ControlEvent::DisplaySettings { settings, .. } if settings == &current)
    );
}

#[test]
fn diagnostics_and_relay_metadata_preserve_their_shapes() {
    let (mut web, _) = fixture();
    let reports = Arc::new(Mutex::new(Vec::new()));
    let reports_copy = reports.clone();
    web.config.diagnostics = Some(Arc::new(move |id, value| {
        reports_copy
            .lock()
            .unwrap()
            .push((id.to_owned(), value.clone()))
    }));
    web.config.relay = Some(RelayInfo {
        name: "OpenXPlay Linux".into(),
        version: "0.1.3".into(),
    });
    hello(&web, "a");
    let value: Value = serde_json::from_str(include_str!(
        "../../web-receiver/spec/vectors/web/fullscreen-exit.json"
    ))
    .unwrap();
    json::validate(&value, Some(json::Direction::Client)).unwrap();
    handle(value.clone(), "a", &web.config, &web.state, 0).unwrap();
    assert_eq!(*reports.lock().unwrap(), vec![("a".into(), value)]);
    assert_eq!(
        web.state.lock().unwrap().clients["a"].messages[0]["relay"],
        json!({"name":"OpenXPlay Linux","version":"0.1.3"})
    );
}
#[test]
fn build_file_is_trimmed_optional_and_bounded() {
    let root = std::env::temp_dir().join(format!("oxp-web-build-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let path = root.join("receiver-build.txt");
    assert_eq!(receiver_build(&root), None);
    std::fs::write(&path, "  build-123\n").unwrap();
    assert_eq!(receiver_build(&root).as_deref(), Some("build-123"));
    for bad in [
        " ".into(),
        "x".repeat(129),
        "bad\nvalue".into(),
        "x".repeat(1025),
    ] {
        std::fs::write(&path, bad).unwrap();
        assert_eq!(receiver_build(&root), None);
    }
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn strict_page_messages_and_directions() {
    let value: Value = serde_json::from_str(include_str!(
        "../../web-receiver/spec/vectors/web/fullscreen-exit.json"
    ))
    .unwrap();
    assert!(json::validate(&value, Some(json::Direction::Relay)).is_err());
    for (key, bad) in [
        ("hiddenMs", json!(-1)),
        ("autoRestore", json!("bad")),
        ("display", Value::Null),
    ] {
        let mut v = value.clone();
        v[key] = bad;
        assert!(json::validate(&v, None).is_err());
    }
    let v = oxp_wire::display_settings(&settings());
    for direction in [json::Direction::Client, json::Direction::Relay] {
        json::validate(&v, Some(direction)).unwrap();
    }
    let mut v = oxp_wire::area_value(area());
    v["type"] = json!("view-area");
    assert!(json::validate(&v, Some(json::Direction::Client)).is_err());
    v["width"] = json!(0);
    assert!(json::validate(&v, None).is_err());
}
