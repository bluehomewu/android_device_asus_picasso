/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.lineageos.settings.picasso.nr.NrModeController.Callback;
import org.lineageos.settings.picasso.nr.NrModeController.Sim;
import org.lineageos.settings.picasso.nr.NrModeController.State;
import org.lineageos.settings.picasso.nr.NrModeController.Status;

/** Host tests execute the production controller, without Android, a SIM or a modem. */
public final class NrModeControllerTest {
    private static int passed;

    private static final class Clock implements NrModeController.Scheduler {
        private static final class Task implements NrModeController.Cancellable {
            final long when;
            final long order;
            final Runnable work;
            boolean cancelled;
            Task(long when, long order, Runnable work) {
                this.when = when;
                this.order = order;
                this.work = work;
            }
            @Override public void cancel() { cancelled = true; }
        }
        final Deque<Runnable> work = new ArrayDeque<>();
        final PriorityQueue<Task> timers = new PriorityQueue<>(
                Comparator.comparingLong((Task task) -> task.when)
                        .thenComparingLong(task -> task.order));
        boolean draining;
        long now;
        long order;

        @Override public void execute(Runnable runnable) {
            work.addLast(runnable);
            if (draining) return;
            draining = true;
            try {
                while (!work.isEmpty()) work.removeFirst().run();
            } finally {
                draining = false;
            }
        }

        @Override public NrModeController.Cancellable schedule(long delay, Runnable runnable) {
            Task task = new Task(now + delay, order++, runnable);
            timers.add(task);
            return task;
        }

        void advance(long duration) {
            long end = now + duration;
            while (!timers.isEmpty() && timers.peek().when <= end) {
                Task task = timers.remove();
                now = task.when;
                if (!task.cancelled) execute(task.work);
            }
            now = end;
        }
    }

    private static final class Request {
        final String kind;
        final int subscriptionId;
        final int slot;
        final int serial;
        final int mode;
        final Callback callback;
        boolean taken;
        boolean cancelled;
        boolean transmitted;
        Request(String kind, int subscriptionId, int slot, int serial, int mode, Callback callback) {
            this.kind = kind;
            this.subscriptionId = subscriptionId;
            this.slot = slot;
            this.serial = serial;
            this.mode = mode;
            this.callback = callback;
        }
        void ok(int reportedMode) { callback.onResult(serial, 0, reportedMode); }
        void error(int error) { callback.onResult(serial, error, -1); }
        void fail() { callback.onFailure(serial, "test transport failure"); }
        boolean dispatch() {
            if (cancelled) return false;
            transmitted = true;
            return true;
        }
    }

    private static final class Radio implements NrModeController.Backend {
        final List<Request> requests = new ArrayList<>();
        final List<Request> cancellations = new ArrayList<>();
        Map<Integer, Integer> activeOwners;
        int rejectedIdentity;
        boolean throwNext;
        boolean throwCancel;
        @Override public void query(int sub, int slot, int serial, Callback callback) {
            add("query", sub, slot, serial, -1, callback);
        }
        @Override public void set(int sub, int slot, int serial, int mode, Callback callback) {
            add("set", sub, slot, serial, mode, callback);
        }
        @Override public void cancel(int slot, int serial) {
            if (throwCancel) throw new IllegalStateException("test cancellation failure");
            for (Request request : requests) {
                if (request.slot == slot && request.serial == serial) {
                    cancellations.add(request);
                    if (!request.transmitted) request.cancelled = true;
                    return;
                }
            }
            throw new AssertionError("Cancelled an unknown slot/serial pair");
        }
        void add(String kind, int sub, int slot, int serial, int mode, Callback callback) {
            if (activeOwners != null && !Integer.valueOf(sub).equals(activeOwners.get(slot))) {
                rejectedIdentity++;
                callback.onFailure(serial, "SIM mapping changed before dispatch");
                return;
            }
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("test failure");
            }
            requests.add(new Request(kind, sub, slot, serial, mode, callback));
        }
        Request take(String kind, int slot) {
            for (Request request : requests) {
                if (!request.taken && request.slot == slot) {
                    eq(kind, request.kind);
                    request.taken = true;
                    return request;
                }
            }
            throw new AssertionError("Missing " + kind + " for slot " + slot);
        }
        int count(String kind) {
            return (int) requests.stream().filter(request -> kind.equals(request.kind)).count();
        }
    }

    private static final class Preferences implements NrModeController.Store {
        final Map<Integer, Integer> modes = new HashMap<>();
        boolean failRead;
        boolean failWrite;
        boolean throwWrite;
        int writes;
        @Override public int readMode(int sub, int fallback) {
            if (failRead) throw new IllegalStateException("test read failure");
            return modes.getOrDefault(sub, fallback);
        }
        @Override public boolean writeMode(int sub, int mode) {
            writes++;
            if (throwWrite) throw new IllegalStateException("test write failure");
            if (failWrite) return false;
            modes.put(sub, mode);
            return true;
        }
    }

    private static final class View implements NrModeController.Listener {
        final Map<Integer, State> states = new HashMap<>();
        final List<State> history = new ArrayList<>();
        final List<Integer> removed = new ArrayList<>();
        @Override public void onState(State state) {
            states.put(state.subscriptionId, state);
            history.add(state);
        }
        @Override public void onRemoved(int sub) {
            removed.add(sub);
            states.remove(sub);
        }
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final Radio radio = new Radio();
        final Preferences store = new Preferences();
        final View view = new View();
        final NrModeController controller = new NrModeController(radio, clock, store, view);
        void sims(Sim... sims) { controller.replaceSims(Arrays.asList(sims)); }
        void start() {
            sims(new Sim(10, 0));
            controller.onBackendConnected(0);
        }
        State state() { return view.states.get(10); }
        void status(Status status) { eq(status, state().status); }
        void applied(int mode) {
            status(Status.APPLIED);
            eq(mode, state().desiredMode);
            eq(Integer.valueOf(mode), state().verifiedMode);
        }
        void startApplied() { start(); radio.take("query", 0).ok(0); applied(0); }
        Request reachSet() {
            start();
            radio.take("query", 0).ok(1);
            status(Status.SETTING);
            return radio.take("set", 0);
        }
        Request reachVerify() {
            reachSet().ok(-999); // Set response payload is deliberately ignored.
            status(Status.VERIFYING);
            return radio.take("query", 0);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
    private static void yes(boolean value) { if (!value) throw new AssertionError("Expected true"); }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid argument");
    }
    private static void test(String name, Runnable action) {
        action.run();
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        test("default waits for connection and query confirms without write", () -> {
            Fixture f = new Fixture();
            f.sims(new Sim(10, 0));
            f.status(Status.WAITING);
            eq(0, f.radio.requests.size());
            f.controller.onBackendConnected(0);
            State immutable = f.state();
            f.radio.take("query", 0).ok(0);
            f.applied(0);
            eq(Status.QUERYING, immutable.status);
            eq(null, immutable.verifiedMode);
            eq(0, f.store.writes);
            eq(0, f.radio.count("set"));
        });
        test("independent slots and out-of-order callbacks", () -> {
            Fixture f = new Fixture();
            f.store.modes.put(10, 1);
            f.store.modes.put(20, 2);
            f.sims(new Sim(10, 0), new Sim(20, 1));
            f.controller.onBackendConnected(0);
            f.controller.onBackendConnected(1);
            Request first = f.radio.take("query", 0);
            Request second = f.radio.take("query", 1);
            eq(10, first.subscriptionId); eq(20, second.subscriptionId);
            yes(second.serial > first.serial);
            second.ok(2);
            eq(Status.QUERYING, f.state().status);
            eq(Status.APPLIED, f.view.states.get(20).status);
            first.ok(1);
            f.applied(1);
            eq(Integer.valueOf(2), f.view.states.get(20).verifiedMode);
        });
        test("set success alone cannot mark applied", () -> {
            Fixture f = new Fixture();
            Request set = f.reachSet();
            eq(10, set.subscriptionId);
            eq(0, set.mode);
            eq(Integer.valueOf(1), f.state().verifiedMode);
            set.ok(2);
            f.status(Status.VERIFYING);
            f.radio.take("query", 0).ok(0);
            f.applied(0);
        });
        test("initial query error is not masked and explicit refresh retries", () -> {
            Fixture f = new Fixture(); f.start();
            f.radio.take("query", 0).error(6);
            f.status(Status.ERROR);
            yes(f.state().detail.contains("6"));
            eq(0, f.radio.count("set"));
            f.controller.onNrModeChanged(0, 0);
            f.status(Status.ERROR);
            f.controller.refresh(10);
            f.radio.take("query", 0).ok(0);
            f.applied(0);
        });
        test("set radio error does not query or become applied from indication", () -> {
            Fixture f = new Fixture();
            f.reachSet().error(2);
            f.status(Status.ERROR);
            int count = f.radio.requests.size();
            f.controller.onNrModeChanged(0, 0);
            f.controller.onNrModeChanged(0, 1);
            f.clock.advance(100_000);
            f.status(Status.ERROR);
            yes(f.state().detail.contains("2"));
            eq(count, f.radio.requests.size());
        });
        test("query transport failure", () -> {
            Fixture f = new Fixture(); f.start();
            f.radio.take("query", 0).fail(); f.status(Status.ERROR);
            eq(0, f.radio.count("set"));
        });
        test("set transport failure", () -> {
            Fixture f = new Fixture(); f.reachSet().fail(); f.status(Status.ERROR);
            eq(1, f.radio.count("query"));
        });
        test("query timeout ignores late response", () -> {
            Fixture f = new Fixture(); f.start();
            Request old = f.radio.take("query", 0);
            f.clock.advance(NrModeController.REQUEST_TIMEOUT_MS);
            f.status(Status.ERROR); old.ok(0); f.status(Status.ERROR);
            eq(1, f.radio.requests.size());
        });
        test("set timeout ignores late success", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            f.clock.advance(NrModeController.REQUEST_TIMEOUT_MS);
            f.status(Status.ERROR); old.ok(0); f.status(Status.ERROR);
            eq(1, f.radio.count("query"));
        });
        test("timeout cancels only the matching queued write before releasing its slot", () -> {
            Fixture f = new Fixture();
            f.sims(new Sim(10, 0), new Sim(20, 1));
            f.controller.onBackendConnected(0);
            Request initial = f.radio.take("query", 0); initial.ok(1);
            Request queued = f.radio.take("set", 0);
            f.clock.advance(10_000);
            f.controller.onBackendConnected(1);
            Request other = f.radio.take("query", 1);
            f.clock.advance(10_000);
            eq(Collections.singletonList(queued), f.radio.cancellations);
            yes(!queued.dispatch()); yes(!initial.cancelled); yes(other.dispatch());
            f.status(Status.ERROR); other.ok(0);
            eq(Status.APPLIED, f.view.states.get(20).status);
            f.controller.refresh(10);
            Request fresh = f.radio.take("query", 0);
            yes(fresh.serial != queued.serial); yes(fresh.dispatch());
            queued.ok(0); f.status(Status.QUERYING); fresh.ok(0); f.applied(0);
        });
        test("timeout cancellation failure still fails closed and rejects late success", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            f.radio.throwCancel = true;
            f.clock.advance(NrModeController.REQUEST_TIMEOUT_MS);
            f.status(Status.ERROR); old.ok(0); f.status(Status.ERROR);
            eq(1, f.radio.count("query"));
        });
        test("timeout does not claim to revoke an already transmitted modem write", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet(); yes(old.dispatch());
            f.clock.advance(NrModeController.REQUEST_TIMEOUT_MS);
            eq(Collections.singletonList(old), f.radio.cancellations);
            yes(old.transmitted); yes(!old.cancelled);
            old.ok(0); f.status(Status.ERROR);
        });
        test("verification timeout ignores late matching mode", () -> {
            Fixture f = new Fixture(); Request old = f.reachVerify();
            f.clock.advance(NrModeController.REQUEST_TIMEOUT_MS);
            f.status(Status.ERROR); old.ok(0); f.status(Status.ERROR);
        });
        test("cache lag requery confirms without repeated writes", () -> {
            Fixture f = new Fixture(); f.reachVerify().ok(1);
            f.status(Status.VERIFYING);
            int count = f.radio.requests.size();
            f.clock.advance(NrModeController.VERIFY_RETRY_DELAY_MS - 1);
            eq(count, f.radio.requests.size());
            f.clock.advance(1); f.radio.take("query", 0).ok(1);
            f.clock.advance(NrModeController.VERIFY_RETRY_DELAY_MS);
            f.radio.take("query", 0).ok(0); f.applied(0);
            eq(1, f.radio.count("set"));
        });
        test("cache lag retry bound is exact and terminal", () -> {
            Fixture f = new Fixture(); Request query = f.reachVerify();
            for (int i = 0; i < NrModeController.MAX_VERIFY_QUERIES; i++) {
                query.ok(1);
                if (i + 1 < NrModeController.MAX_VERIFY_QUERIES) {
                    f.clock.advance(NrModeController.VERIFY_RETRY_DELAY_MS);
                    query = f.radio.take("query", 0);
                }
            }
            f.status(Status.ERROR);
            eq(1 + NrModeController.MAX_VERIFY_QUERIES, f.radio.count("query"));
            int count = f.radio.requests.size();
            f.clock.advance(1_000_000); eq(count, f.radio.requests.size());
            f.controller.onNrModeChanged(0, 0); f.status(Status.ERROR);
        });
        test("new user intent serializes behind old set", () -> {
            Fixture f = new Fixture(); f.startApplied();
            f.controller.setMode(10, 1);
            f.radio.take("query", 0).ok(0);
            Request old = f.radio.take("set", 0); eq(1, old.mode);
            int count = f.radio.requests.size();
            f.controller.setMode(10, 2); f.status(Status.WAITING);
            eq(2, f.state().desiredMode); eq(count, f.radio.requests.size());
            old.ok(1);
            f.radio.take("query", 0).ok(1);
            Request latest = f.radio.take("set", 0); eq(2, latest.mode);
            latest.ok(0); f.radio.take("query", 0).ok(2); f.applied(2);
            eq(Integer.valueOf(2), f.store.modes.get(10));
            old.error(9); f.applied(2);
        });
        test("new explicit intent may retry after superseded request error", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            f.controller.setMode(10, 2); old.error(6);
            f.radio.take("query", 0).ok(2); f.applied(2);
        });
        test("failed durable save does not modify desired or modem", () -> {
            Fixture f = new Fixture(); f.startApplied();
            f.store.failWrite = true; int count = f.radio.requests.size();
            f.controller.setMode(10, 2); f.status(Status.ERROR);
            eq(0, f.state().desiredMode); eq(count, f.radio.requests.size());
            eq(null, f.store.modes.get(10));
        });
        test("save exception invalidates in-flight success", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            f.store.throwWrite = true; int count = f.radio.requests.size();
            f.controller.setMode(10, 2); f.status(Status.ERROR);
            old.ok(0); f.status(Status.ERROR);
            eq(0, f.state().desiredMode); eq(count, f.radio.requests.size());
        });
        test("invalid stored mode fails closed until valid explicit save", () -> {
            Fixture f = new Fixture(); f.store.modes.put(10, 9); f.start();
            f.status(Status.ERROR); eq(0, f.radio.requests.size());
            f.controller.refresh(10); eq(0, f.radio.requests.size());
            f.controller.setMode(10, 1); f.radio.take("query", 0).ok(1); f.applied(1);
        });
        test("storage read exception cannot silently apply default", () -> {
            Fixture f = new Fixture(); f.store.failRead = true; f.start();
            f.status(Status.ERROR); eq(0, f.radio.requests.size());
        });
        test("unchanged SIM map and duplicate connect do not retry", () -> {
            Fixture f = new Fixture(); f.startApplied(); int count = f.radio.requests.size();
            f.sims(new Sim(10, 0)); f.controller.onBackendConnected(0);
            f.clock.advance(100_000); eq(count, f.radio.requests.size()); f.applied(0);
        });
        test("hot swap waits for old query and rejects its result", () -> {
            Fixture f = new Fixture(); f.start(); Request old = f.radio.take("query", 0);
            f.sims(new Sim(20, 0)); eq(Collections.singletonList(10), f.view.removed);
            eq(Status.WAITING, f.view.states.get(20).status);
            eq(1, f.radio.requests.size()); old.ok(0);
            eq(Status.QUERYING, f.view.states.get(20).status);
            Request fresh = f.radio.take("query", 0);
            eq(20, fresh.subscriptionId); fresh.ok(0);
            eq(Status.APPLIED, f.view.states.get(20).status);
            old.error(3); eq(Status.APPLIED, f.view.states.get(20).status);
        });
        test("hot swap cannot overlap old and new slot writes", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            eq(10, old.subscriptionId);
            f.store.modes.put(20, 2); f.sims(new Sim(20, 0));
            eq(2, f.radio.requests.size()); old.ok(0);
            f.radio.take("query", 0).ok(0);
            Request latest = f.radio.take("set", 0); eq(2, latest.mode);
            eq(20, latest.subscriptionId);
            latest.ok(0); f.radio.take("query", 0).ok(2);
            eq(Status.APPLIED, f.view.states.get(20).status);
            eq(2, f.view.states.get(20).desiredMode);
        });
        test("two subscriptions swapping slots invalidate both generations", () -> {
            Fixture f = new Fixture(); f.store.modes.put(10, 1); f.store.modes.put(20, 2);
            f.sims(new Sim(10, 0), new Sim(20, 1));
            f.controller.onBackendConnected(0); f.controller.onBackendConnected(1);
            Request old0 = f.radio.take("query", 0); Request old1 = f.radio.take("query", 1);
            f.sims(new Sim(10, 1), new Sim(20, 0)); eq(2, f.radio.requests.size());
            old0.ok(1); old1.ok(2);
            Request new1 = f.radio.take("query", 1);
            Request new0 = f.radio.take("query", 0);
            eq(10, new1.subscriptionId); eq(20, new0.subscriptionId);
            new1.ok(1); new0.ok(2);
            f.applied(1); eq(1, f.state().slotId);
            eq(Status.APPLIED, f.view.states.get(20).status);
            eq(0, f.view.states.get(20).slotId);
        });
        test("dispatch carries old identity until queued SIM mapping is replaced", () -> {
            Fixture f = new Fixture(); f.start(); Request old = f.radio.take("query", 0);
            // The Android adapter may observe hot-swap before replaceSims reaches this loop.
            // Its dispatch gate must reject this old owner's SET, not operate on the new SIM.
            f.radio.activeOwners = new HashMap<>();
            f.radio.activeOwners.put(0, 20);
            old.ok(1);
            eq(1, f.radio.rejectedIdentity); eq(0, f.radio.count("set"));
            f.sims(new Sim(20, 0));
            Request fresh = f.radio.take("query", 0); eq(20, fresh.subscriptionId);
            fresh.ok(0); eq(Status.APPLIED, f.view.states.get(20).status);
        });
        test("removal drains pending with no new request", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            f.sims(); old.ok(0); f.clock.advance(100_000);
            eq(2, f.radio.requests.size()); eq(0, f.view.states.size());
        });
        test("backend death and reconnect invalidate stale callback", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            f.controller.onBackendDied(0); f.status(Status.WAITING);
            eq(Collections.singletonList(old), f.radio.cancellations);
            yes(!old.dispatch());
            eq(null, f.state().verifiedMode);
            f.controller.onBackendConnected(0); Request fresh = f.radio.take("query", 0);
            yes(fresh.serial > old.serial); old.ok(0); f.status(Status.QUERYING);
            fresh.ok(0); f.applied(0); old.error(9); f.applied(0);
        });
        test("backend death cancels delayed verification", () -> {
            Fixture f = new Fixture(); f.reachVerify().ok(1);
            f.controller.onBackendDied(0); int count = f.radio.requests.size();
            f.clock.advance(100_000); eq(count, f.radio.requests.size());
            f.controller.onBackendConnected(0); f.radio.take("query", 0).ok(0); f.applied(0);
        });
        test("one automatic drift correction then no configuration fight", () -> {
            Fixture f = new Fixture(); f.startApplied();
            f.controller.onNrModeChanged(0, 1); f.status(Status.QUERYING);
            int count = f.radio.requests.size();
            f.controller.onNrModeChanged(0, 1); eq(count, f.radio.requests.size());
            f.radio.take("query", 0).ok(1); f.radio.take("set", 0).ok(0);
            f.radio.take("query", 0).ok(0); f.applied(0);
            count = f.radio.requests.size();
            f.controller.onNrModeChanged(0, 2); f.status(Status.ERROR);
            f.controller.onNrModeChanged(0, 1); f.clock.advance(100_000);
            eq(count, f.radio.requests.size());
            f.controller.refresh(10); f.radio.take("query", 0).ok(0); f.applied(0);
            f.controller.onNrModeChanged(0, 1); f.status(Status.QUERYING);
        });
        test("wrong serial and duplicate callbacks cannot complete another request", () -> {
            Fixture f = new Fixture(); f.start(); Request request = f.radio.take("query", 0);
            request.callback.onResult(request.serial + 1, 0, 0); f.status(Status.QUERYING);
            request.ok(1); f.status(Status.SETTING); request.ok(0); f.status(Status.SETTING);
            f.radio.take("set", 0).ok(0); f.radio.take("query", 0).ok(0); f.applied(0);
        });
        test("invalid public inputs fail before persistence or backend use", () -> {
            Fixture f = new Fixture();
            invalid(() -> f.controller.setMode(10, -1));
            invalid(() -> f.controller.setMode(10, 3));
            invalid(() -> f.controller.onNrModeChanged(0, 3));
            invalid(() -> f.controller.onBackendConnected(-1));
            invalid(() -> new Sim(-1, 0));
            invalid(() -> new Sim(1, -1));
            invalid(() -> f.sims(new Sim(1, 0), new Sim(1, 1)));
            invalid(() -> f.sims(new Sim(1, 0), new Sim(2, 0)));
            eq(0, f.radio.requests.size()); eq(0, f.store.writes);
        });
        test("invalid modem mode fails closed", () -> {
            Fixture f = new Fixture(); f.start(); f.radio.take("query", 0).ok(-1);
            f.status(Status.ERROR); eq(0, f.radio.count("set"));
        });
        test("no SIM does not issue commands or save unknown subscription", () -> {
            Fixture f = new Fixture(); f.controller.onBackendConnected(0);
            f.controller.setMode(10, 2); f.controller.refresh(10);
            f.controller.onNrModeChanged(0, 0);
            eq(0, f.radio.requests.size()); eq(0, f.store.writes);
        });
        test("close cancels timers and ignores all subsequent work", () -> {
            Fixture f = new Fixture(); Request old = f.reachSet();
            int count = f.radio.requests.size(); f.controller.close(); old.ok(0);
            eq(Collections.singletonList(old), f.radio.cancellations);
            yes(!old.dispatch());
            f.clock.advance(100_000); f.controller.onBackendConnected(0);
            f.sims(new Sim(20, 0)); f.controller.setMode(20, 2);
            eq(count, f.radio.requests.size()); eq(0, f.view.states.size());
            eq(Collections.singletonList(10), f.view.removed);
        });
        test("synchronous backend exception becomes error", () -> {
            Fixture f = new Fixture(); f.radio.throwNext = true; f.start();
            f.status(Status.ERROR); f.clock.advance(100_000); f.status(Status.ERROR);
        });
        test("verification radio error stops without cache-lag retry", () -> {
            Fixture f = new Fixture(); f.reachVerify().error(6); f.status(Status.ERROR);
            int count = f.radio.requests.size(); f.clock.advance(100_000);
            eq(count, f.radio.requests.size());
        });
        test("new preference cancels delayed cache query", () -> {
            Fixture f = new Fixture(); f.reachVerify().ok(1);
            f.controller.setMode(10, 2); f.radio.take("query", 0).ok(2); f.applied(2);
            int count = f.radio.requests.size(); f.clock.advance(100_000);
            eq(count, f.radio.requests.size()); f.applied(2);
        });
        test("controller recreation does not reuse process serial", () -> {
            Fixture first = new Fixture(); first.start();
            Request old = first.radio.take("query", 0); first.controller.close();
            Fixture second = new Fixture(); second.start();
            Request current = second.radio.take("query", 0);
            yes(current.serial > old.serial); old.ok(0); second.status(Status.QUERYING);
            current.ok(0); second.applied(0);
        });
        test("hot-swap pending timeout is bounded and old callback stays obsolete", () -> {
            Fixture f = new Fixture(); f.start(); Request old = f.radio.take("query", 0);
            f.sims(new Sim(20, 0)); f.clock.advance(NrModeController.REQUEST_TIMEOUT_MS);
            eq(Status.QUERYING, f.view.states.get(20).status);
            old.ok(0); eq(Status.QUERYING, f.view.states.get(20).status);
            f.radio.take("query", 0).ok(0); eq(Status.APPLIED, f.view.states.get(20).status);
        });

        Field serialField = NrModeController.class.getDeclaredField("NEXT_SERIAL");
        serialField.setAccessible(true);
        AtomicLong serials = (AtomicLong) serialField.get(null);
        long restore = serials.get();
        try {
            serials.set(Integer.MAX_VALUE);
            test("serial exhaustion fails closed instead of wrapping", () -> {
                Fixture f = new Fixture(); f.start(); Request last = f.radio.take("query", 0);
                eq(Integer.MAX_VALUE, last.serial); last.ok(0); f.applied(0);
                f.controller.refresh(10); f.status(Status.ERROR);
                eq(1, f.radio.requests.size());
            });
        } finally {
            serials.set(restore);
        }
        System.out.println("All " + passed + " controller tests passed.");
    }
}
