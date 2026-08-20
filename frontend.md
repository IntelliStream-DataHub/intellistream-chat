# Fronting IntelliStream Chat in production

Two working configs. Use **nginx** unless you are running more than one app node or want stick
tables and a live stats socket, in which case use **haproxy** — at the scale a self-hosted team
chat runs at, nothing else separates them.

Both configs assume the app on `127.0.0.1:8080` (`SERVER_ADDRESS=127.0.0.1`) and Keycloak on
`127.0.0.1:8081`. Everything that will actually bite you is a comment next to the line that
prevents it, because that is where it gets read.

**Keycloak must be on the same registrable domain as the app** — `chat.example.com` +
`auth.example.com`, not `chat.example.com` + `login.example.net`. The session and CSRF cookies are
`SameSite=Strict`, and OIDC login is a redirect away and back; across sites the browser withholds
the cookie, Spring cannot match the OAuth2 state, and the user lands on `/login?error` with nothing
in the server log, because the cookie was never sent. This is the single most expensive mistake
available here.

## nginx

`/etc/nginx/conf.d/intellistream.conf`, adjust the `TODO`s, then
`sudo nginx -t && sudo systemctl reload nginx`.

```nginx
# Both sides of every proxied WebSocket count against worker_connections: one connection from the
# browser, one to the JVM. Size for concurrent people, not requests per second.
events { worker_connections 65536; }
worker_rlimit_nofile 200000;

# HTTP -> HTTPS
server {
    listen      80;
    listen      [::]:80;
    server_name chat.example.com;                                          # TODO

    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }   # TODO (or DNS-01)
    location / { return 301 https://$host$request_uri; }
}

server {
    listen      443 ssl;
    listen      [::]:443 ssl;
    http2       on;
    server_name chat.example.com;                                          # TODO

    ssl_certificate     /etc/letsencrypt/live/chat.example.com/fullchain.pem;   # TODO
    ssl_certificate_key /etc/letsencrypt/live/chat.example.com/privkey.pem;     # TODO
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers on;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    # The app sets its own CSP, X-Content-Type-Options, Referrer-Policy and frame-ancestors.
    # Don't duplicate them. HSTS belongs here, at the TLS terminator.
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # ---- Uploads -------------------------------------------------------------------------
    # Attachments are sent as the raw request body, so the request size *is* the file size.
    #
    # 0 = no limit, which matches the application: there is no per-file cap, and what bounds an
    # ordinary account is its storage quota (2 GiB by default, a total rather than a per-file
    # limit). Admins have no quota at all. Put a number here only if you want the edge to refuse
    # before the bytes cross the network — and remember it then applies to admins too, who are
    # otherwise the only accounts that can move something very large.
    client_max_body_size 0;

    # This is the setting that matters, and its default is the wrong way round.
    #
    # With request buffering ON (nginx's default) nginx reads the *entire* body before it opens a
    # connection to the app: a 10 GB upload is written to nginx's temp directory in full, then sent
    # upstream, then written again by the JVM. Twice the disk, twice the wait, and the app — which
    # streams uploads straight to disk precisely so it never holds one — sees nothing until the
    # whole file has landed on the proxy.
    #
    # Off, nginx relays the body as it arrives and never spools it.
    proxy_request_buffering off;

    # Only a relay chunk once buffering is off, so keep it small: it is held per concurrent upload,
    # and a large value costs memory across simultaneous uploaders while buying nothing. Do not
    # "raise the buffer" for big files — turning buffering off is what makes big files work.
    client_body_buffer_size 128k;

    # Between successive reads, not totals, so these need not cover a whole transfer — but a phone
    # on a weak signal can stall for longer than the default 60s, and losing 9 GB of upload to that
    # is a poor trade for a setting that costs nothing.
    client_body_timeout   300s;
    proxy_send_timeout    300s;
    proxy_read_timeout    300s;
    proxy_connect_timeout 5s;

    proxy_http_version 1.1;

    # Read by server.forward-headers-strategy=framework. X-Forwarded-Proto is what decides the
    # Secure flag on the session and CSRF cookies; without it over TLS they go out unmarked.
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Port  $server_port;

    # STOMP over a native WebSocket — there is no SockJS fallback, because its polling transports
    # inject inline <script> and the app's CSP forbids that. Drop the upgrade headers and real-time
    # messaging silently becomes no real-time messaging. Heartbeats run every 10s each way, so the
    # 60s default technically survives; one GC pause on a loaded box is all it takes for it not to.
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # Static assets. Deliberately no `add_header Cache-Control` here — the application sets it
    # per URL, and it is the only party that can. Only the nine declared bundles are
    # content-versioned (`/js/chat.bundle.min.js?v=<hash>`); the `js/chat/` ES-module graph and
    # `js/vendor/` are some 420 KB served at fixed paths that do not change across a deploy. A rule
    # matching the directory cannot tell those apart, and this one used to answer `immutable` for
    # all of it — so a deploy that changed `chat/index.js` kept serving the old copy against the new
    # server for up to thirty days, silently. See StaticAssetCacheConfig.
    location ~* ^/(css|js|img|fonts)/ {
        proxy_pass http://127.0.0.1:8080;
    }

    location / { proxy_pass http://127.0.0.1:8080; }
}

# Keycloak, on a subdomain of the same registrable domain. Start it with
#   bin/kc.sh start --proxy-headers=xforwarded --hostname=https://auth.example.com
server {
    listen      443 ssl;
    listen      [::]:443 ssl;
    http2       on;
    server_name auth.example.com;                                          # TODO

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
`sudo setsebool -P httpd_can_network_connect on`.

## Calls through the same port 443 (SNI routing for TURN)

Skip this unless your users are on a network that allows nothing outbound but port 443 — the
plain coturn setup in `QUICKSTART-MANUAL.md` § *Calls* (3478 UDP + 5349 TURNS) is simpler and
works everywhere else.

TURN over TLS (`turns:`) also wants port 443, because on the wire it is indistinguishable from
ordinary HTTPS — that indistinguishability is the entire reason a restrictive firewall lets it
through. But nginx already owns `chat.example.com:443` (and `auth.example.com:443`), and two
TLS-terminating services can't bind the same IP:port. The fix is to stop terminating TLS at the
edge for the *outer* connection at all: split it by **SNI**, the hostname a browser sends in the
clear inside the TLS ClientHello, before anything is decrypted. Path-based routing (an nginx
`location`) cannot do this — a path only exists once HTTP has been parsed, which requires TLS to
already be terminated by whoever holds the key for that connection, and TURN isn't HTTP in the
first place. SNI is the one thing available before any of that.

This needs one more DNS name under the same domain — a wildcard cert already covers it — pointing
at the same IP `chat.example.com` already uses:

```
turn.example.com   IN   A   <same IP as chat.example.com>
```

And nginx's `stream` module, which most distro packages ship separately from core nginx:

```bash
# Debian/Ubuntu
sudo apt install libnginx-mod-stream
# AlmaLinux/RHEL — confirm it's already in before assuming you need the package:
nginx -V 2>&1 | grep -o with-stream_ssl_preread_module || sudo dnf install nginx-mod-stream
```

### The shape of the change

The existing `chat.example.com` and `auth.example.com` server blocks above move off the literal
port `443` onto an internal one, and a new `stream {}` block becomes the *only* thing bound to the
real `443`. It reads the SNI and forwards each connection's raw, still-encrypted bytes to whichever
backend owns that hostname — coturn does its own TLS termination with its own certificate from
there; nginx never decrypts TURN traffic at all.

```nginx
# Near the top of nginx.conf itself (not conf.d) — most packages ship this as a dynamic module.
load_module modules/ngx_stream_module.so;

stream {
    map $ssl_preread_server_name $calls_upstream {
        turn.example.com  127.0.0.1:5349;   # coturn's own TLS listener — untouched, no proxy protocol
        default           127.0.0.1:9443;   # everything else: chat + auth, see below
    }

    server {                        # the only thing bound to the real 443
        listen      443;
        listen      [::]:443;
        proxy_pass  $calls_upstream;
        ssl_preread on;
    }

    # A second, loopback-only hop purely so chat/auth traffic arrives at nginx's http {} block
    # with the real client IP attached via the PROXY protocol. Deliberately NOT applied to the
    # coturn branch above: coturn only speaks the HAProxy protocol on a separate, dedicated
    # `tcp-proxy-port` listener, not on its ordinary TLS port — mixing the two is a well-documented
    # source of "proxy protocol violated" failures. Skip this second server if you don't need real
    # client IPs preserved while this routing is active; chat/auth then just see 127.0.0.1, which
    # is fine for a low-traffic self-hosted instance but wrong for Keycloak's per-IP brute-force
    # protection at any real scale.
    server {
        listen         127.0.0.1:9443;
        proxy_pass     127.0.0.1:8443;
        proxy_protocol on;
    }
}
```

Then, in the `http {}` block, both existing `chat.example.com` and `auth.example.com` server
blocks change their `listen` line and pick the real client IP back up from the PROXY protocol
header the loopback hop just attached — nothing else in either block changes:

```nginx
server {
    listen      8443 ssl proxy_protocol;      # was: listen 443 ssl;
    listen      [::]:8443 ssl proxy_protocol; # was: listen [::]:443 ssl;
    http2       on;
    server_name chat.example.com;

    set_real_ip_from 127.0.0.1;
    real_ip_header   proxy_protocol;

    # ...unchanged below: ssl_certificate, /ws, the asset locations, everything else.
}
```

(Do the same two-line `listen` swap plus the `set_real_ip_from`/`real_ip_header` pair on the
`auth.example.com` block.)

### coturn and the app

coturn's TLS listener now only needs to be reached from this host itself:

```
tls-listening-port=5349
listening-ip=127.0.0.1        # reached only via the stream block above — never expose 5349 directly
```

And `ICHAT_TURN_URLS` drops the plain `turn:` entry entirely — a client that can't reach anything
but 443 will just fail that candidate and add latency for no benefit:

```bash
ICHAT_TURN_URLS=turns:turn.example.com:443?transport=tcp
```

**The UDP relay port range (`min-port`–`max-port`) still needs no firewall hole**, and that isn't
obvious given everything else here is about squeezing through one port. `ichat.calls.force-relay`
defaults on, so every call — both participants — goes through this one coturn instance; the actual
media hop between the two callers' relay allocations is coturn forwarding between two sockets on
its own host. Neither caller's network is ever involved in that hop, no matter how restrictive
either one is.

## haproxy

Same behaviour. `/etc/haproxy/haproxy.cfg`, then
`sudo haproxy -c -f /etc/haproxy/haproxy.cfg && sudo systemctl reload haproxy`.

```haproxy
global
    maxconn 200000
    ulimit-n 400000
    tune.ssl.default-dh-param 2048
    # Per-connection relay buffer. Leave it alone for large uploads: haproxy streams request
    # bodies rather than spooling them to disk, so a bigger buffer does not make a 10 GB upload
    # work — it just costs this much more memory on every connection.
    # tune.bufsize 16384

defaults
    mode    http
    option  httplog
    option  forwardfor
    timeout connect 5s
    # Long, because WebSocket sessions idle between heartbeats and large uploads take as long as
    # they take. `tunnel` governs an already-upgraded connection and is the one people forget:
    # haproxy tunnels Upgrade automatically in HTTP mode, then times it out here.
    timeout client  3600s
    timeout server  3600s
    timeout tunnel  3600s

frontend https
    bind :443 ssl crt /etc/haproxy/certs/chat.example.com.pem alpn h2,http/1.1   # TODO
    http-request set-header X-Forwarded-Proto https
    http-response set-header Strict-Transport-Security "max-age=31536000; includeSubDomains"

    # No request-body limit, matching the application: no per-file cap, an ordinary account bounded
    # by its 2 GiB storage quota, admins by nothing. To refuse very large uploads at the edge
    # instead, add this — remembering it applies to admins too:
    #   http-request deny deny_status 413 if { req.body_size gt 10737418240 }

    default_backend intellistream-chat

backend intellistream-chat
    option httpchk GET /actuator/health
    http-check expect status 200
    server app1 127.0.0.1:8080 check maxconn 100000
    # More than one node? Add them here with cookie affinity — a user's WebSocket and their HTTP
    # requests must reach the same JVM, because the STOMP broker is in-process:
    #   cookie SRV insert indirect nocache
    #   server app1 10.0.0.11:8080 check cookie a1
    #   server app2 10.0.0.12:8080 check cookie a2
```

## Sizing, for either

- **Two sockets per user**, not one: the browser side and the upstream side both count against
  `worker_connections` / `maxconn`.
- **Ephemeral ports on the upstream side.** Every proxied connection to `127.0.0.1:8080` consumes
  one, and the default range is about 28k — which a few thousand users exhaust. Widen it with
  `net.ipv4.ip_local_port_range = 10240 65535`, and list the ports your own services listen on in
  `net.ipv4.ip_local_reserved_ports`, or the widened range will eventually take one of them while
  the service is restarting.
- **`LimitNOFILE`** raised in the proxy's service unit to match.
- **Session affinity is mandatory with more than one app node**, as noted in the haproxy backend
  above.
