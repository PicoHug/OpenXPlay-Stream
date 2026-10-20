// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

#![forbid(unsafe_code)]
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::Value;
use std::io::{self, Read, Write};
/// Capability negotiation uses an existing opaque hello string: legacy v0 rejects extra fields.
/// Producers MUST omit context messages unless the consumer advertises this suffix.
pub const CONTEXT_CLIENT_SUFFIX: &str = ";oxp-context=0";
pub fn context_client(client: &str) -> String {
    format!("{client}{CONTEXT_CLIENT_SUFFIX}")
}
pub fn supports_context(hello: &Value) -> bool {
    hello["role"] == "consumer"
        && hello["client"]
            .as_str()
            .is_some_and(|c| c.ends_with(CONTEXT_CLIENT_SUFFIX))
}
pub fn decode_context(v: &Value) -> Result<oxp_core::ContextUpdate> {
    fields(v, &["type", "topic", "value"], &[])?;
    enumeration(&v["type"], &["context"])?;
    oxp_core::ContextUpdate::from_json(v)
}
pub const MAX_PAYLOAD: usize = 16 * 1024 * 1024;
pub const HEADER_SIZE: usize = 24;
pub const KEYFRAME: u8 = 1;
pub const GAP: u8 = 2;
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
fn require(ok: bool, message: &'static str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(message.into())
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Kind {
    Video = 1,
    Audio = 2,
    Microphone = 3,
    Touch = 16,
    Key = 17,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub kind: Kind,
    pub stream: u8,
    pub flags: u8,
    pub sequence: u32,
    pub pts_us: i64,
    pub sender_clock_us: i64,
}
#[derive(Clone, Debug, PartialEq)]
pub enum Message {
    Json(Value),
    Binary { header: Header, payload: Vec<u8> },
}
impl Message {
    pub fn json(value: Value) -> Result<Self> {
        validate_json(&value)?;
        Ok(Self::Json(value))
    }
    pub fn context(update: &oxp_core::ContextUpdate) -> Result<Self> {
        let mut value = update.to_json()?;
        value
            .as_object_mut()
            .unwrap()
            .insert("type".into(), Value::from("context"));
        Self::json(value)
    }
    /// Returns None for a legacy consumer, which rejects unknown message types.
    pub fn context_for_peer(
        update: &oxp_core::ContextUpdate,
        peer: &Value,
    ) -> Result<Option<Self>> {
        if supports_context(peer) {
            Self::context(update).map(Some)
        } else {
            Ok(None)
        }
    }
    pub fn hello(role: &str, name: &str, client: &str) -> Result<Self> {
        Self::json(
            serde_json::json!({"type":"hello","version":0,"role":role,"name":name,"client":client}),
        )
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AudioCodec {
    AacLc,
    Opus,
    PcmS16Le,
}
impl AudioCodec {
    pub fn name(self) -> &'static str {
        match self {
            Self::AacLc => "AAC_LC",
            Self::Opus => "OPUS",
            Self::PcmS16Le => "PCM_S16LE",
        }
    }
    fn parse(v: &Value) -> Result<Self> {
        match string(v, false)? {
            "AAC_LC" => Ok(Self::AacLc),
            "OPUS" => Ok(Self::Opus),
            "PCM_S16LE" => Ok(Self::PcmS16Le),
            _ => Err("Invalid audio codec".into()),
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AudioRole {
    Media,
    Alert,
    Voice,
    Telephony,
    Other,
}
impl AudioRole {
    pub fn name(self) -> &'static str {
        match self {
            Self::Media => "MEDIA",
            Self::Alert => "ALERT",
            Self::Voice => "VOICE",
            Self::Telephony => "TELEPHONY",
            Self::Other => "OTHER",
        }
    }
    fn parse(v: &Value) -> Result<Self> {
        match string(v, false)? {
            "MEDIA" => Ok(Self::Media),
            "ALERT" => Ok(Self::Alert),
            "VOICE" => Ok(Self::Voice),
            "TELEPHONY" => Ok(Self::Telephony),
            "OTHER" => Ok(Self::Other),
            _ => Err("Invalid audio role".into()),
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AudioFormat {
    pub role: AudioRole,
    pub codec: AudioCodec,
    pub sample_rate: u32,
    pub channels: u32,
    pub config: Option<Vec<u8>>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MicRequest {
    pub codec: AudioCodec,
    pub sample_rate: u32,
    pub channels: u32,
    pub frame_ms: u32,
}
impl Message {
    pub fn audio_format(stream: u8, format: Option<&AudioFormat>) -> Result<Self> {
        let format = format.map(|f| {
            let mut v = serde_json::json!({"role":f.role.name(), "codec":f.codec.name(),
                "sampleRate":f.sample_rate, "channels":f.channels});
            if let Some(config) = &f.config {
                v["config"] = Value::String(STANDARD.encode(config));
            }
            v
        });
        Self::json(serde_json::json!({"type":"audio-format", "stream":stream, "format":format}))
    }
    pub fn microphone(stream: u8, request: Option<&MicRequest>) -> Result<Self> {
        let request = request.map(|r| {
            serde_json::json!({"codec":r.codec.name(),
            "sampleRate":r.sample_rate, "channels":r.channels, "frameMs":r.frame_ms})
        });
        Self::json(serde_json::json!({"type":"mic", "stream":stream, "request":request}))
    }
}
fn positive_int(v: &Value) -> Result<u32> {
    let n = integer(v, i32::MAX as u64)?;
    require(n > 0, "Expected positive integer")?;
    Ok(n as u32)
}
pub fn decode_audio_format(v: &Value) -> Result<(u8, Option<AudioFormat>)> {
    fields(v, &["type", "stream", "format"], &[])?;
    enumeration(&v["type"], &["audio-format"])?;
    let stream = integer(&v["stream"], 255)? as u8;
    let f = &v["format"];
    let format = if f.is_null() {
        None
    } else {
        fields(f, &["role", "codec", "sampleRate", "channels"], &["config"])?;
        let config = f
            .get("config")
            .map(|v| -> Result<Vec<u8>> {
                let text = string(v, false)?;
                let bytes = STANDARD.decode(text)?;
                require(STANDARD.encode(&bytes) == text, "Noncanonical base64")?;
                Ok(bytes)
            })
            .transpose()?;
        Some(AudioFormat {
            role: AudioRole::parse(&f["role"])?,
            codec: AudioCodec::parse(&f["codec"])?,
            sample_rate: positive_int(&f["sampleRate"])?,
            channels: positive_int(&f["channels"])?,
            config,
        })
    };
    Ok((stream, format))
}
pub fn decode_microphone(v: &Value) -> Result<(u8, Option<MicRequest>)> {
    fields(v, &["type", "stream", "request"], &[])?;
    enumeration(&v["type"], &["mic"])?;
    let stream = integer(&v["stream"], 255)? as u8;
    let r = &v["request"];
    let request = if r.is_null() {
        None
    } else {
        fields(r, &["codec", "sampleRate", "channels", "frameMs"], &[])?;
        Some(MicRequest {
            codec: AudioCodec::parse(&r["codec"])?,
            sample_rate: positive_int(&r["sampleRate"])?,
            channels: positive_int(&r["channels"])?,
            frame_ms: positive_int(&r["frameMs"])?,
        })
    };
    Ok((stream, request))
}
fn known_json_type(t: &str) -> bool {
    matches!(
        t,
        "display-settings"
            | "view-area"
            | "context"
            | "hello"
            | "session"
            | "video-format"
            | "audio-format"
            | "mic"
            | "capabilities"
            | "input-capabilities"
            | "client"
            | "restart"
            | "keyframe"
            | "stats"
            | "error"
    )
}
/// Skips additive JSON message types before dispatch, while decode keeps strict local validation.
pub fn read(reader: &mut impl Read) -> Result<Option<Message>> {
    loop {
        let mut prefix = [0; 4];
        loop {
            match reader.read(&mut prefix[..1]) {
                Ok(0) => return Ok(None),
                Ok(_) => break,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e.into()),
            }
        }
        reader.read_exact(&mut prefix[1..])?;
        let size = u32::from_le_bytes(prefix) as usize;
        require((1..=MAX_PAYLOAD).contains(&size), "Invalid frame length")?;
        let mut payload = vec![0; size];
        reader.read_exact(&mut payload)?;
        if payload[0] == b'{' {
            let value: Value = serde_json::from_slice(&payload)?;
            if !known_json_type(string(&value["type"], false)?) {
                continue;
            }
        }
        return decode(&payload).map(Some);
    }
}
pub fn write(writer: &mut impl Write, message: &Message) -> Result<()> {
    // Keep borrowed media separate from the small header.
    if let Message::Binary { header, payload } = message {
        require(
            payload.len() <= MAX_PAYLOAD - HEADER_SIZE,
            "Frame exceeds 16 MiB",
        )?;
        validate_payload(header.kind, payload)?;
        let bytes = encode_header(header)?;
        writer.write_all(&((HEADER_SIZE + payload.len()) as u32).to_le_bytes())?;
        writer.write_all(&bytes)?;
        writer.write_all(payload)?;
    } else {
        let bytes = encode(message)?;
        writer.write_all(&(bytes.len() as u32).to_le_bytes())?;
        writer.write_all(&bytes)?;
    }
    writer.flush()?;
    Ok(())
}
pub fn encode_header(h: &Header) -> Result<[u8; 24]> {
    require(h.flags <= 3, "Unknown header flags")?;
    let mut b = [0; 24];
    b[0] = h.kind as u8;
    b[1] = h.stream;
    b[2] = h.flags;
    b[4..8].copy_from_slice(&h.sequence.to_le_bytes());
    b[8..16].copy_from_slice(&h.pts_us.to_le_bytes());
    b[16..24].copy_from_slice(&h.sender_clock_us.to_le_bytes());
    Ok(b)
}
pub fn encode(m: &Message) -> Result<Vec<u8>> {
    let b = match m {
        Message::Json(v) => {
            validate_json(v)?;
            serde_json::to_vec(v)?
        }
        Message::Binary { header, payload } => {
            require(payload.len() <= MAX_PAYLOAD - 24, "Frame exceeds 16 MiB")?;
            validate_payload(header.kind, payload)?;
            let mut b = encode_header(header)?.to_vec();
            b.extend(payload);
            b
        }
    };
    require((1..=MAX_PAYLOAD).contains(&b.len()), "Invalid frame length")?;
    Ok(b)
}
pub fn decode(b: &[u8]) -> Result<Message> {
    require((1..=MAX_PAYLOAD).contains(&b.len()), "Invalid frame length")?;
    if b[0] == b'{' {
        return Message::json(serde_json::from_slice(b)?);
    }
    require(b.len() >= 24, "Truncated header")?;
    require(b[3] == 0 && b[2] <= 3, "Invalid header")?;
    let kind = match b[0] {
        1 => Kind::Video,
        2 => Kind::Audio,
        3 => Kind::Microphone,
        16 => Kind::Touch,
        17 => Kind::Key,
        _ => return Err("Unknown kind".into()),
    };
    validate_payload(kind, &b[24..])?;
    Ok(Message::Binary {
        header: Header {
            kind,
            stream: b[1],
            flags: b[2],
            sequence: u32::from_le_bytes(b[4..8].try_into()?),
            pts_us: i64::from_le_bytes(b[8..16].try_into()?),
            sender_clock_us: i64::from_le_bytes(b[16..24].try_into()?),
        },
        payload: b[24..].to_vec(),
    })
}
#[derive(Clone, Debug, PartialEq)]
pub struct Contact {
    pub id: u8,
    pub x: f64,
    pub y: f64,
    pub pressed: bool,
}
pub fn decode_touch(b: &[u8]) -> Result<Vec<Contact>> {
    require(
        !b.is_empty() && b[0] <= 10 && b.len() == 1 + b[0] as usize * 6,
        "Invalid touch length",
    )?;
    let mut seen = 0u16;
    let mut out = Vec::new();
    for c in b[1..].as_chunks::<6>().0 {
        require(
            c[0] <= 9 && seen & (1 << c[0]) == 0 && c[1] <= 1,
            "Invalid contact",
        )?;
        seen |= 1 << c[0];
        out.push(Contact {
            id: c[0],
            pressed: c[1] == 1,
            x: u16::from_le_bytes([c[2], c[3]]) as f64 / 65535.0,
            y: u16::from_le_bytes([c[4], c[5]]) as f64 / 65535.0,
        });
    }
    Ok(out)
}
pub fn encode_touch(c: &[Contact]) -> Result<Vec<u8>> {
    require(c.len() <= 10, "Too many contacts")?;
    let mut b = vec![c.len() as u8];
    for c in c {
        require(
            c.id <= 9
                && c.x.is_finite()
                && c.y.is_finite()
                && (0.0..=1.0).contains(&c.x)
                && (0.0..=1.0).contains(&c.y),
            "Invalid contact",
        )?;
        b.extend([c.id, c.pressed as u8]);
        b.extend(((c.x * 65535.0).round() as u16).to_le_bytes());
        b.extend(((c.y * 65535.0).round() as u16).to_le_bytes());
    }
    decode_touch(&b)?;
    Ok(b)
}
pub fn decode_key(b: &[u8]) -> Result<(u16, bool)> {
    require(b.len() == 3, "Invalid key length")?;
    let code = u16::from_le_bytes([b[0], b[1]]);
    require(code <= 10 && b[2] <= 1, "Invalid key")?;
    Ok((code, b[2] == 1))
}
pub fn encode_key(code: u16, down: bool) -> Result<Vec<u8>> {
    let mut b = code.to_le_bytes().to_vec();
    b.push(down as u8);
    decode_key(&b)?;
    Ok(b)
}
fn validate_payload(k: Kind, b: &[u8]) -> Result<()> {
    match k {
        Kind::Touch => {
            decode_touch(b)?;
        }
        Kind::Key => {
            decode_key(b)?;
        }
        _ => {}
    }
    Ok(())
}
fn fields(v: &Value, required: &[&str], optional: &[&str]) -> Result<()> {
    let o = v.as_object().ok_or("Expected object")?;
    require(
        required.iter().all(|k| o.contains_key(*k))
            && o.keys()
                .all(|k| required.contains(&k.as_str()) || optional.contains(&k.as_str())),
        "Unexpected or missing field",
    )
}
fn string(v: &Value, nonempty: bool) -> Result<&str> {
    let s = v.as_str().ok_or("Expected string")?;
    require(!nonempty || !s.is_empty(), "Empty string")?;
    Ok(s)
}
fn integer(v: &Value, max: u64) -> Result<u64> {
    let n = v.as_u64().ok_or("Expected integer")?;
    require(n <= max, "Integer out of range")?;
    Ok(n)
}
fn number(v: &Value, positive: bool) -> Result<f64> {
    let n = v.as_f64().ok_or("Expected number")?;
    require(
        n.is_finite() && if positive { n > 0.0 } else { n >= 0.0 },
        "Invalid number",
    )?;
    Ok(n)
}
fn boolean(v: &Value) -> Result<()> {
    v.as_bool().ok_or("Expected boolean")?;
    Ok(())
}
fn enumeration(v: &Value, values: &[&str]) -> Result<()> {
    require(values.contains(&string(v, false)?), "Invalid enum")
}
fn set(v: &Value, mut check: impl FnMut(&Value) -> Result<()>) -> Result<()> {
    let a = v.as_array().ok_or("Expected array")?;
    for (i, e) in a.iter().enumerate() {
        check(e)?;
        require(!a[..i].contains(e), "Duplicate entry")?;
    }
    Ok(())
}
fn keys(v: &Value) -> Result<()> {
    set(v, |e| integer(e, 10).map(|_| ()))
}
fn signed(v: &Value) -> Result<i64> {
    let s = string(v, false)?;
    let n = s.parse::<i64>()?;
    require(n.to_string() == s, "Noncanonical integer")?;
    Ok(n)
}
pub fn validate_json(v: &Value) -> Result<()> {
    let t = string(&v["type"], false)?;
    match t {
        "display-settings" => {
            decode_display_settings(v)?;
        }
        "view-area" => {
            decode_view_area(v)?;
        }
        "context" => {
            decode_context(v)?;
        }
        "hello" => {
            fields(v, &["type", "version", "role", "name", "client"], &[])?;
            require(integer(&v["version"], 0)? == 0, "Unsupported version")?;
            enumeration(&v["role"], &["producer", "consumer"])?;
            string(&v["name"], true)?;
            string(&v["client"], true)?;
        }
        "session" => {
            fields(v, &["type", "session"], &[])?;
            let s = &v["session"];
            fields(s, &["id", "phase"], &["reason"])?;
            signed(&s["id"])?;
            enumeration(
                &s["phase"],
                &["CONNECTING", "ACTIVE", "RECONNECTING", "ENDED"],
            )?;
            if s.get("reason").is_some() {
                string(&s["reason"], false)?;
            }
        }
        "video-format" => {
            fields(v, &["type", "screen", "format"], &[])?;
            integer(&v["screen"], 255)?;
            let f = &v["format"];
            if !f.is_null() {
                fields(
                    f,
                    &["codec", "codecString", "config", "width", "height"],
                    &["fpsHint"],
                )?;
                let s = string(&f["config"], true)?;
                let b = STANDARD.decode(s)?;
                require(STANDARD.encode(&b) == s, "Noncanonical base64")?;
                let (cs, w, h, n) = match string(&f["codec"], false)? {
                    "H264" => {
                        let c = oxp_media::AvcConfig::parse(&b)?;
                        (c.codec_string, c.width, c.height, c.nal_length_size)
                    }
                    "H265" => {
                        let c = oxp_media::HevcConfig::parse(&b)?;
                        (c.codec_string, c.width, c.height, c.nal_length_size)
                    }
                    _ => return Err("Invalid codec".into()),
                };
                require(
                    n == 4
                        && string(&f["codecString"], true)? == cs
                        && integer(&f["width"], i32::MAX as u64)? == w as u64
                        && integer(&f["height"], i32::MAX as u64)? == h as u64,
                    "Inconsistent format",
                )?;
                if f.get("fpsHint").is_some() {
                    number(&f["fpsHint"], true)?;
                }
            }
        }
        "audio-format" => {
            decode_audio_format(v)?;
        }
        "mic" => {
            decode_microphone(v)?;
        }
        "capabilities" => {
            fields(
                v,
                &[
                    "type",
                    "latency",
                    "decoders",
                    "displays",
                    "touch",
                    "keys",
                    "audio",
                    "microphone",
                ],
                &["prefersH264"],
            )?;
            if let Some(preference) = v.get("prefersH264") {
                boolean(preference)?;
            }
            enumeration(&v["latency"], &["INTERACTIVE", "BUFFERED"])?;
            for d in v["decoders"].as_array().ok_or("Expected decoders")? {
                fields(d, &["codecString", "hardware"], &[])?;
                string(&d["codecString"], true)?;
                boolean(&d["hardware"])?;
            }
            for (k, d) in v["displays"].as_object().ok_or("Expected displays")? {
                let n = k.parse::<u8>()?;
                require(n.to_string() == *k, "Invalid screen")?;
                fields(
                    d,
                    &["widthPx", "heightPx", "densityDpi", "refreshHz", "safeArea"],
                    &[
                        "frameRate",
                        "sustainedFps",
                        "viewport",
                        "fullscreen",
                        "fullscreenPreferred",
                    ],
                )?;
                for k in ["widthPx", "heightPx", "densityDpi"] {
                    require(integer(&d[k], i32::MAX as u64)? > 0, "Invalid display")?;
                }
                number(&d["refreshHz"], true)?;
                if let Some(viewport) = d.get("viewport") {
                    fields(viewport, &["widthPx", "heightPx"], &[])?;
                    positive_int(&viewport["widthPx"])?;
                    positive_int(&viewport["heightPx"])?;
                }
                for key in ["fullscreen", "fullscreenPreferred"] {
                    if let Some(value) = d.get(key) {
                        boolean(value)?;
                    }
                }
                if let Some(rate) = d.get("frameRate") {
                    enumeration(
                        rate,
                        &[
                            "AUTO", "FPS_30", "FPS_35", "FPS_40", "FPS_45", "FPS_50", "FPS_55",
                            "FPS_60",
                        ],
                    )?;
                }
                if let Some(fps) = d.get("sustainedFps") {
                    number(fps, true)?;
                }
                let a = &d["safeArea"];
                fields(a, &["left", "top", "right", "bottom"], &[])?;
                for k in ["left", "top", "right", "bottom"] {
                    integer(&a[k], i32::MAX as u64)?;
                }
            }
            integer(&v["touch"], 10)?;
            keys(&v["keys"])?;
            set(&v["audio"], |e| {
                enumeration(e, &["AAC_LC", "OPUS", "PCM_S16LE"])
            })?;
            boolean(&v["microphone"])?;
        }
        "input-capabilities" => {
            fields(
                v,
                &[
                    "type",
                    "codecs",
                    "screens",
                    "touch",
                    "keys",
                    "microphone",
                    "keyFrameRequests",
                    "reconfigure",
                ],
                &[],
            )?;
            set(&v["codecs"], |e| enumeration(e, &["H264", "H265"]))?;
            set(&v["screens"], |e| integer(e, 255).map(|_| ()))?;
            keys(&v["keys"])?;
            for k in ["touch", "microphone", "keyFrameRequests"] {
                boolean(&v[k])?;
            }
            enumeration(
                &v["reconfigure"],
                &["NONE", "RESTART_SESSION", "UNSUPPORTED"],
            )?;
        }
        "client" => {
            fields(v, &["type", "client"], &[])?;
            string(&v["client"], true)?;
        }
        "restart" => fields(v, &["type"], &[])?,
        "keyframe" => {
            fields(v, &["type", "screen"], &[])?;
            integer(&v["screen"], 255)?;
        }
        "stats" => {
            fields(
                v,
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
                &[
                    "audioBufferedMs",
                    "audioUnderruns",
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
                ],
            )?;
            require(
                v.get("audioBufferedMs").is_some() == v.get("audioUnderruns").is_some(),
                "Incomplete audio stats",
            )?;
            if let Some(n) = v.get("audioUnderruns") {
                let n = number(n, false)?;
                require(
                    n <= 9007199254740991.0 && n.fract() == 0.0,
                    "Invalid counter",
                )?;
                if !v["audioBufferedMs"].is_null() {
                    number(&v["audioBufferedMs"], false)?;
                }
            }
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
                    number(value, false)?;
                }
            }
            integer(&v["screen"], 255)?;
            integer(&v["decodeQueue"], i32::MAX as u64)?;
            for k in ["dropped", "rendered"] {
                let n = number(&v[k], false)?;
                require(
                    n <= 9007199254740991.0 && n.fract() == 0.0,
                    "Invalid counter",
                )?;
            }
            for k in ["decodeMs", "decodedLatencyMs", "renderedLatencyMs"] {
                if !v[k].is_null() {
                    require(
                        number(&v[k], false)? <= i64::MAX as f64 / 1000.0,
                        "Invalid duration",
                    )?;
                }
            }
            for k in ["clockOffsetUs", "rttUs"] {
                if !v[k].is_null() {
                    signed(&v[k])?;
                }
            }
        }
        "error" => {
            fields(v, &["type", "code", "message"], &[])?;
            string(&v["code"], true)?;
            string(&v["message"], false)?;
        }
        _ => return Err("Unknown message type".into()),
    }
    Ok(())
}

pub fn decode_display_settings(v: &Value) -> Result<oxp_core::model::DisplaySettings> {
    fields(v, &["type", "resolution", "screenWidth", "videoCodec"], &[])?;
    enumeration(&v["type"], &["display-settings"])?;
    let settings = oxp_core::model::DisplaySettings {
        resolution: string(&v["resolution"], true)?.into(),
        screen_width: positive_int(&v["screenWidth"])?,
        video_codec: string(&v["videoCodec"], true)?.into(),
    };
    settings.validate()?;
    Ok(settings)
}
pub fn display_settings(s: &oxp_core::model::DisplaySettings) -> Value {
    serde_json::json!({"type":"display-settings", "resolution":s.resolution,
        "screenWidth":s.screen_width, "videoCodec":s.video_codec})
}
pub fn area_value(a: oxp_core::model::ViewArea) -> Value {
    serde_json::json!({"x":a.x,"y":a.y,"width":a.width,"height":a.height,
        "canvasWidth":a.canvas_width,"canvasHeight":a.canvas_height})
}
pub fn decode_area(a: &Value) -> Result<oxp_core::model::ViewArea> {
    fields(
        a,
        &["x", "y", "width", "height", "canvasWidth", "canvasHeight"],
        &[],
    )?;
    let area = oxp_core::model::ViewArea {
        x: integer(&a["x"], i32::MAX as u64)? as u32,
        y: integer(&a["y"], i32::MAX as u64)? as u32,
        width: positive_int(&a["width"])?,
        height: positive_int(&a["height"])?,
        canvas_width: positive_int(&a["canvasWidth"])?,
        canvas_height: positive_int(&a["canvasHeight"])?,
    };
    area.validate()?;
    Ok(area)
}
pub fn decode_view_area(v: &Value) -> Result<(u32, Option<oxp_core::model::ViewArea>)> {
    fields(v, &["type", "screen", "area"], &[])?;
    enumeration(&v["type"], &["view-area"])?;
    Ok((
        integer(&v["screen"], 255)? as u32,
        if v["area"].is_null() {
            None
        } else {
            Some(decode_area(&v["area"])?)
        },
    ))
}
