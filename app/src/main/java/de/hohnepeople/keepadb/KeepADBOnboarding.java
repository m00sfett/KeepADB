package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules of the setup assistant (#761, UX concept #758 section 1). Everything that decides
 * <em>when</em> the assistant shows and <em>what a step may write</em> lives here, so the screen
 * ({@link OnboardingActivity}) only draws and the rules stay testable without a view.
 *
 * <h2>One rule for "Next" without input</h2>
 * A step writes a value only when the user's choice differs from the value the step loaded. So on
 * a new installation, "Next" on every step stores exactly what "Later" on the intro stores, and on
 * an existing installation "Next" never moves a stored setting, not even a marked one (decision
 * F1 of #758): the safe variant is one tap on a card away, never a side effect of "Next".
 *
 * <h2>Which installation is it</h2>
 * An installation counts as existing when the preferences already held a setting before the
 * assistant was first considered ({@link #isExistingInstall}). The answer is decided once and
 * stored; the assistant itself writes settings, and asking again later must not flip it.
 */
final class KeepADBOnboarding {
    /**
     * The assistant's version. Raising it shows the assistant once more, to everyone who closed an
     * older one ({@link #shouldAutoStart}).
     */
    static final int CURRENT_VERSION = 1;

    /**
     * The steps this build knows, in the order of the concept. The ids are the {@code EXTRA_STEP}
     * values of the single-step mode (UX concept 1.7). A further step is added here and
     * to {@link OnboardingActivity#buildSteps}. The permissions and the trusted Wi-Fi (#767) come
     * between the protection level and the lock screen, the webhook (#768) last, as in the concept.
     */
    enum Step {
        KEEP_ALIVE("keep_alive"),
        PROTECTION("protection"),
        PERMISSIONS("permissions"),
        NETWORK("network"),
        DETAILS("details"),
        WEBHOOK("webhook");

        final String id;

        Step(String id) {
            this.id = id;
        }

        /** The step with this {@code EXTRA_STEP} value, or null if this build has none. */
        static Step fromId(String id) {
            if (id == null) return null;
            for (Step step : values()) {
                if (step.id.equals(id)) return step;
            }
            return null;
        }
    }

    /**
     * The four settings the concept marks "less secure" (1.5), and nothing else. The balanced
     * preset is deliberately absent: it is an offered preset, not a misconfiguration.
     */
    enum LessSecure {
        /** The former default "in all Wi-Fi networks". */
        PROTECTION_ALL_WIFI,
        /** The force mode is on. */
        FORCE_MODE,
        /** Details (addresses, network names) in notifications. */
        NOTIFICATION_DETAILS,
        /** The webhook reports over plain {@code http://}. */
        WEBHOOK_CLEARTEXT
    }

    private static volatile boolean autoStartEnabled = true;

    private KeepADBOnboarding() {}

    // ---- When the assistant shows -----------------------------------------------------------------

    /** Whether the home screen must hand over to the assistant: it was never closed (this version). */
    static boolean shouldAutoStart(Context context) {
        return autoStartEnabled
                && KeepADBPreferences.getOnboardingCompletedVersion(context) < CURRENT_VERSION;
    }

    /** "Later" and "Done" both end here: the assistant does not come back by itself. */
    static void markCompleted(Context context) {
        KeepADBPreferences.setOnboardingCompletedVersion(context, CURRENT_VERSION);
    }

    /**
     * Whether this installation already held settings when the assistant was first considered.
     * Decided on the first call and stored, so the answer survives the writes of the assistant and
     * of the app around it.
     */
    static boolean isExistingInstall(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL)) {
            return prefs.getBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, false);
        }
        boolean existing = false;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            // The marker of the one-time history discard (#778) is written on every installation.
            if (!KeepADBPreferences.KEY_ONBOARDING_COMPLETED_VERSION.equals(key)
                    && !KeepADBBssidHistory.KEY_LEGACY_DISCARDED.equals(key)
                    && !KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN.equals(key)) {
                existing = true;
                break;
            }
        }
        prefs.edit().putBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, existing).apply();
        return existing;
    }

    /**
     * The audited pre-assistant cohort (#827/#848): established settings, but no completed
     * assistant yet. Both the history removal and warning initialization belong to that upgrade.
     * Do not infer eligibility from remaining history/warning keys: receivers may already have
     * migrated them before this intro opens. Completion excludes already adopted installations.
     * The notice is consumed only by the full intro, never by a notification's single step.
     */
    static boolean consumeUpgradeNotice(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!isExistingInstall(context)
                || KeepADBPreferences.getOnboardingCompletedVersion(context) >= 1
                || preferences.getBoolean(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, false)) {
            return false;
        }
        return preferences.edit().putBoolean(
                KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, true).commit();
    }

    // ---- Markings ---------------------------------------------------------------------------------

    /** The settings that read "less secure" right now, in a fixed order. */
    static List<LessSecure> lessSecure(Context context) {
        List<LessSecure> found = new ArrayList<>();
        if (KeepADBTrustedNetwork.getProtectionLevel(context)
                == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI) {
            found.add(LessSecure.PROTECTION_ALL_WIFI);
        }
        if (KeepADBForceMode.isActive(context)) {
            found.add(LessSecure.FORCE_MODE);
        }
        if (KeepADBPreferences.isNotificationDetailsEnabled(context)) {
            found.add(LessSecure.NOTIFICATION_DETAILS);
        }
        if (isWebhookCleartext(context)) {
            found.add(LessSecure.WEBHOOK_CLEARTEXT);
        }
        return found;
    }

    private static boolean isWebhookCleartext(Context context) {
        if (!KeepADBPreferences.isRegisterWebhookEnabled(context)) return false;
        String url = KeepADBPreferences.getRegisterWebhookUrl(context);
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    // ---- What the steps write ---------------------------------------------------------------------

    /**
     * Step "Keep-Alive". Writes only on a change and then does what the home screen's own switch
     * does, so a service the setting needs starts (or stops) at once.
     */
    static void commitKeepAlive(Context context, boolean wanted) {
        if (KeepADBPreferences.isKeepAliveEnabled(context) == wanted) return;
        KeepADBPreferences.setKeepAliveEnabled(context, wanted);
        KeepADBService.sync(context);
        KeepADBWidget.refreshAll(context);
        KeepADBEndpointCoordinator.refresh(context);
    }

    /**
     * Step "Protection level". Only the two presets can be chosen; the two previous settings are
     * shown (preselected) but are never a target. Choosing the level that is stored writes nothing.
     * Moving away from a previous setting keeps every list and entry stored: "In all Wi-Fi
     * networks" goes back to trusted access points only, and the previous name list is switched off,
     * not emptied, so nothing the user once entered is lost (#769).
     */
    static void commitProtection(Context context, KeepADBTrustedNetwork.ProtectionLevel wanted) {
        if (wanted != KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY
                && wanted != KeepADBTrustedNetwork.ProtectionLevel.BALANCED) {
            return;
        }
        if (KeepADBTrustedNetwork.getProtectionLevel(context) == wanted) return;
        if (!KeepADBTrustedNetwork.isAllowlistMode(context)) {
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        }
        if (KeepADBTrustedNetwork.isSsidMatchingEnabled(context)) {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        }
        boolean byName = wanted == KeepADBTrustedNetwork.ProtectionLevel.BALANCED;
        if (KeepADBTrustedNetwork.isTrustByNameEnabled(context) != byName) {
            KeepADBTrustedNetwork.setTrustByNameEnabled(context, byName);
        }
        KeepADBService.sync(context);
    }

    /** Step "Lock screen": the existing {@code notification_details_enabled}, written on a change only. */
    static void commitNotificationDetails(Context context, boolean wanted) {
        if (KeepADBPreferences.isNotificationDetailsEnabled(context) == wanted) return;
        KeepADBPreferences.setNotificationDetailsEnabled(context, wanted);
        // The same two refreshes the Settings switch does (#592, #597).
        KeepADBUsbReceiver.refresh(context);
        KeepADBEndpointCoordinator.refresh(context);
    }

    // ---- Test seam --------------------------------------------------------------------------------

    /**
     * Lets the home screen's own tests start without the assistant in front. {@link
     * KeepADBNetworkResetRule}, which every Robolectric test applies, switches the hand-over off;
     * the assistant's tests switch it back on.
     */
    static void setAutoStartEnabledForTesting(boolean enabled) {
        autoStartEnabled = enabled;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
