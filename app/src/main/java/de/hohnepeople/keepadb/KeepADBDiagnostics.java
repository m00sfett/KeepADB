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
