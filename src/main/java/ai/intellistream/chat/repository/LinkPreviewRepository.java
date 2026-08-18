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

package ai.intellistream.chat.repository;

import ai.intellistream.chat.domain.LinkPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LinkPreviewRepository extends JpaRepository<LinkPreview, Long> {

    Optional<LinkPreview> findByUrlHash(String urlHash);

    /** One query for a page of messages: every preview whose URL hash is in the set. */
    List<LinkPreview> findAllByUrlHashIn(Collection<String> urlHashes);

    Optional<LinkPreview> findByImageKey(String imageKey);

    /** Every image key on disk that a row still references — the live set for the orphan sweep. */
    @Query("select p.imageKey from LinkPreview p where p.imageKey is not null")
    List<String> findAllImageKeys();

    /** Rows nobody has posted since {@code before}, for retention. */
    List<LinkPreview> findAllByLastSeenAtBefore(Instant before);
}
