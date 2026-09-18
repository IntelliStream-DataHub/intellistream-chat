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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address recorded for an anonymous opener comes from a header the client can write. Only a
 * genuine IP literal survives, re-rendered from the parsed address.
 */
class ClientAddressTest {

    @Test
    void ipv4AndIpv6LiteralsAreKept() {
        assertThat(ClientAddress.literalOrNull("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.literalOrNull(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.literalOrNull("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(ClientAddress.literalOrNull("[2001:db8::1]")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(ClientAddress.literalOrNull("::1")).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void anythingElseIsUnknownRatherThanStoredVerbatim() {
        assertThat(ClientAddress.literalOrNull(null)).isNull();
        assertThat(ClientAddress.literalOrNull("")).isNull();
        // A hostname would need DNS, and is text the client chose.
        assertThat(ClientAddress.literalOrNull("evil.example")).isNull();
        assertThat(ClientAddress.literalOrNull("localhost")).isNull();
        // An unsplit X-Forwarded-For list.
        assertThat(ClientAddress.literalOrNull("1.2.3.4, 5.6.7.8")).isNull();
        // Markup and Markdown meant for the receipt.
        assertThat(ClientAddress.literalOrNull("<script>alert(1)</script>")).isNull();
        assertThat(ClientAddress.literalOrNull("[click](https://evil.example)")).isNull();
        assertThat(ClientAddress.literalOrNull("1.2.3.4**bold**")).isNull();
        // Scoped IPv6 and oversize input.
        assertThat(ClientAddress.literalOrNull("fe80::1%eth0")).isNull();
        assertThat(ClientAddress.literalOrNull("1".repeat(200))).isNull();
    }
}
