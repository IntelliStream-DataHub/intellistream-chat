# Marketing site

Two standalone, self-contained pages for **intellistream.ai** — neither is part of the Spring Boot
build and neither is served by the app (the app's in-product landing page is
`src/main/resources/templates/landing.html`). Deploy them to any static host, side by side, so the
relative link between them resolves.

- `index.html` — the landing page. Header nav, hero, quick start, screenshots, and the
  Why / Features / Performance / Stack / Secrets sections.
- `docs.html` — the documentation site: using the app, the full `ichat.*` and `ICHAT_*`
  configuration reference, and a chapter on tuning PostgreSQL for this workload.

Both are single files with inline CSS and JS and no external requests, images included as data
URIs. That is deliberate: they have to work from a `file://` URL, from a bucket, and from behind a
CSP that allows nothing off-origin. Keep it that way — no CDN fonts, no analytics snippet.

`docs.html` copies `index.html`'s custom properties (`--bg`, `--brand`, `--peach`, `--band`, …)
rather than importing them. If you restyle one, restyle the other.
