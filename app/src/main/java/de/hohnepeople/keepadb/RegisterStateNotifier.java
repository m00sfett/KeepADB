package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * #1049: tells the surfaces that the register state changed, extracted from {@link
 * KeepADBRegisterClient}: the one listener of the visible screen (#1036), the persistent
 * notification (#734) and the widgets (#910). Everything is posted to the main handler, so it
 * runs outside the client's class monitor that the client may hold when it calls in; this class
 * never calls back into the client. A pure refresh: nothing is sent.
 */
final class RegisterStateNotifier {
    interface Listener {
        void onRegisterStateChanged();
    }

    private static volatile Listener listener;
    private static Handler mainHandler;

    private RegisterStateNotifier() {}

    private static synchronized Handler mainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    static synchronized void setListener(Listener newListener) {
        listener = newListener;
    }

    static synchronized void clearListener() {
        listener = null;
    }

    /**
     * #1036 review: clears only {@code oldListener}, so a screen that pauses after another one took
     * over the slot (Settings over the home screen) does not remove the newer listener.
     */
    static synchronized void clearListener(Listener oldListener) {
        if (listener == oldListener) {
            listener = null;
        }
    }

    /**
     * A report settled, or (#911) something else changed what the surfaces show (the widgets
     * switched automatic synchronization): the visible screen, the persistent notification and
     * every widget re-render.
     */
    static void notifyChanged(Context context) {
        Listener current = listener;
        if (current != null) {
            mainHandler().post(current::onRegisterStateChanged);
        }
        // #734: the listener only exists while a screen is visible; the persistent notification
        // must follow the result regardless. The notification path takes the coordinator monitor,
        // which already calls into the client, so it must not run under the client's monitor.
        if (context != null) {
            final Context appContext = context.getApplicationContext();
            mainHandler().post(() -> {
                KeepADBNotification.refreshIfActive(appContext);
                // #910: the widgets show the delivery state, so they follow every result as well.
                // State only: re-rendering reads, it never reports or starts a discovery.
                KeepADBWidget.refreshAllState(appContext);
            });
        }
    }

    /** #910: a report was just queued; the widgets show "sending" at once instead of after it. */
    static void notifyReportQueued(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        mainHandler().post(() -> KeepADBWidget.refreshAllState(appContext));
    }

    static synchronized void resetForTesting() {
        listener = null;
        mainHandler = null;
    }
}
