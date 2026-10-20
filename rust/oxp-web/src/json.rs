// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use base64::{engine::general_purpose::STANDARD, Engine};
use oxp_core::model::*;
use oxp_wire::Result;
use serde_json::{json, Value};
pub const PROJECT_URL: &str = "https://github.com/OpenXPlay/OpenXPlay-Stream";
#[derive(Clone, Copy)]
pub enum Direction {
    Client,
    Relay,
}
fn check(ok: bool) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err("Invalid OXP-Web JSON".into())
    }
}
fn fields(v: &Value, required: &[&str], optional: &[&str]) -> Result<()> {
    let o = v.as_object().ok_or("Expected object")?;
    check(
        required.iter().all(|k| o.contains_key(*k))
            && o.keys()
                .all(|k| required.contains(&k.as_str()) || optional.contains(&k.as_str())),
    )
}
fn int(v: &Value, max: u64) -> bool {
    v.as_f64()
        .is_some_and(|n| n >= 0.0 && n <= max as f64 && n.fract() == 0.0)
}
fn num(v: &Value, positive: bool) -> bool {
    v.as_f64()
        .is_some_and(|n| n.is_finite() && if positive { n > 0.0 } else { n >= 0.0 })
}
fn i64s(v: &Value) -> bool {
    v.as_str()
        .is_some_and(|s| s.parse::<i64>().is_ok_and(|n| n.to_string() == s))
}
fn string(v: &Value, nonempty: bool) -> bool {
    v.as_str().is_some_and(|s| !nonempty || !s.is_empty())
}
pub fn valid_url(v: &str) -> bool {
    let Ok(uri) = v.parse::<tungstenite::http::Uri>() else {
        return false;
    };
    matches!(uri.scheme_str(), Some("http" | "https"))
        && uri.host().is_some_and(|h| !h.is_empty())
        && uri.authority().is_some_and(|a| !a.as_str().contains('@'))
}
pub fn validate(v: &Value, direction: Option<Direction>) -> Result<()> {
    let t = v["type"].as_str().ok_or("Missing type")?;
    if let Some(d) = direction {
        check(match d {
            Direction::Client => [
                "hello",
                "restart",
                "keyframe",
                "pause",
                "ping",
                "pong",
                "stats",
                "receiver-info",
                "display-settings",
                "fullscreen-exit",
            ]
            .contains(&t),
            Direction::Relay => [
                "welcome",
                "display-settings",
                "view-area",
                "session",
                "video-format",
                "audio-format",
                "mic",
                "ping",
                "pong",
                "error",
            ]
            .contains(&t),
        })?;
    }
    match t {
        "hello" => {
            fields(
                v,
                &[
                    "type",
                    "version",
                    "token",
                    "decoders",
                    "displays",
                    "maxTouchContacts",
                    "audio",
                    "microphone",
                ],
                &["playback", "videoCodec"],
            )?;
            check(
                int(&v["version"], 0)
                    && string(&v["token"], false)
                    && int(&v["maxTouchContacts"], 10)
                    && v["microphone"].is_boolean(),
            )?;
            if let Some(mode) = v.get("playback") {
                check(matches!(mode.as_str(), Some("SMART" | "QUALITY" | "SPEED")))?;
            }
            if let Some(codec) = v.get("videoCodec") {
                check(matches!(codec.as_str(), Some("AUTO" | "H264")))?;
            }
            for d in v["decoders"].as_array().ok_or("Decoders")? {
                fields(d, &["codecString", "hardware"], &[])?;
                check(string(&d["codecString"], true) && d["hardware"].is_boolean())?;
            }
            for d in v["displays"].as_array().ok_or("Displays")? {
                display(d)?;
            }
            for a in v["audio"].as_array().ok_or("Audio")? {
                check(matches!(a.as_str(), Some("AAC_LC" | "OPUS" | "PCM_S16LE")))?;
            }
        }
        "welcome" => {
            fields(
                v,
                &[
                    "type",
                    "version",
                    "clientId",
                    "session",
                    "sourceUrl",
                    "attribution",
                    "projectUrl",
                ],
                &["plugins", "features", "receiverBuild", "relay"],
            )?;
            check(
                int(&v["version"], 0)
                    && string(&v["clientId"], true)
                    && string(&v["attribution"], true)
                    && v["sourceUrl"].as_str().is_some_and(valid_url)
                    && v["projectUrl"].as_str().is_some_and(valid_url),
            )?;
            if let Some(features) = v.get("features") {
                check(
                    features
                        .as_array()
                        .is_some_and(|a| a.iter().all(|f| string(f, true))),
                )?;
            }
            if let Some(build) = v.get("receiverBuild") {
                check(build.as_str().is_some_and(|s| {
                    !s.is_empty() && s.len() <= 128 && !s.chars().any(char::is_control)
                }))?;
            }
            if let Some(relay) = v.get("relay") {
                fields(relay, &["name", "version"], &[])?;
                for k in ["name", "version"] {
                    check(relay[k].as_str().is_some_and(|s| {
                        !s.is_empty() && s.len() <= 128 && !s.chars().any(char::is_control)
                    }))?;
                }
            }
            if let Some(p) = v.get("plugins") {
                check(p.is_object() && p["host"] == "/plugins/host/index.html")?;
            }
            oxp_wire::validate_json(&json!({"type":"session","session":v["session"]}))?;
        }
        "display-settings" => {
            fields(v, &["type", "resolution", "screenWidth"], &["videoCodec"])?;
            let mut complete = v.clone();
            if complete.get("videoCodec").is_none() {
                check(!matches!(direction, Some(Direction::Relay)))?;
                complete["videoCodec"] = json!("auto");
            }
            oxp_wire::decode_display_settings(&complete)?;
        }
        "view-area" => {
            fields(
                v,
                &[
                    "type",
                    "x",
                    "y",
                    "width",
                    "height",
                    "canvasWidth",
                    "canvasHeight",
                ],
                &[],
            )?;
            let mut area = v.clone();
            area.as_object_mut().unwrap().remove("type");
            oxp_wire::decode_area(&area)?;
        }
        "fullscreen-exit" => {
            fields(v, &["type", "hiddenMs", "display"], &["autoRestore"])?;
            check(v["hiddenMs"].is_null() || num(&v["hiddenMs"], false))?;
            display(&v["display"])?;
            if let Some(result) = v.get("autoRestore") {
                check(matches!(
                    result.as_str(),
                    Some("restored" | "denied" | "off")
                ))?;
            }
        }
        "pause" => {
            fields(v, &["type", "screen", "paused"], &[])?;
            check(int(&v["screen"], 255) && v["paused"].is_boolean())?;
        }
        "ping" | "pong" => {
            fields(
                v,
                if t == "ping" {
                    &["type", "t0Us"]
                } else {
                    &["type", "t0Us", "t1Us", "t2Us"]
                },
                &[],
            )?;
            for k in if t == "ping" {
                &["t0Us"][..]
            } else {
                &["t0Us", "t1Us", "t2Us"][..]
            } {
                check(i64s(&v[k]))?;
            }
        }
        "receiver-info" => {
            check(v.is_object() && serde_json::to_vec(v)?.len() <= 65536)?;
        }
        "stats" => {
            let known = v
                .as_object()
                .ok_or("Expected object")?
                .iter()
                .filter(|(k, _)| {
                    [
                        "type",
                        "screen",
                        "decodeMs",
                        "decodeQueue",
                        "dropped",
                        "rendered",
                        "decodedLatencyMs",
                        "renderedLatencyMs",
                        "clockOffsetUs",
                        "rttUs",
                        "audioBufferedMs",
                        "audioUnderruns",
                    ]
                    .contains(&k.as_str())
                })
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect();
            fields(
                &Value::Object(known),
                &[
                    "type",
                    "screen",
                    "decodeMs",
                    "decodeQueue",
                    "dropped",
                    "rendered",
                    "decodedLatencyMs",
                    "renderedLatencyMs",
                    "clockOffsetUs",
                    "rttUs",
                ],
                &["audioBufferedMs", "audioUnderruns"],
            )?;
            for key in [
                "wsBytesPerSecond",
                "videoFramesPerSecond",
                "decoderSubmittedPerSecond",
                "decoderOutputPerSecond",
                "decodeLatencyP50Ms",
                "decodeLatencyP95Ms",
                "decodeLatencyMaxMs",
                "maxDecodeQueue",
                "decoderErrors",
                "decoderResets",
                "renderedFps",
                "droppedFrames",
                "renderLatencyP50Ms",
                "renderLatencyP95Ms",
                "eventLoopLagAvgMs",
                "eventLoopLagMaxMs",
                "longTaskCount",
                "longTaskTotalMs",
                "usedJSHeapSize",
                "renderDepth",
            ] {
                if let Some(value) = v.get(key) {
                    check(num(value, false))?;
                }
            }
            check(int(&v["screen"], 255))?;
            for k in ["decodeQueue", "dropped", "rendered"] {
                check(int(&v[k], 9007199254740991))?;
            }
            for k in ["decodeMs", "decodedLatencyMs", "renderedLatencyMs"] {
                check(v[k].is_null() || num(&v[k], false))?;
            }
            for k in ["clockOffsetUs", "rttUs"] {
                check(v[k].is_null() || i64s(&v[k]))?;
            }
            if let Some(a) = v.get("audioBufferedMs") {
                check(a.is_null() || num(a, false))?;
            }
            if let Some(a) = v.get("audioUnderruns") {
                check(int(a, 9007199254740991))?;
            }
        }
        "session" | "video-format" | "audio-format" | "mic" | "keyframe" | "restart" | "error" => {
            oxp_wire::validate_json(v)?
        }
        _ => return Err("Unknown Web message".into()),
    }
    Ok(())
}
pub fn parse(s: &str, d: Option<Direction>) -> Result<Value> {
    let v = serde_json::from_str(s)?;
    validate(&v, d)?;
    Ok(v)
}
pub fn encode(v: &Value) -> Result<String> {
    validate(v, None)?;
    Ok(serde_json::to_string(v)?)
}
pub fn session(s: &SessionState) -> Value {
    let mut v = json!({"id":s.id.to_string(),"phase":match s.phase { SessionPhase::Connecting=>"CONNECTING",SessionPhase::Active=>"ACTIVE",SessionPhase::Reconnecting=>"RECONNECTING",SessionPhase::Ended=>"ENDED" }});
    if let Some(r) = &s.reason {
        v["reason"] = json!(r);
    }
    v
}
pub fn video(screen: u32, f: Option<&VideoFormat>) -> Value {
    let format=f.map(|f| {
        let mut v=json!({"codec":match f.codec { VideoCodec::H264=>"H264",VideoCodec::H265=>"H265" },"codecString":f.codec_string,"config":STANDARD.encode(&f.config),"width":f.width,"height":f.height});
        if let Some(fps)=f.fps_hint { v["fpsHint"]=json!(fps); } v
    });
    json!({"type":"video-format","screen":screen,"format":format})
}

pub fn audio_codec(c: AudioCodec) -> &'static str {
    match c {
        AudioCodec::AacLc => "AAC_LC",
        AudioCodec::Opus => "OPUS",
        AudioCodec::PcmS16le => "PCM_S16LE",
    }
}
pub fn audio(stream: u32, f: Option<&AudioFormat>) -> Value {
    let format=f.map(|f| {let mut v=json!({"role":match f.role {AudioRole::Media=>"MEDIA",AudioRole::Alert=>"ALERT",AudioRole::Voice=>"VOICE",AudioRole::Telephony=>"TELEPHONY",AudioRole::Other=>"OTHER"},"codec":audio_codec(f.codec),"sampleRate":f.sample_rate,"channels":f.channels});
        if let Some(config)=&f.config {v["config"]=json!(STANDARD.encode(config));} v});
    json!({"type":"audio-format","stream":stream,"format":format})
}
pub fn microphone(stream: u32, r: Option<&MicRequest>) -> Value {
    json!({"type":"mic","stream":stream,"request":r.map(|r|json!({"codec":audio_codec(r.codec),"sampleRate":r.sample_rate,"channels":r.channels,"frameMs":r.frame_ms}))})
}

fn display(d: &Value) -> Result<()> {
    fields(
        d,
        &["screen", "width", "height", "dpr", "refreshHz", "safeArea"],
        &[
            "frameRate",
            "sustainedFps",
            "viewport",
            "fullscreen",
            "fullscreenPreferred",
        ],
    )?;
    check(
        int(&d["screen"], 255)
            && int(&d["width"], 9007199254740991)
            && num(&d["width"], true)
            && int(&d["height"], 9007199254740991)
            && num(&d["height"], true)
            && num(&d["dpr"], true)
            && num(&d["refreshHz"], true),
    )?;
    if let Some(rate) = d.get("frameRate") {
        check(matches!(
            rate.as_str(),
            Some(
                "AUTO" | "FPS_30" | "FPS_35" | "FPS_40" | "FPS_45" | "FPS_50" | "FPS_55" | "FPS_60"
            )
        ))?;
    }
    if let Some(fps) = d.get("sustainedFps") {
        check(num(fps, true))?;
    }
    fields(&d["safeArea"], &["left", "top", "right", "bottom"], &[])?;
    for k in ["left", "top", "right", "bottom"] {
        check(num(&d["safeArea"][k], false))?;
    }

    if let Some(viewport) = d.get("viewport") {
        fields(viewport, &["width", "height"], &[])?;
        for k in ["width", "height"] {
            check(int(&viewport[k], 9007199254740991) && num(&viewport[k], true))?;
        }
    }
    for key in ["fullscreen", "fullscreenPreferred"] {
        if let Some(value) = d.get(key) {
            check(value.is_boolean())?;
        }
    }
    Ok(())
}
