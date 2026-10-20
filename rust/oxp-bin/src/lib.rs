// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

#![forbid(unsafe_code)]
pub mod clink_support;
pub mod event_log;
pub mod replay_audio;
use base64::{engine::general_purpose::STANDARD, Engine};
pub use event_log::EventLog;
use oxp_core::model::AudioCodec;
use oxp_wire::{Kind, Message, Result};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    io::{Read, Write},
    net::{Shutdown, TcpListener, TcpStream},
    os::unix::{
        fs::{OpenOptionsExt, PermissionsExt},
        net::{UnixListener, UnixStream},
    },
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
pub struct Args(pub BTreeMap<String, String>);
impl Args {
    pub fn parse(allowed: &[&str]) -> Result<Self> {
        Self::parse_from(std::env::args().skip(1), allowed)
    }
    pub fn parse_from(args: impl IntoIterator<Item = String>, allowed: &[&str]) -> Result<Self> {
        let mut a = args.into_iter();
        let mut map = BTreeMap::new();
        while let Some(k) = a.next() {
            if k == "--help"
                || k == "--stdio"
                || (["--no-tls", "--pairing"].contains(&k.as_str())
                    && allowed.contains(&k.as_str()))
            {
                if map.insert(k, "true".into()).is_some() {
                    return Err("Duplicate option".into());
                }
                continue;
            }
            if !allowed.contains(&k.as_str()) {
                return Err(format!("Unknown option: {k}").into());
            }
            let v = a.next().ok_or("Missing option value")?;
            if v.starts_with("--") || map.insert(k, v).is_some() {
                return Err("Missing value or duplicate option".into());
            }
        }
        if ["--stdio", "--unix", "--tcp"]
            .iter()
            .filter(|k| map.contains_key(**k))
            .count()
            > 1
        {
            return Err("Choose one wire transport".into());
        }
        Ok(Self(map))
    }
    pub fn get(&self, k: &str) -> Option<&str> {
        self.0.get(k).map(String::as_str)
    }
    pub fn need(&self, k: &str) -> Result<&str> {
        self.get(k)
            .ok_or_else(|| format!("Required option: {k}").into())
    }
}
pub struct Connection {
    pub reader: Box<dyn Read + Send>,
    pub writer: Box<dyn Write + Send>,
    pub stdio: bool,
    pub shutdown: Arc<dyn Fn() + Send + Sync>,
    _socket: Option<SocketPath>,
}
struct SocketPath(PathBuf);
impl Drop for SocketPath {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
pub fn connect(args: &Args, listen: bool) -> Result<Connection> {
    if let Some(path) = args.get("--unix") {
        let (stream, guard) = if listen {
            let path = PathBuf::from(path);
            let listener = UnixListener::bind(&path)?;
            let guard = SocketPath(path.clone());
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
            (listener.accept()?.0, Some(guard))
        } else {
            (UnixStream::connect(path)?, None)
        };
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        let closer = stream.try_clone()?;
        return Ok(Connection {
            shutdown: Arc::new(move || {
                let _ = closer.shutdown(Shutdown::Both);
            }),
            reader: Box::new(stream.try_clone()?),
            writer: Box::new(stream),
            stdio: false,
            _socket: guard,
        });
    }
    if let Some(port) = args.get("--tcp") {
        let port = port.parse::<u16>()?;
        if port == 0 {
            return Err("TCP port must be nonzero".into());
        }
        let stream = if listen {
            TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port))?
                .accept()?
                .0
        } else {
            TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port))?
        };
        stream.set_nodelay(true)?;
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        let closer = stream.try_clone()?;
        return Ok(Connection {
            shutdown: Arc::new(move || {
                let _ = closer.shutdown(Shutdown::Both);
            }),
            reader: Box::new(stream.try_clone()?),
            writer: Box::new(stream),
            stdio: false,
            _socket: None,
        });
    }
    Ok(Connection {
        reader: Box::new(std::io::stdin()),
        writer: Box::new(std::io::stdout()),
        stdio: true,
        shutdown: Arc::new(|| {}),
        _socket: None,
    })
}
static EVENT_STDOUT: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
pub fn event_stdout(enabled: bool) {
    EVENT_STDOUT.store(enabled, std::sync::atomic::Ordering::Release);
}
pub fn event(v: Value) {
    if EVENT_STDOUT.load(std::sync::atomic::Ordering::Acquire) {
        println!("{v}");
    } else {
        eprintln!("{v}");
    }
}
pub fn handshake(c: &mut Connection, role: &str, name: &str) -> Result<Value> {
    oxp_wire::write(
        &mut c.writer,
        &Message::hello(
            role,
            name,
            &if role == "consumer" {
                oxp_wire::context_client("default")
            } else {
                "default".into()
            },
        )?,
    )?;
    let peer = oxp_wire::read(&mut c.reader)?.ok_or("EOF before hello")?;
    match peer {
        Message::Json(v)
            if v["type"] == "hello"
                && v["role"]
                    == if role == "consumer" {
                        "producer"
                    } else {
                        "consumer"
                    } =>
        {
            Ok(v)
        }
        _ => Err("Expected peer hello with opposite role".into()),
    }
}
pub fn json_message(v: Value) -> Message {
    Message::Json(v)
}
pub fn video_format(f: &oxp_core::model::VideoFormat) -> Message {
    json_message(
        json!({"type":"video-format","screen":f.screen,"format":{"codec":match f.codec {oxp_media::VideoCodec::H264=>"H264",oxp_media::VideoCodec::H265=>"H265"},"codecString":f.codec_string,"config":STANDARD.encode(&f.config),"width":f.width,"height":f.height,"fpsHint":f.fps_hint.unwrap_or(30.0)}}),
    )
}
/// Session fencing matches the Kotlin WireInput binding.
#[derive(Default)]
pub struct Consumer {
    session: Option<oxp_core::model::SessionState>,
    formats: BTreeMap<u32, ()>,
    audio_formats: BTreeMap<u32, ()>,
}
impl Consumer {
    pub fn receive(
        &mut self,
        m: Message,
        output: &(impl oxp_core::InputSink + ?Sized),
    ) -> Result<()> {
        use oxp_core::model::*;
        match &m {
            Message::Json(v) => oxp_wire::validate_json(v)?,
            Message::Binary { .. } => {}
        }
        match m {
            Message::Json(v) => match v["type"].as_str().unwrap_or("") {
                "display-settings" => {
                    output.display_settings(oxp_wire::decode_display_settings(&v)?)
                }
                "view-area" => {
                    let (screen, area) = oxp_wire::decode_view_area(&v)?;
                    output.view_area(screen, area);
                }
                "context" => output.context(oxp_wire::decode_context(&v)?),
                "input-capabilities" => output.input_capabilities(input_capabilities(&v)?),
                "session" => {
                    let s = &v["session"];
                    let id = s["id"].as_str().ok_or("Session id")?.parse()?;
                    if self.session.as_ref().is_some_and(|old| id < old.id) {
                        return Ok(());
                    }
                    let phase = match s["phase"].as_str() {
                        Some("CONNECTING") => SessionPhase::Connecting,
                        Some("ACTIVE") => SessionPhase::Active,
                        Some("RECONNECTING") => SessionPhase::Reconnecting,
                        _ => SessionPhase::Ended,
                    };
                    if self.session.as_ref().is_none_or(|old| old.id != id)
                        || phase == SessionPhase::Ended
                    {
                        self.formats.clear();
                        self.audio_formats.clear();
                    }
                    let state = SessionState {
                        id,
                        phase,
                        reason: s["reason"].as_str().map(str::to_owned),
                    };
                    output.session(&state);
                    self.session = Some(state);
                }
                "video-format" => {
                    let screen = v["screen"].as_u64().ok_or("Screen")? as u32;
                    let f = &v["format"];
                    let format = if f.is_null() {
                        self.formats.remove(&screen);
                        None
                    } else {
                        let codec = if f["codec"] == "H264" {
                            VideoCodec::H264
                        } else {
                            VideoCodec::H265
                        };
                        let f = VideoFormat::create(
                            screen,
                            codec,
                            STANDARD
                                .decode(f["config"].as_str().ok_or("Config")?)?
                                .into(),
                            f["fpsHint"].as_f64(),
                        )?;
                        self.formats.insert(screen, ());
                        Some(Arc::new(f))
                    };
                    output.video_format(screen, format);
                }
                "audio-format" => {
                    let (stream, format) = oxp_wire::decode_audio_format(&v)?;
                    if format.is_some() {
                        self.audio_formats.insert(stream as u32, ());
                    } else {
                        self.audio_formats.remove(&(stream as u32));
                    }
                    output.audio_format(
                        stream as u32,
                        format.map(|f| {
                            Arc::new(oxp_core::model::AudioFormat {
                                stream: stream as u32,
                                role: match f.role {
                                    oxp_wire::AudioRole::Media => oxp_core::model::AudioRole::Media,
                                    oxp_wire::AudioRole::Alert => oxp_core::model::AudioRole::Alert,
                                    oxp_wire::AudioRole::Voice => oxp_core::model::AudioRole::Voice,
                                    oxp_wire::AudioRole::Telephony => {
                                        oxp_core::model::AudioRole::Telephony
                                    }
                                    oxp_wire::AudioRole::Other => oxp_core::model::AudioRole::Other,
                                },
                                codec: audio_codec(f.codec),
                                sample_rate: f.sample_rate,
                                channels: f.channels,
                                config: f.config.map(Arc::from),
                            })
                        }),
                    );
                }
                "mic" => {
                    let (stream, request) = oxp_wire::decode_microphone(&v)?;
                    output.microphone(
                        stream as u32,
                        request.map(|r| oxp_core::model::MicRequest {
                            stream: stream as u32,
                            codec: audio_codec(r.codec),
                            sample_rate: r.sample_rate,
                            channels: r.channels,
                            frame_ms: r.frame_ms,
                        }),
                    );
                }
                "error" => {
                    return Err(format!("Peer error: {}: {}", v["code"], v["message"]).into());
                }
                "hello" | "capabilities" | "client" | "restart" | "keyframe" | "stats" => {
                    return Err("Unexpected producer message".into());
                }
                _ => {}
            },
            Message::Binary { header, payload } => {
                if header.kind == Kind::Audio {
                    let state = self
                        .session
                        .as_ref()
                        .filter(|s| s.phase != SessionPhase::Ended)
                        .ok_or("Audio requires session")?;
                    if !self.audio_formats.contains_key(&(header.stream as u32)) {
                        return Err("Audio requires format".into());
                    }
                    output.audio_packet(oxp_core::model::AudioPacket {
                        stream: header.stream as u32,
                        session: state.id,
                        data: payload.into(),
                        pts_us: header.pts_us,
                    });
                    return Ok(());
                }
                if header.kind != Kind::Video {
                    return Err("Unexpected producer binary kind".into());
                }
                let state = self
                    .session
                    .as_ref()
                    .filter(|s| s.phase != SessionPhase::Ended)
                    .ok_or("Video requires session")?;
                if !self.formats.contains_key(&(header.stream as u32)) {
                    return Err("Video requires format".into());
                }
                let nanos = oxp_core::monotonic_nanos();
                output.video_frame(VideoFrame {
                    screen: header.stream as u32,
                    session: state.id,
                    data: payload.into(),
                    pts_us: header.pts_us,
                    key_frame: header.flags & 1 != 0,
                    received_at_nanos: nanos,
                });
            }
        }
        Ok(())
    }
}
pub struct FileTrust {
    path: PathBuf,
    lock: std::sync::Mutex<()>,
}
impl FileTrust {
    pub fn new(dir: &Path) -> Result<Self> {
        std::fs::create_dir_all(dir)?;
        std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
        let s = Self {
            path: dir.join("trust.json"),
            lock: Default::default(),
        };
        if s.path.exists() {
            std::fs::set_permissions(&s.path, std::fs::Permissions::from_mode(0o600))?;
        }
        Ok(s)
    }
    fn load(&self) -> Result<Value> {
        match std::fs::read(&self.path) {
            Ok(b) => {
                let v: Value = serde_json::from_slice(&b)?;
                if !v.is_object() || !v["peers"].is_object() {
                    return Err("Invalid trust store".into());
                }
                Ok(v)
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(json!({"peers":{}})),
            Err(e) => Err(e.into()),
        }
    }
    fn update(&self, f: impl FnOnce(&mut Value) -> Result<()>) -> Result<()> {
        let _g = self.lock.lock().unwrap();
        let mut v = self.load()?;
        f(&mut v)?;
        let tmp = self.path.with_extension("json.tmp");
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)?;
        let result = (|| {
            file.write_all(&serde_json::to_vec(&v)?)?;
            file.sync_all()?;
            std::fs::rename(&tmp, &self.path)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(tmp);
        }
        result
    }
}
impl oxp_clink::TrustStore for FileTrust {
    fn load_identity(&self) -> Result<Option<oxp_clink::protocol::AuthIdentity>> {
        let _g = self.lock.lock().unwrap();
        let v = self.load()?;
        match v.get("identity") {
            None => Ok(None),
            Some(i) => Ok(Some(oxp_clink::protocol::AuthIdentity::new(
                &STANDARD.decode(i["private"].as_str().ok_or("Invalid private key")?)?,
                &STANDARD.decode(i["public"].as_str().ok_or("Invalid public key")?)?,
            )?)),
        }
    }
    fn save_identity(&self, i: &oxp_clink::protocol::AuthIdentity) -> Result<()> {
        self.update(|v|{v["identity"]=json!({"private":STANDARD.encode(i.pkcs8()?),"public":STANDARD.encode(i.public_key())});Ok(())})
    }
    fn load_peer_key(&self, id: &str) -> Result<Option<Vec<u8>>> {
        let _g = self.lock.lock().unwrap();
        let v = self.load()?;
        v["peers"]
            .get(id)
            .map(|k| Ok(STANDARD.decode(k.as_str().ok_or("Invalid peer key")?)?))
            .transpose()
    }
    fn save_peer_key(&self, id: &str, key: &[u8]) -> Result<()> {
        self.update(|v| {
            v["peers"][id] = json!(STANDARD.encode(key));
            Ok(())
        })
    }
    fn forget_peer(&self, id: &str) -> Result<()> {
        self.update(|v| {
            v["peers"]
                .as_object_mut()
                .ok_or("Invalid peers")?
                .remove(id);
            Ok(())
        })
    }
}
/// Codec configuration and the pictures of a replay clip, each with its key-frame flag.
pub type ReplayFrames = (Vec<u8>, Vec<(Vec<u8>, bool)>);
/// Split at AUDs or a first slice; preserve non-VCL NALs with the next picture.
pub fn replay_frames(data: &[u8]) -> Result<ReplayFrames> {
    let units = oxp_media::nal::units(data, oxp_media::nal::Format::AnnexB)?;
    let sps = units.iter().find(|n| n[0] & 31 == 7).ok_or("Missing SPS")?;
    let pps = units.iter().find(|n| n[0] & 31 == 8).ok_or("Missing PPS")?;
    let config = oxp_media::AvcConfig::from_parameter_sets(sps, pps)?;
    let mut frames = Vec::new();
    let mut pending = Vec::new();
    let mut picture = false;
    let mut key = false;
    for n in units {
        let t = n[0] & 31;
        let vcl = (1..=5).contains(&t);
        if vcl && n.len() < 2 {
            return Err("Truncated slice".into());
        }
        if picture && (t == 9 || t == 7 || t == 8 || t == 6 || (vcl && n[1] & 0x80 != 0)) {
            frames.push((std::mem::take(&mut pending), key));
            picture = false;
            key = false;
        }
        pending.extend((n.len() as u32).to_be_bytes());
        pending.extend(n);
        picture |= vcl;
        key |= t == 5;
    }
    if picture {
        frames.push((pending, key));
    }
    if frames.is_empty() || !frames.iter().any(|f| f.1) {
        return Err("H.264 replay requires pictures and an IDR".into());
    }
    Ok((config, frames))
}

fn input_capabilities(v: &Value) -> Result<oxp_core::model::InputCapabilities> {
    use oxp_core::model::*;
    let codecs = v["codecs"]
        .as_array()
        .ok_or("Codecs")?
        .iter()
        .map(|c| {
            if c == "H264" {
                VideoCodec::H264
            } else {
                VideoCodec::H265
            }
        })
        .collect();
    Ok(InputCapabilities {
        codecs,
        screens: v["screens"]
            .as_array()
            .ok_or("Screens")?
            .iter()
            .map(|s| s.as_u64().unwrap() as u32)
            .collect(),
        touch: v["touch"].as_bool().unwrap(),
        keys: v["keys"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|v| {
                KeyCode::all()
                    .into_iter()
                    .find(|k| Some(k.wire_code() as u64) == v.as_u64())
            })
            .collect(),
        microphone: v["microphone"].as_bool().unwrap(),
        key_frame_requests: v["keyFrameRequests"].as_bool().unwrap(),
        reconfigure: match v["reconfigure"].as_str() {
            Some("NONE") => ReconfigureCost::None,
            Some("RESTART_SESSION") => ReconfigureCost::RestartSession,
            _ => ReconfigureCost::Unsupported,
        },
    })
}

fn audio_codec(c: oxp_wire::AudioCodec) -> AudioCodec {
    match c {
        oxp_wire::AudioCodec::AacLc => AudioCodec::AacLc,
        oxp_wire::AudioCodec::Opus => AudioCodec::Opus,
        oxp_wire::AudioCodec::PcmS16Le => AudioCodec::PcmS16le,
    }
}
