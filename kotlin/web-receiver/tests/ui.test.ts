// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { expect, it } from 'vitest';
import { gearDragged, gearOffset, clampGear, GearGesture, parseGearPosition, ReceiverPreferences, selectLanguage, statusCard, protocolStatus, strings, type StatusState } from '../receiver/ui';
function store() {
  const values = new Map<string, string>();
  return { getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); }, removeItem: (key: string) => { values.delete(key); } };
}
it('chooses the first supported language and falls back to English', () => {
  for (const language of ['zh', 'zh-CN', 'zh-SG', 'zh-Hans', 'zh-Hans-CN']) expect(selectLanguage([language])).toBe('zh-CN');
  expect(selectLanguage(['fr', 'zh-CN', 'en'])).toBe('zh-CN');
  expect(selectLanguage(['en-GB', 'zh-CN'])).toBe('en');
  for (const languages of [[], ['fr'], ['zh-TW'], ['zh-Hant']]) expect(selectLanguage(languages)).toBe('en');
  expect(Object.keys(strings['zh-CN'])).toEqual(Object.keys(strings.en));
});
it('persists settings and developer mode, supports debug fragment and allows switching it off', () => {
  const storage = store(), preferences = new ReceiverPreferences(() => storage);
  const values = preferences.load(new URLSearchParams());
  expect(values).toEqual({ frameRate: 'AUTO', playback: 'SMART', sound: true, microphone: true, fit: 'fit', developer: false, gear: { side: 'right', y: 0 } });
  preferences.save({ frameRate: 'AUTO', playback: 'SPEED', sound: false, microphone: false, fit: 'fill', developer: true, gear: { side: 'left', y: .3 } });
  expect(preferences.load(new URLSearchParams())).toEqual({ frameRate: 'AUTO', playback: 'SPEED', sound: false, microphone: false, fit: 'fill', developer: true, gear: { side: 'left', y: .3 } });
  values.developer = false; preferences.save(values);
  expect(preferences.load(new URLSearchParams()).developer).toBe(false);
  const debug = preferences.load(new URLSearchParams('debug')); expect(debug.developer).toBe(true);
  debug.developer = false; preferences.save(debug); expect(debug.developer).toBe(false);
});
it('tolerates localStorage access, read, write and clear failures', () => {
  for (const preferences of [new ReceiverPreferences(() => { throw Error('blocked'); }),
    new ReceiverPreferences(() => ({ getItem() { throw Error(); }, setItem() { throw Error(); }, removeItem() { throw Error(); } }))]) {
    const values = preferences.load(new URLSearchParams()); expect(values.fit).toBe('fit');
    expect(() => preferences.save(values)).not.toThrow();
  }
});
it('shows a useful status card for every non-playing state', () => {
  for (const state of ['connecting', 'waiting', 'reconnecting', 'error', 'unsupported'] as StatusState[]) {
    const card = statusCard(state); expect(card.hidden).toBe(false);
    for (const language of ['en', 'zh-CN'] as const) { expect(strings[language][card.headline]).toBeTruthy(); expect(strings[language][card.subline]).toBeTruthy(); }
  }
  expect(statusCard('playing').hidden).toBe(true);
});
it('keeps the required legal attribution visible in both languages', () => {
  for (const language of ['en', 'zh-CN'] as const) expect(strings[language].powered).toBe('Powered by OpenXPlay.');
});

it('maps protocol phases and errors without exposing technical text on the card', () => {
  expect(protocolStatus('ACTIVE', false)).toBe('waiting');
  expect(protocolStatus('ACTIVE', true)).toBe('playing');
  for (const phase of ['CONNECTING', 'ENDED']) expect(protocolStatus(phase, true)).toBe('waiting');
  for (const phase of ['RECONNECTING', 'Disconnected; reconnecting…']) expect(protocolStatus(phase, true)).toBe('reconnecting');
  expect(protocolStatus('Connecting…', false)).toBe('connecting');
  for (const phase of ['Protocol error: invalid JSON', 'Connection failed']) expect(protocolStatus(phase, true)).toBe('error');
});

it('offers browser guidance for unavailable or unsupported decoders', () => {
  for (const reason of ['Decoder unavailable: failed', 'Error: Unsupported codec avc1', 'WebCodecs is unavailable']) expect(protocolStatus(reason, false)).toBe('unsupported');
});
const bounds = { width: 800, height: 480, size: 48, top: 24, right: 32, bottom: 40, left: 16 };
it('starts a drag only beyond eight CSS pixels in any direction', () => {
  expect(gearDragged(8, 0)).toBe(false); expect(gearDragged(0, -8)).toBe(false);
  expect(gearDragged(4.8, 6.4)).toBe(false); expect(gearDragged(6, 6)).toBe(true);
  expect(gearDragged(-8.01, 0)).toBe(true);
});
it('migrates legacy positions and clamps freely without snapping', () => {
  expect(gearOffset({ side: 'left', y: 0 }, bounds)).toEqual({ x: 24, y: 24 });
  expect(gearOffset({ side: 'right', y: 1 }, bounds)).toEqual({ x: 720, y: 392 });
  expect(clampGear({ x: 350, y: 230 }, bounds)).toEqual({ x: 350, y: 230 });
  expect(clampGear({ x: -100, y: 900 }, bounds)).toEqual({ x: 24, y: 392 });
  expect(parseGearPosition('{"x":350,"y":230}')).toEqual({ x: 350, y: 230 });
});
it('reclamps saved free positions on rotation and tiny viewports', () => {
  expect(gearOffset({ x: 700, y: 350 }, { ...bounds, width: 480, height: 800 })).toEqual({ x: 400, y: 350 });
  expect(clampGear({ x: 900, y: -10 }, { ...bounds, width: 60, height: 60 })).toEqual({ x: 6, y: 6 });
});
it('distinguishes threshold taps, immediate drags and cancellation', () => {
  const tap = new GearGesture(); tap.move(8, 0); expect(tap.active).toBe(false); tap.finish(); expect(tap.suppressed).toBe(false);
  const drag = new GearGesture(); drag.move(9, 0); expect(drag.active).toBe(true); drag.move(0, 0); expect(drag.active).toBe(true); drag.finish(); expect(drag.suppressed).toBe(true);
  const cancel = new GearGesture(); cancel.finish(true); cancel.move(100, 100); expect(cancel.active).toBe(false); expect(cancel.suppressed).toBe(true);
});
it('validates stored positions and falls back to the top right', () => {
  for (const value of [null, undefined, '', '{', 'null', [], 1, { side: 'top', y: .3 }, { side: 'left', y: '0.3' },
    ...[NaN, Infinity, -.1, 1.1].map(y => ({ side: 'left', y }))]) expect(parseGearPosition(value)).toEqual({ side: 'right', y: 0 });
  expect(parseGearPosition('{"side":"left","y":0.3}')).toEqual({ side: 'left', y: .3 });
});

it('persists migrated free gear coordinates in the existing preference key', () => {
  const storage = store(), preferences = new ReceiverPreferences(() => storage);
  preferences.write('gear', JSON.stringify({ side: 'left', y: .3 }));
  const loaded = preferences.load(new URLSearchParams());
  loaded.gear = gearOffset(loaded.gear, bounds);
  preferences.save(loaded);
  expect(preferences.load(new URLSearchParams()).gear).toEqual({ x: 24, y: 24 + .3 * 368 });
});

it('places the default gear at the top right, respecting safe edges', () => {
  expect(gearOffset(parseGearPosition(null), bounds)).toEqual({x:720,y:24});
  expect(gearOffset(parseGearPosition(null), {...bounds,top:40,right:50})).toEqual({x:702,y:40});
});
