package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #911: the one place that switches automatic webhook synchronization on and off. The settings
 * form ({@link KeepADBWebhookForm}) and the widgets both go through it, so a widget can never
 * enable synchronization by a path the form does not take.
 *
 * <p><b>Enabling needs a valid destination.</b> {@link #enable} takes a URL the caller has already
 * validated; the widget path ({@link #setAutomaticSync}) validates the stored URL with the form's
 * own rule ({@link KeepADBPreferences#isValidWebhookUrl}) and, without a valid one, changes nothing
 * and sends nothing: the surface then leads to the setup instead.
 *
 * <p><b>Disabling keeps the opt-out semantics.</b> {@link #disable} withdraws the registration
 * (HTTP DELETE at the last reported URL, with the established cleanup retries) before it switches
 * the preference off, exactly as the form's switch did.
 */
final class KeepADBWebhookSync {

    /** What a surface's request to change the automatic synchronization did. */
    enum Result {
        /** Synchronization was switched on. */
        ENABLED,
        /** Synchronization was switched off and the registration is being withdrawn. */
        DISABLED,
        /** Already in the requested state; nothing was changed or sent. */
        UNCHANGED,
        /** No valid destination is stored: nothing was changed or sent; lead the user to the setup. */
        NEEDS_SETUP
    }

    private KeepADBWebhookSync() {}

    /**
     * The one enable path (#733): stores the already valid, normalized URL, switches the webhook on
     * and lets the coordinator report a cached endpoint. The caller owns its own feedback (toast,
     * toggle state).
     */
    static void enable(Context context, String validUrl) {
        KeepADBPreferences.setRegisterWebhookUrl(context, validUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.refresh(context);
    }

    /** The one disable path: withdraws the registration, then switches the webhook off. */
    static void disable(Context context) {
        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
    }

    /**
     * The widgets' synchronization switch. It is explicit and idempotent (a surface asks for a
     * target state, never "flip"), so a repeated tap cannot undo itself. It uses the stored URL
     * only and never invents, repairs or replaces one.
     *
     * <p>The app, the notification and every widget are refreshed afterwards, whatever the result.
     */
    static Result setAutomaticSync(Context context, boolean wanted) {
        Context app = context.getApplicationContext();
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(app);
        Result result;
        if (wanted == enabled) {
            result = Result.UNCHANGED;
        } else if (wanted) {
            String url = KeepADBPreferences.getRegisterWebhookUrl(app);
            if (KeepADBPreferences.isValidWebhookUrl(url)) {
                enable(app, url);
                result = Result.ENABLED;
            } else {
                result = Result.NEEDS_SETUP;
            }
        } else {
            disable(app);
            result = Result.DISABLED;
        }
        KeepADBRegisterClient.notifyStateChanged(app);
        return result;
    }
}
