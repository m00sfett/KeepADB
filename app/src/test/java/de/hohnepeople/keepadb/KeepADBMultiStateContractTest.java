package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Runtime state contracts; no production-source parsing is used here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBMultiStateContractTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();
    private Context context;
    private ServerSocket endpointServer;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        // A new installation as the application leaves it (#904): only the privacy marker.
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBNetwork.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
        closeEndpointServer();
        KeepADBNetwork.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void clickIntentMapsOnlyRealOffStatesToEnable() {
        assertTrue(KeepADB.desiredOnForClick(KeepADB.State.OFF));
        assertTrue(KeepADB.desiredOnForClick(KeepADB.State.OFF_KEEP_ALIVE_WAITING));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.ENABLED_DISCONNECTED));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.ENABLED_CONNECTED));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.PERMISSION_MISSING));
    }

    @Test
    public void everySurfaceRendersTheOffStateAtRuntime() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);

        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse(((Switch) activity.get().findViewById(R.id.toggle)).isChecked());
        assertEquals(context.getString(R.string.status_off),
                ((TextView) activity.get().findViewById(R.id.status)).getText().toString());
        activity.pause().close();

        View widget = renderWidget();
        assertEquals(context.getString(R.string.tile_state_off), widgetText(widget));

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        Tile tile = tileService.getQsTile();
        assertNotNull(tile);
        assertEquals(Tile.STATE_INACTIVE, tile.getState());
        assertEquals(context.getString(R.string.tile_state_off), tile.getSubtitle());
        stopTileService(tileService);
    }

    @Test
    public void everySurfaceRendersTheKeepAliveWaitingStateAtRuntime() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);

        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse(((Switch) activity.get().findViewById(R.id.toggle)).isChecked());
        assertEquals(context.getString(R.string.status_off_keep_alive_waiting),
                ((TextView) activity.get().findViewById(R.id.status)).getText().toString());
        activity.pause().close();

        assertEquals(context.getString(R.string.widget_state_off_waiting), widgetText(renderWidget()));

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        assertEquals(Tile.STATE_INACTIVE, tileService.getQsTile().getState());
        assertEquals(context.getString(R.string.tile_state_keep_alive_waiting),
                tileService.getQsTile().getSubtitle());
        stopTileService(tileService);
    }

    @Test
    public void everySurfaceRendersAnEnabledDisconnectedStateAtRuntime() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));

        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup();
        assertTrue(((Switch) activity.get().findViewById(R.id.toggle)).isChecked());
        assertEquals(context.getString(R.string.status_enabled_disconnected),
                ((TextView) activity.get().findViewById(R.id.status)).getText().toString());
        activity.pause().close();

        assertEquals(context.getString(R.string.tile_state_disconnected), widgetText(renderWidget()));

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        assertEquals(Tile.STATE_ACTIVE, tileService.getQsTile().getState());
        assertEquals(context.getString(R.string.tile_state_disconnected),
                tileService.getQsTile().getSubtitle());
        stopTileService(tileService);
    }

    @Test
    public void everySurfaceRendersAnEnabledConnectedStateAtRuntime() throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        int endpointPort = openEndpointServer();
        seedEndpoint("127.0.0.1", endpointPort);

        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((Switch) activity.get().findViewById(R.id.toggle)).isChecked());
        assertEquals(context.getString(R.string.status_on),
                ((TextView) activity.get().findViewById(R.id.status)).getText().toString());
        assertEquals(context.getString(R.string.endpoint_format, "127.0.0.1", endpointPort),
                ((TextView) activity.get().findViewById(R.id.endpoint)).getText().toString());
        activity.pause().close();

        assertEquals(context.getString(R.string.tile_state_connected), widgetText(renderWidget()));
        assertTrue("the status box of the larger sizes names the endpoint",
                panelText().contains(context.getString(R.string.tile_state_connected_format, "127.0.0.1", endpointPort)));

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        assertEquals(Tile.STATE_ACTIVE, tileService.getQsTile().getState());
        assertEquals(context.getString(R.string.tile_state_connected_format,
                        "127.0.0.1", endpointPort), tileService.getQsTile().getSubtitle());
        stopTileService(tileService);
    }

    /** #904: the tile follows the widget switch only; the app switch has no say there. */
    @Test
    public void connectedTileShowsTheEndpointWhateverTheAppSwitchSaysAndTheWidgetSwitchIsOff()
            throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        int endpointPort = openEndpointServer();
        seedEndpoint("127.0.0.1", endpointPort);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();

        assertEquals(context.getString(R.string.tile_state_connected_format,
                        "127.0.0.1", endpointPort), tileService.getQsTile().getSubtitle());
        stopTileService(tileService);
    }

    @Test
    public void connectedTileShowsOnlyTheStateWhenTheWidgetSwitchIsOn() throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        int endpointPort = openEndpointServer();
        seedEndpoint("127.0.0.1", endpointPort);
        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();

        assertEquals(context.getString(R.string.tile_state_connected),
                tileService.getQsTile().getSubtitle());
        stopTileService(tileService);
    }

    @Test
    public void connectedWidgetShowsThePortUnlessTheWidgetSwitchIsOn() throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        int endpointPort = openEndpointServer();
        seedEndpoint("127.0.0.1", endpointPort);
        // The other two switches do not reach the widget.
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));

        assertTrue(panelText().contains(String.valueOf(endpointPort)));

        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        String text = widgetText(renderWidget());
        assertEquals(context.getString(R.string.tile_state_connected), text);
        String panel = panelText();
        assertFalse(panel, panel.contains(String.valueOf(endpointPort)));
        assertFalse(panel, panel.contains("127.0.0.1"));
    }

    /**
     * #904: each switch masks its own area only. One switch on, the other two off: exactly the
     * matching surface loses the endpoint, the other three keep showing it. The counter-proof for
     * every single-switch test above and in the notification tests: a switch wired to the wrong
     * area (or one that hid everything) fails one of the three rows.
     */
    @Test
    public void eachPrivacySwitchHidesTheEndpointInItsOwnAreaOnly() throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        int endpointPort = openEndpointServer();
        seedEndpoint("127.0.0.1", endpointPort);

        for (String only : new String[] {"app", "notifications", "widgets"}) {
            KeepADBPreferences.setAppPrivacyEnabled(context, "app".equals(only));
            KeepADBPreferences.setNotificationPrivacyEnabled(context, "notifications".equals(only));
            KeepADBPreferences.setWidgetPrivacyEnabled(context, "widgets".equals(only));

            // App: a screen of the app masks the address.
            android.app.Activity screen = Robolectric.buildActivity(NetworkListActivity.class,
                    NetworkListActivity.intent(context)).setup().get();
            assertEquals(only + ": app area", "app".equals(only),
                    KeepADBNetworkDisplay.hidden(screen));
            assertEquals(only + ": app area text", "app".equals(only) ? "127.*.*.*" : "127.0.0.1",
                    KeepADBPreferences.maskHostForDisplay(screen, "127.0.0.1"));

            // Notifications: the persistent notification.
            KeepADBEndpointCoordinator.refresh(context);
            android.app.Notification notification = shadowOf(
                    context.getSystemService(android.app.NotificationManager.class))
                    .getNotification(KeepADBNotification.NOTIFICATION_ID);
            String notificationText = String.valueOf(notification.extras.getCharSequence(
                    android.app.Notification.EXTRA_TEXT));
            assertEquals(only + ": notification area " + notificationText,
                    !"notifications".equals(only), notificationText.contains("127.0.0.1")
                            && notificationText.contains(String.valueOf(endpointPort)));
            if ("notifications".equals(only)) {
                KeepADBNotificationTextScan.assertMentionsNone(notification, "127.0.0.1",
                        String.valueOf(endpointPort));
            }

            // Widgets: the widget and the tile.
            assertEquals(only + ": widget", context.getString(R.string.tile_state_connected),
                    widgetText(renderWidget()));
            assertEquals(only + ": widget status box", !"widgets".equals(only),
                    panelText().contains("127.0.0.1") && panelText().contains(String.valueOf(endpointPort)));
            KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                    .create().get();
            tileService.onStartListening();
            assertEquals(only + ": tile", "widgets".equals(only)
                    ? context.getString(R.string.tile_state_connected)
                    : context.getString(R.string.tile_state_connected_format, "127.0.0.1", endpointPort),
                    tileService.getQsTile().getSubtitle());
            stopTileService(tileService);
        }
    }

    @Test
    public void enabledDisconnectedTileRendersSearchingWhenWifiIsConnected() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        assertEquals(KeepADB.State.ENABLED_DISCONNECTED, KeepADB.getState(context));
        assertEquals(Tile.STATE_ACTIVE, tileService.getQsTile().getState());
        assertEquals(context.getString(R.string.tile_state_searching),
                tileService.getQsTile().getSubtitle());
        assertTrue("A connected Wi-Fi must start the real tile discovery path",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
        stopTileService(tileService);
    }

    /**
     * #596: closes the one direction the removed {@code KeepADBAsyncSurfaceRefreshContractTest}
     * source-content checks ({@code connectedStateUsesAnAtomicEndpointPair}/{@code
     * stateContractPinsConnectedAndMissingEndpointDirections}) asserted about {@code
     * KeepADB.getState()}'s source order (the Wi-Fi guard must be checked before the endpoint
     * guard) that this file's existing {@code prepareState}/{@code assert*Action} coverage did not
     * yet exercise: a cached endpoint alone must never be reported as {@code ENABLED_CONNECTED}
     * once Wi-Fi itself is down, even though nothing in normal operation is expected to leave a
     * stale cached endpoint behind after a real disconnect (the notification's own event-driven
     * teardown already clears it) -- this is the defensive belt-and-suspenders case the guard
     * order exists for.
     */
    @Test
    public void aCachedEndpointNeverReportsConnectedOnceWifiIsDisconnected() throws Exception {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        seedEndpoint("127.0.0.1", openEndpointServer());

        assertEquals("KeepADB.getState() must report ENABLED_DISCONNECTED, not ENABLED_CONNECTED, "
                        + "whenever Wi-Fi itself is down -- a cached endpoint address alone must "
                        + "never be enough",
                KeepADB.State.ENABLED_DISCONNECTED, KeepADB.getState(context));
    }

    @Test
    public void permissionMissingIsRenderedAndCannotTriggerAnySurfaceAction() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        assertEquals(KeepADB.State.PERMISSION_MISSING, KeepADB.getState(context));

        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse(activity.get().findViewById(R.id.toggle).isEnabled());
        assertEquals(context.getString(R.string.status_permission_missing),
                ((TextView) activity.get().findViewById(R.id.status)).getText().toString());
        ((Switch) activity.get().findViewById(R.id.toggle)).performClick();
        assertTrue(gateway.writes.isEmpty());
        activity.pause().close();

        View widget = renderWidget();
        assertEquals(context.getString(R.string.tile_state_permission_missing), widgetText(widget));
        // #912: without the grant a tap leads to the setup instead of a failing write.
        widget.findViewById(R.id.widget_tile).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent opened = shadowOf((Application) context).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(MainActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue(gateway.writes.isEmpty());

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        assertEquals(Tile.STATE_UNAVAILABLE, tileService.getQsTile().getState());
        tileService.onClick();
        assertTrue(gateway.writes.isEmpty());
        stopTileService(tileService);
    }

    @Test
    public void mainActivityActionsExecuteTheSharedOperationalStateMatrix() throws Exception {
        assertMainAction(KeepADB.State.OFF, true, false);
        assertMainAction(KeepADB.State.OFF_KEEP_ALIVE_WAITING, true, false);
        assertMainAction(KeepADB.State.ENABLED_DISCONNECTED, false, false);
        assertMainAction(KeepADB.State.ENABLED_CONNECTED, false, true);
    }

    @Test
    public void widgetActionsExecuteTheSharedOperationalStateMatrix() throws Exception {
        assertWidgetAction(KeepADB.State.OFF, true, false);
        assertWidgetAction(KeepADB.State.OFF_KEEP_ALIVE_WAITING, true, false);
        assertWidgetAction(KeepADB.State.ENABLED_DISCONNECTED, false, false);
        assertWidgetAction(KeepADB.State.ENABLED_CONNECTED, false, true);
    }

    @Test
    public void tileActionsExecuteTheSharedMatrixAndKeepItsReconnectException() throws Exception {
        assertTileAction(KeepADB.State.OFF, true, false);
        assertTileAction(KeepADB.State.OFF_KEEP_ALIVE_WAITING, true, false);
        assertTileAction(KeepADB.State.ENABLED_CONNECTED, false, true);

        KeepADBFakeSettingsGateway gateway = prepareState(KeepADB.State.ENABLED_DISCONNECTED, true);
        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        gateway.writes.clear();
        tileService.onClick();
        assertTrue("The tile reconnect exception must not write a disable", gateway.writes.isEmpty());
        assertEquals(KeepADB.State.ENABLED_DISCONNECTED, KeepADB.getState(context));
        assertTrue("The tile reconnect exception must start endpoint discovery",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
        stopTileService(tileService);
    }

    @Test
    public void allOperationalStateLabelsResolve() {
        assertTrue(context.getString(R.string.status_enabled_disconnected).length() > 0);
        assertTrue(context.getString(R.string.tile_state_disconnected).length() > 0);
        assertTrue(context.getString(R.string.tile_state_searching).length() > 0);
        assertTrue(context.getString(R.string.tile_state_connected).length() > 0);
        assertTrue(context.getString(R.string.widget_state_off_waiting).length() > 0);
    }

    private View renderWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ShadowAppWidgetManager shadowManager = shadowOf(manager);
        int widgetId = shadowManager.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        new KeepADBWidget().onUpdate(context, manager, new int[] {widgetId});
        View view = shadowManager.getViewFor(widgetId);
        assertNotNull(view);
        return view;
    }

    /** Everything the 4x2 widget shows and says, built from the current state (#912). */
    private String panelText() {
        return KeepADBWidgetTestSupport.allText(KeepADBWidgetTestSupport.applyAndMeasure(context,
                KeepADBWidgetViews.forSize(context, 77, KeepADBWidgetConfig.defaults(),
                        KeepADBWidgetState.project(context, false),
                        KeepADBWidgetLayout.SizeClass.PANEL), 304, 196));
    }

    private String widgetText(View view) {
        // The main control is Wireless Debugging in the default configuration, in every size
        // class; its state word is what TalkBack reads after the name (and before a port).
        assertNotNull(KeepADBWidgetTestSupport.hero(view));
        String state = KeepADBWidgetTestSupport.heroStateWord(context, view);
        assertTrue(state.length() > 0);
        return state;
    }

    private void stopTileService(KeepADBTileService tileService) {
        tileService.onStopListening();
        ShadowLooper shadowLooper = shadowOf(Looper.getMainLooper());
        shadowLooper.idleFor(3000, TimeUnit.MILLISECONDS);
    }

    private void assertMainAction(KeepADB.State expectedState, boolean expectedWrite,
            boolean wifiConnected) throws Exception {
        KeepADBFakeSettingsGateway gateway = prepareState(expectedState, wifiConnected);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(expectedState, KeepADB.getState(context));
        gateway.writes.clear();
        ((Switch) controller.get().findViewById(R.id.toggle)).performClick();
        assertEquals(Arrays.asList(expectedWrite), gateway.writes);
        controller.pause().close();
    }

    private void assertWidgetAction(KeepADB.State expectedState, boolean expectedWrite,
            boolean wifiConnected) throws Exception {
        KeepADBFakeSettingsGateway gateway = prepareState(expectedState, wifiConnected);
        View widget = renderWidget();
        assertEquals(expectedState, KeepADB.getState(context));
        gateway.writes.clear();
        clickWidget(widget);
        assertEquals(Arrays.asList(expectedWrite), gateway.writes);
        assertEquals(expectedWrite, gateway.isEnabled(context));
    }

    private void clickWidget(View widget) {
        int before = shadowOf((Application) context).getBroadcastIntents().size();
        assertTrue("The rendered RemoteViews label must dispatch its PendingIntent",
                widget.findViewById(R.id.widget_tile).performClick());
        shadowOf(Looper.getMainLooper()).idle();
        // This also guards the Robolectric dispatch path: use the manifest receiver, never
        // manually call onReceive or install a test-owned listener/receiver.
        java.util.List<Intent> broadcasts = shadowOf((Application) context).getBroadcastIntents();
        assertTrue("Widget click did not send a broadcast", broadcasts.size() > before);
        Intent sent = broadcasts.get(before);
        assertEquals("de.hohnepeople.keepadb.TOGGLE", sent.getAction());
        assertNotNull(sent.getComponent());
        assertEquals(KeepADBWidget.class.getName(), sent.getComponent().getClassName());
    }

    private void assertTileAction(KeepADB.State expectedState, boolean expectedWrite,
            boolean wifiConnected) throws Exception {
        KeepADBFakeSettingsGateway gateway = prepareState(expectedState, wifiConnected);
        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        assertEquals(expectedState, KeepADB.getState(context));
        gateway.writes.clear();
        tileService.onClick();
        assertEquals(Arrays.asList(expectedWrite), gateway.writes);
        stopTileService(tileService);
    }

    private KeepADBFakeSettingsGateway prepareState(KeepADB.State expectedState,
            boolean wifiConnected) throws Exception {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBNetwork.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        if (wifiConnected) {
            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        }
        boolean enabled = expectedState == KeepADB.State.ENABLED_DISCONNECTED
                || expectedState == KeepADB.State.ENABLED_CONNECTED;
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(enabled);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        if (expectedState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            KeepADBPreferences.setKeepAliveEnabled(context, true);
            KeepADB.recordExplicitIntent(context, true);
        } else {
            KeepADBPreferences.setKeepAliveEnabled(context, false);
            KeepADB.recordExplicitIntent(context, false);
        }
        if (expectedState == KeepADB.State.ENABLED_CONNECTED) {
            seedEndpoint("127.0.0.1", openEndpointServer());
        }
        assertEquals(expectedState, KeepADB.getState(context));
        return gateway;
    }

    private void seedEndpoint(String host, int port) throws Exception {
        synchronized (KeepADBEndpointCoordinator.class) {
            setStaticNotificationField("currentHost", host);
            setStaticNotificationField("currentPort", port);
        }
    }

    private void setStaticNotificationField(String fieldName, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private int openEndpointServer() throws java.io.IOException {
        closeEndpointServer();
        endpointServer = new ServerSocket(0);
        return endpointServer.getLocalPort();
    }

    private void closeEndpointServer() {
        if (endpointServer == null) return;
        try {
            endpointServer.close();
        } catch (java.io.IOException ignored) {
        }
        endpointServer = null;
    }
}
