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

/*
 * The emoji picker's "Recently used" shortcut.
 *
 * Worth a check because both of its states are easy to break and neither announces itself: a bad
 * write leaves the list silently empty forever, and a bad empty-case leaves an empty section
 * promising a feature that is not there. The list is also the one piece of picker state that
 * survives a reload, so it is the one piece that can rot.
 *
 * Restores whatever the real list held in a finally — this runs against the live page and a smoke
 * test must not cost somebody their shortcuts.
 */

import { add } from './registry.js';

const KEY = 'ichat.emoji.recent';

add('emoji recents round-trip through storage', () => {
    const kit = window.ChatKit;
    if (!kit || !kit.emojiRecents) throw new Error('ChatKit.emojiRecents missing — chat-kit.js is stale');
    const saved = localStorage.getItem(KEY);
    try {
        localStorage.removeItem(KEY);
        if (kit.emojiRecents.read().length !== 0) throw new Error('a cleared list is not empty');

        kit.emojiRecents.remember('🚀');
        kit.emojiRecents.remember('🎉');
        const after = kit.emojiRecents.read();
        // Most recent first — that ordering IS the feature.
        if (after[0] !== '🎉' || after[1] !== '🚀') {
            throw new Error('expected [🎉,🚀], got ' + JSON.stringify(after));
        }

        // Re-picking moves rather than duplicates.
        kit.emojiRecents.remember('🚀');
        const moved = kit.emojiRecents.read();
        if (moved[0] !== '🚀' || moved.length !== 2) {
            throw new Error('re-picking should move, not duplicate: ' + JSON.stringify(moved));
        }
    } finally {
        if (saved === null) localStorage.removeItem(KEY);
        else localStorage.setItem(KEY, saved);
    }
});

add('emoji recents stay capped', () => {
    const kit = window.ChatKit;
    const saved = localStorage.getItem(KEY);
    try {
        localStorage.removeItem(KEY);
        // An uncapped list would grow without bound and eventually push the picker off the screen.
        for (const c of '🚀🎉👍👎❤️😂👀🙏🔥💯✅❌😀😃😄😁😆😅🤣🙂🙃😉😊😇🤩😘😗') {
            kit.emojiRecents.remember(c);
        }
        const list = kit.emojiRecents.read();
        if (list.length > kit.emojiRecents.max) {
            throw new Error('recents grew past ' + kit.emojiRecents.max + ': ' + list.length);
        }
    } finally {
        if (saved === null) localStorage.removeItem(KEY);
        else localStorage.setItem(KEY, saved);
    }
});

add('the picker leads with recents once there are any, and not before', () => {
    const kit = window.ChatKit;
    // The composer's emoji button; absent on a page with no composer (an archived channel, or a
    // public channel the viewer has not joined), where there is nothing to anchor a picker to.
    const anchor = document.getElementById('composer-emoji');
    if (!anchor) return;
    const saved = localStorage.getItem(KEY);
    const firstTabTitle = () => {
        const tab = document.querySelector('.emoji-picker .emoji-picker-tab');
        return tab ? tab.title : null;
    };
    try {
        localStorage.removeItem(KEY);
        kit.openEmojiPicker(anchor, () => {});
        if (firstTabTitle() === 'Recently used') {
            throw new Error('an empty recents list still rendered a Recently used tab');
        }
        kit.closeEmojiPicker();

        kit.emojiRecents.remember('🚀');
        kit.openEmojiPicker(anchor, () => {});
        if (firstTabTitle() !== 'Recently used') {
            throw new Error('recents exist but the picker did not lead with them (first tab: '
                + firstTabTitle() + ')');
        }
    } finally {
        kit.closeEmojiPicker();
        if (saved === null) localStorage.removeItem(KEY);
        else localStorage.setItem(KEY, saved);
    }
});
