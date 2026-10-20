// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

use oxp_core::{model::*, InputSink};
use oxp_wire::Message;
use serde_json::json;
use std::sync::{Arc, Mutex};
#[derive(Default)]
struct Sink {
    settings: Mutex<Vec<DisplaySettings>>,
    areas: Mutex<Vec<(u32, Option<ViewArea>)>>,
}
impl InputSink for Sink {
    fn display_settings(&self, settings: DisplaySettings) {
        self.settings.lock().unwrap().push(settings);
    }
    fn view_area(&self, screen: u32, area: Option<ViewArea>) {
        self.areas.lock().unwrap().push((screen, area));
    }
    fn session(&self, _: &SessionState) {}
    fn video_format(&self, _: u32, _: Option<Arc<VideoFormat>>) {}
    fn video_frame(&self, _: VideoFrame) {}
    fn audio_format(&self, _: u32, _: Option<Arc<AudioFormat>>) {}
    fn audio_packet(&self, _: AudioPacket) {}
    fn microphone(&self, _: u32, _: Option<MicRequest>) {}
}
#[test]
fn declarations_are_forwarded_without_requiring_a_video_session() {
    let sink = Sink::default();
    let mut consumer = oxp_bin::Consumer::default();
    let settings = DisplaySettings {
        resolution: "auto".into(),
        screen_width: 200,
        video_codec: "h265".into(),
    };
    let area = ViewArea {
        x: 10,
        y: 20,
        width: 400,
        height: 300,
        canvas_width: 1280,
        canvas_height: 720,
    };
    for value in [
        oxp_wire::display_settings(&settings),
        json!({"type":"view-area","screen":2,"area":oxp_wire::area_value(area)}),
        json!({"type":"view-area","screen":2,"area":null}),
    ] {
        consumer
            .receive(Message::json(value).unwrap(), &sink)
            .unwrap();
    }
    assert_eq!(*sink.settings.lock().unwrap(), vec![settings]);
    assert_eq!(
        *sink.areas.lock().unwrap(),
        vec![(2, Some(area)), (2, None)]
    );
    assert!(consumer
        .receive(
            Message::Json(json!({"type":"view-area","screen":0,"area":{"x":0}})),
            &sink
        )
        .is_err());
    assert!(consumer
        .receive(Message::json(json!({"type":"restart"})).unwrap(), &sink)
        .is_err());
}
#[test]
fn output_settings_request_has_client_origin_then_wire_message() {
    use oxp_core::model::OutputHost;
    let (tx, rx) = std::sync::mpsc::sync_channel(8);
    let sink = oxp_bin::clink_support::Sink {
        tx,
        state: Mutex::new(("default".into(), [0; 256])),
        clock: std::time::Instant::now(),
        shutdown: Arc::new(|| panic!("unexpected shutdown")),
        failed: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        log: oxp_bin::EventLog::global().clone(),
    };
    let settings = DisplaySettings {
        resolution: "native".into(),
        screen_width: 125,
        video_codec: "h264".into(),
    };
    sink.control(ControlEvent::DisplaySettings {
        settings: settings.clone(),
        origin: Origin {
            output_id: "web".into(),
            client_id: "primary".into(),
        },
    });
    assert_eq!(
        rx.recv().unwrap(),
        Message::Json(json!({"type":"client","client":"primary"}))
    );
    assert_eq!(
        rx.recv().unwrap(),
        Message::Json(oxp_wire::display_settings(&settings))
    );
}
