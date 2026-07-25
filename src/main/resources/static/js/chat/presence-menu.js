/*
 * Copyright 2026 IntelliStream AS
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
/** Pending hover-out close; cancelled when the pointer re-enters the trigger or menu. */
let closeTimer = null;

function cancelClose() {
    clearTimeout(closeTimer);
    closeTimer = null;
}

function scheduleClose() {
    cancelClose();
    // Grace period long enough to travel across the 6px gap into the menu.
    closeTimer = setTimeout(closeMenu, 220);
}

function closeMenu() {
    cancelClose();
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

function openMenu(anchor, opts = {}) {
    closeMenu();
    menuEl = document.createElement('div');
    menuEl.className = 'presence-menu';
    menuEl.setAttribute('role', 'menu');
    // Keep the menu open while the pointer is inside it (hover-open pairing).
    menuEl.addEventListener('mouseenter', cancelClose);
    menuEl.addEventListener('mouseleave', scheduleClose);

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

    // Admin console — only for workspace admins (realm role ichat-admin → ROLE_ADMIN;
    // the me-is-workspace-admin meta is emitted via sec:authorize on every page).
    if (document.querySelector('meta[name="me-is-workspace-admin"]')?.content === 'true') {
        const adminLink = document.createElement('a');
        adminLink.className = 'presence-menu-item presence-menu-link';
        adminLink.setAttribute('role', 'menuitem');
        adminLink.href = '/admin';
        adminLink.innerHTML = '<span class="presence-menu-label">Admin console</span>';
        menuEl.appendChild(adminLink);
    }

    const aboutItem = document.createElement('button');
    aboutItem.type = 'button';
    aboutItem.className = 'presence-menu-item presence-menu-link';
    aboutItem.setAttribute('role', 'menuitem');
    // textContent, not innerHTML: the app title is admin-editable branding, so treating it
    // as markup would turn "set the workspace name" into stored XSS against every user.
    const aboutLabel = document.createElement('span');
    aboutLabel.className = 'presence-menu-label';
    aboutLabel.textContent = 'About ' + appName();
    aboutItem.appendChild(aboutLabel);
    aboutItem.addEventListener('click', () => { closeMenu(); openAbout(); });
    menuEl.appendChild(aboutItem);

    // Sign out — submits the hidden #logout-form so the POST keeps the
    // Thymeleaf-injected CSRF token (a plain fetch would have to re-plumb it).
    const divider2 = document.createElement('div');
    divider2.className = 'presence-menu-divider';
    menuEl.appendChild(divider2);

    const signOut = document.createElement('button');
    signOut.type = 'button';
    signOut.className = 'presence-menu-item presence-menu-link presence-menu-signout';
    signOut.setAttribute('role', 'menuitem');
    signOut.innerHTML = '<span class="presence-menu-label">Sign out</span>';
    signOut.addEventListener('click', () => {
        closeMenu();
        document.getElementById('logout-form')?.submit();
    });
    menuEl.appendChild(signOut);

    // Anchor below the avatar; constrained to the viewport via simple right-edge clamp.
    const r = anchor.getBoundingClientRect();
    menuEl.style.position = 'fixed';
    menuEl.style.top = (r.bottom + 6) + 'px';
    menuEl.style.right = Math.max(8, window.innerWidth - r.right) + 'px';
    document.body.appendChild(menuEl);

    // Focus the first item only for keyboard opens — a hover-open must not steal
    // focus from whatever the user is typing in. Arrow keys still work either way.
    if (opts.focusFirst) focusItem(0);
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
    // <a class="me"> on the chat pages, <span class="me"> on profile/admin — the menu
    // works on both (preventDefault on the avatar is a no-op for the span).
    const meLink = document.querySelector('.topbar .me');
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
            else openMenu(avatar, { focusFirst: true });
        }
    });

    // Hover-open on pointer devices only: touch synthesizes mouseenter right before
    // click, which would open the menu and have the click instantly toggle it shut.
    if (window.matchMedia('(hover: hover)').matches) {
        meLink.addEventListener('mouseenter', () => {
            cancelClose();
            if (!menuEl) openMenu(avatar);
        });
        meLink.addEventListener('mouseleave', scheduleClose);
    }

    document.addEventListener('click', dismissOnOutsideClick);
    document.addEventListener('keydown', handleKeyNav);
}


// ---------------------------------------------------------------- About ----
// A dialog rather than a page: it is a thing you glance at while doing something
// else (quoting a version into a bug report), and a route would lose your place in
// the channel. Rendered on demand and thrown away on close, so it can never show a
// stale version after a deploy.

function appName() {
    // The topbar logo text is the server-rendered app title (Thymeleaf escaped it on the way
    // out); reading it back as textContent gives the configured name with no extra plumbing.
    return document.querySelector('.logo-text')?.textContent?.trim() || 'IntelliStream Chat';
}

let aboutEl = null;

function closeAbout() {
    if (!aboutEl) return;
    aboutEl.remove();
    aboutEl = null;
    document.removeEventListener('keydown', aboutKeydown);
}

function aboutKeydown(e) {
    if (e.key === 'Escape') closeAbout();
}

/** Rows are built with textContent, never innerHTML: every value here is server data. */
function row(dl, term, value) {
    if (value === null || value === undefined || value === '') return;
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = String(value);
    dl.append(dt, dd);
}

function formatUptime(seconds) {
    const d = Math.floor(seconds / 86400);
    const h = Math.floor((seconds % 86400) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    if (d) return d + 'd ' + h + 'h ' + m + 'm';
    if (h) return h + 'h ' + m + 'm';
    return m + 'm';
}

async function openAbout() {
    closeAbout();
    aboutEl = document.createElement('div');
    aboutEl.className = 'about-backdrop';
    aboutEl.innerHTML =
        '<div class="about-dialog" role="dialog" aria-modal="true" aria-labelledby="about-title">' +
          '<header class="about-head">' +
            '<h2 id="about-title"></h2>' +
            '<button type="button" class="icon-btn about-close" aria-label="Close">' +
              '<svg class="icon"><use href="#icon-close"/></svg>' +
            '</button>' +
          '</header>' +
          '<div class="about-body"><p class="about-loading">Loading…</p></div>' +
        '</div>';
    document.body.appendChild(aboutEl);
    aboutEl.querySelector('#about-title').textContent = 'About ' + appName();
    aboutEl.querySelector('.about-close').addEventListener('click', closeAbout);
    aboutEl.addEventListener('click', (e) => { if (e.target === aboutEl) closeAbout(); });
    document.addEventListener('keydown', aboutKeydown);
    aboutEl.querySelector('.about-close').focus();

    const body = aboutEl.querySelector('.about-body');
    let data;
    try {
        const res = await fetch('/api/about', { headers: headers() });
        if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
        data = await res.json();
    } catch (err) {
        body.textContent = 'Could not load version information: ' + err.message;
        return;
    }
    if (!aboutEl) return; // closed while the request was in flight

    body.textContent = '';

    const version = document.createElement('p');
    version.className = 'about-version';
    version.textContent = 'Version ' + (data.version || 'unknown');
    body.appendChild(version);

    if (data.buildTime) {
        const built = document.createElement('p');
        built.className = 'about-built';
        built.textContent = 'Built ' + new Date(data.buildTime).toLocaleString();
        body.appendChild(built);
    }

    if (data.server) {
        const h = document.createElement('h3');
        h.textContent = 'Server';
        const dl = document.createElement('dl');
        dl.className = 'about-dl';
        row(dl, 'Java', data.server.javaVersion + ' (' + data.server.javaVendor + ')');
        row(dl, 'JVM', data.server.jvm);
        row(dl, 'OS', data.server.os + ' / ' + data.server.arch);
        row(dl, 'CPUs', data.server.availableProcessors);
        row(dl, 'Max heap', data.server.maxHeapMb + ' MB');
        row(dl, 'Uptime', formatUptime(data.server.uptimeSeconds));
        row(dl, 'Time zone', data.server.timeZone);
        body.append(h, dl);
    }

    if (data.components && data.components.length) {
        const h = document.createElement('h3');
        h.textContent = 'Components';
        const dl = document.createElement('dl');
        dl.className = 'about-dl';
        for (const c of data.components) row(dl, c.name, c.version || 'unknown');
        body.append(h, dl);
    }

    if (data.license) {
        const h = document.createElement('h3');
        h.textContent = 'Licence';
        const lic = document.createElement('p');
        lic.className = 'about-licence';
        // Built from nodes, not a template string: the anchor is the only markup here and the
        // surrounding text stays inert.
        lic.append(document.createTextNode((data.copyright ? data.copyright + '. ' : '') + 'Released under the '));
        if (data.licenseUrl) {
            const a = document.createElement('a');
            a.href = data.licenseUrl;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = data.license;
            lic.appendChild(a);
        } else {
            lic.append(document.createTextNode(data.license));
        }
        lic.append(document.createTextNode('. Bundled third-party components keep their own terms; '
            + 'see THIRD-PARTY-NOTICES.md in the source distribution.'));
        body.append(h, lic);
    }

    if (!data.server) {
        // Say why the detail is missing rather than leaving a suspiciously short dialog.
        const note = document.createElement('p');
        note.className = 'about-note';
        note.textContent = 'Server and component details are shown to workspace administrators.';
        body.appendChild(note);
    }
}
