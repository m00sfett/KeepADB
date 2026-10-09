package de.hohnepeople.keepadb;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.PowerManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real setters, force transitions and persistent display state, in both invariant directions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWarningStateTest {
    @Rule public final KeepADBNetworkResetRule networkReset = new KeepADBNetworkResetRule();
    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBForceTestSupport.TestClock clock;
    private SharedPreferences prefs() { return context.getSharedPreferences("keepadb_prefs", 0); }
    @Before public void setUp() {
        prefs().edit().clear().commit();
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport(true));
        KeepADB.resetForTesting(context);
        KeepADBForceMode.resetForTesting();
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBForceMode.setClockForTesting(clock);
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
    }
    @After public void tearDown() {
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
        prefs().edit().clear().commit();
    }
    private KeepADBWarningState.Snapshot snapshot() { return KeepADBWarningState.observe(context); }
    private void dismiss(KeepADBWarningState.Card card) {
        assertTrue(KeepADBWarningState.dismiss(context, card, snapshot()));
    }
    private boolean shown(KeepADBWarningState.Card card) { return snapshot().visible.contains(card); }
    private void http(boolean enabled) {
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://localhost/register/test");
        KeepADBPreferences.setRegisterWebhookEnabled(context, enabled);
    }
    @Test public void warningObservationNeverWaitsForTheEndpointMonitor() throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread coordinator = new Thread(() -> {
            synchronized (KeepADBEndpointCoordinator.class) {
                held.countDown();
                try { release.await(); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        coordinator.start();
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<KeepADBWarningState.Snapshot> reading = reader.submit(this::snapshot);
            try {
                assertFalse(reading.get(2, java.util.concurrent.TimeUnit.SECONDS).active
                        .contains(KeepADBWarningState.Reason.SYSTEM_PERMISSION));
            } catch (java.util.concurrent.TimeoutException deadlocked) {
                throw new AssertionError("warning observation must not acquire the endpoint monitor", deadlocked);
            }
        } finally {
            release.countDown();
            coordinator.join(5_000);
            reader.shutdownNow();
        }
    }
    @Test public void signaturesSurviveSameStateAndOnlyShrinkOnRemoval() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        http(true);
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        assertFalse(shown(KeepADBWarningState.Card.LESS_SECURE));
        http(false);
        assertFalse("only removal must keep the remaining acknowledgment", shown(KeepADBWarningState.Card.LESS_SECURE));
        http(true);
        assertTrue("returning reason is new", shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void equalCountWithDifferentReasonsCannotInheritAcknowledgment() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);
        http(true);
        assertEquals(1, snapshot().reasons(KeepADBWarningState.Card.LESS_SECURE).size());
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void ownChangesWithHomeClosedStartNewEpisodes() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void staleCloseAcknowledgesOnlyTheRenderedSnapshot() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBWarningState.Snapshot shown = snapshot();
        http(true);
        assertTrue(KeepADBWarningState.dismiss(context, KeepADBWarningState.Card.LESS_SECURE, shown));
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
        http(false);
        assertFalse(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void staleCloseCannotAcknowledgeAReturnedEpisodeButKeepsUnchangedReasons() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        http(true);
        KeepADBWarningState.Snapshot old = snapshot();
        http(false);
        http(true);
        assertTrue(KeepADBWarningState.dismiss(context, KeepADBWarningState.Card.LESS_SECURE, old));
        assertTrue("returned HTTP reason was never shown in this episode", shown(KeepADBWarningState.Card.LESS_SECURE));
        assertFalse("old mute feedback cannot mute a returned episode", KeepADBWarningState.muteClosed(context,
                KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT, KeepADBWarningState.Card.LESS_SECURE, old));
        http(false);
        assertFalse("unchanged details reason was acknowledged", shown(KeepADBWarningState.Card.LESS_SECURE));
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        assertTrue(KeepADBWarningState.dismiss(context, KeepADBWarningState.Card.LESS_SECURE, old));
        assertTrue("full inactivity creates a new details episode", shown(KeepADBWarningState.Card.LESS_SECURE));
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        assertFalse(shown(KeepADBWarningState.Card.LESS_SECURE));
        assertTrue(KeepADBWarningState.undo(context, KeepADBWarningState.Card.LESS_SECURE, old));
        assertFalse("stale undo cannot erase the new episode's dismissal", shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void mutedNewReasonDoesNotWakeAcknowledgedOtherReasons() {
        http(true);
        assertTrue(KeepADBWarningState.mute(context, KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT, true));
        http(false);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        http(true);
        assertFalse(shown(KeepADBWarningState.Card.LESS_SECURE));
        assertEquals(1, snapshot().security().size());
        assertTrue(KeepADBWarningState.mute(context, KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT, false));
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void resetOnlyInvalidatesPreviouslyMutedReasonsAndLeavesAdviceAlone() {
        KeepADBPreferences.setAdviceBannerVisible(context, false);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        assertTrue(KeepADBWarningState.mute(context, KeepADBWarningState.Reason.BATTERY_EXEMPTION_MISSING, true));
        KeepADBWarningState.resetMutes(context);
        assertFalse(shown(KeepADBWarningState.Card.LESS_SECURE));
        assertFalse(KeepADBPreferences.isAdviceBannerVisible(context));
    }
    @Test public void inactiveMuteSurvivesReturnAndResetRevealsActiveReason() {
        http(true);
        KeepADBWarningState.mute(context, KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT, true);
        http(false);
        assertTrue(snapshot().muted.contains(KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT));
        http(true);
        assertTrue(snapshot().security().isEmpty());
        KeepADBWarningState.resetMutes(context);
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void closedFeedbackCannotMuteAnUnseenOrResolvedReason() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBWarningState.Snapshot shown = snapshot();
        http(true);
        assertFalse(KeepADBWarningState.muteClosed(context, KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT,
                KeepADBWarningState.Card.LESS_SECURE, shown));
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);
        assertFalse(KeepADBWarningState.muteClosed(context, KeepADBWarningState.Reason.NOTIFICATION_DETAILS,
                KeepADBWarningState.Card.LESS_SECURE, shown));
        assertTrue(snapshot().muted.isEmpty());
    }
    @Test public void mixedMutesDoNotHideAnUnmutedReason() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        http(true);
        KeepADBWarningState.mute(context, KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT, true);
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
        assertEquals(java.util.EnumSet.of(KeepADBWarningState.Reason.NOTIFICATION_DETAILS),
                snapshot().reasons(KeepADBWarningState.Card.LESS_SECURE));
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        assertEquals(1, snapshot().security().size());
        KeepADBWarningState.undo(context, KeepADBWarningState.Card.LESS_SECURE, snapshot());
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void operationalEqualCountChangesAndPureRemovalFollowTheSameRule() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), false);
        dismiss(KeepADBWarningState.Card.LIMITED);
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(shown(KeepADBWarningState.Card.LIMITED));
        dismiss(KeepADBWarningState.Card.LIMITED);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        assertFalse(shown(KeepADBWarningState.Card.LIMITED));
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        snapshot();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), false);
        assertTrue(shown(KeepADBWarningState.Card.LIMITED));
    }
    @Test public void legacyActiveForceWithoutWarningKeysIsShownOnceAndInvalidSchemaCannotHideIt() {
        prefs().edit().putString(KeepADBForceMode.KEY_STATE, KeepADBForceMode.encode(
                new KeepADBForceMode.State(KeepADBForceMode.Span.HOURS_24,
                        KeepADBForceTestSupport.DAY, clock.wall, clock.elapsed, clock.boot))).commit();
        assertTrue(shown(KeepADBWarningState.Card.FORCE));
        String token = snapshot().forceEpisode;
        dismiss(KeepADBWarningState.Card.FORCE);
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
        assertEquals(token, snapshot().forceEpisode);
        prefs().edit().putString(KeepADBWarningState.KEY, "{\"schema\":2}").commit();
        assertTrue(shown(KeepADBWarningState.Card.FORCE));
        assertEquals("{\"schema\":2}", prefs().getString(KeepADBWarningState.KEY, ""));
    }

    @Test public void criticalMutesAreRejectedOnBothReadAndWrite() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        assertFalse(KeepADBWarningState.mute(context, KeepADBWarningState.Reason.SYSTEM_PERMISSION, true));
        assertFalse(KeepADBWarningState.mute(context, KeepADBWarningState.Reason.FORCE_MODE, true));
        prefs().edit().putString(KeepADBWarningState.KEY,
                "{\"schema\":1,\"muted\":\"system_permission|force_mode\"}").commit();
        assertTrue(shown(KeepADBWarningState.Card.SYSTEM));
        assertTrue(snapshot().muted.isEmpty());
    }
    @Test public void invalidSchemasAndTypesFailVisibleWithoutDestroyingStorage() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        for (String raw : new String[]{"{\"schema\":99,\"muted\":\"future_reason\"}", "broken",
                "{\"schema\":1,\"SYSTEM_dismissed\":\"future_reason\"}"}) {
            prefs().edit().putString(KeepADBWarningState.KEY, raw).commit();
            assertTrue(shown(KeepADBWarningState.Card.SYSTEM));
            assertFalse(KeepADBWarningState.dismiss(context, KeepADBWarningState.Card.SYSTEM, snapshot()));
            assertEquals(raw, prefs().getString(KeepADBWarningState.KEY, ""));
        }
        prefs().edit().putInt(KeepADBWarningState.KEY, 1).commit();
        assertTrue(shown(KeepADBWarningState.Card.SYSTEM));
        assertEquals(1, prefs().getInt(KeepADBWarningState.KEY, 0));
    }
    @Test public void failedPersistenceCannotClaimCloseSucceeded() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        SharedPreferences delegate = prefs();
        SharedPreferences failing = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) {
                        SharedPreferences.Editor editor = delegate.edit();
                        return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (ep, em, ea) -> {
                                    if (em.getName().equals("commit")) return false;
                                    Object result = em.invoke(editor, ea);
                                    return result == editor ? ep : result;
                                });
                    }
                    return method.invoke(delegate, args);
                });
        Context failedContext = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return failing; }
        };
        assertFalse(KeepADBWarningState.dismiss(failedContext, KeepADBWarningState.Card.LESS_SECURE, snapshot()));
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
    @Test public void forceCloseDoesNotChangeStateAndNewActivationCannotReuseIt() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        String before = prefs().getString(KeepADBForceMode.KEY_STATE, "");
        String token = snapshot().forceEpisode;
        dismiss(KeepADBWarningState.Card.FORCE);
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
        assertEquals(before, prefs().getString(KeepADBForceMode.KEY_STATE, ""));
        assertTrue(KeepADBForceMode.isActive(context));
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        assertNotEquals(token, snapshot().forceEpisode);
        assertTrue(shown(KeepADBWarningState.Card.FORCE));
    }
    @Test public void forceRebootAndClockRebasePreserveEpisodeAndDismissal() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        dismiss(KeepADBWarningState.Card.FORCE);
        String episode = snapshot().forceEpisode;
        clock.reboot(60_000, 1_000);
        KeepADBForceMode.finishIfExpired(context);
        assertEquals(episode, snapshot().forceEpisode);
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
        clock.advance(60_000);
        clock.setWallClock(clock.wall - 30_000);
        KeepADBForceMode.finishIfExpired(context);
        assertEquals(episode, snapshot().forceEpisode);
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
        clock.advance(2 * KeepADBForceTestSupport.DAY);
        KeepADBForceMode.finishIfExpired(context);
        assertFalse(snapshot().active.contains(KeepADBWarningState.Reason.FORCE_MODE));
    }
    @Test public void unknownLegacyForceWriteInvalidatesOnceAndNormalUpgradePreserves() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        dismiss(KeepADBWarningState.Card.FORCE);
        String episode = snapshot().forceEpisode;
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
        prefs().edit().putString(KeepADBForceMode.KEY_STATE, KeepADBForceMode.encode(
                new KeepADBForceMode.State(KeepADBForceMode.Span.HOURS_24,
                        KeepADBForceTestSupport.DAY, clock.wall, clock.elapsed, clock.boot))).commit();
        assertTrue(shown(KeepADBWarningState.Card.FORCE));
        assertNotEquals(episode, snapshot().forceEpisode);
        dismiss(KeepADBWarningState.Card.FORCE);
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
        assertFalse(shown(KeepADBWarningState.Card.FORCE));
    }
    @Test public void suppressionIsNotRecoveryOfTheUnderlyingCause() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), false);
        dismiss(KeepADBWarningState.Card.LIMITED);
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        assertFalse(shown(KeepADBWarningState.Card.LIMITED));
        assertTrue(snapshot().active.contains(KeepADBWarningState.Reason.BATTERY_EXEMPTION_MISSING));
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        assertFalse("suppression must not create a new episode", shown(KeepADBWarningState.Card.LIMITED));
    }
    @Test public void rawEvaluationRemainsPureAndAppResetDropsOnlyDisplayMemory() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        java.util.Map<String, ?> before = prefs().getAll();
        KeepADBHomeWarnings.evaluate(context);
        KeepADBHomeWarnings.reasons(context);
        assertEquals(before, prefs().getAll());
        dismiss(KeepADBWarningState.Card.LESS_SECURE);
        prefs().edit().remove(KeepADBWarningState.KEY).commit();
        assertTrue(shown(KeepADBWarningState.Card.LESS_SECURE));
    }
}
