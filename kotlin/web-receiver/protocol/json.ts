// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { check, WireFormatError } from './binary';
export type I64 = string;
export type AudioCodec = 'AAC_LC' | 'OPUS' | 'PCM_S16LE';
export interface SessionState { id: I64; phase: 'CONNECTING' | 'ACTIVE' | 'RECONNECTING' | 'ENDED'; reason?: string }
export type FrameRatePreference = 'AUTO' | 'FPS_30' | 'FPS_35' | 'FPS_40' | 'FPS_45' | 'FPS_50' | 'FPS_55' | 'FPS_60';
export type VideoCodecPreference = 'AUTO' | 'H264';
export type PlaybackMode = 'SMART' | 'QUALITY' | 'SPEED';
export interface DisplayInfo {
  screen: number; width: number; height: number; dpr: number; refreshHz: number;
  viewport?: { width: number; height: number }; fullscreen?: boolean; fullscreenPreferred?: boolean;
  frameRate?: FrameRatePreference; sustainedFps?: number;
  safeArea: { top: number; right: number; bottom: number; left: number };
}
export interface Hello {
  type: 'hello'; version: 0; token: string; decoders: { codecString: string; hardware: boolean }[];
  playback?: PlaybackMode; videoCodec?: VideoCodecPreference; displays: DisplayInfo[]; maxTouchContacts: number; audio: AudioCodec[]; microphone: boolean;
}
export interface VideoFormat { codec: 'H264' | 'H265'; codecString: string; config: string; width: number; height: number; fpsHint?: number }
export interface AudioFormat { role: 'MEDIA' | 'ALERT' | 'VOICE' | 'TELEPHONY' | 'OTHER'; codec: AudioCodec; sampleRate: number; channels: number; config?: string }
export interface MicFormat { codec: AudioCodec; sampleRate: number; channels: number; frameMs: number }
export interface ReceiverInfo { type: 'receiver-info'; [key: string]: unknown }
export interface Stats {
  wsBytesPerSecond?: number; videoFramesPerSecond?: number; decoderSubmittedPerSecond?: number; decoderOutputPerSecond?: number;
  decodeLatencyP50Ms?: number; decodeLatencyP95Ms?: number; decodeLatencyMaxMs?: number; maxDecodeQueue?: number;
  decoderErrors?: number; decoderResets?: number; renderedFps?: number; droppedFrames?: number;
  renderLatencyP50Ms?: number; renderLatencyP95Ms?: number; eventLoopLagAvgMs?: number; eventLoopLagMaxMs?: number;
  longTaskCount?: number; longTaskTotalMs?: number; usedJSHeapSize?: number; renderDepth?: number;
  audioBufferedMs?: number | null; audioUnderruns?: number;
  type: 'stats'; screen: number; decodeMs: number | null; decodeQueue: number; dropped: number; rendered: number;
  decodedLatencyMs: number | null; renderedLatencyMs: number | null; clockOffsetUs: I64 | null; rttUs: I64 | null;
}
export interface DisplaySettings { type: 'display-settings'; resolution: 'auto' | 'native' | '1080p' | '720p' | 'screen'; screenWidth: 125 | 200 | 250 | 300; videoCodec: 'auto' | 'h265' | 'h264' }
export type ClientDisplaySettings = Omit<DisplaySettings, 'videoCodec'> & { videoCodec?: DisplaySettings['videoCodec'] };
export interface ViewArea { type: 'view-area'; x: number; y: number; width: number; height: number; canvasWidth: number; canvasHeight: number }
export interface FullscreenExit { type: 'fullscreen-exit'; hiddenMs: number | null; display: DisplayInfo; autoRestore?: 'restored' | 'denied' | 'off' }
export type JsonMessage = ViewArea | FullscreenExit | ClientDisplaySettings | Hello | { type: 'restart' } |
  { type: 'welcome'; receiverBuild?: string; features?: string[]; relay?: { name: string; version: string }; plugins?: { host: string }; version: 0; clientId: string; session: SessionState; sourceUrl: string; attribution: string; projectUrl: string } |
  { type: 'session'; session: SessionState } |
  { type: 'video-format'; screen: number; format: VideoFormat | null } |
  { type: 'audio-format'; stream: number; format: AudioFormat | null } |
  { type: 'mic'; stream: number; request: MicFormat | null } |
  { type: 'keyframe'; screen: number } |
  { type: 'pause'; screen: number; paused: boolean } |
  { type: 'ping'; t0Us: I64 } |
  { type: 'pong'; t0Us: I64; t1Us: I64; t2Us: I64 } |
  ReceiverInfo | Stats | { type: 'error'; code: string; message: string };
export type Direction = 'client-to-relay' | 'relay-to-client';
type Validator = (x: unknown) => boolean;
const str: Validator = x => typeof x === 'string';
const nonempty: Validator = x => str(x) && (x as string).length > 0;
const bool: Validator = x => typeof x === 'boolean';
const num: Validator = x => typeof x === 'number' && Number.isFinite(x) && x >= 0;
const positive: Validator = x => num(x) && (x as number) > 0;
const int: Validator = x => num(x) && Number.isSafeInteger(x);
const range = (max: number): Validator => x => int(x) && (x as number) <= max;
const literal = (...v: unknown[]): Validator => x => v.includes(x);
const optional = (v: Validator): Validator => x => x === undefined || v(x);
const nullable = (v: Validator): Validator => x => x === null || v(x);
const array = (v: Validator): Validator => x => Array.isArray(x) && x.every(v);
const i64: Validator = x => {
  if (typeof x !== 'string' || !/^(0|-?[1-9][0-9]*)$/.test(x) || x.length > 20) return false;
  const n = BigInt(x); return n >= -(1n << 63n) && n < (1n << 63n);
};
const base64: Validator = x => typeof x === 'string' && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(x);
const url: Validator = x => {
  if (typeof x !== 'string') return false;
  try { const u = new URL(x); return ['https:', 'http:'].includes(u.protocol) && !u.username && !u.password; } catch { return false; }
};
function object(fields: Record<string, Validator>): Validator {
  return x => typeof x === 'object' && x !== null && !Array.isArray(x) &&
    Object.keys(x).every(k => Object.hasOwn(fields, k)) && Object.entries(fields).every(([k, v]) => v((x as Record<string, unknown>)[k]));
}
const audioCodec = literal('AAC_LC', 'OPUS', 'PCM_S16LE');
const session = object({ id: i64, phase: literal('CONNECTING', 'ACTIVE', 'RECONNECTING', 'ENDED'), reason: optional(str) });
const display = object({ screen: range(255), width: x => int(x) && positive(x), height: x => int(x) && positive(x), dpr: positive, refreshHz: positive,
  viewport: optional(object({ width: x => int(x) && positive(x), height: x => int(x) && positive(x) })), fullscreen: optional(bool), fullscreenPreferred: optional(bool),
  frameRate: optional(literal('AUTO', 'FPS_30', 'FPS_35', 'FPS_40', 'FPS_45', 'FPS_50', 'FPS_55', 'FPS_60')), sustainedFps: optional(positive),
  safeArea: object({ top: num, right: num, bottom: num, left: num }) });
const video = object({ codec: literal('H264', 'H265'), codecString: nonempty, config: x => nonempty(x) && base64(x),
  width: x => int(x) && positive(x), height: x => int(x) && positive(x), fpsHint: optional(positive) });
const audio = object({ role: literal('MEDIA', 'ALERT', 'VOICE', 'TELEPHONY', 'OTHER'), codec: audioCodec,
  sampleRate: x => int(x) && positive(x), channels: x => int(x) && positive(x), config: optional(base64) });
const mic = object({ codec: audioCodec, sampleRate: x => int(x) && positive(x), channels: x => int(x) && positive(x), frameMs: positive });
const extensible = (schema: Validator): Validator => x => {
  if (typeof x !== 'object' || x === null || Array.isArray(x)) return false;
  const base = ['type', 'screen', 'decodeMs', 'decodeQueue', 'dropped', 'rendered', 'decodedLatencyMs', 'renderedLatencyMs', 'clockOffsetUs', 'rttUs', 'audioBufferedMs', 'audioUnderruns'];
  const known = Object.fromEntries(Object.entries(x).filter(([key]) => base.includes(key)));
  return schema(known) && diagnosticFields.every(key => !(key in x) || optional(num)((x as Record<string, unknown>)[key]));
};
const diagnosticFields = [
  'wsBytesPerSecond', 'videoFramesPerSecond', 'decoderSubmittedPerSecond', 'decoderOutputPerSecond',
  'decodeLatencyP50Ms', 'decodeLatencyP95Ms', 'decodeLatencyMaxMs', 'maxDecodeQueue',
  'decoderErrors', 'decoderResets', 'renderedFps', 'droppedFrames',
  'renderLatencyP50Ms', 'renderLatencyP95Ms', 'eventLoopLagAvgMs', 'eventLoopLagMaxMs',
  'longTaskCount', 'longTaskTotalMs', 'usedJSHeapSize', 'renderDepth',
];
const schemas: Record<string, Validator> = {
  'view-area': x => object({ type: literal('view-area'), x: int, y: int, width: v => int(v) && positive(v), height: v => int(v) && positive(v),
    canvasWidth: v => int(v) && positive(v), canvasHeight: v => int(v) && positive(v) })(x) && (() => {
      const a = x as ViewArea; return a.x + a.width <= a.canvasWidth && a.y + a.height <= a.canvasHeight;
    })(),
  'fullscreen-exit': object({ type: literal('fullscreen-exit'), hiddenMs: nullable(num), display, autoRestore: optional(literal('restored', 'denied', 'off')) }),
  'display-settings': object({ type: literal('display-settings'), videoCodec: optional(literal('auto', 'h265', 'h264')), resolution: literal('auto', 'native', '1080p', '720p', 'screen'), screenWidth: literal(125, 200, 250, 300) }),
  restart: object({ type: literal('restart') }),
  hello: object({ videoCodec: optional(literal('AUTO', 'H264')), playback: optional(literal('SMART', 'QUALITY', 'SPEED')), type: literal('hello'), version: literal(0), token: str,
    decoders: array(object({ codecString: nonempty, hardware: bool })), displays: array(display), maxTouchContacts: range(10), audio: array(audioCodec), microphone: bool }),
  welcome: object({ receiverBuild: optional(nonempty), features: optional(array(str)), relay: optional(object({ name: nonempty, version: nonempty })), plugins: optional(object({ host: literal('/plugins/host/index.html') })), type: literal('welcome'), version: literal(0), clientId: nonempty, session, sourceUrl: url, attribution: nonempty, projectUrl: url }),
  session: object({ type: literal('session'), session }),
  'video-format': object({ type: literal('video-format'), screen: range(255), format: nullable(video) }),
  'audio-format': object({ type: literal('audio-format'), stream: range(255), format: nullable(audio) }),
  mic: object({ type: literal('mic'), stream: range(255), request: nullable(mic) }),
  pause: object({ type: literal('pause'), screen: range(255), paused: bool }),
  keyframe: object({ type: literal('keyframe'), screen: range(255) }),
  ping: object({ type: literal('ping'), t0Us: i64 }),
  pong: object({ type: literal('pong'), t0Us: i64, t1Us: i64, t2Us: i64 }),
  'receiver-info': x => typeof x === 'object' && x !== null && !Array.isArray(x) && new TextEncoder().encode(JSON.stringify(x)).length <= 65536,
  stats: extensible(object({ audioBufferedMs: optional(nullable(num)), audioUnderruns: optional(int), type: literal('stats'), screen: range(255), decodeMs: nullable(num), decodeQueue: int, dropped: int, rendered: int,
    decodedLatencyMs: nullable(num), renderedLatencyMs: nullable(num), clockOffsetUs: nullable(i64), rttUs: nullable(i64) })),
  error: object({ type: literal('error'), code: nonempty, message: str }),
};
const clientTypes = ['fullscreen-exit', 'display-settings', 'hello', 'restart', 'keyframe', 'pause', 'ping', 'pong', 'stats', 'receiver-info'];
const relayTypes = ['view-area', 'display-settings', 'welcome', 'session', 'video-format', 'audio-format', 'mic', 'ping', 'pong', 'error'];
export function parseMessage(input: string | unknown, direction?: Direction): JsonMessage {
  let x: unknown = input;
  if (typeof input === 'string') {
    try { x = JSON.parse(input); } catch { throw new WireFormatError('Invalid JSON'); }
  }
  check(typeof x === 'object' && x !== null, 'Expected JSON object');
  const type = (x as { type?: unknown }).type;
  check(typeof type === 'string' && Object.hasOwn(schemas, type) && schemas[type](x), 'Invalid JSON message');
  if (type === 'display-settings' && direction === 'relay-to-client') check(literal('auto', 'h265', 'h264')((x as DisplaySettings).videoCodec), 'Missing video codec');
  if (direction) check((direction === 'client-to-relay' ? clientTypes : relayTypes).includes(type), 'Wrong message direction');
  return x as JsonMessage;
}
