package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link KeepADBSettingsGateway} fake for #500: the write is accepted <em>and</em> the very next
 * readback reports "on" -- and only {@code holdMs} later does the value fall back to "off" again,
 * as the real Android system does when Wireless Debugging is switched on for a network whose
 * pairing dialog was never confirmed.
 *
 * <p>This is the case {@link KeepADBStuckOffSettingsGateway} cannot express: that one never
 * reports "on" at all, so the readback taken immediately after the write already revealed the
 * mismatch. On the device the readback came back {@code true} every single time, the backoff
 * booked a success, and the retry loop it was supposed to bound ran on unchanged (186 attempts in
 * 72 seconds). The clock is injected so the same fake works with {@link KeepADBFakeScheduler}'s
 * virtual clock and with Robolectric's {@code SystemClock}.
 */
final class KeepADBRevertingSettingsGateway implements KeepADBSettingsGateway {

    interface Clock {
        long elapsedRealtimeMs();
    }

    final List<Boolean> writes = new ArrayList<>();

    private final Clock clock;
    private final long holdMs;
    private long enabledUntilMs = Long.MIN_VALUE;

    KeepADBRevertingSettingsGateway(Clock clock, long holdMs) {
        this.clock = clock;
        this.holdMs = holdMs;
    }

    @Override
    public boolean isEnabled(Context context) {
        return clock.elapsedRealtimeMs() < enabledUntilMs;
    }

    @Override
    public boolean write(Context appContext, boolean on) {
        writes.add(on);
        enabledUntilMs = on ? clock.elapsedRealtimeMs() + holdMs : Long.MIN_VALUE;
        return true;
    }
}
