// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

import { it, expect } from 'vitest';
import { FullscreenIntent, FullscreenExitHistory, autoRestoreFullscreen } from '../receiver/fullscreen';
const storage = () => ({ getItem: () => null, setItem: () => {}, removeItem: () => {} });
it('requests on mouse down and consumes the release even after failure', () => {
  const intent = new FullscreenIntent(true, storage);
  intent.changed(true); expect(intent.changed(false)).toBe(true);
  expect(intent.pointerDown({ pointerId: 1, pointerType: 'mouse' })).toEqual({ swallow: true, request: true });
  intent.denied(); expect(intent.failed).toBe(true); expect(intent.pending).toBe(false);
  expect(intent.pointerUp(1)).toEqual({ swallow: true, request: false });
  expect(intent.pointerDown({ pointerId: 2, pointerType: 'mouse' })).toEqual({ swallow: false, request: false });
  expect(intent.pointerUp(2)).toEqual({ swallow: false, request: false });
  intent.changed(true); intent.changed(false); expect(intent.pending).toBe(true);
  intent.setRestore(false); expect(intent.pending).toBe(false);
});
for (const pointerType of ['touch', 'pen']) it(`defers ${pointerType} restore until the matching release`, () => {
  const intent = new FullscreenIntent(true, storage);
  expect(intent.pointerDown({ pointerId: 3, pointerType })).toEqual({ swallow: true, request: false });
  expect(intent.pointerUp(4)).toEqual({ swallow: false, request: false });
  expect(intent.pointerUp(3)).toEqual({ swallow: true, request: true });
  expect(intent.pointerUp(3)).toEqual({ swallow: false, request: false });
  intent.denied();
  expect(intent.pointerDown({ pointerId: 5, pointerType })).toEqual({ swallow: false, request: false });
});
it('consumes cancellation without requesting and leaves the next tap pending', () => {
  const intent = new FullscreenIntent(true, storage);
  intent.pointerDown({ pointerId: 1, pointerType: 'touch' });
  expect(intent.pointerUp(1, true)).toEqual({ swallow: true, request: false });
  expect(intent.pending).toBe(true); expect(intent.failed).toBe(false);
  expect(intent.pointerDown({ pointerId: 2, pointerType: 'touch' })).toEqual({ swallow: true, request: false });
  expect(intent.pointerUp(2)).toEqual({ swallow: true, request: true });
});
it('shows unavailable only for unsupported fullscreen or a failed enabled intent', () => {
  const intent = new FullscreenIntent(true, storage); let requested = 0;
  expect(intent.state).toBe('enabled');
  intent.denied(); expect(intent.state).toBe('fullscreenUnavailable');
  intent.toggle(() => requested++, () => {});
  expect(intent.intent).toBe(false); expect(intent.failed).toBe(false); expect(intent.state).toBe('off');
  intent.toggle(() => requested++, () => {});
  expect(requested).toBe(1); expect(intent.state).toBe('enabled');
  const unavailable = new FullscreenIntent(false, storage);
  expect(unavailable.state).toBe('fullscreenUnavailable');
  unavailable.toggle(() => requested++, () => {});
  expect(unavailable.state).toBe('fullscreenUnavailable'); expect(requested).toBe(1);
});
it('tries twice with a 500 ms retry, checks visibility and returns diagnostics', async () => {
  let calls = 0, enabled = true, hidden = true; const delays: number[] = [];
  const options = { enabled: () => enabled, restored: () => false, hidden: () => hidden,
    visible: async () => { hidden = false; }, request: async () => { calls++; throw Error(); }, delay: async (ms: number) => { delays.push(ms); } };
  expect(await autoRestoreFullscreen(options)).toBe('denied'); expect(calls).toBe(2); expect(delays).toEqual([500]);
  enabled = false; expect(await autoRestoreFullscreen(options)).toBe('off'); expect(calls).toBe(2);
  enabled = true; expect(await autoRestoreFullscreen({ ...options, request: async () => {} })).toBe('restored');
});
it('records only the recent hidden interval', () => {
  const history = new FullscreenExitHistory(); expect(history.take(10)).toBeNull();
  history.visibility(true, 100); history.visibility(false, 1600); expect(history.take(1700)).toBe(1500); expect(history.take(1800)).toBeNull();
  history.visibility(true, 2000); history.visibility(false, 2500); expect(history.take(5001)).toBeNull();
});
