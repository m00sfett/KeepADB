package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.widget.Button;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * #904: the app part of the three privacy switches ({@link
 * KeepADBPreferences#isAppPrivacyEnabled}, {@link KeepADBPreferences#isNotificationPrivacyEnabled},
 * {@link KeepADBPreferences#isWidgetPrivacyEnabled}).
 *
 * <p>The app switch masks Wi-Fi names, BSSIDs, IP addresses, ports and the webhook host on every
 * screen and dialog. A deliberate "Show" on a screen lifts the mask <em>for that screen only</em>:
 * the reveal belongs to one {@link Activity} instance and ends when that screen is no longer
 * visible ({@link #LIFECYCLE}, registered by {@link KeepADBApplication}), when it is recreated, or
 * when the user turns the switch on again. It never changes the stored switch.
 *
 * <p>Notifications and widgets (and the tile) have no "Show": they read their own switch directly
 * and render without the data while it is on.
 */
final class KeepADBPrivacy {
    private static final Set<Activity> REVEALED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Ends every reveal of a screen that stops; registered once by the application. */
    static final Application.ActivityLifecycleCallbacks LIFECYCLE =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStopped(Activity activity) {
                    REVEALED.remove(activity);
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    REVEALED.remove(activity);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

                @Override
                public void onActivityStarted(Activity activity) {}

                @Override
                public void onActivityResumed(Activity activity) {}

                @Override
                public void onActivityPaused(Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            };

    private KeepADBPrivacy() {}

    /**
     * Whether the network identities and addresses of {@code context}'s screen must be masked:
     * the app switch is on and the user has not chosen "Show" on this screen. A context that is
     * not (a wrapper of) an activity has no screen to reveal on, so only the switch counts.
     */
    static boolean isAppMasked(Context context) {
        if (context == null || !KeepADBPreferences.isAppPrivacyEnabled(context)) return false;
        Activity screen = screenOf(context);
        return screen == null || !REVEALED.contains(screen);
    }

    /** Lifts the mask on {@code screen} until it is left; the caller redraws the screen. */
    static void reveal(Activity screen) {
        if (screen != null && KeepADBPreferences.isAppPrivacyEnabled(screen)) REVEALED.add(screen);
    }

    /** Masks {@code screen} again (the switch was turned on, or the reveal is over). */
    static void concealAgain(Activity screen) {
        if (screen != null) REVEALED.remove(screen);
    }

    /** Whether the user chose "Show" on the screen of {@code context} and it still holds. */
    static boolean isRevealed(Context context) {
        Activity screen = screenOf(context);
        return screen != null && REVEALED.contains(screen);
    }

    /** The shared "Show" button: reveals the screen of {@code context}, then runs {@code redraw}. */
    static void bindShowButton(Button button, Runnable redraw) {
        button.setOnClickListener(v -> {
            reveal(screenOf(button.getContext()));
            redraw.run();
        });
    }

    private static Activity screenOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
