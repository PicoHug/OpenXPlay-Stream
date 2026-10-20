// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

pub use crate::context::*;
use crate::{require, Result};
pub use oxp_media::VideoCodec;
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionPhase {
    Connecting,
    Active,
    Reconnecting,
    Ended,
}
#[derive(Clone, Debug)]
pub struct SessionState {
    pub id: i64,
    pub phase: SessionPhase,
    pub reason: Option<String>,
}
#[derive(Clone, Debug)]
pub struct VideoFormat {
    pub screen: u32,
    pub codec: VideoCodec,
    pub config: Arc<[u8]>,
    pub codec_string: String,
    pub width: u32,
    pub height: u32,
    pub fps_hint: Option<f64>,
}
impl VideoFormat {
    pub fn create(
        screen: u32,
        codec: VideoCodec,
        config: Arc<[u8]>,
        fps_hint: Option<f64>,
    ) -> Result<Self> {
        require(
            fps_hint.is_none_or(|v| v.is_finite() && v > 0.0),
            "Invalid fps hint",
        )?;
        let (codec_string, width, height, n) = match codec {
            VideoCodec::H264 => {
                let p = oxp_media::AvcConfig::parse(&config)?;
                (p.codec_string, p.width, p.height, p.nal_length_size)
            }
            VideoCodec::H265 => {
                let p = oxp_media::HevcConfig::parse(&config)?;
                (p.codec_string, p.width, p.height, p.nal_length_size)
            }
        };
        require(n == 4, "Video frames require four-byte NAL lengths")?;
        Ok(Self {
            screen,
            codec,
            config,
            codec_string,
            width,
            height,
            fps_hint,
        })
    }
}
#[derive(Clone, Debug)]
pub struct VideoFrame {
    pub screen: u32,
    pub session: i64,
    pub data: Arc<[u8]>,
    pub pts_us: i64,
    pub key_frame: bool,
    pub received_at_nanos: i64,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u16)]
pub enum KeyCode {
    Home = 0,
    Back = 1,
    Select = 2,
    KnobLeft = 3,
    KnobRight = 4,
    KnobUp = 5,
    KnobDown = 6,
    PlayPause = 7,
    Next = 8,
    Previous = 9,
    Voice = 10,
}
impl KeyCode {
    pub fn wire_code(self) -> u16 {
        self as u16
    }
    pub fn all() -> BTreeSet<Self> {
        [
            Self::Home,
            Self::Back,
            Self::Select,
            Self::KnobLeft,
            Self::KnobRight,
            Self::KnobUp,
            Self::KnobDown,
            Self::PlayPause,
            Self::Next,
            Self::Previous,
            Self::Voice,
        ]
        .into_iter()
        .collect()
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Origin {
    pub output_id: String,
    pub client_id: String,
}
#[derive(Clone, Debug, PartialEq)]
pub struct TouchContact {
    pub id: u8,
    pub x: f64,
    pub y: f64,
    pub pressed: bool,
}
impl TouchContact {
    pub fn new(id: u8, x: f64, y: f64, pressed: bool) -> Result<Self> {
        require(
            id <= 9
                && x.is_finite()
                && y.is_finite()
                && (0.0..=1.0).contains(&x)
                && (0.0..=1.0).contains(&y),
            "Invalid touch contact",
        )?;
        Ok(Self { id, x, y, pressed })
    }
}
/// Touch events contain the complete contact set; omitted contacts are released.
#[derive(Clone, Debug, PartialEq)]
pub enum ControlEvent {
    DisplaySettings {
        settings: DisplaySettings,
        origin: Origin,
    },
    Touch {
        screen: u32,
        contacts: Vec<TouchContact>,
        origin: Origin,
    },
    Key {
        code: KeyCode,
        down: bool,
        origin: Origin,
    },
    KeyFrameRequest {
        screen: u32,
        origin: Origin,
    },
    MicPacket {
        stream: u32,
        data: Arc<[u8]>,
        pts_us: i64,
        origin: Origin,
    },
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LatencyClass {
    Interactive,
    Buffered,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DecoderSupport {
    pub codec_string: String,
    pub hardware: bool,
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SafeArea {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum FrameRatePreference {
    #[default]
    Auto,
    Fps30,
    Fps35,
    Fps40,
    Fps45,
    Fps50,
    Fps55,
    Fps60,
}
impl FrameRatePreference {
    pub fn from_wire(value: &str) -> Option<Self> {
        Some(match value {
            "AUTO" => Self::Auto,
            "FPS_30" => Self::Fps30,
            "FPS_35" => Self::Fps35,
            "FPS_40" => Self::Fps40,
            "FPS_45" => Self::Fps45,
            "FPS_50" => Self::Fps50,
            "FPS_55" => Self::Fps55,
            "FPS_60" => Self::Fps60,
            _ => return None,
        })
    }
    pub fn wire_name(self) -> &'static str {
        match self {
            Self::Auto => "AUTO",
            Self::Fps30 => "FPS_30",
            Self::Fps35 => "FPS_35",
            Self::Fps40 => "FPS_40",
            Self::Fps45 => "FPS_45",
            Self::Fps50 => "FPS_50",
            Self::Fps55 => "FPS_55",
            Self::Fps60 => "FPS_60",
        }
    }
}
/// Pure frame-rate selection shared by hubs and relay shells.
pub struct FrameRatePolicy;
impl FrameRatePolicy {
    pub fn resolve(
        preference: FrameRatePreference,
        refresh_hz: Option<f64>,
        sustained_fps: Option<f64>,
    ) -> f64 {
        match preference {
            FrameRatePreference::Auto => {
                if refresh_hz.is_some_and(|v| v < 50.0) || sustained_fps.is_some_and(|v| v < 50.0) {
                    30.0
                } else {
                    60.0
                }
            }
            FrameRatePreference::Fps30 => 30.0,
            FrameRatePreference::Fps35 => 35.0,
            FrameRatePreference::Fps40 => 40.0,
            FrameRatePreference::Fps45 => 45.0,
            FrameRatePreference::Fps50 => 50.0,
            FrameRatePreference::Fps55 => 55.0,
            FrameRatePreference::Fps60 => 60.0,
        }
    }
}
#[derive(Clone, Debug, PartialEq)]
pub struct DisplayInfo {
    pub viewport: Option<Viewport>,
    pub fullscreen: Option<bool>,
    pub fullscreen_preferred: Option<bool>,
    pub width_px: u32,
    pub height_px: u32,
    pub density_dpi: u32,
    pub refresh_hz: f64,
    pub frame_rate: FrameRatePreference,
    pub sustained_fps: Option<f64>,
    pub safe_area: SafeArea,
}
impl Default for DisplayInfo {
    fn default() -> Self {
        Self {
            viewport: None,
            fullscreen: None,
            fullscreen_preferred: None,
            width_px: 1280,
            height_px: 720,
            density_dpi: 160,
            refresh_hz: 60.0,
            frame_rate: FrameRatePreference::Auto,
            sustained_fps: None,
            safe_area: SafeArea::default(),
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum AudioCodec {
    AacLc,
    Opus,
    PcmS16le,
}
#[derive(Clone, Debug)]
pub struct OutputCapabilities {
    pub prefers_h264: bool,
    pub latency: LatencyClass,
    pub decoders: Vec<DecoderSupport>,
    pub displays: BTreeMap<u32, DisplayInfo>,
    pub touch: u32,
    pub keys: BTreeSet<KeyCode>,
    pub audio: BTreeSet<AudioCodec>,
    pub microphone: bool,
}
impl Default for OutputCapabilities {
    fn default() -> Self {
        Self {
            prefers_h264: false,
            latency: LatencyClass::Interactive,
            decoders: Vec::new(),
            displays: BTreeMap::new(),
            touch: 0,
            keys: BTreeSet::new(),
            audio: BTreeSet::new(),
            microphone: false,
        }
    }
}
#[derive(Clone, Debug)]
pub struct OutputStats {
    pub origin: Origin,
    pub screen: u32,
    pub decode_time_us: Option<i64>,
    pub decode_queue: u32,
    pub dropped: u64,
    pub rendered: u64,
    pub end_to_end_latency_us: Option<i64>,
    pub audio_buffered_us: Option<i64>,
    pub audio_underruns: u64,
}
/// Callbacks run on workers and must return promptly.
pub trait OutputHost: Send + Sync {
    fn capabilities(&self, caps: OutputCapabilities);
    fn control(&self, event: ControlEvent);
    fn stats(&self, stats: OutputStats);
    fn request_restart(&self, _origin: Origin) {}
}

/// Compatibility name for output implementations predating the hub.
pub use OutputHost as OutputSink;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AudioRole {
    Media,
    Alert,
    Voice,
    Telephony,
    Other,
}
#[derive(Clone, Debug)]
pub struct AudioFormat {
    pub stream: u32,
    pub role: AudioRole,
    pub codec: AudioCodec,
    pub sample_rate: u32,
    pub channels: u32,
    pub config: Option<Arc<[u8]>>,
}
#[derive(Clone, Debug)]
pub struct AudioPacket {
    pub stream: u32,
    pub session: i64,
    pub data: Arc<[u8]>,
    pub pts_us: i64,
}
#[derive(Clone, Debug)]
pub struct MicRequest {
    pub stream: u32,
    pub codec: AudioCodec,
    pub sample_rate: u32,
    pub channels: u32,
    pub frame_ms: u32,
}
#[derive(Clone, Debug, PartialEq)]
pub struct ScreenConfig {
    pub codec: VideoCodec,
    pub width: u32,
    pub height: u32,
    pub fps: f64,
}
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SessionConfig {
    pub screens: BTreeMap<u32, ScreenConfig>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReconfigureCost {
    None,
    RestartSession,
    Unsupported,
}
#[derive(Clone, Debug)]
pub struct InputCapabilities {
    pub codecs: Vec<VideoCodec>,
    pub screens: BTreeSet<u32>,
    pub touch: bool,
    pub keys: BTreeSet<KeyCode>,
    pub microphone: bool,
    pub key_frame_requests: bool,
    pub reconfigure: ReconfigureCost,
}
impl Default for InputCapabilities {
    fn default() -> Self {
        Self {
            codecs: vec![VideoCodec::H264, VideoCodec::H265],
            screens: [0].into_iter().collect(),
            touch: true,
            keys: KeyCode::all(),
            microphone: true,
            key_frame_requests: true,
            reconfigure: ReconfigureCost::Unsupported,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Viewport {
    pub width_px: u32,
    pub height_px: u32,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DisplaySettings {
    pub resolution: String,
    pub screen_width: u32,
    pub video_codec: String,
}
impl DisplaySettings {
    pub fn validate(&self) -> Result<()> {
        require(
            ["auto", "native", "1080p", "720p", "screen"].contains(&self.resolution.as_str())
                && [125, 200, 250, 300].contains(&self.screen_width)
                && ["auto", "h265", "h264"].contains(&self.video_codec.as_str()),
            "Invalid display settings",
        )
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ViewArea {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
    pub canvas_width: u32,
    pub canvas_height: u32,
}
impl ViewArea {
    pub fn validate(&self) -> Result<()> {
        require(
            self.width > 0
                && self.height > 0
                && u64::from(self.x) + u64::from(self.width) <= u64::from(self.canvas_width)
                && u64::from(self.y) + u64::from(self.height) <= u64::from(self.canvas_height),
            "Invalid view area",
        )
    }
}

#[cfg(test)]
mod frame_rate_tests {
    use super::*;
    #[test]
    fn fixed_rates_and_auto_thresholds() {
        for rate in (30..=60).step_by(5) {
            let name = format!("FPS_{rate}");
            let preference = FrameRatePreference::from_wire(&name).unwrap();
            assert_eq!(preference.wire_name(), name);
            assert_eq!(
                FrameRatePolicy::resolve(preference, Some(24.0), Some(10.0)),
                rate as f64
            );
        }
        for (refresh, sustained, expected) in [
            (None, None, 60.0),
            (Some(49.9), None, 30.0),
            (None, Some(49.9), 30.0),
            (Some(50.0), Some(50.0), 60.0),
        ] {
            assert_eq!(
                FrameRatePolicy::resolve(FrameRatePreference::Auto, refresh, sustained),
                expected
            );
        }
        assert!(FrameRatePreference::from_wire("FPS_42").is_none());
    }
}
