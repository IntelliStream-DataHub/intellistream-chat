# Fronting ThreadOrbit in production

What to put in front of the JVM, how to size it, and the things that will bite you if you don't.
Working configs for both nginx and haproxy are at the bottom; the reasoning comes first, because
the copy-paste is the easy part.

## Short answer

**Use nginx.** It handles this workload correctly, and at the scale a self-hosted team chat
actually runs at, the difference between the two is not something you will be able to measure.

**Switch to haproxy when one of these becomes true:**

| Reason | Why haproxy wins |
|---|---|
| More than one app node | Real health checking, and session affinity that survives a backend restart. nginx's `ip_hash`/`sticky` is workable; haproxy's is better and easier to reason about. |
| Beyond roughly 30–50k concurrent connections | haproxy's connection handling is its whole design centre. nginx copes; haproxy is comfortable. |
| You want abuse control at the edge | Stick tables are genuinely better than `limit_req` — per-IP connection rates, concurrent-connection caps, and dynamic blocking, all with visibility into what tripped. |
| You want to see what's happening | The runtime API and stats page expose per-backend queue depth, connection rates and session durations live. nginx needs a separate exporter for a fraction of that. |

Both terminate TLS well. Both proxy WebSockets correctly. Neither is a bottleneck at 10k
connections. Pick on operations, not throughput.

## What the app requires from any proxy

These are not optional — get one wrong and the symptom is usually "it works until it doesn't".

1. **HTTP/1.1 with `Upgrade`/`Connection` passed through on `/ws`.** STOMP runs over a native
   WebSocket; there is no SockJS fallback to save you (the polling transports inject inline
   `<script>`, which the app's CSP forbids). If the upgrade headers are dropped, real-time
   messaging silently degrades to nothing.
2. **`X-Forwarded-Proto`.** The app runs `server.forward-headers-strategy: framework`, and the
   `Secure` flag on both the session and CSRF cookies is decided per request from
   `request.isSecure()`. Without that header over TLS, cookies go out without `Secure`.
3. **A read timeout longer than the heartbeat interval.** STOMP heartbeats run 10s each way, so
   nginx's 60s default technically survives — but any pause in heartbeats (a GC hiccup, a loaded
   box) kills sessions for no reason. Set 3600s on the `/ws` location.
4. **A body-size limit above your upload cap.** Attachments are now sent as the *raw request body*
   rather than multipart, so the request size is the file size. Workspace admins resolve to an
   unlimited per-user cap, which makes the proxy the only ceiling that exists for them.
5. **No buffering on the upgraded connection.** nginx stops buffering once a connection is
   upgraded, so this is automatic there; in haproxy use `option http-server-close` and let the
   tunnel form.

## The one that will actually bite you: same-site cookies and the login round trip

**Keycloak must sit on the same registrable domain as the app.**

The session and CSRF cookies are deliberately `SameSite=Strict` — it is a real CSRF defence and the
app is designed around it. But OIDC login is a redirect *away* to Keycloak and back. If Keycloak is
on a different site, that return trip is a cross-site top-level navigation, the browser withholds
the Strict cookie, Spring can't match the OAuth2 state, and the user lands on `/login?error` with
nothing useful in the logs.

- ✅ `chat.example.com` + `auth.example.com` — same registrable domain, same-site, works.
- ❌ `chat.example.com` + `login.example.net` — cross-site, login fails.
- ❌ Bare IPs that differ (`10.0.0.5` and `10.0.0.6`) — cross-site, login fails.

This is not theoretical: it reproduces exactly this way, and it is invisible in server logs because
the browser simply never sends the cookie. If you must split domains, you have to relax the cookie
to `SameSite=Lax` in `SecurityConfig` and accept the weaker CSRF posture — prefer moving Keycloak
onto a subdomain instead.

## Sizing for connection count

A chat server's proxy problem is *connections*, not requests per second. Two things follow.

### Every proxied connection costs two sockets

One from the browser, one to the upstream. Size accordingly:

```nginx
# nginx: worker_connections is per worker and counts BOTH sides.
events { worker_connections 65536; }        # ~32k proxied connections per worker
worker_rlimit_nofile 200000;                # must exceed workers × worker_connections
```

```haproxy
# haproxy: maxconn is frontend connections; the backend side is additional.
global
    maxconn 200000
    ulimit-n 400000
```

And raise the service's own limit — on systemd, `LimitNOFILE=` in the unit, not `ulimit` in a shell.

### Ephemeral ports on the *upstream* side — the sleeper problem

This is the one nobody plans for. Every connection the proxy opens to `127.0.0.1:8080` takes a
local ephemeral port, and a single `(dst IP, dst port)` pair can only be addressed from about 28k
of them on a stock kernel. **Your proxy runs out of ports long before the app runs out of memory.**

Three fixes, best first:

1. **Widen the range** — the cheapest, and enough to roughly double your headroom:
   ```bash
   sysctl -w net.ipv4.ip_local_port_range="1024 65535"
   ```
2. **Spread the upstream across loopback addresses.** Each additional destination IP is a fresh
   ~64k port pool. Bind the app to `0.0.0.0` and:
   ```nginx
   upstream threadorbit {
       server 127.0.0.1:8080;
       server 127.0.0.2:8080;
       server 127.0.0.3:8080;
       keepalive 512;
   }
   ```
   (The load generator in `benchmark/` uses exactly this trick for the same reason — see
   `scalability.md`.)
3. **Put the proxy on a different host.** Then upstream connections leave over the network and the
   port pool is per destination rather than shared with everything else on the box.

### Reserve your listen ports, or widening the range will bite back

Widening `ip_local_port_range` to `1024 65535` puts **your own service ports inside the ephemeral
range**. An outgoing connection can then bind your listen port as its *source* port, and if that
socket lingers — `CLOSE-WAIT` from a client that never closed its end, or `TIME_WAIT` after a
burst — the service fails to restart with "port already in use" while `ss -ltn` shows nothing
listening on it. It is a deeply confusing ten minutes.

Reserve the ports your services listen on:

```bash
sysctl -w net.ipv4.ip_local_reserved_ports=8080,8090,8443
```

Diagnose it by looking at *all* socket states rather than listeners, which is where the culprit
actually shows up:

```bash
ss -tanH | awk '$4 ~ /:8080$/ || $5 ~ /:8080$/'   # not just `ss -ltn`
```

The rest of the kernel tuning that matters at these connection counts — `somaxconn`,
`tcp_max_syn_backlog`, `netdev_max_backlog`, conntrack — is tabulated in
[`scalability.md`](scalability.md), and applies to the proxy host as much as the app host.

## Multi-node: affinity is mandatory today

The STOMP broker is **in-process**. A message posted on node A is broadcast only to WebSocket
sessions held by node A. Until an external relay lands (see the deferred
`horizontal-scalability-plan`), running two nodes behind a round-robin proxy means users silently
miss each other's messages.

If you must run more than one node now, pin each user to a node:

```haproxy
backend threadorbit
    balance source
    hash-type consistent          # adding a node reshuffles the minimum
    cookie TOSRV insert indirect nocache
    server app1 10.0.0.11:8080 check cookie a1
    server app2 10.0.0.12:8080 check cookie a2
```

Affinity buys you availability and rolling restarts, not throughput sharing — and note a single
node already sustains ~17,000 messages/second and ~136,000 deliveries/second, so "we need a second
node" is almost always an availability argument rather than a capacity one.

## Static assets

JS and CSS bundles are content-versioned (`?v=<hash>`), so they are safe to cache hard at the edge:

```nginx
location ~* ^/(css|js|img|fonts)/ {
    proxy_pass http://threadorbit;
    proxy_cache_valid 200 30d;
    add_header Cache-Control "public, max-age=2592000, immutable";
}
```

Don't add security headers at the edge that the app already sets — it sends its own CSP,
`X-Content-Type-Options`, `Referrer-Policy` and `frame-ancestors`, and a second, differently-worded
copy from the proxy is how you end up debugging a CSP that nobody wrote on purpose. HSTS is the
exception: it belongs at the TLS terminator.

## Health checks

`/actuator/health` is deliberately unauthenticated so a proxy can poll it. Everything else under
`/actuator` requires authentication.

```haproxy
option httpchk GET /actuator/health
http-check expect status 200
```

Check the health endpoint, not `/` — the root path redirects unauthenticated visitors into the
OIDC flow, so a naive check measures Keycloak's availability instead of the app's.

## Complete nginx config

Drop into `/etc/nginx/conf.d/threadorbit.conf`, adjust the `TODO`s, then
`sudo nginx -t && sudo systemctl reload nginx`. Assumes the app on `127.0.0.1:8080`
(`SERVER_ADDRESS=127.0.0.1`) and certificates from your ACME client.

```nginx
# Sized for connection count, not request rate — see "Sizing" above. Both sides of every
# proxied WebSocket count against worker_connections.
events { worker_connections 65536; }
worker_rlimit_nofile 200000;

# HTTP -> HTTPS
server {
    listen      80;
    listen      [::]:80;
    server_name chat.example.com;                                    # TODO

    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }   # TODO (or DNS-01)
    location / { return 301 https://$host$request_uri; }
}

server {
    listen      443 ssl;
    listen      [::]:443 ssl;
    http2       on;
    server_name chat.example.com;                                    # TODO

    ssl_certificate     /etc/letsencrypt/live/chat.example.com/fullchain.pem;   # TODO
    ssl_certificate_key /etc/letsencrypt/live/chat.example.com/privkey.pem;     # TODO
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers on;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    # The app sets its own CSP, X-Content-Type-Options, Referrer-Policy and frame-ancestors.
    # Don't duplicate them here. HSTS belongs at the TLS terminator.
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # Attachments are sent as the raw request body, so this is the file size. Workspace admins
    # have no per-user cap, which makes this the only ceiling that applies to them.
    client_max_body_size 500m;
    client_body_buffer_size 128k;

    proxy_http_version    1.1;
    proxy_connect_timeout 5s;
    proxy_read_timeout    60s;
    proxy_send_timeout    60s;

    # Read by server.forward-headers-strategy=framework. X-Forwarded-Proto is what decides the
    # Secure flag on the session and CSRF cookies.
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Port  $server_port;

    # STOMP over native WebSocket. Without the Upgrade headers real-time messaging silently
    # stops working; without the long timeout, sessions die between heartbeats for no reason.
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # Content-versioned bundles (?v=<hash>) — safe to cache hard.
    location ~* ^/(css|js|img|fonts)/ {
        proxy_pass http://127.0.0.1:8080;
        add_header Cache-Control "public, max-age=2592000, immutable";
    }

    location / { proxy_pass http://127.0.0.1:8080; }
}
```

Keycloak on a subdomain of the *same registrable domain* (see the cookie section above — this is
not optional). Tell Keycloak it sits behind a proxy:
`bin/kc.sh start --proxy-headers=xforwarded --hostname=https://auth.example.com`.

```nginx
server {
    listen      443 ssl;
    listen      [::]:443 ssl;
    http2       on;
    server_name auth.example.com;                                    # TODO

    ssl_certificate     /etc/letsencrypt/live/auth.example.com/fullchain.pem;   # TODO
    ssl_certificate_key /etc/letsencrypt/live/auth.example.com/privkey.pem;     # TODO
    ssl_protocols       TLSv1.2 TLSv1.3;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    proxy_http_version 1.1;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;

    location / { proxy_pass http://127.0.0.1:8081; }
}
```

On AlmaLinux / Rocky / RHEL with SELinux enforcing, nginx cannot reach the JVM until you allow it:
`sudo setsebool -P httpd_can_network_connect on` (see the SELinux section in the README).

## Complete haproxy config

For when the table at the top says it's time. Same behaviour as the nginx config above.

```haproxy
global
    maxconn 200000
    ulimit-n 400000
    tune.ssl.default-dh-param 2048

defaults
    mode    http
    option  httplog
    option  forwardfor
    timeout connect 5s
    timeout client  3600s        # long, because WebSocket sessions idle between heartbeats
    timeout server  3600s
    timeout tunnel  3600s        # the one that governs an upgraded connection

frontend https
    bind :443 ssl crt /etc/haproxy/certs/chat.example.com.pem alpn h2,http/1.1
    http-request set-header X-Forwarded-Proto https
    http-response set-header Strict-Transport-Security "max-age=31536000; includeSubDomains"

    # Raw-body attachment uploads: the request IS the file.
    http-request deny deny_status 413 if { path_beg /api/channels/ } { req.body_size gt 524288000 }

    default_backend threadorbit

backend threadorbit
    option httpchk GET /actuator/health
    http-check expect status 200
    server app1 127.0.0.1:8080 check maxconn 100000
```

haproxy tunnels `Upgrade` automatically in HTTP mode — `timeout tunnel` is what keeps the WebSocket
alive, and it's the setting people forget.

## Checklist

- [ ] `Upgrade` / `Connection` passed through on `/ws`, tunnel timeout ≥ 1h
- [ ] `X-Forwarded-Proto` set, app bound to `127.0.0.1` behind the proxy
- [ ] Keycloak on the **same registrable domain** as the app
- [ ] Body-size limit above your largest expected upload
- [ ] `worker_connections` / `maxconn` sized for **two** sockets per user
- [ ] `net.ipv4.ip_local_port_range` widened on the proxy host
- [ ] `net.ipv4.ip_local_reserved_ports` covers every port your services listen on
- [ ] `LimitNOFILE` raised in the service unit
- [ ] Health check points at `/actuator/health`
- [ ] Session affinity configured **if** running more than one node
