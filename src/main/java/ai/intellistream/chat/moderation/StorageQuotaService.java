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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.attachments.AttachmentBytes.Allowance;
import ai.intellistream.chat.domain.AdminAudit;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.domain.UserStorage;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.repository.UserStorageRepository;
import ai.intellistream.chat.security.StorageQuotaExceededException;
import ai.intellistream.chat.security.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-account attachment quotas, and the last line before the disk itself.
 *
 * <h2>What this is for, and what it is not</h2>
 * The filesystem quota is what stops the volume filling — a ZFS dataset quota on the attachments
 * directory means a runaway upload fails a write rather than taking the host, Postgres and the
 * Lucene index down with it. This service exists for the failure that happens <em>long before</em>
 * that: one account quietly consuming the space everybody else was going to need. By the time the
 * filesystem notices, the damage is shared; a per-account quota keeps it where it belongs, and
 * gives the admin screen a number to point at.
 *
 * <h2>Admins are exempt</h2>
 * An uploader whose per-file cap is {@link AttachmentBytes#UNLIMITED} — an admin, or a user an
 * operator has explicitly granted an unlimited {@code chat_max_upload_bytes} — is not blocked by a
 * total quota either. Two reasons. It would be an inconsistent story about one privilege: the
 * request already resolved "this account has no upload limit", and re-imposing one two layers down
 * is the kind of contradiction that gets discovered during an incident. And an admin is precisely
 * the person dealing with a storage problem; locking them out of uploading while they work on it
 * helps nobody. Their usage is still recorded, so the admin screen tells the truth about who is
 * using what — exemption from the limit is not exemption from the accounting.
 *
 * <p>Deriving the exemption from the already-resolved cap, rather than re-reading the principal,
 * also keeps role interpretation in {@code CurrentUser} where the project puts it, and keeps this
 * service usable from paths that have no {@code Principal} in hand.
 *
 * <h2>Accounting</h2>
 * Usage moves only through {@link UserStorageRepository#addBytes} — an atomic upsert, never a
 * read-then-write. Two uploads from the same account finishing at once would otherwise interleave
 * and lose one of the increments, which is exactly the case a quota exists to catch. Increments
 * ride the uploading transaction, so a rolled-back upload doesn't leave phantom usage behind;
 * decrements are the caller's job at the point attachments are actually removed from disk (see
 * {@link #release}).
 */
@Service
public class StorageQuotaService {

    private static final Logger log = LoggerFactory.getLogger(StorageQuotaService.class);

    /** Sanity bound on the admin screen's "biggest users" query — a UI page, not an export. */
    private static final int MAX_TOP_USERS = 500;

    private final UserStorageRepository storage;
    private final UserRepository users;
    private final AuditService audit;
    private final long defaultQuotaBytes;
    private final long minFreeBytes;

    public StorageQuotaService(UserStorageRepository storage,
                               UserRepository users,
                               AuditService audit,
                               @Value("${ichat.attachments.user-quota-bytes:2147483648}") long defaultQuotaBytes,
                               @Value("${ichat.attachments.min-free-bytes:67108864}") long minFreeBytes) {
        this.storage = storage;
        this.users = users;
        this.audit = audit;
        this.defaultQuotaBytes = defaultQuotaBytes;
        this.minFreeBytes = minFreeBytes;
    }

    /**
     * Default total per account, from {@code ichat.attachments.user-quota-bytes} (default 2 GiB).
     * Negative disables quotas deployment-wide. Overridden per account by
     * {@code user_storage.quota_bytes} — see {@link #setQuota}.
     */
    public long defaultQuotaBytes() {
        return defaultQuotaBytes;
    }

    // ------------------------------------------------------------------ upload path

    /**
     * Decide how much this upload may write, refusing outright when there is no room at all.
     *
     * <p>Called before a byte is read. The returned {@link Allowance} is then enforced by
     * {@code AttachmentBytes.streamToFile} as the bytes arrive, because {@code declaredSize} is
     * whatever the client put in {@code Content-Length} and a chunked request has none — checking
     * it here refuses the honest oversize cases cheaply, and is worth nothing against a dishonest
     * one.
     *
     * @param uploader          the account the bytes will be charged to
     * @param declaredSize      client-declared length, or a negative value when unknown
     * @param perUploadCapBytes the already-resolved per-file cap; {@link AttachmentBytes#UNLIMITED}
     *                          means this account is exempt from the total quota too (class javadoc)
     * @throws StorageQuotaExceededException the account is already full, or the declared length
     *                                       alone would not fit
     */
    @Transactional(readOnly = true)
    public Allowance allowanceFor(User uploader, long declaredSize, long perUploadCapBytes) {
        if (perUploadCapBytes < 0 || uploader == null || uploader.getId() == null) {
            return Allowance.UNMETERED;
        }
        var row = storage.findById(uploader.getId());
        var used = row.map(UserStorage::getBytesUsed).orElse(0L);
        var quota = row.map(UserStorage::getQuotaBytes).orElse(null);
        var effective = quota == null ? defaultQuotaBytes : quota;
        if (effective < 0) {
            return Allowance.UNMETERED;
        }

        var allowance = new Allowance(effective, used);
        var remaining = allowance.remaining();
        if (remaining <= 0 || (declaredSize > 0 && declaredSize > remaining)) {
            throw new StorageQuotaExceededException(effective, used);
        }
        return allowance;
    }

    /**
     * Charge a completed upload to its uploader.
     *
     * <p>Joins the caller's transaction on purpose: if the attachment row fails to save, the bytes
     * were never really stored (the file is removed by the rollback hook) and the usage increment
     * must go with it. Call this only once the write to disk has succeeded — never on the strength
     * of a declared length.
     */
    @Transactional
    public void recordUpload(User uploader, long bytes) {
        if (uploader == null || uploader.getId() == null || bytes <= 0) return;
        storage.addBytes(uploader.getId(), bytes);
    }

    // ------------------------------------------------------------------ deletion path

    /**
     * Credit bytes back to an account whose attachments have been removed.
     *
     * <p>Call this where the files actually leave the disk, which is not always where a user
     * "deletes" something: a soft-deleted message still occupies its bytes, so the credit belongs
     * to the retention purge that hard-deletes it, not to the click that hid it. Crediting early
     * would let an account delete and re-upload its way past the quota while nothing was ever
     * freed.
     *
     * <p>Runs in its own transaction when called outside one — the delete paths reap files after
     * their transaction has committed, and by then there is nothing left to join.
     */
    @Transactional
    public void release(long userId, long bytes) {
        if (bytes <= 0) return;
        storage.addBytes(userId, -bytes);
    }

    /** {@link #release(long, long)} for a resolved user. */
    @Transactional
    public void release(User owner, long bytes) {
        if (owner == null || owner.getId() == null) return;
        release(owner.getId(), bytes);
    }

    /**
     * Credit several accounts at once — one message's attachments can only belong to its author,
     * but deleting a whole channel frees bytes belonging to everyone who ever posted in it.
     *
     * @param bytesByUserId user id → bytes freed, as gathered <em>before</em> the rows were deleted
     */
    @Transactional
    public void releaseAll(Map<Long, Long> bytesByUserId) {
        if (bytesByUserId == null || bytesByUserId.isEmpty()) return;
        bytesByUserId.forEach((userId, bytes) -> {
            if (userId != null && bytes != null && bytes > 0) {
                storage.addBytes(userId, -bytes);
            }
        });
    }

    // ------------------------------------------------------------------ the disk itself

    /**
     * Refuse the upload if the attachments volume is nearly out of space, before anything is
     * written.
     *
     * <p>Not redundant with the ENOSPC handling in {@code AttachmentBytes}: that one is the
     * recovery, this one is the reserve. Letting uploads consume the final megabytes of a shared
     * volume is how Postgres loses the ability to write a WAL segment and Lucene loses the ability
     * to finish a merge — failures that are far more expensive than a refused upload, and much
     * harder to undo. {@code ichat.attachments.min-free-bytes} (default 64 MiB) is the floor;
     * raise it when the Lucene index shares the volume, since a merge transiently needs room for a
     * second copy of the segments it is merging.
     *
     * <p>A probe that fails is treated as "unknown" and lets the upload through. Refusing every
     * upload because {@code statvfs} did not answer would be a self-inflicted outage.
     */
    public void requireHeadroom(Path attachmentsDir) {
        if (minFreeBytes <= 0) return;
        var usable = AttachmentBytes.usableSpaceBytes(attachmentsDir);
        if (usable < 0 || usable >= minFreeBytes) return;
        log.error("Refusing uploads: only {} bytes free at {}, below the {}-byte floor "
                        + "(ichat.attachments.min-free-bytes). Free space or raise the dataset quota.",
                usable, attachmentsDir, minFreeBytes);
        throw new StorageUnavailableException("The server is low on storage space. Please try again later.");
    }

    // ------------------------------------------------------------------ admin read API

    /**
     * One account's storage line for the admin screen.
     *
     * @param quotaBytes          the per-account override, or null when the default applies —
     *                            kept distinct from {@code effectiveQuotaBytes} so the UI can show
     *                            "default (2 GiB)" rather than pretending someone set it
     * @param effectiveQuotaBytes what is actually enforced; negative means unlimited
     */
    public record Usage(Long userId, String username, String displayName,
                        long bytesUsed, Long quotaBytes, long effectiveQuotaBytes) {

        /** Never above 100, and 0 when unlimited — a progress bar needs a number it can draw. */
        public int percentUsed() {
            if (effectiveQuotaBytes <= 0) return 0;
            return (int) Math.min(100L, (bytesUsed * 100L) / effectiveQuotaBytes);
        }
    }

    @Transactional(readOnly = true)
    public long totalBytesUsed() {
        return storage.totalBytesUsed();
    }

    /** Usage for one account, including accounts that have never uploaded (no row yet). */
    @Transactional(readOnly = true)
    public Usage usageFor(User user) {
        var row = storage.findById(user.getId());
        var quota = row.map(UserStorage::getQuotaBytes).orElse(null);
        return new Usage(user.getId(), user.getUsername(), user.getDisplayName(),
                row.map(UserStorage::getBytesUsed).orElse(0L),
                quota, quota == null ? defaultQuotaBytes : quota);
    }

    /**
     * The biggest consumers first — what an admin actually wants when the volume is filling.
     *
     * <p>Two queries rather than a join: {@code user_storage} is one row per account that has ever
     * uploaded, so the page is small, and joining would mean adding a projection to the repository
     * that the foundation deliberately kept to two methods.
     */
    @Transactional(readOnly = true)
    public List<Usage> topUsers(int limit) {
        var page = storage.findAll(PageRequest.of(0, Math.clamp(limit, 1, MAX_TOP_USERS),
                Sort.by(Sort.Direction.DESC, "bytesUsed")));
        var rows = page.getContent();
        if (rows.isEmpty()) return List.of();
        var byId = users.findAllById(rows.stream().map(UserStorage::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream()
                .map(row -> {
                    var user = byId.get(row.getUserId());
                    return new Usage(row.getUserId(),
                            user == null ? null : user.getUsername(),
                            user == null ? null : user.getDisplayName(),
                            row.getBytesUsed(), row.getQuotaBytes(),
                            row.getQuotaBytes() == null ? defaultQuotaBytes : row.getQuotaBytes());
                })
                .toList();
    }

    // ------------------------------------------------------------------ admin write API

    /**
     * Set (or clear) one account's quota override.
     *
     * <p>{@code null} restores the configured default; any negative value means unlimited for this
     * account. Normalised to {@code -1} so the stored value reads the same way as the
     * {@link AttachmentBytes#UNLIMITED} sentinel everywhere else.
     *
     * <p>Audited: changing what an account is allowed to store is an administrative decision about
     * a person, and the trail is the difference between "their uploads stopped working" being an
     * answerable question and a mystery. The old value goes in the detail — the row records what
     * changed, not just that something did.
     */
    @Transactional
    public void setQuota(User actor, User target, Long quotaBytes) {
        var normalised = quotaBytes == null ? null : (quotaBytes < 0 ? -1L : quotaBytes);
        var row = storage.findById(target.getId()).orElseGet(() -> new UserStorage(target.getId()));
        var previous = row.getQuotaBytes();
        row.setQuotaBytes(normalised);
        storage.save(row);
        audit.recordOnUser(actor, AdminAudit.QUOTA_SET, target,
                "quota " + describe(previous) + " -> " + describe(normalised));
    }

    private String describe(Long quota) {
        if (quota == null) return "default(" + defaultQuotaBytes + ")";
        return quota < 0 ? "unlimited" : quota + " bytes";
    }
}
