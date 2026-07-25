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

package ai.intellistream.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A one-shot "this message is now durable (or isn't)" signal, used to hold a broadcast back until
 * the row it describes has actually committed.
 *
 * <p>The caller registers what to do with {@link #whenDurable}; the writer calls {@link #committed}
 * or {@link #failed} exactly once. Either order works — a batch that commits before the caller has
 * finished rendering its payload simply causes the action to run inline on registration. On
 * {@link #failed} the action never runs, which is the point: a message whose INSERT was lost must
 * not have been shown to anybody.
 */
public final class Durability {

    private static final Logger log = LoggerFactory.getLogger(Durability.class);

    private enum State { PENDING, COMMITTED, FAILED }

    private State state = State.PENDING;
    private Runnable action;

    /** A handle that is already durable — the synchronous path, where the commit has happened. */
    public static Durability alreadyCommitted() {
        var handle = new Durability();
        handle.state = State.COMMITTED;
        return handle;
    }

    /**
     * Run {@code action} once the message is known to be persisted, or immediately if it already
     * is. Never runs if the write failed.
     */
    public void whenDurable(Runnable action) {
        boolean runNow;
        synchronized (this) {
            switch (state) {
                case PENDING -> {
                    this.action = action;
                    runNow = false;
                }
                case COMMITTED -> runNow = true;
                default -> runNow = false; // FAILED — deliberately drop it
            }
        }
        if (runNow) {
            run(action);
        }
    }

    /** Signal success. Runs any registered action on the calling thread. */
    public void committed() {
        Runnable pending;
        synchronized (this) {
            if (state != State.PENDING) return;
            state = State.COMMITTED;
            pending = action;
            action = null;
        }
        if (pending != null) {
            run(pending);
        }
    }

    /** Signal failure. Any registered action is discarded and never runs. */
    public void failed() {
        synchronized (this) {
            if (state != State.PENDING) return;
            state = State.FAILED;
            action = null;
        }
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // A failed broadcast must not take down the flusher thread that is committing other
            // people's messages.
            log.warn("Post-commit action (message broadcast) failed", e);
        }
    }
}
