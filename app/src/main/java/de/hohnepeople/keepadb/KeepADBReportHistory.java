package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #892: a local, bounded history of what KeepADB reported to the webhook and which endpoints it
 * saw, so a failure episode can be read in the app instead of in logcat. It keeps the last 24
 * hours, at most {@link #MAX_ENTRIES} entries, in an app-private preference file that survives a
 * process restart and goes with an uninstall or an app reset.
 *
 * <p>Entries hold the full endpoint for the local view; they never hold the webhook URL (it may
 * carry a token) or pairing data. The history is not exported or shared; the diagnostics export
 * stays the only way out of the app.
 *
 * <p>Pruning is by age and count on every write. An entry from a wall clock that has since moved
 * back stays until it ages out against the corrected clock or the cap pushes it out, so it can
 * neither block new entries nor stay forever.
 */
final class KeepADBReportHistory {
    static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    static final int MAX_ENTRIES = 300;
    static final int NO_MAX = -1;
    private static final String PREFS_NAME = "keepadb_report_history";
    private static final String KEY_ENTRIES = "entries";

    enum Event {
        ENDPOINT_FOUND("endpoint_found"),
        ENDPOINT_LOST("endpoint_lost"),
        REPORT("report"),
        UNREGISTER("unregister");

        final String id;

        Event(String id) {
            this.id = id;
        }

        static Event fromId(String id) {
            for (Event event : values()) {
                if (event.id.equals(id)) return event;
            }
            return null;
        }
    }

    /** One line of the history; {@code outcome} is {@code http=<code>}, {@code error=<class>} or empty. */
    static final class Entry {
        final long timeMs;
        final Event event;
        final String endpoint;
        final boolean ok;
        final String outcome;
        final int retry;
        final int maxRetries;

        Entry(long timeMs, Event event, String endpoint, boolean ok, String outcome, int retry,
                int maxRetries) {
            this.timeMs = timeMs;
            this.event = event;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.ok = ok;
            this.outcome = outcome == null ? "" : outcome;
            this.retry = retry;
            this.maxRetries = maxRetries;
        }
    }

    private static volatile Long nowForTesting;

    private KeepADBReportHistory() {}

    static void endpointFound(Context context, String endpoint) {
        add(context, new Entry(now(), Event.ENDPOINT_FOUND, endpoint, true, "", 0, NO_MAX));
    }

    static void endpointLost(Context context) {
        add(context, new Entry(now(), Event.ENDPOINT_LOST, "", true, "", 0, NO_MAX));
    }

    /** A POST of {@code endpoint}; {@code retry} 0 is the first attempt of the series. */
    static void report(Context context, String endpoint, boolean ok, String outcome, int retry,
            int maxRetries) {
        add(context, new Entry(now(), Event.REPORT, endpoint, ok, outcome, retry, maxRetries));
    }

    static void unregister(Context context, String endpoint, boolean ok, String outcome, int retry) {
        add(context, new Entry(now(), Event.UNREGISTER, endpoint, ok, outcome, retry, NO_MAX));
    }

    /** Newest first, already pruned to the last 24 hours. */
    static List<Entry> entries(Context context) {
        if (context == null) return Collections.emptyList();
        synchronized (KeepADBReportHistory.class) {
            List<Entry> list = prune(read(prefs(context)), now());
            Collections.reverse(list);
            return list;
        }
    }

    static void clear(Context context) {
        if (context == null) return;
        synchronized (KeepADBReportHistory.class) {
            prefs(context).edit().remove(KEY_ENTRIES).apply();
        }
    }

    private static void add(Context context, Entry entry) {
        if (context == null || entry.event == null) return;
        synchronized (KeepADBReportHistory.class) {
            SharedPreferences prefs = prefs(context);
            List<Entry> list = read(prefs);
            // Discovery retries repeat the same endpoint state; one line per change is enough.
            if (isEndpointEvent(entry.event) && !list.isEmpty()) {
                Entry last = lastEndpointEntry(list);
                if (last != null && last.event == entry.event && last.endpoint.equals(entry.endpoint)) {
                    return;
                }
            }
            list.add(entry);
            list = prune(list, entry.timeMs);
            prefs.edit().putString(KEY_ENTRIES, serialize(list)).apply();
        }
    }

    private static boolean isEndpointEvent(Event event) {
        return event == Event.ENDPOINT_FOUND || event == Event.ENDPOINT_LOST;
    }

    private static Entry lastEndpointEntry(List<Entry> oldestFirst) {
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            if (isEndpointEvent(oldestFirst.get(i).event)) return oldestFirst.get(i);
        }
        return null;
    }

    /** Oldest first in, oldest first out; drops what is older than a day and what exceeds the cap. */
    static List<Entry> prune(List<Entry> oldestFirst, long now) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : oldestFirst) {
            if (now - entry.timeMs <= MAX_AGE_MS) kept.add(entry);
        }
        int overflow = kept.size() - MAX_ENTRIES;
        return overflow > 0 ? new ArrayList<>(kept.subList(overflow, kept.size())) : kept;
    }

    static String serialize(List<Entry> list) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : list) {
                JSONObject item = new JSONObject();
                item.put("t", entry.timeMs);
                item.put("e", entry.event.id);
                item.put("ep", entry.endpoint);
                item.put("ok", entry.ok);
                item.put("o", entry.outcome);
                item.put("r", entry.retry);
                item.put("m", entry.maxRetries);
                array.put(item);
            }
        } catch (JSONException ignored) {
            // Only primitive values are put; cannot happen.
        }
        return array.toString();
    }

    static List<Entry> parse(String json) {
        List<Entry> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Event event = Event.fromId(item.optString("e"));
                if (event == null) continue;
                list.add(new Entry(item.optLong("t"), event, item.optString("ep"),
                        item.optBoolean("ok"), item.optString("o"), item.optInt("r"),
                        item.optInt("m", NO_MAX)));
            }
        } catch (JSONException ignored) {
            // A damaged history starts over instead of breaking the reports.
        }
        return list;
    }

    private static List<Entry> read(SharedPreferences prefs) {
        return parse(prefs.getString(KEY_ENTRIES, null));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static long now() {
        Long fixed = nowForTesting;
        return fixed != null ? fixed : System.currentTimeMillis();
    }

    static void setNowForTesting(Long now) {
        nowForTesting = now;
    }
}
