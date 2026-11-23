package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * #1050: the one-time migrations of {@link KeepADBPreferences}, split out of it: the #904 privacy
 * split, the #997 open-network default and the deletion of keys nothing reads any more (#764,
 * #956, #958, #960). Same preferences file, keys, values and idempotence as before; the order in
 * which {@link KeepADBApplication#onCreate} runs them is unchanged.
 */
final class KeepADBPreferencesMigrations {
    private KeepADBPreferencesMigrations() {}

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
        ensurePrivacySplit(KeepADBPreferences.prefs(context));
    }

    static void ensurePrivacySplit(SharedPreferences prefs) {
        if (prefs.getBoolean(KeepADBPreferences.KEY_PRIVACY_SPLIT_INITIALIZED, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        // #997: the open-network default is stored just before this and is no sign of an
        // existing installation.
        java.util.Map<String, ?> stored = new java.util.HashMap<>(prefs.getAll());
        stored.remove(KeepADBPreferences.KEY_OPEN_NETWORK_SHUTDOWN);
        if (!stored.isEmpty()) {
            boolean detailsShown = prefs.getBoolean(KeepADBPreferences.KEY_NOTIFICATION_DETAILS_ENABLED, false);
            editor.putBoolean(KeepADBPreferences.KEY_NOTIFICATION_PRIVACY_ENABLED, !detailsShown);
            editor.putBoolean(KeepADBPreferences.KEY_WIDGET_PRIVACY_ENABLED,
                    prefs.getBoolean(KeepADBPreferences.KEY_PRIVACY_MODE_ENABLED, false));
        }
        editor.putBoolean(KeepADBPreferences.KEY_PRIVACY_SPLIT_INITIALIZED, true).apply();
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
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        if (prefs.contains(KeepADBPreferences.KEY_OPEN_NETWORK_SHUTDOWN)) return;
        prefs.edit().putBoolean(KeepADBPreferences.KEY_OPEN_NETWORK_SHUTDOWN, prefs.getAll().isEmpty()).apply();
    }

    /**
     * #764: deletes the dismiss flags of the home cards that no longer exist. The assistant's
     * existing/new decision is frozen first: it counts any stored key as "existing", and removing
     * the only keys of an installation must not turn that into "new" afterwards.
     */
    static void removeObsoleteHomeCardKeys(Context context) {
        KeepADBOnboarding.isExistingInstall(context);
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
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
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
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
}
