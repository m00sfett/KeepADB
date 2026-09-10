package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
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

import java.util.concurrent.TimeUnit;

/** Runtime state contracts; no production-source parsing is used here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBMultiStateContractTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBNotification.resetForTesting();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
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
}
