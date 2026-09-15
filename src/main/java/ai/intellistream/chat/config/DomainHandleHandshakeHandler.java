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

package ai.intellistream.chat.config;

import ai.intellistream.chat.security.CurrentUser;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.io.Serial;
import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Names a WebSocket session after the domain handle, so that a message addressed to a person
 * reaches that person.
 *
 * <p>Spring routes {@code /user/**} destinations by the <em>name of the session's principal</em>:
 * {@code convertAndSendToUser("alice", "/queue/calls", …)} is delivered to the sessions whose
 * principal is called {@code alice}, and to nothing else. Without this handler the principal is the
 * one the HTTP session carries, whose name is Keycloak's {@code preferred_username} — the login.
 * Every sender in this application, however, addresses people by the domain handle
 * ({@code User.username}), which {@code UserService.sanitizeUsername} derives from that login: the
 * local part of an email-shaped one, collision-suffixed when taken. For every plain {@code alice}
 * the two strings are equal and the difference is invisible. For the accounts where they differ it
 * is a misdelivery:
 *
 * <ul>
 *   <li>Someone logging in as {@code olav@example.com} gets the handle {@code olav}. Their DM
 *       alerts, call invitations and reminder notices are addressed to {@code olav} and delivered
 *       to whichever session is named {@code olav} — not theirs, whose principal is
 *       {@code olav@example.com}.</li>
 *   <li>If a second account logs in as plain {@code olav}, it takes the handle {@code olav-<suffix>}
 *       but its principal <em>is</em> {@code olav}. It then receives the first account's DM
 *       previews, call invitations and notices.</li>
 *   <li>With nobody named {@code olav} connected, the messages are simply dropped: the phone never
 *       rings and no toast ever appears.</li>
 * </ul>
 *
 * <p>The fix belongs here rather than at the six {@code convertAndSendToUser} call sites. Those
 * agree already — they all use the handle, which is the identity the rest of the application is
 * written in (mentions, presence, rate-limit keys, the {@code me-username} meta tag) — so giving the
 * session that same name makes them all correct at once, and leaves nothing for a seventh call site
 * to get wrong. The two sites that pass {@code principal.getName()} stay correct too, because that
 * name is now the handle.
 *
 * <p>Resolution goes through {@link CurrentUser}, the one bridge from a Spring principal to a domain
 * {@code User}, and costs one lookup per socket — not per frame; {@code StompAuthorizationConfig}
 * caches the {@code User} itself at CONNECT. When it cannot resolve (a suspended account, an
 * unfamiliar principal type), the original principal is kept and the CONNECT frame refuses the
 * session in the usual way.
 */
@Component
public class DomainHandleHandshakeHandler extends DefaultHandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(DomainHandleHandshakeHandler.class);

    /**
     * Looked up per handshake rather than injected, to break a cycle: this handler is wired into
     * {@code WebSocketConfig}, which the servlet container's factory depends on, while
     * {@code CurrentUser} needs the JPA stack that the same factory has to come up before. Nothing
     * here needs the bean until a socket is actually opened, by which time everything exists.
     */
    private final ObjectProvider<CurrentUser> currentUser;

    public DomainHandleHandshakeHandler(ObjectProvider<CurrentUser> currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        var principal = super.determineUser(request, wsHandler, attributes);
        if (!(principal instanceof Authentication auth)) {
            return principal;
        }
        try {
            var handle = currentUser.getObject().resolve(auth).getUsername();
            return handle.equals(auth.getName()) ? auth : new HandleNamed(auth, handle);
        } catch (RuntimeException e) {
            // A suspended account, or a principal type CurrentUser does not know. Leave the
            // handshake alone: CONNECT resolves the same principal a moment later and refuses it
            // there, which is where that refusal is already handled and logged.
            log.debug("Keeping the login name for a session that could not be resolved: {}", e.toString());
            return principal;
        }
    }

    /**
     * The same authentication under the domain handle. Everything except the name is delegated, so
     * {@code CurrentUser} still sees the OIDC user or JWT it needs — it matches on the wrapped
     * {@link #getPrincipal()}, never on the token's own class.
     */
    static final class HandleNamed implements Authentication {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Authentication delegate;
        private final String handle;

        HandleNamed(Authentication delegate, String handle) {
            this.delegate = Objects.requireNonNull(delegate);
            this.handle = Objects.requireNonNull(handle);
        }

        @Override
        public String getName() {
            return handle;
        }

        /** The login this session authenticated as, for anything that wants to say so. */
        public String loginName() {
            return delegate.getName();
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }

        @Override
        public Object getCredentials() {
            return delegate.getCredentials();
        }

        @Override
        public Object getDetails() {
            return delegate.getDetails();
        }

        @Override
        public Object getPrincipal() {
            return delegate.getPrincipal();
        }

        @Override
        public boolean isAuthenticated() {
            return delegate.isAuthenticated();
        }

        @Override
        public void setAuthenticated(boolean authenticated) throws IllegalArgumentException {
            delegate.setAuthenticated(authenticated);
        }

        @Override
        public String toString() {
            return "HandleNamed[" + handle + "]";
        }
    }
}
