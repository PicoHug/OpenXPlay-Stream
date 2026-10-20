// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

import { bestEffort } from './best-effort';

export interface CodecProbe { codecString: string; width: number; height: number; supported: boolean; error?: string }
// The higher levels cover what the iPhone sends at 1080p 60 fps (4.2 and above); a relay treats a lower
// reported level as unable to decode the stream and transcodes it.
export const codecCandidates = [
  'avc1.42E01F', 'avc1.42E028', 'avc1.4D401F', 'avc1.4D4028', 'avc1.64001F', 'avc1.640028',
  'avc1.64002A', 'avc1.640032', 'avc1.640033', 'avc1.640034',
  'hvc1.1.6.L93.B0', 'hvc1.1.6.L120.B0', 'hvc1.1.6.L123.B0', 'hvc1.1.6.L150.B0', 'hvc1.1.6.L153.B0',
];
export async function probeDecoders(): Promise<CodecProbe[]> {
  return Promise.all(codecCandidates.flatMap(codecString => [[1280, 720], [1920, 1080]].map(async ([width, height]) => {
    const support = await bestEffort(() => VideoDecoder.isConfigSupported({
      codec: codecString, codedWidth: width, codedHeight: height, hardwareAcceleration: 'prefer-hardware', optimizeForLatency: true,
    }));
    return { codecString, width, height, supported: support?.supported ?? false };
  })));
}
export function estimateRefreshHz(): Promise<number> {
  return new Promise(resolve => {
    const intervals: number[] = []; let previous = 0, done = false, handle = 0;
    const finish = () => {
      if (done) return; done = true; cancelAnimationFrame(handle); clearTimeout(timer);
      intervals.sort((a, b) => a - b);
      resolve(intervals.length ? Math.round(1000 / intervals[Math.floor(intervals.length / 2)] * 10) / 10 : 60);
    };
    const timer = setTimeout(finish, 1200);
    const tick = (time: number) => {
      if (done) return;
      if (previous && time > previous) intervals.push(time - previous);
      previous = time;
      if (intervals.length >= 24) finish(); else handle = requestAnimationFrame(tick);
    };
    handle = requestAnimationFrame(tick);
  });
}

export function audioCapabilities(worklet: boolean, aac: boolean, getUserMedia: boolean): { audio: import('../protocol/json').AudioCodec[]; microphone: boolean } {
  return { audio: worklet ? ['PCM_S16LE', ...(aac ? ['AAC_LC' as const] : [])] : [], microphone: worklet && getUserMedia };
}
