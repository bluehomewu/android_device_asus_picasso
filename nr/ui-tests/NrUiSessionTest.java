/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr;

import java.util.HashMap;
import java.util.Map;

public final class NrUiSessionTest {
    private static int checks;
    private static void check(boolean condition) {
        ++checks;
        if (!condition) throw new AssertionError("Check " + checks);
    }
    private static Map<Integer, Integer> sims(int sub, int slot) {
        Map<Integer, Integer> result = new HashMap<>();
        result.put(sub, slot);
        return result;
    }
    public static void main(String[] ignored) {
        NrUiSession ui = new NrUiSession();
        Map<Integer, Integer> active = sims(10, 0);
        ui.update(true, false, false, false, active);
        check(ui.token(10, 0) == null);
        ui.start();
        NrUiSession.Token row = ui.token(10, 0);
        check(row != null);
        check(ui.token(10, 1) == null);
        check(ui.token(11, 0) == null);
        check(ui.choose(row, -1) == NrUiSession.Decision.REJECTED);
        check(ui.choose(row, 3) == NrUiSession.Decision.REJECTED);
        check(ui.choose(row, 0) == NrUiSession.Decision.SUBMIT);
        check(ui.choose(row, 1) == NrUiSession.Decision.SUBMIT);
        check(ui.choose(row, 2) == NrUiSession.Decision.CONFIRM_SA);
        NrUiSession.Token dialog = ui.pending();
        check(dialog != null && dialog != row);
        check(ui.confirm(ui.token(10, 0)) == NrUiSession.Decision.REJECTED);
        check(ui.confirm(dialog) == NrUiSession.Decision.SUBMIT);
        check(ui.confirm(dialog) == NrUiSession.Decision.REJECTED);
        ui.choose(row, 2);
        dialog = ui.pending();
        ui.cancel();
        check(ui.pending() == null);
        check(ui.confirm(dialog) == NrUiSession.Decision.REJECTED);
        ui.choose(row, 2);
        check(ui.confirm(dialog) == NrUiSession.Decision.REJECTED);
        dialog = ui.pending();
        ui.update(true, false, false, false, active);
        check(ui.confirm(dialog) == NrUiSession.Decision.SUBMIT);
        ui.choose(row, 2);
        dialog = ui.pending();
        ui.stop();
        check(ui.pending() == null);
        check(ui.confirm(dialog) == NrUiSession.Decision.REJECTED);
        ui.start();
        check(ui.choose(row, 0) == NrUiSession.Decision.REJECTED);
        for (int gate = 0; gate < 4; ++gate) {
            ui.update(true, false, false, false, active);
            row = ui.token(10, 0);
            ui.choose(row, 2);
            dialog = ui.pending();
            ui.update(gate != 0, gate == 1, gate == 2, gate == 3, active);
            check(ui.pending() == null);
            check(ui.token(10, 0) == null);
            check(ui.choose(row, 0) == NrUiSession.Decision.REJECTED);
            check(ui.confirm(dialog) == NrUiSession.Decision.REJECTED);
        }
        ui.update(true, false, false, false, active);
        row = ui.token(10, 0);
        ui.choose(row, 2);
        ui.update(true, false, false, false, new HashMap<>());
        check(ui.pending() == null);
        check(ui.token(10, 0) == null);
        ui.update(true, false, false, false, active);
        check(ui.choose(row, 0) == NrUiSession.Decision.REJECTED);
        row = ui.token(10, 0);
        ui.update(true, false, false, false, sims(10, 1));
        check(ui.choose(row, 0) == NrUiSession.Decision.REJECTED);
        check(ui.token(10, 1) != null);
        row = ui.token(10, 1);
        ui.update(true, false, false, false, sims(11, 1));
        check(ui.choose(row, 0) == NrUiSession.Decision.REJECTED);
        active = sims(10, 0);
        active.put(11, 1);
        ui.update(true, false, false, false, active);
        NrUiSession.Token first = ui.token(10, 0);
        NrUiSession.Token second = ui.token(11, 1);
        check(ui.choose(first, 2) == NrUiSession.Decision.CONFIRM_SA);
        NrUiSession.Token firstDialog = ui.pending();
        check(ui.choose(second, 2) == NrUiSession.Decision.CONFIRM_SA);
        NrUiSession.Token secondDialog = ui.pending();
        check(ui.confirm(firstDialog) == NrUiSession.Decision.REJECTED);
        check(ui.confirm(secondDialog) == NrUiSession.Decision.SUBMIT);
        active.clear();
        check(ui.token(10, 0) != null); // The session owns an immutable snapshot copy.
        System.out.println("NrUiSession: " + checks + " checks passed");
    }
}
