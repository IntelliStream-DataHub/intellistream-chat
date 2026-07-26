#!/usr/bin/env python3
"""
Regenerate the website's screenshot carousel from a running instance.

The screenshots on the site are the product's only honest description of itself, and they rot
silently: a feature ships, the prose gets updated, and the pictures keep showing the app as it
was two releases ago. Nobody notices, because nobody diffs a screenshot. So this exists to make
refreshing them a command rather than an afternoon.

    ICHAT_BASE=http://localhost:8080 python3 website/shots/capture.py

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
INDEX = REPO / "website" / "index.html"
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


def seed(ctx):
    """Make sure the instance has something worth photographing. Safe to re-run."""
    me = _token(USER)
    status, channels = _api(me, "/api/channels")
    if status != 200 or not channels:
        print("  ! no channels visible — seed the instance first", file=sys.stderr)
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

    # A file, so the file manager has a row.
    status, files = _api(me, "/api/files")
    if status == 200 and not files.get("files"):
        _api(me, f"/api/channels/{channel_id}/attachments", "POST",
             raw=b"screenshot fixture\n" * 400,
             headers={"Content-Type": "application/octet-stream",
                      "X-Upload-Filename": urllib.parse.quote("release-notes.txt"),
                      "X-Upload-Caption": urllib.parse.quote("Draft release notes, comments welcome")})

    # A direct conversation, so the sidebar and the DM shots are not empty.
    _api(me, "/api/conversations/direct", "POST", body={"username": SECOND_USER})

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


SHOTS = [
    ("channel", shot_channel,
     "A channel, with the sidebar showing the channels you actually use rather than every channel that exists."),
    ("thread", shot_thread,
     "Threads open beside the conversation instead of burying replies inside it."),
    ("search", shot_search,
     "One search box across every channel and every direct message you can read, served by an embedded Lucene index."),
    ("poll", shot_poll,
     "Polls are built in a dialog — or typed as a slash command, if that is faster for you."),
    ("new-conversation", shot_new_conversation,
     "Start a direct message or a group from the same place: one name is a DM, more than one is a group."),
    ("files", shot_files,
     "Every file you have uploaded, in one place, searchable — and deleting one leaves the message that posted it standing."),
    ("about", shot_about,
     "The About dialog: version, build time, runtime and the exact component versions you are running."),
    ("admin", shot_admin,
     "The admin console: suspend an account and close its live sessions, clear or restore someone's messages, set per-person storage quotas, and an append-only audit trail."),
    ("profile", shot_profile,
     "Per-device notification settings: mentions and direct messages can make a sound, and you decide per browser."),
]


# ------------------------------------------------------------------------- encoding ----

def to_webp_base64(png_bytes: bytes, quality: str = WEBP_QUALITY) -> str:
    """PNG → WebP via cwebp. Pillow is not installed on the dev box; cwebp is the reference encoder."""
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "s.png", Path(tmp) / "d.webp"
        src.write_bytes(png_bytes)
        subprocess.run(["cwebp", "-quiet", "-q", quality, str(src), "-o", str(dst)], check=True)
        return base64.b64encode(dst.read_bytes()).decode()


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


async def doc_admin(page, ctx):
    await page.goto(f"{BASE}/admin", wait_until="domcontentloaded")
    await page.wait_for_timeout(1400)
    return await page.query_selector("main") or await page.query_selector("body")


# (docs.html section id, recipe, caption)
DOC_SHOTS = [
    ("sidebar", doc_sidebar,
     "The sidebar is a shortlist — your largest and most active channels, then direct messages — not every channel that exists."),
    ("messages", doc_composer,
     "The composer: Markdown with a formatting toolbar, and a live preview of what you are about to send."),
    ("threads", doc_threads,
     "A thread opens in a panel beside the conversation, so replies stay together without burying the channel."),
    ("dms", doc_dms,
     "Starting a conversation: one name opens a direct message, more than one creates a group and asks for a name."),
    ("search", doc_search,
     "Search spans every channel and every conversation you can read; each hit says where it came from."),
    ("polls", doc_poll_builder,
     "Polls are built in a dialog, or typed as a slash command — both produce the same poll."),
    ("files", doc_files,
     "Your files: everything you have uploaded, searchable by name, with the storage it accounts for."),
    ("admin", doc_admin,
     "The admin console: permissions, moderation and an append-only audit trail."),
]


def rewrite_docs(entries):
    """Insert (or replace) one figure per documented section in docs.html."""
    path = REPO / "website" / "docs.html"
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
    print(f"  wrote {len(entries)} figures into website/docs.html")


async def capture_docs(page, ctx):
    out = []
    for section_id, recipe, caption in DOC_SHOTS:
        try:
            el = await recipe(page, ctx)
            if el is None:
                print(f"  ! doc shot {section_id}: nothing to capture", file=sys.stderr)
                return None
            png = (await page.screenshot(clip=el)) if isinstance(el, dict) else (await el.screenshot())
            out.append((section_id, caption, to_webp_base64(png, DOC_QUALITY)))
            print(f"  doc figure {section_id:10} {len(png) // 1024:>5} KB png")
        except Exception as e:
            print(f"  ! doc shot {section_id} failed: {e}", file=sys.stderr)
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
        browser = await pw.chromium.launch()
        page = await browser.new_page(viewport=VIEWPORT)
        await page.goto(f"{BASE}/channels", wait_until="domcontentloaded")
        if "realms" in page.url:
            await page.fill("#username", USER)
            await page.fill("#password", USER)
            await page.click("input[type=submit], button[type=submit]")
            await page.wait_for_load_state("domcontentloaded")

        for name, recipe, caption in SHOTS:
            try:
                clip = await recipe(page, ctx)
                png = await page.screenshot(clip=clip) if clip else await page.screenshot()
                captured.append((name, caption, to_webp_base64(png)))
                print(f"  captured {name:17} {len(png) // 1024:>5} KB png")
            except Exception as e:
                # One broken recipe must not silently drop a slide from the site: fail loudly and
                # leave index.html alone, rather than publishing a carousel missing a feature.
                print(f"  ! {name} failed: {e}", file=sys.stderr)
                await browser.close()
                return 1
        doc_figures = await capture_docs(page, ctx)
        await browser.close()

    if doc_figures is None:
        print("  ! doc figures incomplete — nothing written", file=sys.stderr)
        return 1
    rewrite_index(captured)
    rewrite_docs(doc_figures)
    print(f"\n  wrote {len(captured)} shots into {INDEX.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
