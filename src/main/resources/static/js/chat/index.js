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

/*
 * Entry point for the channels page. Loaded as an ES module via
 * `<script type="module" src="/js/chat/index.js" defer>` from channels.html.
 *
 * Currently a near-verbatim port of the previous IIFE-wrapped chat.js — the only
 * structural change is that the boot-time utilities (meta, headers, csrfToken,
 * activeChannelId) live in ./shared.js so future splits can pick them up via import.
 * Subsequent commits will carve this file into chat/realtime.js, chat/interactions.js,
 * chat/browse.js, chat/chrome.js per the modularization plan.
 */
import { meta, csrfToken, csrfHeader, activeChannelId, headers } from './shared.js';
import * as chrome from './chrome.js';
import { initSearchBox } from './search-box.js';
import { openPollModal } from './poll-modal.js';
import * as presenceMenu from './presence-menu.js';

chrome.init();
presenceMenu.init();

// ---------- Channel CRUD ----------
  const wireCreateChannel = (formId) => {
    const form = document.getElementById(formId);
    if (!form) return;
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const data = Object.fromEntries(new FormData(form).entries());
      const res = await fetch('/api/channels', {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify(data),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Could not create channel: ' + err.error);
        return;
      }
      const channel = await res.json();
      window.location.href = '/channels/' + channel.id;
    });
  };
  wireCreateChannel('create-channel-form');
  wireCreateChannel('create-channel-form-sidebar');

  // Sidebar "+" opens the create-channel form as a popover anchored to the button. Bound here
  // rather than inline because the CSP forbids inline handlers (script-src 'self').
  // wirePopover lives in chat-kit.js — both pages need it. The "New message" popover beside the
  // Direct messages header wires itself there too, for the same reason.
  window.ChatKit.wirePopover('sidebar-create-add-btn', 'sidebar-create-popover',
      'input[name="name"]');

  // ---------- Enter-to-send (Slack/Mattermost-style) ----------
  // Wired at document level so it survives any failure in the larger composer-setup
  // block below. Plain Enter submits the *closest* form, Shift+Enter (or any modifier)
  // falls through to the textarea's default newline. IME-composition is honored so
  // CJK input doesn't accidentally fire send.
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.isComposing || e.keyCode === 229) return;
    const ta = e.target;
    if (!(ta instanceof HTMLTextAreaElement)) return;
    if (ta.id !== 'composer-input' && ta.id !== 'thread-input') return;
    const form = ta.closest('form');
    if (!form) return;
    e.preventDefault();
    form.requestSubmit();
  });

  // ---------- Mobile sidebar toggle ----------
  // Hamburger flips body.sidebar-open; CSS handles the slide-in + backdrop visibility.
  // Auto-closes on channel pick, Escape, backdrop tap, or resize past the breakpoint.
  (function () {
    const toggle = document.getElementById('sidebar-toggle');
    const backdrop = document.getElementById('sidebar-backdrop');
    if (!toggle) return;
    const setOpen = (open) => {
      document.body.classList.toggle('sidebar-open', open);
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (backdrop) backdrop.hidden = !open;
    };
    toggle.addEventListener('click', () => {
      setOpen(!document.body.classList.contains('sidebar-open'));
    });
    backdrop?.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
    // Any channel list, not just the main one — there is a Favourites group above it now. The
    // closest('a') test is what keeps the star button from counting as "picked a channel".
    document.getElementById('app-sidebar')?.addEventListener('click', (e) => {
      if (e.target.closest('.channel-list a, .dm-list a')) setOpen(false);
    });
    window.addEventListener('resize', () => {
      if (window.innerWidth > 768 && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
  })();

  // ---------- Join public channel ----------
  const joinBtn = document.getElementById('join-channel-btn');
  if (joinBtn) {
    joinBtn.addEventListener('click', async () => {
      const id = joinBtn.dataset.channelId;
      const res = await fetch(`/api/channels/${id}/join`, { method: 'POST', headers: headers() });
      if (res.ok) window.location.reload();
      else alert('Could not join channel');
    });
  }

  // ---------- Channel admin dropdown ----------
  const adminCog = document.getElementById('channel-admin-cog');
  const adminDropdown = document.getElementById('channel-admin-dropdown');
  const adminClose = document.getElementById('channel-admin-close');
  if (adminCog && adminDropdown) {
    const isOpen = () => !adminDropdown.hidden;
    const open = () => {
      adminDropdown.hidden = false;
      adminCog.setAttribute('aria-expanded', 'true');
      adminDropdown.querySelector('input[name="username"]')?.focus();
    };
    const close = () => {
      adminDropdown.hidden = true;
      adminCog.setAttribute('aria-expanded', 'false');
    };
    adminCog.addEventListener('click', (e) => {
      e.stopPropagation();
      isOpen() ? close() : open();
    });
    adminClose?.addEventListener('click', close);
    document.addEventListener('click', (e) => {
      if (!isOpen()) return;
      if (adminDropdown.contains(e.target) || adminCog.contains(e.target)) return;
      close();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isOpen()) close();
    });
  }

  // ---------- Channel members panel ----------
  // Visible to anyone who can read the channel (PUBLIC: any auth'd user, PRIVATE: members
  // only — the server enforces). Lazily fetches on first open and caches the count badge.
  const membersToggle = document.getElementById('channel-members-toggle');
  const membersPanel = document.getElementById('channel-members-panel');
  const membersClose = document.getElementById('channel-members-close');
  const membersList = document.getElementById('channel-members-list');
  const membersCountEl = document.getElementById('channel-members-count');

  if (membersToggle && membersPanel && activeChannelId) {
    let membersLoaded = false;
    // chat.js's main destructure of ChatKit happens far below (line ~841) — pull
    // buildAvatarEl in locally so this early panel doesn't ReferenceError on first paint.
    const buildMemberAvatar = (window.ChatKit && window.ChatKit.buildAvatarEl)
        || ((opts) => {
          // Bare-minimum fallback so we still render *something* if ChatKit isn't loaded
          // yet (shouldn't happen — chat-kit.js is in the script chain ahead of chat.js).
          const span = document.createElement('span');
          span.className = 'avatar';
          if (opts.username) span.dataset.author = opts.username;
          const letter = document.createElement('span');
          letter.className = 'avatar-letter';
          letter.textContent = opts.letter || '?';
          span.appendChild(letter);
          return span;
        });

    const myUsername = meta('me-username');

    const renderMembers = (members) => {
      membersCountEl.textContent = String(members.length);
      membersList.innerHTML = '';
      if (!members.length) {
        const empty = document.createElement('li');
        empty.className = 'dm-empty';
        empty.textContent = 'No members yet.';
        membersList.appendChild(empty);
        return;
      }
      // The viewer's promote/demote affordance only shows when they're an admin of THIS
      // channel. Server still gates the actual mutation, but rendering the buttons
      // unconditionally would clutter the panel for plain members.
      const viewerIsAdmin = members.some(
          (m) => m.username === myUsername && m.role === 'ADMIN');
      for (const m of members) {
        const li = document.createElement('li');
        const name = m.displayName || m.username;
        const av = buildMemberAvatar({
          username: m.username,
          letter: (name || '?').slice(0, 1).toUpperCase(),
          hasAvatar: m.hasAvatar,
          avatarVersion: m.avatarVersion,
        });
        const label = document.createElement('span');
        label.className = 'member-name';
        label.textContent = name;
        const handle = document.createElement('small');
        handle.className = 'member-handle';
        handle.textContent = '@' + m.username;
        // Badges and the role control share the last grid track, so they go in one wrapper
        // rather than each claiming a column — that is what keeps the name and handle columns
        // in the same place whether or not a given member has any badges.
        const meta = document.createElement('span');
        meta.className = 'member-meta';
        li.append(av, label, handle, meta);
        if (m.role === 'ADMIN') {
          const role = document.createElement('small');
          role.className = 'channel-role-tag';
          role.title = 'Channel administrator';
          role.textContent = 'channel admin';
          meta.appendChild(role);
        }
        if (m.admin) {
          const ws = document.createElement('small');
          ws.className = 'dm-admin-tag';
          ws.title = 'Workspace administrator';
          ws.textContent = 'admin';
          meta.appendChild(ws);
        }
        // Promote/demote toggle. Only the channel-admin viewer sees it, never on their
        // own row (no self-demote — also blocks the "last admin" footgun before it can
        // even reach the server, which itself refuses the demote).
        if (viewerIsAdmin && m.username !== myUsername) {
          const toggle = document.createElement('button');
          toggle.type = 'button';
          toggle.className = 'channel-role-toggle';
          const targetRole = m.role === 'ADMIN' ? 'MEMBER' : 'ADMIN';
          toggle.textContent = targetRole === 'ADMIN' ? 'Make admin' : 'Demote';
          toggle.title = targetRole === 'ADMIN'
              ? 'Promote to channel admin'
              : 'Demote to plain member';
          toggle.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            toggle.disabled = true;
            try {
              const res = await fetch('/api/channels/' + activeChannelId
                  + '/members/' + encodeURIComponent(m.username) + '/role', {
                method: 'PUT',
                headers: headers(),
                body: JSON.stringify({ role: targetRole }),
              });
              if (!res.ok && res.status !== 204) {
                const err = await res.json().catch(() => ({}));
                alert('Role change failed: ' + (err.message || res.statusText));
                toggle.disabled = false;
                return;
              }
              // Reload to reflect the new state — also re-derives viewerIsAdmin.
              await loadMembers();
            } catch (e) {
              alert('Role change failed: ' + e.message);
              toggle.disabled = false;
            }
          });
          meta.appendChild(toggle);

          // The kick. Admin-only and never on their own row — leaving is the settings panel's
          // "Leave channel", which knows to warn about a private channel; a Remove button on your
          // own row would be the same action with none of the warning.
          const remove = document.createElement('button');
          remove.type = 'button';
          remove.className = 'channel-member-remove';
          remove.textContent = 'Remove';
          remove.title = 'Remove ' + name + ' from this channel';
          remove.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            // Two-step in place, for the same reason the leave control is: it is destructive, and a
            // native confirm() looks like a browser error. The button becoming "Remove?" is the
            // confirmation.
            if (remove.dataset.armed !== 'true') {
              remove.dataset.armed = 'true';
              remove.textContent = 'Remove?';
              setTimeout(() => {
                if (!remove.isConnected) return;
                remove.dataset.armed = 'false';
                remove.textContent = 'Remove';
              }, 4000);
              return;
            }
            remove.disabled = true;
            try {
              const res = await fetch('/api/channels/' + activeChannelId
                  + '/members/' + encodeURIComponent(m.username), {
                method: 'DELETE',
                headers: headers(),
              });
              if (!res.ok && res.status !== 204) {
                const err = await res.json().catch(() => ({}));
                throw new Error(err.message || err.error || res.statusText);
              }
              await loadMembers();
            } catch (e) {
              alert('Could not remove that member: ' + e.message);
              remove.disabled = false;
            }
          });
          meta.appendChild(remove);
        }
        membersList.appendChild(li);
      }
    };

    const loadMembers = async () => {
      try {
        const res = await fetch('/api/channels/' + activeChannelId + '/members',
            { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
        if (!res.ok) throw new Error('members load failed: ' + res.status);
        renderMembers(await res.json());
        membersLoaded = true;
      } catch (e) {
        membersList.innerHTML = '<li class="dm-empty">Could not load members.</li>';
      }
    };

    const isMembersOpen = () => !membersPanel.hidden;
    const setMembersOpen = (open) => {
      if (open && !membersLoaded) loadMembers();
      membersPanel.hidden = !open;
      membersToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    };
    membersToggle.addEventListener('click', (e) => {
      e.stopPropagation();
      setMembersOpen(!isMembersOpen());
    });
    membersClose?.addEventListener('click', () => setMembersOpen(false));
    document.addEventListener('click', (e) => {
      if (!isMembersOpen()) return;
      if (membersPanel.contains(e.target) || membersToggle.contains(e.target)) return;
      setMembersOpen(false);
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isMembersOpen()) setMembersOpen(false);
    });

    // Eagerly populate the count badge so the button shows "👥 N" before opening.
    loadMembers();
  }

  // ---------- Leave channel ----------
  // Two-step: the trigger reveals a confirmation with the consequence spelled out, and only the
  // second button leaves. Destructive, and for a private channel irreversible from this side.
  //
  // No native confirm() — it is unstyleable, it reads as a browser error, and it is used elsewhere
  // in this file only because nothing better was wired up. This is inline in the panel that
  // launched it, which needs no modal machinery at all.
  (() => {
    const trigger = document.getElementById('channel-leave-btn');
    const panel = document.getElementById('channel-leave-confirm');
    const cancel = document.getElementById('channel-leave-cancel');
    const go = document.getElementById('channel-leave-go');
    if (!trigger || !panel || !go) return;

    trigger.addEventListener('click', () => {
      panel.hidden = false;
      trigger.hidden = true;
      go.focus();
    });
    cancel?.addEventListener('click', () => {
      panel.hidden = true;
      trigger.hidden = false;
      trigger.focus();
    });

    go.addEventListener('click', async () => {
      const channelId = trigger.dataset.channelId;
      go.disabled = true;
      go.textContent = 'Leaving…';
      try {
        const res = await fetch('/api/channels/' + channelId + '/leave', {
          method: 'POST', headers: headers(),
        });
        if (!res.ok && res.status !== 204) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.message || err.error || res.statusText);
        }
        // Stop listening before navigating. The server revokes the subscription too — it has to,
        // because a client that never asked could otherwise keep receiving a private channel — but
        // the client must not be relying on that to stop bumping a badge for a channel it left.
        dropChannelSubscription(channelId);
        sidebarChannels.get(String(channelId))?.li.remove();
        // The channel page is no longer ours to be on: a public one would render as a join screen,
        // a private one as "ask an admin". Go somewhere that still makes sense.
        window.location.href = '/channels';
      } catch (e) {
        go.disabled = false;
        go.textContent = 'Leave channel';
        chrome.flashToast('Could not leave: ' + e.message);
      }
    });
  })();

  // ---------- Invite (admin) ----------
  const inviteForm = document.getElementById('invite-form');
  if (inviteForm) {
    inviteForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const id = inviteForm.dataset.channelId;
      const username = new FormData(inviteForm).get('username');
      const res = await fetch(`/api/channels/${id}/invite`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ username }),
      });
      if (res.ok) {
        inviteForm.reset();
        window.location.reload();
      } else {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Invite failed: ' + err.error);
      }
    });
  }

  // ---------- Poll builder ----------
  // The button fills the composer with the command and submits it, rather than posting by
  // itself: creation then travels the one path — slash dispatch, rate limit, broadcast — that
  // a typed /poll already travels, instead of a parallel one that can drift from it.
  document.getElementById('composer-poll')?.addEventListener('click', () => {
    openPollModal({
      onSubmit: async (command) => {
        const input = document.getElementById('composer-input');
        if (!input) throw new Error('Composer is not available here.');
        input.value = command;
        input._autoResize?.();
        composer?.requestSubmit ? composer.requestSubmit() : composer?.dispatchEvent(
            new Event('submit', { cancelable: true, bubbles: true }));
      },
    });
  });

  // ---------- Search (live dropdown) ----------
  // The box itself lives in chat/search-box.js — every page carries the same one, in the top bar.
  // There used to be a second call here for a "Search this channel…" field in the channel header;
  // the channel is now a scope on the one box rather than a box of its own, and search-box.js
  // reads it from the form's hidden fields.
  initSearchBox('global-search-input');

  // Composer/textarea helpers (auto-resize, caret insert, format toolbar, emoji picker)
  // come from window.ChatKit (chat-kit.js). Pull them into local scope for terseness.
  const { wireAutoResize, insertAtCursor, openEmojiPicker } = ChatKit;

  // ---------- Joined channels: the subscription set ----------
  // Every channel the user is a member of, straight from the server (meta me-channel-ids, built
  // from SidebarView.channelIds()). This — not the rendered sidebar — is what drives the STOMP
  // subscriptions below.
  //
  // KEEP IT THAT WAY. This used to be derived from `#sidebar-channel-list li.joined`, so
  // notification coverage was a side effect of what the sidebar happened to render: a mention in a
  // channel outside the rendered set produced no toast, no chime, no badge and no bell update until
  // the next page load. The sidebar now renders every joined channel, which fixes that by accident;
  // reading the set from the server means a future rendering change cannot un-fix it. If you find
  // yourself scraping channel ids out of the DOM again, this is the bug you are reintroducing.
  const joinedChannelIds = (meta('me-channel-ids') || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

  // channelId -> StompJS subscription for the non-active joined channels, populated on connect.
  // Module-scope rather than inside the STOMP block so the leave path can reach it: a client whose
  // membership just ended must stop listening immediately, not at the next reload. The server
  // revokes its side too (see ChannelSubscriptionRevoker) — that is what covers a client that never
  // asked — but the two are independent and each has to do its own half.
  const channelSubscriptions = new Map();

  const dropChannelSubscription = (channelId) => {
    const id = String(channelId);
    const sub = channelSubscriptions.get(id);
    if (!sub) return;
    channelSubscriptions.delete(id);
    try {
      sub.unsubscribe();
    } catch (_) {
      // Socket already gone; the subscription went with it.
    }
  };

  // ---------- Sidebar unread tracking ----------
  // Index the rendered channel rows by id so STOMP listeners can bump a badge live. This IS
  // DOM-derived, and that is correct: it is about painting a row, so a row that isn't on the page
  // has nothing to paint and bumpSidebarUnread no-ops. Subscriptions are a different question and
  // must not be answered from here.
  const sidebarChannels = new Map(); // channelId -> { li, a }
  document.querySelectorAll('.sidebar .channel-list li[data-channel-id]').forEach((li) => {
    const a = li.querySelector('a');
    if (a && li.dataset.channelId) sidebarChannels.set(li.dataset.channelId, { li, a });
  });
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const MUTED_TITLE = "Muted — unread still counts, it just doesn't interrupt";

  /**
   * Paint one sidebar row's unread state from the two counts on the row.
   *
   * <p>This must produce exactly what `fragments/channel-group.html` produces from
   * `ChannelSidebarDto.unreadCue` for the same numbers. Two renderers of the same decision is a
   * standing hazard — if they disagree, reloading the page changes what the user is looking at, and
   * whichever one is wrong is wrong only intermittently. The decision itself is documented on that
   * enum: ordinary unread is a bold name, a number is reserved for mentions, and a muted channel
   * keeps counting but stops shouting.
   *
   * <p>The counts live in data-unread / data-mentions rather than being read back out of the
   * badge's text. That worked while every unread channel had a badge; with ordinary unread rendered
   * as weight there is no number in the DOM to read.
   */
  const paintUnreadCue = (entry) => {
    const li = entry.li;
    const unread = Number(li.dataset.unread || 0);
    const mentions = Number(li.dataset.mentions || 0);
    const muted = notifyLevelFor(li.dataset.channelId) === 'NONE';
    const cue = mentions > 0 ? 'count' : (unread > 0 && !muted ? 'bold' : 'none');
    li.dataset.unreadCue = cue;
    li.dataset.muted = String(muted);
    // has-unread stays truthful — "there is unread here" — independently of how loud the row is.
    li.classList.toggle('has-unread', unread > 0);

    let marker = entry.a.querySelector('.channel-muted-marker');
    if (muted && !marker) {
      marker = document.createElementNS(SVG_NS, 'svg');
      marker.setAttribute('class', 'icon icon-sm channel-muted-marker');
      marker.setAttribute('title', MUTED_TITLE);
      const use = document.createElementNS(SVG_NS, 'use');
      use.setAttribute('href', '#icon-bell-slash');
      marker.appendChild(use);
      entry.a.appendChild(marker);
    } else if (!muted && marker) {
      marker.remove();
      marker = null;
    }

    let badge = entry.a.querySelector('.unread-badge');
    if (cue !== 'count') {
      badge?.remove();
      return;
    }
    if (!badge) {
      badge = document.createElement('span');
      // Before the muted marker, so the row reads the same as a server-rendered one.
      if (marker) entry.a.insertBefore(badge, marker);
      else entry.a.appendChild(badge);
    }
    badge.className = 'unread-badge mention' + (muted ? ' muted' : '');
    badge.textContent = mentions > 99 ? '99+' : String(mentions);
  };

  const bumpSidebarUnread = (channelId, isMention) => {
    const entry = sidebarChannels.get(String(channelId));
    if (!entry) return;
    entry.li.dataset.unread = String(Number(entry.li.dataset.unread || 0) + 1);
    if (isMention) {
      entry.li.dataset.mentions = String(Number(entry.li.dataset.mentions || 0) + 1);
    }
    paintUnreadCue(entry);
  };

  // The per-channel picker in the settings panel. Writes through to the account so the choice
  // follows the person between devices — unlike the sound settings, which are per browser.
  (() => {
    const picker = document.getElementById('channel-notify-level');
    if (!picker) return;
    const status = document.getElementById('channel-notify-status');
    const say = (text, bad) => {
      if (!status) return;
      status.textContent = text;
      status.hidden = !text;
      status.classList.toggle('is-error', !!bad);
    };
    picker.addEventListener('change', async () => {
      const previous = picker.dataset.current;
      picker.disabled = true;
      say('Saving…', false);
      try {
        const res = await fetch('/api/channels/' + picker.dataset.channelId + '/notify', {
          method: 'PUT', headers: headers(), body: JSON.stringify({ level: picker.value }),
        });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || 'Could not save that.');
        picker.dataset.current = picker.value;
        // The sidebar row is what the notification rule reads, so it has to move with the
        // setting or the change only takes effect after a reload. Repaint it too: muting is
        // visible on the row (dimmed, unbolded, marked), and a mute that only shows up after a
        // reload looks like it didn't save.
        const row = document.querySelector(`[data-channel-id="${picker.dataset.channelId}"]`);
        if (row) row.dataset.notifyLevel = picker.value;
        const entry = sidebarChannels.get(String(picker.dataset.channelId));
        if (entry) paintUnreadCue(entry);
        say('Saved.', false);
        setTimeout(() => say('', false), 2000);
      } catch (e) {
        // Put the control back where it was: a picker showing a value the server rejected is
        // worse than no feedback, because it looks applied.
        picker.value = previous;
        say(e.message, true);
      } finally {
        picker.disabled = false;
      }
    });
  })();

  // ============== Channel administration (rename · archive · unarchive) ==============
  // Everything between this banner and the matching END marker is the channel-settings block:
  // rename / re-describe, archive / unarchive, and the live repaint that any of it triggers in
  // another tab.
  //
  // applyChannelEvent is module scope because two STOMP subscriptions feed it — the active
  // channel's full handler and the one-per-joined-channel badge handler — and a second copy of
  // "what a channel-* frame means" is a second thing to keep in step.

  /**
   * Repaint whatever names the channel, from a /topic/channels/{id} channel-* frame.
   *
   * Every place a channel's name is on screen at once: the header, the description strip beside it,
   * the sidebar row (and the star's labels, which quote the name), the composer placeholder and the
   * leave button. Missing one leaves a page contradicting itself, which reads as a bug in whichever
   * half the user happens to trust.
   */
  const applyChannelEvent = (ev) => {
    if (!ev || !ev.id) return;
    const id = String(ev.id);
    if (ev.type === 'channel-updated') {
      const row = sidebarChannels.get(id);
      if (row) {
        row.a.querySelector('.channel-name')?.replaceChildren(ev.name);
        row.li.dataset.name = (ev.name || '').toLowerCase();
        const star = row.li.querySelector('.channel-star');
        if (star) {
          const on = star.getAttribute('aria-pressed') === 'true';
          star.setAttribute('aria-label',
              (on ? 'Remove #' : 'Add #') + ev.name + (on ? ' from favourites' : ' to favourites'));
        }
      }
      if (id !== String(activeChannelId)) return;
      document.getElementById('channel-name')?.replaceChildren(ev.name);
      const purpose = document.getElementById('channel-purpose');
      if (purpose) {
        purpose.textContent = ev.description || '';
        purpose.classList.toggle('is-empty', !ev.description);
      }
      document.querySelectorAll('.channel-name-echo')
          .forEach((el) => { el.textContent = '#' + ev.name; });
      const composerInput = document.getElementById('composer-input');
      if (composerInput) composerInput.placeholder = 'Write to #' + ev.name;
      // The form is the one place that must NOT be repainted while it is focused: the person typing
      // in it is the one who caused this frame, and overwriting their field mid-edit is worse than
      // showing them a value they just submitted.
      const nameField = document.getElementById('channel-rename-name');
      const descField = document.getElementById('channel-rename-description');
      if (nameField && document.activeElement !== nameField) nameField.value = ev.name || '';
      if (descField && document.activeElement !== descField) descField.value = ev.description || '';
      return;
    }
    if (ev.type === 'channel-archived' || ev.type === 'channel-unarchived') {
      // The sidebar row is removed on archive and NOT re-added on unarchive. Re-adding it means
      // reproducing the whole row — unread cue, muted marker, star, private lock — from a frame that
      // carries none of those, and a row that comes back subtly different from the server's is worse
      // than a row that comes back on the next load. Removal is safe because it needs no state.
      if (ev.archived) sidebarChannels.get(id)?.li.remove();
      if (id !== String(activeChannelId)) return;
      // The controls that exist only in one of the two states are server-rendered, so the page has
      // to reload to get the other set. Reloading is also what re-renders the composer, and the
      // person seeing this frame did not necessarily cause it.
      window.location.reload();
      return;
    }
    if (ev.type === 'channel-deleted') {
      sidebarChannels.get(id)?.li.remove();
      dropChannelSubscription(id);
      if (id !== String(activeChannelId)) return;
      // Told, not just moved. Somebody else destroyed the channel this person was reading, and
      // arriving at /channels with no explanation reads as the application losing their place.
      chrome.flashToast('This channel was deleted.');
      window.location.href = '/channels';
    }
  };

  // ---------- Rename / re-describe ----------
  (() => {
    const form = document.getElementById('channel-rename-form');
    if (!form) return;
    const save = document.getElementById('channel-rename-save');
    const status = document.getElementById('channel-rename-status');
    const say = (text, bad) => {
      if (!status) return;
      status.textContent = text;
      status.hidden = !text;
      status.classList.toggle('is-error', !!bad);
    };

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const data = Object.fromEntries(new FormData(form).entries());
      save.disabled = true;
      say('Saving…', false);
      try {
        const res = await fetch('/api/channels/' + form.dataset.channelId, {
          method: 'PATCH',
          headers: headers(),
          body: JSON.stringify({ name: data.name, description: data.description }),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          // 409 is the one refusal worth naming: the envelope's generic "Conflicting state" is
          // exactly wrong advice here, since refreshing will not free the other channel's name.
          throw new Error(res.status === 409
              ? 'Another channel already uses that name.'
              : (err.message || err.error || res.statusText));
        }
        const channel = await res.json();
        // Paint from the server's answer, not from the form: the slug it derived and any trimming
        // it did are what actually got stored.
        applyChannelEvent({ type: 'channel-updated', id: channel.id, name: channel.name,
          description: channel.description, slug: channel.slug });
        say('Saved.', false);
        setTimeout(() => say('', false), 2000);
      } catch (err) {
        say(err.message, true);
      } finally {
        save.disabled = false;
      }
    });
  })();

  // ---------- Archive / unarchive ----------
  // Archiving reveals a confirmation first, exactly as leaving does and for a stronger reason: this
  // one takes the channel away from everyone. Unarchiving is a single click — it is the undo, and a
  // confirmation in front of an undo is how a reversible action starts feeling irreversible.
  (() => {
    // Two separate hosts on purpose: the archive trigger is in the settings panel, the unarchive
    // button is on the banner beside the message list (see the template's note on why the undo
    // cannot live behind the cog). Either may be absent.
    const box = document.querySelector('.channel-archive');
    const unarchive = document.getElementById('channel-unarchive-btn');
    if (!box && !unarchive) return;
    const channelId = (box || unarchive).dataset.channelId;

    const call = async (path, button, busyLabel) => {
      const original = button.textContent;
      button.disabled = true;
      button.textContent = busyLabel;
      try {
        const res = await fetch('/api/channels/' + channelId + '/' + path, {
          method: 'POST', headers: headers(),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.message || err.error || res.statusText);
        }
        // Reload rather than repaint. Which controls exist, whether there is a composer and whether
        // the banner is showing are all server-rendered decisions, and reproducing that split here
        // would be a second renderer of the same state — the standing hazard this file already
        // warns about for the sidebar's unread cue.
        window.location.reload();
      } catch (e) {
        button.disabled = false;
        button.textContent = original;
        chrome.flashToast('Could not ' + path + ': ' + e.message);
      }
    };

    const trigger = document.getElementById('channel-archive-btn');
    const panel = document.getElementById('channel-archive-confirm');
    const cancel = document.getElementById('channel-archive-cancel');
    const go = document.getElementById('channel-archive-go');
    if (trigger && panel && go) {
      trigger.addEventListener('click', () => {
        panel.hidden = false;
        trigger.hidden = true;
        go.focus();
      });
      cancel?.addEventListener('click', () => {
        panel.hidden = true;
        trigger.hidden = false;
        trigger.focus();
      });
      go.addEventListener('click', () => call('archive', go, 'Archiving…'));
    }

    unarchive?.addEventListener('click', () => call('unarchive', unarchive, 'Unarchiving…'));
  })();

  // ---------- Delete forever ----------
  // The one action in this application with no undo, so it is the only one that asks the user to
  // type something. The Delete button stays disabled until the field matches the channel's name,
  // which puts the failure mode before the click instead of after it — an error message that
  // arrives after an irreversible action has already been attempted is not a safeguard.
  //
  // The server re-checks the name. This is a convenience for a human, not the enforcement.
  (() => {
    const box = document.querySelector('.channel-destroy');
    if (!box) return;
    const trigger = document.getElementById('channel-destroy-btn');
    const panel = document.getElementById('channel-destroy-confirm');
    const field = document.getElementById('channel-destroy-name');
    const cancel = document.getElementById('channel-destroy-cancel');
    const go = document.getElementById('channel-destroy-go');
    const status = document.getElementById('channel-destroy-status');
    if (!trigger || !panel || !field || !go) return;

    // Case-insensitive and trimmed, matching the server: the confirmation is a statement of intent,
    // not a typing test, and a name with a trailing space nobody can see would be a cruel way to
    // fail someone twice.
    const expected = (box.dataset.channelName || '').trim().toLowerCase();
    const matches = () => field.value.trim().toLowerCase() === expected;
    const sync = () => { go.disabled = !matches(); };

    trigger.addEventListener('click', () => {
      panel.hidden = false;
      trigger.hidden = true;
      field.focus();
    });
    cancel?.addEventListener('click', () => {
      panel.hidden = true;
      trigger.hidden = false;
      field.value = '';
      sync();
      trigger.focus();
    });
    field.addEventListener('input', sync);
    // Enter in the field is the same as clicking, but only once it matches — otherwise the key that
    // finishes typing the name would also fire the action.
    field.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && matches()) {
        e.preventDefault();
        go.click();
      }
    });

    go.addEventListener('click', async () => {
      if (!matches()) return;
      go.disabled = true;
      field.disabled = true;
      go.textContent = 'Deleting…';
      try {
        const res = await fetch('/api/channels/' + box.dataset.channelId
            + '?name=' + encodeURIComponent(field.value.trim()), {
          method: 'DELETE', headers: headers(),
        });
        if (!res.ok && res.status !== 204) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.message || err.error || res.statusText);
        }
        // Stop listening before navigating, as the leave path does. The server revokes its side too
        // — it has to, for the clients that never asked — but each half does its own.
        dropChannelSubscription(box.dataset.channelId);
        sidebarChannels.get(String(box.dataset.channelId))?.li.remove();
        window.location.href = '/channels';
      } catch (e) {
        go.disabled = false;
        field.disabled = false;
        go.textContent = 'Delete forever';
        if (status) {
          status.textContent = e.message;
          status.hidden = false;
        }
      }
    });
  })();
  // ===================== END channel administration =====================

  // ---------- Notification level ----------
  // Slack/Mattermost model: an account-wide default, and a per-channel override whose default
  // value is "inherit" rather than a copy. Resolving here rather than server-side keeps the
  // account default authoritative — change it once and every un-overridden channel follows,
  // which is the whole point of storing inherit instead of a snapshot.
  const accountNotifyDefault = () => meta('me-notify-default') || 'MENTIONS';

  const notifyLevelFor = (channelId) => {
    const li = sidebarChannels.get(String(channelId))?.li
        || document.querySelector(`[data-channel-id="${channelId}"]`);
    const raw = li?.dataset.notifyLevel || 'DEFAULT';
    return raw === 'DEFAULT' ? accountNotifyDefault() : raw;
  };

  /**
   * Should this incoming message interrupt the user?
   *
   * <p>NONE is a mute and silences even a mention: the user said "nothing from this channel", and
   * a mute with exceptions is not a mute. ALL notifies on any message. MENTIONS is the default
   * and is the behaviour every existing install already has.
   */
  const shouldNotify = (channelId, mentioned) => {
    const level = notifyLevelFor(channelId);
    if (level === 'NONE') return false;
    if (level === 'ALL') return true;
    return mentioned;
  };

  /** Am I one of the people in this thread, per the participant list on the broadcast? */
  const inThread = (message) =>
      !!(message?.threadParticipants || []).includes(myUsernameMeta);

  /**
   * Should a reply in a thread I am in interrupt me?
   *
   * <p>Mute wins, exactly as it does for an ordinary message: NONE means nothing from this channel,
   * and a thread is part of the channel. Above that, a reply in a thread I have written in notifies
   * at MENTIONS as well as at ALL — because being a participant is what makes it addressed to me,
   * in the same sense a mention is. Treating it as ordinary traffic instead would mean the default
   * level produces no signal for a threaded conversation, which is the exact failure being fixed
   * here: the reply would count toward unread and nothing would ever tell the people in the thread
   * to look. It stays out of the mention bell regardless — see maybeNotify.
   */
  const shouldNotifyThreadReply = (channelId) => notifyLevelFor(channelId) !== 'NONE';

  /**
   * Surface an incoming message via the shared notifications module.
   *
   * <p>{@code kind} is one of 'mention' (addressed to you by name), 'thread' (a reply in a thread
   * you are in) or 'channel' (ordinary traffic in a channel set to ALL). It decides the headline and
   * whether the mention bell hears about it, so it is passed in rather than guessed.
   *
   * <p>Reading the channel suppresses the toast, not the sound, for the two kinds that are about
   * you. The toast would point at a message already on screen — but "someone just called on you", or
   * "the thread you are in moved", is worth hearing even while the channel is open, and watching a
   * busy channel scroll is exactly the situation where either gets missed.
   */
  const maybeNotify = (message, channelId, isActiveChannel, kind = 'mention') => {
    if (!message) return;
    const mentioned = kind === 'mention';
    // The bell is a mention inbox, so only an actual mention belongs in it. A channel set to
    // ALL produces notifications for ordinary messages, and a thread reply produces one for its
    // participants; putting either in the bell would turn "things addressed to me" into
    // "everything", which is the one thing it is for.
    if (mentioned && window.MentionInbox) window.MentionInbox.notifyMention();
    if (!window.MentionNotifications) return;
    if (isActiveChannel && document.visibilityState === 'visible' && document.hasFocus()) {
      // An ordinary message in a channel set to ALL makes no sound: you are looking straight at it.
      if (kind !== 'channel') window.MentionNotifications.playChime('mention');
      return;
    }
    const entry = sidebarChannels.get(String(channelId));
    const channelName = entry?.a.querySelector('.channel-name')?.textContent || 'channel';
    const author = message.authorDisplayName || message.authorUsername || 'someone';
    const snippet = (message.bodyMarkdown || '').replace(/\s+/g, ' ').slice(0, 200);
    const anchorId = message.parentId || message.id;
    window.MentionNotifications.show({
      author,
      channel: channelName,
      kind: mentioned ? undefined : kind,
      snippet,
      // Match permalinkFor: ?m= makes the server render context around an older message
      // (it may be outside the latest page), #m= is what scrollToPermalinkTarget matches.
      // The old '#m-<id>' matched neither, landing the user at the tail with no highlight.
      //
      // A thread reply anchors on its PARENT. The server's context-around refuses a thread-reply
      // anchor (messageService.around throws for one, and HomeController falls back to the latest
      // 50), so linking to the reply's own id would drop the user at the tail of the channel with
      // nothing highlighted — the parent puts them at the thread, with its "N replies" indicator.
      url: '/channels/' + channelId + '?m=' + encodeURIComponent(anchorId) + '#m=' + anchorId,
    });
  };

  // ---------- WebSocket / STOMP ----------
  const messagesEl = document.getElementById('messages');
  const composer = document.getElementById('composer');

  if (activeChannelId && messagesEl) {
    // Use native WebSocket. SockJS's iframe / htmlfile / jsonp-polling fallback transports
    // inject inline <script> tags and break our strict CSP (script-src 'self'). Modern browsers
    // all support WebSocket directly.
    const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
    const stomp = new StompJs.Client({
      brokerURL: wsUrl,
      reconnectDelay: 4000,
    });

    const myUsername = meta('me-username');
    let stompConnectedBefore = false;
    let backfilling = false;
    const pendingLive = [];

    // Page the ?after= endpoint until caught up. The server caps each page at 50, so the old
    // single limit=200 request silently dropped every message past the first 50 (a permanent
    // gap if the outage was busy). Live events that arrive mid-backfill are buffered so they
    // can't be appended ahead of the older rows still loading; they replay in arrival order,
    // and appendMessage de-dupes by id so overlap is harmless (N5).
    async function backfillMissedMessages() {
      backfilling = true;
      try {
        for (let page = 0; page < 50; page++) { // safety cap: 50 pages * 50 = 2500 messages
          const last = lastMessageEl();
          const after = last ? last.dataset.createdAt : null;
          if (!after) break;
          const rows = await fetch('/api/channels/' + activeChannelId + '/messages?after='
                + encodeURIComponent(after) + '&limit=50', { headers: headers() })
            .then((r) => (r.ok ? r.json() : []))
            .catch(() => []);
          if (!rows || rows.length === 0) break;
          rows.forEach(appendMessage);
          if (rows.length < 50) break; // short page => we've caught up
        }
      } finally {
        backfilling = false;
        pendingLive.splice(0).forEach(handleMessageEvent);
      }
    }

    stomp.onConnect = () => {
      // On a RECONNECT (not the first connect), the simple broker replayed nothing, so backfill
      // everything missed during the outage. Only while live-tailing — an anchored/historical
      // view backfills when the user jumps to latest.
      if (stompConnectedBefore && activeChannelId && infiniteScrollDownDone) {
        backfillMissedMessages();
      }
      stompConnectedBefore = true;
      stomp.subscribe('/topic/channels/' + activeChannelId, (frame) => {
        const event = JSON.parse(frame.body);
        if (backfilling) { pendingLive.push(event); return; } // ordered replay after backfill
        handleMessageEvent(event);
      });
      stomp.subscribe('/topic/channels/' + activeChannelId + '/typing', (frame) => {
        const t = JSON.parse(frame.body);
        if (t.username && t.username !== myUsername) {
          noteTyping(t.username, t.displayName || t.username);
        }
      });
      // One subscription per joined channel, so a message anywhere the user is a member reaches
      // them — badge, chime, toast, bell — without a page load. Driven by joinedChannelIds (the
      // server's membership list), never by what the sidebar rendered.
      //
      // Cost, since this is N frames on connect: every SUBSCRIBE is authorised by
      // StompAuthorizationConfig, which resolves the channel through ChannelAccessCache (a map hit
      // after the first user of that channel) and then calls requireMember — free for a PUBLIC
      // channel, one cached membership check for a PRIVATE one. The per-session SUBSCRIBE rate cap
      // was raised to match, because at 200 channels the old 200/min silently dropped the tail.
      // A reconnect re-runs this block; the previous handles died with the socket.
      channelSubscriptions.clear();
      joinedChannelIds.forEach((id) => {
        if (id === activeChannelId) return; // already subscribed above, with the full handler
        channelSubscriptions.set(id, stomp.subscribe('/topic/channels/' + id, (frame) => {
          const ev = JSON.parse(frame.body);
          // A channel renamed elsewhere has a sidebar row here that must move with it, even though
          // this handler otherwise exists only to count unread.
          if (ev.type && ev.type.startsWith('channel-')) { applyChannelEvent(ev); return; }
          if (ev.type !== 'created') return;
          if (ev.message?.authorUsername === myUsername) return;
          const mentioned = !!(ev.message?.mentions || []).includes(myUsername);
          // Thread replies used to be dropped here (`|| ev.parentId` on the guard above), so a
          // reply in another channel produced nothing at all. They count as ordinary unread — a
          // reply is a message in the channel — and the server's unread query now agrees.
          //
          // The badge is not the notification: an unread count still reflects reality in a muted
          // channel, it just does not interrupt. Muting means "stop telling me", not "pretend
          // nothing happened".
          bumpSidebarUnread(id, mentioned);
          if (mentioned) {
            if (shouldNotify(id, true)) maybeNotify(ev.message, id, false, 'mention');
          } else if (ev.parentId) {
            // Only the people in the thread; everyone else gets the unread cue and nothing more.
            if (inThread(ev.message) && shouldNotifyThreadReply(id)) {
              maybeNotify(ev.message, id, false, 'thread');
            }
          } else if (shouldNotify(id, false)) {
            maybeNotify(ev.message, id, false, 'channel');
          }
        }));
      });
      stomp.subscribe('/topic/users', (frame) => {
        const ev = JSON.parse(frame.body);
        if (!ev || !ev.username) return;
        if (ev.type === 'avatar-updated') refreshAvatarsFor(ev.username, ev.avatarVersion);
        else if (ev.type === 'avatar-removed') refreshAvatarsFor(ev.username, 0);
      });
      // Per-user notices: slash-command usage errors and similar private feedback. The
      // server publishes via convertAndSendToUser → /user/queue/notices.
      stomp.subscribe('/user/queue/notices', (frame) => {
        try {
          const n = JSON.parse(frame.body);
          showSlashNotice(n.text || '', n.level || 'info');
          // An unknown slash command is answered privately rather than posted to the channel, so
          // the text the user typed is not on screen anywhere — the composer was cleared on submit.
          // The server sends it back on the notice as `body`; put it back where they typed it, with
          // the caret at the end, so fixing a typo is an edit rather than retyping the line.
          // Only when the composer is empty: they may have started something new in the meantime,
          // and overwriting that would be a worse loss than the one this repairs.
          if (n.body) {
            const input = document.getElementById('composer-input');
            if (input && !input.value) {
              input.value = n.body;
              input._autoResize?.();
              input.focus();
              input.setSelectionRange(input.value.length, input.value.length);
            }
          }
        } catch (e) { /* ignore malformed */ }
      });
      // Direct messages and group messages. On this page the user is never looking at the
      // conversation the alert is about, so there is no "are they already reading it" case to
      // suppress — that check lives on the conversation page, which can be showing it.
      stomp.subscribe('/user/queue/conversation-alerts', (frame) => {
        try {
          const a = JSON.parse(frame.body);
          if (!window.MentionNotifications) return;
          window.MentionNotifications.show({
            author: a.author,
            channel: a.title,
            kind: a.type === 'DIRECT' ? 'direct' : 'group',
            snippet: a.preview,
            url: '/conversations/' + a.conversationId,
          });
        } catch (e) { /* ignore malformed */ }
      });
      if (window.Presence) window.Presence.attachStomp(stomp);
    };

    // Catch-up read when the tab returns to the foreground: while backgrounded we deliberately
    // don't mark live traffic read (see handleMessageEvent), so on refocus advance the marker
    // once for the channel the user is actually looking at now.
    const catchUpRead = () => {
      if (!activeChannelId) return;
      if (document.visibilityState !== 'visible' || !document.hasFocus()) return;
      fetch('/api/channels/' + activeChannelId + '/read', { method: 'POST', headers: headers() })
        .then(() => { if (window.MentionInbox) window.MentionInbox.refresh(); })
        .catch(() => {});
    };
    document.addEventListener('visibilitychange', catchUpRead);
    window.addEventListener('focus', catchUpRead);

    /**
     * Drop a transient banner above the composer for a few seconds. Reuses the
     * #composer-notice element if it's there, otherwise injects one. Errors get a
     * red border; info uses neutral styling.
     */
    const showSlashNotice = (text, level) => {
      const composerEl = document.getElementById('composer');
      if (!composerEl || !text) return;
      let notice = document.getElementById('composer-notice');
      if (!notice) {
        notice = document.createElement('div');
        notice.id = 'composer-notice';
        notice.className = 'composer-notice';
        composerEl.parentNode.insertBefore(notice, composerEl);
      }
      notice.classList.toggle('error', level === 'error');
      notice.textContent = text;
      notice.hidden = false;
      clearTimeout(notice._hideTimer);
      notice._hideTimer = setTimeout(() => {
        notice.hidden = true;
      }, 6000);
    };

    /**
     * Swap every on-screen avatar for {@code username} to the latest version. {@code version === 0}
     * means the user cleared their picture — drop the {@code <img>} so the fallback initial shows.
     */
    const refreshAvatarsFor = (username, version) => {
      const sel = '.avatar[data-author="' + (window.CSS && CSS.escape ? CSS.escape(username) : username) + '"]';
      document.querySelectorAll(sel).forEach((el) => {
        const existing = el.querySelector('img.avatar-image');
        if (!version) {
          if (existing) existing.remove();
          return;
        }
        const url = '/api/users/' + encodeURIComponent(username) + '/avatar?v=' + version;
        if (existing) {
          existing.src = url;
        } else {
          const img = document.createElement('img');
          img.className = 'avatar-image';
          img.alt = '';
          img.src = url;
          img.addEventListener('error', () => img.remove());
          el.insertBefore(img, el.firstChild);
        }
      });
    };

    // ---- Optimistic send ------------------------------------------------------------------
    // The server broadcasts a message only after its row commits, so there is a short window
    // between hitting enter and seeing it. Rather than make the sender wait on it, draw the
    // bubble immediately in a "sending" state and reconcile when the broadcast arrives. The
    // placeholder carries a synthetic id ("pending:<clientId>") so it goes through exactly the
    // same render path as a real message and can't drift from it visually.
    const PENDING_TIMEOUT_MS = 12000;
    const pendingSends = new Map(); // clientId -> timeout handle

    const newClientId = () => (crypto.randomUUID
      ? crypto.randomUUID()
      : 'c' + Date.now() + Math.random().toString(36).slice(2));

    const pendingIdFor = (clientId) => 'pending:' + clientId;

    const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    const markPendingSend = (clientId, body) => {
      appendMessage({
        id: pendingIdFor(clientId),
        channelId: activeChannelId,
        authorUsername: myUsername,
        authorDisplayName: meta('me-display') || myUsername,
        authorHasAvatar: meta('me-has-avatar') === 'true',
        authorAvatarVersion: Number(meta('me-avatar-version') || 0),
        bodyMarkdown: body,
        // Plain-text stand-in: the real Markdown render happens server-side and replaces this
        // the moment the broadcast lands. Escaped, because it goes in via innerHTML.
        bodyHtml: '<p>' + escapeHtml(body) + '</p>',
        createdAt: new Date().toISOString(),
        attachments: [], reactions: [], mentions: [], replyCount: 0, poll: null,
      });
      const li = findMessageEl(pendingIdFor(clientId));
      if (li) li.classList.add('sending');
      pendingSends.set(clientId, setTimeout(() => failPendingSend(clientId), PENDING_TIMEOUT_MS));
    };

    // Never confirmed. Leave the text on screen — losing what someone typed is worse than
    // showing it as failed — mark it, and offer a way out that isn't "type it again".
    const failPendingSend = (clientId) => {
      clearTimeout(pendingSends.get(clientId));
      pendingSends.delete(clientId);
      const li = findMessageEl(pendingIdFor(clientId));
      if (!li || li.querySelector('.message-retry')) return;
      li.classList.remove('sending');
      li.classList.add('send-failed');

      const retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'message-retry';
      retry.textContent = 'Retry';
      // Named for assistive tech, which doesn't get the surrounding "not delivered" cue.
      retry.setAttribute('aria-label', 'Resend this message');
      retry.addEventListener('click', () => {
        // Fresh clientId: the old one is spent, and if the original send is still in flight
        // somewhere its late arrival must not retire the new bubble.
        const body = li.dataset.bodyMarkdown || '';
        li.remove();
        if (body) sendChannelMessage(body);
      });
      li.querySelector('.message-body')?.append(retry);
    };

    const resolvePendingSend = (clientId) => {
      if (!clientId || !pendingSends.has(clientId)) return;
      clearTimeout(pendingSends.get(clientId));
      pendingSends.delete(clientId);
      removeMessageDom(pendingIdFor(clientId));
    };

    const handleMessageEvent = (event) => {
      if (!event || !event.type) return;
      // ChannelEvent shares this destination with MessageEvent and is told apart by the prefix.
      // Handled first and returned on, so nothing below ever has to guard against a frame with no
      // `message` on it — see the note on the ChannelEvent record.
      if (event.type.startsWith('channel-')) { applyChannelEvent(event); return; }
      if (event.type === 'created') {
        // Retire our placeholder before the real one is appended, so the two never coexist.
        resolvePendingSend(event.clientId);
        if (event.parentId) {
          appendThreadReply(event.message);
          bumpThreadIndicator(event.parentId, +1);
          // A reply counts toward the channel's unread now, so the read marker has to move for the
          // channel the viewer is looking at — otherwise navigating away leaves a phantom badge for
          // messages they watched arrive. Same foreground-only rule as a top-level message: a tab
          // left open in the background must not silently mark traffic read.
          if (event.message?.authorUsername !== myUsername) {
            if (document.visibilityState === 'visible' && document.hasFocus()) {
              fetch('/api/channels/' + activeChannelId + '/read', {
                method: 'POST', headers: headers(),
              })
                .then(() => { if (window.MentionInbox) window.MentionInbox.refresh(); })
                .catch(() => {});
            }
            const mentioned = !!(event.message?.mentions || []).includes(myUsername);
            if (mentioned) {
              if (shouldNotify(activeChannelId, true)) {
                maybeNotify(event.message, activeChannelId, true, 'mention');
              }
            } else if (inThread(event.message) && shouldNotifyThreadReply(activeChannelId)) {
              // The chime fires even with the channel open (maybeNotify suppresses only the toast),
              // which is right: the thread panel may be closed, or open on a different thread, and
              // the reply is not on screen either way.
              maybeNotify(event.message, activeChannelId, true, 'thread');
            }
          }
        } else {
          // If the viewer is reading context-around an old anchor and hasn't paged forward
          // to the live tail, skipping the live-append keeps the loaded batch chronologically
          // contiguous. The Jump-to-latest banner is the user's path back to current traffic;
          // once they reach it (or click it), infiniteScrollDownDone flips and live appends
          // resume normally.
          if (!infiniteScrollDownDone) return;
          appendMessage(event.message);
          // Active channel is being read live — advance the read marker so navigating away
          // doesn't leave these messages counted as unread on next page load. Only when the
          // tab is actually in the foreground: a channel left open in a background tab must NOT
          // silently mark incoming traffic read (that would wipe sidebar badges, the bell, and
          // unseen mention rows the user never looked at). The catch-up read fires on refocus.
          if (event.message?.authorUsername !== myUsername) {
            if (document.visibilityState === 'visible' && document.hasFocus()) {
              fetch('/api/channels/' + activeChannelId + '/read', {
                method: 'POST', headers: headers(),
              })
                .then(() => { if (window.MentionInbox) window.MentionInbox.refresh(); })
                .catch(() => {});
            }
            const mentioned = !!(event.message?.mentions || []).includes(myUsername);
            if (shouldNotify(activeChannelId, mentioned)) {
              maybeNotify(event.message, activeChannelId, /* isActiveChannel */ true,
                  mentioned ? 'mention' : 'channel');
            }
          }
        }
      } else if (event.type === 'updated') {
        replaceMessageDom(event.message);
      } else if (event.type === 'deleted') {
        removeMessageDom(event.id);
        if (event.parentId) bumpThreadIndicator(event.parentId, -1);
      } else if (event.type === 'poll-vote') {
        applyPollUpdate(event.id, event.poll);
      }
    };

    stomp.activate();

    // Typing indicator state: username -> { displayName, expiresAt }.
    const typingUsers = new Map();
    const typingEl = document.getElementById('typing-indicator');
    let typingSweep = null;

    const noteTyping = (username, displayName) => {
      typingUsers.set(username, { displayName, expiresAt: Date.now() + 4000 });
      renderTyping();
      if (!typingSweep) typingSweep = setInterval(sweepTyping, 1000);
    };
    const sweepTyping = () => {
      const now = Date.now();
      let changed = false;
      for (const [u, v] of typingUsers) {
        if (v.expiresAt <= now) { typingUsers.delete(u); changed = true; }
      }
      if (changed) renderTyping();
      if (typingUsers.size === 0 && typingSweep) {
        clearInterval(typingSweep); typingSweep = null;
      }
    };
    const renderTyping = () => {
      if (!typingEl) return;
      const names = [...typingUsers.values()].map(v => v.displayName);
      if (names.length === 0) {
        typingEl.hidden = true;
        typingEl.textContent = '';
        return;
      }
      let text;
      if (names.length === 1) text = names[0] + ' is typing…';
      else if (names.length === 2) text = names[0] + ' and ' + names[1] + ' are typing…';
      else text = names.length + ' people are typing…';
      typingEl.textContent = text;
      typingEl.hidden = false;
    };

    // Throttled typing publisher: at most once per 2s while user keeps typing.
    let lastTypingSentAt = 0;
    const publishTyping = () => {
      if (!stomp.connected) return;
      const now = Date.now();
      if (now - lastTypingSentAt < 2000) return;
      lastTypingSentAt = now;
      stomp.publish({ destination: '/app/channels/' + activeChannelId + '/typing', body: '{}' });
    };

    // Send a channel message resiliently even if STOMP is mid-handshake / reconnecting (N10):
    // wait briefly for the socket, publish over STOMP when up (the only path that runs slash
    // commands), otherwise HTTP-POST non-slash messages (the server broadcasts them — N6 — and we
    // render the returned DTO locally since our own subscription may be down). Never a silent no-op.
    async function awaitConnected(timeoutMs) {
      const deadline = Date.now() + timeoutMs;
      while (!stomp.connected && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 50));
      }
      return stomp.connected;
    }
    async function sendChannelMessage(body) {
      if (await awaitConnected(800)) {
        // Optimistic echo. The server now broadcasts only once the message is durably stored, so
        // without this the sender stares at an empty composer for the round trip. Draw the bubble
        // straight away in a "sending" state and let the broadcast retire it — the clientId is how
        // we recognise our own message coming back, which body text can't do once someone sends
        // the same line twice.
        const clientId = newClientId();
        markPendingSend(clientId, body);
        try {
          stomp.publish({ destination: '/app/channels/' + activeChannelId + '/send',
                          body: JSON.stringify({ body, clientId }) });
          return true;
        } catch (err) {
          failPendingSend(clientId);
          console.warn('[chat] STOMP publish failed, trying HTTP', err);
        }
      }
      if (body.startsWith('/')) {
        alert('Not connected — reconnecting. Please try that command again in a moment.');
        return false;
      }
      try {
        const res = await fetch('/api/channels/' + activeChannelId + '/messages', {
          method: 'POST', headers: headers(), body: JSON.stringify({ body }),
        });
        if (!res.ok) { alert('Message not sent — please try again.'); return false; }
        const dto = await res.json().catch(() => null);
        if (dto) appendMessage(dto);
        return true;
      } catch (err) {
        alert('Could not send: ' + (err?.message || err));
        return false;
      }
    }

    if (composer) {
      const fileInput = document.getElementById('composer-file');
      const attachBtn = document.getElementById('composer-attach');
      const pending = new Map(); // localId -> { file, chip }

      if (attachBtn && fileInput) {
        attachBtn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', () => {
          for (const f of fileInput.files) addPendingAttachment(f);
          fileInput.value = '';
        });
      }

      const emojiBtn = document.getElementById('composer-emoji');
      if (emojiBtn) {
        emojiBtn.addEventListener('click', () => {
          const ta = document.getElementById('composer-input');
          openEmojiPicker(emojiBtn, (e) => insertAtCursor(ta, e));
        });
      }

      composer.addEventListener('submit', async (e) => {
        e.preventDefault();
        const input = document.getElementById('composer-input');
        const body = input.value.trim();
        const hasFiles = pending.size > 0;
        if (!body && !hasFiles) return;

        if (hasFiles) {
          // Upload each file as its own message (caption = body, only on the first one).
          let caption = body;
          for (const [localId, item] of Array.from(pending.entries())) {
            try {
              await uploadAttachment(item.file, caption);
              // Clear the composer as soon as the caption is consumed — otherwise a later file's
              // failure returns with the caption still in the box, and resubmitting re-posts it
              // against the remaining files (N14).
              if (caption) { input.value = ''; input._autoResize?.(); }
              caption = '';
              removePendingAttachment(localId);
            } catch (err) {
              alert('Upload failed for ' + item.file.name + ': ' + err.message);
              return;
            }
          }
          input.value = '';
          input._autoResize?.();
        } else {
          if (await sendChannelMessage(body)) {
            input.value = '';
            input._autoResize?.();
          }
        }
      });
      const composerInput = document.getElementById('composer-input');
      wireAutoResize(composerInput);
      // Broadcast a "typing" ping while the user is editing the composer.
      composerInput?.addEventListener('input', () => {
        if (composerInput.value.trim().length > 0) publishTyping();
      });

      // Live markdown preview — server-rendered so the preview matches the posted message
      // exactly (mentions, code highlighting, sanitisation, all identical).
      const previewPane = document.getElementById('composer-preview');
      const previewBody = document.getElementById('composer-preview-body');
      let previewDebounce = null;
      let previewReq = 0;
      async function refreshPreview() {
        if (!previewPane || !previewBody || !composerInput) return;
        const body = composerInput.value;
        if (!body.trim()) {
          previewPane.hidden = true;
          previewBody.innerHTML = '';
          return;
        }
        const myReq = ++previewReq;
        try {
          const res = await fetch('/api/preview', {
            method: 'POST',
            headers: headers(),
            body: JSON.stringify({ body })
          });
          if (!res.ok) return;
          const data = await res.json();
          if (myReq !== previewReq) return; // stale response, dropped
          previewBody.innerHTML = data.html || '';
          highlightCode(previewBody);
          previewPane.hidden = !data.html;
        } catch (_) {
          // Network blip — leave the prior preview in place rather than blanking it.
        }
      }
      composerInput?.addEventListener('input', () => {
        clearTimeout(previewDebounce);
        previewDebounce = setTimeout(refreshPreview, 220);
      });
      // Hide preview after sending so an empty composer doesn't show a stale render.
      composer.addEventListener('submit', () => {
        clearTimeout(previewDebounce);
        if (previewPane) previewPane.hidden = true;
        if (previewBody) previewBody.innerHTML = '';
      });

      const addPendingAttachment = (file) => {
        const tray = document.getElementById('composer-attachments');
        if (!tray) return;
        const localId = 'p' + Math.random().toString(36).slice(2);
        const chip = document.createElement('div');
        chip.className = 'composer-chip';
        chip.innerHTML = `<span class="composer-chip-name"></span>
          <span class="composer-chip-size"></span>
          <button type="button" class="composer-chip-remove" title="Remove" aria-label="Remove">
            <svg class="icon icon-sm"><use href="#icon-close"/></svg>
          </button>`;
        chip.querySelector('.composer-chip-name').textContent = file.name;
        chip.querySelector('.composer-chip-size').textContent = formatBytes(file.size);
        chip.querySelector('.composer-chip-remove')
            .addEventListener('click', () => removePendingAttachment(localId));
        tray.append(chip);
        tray.hidden = false;
        pending.set(localId, { file, chip });
      };
      const removePendingAttachment = (localId) => {
        const item = pending.get(localId);
        if (!item) return;
        item.chip.remove();
        pending.delete(localId);
        const tray = document.getElementById('composer-attachments');
        if (tray && pending.size === 0) tray.hidden = true;
      };
      async function uploadAttachment(file, caption) {
        // Raw-body upload: the File itself is the request body, so the browser streams it and the
        // server copies socket -> disk. Multipart would wrap it in boundaries that the server then
        // has to scan for byte by byte, which is what used to cap upload speed. Filename and
        // caption travel as percent-encoded headers (header values are ISO-8859-1, so anything
        // non-ASCII has to be encoded).
        const h = headers();
        h['Content-Type'] = file.type || 'application/octet-stream';
        h['X-Upload-Filename'] = encodeURIComponent(file.name);
        if (caption) h['X-Upload-Caption'] = encodeURIComponent(caption);
        const res = await fetch('/api/channels/' + activeChannelId + '/attachments', {
          method: 'POST',
          headers: h,
          body: file,
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({ error: res.statusText }));
          // 413 carries the actual cap as `maxBytes` so we can render a precise message.
          if (res.status === 413 && typeof err.maxBytes === 'number') {
            const mib = (err.maxBytes / (1024 * 1024)).toFixed(0);
            throw new Error('File too large — your account is capped at ' + mib + ' MiB per upload.');
          }
          throw new Error(err.message || err.error || res.statusText);
        }
      }
    }
  }

  // formatBytes / hashCode / avatarColor / buildAvatarEl / dayKey / formatTime /
  // appendAuthorHandle all come from window.ChatKit (see chat-kit.js). Locals below
  // are page-specific (fuzzyMatch, levenshtein, formatDay) and stay here.
  const { formatBytes, avatarColor, buildAvatarEl, dayKey, formatTime, appendAuthorHandle } = ChatKit;

  // formatDay is page-local (channel-feed day-divider label); other date helpers come from ChatKit.
  // fuzzyMatch / levenshtein moved to ./shared.js so chat/chrome.js (sidebar filter) can use them.
  const formatDay = (d) =>
      d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });

  const lastMessageEl = () => {
    const items = messagesEl.querySelectorAll('li.message');
    return items.length ? items[items.length - 1] : null;
  };

  /**
   * Build the bare {@code <li.message>} for {@code msg}. Day-of/grouped class flags are NOT
   * set here — callers either compute them inline ({@code appendMessage}) or rely on the
   * post-mutation {@code refreshDayDividers()} walker ({@code prependOlderMessages}) to fill
   * them in by walking the DOM. Keeps the per-message DOM construction in one place.
   */
  const buildMessageLi = (msg) => {
    const created = new Date(msg.createdAt);
    const curDay = dayKey(created);
    const li = document.createElement('li');
    li.className = 'message';
    li.dataset.id = msg.id;
    li.dataset.createdAt = msg.createdAt;
    li.dataset.author = msg.authorUsername;
    li.dataset.day = curDay;
    // Server-rendered LIs carry this from Thymeleaf; live-appended/paged ones must too, or
    // attachActions (Edit button), startEdit (edit seed), and the reaction-vs-edit detection
    // in replaceMessageDom all misfire. (conversation.js already sets this.)
    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';

    const name = msg.authorDisplayName || msg.authorUsername;
    const avatar = buildAvatarEl({
      username: msg.authorUsername,
      letter: (name || '?').slice(0, 1).toUpperCase(),
      hasAvatar: msg.authorHasAvatar,
      avatarVersion: msg.authorAvatarVersion,
    });

    const right = document.createElement('div');
    const meta = document.createElement('div');
    meta.className = 'message-meta';
    const author = document.createElement('span');
    author.className = 'author';
    author.dataset.author = msg.authorUsername;
    author.textContent = name;
    const time = document.createElement('time');
    time.textContent = formatTime(created);
    meta.append(author);
    appendAuthorHandle(meta, msg.authorDisplayName, msg.authorUsername);
    meta.append(time);

    right.append(meta);

    if (msg.bodyMarkdown && msg.bodyMarkdown.length > 0) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml;
      highlightCode(body);
      right.append(body);
    }

    if (msg.poll) {
      right.append(renderPollWidget(msg.poll));
    }

    if (msg.reactions && msg.reactions.length > 0) {
      right.append(renderReactionTray(msg.reactions));
    }

    if (msg.attachments && msg.attachments.length > 0) {
      right.append(renderAttachmentTray(msg.attachments));
    }

    // Thread indicator anchors the bottom of the message, like Slack's "N replies" widget.
    const indicator = renderThreadIndicator(msg.replyCount);
    if (indicator) right.append(indicator);

    li.append(avatar, right);
    return li;
  };

  /**
   * Scroll to the bottom now, and again as each image inside {@code el} finishes loading.
   *
   * An image attachment has no height until its bytes arrive, so a single synchronous scroll
   * lands on what is momentarily the bottom; the image then loads, the container grows, and the
   * message the user just posted ends up below the fold. The initial page load already handles
   * this — the live append path did not, which is why sending a picture left you stranded above
   * it. `error` counts too: a broken image still changes the layout when it collapses.
   */
  const stickToBottomThroughImageLoads = (el) => {
    const stick = () => { messagesEl.scrollTop = messagesEl.scrollHeight; };
    stick();
    el.querySelectorAll('img').forEach((img) => {
      if (img.complete) return;
      img.addEventListener('load', stick, { once: true });
      img.addEventListener('error', stick, { once: true });
    });
  };

  const appendMessage = (msg) => {
    // De-dupe: a live broadcast can race the final infinite-scroll page (which flips
    // infiniteScrollDownDone) and arrive for a message already rendered — without this
    // guard it would append a duplicate <li> and a duplicate day-divider anchor.
    if (messagesEl.querySelector('li.message[data-id="' + CSS.escape(String(msg.id)) + '"]')) return;
    const created = new Date(msg.createdAt);
    const curDay = dayKey(created);
    const prev = lastMessageEl();
    const prevDay = prev ? prev.dataset.day : null;
    const prevAuthor = prev ? prev.dataset.author : null;

    const isFirstOfDay = curDay !== prevDay;
    if (isFirstOfDay) {
      addDayDividerForNewMessage(msg.id, created);
    }

    const sameAuthor = prevAuthor === msg.authorUsername && curDay === prevDay;
    const li = buildMessageLi(msg);
    if (sameAuthor) li.classList.add('grouped');
    if (isFirstOfDay) li.classList.add('first-of-day');

    // Only follow the tail if the reader is already near the bottom; otherwise a live
    // message would yank someone reading history straight down. (The prepend/history path
    // preserves the viewport separately.) Measure BEFORE appending.
    const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 120;
    messagesEl.append(li);
    attachActions(li);
    flagAsAppearing(li);
    if (nearBottom || msg.authorUsername === myUsernameMeta) {
      stickToBottomThroughImageLoads(li);
    }
    positionDayDividers();
  };

  /**
   * Walk the current message list top-to-bottom and reset the {@code first-of-day} +
   * {@code grouped} classes plus the day-divider layer to match. Called after a prepend
   * so the day-of-week labels and run-grouping for the boundary message and its prior
   * neighbour stay correct. O(N) over the visible message count, which is bounded by
   * however many pages the user has scrolled through — fine in practice.
   */
  const refreshDayDividers = () => {
    if (!messagesEl) return;
    if (dividerLayer) {
      dividerLayer.querySelectorAll('.day-divider').forEach((d) => d.remove());
    }
    let prevDay = null;
    let prevAuthor = null;
    messagesEl.querySelectorAll('li.message').forEach((li) => {
      const curDay = li.dataset.day;
      const curAuthor = li.dataset.author;
      const isFirstOfDay = curDay !== prevDay;
      const isGrouped = !isFirstOfDay && curAuthor === prevAuthor;
      li.classList.toggle('first-of-day', isFirstOfDay);
      li.classList.toggle('grouped', isGrouped);
      if (isFirstOfDay) {
        addDayDividerForNewMessage(li.dataset.id, new Date(li.dataset.createdAt));
      }
      prevDay = curDay;
      prevAuthor = curAuthor;
    });
    positionDayDividers();
  };

  // Server-rendered messages carry data-day and <time> formatted in the SERVER's timezone, but
  // live-appended messages use the BROWSER's zone (dayKey/formatTime). For a viewer in a different
  // zone that mismatch gives wrong day dividers/grouping at boundaries and timestamps that disagree
  // between old and new messages. Re-key every server-rendered message from its data-created-at in
  // the browser zone once on load, then rebuild dividers so everything is consistently client-zone.
  const hydrateServerTimestamps = () => {
    if (!messagesEl) return;
    messagesEl.querySelectorAll('li.message').forEach((li) => {
      if (!li.dataset.createdAt) return;
      const created = new Date(li.dataset.createdAt);
      li.dataset.day = dayKey(created);
      const timeEl = li.querySelector('.message-meta time');
      if (timeEl) timeEl.textContent = formatTime(created);
    });
    refreshDayDividers();
  };

  // ---------- Infinite scroll for older messages ----------
  // The initial Thymeleaf-rendered batch is the latest 50 (DEFAULT_PAGE_SIZE in MessageService).
  // When the user scrolls up to that batch's top, we fetch the prior 50 via the existing
  // /api/channels/{id}/messages?before=<instant>&limit=50 endpoint and prepend them. Repeats
  // until the server returns fewer than the limit, then stops watching.
  let oldestLoadedAt = (() => {
    const first = messagesEl?.querySelector('li.message[data-created-at]');
    return first ? first.dataset.createdAt : null;
  })();
  let infiniteScrollDone = !oldestLoadedAt; // empty channel → nothing to load
  let loadingOlder = false;
  let olderSentinel = null;
  let olderObserver = null;

  const prependOlderMessages = (rows) => {
    // Server returns oldest-first inside the batch (MessageService re-sorts ascending after
    // the descending DB fetch). Inserting each row before the current first.message LI
    // preserves that order: row[0] ends up at the new top, row[N-1] right above the prior top.
    const firstExisting = messagesEl.querySelector('li.message');
    let inserted = 0;
    for (const msg of rows) {
      // De-dupe in case of overlap with the existing batch (shouldn't happen with the
      // before=<instant> contract, but handle it defensively).
      if (messagesEl.querySelector('li.message[data-id="' + CSS.escape(msg.id) + '"]')) continue;
      const li = buildMessageLi(msg);
      if (firstExisting) {
        messagesEl.insertBefore(li, firstExisting);
      } else {
        messagesEl.append(li);
      }
      attachActions(li);
      // Don't flagAsAppearing — these are old messages, no slide-in animation.
      inserted++;
    }
    return inserted;
  };

  const loadOlder = async () => {
    if (loadingOlder || infiniteScrollDone || !activeChannelId || !oldestLoadedAt) return;
    loadingOlder = true;
    try {
      const url = '/api/channels/' + encodeURIComponent(activeChannelId) +
          '/messages?before=' + encodeURIComponent(oldestLoadedAt) + '&limit=50';
      const res = await fetch(url, { headers: headers(), credentials: 'same-origin' });
      if (!res.ok) return;
      const rows = await res.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        infiniteScrollDone = true;
        olderSentinel?.remove();
        olderObserver?.disconnect();
        return;
      }
      // Preserve the user's viewport: we're growing the list above their current scrollTop,
      // so push scrollTop down by the height delta to keep the visible content steady.
      const prevScrollHeight = messagesEl.scrollHeight;
      const prevScrollTop = messagesEl.scrollTop;

      const insertedCount = prependOlderMessages(rows);
      // Update the high-water mark to the new oldest visible message.
      oldestLoadedAt = rows[0].createdAt;
      refreshDayDividers();

      const newScrollHeight = messagesEl.scrollHeight;
      messagesEl.scrollTop = prevScrollTop + (newScrollHeight - prevScrollHeight);

      // Server returned a partial batch → nothing older exists.
      if (rows.length < 50 || insertedCount === 0) {
        infiniteScrollDone = true;
        olderSentinel?.remove();
        olderObserver?.disconnect();
      }
    } catch (e) {
      // Network blip — leave state alone; observer will fire again on next scroll-to-top.
    } finally {
      loadingOlder = false;
    }
  };

  const setupInfiniteScroll = () => {
    if (!messagesEl || infiniteScrollDone || !activeChannelId) return;
    olderSentinel = document.createElement('li');
    olderSentinel.className = 'load-older-sentinel';
    olderSentinel.setAttribute('aria-hidden', 'true');
    messagesEl.insertBefore(olderSentinel, messagesEl.firstChild);
    olderObserver = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) loadOlder();
      }
    }, { root: messagesEl, threshold: 0.1, rootMargin: '120px 0px 0px 0px' });
    olderObserver.observe(olderSentinel);
  };
  setupInfiniteScroll();

  // ---------- Symmetric infinite scroll for newer messages ----------
  // Only relevant when the page was opened on an old anchor (?m=<id>) — the user is reading
  // context-around something old, and there's still unloaded history between the visible
  // bottom and "now". A sentinel at the bottom of the list triggers a forward-paging fetch
  // when intersected. Regular page loads start at "latest 50" so there's nothing newer to
  // load forward; we leave the down-observer disabled for them.
  const centeredOnAnchor = (meta('centered-on-anchor') || '') === 'true';
  let latestLoadedAt = (() => {
    const items = messagesEl?.querySelectorAll('li.message[data-created-at]');
    if (!items || !items.length) return null;
    return items[items.length - 1].dataset.createdAt;
  })();
  // For non-anchor loads we know we're already at the tail — skip the down-observer.
  let infiniteScrollDownDone = !centeredOnAnchor;
  let loadingNewer = false;
  let newerSentinel = null;
  let newerObserver = null;

  const appendNewerMessages = (rows) => {
    let inserted = 0;
    for (const msg of rows) {
      if (messagesEl.querySelector('li.message[data-id="' + CSS.escape(msg.id) + '"]')) continue;
      const li = buildMessageLi(msg);
      // Insert before the bottom sentinel so it stays the last child.
      if (newerSentinel && newerSentinel.parentNode === messagesEl) {
        messagesEl.insertBefore(li, newerSentinel);
      } else {
        messagesEl.append(li);
      }
      attachActions(li);
      inserted++;
    }
    return inserted;
  };

  const loadNewer = async () => {
    if (loadingNewer || infiniteScrollDownDone || !activeChannelId || !latestLoadedAt) return;
    loadingNewer = true;
    try {
      const url = '/api/channels/' + encodeURIComponent(activeChannelId) +
          '/messages?after=' + encodeURIComponent(latestLoadedAt) + '&limit=50';
      const res = await fetch(url, { headers: headers(), credentials: 'same-origin' });
      if (!res.ok) return;
      const rows = await res.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        infiniteScrollDownDone = true;
        newerSentinel?.remove();
        newerObserver?.disconnect();
        // We've now caught up to the live feed — drop the "showing context" banner since the
        // viewer has paged forward to the present.
        document.getElementById('jump-to-latest-banner')?.remove();
        return;
      }
      // No scroll-position adjustment needed: we're appending below the viewport, so the
      // user's current scrollTop position stays anchored to the same content.
      appendNewerMessages(rows);
      latestLoadedAt = rows[rows.length - 1].createdAt;
      refreshDayDividers();

      if (rows.length < 50) {
        // Reached the tail of history — close out the down-observer and the banner.
        infiniteScrollDownDone = true;
        newerSentinel?.remove();
        newerObserver?.disconnect();
        document.getElementById('jump-to-latest-banner')?.remove();
      }
    } catch (e) {
      // Network blip — leave state alone; observer will retry on the next scroll-down.
    } finally {
      loadingNewer = false;
    }
  };

  const setupInfiniteScrollDown = () => {
    if (!messagesEl || infiniteScrollDownDone || !activeChannelId) return;
    newerSentinel = document.createElement('li');
    newerSentinel.className = 'load-newer-sentinel';
    newerSentinel.setAttribute('aria-hidden', 'true');
    messagesEl.appendChild(newerSentinel);
    newerObserver = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) loadNewer();
      }
    }, { root: messagesEl, threshold: 0.1, rootMargin: '0px 0px 120px 0px' });
    newerObserver.observe(newerSentinel);
  };
  setupInfiniteScrollDown();

  const renderThreadIndicator = (count) => {
    if (!count || count <= 0) return null;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'thread-indicator';
    btn.title = 'Open thread (' + count + ' ' + (count === 1 ? 'reply' : 'replies') + ')';
    btn.innerHTML = '<svg class="icon thread-indicator-icon" aria-hidden="true">'
        + '<use href="#icon-thread"/></svg><span class="thread-indicator-count"></span>';
    btn.querySelector('.thread-indicator-count').textContent = count + ' ' + (count === 1 ? 'reply' : 'replies');
    btn.dataset.count = String(count);
    return btn;
  };

  // Bump (or create) the thread indicator on a parent message after a reply arrives.
  const bumpThreadIndicator = (parentId, delta) => {
    const li = findMessageEl(parentId);
    if (!li) return;
    const right = li.querySelector(':scope > div');
    if (!right) return;
    let btn = right.querySelector('.thread-indicator');
    let count = btn ? (parseInt(btn.dataset.count, 10) || 0) : 0;
    count = Math.max(0, count + (delta || 0));
    if (count <= 0) {
      btn?.remove();
      return;
    }
    if (!btn) {
      btn = renderThreadIndicator(count);
      // Insert before attachments / reactions / edit-form, after message-body.
      const body = right.querySelector('.message-body');
      if (body && body.nextSibling) right.insertBefore(btn, body.nextSibling);
      else right.appendChild(btn);
    } else {
      btn.querySelector('.thread-indicator-count').textContent =
          count + ' ' + (count === 1 ? 'reply' : 'replies');
      btn.title = 'Open thread (' + count + ' ' + (count === 1 ? 'reply' : 'replies') + ')';
      btn.dataset.count = String(count);
    }
  };

  // ---------- Day-divider layer ----------
  // Day-dividers live as siblings of .messages (inside .messages-stack) so the mask on
  // .messages doesn't fade them. Each divider is anchored to the first message of its day;
  // we keep them aligned to that message by setting `top` from anchor.offsetTop - scrollTop.
  const dividerLayer = document.getElementById('day-divider-layer');

  const addDayDividerForNewMessage = (messageId, created) => {
    if (!dividerLayer) return;
    const div = document.createElement('div');
    div.className = 'day-divider';
    div.dataset.anchorId = messageId;
    div.dataset.day = dayKey(created);
    const span = document.createElement('span');
    span.textContent = formatDay(created);
    div.append(span);
    dividerLayer.appendChild(div);
  };

  let dividerRaf = 0;
  const positionDayDividers = () => {
    if (!dividerLayer || !messagesEl) return;
    if (dividerRaf) return;
    dividerRaf = requestAnimationFrame(() => {
      dividerRaf = 0;
      const scrollTop = messagesEl.scrollTop;
      dividerLayer.querySelectorAll('.day-divider').forEach((div) => {
        const anchorId = div.dataset.anchorId;
        const anchor = anchorId
            ? messagesEl.querySelector('li.message[data-id="' + CSS.escape(anchorId) + '"]')
            : null;
        if (!anchor) {
          div.style.visibility = 'hidden';
          return;
        }
        // Center the divider in the gap created by .first-of-day's margin-top (≈2.5rem ≈ 40px).
        const top = anchor.offsetTop - scrollTop - 32;
        div.style.top = top + 'px';
        div.style.visibility = 'visible';
      });
    });
  };
  messagesEl?.addEventListener('scroll', positionDayDividers, { passive: true });
  window.addEventListener('resize', positionDayDividers);
  // Re-key server-rendered timestamps into the browser's timezone before the first layout.
  hydrateServerTimestamps();
  // Re-measure after the page settles (fonts/images may shift offsets).
  setTimeout(positionDayDividers, 50);
  setTimeout(positionDayDividers, 300);
  positionDayDividers();

  // Add the .appearing class so CSS plays the slide-in + accent stripe, then strip it after the
  // longest animation completes so subsequent re-renders (edits, reactions) don't replay it.
  const flagAsAppearing = (li) => {
    if (!li) return;
    li.classList.add('appearing');
    setTimeout(() => li.classList.remove('appearing'), 1700);
  };

  // Open the channel pinned to the most recent message. Re-runs after a tick and once
  // images settle, since avatars / inline images / code highlighting can grow the content
  // height *after* the synchronous scroll, leaving the viewport a few hundred pixels short.
  // Skipped when the URL carries a #m=… permalink — that flow scrolls to a specific message.
  const scrollToBottom = () => {
    if (!messagesEl) return;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  };
  const hasPermalink = /^#m=/.test(window.location.hash || '');
  if (messagesEl && !hasPermalink) {
    scrollToBottom();
    requestAnimationFrame(scrollToBottom);
    setTimeout(scrollToBottom, 50);
    setTimeout(scrollToBottom, 300);
    messagesEl.querySelectorAll('img').forEach((img) => {
      if (!img.complete) img.addEventListener('load', scrollToBottom, { once: true });
    });
  }

  // ---------- Syntax highlighting ----------
  const highlightCode = (root) => {
    if (!root) return;
    if (!window.hljs) {
      if (!highlightCode._warned) {
        highlightCode._warned = true;
        console.warn('[hljs] highlight.js not loaded — code blocks will render unhighlighted');
      }
      return;
    }
    root.querySelectorAll('pre code').forEach((block) => {
      // hljs v11 marks processed blocks with data-highlighted="yes"; re-running just spams a warning.
      if (block.dataset.highlighted === 'yes') return;
      try {
        window.hljs.highlightElement(block);
      } catch (err) {
        console.warn('[hljs] failed to highlight a block:', err);
      }
    });
  };
  // Highlight everything currently on the page (server-rendered messages, search results, etc.).
  highlightCode(document);

  // ---------- Color server-rendered avatars (delegated to ChatKit) ----------
  ChatKit.backfillAvatarColors();

  // ---------- Message actions / edit / delete / threads ----------
  const myUsernameMeta = meta('me-username');
  const isAdmin = meta('me-is-admin') === 'true';

  // Match in the main feed first, then fall back to the thread panel — thread replies
  // that aren't currently in the channel viewport (e.g. older replies) only live in the
  // thread <ol>, so reaction/edit broadcasts must update them there.
  const findMessageEl = (id) => {
    const sel = 'li.message[data-id="' + CSS.escape(id) + '"]';
    return (messagesEl && messagesEl.querySelector(sel))
        || document.querySelector('#thread-replies ' + sel)
        || document.querySelector('#thread-parent ' + sel);
  };

  const buildActions = (authorUsername, hasBody) => {
    const isMine = authorUsername === myUsernameMeta;
    const canDelete = isMine || isAdmin;
    const actions = document.createElement('div');
    actions.className = 'message-actions';
    // Icons come from the sprite in fragments/icon-sprite.html rather than being emoji: emoji
    // render as full-colour glyphs from whatever font the OS picked, so they ignore the theme,
    // change shape per platform, and can't be dimmed to --muted the way the rest of the row is.
    // The `title` on each button is load-bearing beyond the tooltip — the mobile action sheet in
    // chat-kit.js reads it for the row label.
    const action = (name, icon, title) =>
        '<button type="button" class="msg-action" data-action="' + name + '" title="' + title + '"'
        + ' aria-label="' + title + '"><svg class="icon" aria-hidden="true"><use href="#icon-'
        + icon + '"/></svg></button>';
    let html = '';
    // React is offered on every message including your own. The server allows it; a ✅ on your
    // own announcement or the first 👍 under your own question is a normal thing to want.
    html += action('react', 'face-smile', 'Add reaction');
    html += action('reply', 'reply', 'Reply in thread');
    html += action('permalink', 'link', 'Copy link to message');
    if (isMine && hasBody) {
      html += action('edit', 'pencil', 'Edit');
    }
    if (canDelete) {
      html += action('delete', 'trash', 'Delete');
    }
    actions.innerHTML = html;
    return actions;
  };

  // ---------- Reactions ----------
  const renderReactionTray = (groups) => {
    const tray = document.createElement('div');
    tray.className = 'message-reactions';
    for (const g of groups) tray.appendChild(buildReactionBubble(g));
    return tray;
  };
  const buildReactionBubble = (g) => {
    const mineDerived = g.usernames && g.usernames.includes(myUsernameMeta);
    const mine = mineDerived || g.mine;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'reaction' + (mine ? ' mine' : '');
    btn.dataset.emoji = g.emoji;
    btn.dataset.mine = String(mine);
    btn.title = (g.usernames || []).join(', ');
    btn.innerHTML = '<span class="reaction-emoji"></span><span class="reaction-count"></span>';
    btn.querySelector('.reaction-emoji').textContent = g.emoji;
    btn.querySelector('.reaction-count').textContent = g.count;
    return btn;
  };

  async function toggleReaction(messageId, emoji, currentlyMine) {
    const url = '/api/messages/' + messageId + '/reactions' + (currentlyMine ? '/' + encodeURIComponent(emoji) : '');
    const res = await fetch(url, {
      method: currentlyMine ? 'DELETE' : 'POST',
      headers: headers(),
      body: currentlyMine ? undefined : JSON.stringify({ emoji })
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Reaction failed: ' + (err.error || res.statusText));
    }
    // WS broadcast triggers replaceMessageDom to refresh the bubble row.
  }
  // Lets the mobile long-press action sheet's emoji strip toggle reactions.
  ChatKit.setQuickReaction(toggleReaction);

  // Markdown toolbar / caret-insert helpers come from window.ChatKit (see chat-kit.js).
  // Wire all toolbars on the page (main composer + thread reply composer share the
  // same data-format-target attribute scheme).
  ChatKit.wireAllFormatToolbars();

  const attachActions = (li) => {
    if (!li || li.querySelector('.message-actions')) return;
    const author = li.dataset.author;
    const hasBody = !!(li.dataset.bodyMarkdown && li.dataset.bodyMarkdown.length > 0);
    li.appendChild(buildActions(author, hasBody));
  };

  const buildAttachmentLink = (a) => {
    // Tombstone: the file was deleted from the file manager, the message stayed.
    if (a.deletedAt) return window.ChatKit.buildRemovedAttachmentEl(a);
    const isImage = (a.contentType || '').startsWith('image/');
    const link = document.createElement('a');
    link.href = a.downloadUrl;
    link.title = a.filename;
    if (isImage) {
      link.className = 'attachment-image';
      // Keep href + target so middle-click and "Open in new tab" still work; left-click
      // is intercepted by the document-level delegate that opens the lightbox.
      link.target = '_blank';
      link.rel = 'noopener';
      const img = document.createElement('img');
      img.src = a.downloadUrl;
      img.alt = a.filename;
      img.loading = 'lazy';
      link.append(img);
    } else {
      link.className = 'attachment';
      link.dataset.contentType = a.contentType;
      link.innerHTML = '<svg class="icon attachment-icon"><use href="#icon-paperclip"/></svg>' +
          '<span class="attachment-info"><span class="attachment-name"></span>' +
          '<span class="attachment-meta"></span></span>' +
          '<svg class="icon attachment-download"><use href="#icon-download"/></svg>';
      link.querySelector('.attachment-name').textContent = a.filename;
      link.querySelector('.attachment-meta').textContent =
          (a.contentType || '') + ' · ' + formatBytes(a.sizeBytes);
    }
    return link;
  };

  const renderAttachmentTray = (attachments) => {
    const tray = document.createElement('div');
    tray.className = 'message-attachments';
    for (const a of attachments) tray.append(buildAttachmentLink(a));
    return tray;
  };

  // ---------- Poll widget ----------
  // Click-to-vote with bar visualisation. Reactions on the host message stay independent —
  // they're emoji reactions, not votes. Mobile: each option is a full-width ≥44px button so
  // it's a comfortable tap target on phones; the bar fills the button's background instead
  // of sitting beside it.
  const renderPollWidget = (poll) => {
    const root = document.createElement('div');
    root.className = 'poll-widget';
    root.dataset.pollId = poll.id;
    // Stashed so startEdit can populate the builder without another fetch: the stored message
    // body is only the question, so the options exist nowhere else on the page.
    root.dataset.poll = JSON.stringify({
      question: poll.question,
      options: (poll.options || []).map((o) => ({ label: o.label, votes: o.voteCount || 0 })),
    });

    const q = document.createElement('div');
    q.className = 'poll-question';
    q.textContent = poll.question;
    root.append(q);

    const list = document.createElement('ul');
    list.className = 'poll-options';
    for (const opt of poll.options) list.append(renderPollOption(poll, opt));
    root.append(list);

    const footer = document.createElement('div');
    footer.className = 'poll-footer';

    const tally = document.createElement('span');
    tally.className = 'poll-tally';
    tally.textContent = poll.totalVoters + ' vote' + (poll.totalVoters === 1 ? '' : 's');
    footer.append(tally);

    if (poll.myVoteOptionId) {
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'poll-remove-vote';
      remove.textContent = 'Remove vote';
      remove.addEventListener('click', () => removePollVote(poll.id, root));
      footer.append(remove);
    }
    root.append(footer);
    return root;
  };

  const renderPollOption = (poll, opt) => {
    const li = document.createElement('li');
    li.className = 'poll-option';
    li.dataset.optionId = opt.id;

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'poll-option-btn';
    if (opt.id === poll.myVoteOptionId) btn.classList.add('voted');
    btn.setAttribute('aria-pressed', opt.id === poll.myVoteOptionId ? 'true' : 'false');

    const denominator = Math.max(poll.totalVoters || 0, 1);
    const pct = (opt.voteCount / denominator) * 100;
    const bar = document.createElement('span');
    bar.className = 'poll-option-bar';
    bar.style.width = pct.toFixed(1) + '%';

    const label = document.createElement('span');
    label.className = 'poll-option-label';
    label.textContent = opt.label;

    const count = document.createElement('span');
    count.className = 'poll-option-count';
    count.textContent = opt.voteCount;

    btn.append(bar, label, count);
    btn.addEventListener('click', () => castPollVote(poll.id, opt.id, btn.closest('.poll-widget')));

    li.append(btn);
    return li;
  };

  const castPollVote = async (pollId, optionId, widgetEl) => {
    if (!widgetEl) return;
    try {
      const res = await fetch('/api/polls/' + encodeURIComponent(pollId) + '/vote', {
        method: 'POST', headers: headers(),
        body: JSON.stringify({ optionId }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert('Vote failed: ' + (err.message || err.error || res.statusText));
        return;
      }
      const dto = await res.json();
      widgetEl.replaceWith(renderPollWidget(dto));
    } catch (e) {
      // Network blip — leave the existing widget; live broadcast will reconcile if it lands.
    }
  };

  const removePollVote = async (pollId, widgetEl) => {
    if (!widgetEl) return;
    try {
      const res = await fetch('/api/polls/' + encodeURIComponent(pollId) + '/vote', {
        method: 'DELETE', headers: headers(),
      });
      if (!res.ok) return;
      const dto = await res.json();
      widgetEl.replaceWith(renderPollWidget(dto));
    } catch (e) { /* ignore */ }
  };

  /**
   * Apply a {@code poll-vote} broadcast to a rendered widget without clobbering the local
   * "I voted for X" indicator — that comes from this user's own POST/DELETE round-trip and
   * isn't carried correctly in a topic-level broadcast (the broadcast contains the *actor's*
   * myVoteOptionId, not the recipient's).
   */
  const applyPollUpdate = (messageId, dto) => {
    if (!messageId || !dto) return;
    const li = findMessageEl(messageId);
    if (!li) return;
    const widget = li.querySelector('.poll-widget');
    if (!widget) return;
    const myVotedEl = widget.querySelector('.poll-option-btn.voted');
    const myOptionId = myVotedEl ? myVotedEl.closest('.poll-option').dataset.optionId : null;
    const merged = Object.assign({}, dto, { myVoteOptionId: myOptionId });
    widget.replaceWith(renderPollWidget(merged));
  };

  /**
   * Replace every server-rendered <div class="poll-placeholder" data-poll-id="..."> with the
   * live widget. Page-load only — runs once after the initial Thymeleaf message list is in
   * the DOM. New messages arriving via WS already include the poll directly in the dto and
   * are rendered through {@code renderPollWidget} in {@code appendMessage}.
   */
  const hydratePollPlaceholders = async () => {
    const placeholders = document.querySelectorAll('.poll-placeholder[data-poll-id]');
    if (!placeholders.length) return;
    await Promise.all([...placeholders].map(async (el) => {
      const id = el.dataset.pollId;
      try {
        const res = await fetch('/api/polls/' + encodeURIComponent(id), {
          headers: headers(), credentials: 'same-origin',
        });
        if (!res.ok) {
          el.remove();
          return;
        }
        const dto = await res.json();
        el.replaceWith(renderPollWidget(dto));
      } catch (e) {
        el.remove();
      }
    }));
  };
  hydratePollPlaceholders();

  // ---------- Image lightbox ----------
  // Lives in chat-kit.js: the conversation page needs the identical one, and it used to make do
  // with window.open — a new browser tab instead of the in-page viewer, which is the difference
  // people notice when they say attachments "behave differently" in a DM.
  window.ChatKit.wireImageLightbox();

  const replaceMessageDom = (msg) => {
    const li = findMessageEl(msg.id);
    if (!li) return;
    // Detect an actual body edit (vs. a reaction-only update) so we only flash on edits.
    const prevBody = li.dataset.bodyMarkdown || '';
    const newBody = msg.bodyMarkdown || '';
    const isEdit = newBody !== prevBody;
    const right = li.querySelector(':scope > div');
    if (!right) return;
    // If the author has an edit form open and this update is only a reaction/attachment/poll
    // change (not a body change), refresh just those trays — blowing away .message-edit here
    // would destroy their unsaved draft the moment anyone reacts.
    if (right.querySelector('.message-edit') && !isEdit) {
      right.querySelectorAll('.message-attachments, .message-reactions, .poll-widget').forEach(n => n.remove());
      if (msg.poll) right.appendChild(renderPollWidget(msg.poll));
      if (msg.attachments && msg.attachments.length) right.appendChild(renderAttachmentTray(msg.attachments));
      if (msg.reactions && msg.reactions.length) right.appendChild(renderReactionTray(msg.reactions));
      positionDayDividers();
      return;
    }
    li.dataset.bodyMarkdown = newBody;
    right.querySelectorAll('.message-body, .message-attachments, .message-reactions, .message-edit, .edited-tag, .poll-widget').forEach(n => n.remove());
    const meta = right.querySelector('.message-meta');
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml;
      highlightCode(body);
      meta.after(body);
      if (isEdit) flashEdited(body);
    }
    if (msg.editedAt && meta && !meta.querySelector('.edited-tag')) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      meta.appendChild(tag);
      if (isEdit) {
        tag.classList.add('just-changed');
        setTimeout(() => tag.classList.remove('just-changed'), 1000);
      }
    }
    if (msg.poll) {
      right.appendChild(renderPollWidget(msg.poll));
    }
    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }
    // Refresh action toolbar so edit visibility tracks the new body
    li.querySelector('.message-actions')?.remove();
    attachActions(li);
    positionDayDividers();
  };

  const flashEdited = (bodyEl) => {
    if (!bodyEl) return;
    bodyEl.classList.add('just-edited');
    setTimeout(() => bodyEl.classList.remove('just-edited'), 1500);
  };

  const removeMessageDom = (id) => {
    const li = findMessageEl(id);
    if (li) li.remove();
    // Drop any divider that was anchored to this message (orphans).
    document.querySelectorAll('#day-divider-layer .day-divider[data-anchor-id="' + CSS.escape(id) + '"]')
        .forEach(d => d.remove());
    // Remove from thread panel if present, and close if the parent itself got deleted.
    document.querySelectorAll('#thread-replies li[data-id="' + CSS.escape(id) + '"]').forEach(el => el.remove());
    const tp = document.querySelector('#thread-parent [data-id]');
    // dataset.id is always a string; id may arrive as a JSON number via the STOMP 'deleted'
    // frame. Coerce so the strict-equal doesn't silently miss the remote-delete case.
    if (tp && tp.dataset.id === String(id)) closeThread();
    // Rebuild dividers AND grouping — deleting a day's first message (or an author-run head)
    // otherwise orphans the divider and leaves the next row wrongly .grouped (N13).
    refreshDayDividers();
  };

  // Single delegate handles reactions / thread-indicator / msg-action clicks. Bound to
  // both the main feed and the thread panel so reactions + edit + delete all work in
  // either surface (a thread reply may have no twin in the main viewport).
  const handleMessageClick = (e) => {
    const reactionBtn = e.target.closest('.reaction');
    if (reactionBtn) {
      const li = reactionBtn.closest('li.message');
      if (li) toggleReaction(li.dataset.id, reactionBtn.dataset.emoji, reactionBtn.dataset.mine === 'true');
      return;
    }
    const threadBtn = e.target.closest('.thread-indicator');
    if (threadBtn) {
      const li = threadBtn.closest('li.message');
      if (li) openThread(li.dataset.id);
      return;
    }
    const btn = e.target.closest('.msg-action');
    if (!btn) return;
    const li = btn.closest('li.message');
    if (!li) return;
    const id = li.dataset.id;
    if (btn.dataset.action === 'edit') startEdit(li);
    else if (btn.dataset.action === 'delete') doDelete(id);
    else if (btn.dataset.action === 'reply') openThread(id);
    else if (btn.dataset.action === 'react') {
      openEmojiPicker(btn, (emoji) => toggleReaction(li.dataset.id, emoji, false));
    }
    else if (btn.dataset.action === 'permalink') copyPermalink(li);
  };
  if (messagesEl) {
    messagesEl.addEventListener('click', handleMessageClick);
    messagesEl.querySelectorAll('li.message').forEach(attachActions);
  }
  const threadPanelForClicks = document.getElementById('thread-panel');
  if (threadPanelForClicks) {
    threadPanelForClicks.addEventListener('click', handleMessageClick);
  }

  const startEdit = (li) => {
    if (li.querySelector('.message-edit')) return;
    const right = li.querySelector(':scope > div');
    const body = right.querySelector('.message-body');
    if (!body) return;
    // A poll is edited in the poll builder, not as text. The command form still works — it is
    // what gets sent — but asking someone to edit pipe-separated syntax to fix a typo is asking
    // them to learn the syntax to use the feature.
    const pollEl = li.querySelector('.poll-widget');
    if (pollEl && pollEl.dataset.poll) {
      const poll = JSON.parse(pollEl.dataset.poll);
      const votes = (poll.options || []).reduce((n, o) => n + (o.votes || 0), 0);
      openPollModal({
        poll,
        lockOptions: votes > 0,
        onSubmit: async (command) => {
          const res = await fetch('/api/messages/' + li.dataset.id, {
            method: 'PATCH', headers: headers(), body: JSON.stringify({ body: command }),
          });
          if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.message || err.error || 'Could not save that poll.');
          }
        },
      });
      return;
    }
    const original = li.dataset.bodyMarkdown || '';
    const wrap = document.createElement('div');
    wrap.className = 'message-edit';
    wrap.innerHTML =
        '<textarea class="message-edit-input" rows="3"></textarea>' +
        '<div class="message-edit-actions">' +
        '<button type="button" class="message-edit-cancel">Cancel</button>' +
        '<button type="button" class="message-edit-save">Save</button>' +
        '</div>';
    const ta = wrap.querySelector('textarea');
    ta.value = original;
    body.replaceWith(wrap);
    ta.focus();
    ta.setSelectionRange(ta.value.length, ta.value.length);

    wrap.querySelector('.message-edit-cancel').addEventListener('click', () => {
      wrap.replaceWith(body);
    });
    wrap.querySelector('.message-edit-save').addEventListener('click', async () => {
      const newBody = ta.value.trim();
      if (!newBody) { alert('Body cannot be empty'); return; }
      const id = li.dataset.id;
      const res = await fetch('/api/messages/' + id, {
        method: 'PATCH',
        headers: headers(),
        body: JSON.stringify({ body: newBody })
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert('Edit failed: ' + (err.error || res.statusText));
        return;
      }
      // WS broadcast triggers replaceMessageDom — nothing else to do.
    });
    ta.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') {
        ev.preventDefault();
        wrap.replaceWith(body);
      } else if (ev.key === 'Enter' && !ev.shiftKey && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
        if (ev.isComposing || ev.keyCode === 229) return;
        ev.preventDefault();
        wrap.querySelector('.message-edit-save').click();
      }
    });
    wireAutoResize(ta);
  };

  async function doDelete(id) {
    if (!confirm('Delete this message?')) return;
    const res = await fetch('/api/messages/' + id, {
      method: 'DELETE',
      headers: headers()
    });
    if (!res.ok && res.status !== 204) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Delete failed: ' + (err.error || res.statusText));
      return;
    }
    // Optimistically remove the message in this tab the moment the server confirms — don't
    // wait for the WS broadcast round-trip. removeMessageDom is idempotent so the eventual
    // /topic/channels/{id} 'deleted' frame is a harmless no-op here, while still updating
    // any other tabs / other users that have the channel open.
    removeMessageDom(id);
  }

  // ---------- Permalink ----------
  // Query for the server (renders context-around), fragment for the client-side scroll/highlight.
  const permalinkFor = (messageId) => {
    const id = encodeURIComponent(messageId);
    return window.location.origin + '/channels/' + activeChannelId + '?m=' + id + '#m=' + id;
  };
  async function copyPermalink(li) {
    const url = permalinkFor(li.dataset.id);
    try {
      await navigator.clipboard.writeText(url);
      chrome.flashToast('Link copied');
    } catch (_) {
      // Clipboard API may be unavailable on insecure origins — fall back to a prompt.
      window.prompt('Copy this link', url);
    }
  }
  const scrollToPermalinkTarget = () => {
    const m = (window.location.hash || '').match(/^#m=([^&]+)/);
    if (!m) return;
    const id = decodeURIComponent(m[1]);
    const el = findMessageEl(id);
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: 'auto' });
    el.classList.add('flash-highlight');
    setTimeout(() => el.classList.remove('flash-highlight'), 1800);
  };
  // Run on initial load + when fragment changes (e.g. user navigates within page).
  if (messagesEl) {
    setTimeout(scrollToPermalinkTarget, 50);
    window.addEventListener('hashchange', scrollToPermalinkTarget);
  }

  // ---------- Thread panel ----------
  const threadPanel = document.getElementById('thread-panel');
  const threadParentEl = document.getElementById('thread-parent');
  const threadRepliesEl = document.getElementById('thread-replies');
  const threadComposerForm = document.getElementById('thread-composer');
  const threadInput = document.getElementById('thread-input');
  const threadEmojiBtn = document.getElementById('thread-emoji');
  const threadCloseBtn = document.getElementById('thread-close');
  let openThreadId = null;
  threadEmojiBtn?.addEventListener('click', () => {
    openEmojiPicker(threadEmojiBtn, (e) => insertAtCursor(threadInput, e));
  });

  let threadReq = 0;
  const closeThread = () => {
    if (!threadPanel) return;
    threadReq++; // invalidate any in-flight openThread so its late response can't reopen the panel (N11)
    threadPanel.hidden = true;
    document.body.classList.remove('thread-open');
    openThreadId = null;
    if (threadParentEl) threadParentEl.innerHTML = '';
    if (threadRepliesEl) threadRepliesEl.innerHTML = '';
    if (threadInput) {
      threadInput.value = '';
      threadInput._autoResize?.();
    }
  };
  threadCloseBtn?.addEventListener('click', closeThread);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && threadPanel && !threadPanel.hidden) closeThread();
  });

  async function openThread(parentId) {
    if (!threadPanel) return;
    // Monotonic request id (like the search/preview paths): if the user clicks a different thread
    // before this fetch lands, drop the stale response so the panel always shows the last click.
    const myReq = ++threadReq;
    const res = await fetch('/api/messages/' + parentId + '/thread');
    if (myReq !== threadReq) return; // superseded by a newer openThread
    if (!res.ok) { alert('Could not load thread'); return; }
    const data = await res.json();
    if (myReq !== threadReq) return; // superseded while awaiting the body
    openThreadId = parentId;
    threadParentEl.innerHTML = '';
    threadRepliesEl.innerHTML = '';
    threadParentEl.appendChild(renderThreadMessage(data.parent, true));
    for (const r of data.replies) threadRepliesEl.appendChild(renderThreadMessage(r, false));
    threadPanel.hidden = false;
    document.body.classList.add('thread-open');
    threadInput?.focus();
  }

  const renderThreadMessage = (msg, isParent) => {
    const li = document.createElement('li');
    li.className = 'message thread-message' + (isParent ? ' thread-parent-msg' : '');
    li.dataset.id = msg.id;
    li.dataset.author = msg.authorUsername;
    li.dataset.bodyMarkdown = msg.bodyMarkdown || '';
    const name = msg.authorDisplayName || msg.authorUsername;
    const created = new Date(msg.createdAt);
    const initial = (name || '?').slice(0, 1).toUpperCase();
    li.innerHTML = `
      <div>
        <div class="message-meta">
          <span class="author"></span>
          <time></time>
        </div>
      </div>`;
    const avatar = buildAvatarEl({
      username: msg.authorUsername,
      letter: initial,
      hasAvatar: msg.authorHasAvatar,
      avatarVersion: msg.authorAvatarVersion,
    });
    li.insertBefore(avatar, li.firstChild);
    const authorSpan = li.querySelector('.author');
    authorSpan.textContent = name;
    authorSpan.dataset.author = msg.authorUsername;
    appendAuthorHandle(li.querySelector('.message-meta'), msg.authorDisplayName, msg.authorUsername);
    // Re-append <time> after the handle so order stays: name, @handle, time.
    const timeEl = li.querySelector('time');
    li.querySelector('.message-meta').appendChild(timeEl);
    timeEl.textContent = formatTime(created);
    if (msg.editedAt) {
      const tag = document.createElement('span');
      tag.className = 'edited-tag';
      tag.textContent = '(edited)';
      li.querySelector('.message-meta').appendChild(tag);
    }
    const right = li.querySelector(':scope > div');
    if (msg.bodyMarkdown) {
      const body = document.createElement('div');
      body.className = 'message-body';
      body.innerHTML = msg.bodyHtml;
      highlightCode(body);
      right.appendChild(body);
    }
    if (msg.reactions && msg.reactions.length) {
      right.appendChild(renderReactionTray(msg.reactions));
    }
    if (msg.attachments && msg.attachments.length) {
      right.appendChild(renderAttachmentTray(msg.attachments));
    }
    if (msg.parentId) li.dataset.parentId = msg.parentId;
    attachActions(li);
    return li;
  };

  const appendThreadReply = (msg) => {
    if (!threadPanel || threadPanel.hidden) return;
    if (msg.parentId !== openThreadId) return;
    // De-dupe: the sender appends optimistically from the HTTP response and the WS
    // broadcast follows. Whichever arrives second is a no-op.
    if (threadRepliesEl.querySelector('[data-id="' + msg.id + '"]')) return;
    threadRepliesEl.appendChild(renderThreadMessage(msg, false));
    threadRepliesEl.scrollTop = threadRepliesEl.scrollHeight;
  };

  threadComposerForm?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!openThreadId) return;
    const body = threadInput.value.trim();
    if (!body) return;
    const res = await fetch('/api/messages/' + openThreadId + '/replies', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ body })
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert('Reply failed: ' + (err.error || res.statusText));
      return;
    }
    // Render the reply locally from the HTTP response so the sender sees it instantly,
    // independent of WS round-trip timing. appendThreadReply de-dupes by id, so the
    // broadcast that arrives moments later is a no-op.
    const dto = await res.json().catch(() => null);
    if (dto) appendThreadReply(dto);
    threadInput.value = '';
    threadInput._autoResize?.();
  });
  // Enter-to-send is handled by the top-level document keydown handler.
  wireAutoResize(threadInput);

  // Live markdown preview for the thread reply composer — same /api/preview path the
  // channel composer uses so the rendered HTML is identical.
  (function wireThreadPreview() {
    const pane = document.getElementById('thread-preview');
    const body = document.getElementById('thread-preview-body');
    if (!pane || !body || !threadInput) return;
    let debounce = null;
    let req = 0;
    async function refresh() {
      const text = threadInput.value;
      if (!text.trim()) {
        pane.hidden = true;
        body.innerHTML = '';
        return;
      }
      const myReq = ++req;
      try {
        const res = await fetch('/api/preview', {
          method: 'POST',
          headers: headers(),
          body: JSON.stringify({ body: text }),
        });
        if (!res.ok) return;
        const data = await res.json();
        if (myReq !== req) return;
        body.innerHTML = data.html || '';
        highlightCode(body);
        pane.hidden = !data.html;
      } catch (_) { /* leave previous render */ }
    }
    threadInput.addEventListener('input', () => {
      clearTimeout(debounce);
      debounce = setTimeout(refresh, 220);
    });
    threadComposerForm?.addEventListener('submit', () => {
      clearTimeout(debounce);
      pane.hidden = true;
      body.innerHTML = '';
    });
  })();

  // Tutorial overlay + sidebar filter were moved to ./chrome.js — see chrome.init() at the
  // top of this file. They were structurally independent of the message-feed code in here.
