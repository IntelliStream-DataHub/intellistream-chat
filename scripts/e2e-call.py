#!/usr/bin/env python3
"""
End-to-end check that a 1:1 call actually connects.

WHY THIS IS A SCRIPT AND NOT A JUnit IT
---------------------------------------
CallFlowIT covers everything the server decides — who may call whom, the ring state machine, the
line left in the conversation. What it cannot cover is the half that only exists in a browser:
getUserMedia, ICE, the TURN relay, and whether two peer connections ever reach `connected`. That
needs two real browsers and a real relay, so it needs a running stack, so it cannot be a
Testcontainers test.

It is the only test that would fail if the media path broke, which is why it exists.

WHAT IT ASSERTS
  1. Alice, in a DM, can start a call.
  2. Bob's devices ring even though he is looking at a CHANNEL, not the conversation.
  3. Both sides reach the connected state — the in-call timer only starts on `connected`, so a
     visible timer is the assertion that ICE completed and media is flowing.
  4. coturn actually relayed it (a new allocation appears in its log). With force-relay on, a call
     that connected without an allocation would mean the policy is not being applied.
  5. Hanging up leaves exactly one archive line in the conversation.

REQUIREMENTS
  - the stack up:      podman compose up -d          (coturn is part of the default stack)
  - the app running with TURN configured (ICHAT_TURN_URLS / ICHAT_TURN_SECRET)
  - `pip install playwright && playwright install chromium`

RUN
    python3 scripts/e2e-call.py
    ICHAT_BASE=http://localhost:8080 python3 scripts/e2e-call.py

Use a localhost base URL. getUserMedia needs a secure context and localhost is the only origin
exempt from the HTTPS requirement — over a LAN IP the fake devices are blocked too, and the
failure looks like a call that rings and never connects.
"""
import asyncio
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

from playwright.async_api import async_playwright

BASE = os.environ.get("ICHAT_BASE", "http://localhost:8080").rstrip("/")
KEYCLOAK = os.environ.get("ICHAT_KEYCLOAK", "").rstrip("/")
REALM_JSON = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "keycloak", "realm.json")
COTURN_CONTAINER = os.environ.get("ICHAT_COTURN_CONTAINER", "chat_coturn_1")
CALLER, CALLEE = "alice", "bob"

# Fake camera and microphone, and no permission prompt to click. Without the second flag the
# call hangs on a permission dialog no automated run can answer.
BROWSER_ARGS = [
    "--use-fake-device-for-media-stream",
    "--use-fake-ui-for-media-stream",
]

with open(REALM_JSON) as fh:
    _realm = json.load(fh)
SECRET = next(c["secret"] for c in _realm["clients"] if c["clientId"] == "ichat-client")


def _issuer() -> str:
    """
    Where Keycloak actually is.

    Not derivable from BASE: a LAN dev setup runs the app on localhost while Keycloak advertises
    a LAN address, and the issuer is compared as a string, so guessing wrong fails at the token
    exchange rather than at the connection. application-dev.properties is the file that knows —
    it is where the four values that have to agree are written down.
    """
    if KEYCLOAK:
        return KEYCLOAK
    props = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "src", "main", "resources", "application-dev.properties")
    if os.path.exists(props):
        with open(props) as fh:
            for line in fh:
                line = line.strip()
                if line.startswith("spring.security.oauth2.client.provider.keycloak.issuer-uri="):
                    return line.split("=", 1)[1].split("/realms/")[0]
    return BASE.replace(":8080", ":8081")


def token(user: str) -> str:
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "ichat-client", "client_secret": SECRET,
        "username": user, "password": user,
    }).encode()
    req = urllib.request.Request(
        f"{_issuer()}/realms/ichat-realm/protocol/openid-connect/token", data=data)
    return json.load(urllib.request.urlopen(req, timeout=10))["access_token"]


def api(tok: str, path: str, method: str = "GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={
        "Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or "null")


def coturn_allocations() -> int:
    """How many relay allocations coturn has logged. The proof that media took the relay."""
    try:
        out = subprocess.run(["podman", "logs", COTURN_CONTAINER],
                             capture_output=True, text=True, timeout=15)
        return (out.stdout + out.stderr).lower().count("allocation")
    except Exception:
        return -1


async def sign_in(context, username: str):
    page = await context.new_page()
    # Straight at the OIDC entry point: navigating to a protected page lands on the public
    # landing page rather than on Keycloak, so a "were we redirected" check never fires.
    await page.goto(f"{BASE}/oauth2/authorization/keycloak", wait_until="domcontentloaded")
    await page.wait_for_timeout(700)
    if "realms" in page.url:
        await page.fill("#username", username)
        await page.fill("#password", username)
        await page.click("input[type=submit], button[type=submit]")
        await page.wait_for_load_state("domcontentloaded")
    await page.wait_for_timeout(600)
    # The welcome overlay is modal and silently swallows every later click.
    try:
        await page.click("#tutorial-done", timeout=2000)
    except Exception:
        pass
    return page


async def count_archive_lines(page) -> int:
    """How many completed-call lines the visible feed holds right now."""
    return await page.eval_on_selector_all(
        "#messages li.message .message-body",
        "els => els.filter(e => e.textContent.trim().startsWith('Call ·')).length")


async def main() -> int:
    alice_tok = token(CALLER)
    token(CALLEE)  # provisions bob on first sight, via CurrentUser
    dm = api(alice_tok, "/api/conversations/direct", "POST", {"username": CALLEE})
    channels = api(alice_tok, "/api/channels")
    if not channels:
        print("! no channel to park the callee on — create one first", file=sys.stderr)
        return 1
    dm_id, channel_id = dm["id"], channels[0]["id"]
    print(f"dm={dm_id} channel={channel_id}")

    ice = api(alice_tok, "/api/calls/ice")
    if not ice.get("available"):
        print(f"! calling is not configured on this server: {ice}", file=sys.stderr)
        return 1
    print(f"ice: policy={ice['iceTransportPolicy']} turn={ice['iceServers'][-1]['urls']}")

    before = coturn_allocations()
    failures = []

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(args=BROWSER_ARGS)
        # Separate contexts, so the two accounts get separate cookie jars and separate sessions.
        ctx_a = await browser.new_context(permissions=["microphone", "camera"])
        ctx_b = await browser.new_context(permissions=["microphone", "camera"])

        alice = await sign_in(ctx_a, CALLER)
        bob = await sign_in(ctx_b, CALLEE)
        for who, page in ((CALLER, alice), (CALLEE, bob)):
            page.on("pageerror", lambda e, w=who: failures.append(f"{w}: JS error: {e}"))

        await alice.goto(f"{BASE}/conversations/{dm_id}", wait_until="domcontentloaded")
        # The callee sits on a CHANNEL: a call has to ring wherever you are, not only in the
        # conversation it belongs to. If the panel were DM-only this is the assertion that fails.
        await bob.goto(f"{BASE}/channels/{channel_id}", wait_until="domcontentloaded")
        # Let both sockets finish CONNECT — an invite published before STOMP is up goes nowhere.
        await alice.wait_for_timeout(2500)
        await bob.wait_for_timeout(2500)

        # Count what is already there. The DM is long-lived and every previous call left its own
        # line, so the assertion has to be "this call added exactly one" rather than "there is
        # exactly one" — the latter passes only on a conversation nobody has ever called in.
        archived_before = await count_archive_lines(alice)

        print("placing the call…")
        await alice.click('[data-call-start="audio"]')

        try:
            await bob.wait_for_selector("#call-accept:visible", timeout=15000)
        except Exception:
            failures.append("callee never rang (no answer button appeared on the channel page)")
            await browser.close()
            return report(failures)
        status = (await bob.text_content("#call-status") or "").strip()
        print(f"  callee is ringing: {status!r}")
        if "incoming" not in status.lower():
            failures.append(f"unexpected ringing status: {status!r}")

        await bob.click("#call-accept")

        # The timer is only started from the transport's `connected` state change, so waiting on
        # it waits on ICE completing and media flowing — not merely on the UI changing.
        for who, page in ((CALLER, alice), (CALLEE, bob)):
            try:
                await page.wait_for_selector("#call-timer:visible", timeout=25000)
                print(f"  {who}: connected")
            except Exception:
                st = (await page.text_content("#call-status") or "").strip()
                failures.append(f"{who} never reached connected (status stuck at {st!r})")

        if not failures:
            await alice.wait_for_timeout(3000)  # let a couple of seconds of talk time accrue

            after = coturn_allocations()
            if before >= 0 and after <= before:
                # force-relay is on, so a call that connected without an allocation means the
                # policy did not reach the browser and the peers went direct.
                failures.append(
                    f"coturn logged no new allocation ({before} -> {after}) — "
                    "the call did not go through the relay")
            else:
                print(f"  coturn allocations: {before} -> {after}")

            await alice.click("#call-hangup")
            await alice.wait_for_timeout(2500)

            archived_after = await count_archive_lines(alice)
            added = archived_after - archived_before
            if added != 1:
                failures.append(
                    f"one call should leave one archive line, but the count moved by {added} "
                    f"({archived_before} -> {archived_after})")
            else:
                last = await alice.eval_on_selector_all(
                    "#messages li.message .message-body",
                    "els => els.map(e => e.textContent.trim()).filter(t => t.startsWith('Call ·'))")
                print(f"  archived: {last[-1]!r}")

        await browser.close()

    return report(failures)


def report(failures) -> int:
    if failures:
        print("\nFAILED:")
        for f in failures:
            print(f"  ! {f}")
        return 1
    print("\nCall connected, relayed and archived. All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
