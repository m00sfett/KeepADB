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
        KeepADBNetwork.resetForTesting();
        KeepADBNotification.resetForTesting();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
        closeEndpointServer();
        KeepADBNetwork.resetForTesting();
        KeepADBNotification.resetForTesting();
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
        assertEquals(context.getString(R.string.widget_text_off), widgetText(widget));

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

        assertEquals(context.getString(R.string.widget_text_keep_alive_waiting), widgetText(renderWidget()));

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

        assertEquals(context.getString(R.string.widget_text_disconnected), widgetText(renderWidget()));

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

        assertEquals(context.getString(R.string.widget_text_connected_format, endpointPort),
                widgetText(renderWidget()));

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        tileService.onStartListening();
        assertEquals(Tile.STATE_ACTIVE, tileService.getQsTile().getState());
        assertEquals(context.getString(R.string.tile_state_connected_format,
                        "127.0.0.1", endpointPort), tileService.getQsTile().getSubtitle());
        stopTileService(tileService);
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
                KeepADBNotification.hasActiveDiscoveryAttemptForTesting());
        stopTileService(tileService);
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
        assertEquals(context.getString(R.string.widget_text_permission_missing), widgetText(widget));
        clickWidget(widget);
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
                KeepADBNotification.hasActiveDiscoveryAttemptForTesting());
        stopTileService(tileService);
    }

    @Test
    public void allOperationalStateLabelsResolve() {
        assertTrue(context.getString(R.string.status_enabled_disconnected).length() > 0);
        assertTrue(context.getString(R.string.tile_state_disconnected).length() > 0);
        assertTrue(context.getString(R.string.tile_state_searching).length() > 0);
        assertTrue(context.getString(R.string.tile_state_connected).length() > 0);
        assertTrue(context.getString(R.string.widget_text_disconnected).length() > 0);
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

    private String widgetText(View view) {
        TextView label = view.findViewById(R.id.widget_label);
        assertNotNull(label);
        assertTrue(label.getText().length() > 0);
        return label.getText().toString();
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
                widget.findViewById(R.id.widget_label).performClick());
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
        KeepADBNotification.resetForTesting();
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
        synchronized (KeepADBNotification.class) {
            setStaticNotificationField("currentHost", host);
            setStaticNotificationField("currentPort", port);
        }
    }

    private void setStaticNotificationField(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
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
