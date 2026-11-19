package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * #998: the opt-in automation interface. Automation apps (Tasker, MacroDroid, Automate, ...) send
 * explicit broadcasts to {@link KeepADBAutomationReceiver}, the only exported receiver of the app;
 * this class decides what such a request may do. The public contract (action and extra names, the
 * result values) is documented in {@code docs/automation.md} and frozen from 1.15: later changes
 * are additive only.
 *
 * <p>Security model (maintainer decisions of 2026-11-18, see #998):
 * <ul>
 *   <li>The interface is off by default. While it is off every request is ignored: no reply, only
 *       a local journal line.</li>
 *   <li>Everything that can switch something on (Wifi-ADB on, toggle, Keep-Alive on) needs the
 *       secret token KeepADB generates. The comparison runs in constant time. The token is never
 *       logged, exported, put into diagnostics or into a webhook payload.</li>
 *   <li>Turning off and reading the on/off state need no token; the endpoint (address and port)
 *       is only part of a reply to a request with a valid token.</li>
 *   <li>A Wifi-ADB "on" never bypasses the network protection: it is refused unless Wi-Fi is
 *       connected and the current network allows an automatic enable, re-checked inside the
 *       toggle lock immediately before the write. It does not need an unlocked device; the token
 *       is the authorization.</li>
 *   <li>Requests go through the manual toggle path ({@link KeepADB#SOURCE_AUTOMATION}), so an
 *       automation "off" is the user's intent and Keep-Alive respects it.</li>
 * </ul>
 * A reply is sent only to the package named in {@link #EXTRA_REPLY_PACKAGE}, never as an
 * implicit broadcast. Any app can name any reply package, so a reply is information for the
 * client, never an authorization; {@link #EXTRA_REQUEST_ID} lets a client match a reply to its
 * own request.
 *
 * <p>Any installed app can also send requests in a loop. Requests without a valid token write at
 * most one journal line per kind and {@link #FLOOD_WINDOW_MS}, and the rejection counter is
 * saved at most once per window, so a flood neither churns storage nor pushes useful history out
 * of the diagnostics journal.
 */
final class KeepADBAutomation {
    private KeepADBAutomation() {}

    private static final String ACTION_PREFIX = "de.hohnepeople.keepadb.action.";
    static final String ACTION_WIFI_ADB_ON = ACTION_PREFIX + "WIFI_ADB_ON";
    static final String ACTION_WIFI_ADB_OFF = ACTION_PREFIX + "WIFI_ADB_OFF";
    static final String ACTION_WIFI_ADB_TOGGLE = ACTION_PREFIX + "WIFI_ADB_TOGGLE";
    static final String ACTION_KEEP_ALIVE_ON = ACTION_PREFIX + "KEEP_ALIVE_ON";
    static final String ACTION_KEEP_ALIVE_OFF = ACTION_PREFIX + "KEEP_ALIVE_OFF";
    static final String ACTION_STATUS = ACTION_PREFIX + "STATUS";
    /** The reply KeepADB sends to {@link #EXTRA_REPLY_PACKAGE}. */
    static final String ACTION_RESULT = ACTION_PREFIX + "RESULT";

    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_REPLY_PACKAGE = "reply_package";
    /** Optional, echoed unchanged in the reply so a client can match it; never logged. */
    static final String EXTRA_REQUEST_ID = "request_id";
    static final int MAX_REQUEST_ID_LENGTH = 128;

    static final String EXTRA_REQUEST = "request";
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_WIFI_ADB = "wifi_adb";
    static final String EXTRA_KEEP_ALIVE = "keep_alive";
    static final String EXTRA_IP = "ip";
    static final String EXTRA_PORT = "port";

    /** The values of {@link #EXTRA_RESULT}. */
    enum Result {
        OK("ok"),
        REJECTED_TOKEN("rejected_token"),
        REJECTED_NETWORK("rejected_network"),
        FAILED_PERMISSION("failed_permission"),
        FAILED("failed");

        final String value;

        Result(String value) {
            this.value = value;
        }
    }

    static final int TOKEN_BYTES = 32;

    /** A dotted Java package name; anything else is not used as a reply target. */
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private static final String EVENT = "automation";

    /** Journal lines and counter writes caused by untokened requests: at most one per window. */
    static final long FLOOD_WINDOW_MS = 10_000L;
    private static volatile LongSupplier clock = SystemClock::elapsedRealtime;
    private static final Object FLOOD_LOCK = new Object();
    /** Journal key -> {elapsed time of the last written line, lines suppressed since}. */
    private static final Map<String, long[]> journalWindows = new HashMap<>();
    /** Rejections counted but not saved yet, and the wall-clock time of the newest one. */
    private static int pendingRejections;
    private static long pendingLastRejectedAt;
    private static long lastRejectionSaveAt;
    private static boolean rejectionSavedOnce;

    /** What a request ended with; {@code null} from {@link #process} means "ignored, no reply". */
    static final class Response {
        final String request;
        final Result result;
        final boolean wifiAdb;
        final boolean keepAlive;
        /** Only set for a request with a valid token and a known endpoint. */
        final String host;
        final int port;

        Response(String request, Result result, boolean wifiAdb, boolean keepAlive, String host,
                 int port) {
            this.request = request;
            this.result = result;
            this.wifiAdb = wifiAdb;
            this.keepAlive = keepAlive;
            this.host = host;
            this.port = port;
        }

        boolean hasEndpoint() {
            return host != null && port > 0;
        }
    }

    /** The side effects of a request, a seam so tests do not need a real Wifi-ADB switch. */
    interface Actions {
        /** Wi-Fi is connected and the current network allows an automatic enable. */
        boolean networkAllowsEnable(Context app);

        KeepADB.ToggleResult setWifiAdb(Context app, boolean on, KeepADB.EnableGuard guard);

        boolean isWifiAdbOn(Context app);

        void setKeepAlive(Context app, boolean on);

        /** The verified endpoint, or {@code null}. */
        KeepADBEndpointCoordinator.Snapshot endpoint();

        void refreshSurfaces(Context app);
    }

    private static final Actions REAL_ACTIONS = new Actions() {
        @Override
        public boolean networkAllowsEnable(Context app) {
            return KeepADBService.isWifiConnected(app)
                    && KeepADBTrustedNetwork.isCurrentNetworkTrusted(app);
        }

        @Override
        public KeepADB.ToggleResult setWifiAdb(Context app, boolean on, KeepADB.EnableGuard guard) {
            return KeepADB.setEnabled(app, on, KeepADB.SOURCE_AUTOMATION, guard);
        }

        @Override
        public boolean isWifiAdbOn(Context app) {
            KeepADB.State state = KeepADB.getState(app);
            return state == KeepADB.State.ENABLED_CONNECTED
                    || state == KeepADB.State.ENABLED_DISCONNECTED;
        }

        @Override
        public void setKeepAlive(Context app, boolean on) {
            KeepADBPreferences.setKeepAliveEnabled(app, on);
        }

        @Override
        public KeepADBEndpointCoordinator.Snapshot endpoint() {
            return KeepADBEndpointCoordinator.snapshot();
        }

        @Override
        public void refreshSurfaces(Context app) {
            KeepADBService.sync(app);
            KeepADBWidget.refreshAll(app);
            KeepADBEndpointCoordinator.refresh(app);
            KeepADBTileService.requestRefresh(app);
        }
    };

    private static volatile Actions actions = REAL_ACTIONS;

    static void setActionsForTesting(Actions testActions) {
        actions = testActions == null ? REAL_ACTIONS : testActions;
    }

    /** A fake elapsed-time clock for the flood window; {@code null} restores the real one. */
    static void setClockForTesting(LongSupplier testClock) {
        clock = testClock == null ? SystemClock::elapsedRealtime : testClock;
    }

    /** Forgets the in-memory flood state (tests run in one process). */
    static void resetFloodStateForTesting() {
        synchronized (FLOOD_LOCK) {
            journalWindows.clear();
            pendingRejections = 0;
            pendingLastRejectedAt = 0L;
            lastRejectionSaveAt = 0L;
            rejectionSavedOnce = false;
        }
    }

    // ---- Settings -------------------------------------------------------------------------

    static boolean isEnabled(Context context) {
        return KeepADBPreferences.isAutomationEnabled(context);
    }

    /** Turns the interface on; the first time, a token is generated (Q12). */
    static void enable(Context context) {
        if (KeepADBPreferences.getAutomationToken(context) == null) {
            KeepADBPreferences.setAutomationToken(context, generateToken(new SecureRandom()));
        }
        KeepADBPreferences.setAutomationEnabled(context, true);
        KeepADBDiagnostics.event(context, EVENT, "settings", "enabled", "interface");
    }

    /** Turns the interface off; the token is kept, so re-enabling keeps automations working. */
    static void disable(Context context) {
        KeepADBPreferences.setAutomationEnabled(context, false);
        KeepADBDiagnostics.event(context, EVENT, "settings", "disabled", "interface");
    }

    /** A new token; the old one stops working at once. The rejection counter starts again. */
    static String regenerateToken(Context context) {
        String token = generateToken(new SecureRandom());
        KeepADBPreferences.setAutomationToken(context, token);
        synchronized (FLOOD_LOCK) {
            pendingRejections = 0;
            pendingLastRejectedAt = 0L;
            KeepADBPreferences.resetAutomationRejections(context);
        }
        KeepADBDiagnostics.event(context, EVENT, "settings", "token_regenerated", "interface");
        return token;
    }

    static String generateToken(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** Constant-time comparison; a missing stored or presented token never matches. */
    static boolean tokenMatches(String stored, String presented) {
        if (stored == null || stored.isEmpty() || presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isValidReplyPackage(String packageName) {
        return packageName != null && packageName.length() <= 255
                && PACKAGE_NAME.matcher(packageName).matches();
    }

    // ---- Requests -------------------------------------------------------------------------

    /** Handles one broadcast: decides, acts and replies if a reply package was named. */
    static void handle(Context context, Intent intent) {
        if (intent == null) return;
        Context app = context.getApplicationContext();
        Response response = process(app, intent.getAction(), intent.getStringExtra(EXTRA_TOKEN));
        if (response == null) return;
        String replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE);
        if (replyPackage == null || replyPackage.isEmpty()) return;
        if (!isValidReplyPackage(replyPackage)) {
            journal(app, "reply_skipped", "reason=invalid_reply_package", true);
            return;
        }
        app.sendBroadcast(replyIntent(response, replyPackage,
                intent.getStringExtra(EXTRA_REQUEST_ID)));
    }

    static Intent replyIntent(Response response, String replyPackage, String requestId) {
        Intent reply = new Intent(ACTION_RESULT).setPackage(replyPackage);
        if (requestId != null && !requestId.isEmpty()
                && requestId.length() <= MAX_REQUEST_ID_LENGTH) {
            reply.putExtra(EXTRA_REQUEST_ID, requestId);
        }
        reply.putExtra(EXTRA_REQUEST, response.request);
        reply.putExtra(EXTRA_RESULT, response.result.value);
        reply.putExtra(EXTRA_WIFI_ADB, response.wifiAdb);
        reply.putExtra(EXTRA_KEEP_ALIVE, response.keepAlive);
        if (response.hasEndpoint()) {
            reply.putExtra(EXTRA_IP, response.host);
            reply.putExtra(EXTRA_PORT, response.port);
        }
        return reply;
    }

    /**
     * Decides and performs one request. Returns {@code null} when the request is ignored (the
     * interface is off, or the action is unknown); every other outcome is a {@link Response}.
     */
    static Response process(Context app, String action, String presentedToken) {
        String request = requestName(action);
        if (!isEnabled(app)) {
            journal(app, "ignored",
                    "reason=interface_disabled request=" + (request == null ? "unknown" : request),
                    true);
            return null;
        }
        if (request == null) {
            journal(app, "ignored", "reason=unknown_action", true);
            return null;
        }
        Actions act = actions;
        boolean tokenPresented = presentedToken != null && !presentedToken.isEmpty();
        boolean tokenValid = tokenMatches(KeepADBPreferences.getAutomationToken(app), presentedToken);

        if (ACTION_STATUS.equals(action)) {
            // The on/off state needs no token, a wrong one is still a rejection worth counting.
            if (tokenPresented && !tokenValid) {
                recordRejection(app, request, "token_invalid");
                return response(app, act, request, Result.REJECTED_TOKEN, false);
            }
            journal(app, "accepted", "request=" + request + " endpoint=" + tokenValid,
                    !tokenValid);
            return response(app, act, request, Result.OK, tokenValid);
        }

        boolean needsToken = ACTION_WIFI_ADB_ON.equals(action)
                || ACTION_WIFI_ADB_TOGGLE.equals(action)
                || ACTION_KEEP_ALIVE_ON.equals(action);
        if (needsToken && !tokenValid) {
            recordRejection(app, request, tokenPresented ? "token_invalid" : "token_missing");
            return response(app, act, request, Result.REJECTED_TOKEN, false);
        }

        Result result;
        if (ACTION_KEEP_ALIVE_ON.equals(action) || ACTION_KEEP_ALIVE_OFF.equals(action)) {
            boolean on = ACTION_KEEP_ALIVE_ON.equals(action);
            journal(app, "accepted", "request=" + request, !tokenValid);
            act.setKeepAlive(app, on);
            result = Result.OK;
        } else {
            boolean on = ACTION_WIFI_ADB_ON.equals(action)
                    || (ACTION_WIFI_ADB_TOGGLE.equals(action) && !act.isWifiAdbOn(app));
            result = switchWifiAdb(app, act, request, on, tokenValid);
        }
        act.refreshSurfaces(app);
        return response(app, act, request, result, tokenValid);
    }

    private static Result switchWifiAdb(Context app, Actions act, String request, boolean on,
                                        boolean tokenValid) {
        if (on && !act.networkAllowsEnable(app)) {
            journal(app, "rejected", "request=" + request + " reason=network", false);
            return Result.REJECTED_NETWORK;
        }
        journal(app, "accepted", "request=" + request + " desired=" + on, !tokenValid);
        // Re-checked inside the toggle lock, immediately before the write: the interface may have
        // been switched off or the network may have changed in between.
        KeepADB.EnableGuard guard = on
                ? guarded -> isEnabled(guarded) && act.networkAllowsEnable(guarded)
                : null;
        KeepADB.ToggleResult toggle = act.setWifiAdb(app, on, guard);
        return mapToggleResult(toggle);
    }

    static Result mapToggleResult(KeepADB.ToggleResult toggle) {
        if (toggle.isSuccess()) return Result.OK;
        if (toggle.isPermissionFailure()) return Result.FAILED_PERMISSION;
        if (toggle == KeepADB.ToggleResult.GUARD_ABORTED) return Result.REJECTED_NETWORK;
        return Result.FAILED;
    }

    private static Response response(Context app, Actions act, String request, Result result,
                                     boolean withEndpoint) {
        boolean wifiAdb = act.isWifiAdbOn(app);
        String host = null;
        int port = 0;
        if (withEndpoint && wifiAdb) {
            KeepADBEndpointCoordinator.Snapshot snapshot = act.endpoint();
            if (snapshot != null && snapshot.hasEndpoint()) {
                host = snapshot.host;
                port = snapshot.port;
            }
        }
        return new Response(request, result, wifiAdb, KeepADBPreferences.isKeepAliveEnabled(app),
                host, port);
    }

    private static void recordRejection(Context app, String request, String reason) {
        long now = clock.getAsLong();
        synchronized (FLOOD_LOCK) {
            if (pendingRejections < Integer.MAX_VALUE) pendingRejections++;
            pendingLastRejectedAt = System.currentTimeMillis();
            if (!rejectionSavedOnce || now - lastRejectionSaveAt >= FLOOD_WINDOW_MS) {
                saveRejectionsLocked(app);
                lastRejectionSaveAt = now;
                rejectionSavedOnce = true;
            }
        }
        journal(app, "rejected", "request=" + request + " reason=" + reason, true);
    }

    /**
     * Saves rejections counted within the current flood window. Settings calls this before it
     * shows the counter; a process death can lose at most one window of a flood, never the
     * first rejection, which is always saved at once.
     */
    static void flushRejections(Context context) {
        synchronized (FLOOD_LOCK) {
            saveRejectionsLocked(context);
        }
    }

    private static void saveRejectionsLocked(Context context) {
        if (pendingRejections <= 0) return;
        KeepADBPreferences.recordAutomationRejections(context, pendingRejections,
                pendingLastRejectedAt);
        pendingRejections = 0;
    }

    /**
     * One journal line. A throttled line (from a request without a valid token, which any app
     * can send in a loop) is written at most once per kind and {@link #FLOOD_WINDOW_MS}; the
     * next written line of that kind says how many were left out.
     */
    private static void journal(Context app, String outcome, String detail, boolean throttled) {
        if (throttled) {
            long now = clock.getAsLong();
            String key = outcome + ' ' + detail;
            synchronized (FLOOD_LOCK) {
                long[] window = journalWindows.get(key);
                if (window != null && now - window[0] < FLOOD_WINDOW_MS) {
                    window[1]++;
                    return;
                }
                long suppressed = window == null ? 0 : window[1];
                journalWindows.put(key, new long[] {now, 0});
                if (suppressed > 0) detail = detail + " suppressed=" + suppressed;
            }
        }
        KeepADBDiagnostics.event(app, EVENT, "automation", outcome, detail);
    }

    /** The short request name used in replies and the journal, {@code null} for anything else. */
    static String requestName(String action) {
        if (action == null || !action.startsWith(ACTION_PREFIX)) return null;
        switch (action) {
            case ACTION_WIFI_ADB_ON:
            case ACTION_WIFI_ADB_OFF:
            case ACTION_WIFI_ADB_TOGGLE:
            case ACTION_KEEP_ALIVE_ON:
            case ACTION_KEEP_ALIVE_OFF:
            case ACTION_STATUS:
                return action.substring(ACTION_PREFIX.length());
            default:
                return null;
        }
    }
}
