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
 * How much a channel is allowed to interrupt you — the Slack / Mattermost notification control,
 * with muting as its bottom setting rather than a separate switch.
 *
 * <p>Three concrete levels, plus {@link #DEFAULT}, which is not a level at all but the instruction
 * <em>follow the account-wide default</em>. It is legal only on a membership
 * ({@code channel_members.notify_level}); the account default itself
 * ({@code users.notify_default}) sits at the bottom of the chain and must be concrete, because
 * there is nothing beneath it to inherit from. {@link User#chooseNotifyDefault} enforces that, as
 * does a check constraint in {@code V7__notification_levels.sql}.
 *
 * <p>{@code DEFAULT} is <b>stored</b>, not resolved at write time. A membership records that it
 * inherits, never a copy of what the account default happened to be when the user joined. The
 * difference only shows up later: with the copy, changing the account default moves nothing,
 * because every channel is carrying a frozen value that is indistinguishable from a deliberate
 * per-channel choice. Storing the inheritance means the change lands everywhere the user has not
 * explicitly overridden, which is what "default" means to the person setting it.
 *
 * <p>Consequently, callers deciding whether to notify want {@link #resolvedAgainst}; callers
 * rendering a picker want the raw value, so the picker can show "Default" as the selected option
 * rather than silently pre-selecting whatever it currently resolves to.
 */
public enum NotificationLevel {

    /** Membership-only: follow the user's account-wide default, whatever it is now. */
    DEFAULT,
    /** Every message in the channel notifies. */
    ALL,
    /** Only @-mentions (and keyword-style highlights) notify. The shipped account default. */
    MENTIONS,
    /** Nothing notifies. This is what "mute" means here. */
    NONE;

    /** The level applied when nothing has ever been chosen — today's behaviour for everybody. */
    public static final NotificationLevel ACCOUNT_FALLBACK = MENTIONS;

    /** True for {@link #DEFAULT} — this setting defers to the account-wide default. */
    public boolean isInherited() {
        return this == DEFAULT;
    }

    /** True for a level that stands on its own: {@link #ALL}, {@link #MENTIONS}, {@link #NONE}. */
    public boolean isConcrete() {
        return this != DEFAULT;
    }

    /**
     * Resolve to the level actually in force. A concrete level is its own answer; {@link #DEFAULT}
     * yields {@code accountDefault}, falling back to {@link #ACCOUNT_FALLBACK} if that is somehow
     * absent or itself {@code DEFAULT} (which the domain and the schema both forbid, but a
     * resolver that can loop or NPE on bad data is not worth the brevity).
     */
    public NotificationLevel resolvedAgainst(NotificationLevel accountDefault) {
        if (isConcrete()) {
            return this;
        }
        return accountDefault == null || accountDefault.isInherited() ? ACCOUNT_FALLBACK : accountDefault;
    }
}
