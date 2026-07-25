/*
 * Copyright 2026 Olav Gjerde
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
import ai.intellistream.chat.service.AvatarService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage of {@link AvatarService}: storage, content-type sniffing, resize,
 * cap enforcement, replacement, and clear. Mirrors the avatar request flow from the
 * profile page minus the HTTP layer (the controller is a thin streaming adapter — the
 * interesting failure modes all live in the service).
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ichat.search.lucene-dir=build/test-lucene/AvatarFlowIT"
        }
)
class AvatarFlowIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    static Path avatarsDir;

    @BeforeAll
    static void prepareDirs() throws IOException {
        avatarsDir = Files.createTempDirectory("chat-avatars-it-");
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

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser() {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-avatar-" + n, "avatar" + n, "u" + n + "@example.com", "User " + n));
    }

    @Test
    void uploadStoresKeyAndContentTypeAndFile() throws IOException {
        var u = newUser();
        var bytes = pngBytes(64, 64, Color.BLUE);

        var saved = avatars.upload(u, "image/png", new ByteArrayInputStream(bytes));

        assertThat(saved.hasAvatar()).isTrue();
        assertThat(saved.getAvatarContentType()).isEqualTo("image/png");
        assertThat(saved.getAvatarUpdatedAt()).isNotNull();
        assertThat(saved.avatarVersion()).isPositive();

        var path = avatars.resolve(saved);
        assertThat(path).isNotNull();
        assertThat(Files.isRegularFile(path)).isTrue();
        assertThat(Files.size(path)).isPositive();
    }

    @Test
    void uploadResizesLargePng() throws IOException {
        var u = newUser();
        var bytes = pngBytes(1024, 768, Color.RED);

        var saved = avatars.upload(u, "image/png", new ByteArrayInputStream(bytes));

        var path = avatars.resolve(saved);
        var stored = ImageIO.read(path.toFile());
        assertThat(stored).isNotNull();
        assertThat(Math.max(stored.getWidth(), stored.getHeight())).isLessThanOrEqualTo(256);
        // Aspect ratio preserved
        assertThat(stored.getWidth()).isEqualTo(256);
        assertThat(stored.getHeight()).isEqualTo(192);
    }

    @Test
    void uploadSkipsResizeForSmallPng() throws IOException {
        var u = newUser();
        var bytes = pngBytes(128, 128, Color.GREEN);

        var saved = avatars.upload(u, "image/png", new ByteArrayInputStream(bytes));
        var stored = ImageIO.read(avatars.resolve(saved).toFile());

        assertThat(stored.getWidth()).isEqualTo(128);
        assertThat(stored.getHeight()).isEqualTo(128);
    }

    @Test
    void uploadResizesLargeJpegWithoutAlphaArtifacts() throws IOException {
        var u = newUser();
        var bytes = jpegBytes(800, 600, Color.ORANGE);

        var saved = avatars.upload(u, "image/jpeg", new ByteArrayInputStream(bytes));
        var stored = ImageIO.read(avatars.resolve(saved).toFile());

        assertThat(Math.max(stored.getWidth(), stored.getHeight())).isLessThanOrEqualTo(256);
        // Sample a centre-ish pixel — orange should still be roughly orange after JPEG round-trip.
        var rgb = new Color(stored.getRGB(stored.getWidth() / 2, stored.getHeight() / 2));
        assertThat(rgb.getRed()).isGreaterThan(150);
        assertThat(rgb.getGreen()).isBetween(100, 200);
    }

    @Test
    void rejectsUnsupportedContentType() {
        var u = newUser();
        var bytes = "not really an image".getBytes();

        assertThatThrownBy(() -> avatars.upload(u, "text/plain", new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    void rejectsOversizedUploadMidStream() {
        var u = newUser();
        // 6 MiB stream of zero bytes — still PNG-sniffed if we prepend the PNG magic? Actually
        // we want to be sure cap fires regardless of type, so prepend a PNG signature so the
        // sniff passes, then bulk junk to overflow.
        var oversized = oversizedPngLikeStream(6 * 1024 * 1024);

        assertThatThrownBy(() -> avatars.upload(u, "image/png", oversized))
                .isInstanceOf(ai.intellistream.chat.security.UploadTooLargeException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void replacingAvatarDeletesPreviousFile() throws IOException {
        var u = newUser();
        var first = avatars.upload(u, "image/png", new ByteArrayInputStream(pngBytes(48, 48, Color.CYAN)));
        var firstPath = avatars.resolve(first);
        assertThat(Files.exists(firstPath)).isTrue();

        var second = avatars.upload(u, "image/png", new ByteArrayInputStream(pngBytes(48, 48, Color.MAGENTA)));
        var secondPath = avatars.resolve(second);

        assertThat(secondPath).isNotEqualTo(firstPath);
        assertThat(Files.exists(secondPath)).isTrue();
        assertThat(Files.exists(firstPath)).isFalse();
    }

    @Test
    void clearRemovesFileAndUserFields() throws IOException {
        var u = newUser();
        var saved = avatars.upload(u, "image/png", new ByteArrayInputStream(pngBytes(48, 48, Color.YELLOW)));
        var path = avatars.resolve(saved);
        assertThat(Files.exists(path)).isTrue();

        var cleared = avatars.clear(saved);

        assertThat(cleared.hasAvatar()).isFalse();
        assertThat(cleared.getAvatarStorageKey()).isNull();
        assertThat(cleared.getAvatarContentType()).isNull();
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void resolveRefusesPathOutsideStorageRoot() {
        // A user row with a key that tries to escape — never created via upload(), but this
        // proves the path-traversal guard rejects it.
        var u = users.save(new User("kc-avatar-escape", "escape", "esc@example.com", "Escape"));
        u.setAvatar("../etc/passwd", "image/png");
        users.save(u);

        assertThatThrownBy(() -> avatars.resolve(u))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escaped");
    }

    @Test
    void resolveReturnsNullWhenNoAvatar() {
        var u = newUser();
        assertThat(avatars.resolve(u)).isNull();
    }

    @Test
    void contentTypeSniffOverridesDeclaredHeader() throws IOException {
        var u = newUser();
        // Real PNG bytes, but lying about the type — sniff should fix it up to image/png so
        // the upload is accepted (and stored with the truthful type).
        var saved = avatars.upload(u, "application/octet-stream",
                new ByteArrayInputStream(pngBytes(64, 64, Color.PINK)));

        assertThat(saved.getAvatarContentType()).isEqualTo("image/png");
    }

    private static byte[] pngBytes(int w, int h, Color colour) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(colour);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpegBytes(int w, int h, Color colour) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(colour);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    /**
     * Produces a stream that begins with a real PNG signature (so content-type sniff
     * passes) and then keeps emitting filler bytes until {@code totalSize} is reached.
     */
    private static InputStream oversizedPngLikeStream(int totalSize) {
        byte[] pngSig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        return new InputStream() {
            int produced = 0;
            @Override public int read() {
                if (produced >= totalSize) return -1;
                int b = produced < pngSig.length ? (pngSig[produced] & 0xFF) : 0x00;
                produced++;
                return b;
            }
            @Override public int read(byte[] buf, int off, int len) {
                if (produced >= totalSize) return -1;
                int n = Math.min(len, totalSize - produced);
                for (int i = 0; i < n; i++) {
                    buf[off + i] = produced < pngSig.length ? pngSig[produced] : 0x00;
                    produced++;
                }
                return n;
            }
        };
    }
}
