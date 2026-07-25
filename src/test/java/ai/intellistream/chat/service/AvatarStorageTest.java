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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where an avatar sits relative to the two storage limits.
 *
 * <p>Avatars used to write with a bare {@code Files.write}, which meant they were the one upload
 * path that neither respected the free-space reserve nor turned a full volume into anything more
 * useful than a 500. They are metered against the volume for the same reason attachments are —
 * Postgres still needs room for a WAL segment — and deliberately <em>not</em> metered against the
 * per-account quota, because one replaceable 5 MiB-capped file per account is bounded by the user
 * count rather than by behaviour, and charging it would leave an account that filled its quota with
 * attachments unable to fix or even clear its own profile picture.
 */
class AvatarStorageTest {

    private UserRepository users;
    private StorageQuotaService quotas;
    private AvatarService service;
    private Path storageRoot;

    private final User alice = user(1L, "alice");

    @BeforeEach
    void setUp(@TempDir Path dir) {
        storageRoot = dir;
        users = mock(UserRepository.class);
        quotas = mock(StorageQuotaService.class);
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        service = new AvatarService(users, quotas, dir.toString());
    }

    @Test
    void anAvatarIsWrittenAndPointedAtByTheUserRow() throws IOException {
        var saved = service.upload(alice, "image/png", new ByteArrayInputStream(png()));

        assertThat(saved.getAvatarStorageKey()).isNotNull();
        assertThat(storageRoot.resolve(saved.getAvatarStorageKey())).exists();
        assertThat(saved.getAvatarContentType()).isEqualTo("image/png");
    }

    @Test
    void theFreeSpaceReserveIsCheckedAgainstTheAvatarDirectory() throws IOException {
        service.upload(alice, "image/png", new ByteArrayInputStream(png()));

        // The avatar store can be a different volume from the attachment store; the only free space
        // that means anything is the one about to be written to.
        verify(quotas).requireHeadroom(storageRoot);
    }

    @Test
    void aVolumeWithoutHeadroomRefusesTheUploadBeforeAnythingIsWritten() {
        doThrow(new StorageUnavailableException("The server is low on storage space."))
                .when(quotas).requireHeadroom(any());

        // StorageUnavailableException is what ApiExceptionHandler answers 507 to. The point of
        // refusing here rather than letting the write fail is that the last megabytes of a shared
        // volume are worth more to Postgres and Lucene than to a profile picture.
        assertThatThrownBy(() -> service.upload(alice, "image/png", new ByteArrayInputStream(png())))
                .isInstanceOf(StorageUnavailableException.class);

        assertThat(storageRoot).isEmptyDirectory();
    }

    @Test
    void anAvatarIsNotChargedToTheAccountsQuota() throws IOException {
        service.upload(alice, "image/png", new ByteArrayInputStream(png()));

        // Deliberate — see the class javadoc. If this ever flips, the replace path needs a credit
        // for the file it supersedes, or usage climbs by one avatar per change.
        verify(quotas, never()).recordUpload(any(), anyLong());
        verify(quotas, never()).allowanceFor(any(), anyLong(), anyLong());
    }

    @Test
    void aNonImageIsRejectedBeforeTheDiskIsTouchedAtAll() {
        assertThatThrownBy(() -> service.upload(alice, "text/html",
                new ByteArrayInputStream("<html>not an image</html>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(storageRoot).isEmptyDirectory();
    }

    // ------------------------------------------------------------------ helpers

    /** A real 1×1 PNG — the content type is sniffed from the bytes, so a fake header won't do. */
    private static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
