package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Structured, bounded diagnostics for reconstructing KeepADB lifecycle events. */
final class KeepADBDiagnostics {
    static final String TAG = "KeepADBDiag";
    static final String EXPORT_HEADER = "KeepADB diagnostics v1";
    static final int MAX_EVENTS = 128;
    private static final String PREFS_NAME = "keepadb_diagnostics";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENT_LENGTH = 512;
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization)(\\s*[=:]\\s*)[^,;]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(pair(?:ing)?[-_ ]?code|token|password|secret)(\\s*[=:]\\s*)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    private KeepADBDiagnostics() {}

    private static final Map<String, String> lastHeartbeatSignatureBySlot = new ConcurrentHashMap<>();

    static void event(Context context, String name, String source, String outcome, String detail) {
        String line = formatEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                Process.myPid(), name, source, outcome, detail);
        Log.i(TAG, line);
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        synchronized (KeepADBDiagnostics.class) {
            List<String> events = readEvents(prefs);
            appendBounded(events, line);
            prefs.edit().putString(KEY_EVENTS, join(events)).apply();
        }
    }

    /**
     * #545: for events fired on every 60s heartbeat tick rather than on an actual occurrence,
     * a release build only feeds the bounded ring buffer ({@link #MAX_EVENTS}) when the
     * outcome/detail signature actually changed since the previous heartbeat tick -- an
     * unchanged tick still reaches logcat (unbounded, not the scarce resource here) but is kept
     * out of the export so the export's historical coverage is not dominated by identical
     * "still waiting"/"still blocked" repeats. A debug build keeps every tick, matching the
     * existing debug-vs-release distinction in {@code SettingsActivity.isDebugBuild()}: the
     * Keep-Alive/recovery logic itself never differs, only diagnostic verbosity does. A changed
     * signature -- including any transition between the "waiting for network" / "retry
     * deferred" / "recheck due" outcomes this guards -- is always stored, so state changes stay
     * fully reconstructable.
     *
     * <p>{@code slot} keys the "last signature seen" independently per call site. One heartbeat
     * tick of {@code recheckAndEnable()} fires two calls with the same event name but different
     * outcomes -- a "started" preamble, then exactly one of the mutually exclusive result
     * outcomes. Comparing both against a single shared last-signature would make it alternate
     * between two different values on every tick and never coalesce anything; each call site
     * therefore gets its own slot so a repeated tick is compared against its own previous call,
     * not against the other call's outcome.
     */
    static void heartbeatEvent(Context context, String slot, String name, String source,
            String outcome, String detail) {
        boolean debugBuild = context != null && context.getPackageName().endsWith(".debug");
        heartbeatEvent(context, slot, name, source, outcome, detail, debugBuild);
    }

    /**
     * Package-private overload with an explicit {@code storeEveryTick} flag so the coalescing
     * decision itself is unit-testable without depending on which build variant a unit test
     * happens to run under (unit tests here always execute against the debug variant's
     * applicationId, so {@code getPackageName()} alone cannot exercise the release path).
     */
    static void heartbeatEvent(Context context, String slot, String name, String source,
            String outcome, String detail, boolean storeEveryTick) {
        String signature = name + '\u0001' + outcome + '\u0001' + detail;
        boolean stateChanged = !signature.equals(lastHeartbeatSignatureBySlot.put(slot, signature));
        if (storeEveryTick || stateChanged) {
            event(context, name, source, outcome, detail);
        } else {
            Log.i(TAG, formatEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    Process.myPid(), name, source, outcome, detail));
        }
    }

    static String export(Context context) {
        if (context == null) return EXPORT_HEADER;
        List<String> events;
        synchronized (KeepADBDiagnostics.class) {
            events = readEvents(
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
        }
        return renderExport(events);
    }

    static String renderExport(List<String> events) {
        StringBuilder result = new StringBuilder(EXPORT_HEADER).append('\n');
        for (String event : events) result.append(event).append('\n');
        return result.toString();
    }

    static String formatEvent(long wallTimeMs, long elapsedTimeMs, int pid,
            String name, String source, String outcome, String detail) {
        String timestamp;
        synchronized (TIME_FORMAT) {
            timestamp = TIME_FORMAT.format(new Date(wallTimeMs));
        }
        return trim("ts=" + timestamp
                + " elapsedMs=" + elapsedTimeMs
                + " pid=" + pid
                + " sdk=" + Build.VERSION.SDK_INT
                + " event=" + safe(name)
                + " source=" + safe(source)
                + " outcome=" + safe(outcome)
                + " detail=" + redact(detail));
    }

    static String redact(String value) {
        if (value == null || value.trim().isEmpty()) return "none";
        String redacted = URL.matcher(value).replaceAll("[URL_REDACTED]");
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1$2[REDACTED]");
        return SECRET.matcher(redacted).replaceAll("$1$2[REDACTED]")
                .replaceAll("\\s+", " ").trim();
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9_.:/-]", "_");
    }

    private static String trim(String value) {
        return value.length() <= MAX_EVENT_LENGTH
                ? value : value.substring(0, MAX_EVENT_LENGTH);
    }

    private static List<String> readEvents(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_EVENTS, "");
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String line : raw.split("\\n")) {
            if (!line.isEmpty()) result.add(line);
        }
        return result;
    }

    static void appendBounded(List<String> events, String event) {
        events.add(event);
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    private static String join(List<String> events) {
        StringBuilder result = new StringBuilder();
        for (String event : events) {
            if (result.length() > 0) result.append('\n');
            result.append(event);
        }
        return result.toString();
    }
}
