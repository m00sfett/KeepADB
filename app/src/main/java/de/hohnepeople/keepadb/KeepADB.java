package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Liest/schreibt Androids "Wireless debugging"-Schalter (Settings.Global.adb_wifi_enabled).
 *
 * <p>#248: this class is now purely the Android-facing orchestrator. Every decision about
 * <em>what</em> should happen -- user intent, generation tokens, debounce timing -- lives in the
 * framework-free {@link KeepADBToggleState}; every <em>effect</em> goes through one of three
 * explicit boundaries: {@link KeepADBSettingsGateway} for Settings.Global, {@link KeepADBScheduler}
 * for delay/thread/clock, and {@link KeepADBSurfaceRefresher} for the service/notification/widget
 * fan-out. What remains here is the glue that ties a decision to its effects: permission checks,
 * the persisted last-intent preference, diagnostics, and the locking that keeps a decision and the
 * write it authorizes atomic.
 */
final class KeepADB {
    private static final String TAG = "KeepADB";
    static final String KEY = "adb_wifi_enabled";

    // Kept on KeepADB as the public names callers and tests already use; the values (and the
    // reasoning behind them) live with the state machine that applies them.
    static final long TOGGLE_COOLDOWN_MS = KeepADBToggleState.TOGGLE_COOLDOWN_MS;
    static final long RECOVERY_PULSE_OFF_MS = KeepADBToggleState.RECOVERY_PULSE_OFF_MS;

    // #248: static mutable state is down to four slots -- one decision core plus the three
    // effect boundaries -- from the previous four loose state fields plus two collaborators.
    // Their lifecycle is deliberately process-wide and explicitly justified: KeepADB is reached
    // from a tile service, an app widget receiver, a boot receiver, a foreground service and two
    // activities, i.e. from several independently created Android components that must agree on
    // one debounce window and one generation-token sequence. There is no instance any of them
    // could share instead, so the toggle state is process-scoped by necessity, not by habit; it
    // is reset wholesale by resetForTesting(). The three boundaries are swapped only by tests.
    private static volatile KeepADBSettingsGateway gateway = new KeepADBAndroidSettingsGateway();
    private static volatile KeepADBScheduler scheduler = new KeepADBAndroidScheduler();
    private static volatile KeepADBSurfaceRefresher surfaces = new KeepADBAndroidSurfaceRefresher();
    private static final KeepADBToggleState state = new KeepADBToggleState();

    static void setGatewayForTesting(KeepADBSettingsGateway testGateway) {
        gateway = testGateway;
    }

    static void setSchedulerForTesting(KeepADBScheduler testScheduler) {
        scheduler = testScheduler;
    }

    static void setSurfaceRefresherForTesting(KeepADBSurfaceRefresher testSurfaces) {
        surfaces = testSurfaces;
    }

    private static Runnable pendingToggleRunnable;

    // #310: source strings whose caller is a direct, explicit user action. Everything else is
    // treated as automatic, so an unrecognized/new source keeps today's conservative behavior
    // (debounced, never written straight away) instead of silently gaining an instant write.
    // The USB handover has one entry per entry point on purpose: its automatic broadcast path
    // and its notification-action tap used to share one source string, which made the two
    // indistinguishable here even though only one of them is a user action.
    static final String SOURCE_APP = "app";
    static final String SOURCE_TILE = "tile";
    static final String SOURCE_WIDGET = "widget";
    static final String SOURCE_NOTIFICATION = "notification";
    static final String SOURCE_USB_HANDOVER_MANUAL = "usb_handover_manual";

    private static final Set<String> MANUAL_SOURCES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(SOURCE_APP, SOURCE_TILE, SOURCE_WIDGET, SOURCE_NOTIFICATION,
                    SOURCE_USB_HANDOVER_MANUAL)));

    /** True for the sources that represent a direct user action rather than an automatic one. */
    static boolean isManualSource(String source) {
        return MANUAL_SOURCES.contains(source);
    }

    /**
     * Caller-supplied re-check evaluated immediately before an automatic enable actually writes
     * (#310). It exists so the toggle facade can revalidate a delayed intent without knowing
     * <em>what</em> makes it valid: the Keep-Alive setting and the trusted-network allowlist stay
     * with the call sites that own that policy (#245), and this class never learns about either.
     * Implementations must not block and must not acquire another lock -- they are invoked while
     * {@code KeepADB.class} is held, together with the write they authorize.
     */
    interface EnableGuard {
        /** False aborts the pending enable exactly like a superseded intent: no write at all. */
        boolean stillApplies(Context appContext);
    }

    /**
     * Records that the connected Wi-Fi network changed, invalidating every automatic intent that
     * was planned for the previous one. Returns the new generation for diagnostics.
     */
    static long noteNetworkChanged() {
        return state.noteNetworkChanged();
    }

    private KeepADB() {}

    static boolean isEnabled(Context ctx) {
        return gateway.isEnabled(ctx);
    }

    static boolean isUserDisabled() {
        return state.isUserDisabled();
    }

    /**
     * Non-consumed counterpart to {@link #isUserDisabled()}: reflects the on/off state of the
     * last explicit {@link #setEnabled} call and is never reset as a side effect of an unrelated
     * {@link #consumeUserDisabled()} read. See the {@code lastDesiredOn} field comment in
     * {@link KeepADBToggleState} for why two independent flags exist.
     */
    static boolean wasLastExplicitIntentOff() {
        return state.wasLastExplicitIntentOff();
    }

    static boolean wasLastExplicitIntentOff(Context ctx) {
        if (ctx == null) return wasLastExplicitIntentOff();
        boolean prefOn = KeepADBPreferences.getLastDesiredOn(ctx.getApplicationContext());
        if (!prefOn) {
            state.forceLastDesiredOn(false);
            return true;
        }
        return state.wasLastExplicitIntentOff();
    }

    enum State {
        PERMISSION_MISSING,
        OFF,
        ENABLED_DISCONNECTED,
        ENABLED_CONNECTED
    }

    static boolean hasPermission(Context ctx) {
        if (ctx == null) return false;
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static State getState(Context ctx) {
        if (ctx == null) return State.OFF;
        Context appContext = ctx.getApplicationContext();
        if (!hasPermission(appContext)) {
            return State.PERMISSION_MISSING;
        }
        boolean enabled = isEnabled(appContext);
        if (!enabled) {
            if (KeepADBPreferences.isKeepAliveEnabled(appContext) && !wasLastExplicitIntentOff(appContext)) {
                return State.ENABLED_DISCONNECTED;
            }
            return State.OFF;
        }
        if (!KeepADBService.isWifiConnected(appContext)) {
            return State.ENABLED_DISCONNECTED;
        }
        if (KeepADBNotification.hasCurrentEndpoint()) {
            return State.ENABLED_CONNECTED;
        }
        return State.ENABLED_DISCONNECTED;
    }

    /**
     * Schaltet Wireless Debugging um. Bei schnellen wiederholten Aufrufen aus einer
     * <em>automatischen</em> Quelle innerhalb TOGGLE_COOLDOWN_MS wird die letzte gewünschte
     * Absicht debounced eingeplant; manuelle Quellen ({@link #isManualSource}) schreiben sofort.
     */
    static boolean setEnabled(Context ctx, boolean on) {
        return setEnabled(ctx, on, "app");
    }

    static boolean setEnabled(Context ctx, boolean on, String source) {
        return setEnabled(ctx, on, source, null);
    }

    /**
     * Toggle variant for automatic enable callers (#310). {@code guard} is re-evaluated
     * immediately before the write -- after any debounce delay -- and aborts the toggle if the
     * conditions that justified it no longer hold. It is only consulted for {@code on == true};
     * a disable is never blocked by it.
     */
    static boolean setEnabled(Context ctx, boolean on, String source, EnableGuard guard) {
        Context appContext = ctx.getApplicationContext();
        boolean observed = isEnabled(appContext);
        String eventName = diagnosticEventName(source);
        if (!hasPermission(appContext)) {
            KeepADBDiagnostics.event(appContext, eventName, source, "failed",
                    "desired=" + on + " observed=" + observed + " reason=permission_missing");
            return false;
        }

        final long token;
        final long delayMs;
        final long networkGeneration;
        synchronized (KeepADB.class) {
            KeepADBToggleState.ToggleDecision decision = state.requestToggle(
                    on, scheduler.elapsedRealtimeMs(), !isManualSource(source));
            token = decision.token;
            delayMs = decision.delayMs;
            networkGeneration = decision.networkGeneration;
            KeepADBPreferences.setLastDesiredOn(appContext, on);
            if (pendingToggleRunnable != null) {
                scheduler.removeCallbacks(pendingToggleRunnable);
                pendingToggleRunnable = null;
            }
            if (!decision.isImmediate()) {
                pendingToggleRunnable =
                        () -> applyNow(appContext, on, source, token, networkGeneration, guard);
                scheduler.postDelayed(pendingToggleRunnable, delayMs);
            }
        }
        KeepADBDiagnostics.event(appContext, eventName, source,
                delayMs > 0 ? "scheduled" : "accepted",
                "intentId=" + token + " desired=" + on + " observed=" + observed
                        + (delayMs > 0 ? " delayMs=" + delayMs : ""));
        if (delayMs > 0) return true;
        return applyNow(appContext, on, source, token, networkGeneration, guard);
    }

    private static synchronized boolean applyNow(Context appContext, boolean on, String source,
            long token, long networkGeneration, EnableGuard guard) {
        String eventName = diagnosticEventName(source);
        if (!state.isCurrentIntent(token)) {
            KeepADBDiagnostics.event(appContext, eventName, source, "cancelled",
                    "intentId=" + token + " reason=newer_intent");
            return false; // Superseded by a newer toggle intent
        }
        pendingToggleRunnable = null;
        // #310: an automatic enable was authorized under conditions read at planning time, up to
        // TOGGLE_COOLDOWN_MS ago. Re-check them here, inside the same lock as the write they
        // authorize, so a network change or a withdrawn Keep-Alive during the delay cancels the
        // write instead of being written blind. Treated exactly like a superseded intent.
        if (on && guard != null) {
            if (!state.isCurrentNetworkGeneration(networkGeneration)) {
                KeepADBDiagnostics.event(appContext, eventName, source, "cancelled",
                        "intentId=" + token + " reason=network_changed");
                return false;
            }
            if (!guard.stillApplies(appContext)) {
                KeepADBDiagnostics.event(appContext, eventName, source, "cancelled",
                        "intentId=" + token + " reason=preconditions_changed");
                return false;
            }
        }
        try {
            boolean writeAccepted = gateway.write(appContext, on);
            if (!writeAccepted) {
                // #309: a rejected write is a failure, not a success with a footnote. Recording
                // it as applied would move the debounce anchor and the persisted last-intent to
                // a state the system never actually reached, and returning true would tell the
                // caller (tile, widget, service) the toggle went through.
                KeepADBDiagnostics.event(appContext, eventName, source, "failed",
                        "intentId=" + token + " desired=" + on + " reason=write_rejected");
                return false;
            }
            state.recordApplied(on, scheduler.elapsedRealtimeMs());
            KeepADBPreferences.setLastDesiredOn(appContext, on);
            boolean actual = isEnabled(appContext);
            KeepADBDiagnostics.event(appContext, eventName, source,
                    writeAccepted && actual == on ? "success" : "state_mismatch",
                    "intentId=" + token + " desired=" + on + " actual=" + actual
                            + " writeAccepted=" + writeAccepted);
            surfaces.refreshAll(appContext);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS when applying toggle", e);
            KeepADBDiagnostics.event(appContext, eventName, source, "failed",
                    "intentId=" + token + " reason=security_exception");
            return false;
        }
    }

    private static String diagnosticEventName(String source) {
        return "content_observer".equals(source) || "keep_alive_check".equals(source)
                ? "recovery_attempt" : "toggle_attempt";
    }

    /**
     * Führt einen Recovery-Puls (AUS -> PAUSE -> AN) kontrolliert aus.
     * Bricht ab, wenn der Nutzer während des Pulses manuell ausgeschaltet hat.
     */
    static void performRecoveryPulse(Context ctx) {
        Context appContext = ctx.getApplicationContext();
        boolean observed = isEnabled(appContext);
        if (!hasPermission(appContext)) {
            KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                    "stage=request observed=" + observed + " reason=permission_missing");
            return;
        }

        final long pulseToken;
        synchronized (KeepADB.class) {
            if (state.isUserDisabled() || wasLastExplicitIntentOff(appContext) || !isEnabled(appContext)) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "skipped",
                        "stage=request observed=" + observed + " reason=user_disabled_or_state_off");
                return;
            }
            pulseToken = state.beginPulse();
        }
        KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "started",
                "intentId=" + pulseToken + " observed=" + observed);

        scheduler.runAsync(() -> {
            // #309: both pulse stages re-check the guards *inside* the same lock that covers the
            // write they authorize. Checking outside (or, as before, not at all for the disable
            // stage) leaves a window in which a manual toggle lands between the decision and the
            // write, so a superseded pulse would overwrite a newer explicit user intent.
            boolean disableRejected;
            boolean disableSecurityException = false;
            boolean disableActual = false;
            synchronized (KeepADB.class) {
                if (pulseSuperseded(appContext, pulseToken)) {
                    logPulseCancelled(appContext, pulseToken, "disable");
                    return;
                }
                try {
                    disableRejected = !gateway.write(appContext, false);
                    state.recordAppliedTime(scheduler.elapsedRealtimeMs());
                    disableActual = isEnabled(appContext);
                } catch (SecurityException e) {
                    disableRejected = true;
                    disableSecurityException = true;
                }
            }
            if (disableSecurityException) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                        "intentId=" + pulseToken + " stage=disable reason=security_exception");
                return;
            }
            KeepADBDiagnostics.event(appContext, "recovery_state", "endpoint",
                    !disableRejected && !disableActual ? "success" : "state_mismatch",
                    "intentId=" + pulseToken + " stage=disable actual=" + disableActual
                            + " writeAccepted=" + !disableRejected);

            try {
                scheduler.sleep(RECOVERY_PULSE_OFF_MS);
            } catch (InterruptedException ignored) {
            }

            boolean enableRejected;
            boolean enableSecurityException = false;
            boolean enableActual = false;
            synchronized (KeepADB.class) {
                if (pulseSuperseded(appContext, pulseToken)) {
                    logPulseCancelled(appContext, pulseToken, "enable");
                    return;
                }
                try {
                    enableRejected = !gateway.write(appContext, true);
                    state.recordAppliedTime(scheduler.elapsedRealtimeMs());
                    enableActual = isEnabled(appContext);
                } catch (SecurityException e) {
                    enableRejected = true;
                    enableSecurityException = true;
                }
            }
            if (enableSecurityException) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                        "intentId=" + pulseToken + " stage=enable reason=security_exception");
                return;
            }
            KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint",
                    !enableRejected && enableActual ? "success" : "state_mismatch",
                    "intentId=" + pulseToken + " stage=enable actual=" + enableActual
                            + " writeAccepted=" + !enableRejected);
            surfaces.refreshAll(appContext);
        });
    }

    /**
     * True when the in-flight recovery pulse identified by {@code pulseToken} must not write:
     * a newer intent superseded it, or the user disabled wireless debugging in the meantime.
     * Must be called while holding {@code KeepADB.class}, together with the write it guards.
     */
    private static boolean pulseSuperseded(Context appContext, long pulseToken) {
        return !state.isCurrentIntent(pulseToken) || state.isUserDisabled()
                || wasLastExplicitIntentOff(appContext);
    }

    private static void logPulseCancelled(Context appContext, long pulseToken, String stage) {
        Log.i(TAG, "Recovery pulse cancelled by newer user intent");
        KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "cancelled",
                "intentId=" + pulseToken + " stage=" + stage + " reason=newer_user_intent");
    }

    /** Consumes and returns whether the last disable was user-initiated (vs. an external drop). */
    static synchronized boolean consumeUserDisabled() {
        return state.consumeUserDisabled();
    }

    /** Reset state for unit tests. Also restores the production gateway/scheduler/surface
     * refresher; a test that wants fakes must call the corresponding setter afterward. */
    static synchronized void resetForTesting() {
        if (pendingToggleRunnable != null) {
            scheduler.removeCallbacks(pendingToggleRunnable);
            pendingToggleRunnable = null;
        }
        state.reset();
        gateway = new KeepADBAndroidSettingsGateway();
        scheduler = new KeepADBAndroidScheduler();
        surfaces = new KeepADBAndroidSurfaceRefresher();
    }

    static synchronized void resetForTesting(Context ctx) {
        resetForTesting();
        if (ctx != null) {
            KeepADBPreferences.setLastDesiredOn(ctx.getApplicationContext(), true);
        }
    }
}
