# Security Policy

ThreadOrbit is self-hosted chat that handles private messages, credentials, and file uploads,
so we take security reports seriously. Thank you for helping keep it safe.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report privately through one of:

- **GitHub Security Advisories** — the preferred channel: open a draft advisory under the
  repository's **Security → Advisories → Report a vulnerability** tab. This keeps the report
  private until a fix ships.
- **Email** — `olavgjerde@yahoo.no` with a subject starting `[ThreadOrbit security]`.

Please include:

- the affected version / commit,
- a description of the issue and its impact,
- reproduction steps or a proof-of-concept,
- any suggested remediation.

We aim to acknowledge a report within **72 hours** and to agree on a disclosure timeline with
you. We'll credit reporters in the release notes unless you prefer to remain anonymous.

## Supported versions

ThreadOrbit is pre-1.0 and ships from `main`. Security fixes land on `main`; there is no
back-port branch yet. Run a recent build.

## Operator responsibilities

Several controls are the deploying operator's responsibility — the defaults are safe for local
development but **must** be changed before exposing an instance:

- **Rotate the bundled Keycloak client secret.** `keycloak/realm.json` ships a well-known
  dev secret. Regenerate it in Keycloak and supply the new value via `KEYCLOAK_CLIENT_SECRET`
  (the app fails fast if it's unset in the `prod` profile).
- **Remove the demo users** (`alice`, `bob`) and the `admin/admin` Keycloak bootstrap account.
- **Terminate TLS in front of the app** and keep it bound to loopback (`SERVER_ADDRESS=127.0.0.1`)
  behind the proxy — see `nginx_example.conf`. The app trusts `X-Forwarded-*` from its upstream.
- **Set a concrete upload body cap** at the edge (`client_max_body_size`) — workspace admins have
  no application-side upload ceiling.
- **Back up** the database and the `data/` directory (attachments, avatars, Lucene index).

See `README.md` and `QUICKSTART-MANUAL.md` for the full production checklist.
