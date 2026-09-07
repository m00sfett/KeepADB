package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
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
        setStatic("currentHost", null);
        setStatic("currentPort", 0);
        setStatic("endpointListener", null);
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

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
