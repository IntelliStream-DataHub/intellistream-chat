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

package ai.intellistream.chat.integration;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.function.Supplier;

/**
 * Run a block with {@code ROLE_ADMIN} in the security context.
 *
 * <p>Needed because the two workspace-admin decisions in {@code ChannelService} — who may delete a
 * channel, and who may archive one they do not administer — read Spring's live authority rather than
 * the {@code users.admin} column, whose own javadoc says never to decide access from it alone. These
 * integration tests run with {@code webEnvironment = NONE} and no authentication at all, which is the
 * correct default: it means nothing in the suite is accidentally permitted by the <em>absence</em> of
 * a principal, and a test that wants the admin path has to say so.
 *
 * <p>The context is always cleared afterwards. {@code SecurityContextHolder} is thread-local and JUnit
 * reuses the thread, so a leaked authentication would silently grant admin to every test that ran
 * after it in the same class — the kind of contamination that makes a suite pass and the product
 * fail.
 */
final class AsWorkspaceAdmin {

    private AsWorkspaceAdmin() {}

    static void run(Runnable body) {
        get(() -> {
            body.run();
            return null;
        });
    }

    static <T> T get(Supplier<T> body) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("workspace-admin", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return body.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
