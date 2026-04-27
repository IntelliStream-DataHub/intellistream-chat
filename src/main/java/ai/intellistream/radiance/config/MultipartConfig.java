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

package ai.intellistream.radiance.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import java.util.regex.Pattern;

/**
 * Disable Spring's multipart parsing for the attachment upload endpoint so we can
 * stream the request body directly with Apache Commons FileUpload — letting us handle
 * arbitrarily large files without buffering them in memory or on disk through the
 * servlet container's multipart machinery.
 */
@Configuration
public class MultipartConfig {

    static final Pattern ATTACHMENT_UPLOAD =
            Pattern.compile("^/api/channels/[^/]+/attachments/?$");
    static final Pattern CONVERSATION_ATTACHMENT_UPLOAD =
            Pattern.compile("^/api/conversations/[^/]+/attachments/?$");
    static final Pattern AVATAR_UPLOAD =
            Pattern.compile("^/api/profile/avatar/?$");

    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override
            public boolean isMultipart(HttpServletRequest request) {
                if (!"POST".equalsIgnoreCase(request.getMethod())) return super.isMultipart(request);
                var uri = request.getRequestURI();
                if (ATTACHMENT_UPLOAD.matcher(uri).matches()) return false;
                if (CONVERSATION_ATTACHMENT_UPLOAD.matcher(uri).matches()) return false;
                if (AVATAR_UPLOAD.matcher(uri).matches()) return false;
                return super.isMultipart(request);
            }
        };
    }
}
