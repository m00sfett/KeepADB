package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.util.regex.Matcher;
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
    // #574: output-path-only, see maskNetworkIdentifiersForExport(). A well-formed BSSID (six
    // colon-separated hex octets) keeps its OUI (group 1) and masks the rest; anything else after
    // "bssid=" falls into group 2 and is masked in full. Both alternatives run to end-of-line
    // (".", not "\\S+") rather than stopping at the first space, since a value with an embedded
    // space -- most plausibly a future free-text SSID -- must not leak its remainder unmasked.
    private static final Pattern EXPORT_BSSID = Pattern.compile(
            "(?i)\\bbssid=(?:([0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}):"
                    + "[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}\\b|(.*))");
    private static final Pattern EXPORT_SSID = Pattern.compile("(?i)\\bssid=.*");
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    private KeepADBDiagnostics() {}

    private static final Map<String, String> lastHeartbeatSignatureBySlot = new ConcurrentHashMap<>();

    static void event(Context context, String name, String source, String outcome, String detail) {
        String line = formatEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                Process.myPid(), name, source, outcome, detail);
        Log.i(TAG, line);
        if (context == null) return;
        KeepADBDiagnosticJournal journal = debugJournal(context);
        if (journal != null) {
            // #566: debug builds keep a 48h journal persisted at most hourly instead of writing
            // every event straight into the bounded release ring buffer below.
            journal.record(line);
            return;
        }
        storeInRingBuffer(context, line);
    }

    private static void storeInRingBuffer(Context context, String line) {
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
     * "still waiting"/"still blocked" repeats. A changed signature -- including any transition
     * between the "waiting for network" / "retry deferred" / "recheck due" outcomes this guards
     * -- is always stored, so state changes stay fully reconstructable.
     *
     * <p>#566: a debug build instead counts every tick in the 48h {@link KeepADBDiagnosticJournal},
     * where unchanged consecutive ticks become one entry with a sample count, so the
     * per-tick history stays complete without every tick costing its own export line. The
     * Keep-Alive/recovery logic itself never differs between variants, only diagnostic
     * verbosity does.
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
        heartbeatEvent(context, slot, name, source, outcome, detail,
                KeepADBBuildFlags.isDebugBuild(context));
    }

    /**
     * Package-private overload with an explicit {@code debugBuild} flag so both variants' paths
     * are unit-testable regardless of which applicationId a unit test happens to run under.
     */
    static void heartbeatEvent(Context context, String slot, String name, String source,
            String outcome, String detail, boolean debugBuild) {
        String signature = name + '\u0001' + outcome + '\u0001' + detail;
        String line = formatEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                Process.myPid(), name, source, outcome, detail);
        Log.i(TAG, line);
        if (context == null) return;
        KeepADBDiagnosticJournal journal = debugBuild ? journalFor(context) : null;
        if (journal != null) {
            journal.recordSample("heartbeat:" + slot, signature, line);
            return;
        }
        boolean stateChanged = !signature.equals(lastHeartbeatSignatureBySlot.put(slot, signature));
        if (stateChanged) storeInRingBuffer(context, line);
    }

    static String export(Context context) {
        if (context == null) return EXPORT_HEADER;
        KeepADBDiagnosticJournal journal = debugJournal(context);
        if (journal != null) return maskNetworkIdentifiersForExport(journal.render());
        List<String> events;
        synchronized (KeepADBDiagnostics.class) {
            events = readEvents(
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
        }
        return maskNetworkIdentifiersForExport(renderExport(events));
    }

    /**
     * #566: compact variant for the issue-report draft, which embeds the export into an editable
     * text field and a share intent: a debug build contributes only its newest {@link #MAX_EVENTS}
     * journal entries (the same size as the release ring buffer), a release build is unchanged.
     */
    static String exportForIssueReport(Context context) {
        KeepADBDiagnosticJournal journal = debugJournal(context);
        if (journal == null) return export(context);
        return maskNetworkIdentifiersForExport(renderExport(journal.renderEntries(MAX_EVENTS)));
    }

    /**
     * #574: output-path-only redaction of the rendered export text -- never applied to what
     * {@link #event}, {@link #heartbeatEvent} or {@link #snapshot} write, so the stored ring
     * buffer and journal stay exactly as recorded. A well-formed BSSID keeps its OUI (first three
     * octets, which identify the vendor rather than one physical access point) and masks the
     * remaining three, analogous to {@link KeepADBAddressMask}'s address masking; a value that
     * does not look like a six-octet MAC is masked in full rather than risking a partial,
     * unrecognised leak. SSID is masked in full even though no current writer emits one, so a
     * future one is covered without another export-path change. {@link
     * KeepADBIssueReporter#redactDiagnostics} redacts both fully on top of this for the feedback
     * report draft.
     */
    static String maskNetworkIdentifiersForExport(String text) {
        if (text == null || text.isEmpty()) return text;
        // Matcher#replaceAll(Function) and its StringBuilder-based appendReplacement/appendTail
        // overloads need API 34; minSdk here is 30, so this uses the long-available StringBuffer
        // overloads to do the per-match replacement (OUI kept vs. fully redacted) by hand.
        Matcher matcher = EXPORT_BSSID.matcher(text);
        StringBuffer maskedBssid = new StringBuffer();
        while (matcher.find()) {
            String oui = matcher.group(1);
            String replacement = oui != null ? "bssid=" + oui + ":*:*:*" : "bssid=[REDACTED]";
            matcher.appendReplacement(maskedBssid, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(maskedBssid);
        return EXPORT_SSID.matcher(maskedBssid).replaceAll("ssid=[REDACTED]");
    }

    /** #566: a verified endpoint counts as currently confirmed for this long after its last probe. */
    static final long ENDPOINT_CONFIRMED_WINDOW_MS = 150_000;

    /**
     * #566: debug-build-only per-minute state snapshot, fed by the service's 60s heartbeat. It
     * only reads state and never changes Keep-Alive, recovery, endpoint or Tailscale settings.
     * Unchanged consecutive minutes are counted into one journal entry; any field change starts a
     * new, timestamped entry naming the changed fields.
     */
    static void snapshot(Context context) {
        KeepADBDiagnosticJournal journal = debugJournal(context);
        if (journal == null) return;
        try {
            recordSnapshot(context, journal);
        } catch (RuntimeException e) {
            // A diagnostics read must never break the heartbeat that called it.
            Log.w(TAG, "State snapshot failed", e);
        }
    }

    private static void recordSnapshot(Context context, KeepADBDiagnosticJournal journal) {
        String state = snapshotState(describeActiveNetwork(context),
                KeepADBService.isWifiConnected(context),
                KeepADBTailscaleStatus.detect(context),
                KeepADB.isEnabled(context),
                KeepADBPreferences.isKeepAliveEnabled(context),
                KeepADBNotification.getCurrentHost(), KeepADBNotification.getCurrentPort(),
                KeepADBNotification.getCurrentEndpointVerifiedAtMs(), System.currentTimeMillis());
        String changed = changedFields(lastSnapshotState, state);
        lastSnapshotState = state;
        String line = formatEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                Process.myPid(), "state_snapshot", "heartbeat", "sampled",
                state + " changed=" + changed);
        Log.i(TAG, line);
        journal.recordSample("state_snapshot", state, line);
    }

    private static volatile String lastSnapshotState;

    static String snapshotState(String network, boolean wifiEligible,
            KeepADBTailscaleStatus.Status tailscale, boolean adbWifi, boolean keepAlive,
            String shownHost, int shownPort, long verifiedAtMs, long nowMs) {
        boolean shown = shownHost != null && shownPort > 0;
        String reachability;
        if (!shown) {
            reachability = "none";
        } else if (verifiedAtMs <= 0) {
            reachability = "unverified";
        } else if (nowMs - verifiedAtMs <= ENDPOINT_CONFIRMED_WINDOW_MS && nowMs >= verifiedAtMs) {
            reachability = "confirmed";
        } else {
            reachability = "stale";
        }
        return "net=" + network
                + " wifiEligible=" + wifiEligible
                + " tailscale=" + (tailscale == null ? "unknown" : tailscale.name().toLowerCase(Locale.ROOT))
                + " adbWifi=" + (adbWifi ? "on" : "off")
                + " keepAlive=" + (keepAlive ? "on" : "off")
                + " shownEndpoint=" + (shown ? "host=" + shownHost + " port=" + shownPort : "none")
                + " endpointReachability=" + reachability;
    }

    /** Names of the space-separated {@code key=value} fields that differ, or {@code initial}. */
    static String changedFields(String previous, String current) {
        if (previous == null) return "initial";
        Map<String, String> before = parseFields(previous);
        Map<String, String> after = parseFields(current);
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> field : after.entrySet()) {
            if (!field.getValue().equals(before.get(field.getKey()))) {
                if (result.length() > 0) result.append(',');
                result.append(field.getKey());
            }
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static Map<String, String> parseFields(String state) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String token : state.split(" ")) {
            int separator = token.indexOf('=');
            if (separator > 0) fields.put(token.substring(0, separator), token.substring(separator + 1));
        }
        return fields;
    }

    /**
     * The transports of Android's current default network ({@code wifi}, {@code cellular},
     * {@code vpn}, ...), {@code none} without a default network, or {@code unknown} when the
     * platform read itself fails -- never a guess.
     */
    static String describeActiveNetwork(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return "unknown";
            Network network = manager.getActiveNetwork();
            if (network == null) return "none";
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null) return "unknown";
            return describeTransports(capabilities);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    static String describeTransports(NetworkCapabilities capabilities) {
        StringBuilder result = new StringBuilder();
        int[] transports = {NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.TRANSPORT_BLUETOOTH, NetworkCapabilities.TRANSPORT_USB};
        String[] names = {"wifi", "cellular", "ethernet", "vpn", "bluetooth", "usb"};
        for (int i = 0; i < transports.length; i++) {
            if (capabilities.hasTransport(transports[i])) {
                if (result.length() > 0) result.append('+');
                result.append(names[i]);
            }
        }
        if (result.length() == 0) return "unknown";
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            result.append("(unvalidated)");
        }
        return result.toString();
    }

    /** #566: the debug journal, or {@code null} in a release build or without app storage. */
    private static KeepADBDiagnosticJournal debugJournal(Context context) {
        return context != null && KeepADBBuildFlags.isDebugBuild(context) ? journalFor(context) : null;
    }

    private static KeepADBDiagnosticJournal journalFor(Context context) {
        if (context == null) return null;
        try {
            return KeepADBDiagnosticJournal.get(context);
        } catch (RuntimeException e) {
            // Diagnostics must never break the calling Keep-Alive/recovery path.
            Log.w(TAG, "Debug diagnostics journal unavailable", e);
            return null;
        }
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
