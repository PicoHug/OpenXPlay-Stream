// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

import { it, expect } from 'vitest';
import { shouldReload, version, build } from '../receiver/version';
it('reloads once per distinct server build across upgrades and rollbacks', () => {
  const seen = new Map<string, string>(), storage = () => ({ getItem: (k: string) => seen.get(k) ?? null, setItem: (k: string, v: string) => { seen.set(k, v); } });
  expect(build).toBeTruthy(); expect(version).toContain(__APP_VERSION__);
  expect(shouldReload(undefined, storage, 'page')).toBe(false); expect(shouldReload('page', storage, 'page')).toBe(false);
  expect(shouldReload('a', storage, 'page')).toBe(true); expect(shouldReload('a', storage, 'page')).toBe(false);
  expect(shouldReload('b', storage, 'page')).toBe(true); expect(shouldReload('a', storage, 'page')).toBe(false);
  expect(shouldReload('c', () => { throw Error(); }, 'page')).toBe(false);
  expect(shouldReload('c', () => ({ getItem: () => null, setItem: () => {} }), 'page')).toBe(false);
});
