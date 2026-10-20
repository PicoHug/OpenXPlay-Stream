// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { BinaryKind, KeyCode, WireFormatError, decodeHeader, encodeHeader, decodeBinary, encodeBinary, decodeTouch, encodeTouch, decodeKey, encodeKey } from '../protocol/binary';
import { parseMessage } from '../protocol/json';
const vectors = new URL('../../../spec/vectors/', import.meta.url);
const webVectors = new URL('../spec/vectors/web/', import.meta.url);
function hex(name: string): Uint8Array {
  return Uint8Array.from(readFileSync(new URL(name, vectors), 'utf8').trim().split(/\s+/).map(v => parseInt(v, 16)));
}
describe('Kotlin wire compatibility', () => {
  it('matches the video header vector', () => {
    const bytes = hex('video-header.hex'), h = decodeHeader(bytes);
    expect(h).toEqual({ kind: BinaryKind.Video, stream: 1, flags: 3, sequence: 0x12345678,
      ptsUs: 0x0102030405060708n, senderClockUs: 0x1112131415161718n });
    expect(encodeHeader(h)).toEqual(bytes);
  });
  it('matches the touch vector and rounding', () => {
    const bytes = hex('touch-message.hex'), m = decodeBinary(bytes), contacts = decodeTouch(m.payload);
    expect(m.header).toEqual({ kind: BinaryKind.Touch, stream: 0, flags: 0, sequence: 42, ptsUs: 0n, senderClockUs: 1000000n });
    expect(contacts).toEqual([{ id: 2, pressed: true, x: 32768 / 65535, y: 1 }, { id: 7, pressed: false, x: 0, y: 16384 / 65535 }]);
    expect(encodeBinary(m.header, encodeTouch([{ id: 2, pressed: true, x: 0.5, y: 1 }, { id: 7, pressed: false, x: 0, y: 0.25 }]))).toEqual(bytes);
    expect(decodeTouch(encodeTouch([]))).toEqual([]);
  });
  it('preserves signed clocks and wrapping sequence', () => {
    const h = { kind: BinaryKind.Video, stream: 255, flags: 0, sequence: 0xffffffff, ptsUs: -(1n << 63n), senderClockUs: (1n << 63n) - 1n };
    expect(decodeHeader(encodeHeader(h))).toEqual(h);
  });
  it('supports every Kotlin key code', () => {
    for (let code = KeyCode.Home; code <= KeyCode.Voice; code++) for (const down of [true, false])
      expect(decodeKey(encodeKey({ code, down }))).toEqual({ code, down });
  });
  it('rejects malformed binary data with one error type', () => {
    for (const mutate of [(b: Uint8Array) => b.subarray(1), (b: Uint8Array) => { b[0] = 99; return b; },
      (b: Uint8Array) => { b[3] = 1; return b; }, (b: Uint8Array) => { b[2] = 4; return b; }])
      expect(() => decodeHeader(mutate(hex('video-header.hex')))).toThrow(WireFormatError);
    for (const bytes of [new Uint8Array(), new Uint8Array([11]), new Uint8Array([1, 10, 1, 0, 0, 0, 0]),
      new Uint8Array([1, 0, 2, 0, 0, 0, 0]), new Uint8Array([2, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0])])
      expect(() => decodeTouch(bytes)).toThrow(WireFormatError);
    expect(() => encodeTouch([{ id: 0, pressed: true, x: NaN, y: 0 }])).toThrow(WireFormatError);
    expect(() => decodeKey(new Uint8Array([11, 0, 0]))).toThrow(WireFormatError);
    expect(() => decodeKey(new Uint8Array([0, 0, 2]))).toThrow(WireFormatError);
    expect(() => decodeBinary(new Uint8Array(23))).toThrow(WireFormatError);
  });
});
describe('JSON contract', () => {
  it('accepts every shared example in its declared direction', () => {
    const dir = webVectors;
    for (const name of readdirSync(dir).filter(name => name.endsWith('.json'))) {
      const value = JSON.parse(readFileSync(new URL(name, dir), 'utf8'));
      const direction = ['fullscreen-exit', 'display-settings', 'hello', 'restart', 'keyframe', 'pause', 'stats', 'receiver-info', 'ping', 'pong'].includes(value.type) ? 'client-to-relay' : 'relay-to-client';
      expect(parseMessage(value, direction)).toEqual(value);
    }
  });
  it('validates preferences and restart strictly while retaining old hello compatibility', () => {
    const hello = JSON.parse(readFileSync(new URL('hello.json', webVectors), 'utf8'));
    expect(parseMessage(hello)).toEqual(hello);
    for (const playback of ['SMART', 'QUALITY', 'SPEED'])
      expect(parseMessage({ ...hello, playback })).toEqual({ ...hello, playback });
    for (const frameRate of ['AUTO', 'FPS_30', 'FPS_35', 'FPS_40', 'FPS_45', 'FPS_50', 'FPS_55', 'FPS_60'])
      parseMessage({ ...hello, displays: [{ ...hello.displays[0], frameRate, sustainedFps: 59.5 }] });
    for (const playback of ['AUTO', '', null, 1])
      expect(() => parseMessage({ ...hello, playback })).toThrow(WireFormatError);
    for (const fields of [{ frameRate: 'FPS_120' }, { frameRate: null }, { frameRate: 30 },
      { sustainedFps: 0 }, { sustainedFps: -1 }, { sustainedFps: null }, { sustainedFps: '60' },
      { sustainedFps: Infinity }, { sustainedFps: NaN }, { unknown: true }])
      expect(() => parseMessage({ ...hello, displays: [{ ...hello.displays[0], ...fields }] })).toThrow(WireFormatError);
    expect(parseMessage({ type: 'restart' }, 'client-to-relay')).toEqual({ type: 'restart' });
    expect(() => parseMessage({ type: 'restart' }, 'relay-to-client')).toThrow(WireFormatError);
    expect(() => parseMessage({ type: 'restart', screen: 0 })).toThrow(WireFormatError);
  });
  it('validates pause state, screen and direction', () => {
    for (const paused of [true, false])
      expect(parseMessage({ type: 'pause', screen: 0, paused }, 'client-to-relay')).toEqual({ type: 'pause', screen: 0, paused });
    for (const value of [{ type: 'pause', screen: 0 }, { type: 'pause', screen: 0, paused: 'true' },
      { type: 'pause', screen: 0, paused: 1 }, { type: 'pause', screen: 256, paused: true },
      { type: 'pause', screen: -1, paused: true }, { type: 'pause', screen: 0.5, paused: true },
      { type: 'pause', screen: 0, paused: null }, { type: 'pause', screen: 0, paused: true, extra: 1 }])
      expect(() => parseMessage(value)).toThrow(WireFormatError);
    expect(() => parseMessage({ type: 'pause', screen: 0, paused: true }, 'relay-to-client')).toThrow(WireFormatError);
  });
  it('accepts nullable stream ends and optional fields', () => {
    for (const value of [{ type: 'video-format', screen: 0, format: null }, { type: 'audio-format', stream: 1, format: null },
      { type: 'mic', stream: 1, request: null }, { type: 'session', session: { id: '-9223372036854775808', phase: 'ENDED', reason: 'Closed' } }])
      expect(parseMessage(JSON.stringify(value))).toEqual(value);
  });
  it('rejects malformed JSON, unknown fields, invalid clocks and wrong direction', () => {
    for (const value of ['{', 'null', '{}', { type: 'ping', t0Us: 1 }, { type: 'ping', t0Us: '9223372036854775808' },
      { type: 'ping', t0Us: '01' }, { type: 'ping', t0Us: '1', extra: true }, { type: 'keyframe', screen: 256 },
      { type: 'session', session: { id: '1', phase: 'BOGUS' } }, { type: 'video-format', screen: 0, format: {} }])
      expect(() => parseMessage(value)).toThrow(WireFormatError);
    const welcome = JSON.parse(readFileSync(new URL('welcome.json', webVectors), 'utf8'));
    expect(() => parseMessage({ ...welcome, sourceUrl: 'javascript:alert(1)' })).toThrow(WireFormatError);
    expect(() => parseMessage({ type: 'keyframe', screen: 0 }, 'relay-to-client')).toThrow(WireFormatError);
  });
});
