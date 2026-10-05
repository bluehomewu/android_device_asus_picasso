/* SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr;

import java.util.HashMap;
import java.util.Map;

/** Main-thread UI guard. Tokens are never saved across Activity lifecycles. */
final class NrUiSession {
    enum Decision { REJECTED, CONFIRM_SA, SUBMIT }

    static final class Token {
        final int subscriptionId;
        final int slotId;
        private final long generation;
        private Token(int sub, int slot, long generation) {
            subscriptionId = sub;
            slotId = slot;
            this.generation = generation;
        }
    }

    private boolean started;
    private boolean allowed;
    private long generation;
    private Map<Integer, Integer> mappings = new HashMap<>();
    private Token pending;

    void start() { started = true; invalidate(); }
    void stop() { started = false; invalidate(); }

    void update(boolean owner, boolean restricted, boolean airplane, boolean backendError,
            Map<Integer, Integer> eligibleMappings) {
        boolean nextAllowed = owner && !restricted && !airplane && !backendError;
        if (allowed != nextAllowed || !mappings.equals(eligibleMappings)) invalidate();
        allowed = nextAllowed;
        mappings = new HashMap<>(eligibleMappings);
    }

    Token token(int sub, int slot) {
        Token token = new Token(sub, slot, generation);
        return valid(token) ? token : null;
    }

    Decision choose(Token token, int mode) {
        if (!valid(token) || mode < 0 || mode > 2) return Decision.REJECTED;
        pending = null;
        if (mode == 2) {
            // A canceled dialog must not authorize a later dialog for the same row.
            pending = new Token(token.subscriptionId, token.slotId, generation);
            return Decision.CONFIRM_SA;
        }
        return Decision.SUBMIT;
    }

    Decision confirm(Token token) {
        if (token == null || pending != token || !valid(token)) return Decision.REJECTED;
        pending = null;
        return Decision.SUBMIT;
    }

    void cancel() { pending = null; }
    Token pending() { return pending; }
    long generation() { return generation; }

    private boolean valid(Token token) {
        return started && allowed && token != null && token.generation == generation
                && token.subscriptionId >= 0 && token.slotId >= 0 && token.slotId <= 1
                && Integer.valueOf(token.slotId).equals(mappings.get(token.subscriptionId));
    }

    private void invalidate() { ++generation; pending = null; }
}
