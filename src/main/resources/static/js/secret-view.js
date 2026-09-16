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
 * /s/{id} (secret-view.html): open a one-time secret.
 *
 * The page arrives knowing nothing — the server renders the same shell for every id — and works out
 * what to show from the link itself:
 *
 *   1. The key comes from the #fragment, or from sessionStorage when this tab has just come back
 *      from signing in, or from the person pasting it ("send the key separately").
 *   2. POST /api/secrets/{id}/status with the verifier derived from it. That says whether the secret
 *      still waits, who shared it, and whether this visitor must sign in first. Nothing is consumed.
 *   3. Reveal is a click, never a page load, so a link scanner that loads the page cannot burn it.
 *      POST /api/secrets/{id}/open consumes it on the server; the ciphertext is decrypted here.
 *   4. The plaintext is on screen until a deadline (at most five minutes), until the tab is hidden,
 *      or until "Hide now" — then the node is emptied and every reference dropped.
 *
 * Signing in: the page stashes the key in sessionStorage and navigates to /s/{id}/sign-in WITHOUT
 * the fragment. That route is refused while signed out, which is what makes Spring remember it
 * across the Keycloak round-trip; a fragment on it would ride along through every redirect into
 * Keycloak's own URLs. The stash is removed the moment it is read back.
 *
 * Everything is textContent; the one dialog is static markup in the template.
 *
 * Promise chains rather than async/await, and every step ends in one synchronous block that does all
 * of that step's DOM work: an await is a point where the function stops and the browser recalculates
 * style and layout, so work spread over several awaits is several passes. For the same reason the
 * countdown bar is a CSS animation (the compositor runs it, at no cost per tick) and the text is
 * rewritten once a second, and nothing here reads a layout property such as offsetWidth, which would
 * force a layout in the middle of a function.
 */
(function () {
  const root = document.getElementById('secret-view');
  if (!root) return;

  const match = /^\/s\/([A-Za-z0-9_-]{22})\/?$/.exec(location.pathname);
  if (!match) return;
  const id = match[1];
  const STASH_KEY = 'ichat.secret.key.' + id;
  // Set once this tab has revealed the secret. Holds no secret material — only that the id was
  // opened here — so a reload, which no longer has the key in its URL, says "gone" rather than
  // asking for a key that would only lead to the same answer.
  const OPENED_KEY = 'ichat.secret.opened.' + id;

  const $ = (elId) => document.getElementById(elId);
  const panels = ['sv-loading', 'sv-unsupported', 'sv-key-prompt', 'sv-ready', 'sv-signin',
    'sv-revealed', 'sv-wiped', 'sv-gone', 'sv-notfound', 'sv-error'];
  const show = (panelId) => panels.forEach((p) => { $(p).hidden = p !== panelId; });

  const fail = (text) => {
    $('sv-error-text').textContent = text;
    show('sv-error');
  };

  if (!SecretCrypto.available()) {
    show('sv-unsupported');
    return;
  }

  // ---------- 1. The key ----------

  let keyText = null;
  let keyFromPrompt = false;

  const readStash = () => {
    try {
      const stashed = sessionStorage.getItem(STASH_KEY);
      sessionStorage.removeItem(STASH_KEY);
      return stashed;
    } catch (e) {
      return null;
    }
  };

  const fromFragment = location.hash.length > 1 ? location.hash.slice(1) : null;
  const fromStash = readStash();
  if (fromFragment && SecretCrypto.parseKey(fromFragment)) {
    keyText = fromFragment;
  } else if (fromStash && SecretCrypto.parseKey(fromStash)) {
    keyText = fromStash;
    // Back from signing in: put the key back where a reload would look for it. It is stripped again
    // the moment the secret is revealed.
    history.replaceState(null, '', location.pathname + '#' + keyText);
  }

  // ---------- 2. Status ----------

  const call = (action, verifier) => fetch('/api/secrets/' + id + '/' + action, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ verifier: verifier }),
    cache: 'no-store',
    credentials: 'same-origin',
    // Never tell anything downstream where this request came from.
    referrerPolicy: 'no-referrer',
  });

  const GONE_TEXT = {
    OPENED: (at) => 'It was already opened' + (at ? ' ' + ChatTime.formatDateTime(at) : '')
        + '. If that wasn\'t you, tell the person who shared it: someone else had the link, and the secret should be changed.',
    EXPIRED: (at) => 'Its link expired' + (at ? ' ' + ChatTime.formatDateTime(at) : '') + ' without being opened.',
    REVOKED: (at) => 'The person who shared it withdrew it' + (at ? ' ' + ChatTime.formatDateTime(at) : '') + '.',
  };

  const showGone = (res) => res.json().then((body) => body, () => ({})).then((body) => {
    const describe = GONE_TEXT[body.state];
    $('sv-gone-text').textContent = describe ? describe(body.endedAt) : '';
    show('sv-gone');
  });

  let verifier = null;

  const checkStatus = () => {
    show('sv-loading');
    return SecretCrypto.verifierFor(keyText)
        .then((v) => {
          verifier = v;
          return call('status', verifier);
        })
        .then((res) => {
          if (res.status === 200) {
            return res.json().then((body) => {
              // One block: creator, expiry and the panel swap are a single style and layout pass.
              $('sv-creator').textContent = body.creatorName || 'Someone';
              $('sv-minutes').textContent =
                  String(Math.max(1, Math.round((Number(body.displaySeconds) || 300) / 60)));
              $('sv-expiry').textContent = body.expiresAt
                  ? 'If nobody opens it, the link expires ' + ChatTime.formatDateTime(body.expiresAt) + '.'
                  : '';
              show('sv-ready');
              $('sv-reveal').focus();
            });
          }
          if (res.status === 401) {
            show('sv-signin');
          } else if (res.status === 404) {
            if (keyFromPrompt) {
              askForKey('That key doesn\'t match this secret. Check it and try again.');
            } else {
              show('sv-notfound');
            }
          } else if (res.status === 410) {
            return showGone(res);
          } else if (res.status === 429) {
            fail('Too many attempts. Wait a minute and reload.');
          } else {
            fail('Something went wrong (' + res.status + '). Reload to try again.');
          }
          return null;
        })
        .catch(() => { fail('Could not reach the server. Check your connection and reload.'); });
  };

  const askForKey = (error) => {
    show('sv-key-prompt');
    const errEl = $('sv-key-error');
    errEl.textContent = error || '';
    errEl.hidden = !error;
    $('sv-key').focus();
  };

  $('sv-key-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = $('sv-key');
    const candidate = input.value.trim().replace(/^#/, '');
    if (!SecretCrypto.parseKey(candidate)) {
      askForKey('That doesn\'t look like a key. It is 43 letters, digits, dashes and underscores.');
      return;
    }
    input.value = '';
    keyText = candidate;
    keyFromPrompt = true;
    checkStatus();
  });

  $('sv-signin-btn').addEventListener('click', () => {
    try {
      sessionStorage.setItem(STASH_KEY, keyText);
    } catch (e) {
      // No sessionStorage (a locked-down browser). The key prompt after sign-in is the fallback.
    }
    // The pathname only — see the note at the top of this file.
    location.assign('/s/' + id + '/sign-in');
  });

  // ---------- 3. Reveal ----------

  let plaintext = null;
  let deadline = 0;
  let ticker = null;

  $('sv-reveal').addEventListener('click', (e) => {
    const button = e.currentTarget;
    button.disabled = true;
    let reachedServer = false;
    call('open', verifier)
        .then((res) => {
          reachedServer = true;
          if (res.status === 200) {
            return res.json().then((body) => SecretCrypto.openPayload(body.payload, keyText).then(
                (text) => {
                  plaintext = text;
                  keyText = null;
                  verifier = null;
                  // The link is spent; take its key out of the address bar and this history entry.
                  history.replaceState(null, '', location.pathname);
                  try { sessionStorage.setItem(OPENED_KEY, '1'); } catch (err) { /* only a nicer reload */ }
                  reveal(Math.max(1, Math.min(300, Number(body.displaySeconds) || 300)));
                },
                () => {
                  keyText = null;
                  verifier = null;
                  fail('The secret was opened but could not be decrypted with this key. '
                      + 'Ask the person who shared it for a new link.');
                }));
          }
          if (res.status === 401) {
            show('sv-signin');
          } else if (res.status === 410) {
            return showGone(res);
          } else if (res.status === 404) {
            show('sv-notfound');
          } else if (res.status === 429) {
            button.disabled = false;
            fail('Too many attempts. Wait a minute and reload.');
          } else {
            button.disabled = false;
            fail('Something went wrong (' + res.status + '). The secret was not opened; reload to try again.');
          }
          return null;
        })
        .catch(() => {
          if (!reachedServer) {
            button.disabled = false;
            fail('Could not reach the server. The secret was not opened; reload to try again.');
          }
        });
  });

  const formatRemaining = (ms) => {
    const s = Math.max(0, Math.ceil(ms / 1000));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  };

  /** One text node, once a second. The bar beside it is a CSS animation and costs nothing here. */
  const tick = () => {
    const left = deadline - Date.now();
    if (left <= 0) {
      wipe();
      return;
    }
    $('sv-countdown').textContent = 'Disappears in ' + formatRemaining(left);
  };

  const reveal = (seconds) => {
    deadline = Date.now() + seconds * 1000;
    // Everything the reveal changes, in one go: text, timer, banner and the panel swap are a single
    // style and layout pass rather than one per property.
    $('sv-secret').textContent = plaintext;
    $('sv-copy-note').hidden = true;
    $('sv-countdown').textContent = 'Disappears in ' + formatRemaining(seconds * 1000);
    // The bar empties by CSS over exactly the display time. Animating width from JS meant a style
    // and layout pass per frame written by hand; this one the browser runs on its own. The panel is
    // hidden until now, so the animation starts here — no offsetWidth read to restart it, which
    // would force a layout mid-function.
    $('sv-timer-fill').style.animationDuration = seconds + 's';
    $('sv-warning').classList.add('is-flashing');
    show('sv-revealed');
    // A deadline, not a count of ticks: a hidden tab's timers are throttled, so counting would run
    // slow. Every tick compares against the clock.
    ticker = setInterval(tick, 1000);
  };

  // ---------- 4. Wipe ----------

  const wipe = () => {
    if (ticker) clearInterval(ticker);
    ticker = null;
    const node = $('sv-secret');
    if (node) node.textContent = '';
    plaintext = null;
    keyText = null;
    verifier = null;
    closeWhy();
    show('sv-wiped');
  };

  $('sv-hide').addEventListener('click', wipe);

  $('sv-copy').addEventListener('click', () => {
    if (plaintext == null || !navigator.clipboard) return;
    navigator.clipboard.writeText(plaintext).then(() => { $('sv-copy-note').hidden = false; }, () => {});
  });

  // Leaving the tab ends the display: the promise on the page is "or until you leave the tab".
  document.addEventListener('visibilitychange', () => {
    if (plaintext != null && document.visibilityState === 'hidden') wipe();
  });
  // pagehide covers closing, navigating away and entering the back/forward cache; a page restored
  // from that cache must come back wiped, not showing the secret.
  window.addEventListener('pagehide', () => {
    if (plaintext != null || !$('sv-revealed').hidden) wipe();
  });
  window.addEventListener('pageshow', (e) => {
    if (e.persisted && plaintext == null && !$('sv-revealed').hidden) wipe();
  });

  // ---------- "Why this matters" ----------

  const dialog = $('sv-why-dialog');
  let returnFocus = null;

  const onDialogKey = (e) => {
    if (e.key === 'Escape') {
      // Capture phase + stopPropagation: one Escape closes one layer.
      e.stopPropagation();
      closeWhy();
    }
  };

  const openWhy = () => {
    returnFocus = document.activeElement;
    dialog.hidden = false;
    document.addEventListener('keydown', onDialogKey, true);
    $('sv-why-close').focus();
  };

  function closeWhy() {
    if (dialog.hidden) return;
    dialog.hidden = true;
    document.removeEventListener('keydown', onDialogKey, true);
    if (returnFocus && document.contains(returnFocus)) returnFocus.focus();
    returnFocus = null;
  }

  $('sv-why').addEventListener('click', openWhy);
  $('sv-why-close').addEventListener('click', closeWhy);
  $('sv-why-ok').addEventListener('click', closeWhy);
  dialog.addEventListener('click', (e) => { if (e.target === dialog) closeWhy(); });

  // ---------- Go ----------

  const openedHere = () => {
    try { return sessionStorage.getItem(OPENED_KEY) === '1'; } catch (e) { return false; }
  };

  if (keyText) {
    checkStatus();
  } else if (openedHere()) {
    show('sv-wiped');
  } else {
    askForKey('');
  }
})();
