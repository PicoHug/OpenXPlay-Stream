// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

import { it, expect } from 'vitest';
import { StableScreen, DisplayDebounce, DisplaySettingsGate } from '../receiver/display';
import { parseMessage, type DisplayInfo } from '../protocol/json';
import { readFileSync } from 'node:fs';
const hello = JSON.parse(readFileSync(new URL('../spec/vectors/web/hello-fullscreen-preferred.json', import.meta.url), 'utf8'));
const display: DisplayInfo = hello.displays[0];
it('debounces changed geometry, viewport and fullscreen intent for 500 ms', () => {
  const debounce = new DisplayDebounce(); debounce.seed(display);
  const changed = { ...display, viewport: { width: 800, height: 600 } };
  expect(debounce.observe(changed, 0)).toBe(false);
  expect(debounce.observe(changed, 300)).toBe(false);
  expect(debounce.observe(changed, 500)).toBe(true);
  expect(debounce.observe(changed, 1000)).toBe(false);
  expect(debounce.observe({ ...changed, fullscreenPreferred: false }, 1100)).toBe(false);
  expect(debounce.observe({ ...changed, fullscreenPreferred: false }, 1600)).toBe(true);
});
it('requires feature negotiation and sends every settings request without throttling', () => {
  const gate = new DisplaySettingsGate(); expect(gate.message('auto', 200, 'auto')).toBeUndefined();
  gate.welcome(['display-settings']);
  for (const resolution of ['auto', 'native', '1080p', '720p', 'screen'] as const)
    for (const screenWidth of [125, 200, 250, 300] as const)
      for (const videoCodec of ['auto', 'h265', 'h264'] as const)
        expect(parseMessage(gate.message(resolution, screenWidth, videoCodec), 'client-to-relay')).toEqual({ type: 'display-settings', resolution, screenWidth, videoCodec });
  gate.welcome(); expect(gate.supported).toBe(false);
});
it('rejects malformed optional display fields and message directions', () => {
  for (const fields of [{ viewport: null }, { viewport: { width: 0, height: 10 } }, { viewport: { width: 1.5, height: 10 } }, { fullscreen: null }, { fullscreenPreferred: 'true' }])
    expect(() => parseMessage({ ...hello, displays: [{ ...display, ...fields }] })).toThrow();
  for (const fields of [{ resolution: 'bad' }, { screenWidth: 150 }, { videoCodec: 'H265' }, { videoCodec: undefined }])
    expect(() => parseMessage({ type: 'display-settings', resolution: 'auto', screenWidth: 200, videoCodec: 'auto', ...fields }, 'relay-to-client')).toThrow();
  const area = { type: 'view-area', x: 100, y: 10, width: 300, height: 200, canvasWidth: 800, canvasHeight: 400 };
  expect(() => parseMessage(area, 'client-to-relay')).toThrow();
  for (const fields of [{ width: 0 }, { x: -1 }, { x: 501 }, { height: 391 }, { canvasWidth: 0 }]) expect(() => parseMessage({ ...area, ...fields })).toThrow();
  const exit = { type: 'fullscreen-exit', hiddenMs: null, display };
  expect(parseMessage(exit, 'client-to-relay')).toEqual(exit);
  for (const fields of [{ hiddenMs: -1 }, { autoRestore: 'bad' }, { display: null }]) expect(() => parseMessage({ ...exit, ...fields })).toThrow();
  expect(() => parseMessage(exit, 'relay-to-client')).toThrow();
});

const screenStorage = () => {
  const values = new Map<string, string>();
  return { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); } };
};
const dpr = 2.625;
const windowed = { width: 2402, height: 1082, dpr };
const full = { width: 2328, height: 1082, dpr };
it('keeps the windowed screen maximum in fullscreen and across reloads', () => {
  const store = screenStorage(), screen = new StableScreen(() => store);
  expect(screen.measure(915, 412, dpr)).toEqual(windowed);
  expect(screen.measure(887, 412, dpr)).toEqual(windowed);
  expect(new StableScreen(() => store).measure(887, 412, dpr)).toEqual(windowed);
});
it('grows once when fullscreen is measured first and retains each dimension maximum', () => {
  const store = screenStorage(), screen = new StableScreen(() => store);
  expect(screen.measure(887, 412, dpr)).toEqual(full);
  expect(screen.measure(915, 412, dpr)).toEqual(windowed);
  expect(screen.measure(887, 412, dpr)).toEqual(windowed);
  expect(screen.measure(887, 420, dpr)).toEqual({ ...windowed, height: 1103 });
  expect(screen.measure(915, 412, dpr)).toEqual({ ...windowed, height: 1103 });
});
it('starts a new maximum on orientation or dpr changes', () => {
  const store = screenStorage(), screen = new StableScreen(() => store);
  screen.measure(915, 412, dpr);
  expect(screen.measure(412, 915, dpr)).toEqual({ width: 1082, height: 2402, dpr });
  expect(screen.measure(412, 887, dpr)).toEqual({ width: 1082, height: 2402, dpr });
  expect(screen.measure(887, 412, dpr)).toEqual(full);
  expect(screen.measure(887, 412, 2)).toEqual({ width: 1774, height: 824, dpr: 2 });
  expect(new StableScreen(() => store).measure(800, 400, 2)).toEqual({ width: 1774, height: 824, dpr: 2 });
});
it('keeps session maxima when storage access, reads or writes throw', () => {
  for (const storage of [
    () => { throw Error(); },
    () => ({ getItem: () => { throw Error(); }, setItem: () => { throw Error(); } }),
    () => ({ getItem: () => null, setItem: () => { throw Error(); } }),
  ]) {
    const screen = new StableScreen(storage);
    expect(screen.measure(915, 412, dpr)).toEqual(windowed);
    expect(screen.measure(887, 412, dpr)).toEqual(windowed);
  }
});
it('ignores invalid persisted screen sizes', () => {
  for (const saved of ['bad json', 'null', '{}', '{"width":-1,"height":1082,"dpr":2.625}', '{"width":2402,"height":1082,"dpr":"2.625"}']) {
    const store = screenStorage(); store.setItem('oxp-screen-max', saved);
    expect(new StableScreen(() => store).measure(887, 412, dpr)).toEqual(full);
  }
});
