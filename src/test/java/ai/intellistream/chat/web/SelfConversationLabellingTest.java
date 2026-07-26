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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.web.dto.ConversationDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DIRECT conversation is normally "the conversation with that person", and both the sidebar and
 * the conversation header take their title from whoever that is. A DM with yourself has no such
 * person, so without this it rendered as a row with a blank name and a blank avatar letter.
 *
 * <p>The title is derived from the conversation's own shape rather than from the viewer, because
 * the two callers pass opposite things for "the other participant": the page path filters the
 * viewer out and passes null, the start-a-DM endpoint passes the person asked for, who is the
 * viewer. Both have to render the same.
 */
class SelfConversationLabellingTest {

    private static User user(long id, String username) {
        var u = new User("kc-" + username, username, username + "@example.com", username);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static Conversation conversation(ConversationType type, String dmKey, String title) {
        var c = new Conversation(type, title, dmKey, user(1L, "alice"));
        ReflectionTestUtils.setField(c, "id", 9L);
        return c;
    }

    @Test
    void aSelfKeyIsRecognisedAndATwoPersonKeyIsNot() {
        assertThat(conversation(ConversationType.DIRECT, "7:7", null).isSelfDirect()).isTrue();
        assertThat(conversation(ConversationType.DIRECT, "12:12", null).isSelfDirect()).isTrue();
        // Sorted distinct ids — the only other shape directKey can produce.
        assertThat(conversation(ConversationType.DIRECT, "1:2", null).isSelfDirect()).isFalse();
        assertThat(conversation(ConversationType.DIRECT, "1:12", null).isSelfDirect()).isFalse();
        assertThat(conversation(ConversationType.DIRECT, "12:1", null).isSelfDirect()).isFalse();
        // Groups have no dm_key at all.
        assertThat(conversation(ConversationType.GROUP, null, "Launch").isSelfDirect()).isFalse();
    }

    @Test
    void aSelfConversationIsTitledYouWhicheverWayItIsBuilt() {
        var mine = conversation(ConversationType.DIRECT, "1:1", null);
        var me = user(1L, "alice");

        // Sidebar / page path: no "other" was found, because there is none.
        assertThat(ConversationDto.of(mine, null).title()).isEqualTo("You");
        // Start-a-DM path: "other" is the viewer. Not "Alice" — in a list of people you are
        // talking to, your own name reads as somebody else.
        assertThat(ConversationDto.of(mine, me).title()).isEqualTo("You");
        // …and it keeps the avatar when one was available, so the row is not a blank circle.
        assertThat(ConversationDto.of(mine, me).otherUsername()).isEqualTo("alice");
    }

    @Test
    void anOrdinaryDirectConversationIsStillTitledAfterTheOtherPerson() {
        var dm = conversation(ConversationType.DIRECT, "1:2", null);

        var dto = ConversationDto.of(dm, user(2L, "bob"), 3L);

        assertThat(dto.title()).isEqualTo("bob");
        assertThat(dto.otherUsername()).isEqualTo("bob");
        assertThat(dto.unreadCount()).isEqualTo(3L);
    }

    @Test
    void aGroupKeepsItsOwnTitle() {
        var group = conversation(ConversationType.GROUP, null, "Launch");

        var dto = ConversationDto.of(group, null);

        assertThat(dto.title()).isEqualTo("Launch");
        assertThat(dto.otherUsername()).isNull();
    }
}
