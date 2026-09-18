package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.ConnectivityManager;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * #286 entry step: a first real Robolectric migration, sitting alongside the still-active
 * text-parsing contract tests (they stay as fast regression guards, see AGENTS.md/issue #286).
 *
 * <p>Unlike {@link KeepADBRoamNotificationRefreshContractTest} (which only asserts that certain
 * source snippets exist), these tests drive {@link KeepADBNotification} and
 * {@link KeepADBService} against genuine, Robolectric-shadowed {@link NotificationManager} and
 * {@link ConnectivityManager} instances: real {@link Notification}/{@link NotificationChannel}
 * objects get created and inspected, and a real {@code NetworkCallback} gets registered with a
 * real {@link ConnectivityManager} request.
 *
 * <p>Deliberately NOT migrated in this first step: the full #276 roam scenario (a same-Network
 * {@code onCapabilitiesChanged} re-verifying a cached endpoint) — see the class javadoc below on
 * {@link #wifiNetworkCallbackIsRegisteredAgainstARealConnectivityManager()} for why that one stays
 * out of scope for now.
 */
// Robolectric 4.13's newest supported shadow SDK is API 34 (Android 14); the app's own
// targetSdk/compileSdk stay at 35 (#286 explicitly excludes SDK/AGP changes) -- this override
// only pins which platform SDK Robolectric shadows for these tests, it does not change what the
// app ships or targets at runtime.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNotificationRobolectricTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @org.junit.Before
    public void grantNotificationPermission() {
        // POST_NOTIFICATIONS is a runtime (dangerous) permission from API 33 on; Robolectric does
        // not auto-grant it just because it's in the manifest, unlike normal permissions.
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void resetNotificationStaticState() throws Exception {
        // KeepADBNotification keeps its cached endpoint in static fields (it's a process-wide
        // singleton in production). Reset them between tests so Robolectric's shared classloader
        // state doesn't leak between test methods.
        // #453: also tears down any KeepADBEndpoint installed via setEndpointForTesting(), so a
        // fake endpoint from one test never leaks into the next.
        KeepADBNotification.resetForTesting();
        setStatic("currentHost", null);
        setStatic("currentPort", 0);
        setStatic("endpointListener", null);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void getServiceNotificationShowsSearchingPlaceholderWithNoCachedEndpoint() {
        Notification notification = KeepADBNotification.getServiceNotification(context);

        assertNotNull(notification);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(KeepADBNotification.CHANNEL_ID);
        assertNotNull("ensureChannel() must have created the real notification channel", channel);
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.getImportance());

        String title = notification.extras.getString(Notification.EXTRA_TITLE);
        assertEquals(context.getString(R.string.notification_title_searching), title);
    }

    @Test
    public void showPermissionMissingPostsARealNotificationWithoutDisableAction() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);

        KeepADBNotification.showPermissionMissing(context);

        ShadowNotificationManager shadowManager = shadowOf(manager);
        Notification notification = shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull("showPermissionMissing() must post a real notification via NotificationManager",
                notification);
        String title = notification.extras.getString(Notification.EXTRA_TITLE);
        assertEquals(context.getString(R.string.notification_permission_missing_title), title);
        assertTrue("permission-missing placeholder must not offer a disable action the user cannot "
                        + "act on without the permission it is warning about",
                notification.actions == null || notification.actions.length == 0);
    }

    @Test
    public void invalidateEndpointClearsCachedStateAndFlushesTheMainHandlerDispatch() throws Exception {
        // Seed a cached endpoint the way a completed discovery would, without driving the full
        // NSD/socket discovery flow (out of scope for this entry step, see class javadoc).
        setStatic("currentHost", "192.0.2.1");
        setStatic("currentPort", 40000);
        assertTrue(KeepADBNotification.hasCurrentEndpoint());

        KeepADBNotification.invalidateEndpoint(context);
        // invalidateEndpoint() dispatches its widget/tile refresh via a static
        // Handler(Looper.getMainLooper()); Robolectric's main looper is paused by default, so
        // without this the post() below would never actually run within the test.
        ShadowLooper.idleMainLooper();

        assertNull(KeepADBNotification.getCurrentHost());
        assertEquals(0, KeepADBNotification.getCurrentPort());
        assertTrue(!KeepADBNotification.hasCurrentEndpoint());
    }

    /**
     * Confirms {@link KeepADBService#onStartCommand} really registers its Wi-Fi
     * {@code NetworkCallback} with the platform {@link ConnectivityManager} API (shadowed by
     * Robolectric), rather than only asserting the callback's source text exists as
     * {@link KeepADBRoamNotificationRefreshContractTest} does.
     *
     * <p>The #276 roam re-verification itself ({@code onCapabilitiesChanged} ->
     * {@code KeepADBNotification.verifyEndpointHealth()}) is intentionally NOT exercised
     * end-to-end here: {@code verifyEndpointHealth()} spawns a raw background {@link Thread} that
     * opens a real {@link java.net.Socket} to check reachability, which would either hang or
     * flake in a sandboxed test run. Driving that path for real would need an injectable
     * reachability check, which is a production refactor beyond this entry step's scope.
     */
    @Test
    public void wifiNetworkCallbackIsRegisteredAgainstARealConnectivityManager() {
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);

        ServiceController<KeepADBService> controller = org.robolectric.Robolectric.buildService(KeepADBService.class);
        try {
            controller.create().startCommand(0, 0);
            ShadowLooper.idleMainLooper();

            assertTrue("registerNetworkCallback() must have registered a real NetworkCallback "
                            + "with the platform ConnectivityManager",
                    !shadowConnectivityManager.getNetworkCallbacks().isEmpty());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void notificationUpdatesWithEndpointWhenHiddenIfKeepAliveActive() throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setNotificationHidden(context, true);
        KeepADBPreferences.setPrivacyModeEnabled(context, false);

        setStatic("currentHost", "192.168.1.50");
        setStatic("currentPort", 39123);

        KeepADBNotification.refresh(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        Notification notification = shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull("Notification must update and be posted when Keep-Alive is active even if hidden",
                notification);
        assertEquals(context.getString(R.string.notification_title_active),
                notification.extras.getString(Notification.EXTRA_TITLE));
        String content = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
        assertTrue(content.contains("39123"));
        assertTrue(content.contains("192.168.1.50"));
    }

    @Test
    public void notificationMasksEndpointWhenPrivacyModeIsEnabled() throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        setStatic("currentHost", "192.168.1.50");
        setStatic("currentPort", 39123);

        KeepADBNotification.refresh(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = shadowOf(manager)
                .getNotification(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull(notification);
        String content = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
        assertTrue(content.contains("39123"));
        assertTrue(content.contains("192.*.*.*"));
        assertFalse(content.contains("192.168.1.50"));
    }

    @Test
    public void notificationIsCancelledWhenHiddenIfKeepAliveInactive() throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBPreferences.setNotificationHidden(context, false);

        setStatic("currentHost", "192.168.1.50");
        setStatic("currentPort", 39123);

        KeepADBNotification.refresh(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        assertNotNull(shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID));

        KeepADBPreferences.setNotificationHidden(context, true);
        KeepADBNotification.refresh(context);

        assertNull("Notification must be cancelled when Keep-Alive is inactive and hidden is true",
                shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID));
    }

    /**
     * #445 regression guard: Wireless Debugging turning off while Keep-Alive is still watching
     * for it to come back (i.e. not an explicit user-off -- {@code lastDesiredOn} stays true)
     * must update the still-foreground notification to the "disabled, waiting" content, not fall
     * through to {@code stop()}'s {@code manager.cancel()}. Android silently ignores cancel() on
     * a foreground service's own notification, so with the old {@code stop()}-only logic this
     * test would still observe the stale "active endpoint" notification here instead of the
     * updated placeholder -- see the class javadoc reasoning verified manually against a
     * temporary revert of the fix (reported alongside this test, not committed).
     */
    @Test
    public void notificationShowsDisabledWaitingWhenWirelessDebuggingDropsWhileKeepAliveKeepsRunning()
            throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        // Not an explicit user-off (e.g. a roam/timeout/AP loss instead) -- shouldRun() must stay
        // true, matching KeepADBService keeping its foreground notification alive.
        KeepADBPreferences.setLastDesiredOn(context, true);

        setStatic("currentHost", "192.168.1.50");
        setStatic("currentPort", 39123);

        KeepADBNotification.refresh(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        Notification notification = shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull("Notification must still be posted (service stays foreground) instead of "
                + "being left on its previous content or silently cancelled", notification);
        assertEquals(context.getString(R.string.notification_title_disabled),
                notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(context.getString(R.string.notification_text_disabled_keepalive_waiting),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue("Cached endpoint must be cleared once Wireless Debugging is confirmed off",
                !KeepADBNotification.hasCurrentEndpoint());
    }

    /**
     * Contrast case for #445: when the service will really stop (here, Keep-Alive itself is
     * off), the old {@code stop()} path -- including its {@code manager.cancel()} -- must still
     * apply, since the foreground service is actually going away and cancel() is effective.
     */
    @Test
    public void notificationIsCancelledWhenWirelessDebuggingDropsAndKeepAliveWontKeepRunning()
            throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBPreferences.setLastDesiredOn(context, true);

        setStatic("currentHost", "192.168.1.50");
        setStatic("currentPort", 39123);

        KeepADBNotification.refresh(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        assertNull("Notification must be cancelled once the service actually stops (shouldRun() "
                + "false)", shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID));
    }

    /**
     * #448 regression guard: When Keep-Alive is enabled and discovery finishes with onUnavailable()
     * while Wireless Debugging has already dropped (KeepADB.isEnabled() == false), the notification
     * must show "disabled, waiting" rather than "searching...".
     *
     * <p>#453: the previous version of this test set {@code gateway(false)} before calling {@link
     * KeepADBNotification#refresh(Context)}, so {@code refreshInternal()}'s early {@code
     * !isEnabled()} guard returned before discovery ever started -- the {@code onUnavailable()}
     * branch inside {@code startDiscoveryDirectLocked()} this test claims to cover was never
     * reached. This version starts Wireless Debugging enabled so a real discovery attempt begins
     * (using the {@link KeepADBFakeNsdProbe}/{@link KeepADBFakeScheduler} seams {@link
     * KeepADBEndpoint} already exposes for its own tests, #249, plus {@link
     * KeepADBNotification#setEndpointForTesting}, #453), then flips the gateway to disabled while
     * that discovery is still in flight and advances the fake scheduler past {@code
     * OVERALL_TIMEOUT_MS} so the real {@code KeepADBEndpoint.Listener.onUnavailable()} callback
     * fires -- exactly the mid-discovery drop the issue describes.
     */
    @Test
    public void notificationShowsDisabledWaitingWhenWirelessDebuggingDropsMidDiscovery()
            throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint fakeEndpoint = new KeepADBEndpoint(context, nsdProbe, scheduler);
        KeepADBNotification.setEndpointForTesting(fakeEndpoint);

        // isEnabled() is still true here: refreshInternal() must actually start discovery, not
        // take the early !isEnabled() exit the old test accidentally triggered.
        KeepADBNotification.refresh(context);
        assertEquals("refresh() must have started a real mDNS discovery attempt",
                1, nsdProbe.discoverServicesCallCount);

        // Wireless Debugging drops mid-discovery: not an explicit user-off, so
        // KeepADBPreferences.lastDesiredOn stays true (matches the issue's Keep-Alive-waiting
        // scenario) while the gateway itself now reports disabled.
        gateway.write(context, false);

        // Drives KeepADBEndpoint's real OVERALL_TIMEOUT_MS watchdog without waiting real time,
        // which calls the discovery Listener's onUnavailable() -- the branch this test covers.
        scheduler.advanceBy(8_000);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        Notification notification = shadowManager.getNotification(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull("Notification must still be posted when Keep-Alive is waiting", notification);
        assertEquals(context.getString(R.string.notification_title_disabled),
                notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(context.getString(R.string.notification_text_disabled_keepalive_waiting),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
