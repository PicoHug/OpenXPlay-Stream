// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

import { it, expect } from 'vitest';
import { pictureSource, canvasTouch, videoRect, mapTouch } from '../shared/math';
const area = { x: 120, y: 40, width: 1440, height: 900, canvasWidth: 1920, canvasHeight: 1080 };
it('scales the crop to decoded frame pixels and maps touches back into the canvas', () => {
  expect(pictureSource(960, 540, area)).toEqual({ x: 60, y: 20, width: 720, height: 450 });
  expect(pictureSource(960, 540)).toEqual({ x: 0, y: 0, width: 960, height: 540 });
  const rect = videoRect(1000, 1000, area.width, area.height);
  expect(rect).toEqual({ x: 0, y: 187.5, width: 1000, height: 625 });
  expect(canvasTouch(mapTouch(500, 500, rect), area)).toEqual({ x: 840 / 1920, y: 490 / 1080 });
  expect(canvasTouch(mapTouch(0, 0, rect), area)).toBeNull();
  expect(canvasTouch(mapTouch(0, 0, rect, true), area)).toEqual({ x: 120 / 1920, y: 40 / 1080 });
  const fill = videoRect(1000, 1000, area.width, area.height, 'fill');
  expect(canvasTouch(mapTouch(500, 500, fill), area)).toEqual({ x: 840 / 1920, y: 490 / 1080 });
});
