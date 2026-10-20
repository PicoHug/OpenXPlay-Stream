// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { BinaryKind, KEYFRAME, encodeBinary } from '../protocol/binary';

const vectors = new URL('../spec/vectors/web/', import.meta.url);
const vector = (name: string) => JSON.parse(readFileSync(new URL(name + '.json', vectors), 'utf8'));
let scope: { onmessage: (event: { data: unknown }) => void; postMessage: ReturnType<typeof vi.fn> };
class Socket {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = 0;
  bufferedAmount = 0;
  sent: unknown[] = [];
  onopen = () => {};
  onclose = (_event?: { code: number }) => {};
  onmessage = (_event: { data: unknown }) => {};
  constructor(_url: string) { Socket.instances.push(this); }
  send(data: unknown) { this.sent.push(typeof data === 'string' ? JSON.parse(data) : data); }
  close(code = 1000) { this.readyState = 3; this.onclose({ code }); }
  open() { this.readyState = Socket.OPEN; this.onopen(); }
  receive(data: unknown) { this.onmessage({ data: typeof data === 'object' && !(data instanceof ArrayBuffer) ? JSON.stringify(data) : data }); }
  json() { return this.sent.filter(value => !(value instanceof ArrayBuffer)); }
}
const decode = vi.fn();
class Decoder {
  static instances: Decoder[] = [];
  constructor(public callbacks: { output(frame: unknown): void; error(): void }) { Decoder.instances.push(this); }
  static async isConfigSupported(config: unknown) { return { supported: true, config }; }
  state = 'configured';
  decodeQueueSize = 0;
  configure() {}
  close() { this.state = 'closed'; }
  decode = decode;
}
const command = (data: unknown) => scope.onmessage({ data });
beforeEach(async () => {
  vi.resetModules(); vi.useFakeTimers(); Socket.instances = []; Decoder.instances = []; decode.mockClear();
  scope = { onmessage: () => {}, postMessage: vi.fn() };
  vi.stubGlobal('self', scope); vi.stubGlobal('WebSocket', Socket);
  vi.stubGlobal('VideoDecoder', Decoder);
  vi.stubGlobal('EncodedVideoChunk', class { constructor(public init: unknown) {} });
  await import('../receiver/worker');
});
afterEach(() => { command({ type: 'stop' }); vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); });
function init(context: unknown = null, hello = vector('hello')) {
  command({ type: 'init', canvas: { width: 800, height: 480, getContext: () => context }, hello, url: 'ws://localhost/oxp/v0', queueDepth: 1 });
  return Socket.instances.at(-1)!;
}
async function welcome(socket: Socket) {
  socket.receive(vector('welcome')); socket.receive(vector('video-format'));
  await Promise.resolve(); await Promise.resolve();
}
function video(socket: Socket, key: boolean, ptsUs = 1n) {
  socket.receive(encodeBinary({ kind: BinaryKind.Video, stream: 0, flags: key ? KEYFRAME : 0, sequence: 0,
    ptsUs, senderClockUs: 1n }, new Uint8Array([1])).buffer);
}
it('sends hello then the current hidden state on each connection', async () => {
  command({ type: 'visibility', visible: false });
  const first = init(); first.open();
  expect(first.json()).toEqual([vector('hello'), { type: 'pause', screen: 0, paused: true }]);
  await welcome(first);
  expect(first.json().filter((m: any) => m.type === 'keyframe')).toEqual([]);
  first.close();
  await vi.advanceTimersByTimeAsync(1000);
  const second = Socket.instances.at(-1)!; second.open();
  expect(second.json()).toEqual([vector('hello'), { type: 'pause', screen: 0, paused: true }]);
});
it('reports visibility before welcome and resumes with pause before keyframe', async () => {
  const socket = init(); socket.open();
  expect(socket.json().slice(0, 2)).toEqual([vector('hello'), { type: 'pause', screen: 0, paused: false }]);
  command({ type: 'visibility', visible: false });
  expect(socket.json().at(-1)).toEqual({ type: 'pause', screen: 0, paused: true });
  await welcome(socket);
  socket.sent = [];
  command({ type: 'visibility', visible: true });
  expect(socket.json()).toEqual([{ type: 'pause', screen: 0, paused: false }, { type: 'keyframe', screen: 0 }]);
  video(socket, false); expect(decode).not.toHaveBeenCalled();
  video(socket, true); expect(decode).toHaveBeenCalledOnce();
  socket.sent = [];
  command({ type: 'visibility', visible: false });
  command({ type: 'visibility', visible: true });
  expect(socket.json()).toEqual([{ type: 'pause', screen: 0, paused: true },
    { type: 'pause', screen: 0, paused: false }, { type: 'keyframe', screen: 0 }]);
  decode.mockClear(); video(socket, false); expect(decode).not.toHaveBeenCalled();
});
it('passes PCM directly to the player while video is hidden and clears audio on disconnect', async () => {
  const socket = init(); socket.open(); await welcome(socket);
  const port = { postMessage: vi.fn(), onmessage: null };
  command({ type: 'audio-port', port });
  socket.receive({ type: 'audio-format', stream: 2, format: { role: 'VOICE', codec: 'PCM_S16LE', sampleRate: 16000, channels: 1 } });
  // The fixture hello advertises no audio; enable it through a capability update.
  command({ type: 'hello', hello: { ...vector('hello'), audio: ['PCM_S16LE'], microphone: true } });
  socket.receive({ type: 'audio-format', stream: 2, format: { role: 'VOICE', codec: 'PCM_S16LE', sampleRate: 16000, channels: 1 } });
  command({ type: 'visibility', visible: false });
  socket.receive(encodeBinary({ kind: BinaryKind.Audio, stream: 2, flags: 0, sequence: 0, ptsUs: 0n, senderClockUs: 0n }, new Uint8Array([0, 64, 0, 128])).buffer);
  const value = port.postMessage.mock.calls.at(-1)![0];
  expect(value.type).toBe('pcm'); expect(value.stream).toBe(2); expect(value.rate).toBe(16000);
  expect([...value.planes[0]]).toEqual([.5, -1]);
  socket.close(); expect(port.postMessage).toHaveBeenCalledWith({ type: 'clear' });
  expect(scope.postMessage.mock.calls.map(([v]) => v)).not.toContainEqual({ type: 'format', format: null });
  expect(scope.postMessage.mock.calls.some(([v]) => v.type === 'mic' && v.request === null)).toBe(true);
});
it('sends microphone kind 3 with the requested stream and rejects stale capture tokens', async () => {
  const socket = init(); socket.open(); await welcome(socket);
  command({ type: 'hello', hello: { ...vector('hello'), microphone: true } });
  socket.receive({ type: 'mic', stream: 7, request: { codec: 'PCM_S16LE', sampleRate: 16000, channels: 1, frameMs: 20 } });
  const request = scope.postMessage.mock.calls.map(([value]) => value).filter(v => v.type === 'mic' && v.request).at(-1);
  const port = { close: vi.fn(), onmessage: (_event: any) => {} };
  command({ type: 'mic-port', port, token: request.token });
  const frame = new ArrayBuffer(640);
  socket.sent = [];
  port.onmessage({ data: { token: request.token - 1, data: frame } }); expect(socket.sent).toHaveLength(0);
  port.onmessage({ data: { token: request.token, data: frame } });
  const { decodeBinary } = await import('../protocol/binary');
  const message = decodeBinary(new Uint8Array(socket.sent[0] as ArrayBuffer));
  expect(message.header.kind).toBe(BinaryKind.Microphone); expect(message.header.stream).toBe(7); expect(message.payload.length).toBe(640);
  socket.receive({ type: 'mic', stream: 7, request: null }); expect(port.close).toHaveBeenCalled();
  port.onmessage({ data: { token: request.token, data: frame } }); expect(socket.sent).toHaveLength(1);
});
it('probes AAC using ASC and decodes in the worker with transferred planar PCM', async () => {
  const configure = vi.fn(), close = vi.fn();
  let output: (data: any) => void = () => {};
  class AudioDecoder {
    static isConfigSupported = vi.fn(async () => ({ supported: true }));
    state = 'configured'; decodeQueueSize = 0;
    configure = configure; close = close;
    flush = vi.fn(async () => {});
    constructor(init: any) { output = init.output; }
    decode(_chunk: unknown) {
      const data = { numberOfChannels: 2, numberOfFrames: 2, sampleRate: 48000, close: vi.fn(),
        copyTo(plane: Float32Array, options: { planeIndex: number }) { plane.fill(options.planeIndex + .25); } };
      output(data); expect(data.close).toHaveBeenCalledOnce();
    }
  }
  vi.stubGlobal('AudioDecoder', AudioDecoder);
  vi.stubGlobal('EncodedAudioChunk', class { constructor(public init: unknown) {} });
  command({ type: 'audio-probe' }); await Promise.resolve(); await Promise.resolve();
  expect(AudioDecoder.isConfigSupported).toHaveBeenCalledWith({ codec: 'mp4a.40.2', sampleRate: 48000,
    numberOfChannels: 2, description: new Uint8Array([0x11, 0x90]) });
  const socket = init(); socket.open(); await welcome(socket);
  command({ type: 'hello', hello: { ...vector('hello'), audio: ['AAC_LC'] } });
  const port = { postMessage: vi.fn(), onmessage: null }; command({ type: 'audio-port', port });
  socket.receive({ type: 'audio-format', stream: 1, format: { role: 'MEDIA', codec: 'AAC_LC', sampleRate: 48000, channels: 2, config: 'EZA=' } });
  expect(configure).toHaveBeenCalledWith({ codec: 'mp4a.40.2', sampleRate: 48000, numberOfChannels: 2, description: new Uint8Array([0x11, 0x90]) });
  socket.receive(encodeBinary({ kind: BinaryKind.Audio, stream: 1, flags: 0, sequence: 0, ptsUs: 123n, senderClockUs: 123n }, new Uint8Array([1])).buffer);
  const [message, transfers] = port.postMessage.mock.calls.at(-1)!;
  expect(message.type).toBe('pcm'); expect([...message.planes[0]]).toEqual([.25, .25]); expect([...message.planes[1]]).toEqual([1.25, 1.25]);
  expect(transfers).toEqual(message.planes.map((p: Float32Array) => p.buffer));
  socket.receive({ type: 'audio-format', stream: 1, format: null });
  await Promise.resolve();
  expect(close).toHaveBeenCalledOnce();
  expect(port.postMessage.mock.calls.at(-1)![0]).toMatchObject({ type: 'end', stream: 1, run: message.run });
});
it('flushes delayed AAC output before end, preserves a successor, and keeps diagnostics off the wire', async () => {
  const decoders: any[] = [];
  class AudioDecoder {
    state = 'configured'; decodeQueueSize = 0;
    configure() {} decode() {}
    close() { this.state = 'closed'; }
    resolve: () => void = () => {};
    flush = vi.fn(() => new Promise<void>(resolve => { this.resolve = resolve; }));
    constructor(public callbacks: any) { decoders.push(this); }
  }
  vi.stubGlobal('AudioDecoder', AudioDecoder);
  vi.stubGlobal('EncodedAudioChunk', class { constructor(public init: unknown) {} });
  const socket = init(); socket.open(); await welcome(socket);
  command({ type: 'hello', hello: { ...vector('hello'), audio: ['AAC_LC'] } });
  const port = { postMessage: vi.fn(), onmessage: (_event: any) => {} };
  command({ type: 'audio-port', port });
  const format = { role: 'MEDIA', codec: 'AAC_LC', sampleRate: 48000, channels: 2, config: 'EZA=' };
  socket.receive({ type: 'audio-format', stream: 1, format });
  const now = vi.spyOn(performance, 'now');
  for (const time of [10, 35]) {
    now.mockReturnValue(time);
    socket.receive(encodeBinary({ kind: BinaryKind.Audio, stream: 1, flags: 0, sequence: 0,
      ptsUs: 123n, senderClockUs: 123n }, new Uint8Array([1])).buffer);
  }
  socket.receive({ type: 'audio-format', stream: 1, format: null });
  expect(decoders[0].flush).toHaveBeenCalledOnce();
  expect(port.postMessage.mock.calls.some(([m]) => m.type === 'end')).toBe(false);
  socket.receive({ type: 'audio-format', stream: 1, format });
  const run = port.postMessage.mock.calls.at(-1)![0].run;
  for (const time of [40, 70]) {
    now.mockReturnValue(time);
    decoders[0].callbacks.output({ numberOfChannels: 2, numberOfFrames: 2, sampleRate: 48000,
      close: vi.fn(), copyTo(plane: Float32Array) { plane.fill(.25); } });
    expect(port.postMessage.mock.calls.at(-1)![0]).toMatchObject({ type: 'pcm', run: run - 1 });
  }
  decoders[0].resolve(); await Promise.resolve();
  expect(port.postMessage.mock.calls.at(-1)![0]).toMatchObject({ type: 'end', run: run - 1 });
  expect(decoders[0].state).toBe('closed'); expect(decoders[1].state).toBe('configured');
  port.onmessage({ data: { type: 'audio-stats', bufferedMs: 80, underruns: 1,
    streams: [{ stream: 1, run, minBufferedMs: 2, maxBufferedMs: 200, targetMs: 100 }] } });
  await vi.advanceTimersByTimeAsync(1000);
  const report = scope.postMessage.mock.calls.map(([m]) => m).find(m => m.type === 'stats');
  expect(report.audio).toEqual([{ stream: 1, packets: 2, maxArrivalGapMs: 25, decoderOutputs: 2, maxDecoderOutputGapMs: 30 }]);
  expect(report.audioBuffers[0]).toMatchObject({ minBufferedMs: 2, maxBufferedMs: 200, targetMs: 100 });
  const wire = socket.json().find((m: any) => m.type === 'stats') as any;
  expect(wire.audioBufferedMs).toBe(80); expect(wire.audioUnderruns).toBe(1);
  expect(wire).not.toHaveProperty('audio'); expect(wire).not.toHaveProperty('audioBuffers');
  await vi.advanceTimersByTimeAsync(1000);
  const next = scope.postMessage.mock.calls.map(([m]) => m).filter(m => m.type === 'stats').at(-1);
  expect(next.audio[0]).toMatchObject({ packets: 0, decoderOutputs: 0, maxArrivalGapMs: 0, maxDecoderOutputGapMs: 0 });
  now.mockRestore();
});
it('rejects stale port installation and same-token callbacks from replaced captures', async () => {
  const socket = init(); socket.open(); await welcome(socket);
  command({ type: 'hello', hello: { ...vector('hello'), microphone: true } });
  socket.receive({ type: 'mic', stream: 7, request: { codec: 'PCM_S16LE', sampleRate: 48000, channels: 1, frameMs: 20 } });
  const token = scope.postMessage.mock.calls.map(([v]) => v).filter(v => v.type === 'mic' && v.request).at(-1).token;
  const first = { close: vi.fn(), onmessage: (_event: any) => {} };
  const current = { close: vi.fn(), onmessage: (_event: any) => {} };
  const stale = { close: vi.fn(), onmessage: (_event: any) => {} };
  command({ type: 'mic-port', port: first, token });
  command({ type: 'mic-port', port: current, token });
  expect(first.close).toHaveBeenCalledOnce();
  command({ type: 'mic-port', port: stale, token: token - 1 }); expect(stale.close).toHaveBeenCalledOnce();
  socket.sent = [];
  first.onmessage({ data: { token, data: new ArrayBuffer(1920) } }); expect(socket.sent).toHaveLength(0);
  current.onmessage({ data: { token, data: new ArrayBuffer(1920) } }); expect(socket.sent).toHaveLength(1);
  socket.receive({ type: 'mic', stream: 7, request: null });
  current.onmessage({ data: { token, data: new ArrayBuffer(1920) } }); expect(socket.sent).toHaveLength(1);
});
it('stops reconnecting after policy close and resumes with the new pairing code', async () => {
  const socket = init(); socket.open(); await welcome(socket);
  socket.close(1008);
  expect(scope.postMessage).toHaveBeenCalledWith({ type: 'rejected', token: 'example-pairing-token' });
  await vi.advanceTimersByTimeAsync(60000); expect(Socket.instances).toHaveLength(1);
  command({ type: 'pair', token: '00123456' });
  const next = Socket.instances.at(-1)!; next.open();
  expect(next.json()[0]).toMatchObject({ type: 'hello', token: '00123456' });
});
it('detaches capture immediately while permission or the relay response is pending', async () => {
  const socket = init(); socket.open(); await welcome(socket);
  command({ type: 'hello', hello: { ...vector('hello'), microphone: true } });
  socket.receive({ type: 'mic', stream: 7, request: { codec: 'PCM_S16LE', sampleRate: 48000, channels: 1, frameMs: 20 } });
  const token = scope.postMessage.mock.calls.map(([v]) => v).filter(v => v.type === 'mic' && v.request).at(-1).token;
  const port = { close: vi.fn(), onmessage: (_event: any) => {} };
  command({ type: 'mic-port', port, token }); command({ type: 'mic-detach' });
  expect(port.close).toHaveBeenCalledOnce(); socket.sent = [];
  port.onmessage({ data: { token, data: new ArrayBuffer(1920) } }); expect(socket.sent).toHaveLength(0);
});

it('starts without a token and waits after rejection until a code is submitted', async () => {
  const hello = { ...vector('hello'), token: '' };
  command({ type: 'init', canvas: { getContext: () => null }, url: 'wss://relay/oxp/v0', hello, queueDepth: 1 });
  const socket = Socket.instances[0]; socket.open();
  expect(socket.sent[0]).toEqual(hello);
  command({ type: 'hello', hello: { ...hello, token: 'not-sent-yet' } });
  socket.close(1008);
  expect(scope.postMessage).toHaveBeenCalledWith({ type: 'rejected', token: '' });
  await vi.advanceTimersByTimeAsync(60000); expect(Socket.instances).toHaveLength(1);
  command({ type: 'pair', token: '00123456' }); Socket.instances[1].open();
  expect(Socket.instances[1].sent[0]).toMatchObject({ token: '00123456' });
});

const notices = () => scope.postMessage.mock.calls.map(([value]) => value);
async function picture(socket: Socket) {
  video(socket, true);
  Decoder.instances.at(-1)!.callbacks.output({ timestamp: 1, displayWidth: 800, displayHeight: 480, close: vi.fn() });
  await vi.advanceTimersByTimeAsync(20);
}
async function streaming() {
  const context = { fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn() };
  const socket = init(context); socket.open(); await welcome(socket); await picture(socket);
  context.fillRect.mockClear(); context.drawImage.mockClear(); scope.postMessage.mockClear();
  return { socket, context };
}
it('ignores identical formats without resetting the decoder, painting or notifying the page', async () => {
  const { socket, context } = await streaming();
  const decoder = Decoder.instances.at(-1);
  socket.receive(vector('video-format')); await Promise.resolve();
  expect(Decoder.instances.at(-1)).toBe(decoder); expect(decoder!.state).toBe('configured');
  expect(context.fillRect).not.toHaveBeenCalled(); expect(notices()).toEqual([]);
});
it('holds a changed format until its first frame and cancels the clear deadline', async () => {
  const { socket, context } = await streaming();
  const old = Decoder.instances.at(-1)!;
  socket.receive({ ...vector('video-format'), format: { ...vector('video-format').format, width: 1024 } });
  await Promise.resolve();
  expect(old.state).toBe('closed'); expect(context.fillRect).not.toHaveBeenCalled();
  expect(notices()).toContainEqual({ type: 'hold', active: true });
  await picture(socket); context.fillRect.mockClear(); scope.postMessage.mockClear();
  await vi.advanceTimersByTimeAsync(3000);
  expect(context.fillRect).not.toHaveBeenCalled(); expect(notices()).not.toContainEqual({ type: 'hold', active: false });
});
it('clears a changed format after three seconds without a replacement frame', async () => {
  const { socket, context } = await streaming();
  socket.receive({ ...vector('video-format'), format: { ...vector('video-format').format, width: 1024 } });
  await vi.advanceTimersByTimeAsync(2999); expect(context.fillRect).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1); expect(context.fillRect).toHaveBeenCalledOnce();
  expect(notices()).toContainEqual({ type: 'hold', active: false });
});
it('keeps the frame across a same-session interruption and resumes without format notices', async () => {
  const { socket, context } = await streaming();
  socket.receive({ type: 'session', session: { id: '42', phase: 'RECONNECTING' } });
  await vi.advanceTimersByTimeAsync(2000);
  socket.receive({ type: 'session', session: { id: '42', phase: 'ACTIVE' } });
  await picture(socket); context.fillRect.mockClear();
  await vi.advanceTimersByTimeAsync(3000);
  expect(context.fillRect).not.toHaveBeenCalled();
  expect(notices().filter(value => value.type === 'format')).toEqual([]);
  expect(notices().filter(value => value.type === 'hold')).toEqual([{ type: 'hold', active: true }]);
  expect(notices()).toContainEqual({ type: 'playing' });
});
it('expires the card grace on repeated interruptions while preserving the frame', async () => {
  const { socket, context } = await streaming();
  const pause = { type: 'session', session: { id: '42', phase: 'CONNECTING' } };
  socket.receive(pause); await vi.advanceTimersByTimeAsync(2000); socket.receive(pause);
  await vi.advanceTimersByTimeAsync(1000);
  expect(notices()).toContainEqual({ type: 'hold', active: false });
  expect(context.fillRect).not.toHaveBeenCalled();
});
it('preserves the frame through socket errors and reconnects to the same session', async () => {
  const { socket, context } = await streaming();
  (socket as any).onerror(); socket.close();
  await vi.advanceTimersByTimeAsync(1000);
  const next = Socket.instances.at(-1)!; next.open(); await welcome(next);
  expect(context.fillRect).not.toHaveBeenCalled();
  expect(notices()).not.toContainEqual({ type: 'format', format: null });
  await picture(next); context.fillRect.mockClear();
  await vi.advanceTimersByTimeAsync(3000);
  expect(context.fillRect).not.toHaveBeenCalled();
  expect(notices()).not.toContainEqual({ type: 'hold', active: false });
});
it('holds through decoder recovery and resumes on a keyframe', async () => {
  const { socket, context } = await streaming();
  Decoder.instances.at(-1)!.callbacks.error();
  expect(context.fillRect).not.toHaveBeenCalled();
  expect(notices()).toContainEqual({ type: 'hold', active: true });
  await picture(socket);
  expect(notices()).toContainEqual({ type: 'playing' });
});
it('clears immediately on ENDED and on stop, with no delayed hold callback', async () => {
  const { socket, context } = await streaming();
  socket.receive({ type: 'session', session: { id: '42', phase: 'RECONNECTING' } });
  socket.receive({ type: 'session', session: { id: '42', phase: 'ENDED' } });
  expect(context.fillRect).toHaveBeenCalledOnce(); expect(notices()).toContainEqual({ type: 'ended' });
  context.fillRect.mockClear(); scope.postMessage.mockClear();
  await vi.advanceTimersByTimeAsync(4000);
  expect(context.fillRect).not.toHaveBeenCalled(); expect(notices().filter(v => v.type === 'hold')).toEqual([]);
  command({ type: 'stop' }); expect(context.fillRect).toHaveBeenCalledOnce();
});
it('clears an abandoned session after grace but allows the old session to resume first', async () => {
  const { socket, context } = await streaming();
  socket.receive({ type: 'session', session: { id: '43', phase: 'CONNECTING' } });
  await vi.advanceTimersByTimeAsync(1000);
  socket.receive({ type: 'session', session: { id: '42', phase: 'ACTIVE' } });
  socket.receive(vector('video-format')); await Promise.resolve(); await picture(socket);
  context.fillRect.mockClear(); await vi.advanceTimersByTimeAsync(3000);
  expect(context.fillRect).not.toHaveBeenCalled();
  socket.receive({ type: 'session', session: { id: '43', phase: 'CONNECTING' } });
  await vi.advanceTimersByTimeAsync(3000); expect(context.fillRect).toHaveBeenCalledOnce();
});

it.each(['socket', 'decoder', 'manual reconnect'] as const)('shows the card after grace for %s while keeping the frozen frame', async interruption => {
  const { socket, context } = await streaming();
  if (interruption === 'socket') socket.close();
  else if (interruption === 'decoder') Decoder.instances.at(-1)!.callbacks.error();
  else command({ type: 'reconnect' });
  await vi.advanceTimersByTimeAsync(2999);
  expect(notices()).not.toContainEqual({ type: 'hold', active: false });
  await vi.advanceTimersByTimeAsync(1);
  expect(notices()).toContainEqual({ type: 'hold', active: false });
  expect(context.fillRect).not.toHaveBeenCalled();
});
it('preserves pairing rejection after an interruption without a delayed status override', async () => {
  const { socket } = await streaming();
  socket.close(1008); scope.postMessage.mockClear();
  await vi.advanceTimersByTimeAsync(4000);
  expect(notices().filter(v => v.type === 'hold' || v.type === 'status')).toEqual([]);
  expect(Socket.instances).toHaveLength(1);
});
it('preserves the frozen canvas across resize without clearing it again at the same size', async () => {
  const { socket, context } = await streaming();
  const copy = { drawImage: vi.fn() };
  const surfaces: unknown[] = [];
  vi.stubGlobal('OffscreenCanvas', class {
    constructor(public width: number, public height: number) { surfaces.push(this); }
    getContext() { return copy; }
  });
  socket.close(); command({ type: 'resize', width: 1024, height: 600 });
  expect(copy.drawImage).toHaveBeenCalledOnce();
  expect(context.drawImage).toHaveBeenCalledWith(surfaces[0], 0, 0, 1024, 600);
  command({ type: 'resize', width: 1024, height: 600 });
  expect(context.drawImage).toHaveBeenCalledOnce(); expect(context.fillRect).not.toHaveBeenCalled();
});

it('cancels a socket error hold when a frame renders before the socket closes', async () => {
  const { socket } = await streaming();
  (socket as any).onerror(); await picture(socket);
  expect(notices()).toContainEqual({ type: 'playing' });
  scope.postMessage.mockClear(); await vi.advanceTimersByTimeAsync(3000);
  expect(notices()).not.toContainEqual({ type: 'hold', active: false });
});
it('keeps diagnostics optional, forwards the report after welcome, and reuses it on reconnect', async () => {
  const socket = init(); socket.open(); await welcome(socket);
  video(socket, true); expect(decode).toHaveBeenCalledOnce();
  expect(socket.json().some((m: any) => m.type === 'receiver-info')).toBe(false);
  const info = vector('receiver-info'); command({ type: 'receiver-info', info });
  expect(socket.json().at(-1)).toEqual(info);
  socket.close(); await vi.advanceTimersByTimeAsync(1100);
  const next = Socket.instances.at(-1)!; next.open();
  expect(next.json().some((m: any) => m.type === 'receiver-info')).toBe(false);
  await welcome(next); expect(next.json()).toContainEqual(info);
});
it('reports window rates, latency distributions, drops, errors, and page metrics without changing cumulative counters', async () => {
  const clock = vi.spyOn(performance, 'now').mockReturnValue(0);
  const context = { fillRect: vi.fn(), drawImage: vi.fn() };
  const socket = init(context); socket.open(); await welcome(socket);
  // Establish a zero-offset clock sample so render latency is observable.
  await vi.advanceTimersByTimeAsync(1000);
  const ping = socket.json().find((m: any) => m.type === 'ping') as any;
  socket.receive({ type: 'pong', t0Us: ping.t0Us, t1Us: '0', t2Us: '0' });
  socket.sent = [];
  const decoder = Decoder.instances.at(-1)!; decoder.decodeQueueSize = 3;
  video(socket, true);
  clock.mockReturnValue(10);
  decoder.callbacks.output({ timestamp: 1, displayWidth: 800, displayHeight: 480, close: vi.fn() });
  clock.mockReturnValue(20); await vi.advanceTimersByTimeAsync(20);
  video(socket, false);
  clock.mockReturnValue(50);
  decoder.callbacks.output({ timestamp: 1, displayWidth: 800, displayHeight: 480, close: vi.fn() });
  await vi.advanceTimersByTimeAsync(20);
  decoder.callbacks.error();
  command({ type: 'visibility', visible: false }); video(socket, true);
  command({ type: 'page-metrics', metrics: { eventLoopLagAvgMs: 5, eventLoopLagMaxMs: 10, longTaskCount: 2, longTaskTotalMs: 120, usedJSHeapSize: 42 } });
  clock.mockReturnValue(1000); await vi.advanceTimersByTimeAsync(960);
  const stats = socket.json().find((m: any) => m.type === 'stats') as any;
  expect(stats).toMatchObject({ videoFramesPerSecond: 3, decoderSubmittedPerSecond: 2, decoderOutputPerSecond: 2,
    decodeLatencyP50Ms: 30, decodeLatencyP95Ms: 30, decodeLatencyMaxMs: 30, maxDecodeQueue: 3,
    decoderErrors: 1, decoderResets: 2, renderedFps: 2, droppedFrames: 1, rendered: 2, dropped: 1,
    renderLatencyP50Ms: 49.999, renderLatencyP95Ms: 49.999, eventLoopLagAvgMs: 5, eventLoopLagMaxMs: 10,
    longTaskCount: 2, longTaskTotalMs: 120, usedJSHeapSize: 42 });
  expect(stats.wsBytesPerSecond).toBeGreaterThan(75);
  clock.mockReturnValue(2000); await vi.advanceTimersByTimeAsync(1000);
  expect(socket.json().filter((m: any) => m.type === 'stats').at(-1)).toMatchObject({ videoFramesPerSecond: 0, decoderSubmittedPerSecond: 0, decoderOutputPerSecond: 0, renderedFps: 0, droppedFrames: 0, rendered: 2, dropped: 1, decoderErrors: 0, decoderResets: 0 });
  clock.mockRestore();
});

it('sends the preference hello before restart and waits for the next session to report playing', async () => {
  const { socket } = await streaming();
  const hello = vector('hello'); hello.displays[0].frameRate = 'FPS_45';
  command({ type: 'hello', hello }); command({ type: 'restart' });
  expect(socket.json().slice(-2)).toEqual([hello, { type: 'restart' }]);
  scope.postMessage.mockClear();
  await picture(socket);
  expect(notices()).not.toContainEqual({ type: 'playing' });
  socket.receive({ type: 'session', session: { id: '43', phase: 'CONNECTING' } });
  socket.receive({ type: 'session', session: { id: '43', phase: 'ACTIVE' } });
  socket.receive(vector('video-format')); await Promise.resolve(); await Promise.resolve();
  await picture(socket);
  expect(notices()).toContainEqual({ type: 'playing' });
});

it('finishes applying a preference with the same effective rate on the next frame', async () => {
  const { socket } = await streaming();
  const hello = vector('hello'); hello.displays[0].frameRate = 'FPS_60';
  command({ type: 'hello', hello }); command({ type: 'restart' });
  scope.postMessage.mockClear(); await picture(socket);
  expect(notices()).toContainEqual({ type: 'playing' });
});

it('finishes applying when a socket reconnect welcome declares the restarted session', async () => {
  const { socket } = await streaming();
  const hello = vector('hello'); hello.displays[0].frameRate = 'FPS_35';
  command({ type: 'hello', hello }); command({ type: 'restart' }); socket.close();
  await vi.advanceTimersByTimeAsync(1000);
  const next = Socket.instances.at(-1)!; next.open();
  const state = vector('welcome'); state.session = { id: '43', phase: 'ACTIVE' };
  next.receive(state); next.receive(vector('video-format'));
  await Promise.resolve(); await Promise.resolve(); scope.postMessage.mockClear();
  await picture(next); expect(notices()).toContainEqual({ type: 'playing' });
});

const decoded = () => decode.mock.calls.map(([chunk]) => (chunk as { init: { timestamp: number } }).init.timestamp);
const output = (timestamp: number) => ({ timestamp, displayWidth: 800, displayHeight: 480, close: vi.fn() });
it('Speed shows only the newest decoded frame and drops deltas beyond a decode queue of two', async () => {
  const context = { fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn() };
  const socket = init(context, { ...vector('hello'), playback: 'SPEED' });
  expect(socket.sent).toEqual([]); socket.open();
  expect(socket.json()[0]).toMatchObject({ type: 'hello', playback: 'SPEED' });
  await welcome(socket);
  const decoder = Decoder.instances.at(-1)!;
  video(socket, true, 0n); video(socket, false, 16_667n);
  const [older, newer] = [output(0), output(16_667)];
  decoder.callbacks.output(older); decoder.callbacks.output(newer);
  expect(older.close).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(20);
  expect(context.drawImage).toHaveBeenCalledTimes(1); expect(context.drawImage.mock.calls[0][0]).toBe(newer);
  // Key frame requests are limited to one per 250 ms.
  await vi.advanceTimersByTimeAsync(300); socket.sent = []; decoder.decodeQueueSize = 3;
  video(socket, false, 33_333n);
  expect(decoded()).toEqual([0, 16_667]); expect(socket.json()).toContainEqual({ type: 'keyframe', screen: 0 });
  // A repeated hello switches to Smart, which accepts a queue of three after the next key frame.
  command({ type: 'hello', hello: vector('hello') });
  video(socket, true, 50_000n); video(socket, false, 66_667n);
  expect(decoded()).toEqual([0, 16_667, 50_000, 66_667]);
});
it('Quality holds encoded frames for the decoder pipeline and presents them at their timestamp cadence', async () => {
  const clock = vi.spyOn(performance, 'now').mockReturnValue(0);
  const context = { fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn() };
  const socket = init(context, { ...vector('hello'), playback: 'QUALITY' }); socket.open(); await welcome(socket);
  const decoder = Decoder.instances.at(-1)!;
  for (let frame = 0; frame < 6; frame++) video(socket, frame === 0, BigInt(frame * 33_333));
  // Four frames fill the pipeline (depth three plus the frame due); the rest stay encoded.
  expect(decoded()).toEqual([0, 33_333, 66_666, 99_999]);
  decoder.callbacks.output(output(0));
  expect(decoded()).toHaveLength(4);
  await vi.advanceTimersByTimeAsync(20);
  expect(context.drawImage).toHaveBeenCalledTimes(1);
  expect(decoded()).toEqual([0, 33_333, 66_666, 99_999, 133_332]);
  decoder.callbacks.output(output(33_333));
  clock.mockReturnValue(10); await vi.advanceTimersByTimeAsync(20);
  expect(context.drawImage).toHaveBeenCalledTimes(1);
  clock.mockReturnValue(30); await vi.advanceTimersByTimeAsync(20);
  expect(context.drawImage).toHaveBeenCalledTimes(2);
  // Beyond 150 ms of backlog Quality catches up at the refresh rate instead of waiting for the cadence.
  for (let frame = 6; frame < 12; frame++) video(socket, false, BigInt(frame * 33_333));
  decoder.callbacks.output(output(66_666));
  clock.mockReturnValue(31); await vi.advanceTimersByTimeAsync(20);
  expect(context.drawImage).toHaveBeenCalledTimes(3);
  expect(scope.postMessage.mock.calls.some(([m]) => m.type === 'stats')).toBe(false);
  await vi.advanceTimersByTimeAsync(1000);
  const stats = notices().filter(m => m.type === 'stats').at(-1);
  expect(stats).toMatchObject({ queueDepth: 3, playback: 'QUALITY', stats: { renderDepth: 3 } });
  clock.mockRestore();
});
it('Smart lowers its depth after smooth seconds and reports it in stats', async () => {
  const clock = vi.spyOn(performance, 'now').mockReturnValue(0);
  const socket = init(); socket.open(); await welcome(socket);
  const depths: number[] = [];
  for (let index = 0; index < 5; index++) {
    for (let frame = 0; frame < 60; frame++) {
      const at = index * 1000 + frame * 1000 / 60;
      clock.mockReturnValue(at); video(socket, index === 0 && frame === 0, BigInt(Math.round(at * 1000)));
    }
    await vi.advanceTimersByTimeAsync(1000);
    depths.push((socket.json().filter((m: any) => m.type === 'stats').at(-1) as any).renderDepth);
  }
  expect(depths).toEqual([1, 1, 1, 1, 0]);
  expect(notices().filter(m => m.type === 'stats').at(-1)).toMatchObject({ queueDepth: 0, playback: 'SMART' });
  clock.mockRestore();
});
it('Quality gives a decoder that withholds output one more frame in flight per stall', async () => {
  const clock = vi.spyOn(performance, 'now').mockReturnValue(0);
  const socket = init(null, { ...vector('hello'), playback: 'QUALITY' }); socket.open(); await welcome(socket);
  for (let frame = 0; frame < 6; frame++) video(socket, frame === 0, BigInt(frame * 33_333));
  expect(decoded()).toHaveLength(4);
  clock.mockReturnValue(90); video(socket, false, 200_000n);
  expect(decoded()).toHaveLength(4);
  clock.mockReturnValue(150); video(socket, false, 233_333n);
  expect(decoded()).toHaveLength(5);
  // The learned lead persists; the next stall adds another frame.
  clock.mockReturnValue(200); video(socket, false, 266_666n);
  expect(decoded()).toHaveLength(5);
  clock.mockReturnValue(300); video(socket, false, 300_000n);
  expect(decoded()).toEqual([0, 33_333, 66_666, 99_999, 133_332, 166_665]);
  clock.mockRestore();
});

it('forwards codec preference updates and reconnects with truthful decoders', async () => {
  const hello = vector('hello-video-codec-h264');
  delete hello.videoCodec;
  const socket = init(undefined, hello); socket.open(); await welcome(socket);
  command({ type: 'hello', hello: { ...hello, videoCodec: 'H264' } });
  expect(socket.json().at(-1)).toEqual({ ...hello, videoCodec: 'H264' });
  command({ type: 'hello', hello });
  expect(socket.json().at(-1)).toEqual(hello);
  command({ type: 'hello', hello: { ...hello, videoCodec: 'H264' } });
  socket.close(); await vi.advanceTimersByTimeAsync(1000);
  const next = Socket.instances.at(-1)!; next.open();
  expect(next.json()[0]).toEqual({ ...hello, videoCodec: 'H264' });
});

it('negotiates display settings, forwards diagnostics and crops decoded frames', async () => {
  const context = { fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn() };
  const socket = init(context); socket.open(); await welcome(socket);
  command(vector('display-settings-native'));
  expect(socket.json().some((v: any) => v.type === 'display-settings')).toBe(false);
  socket.receive(vector('welcome-features'));
  socket.receive(vector('display-settings-native')); socket.receive(vector('view-area'));
  expect(scope.postMessage).toHaveBeenCalledWith(vector('display-settings-native'));
  command(vector('display-settings-native')); command(vector('display-settings-native'));
  command(vector('fullscreen-exit'));
  expect(socket.json().filter((v: any) => v.type === 'display-settings')).toHaveLength(2);
  expect(socket.json()).toContainEqual(vector('fullscreen-exit'));
  video(socket, true);
  const frame = { displayWidth: 960, displayHeight: 540, timestamp: 1, close: vi.fn() };
  Decoder.instances.at(-1)!.callbacks.output(frame);
  await vi.advanceTimersByTimeAsync(40);
  expect(context.drawImage).toHaveBeenCalledWith(frame, 60, 20, 720, 450, 16, 0, 768, 480);
  socket.close();
  expect(scope.postMessage).toHaveBeenCalledWith({ type: 'display-disconnected' });
});
