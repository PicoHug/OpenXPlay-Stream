// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { collectReceiverInfo, codecPowerEfficient } from '../shared/receiver-info';
import { codecCandidates, probeDecoders, estimateRefreshHz, audioCapabilities } from '../shared/capabilities';
import { mapTouch, renderQueueDepth, videoRect } from '../shared/math';
import { DisplayDebounce, DisplaySettingsGate, pictureSize } from './display';
import type { ViewArea, DisplaySettings } from '../protocol/json';
import type { Hello } from '../protocol/json';
import type { TouchContact } from '../protocol/binary';
import type { PageCommand, WorkerNotice } from './messages';
import workletUrl from './audio-worklet.ts?worker&url';
import type { MicFormat } from '../protocol/json';
import { ReceiverPreferences, playbackHint, selectLanguage, strings, statusCard, soundPrompt, gearOffset, clampGear, GearGesture, type GearBounds, type StatusState, type TextKey } from './ui';
import { bindSheet } from './sheet';
import { PluginSlot } from './plugin-slot';
import { microphoneStatus, applyReducedMotion } from './ui';
import { CaptureLifecycle } from './capture-lifecycle';
import { playbackUi, type PlaybackUi, type PlaybackEvent } from './video-hold';
import { version, shouldReload } from './version';
import { frameRatePreference, RestartGate, withFrameRate } from './frame-rate';
import { playbackMode, withPlayback } from '../shared/playback';
import { autoRestoreFullscreen, FullscreenExitHistory, FullscreenIntent } from './fullscreen';
import './style.css';
const canvas = document.querySelector<HTMLCanvasElement>('#video')!;
const element = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id)! as T;
const source = element<HTMLAnchorElement>('source');
const debug = element('debug');
const fragment = new URLSearchParams(location.hash.slice(1));
const storage = new ReceiverPreferences(() => localStorage);
const preferences = storage.load(fragment);
// Capture is controlled by CarPlay requests rather than a saved UI switch.
preferences.microphone = true;
const language = selectLanguage(navigator.languages);
const text = strings[language];
document.documentElement.lang = language;
for (const node of document.querySelectorAll<HTMLElement>('[data-text]')) node.textContent = text[node.dataset.text as TextKey];
canvas.setAttribute('aria-label', text.projection);
const gear = element<HTMLButtonElement>('gear'); gear.setAttribute('aria-label', text.settings);
const settings = element<HTMLDialogElement>('settings');
let viewArea: ViewArea | undefined;
let uiState: StatusState = 'connecting';
const pluginSlot = new PluginSlot(() => {
  const box = canvas.getBoundingClientRect(), g = gear.getBoundingClientRect();
  const r = videoRect(box.width, box.height, videoWidth, videoHeight, preferences.fit, viewArea);
  return { locale: language, developer: preferences.developer, theme: 'dark', reducedMotion: reducedMotion(), viewport: { w: innerWidth, h: innerHeight },
    video: { x: box.x + r.x, y: box.y + r.y, w: r.width, h: r.height },
    gear: { gap: parseFloat(getComputedStyle(document.documentElement).fontSize) * .5, x: g.x, y: g.y, w: g.width, h: g.height, faded: gear.classList.contains('faded'), side: g.x + g.width / 2 < innerWidth / 2 ? 'left' : 'right' }, channel: '/plugins/channel' };
}, (code, detail) => { console.warn(code, detail ?? ''); }, wakeGear);
const pluginSwitch = element<HTMLInputElement>('plugins-enabled');
pluginSwitch.checked = pluginSlot.preference;
pluginSwitch.onchange = () => pluginSlot.enable(pluginSwitch.checked);

const displayGate = new DisplaySettingsGate(), displayDebounce = new DisplayDebounce();
let currentDisplaySettings: DisplaySettings = { type: 'display-settings', resolution: 'auto', screenWidth: 200, videoCodec: 'auto' };
function updateDisplaySettings(): void {
  element('display-settings').hidden = !displayGate.supported;
  element<HTMLSelectElement>('resolution').value = currentDisplaySettings.resolution;
  element<HTMLSelectElement>('screen-width').value = String(currentDisplaySettings.screenWidth);
  element<HTMLSelectElement>('relay-video-codec').value = currentDisplaySettings.videoCodec;
}
function updatePictureSize(): void {
  const box = canvas.getBoundingClientRect(), dpr = devicePixelRatio || 1;
  element('picture-size').textContent = `${text.picture} ${pictureSize(videoWidth, videoHeight)} · ${text.windowSize} ${pictureSize(Math.round(box.width * dpr), Math.round(box.height * dpr))}`;
}
let protocolState = 'Not connected';
let playback: PlaybackUi = { state: 'connecting', protocol: protocolState, holding: false };
function updatePlayback(event: PlaybackEvent): void {
  playback = playbackUi(playback, event); setState(playback.state);
}
let micState: TextKey = 'idle';
let requestedMic: { request: MicFormat | null; token: number } | null = null;
let initialized = false, starting = false;
const soundHint = element('sound-hint');
let audioContext: AudioContext | null = null, player: AudioWorkletNode | null = null, gain: GainNode | null = null;
let audioActive = false;
function syncSound(): void {
  const prompt = soundPrompt(uiState === 'playing', preferences.sound, audioContext?.state, audioActive);
  soundHint.hidden = prompt !== 'pill';
  element('card-sound').hidden = prompt !== 'card';
  element('controls').classList.toggle('playing', uiState === 'playing');
  placeGear();
}
const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
const motion = matchMedia('(prefers-reduced-motion: reduce)');
const syncMotion = () => {
  applyReducedMotion(document.documentElement, motion.matches);
  if (motion.matches) { document.getAnimations().forEach(animation => animation.cancel()); element('headline').textContent = text[statusCard(uiState).headline]; }
};
motion.addEventListener('change', syncMotion); syncMotion();
element('version').textContent = version;
element('close-settings').setAttribute('aria-label', text.close);
let cardTimer: ReturnType<typeof setTimeout>;
let countdown: ReturnType<typeof setInterval> | undefined;
function setState(state: StatusState): void {
  if (state === uiState && element('card').dataset.state === state) return;
  uiState = state;
  pluginSlot.receiverState(state === 'playing' ? 'playing' : state === 'error' ? 'error' : 'waiting');
  syncSound();
  element('protocol-state').textContent = `Protocol: ${protocolState}`;
  clearInterval(countdown); clearTimeout(cardTimer);
  const card = statusCard(state), panel = element('card');
  panel.dataset.state = state;
  panel.classList.toggle('leaving', card.hidden);
  if (card.hidden && !reducedMotion()) cardTimer = setTimeout(() => { panel.hidden = true; }, 200);
  else panel.hidden = card.hidden;
  const headline = element('headline');
  if (headline.textContent !== text[card.headline]) {
    if (!reducedMotion()) {
      const fade = headline.animate?.([{ opacity: 1 }, { opacity: 0 }], { duration: 100 });
      if (fade) fade.onfinish = () => { if (state !== uiState) return; headline.textContent = text[card.headline]; headline.animate?.([{ opacity: 0 }, { opacity: 1 }], { duration: 100 }); };
      else headline.textContent = text[card.headline];
    } else headline.textContent = text[card.headline];
  }
  element('subline').textContent = text[card.subline];
  element('retry').hidden = state !== 'error';
  if (state !== 'playing') setGearFaded(false);
}
let fadeTimer: ReturnType<typeof setTimeout>;
function setGearFaded(faded: boolean): void {
  if (gear.classList.contains('faded') === faded) return;
  gear.classList.toggle('faded', faded); pluginSlot.refresh();
}
function wakeGear(): void {
  setGearFaded(false); clearTimeout(fadeTimer);
  fadeTimer = setTimeout(() => { if (uiState === 'playing' && !settings.open) setGearFaded(true); }, 3000);
}
function showMic(state: TextKey): void {
  micState = state; const status = microphoneStatus(state);
  element('mic-state').textContent = text[status.label];
  element('mic-hint').textContent = text[status.hint];
  element('mic-retry').hidden = !status.retry || !requestedMic?.request;
}
/** The relay decides the codec (an Android relay always uses H.264), so the settings show what is in use. */
function showCodecInUse(codec: 'H264' | 'H265' | undefined): void {
  const line = element('video-codec-current');
  line.hidden = !codec;
  line.textContent = codec ? text[codec === 'H265' ? 'codecInUseH265' : 'codecInUseH264'] : '';
}
function updateSettings(): void {
  for (const key of ['sound', 'developer'] as const) element<HTMLInputElement>(key).checked = preferences[key];
  debug.hidden = !preferences.developer;
  element<HTMLSelectElement>('frame-rate').value = preferences.frameRate;
  element<HTMLSelectElement>('playback').value = preferences.playback;
  element('playback-hint').textContent = text[playbackHint(preferences.playback)];
  element('fit').setAttribute('aria-pressed', String(preferences.fit === 'fit'));
  element('fill').setAttribute('aria-pressed', String(preferences.fit === 'fill'));
}
const sheet = bindSheet(settings, gear, element('close-settings'), element('sheet-backdrop'), reducedMotion);
element('reset-positions').onclick = () => {
  finishGearDrag(true); preferences.gear = { side: 'right', y: 0 }; storage.write('gear', null);
  pluginSlot.resetPositions(); placeGear(); wakeGear();
};
gear.onclick = event => { if (suppressGearClick && event.detail !== 0) { suppressGearClick = false; return; } if (pluginSlot.collapse()) { wakeGear(); return; } releaseAll(); sheet.open(); pluginSlot.visibility(false); wakeGear(); };
settings.addEventListener('close', () => { pluginSlot.visibility(true); wakeGear(); });
document.addEventListener('pointerdown', event => { if (event.target !== canvas) wakeGear(); }, { passive: true });
const fullscreen = new FullscreenIntent(!!document.fullscreenEnabled, () => localStorage);
const fullscreenHint = element('fullscreen-hint');
const restoreSwitch = element<HTMLInputElement>('fullscreen-restore');
restoreSwitch.checked = fullscreen.restore;
restoreSwitch.onchange = () => { fullscreen.setRestore(restoreSwitch.checked); fullscreenState(); };
const fullscreenHistory = new FullscreenExitHistory();
let pendingFullscreenExit = false;
async function reportFullscreenExit(): Promise<void> {
  if (!pendingFullscreenExit || document.hidden || !hello) return;
  pendingFullscreenExit = false;
  const hiddenMs = fullscreenHistory.take(performance.now()), display = measuredDisplay();
  const autoRestore = await autoRestoreFullscreen({
    enabled: () => fullscreen.restore && fullscreen.intent, restored: () => !!document.fullscreenElement,
    hidden: () => document.hidden,
    visible: () => new Promise(resolve => {
      const changed = () => { if (!document.hidden) { document.removeEventListener('visibilitychange', changed); resolve(); } };
      document.addEventListener('visibilitychange', changed); changed();
    }),
    request: () => document.documentElement.requestFullscreen(), delay: ms => new Promise(resolve => setTimeout(resolve, ms)),
  });
  post({ type: 'fullscreen-exit', hiddenMs, display, autoRestore });
}
function requestFullscreen(): void {
  void document.documentElement.requestFullscreen?.().catch(() => { element('fullscreen-state').textContent = text.fullscreenUnavailable; });
}
// Capture before canvas controls: the restore gesture must never become a CarPlay touch.
document.addEventListener('pointerdown', event => {
  if (settings.open || (event.target instanceof Node && element('fullscreen').contains(event.target))) return;
  fullscreen.pointerDown(event, requestFullscreen);
}, { capture: true });
function fullscreenState(): void {
  const on = fullscreen.intent;
  document.querySelectorAll<SVGElement>('[data-fullscreen]').forEach(icon => { icon.toggleAttribute('hidden', (icon.dataset.fullscreen === 'on') !== on); });
  element('fullscreen').setAttribute('aria-pressed', String(fullscreen.intent));
  fullscreenHint.hidden = !fullscreen.pending;
  element('fullscreen-state').textContent = !document.fullscreenEnabled ? text.fullscreenUnavailable : on ? text.enabled : text.off;
}
document.addEventListener('fullscreenchange', () => {
  if (fullscreen.changed(!!document.fullscreenElement, hello ? measuredDisplay() : undefined)) {
    pendingFullscreenExit = true; requestAnimationFrame(reportFullscreenExit);
  }
  fullscreenState(); resize();
}); fullscreenState();
element('fullscreen').onclick = () => {
  fullscreen.toggle(requestFullscreen, () => { void document.exitFullscreen().catch(() => {}); });
  fullscreenState();
};
for (const key of ['sound', 'developer'] as const) {
  element<HTMLInputElement>(key).onchange = event => {
    preferences[key] = (event.target as HTMLInputElement).checked; storage.save(preferences); updateSettings();
    if (key === 'developer') pluginSlot.refresh();
    if (key === 'sound') {
      if (audioContext && gain) gain.gain.setValueAtTime(preferences.sound ? 1 : 0, audioContext.currentTime);
      if (preferences.sound) void audioContext?.resume().catch(() => {});
      syncSound();
    }

  };
}
for (const fit of ['fit', 'fill'] as const) element(fit).onclick = () => {
  releaseAll(); preferences.fit = fit; storage.save(preferences); updateSettings(); post({ type: 'fit', fit });
};
const restartGate = new RestartGate();
for (const id of ['resolution', 'screen-width', 'relay-video-codec']) element<HTMLSelectElement>(id).onchange = () => {
  if (!initialized || !displayGate.supported || !restartGate.allow(performance.now())) { updateDisplaySettings(); return; }
  const message = displayGate.message(element<HTMLSelectElement>('resolution').value as DisplaySettings['resolution'], Number(element<HTMLSelectElement>('screen-width').value) as DisplaySettings['screenWidth'], element<HTMLSelectElement>('relay-video-codec').value as DisplaySettings['videoCodec']);
  if (message) post(message);
  updateDisplaySettings();
};
element<HTMLSelectElement>('frame-rate').onchange = event => {
  const choice = event.target as HTMLSelectElement;
  const preference = frameRatePreference(choice.value);
  if (preference === preferences.frameRate) return;
  if (initialized && !restartGate.allow(performance.now())) { updateSettings(); return; }
  preferences.frameRate = preference; storage.save(preferences); updateSettings();
  if (!initialized) return;
  hello = withFrameRate(hello, preference);
  post({ type: 'hello', hello }); post({ type: 'restart' });
  element('frame-rate-status').hidden = false;
  choice.disabled = true;
  setTimeout(() => { choice.disabled = false; }, 10_000);
};
// The mode applies without a restart: the receiver and the relay's queue for this client follow the repeated hello.
element<HTMLSelectElement>('playback').onchange = event => {
  const mode = playbackMode((event.target as HTMLSelectElement).value);
  if (mode === preferences.playback) return;
  preferences.playback = mode; storage.save(preferences); updateSettings();
  if (!initialized) return;
  hello = withPlayback(hello, mode); post({ type: 'hello', hello });
};
element('mic-retry').onclick = () => { if (requestedMic?.request) void microphone(requestedMic.request, requestedMic.token); };
for (const id of ['sound-hint', 'card-sound']) element(id).onclick = () => { void audioContext?.resume().then(syncSound).catch(() => {}); };
element('retry').onclick = () => { if (initialized) post({ type: 'reconnect' }); else void start().catch(() => setState('error')); setState('connecting'); };
function statistics(message: Extract<WorkerNotice, { type: 'stats' }>): void {
  if (debug.hidden) return;
  const rows: [string, unknown][] = [['Protocol', protocolState], ['Video', `${videoWidth} × ${videoHeight}`],
    ['Rendered / dropped / skipped', `${message.stats.rendered} / ${message.stats.dropped} / ${message.skipped}`],
    ['Decode / display latency (ms)', `${message.stats.decodeMs?.toFixed(1) ?? '—'} / ${message.stats.renderedLatencyMs?.toFixed(1) ?? '—'}`],
    ['Decode / render queue', `${message.stats.decodeQueue} / ${message.renderQueue} (depth ${message.queueDepth}, ${message.playback})`],
    ['Audio buffered (ms) / underruns', `${message.stats.audioBufferedMs?.toFixed(1) ?? '—'} / ${message.stats.audioUnderruns ?? 0}`],
    ['Microphone', micState], ['Clock offset / RTT (µs)', `${message.stats.clockOffsetUs ?? '—'} / ${message.stats.rttUs ?? '—'}`]];
  for (const a of message.audio) rows.push([`Audio ${a.stream}: packets / decoder outputs`, `${a.packets} / ${a.decoderOutputs}`],
    [`Audio ${a.stream}: max arrival / decode gap (ms)`, `${a.maxArrivalGapMs.toFixed(1)} / ${a.maxDecoderOutputGapMs.toFixed(1)}`]);
  for (const a of message.audioBuffers) rows.push([`Buffer ${a.stream}/${a.run}: min / max / target (ms)`, `${a.minBufferedMs.toFixed(1)} / ${a.maxBufferedMs.toFixed(1)} / ${a.targetMs}`]);
  const list = document.createElement('dl');
  for (const [label, value] of rows) { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = String(value); list.append(dt, dd); }
  element('statistics').replaceChildren(list);
}
const controls = element('controls');
let suppressGearClick = false;
let gearDrag: { id: number; x: number; y: number; origin: { x: number; y: number }; gesture: GearGesture } | undefined;
function gearBounds(): GearBounds {
  const style = getComputedStyle(controls);
  return { width: innerWidth, height: innerHeight, size: gear.offsetHeight,
    top: parseFloat(style.marginTop), right: parseFloat(style.marginRight), bottom: parseFloat(style.marginBottom), left: parseFloat(style.marginLeft) };
}
function placeGear(): void {
  const bounds = gearBounds(), offset = gearOffset(preferences.gear, bounds);
  controls.dataset.side = offset.x + bounds.size / 2 < innerWidth / 2 ? 'left' : 'right';
  controls.style.transform = `translate(${offset.x - bounds.left}px, ${offset.y - bounds.top}px)`;
  pluginSlot.refresh();
}
gear.addEventListener('pointerdown', event => {
  if (event.button !== 0 || event.isPrimary === false || gearDrag) return;
  suppressGearClick = false;
  const box = gear.getBoundingClientRect(), gesture = new GearGesture();
  gearDrag = { id: event.pointerId, x: event.clientX, y: event.clientY, origin: { x: box.left, y: box.top }, gesture };
  gear.setPointerCapture(event.pointerId); wakeGear();
});
gear.addEventListener('pointermove', event => {
  const drag = gearDrag; if (!drag || drag.id !== event.pointerId) return;
  const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
  const wasActive = drag.gesture.active;
  drag.gesture.move(dx, dy);
  if (!wasActive && drag.gesture.active) { releaseAll(); controls.classList.add('dragging'); }
  if (!drag.gesture.active) return;
  event.preventDefault(); wakeGear();
  preferences.gear = clampGear({ x: drag.origin.x + dx, y: drag.origin.y + dy }, gearBounds());
  placeGear();
});
function finishGearDrag(cancelled = false): void {
  const drag = gearDrag; if (!drag) return;
  gearDrag = undefined;
  if (drag.gesture.active) storage.write('gear', JSON.stringify(preferences.gear));
  controls.classList.remove('dragging');
  drag.gesture.finish(cancelled); suppressGearClick = drag.gesture.suppressed;
  if (gear.hasPointerCapture(drag.id)) gear.releasePointerCapture(drag.id);
  placeGear();
}
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) gear.addEventListener(type, event => {
  if ((event as PointerEvent).pointerId === gearDrag?.id) finishGearDrag(type !== 'pointerup');
});
gear.addEventListener('contextmenu', event => event.preventDefault());
for (const type of ['resize', 'orientationchange']) window.addEventListener(type, () => { finishGearDrag(true); placeGear(); });
placeGear();
setState(uiState); updateSettings(); showMic('idle');
// Read permission state without prompting or starting a microphone stream.
void navigator.permissions?.query({ name: 'microphone' as PermissionName }).then(permission => {
  const update = () => {
    if (permission.state === 'denied') showMic('denied');
    else if (micState === 'denied') showMic('idle');
  };
  permission.addEventListener('change', update); update();
}).catch(() => {});
const contactLimit = Math.min(10, navigator.maxTouchPoints || 0);
const contacts = new Map<number, TouchContact>();
let videoWidth = 0, videoHeight = 0, worker: Worker | null = null, hello: Hello;
let micAvailable = false;
const captureLifecycle = new CaptureLifecycle();
let supportResult: ((aac: boolean) => void) | null = null;
function stopCapture(): void { captureLifecycle.stop(); showMic('idle'); post({ type: 'mic-detach' }); }
async function microphone(request: MicFormat | null, token: number): Promise<void> {
  post({ type: 'mic-detach' });
  const fence = captureLifecycle.begin();
  if (!request) { showMic('idle'); return; }
  if (!audioContext || request.codec !== 'PCM_S16LE' || request.frameMs !== 20 ||
      request.sampleRate < 8000 || request.sampleRate > 48000 || request.sampleRate % 50 || ![1, 2].includes(request.channels)) { showMic('unavailable'); return; }
  showMic('requesting');
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: {
      echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1,
    } });
  } catch { if (captureLifecycle.current(fence)) showMic('denied'); return; }
  if (!captureLifecycle.adopt(fence, stream)) return;
  for (const track of stream.getAudioTracks()) track.onended = () => {
    if (captureLifecycle.current(fence)) { stopCapture(); showMic('denied'); }
  };
  try {
    const captureSource = audioContext.createMediaStreamSource(stream);
    const capture = new AudioWorkletNode(audioContext, 'oxp-capture', { processorOptions: { rate: request.sampleRate, channels: request.channels, token } });
    captureLifecycle.attach(captureSource, capture);
    const channel = new MessageChannel();
    capture.port.postMessage({ port: channel.port1 }, [channel.port1]);
    worker?.postMessage({ type: 'mic-port', port: channel.port2, token } satisfies PageCommand, [channel.port2]);
    captureSource.connect(capture); capture.connect(audioContext.destination); showMic('capturing');
  } catch { stopCapture(); showMic('unavailable'); }
}
// Only these events grant user activation for touch input (pointerdown does so only for a mouse).
for (const type of ['pointerup', 'touchend', 'click', 'keydown']) {
  document.addEventListener(type, () => {
    if (audioContext?.state === 'suspended') void audioContext.resume().then(syncSound).catch(() => {});
  }, { passive: true });
}
async function prepareAudio(): Promise<boolean> {
  try {
    if (typeof AudioContext === 'undefined' || typeof AudioWorkletNode === 'undefined') return false;
    audioContext = new AudioContext({ latencyHint: 'interactive' });
    await audioContext.audioWorklet.addModule(workletUrl);
    player = new AudioWorkletNode(audioContext, 'oxp-player', { outputChannelCount: [2] });
    gain = audioContext.createGain(); gain.gain.value = preferences.sound ? 1 : 0;
    player.connect(gain); gain.connect(audioContext.destination);
    const channel = new MessageChannel();
    player.port.postMessage({ port: channel.port1 }, [channel.port1]);
    worker!.postMessage({ type: 'audio-port', port: channel.port2 } satisfies PageCommand, [channel.port2]);
    audioContext.onstatechange = syncSound; syncSound();
    return true;
  } catch { void audioContext?.close(); audioContext = null; syncSound(); return false; }
}
function safeArea(dpr: number) {
  const style = getComputedStyle(document.documentElement);
  const inset = (side: string) => (parseFloat(style.getPropertyValue(`--safe-${side}`)) || 0) * dpr;
  return { top: inset('top'), right: inset('right'), bottom: inset('bottom'), left: inset('left') };
}
function post(message: PageCommand): void { worker?.postMessage(message); }
function releaseAll(): void { contacts.clear(); post({ type: 'touch', contacts: [] }); }
function resize(): void {
  finishGearDrag(); placeGear();
  const box = canvas.getBoundingClientRect(), dpr = devicePixelRatio || 1;
  const width = Math.max(1, Math.round(box.width * dpr)), height = Math.max(1, Math.round(box.height * dpr));
  if (worker) post({ type: 'resize', width, height }); else { canvas.width = width; canvas.height = height; }
  updatePictureSize();
}
function measuredDisplay(): Hello['displays'][number] {
  const box = canvas.getBoundingClientRect(), dpr = devicePixelRatio || 1;
  return { ...hello.displays[0], width: Math.max(1, Math.round(screen.width * dpr)), height: Math.max(1, Math.round(screen.height * dpr)), dpr, safeArea: safeArea(dpr),
    viewport: { width: Math.max(1, Math.round(box.width * dpr)), height: Math.max(1, Math.round(box.height * dpr)) }, fullscreen: !!document.fullscreenElement, fullscreenPreferred: fullscreen.intent };
}
// Polling also catches DPR, safe-area and screen changes that do not resize the CSS box.
setInterval(() => {
  if (!initialized || !hello) return;
  const display = fullscreen.display(measuredDisplay());
  if (displayDebounce.observe(display, performance.now())) {
    hello = { ...hello, displays: [display] }; post({ type: 'hello', hello }); resize();
  }
}, 100);
function displayReportSeed(): void {
  hello = { ...hello, displays: [fullscreen.display(measuredDisplay())] }; displayDebounce.seed(hello.displays[0]);
}
function position(event: PointerEvent, clamp: boolean) {
  const box = canvas.getBoundingClientRect();
  return mapTouch(event.clientX - box.left, event.clientY - box.top, videoRect(box.width, box.height, videoWidth, videoHeight, preferences.fit, viewArea), clamp, viewArea);
}
canvas.addEventListener('pointerdown', event => {
  event.preventDefault();
  const point = position(event, false); if (!point) { wakeGear(); return; }
  if (contacts.has(event.pointerId) || contacts.size >= contactLimit || event.button !== 0) return;
  const used = new Set([...contacts.values()].map(c => c.id));
  const id = Array.from({ length: contactLimit }, (_, i) => i).find(i => !used.has(i));
  if (id === undefined) return;
  contacts.set(event.pointerId, { id, pressed: true, ...point }); canvas.setPointerCapture(event.pointerId);
  post({ type: 'touch', contacts: [...contacts.values()] });
});
canvas.addEventListener('pointermove', event => {
  const contact = contacts.get(event.pointerId); if (!contact) return;
  event.preventDefault(); const point = position(event, true); if (!point) return;
  contacts.set(event.pointerId, { ...contact, ...point }); post({ type: 'touch', contacts: [...contacts.values()] });
});
canvas.addEventListener('pointerup', event => {
  const contact = contacts.get(event.pointerId); if (!contact) return;
  const point = position(event, true) ?? contact;
  contacts.delete(event.pointerId);
  post({ type: 'touch', contacts: [...contacts.values(), { ...contact, x: point.x, y: point.y, pressed: false }] });
  if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
});
canvas.addEventListener('pointercancel', releaseAll);
canvas.addEventListener('lostpointercapture', event => { if (contacts.has(event.pointerId)) releaseAll(); });
document.addEventListener('visibilitychange', () => { fullscreenHistory.visibility(document.hidden, performance.now());
  if (!document.hidden) requestAnimationFrame(reportFullscreenExit); releaseAll(); post({ type: 'visibility', visible: !document.hidden }); });
window.addEventListener('pagehide', () => { stopCapture(); void audioContext?.close(); releaseAll(); post({ type: 'stop' }); });
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
new ResizeObserver(resize).observe(canvas);
let receiverInfoStarted = false;
let lag: number[] = [], longTaskCount = 0, longTaskTotalMs = 0, previousTick = performance.now(), pageWindow = previousTick;
try {
  new PerformanceObserver(list => { for (const entry of list.getEntries()) { longTaskCount++; longTaskTotalMs += entry.duration; } }).observe({ type: 'longtask', buffered: false });
} catch { /* Long tasks are unavailable in some browsers. */ }
setInterval(() => {
  const now = performance.now(); lag.push(Math.max(0, now - previousTick - 100)); previousTick = now;
  if (now - pageWindow < 1000) return;
  let usedJSHeapSize: number | undefined;
  try { usedJSHeapSize = (performance as Performance & { memory?: { usedJSHeapSize: number } }).memory?.usedJSHeapSize; } catch { /* Optional Chromium heap measurement. */ }
  post({ type: 'page-metrics', metrics: { eventLoopLagAvgMs: lag.reduce((a, b) => a + b, 0) / lag.length, eventLoopLagMaxMs: Math.max(...lag), longTaskCount, longTaskTotalMs, usedJSHeapSize } });
  lag = []; longTaskCount = 0; longTaskTotalMs = 0; pageWindow = now;
}, 100);
async function start(): Promise<void> {
  if (starting || initialized) return;
  starting = true;
  try {
    if (!isSecureContext || !canvas.transferControlToOffscreen || typeof Worker === 'undefined' || typeof VideoDecoder === 'undefined') {
      setState('unsupported'); return;
    }
    setState('connecting');
    const hardware = new Map<string, boolean>();
    void Promise.all(codecCandidates.map(async codec => hardware.set(codec, await codecPowerEfficient(codec)))).then(() => {
      if (hello) { hello = { ...hello, decoders: hello.decoders.map(d => ({ ...d, hardware: hardware.get(d.codecString) ?? false })) }; if (initialized) post({ type: 'hello', hello }); }
    });
    const [probes, refreshHz] = await Promise.all([probeDecoders(), estimateRefreshHz()]);
    const dpr = devicePixelRatio || 1;
    hello = { type: 'hello', version: 0, token: '',
      decoders: [...new Set(probes.filter(p => p.supported).map(p => p.codecString))].map(codecString => ({ codecString, hardware: hardware.get(codecString) ?? false })),
      displays: [{ screen: 0, width: Math.round(screen.width * dpr), height: Math.round(screen.height * dpr), dpr, refreshHz,
        safeArea: safeArea(dpr) }], maxTouchContacts: contactLimit, audio: [], microphone: false };
    hello = withPlayback(withFrameRate(hello, preferences.frameRate), preferences.playback);
    displayReportSeed(); resize();
    worker?.terminate(); void audioContext?.close(); audioContext = null; gain = null;
    let workerFailed = false;
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (event: MessageEvent<WorkerNotice>) => {
      const message = event.data;
      switch (message.type) {
        case 'audio-support': supportResult?.(message.aac); supportResult = null; break;
        case 'audio-active': audioActive = message.active; syncSound(); break;
        case 'mic': requestedMic = message; void microphone(message.request, message.token); break;
        case 'status':
          protocolState = message.text;
          updatePlayback(message); break;
        case 'retry': {
          const until = performance.now() + message.delayMs;
          const tick = () => { if (uiState === 'reconnecting') element('subline').textContent = text.attempt.replace('{attempt}', String(message.attempt)).replace('{seconds}', String(Math.max(0, Math.ceil((until - performance.now()) / 1000)))); };
          tick(); countdown = setInterval(tick, 1000); break;
        }
        case 'hold': updatePlayback(message); break;
        case 'ended': updatePlayback(message); break;
        case 'playing': element('frame-rate-status').hidden = true; updatePlayback(message); wakeGear(); break;
        case 'view-area': releaseAll(); viewArea = message.area; break;
        case 'display-disconnected': fullscreen.viewAreas = false; displayGate.welcome(); updateDisplaySettings(); break;
        case 'display-settings': currentDisplaySettings = message; updateDisplaySettings(); break;
        case 'welcome':
          if (shouldReload(message.receiverBuild, () => sessionStorage)) { location.reload(); break; }
          fullscreen.viewAreas = message.features?.includes('view-area') ?? false;
          displayGate.welcome(message.features); updateDisplaySettings();
          pluginSlot.configure(message);
          if (!receiverInfoStarted) { receiverInfoStarted = true; void collectReceiverInfo().then(info => post({ type: 'receiver-info', info })); }
          source.href = message.sourceUrl;
          element('relay-version-row').hidden = !message.relay;
          element('relay-name').textContent = message.relay?.name ?? '';
          element('relay-version').textContent = message.relay?.version ?? '';
          break;
        case 'format':
          releaseAll(); videoWidth = message.format?.width ?? 0; videoHeight = message.format?.height ?? 0;
          showCodecInUse(message.format?.codec); updatePictureSize();
          break;
        case 'stats': statistics(message); break;
      }
    };
    worker.onerror = () => { workerFailed = true; supportResult?.(false); supportResult = null; stopCapture(); releaseAll(); setState('error'); };
    const worklet = await prepareAudio();
    const aac = await new Promise<boolean>(resolve => {
      const timeout = setTimeout(() => { supportResult = null; resolve(false); }, 2000);
      supportResult = supported => { clearTimeout(timeout); resolve(supported); };
      post({ type: 'audio-probe' });
    });
    if (workerFailed) throw new Error('Receiver worker failed');
    micAvailable = worklet && typeof navigator.mediaDevices?.getUserMedia === 'function';
    hello = { ...hello, ...audioCapabilities(worklet, aac, preferences.microphone && micAvailable) };
    hello = { ...hello, decoders: hello.decoders.map(d => ({ ...d, hardware: hardware.get(d.codecString) ?? false })) };
    const offscreen = canvas.transferControlToOffscreen();
    const message: PageCommand = { type: 'init', canvas: offscreen, url: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/oxp/v0`, hello,
      queueDepth: renderQueueDepth(fragment.get('queue')) };
    worker.postMessage(message, [offscreen]); initialized = true;
    post({ type: 'fit', fit: preferences.fit });
    post({ type: 'visibility', visible: !document.hidden });
  } finally { starting = false; }
}
void start().catch(() => setState('error'));
