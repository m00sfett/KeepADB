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
    /** #864: opt-in policy that turns Wireless Debugging off on an untrusted Wi-Fi; default off. */
    private static final String KEY_UNTRUSTED_SHUTDOWN = "untrusted_shutdown_enabled";
    /** #864: the Wi-Fi identity a manual enable was made on ("" when unreadable), or absent. */
    private static final String KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID = "untrusted_shutdown_manual_bssid";
    /** #864: elapsed-realtime millis when that manual exemption was recorded. */
    private static final String KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT = "untrusted_shutdown_manual_at";
    /** #997: on for new installations, off for existing ones; see migrateOpenNetworkShutdown. */
    static final String KEY_OPEN_NETWORK_SHUTDOWN = "open_network_shutdown_enabled";
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
    // #904: three independent, opt-in privacy switches (all off on a new installation). Each one
    // masks its own area only and is purely a rendering preference, never the toggle facade's own
    // WRITE_SECURE_SETTINGS state and never the real ADB transport.
    //  - App (#482/#483): every screen and dialog. This is the former single "privacy mode", so
    //    the key and its stored value are unchanged.
    //  - Notifications: every KeepADB notification. Replaces the former "notification details"
    //    opt-in (#592) with the opposite polarity; see migratePrivacySplit.
    //  - Widgets: every home-screen widget and the Quick Settings tile.
    static final String KEY_PRIVACY_MODE_ENABLED = "privacy_mode_enabled";
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


    /**
     * Persisted record of the last explicit on/off user intent. Survives process death and LMK
     * so recovery doesn't fail open after process resurrection. Defaults to true.
     */
    static boolean getLastDesiredOn(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_LAST_DESIRED_ON, true);
    }

    /** #1000: the preselected timer duration; {@link KeepADBTimer.Duration#DEFAULT} if unset. */
    static KeepADBTimer.Duration getTimerDefaultDuration(Context context) {
        if (context == null) return KeepADBTimer.Duration.DEFAULT;
        return KeepADBTimer.Duration.fromToken(
                prefs(context).getString(KEY_TIMER_DEFAULT_DURATION, null));
    }

    static void setTimerDefaultDuration(Context context, KeepADBTimer.Duration duration) {
        if (context == null || duration == null) return;
        prefs(context).edit()
                .putString(KEY_TIMER_DEFAULT_DURATION, duration.token).apply();
    }

    static void setLastDesiredOn(Context context, boolean on) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_LAST_DESIRED_ON, on).apply();
    }

    /**
     * #904: whether KeepADB notifications must stay free of connection details (port, IP address,
     * Wi-Fi name, BSSID). Default OFF, so a new installation shows the details;
     * when they are shown they can also appear on the lock screen if the device shows sensitive
     * notification content there. An existing installation keeps what it had: see {@link
     * KeepADBPreferencesMigrations#migratePrivacySplit}.
     */
    static boolean isNotificationPrivacyEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        KeepADBPreferencesMigrations.ensurePrivacySplit(prefs);
        return prefs.getBoolean(KEY_NOTIFICATION_PRIVACY_ENABLED, false);
    }

    static void setNotificationPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        KeepADBPreferencesMigrations.ensurePrivacySplit(prefs);
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
        return prefs(context)
                .getBoolean(KEY_LOCKSCREEN_PRIVACY_ENABLED, false);
    }

    static void setLockScreenPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit()
                .putBoolean(KEY_LOCKSCREEN_PRIVACY_ENABLED, enabled).apply();
    }

    /**
     * #904: whether every widget and the Quick Settings tile must show the state only, without
     * port or address. Default OFF. It takes precedence over any per-widget "hide details" option.
     */
    static boolean isWidgetPrivacyEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        KeepADBPreferencesMigrations.ensurePrivacySplit(prefs);
        return prefs.getBoolean(KEY_WIDGET_PRIVACY_ENABLED, false);
    }

    static void setWidgetPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        KeepADBPreferencesMigrations.ensurePrivacySplit(prefs);
        prefs.edit().putBoolean(KEY_WIDGET_PRIVACY_ENABLED, enabled).apply();
    }

    static boolean isNotificationHidden(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_HIDE_NOTIFICATION, false);
    }

    static void setNotificationHidden(Context context, boolean hidden) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_HIDE_NOTIFICATION, hidden).apply();
    }

    /** #224: keeps the display on only while MainActivity is in the foreground. Default OFF. */
    static boolean isKeepDisplayOnEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_KEEP_DISPLAY_ON, false);
    }

    static void setKeepDisplayOnEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_KEEP_DISPLAY_ON, enabled).apply();
    }

    static boolean isKeepAliveEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
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
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
        KeepADBWarningState.observe(context);
        refreshWidgetState(context);
    }

    static boolean isRegisterWebhookEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_WEBHOOK_ENABLED, false);
    }

    static void setRegisterWebhookEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_WEBHOOK_ENABLED, enabled).apply();
        KeepADBWarningState.observe(context);
        refreshWidgetState(context);
    }

    /** The app's preferences file; also used by the stores and migrations split out of here. */
    static SharedPreferences prefs(Context context) {
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

    /** Adds {@code added} rejections; the count saturates at {@link Integer#MAX_VALUE}. */
    static synchronized void recordAutomationRejections(Context context, int added,
                                                        long lastAtMillis) {
        if (context == null || added <= 0) return;
        SharedPreferences prefs = prefs(context);
        long count = (long) prefs.getInt(KEY_AUTOMATION_REJECTED_COUNT, 0) + added;
        prefs.edit()
                .putInt(KEY_AUTOMATION_REJECTED_COUNT, (int) Math.min(count, Integer.MAX_VALUE))
                .putLong(KEY_AUTOMATION_LAST_REJECTED_AT, lastAtMillis)
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
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_WEBHOOK_PERIODIC_ENABLED, false);
    }

    static void setPeriodicWebhookReportEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_WEBHOOK_PERIODIC_ENABLED, enabled).apply();
    }

    /** #976: chosen schedule preset for periodic webhook refreshes; defaults to 4 h (3 h 59 m). */
    static KeepADBPeriodicReport.Interval getPeriodicWebhookInterval(Context context) {
        if (context == null) return KeepADBPeriodicReport.Interval.DEFAULT;
        SharedPreferences prefs = prefs(context);
        return KeepADBPeriodicReport.Interval.fromId(
                prefs.getString(KEY_WEBHOOK_PERIODIC_INTERVAL,
                        KeepADBPeriodicReport.Interval.DEFAULT.id));
    }

    static void setPeriodicWebhookInterval(Context context,
            KeepADBPeriodicReport.Interval interval) {
        if (context == null) return;
        KeepADBPeriodicReport.Interval chosen = interval != null
                ? interval : KeepADBPeriodicReport.Interval.DEFAULT;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putString(KEY_WEBHOOK_PERIODIC_INTERVAL, chosen.id).apply();
    }

    static String getRegisterWebhookUrl(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = prefs(context);
        return WebhookUrlCodec.sanitize(prefs.getString(KEY_REGISTER_URL, null));
    }

    static void setRegisterWebhookUrl(Context context, String url) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        String sanitized = WebhookUrlCodec.sanitize(url);
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

    /** #864: whether Wireless Debugging is turned off on an untrusted Wi-Fi; off by default. */
    static boolean isUntrustedShutdownEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_UNTRUSTED_SHUTDOWN, false);
    }

    static void setUntrustedShutdownEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_UNTRUSTED_SHUTDOWN, enabled).apply();
    }

    /**
     * #997: whether Wifi-ADB is turned off (and not recovered) in an open Wi-Fi or behind a
     * captive portal, trusted access points included. An absent key reads as off; the stored
     * default comes from {@link #migrateOpenNetworkShutdown}.
     */
    static boolean isOpenNetworkShutdownEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_OPEN_NETWORK_SHUTDOWN, false);
    }

    static void setOpenNetworkShutdownEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_OPEN_NETWORK_SHUTDOWN, enabled).apply();
    }

    /**
     * #864: the readable BSSID a manual enable was made on, or null when no manual exemption is
     * recorded.
     */
    static String getUntrustedShutdownManualBssid(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = prefs(context);
        return prefs.getString(KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID, null);
    }

    /** Clears the manual exemption. */
    static void clearUntrustedShutdownManualBssid(Context context) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().remove(KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID)
                .remove(KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT).apply();
    }

    /** Records the manual exemption for {@code bssid} at elapsed-realtime {@code atElapsedMs}. */
    static void setUntrustedShutdownManualBssid(Context context, String bssid, long atElapsedMs) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putString(KEY_UNTRUSTED_SHUTDOWN_MANUAL_BSSID, bssid)
                .putLong(KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT, atElapsedMs).apply();
    }

    /** Elapsed-realtime millis of the recorded manual exemption, or -1 without one. */
    static long getUntrustedShutdownManualAt(Context context) {
        if (context == null) return -1L;
        SharedPreferences prefs = prefs(context);
        return prefs.getLong(KEY_UNTRUSTED_SHUTDOWN_MANUAL_AT, -1L);
    }

    /** Marks the moment the foreground service was known alive; used to log restart gaps. */
    static long getServiceLastHeartbeat(Context context) {
        if (context == null) return 0L;
        SharedPreferences prefs = prefs(context);
        return prefs.getLong(KEY_SERVICE_LAST_HEARTBEAT, 0L);
    }

    static void setServiceLastHeartbeatNow(Context context) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putLong(KEY_SERVICE_LAST_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    static String getAppLanguage(Context context) {
        if (context == null) return "";
        SharedPreferences prefs = prefs(context);
        return prefs.getString(KEY_APP_LANGUAGE, "");
    }

    static void setAppLanguage(Context context, String languageTag) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        if (languageTag == null || languageTag.trim().isEmpty()) {
            prefs.edit().remove(KEY_APP_LANGUAGE).apply();
        } else {
            prefs.edit().putString(KEY_APP_LANGUAGE, languageTag.trim()).apply();
        }
    }

    /** #761: version of the setup assistant the user last closed; 0 when it never was. */
    static int getOnboardingCompletedVersion(Context context) {
        if (context == null) return 0;
        SharedPreferences prefs = prefs(context);
        return prefs.getInt(KEY_ONBOARDING_COMPLETED_VERSION, 0);
    }

    static void setOnboardingCompletedVersion(Context context, int version) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putInt(KEY_ONBOARDING_COMPLETED_VERSION, version).apply();
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
        if (context == null) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_PRIVACY_MODE_ENABLED, false);
    }

    static void setAppPrivacyEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(KEY_PRIVACY_MODE_ENABLED, enabled).apply();
    }
}
