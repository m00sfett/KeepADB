package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.widget.Button;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * #904: the app part of the three privacy switches ({@link
 * KeepADBPreferences#isAppPrivacyEnabled}, {@link KeepADBPreferences#isNotificationPrivacyEnabled},
 * {@link KeepADBPreferences#isWidgetPrivacyEnabled}).
 *
 * <p>The app switch masks Wi-Fi names, BSSIDs, IP addresses, ports and the webhook host on every
 * screen and dialog. Two kinds of surfaces read it differently:
 * <ul>
 *   <li><b>Eye-only surfaces</b> (home screen, Settings network card, report history, the USB
 *       line in the Settings, shared diagnostics) read the persistent switch alone: they are
 *       passed a plain {@link Context} and {@link #isAppMasked} is true whenever the switch is
 *       on. The eye in the title bars is their only way to unmask.</li>
 *   <li><b>Reveal surfaces</b> (the Networks list, the assistant's network page, the trust
 *       decision, the webhook field, the USB profile dialog) offer a deliberate "Show". They are
 *       passed a context made by {@link #scoped}, and a reveal lifts the mask for <em>that scope
 *       only</em>: it never reaches another surface of the same screen, ends when the scope ends
 *       (page change, dialog dismissed), when the screen stops or is recreated, or when the user
 *       turns the switch on again, and it never changes the stored switch.</li>
 * </ul>
 *
 * <p>Notifications and widgets (and the tile) have no "Show": they read their own switch.
 */
final class KeepADBPrivacy {
    /** The Networks list screen. */
    static final String SCOPE_NETWORK_LIST = "network_list";
    /** The "Trust this network?" dialog. */
    static final String SCOPE_DECISION = "decision";
    /** The page of the setup assistant that shows the current network. */
    static final String SCOPE_ASSISTANT = "assistant";
    /** The webhook URL field (Settings and assistant). */
    static final String SCOPE_WEBHOOK = "webhook";
    /** The USB host profile dialog. */
    static final String SCOPE_USB = "usb_profiles";

    private static final Map<Activity, Set<String>> REVEALED =
            Collections.synchronizedMap(new WeakHashMap<>());

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

    /** A context that remembers the reveal scope its screen part renders under. */
    private static final class ScopedContext extends ContextWrapper {
        final String scope;

        ScopedContext(Activity screen, String scope) {
            super(screen);
            this.scope = scope;
        }
    }

    private KeepADBPrivacy() {}

    /**
     * Whether the names and addresses shown through {@code context} must be masked: the app
     * switch is on and, for a context made by {@link #scoped}, the user has not chosen "Show" for
     * that scope. A plain context (eye-only surface, service) only has the switch.
     */
    static boolean isAppMasked(Context context) {
        if (context == null || !KeepADBPreferences.isAppPrivacyEnabled(context)) return false;
        ScopedContext scoped = scopedOf(context);
        if (scoped == null) return true;
        return !isRevealed((Activity) scoped.getBaseContext(), scoped.scope);
    }

    /** {@link #isAppMasked} for a reveal scope of {@code screen}. */
    static boolean isMasked(Activity screen, String scope) {
        return isAppMasked(scoped(screen, scope));
    }

    /**
     * The context a reveal surface hands to the display helpers. Without an activity behind
     * {@code context} there is no screen to reveal on, so it is returned unchanged.
     */
    static Context scoped(Context context, String scope) {
        Activity screen = screenOf(context);
        return screen == null ? context : new ScopedContext(screen, scope);
    }

    /** Lifts the mask of {@code scope} on {@code screen}; the caller redraws that part. */
    static void reveal(Activity screen, String scope) {
        if (screen == null || !KeepADBPreferences.isAppPrivacyEnabled(screen)) return;
        synchronized (REVEALED) {
            Set<String> scopes = REVEALED.get(screen);
            if (scopes == null) {
                scopes = new HashSet<>();
                REVEALED.put(screen, scopes);
            }
            scopes.add(scope);
        }
    }

    /** Masks {@code scope} on {@code screen} again (its page or dialog ended). */
    static void conceal(Activity screen, String scope) {
        if (screen == null) return;
        synchronized (REVEALED) {
            Set<String> scopes = REVEALED.get(screen);
            if (scopes == null) return;
            scopes.remove(scope);
            if (scopes.isEmpty()) REVEALED.remove(screen);
        }
    }

    /** Masks every scope of {@code screen} again (the switch was turned on, or the page changed). */
    static void concealAll(Activity screen) {
        if (screen != null) REVEALED.remove(screen);
    }

    /** Whether the user chose "Show" for {@code scope} on {@code screen} and it still holds. */
    static boolean isRevealed(Activity screen, String scope) {
        if (screen == null) return false;
        synchronized (REVEALED) {
            Set<String> scopes = REVEALED.get(screen);
            return scopes != null && scopes.contains(scope);
        }
    }

    /** The shared "Show" button: reveals {@code scope} of the button's screen, then runs {@code redraw}. */
    static void bindShowButton(Button button, String scope, Runnable redraw) {
        button.setOnClickListener(v -> {
            reveal(screenOf(button.getContext()), scope);
            redraw.run();
        });
    }

    static Activity screenOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static ScopedContext scopedOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof ScopedContext) return (ScopedContext) current;
            if (current instanceof Activity) return null;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
