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

package com.example.chat.web.dto;

import java.time.Instant;

/**
 * Request body for {@code POST /api/presence/status}. All fields are optional; sending an
 * empty {@code emoji} and blank {@code text} is equivalent to {@code DELETE /api/presence/status}.
 * {@code clearAt} is an absolute UTC instant — clients compute it from a duration shortcut
 * ("clear in 30m") and send the resolved time so server clock skew doesn't matter.
 */
public record SetStatusRequest(String emoji, String text, Instant clearAt) {}
