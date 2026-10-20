// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { videoDecoderConfig } from './video-config';
import { effectiveFrameRate } from './frame-rate';
import { BinaryKind, GAP, KEYFRAME, decodeBinary, encodeBinary, encodeTouch } from '../protocol/binary';
import { parseMessage, type Hello, type JsonMessage, type PlaybackMode, type VideoFormat, type Stats } from '../protocol/json';
import { clockSample, dropPolicy, latencyMs, nowUs, renderQueuePolicy, videoRect, pictureSource } from '../shared/math';
import { SmartDepth, paceDue, playbackMode, playbackParameters, type MediaTime } from '../shared/playback';
import { emptyVideoHold, holdingVideo, sameVideoFormat, videoHold, type HoldEvent } from './video-hold';
import type { AudioDebug, AudioBufferDebug, PageCommand, WorkerNotice } from './messages';
import { audioCodecs, aacConfig, type AudioDecoderInstance } from '../shared/audio-codecs';
import type { AudioFormat, MicFormat } from '../protocol/json';
const scope = self as DedicatedWorkerGlobalScope;
const SCREEN = 0;
let displaySettingsSupported = false, viewAreaSupported = false;
let viewArea: import('../protocol/json').ViewArea | undefined;
let canvas: OffscreenCanvas, context: OffscreenCanvasRenderingContext2D | null;
let hello: Hello, url = '', socket: WebSocket | null = null, stopped = false, visible = true;
let restartSession: string | null = null;
let restartChangesFrameRate = true;
let playing = false, fit: 'fit' | 'fill' = 'fit';
let ready = false, active = false, retry = 0, reconnectTimer: ReturnType<typeof setTimeout> | undefined;
let decoder: VideoDecoder | null = null, format: VideoFormat | null = null, config: VideoDecoderConfig | null = null;
let generation = 0, waiting = true, sessionId: string | null = null, sequence = 0;
let frames: { frame: VideoFrame; relayUs: bigint }[] = [], renderPending = false, queueDepth = 1;
// Playback mode from the current hello; Smart adapts queueDepth once per second.
let mode: PlaybackMode = 'SMART', params = playbackParameters.SMART, smart = new SmartDepth();
interface Encoded { timestamp: number; key: boolean; data: Uint8Array; relayUs: bigint }
// Quality's jitter buffer: frames held encoded until the decoder pipeline has room.
let held: Encoded[] = [];
// Extra frames in flight for a decoder that withholds output until it has more input; learned per stall.
const DECODER_STALL_MS = 100;
let decoderLead = 0, progressAt = 0;
let presented: MediaTime | null = null, newestPtsUs: number | null = null;
let lastKeyRequest = -Infinity;
let offsetUs: bigint | null = null, rttUs: bigint | null = null, clockSampleAt = 0;
let dropped = 0, skipped = 0, rendered = 0, decodeMs: number | null = null, decodedLatencyMs: number | null = null, renderedLatencyMs: number | null = null;
let wsBytes = 0, videoReceived = 0, submitted = 0, output = 0, maxDecodeQueue = 0, decoderErrors = 0, decoderResets = 0;
let decodeSamples: number[] = [], renderSamples: number[] = [], statsAt = performance.now(), previousRendered = 0, previousDropped = 0;
let pageMetrics: Partial<Stats> = {}, receiverInfo: import('../protocol/json').ReceiverInfo | null = null;
function percentile(values: number[], fraction: number): number | undefined { return values.length ? values[Math.min(values.length - 1, Math.floor(values.length * fraction))] : undefined; }
const pending = new Map<number, { submittedUs: bigint; relayUs: bigint }[]>();
const pings = new Map<string, bigint>();
let audioPort: MessagePort | null = null, micPort: MessagePort | null = null;
let audioBufferedMs: number | null = null, audioUnderruns = 0;
interface AudioStream { format: AudioFormat; run: number; decoder?: AudioDecoderInstance }
const audioStreams = new Map<number, AudioStream>();
const endingAudio = new Set<AudioStream>();
let audioRun = 0, audioGeneration = 0;
let audioBuffers: AudioBufferDebug[] = [];
const audioDebug = new Map<number, AudioDebug & { arrivalAt?: number; outputAt?: number }>();
function audioCounter(stream: number): AudioDebug & { arrivalAt?: number; outputAt?: number } {
  let counters = audioDebug.get(stream);
  if (!counters) {
    counters = { stream, packets: 0, maxArrivalGapMs: 0, decoderOutputs: 0, maxDecoderOutputGapMs: 0 };
    audioDebug.set(stream, counters);
  }
  return counters;
}
let mic: { stream: number; request: MicFormat; token: number } | null = null, micToken = 0, micPts = 0n;
function stopMic(): void {
  mic = null; micPort?.close(); micPort = null;
  notice({ type: 'mic', stream: 0, request: null, token: ++micToken });
}
let audioActive = false;
// The page offers "Tap for sound" only while a stream actually has audio to play.
function syncAudioActive(): void {
  const active = audioStreams.size > 0;
  if (active !== audioActive) { audioActive = active; notice({ type: 'audio-active', active }); }
}
function clearAudio(): void {
  audioGeneration++;
  for (const entry of [...audioStreams.values(), ...endingAudio]) {
    if (entry.decoder && entry.decoder.state !== 'closed') entry.decoder.close();
  }
  audioStreams.clear(); endingAudio.clear(); audioDebug.clear(); audioBuffers = [];
  audioPort?.postMessage({ type: 'clear' }); audioBufferedMs = null; audioUnderruns = 0; stopMic();
  syncAudioActive();
}
function pcm(stream: number, entry: AudioStream, rate: number, planes: Float32Array[]): void {
  // Transfer from the decoder callback immediately; video rendering has its own scheduled task.
  audioPort?.postMessage({ type: 'pcm', stream, run: entry.run, rate, planes }, planes.map(p => p.buffer));
}
function endAudio(stream: number, entry: AudioStream): void {
  audioStreams.delete(stream);
  syncAudioActive();
  audioPort?.postMessage({ type: 'ending', stream, run: entry.run });
  const fence = audioGeneration;
  const finish = () => {
    endingAudio.delete(entry);
    if (entry.decoder && entry.decoder.state !== 'closed') entry.decoder.close();
    if (fence === audioGeneration) audioPort?.postMessage({ type: 'end', stream, run: entry.run });
  };
  if (entry.decoder?.state === 'configured') {
    endingAudio.add(entry);
    // The marker must follow all accepted decoder outputs, without awaiting in the WebSocket handler.
    try { void entry.decoder.flush().then(finish, finish); } catch { finish(); }
  } else finish();
}
function audioFormat(stream: number, format: AudioFormat | null): void {
  const old = audioStreams.get(stream);
  if (old) endAudio(stream, old);
  if (!format || !hello.audio.includes(format.codec)) return;
  const counters = audioCounter(stream); counters.arrivalAt = undefined; counters.outputAt = undefined;
  const entry: AudioStream = { format, run: ++audioRun };
  const fence = audioGeneration;
  audioStreams.set(stream, entry);
  syncAudioActive();
  // Media is buffered by the phone (CarPlay asks for up to 1 s); speech and calls stay low-latency.
  audioPort?.postMessage({ type: 'format', stream, run: entry.run, rate: format.sampleRate, channels: format.channels,
    maxTargetMs: format.role === 'MEDIA' ? 1000 : 300 });
  if (format.codec === 'AAC_LC' && audioCodecs.AudioDecoder) {
    try {
      entry.decoder = new audioCodecs.AudioDecoder({
        output(data) {
          try {
            if (fence !== audioGeneration || (audioStreams.get(stream) !== entry && !endingAudio.has(entry))) return;
            const counters = audioCounter(stream), now = performance.now();
            counters.decoderOutputs++;
            if (counters.outputAt !== undefined) counters.maxDecoderOutputGapMs = Math.max(counters.maxDecoderOutputGapMs, now - counters.outputAt);
            counters.outputAt = now;
            const planes = Array.from({ length: data.numberOfChannels }, (_, planeIndex) => {
              const plane = new Float32Array(data.numberOfFrames); data.copyTo(plane, { planeIndex, format: 'f32-planar' }); return plane;
            });
            pcm(stream, entry, data.sampleRate, planes);
          } finally { data.close(); }
        },
        error() { if (audioStreams.get(stream) === entry) audioFormat(stream, null); },
      });
      entry.decoder.configure({ codec: 'mp4a.40.2', sampleRate: format.sampleRate, numberOfChannels: format.channels,
        description: format.config ? Uint8Array.from(atob(format.config), c => c.charCodeAt(0)) : aacConfig.description });
    } catch { audioFormat(stream, null); }
  }
}
function audioPacket(stream: number, payload: Uint8Array, ptsUs: bigint): void {
  const entry = audioStreams.get(stream); if (!entry) return;
  const counters = audioCounter(stream), now = performance.now();
  counters.packets++;
  if (counters.arrivalAt !== undefined) counters.maxArrivalGapMs = Math.max(counters.maxArrivalGapMs, now - counters.arrivalAt);
  counters.arrivalAt = now;
  const f = entry.format;
  if (f.codec === 'PCM_S16LE') {
    if (payload.length % (f.channels * 2)) return;
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const planes = Array.from({ length: f.channels }, (_, c) => Float32Array.from({ length: payload.length / (f.channels * 2) },
      (_, i) => view.getInt16((i * f.channels + c) * 2, true) / 32768));
    pcm(stream, entry, f.sampleRate, planes);
  // Phones deliver buffered audio in Wi-Fi batches of ~20 packets (0.4 s); a 12-packet cap dropped a third of the
  // music (2026-10-15 Pi test). 96 AAC packets are about 2 s, beyond the 1 s CarPlay latency allowance.
  } else if (entry.decoder?.state === 'configured' && entry.decoder.decodeQueueSize < 96 && Number.isSafeInteger(Number(ptsUs))) {
    try { entry.decoder.decode(new audioCodecs.EncodedAudioChunk({ type: 'key', timestamp: Number(ptsUs), data: payload })); }
    catch { audioFormat(stream, null); }
  }
}
function notice(value: WorkerNotice): void { scope.postMessage(value); }
function late(item: { relayUs: bigint }): boolean {
  return (latencyMs(nowUs(), item.relayUs, offsetUs) ?? 0) > params.lateBudgetMs;
}
function discard(item: { frame: VideoFrame; relayUs: bigint }): void {
  if (mode === 'SMART' && late(item)) smart.lateFrame();
  item.frame.close(); skipped++;
}
function setPlayback(next: PlaybackMode): void {
  if (next === mode) return;
  mode = next; params = playbackParameters[next]; presented = null;
  queueDepth = next === 'SMART' ? smart.depth : params.maxDepth;
  // Frames held for Quality go to the decoder at once in the other modes.
  if (!params.paced) while (held.length) if (!submit(held.shift()!)) { dropped += held.length; held = []; }
}
function send(value: JsonMessage): void {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
}
function keyframe(): void {
  const now = performance.now();
  if (ready && visible && now - lastKeyRequest >= 250) { lastKeyRequest = now; send({ type: 'keyframe', screen: SCREEN }); }
}
let hold = emptyVideoHold, holdTimer: ReturnType<typeof setTimeout> | undefined;
function updateHold(event: HoldEvent): void {
  const now = performance.now(), hadDeadline = hold.deadline !== null;
  const result = videoHold(hold, event, now); hold = result.state;
  clearTimeout(holdTimer);
  if (result.clear && context) { context.fillStyle = '#000'; context.fillRect(0, 0, canvas.width, canvas.height); }
  if (event === 'end') notice({ type: 'ended' });
  else if (event !== 'frame' && (hadDeadline || hold.deadline !== null)) notice({ type: 'hold', active: holdingVideo(hold, now) });
  const deadline = [hold.deadline, hold.clearAt].filter((at): at is number => at !== null && at > now);
  if (deadline.length) holdTimer = setTimeout(() => updateHold('tick'), Math.min(...deadline) - now);
}
function clearDecoder(): void {
  if (decoder) decoderResets++;
  playing = false;
  generation++;
  if (decoder && decoder.state !== 'closed') decoder.close();
  decoder = null; waiting = true;
  decodeMs = null; decodedLatencyMs = null; renderedLatencyMs = null;
  for (const values of pending.values()) dropped += values.length;
  pending.clear();
  for (const item of frames) { item.frame.close(); skipped++; }
  frames = [];
  dropped += held.length; held = []; decoderLead = 0;
  presented = null; newestPtsUs = null; smart.restart();
}
function startDecoder(): void {
  if (!config || !visible || !active) return;
  const fence = generation;
  try {
    decoder = new VideoDecoder({
      output(frame) {
        output++;
        if (fence !== generation || !visible) { frame.close(); skipped++; return; }
        const values = pending.get(frame.timestamp), meta = values?.shift();
        if (values?.length === 0) pending.delete(frame.timestamp);
        if (!meta) { frame.close(); skipped++; return; }
        progressAt = performance.now();
        const now = nowUs();
        decodeMs = Number(now - meta.submittedUs) / 1000;
        decodeSamples.push(decodeMs);
        decodedLatencyMs = latencyMs(now, meta.relayUs, offsetUs);
        const next = renderQueuePolicy(frames, { frame, relayUs: meta.relayUs }, queueDepth, late);
        next.skipped.forEach(discard);
        frames = next.frames;
        if (params.paced) pump();
        scheduleRender();
      },
      error() { if (fence === generation) { decoderErrors++; recover(); } },
    });
    decoder.configure(config);
  } catch (e) {
    decoderErrors++;
    updateHold('interrupt'); clearDecoder(); notice({ type: 'status', text: `Decoder unavailable: ${String(e)}` }); keyframe();
  }
}
function recover(): void { updateHold('interrupt'); clearDecoder(); startDecoder(); keyframe(); }
function backlogMs(): number {
  return newestPtsUs === null || !frames.length ? 0 : (newestPtsUs - frames[0].frame.timestamp) / 1000;
}
function render(): void {
  renderPending = false;
  // A render stall can make the queued frame late while it waits; show its successor instead.
  if (frames.length > 1 && late(frames[0])) discard(frames.shift()!);
  const now = performance.now();
  if (params.paced && frames.length && !paceDue(now, presented, frames[0].frame.timestamp, backlogMs())) { scheduleRender(); return; }
  const item = frames.shift();
  if (!item) return;
  try {
    if (!context || !visible || !active || !format) { skipped++; return; }
    const crop = pictureSource(item.frame.displayWidth, item.frame.displayHeight, viewArea);
    const r = videoRect(canvas.width, canvas.height, crop.width, crop.height, fit);
    context.fillStyle = '#000'; context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(item.frame, crop.x, crop.y, crop.width, crop.height, r.x, r.y, r.width, r.height);
    presented = { at: now, ptsUs: item.frame.timestamp };
    const resumed = hold.deadline !== null;
    updateHold('frame');
    if (restartSession === null && (!playing || resumed)) { playing = true; notice({ type: 'playing' }); }
    rendered++; renderedLatencyMs = latencyMs(nowUs(), item.relayUs, offsetUs);
    if (renderedLatencyMs !== null) renderSamples.push(renderedLatencyMs);
  } finally {
    item.frame.close();
    if (params.paced) pump();
    if (frames.length) scheduleRender();
  }
}
function scheduleRender(): void {
  if (renderPending) return;
  renderPending = true;
  if (typeof scope.requestAnimationFrame === 'function') scope.requestAnimationFrame(render);
  // Timer fallback approximates a refresh when worker animation callbacks are unavailable.
  else setTimeout(render, 1000 / (hello.displays[0]?.refreshHz || 60));
}
async function applyFormat(next: VideoFormat | null): Promise<void> {
  if (sameVideoFormat(format, next)) return;
  updateHold('replace');
  clearDecoder(); config = null; format = next;
  notice({ type: 'format', format });
  if (!next) return;
  await configureFormat(next);
}
async function configureFormat(next: VideoFormat): Promise<void> {
  const fence = generation;
  try {
    if (typeof VideoDecoder === 'undefined') throw new Error('WebCodecs is unavailable');
    const candidate = videoDecoderConfig(next);
    const result = await VideoDecoder.isConfigSupported(candidate);
    if (fence !== generation) return;
    if (!result.supported) throw new Error(`Unsupported codec ${next.codecString}`);
    config = result.config ?? candidate; startDecoder(); keyframe();
  } catch (e) { if (fence === generation) notice({ type: 'status', text: String(e) }); }
}
function touch(contacts: Parameters<typeof encodeTouch>[0]): void {
  if (!ready || socket?.readyState !== WebSocket.OPEN) return;
  if (socket.bufferedAmount > 65536) { socket.close(1013, 'Input backpressure'); return; }
  const bytes = encodeBinary({ kind: BinaryKind.Touch, stream: SCREEN, flags: 0, sequence, ptsUs: 0n, senderClockUs: nowUs() }, encodeTouch(contacts));
  sequence = (sequence + 1) >>> 0;
  socket.send(bytes.buffer as ArrayBuffer);
}
function session(next: import('../protocol/json').SessionState): void {
  if (restartSession !== null && (next.id !== restartSession || next.phase !== 'ACTIVE')) restartSession = null;
  const changed = sessionId !== null && sessionId !== next.id;
  active = next.phase === 'ACTIVE'; sessionId = next.id;
  if (changed || next.phase === 'ENDED') clearAudio();
  if (next.phase === 'ENDED') updateHold('end');
  else if (changed || !active) updateHold(changed ? 'replace' : 'interrupt');
  if (changed || !active) {
    touch([]); clearDecoder();
    if (changed || next.phase === 'ENDED') { config = null; format = null; notice({ type: 'format', format: null }); }
  }
  if (active && !decoder && format) {
    if (config) { startDecoder(); keyframe(); }
    else void configureFormat(format);
  }
  notice({ type: 'status', text: next.phase });
}
function control(message: JsonMessage): void {
  switch (message.type) {
    case 'display-settings': if (ready && displaySettingsSupported) notice(message); break;
    case 'view-area': if (ready && viewAreaSupported) { viewArea = message; notice(message); } break;
    case 'welcome':
      viewArea = undefined; displaySettingsSupported = message.features?.includes('display-settings') === true; viewAreaSupported = message.features?.includes('view-area') === true;
      ready = true; retry = 0; resetMetrics(); if (receiverInfo) send(receiverInfo);
      notice({ features: message.features, receiverBuild: message.receiverBuild, relay: message.relay, type: 'welcome', sourceUrl: message.sourceUrl, token: sentHelloToken, ...(message.plugins ? { plugins: message.plugins } : {}) });
      session(message.session); break;
    case 'session': session(message.session); break;
    case 'audio-format': if (ready) audioFormat(message.stream, message.format); break;
    case 'mic':
      if (!message.request && mic?.stream !== message.stream) break;
      stopMic();
      if (ready && message.request && hello.microphone) {
        mic = { stream: message.stream, request: message.request, token: ++micToken }; micPts = nowUs();
        notice({ type: 'mic', ...mic });
      }
      break;
    case 'video-format': if (ready && message.screen === SCREEN) void applyFormat(message.format); break;
    case 'ping': {
      const t1 = nowUs(); send({ type: 'pong', t0Us: message.t0Us, t1Us: t1.toString(), t2Us: nowUs().toString() }); break;
    }
    case 'pong': {
      const sent = pings.get(message.t0Us); if (sent === undefined) break;
      pings.delete(message.t0Us);
      const sample = clockSample(sent, BigInt(message.t1Us), BigInt(message.t2Us), nowUs());
      if (sample && (rttUs === null || sample.rttUs <= rttUs || performance.now() - clockSampleAt > 30000)) {
        offsetUs = sample.offsetUs; rttUs = sample.rttUs; clockSampleAt = performance.now();
      }
      break;
    }
    case 'error': notice({ type: 'status', text: `${message.code}: ${message.message}` }); break;
  }
}
function pendingCount(): number {
  let count = 0;
  for (const values of pending.values()) count += values.length;
  return count;
}
function decodePressure(): number { return (decoder?.decodeQueueSize ?? 0) + held.length; }
/** Returns false when the frame was dropped instead; the decoder then waits for a key frame. */
function submit(item: Encoded): boolean {
  if (!decoder || decoder.state !== 'configured') { dropped++; return false; }
  // At most eight small metadata entries; no video payload or pixel copies here.
  // Bound metadata independently of decodeQueueSize, which excludes decoder-internal frames.
  if (pendingCount() >= 8) { dropped++; waiting = true; keyframe(); return false; }
  if (!pending.size) progressAt = performance.now();
  const values = pending.get(item.timestamp) ?? [];
  values.push({ submittedUs: nowUs(), relayUs: item.relayUs }); pending.set(item.timestamp, values);
  try {
    decoder.decode(new EncodedVideoChunk({ type: item.key ? 'key' : 'delta', timestamp: item.timestamp, data: item.data }));
    submitted++; maxDecodeQueue = Math.max(maxDecodeQueue, decoder.decodeQueueSize);
    const pressure = dropPolicy(waiting, false, decodePressure(), false, false, params.maxDecodeQueue);
    waiting = pressure.waiting;
    if (pressure.requestKeyframe) keyframe();
    return true;
  }
  catch { decoderErrors++; recover(); return false; }
}
/** Quality feeds the decoder only while few decoded frames are outstanding, so held frames stay encoded. */
function pump(): void {
  const now = performance.now();
  if (held.length && !frames.length && pending.size && decoderLead < 4 && now - progressAt > DECODER_STALL_MS) { decoderLead++; progressAt = now; }
  while (held.length && pendingCount() + frames.length <= params.maxDepth + decoderLead) {
    if (!submit(held.shift()!)) { dropped += held.length; held = []; }
  }
}
function media(data: ArrayBuffer): void {
  const { header, payload } = decodeBinary(new Uint8Array(data));
  if (ready && header.kind === BinaryKind.Audio) { audioPacket(header.stream, payload, header.ptsUs); return; }
  if (!ready || header.kind !== BinaryKind.Video || header.stream !== SCREEN) return;
  videoReceived++;
  if (!active || !visible) { dropped++; return; }
  const timestamp = Number(header.ptsUs);
  if (mode === 'SMART' && Number.isSafeInteger(timestamp)) smart.arrival(performance.now(), timestamp);
  if (!decoder || decoder.state !== 'configured') { dropped++; return; }
  const key = !!(header.flags & KEYFRAME), recovering = waiting;
  const decision = dropPolicy(waiting, key, decodePressure(), !!(header.flags & GAP), false, params.maxDecodeQueue);
  waiting = decision.waiting;
  if (decision.requestKeyframe) keyframe();
  if (!decision.decode) { dropped++; return; }
  if (!Number.isSafeInteger(timestamp)) { dropped++; waiting = true; keyframe(); return; }
  newestPtsUs = timestamp;
  const item: Encoded = { timestamp, key, data: payload, relayUs: header.senderClockUs };
  if (!params.paced) { submit(item); return; }
  // The key frame that ends a drop makes older held frames stale.
  if (recovering) { dropped += held.length; held = []; }
  held.push(item); pump();
}
let sentHelloToken = '';
function connect(): void {
  if (stopped) return;
  ready = false; viewArea = undefined; displaySettingsSupported = false; viewAreaSupported = false; notice({ type: 'display-disconnected' });
  notice({ type: 'status', text: 'Connecting…' });
  const current = new WebSocket(url); socket = current; current.binaryType = 'arraybuffer';
  current.onopen = () => {
    if (socket === current) { sentHelloToken = hello.token; send(hello); send({ type: 'pause', screen: SCREEN, paused: !visible }); }
  };
  current.onmessage = event => {
    if (socket !== current) return;
    wsBytes += typeof event.data === 'string' ? new TextEncoder().encode(event.data).length : event.data.byteLength ?? 0;
    try {
      if (typeof event.data === 'string') control(parseMessage(event.data, 'relay-to-client'));
      else if (event.data instanceof ArrayBuffer) media(event.data);
    } catch (e) { updateHold('interrupt'); notice({ type: 'status', text: `Protocol error: ${String(e)}` }); current.close(1002, 'Invalid OXP-Web message'); }
  };
  current.onerror = () => { updateHold('interrupt'); notice({ type: 'status', text: 'Connection failed' }); };
  current.onclose = event => {
    if (socket !== current) return;
    ready = false; viewArea = undefined; displaySettingsSupported = false; viewAreaSupported = false; notice({ type: 'display-disconnected' }); active = false; updateHold('interrupt'); clearAudio(); clearDecoder();
    offsetUs = null; rttUs = null; pings.clear(); lastKeyRequest = -Infinity;
    if (event?.code === 1008) {
      stopped = true; clearTimeout(holdTimer); hold = { ...hold, deadline: null, clearAt: null };
      notice({ type: 'rejected', token: sentHelloToken }); return;
    }
    notice({ type: 'status', text: 'Disconnected; reconnecting…' });
    if (!stopped) {
      const delay = Math.min(30000, 500 * 2 ** Math.min(retry++, 6)) * (0.8 + Math.random() * 0.4);
      notice({ type: 'retry', delayMs: delay, attempt: retry });
      reconnectTimer = setTimeout(connect, delay);
    }
  };
}
scope.onmessage = (event: MessageEvent<PageCommand>) => {
  const message = event.data;
  switch (message.type) {
    case 'display-settings': if (ready && displaySettingsSupported) send(message); break;
    case 'fullscreen-exit': if (ready) send(message); break;
    case 'audio-probe':
      void (async () => {
        let aac = false;
        try { aac = !!(await audioCodecs.AudioDecoder?.isConfigSupported(aacConfig))?.supported; } catch { }
        notice({ type: 'audio-support', aac });
      })(); break;
    case 'audio-port':
      audioPort = message.port;
      audioPort.onmessage = ({ data }) => { if (data.type === 'audio-stats') { audioBufferedMs = data.bufferedMs; audioUnderruns = data.underruns; audioBuffers = data.streams; } }; break;
    case 'mic-detach': micPort?.close(); micPort = null; break;
    case 'mic-port': {
      if (!mic || message.token !== mic.token) { message.port.close(); break; }
      micPort?.close(); micPort = message.port;
      micPort.onmessage = ({ data }) => {
        if (micPort !== message.port || !mic || data.token !== mic.token || message.token !== mic.token || !ready || socket?.readyState !== WebSocket.OPEN) return;
        const now = nowUs();
        if (now - micPts > 100000n || micPts - now > 100000n) micPts = now;
        const ptsUs = micPts; micPts += 20000n;
        if (socket.bufferedAmount > 65536) return;
        const payload = new Uint8Array(data.data);
        socket.send(encodeBinary({ kind: BinaryKind.Microphone, stream: mic.stream, flags: 0, sequence: sequence++ >>> 0,
          ptsUs, senderClockUs: now }, payload).buffer as ArrayBuffer);
      }; break;
    }
    case 'init':
      canvas = message.canvas; context = canvas.getContext('2d', { alpha: false });
      hello = message.hello; url = message.url;
      smart = new SmartDepth(message.queueDepth); queueDepth = smart.depth; setPlayback(playbackMode(hello.playback));
      connect(); break;
    case 'hello':
      restartChangesFrameRate = effectiveFrameRate(hello.displays[0]) !== effectiveFrameRate(message.hello.displays[0]);
      hello = message.hello; setPlayback(playbackMode(hello.playback)); if (ready) send(hello); break;
    case 'restart': if (ready) { restartSession = restartChangesFrameRate ? sessionId ?? '0' : null; playing = false; send({ type: 'restart' }); } break;
    case 'fit': fit = message.fit; break;
    case 'pair': hello = { ...hello, token: message.token }; stopped = false; clearTimeout(reconnectTimer); connect(); break;
    case 'receiver-info': receiverInfo = message.info; if (ready) send(receiverInfo); break;
    case 'page-metrics': pageMetrics = message.metrics; break;
    case 'reconnect': {
      stopped = false; clearTimeout(reconnectTimer);
      const previous = socket; socket = null; previous?.close();
      updateHold('interrupt'); clearAudio(); clearDecoder();
      connect(); break;
    }
    case 'resize':
      if (canvas && (canvas.width !== message.width || canvas.height !== message.height)) {
        const saved = hold.frame && context ? new OffscreenCanvas(canvas.width, canvas.height) : null;
        saved?.getContext('2d')?.drawImage(canvas, 0, 0);
        canvas.width = message.width; canvas.height = message.height;
        if (saved) context?.drawImage(saved, 0, 0, canvas.width, canvas.height);
      } break;
    case 'touch': touch(message.contacts); break;
    case 'visibility':
      visible = message.visible;
      send({ type: 'pause', screen: SCREEN, paused: !visible });
      touch([]); clearDecoder();
      if (visible) {
        lastKeyRequest = -Infinity;
        if (!config && format) void configureFormat(format); else { startDecoder(); keyframe(); }
      } break;
    case 'stop': updateHold('end'); clearAudio(); stopped = true; touch([]); clearTimeout(reconnectTimer); clearInterval(statsTimer); clearDecoder(); socket?.close(); break;
  }
};
function resetMetrics(): void {
  statsAt = performance.now(); wsBytes = 0; videoReceived = 0; submitted = 0; output = 0;
  maxDecodeQueue = decoder?.decodeQueueSize ?? 0; decoderErrors = 0; decoderResets = 0;
  decodeSamples = []; renderSamples = []; previousRendered = rendered; previousDropped = dropped + skipped;
}
const statsTimer = setInterval(() => {
  if (!ready) { resetMetrics(); return; }
  const t0 = nowUs();
  for (const [key, value] of pings) if (t0 - value > 10000000n) pings.delete(key);
  pings.set(t0.toString(), t0); send({ type: 'ping', t0Us: t0.toString() });
  if (mode === 'SMART') queueDepth = smart.evaluate();
  const now = performance.now(), seconds = Math.max(0.001, (now - statsAt) / 1000); statsAt = now;
  decodeSamples.sort((a, b) => a - b); renderSamples.sort((a, b) => a - b);
  const stats: Stats = { ...pageMetrics, wsBytesPerSecond: wsBytes / seconds, videoFramesPerSecond: videoReceived / seconds,
    decoderSubmittedPerSecond: submitted / seconds, decoderOutputPerSecond: output / seconds,
    decodeLatencyP50Ms: percentile(decodeSamples, 0.5), decodeLatencyP95Ms: percentile(decodeSamples, 0.95), decodeLatencyMaxMs: percentile(decodeSamples, 1),
    maxDecodeQueue, decoderErrors, decoderResets, renderedFps: (rendered - previousRendered) / seconds, droppedFrames: dropped + skipped - previousDropped,
    renderLatencyP50Ms: percentile(renderSamples, 0.5), renderLatencyP95Ms: percentile(renderSamples, 0.95), renderDepth: queueDepth,
    audioBufferedMs, audioUnderruns, type: 'stats', screen: SCREEN, decodeMs, decodeQueue: decoder?.decodeQueueSize ?? 0,
    dropped, rendered, decodedLatencyMs, renderedLatencyMs, clockOffsetUs: offsetUs?.toString() ?? null, rttUs: rttUs?.toString() ?? null };
  const audio = [...audioDebug.values()].map(({ stream, packets, maxArrivalGapMs, decoderOutputs, maxDecoderOutputGapMs }) =>
    ({ stream, packets, maxArrivalGapMs, decoderOutputs, maxDecoderOutputGapMs }));
  for (const counters of audioDebug.values()) {
    counters.packets = 0; counters.maxArrivalGapMs = 0; counters.decoderOutputs = 0; counters.maxDecoderOutputGapMs = 0;
  }
  resetMetrics(); pageMetrics = {};
  send(stats); notice({ type: 'stats', stats, skipped, renderQueue: Math.max(0, frames.length - 1), queueDepth, playback: mode, audio, audioBuffers });
}, 1000);
