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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.paste.HtmlToMarkdownConverter;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class PasteRestControllerTest {

    private final HtmlToMarkdownConverter converter = Mockito.mock(HtmlToMarkdownConverter.class);
    private final CurrentUser currentUser = Mockito.mock(CurrentUser.class);
    private final RateLimiter rateLimiter = Mockito.mock(RateLimiter.class);
    private final Principal principal = Mockito.mock(Principal.class);

    private final PasteRestController controller =
            new PasteRestController(converter, currentUser, rateLimiter);

    {
        var user = Mockito.mock(User.class);
        Mockito.when(user.getUsername()).thenReturn("alice");
        Mockito.when(currentUser.resolve(any(Principal.class))).thenReturn(user);
        Mockito.when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    void happyPathDelegatesToTheConverter() {
        Mockito.when(converter.convert("<p><b>x</b></p>"))
                .thenReturn(new HtmlToMarkdownConverter.Result(true, "**x**"));
        var response = controller.convert(
                new PasteRestController.PasteMarkdownRequest("<p><b>x</b></p>"), principal);
        assertThat(response.convert()).isTrue();
        assertThat(response.markdown()).isEqualTo("**x**");
    }

    @Test
    void notConvertibleComesBackAsConvertFalse() {
        Mockito.when(converter.convert(anyString()))
                .thenReturn(HtmlToMarkdownConverter.Result.notConvertible());
        var response = controller.convert(
                new PasteRestController.PasteMarkdownRequest("<div>plain</div>"), principal);
        assertThat(response.convert()).isFalse();
        assertThat(response.markdown()).isNull();
    }

    @Test
    void oversizeInputIsPlainWithoutTouchingTheConverter() {
        var response = controller.convert(new PasteRestController.PasteMarkdownRequest(
                "y".repeat(HtmlToMarkdownConverter.MAX_HTML_CHARS + 1)), principal);
        assertThat(response.convert()).isFalse();
        Mockito.verifyNoInteractions(converter);
    }

    @Test
    void missingBodyIsPlainNotAnError() {
        assertThat(controller.convert(new PasteRestController.PasteMarkdownRequest(null), principal)
                .convert()).isFalse();
        Mockito.verifyNoInteractions(converter);
    }

    @Test
    void rateLimitDenialThrows() {
        Mockito.when(rateLimiter.tryAcquire(eq("alice"), eq("paste-convert"), anyInt(), any(Duration.class)))
                .thenReturn(false);
        assertThatThrownBy(() -> controller.convert(
                new PasteRestController.PasteMarkdownRequest("<b>x</b>"), principal))
                .isInstanceOf(RateLimitExceededException.class);
        Mockito.verifyNoInteractions(converter);
    }
}
