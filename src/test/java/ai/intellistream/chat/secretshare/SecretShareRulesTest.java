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

package ai.intellistream.chat.secretshare;

import ai.intellistream.chat.domain.SecretAudience;
import ai.intellistream.chat.domain.SecretShare;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.PublicBadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretShareRulesTest {

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    private static SecretShare share(String label) {
        return new SecretShare("AAAAAAAAAAAAAAAAAAAAAA", new User("kc-a", "alice", "a@e", "Alice"), label,
                new byte[]{1}, new byte[32], SecretAudience.ANYONE, T0, T0.plusSeconds(3600));
    }

    @Test
    void theReceiptNamesASignedInOpenerByHandle() {
        var s = share("Staging DB");
        s.consume(SecretShare.Opener.signedIn(new User("kc-b", "bob", "b@e", "Bob")), T0);

        assertThat(SecretShareService.receiptBody(s))
                .isEqualTo("🔓 Your secret “Staging DB” was opened by @bob. The link no longer works.");
    }

    @Test
    void theReceiptNeverCarriesAnAnonymousOpenersAddressOrBrowser() {
        // A message is permanent; the address and browser summary are promised to go with the secret's
        // row after retention. So the receipt points at the list instead of copying them.
        var s = share(null);
        s.consume(SecretShare.Opener.anonymous("203.0.113.7", "Firefox on Windows"), T0);

        var body = SecretShareService.receiptBody(s);
        assertThat(body).contains("someone without an account").contains("/secrets")
                .doesNotContain("203.0.113.7").doesNotContain("Firefox");
    }

    @Test
    void theLabelCannotInjectMarkdownOrHtmlIntoTheReceipt() {
        var s = share("[click](https://evil.example) <b>x</b> **y**");
        s.consume(SecretShare.Opener.signedIn(new User("kc-b", "bob", "b@e", "Bob")), T0);

        var body = SecretShareService.receiptBody(s);
        assertThat(body).contains("\\[click\\]").contains("\\<b>").contains("\\*\\*y\\*\\*");
    }

    @Test
    void labelsAreOneLineTrimmedAndBounded() {
        assertThat(SecretShareService.normaliseLabel(null)).isNull();
        assertThat(SecretShareService.normaliseLabel("   ")).isNull();
        assertThat(SecretShareService.normaliseLabel("  prod\n\tdb  ")).isEqualTo("prod db");
        assertThat(SecretShareService.normaliseLabel("é".repeat(80))).hasSize(80);
        assertThatThrownBy(() -> SecretShareService.normaliseLabel("x".repeat(81)))
                .isInstanceOf(PublicBadRequestException.class);
    }

    @Test
    void displaySecondsCannotBeRaisedPastFiveMinutes() {
        var props = new SecretShareProperties();
        props.setDisplaySeconds(300);
        props.setDisplaySeconds(10);
        assertThat(props.getDisplaySeconds()).isEqualTo(10);
        assertThatThrownBy(() -> props.setDisplaySeconds(301)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setDisplaySeconds(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SecretShareProperties().getDisplaySeconds()).isEqualTo(300);
        assertThat(new SecretShareProperties().getLifetimes()).hasSize(3);
        assertThat(List.copyOf(new SecretShareProperties().getLifetimes())).contains(new SecretShareProperties().getDefaultLifetime());
    }
}
