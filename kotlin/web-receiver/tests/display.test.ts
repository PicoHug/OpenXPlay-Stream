// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { DisplayDebounce, DisplaySettingsGate, pictureSize } from '../receiver/display';
import { strings } from '../receiver/ui';
import { parseMessage, type Hello } from '../protocol/json';
const vector = (name: string) => JSON.parse(readFileSync(new URL('../spec/vectors/web/' + name + '.json', import.meta.url), 'utf8'));
const display = (vector('hello-viewport') as Hello).displays[0];
it('reports only geometry stable for 500 ms, cancels reversion and ignores preference changes', () => {
  const gate = new DisplayDebounce(); gate.seed(display);
  const next = { ...display, viewport: { width: 1800, height: 900 } };
  expect(gate.observe(next, 0)).toBe(false);
  expect(gate.observe(next, 499)).toBe(false);
  expect(gate.observe(next, 500)).toBe(true);
  expect(gate.observe(next, 1000)).toBe(false);
  expect(gate.observe(display, 1100)).toBe(false);
  expect(gate.observe(next, 1500)).toBe(false);
  expect(gate.observe(display, 1501)).toBe(false);
  expect(gate.observe(display, 2000)).toBe(false);
  expect(gate.observe(display, 2001)).toBe(true);
  expect(gate.observe({ ...display, frameRate: 'FPS_30' }, 3000)).toBe(false);
  expect(gate.observe({ ...display, frameRate: 'FPS_30' }, 4000)).toBe(false);
});
it('observes fullscreen, DPR, safe area and screen changes independently of the CSS viewport', () => {
  for (const next of [{ ...display, fullscreen: true }, { ...display, dpr: 2 },
    { ...display, width: display.width + 1 }, { ...display, height: display.height + 1 },
    { ...display, safeArea: { ...display.safeArea, left: 10 } }]) {
    const gate = new DisplayDebounce(); gate.seed(display);
    expect(gate.observe(next, 0)).toBe(false); expect(gate.observe(next, 500)).toBe(true);
  }
});
it('requires a welcome feature and clears it on legacy welcome or disconnect', () => {
  const gate = new DisplaySettingsGate();
  expect(gate.supported).toBe(false); expect(gate.message('auto', 125)).toBeUndefined();
  gate.welcome(['unrelated']); expect(gate.message('auto', 125)).toBeUndefined();
  gate.welcome(['display-settings']);
  expect(gate.message('720p', 125, 'h265')).toEqual(vector('display-settings-client-codec'));
  expect(gate.message('native', 200)).toEqual(vector('display-settings-native'));
  gate.welcome(); expect(gate.supported).toBe(false);
});
it('accepts the exact vectors and rejects malformed viewport, feature and setting fields', () => {
  parseMessage(vector('hello-viewport'), 'client-to-relay');
  parseMessage(vector('welcome-display-settings'), 'relay-to-client');
  for (const name of ['display-settings-client-codec', 'display-settings-relay', 'display-settings-native'])
    for (const direction of ['client-to-relay', 'relay-to-client'] as const) expect(parseMessage(vector(name), direction)).toEqual(vector(name));
  for (const viewport of [{ width: 0, height: 1 }, { width: 1.5, height: 2 }, { width: 1 }, null, { width: 1, height: 2, extra: 0 }])
    expect(() => parseMessage({ ...vector('hello-viewport'), displays: [{ ...display, viewport }] })).toThrow();
  for (const fullscreen of [null, 1, 'false'])
    expect(() => parseMessage({ ...vector('hello-viewport'), displays: [{ ...display, fullscreen }] })).toThrow();
  for (const features of [null, 'display-settings', [1]])
    expect(() => parseMessage({ ...vector('welcome-display-settings'), features })).toThrow();
  for (const patch of [{ resolution: 'AUTO' }, { screenWidth: 150 }, { screenWidth: '200' }, { screenWidth: null }, { extra: 1 }])
    expect(() => parseMessage({ ...vector('display-settings-client'), ...patch })).toThrow();
});
it('provides hidden settings rows, bilingual labels and canvas/window ratios', () => {
  const html = readFileSync(new URL('../receiver/index.html', import.meta.url), 'utf8');
  expect(html).toContain('id="display-settings" hidden');
  const options = [...html.matchAll(/<option value="(auto|native|1080p|720p|screen)" data-text="(?:automatic|native|wholeScreen)"|<option value="(1080p|720p)"/g)].map(match => match[1] ?? match[2]);
  expect(options).toEqual(['auto', 'native', '1080p', '720p', 'screen']);
  expect(html).toContain('value="native" data-text="native"');
  expect(html).toContain('data-text="nativeHint"');
  expect(strings.en.native).toBe('Native'); expect(strings['zh-CN'].native).toBe('原画');
  expect(strings.en.nativeHint).toContain('sharpest'); expect(strings.en.nativeHint).toContain('Wi-Fi bandwidth');
  expect(strings['zh-CN'].nativeHint).toContain('最清晰'); expect(strings['zh-CN'].nativeHint).toContain('Wi-Fi 带宽');
  for (const id of ['resolution', 'screen-width', 'picture-size', 'relay-video-codec', 'video-codec-current']) expect(html).toContain('id="' + id + '"');
  const widths = [...html.matchAll(/<option value="(125|200|250|300)"/g)].map(match => Number(match[1]));
  expect(widths).toEqual([125, 200, 250, 300]);
  expect(html).toContain('data-text="screenWidthHint"');
  expect(strings.en.screenWidth).toBe('CarPlay screen width');
  expect(strings['zh-CN'].screenWidth).toBe('CarPlay 屏幕宽度');
  expect(strings.en.screenWidthHint).toContain('more icons on one page');
  expect(strings['zh-CN'].screenWidthHint).toContain('一页显示的图标越多');
  expect(pictureSize(1920, 960)).toBe('1920×960 (2.00:1)');
  expect(pictureSize(1533, 906)).toBe('1533×906 (1.69:1)');
  expect(pictureSize(0, 0)).toBe('—');
});

it('reports fullscreen intent changes through the display debounce even without a resize', () => {
  const gate = new DisplayDebounce(); gate.seed({ ...display, fullscreenPreferred: true });
  const next = { ...display, fullscreenPreferred: false };
  expect(gate.observe(next, 0)).toBe(false); expect(gate.observe(next, 499)).toBe(false);
  expect(gate.observe(next, 500)).toBe(true); expect(gate.observe(next, 1000)).toBe(false);
  expect(gate.observe({ ...next, fullscreenPreferred: true }, 1100)).toBe(false);
  expect(gate.observe({ ...next, fullscreenPreferred: true }, 1600)).toBe(true);
  const main = readFileSync(new URL('../receiver/main.ts', import.meta.url), 'utf8');
  expect(main).toContain('fullscreenPreferred: fullscreen.intent');
});
it('accepts optional boolean fullscreen preference and rejects other types', () => {
  for (const fullscreenPreferred of [true, false])
    parseMessage({ ...vector('hello-viewport'), displays: [{ ...display, fullscreenPreferred }] }, 'client-to-relay');
  for (const fullscreenPreferred of [null, 0, 'true', {}])
    expect(() => parseMessage({ ...vector('hello-viewport'), displays: [{ ...display, fullscreenPreferred }] })).toThrow();
  parseMessage(vector('hello-viewport'), 'client-to-relay');
});

it('accepts every numeric screen width in both directions and rejects missing or invalid presets', () => {
  for (const direction of ['client-to-relay', 'relay-to-client'] as const) {
    for (const screenWidth of [125, 200, 250, 300]) {
      const message = { ...vector('display-settings-relay'), screenWidth };
      expect(parseMessage(message, direction)).toEqual(message);
    }
    for (const screenWidth of [undefined, 100, 400, 200.5, '125', null, true])
      expect(() => parseMessage({ ...vector('display-settings-client'), screenWidth }, direction)).toThrow();
  }
});

it('requires codec in relay settings, accepts old clients and validates every codec', () => {
  const legacy = vector('display-settings-client');
  expect(parseMessage(legacy, 'client-to-relay')).toEqual(legacy);
  expect(() => parseMessage(legacy, 'relay-to-client')).toThrow();
  for (const videoCodec of ['auto', 'h265', 'h264'])
    for (const direction of ['client-to-relay', 'relay-to-client'] as const)
      expect(parseMessage({ ...legacy, videoCodec }, direction)).toEqual({ ...legacy, videoCodec });
  for (const videoCodec of [null, 'AUTO', 'H265', 1, true])
    expect(() => parseMessage({ ...legacy, videoCodec })).toThrow();
  const html = readFileSync(new URL('../receiver/index.html', import.meta.url), 'utf8');
  expect(html).not.toContain('id="video-codec"');
  expect(html.slice(html.indexOf('id="display-settings"'), html.indexOf('id="frame-rate"'))).toContain('id="relay-video-codec"');
});
