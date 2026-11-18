package de.hohnepeople.keepadb;

import android.content.Context;

/** #1000: the words of the Wifi-ADB timer, shared by the home screen, the settings and the shortcut. */
final class KeepADBTimerText {
    private KeepADBTimerText() {}

    static String duration(Context context, KeepADBTimer.Duration duration) {
        switch (duration) {
            case MINUTES_15:
                return context.getString(R.string.timer_duration_15m);
            case MINUTES_30:
                return context.getString(R.string.timer_duration_30m);
            case HOURS_2:
                return context.getString(R.string.timer_duration_2h);
            case HOURS_4:
                return context.getString(R.string.timer_duration_4h);
            case HOURS_8:
                return context.getString(R.string.timer_duration_8h);
            case HOUR_1:
            default:
                return context.getString(R.string.timer_duration_1h);
        }
    }

    /** All durations in their order, for the choice dialogs. */
    static String[] durations(Context context) {
        KeepADBTimer.Duration[] values = KeepADBTimer.Duration.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = duration(context, values[i]);
        return labels;
    }

    /**
     * The time left, rounded up to the minute so it never reads "0 min" while the timer still
     * holds: "42 min", "3 h 12 min".
     */
    static String remaining(Context context, long remainingMs) {
        long minutes = Math.max(1L, (remainingMs + 59_999L) / 60_000L);
        if (minutes < 60) return context.getString(R.string.timer_remaining_minutes, (int) minutes);
        return context.getString(R.string.timer_remaining_hours_minutes,
                (int) (minutes / 60), (int) (minutes % 60));
    }
}
