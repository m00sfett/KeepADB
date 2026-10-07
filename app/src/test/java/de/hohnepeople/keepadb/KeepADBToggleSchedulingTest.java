package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Deterministic tests for KeepADB's debounce and generation-token logic (#249), using the
 * KeepADBSettingsGateway/KeepADBScheduler seams introduced in #248 instead of a real
 * ContentResolver, Handler, or Thread. {@link KeepADBFakeScheduler#runAsync} runs synchronously,
 * which is enough to prove ordering/cancellation for these scenarios; the one scenario that
 * genuinely needs concurrent execution (a manual disable landing *during* a recovery pulse's
 * pause) is covered separately in {@link KeepADBRecoveryPulseInterruptionTest} with real threads
 * and latches instead.
 *
 * <p>Scenario "process recreation with a persisted manual-off intent" is already covered by
 * {@link KeepADBUsbHandoverTest#lastDesiredOnPreferencesPersistenceAcrossSimulatedRestart()}
 * against the production gateway/scheduler, so it isn't duplicated here.
 */
public class KeepADBToggleSchedulingTest {
    /** An automatic source: still debounced by TOGGLE_COOLDOWN_MS after #310. */
    private static final String AUTO = "keep_alive_check";

    private KeepADBFakeScheduler scheduler;
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBFakeSurfaceRefresher surfaces;
    private FakeContext ctx;

    @Before
    public void setUp() {
        KeepADB.resetForTesting();
        scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000); // comfortably past TOGGLE_COOLDOWN_MS since "boot"
        gateway = new KeepADBFakeSettingsGateway(false);
        surfaces = new KeepADBFakeSurfaceRefresher();
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSurfaceRefresherForTesting(surfaces);
        // #348: the Wi-Fi-transport override is a process-wide static seam, so it has to be
        // cleared here or a neighbouring test could leak its connectivity state into this one.
        KeepADBNetwork.resetForTesting();
        ctx = new FakeContext();
    }

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
        KeepADBNetwork.resetForTesting();
    }

    @Test
    public void firstToggleAppliesImmediatelyWhenNotThrottled() {
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertEquals(Arrays.asList(true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void rapidSecondToggleIsDebouncedAndANewerIntentSupersedesTheDelayedOne() {
        // #310: the debounce is now scoped to *automatic* sources, so this scenario drives one
        // (AUTO) throughout; the manual counterpart is manualTogglesAreNeverDelayed() below.
        // First call applies immediately (clock is far past the cooldown window).
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertEquals(1, gateway.writes.size());

        // A second call at the same instant is within the cooldown window and gets scheduled
        // instead of applied immediately.
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertEquals("the throttled call must not have written yet", 1, gateway.writes.size());
        assertTrue(scheduler.hasAnyPending());

        // A third, newer call before the scheduled one fires must supersede it -- the "false"
        // intent in between must never reach the gateway.
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the superseded false intent must never reach the gateway",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void surfacesAreRefreshedOncePerAppliedWriteAndNeverForASupersededOne() {
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertEquals("an applied write must fan out to the surfaces exactly once",
                1, surfaces.refreshCount);

        // Throttled, then immediately superseded. #318: scheduling a write now fans out too, so
        // the surfaces can show the pending state during the debounce window -- that is a render
        // of "a write is in flight", not a claim that anything was applied (the gateway assertions
        // in throttledTogglesCollapseToTheNewestIntent() pin the applied side).
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertEquals("scheduling must make the pending state visible", 2, surfaces.refreshCount);
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertEquals(3, surfaces.refreshCount);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("only the surviving intent may refresh the surfaces on apply",
                4, surfaces.refreshCount);
        assertFalse("the debounce window must be over", KeepADB.isTogglePending());
    }

    @Test
    public void aGuardAbortedAutomaticEnableClearsThePendingIndicator() {
        // #318: an automatic enable that is cancelled by its own #310 guard has no successor that
        // could refresh the surfaces -- unlike a supersession, where the newer intent does it. It
        // must therefore fan out itself, or widget, tile and notification keep showing
        // "switching…" until something unrelated happens to refresh them.
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        int afterFirstWrite = surfaces.refreshCount;

        assertTrue("the second automatic call must be debounced, not written",
                KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        assertTrue("the debounce window must be visible while it lasts", KeepADB.isTogglePending());
        assertEquals("scheduling fans out once", afterFirstWrite + 1, surfaces.refreshCount);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("a guard-aborted intent must not reach the gateway",
                Arrays.asList(true), gateway.writes);
        assertFalse("nothing may stay pending after the abort", KeepADB.isTogglePending());
        assertEquals("the abort must clear the pending indicator on the surfaces",
                afterFirstWrite + 2, surfaces.refreshCount);
    }

    @Test
    public void aGuardAbortedAutomaticEnableRestoresThePreviousOffIntent() {
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADB.wasLastExplicitIntentOff(ctx));

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        assertFalse("the pending on-intent is visible until its guard is checked",
                KeepADB.wasLastExplicitIntentOff(ctx));
        assertTrue(scheduler.hasAnyPending());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals("the guarded enable must not reach the gateway",
                Arrays.asList(false), gateway.writes);
        assertTrue("an aborted enable must not erase the previous off-intent",
                KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse("the persisted intent must match the restored in-memory intent",
                KeepADBPreferences.getLastDesiredOn(ctx));
        assertTrue("the rejected automatic intent must restore the user-disabled state",
                KeepADB.isUserDisabled());
    }

    @Test
    public void aNetworkChangeAbortedAutomaticEnableRestoresThePreviousOffIntent() {
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true).isSuccess());
        KeepADB.noteNetworkChanged();
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals("a stale-network enable must not reach the gateway",
                Arrays.asList(false), gateway.writes);
        assertTrue(KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse(KeepADBPreferences.getLastDesiredOn(ctx));
        assertTrue(KeepADB.isUserDisabled());
    }

    @Test
    public void aGuardAbortOverAPendingPredecessorRestoresTheAppliedOffIntentNotThePendingOne() {
        // #776: applied state is "off". A pending automatic enable (A) is superseded by a second
        // one (B) whose guard fails. B must roll back to the applied off, not to A's pending on
        // that setEnabled() had already persisted -- otherwise nothing was ever switched on, yet
        // the user-visible intent (and every later guard reading it) says "on".
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        assertTrue(KeepADB.isTogglePending());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals("neither pending enable may reach the gateway",
                Arrays.asList(false), gateway.writes);
        assertTrue("the applied off-intent must survive the aborted chain",
                KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse("the persisted intent must be the applied one",
                KeepADBPreferences.getLastDesiredOn(ctx));
        assertTrue(KeepADB.isUserDisabled());
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void aGuardAbortOverAThreeLinkChainRestoresTheAppliedOffIntent() {
        // #784: the two-link tests cannot tell whether a chain member renews the baseline token
        // it hands on. With three links the third one only inherits the baseline if the second
        // link re-registered its own (still current) token; a token that stays at the first
        // link's value is already superseded, so the third link would fall back to the persisted
        // value -- the second link's never-applied on. Applied state is "off".
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        assertTrue(KeepADB.isTogglePending());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals("none of the three pending enables may reach the gateway",
                Arrays.asList(false), gateway.writes);
        assertTrue("the applied off-intent must survive the aborted three-link chain",
                KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse(KeepADBPreferences.getLastDesiredOn(ctx));
        assertTrue(KeepADB.isUserDisabled());
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void aPulseSupersededPredecessorBaselineIsNotInheritedByALaterGuardAbort() {
        // #784: a recovery pulse issues a newer intent token while the pending automatic enable
        // (A) is still queued, and applyNow() would later discard A as newer_intent (its pending
        // state is cleared since #780, see the pulse tests below). A's captured baseline
        // (off) must then be ignored by the next guarded enable (B): B is not a continuation of
        // A, so it falls back to the persisted intent (on, written by A) instead of A's stale
        // off baseline.
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertFalse(KeepADBPreferences.getLastDesiredOn(ctx));
        // Wireless debugging is back on behind the app's back (as after an external re-enable).
        gateway.write(ctx, true);

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue("A must be pending with the off baseline captured", KeepADB.isTogglePending());
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));

        scheduler.setDeferAsync(true);
        KeepADB.performRecoveryPulse(ctx);
        assertEquals("the queued pulse body must not have written anything yet",
                Arrays.asList(true, false, true), gateway.writes);

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertFalse("the stale off baseline of the pulse-superseded A must not be restored",
                KeepADB.wasLastExplicitIntentOff(ctx));
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));
    }

    @Test
    public void aNetworkChangeAbortOverAPendingPredecessorRestoresTheAppliedOffIntent() {
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true).isSuccess());
        KeepADB.noteNetworkChanged();
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals(Arrays.asList(false), gateway.writes);
        assertTrue(KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse(KeepADBPreferences.getLastDesiredOn(ctx));
        assertTrue(KeepADB.isUserDisabled());
    }

    @Test
    public void aRejectedWriteOverAPendingPredecessorRestoresTheAppliedOnIntent() {
        // #776, write_rejected path with an immediate successor: applied state is "on", a pending
        // automatic off (A) is superseded by a manual off (B) whose write is rejected. The
        // rollback must restore the applied on, not A's pending off.
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.isTogglePending());
        gateway.setWriteSuccess(false);

        assertFalse("a rejected write must be reported as failed",
                KeepADB.setEnabled(ctx, false, "app").isSuccess());

        assertFalse("the applied on-intent must be restored",
                KeepADB.wasLastExplicitIntentOff(ctx));
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));
        assertFalse(KeepADB.isUserDisabled());
    }

    @Test
    public void aGuardAbortNeverRollsBackOverANewerIntentOfAnotherPath() {
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());

        // A manual on tap lands while the guarded enable is still pending: the newer intent wins
        // and the superseded guarded one must neither write nor roll anything back.
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertTrue(gateway.isEnabled(ctx));
        assertFalse(KeepADB.wasLastExplicitIntentOff(ctx));
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));
    }

    @Test
    public void theBaselineOfAResolvedChainDoesNotLeakIntoALaterGuardAbort() {
        // First chain aborts over an applied off ...
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertTrue(KeepADB.wasLastExplicitIntentOff(ctx));

        // ... then the user switches on for real; a later, unchained aborted enable must restore
        // *that* applied on, not the stale off baseline of the first chain.
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(gateway.isEnabled(ctx));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertFalse(KeepADB.wasLastExplicitIntentOff(ctx));
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));
    }

    @Test
    public void recoveryPulseRestoresWhenUninterrupted() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        // KeepADBFakeScheduler's runAsync() runs synchronously and its sleep() only advances
        // the virtual clock rather than blocking, so both pulse stages complete within this
        // one call -- there is no external "advance past the pause" step with this scheduler
        // (see the class javadoc; KeepADBRecoveryPulseInterruptionTest uses a real thread and
        // latches instead specifically to observe the state in between the two stages).
        KeepADB.performRecoveryPulse(ctx);
        assertEquals("both pulse stages must apply when nothing intervenes",
                Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void recoveryPulseAbortsBeforeItsDisableWriteWhenANewerIntentLandedFirst() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        // The pulse body is queued rather than run inline, which reproduces the real gap between
        // beginPulse() on the calling thread and the pulse body starting on its own thread.
        scheduler.setDeferAsync(true);

        KeepADB.performRecoveryPulse(ctx);
        assertEquals("nothing may be written before the pulse body runs", 0, gateway.writes.size());

        // A manual "on" tap lands in that gap. It issues a newer intent token, so the pulse is
        // already stale when its *first* (disable) write would happen -- #309: before the fix
        // only the second (restore) stage checked the token, so this off-write went through and
        // switched wireless debugging back off behind the user's back.
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertEquals(Arrays.asList(true), gateway.writes);

        scheduler.runDeferredAsync();
        assertEquals("a superseded pulse must not reach the gateway at all",
                Arrays.asList(true), gateway.writes);
        assertTrue("the manual intent must survive the stale pulse", gateway.isEnabled(ctx));
    }

    @Test
    public void aRejectedWriteFailsTheToggleAndIsNotBookedAsApplied() {
        gateway.setWriteSuccess(false);

        // AUTO throughout: after #310 only an automatic source is debounced at all, so only an
        // automatic retry can still observe whether the rejected write moved the debounce anchor.
        assertFalse("a gateway that rejected the write must not report success",
                KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertEquals(Arrays.asList(true), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
        assertFalse("a rejected enable must not leave a persisted off-intent behind",
                KeepADB.wasLastExplicitIntentOff(ctx));
        // #318: a rejected write does fan out -- not to publish a new state, but so the surfaces
        // drop any pending indicator and fall back to the real, unchanged setting. What must not
        // happen is the *bookkeeping* of an applied write, which the retry below pins.
        assertEquals("a rejected write must resolve the pending indicator exactly once",
                1, surfaces.refreshCount);

        // #309: recordApplied() must have been skipped too. Had the failed write been booked as
        // applied, it would have moved the debounce anchor to "now" and this immediate retry
        // would be delayed instead of writing straight away.
        gateway.setWriteSuccess(true);
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertEquals("the retry after a rejected write must not be debounced",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void aRejectedWriteRestoresThePreviousPersistedIntent() {
        KeepADBPreferences.setLastDesiredOn(ctx, false);
        KeepADB.resetForTesting();
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setLastDesiredOn(ctx, false);
        gateway.setWriteSuccess(false);

        assertFalse(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertFalse(KeepADBPreferences.getLastDesiredOn(ctx));
        assertTrue(KeepADB.wasLastExplicitIntentOff(ctx));
    }

    @Test
    public void aRejectedDisableRestoresThePreviousOnIntent() {
        KeepADBPreferences.setLastDesiredOn(ctx, true);
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        gateway.setWriteSuccess(false);

        assertFalse(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));
        assertFalse(KeepADB.wasLastExplicitIntentOff(ctx));
    }

    @Test
    public void anAcceptedWriteWithAStaleReadKeepsTheMismatchDiagnostic() throws IOException {
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context context) {
                return false;
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                return true;
            }
        });

        assertTrue("an accepted write keeps the existing applyNow return value",
                KeepADB.setEnabled(ctx, true, "app").isSuccess());
        String events = ctx.getSharedPreferences("keepadb_diagnostics", 0)
                .getString("events", "");
        assertTrue("a stale reread must remain a state mismatch",
                events.contains("event=toggle_attempt source=app outcome=state_mismatch"));
        assertTrue("the diagnostic detail must preserve the accepted-write field",
                events.contains("actual=false writeAccepted=true"));

        // The runtime setup necessarily reaches this diagnostic only with writeAccepted=true:
        // #309 returns before it when the gateway rejects a write. Keep the behavior probe above,
        // and pin the source-level mutation separately so the old redundant conjunction cannot
        // silently return in a future cleanup.
        String applyNow = methodBody(read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java"),
                "private static synchronized ToggleResult applyNow(Context appContext, boolean on, String source,");
        assertFalse("the diagnostic must not restore the dead writeAccepted conjunction",
                applyNow.contains("writeAccepted && actual == on"));
        assertTrue("the diagnostic must classify from the post-write state reread",
                applyNow.contains("actual == on ? \"success\" : \"state_mismatch\""));
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Could not find " + signature, start >= 0);
        return source.substring(start, findMatchingBraceEnd(source, source.indexOf('{', start)));
    }

    private static int findMatchingBraceEnd(String source, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        throw new IllegalStateException("Unbalanced braces from index " + openBraceIndex);
    }

    // ---------------------------------------------------------------------------------------
    // #310/#335: automatic enables are revalidated at write time; manual ones avoid the long
    // debounce, with only the short OFF -> ON teardown gap from #335.
    // ---------------------------------------------------------------------------------------

    /**
     * Acceptance criterion 2: "Manuelles Einschalten durch den Nutzer bleibt als expliziter
     * Override ohne den langen Debounce erhalten." A rapid manual OFF -> ON pair gets only the
     * short technical teardown gap; other manual toggles still write straight away.
     */
    @Test
    public void manualTogglesAreNeverDelayed() {
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertEquals(1, gateway.writes.size());

        // Each of these lands well inside TOGGLE_COOLDOWN_MS of the previous write.
        assertTrue(KeepADB.setEnabled(ctx, false, "tile").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, "widget").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, false, "notification").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, KeepADB.SOURCE_USB_HANDOVER_MANUAL).isSuccess());

        scheduler.advanceBy(KeepADB.MANUAL_REENABLE_GAP_MS + 1);
        assertEquals("the short gap must not restore the long user debounce: " + gateway.writes,
                Arrays.asList(true, false, false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));

        // Control: at this very same clock reading an automatic source is still debounced, so
        // the assertions above are about the manual/automatic split, not about a wide-open
        // cooldown window.
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertEquals(4, gateway.writes.size());
        assertTrue(scheduler.hasAnyPending());
    }

    /**
     * Acceptance criterion 1, network half: the Wi-Fi changed while an automatic enable sat in
     * its cooldown window, so the enable must be dropped. The counter-probe below runs the
     * identical sequence without the network change and proves the write does land otherwise.
     */
    @Test
    public void pendingAutomaticEnableIsDroppedWhenTheNetworkChangedDuringTheCooldown() {
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess()); // anchors the cooldown window
        assertEquals(1, gateway.writes.size());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true).isSuccess());
        assertTrue("the automatic enable must have been scheduled, not applied",
                scheduler.hasAnyPending());

        KeepADB.noteNetworkChanged(); // e.g. KeepADBService's onLost()/onAvailable()

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("an enable planned for a network we left must never be written",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    @Test
    public void pendingAutomaticEnableSurvivesWhenTheNetworkDidNotChange() {
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true).isSuccess());
        assertTrue(scheduler.hasAnyPending());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("without a network change the pending enable must still be applied",
                Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    /**
     * Acceptance criterion 1, Keep-Alive half -- driven through the real production guard
     * {@link KeepADBService#isAutoEnableStillPermitted}, not a stand-in, so this pins the actual
     * wiring KeepADBService uses. MODE_ALL_WIFI keeps the trusted-network half of that guard out
     * of the way (a plain JVM test has no WifiManager, so allowlist mode always fails closed);
     * the trusted-network half is covered separately below.
     */
    @Test
    public void pendingAutomaticEnableIsDroppedWhenKeepAliveIsDisabledDuringTheCooldown() {
        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(ctx, true);

        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, KeepADBService::isAutoEnableStillPermitted).isSuccess());
        assertTrue(scheduler.hasAnyPending());

        KeepADBPreferences.setKeepAliveEnabled(ctx, false);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("a Keep-Alive enable must not fire after Keep-Alive was switched off",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    @Test
    public void pendingAutomaticEnableSurvivesWhenKeepAliveStaysEnabled() {
        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(ctx, true);
        // #348: isAutoEnableStillPermitted() now also requires an actually connected Wi-Fi
        // transport -- this positive counter-probe must simulate one being present.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, KeepADBService::isAutoEnableStillPermitted).isSuccess());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the counter-probe: with Keep-Alive left on the enable must be applied",
                Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    /**
     * Acceptance criterion 1, trust half: the same production guard must also drop the pending
     * enable when the network stops being trusted mid-cooldown -- here by switching the policy
     * back to allowlist mode, which this JVM test's contextless WifiManager can never satisfy.
     */
    @Test
    public void pendingAutomaticEnableIsDroppedWhenTheNetworkIsNoLongerTrusted() {
        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(ctx, true);
        // Keep the transport guard permissive so only trust withdrawal cancels the enable.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, KeepADBService::isAutoEnableStillPermitted).isSuccess());
        assertTrue(scheduler.hasAnyPending());

        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("an enable must not fire onto a network that is no longer trusted",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    /**
     * The guard must not leak into the disable direction: #310 is explicitly scoped to the
     * automatic ON path, and a guard that says "no" must never stop an automatic OFF.
     */
    @Test
    public void anAutomaticDisableIsNeverBlockedByTheGuard() {
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO, appContext -> false).isSuccess());
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals(Arrays.asList(true, false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    /**
     * A manual toggle landing during the cooldown must still supersede a pending automatic one
     * (non-goal: #310 removes the manual *delay*, never the token-superseding race protection).
     */
    @Test
    public void aManualToggleStillSupersedesAPendingAutomaticEnable() {
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true).isSuccess());
        assertTrue(scheduler.hasAnyPending());

        assertTrue("the manual off must be applied at once", KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertEquals(Arrays.asList(false, false), gateway.writes);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the superseded automatic enable must never reach the gateway",
                Arrays.asList(false, false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    // ---------------------------------------------------------------------------------------
    // #780: state reset of a superseded pending intent, and protection of a pending manual one.
    // ---------------------------------------------------------------------------------------

    @Test
    public void aPulseSupersededPendingIntentClearsThePendingStateWhenItsRunnableFires() {
        // #780 point 1: wireless debugging is on, an automatic intent (A) waits in its cooldown
        // and a recovery pulse issues a newer token without touching A's runnable. When A fires
        // it is discarded as newer_intent -- and must take the pending state with it, otherwise
        // isTogglePending() stays true for good and the surfaces keep showing "switching...".
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.isTogglePending());

        scheduler.setDeferAsync(true);
        KeepADB.performRecoveryPulse(ctx);
        int refreshesBeforeFire = surfaces.refreshCount;

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals("the superseded intent must not write", Arrays.asList(true), gateway.writes);
        assertFalse("the discarded intent must not leave the pending state behind",
                KeepADB.isTogglePending());
        assertEquals("the surfaces must be told that the pending indicator is gone",
                refreshesBeforeFire + 1, surfaces.refreshCount);
    }

    @Test
    public void aStaleRunnableNeverClearsTheRunnableOfANewerPendingIntent() {
        // #780 point 1, the other side: the discarded runnable may only clear what is its own.
        // A real Handler cannot recall a runnable that is already waiting on the class lock, so
        // the older one can still fire after setEnabled() registered its successor. The recording
        // scheduler models that by ignoring removeCallbacks().
        final java.util.List<Runnable> queued = new java.util.ArrayList<>();
        KeepADBFakeScheduler recording = new KeepADBFakeScheduler() {
            @Override
            public void postDelayed(Runnable runnable, long delayMs) {
                queued.add(runnable);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                // deliberately ignored, see above
            }
        };
        recording.setClockMs(100_000);
        KeepADB.setSchedulerForTesting(recording);

        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertEquals("both automatic intents must be registered", 2, queued.size());

        queued.get(0).run();
        assertTrue("the stale runnable must leave the newer pending intent alone",
                KeepADB.isTogglePending());
        assertEquals(Arrays.asList(true), gateway.writes);

        queued.get(1).run();
        assertFalse(KeepADB.isTogglePending());
        assertEquals("the newer intent is still the one that gets applied",
                Arrays.asList(true, true), gateway.writes);
    }

    @Test
    public void anAutomaticIntentDoesNotDisplaceAPendingManualReEnable() {
        // #780 point 2: the user switches off and taps on again inside the 100 ms teardown gap.
        // The tap waits for the gap. An automatic intent (here: Keep-Alive recheck whose guard
        // would abort) arriving meanwhile used to take over the token, so the tap was cancelled
        // as newer_intent and the automatic enable then aborted itself: nothing was written.
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue("the manual re-enable must be waiting for the gap", KeepADB.isTogglePending());
        assertEquals(Arrays.asList(false), gateway.writes);

        assertFalse("the automatic request must be refused, not scheduled",
                KeepADB.setEnabled(ctx, true, AUTO, appContext -> false).isSuccess());
        assertFalse("an automatic disable is refused as well",
                KeepADB.setEnabled(ctx, false, AUTO).isSuccess());

        scheduler.advanceBy(KeepADB.MANUAL_REENABLE_GAP_MS);
        assertEquals("the manual intent must be applied", Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
        assertFalse(KeepADB.wasLastExplicitIntentOff(ctx));
        assertTrue(KeepADBPreferences.getLastDesiredOn(ctx));
        assertFalse(KeepADB.isTogglePending());
        String events = ctx.getSharedPreferences("keepadb_diagnostics", 0).getString("events", "");
        assertTrue("the refusal must be diagnosable",
                events.contains("event=recovery_attempt source=keep_alive_check outcome=skipped"));
        assertTrue(events.contains("reason=manual_intent_pending"));

        // No lasting block: once the tap is applied, the next automatic request is planned again.
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue("the automatic path must be open again", KeepADB.isTogglePending());
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals(Arrays.asList(false, true, true), gateway.writes);
    }

    @Test
    public void aPendingManualReEnableStillSupersedesAPendingAutomaticIntent() {
        // #780, the other side of the invariant: protection runs one way only. An automatic
        // disable waits in its cooldown; the manual re-enable tap must replace it, not be refused.
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO).isSuccess());
        assertTrue(KeepADB.isTogglePending());
        assertFalse(KeepADB.isManualIntentPending());

        assertTrue("the manual call must be accepted over the pending automatic one",
                KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.isManualIntentPending());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the automatic disable must never reach the gateway",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
        assertFalse(KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void aNewerManualIntentStillSupersedesAPendingManualOne() {
        // #780: manual against manual is unchanged, the newer one wins.
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, "tile").isSuccess());
        assertTrue(KeepADB.isManualIntentPending());

        assertTrue(KeepADB.setEnabled(ctx, false, "widget").isSuccess());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the older manual re-enable must not be applied",
                Arrays.asList(false, false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
        assertTrue(KeepADB.wasLastExplicitIntentOff(ctx));
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void anAutomaticIntentPlannedAfterAManualOneIsNeverMistakenForAManualOne() {
        // #780: the manual marker belongs to the pending runnable it was registered with. If it
        // survived the applied tap, a later automatic intent would count as a pending manual one
        // and refuse every newer automatic request, i.e. the automatic debounce would be lost.
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.isManualIntentPending());
        scheduler.advanceBy(KeepADB.MANUAL_REENABLE_GAP_MS);
        assertFalse(KeepADB.isTogglePending());
        assertFalse(KeepADB.isManualIntentPending());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
        assertTrue(KeepADB.isTogglePending());
        assertFalse("an automatic intent is not a manual one",
                KeepADB.isManualIntentPending());
        assertTrue("a newer automatic intent still replaces the pending automatic one",
                KeepADB.setEnabled(ctx, true, AUTO).isSuccess());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals(Arrays.asList(false, true, true), gateway.writes);
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void aRecoveryPulseIsNotBlockedByAPendingManualReEnableAndReachesTheSameEndState() {
        // #780 decision: the protection does not extend to recovery pulses. A pulse only starts
        // while wireless debugging reads "on" and the last intent is "on", which with a pending
        // manual re-enable can only happen after an external switch-on inside the 100 ms gap --
        // the tap's goal ("on") is already reached, and the pulse bounces back to on. Refusing
        // the pulse would only cost the recovery; the superseded tap is discarded cleanly.
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        gateway.write(ctx, true); // switched on behind the app's back
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        assertTrue(KeepADB.isManualIntentPending());

        scheduler.setDeferAsync(true);
        KeepADB.performRecoveryPulse(ctx);
        assertFalse("a pulse supersedes the tap, so the tap no longer counts as pending",
                KeepADB.isManualIntentPending());

        scheduler.advanceBy(KeepADB.MANUAL_REENABLE_GAP_MS);
        assertFalse("the discarded tap must not leave the pending state behind",
                KeepADB.isTogglePending());
        scheduler.runDeferredAsync();

        assertEquals("the pulse bounces the setting and ends on",
                Arrays.asList(false, true, false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
        assertFalse(KeepADB.wasLastExplicitIntentOff(ctx));
    }

    @Test
    public void aManualIntentSupersededByAPulseNoLongerBlocksAutomaticIntents() {
        // #780: the protection only holds while the manual intent is still the newest one.
        assertTrue(KeepADB.setEnabled(ctx, false, "app").isSuccess());
        gateway.write(ctx, true);
        assertTrue(KeepADB.setEnabled(ctx, true, "app").isSuccess());
        scheduler.setDeferAsync(true);
        KeepADB.performRecoveryPulse(ctx);

        assertTrue("a dead manual intent must not refuse automatic ones",
                KeepADB.setEnabled(ctx, true, AUTO).isSuccess());
    }

    // -----------------------------------------------------------------------------------------
    // #795: setEnabled() reports *why* a request did not go through. Before, one boolean covered
    // a missing permission, a rejected or refused write, a guard abort, a superseded intent and
    // (#780) a skipped request, and the automatic callers read every false as "permission missing".
    // -----------------------------------------------------------------------------------------

    @Test
    public void anImmediateWriteIsReportedAsApplied() {
        assertEquals(KeepADB.ToggleResult.APPLIED, KeepADB.setEnabled(ctx, true, "app"));
    }

    @Test
    public void aDebouncedAutomaticRequestIsReportedAsScheduled() {
        assertEquals(KeepADB.ToggleResult.APPLIED, KeepADB.setEnabled(ctx, true, AUTO));
        assertEquals(KeepADB.ToggleResult.SCHEDULED, KeepADB.setEnabled(ctx, false, AUTO));
        assertTrue(KeepADB.isTogglePending());
    }

    @Test
    public void aMissingGrantIsReportedAsPermissionMissingAndNothingIsPlanned() {
        ctx.permissionGranted = false;

        assertEquals(KeepADB.ToggleResult.PERMISSION_MISSING, KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(KeepADB.ToggleResult.PERMISSION_MISSING, KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue("nothing may be written without the grant", gateway.writes.isEmpty());
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void aRejectedWriteIsReportedAsWriteRejectedNotAsAMissingPermission() {
        gateway.setWriteSuccess(false);

        KeepADB.ToggleResult result = KeepADB.setEnabled(ctx, true, "app");

        assertEquals(KeepADB.ToggleResult.WRITE_REJECTED, result);
        assertFalse("the grant is there, so this is no permission problem",
                result.isPermissionFailure());
    }

    @Test
    public void aWriteRefusedWithASecurityExceptionIsReportedAsSecurityException() {
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context context) {
                return false;
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                throw new SecurityException("WRITE_SECURE_SETTINGS revoked behind our back");
            }
        });

        KeepADB.ToggleResult result = KeepADB.setEnabled(ctx, true, AUTO);

        assertEquals(KeepADB.ToggleResult.SECURITY_EXCEPTION, result);
        assertTrue("a refused secure-settings write is about the grant", result.isPermissionFailure());
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void aGuardAbortInTheImmediatePathIsReportedAsGuardAbortedAndWritesNothing() {
        KeepADB.ToggleResult result = KeepADB.setEnabled(ctx, true, AUTO, appContext -> false);

        assertEquals(KeepADB.ToggleResult.GUARD_ABORTED, result);
        assertFalse(result.isPermissionFailure());
        assertTrue("an aborted enable must not reach the gateway", gateway.writes.isEmpty());
    }

    @Test
    public void anAutomaticRequestBehindAPendingManualIntentIsReportedAsManualIntentPending() {
        assertEquals(KeepADB.ToggleResult.APPLIED, KeepADB.setEnabled(ctx, false, "app"));
        assertEquals(KeepADB.ToggleResult.SCHEDULED, KeepADB.setEnabled(ctx, true, "app"));

        KeepADB.ToggleResult enable = KeepADB.setEnabled(ctx, true, AUTO, appContext -> true);
        KeepADB.ToggleResult disable = KeepADB.setEnabled(ctx, false, AUTO);

        assertEquals(KeepADB.ToggleResult.MANUAL_INTENT_PENDING, enable);
        assertEquals(KeepADB.ToggleResult.MANUAL_INTENT_PENDING, disable);
        assertFalse(enable.isPermissionFailure());
        assertFalse(enable.isSuccess());
    }

    @Test
    public void anIntentSupersededBeforeItIsAppliedIsReportedAsSuperseded() {
        // The one window a request can lose its token in the immediate path: after the planning
        // lock was released and before applyNow() takes it again, a recovery pulse (own thread in
        // production) starts. The first diagnostics write of the call is that window here.
        KeepADBFakeSettingsGateway on = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(on);
        scheduler.setDeferAsync(true);
        ctx.onDiagnosticsAccess = () -> KeepADB.performRecoveryPulse(ctx);

        KeepADB.ToggleResult result = KeepADB.setEnabled(ctx, true, AUTO);

        assertEquals(KeepADB.ToggleResult.SUPERSEDED, result);
        assertFalse(result.isPermissionFailure());
        assertTrue("the superseded request itself must not have written", on.writes.isEmpty());
    }

    @Test
    public void aNetworkChangeBeforeTheGuardedWriteIsReportedAsGuardAbortedAndWritesNothing() {
        // Same window as above, but the Wi-Fi network changes instead: applyNow() must abort on the
        // network generation (reason=network_changed) before it even asks the guard (#795).
        KeepADBFakeSettingsGateway on = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(on);
        ctx.onDiagnosticsAccess = KeepADB::noteNetworkChanged;

        KeepADB.ToggleResult result = KeepADB.setEnabled(ctx, true, AUTO, appContext -> true);

        assertEquals(KeepADB.ToggleResult.GUARD_ABORTED, result);
        assertFalse(result.isPermissionFailure());
        assertTrue("an enable planned for the previous network must not be written",
                on.writes.isEmpty());
        assertTrue(KeepADBDiagnostics.export(ctx).contains("reason=network_changed"));
    }

    @Test
    public void onlyThePermissionCausesCountAsPermissionFailuresAndOnlyAppliedOrScheduledAsSuccess() {
        for (KeepADB.ToggleResult result : KeepADB.ToggleResult.values()) {
            boolean permission = result == KeepADB.ToggleResult.PERMISSION_MISSING
                    || result == KeepADB.ToggleResult.SECURITY_EXCEPTION;
            boolean success = result == KeepADB.ToggleResult.APPLIED
                    || result == KeepADB.ToggleResult.SCHEDULED;
            assertEquals(result + " permission failure", permission, result.isPermissionFailure());
            assertEquals(result + " success", success, result.isSuccess());
        }
    }

    private static final class FakeContext extends ContextWrapper {
        private final SharedPreferences preferences = new MemoryPreferences();
        /** #795: false makes {@code checkSelfPermission} report the secure-settings grant missing. */
        boolean permissionGranted = true;
        /** #795: run once, on the first access to the diagnostics store after it is set. */
        Runnable onDiagnosticsAccess;

        FakeContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return permissionGranted
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            if ("keepadb_diagnostics".equals(name) && onDiagnosticsAccess != null) {
                Runnable hook = onDiagnosticsAccess;
                onDiagnosticsAccess = null;
                hook.run();
            }
            return preferences;
        }
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? Set.copyOf((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new java.util.HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                updates.put(key, values == null ? null : Set.copyOf(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(updates);
            }
        }
    }
}
