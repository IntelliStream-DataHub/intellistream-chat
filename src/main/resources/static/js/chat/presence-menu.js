/*
 * Copyright 2026 Olav Gjerde
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Slack/Mattermost-style presence-kind picker. Clicking the topbar avatar opens
 * a small floating menu with the four kinds (Active / Away / DND / Offline);
 * clicking an option calls PUT /api/presence/kind and the server broadcasts via
 * /topic/presence so all the user's tabs (and other users) update live.
 *
 * The topbar `<a class="me" href="/profile">` still navigates to the profile
 * page when the user clicks the display-name half — only avatar clicks open
 * the menu, via preventDefault on the parent link.
 */

import { headers } from './shared.js';

const KINDS = [
    { value: 'ACTIVE',  label: 'Active',         hint: 'Connected, available' },
    { value: 'AWAY',    label: 'Away',           hint: "Step away from the keyboard" },
    { value: 'DND',     label: 'Do not disturb', hint: 'Notifications muted' },
    { value: 'OFFLINE', label: 'Offline',        hint: 'Appear offline to others' },
];

let menuEl = null;
/** Index of the currently keyboard-focused item; -1 means nothing focused. */
let focusedIdx = -1;

function closeMenu() {
    if (menuEl) {
        menuEl.remove();
        menuEl = null;
        focusedIdx = -1;
    }
}

/** All focusable menu items in DOM order. Used by the arrow-key nav. */
function items() {
    return menuEl ? [...menuEl.querySelectorAll('[role="menuitem"]')] : [];
}

function focusItem(idx) {
    const list = items();
    if (!list.length) return;
    focusedIdx = (idx + list.length) % list.length;
    list[focusedIdx].focus();
}

function openMenu(anchor) {
    closeMenu();
    menuEl = document.createElement('div');
    menuEl.className = 'presence-menu';
    menuEl.setAttribute('role', 'menu');

    KINDS.forEach((k) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'presence-menu-item';
        item.setAttribute('role', 'menuitem');
        item.dataset.kind = k.value;
        item.innerHTML =
            '<span class="presence-menu-dot" data-presence-kind="' + k.value + '"></span>' +
            '<span class="presence-menu-label">' + k.label + '</span>' +
            '<span class="presence-menu-hint">' + k.hint + '</span>';
        item.addEventListener('click', () => {
            applyKind(k.value);
            closeMenu();
        });
        menuEl.appendChild(item);
    });

    // Divider then "View profile" / "Set a status" — match Slack's avatar dropdown shape.
    const divider = document.createElement('div');
    divider.className = 'presence-menu-divider';
    menuEl.appendChild(divider);

    const profileLink = document.createElement('a');
    profileLink.className = 'presence-menu-item presence-menu-link';
    profileLink.setAttribute('role', 'menuitem');
    profileLink.href = '/profile';
    profileLink.innerHTML = '<span class="presence-menu-label">View profile</span>';
    menuEl.appendChild(profileLink);

    const statusLink = document.createElement('a');
    statusLink.className = 'presence-menu-item presence-menu-link';
    statusLink.setAttribute('role', 'menuitem');
    // Profile page hosts the status emoji + clear-at editor; deep-link to its anchor.
    statusLink.href = '/profile#status-section';
    statusLink.innerHTML = '<span class="presence-menu-label">Set a status</span>';
    menuEl.appendChild(statusLink);

    // Anchor below the avatar; constrained to the viewport via simple right-edge clamp.
    const r = anchor.getBoundingClientRect();
    menuEl.style.position = 'fixed';
    menuEl.style.top = (r.bottom + 6) + 'px';
    menuEl.style.right = Math.max(8, window.innerWidth - r.right) + 'px';
    document.body.appendChild(menuEl);

    // Keyboard nav: focus the first item so the user can tab/arrow without a mouse.
    focusItem(0);
}

function handleKeyNav(e) {
    if (!menuEl) return;
    if (e.key === 'Escape') {
        e.preventDefault();
        closeMenu();
        return;
    }
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        focusItem(focusedIdx + 1);
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        focusItem(focusedIdx - 1);
    } else if (e.key === 'Home') {
        e.preventDefault();
        focusItem(0);
    } else if (e.key === 'End') {
        e.preventDefault();
        focusItem(items().length - 1);
    }
    // Enter/Space on a focused item is the browser default for <button>/<a>; nothing to do.
}

async function applyKind(kind) {
    try {
        const res = await fetch('/api/presence/kind', {
            method: 'PUT',
            headers: headers(),
            body: JSON.stringify({ kind }),
        });
        if (!res.ok) {
            // The /topic/presence broadcast is the source of truth for the dot — we don't
            // need to mutate the DOM here on success. Surface failures only.
            const err = await res.json().catch(() => ({}));
            console.error('Failed to set presence kind:', err.message || res.statusText);
        }
    } catch (e) {
        console.error('Failed to set presence kind:', e);
    }
}

function dismissOnOutsideClick(e) {
    if (!menuEl) return;
    // e.target can be the Document itself (e.g. a synthesized event after the node
    // under the pointer was replaced) — that counts as "outside", but has no closest().
    const t = e.target instanceof Element ? e.target : null;
    if (t && menuEl.contains(t)) return;
    if (t && t.closest('.me .avatar')) return; // re-clicks on the trigger handled separately
    closeMenu();
}

export function init() {
    const meLink = document.querySelector('a.me');
    if (!meLink) return;
    const avatar = meLink.querySelector('.avatar');
    if (!avatar) return;

    // Clicking the avatar opens the menu instead of navigating to /profile. The
    // display-name half of the .me link is a separate event target and still
    // navigates — clicks fall through to the parent <a>.
    avatar.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (menuEl) closeMenu();
        else openMenu(avatar);
    });
    // Keyboard equivalent: Enter/Space on the avatar with focus toggles the menu.
    avatar.setAttribute('tabindex', '0');
    avatar.setAttribute('role', 'button');
    avatar.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            if (menuEl) closeMenu();
            else openMenu(avatar);
        }
    });

    document.addEventListener('click', dismissOnOutsideClick);
    document.addEventListener('keydown', handleKeyNav);
}
