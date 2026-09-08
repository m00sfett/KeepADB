package de.hohnepeople.keepadb;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic {@link KeepADBScheduler} fake shared by tests that need to control KeepADB's
 * and KeepADBEndpoint's debounce, watchdog, and recovery-pulse timing without real threads or
 * real elapsed time (#249). {@link #runAsync} runs synchronously, and {@link #sleep} advances
 * the virtual clock instead of blocking -- both deliberately unify what production code keeps
 * as two separate clock sources, purely so tests stay deterministic; this class does not model
 * concurrency, only ordering.
 */
final class KeepADBFakeScheduler implements KeepADBScheduler {
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Runnable> deferredAsync = new ArrayList<>();
    private boolean deferAsync;
    private long clockMs;

    private static final class Scheduled {
        final Runnable runnable;
        final long dueAtMs;
        boolean cancelled;

        Scheduled(Runnable runnable, long dueAtMs) {
            this.runnable = runnable;
            this.dueAtMs = dueAtMs;
        }
    }

    void setClockMs(long clockMs) {
        this.clockMs = clockMs;
    }

    long clockMs() {
        return clockMs;
    }

    /**
     * Advances the virtual clock to {@code newClockMs} and runs every not-yet-cancelled
     * runnable now due, in the order they were originally scheduled. A runnable that schedules
     * another runnable during this call does not itself get run again within the same
     * {@code advanceTo} -- it is picked up by a later call, mirroring a real Handler's queue
     * being drained once per loop iteration rather than recursively.
     */
    void advanceTo(long newClockMs) {
        clockMs = newClockMs;
        List<Scheduled> due = new ArrayList<>();
        for (Scheduled entry : scheduled) {
            if (!entry.cancelled && entry.dueAtMs <= clockMs) {
                due.add(entry);
            }
        }
        scheduled.removeAll(due);
        for (Scheduled entry : due) {
            entry.runnable.run();
        }
    }

    void advanceBy(long deltaMs) {
        advanceTo(clockMs + deltaMs);
    }

    boolean hasPending(Runnable runnable) {
        for (Scheduled entry : scheduled) {
            if (!entry.cancelled && entry.runnable == runnable) return true;
        }
        return false;
    }

    boolean hasAnyPending() {
        for (Scheduled entry : scheduled) {
            if (!entry.cancelled) return true;
        }
        return false;
    }

    @Override
    public void postDelayed(Runnable runnable, long delayMs) {
        scheduled.add(new Scheduled(runnable, clockMs + delayMs));
    }

    @Override
    public void removeCallbacks(Runnable runnable) {
        for (Scheduled entry : scheduled) {
            if (entry.runnable == runnable) entry.cancelled = true;
        }
    }

    /**
     * Makes {@link #runAsync} queue its runnable instead of running it inline (#309), so a test
     * can act on KeepADB's state in the gap between a caller handing work to the scheduler and
     * that work actually starting -- the gap a real background thread has too.
     */
    void setDeferAsync(boolean deferAsync) {
        this.deferAsync = deferAsync;
    }

    /** Runs every runnable queued by {@link #runAsync} while {@link #setDeferAsync} was on. */
    void runDeferredAsync() {
        List<Runnable> due = new ArrayList<>(deferredAsync);
        deferredAsync.clear();
        for (Runnable runnable : due) {
            runnable.run();
        }
    }

    @Override
    public void runAsync(Runnable runnable) {
        if (deferAsync) {
            deferredAsync.add(runnable);
            return;
        }
        runnable.run();
    }

    @Override
    public void sleep(long delayMs) {
        clockMs += delayMs;
    }

    @Override
    public long elapsedRealtimeMs() {
        return clockMs;
    }
}
