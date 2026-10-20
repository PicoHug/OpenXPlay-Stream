// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

import { it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { videoCodecPreference, withVideoCodec } from '../shared/video-codec';
import { ReceiverPreferences, strings } from '../receiver/ui';
import { parseMessage, type Hello } from '../protocol/json';

const hello = JSON.parse(readFileSync(new URL('../spec/vectors/web/hello-video-codec-h264.json', import.meta.url), 'utf8')) as Hello;
it('ignores the old stored browser preference while retaining legacy codec validation', () => {
  const values = new Map<string, string>();
  const storage = new ReceiverPreferences(() => ({ getItem: k => values.get(k) ?? null, setItem: (k, v) => { values.set(k, v); }, removeItem: k => { values.delete(k); } }));
  const prefs = storage.load(new URLSearchParams());
  expect(prefs.videoCodec).toBe('AUTO');
  prefs.videoCodec = 'H264'; storage.save(prefs);
  expect(storage.load(new URLSearchParams()).videoCodec).toBe('AUTO');
  const forced = withVideoCodec(hello, 'H264');
  expect(forced.decoders).toBe(hello.decoders);
  expect(parseMessage(forced)).toEqual(forced);
  const automatic = withVideoCodec(forced, 'AUTO');
  expect(automatic.videoCodec).toBeUndefined();
  expect(automatic.decoders).toBe(hello.decoders);
  for (const value of [null, undefined, '', 'h264', 'H265']) expect(videoCodecPreference(value)).toBe('AUTO');
  for (const language of ['en', 'zh-CN'] as const)
    for (const key of ['videoCodec', 'codecAuto', 'codecH264', 'codecHint'] as const) expect(strings[language][key]).toBeTruthy();
});
it('accepts only AUTO and H264 and supports legacy hello', () => {
  for (const videoCodec of ['AUTO', 'H264']) expect(parseMessage({ ...hello, videoCodec })).toEqual({ ...hello, videoCodec });
  parseMessage(withVideoCodec(hello, 'AUTO'));
  for (const videoCodec of ['H265', '', null, 1, true]) expect(() => parseMessage({ ...hello, videoCodec })).toThrow();
});
