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
        /** Synchronization is off and the registration is being withdrawn (again, if it already was off). */
        DISABLED,
        /** Already in the requested state with nothing to withdraw; nothing was changed or sent. */
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

    /** The one disable path, see {@link #disable(Context, Runnable)}. */
    static void disable(Context context) {
        disable(context, null);
    }

    /**
     * The one disable path. The withdrawal is queued first (it reads the saved URL right away) and
     * the preference goes off at once; the DELETE itself runs asynchronously. Until it has
     * succeeded the report snapshot still names the registration. Nothing sends it again on its
     * own while synchronization is off: another explicit "off" retries it
     * ({@link #setAutomaticSync}); queued withdrawals also follow at the next register activity.
     *
     * @param onDone runs on the register executor once the withdrawal has been attempted, so a
     *         receiver can hold its broadcast open until then.
     */
    static void disable(Context context, Runnable onDone) {
        KeepADBRegisterClient.unregisterAndDisableAsync(context, onDone);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
    }

    /** {@link #setAutomaticSync(Context, boolean, Runnable)} for a caller that does not wait. */
    static Result setAutomaticSync(Context context, boolean wanted) {
        return setAutomaticSync(context, wanted, null);
    }

    /**
     * The widgets' synchronization switch. It is explicit and idempotent (a surface asks for a
     * target state, never "flip"), so a repeated tap cannot undo itself. It uses the stored URL
     * only and never invents, repairs or replaces one.
     *
     * <p>Asking for "off" while it already is off is the user's retry of a withdrawal that an
     * earlier opt-out left outstanding (the result is then {@link Result#DISABLED}): while the
     * report snapshot still names the URL that is saved now, or (#935) while a destination the app
     * may have registered to without a record is outstanding (a dispatch marker or a due queued
     * withdrawal, #923). A report record of a URL that was cleared or replaced since is not
     * contacted from here. Otherwise the result is {@link Result#UNCHANGED}. The app, the
     * notification and every widget are refreshed afterwards, whatever the result.
     *
     * @param onDone runs once the work this request queued has been attempted (on the register
     *         executor), or at once when it queued none, so a receiver can keep its broadcast
     *         alive meanwhile. For an enable that is the work queued behind it, such as the report
     *         of an endpoint the process already knows; discovery of a missing endpoint is not
     *         awaited, the next normal synchronization trigger reports then.
     */
    static Result setAutomaticSync(Context context, boolean wanted, Runnable onDone) {
        Context app = context.getApplicationContext();
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(app);
        Result result;
        if (wanted == enabled) {
            if (!wanted && KeepADBRegisterClient.hasOutstandingWithdrawal(app)) {
                disable(app, onDone);
                result = Result.DISABLED;
            } else {
                result = Result.UNCHANGED;
                if (onDone != null) onDone.run();
            }
        } else if (wanted) {
            String url = KeepADBPreferences.getRegisterWebhookUrl(app);
            if (KeepADBPreferences.isValidWebhookUrl(url)) {
                enable(app, url);
                result = Result.ENABLED;
                if (onDone != null) KeepADBRegisterClient.afterQueuedWork(onDone);
            } else {
                result = Result.NEEDS_SETUP;
                if (onDone != null) onDone.run();
            }
        } else {
            disable(app, onDone);
            result = Result.DISABLED;
        }
        KeepADBRegisterClient.notifyStateChanged(app);
        return result;
    }
}
