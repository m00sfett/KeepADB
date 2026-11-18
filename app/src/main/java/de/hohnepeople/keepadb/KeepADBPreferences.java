package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/** Manages persistent preferences such as keep-alive auto-reconnect and webhook sync. */
final class KeepADBPreferences {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_KEEP_ALIVE = "keep_alive_enabled";
    private static final String KEY_REGISTER_URL = "register_webhook_url";
    private static final String KEY_WEBHOOK_ENABLED = "register_webhook_enabled";
    /** #976: opt-in periodic webhook refresh while Keep-Alive and Webhook Sync are active. */
    private static final String KEY_WEBHOOK_PERIODIC_ENABLED = "register_webhook_periodic_enabled";
    private static final String KEY_WEBHOOK_PERIODIC_INTERVAL = "register_webhook_periodic_interval";
    private static final String KEY_WEBHOOK_LAST_REPORTED = "register_webhook_last_reported";
    private static final String KEY_WEBHOOK_LAST_ENDPOINT = "register_webhook_last_endpoint";
    private static final String KEY_WEBHOOK_LAST_URL = "register_webhook_last_url";
    private static final String KEY_WEBHOOK_LAST_STATUS = "register_webhook_last_status";
    /** #734: epoch-millis of the last SUCCESSFUL report only; never touched by failures or deregistration. */
    private static final String KEY_WEBHOOK_LAST_SUCCESS_AT = "register_webhook_last_success_at";
    /** #893 (Codex review): the URL that accepted the success at {@link #KEY_WEBHOOK_LAST_SUCCESS_AT}. */
    private static final String KEY_WEBHOOK_LAST_SUCCESS_URL = "register_webhook_last_success_url";
    private static final String KEY_WEBHOOK_PENDING_CLEANUP = "register_webhook_pending_cleanup";
    /** #923: the URL of a registering POST whose outcome is not settled yet (see the accessor). */
    private static final String KEY_WEBHOOK_DISPATCHED_URL = "register_webhook_dispatched_url";
    /** #864: opt-in policy that turns Wireless Debugging off on an untrusted Wi-Fi; default off. */
    private static final String KEY_UNTRUSTED_SHUTDOWN = "untrusted_shutdown_enabled";
    /** #864: the Wi-Fi identity a manual enable was made on ("" when unreadable), or absent. */
    private static final String KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID = "untrusted_shutdown_manual_bssid";
    /** #864: elapsed-realtime millis when that manual exemption was recorded. */
    private static final String KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT = "untrusted_shutdown_manual_at";
    /** #997: on for new installations, off for existing ones; see migrateOpenNetworkShutdown. */
    static final String KEY_OPEN_NETWORK_SHUTDOWN = "open_network_shutdown_enabled";
    private static final String KEY_WEBHOOK_FAILURE_SINCE = "register_webhook_failure_since";
    private static final String KEY_WEBHOOK_ALERTS_SHOWN = "register_webhook_alerts_shown";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_SERVICE_LAST_HEARTBEAT = "service_last_heartbeat";
    private static final String KEY_HIDE_NOTIFICATION = "hide_notification_enabled";
    /** Package-visible for the timer's end commit (#1000), which writes the "off" intent with it. */
    static final String KEY_LAST_DESIRED_ON = "last_desired_on";
    private static final String KEY_KEEP_DISPLAY_ON = "keep_display_on_enabled";
    /**
     * #998: the automation interface. None of these keys is in the settings backup
     * ({@link KeepADBSettingsBackup} exports only the keys it has a rule for): an import must never
     * silently open the exported entry point, and the token must never leave the device.
     */
    static final String KEY_AUTOMATION_ENABLED = "automation_enabled";
    static final String KEY_AUTOMATION_TOKEN = "automation_token";
    static final String KEY_AUTOMATION_REJECTED_COUNT = "automation_rejected_count";
    static final String KEY_AUTOMATION_LAST_REJECTED_AT = "automation_last_rejected_at";
    /** #1000: the preselected Wifi-ADB timer duration, a {@link KeepADBTimer.Duration} token. */
    static final String KEY_TIMER_DEFAULT_DURATION = "timer_default_duration";
    // #764: keys of the home cards that moved into the assistant. Never read or written any more;
    // kept only to be deleted (removeObsoleteHomeCardKeys). Their meaning, for the record:
    // #528 notification permission, #502 battery optimization, #619 network onboarding and #616
    // background location card, each "visible = true until dismissed".
    private static final String[] OBSOLETE_HOME_CARD_KEYS = {
            "notification_permission_panel_visible",
            "battery_optimization_panel_visible",
            "network_onboarding_panel_visible",
            "background_location_panel_visible",
    };
    // #956: keys of the removed USB-ADB area (notification, host profiles, handover mode). Never
    // read or written any more; kept only to be deleted (removeObsoleteUsbKeys). The host
    // profiles hold host names and IP addresses, so they must not outlive the update. A profile
    // stores its fields as usb_profile_<id>_<field>, which the prefix covers.
    private static final String[] OBSOLETE_USB_KEYS = {
            "usb_notification_enabled",
            "usb_profile_notification_enabled",
            "usb_profile_next_id",
            "usb_profile_selected_id",
            "usb_profile_ids",
            "usb_wlan_handover_mode",
    };
    private static final String OBSOLETE_USB_PROFILE_PREFIX = "usb_profile_";
    // #958: the dismiss flag of the home-screen advice card (#225). The advice moved into the
    // setup assistant and is always shown there, so nothing reads the flag; kept only to be
    // deleted (removeObsoleteAdviceBannerKey).
    private static final String OBSOLETE_ADVICE_BANNER_KEY = "advice_banner_visible";
    // #960: the retry timing profile ("fast", "standard", "gentle") and the retry count. The series
    // is fixed now, so nothing reads them; kept only to be deleted (removeObsoleteWebhookRetryKeys),
    // so a value an older version stored cannot be mistaken for a setting that still applies.
    private static final String[] OBSOLETE_WEBHOOK_RETRY_KEYS = {
            "register_webhook_retry_profile",
            "register_webhook_retry_count",
    };
    // #904: three independent, opt-in privacy switches (all off on a new installation). Each one
    // masks its own area only and is purely a rendering preference, never the toggle facade's own
    // WRITE_SECURE_SETTINGS state and never the real ADB transport.
    //  - App (#482/#483): every screen and dialog. This is the former single "privacy mode", so
    //    the key and its stored value are unchanged.
    //  - Notifications: every KeepADB notification. Replaces the former "notification details"
    //    opt-in (#592) with the opposite polarity; see migratePrivacySplit.
    //  - Widgets: every home-screen widget and the Quick Settings tile.
    private static final String KEY_PRIVACY_MODE_ENABLED = "privacy_mode_enabled";
    static final String KEY_NOTIFICATION_PRIVACY_ENABLED = "notification_privacy_enabled";
    static final String KEY_WIDGET_PRIVACY_ENABLED = "widget_privacy_enabled";
    /** #920: keep every KeepADB notification off the lock screen. */
    static final String KEY_LOCKSCREEN_PRIVACY_ENABLED = "lockscreen_privacy_enabled";
    /** #904: bookkeeping, not a setting. Set once when the three switches were derived. */
    static final String KEY_PRIVACY_SPLIT_INITIALIZED = "privacy_split_initialized";
    // #507: former opt-in toggle for Wi-Fi & access point discovery. Since #769 no UI, since #788
    // no code reads or writes it; the key stays a name so a stored value is recognised as legacy.
    static final String KEY_WIFI_APS_FEATURE_ENABLED = "wifi_aps_feature_enabled";
    // #592: former opt-in for connection details inside notifications. Since #904 nothing reads or
    // writes it any more except the one-time migration into KEY_NOTIFICATION_PRIVACY_ENABLED; the
    // stored value stays untouched, so an older app that is installed again still finds it.
    static final String KEY_NOTIFICATION_DETAILS_ENABLED = "notification_details_enabled";

    // #761: the setup assistant. The completed version is the number of the assistant that was
    // last closed ("Later" or "Done"); a higher CURRENT_VERSION shows the assistant once more. The
    // install kind is decided once, before anything else writes, and kept so that the intro stays
    // the same after a process death in the middle of the assistant.
    static final String KEY_ONBOARDING_COMPLETED_VERSION = "onboarding_completed_version";
    static final String KEY_UPGRADE_NOTICE_SHOWN = "upgrade_notice_1_10_shown";
    static final String KEY_ONBOARDING_EXISTING_INSTALL = "onboarding_existing_install";

    private KeepADBPreferences() {}

    static final String WEBHOOK_STATUS_NEVER = "never";
    static final String WEBHOOK_STATUS_SUCCESS = "success";
    static final String WEBHOOK_STATUS_DEREGISTERED = "deregistered";
    static final String WEBHOOK_STATUS_FAILED = "failed";

    /**
     * Persisted record of the last explicit on/off user intent. Survives process death and LMK
     * so recovery doesn't fail open after process resurrection. Defaults to true.
     */
    static boolean getLastDesiredOn(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_LAST_DESIRED_ON, true);
    }

    /** #1000: the preselected timer duration; {@link KeepADBTimer.Duration#DEFAULT} if unset. */
    static KeepADBTimer.Duration getTimerDefaultDuration(Context context) {
        if (context == null) return KeepADBTimer.Duration.DEFAULT;
        return KeepADBTimer.Duration.fromToken(context.getSharedPreferences(PREFS_NAME,
                Context.MODE_PRIVATE).getString(KEY_TIMER_DEFAULT_DURATION, null));
    }

    static void setTimerDefaultDuration(Context context, KeepADBTimer.Duration duration) {
        if (context == null || duration == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TIMER_DEFAULT_DURATION, duration.token).apply();
    }

    static void setLastDesiredOn(Context context, boolean on) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_LAST_DESIRED_ON, on).apply();
    }

    /**
     * #904: whether KeepADB notifications must stay free of connection details (port, IP address,
     * Wi-Fi name, BSSID). Default OFF, so a new installation shows the details;
     * when they are shown they can also appear on the lock screen if the device shows sensitive
     * notification content there. An existing installation keeps what it had: see {@link
     * #migratePrivacySplit}.
     */
    static boolean isNotificationPrivacyEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensurePrivacySplit(prefs);
        return prefs.getBoolean(KEY_NOTIFICATION_PRIVACY_ENABLED, false);
    }

    static void setNotificationPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensurePrivacySplit(prefs);
        prefs.edit().putBoolean(KEY_NOTIFICATION_PRIVACY_ENABLED, enabled).apply();
    }

    /**
     * #920: whether KeepADB notifications are kept off the lock screen entirely
     * ({@code VISIBILITY_SECRET}), independent of Android's "sensitive content" setting. Default
     * OFF; it is a new key, so existing installations keep what they had. Unlocked, the shade
     * shows the notifications as the notification switch above decides.
     */
    static boolean isLockScreenPrivacyEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCKSCREEN_PRIVACY_ENABLED, false);
    }

    static void setLockScreenPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LOCKSCREEN_PRIVACY_ENABLED, enabled).apply();
    }

    /**
     * #904: whether every widget and the Quick Settings tile must show the state only, without
     * port or address. Default OFF. It takes precedence over any per-widget "hide details" option.
     */
    static boolean isWidgetPrivacyEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensurePrivacySplit(prefs);
        return prefs.getBoolean(KEY_WIDGET_PRIVACY_ENABLED, false);
    }

    static void setWidgetPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensurePrivacySplit(prefs);
        prefs.edit().putBoolean(KEY_WIDGET_PRIVACY_ENABLED, enabled).apply();
    }

    /**
     * #904: derives the notification and widget switches once, from what the installation stored
     * under the former single privacy mode and the former notification details opt-in, following
     * the pattern of {@code KeepADBTrustedNetwork#ensureModeInitialized} (a default is persisted
     * once, never computed from a fallback).
     *
     * <ul>
     *   <li><b>New installation</b> (nothing stored yet): the marker only; all three switches
     *       stay at their default, off.</li>
     *   <li><b>Existing installation:</b> nothing may loosen. Notification privacy is on unless
     *       {@code notification_details_enabled} was stored as {@code true} (before #904 the
     *       details were hidden by default, so an absent key counts as hidden). Widget privacy is
     *       on when the former privacy mode was on. The app switch is the former privacy mode and
     *       keeps its key and value.</li>
     * </ul>
     * The app runs this from {@link KeepADBApplication#onCreate}, before any component writes, so
     * "nothing stored" really identifies a new installation. Idempotent: keyed on the marker.
     */
    static void migratePrivacySplit(Context context) {
        if (context == null) return;
        ensurePrivacySplit(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    private static void ensurePrivacySplit(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_PRIVACY_SPLIT_INITIALIZED, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        // #997: the open-network default is stored just before this and is no sign of an
        // existing installation.
        java.util.Map<String, ?> stored = new java.util.HashMap<>(prefs.getAll());
        stored.remove(KEY_OPEN_NETWORK_SHUTDOWN);
        if (!stored.isEmpty()) {
            boolean detailsShown = prefs.getBoolean(KEY_NOTIFICATION_DETAILS_ENABLED, false);
            editor.putBoolean(KEY_NOTIFICATION_PRIVACY_ENABLED, !detailsShown);
            editor.putBoolean(KEY_WIDGET_PRIVACY_ENABLED,
                    prefs.getBoolean(KEY_PRIVACY_MODE_ENABLED, false));
        }
        editor.putBoolean(KEY_PRIVACY_SPLIT_INITIALIZED, true).apply();
    }

    static boolean isNotificationHidden(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_HIDE_NOTIFICATION, false);
    }

    static void setNotificationHidden(Context context, boolean hidden) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HIDE_NOTIFICATION, hidden).apply();
    }

    /** #224: keeps the display on only while MainActivity is in the foreground. Default OFF. */
    static boolean isKeepDisplayOnEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_DISPLAY_ON, false);
    }

    static void setKeepDisplayOnEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_DISPLAY_ON, enabled).apply();
    }

    static boolean isKeepAliveEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_ALIVE, false);
    }

    /**
     * #1000: whether Keep-Alive acts right now. The stored setting ({@link #isKeepAliveEnabled},
     * which every settings surface shows and writes) or a running Wifi-ADB timer, which is a
     * temporary Keep-Alive overlay. A timer whose time is up but whose end has not been performed
     * yet suppresses Keep-Alive entirely, the stored setting included: the end is a "manual off"
     * not written yet, and nothing may re-enable Wifi-ADB in between. Every decision about keeping
     * Wifi-ADB alive reads this; only the settings surfaces read the stored setting.
     */
    static boolean isKeepAliveEffective(Context context) {
        if (context == null) return false;
        if (KeepADBTimer.isExpiryPending(context) || KeepADBTimer.isShutdownPending(context)) return false;
        return isKeepAliveEnabled(context) || KeepADBTimer.isActive(context);
    }

    static void setKeepAliveEnabled(Context context, boolean enabled) {
        KeepADBDiagnostics.event(context, "keep_alive_setting", "app", enabled ? "enabled" : "disabled", "user_setting");
        KeepADB.consumeUserDisabled();
        if (enabled) {
            KeepADB.recordExplicitIntent(context, true);
        }
        if (context == null) return;
        // #962: a decision about Keep-Alive voids a pending self-pairing cleanup.
        KeepADBSelfPairCleanup.supersede(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
        KeepADBWarningState.observe(context);
        refreshWidgetState(context);
    }

    static boolean isRegisterWebhookEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_WEBHOOK_ENABLED, false);
    }

    static void setRegisterWebhookEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_WEBHOOK_ENABLED, enabled).apply();
        KeepADBWarningState.observe(context);
        refreshWidgetState(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** #998: whether the automation interface accepts requests; off by default. */
    static boolean isAutomationEnabled(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_AUTOMATION_ENABLED, false);
    }

    static void setAutomationEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).apply();
    }

    /** #998: the automation token, or {@code null} before the interface was first enabled. */
    static String getAutomationToken(Context context) {
        if (context == null) return null;
        String token = prefs(context).getString(KEY_AUTOMATION_TOKEN, null);
        return token == null || token.isEmpty() ? null : token;
    }

    static void setAutomationToken(Context context, String token) {
        if (context == null) return;
        prefs(context).edit().putString(KEY_AUTOMATION_TOKEN, token).apply();
    }

    static int getAutomationRejectedCount(Context context) {
        if (context == null) return 0;
        return prefs(context).getInt(KEY_AUTOMATION_REJECTED_COUNT, 0);
    }

    static long getAutomationLastRejectedAt(Context context) {
        if (context == null) return 0L;
        return prefs(context).getLong(KEY_AUTOMATION_LAST_REJECTED_AT, 0L);
    }

    static synchronized void recordAutomationRejection(Context context, long nowMillis) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        int count = prefs.getInt(KEY_AUTOMATION_REJECTED_COUNT, 0);
        prefs.edit()
                .putInt(KEY_AUTOMATION_REJECTED_COUNT, count == Integer.MAX_VALUE ? count : count + 1)
                .putLong(KEY_AUTOMATION_LAST_REJECTED_AT, nowMillis)
                .apply();
    }

    static void resetAutomationRejections(Context context) {
        if (context == null) return;
        prefs(context).edit()
                .remove(KEY_AUTOMATION_REJECTED_COUNT)
                .remove(KEY_AUTOMATION_LAST_REJECTED_AT)
                .apply();
    }

    /** #976: opt-in periodic webhook refresh while Keep-Alive and Webhook Sync are active; default off. */
    static boolean isPeriodicWebhookReportEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_WEBHOOK_PERIODIC_ENABLED, false);
    }

    static void setPeriodicWebhookReportEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_WEBHOOK_PERIODIC_ENABLED, enabled).apply();
    }

    /** #976: chosen schedule preset for periodic webhook refreshes; defaults to 4 h (3 h 59 m). */
    static KeepADBPeriodicReport.Interval getPeriodicWebhookInterval(Context context) {
        if (context == null) return KeepADBPeriodicReport.Interval.DEFAULT;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return KeepADBPeriodicReport.Interval.fromId(
                prefs.getString(KEY_WEBHOOK_PERIODIC_INTERVAL,
                        KeepADBPeriodicReport.Interval.DEFAULT.id));
    }

    static void setPeriodicWebhookInterval(Context context,
            KeepADBPeriodicReport.Interval interval) {
        if (context == null) return;
        KeepADBPeriodicReport.Interval chosen = interval != null
                ? interval : KeepADBPeriodicReport.Interval.DEFAULT;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_WEBHOOK_PERIODIC_INTERVAL, chosen.id).apply();
    }

    static String getRegisterWebhookUrl(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sanitizeWebhookUrl(prefs.getString(KEY_REGISTER_URL, null));
    }

    static void setRegisterWebhookUrl(Context context, String url) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String sanitized = sanitizeWebhookUrl(url);
        if (sanitized == null || sanitized.trim().isEmpty()) {
            prefs.edit().remove(KEY_REGISTER_URL).apply();
        } else {
            prefs.edit().putString(KEY_REGISTER_URL, sanitized.trim()).apply();
        }
        KeepADBWarningState.observe(context);
        refreshWidgetState(context);
    }

    /**
     * #910: the widgets show the Keep-Alive and webhook configuration, so a change of it re-renders
     * them. State only ({@link KeepADBWidget#refreshAllState}): it reads, it never reports or
     * starts a discovery, so saving a setting cannot cause traffic through this call.
     */
    private static void refreshWidgetState(Context context) {
        KeepADBWidget.refreshAllState(context.getApplicationContext());
    }

    static long getWebhookLastReportedAt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L);
    }

    /**
     * #734: when the last report was actually accepted by the webhook, or 0 if none ever was.
     * {@link #getWebhookLastReportedAt} cannot answer this: a deregistration also moves it. Older
     * installations without the dedicated key fall back to that timestamp only while a reported
     * endpoint is still stored, which is exactly the "last report was a success" state.
     */
    static long getWebhookLastSuccessAt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // #893 (Codex review): a success belongs to the URL that accepted it, so a replaced
        // destination, a late answer from the previous one or an older version's leftover does
        // not show up as a success for the current URL. Older records name their URL only in the
        // last report snapshot; a time whose URL is not known at all is not claimed either.
        String owner = prefs.getString(KEY_WEBHOOK_LAST_SUCCESS_URL,
                prefs.getString(KEY_WEBHOOK_LAST_URL, null));
        if (owner == null || !owner.trim().equals(prefs.getString(KEY_REGISTER_URL, ""))) {
            return 0L;
        }
        if (prefs.contains(KEY_WEBHOOK_LAST_SUCCESS_AT)) {
            return prefs.getLong(KEY_WEBHOOK_LAST_SUCCESS_AT, 0L);
        }
        String endpoint = prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L);
        }
        return 0L;
    }

    static void setWebhookLastReportedAtNow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_WEBHOOK_LAST_REPORTED, System.currentTimeMillis()).apply();
    }

    static String getWebhookLastReportedEndpoint(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
    }

    static void setWebhookLastReportedEndpoint(Context context, String endpoint) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_ENDPOINT).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_ENDPOINT, endpoint.trim()).apply();
        }
    }

    /**
     * Sanitised on read so a legacy URL containing user information cannot be reused by a later
     * cleanup request or written to a log. Production cleanup uses HTTP DELETE at the stored URL.
     */
    static String getWebhookLastReportedUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sanitizeWebhookUrl(prefs.getString(KEY_WEBHOOK_LAST_URL, null));
    }

    static void setWebhookLastReportedUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String sanitized = sanitizeWebhookUrl(url);
        if (sanitized == null) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_URL).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_URL, sanitized).apply();
        }
    }

    /**
     * Returns the last Wifi-ADB webhook result. Missing status is derived from the legacy fields
     * so existing installations keep their previous success/deregistration meaning.
     */
    static String getWebhookLastReportStatus(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_WEBHOOK_LAST_STATUS, null);
        if (isWebhookReportStatus(stored)) {
            return stored;
        }
        String endpoint = prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            return WEBHOOK_STATUS_SUCCESS;
        }
        return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L) > 0L
                ? WEBHOOK_STATUS_DEREGISTERED : WEBHOOK_STATUS_NEVER;
    }

    static void setWebhookLastReportStatus(Context context, String status) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (isWebhookReportStatus(status)) {
            prefs.edit().putString(KEY_WEBHOOK_LAST_STATUS, status).apply();
        } else {
            prefs.edit().remove(KEY_WEBHOOK_LAST_STATUS).apply();
        }
    }

    private static boolean isWebhookReportStatus(String status) {
        return WEBHOOK_STATUS_SUCCESS.equals(status)
                || WEBHOOK_STATUS_DEREGISTERED.equals(status)
                || WEBHOOK_STATUS_FAILED.equals(status)
                || WEBHOOK_STATUS_NEVER.equals(status);
    }

    /**
     * #317: writes the whole Wifi-ADB report snapshot through a single {@link SharedPreferences.Editor}.
     *
     * <p>Timestamp, URL, endpoint and status used to be written through four independent
     * {@code apply()} calls, so a crash or LMK kill between two of them could persist a URL
     * without its endpoint (or a success status without the endpoint it refers to). One editor
     * with several {@code put*()} calls is written to the preferences file as one unit.
     *
     * <p>A {@code null} {@code status} leaves the stored status untouched; any other value follows
     * the same validity rule as {@link #setWebhookLastReportStatus(Context, String)}.
     */
    static void setWebhookReportSnapshot(Context context, String url, String endpoint, String status,
            boolean touchTimestamp) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        // #923: every snapshot is written by a settled register transaction, which also settles
        // the dispatch marker; in the same editor transaction, so no crash can split the two.
        editor.remove(KEY_WEBHOOK_DISPATCHED_URL);
        String sanitizedUrl = sanitizeWebhookUrl(url);
        if (sanitizedUrl == null) {
            editor.remove(KEY_WEBHOOK_LAST_URL);
        } else {
            editor.putString(KEY_WEBHOOK_LAST_URL, sanitizedUrl);
        }
        if (endpoint == null) {
            editor.remove(KEY_WEBHOOK_LAST_ENDPOINT);
        } else {
            editor.putString(KEY_WEBHOOK_LAST_ENDPOINT, endpoint);
        }
        if (status != null) {
            if (isWebhookReportStatus(status)) {
                editor.putString(KEY_WEBHOOK_LAST_STATUS, status);
            } else {
                editor.remove(KEY_WEBHOOK_LAST_STATUS);
            }
        }
        if (touchTimestamp) {
            long now = System.currentTimeMillis();
            editor.putLong(KEY_WEBHOOK_LAST_REPORTED, now);
            if (WEBHOOK_STATUS_SUCCESS.equals(status)) {
                editor.putLong(KEY_WEBHOOK_LAST_SUCCESS_AT, now);
                if (sanitizedUrl == null) {
                    editor.remove(KEY_WEBHOOK_LAST_SUCCESS_URL);
                } else {
                    editor.putString(KEY_WEBHOOK_LAST_SUCCESS_URL, sanitizedUrl.trim());
                }
            }
        }
        editor.apply();
    }

    // ---- #866: bounded retry series for a failed endpoint-report POST (fixed since #960). ----

    /** Retries per series, after the first attempt. */
    static final int WEBHOOK_MAX_RETRIES = 4;
    /** Delay before retry 1..4 of a series: 5, 15, 30 and 60 seconds. */
    private static final long[] WEBHOOK_RETRY_DELAYS_MS = {5_000L, 15_000L, 30_000L, 60_000L};

    /** Delay before retry {@code retryNumber} (1-based) of the fixed series, in milliseconds. */
    static long webhookRetryDelayMs(int retryNumber) {
        int index = Math.max(1, Math.min(WEBHOOK_RETRY_DELAYS_MS.length, retryNumber)) - 1;
        return WEBHOOK_RETRY_DELAYS_MS[index];
    }

    // ---- #868: warnings for a continuous period without a successful report. ----

    /** #864: whether Wireless Debugging is turned off on an untrusted Wi-Fi; off by default. */
    static boolean isUntrustedShutdownEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_UNTRUSTED_SHUTDOWN, false);
    }

    static void setUntrustedShutdownEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_UNTRUSTED_SHUTDOWN, enabled).apply();
    }

    /**
     * #997: whether Wifi-ADB is turned off (and not recovered) in an open Wi-Fi or behind a
     * captive portal, trusted access points included. An absent key reads as off; the stored
     * default comes from {@link #migrateOpenNetworkShutdown}.
     */
    static boolean isOpenNetworkShutdownEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_OPEN_NETWORK_SHUTDOWN, false);
    }

    static void setOpenNetworkShutdownEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_OPEN_NETWORK_SHUTDOWN, enabled).apply();
    }

    /**
     * #997: stores the default of the open-network rule once. A new installation (nothing stored
     * yet) gets it on; an existing installation gets it off, so an update changes nothing until
     * its user decides. Must run in {@link KeepADBApplication#onCreate} before anything else
     * writes a preference, which is the only moment "nothing stored" identifies a new
     * installation. Idempotent: keyed on the setting itself.
     */
    static void migrateOpenNetworkShutdown(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_OPEN_NETWORK_SHUTDOWN)) return;
        prefs.edit().putBoolean(KEY_OPEN_NETWORK_SHUTDOWN, prefs.getAll().isEmpty()).apply();
    }

    /**
     * #864: the readable BSSID a manual enable was made on, or null when no manual exemption is
     * recorded.
     */
    static String getUntrustedShutdownManualBssid(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID, null);
    }

    /** Clears the manual exemption. */
    static void clearUntrustedShutdownManualBssid(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID)
                .remove(KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT).apply();
    }

    /** Records the manual exemption for {@code bssid} at elapsed-realtime {@code atElapsedMs}. */
    static void setUntrustedShutdownManualBssid(Context context, String bssid, long atElapsedMs) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID, bssid)
                .putLong(KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT, atElapsedMs).apply();
    }

    /** Elapsed-realtime millis of the recorded manual exemption, or -1 without one. */
    static long getUntrustedShutdownManualAt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT, -1L);
    }

    /** Epoch-millis of the first failed report of the current failure episode, or 0 without one. */
    static long getWebhookFailureSince(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_WEBHOOK_FAILURE_SINCE, 0L);
    }

    static int getWebhookAlertsShown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_WEBHOOK_ALERTS_SHOWN, 0);
    }

    /** One editor transaction, so an episode start is never stored without its alert count. */
    static void setWebhookFailureEpisode(Context context, long failureSince, int alertsShown) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (failureSince <= 0L) {
            editor.remove(KEY_WEBHOOK_FAILURE_SINCE).remove(KEY_WEBHOOK_ALERTS_SHOWN);
        } else {
            editor.putLong(KEY_WEBHOOK_FAILURE_SINCE, failureSince)
                    .putInt(KEY_WEBHOOK_ALERTS_SHOWN, alertsShown);
        }
        editor.apply();
    }

    // ---- #317: retryable cleanups for registrations left behind on a previous webhook URL. ----

    /**
     * Upper bound for either pending-cleanup set. Each retry costs one HTTP round trip on the
     * register executor, so the backlog is deliberately small; a full set evicts its OLDEST entry
     * (FIFO, see #368) rather than growing without limit or rejecting the newest one.
     */
    static final int MAX_PENDING_CLEANUPS = 4;

    /**
     * Separator joining entries in the ordered FIFO shadow list (#368). {@code SharedPreferences}
     * string sets have no defined iteration order, so the eviction order can't be derived from the
     * legacy {@code StringSet} keys alone; this ASCII Group Separator control character is joined
     * between entries in a plain {@code String} preference instead, preserving insertion order.
     * Chosen because it cannot appear in a URL.
     */
    private static final String ORDER_SEPARATOR = "\u001D";

    static java.util.Set<String> getPendingWebhookCleanupUrls(Context context) {
        return getPendingCleanups(context, KEY_WEBHOOK_PENDING_CLEANUP);
    }

    /**
     * Queues {@code url} as a pending cleanup. Returns the entry that had to make room for it --
     * the OLDEST one of a full backlog, see {@link #MAX_PENDING_CLEANUPS} -- or {@code null} when
     * nothing was evicted (blank url, entry already queued, or a free slot). #707: the caller owns
     * whatever else belongs to an evicted entry (its retry record); this class does not know it.
     */
    static String addPendingWebhookCleanupUrl(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return null;
        return addPendingCleanup(context, KEY_WEBHOOK_PENDING_CLEANUP, url);
    }

    static void removePendingWebhookCleanupUrl(Context context, String url) {
        removePendingCleanup(context, KEY_WEBHOOK_PENDING_CLEANUP, url);
    }

    /**
     * #923: the destination of a registering POST that has been sent but whose outcome nothing has
     * settled yet, or {@code null}. Written synchronously before the request, so a process that
     * dies while the POST is in flight still knows where it may have registered. Like the pending
     * cleanups, the raw URL is kept (the DELETE needs it) and sanitized only where it is used.
     */
    static String getWebhookDispatchedUrl(Context context) {
        String url = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_WEBHOOK_DISPATCHED_URL, null);
        return url == null || url.trim().isEmpty() ? null : url;
    }

    /** Writes or clears the #923 dispatch marker with {@code commit()}: it must land before the POST. */
    static void setWebhookDispatchedUrl(Context context, String url) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (url == null || url.trim().isEmpty()) {
            editor.remove(KEY_WEBHOOK_DISPATCHED_URL);
        } else {
            editor.putString(KEY_WEBHOOK_DISPATCHED_URL, url);
        }
        editor.commit();
    }

    /** The ordered shadow key for a legacy pending-cleanup {@code StringSet} key. */
    private static String orderKeyFor(String key) {
        return key + "_order";
    }

    /**
     * Returns the pending cleanups in FIFO order (oldest first, see #368).
     *
     * <p>Always a fresh, mutable copy. Preferred source is the ordered shadow key written by
     * {@link #persistPendingCleanups}. When that key is missing (e.g. data written by an app
     * version predating #368) but the legacy {@code StringSet} key holds entries, the order is
     * reconstructed best-effort from the set's iteration order -- {@code SharedPreferences}
     * string sets carry no defined ordering, so this is only a starting point, not a guarantee
     * that it matches the original insertion order. It is immediately persisted in the new
     * ordered format so that later evictions are FIFO-correct going forward.
     */
    private static java.util.LinkedHashSet<String> getPendingCleanups(Context context, String key) {
        if (context == null) return new java.util.LinkedHashSet<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String orderKey = orderKeyFor(key);
        String joined = prefs.getString(orderKey, null);
        if (joined != null) {
            java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
            if (!joined.isEmpty()) {
                for (String entry : joined.split(ORDER_SEPARATOR, -1)) {
                    if (!entry.isEmpty()) ordered.add(entry);
                }
            }
            return ordered;
        }
        java.util.Set<String> legacy = prefs.getStringSet(key, null);
        java.util.LinkedHashSet<String> migrated = legacy == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(legacy);
        if (!migrated.isEmpty()) {
            persistPendingCleanups(context, key, migrated);
        }
        return migrated;
    }

    /** Persists both the legacy {@code StringSet} key (compat) and the new ordered shadow key. */
    private static void persistPendingCleanups(Context context, String key, java.util.LinkedHashSet<String> pending) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String orderKey = orderKeyFor(key);
        if (pending.isEmpty()) {
            prefs.edit().remove(key).remove(orderKey).apply();
            return;
        }
        prefs.edit()
                .putStringSet(key, pending)
                .putString(orderKey, String.join(ORDER_SEPARATOR, pending))
                .apply();
    }

    private static String addPendingCleanup(Context context, String key, String entry) {
        if (context == null) return null;
        java.util.LinkedHashSet<String> pending = getPendingCleanups(context, key);
        if (pending.contains(entry)) return null;
        String evicted = null;
        if (pending.size() >= MAX_PENDING_CLEANUPS) {
            // #368: evict the OLDEST entry (head of insertion order), not the newest, so the
            // newest -- most likely to still be a live orphan registration -- is kept.
            java.util.Iterator<String> oldest = pending.iterator();
            evicted = oldest.next();
            oldest.remove();
        }
        pending.add(entry);
        persistPendingCleanups(context, key, pending);
        return evicted;
    }

    private static void removePendingCleanup(Context context, String key, String entry) {
        if (context == null || entry == null) return;
        java.util.LinkedHashSet<String> pending = getPendingCleanups(context, key);
        if (!pending.remove(entry)) return;
        persistPendingCleanups(context, key, pending);
    }

    static boolean isValidWebhookUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return false;
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(trimmed);
            return uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static String sanitizeWebhookUrl(String rawUrl) {
        if (rawUrl == null) return null;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return "";
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) return null;
        }

        try {
            java.net.URI uri = new java.net.URI(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme != null && host != null) {
                String path = uri.getPath();
                if (path != null && path.isEmpty()) {
                    path = null;
                }
                java.net.URI sanitized = new java.net.URI(
                        scheme.toLowerCase(java.util.Locale.ROOT),
                        null,
                        host,
                        uri.getPort(),
                        path,
                        uri.getQuery(),
                        null);
                return sanitized.toString();
            }
        } catch (Exception ignored) {
            // Non-standard URI syntax; fallback to manual stripping below.
        }

        String result = trimmed;
        int hashIdx = result.indexOf('#');
        if (hashIdx >= 0) {
            result = result.substring(0, hashIdx);
        }
        int schemeIdx = result.indexOf("://");
        if (schemeIdx >= 0) {
            String schemePart = result.substring(0, schemeIdx + 3).toLowerCase(java.util.Locale.ROOT);
            String remainder = result.substring(schemeIdx + 3);
            int slashIdx = remainder.indexOf('/');
            int queryIdx = remainder.indexOf('?');
            int authEnd = (slashIdx >= 0 && queryIdx >= 0)
                    ? Math.min(slashIdx, queryIdx)
                    : (slashIdx >= 0 ? slashIdx : queryIdx);
            // A password may contain '@'. Strip the complete userinfo authority suffix, not just
            // the part before its first embedded '@' (legacy values can reach this fallback when
            // URI parsing rejects a non-standard host such as an IPv6 zone-id literal). Ignore
            // any '@' that belongs to the path or query when locating that authority delimiter.
            int atIdx = authEnd >= 0
                    ? remainder.lastIndexOf('@', authEnd - 1)
                    : remainder.lastIndexOf('@');
            if (atIdx >= 0 && (authEnd < 0 || atIdx < authEnd)) {
                remainder = remainder.substring(atIdx + 1);
            }
            result = schemePart + remainder;
        }
        return result;
    }

    /**
     * #350: UI-facing redaction. Delegates to {@link KeepADBUrlRedaction}, the one place that
     * decides how userinfo, host, port, path, query and fragment are treated. This method used to
     * mask IPv4 hosts only, leaving query parameters and IPv6 literals fully readable.
     *
     * <p>Never feed the result into a request — it is display text, not a URL.
     */
    static String maskWebhookUrl(String rawUrl) {
        return KeepADBUrlRedaction.forDisplay(rawUrl);
    }

    /**
     * #483: same display text, with the stricter host rule applied while the privacy mode is on.
     * Reads the toggle itself so all three render sites cannot drift apart.
     *
     * <p>Never feed the result into a request — it is display text, not a URL.
     */
    static String maskWebhookUrlForDisplay(Context context, String rawUrl) {
        return KeepADBUrlRedaction.forDisplay(rawUrl, KeepADBPrivacy.isAppMasked(context));
    }

    /**
     * #483: masks a {@code host:port} endpoint for display while the privacy mode is on; returns
     * the value unchanged while it is off.
     */
    static String maskEndpointForDisplay(Context context, String endpoint) {
        return KeepADBPrivacy.isAppMasked(context)
                ? KeepADBAddressMask.maskEndpointForDisplay(endpoint) : endpoint;
    }

    /** #483: masks a bare host for display while the privacy mode is on. */
    static String maskHostForDisplay(Context context, String host) {
        return KeepADBPrivacy.isAppMasked(context) ? KeepADBAddressMask.maskHost(host) : host;
    }

    /** Marks the moment the foreground service was known alive; used to log restart gaps. */
    static long getServiceLastHeartbeat(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_SERVICE_LAST_HEARTBEAT, 0L);
    }

    static void setServiceLastHeartbeatNow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_SERVICE_LAST_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    static String getAppLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_APP_LANGUAGE, "");
    }

    static void setAppLanguage(Context context, String languageTag) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (languageTag == null || languageTag.trim().isEmpty()) {
            prefs.edit().remove(KEY_APP_LANGUAGE).apply();
        } else {
            prefs.edit().putString(KEY_APP_LANGUAGE, languageTag.trim()).apply();
        }
    }

    /** #761: version of the setup assistant the user last closed; 0 when it never was. */
    static int getOnboardingCompletedVersion(Context context) {
        if (context == null) return 0;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_ONBOARDING_COMPLETED_VERSION, 0);
    }

    static void setOnboardingCompletedVersion(Context context, int version) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_ONBOARDING_COMPLETED_VERSION, version).apply();
    }

    /**
     * #764: deletes the dismiss flags of the home cards that no longer exist. The assistant's
     * existing/new decision is frozen first: it counts any stored key as "existing", and removing
     * the only keys of an installation must not turn that into "new" afterwards.
     */
    static void removeObsoleteHomeCardKeys(Context context) {
        KeepADBOnboarding.isExistingInstall(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        for (String key : OBSOLETE_HOME_CARD_KEYS) {
            if (!prefs.contains(key)) continue;
            if (editor == null) editor = prefs.edit();
            editor.remove(key);
        }
        if (editor != null) editor.apply();
    }

    /**
     * #956: deletes what the removed USB-ADB area stored: the notification and handover settings
     * and every host profile (name, IP address, host names). Idempotent and silent when nothing is
     * stored, so a new installation gets no write from it. The assistant's existing/new decision
     * is frozen first, as {@link #removeObsoleteHomeCardKeys} does: it counts any stored key as
     * "existing", and removing the only keys of an installation must not turn that into "new".
     * Runs from {@link KeepADBApplication#onCreate} right after {@link #migratePrivacySplit}, which
     * has to see the keys that were stored before this deletion.
     */
    static void removeObsoleteUsbKeys(Context context) {
        removeStoredKeys(context, key -> key.startsWith(OBSOLETE_USB_PROFILE_PREFIX)
                || java.util.Arrays.asList(OBSOLETE_USB_KEYS).contains(key));
    }

    /**
     * #960: deletes the two stored retry settings of the removed selectors. Same rules as {@link
     * #removeObsoleteUsbKeys}: idempotent, silent when nothing is stored, run at the first start.
     */
    static void removeObsoleteWebhookRetryKeys(Context context) {
        removeStoredKeys(context,
                key -> java.util.Arrays.asList(OBSOLETE_WEBHOOK_RETRY_KEYS).contains(key));
    }

    /**
     * #958: deletes the stored dismiss flag of the removed home-screen advice card, whether it was
     * dismissed or not. Same rules as {@link #removeObsoleteUsbKeys}: idempotent, silent when
     * nothing is stored, run at the first start after the update.
     */
    static void removeObsoleteAdviceBannerKey(Context context) {
        removeStoredKeys(context, OBSOLETE_ADVICE_BANNER_KEY::equals);
    }

    private static void removeStoredKeys(Context context,
            java.util.function.Predicate<String> obsolete) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (obsolete.test(key)) found.add(key);
        }
        if (found.isEmpty()) return;
        KeepADBOnboarding.isExistingInstall(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : found) editor.remove(key);
        editor.apply();
    }

    /**
     * #482/#483/#904: the app privacy switch: whether network identities and addresses are masked
     * on the screens and dialogs of the app. This is the stored switch only; what a screen shows
     * also depends on a "Show" the user chose on that screen, see {@link KeepADBPrivacy#isAppMasked}.
     * The default is off so a newly installed app starts with everything visible; it is an opt-in
     * the user must choose explicitly (#509). The flag only ever controls rendering; it never
     * touches {@code adb_wifi_enabled} or any other persisted original value.
     */
    static boolean isAppPrivacyEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PRIVACY_MODE_ENABLED, false);
    }

    static void setAppPrivacyEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PRIVACY_MODE_ENABLED, enabled).apply();
    }
}
