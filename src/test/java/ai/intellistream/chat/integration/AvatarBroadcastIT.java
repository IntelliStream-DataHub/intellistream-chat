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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.AvatarService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.AvatarRestController;
import ai.intellistream.chat.web.dto.UserEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that avatar uploads and clears fan out a {@link UserEvent} on
 * {@code /topic/users} so connected clients refresh in-flight without a reload.
 * The {@link SimpMessagingTemplate} is mocked — Spring's broker plumbing is
 * framework code we don't need to retest. Everything below it (the raw-body upload
 * reader, the file write, the user-row update) runs for real.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ichat.search.lucene-dir=build/test-lucene/AvatarBroadcastIT"
        }
)
class AvatarBroadcastIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    static Path avatarsDir;

    @BeforeAll
    static void prepareDirs() throws IOException {
        avatarsDir = Files.createTempDirectory("chat-avatars-bcast-it-");
    }

    @AfterAll
    static void cleanupDirs() throws IOException {
        if (avatarsDir != null && Files.exists(avatarsDir)) {
            try (var paths = Files.walk(avatarsDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("ichat.avatars.dir", () -> avatarsDir.toString());
    }

    @Autowired UserRepository users;
    @Autowired AvatarService avatars;

    private SimpMessagingTemplate broker;
    private CurrentUser currentUser;
    private UserService userService;
    private AvatarRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wireController() {
        broker = mock(SimpMessagingTemplate.class);
        currentUser = mock(CurrentUser.class);
        userService = mock(UserService.class);
        controller = new AvatarRestController(avatars, userService, currentUser, new RateLimiter(), broker);
    }

    @Test
    void uploadBroadcastsAvatarUpdated() throws IOException {
        var alice = newUser("alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var request = uploadRequest("avatar.png", "image/png", pngBytes(64, 64, Color.BLUE));

        var response = controller.upload(request, mock(Principal.class));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        var captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(broker).convertAndSend(eq("/topic/users"), captor.capture());

        var event = captor.getValue();
        assertThat(event.type()).isEqualTo("avatar-updated");
        assertThat(event.username()).isEqualTo(alice.getUsername());
        assertThat(event.avatarVersion()).isPositive();
    }

    @Test
    void clearBroadcastsAvatarRemoved() throws IOException {
        var alice = newUser("clearer");
        // Seed an existing avatar so clear() has a file to delete.
        avatars.upload(alice, "image/png", new ByteArrayInputStream(pngBytes(64, 64, Color.RED)));
        var refreshed = users.findById(alice.getId()).orElseThrow();
        when(currentUser.resolve(any(Principal.class))).thenReturn(refreshed);

        var response = controller.clear(mock(Principal.class));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        var captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(broker).convertAndSend(eq("/topic/users"), captor.capture());

        var event = captor.getValue();
        assertThat(event.type()).isEqualTo("avatar-removed");
        assertThat(event.username()).isEqualTo(alice.getUsername());
    }

    @Test
    void uploadBroadcastsFreshVersionOnReplace() throws IOException {
        var alice = newUser("replacer");
        avatars.upload(alice, "image/png", new ByteArrayInputStream(pngBytes(48, 48, Color.GREEN)));
        var withFirst = users.findById(alice.getId()).orElseThrow();
        var firstVersion = withFirst.avatarVersion();
        when(currentUser.resolve(any(Principal.class))).thenReturn(withFirst);

        // Force a measurable gap so the new avatar_updated_at timestamp differs.
        sleepMs(5);
        var request = uploadRequest("avatar.png", "image/png", pngBytes(48, 48, Color.YELLOW));
        controller.upload(request, mock(Principal.class));

        var captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(broker).convertAndSend(eq("/topic/users"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("avatar-updated");
        assertThat(captor.getValue().avatarVersion()).isGreaterThan(firstVersion);
    }

    @Test
    void rejectedUploadDoesNotBroadcast() {
        var alice = newUser("rejected");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var request = uploadRequest("evil.txt", "text/plain", "definitely not an image".getBytes());

        assertThatThrownBy(() -> controller.upload(request, mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(broker, never()).convertAndSend(eq("/topic/users"), any(UserEvent.class));
    }

    @Test
    void rateLimitedUploadDoesNotBroadcast() {
        var alice = newUser("ratelimited");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        // 5/min cap in the controller — sixth request should be refused before the broadcast.
        for (int i = 0; i < 5; i++) {
            var ok = uploadRequest("ok.png", "image/png", pngBytes(32, 32, Color.GRAY));
            try {
                controller.upload(ok, mock(Principal.class));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        var sixth = uploadRequest("over.png", "image/png", pngBytes(32, 32, Color.GRAY));

        assertThatThrownBy(() -> controller.upload(sixth, mock(Principal.class)))
                .isInstanceOf(ai.intellistream.chat.security.RateLimitExceededException.class);

        // Five accepted broadcasts, none for the throttled sixth.
        verify(broker, org.mockito.Mockito.times(5))
                .convertAndSend(eq("/topic/users"), any(UserEvent.class));
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-bcast-" + n + "-" + label,
                "bcast-" + n + "-" + label,
                label + n + "@example.com",
                "Cast " + n));
    }

    /**
     * Build a raw-body upload: the bytes <em>are</em> the request body and the metadata rides in
     * headers. This is the wire format the endpoint takes now — no multipart wrapper to build,
     * which is rather the point of the change.
     */
    private static MockHttpServletRequest uploadRequest(String filename, String contentType,
                                                        byte[] data) {
        var req = new MockHttpServletRequest("POST", "/api/profile/avatar");
        req.setContentType(contentType);
        req.addHeader("X-Upload-Filename", URLEncoder.encode(filename, StandardCharsets.UTF_8));
        req.setContent(data);
        return req;
    }

    private static byte[] pngBytes(int w, int h, Color colour) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(colour);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
