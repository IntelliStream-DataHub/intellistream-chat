#!/usr/bin/env python3
"""
Regenerate the public site's screenshot carousel from a running instance.

The site lives in docs/ because that is one of the two directories GitHub Pages will serve
from a branch; docs/index.html is the landing page and docs/docs.html is the manual.

The screenshots on the site are the product's only honest description of itself, and they rot
silently: a feature ships, the prose gets updated, and the pictures keep showing the app as it
was two releases ago. Nobody notices, because nobody diffs a screenshot. So this exists to make
refreshing them a command rather than an afternoon.

    ICHAT_BASE=http://localhost:8080 python3 docs/shots/capture.py

What it does:
  1. logs into a running instance as a normal user
  2. seeds whatever each shot needs, idempotently, so a fresh database produces the same pictures
  3. captures each shot at a fixed viewport
  4. encodes to WebP with cwebp and rewrites the <figure class="shot"> blocks in index.html

Adding a feature means adding a SHOTS entry, not editing HTML: the captions, the alt text and
the slide order all come from this file, and the carousel dots are generated from the slide
count at runtime, so nothing else has to be kept in step.

Requirements: playwright (python), cwebp (libwebp-tools). Both are already present on the dev
box; see .claude/skills/website-shots/SKILL.md for the full playbook.

The call shots additionally need a **TURN server and calls configured** on the instance —
`podman compose --profile calls up -d`, with ICHAT_TURN_URLS and ICHAT_TURN_SECRET set for the
app. They place a real call between two browser contexts, because a screenshot of a call is the
one picture that would be a lie if the feature did not work. Without TURN the run stops and says
so rather than quietly publishing a carousel with a slide missing.
"""

import asyncio
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

from playwright.async_api import async_playwright

REPO = Path(__file__).resolve().parents[2]
INDEX = REPO / "docs" / "index.html"
BASE = os.environ.get("ICHAT_BASE", "http://localhost:8080").rstrip("/")
KEYCLOAK = os.environ.get("ICHAT_KEYCLOAK", BASE.replace(":8080", ":8081")).rstrip("/")
USER = os.environ.get("ICHAT_USER", "alice")
SECOND_USER = os.environ.get("ICHAT_USER2", "bob")

# The carousel is sized for this; changing it means re-checking the width/height attributes
# written into the <img> tags below.
VIEWPORT = {"width": 1200, "height": 733}
WEBP_QUALITY = "80"


# --------------------------------------------------------------------------- seeding ----
# Everything a shot needs is created through the public API, so a picture can never show a state
# the application cannot actually reach.

def _token(username: str) -> str:
    secret = json.loads((REPO / "keycloak" / "realm.json").read_text())
    secret = next(c["secret"] for c in secret["clients"] if c["clientId"] == "ichat-client")
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "ichat-client", "client_secret": secret,
        "username": username, "password": username,
    }).encode()
    url = f"{KEYCLOAK}/realms/ichat-realm/protocol/openid-connect/token"
    return json.load(urllib.request.urlopen(url, data))["access_token"]


def _api(token: str, path: str, method: str = "GET", body=None, raw=None, headers=None):
    hdrs = {"Authorization": "Bearer " + token}
    if headers:
        hdrs.update(headers)
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text.strip() else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


# The demo workspace, built from nothing if the instance is empty.
#
# This used to assume somebody had already put realistic conversation into the database by hand,
# which made the screenshots depend on the state of one developer's machine — and that state
# accumulates. The published carousel has previously shown a workspace with duplicate suffixed test
# accounts in the mention list and channels named after individual football matches. Building the
# room here instead means `capture.py` is the whole recipe: wipe the database, start the app, run
# this, and the pictures are the same pictures.
#
# The content is written to be worth photographing. Captions promise Markdown, code, threads,
# reactions and polls, so the seeded conversation contains all of them, and it reads like a team
# talking rather than like "test message 1".

DEMO_CHANNELS = [
    ("engineering", "Backend, infra, and the occasional incident"),
    ("design", "Interface work, critique, and the design system"),
    ("general", "Everything that is not about work"),
]

DEMO_CONVERSATION = [
    (USER, "Morning. The write-behind batching landed on main last night, "
           "**17,066 messages/second** on the bench box."),
    (USER, "Numbers are in `scalability.md` if anyone wants the method. Short version: the "
           "ceiling was a mis-wired STOMP executor, not the database."),
    (SECOND_USER, "That executor bug is brutal. One thread for the whole server and every metric "
                  "looks fine."),
    (USER, "```java\n@Override\npublic void configureClientInboundChannel(ChannelRegistration r) {\n"
           "    r.executor(stompInboundExecutor());\n}\n```"),
    (SECOND_USER, f"Nice work @{USER} — that one was hiding well."),
    (USER, "Thanks! Next up is getting the load generator off-box so the 150k number means "
           "something."),
]


def _seed_workspace(me):
    """Create the demo channels and conversation. Idempotent: re-running adds nothing."""
    status, channels = _api(me, "/api/channels")
    existing = {c["name"] for c in channels} if status == 200 and channels else set()
    for name, description in DEMO_CHANNELS:
        if name not in existing:
            _api(me, "/api/channels", "POST",
                 body={"name": name, "description": description, "type": "PUBLIC"})
    status, channels = _api(me, "/api/channels")
    by_name = {c["name"]: c["id"] for c in channels} if status == 200 else {}
    room = by_name.get("engineering")
    if room is None:
        return None

    # The second user has to be a member before they can post, and both have to be members for
    # @-mentions and the member list to look like a real room.
    _api(me, f"/api/channels/{room}/invite", "POST", body={"username": SECOND_USER})
    other = _token(SECOND_USER)
    _api(other, f"/api/channels/{room}/join", "POST")

    # Each piece checks for itself rather than one count standing in for all of them. A single
    # "enough messages?" guard skipped the reaction and the poll on any instance that had a
    # conversation but not those — which is how a channel ended up photographed with the literal
    # text "/poll Offsite venue? | a | b" sitting in it where the poll widget should have been.
    status, msgs = _api(me, f"/api/channels/{room}/messages")
    have_conversation = status == 200 and len(msgs) >= len(DEMO_CONVERSATION)
    if have_conversation:
        _seed_poll(me, room, msgs)
        return room
    for author, body in DEMO_CONVERSATION:
        token = me if author == USER else other
        _api(token, f"/api/channels/{room}/messages", "POST", body={"body": body})

    # A reaction, so the captions that mention them are not writing cheques the picture cannot
    # cash. Authors may react to their own messages, but a reaction from the other person is the
    # honest illustration of what a reaction is for.
    status, msgs = _api(me, f"/api/channels/{room}/messages")
    if status == 200 and msgs:
        _api(other, f"/api/messages/{msgs[0]['id']}/reactions", "POST", body={"emoji": "🎉"})

    status, msgs = _api(me, f"/api/channels/{room}/messages")
    _seed_poll(me, room, msgs if status == 200 else [])
    return room


POLL_COMMAND = "/poll Offsite venue in May? | The barn | The lighthouse | Somewhere with wifi"


def _seed_poll(me, room, msgs):
    """Post the demo poll unless it is already there. Idempotent on the poll specifically."""
    if any(m.get("poll") for m in msgs):
        return
    _api(me, f"/api/channels/{room}/messages", "POST", body={"body": POLL_COMMAND})


def seed(ctx):
    """Make sure the instance has something worth photographing. Safe to re-run."""
    me = _token(USER)
    status, channels = _api(me, "/api/channels")
    if status != 200:
        print(f"  ! cannot read channels ({status}) — is the app up?", file=sys.stderr)
        return None
    if not channels:
        print("  empty instance — building the demo workspace")
    _seed_workspace(me)
    status, channels = _api(me, "/api/channels")
    if status != 200 or not channels:
        print("  ! no channels visible after seeding", file=sys.stderr)
        return None
    # The busiest channel, not the first one. The captions promise threads, reactions, markdown
    # and code; pointing the camera at whichever channel sorts first produces a screenshot of an
    # empty room under a caption describing a full one, which is worse than an out-of-date
    # picture because it reads as the product being empty.
    best, best_count = channels[0]["id"], -1
    for c in channels:
        st, msgs = _api(me, f"/api/channels/{c['id']}/messages")
        n = len(msgs) if st == 200 and isinstance(msgs, list) else 0
        if n > best_count:
            best, best_count = c["id"], n
    channel_id = best
    print(f"  using channel {channel_id} ({best_count} messages)")

    # Files, so neither file page photographs as one row in an empty table. A channel that has
    # had a project run through it accumulates a handful of these; one lonely attachment under a
    # caption about browsing a channel's files reads as a feature nobody uses.
    #
    # Names carry their own weight in a screenshot: they are the only thing in the table a reader
    # can actually read, so they say what a working team shares rather than "file1.bin".
    DEMO_FILES = [
        ("release-notes.txt", "text/plain", 7800,
         "Draft release notes, comments welcome"),
        ("load-test-results.csv", "text/csv", 24000,
         "Raw numbers behind the 17k/s figure — one row per run"),
        ("executor-config.patch", "text/x-patch", 3200,
         "The one-line fix for the inbound channel executor"),
        ("onboarding-checklist.md", "text/markdown", 5100,
         "New-joiner checklist, updated after last week's session"),
    ]
    status, files = _api(me, "/api/files")
    have = {f.get("filename") for f in (files.get("files") or [])} if status == 200 else set()
    for name, ctype, size, caption in DEMO_FILES:
        if name in have:
            continue
        _api(me, f"/api/channels/{channel_id}/attachments", "POST",
             raw=b"screenshot fixture\n" * (size // 19),
             headers={"Content-Type": "application/octet-stream",
                      "X-Upload-Filename": urllib.parse.quote(name),
                      "X-Upload-Caption": urllib.parse.quote(caption)})

    # A direct conversation, so the sidebar and the DM shots are not empty. Its id is kept
    # because the call shot needs somewhere with a call button, and calls are 1:1 only.
    status, dm = _api(me, "/api/conversations/direct", "POST", body={"username": SECOND_USER})
    ctx["dm"] = dm["id"] if status == 200 and isinstance(dm, dict) else None

    # A thread written for the manual. Without this the threads figure illustrates whatever
    # thread happens to exist, which on a working instance is somebody's half-finished test —
    # a screenshot explaining threads should not need the reader to ignore most of it.
    ctx["thread_parent"] = _ensure_thread(me, channel_id)
    return channel_id


THREAD_MARKER = "Deploy window for 1.1"


def _ensure_thread(me, channel_id):
    """Find the demo thread or create it. Idempotent: re-running does not add a second copy."""
    status, msgs = _api(me, f"/api/channels/{channel_id}/messages")
    if status == 200 and isinstance(msgs, list):
        for m in msgs:
            if THREAD_MARKER in (m.get("bodyMarkdown") or ""):
                return m["id"]
    status, parent = _api(me, f"/api/channels/{channel_id}/messages", "POST",
                          body={"body": THREAD_MARKER + " — proposing Thursday 09:00 UTC, "
                                        "which misses the Friday freeze. Objections?"})
    if status != 200:
        return None
    pid = parent["id"]
    other = _token(SECOND_USER)
    _api(other, f"/api/messages/{pid}/replies", "POST",
         body={"body": "Thursday works. I will have the migration reviewed by Wednesday."})
    _api(me, f"/api/messages/{pid}/replies", "POST",
         body={"body": "Booked. I will post the runbook here the evening before."})
    return pid


# ----------------------------------------------------------------------------- shots ----
# Each recipe positions the page and returns a clip dict (or None for the whole viewport).

async def shot_channel(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1500)
    # Frame something representative rather than whatever happens to be at the bottom. A channel
    # opens at its tail, and the tail of a development instance is usually the last thing someone
    # was testing — which then becomes the first thing a visitor sees of the product.
    await page.evaluate("""() => {
        const rich = document.querySelector('#messages pre, #messages .message-reactions');
        (rich || document.querySelector('#messages li.message'))
            ?.scrollIntoView({ block: 'center' }); }""")
    await page.wait_for_timeout(700)
    return None


async def shot_thread(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    opened = await page.evaluate("""() => {
        const b = document.querySelector('.thread-indicator')
             || document.querySelector('[data-action=reply]');
        if (!b) return false; b.click(); return true; }""")
    if not opened:
        return None
    await page.wait_for_timeout(1200)
    return None


async def shot_search(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    await page.fill("#global-search-input", "the")
    try:
        await page.wait_for_selector(".search-dropdown-row", timeout=6000)
    except Exception:
        pass
    await page.wait_for_timeout(500)
    return None


async def shot_about(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    await page.hover(".topbar .me")
    await page.wait_for_timeout(600)
    await page.evaluate("""() => {
        const i = [...document.querySelectorAll('[role=menuitem]')]
            .find(e => /about/i.test(e.textContent));
        if (i) i.click(); }""")
    await page.wait_for_timeout(1200)
    return None


async def shot_admin(page, ctx):
    await page.goto(f"{BASE}/admin", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    return None


async def shot_files(page, ctx):
    await page.goto(f"{BASE}/files", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    return None


async def shot_poll(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1300)
    await page.click("#composer-poll")
    await page.wait_for_selector(".poll-modal", timeout=5000)
    await page.fill(".poll-question-input", "Where should the offsite be?")
    inputs = await page.query_selector_all(".poll-option-input")
    if len(inputs) >= 2:
        await inputs[0].fill("Lisbon")
        await inputs[1].fill("Tallinn")
    await page.wait_for_timeout(400)
    return None


async def shot_new_conversation(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    await page.click("#sidebar-dm-btn")
    await page.wait_for_timeout(400)
    await page.fill('#sidebar-dm-popover input[name="members"]', f"{SECOND_USER}, carol")
    await page.wait_for_timeout(400)
    return None


async def shot_profile(page, ctx):
    await page.goto(f"{BASE}/profile", wait_until="domcontentloaded")
    await page.wait_for_timeout(1000)
    await page.evaluate("() => document.getElementById('notification-sound')?.scrollIntoView({block:'center'})")
    await page.wait_for_timeout(400)
    return None


async def shot_mention(page, ctx):
    """The @-typeahead. Mentions used to need an exact username while the UI showed display
    names, so a mistyped handle notified nobody and said nothing — this is the fix, and it
    matches display names as well as handles."""
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    await page.click("#composer-input")
    # A bare "@" rather than "@a": the list then leads with the people actually in the room and
    # the broadcast handles, which is what the caption promises. A letter narrows it to whoever
    # happens to match, and on a workspace with test accounts that is mostly people who are not
    # in the channel — a screenshot of the feature failing to do the thing described under it.
    await page.type("#composer-input", "Nice work on the executor fix @", delay=45)
    try:
        await page.wait_for_selector(".mention-dropdown .search-dropdown-row", timeout=6000)
    except Exception:
        pass
    await page.wait_for_timeout(500)
    return None


async def shot_search_results(page, ctx):
    """The results page: a count, a scope, and hits you can read — rather than a ten-row
    dropdown that jumps you to whichever one it guessed."""
    await page.goto(f"{BASE}/search?q=the", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    return None


async def shot_channel_settings(page, ctx):
    """Channel administration: rename, edit the description, archive — and leave."""
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    await page.evaluate("() => document.getElementById('channel-admin-cog')?.click()")
    await page.wait_for_timeout(900)
    return None


async def shot_channel_files(page, ctx):
    """The files shared in one channel. Finding a PDF someone posted last month used to mean
    scrolling the channel or remembering words from the message that carried it."""
    await page.goto(f"{BASE}/channels/{ctx['channel']}/files", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    return None


async def _answer_from_second_browser(page, ctx, media="audio"):
    """
    Place a call and have the other side pick it up, so the shot shows a call that is actually
    connected rather than a mocked-up panel.

    A call needs two people, so this opens a second browser context signed in as the other
    account. It is the only recipe that does — everything else in this file is one page — and it
    is worth the machinery: a screenshot of a call is the one picture that would be a lie if the
    feature did not work, and this one cannot be produced unless it does.

    Returns the peer's page so the caller can close it.
    """
    if ctx.get("dm") is None:
        raise RuntimeError("no direct conversation to call in")

    await page.goto(f"{BASE}/conversations/{ctx['dm']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1600)
    if not await page.query_selector(f'[data-call-start="{media}"]'):
        # Say why rather than timing out on a missing selector: calls are hidden until an
        # operator configures TURN, so this is a setup problem and not a broken recipe.
        raise RuntimeError(
            "no call button — the instance has no TURN server configured. Start one with "
            "`podman compose --profile calls up -d` and run the app with ICHAT_TURN_URLS "
            "and ICHAT_TURN_SECRET set.")

    peer_ctx = await page.context.browser.new_context(
        viewport=VIEWPORT, permissions=["microphone", "camera"])
    peer = await peer_ctx.new_page()
    await peer.goto(f"{BASE}/oauth2/authorization/keycloak", wait_until="domcontentloaded")
    await peer.wait_for_timeout(700)
    if "realms" in peer.url:
        await peer.fill("#username", SECOND_USER)
        await peer.fill("#password", SECOND_USER)
        await peer.click("input[type=submit], button[type=submit]")
        await peer.wait_for_load_state("domcontentloaded")
    await peer.goto(f"{BASE}/conversations/{ctx['dm']}", wait_until="domcontentloaded")
    try:
        await peer.click("#tutorial-done", timeout=2000)
    except Exception:
        pass
    await peer.wait_for_timeout(2200)  # let both STOMP sockets finish CONNECT

    await page.click(f'[data-call-start="{media}"]')
    await peer.wait_for_selector("#call-accept:visible", timeout=15000)
    await peer.click("#call-accept")
    # The timer only starts on the transport's `connected` state, so waiting for it waits for ICE
    # to complete — the picture is of a call carrying media, not of a hopeful UI.
    await page.wait_for_selector("#call-timer:visible", timeout=25000)
    await page.wait_for_timeout(2500)  # a couple of seconds on the clock reads better than 00:00
    return peer


def _purge_call_lines(ctx):
    """
    Delete the archive lines the capture's own calls left behind.

    A completed call writes "Call · 3 sec" into the conversation, which is the feature working
    correctly and is still debris here: run the capture five times and the demo DM fills with
    three-second calls that end up photographed behind the panel on the sixth. A script that
    photographs an instance must not keep changing it.
    """
    if ctx.get("dm") is None:
        return
    me = _token(USER)
    status, msgs = _api(me, f"/api/conversations/{ctx['dm']}/messages")
    if status != 200 or not isinstance(msgs, list):
        return
    for m in msgs:
        body = m.get("bodyMarkdown") or ""
        if body.startswith("_Call ·") or body == "_Missed call_":
            _api(me, f"/api/conversations/messages/{m['id']}", "DELETE")


async def _end_call(page, peer, ctx):
    """Hang up, close the peer's browser, and remove the line the call just wrote."""
    try:
        await page.click("#call-hangup")
        await page.wait_for_timeout(800)
    except Exception:
        pass
    # Closing without hanging up first leaves the peer in a call the server still believes in
    # until the disconnect hook fires, and the next recipe finds the account busy.
    await peer.context.close()
    _purge_call_lines(ctx)


# Teardown that must not run until the screenshot has been taken.
#
# A recipe cannot clean up after itself here. The picture is taken by the caller *after* the
# recipe returns, and Python runs a `finally` before the caller resumes — so hanging up in one
# photographs the panel reading "Call ended" instead of a call in progress. Anything that would
# change the page goes on this list and is drained once the shutter has closed.
_PENDING_TEARDOWN = []


async def _drain_teardown():
    while _PENDING_TEARDOWN:
        fn = _PENDING_TEARDOWN.pop()
        try:
            await fn()
        except Exception as e:
            print(f"  ! call teardown failed: {e}", file=sys.stderr)


async def shot_call(page, ctx):
    """A connected 1:1 call, seen from the caller's side, over the conversation it started in."""
    peer = await _answer_from_second_browser(page, ctx, "audio")
    _PENDING_TEARDOWN.append(lambda: _end_call(page, peer, ctx))
    return None


SHOTS = [
    # (name, recipe, caption, theme). The theme is applied before the shot, so the strip shows
    # what the app looks like in more than one skin — twenty ship, and a carousel entirely in the
    # default one undersells that. At least two light and two dark, deliberately spread rather
    # than clustered, so a visitor scrolling sees the range without being told about it.
    ("channel", shot_channel,
     "A channel, with every room you are in listed down the left and your favourites pinned to the top.",
     "default"),
    ("thread", shot_thread,
     "Threads open beside the conversation instead of burying replies inside it. Shown in Midnight — one of twenty themes.",
     "midnight"),
    ("search", shot_search,
     "One search box, scoped to the room you are reading, with the fast path still a keystroke away.",
     "default"),
    ("search-results", shot_search_results,
     "Search reaches every channel you are allowed to read, not only the ones you joined, and matches attachment filenames. from:@bob finds what someone wrote, @bob finds where they were mentioned.",
     "slate"),
    ("mention", shot_mention,
     "Typing @ offers the people in the room, matching display names as well as handles — and @channel or @here when you mean everyone.",
     "default"),
    ("poll", shot_poll,
     "Polls are built in a dialog — or typed as a slash command, if that is faster for you. Shown in Dusk.",
     "dusk"),
    ("new-conversation", shot_new_conversation,
     "Start a direct message or a group from the same place: one name is a DM, more than one is a group.",
     "teal"),
    ("call", shot_call,
     "Voice and video calls in any direct message. Media goes peer to peer through your own TURN relay — the chat server never sees it, and neither participant learns the other's IP address.",
     "default"),
    ("files", shot_files,
     "Every file you have uploaded, in one place, searchable — and deleting one leaves the message that posted it standing.",
     "carbon"),
    ("channel-files", shot_channel_files,
     "The files shared in one channel, with the message each came from a click away.",
     "default"),
    ("channel-settings", shot_channel_settings,
     "Rename a channel, edit what it is for, archive it when the project ends — or leave it.",
     "midnight"),
    ("about", shot_about,
     "The About dialog: version, build time, runtime and the exact component versions you are running.",
     "default"),
    ("admin", shot_admin,
     "The admin console: suspend an account and close its live sessions, clear or restore someone's messages, set per-person storage quotas, and an append-only audit trail.",
     "forest"),
    ("profile", shot_profile,
     "Twenty themes, five of them dark, an account-wide notification default, and per-device sounds set separately for mentions and direct messages.",
     "indigo"),
]


# --------------------------------------------------------------------------- hero ----
# The README's masthead. Unlike every other image here it is written as a real file rather than
# inlined, because GitHub's markdown sanitiser drops data: URIs — a README that inlines its
# screenshot the way docs/index.html does renders as a broken image on the project's front page.
#
# Two of them, light and dark, selected by the reader's own GitHub theme through <picture>. A
# light screenshot on a dark README is the most conspicuous way to look like you did not try.

HERO_DIR = REPO / "docs" / "shots"
HERO_QUALITY = "82"
HEROES = [
    ("hero-light.webp", shot_channel, "default"),
    ("hero-dark.webp", shot_channel, "midnight"),
]


# ------------------------------------------------------------------------- encoding ----

def to_webp_base64(png_bytes: bytes, quality: str = WEBP_QUALITY) -> str:
    """PNG → WebP via cwebp. Pillow is not installed on the dev box; cwebp is the reference encoder."""
    return base64.b64encode(to_webp_bytes(png_bytes, quality)).decode()


def to_webp_bytes(png_bytes: bytes, quality: str = WEBP_QUALITY) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "s.png", Path(tmp) / "d.webp"
        src.write_bytes(png_bytes)
        subprocess.run(["cwebp", "-quiet", "-q", quality, str(src), "-o", str(dst)], check=True)
        return dst.read_bytes()


def rewrite_index(entries):
    """Replace every <figure class="shot"> with the freshly captured set."""
    html = INDEX.read_text()
    start = html.index('<figure class="shot"')
    end = html.rindex("</figure>") + len("</figure>")
    figures = []
    for i, (name, caption, b64) in enumerate(entries):
        alt = caption.replace('"', "&quot;")
        figures.append(
            f'      <figure class="shot" id="shot-{i}">\n'
            f'        <img src="data:image/webp;base64,{b64}" alt="{alt}" loading="lazy" '
            f'width="{VIEWPORT["width"]}" height="{VIEWPORT["height"]}"/>\n'
            f'        <figcaption>{caption}</figcaption>\n'
            f'      </figure>'
        )
    INDEX.write_text(html[:start] + "\n".join(figures).lstrip() + html[end:])


# ------------------------------------------------------------------- doc figures ----
# The manual explains behaviour in prose. A picture of the thing being described is worth more
# than another paragraph, but only where the UI is the explanation — these are element crops, not
# viewport captures, because docs.html is one self-contained file and a full page each would
# triple it for no extra meaning.

DOC_QUALITY = "72"


async def doc_threads(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    pid = ctx.get("thread_parent")
    opened = await page.evaluate("""(id) => {
        const li = id ? document.querySelector(`li.message[data-id="${id}"]`) : null;
        const b = (li && (li.querySelector('.thread-indicator') || li.querySelector('[data-action=reply]')))
               || document.querySelector('.thread-indicator');
        if (!b) return false; b.click(); return true; }""", pid)
    if not opened:
        return None
    await page.wait_for_timeout(1500)
    # The panel is a full-height column and a short thread leaves most of it blank. Crop from the
    # panel's top to just under its last reply — the composer below is shown in the Messages
    # figure already, and empty space is not information.
    clip = await page.evaluate("""() => {
        const p = document.querySelector('#thread-panel');
        if (!p) return null;
        const r = p.getBoundingClientRect();
        const items = p.querySelectorAll('li.message');
        if (!items.length) return null;
        const last = items[items.length - 1].getBoundingClientRect();
        return {x: r.x, y: r.y, width: r.width, height: Math.min(r.height, last.bottom - r.y + 16)};
    }""")
    return clip


async def doc_sidebar(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1300)
    return await page.query_selector(".sidebar")


async def doc_search(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    await page.fill("#global-search-input", "the")
    try:
        await page.wait_for_selector(".search-dropdown-row", timeout=6000)
    except Exception:
        return None
    await page.wait_for_timeout(500)
    return await page.query_selector(".search-dropdown")


async def doc_polls(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    return await page.query_selector(".poll-widget")


async def doc_poll_builder(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1300)
    await page.click("#composer-poll")
    await page.wait_for_selector(".poll-modal", timeout=5000)
    await page.fill(".poll-question-input", "Where should the offsite be?")
    ins = await page.query_selector_all(".poll-option-input")
    if len(ins) >= 2:
        await ins[0].fill("Lisbon")
        await ins[1].fill("Tallinn")
    await page.wait_for_timeout(400)
    return await page.query_selector(".poll-modal")


async def doc_dms(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1200)
    await page.click("#sidebar-dm-btn")
    await page.wait_for_timeout(500)
    await page.fill('#sidebar-dm-popover input[name="members"]', f"{SECOND_USER}, carol")
    await page.wait_for_timeout(400)
    return await page.query_selector("#sidebar-dm-popover")


async def doc_files(page, ctx):
    await page.goto(f"{BASE}/files", wait_until="domcontentloaded")
    await page.wait_for_timeout(1300)
    return await page.query_selector("main") or await page.query_selector("body")


async def doc_composer(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1300)
    await page.fill("#composer-input",
                    "**Bold**, `code`, a [link](https://example.com) and a list:\n- one\n- two")
    await page.wait_for_timeout(600)
    return await page.query_selector("#composer")


async def doc_call(page, ctx):
    """The in-call panel itself, cropped to the control rather than the whole page."""
    peer = await _answer_from_second_browser(page, ctx, "audio")
    _PENDING_TEARDOWN.append(lambda: _end_call(page, peer, ctx))
    return await page.query_selector("#call-panel")


async def doc_admin(page, ctx):
    await page.goto(f"{BASE}/admin", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    return await page.query_selector("main") or await page.query_selector("body")


async def doc_notifications(page, ctx):
    await page.goto(f"{BASE}/channels/{ctx['channel']}", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    await page.click(".channel-cog")
    await page.wait_for_selector("#channel-notify-level", timeout=5000)
    await page.wait_for_timeout(400)
    return await page.query_selector("#channel-admin-dropdown")


# (docs.html section id, recipe, caption)
DOC_SHOTS = [
    ("sidebar", doc_sidebar,
     "The sidebar: every channel you are in, favourites first, then your direct messages. Bold means unread; a number means someone used your name."),
    ("messages", doc_composer,
     "The composer: Markdown with a formatting toolbar, and a live preview of what you are about to send."),
    ("threads", doc_threads,
     "A thread opens in a panel beside the conversation, so replies stay together without burying the channel."),
    ("dms", doc_dms,
     "Starting a conversation: one name opens a direct message, more than one creates a group and asks for a name."),
    ("search", doc_search,
     "Search spans every channel you are allowed to read — joined or not — and every conversation you are in. Each hit says where it came from."),
    ("polls", doc_poll_builder,
     "Polls are built in a dialog, or typed as a slash command — both produce the same poll."),
    ("calls", doc_call,
     "A call in progress. Mute and hang up are the whole of the in-call UI for an audio call; a video call adds the camera toggle and the picture."),
    ("files", doc_files,
     "Your files: everything you have uploaded, searchable by name, with the storage it accounts for."),
    ("notifications", doc_notifications,
     "Per-channel notifications. \u201cDefault\u201d inherits the account setting, so changing that moves every channel you have not overridden."),
    ("admin", doc_admin,
     "The admin console: permissions, moderation and an append-only audit trail."),
]


def rewrite_docs(entries):
    """Insert (or replace) one figure per documented section in docs.html."""
    path = REPO / "docs" / "docs.html"
    html = path.read_text()
    # Drop any figures from a previous run first, so this is idempotent rather than cumulative.
    html = re.sub(r'\n\s*<figure class="doc-shot">.*?</figure>', "", html, flags=re.S)
    for section_id, caption, b64 in entries:
        marker = f'<section class="doc" id="{section_id}">'
        start = html.find(marker)
        if start == -1:
            print(f"  ! docs section #{section_id} not found", file=sys.stderr)
            continue
        end = html.index("</section>", start)
        alt = caption.replace('"', "&quot;")
        fig = (f'\n  <figure class="doc-shot">\n'
               f'    <img src="data:image/webp;base64,{b64}" alt="{alt}" loading="lazy"/>\n'
               f'    <figcaption>{caption}</figcaption>\n'
               f'  </figure>\n')
        html = html[:end] + fig + html[end:]
    path.write_text(html)
    print(f"  wrote {len(entries)} figures into docs/docs.html")


async def capture_docs(page, ctx):
    out = []
    for section_id, recipe, caption in DOC_SHOTS:
        try:
            el = await recipe(page, ctx)
            if el is None:
                print(f"  ! doc shot {section_id}: nothing to capture", file=sys.stderr)
                return None
            png = (await page.screenshot(clip=el)) if isinstance(el, dict) else (await el.screenshot())
            await _drain_teardown()
            out.append((section_id, caption, to_webp_base64(png, DOC_QUALITY)))
            print(f"  doc figure {section_id:10} {len(png) // 1024:>5} KB png")
        except Exception as e:
            print(f"  ! doc shot {section_id} failed: {e}", file=sys.stderr)
            await _drain_teardown()
            return None
    return out


# ----------------------------------------------------------------------------- main ----

async def main():
    ctx = {}
    channel = seed(ctx)
    if channel is None:
        return 1
    ctx["channel"] = channel
    captured = []
    async with async_playwright() as pw:
        # Fake camera and microphone, and no permission prompt to answer. Only the call shots use
        # them, and without both flags that recipe hangs on a dialog no automated run can click.
        browser = await pw.chromium.launch(args=[
            "--use-fake-device-for-media-stream",
            "--use-fake-ui-for-media-stream",
        ])
        page = await browser.new_page(viewport=VIEWPORT,
                                      permissions=["microphone", "camera"])
        await page.goto(f"{BASE}/channels", wait_until="domcontentloaded")
        if "realms" in page.url:
            await page.fill("#username", USER)
            await page.fill("#password", USER)
            await page.click("input[type=submit], button[type=submit]")
            await page.wait_for_load_state("domcontentloaded")

        # Dismiss the welcome tutorial. On an instance seeded from empty this account has never
        # signed in before, so the overlay is up — and it is modal, so it silently intercepts every
        # click a recipe makes and each shot fails on a timeout with no obvious cause. Doing it once
        # here rather than in each recipe: it is a property of the session, not of any one picture.
        await page.wait_for_timeout(600)
        try:
            await page.click("#tutorial-done", timeout=2500)
            await page.wait_for_timeout(400)
        except Exception:
            pass  # Already dismissed — the normal case on a re-run.

        for name, recipe, caption, theme in SHOTS:
            try:
                clip = await recipe(page, ctx)
                # After the recipe, so a recipe that navigates cannot undo it. data-theme on
                # <body> is what the app itself toggles, so this is the real thing, not a mock.
                await page.evaluate("(t) => document.body.setAttribute('data-theme', t)", theme)
                await page.wait_for_timeout(350)
                png = await page.screenshot(clip=clip) if clip else await page.screenshot()
                # Only now is it safe to hang up — see _PENDING_TEARDOWN.
                await _drain_teardown()
                captured.append((name, caption, to_webp_base64(png)))
                print(f"  captured {name:17} {len(png) // 1024:>5} KB png")
            except Exception as e:
                # One broken recipe must not silently drop a slide from the site: fail loudly and
                # leave index.html alone, rather than publishing a carousel missing a feature.
                print(f"  ! {name} failed: {e}", file=sys.stderr)
                await browser.close()
                return 1
        doc_figures = await capture_docs(page, ctx)

        heroes = []
        for filename, recipe, theme in HEROES:
            try:
                clip = await recipe(page, ctx)
                await page.evaluate("(t) => document.body.setAttribute('data-theme', t)", theme)
                await page.wait_for_timeout(350)
                png = await page.screenshot(clip=clip) if clip else await page.screenshot()
                heroes.append((filename, to_webp_bytes(png, HERO_QUALITY)))
                print(f"  captured {filename:17} {len(png) // 1024:>5} KB png")
            except Exception as e:
                print(f"  ! {filename} failed: {e}", file=sys.stderr)
                await browser.close()
                return 1

        await browser.close()

    if doc_figures is None:
        print("  ! doc figures incomplete — nothing written", file=sys.stderr)
        return 1
    rewrite_index(captured)
    rewrite_docs(doc_figures)
    # Written last, with everything else, so a partial run never leaves the README pointing at a
    # screenshot from a different capture than the site's.
    for filename, data in heroes:
        (HERO_DIR / filename).write_bytes(data)
    print(f"\n  wrote {len(captured)} shots into {INDEX.relative_to(REPO)}")
    print(f"  wrote {len(heroes)} README heroes into {HERO_DIR.relative_to(REPO)}/")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
