// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15

import { expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { applyReducedMotion, microphoneStatus, statusCard, strings, type StatusState } from '../receiver/ui';
import { bindSheet, sheetKey } from '../receiver/sheet';
import { version } from '../receiver/version';

it('injects the package version into the same module used by About', () => {
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  expect(version.split(' ')[0]).toBe(pkg.version); expect(version).not.toBe('0.0.0');
});
it('maps microphone lifecycle to honest status, guidance and only useful actions', () => {
  for (const state of ['idle', 'disabled', 'requesting'] as const) expect(microphoneStatus(state).label).toBe('notInUse');
  expect(microphoneStatus('requesting').hint).toBe('micRequestHint');
  expect(microphoneStatus('capturing')).toEqual({ label: 'listening', hint: 'micListeningHint', retry: false });
  expect(microphoneStatus('denied')).toEqual({ label: 'blocked', hint: 'micBlockedHint', retry: true });
  expect(microphoneStatus('unavailable')).toEqual({ label: 'blocked', hint: 'micUnavailableHint', retry: false });
});
it('supplies plain bilingual copy for every receiver state and microphone status', () => {
  const states: StatusState[] = ['code', 'incorrect', 'connecting', 'waiting', 'playing', 'reconnecting', 'error', 'unsupported', 'soundBlocked', 'microphoneDenied'];
  for (const language of ['en', 'zh-CN'] as const) {
    for (const state of states) {
      const card = statusCard(state);
      expect(strings[language][card.headline].length).toBeGreaterThan(2);
      expect(strings[language][card.subline].length).toBeGreaterThan(2);
    }
    for (const state of ['idle', 'capturing', 'denied', 'unavailable', 'requesting'] as const) {
      const status = microphoneStatus(state);
      expect(strings[language][status.label]).toBeTruthy(); expect(strings[language][status.hint]).toBeTruthy();
    }
    expect(strings[language].attempt).toContain('{attempt}'); expect(strings[language].attempt).toContain('{seconds}');
  }
  expect(strings.en.error).toBe('Relay unreachable');
  expect(strings['zh-CN'].microphoneDenied).toBe('麦克风已被阻止');
});
it('applies and removes the reduced-motion class on preference changes', () => {
  const classes = new Set<string>();
  const target = { classList: { toggle: (name: string, force: boolean) => force ? classes.add(name) : classes.delete(name) } };
  applyReducedMotion(target, true); expect([...classes]).toEqual(['reduced-motion']);
  applyReducedMotion(target, false); expect(classes.size).toBe(0);
});
function control(checkbox = false) {
  return { focus: vi.fn(), click: vi.fn(), matches: () => checkbox, closest: () => null } as unknown as HTMLElement;
}
function key(key: string, shiftKey = false) { return { key, shiftKey, preventDefault: vi.fn() }; }
it('traps Tab and Shift+Tab at sheet boundaries, preserves internal order and closes on Back/Escape', () => {
  const controls = [control(), control(true), control(), control()]; const close = vi.fn();
  let event = key('Tab'); sheetKey(event, controls, controls[3], close); expect(controls[0].focus).toHaveBeenCalledOnce(); expect(event.preventDefault).toHaveBeenCalledOnce();
  event = key('Tab', true); sheetKey(event, controls, controls[0], close); expect(controls[3].focus).toHaveBeenCalledOnce();
  event = key('Tab'); sheetKey(event, controls, controls[1], close); expect(event.preventDefault).not.toHaveBeenCalled();
  sheetKey(key('Enter'), controls, controls[1], close); expect(controls[1].click).toHaveBeenCalledOnce();
  for (const name of ['Escape', 'BrowserBack', 'GoBack', 'Backspace']) sheetKey(key(name), controls, controls[0], close);
  expect(close).toHaveBeenCalledTimes(4);
});
it('moves arrows in order, wraps, and confines horizontal arrows to the picture-fit group', () => {
  const controls = [control(), control(), control(), control()]; const close = vi.fn();
  sheetKey(key('ArrowDown'), controls, controls[0], close); expect(controls[1].focus).toHaveBeenCalledOnce();
  sheetKey(key('ArrowUp'), controls, controls[0], close); expect(controls[3].focus).toHaveBeenCalledOnce();
  const group = { contains: (node: HTMLElement) => controls.slice(1, 3).includes(node) };
  controls[1].closest = (() => group) as HTMLElement['closest']; controls[2].closest = (() => group) as HTMLElement['closest'];
  sheetKey(key('ArrowRight'), controls, controls[2], close); expect(controls[1].focus).toHaveBeenCalledTimes(2);
  sheetKey(key('ArrowLeft'), controls, controls[1], close); expect(controls[2].focus).toHaveBeenCalledOnce();
});

function sheetFixture(reduced: boolean) {
  const handlers = new Map<string, (event: any) => void>();
  const classes = new Set<string>();
  const close = control(), sound = control(true);
  for (const item of [close, sound]) { item.closest = (() => null) as HTMLElement['closest']; item.getClientRects = (() => [1]) as unknown as HTMLElement['getClientRects']; item.matches = (() => false); }
  const sheet = {
    open: false, classList: { add: (s: string) => classes.add(s), remove: (s: string) => classes.delete(s), contains: (s: string) => classes.has(s) },
    querySelectorAll: () => [close, sound],
    querySelector: () => ({ addEventListener: (type: string, handler: (event: any) => void) => handlers.set(`header:${type}`, handler), setPointerCapture: vi.fn() }),
    showModal() { this.open = true; }, close() { this.open = false; },
    addEventListener: (type: string, handler: (event: any) => void) => handlers.set(type, handler),
  };
  const opener = control(); const backdropClasses = new Set();
  const backdrop = { hidden: true, classList: { add: (s: string) => backdropClasses.add(s), remove: (s: string) => backdropClasses.delete(s) } };
  const binding = bindSheet(sheet as unknown as HTMLDialogElement, opener, close, backdrop as unknown as HTMLElement, () => reduced);
  return { binding, sheet, opener, close, sound, backdrop, handlers };
}
it('opens a modal with Close focused first and restores focus after its exit animation', () => {
  vi.useFakeTimers();
  try {
    const fixture = sheetFixture(false); fixture.binding.open();
    expect(fixture.sheet.open).toBe(true); expect(fixture.close.focus).toHaveBeenCalledOnce(); expect(fixture.backdrop.hidden).toBe(false);
    fixture.binding.close(); expect(fixture.sheet.open).toBe(true);
    vi.advanceTimersByTime(300); expect(fixture.sheet.open).toBe(false); expect(fixture.opener.focus).toHaveBeenCalledOnce(); expect(fixture.backdrop.hidden).toBe(true);
  } finally { vi.useRealTimers(); }
});
it('closes immediately with reduced motion and handles native Escape cancellation', () => {
  const fixture = sheetFixture(true); fixture.binding.open();
  const event = { preventDefault: vi.fn() }; fixture.handlers.get('cancel')!(event);
  expect(event.preventDefault).toHaveBeenCalledOnce(); expect(fixture.sheet.open).toBe(false); expect(fixture.opener.focus).toHaveBeenCalledOnce();
});
it('closes on a deliberate downward touch swipe, preserving taps and horizontal gestures', () => {
  const fixture = sheetFixture(true);
  const swipe = (x: number, y: number) => {
    fixture.binding.open();
    fixture.handlers.get('header:pointerdown')!({ pointerType: 'touch', pointerId: 1, clientX: 100, clientY: 10, target: { closest: () => null } });
    fixture.handlers.get('header:pointerup')!({ pointerId: 1, clientX: x, clientY: y });
    return fixture.sheet.open;
  };
  expect(swipe(100, 30)).toBe(true); expect(swipe(200, 100)).toBe(true); expect(swipe(105, 100)).toBe(false);
});
it('keeps the preview out of production inputs and preserves the sheet DOM focus order', () => {
  const config = readFileSync(new URL('../vite.config.ts', import.meta.url), 'utf8');
  expect(config).toContain("receiver: 'receiver/index.html', probe: 'probe/index.html'");
  const html = readFileSync(new URL('../receiver/index.html', import.meta.url), 'utf8').split('<dialog')[1];
  const ids = ['close-settings', 'sound', 'mic-retry', 'fit', 'fill', 'fullscreen', 'developer', 'source'];
  const positions = ids.map(id => html.indexOf(`id="${id}"`));
  expect(positions.every(n => n >= 0)).toBe(true); expect(positions).toEqual([...positions].sort((a, b) => a - b));
});
