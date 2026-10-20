// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

export interface Rect { x: number; y: number; width: number; height: number }
export function videoRect(width: number, height: number, videoWidth: number, videoHeight: number, fit: 'fit' | 'fill' = 'fit'): Rect {
  if ([width, height, videoWidth, videoHeight].some(n => !Number.isFinite(n) || n <= 0)) return { x: 0, y: 0, width: 0, height: 0 };
  // The filled axis uses the container size exactly so edge taps never fall outside by rounding.
  if ((width / videoWidth <= height / videoHeight) === (fit === 'fit')) {
    const h = videoHeight * (width / videoWidth);
    return { x: 0, y: (height - h) / 2, width, height: h };
  }
  const w = videoWidth * (height / videoHeight);
  return { x: (width - w) / 2, y: 0, width: w, height };
}
export function mapTouch(x: number, y: number, rect: Rect, clamp = false): { x: number; y: number } | null {
  if (rect.width <= 0 || rect.height <= 0 || !Number.isFinite(x) || !Number.isFinite(y)) return null;
  const nx = (x - rect.x) / rect.width, ny = (y - rect.y) / rect.height;
  if (!clamp && (nx < 0 || nx > 1 || ny < 0 || ny > 1)) return null;
  return { x: Math.max(0, Math.min(1, nx)), y: Math.max(0, Math.min(1, ny)) };
}
// Offset is relay clock minus client clock. All timestamps are monotonic microseconds.
export function clockSample(t0: bigint, t1: bigint, t2: bigint, t3: bigint): { offsetUs: bigint; rttUs: bigint } | null {
  const rttUs = (t3 - t0) - (t2 - t1);
  if (t3 < t0 || t2 < t1 || rttUs < 0n) return null;
  return { offsetUs: ((t1 - t0) + (t2 - t3)) / 2n, rttUs };
}
export function latencyMs(clientUs: bigint, relayUs: bigint, offsetUs: bigint | null): number | null {
  if (offsetUs === null) return null;
  const delta = clientUs + offsetUs - relayUs;
  return delta >= 0n && delta <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(delta) / 1000 : null;
}
export interface DropDecision { waiting: boolean; decode: boolean; reset: boolean; requestKeyframe: boolean }
export function dropPolicy(waiting: boolean, keyframe: boolean, queue: number, gap = false, error = false, maxQueue = 3): DropDecision {
  const reset = error;
  const needsKey = waiting || queue > maxQueue || gap || error;
  const decode = !error && (!needsKey || keyframe);
  return { waiting: needsKey && !decode, decode, reset, requestKeyframe: needsKey && !decode };
}
// Depth counts extra frames behind the next frame due for display. The fragment sets Smart's initial depth, zero or one.
export function renderQueueDepth(value: string | null): 0 | 1 { return value === '0' ? 0 : 1; }
// A queued frame later than this would show a network or render stall, so newest-only wins instead.
export const QUEUE_LATENCY_BUDGET_MS = 80;
export function renderQueuePolicy<T>(frames: readonly T[], incoming: T, depth: number,
  late: (item: T) => boolean = () => false): { frames: T[]; skipped: T[] } {
  if (depth <= 0 || frames.length === 0) return { frames: [incoming], skipped: [...frames] };
  // Keep the newest queued frames: holding the oldest would carry a render stall into their latency.
  const previous = frames[frames.length - 1];
  if (late(previous)) return { frames: [incoming], skipped: [...frames] };
  const kept = frames.slice(-depth);
  return { frames: [...kept, incoming], skipped: frames.slice(0, frames.length - kept.length) };
}
export const nowUs = (): bigint => BigInt(Math.round(performance.now() * 1000));

export interface PictureArea { x: number; y: number; width: number; height: number; canvasWidth: number; canvasHeight: number }
export function pictureSource(width: number, height: number, area?: PictureArea): Rect {
  if (!area) return { x: 0, y: 0, width, height };
  return { x: area.x * width / area.canvasWidth, y: area.y * height / area.canvasHeight,
    width: area.width * width / area.canvasWidth, height: area.height * height / area.canvasHeight };
}
export function canvasTouch(point: { x: number; y: number } | null, area?: PictureArea): { x: number; y: number } | null {
  if (!point || !area) return point;
  return { x: (area.x + point.x * area.width) / area.canvasWidth, y: (area.y + point.y * area.height) / area.canvasHeight };
}
