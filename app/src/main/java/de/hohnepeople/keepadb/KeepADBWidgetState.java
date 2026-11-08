package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #910: the one read-only projection of what KeepADB knows about Wireless Debugging, Keep-Alive,
 * the verified WLAN endpoint and the webhook delivery, in the shape the home-screen widgets and the
 * Quick Settings tile need. It owns no state and no discovery or report loop: every value is read
 * from the component that owns it ({@link KeepADB}, {@link KeepADBEndpointCoordinator},
 * {@link KeepADBRegisterClient}, {@link KeepADBPreferences}, {@link KeepADBReportHistory}).
 *
 * <p><b>Pure read.</b> Projecting never sends a report, never starts a discovery or a timer and
 * never writes a preference, so rendering or resizing a widget cannot cause traffic. The surfaces
 * are told to re-render by the components that change the state (toggle, endpoint, report result,
 * preference setters), not by polling.
 *
 * <p><b>Privacy.</b> With the widget privacy switch on ({@link
 * KeepADBPreferences#isWidgetPrivacyEnabled}) or the stricter per-widget "hide details" option, the
 * projection carries the state only: no host, port, IP address, Wi-Fi name or BSSID. The global
 * switch takes precedence; a per-widget option can only hide more, never show what the switch hides.
 * Availability of an endpoint is state and stays visible; the address is detail and does not.
 * The webhook URL is never part of the projection.
 *
 * <p><b>Delivery claim.</b> {@link Webhook#delivery} is sender-side evidence: the last report the
 * server accepted named exactly the endpoint that is current now and the destination that is
 * configured now. It is neither proof of the receiver's stored state nor of an active ADB client
 * session, and no field here infers a session from an endpoint.
 */
final class KeepADBWidgetState {

    /** Wireless Debugging as the system reports it, with the debounce window shown as pending. */
    enum Adb {
        /** The setting cannot be written or read; see {@link #adbFailure}. */
        PERMISSION_FAILURE,
        OFF,
        ON,
        /** A toggle was accepted and is waiting in its debounce window; the old value is stale. */
        PENDING
    }

    /** Why something is blocked or waiting, for an actionable explanation. */
    enum Reason {
        NONE,
        /** {@code WRITE_SECURE_SETTINGS} is not granted: the one-time {@code pm grant} is missing. */
        PERMISSION_NOT_GRANTED,
        /** The grant exists but the platform refuses to read {@code adb_wifi_enabled} (#580). */
        SETTING_UNREADABLE,
        /** The last explicit intent was off, so Keep-Alive stays out of the way until the user acts. */
        MANUAL_OFF,
        /** No Wi-Fi connection; Keep-Alive resumes when one appears. */
        NO_WIFI,
        /** The connected network is not trusted, or is blocked: nothing is enabled automatically. */
        UNTRUSTED_NETWORK,
        /** The Wi-Fi identity cannot be read (location access), so trust cannot be decided. */
        NETWORK_IDENTITY_UNAVAILABLE,
        /** A write was accepted but never took effect (#496); automatic retries are paused. */
        RECOVERY_BACKOFF
    }

    enum KeepAlive {
        /** The Keep-Alive setting is off. */
        DISABLED,
        /** Keep-Alive is on and Wireless Debugging is on. */
        ACTIVE,
        /** Keep-Alive is on, Wireless Debugging is off and it resolves by itself; see the reason. */
        WAITING,
        /** Keep-Alive is on but cannot act until the user or the setup changes something. */
        BLOCKED
    }

    /** Whether a verified WLAN endpoint is known, and if not, the cause that is knowable. */
    enum EndpointAvailability {
        AVAILABLE,
        NO_WIFI,
        ADB_OFF,
        /** Wireless Debugging and Wi-Fi are on and discovery has not produced an endpoint yet. */
        SEARCHING,
        /** The Wireless Debugging state cannot be read, so the absence cannot be explained. */
        UNKNOWN
    }

    enum Sync {
        /** No valid destination is configured, with or without the switch. */
        UNCONFIGURED,
        /** A destination is configured, the automatic synchronization switch is off. */
        OFF,
        ON
    }

    /** Alias of the report client's activity, so consumers need one import less. */
    enum Activity {
        IDLE, SENDING, RETRY_WAITING
    }

    /** The newest stored report outcome. Says nothing about whether it is current. */
    enum Outcome {
        NONE, SUCCESS, FAILED, DEREGISTERED
    }

    enum Delivery {
        /** The last accepted report names the current endpoint and the configured destination. */
        CURRENT,
        /** The endpoint and destination are known and no accepted report covers exactly them. */
        NOT_CURRENT,
        /** Without a current endpoint (lost, not yet rediscovered, after a restart) nothing can be claimed. */
        UNKNOWN
    }

    /**
     * #911: what "Report now" can do at this moment, for choosing the control. It reads the same
     * facts the action checks when it is tapped (a valid saved destination, a verified Wi-Fi
     * endpoint), whether or not automatic synchronization is on; the action still re-checks.
     */
    enum ReportNow {
        /** A valid destination is saved and a verified Wi-Fi endpoint is available. */
        READY,
        /** No valid destination is saved: offer the setup, a report would send nothing. */
        NEEDS_SETUP,
        /** No verified Wi-Fi endpoint: a report would send nothing. */
        NO_ENDPOINT
    }

    /** The webhook half of the projection. No URL, no destination host, no endpoint address. */
    static final class Webhook {
        final Sync sync;
        final Activity activity;
        /** Newest stored outcome; a failure after a success reads {@link Outcome#FAILED}. */
        final Outcome latestOutcome;
        /**
         * Historical: when a report to the currently configured destination was last accepted, or 0.
         * It survives a later failure and is not a claim about now, see {@link #delivery}.
         */
        final long lastSuccessAtMs;
        /** When the newest failed attempt of the last 24 hours happened, or 0 (history is bounded). */
        final long lastFailureAtMs;
        final Delivery delivery;
        /** What a "Report now" tap would do right now; see {@link ReportNow}. */
        final ReportNow reportNow;

        Webhook(Sync sync, Activity activity, Outcome latestOutcome, long lastSuccessAtMs,
                long lastFailureAtMs, Delivery delivery, ReportNow reportNow) {
            this.sync = sync;
            this.activity = activity;
            this.latestOutcome = latestOutcome;
            this.lastSuccessAtMs = lastSuccessAtMs;
            this.lastFailureAtMs = lastFailureAtMs;
            this.delivery = delivery;
            this.reportNow = reportNow;
        }
    }

    /** The legacy four-plus-one state the existing surfaces map their text from. */
    final KeepADB.State systemState;
    final Adb adb;
    /** {@link Reason#NONE} unless {@link #adb} is {@link Adb#PERMISSION_FAILURE}. */
    final Reason adbFailure;
    final KeepAlive keepAlive;
    final Reason keepAliveReason;
    final EndpointAvailability endpoint;
    /** Null while details are hidden or there is no endpoint. */
    final String endpointHost;
    /** 0 while details are hidden or there is no endpoint. */
    final int endpointPort;
    /** Epoch millis of the last reachability confirmation; 0 without endpoint. Not an identity. */
    final long endpointVerifiedAtMs;
    /** True when the global switch or the per-widget option withholds the connection details. */
    final boolean detailsHidden;
    final Webhook webhook;

    private KeepADBWidgetState(KeepADB.State systemState, Adb adb, Reason adbFailure,
            KeepAlive keepAlive, Reason keepAliveReason, EndpointAvailability endpoint,
            String endpointHost, int endpointPort, long endpointVerifiedAtMs,
            boolean detailsHidden, Webhook webhook) {
        this.systemState = systemState;
        this.adb = adb;
        this.adbFailure = adbFailure;
        this.keepAlive = keepAlive;
        this.keepAliveReason = keepAliveReason;
        this.endpoint = endpoint;
        this.endpointHost = endpointHost;
        this.endpointPort = endpointPort;
        this.endpointVerifiedAtMs = endpointVerifiedAtMs;
        this.detailsHidden = detailsHidden;
        this.webhook = webhook;
    }

    /** The projection for a surface without its own "hide details" option (tile, legacy widget). */
    static KeepADBWidgetState project(Context context) {
        return project(context, false);
    }

    /**
     * @param widgetHidesDetails the per-widget "hide details" option; it can only add to the global
     *         widget privacy switch, never lift it.
     */
    static KeepADBWidgetState project(Context context, boolean widgetHidesDetails) {
        Context app = context.getApplicationContext();
        boolean hidden = widgetHidesDetails || KeepADBPreferences.isWidgetPrivacyEnabled(app);

        KeepADB.State system = KeepADB.getState(app);
        Adb adb;
        Reason adbFailure = Reason.NONE;
        if (system == KeepADB.State.PERMISSION_MISSING) {
            adb = Adb.PERMISSION_FAILURE;
            // getState() reports an unreadable value as a missing permission (#580); the cause
            // decides which hint is actionable.
            adbFailure = KeepADB.hasPermission(app)
                    ? Reason.SETTING_UNREADABLE : Reason.PERMISSION_NOT_GRANTED;
        } else if (KeepADB.isTogglePending()) {
            adb = Adb.PENDING;
        } else if (system == KeepADB.State.OFF || system == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            adb = Adb.OFF;
        } else {
            adb = Adb.ON;
        }
        boolean adbOn = system == KeepADB.State.ENABLED_DISCONNECTED
                || system == KeepADB.State.ENABLED_CONNECTED;

        KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
        EndpointAvailability availability;
        if (snapshot.hasEndpoint()) {
            // A cached endpoint is only believed while Wi-Fi is up (see KeepADB#getState), but it is
            // reported whatever Wireless Debugging reads: availability does not depend on the toggle.
            availability = KeepADBService.isWifiConnected(app)
                    ? EndpointAvailability.AVAILABLE : EndpointAvailability.NO_WIFI;
        } else if (adb == Adb.PERMISSION_FAILURE) {
            availability = EndpointAvailability.UNKNOWN;
        } else if (!adbOn) {
            availability = EndpointAvailability.ADB_OFF;
        } else {
            availability = KeepADBService.isWifiConnected(app)
                    ? EndpointAvailability.SEARCHING : EndpointAvailability.NO_WIFI;
        }
        boolean endpointKnown = availability == EndpointAvailability.AVAILABLE;
        String currentEndpoint = endpointKnown
                ? KeepADBEndpoint.formatEndpoint(snapshot.host, snapshot.port) : null;

        KeepAlive keepAlive;
        Reason keepAliveReason = Reason.NONE;
        if (!KeepADBPreferences.isKeepAliveEnabled(app)) {
            keepAlive = KeepAlive.DISABLED;
        } else if (adb == Adb.PERMISSION_FAILURE) {
            keepAlive = KeepAlive.BLOCKED;
            keepAliveReason = adbFailure;
        } else if (adbOn) {
            keepAlive = KeepAlive.ACTIVE;
        } else if (KeepADB.wasLastExplicitIntentOff(app)) {
            keepAlive = KeepAlive.BLOCKED;
            keepAliveReason = Reason.MANUAL_OFF;
        } else if (!KeepADBService.isWifiConnected(app)) {
            keepAlive = KeepAlive.WAITING;
            keepAliveReason = Reason.NO_WIFI;
        } else {
            KeepADBTrustedNetwork.BlockReason trust = KeepADBTrustedNetwork.getBlockReason(app);
            if (trust == KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK) {
                keepAlive = KeepAlive.BLOCKED;
                keepAliveReason = Reason.UNTRUSTED_NETWORK;
            } else if (trust == KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE) {
                keepAlive = KeepAlive.BLOCKED;
                keepAliveReason = Reason.NETWORK_IDENTITY_UNAVAILABLE;
            } else if (KeepADB.isAutomaticEnableBackoffBlocked()) {
                keepAlive = KeepAlive.WAITING;
                keepAliveReason = Reason.RECOVERY_BACKOFF;
            } else {
                keepAlive = KeepAlive.WAITING;
            }
        }

        Webhook webhook = projectWebhook(app, currentEndpoint);

        return new KeepADBWidgetState(system, adb, adbFailure, keepAlive, keepAliveReason,
                availability,
                endpointKnown && !hidden ? snapshot.host : null,
                endpointKnown && !hidden ? snapshot.port : 0,
                endpointKnown ? snapshot.verifiedAtMs : 0L,
                hidden, webhook);
    }

    private static Webhook projectWebhook(Context app, String currentEndpoint) {
        String url = KeepADBPreferences.getRegisterWebhookUrl(app);
        boolean configured = url != null && !url.trim().isEmpty();
        Sync sync = !configured ? Sync.UNCONFIGURED
                : KeepADBPreferences.isRegisterWebhookEnabled(app) ? Sync.ON : Sync.OFF;

        Outcome outcome = outcomeOf(KeepADBPreferences.getWebhookLastReportStatus(app));
        Delivery delivery = deliveryOf(currentEndpoint, configured ? url.trim() : null, outcome,
                KeepADBPreferences.getWebhookLastReportedUrl(app),
                KeepADBPreferences.getWebhookLastReportedEndpoint(app));

        KeepADBRegisterClient.Activity clientActivity = KeepADBRegisterClient.activity();
        Activity activity = clientActivity == KeepADBRegisterClient.Activity.SENDING
                ? Activity.SENDING
                : clientActivity == KeepADBRegisterClient.Activity.RETRY_WAITING
                        ? Activity.RETRY_WAITING : Activity.IDLE;
        ReportNow reportNow = !KeepADBPreferences.isValidWebhookUrl(url) ? ReportNow.NEEDS_SETUP
                : currentEndpoint == null ? ReportNow.NO_ENDPOINT : ReportNow.READY;
        return new Webhook(sync, activity, outcome,
                KeepADBPreferences.getWebhookLastSuccessAt(app),
                KeepADBReportHistory.latestFailureAtMs(app), delivery, reportNow);
    }

    private static Outcome outcomeOf(String status) {
        if (KeepADBPreferences.WEBHOOK_STATUS_SUCCESS.equals(status)) return Outcome.SUCCESS;
        if (KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(status)) return Outcome.FAILED;
        if (KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(status)) return Outcome.DEREGISTERED;
        return Outcome.NONE;
    }

    /**
     * The currentness guard (#910): a success counts as the current delivery only while the stored
     * report names exactly the endpoint that is current now AND the destination configured now, and
     * it is still the newest outcome. A changed endpoint, a changed or removed destination, a later
     * failure, a deregistration and a missing record each end the claim, and a completion of an
     * older report can never restore it because the claim is derived from what is current, not
     * remembered. Without a current endpoint (lost, not yet rediscovered after a restart) nothing is
     * claimed either way.
     *
     * @param currentEndpoint the verified endpoint formatted like a report, or null without one
     * @param configuredUrl the destination configured now, or null without one
     */
    static Delivery deliveryOf(String currentEndpoint, String configuredUrl, Outcome outcome,
            String reportedUrl, String reportedEndpoint) {
        if (currentEndpoint == null) return Delivery.UNKNOWN;
        if (configuredUrl == null || outcome != Outcome.SUCCESS) return Delivery.NOT_CURRENT;
        if (reportedUrl == null || !configuredUrl.equals(reportedUrl.trim())) {
            return Delivery.NOT_CURRENT;
        }
        if (reportedEndpoint == null || !currentEndpoint.equals(reportedEndpoint.trim())) {
            return Delivery.NOT_CURRENT;
        }
        return Delivery.CURRENT;
    }
}
