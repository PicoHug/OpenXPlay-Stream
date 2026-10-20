// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

pub use crate::model::OutputHost;
use crate::{model::*, Result};
use std::sync::Arc;

/// Media callbacks retain shared data and return promptly without socket I/O.
pub trait ProjectionOutput: Send + Sync {
    fn id(&self) -> &str;
    fn open(&self, host: Arc<dyn OutputHost>) -> Result<()>;
    /// Opts into asynchronous context replay and updates when attached to a hub.
    fn wants_context(&self) -> bool {
        false
    }
    fn on_context(&self, _update: crate::ContextUpdate) {}
    fn on_display_settings(&self, _settings: DisplaySettings) {}
    /// None clears the crop and declares view-area support.
    fn on_view_area(&self, _screen: u32, _area: Option<ViewArea>) {}
    fn on_session(&self, state: &SessionState, config: &SessionConfig);
    fn on_video_format(&self, screen: u32, format: Option<Arc<VideoFormat>>);
    fn on_video_frame(&self, frame: VideoFrame);
    fn on_audio_format(&self, _stream: u32, _format: Option<Arc<AudioFormat>>) {}
    fn on_audio_packet(&self, _packet: AudioPacket) {}
    fn on_microphone(&self, _stream: u32, _request: Option<MicRequest>) {}
    fn close(&self);
}

/// In-process destination for a producer, independent of the selected outputs.
pub trait InputSink: Send + Sync {
    fn display_settings(&self, _settings: DisplaySettings) {}
    fn view_area(&self, _screen: u32, _area: Option<ViewArea>) {}
    fn context(&self, _update: crate::ContextUpdate) {}
    fn input_capabilities(&self, _capabilities: InputCapabilities) {}
    fn session(&self, state: &SessionState);
    fn video_format(&self, screen: u32, format: Option<Arc<VideoFormat>>);
    fn video_frame(&self, frame: VideoFrame);
    fn audio_format(&self, stream: u32, format: Option<Arc<AudioFormat>>);
    fn audio_packet(&self, packet: AudioPacket);
    fn microphone(&self, stream: u32, request: Option<MicRequest>);
}
impl<T: ProjectionOutput + ?Sized> InputSink for T {
    fn display_settings(&self, settings: DisplaySettings) {
        self.on_display_settings(settings);
    }
    fn view_area(&self, screen: u32, area: Option<ViewArea>) {
        self.on_view_area(screen, area);
    }
    fn context(&self, update: crate::ContextUpdate) {
        if self.wants_context() {
            self.on_context(update);
        }
    }
    fn session(&self, s: &SessionState) {
        self.on_session(s, &SessionConfig::default());
    }
    fn video_format(&self, s: u32, f: Option<Arc<VideoFormat>>) {
        self.on_video_format(s, f);
    }
    fn video_frame(&self, f: VideoFrame) {
        self.on_video_frame(f);
    }
    fn audio_format(&self, s: u32, f: Option<Arc<AudioFormat>>) {
        self.on_audio_format(s, f);
    }
    fn audio_packet(&self, p: AudioPacket) {
        self.on_audio_packet(p);
    }
    fn microphone(&self, s: u32, r: Option<MicRequest>) {
        self.on_microphone(s, r);
    }
}
