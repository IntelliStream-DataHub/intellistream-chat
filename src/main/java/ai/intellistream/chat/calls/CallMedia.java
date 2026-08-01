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

package ai.intellistream.chat.calls;

/**
 * What the caller asked for. Carried through the invite so the callee's ring UI can say "video
 * call" and ask for the camera before answering rather than after — being dropped into a video call
 * you thought was audio is the kind of surprise that makes people stop using a feature.
 *
 * <p>This is the caller's <em>intent</em>, not a contract about what tracks exist. Either side can
 * mute or stop their camera at any point, and a video call where both cameras are off is still a
 * video call as far as this enum is concerned.
 */
public enum CallMedia {

    /** Microphone only. */
    AUDIO,

    /** Microphone and camera. */
    VIDEO
}
