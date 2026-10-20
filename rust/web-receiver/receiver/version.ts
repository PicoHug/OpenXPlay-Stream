// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15

export const version = [__APP_VERSION__, __APP_COMMIT__ && `(${__APP_COMMIT__})`].filter(Boolean).join(' ');
export const build = __APP_BUILD__;
export function shouldReload(receiverBuild: string | undefined, storage: () => Pick<Storage, 'getItem' | 'setItem'>, pageBuild = build): boolean {
  if (!receiverBuild || receiverBuild === pageBuild) return false;
  try {
    const store = storage(), key = `oxp-receiver-reloaded:${receiverBuild}`;
    if (store.getItem(key) !== null) return false;
    store.setItem(key, '1'); return store.getItem(key) === '1';
  } catch { return false; }
}
