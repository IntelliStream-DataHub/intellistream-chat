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

package ai.intellistream.chat.linkpreview;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the server may make an outbound request to a URL a user typed.
 *
 * <p>This is the SSRF guard, and it is the reason link previews are safe to have at all. The
 * server fetches the pages people paste; without this, "paste a link" is "make the chat server
 * GET any address it can reach" — the cloud metadata endpoint, the Postgres admin UI on the
 * private network, Keycloak's admin API on localhost. So: {@code http} and {@code https} only, and
 * the host must resolve to <em>only</em> public addresses. Loopback, private (RFC 1918 and the
 * IPv6 unique-local range), link-local (which is where the metadata endpoint lives), carrier NAT,
 * multicast, wildcard, and the IPv6 forms that smuggle an IPv4 address through are all refused.
 * Every hop of a redirect goes through this again, because {@code https://bit.ly/x} resolving to
 * something public says nothing about where it points.
 *
 * <p>The check resolves the name and the client then connects by name, so a DNS answer that
 * changes between the two (rebinding) is a residual risk that is accepted here, as it is in every
 * unfurler that does not pin the connection to the checked address; connect and read timeouts
 * keep the window small. The one thing that is not accepted is a name that has <em>any</em>
 * forbidden address among its answers.
 *
 * <p>{@link #allowLoopback} exists so the fetcher's tests can point it at a server on
 * {@code 127.0.0.1}; production constructs it {@code false}.
 */
public class OutboundUrlGuard {

    private final boolean allowLoopback;

    public OutboundUrlGuard() {
        this(false);
    }

    public OutboundUrlGuard(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** Thrown for a URL the server must not fetch; the message names the reason and is safe to log. */
    public static final class RefusedException extends Exception {
        public RefusedException(String message) {
            super(message);
        }
    }

    public void check(URI uri) throws RefusedException {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RefusedException("scheme not allowed: " + scheme);
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RefusedException("no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new RefusedException("credentials in URL");
        }
        var bare = host.toLowerCase(Locale.ROOT);
        if (bare.equals("localhost") || bare.endsWith(".localhost") || bare.endsWith(".local")
                || bare.endsWith(".internal")) {
            if (!allowLoopback) throw new RefusedException("host not allowed: " + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RefusedException("host does not resolve: " + host);
        }
        if (addresses.length == 0) {
            throw new RefusedException("host does not resolve: " + host);
        }
        for (var address : addresses) {
            var reason = forbiddenReason(address);
            if (reason != null) {
                throw new RefusedException(host + " resolves to " + address.getHostAddress() + " (" + reason + ")");
            }
        }
    }

    /** Null when the address is public; otherwise a short reason. Package-private for the tests. */
    String forbiddenReason(InetAddress address) {
        if (address.isLoopbackAddress()) return allowLoopback ? null : "loopback";
        if (address.isAnyLocalAddress()) return "wildcard";
        if (address.isLinkLocalAddress()) return "link-local";
        if (address.isSiteLocalAddress()) return "private";
        if (address.isMulticastAddress()) return "multicast";
        if (address instanceof Inet4Address) {
            var b = address.getAddress();
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return "carrier-grade NAT";   // 100.64.0.0/10
            if (b0 == 0) return "this-network";                                    // 0.0.0.0/8
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return "IETF protocol"; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return "benchmarking";        // 198.18.0.0/15
            if (b0 >= 240) return "reserved";                                      // 240.0.0.0/4
            return null;
        }
        if (address instanceof Inet6Address v6) {
            var b = v6.getAddress();
            if ((b[0] & 0xfe) == 0xfc) return "unique-local";                       // fc00::/7
            if (v6.isIPv4CompatibleAddress()) return "IPv4-compatible";
            // ::ffff:a.b.c.d — Java usually hands these back as Inet4Address, but not always.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return "IPv4-mapped";
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) return "6to4";     // 2002::/16
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) return "Teredo"; // 2001::/32
        }
        return null;
    }

    /** For log lines: the hosts a redirect chain went through, without the query strings. */
    static String describe(List<URI> chain) {
        var sb = new StringBuilder();
        for (var u : chain) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(u.getScheme()).append("://").append(u.getHost());
        }
        return sb.toString();
    }
}
