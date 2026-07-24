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

package ai.intellistream.threadorbit.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuceneConfig {

    @Bean
    @ConditionalOnMissingBean
    public MessageIndexService messageIndexService(
            @Value("${threadorbit.search.lucene-dir:./data/lucene}") String dir,
            @Value("${threadorbit.search.async-indexing:true}") boolean async) {
        // async batches the NRT refresh + commit off the per-message write path (throughput);
        // tests set it false for immediate, synchronous visibility. See scalability.md.
        return new MessageIndexService(dir, async);
    }
}
