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

package ai.intellistream.chat.domain;

/**
 * Who may create channels.
 *
 * <p>Modelled on Slack and Mattermost, which both settled on a permission that defaults permissive
 * and can be tightened by an administrator, rather than a capability you must be granted. Kept in
 * the application rather than as a Keycloak realm role because it is a product decision: the
 * identity provider owns who you are, this owns what you may do here. It also means tightening it
 * during an abuse incident does not require touching the IdP, and relaxing it later does not
 * require re-granting a role to every existing account.
 */
public enum ChannelCreationPolicy {

    /** Any authenticated member. The default, and what every deployment did before this existed. */
    EVERYONE,

    /** Workspace admins only ({@code ichat-admin}). Use when open registration meets a spam wave. */
    ADMINS_ONLY;

    /** Tolerant parse for form input; anything unrecognised falls back to the permissive default. */
    public static ChannelCreationPolicy parse(String raw) {
        if (raw == null) return EVERYONE;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EVERYONE;
        }
    }
}
