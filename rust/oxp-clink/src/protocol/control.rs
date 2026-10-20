// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use super::{FieldValue as V, Fields};
use crate::{model::DisplayInfo, require, Result};
pub fn display_body(d: &DisplayInfo) -> Result<Vec<u8>> {
    let mut f = Fields::default();
    for (id, n) in [
        (2, d.width_px),
        (3, d.height_px),
        (4, d.density_dpi),
        (5, d.width_px),
        (6, d.height_px),
        (7, d.refresh_hz as u32),
        (8, 0),
        (9, 0),
        (10, 0),
        (12, 0),
        (13, 0),
        (14, 1),
        (15, 0),
    ] {
        f.0.insert(id, V::Varint(n as u64));
    }
    f.0.insert(17, V::Bytes(b"openxplay-stream-1".to_vec()));
    f.encode()
}
pub fn display(body: &[u8], fallback: &DisplayInfo) -> Result<DisplayInfo> {
    let f = Fields::decode(body)?;
    require(f.number(10)? == 0, "projection_mode_required")?;
    let value = |id, default| -> Result<u32> {
        if f.0.contains_key(&id) {
            let n = f.number(id)?;
            require((1..=16384).contains(&n), "invalid_display")?;
            Ok(n as u32)
        } else {
            Ok(default)
        }
    };
    Ok(DisplayInfo {
        width_px: value(2, fallback.width_px)?,
        height_px: value(3, fallback.height_px)?,
        density_dpi: value(4, fallback.density_dpi)?,
        refresh_hz: value(7, fallback.refresh_hz as u32)? as f64,
        safe_area: Default::default(),
        frame_rate: fallback.frame_rate,
        sustained_fps: fallback.sustained_fps,
        viewport: None,
        fullscreen: None,
        fullscreen_preferred: None,
    })
}
/// Head-unit display assumed until the head unit reports its own; Carlink sinks run at 30 FPS.
pub fn head_unit_fallback_display() -> DisplayInfo {
    DisplayInfo {
        width_px: 1280,
        height_px: 720,
        density_dpi: 160,
        refresh_hz: 30.0,
        ..Default::default()
    }
}
pub fn server_response(port: u16) -> Result<Vec<u8>> {
    Fields(std::collections::BTreeMap::from([
        (1, V::Varint(if port == 0 { 2 } else { 1 })),
        (2, V::Varint(port as u64)),
    ]))
    .encode()
}
