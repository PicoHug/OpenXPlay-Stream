// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

import type { DisplayInfo, DisplaySettings } from '../protocol/json';

function displayKey(d: DisplayInfo): string {
  return JSON.stringify([d.width, d.height, d.dpr, d.safeArea.top, d.safeArea.right, d.safeArea.bottom, d.safeArea.left, d.viewport, d.fullscreen, d.fullscreenPreferred]);
}
/** Duplicate observations do not postpone a pending report. */
export class DisplayDebounce {
  private reported = '';
  private pending = '';
  private since = 0;
  observe(display: DisplayInfo, now: number): boolean {
    const key = displayKey(display);
    if (key === this.reported) { this.pending = ''; return false; }
    if (key !== this.pending) { this.pending = key; this.since = now; }
    if (now - this.since < 500) return false;
    this.reported = key; this.pending = ''; return true;
  }
  seed(display: DisplayInfo): void { this.reported = displayKey(display); this.pending = ''; }
}
export class DisplaySettingsGate {
  supported = false;
  welcome(features?: string[]): void { this.supported = features?.includes('display-settings') ?? false; }
  message(resolution: DisplaySettings['resolution'], screenWidth: DisplaySettings['screenWidth'], videoCodec: DisplaySettings['videoCodec'] = 'auto'): DisplaySettings | undefined {
    return this.supported ? { type: 'display-settings', resolution, screenWidth, videoCodec } : undefined;
  }
}
export function pictureSize(width: number, height: number): string {
  return width && height ? `${width}×${height} (${(width / height).toFixed(2)}:1)` : '—';
}
