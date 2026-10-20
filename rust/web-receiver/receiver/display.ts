// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

import type { DisplayInfo, DisplaySettings } from '../protocol/json';
import type { Store } from './ui';

type ScreenSize = { width: number; height: number; dpr: number };
export class StableScreen {
  private maximum?: ScreenSize;
  constructor(private readonly storage: () => Pick<Store, 'getItem' | 'setItem'>) {
    try {
      const saved = JSON.parse(storage().getItem('oxp-screen-max') ?? 'null');
      if (saved && Number.isInteger(saved.width) && saved.width > 0 && Number.isInteger(saved.height) && saved.height > 0 && Number.isFinite(saved.dpr) && saved.dpr > 0)
        this.maximum = { width: saved.width, height: saved.height, dpr: saved.dpr };
    } catch { }
  }
  measure(width: number, height: number, dpr: number): ScreenSize {
    const next = { width: Math.max(1, Math.round(width * dpr)), height: Math.max(1, Math.round(height * dpr)), dpr };
    const last = this.maximum;
    if (last && last.dpr === dpr && (last.width >= last.height) === (next.width >= next.height)) {
      next.width = Math.max(last.width, next.width); next.height = Math.max(last.height, next.height);
    }
    if (!last || last.width !== next.width || last.height !== next.height || last.dpr !== dpr) {
      this.maximum = next;
      try { this.storage().setItem('oxp-screen-max', JSON.stringify(next)); } catch { }
    }
    return { ...next };
  }
}

function signature(display: DisplayInfo): string {
  const { width, height, dpr, safeArea, viewport, fullscreen, fullscreenPreferred } = display;
  return JSON.stringify([width, height, dpr, safeArea, viewport, fullscreen, fullscreenPreferred]);
}
export class DisplayDebounce {
  private sent = '';
  private candidate = '';
  private changedAt = 0;
  seed(display: DisplayInfo): void { this.sent = signature(display); this.candidate = ''; }
  observe(display: DisplayInfo, now: number): boolean {
    const next = signature(display);
    if (next === this.sent) { this.candidate = ''; return false; }
    if (next !== this.candidate) { this.candidate = next; this.changedAt = now; }
    if (now - this.changedAt < 500) return false;
    this.seed(display); return true;
  }
}
export class DisplaySettingsGate {
  supported = false;
  welcome(features?: string[]): void { this.supported = features?.includes('display-settings') === true; }
  message(resolution: DisplaySettings['resolution'], screenWidth: DisplaySettings['screenWidth'], videoCodec: DisplaySettings['videoCodec']): DisplaySettings | undefined {
    if (this.supported) return { type: 'display-settings', resolution, screenWidth, videoCodec };
  }
}
