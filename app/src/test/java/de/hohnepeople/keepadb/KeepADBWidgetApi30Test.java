package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.tiles;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

/**
 * #912: Android 11 has no responsive RemoteViews. There the provider picks the one layout the
 * launcher's reported size selects and rebuilds it when the options change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class KeepADBWidgetApi30Test {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private Context context;
    private AppWidgetManager manager;
    private ShadowAppWidgetManager widgets;
    private int id;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBWidgetConfig.clearAll(context);
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        // Off: the shadow's own first update then starts nothing.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        // Synchronization on: only then "Report now" has a place in the widget.
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://register.example/register/dev1");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        manager = AppWidgetManager.getInstance(context);
        widgets = shadowOf(manager);
        id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBWidgetConfig.clearAll(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private int tilesAfterResizeTo(int portraitWidthDp, int portraitHeightDp) {
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, portraitWidthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, portraitWidthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, portraitHeightDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, portraitHeightDp);
        manager.updateAppWidgetOptions(id, options);
        new KeepADBWidget().onAppWidgetOptionsChanged(context, manager, id, options);
        shadowOf(Looper.getMainLooper()).idle();
        View view = widgets.getViewFor(id);
        return tiles(view).size();
    }

    @Test
    public void theInstancesOwnHideOptionAndTheGlobalSwitchReachTheRenderedStatusBox() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBEndpointCoordinator.seedEndpointForTesting("10.20.30.40", 41873, 1L);
        int hidden = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        KeepADBWidgetConfig.save(context, hidden, KeepADBWidgetConfig.of(
                KeepADBWidgetConfig.Action.ADB, null, true));
        for (int widget : new int[] {id, hidden}) {
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120);
            manager.updateAppWidgetOptions(widget, options);
        }
        KeepADBWidget.refreshAllState(context);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the widget without the option shows the endpoint",
                KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("41873"));
        assertTrue("the widget with the option hides it",
                !KeepADBWidgetTestSupport.allText(widgets.getViewFor(hidden)).contains("41873"));

        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        KeepADBWidget.refreshAllState(context); // what the settings switch does after flipping it
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the global switch hides it in every widget",
                !KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("41873"));
    }

    /** Below Android 13 the in-app language is a preference; choosing it must redraw the widgets. */
    @Test
    public void choosingTheAppLanguageRedrawsThePlacedWidgets() {
        assertTrue(KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("Off"));

        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(
                android.app.Activity.class).create().get();
        KeepADBLocaleHelper.setAppLanguage(activity, "de");
        shadowOf(Looper.getMainLooper()).idle();

        String text = KeepADBWidgetTestSupport.allText(widgets.getViewFor(id));
        assertTrue(text, text.contains("Aus"));
        assertTrue(text, !text.contains("Off"));
    }

    @Test
    public void android11KeepsTheConfigurationScreenAtPlacement() throws Exception {
        android.content.res.XmlResourceParser parser = context.getResources().getXml(R.xml.widget_info);
        while (parser.getEventType() != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next();
        String ns = "http://schemas.android.com/apk/res/android";
        int features = parser.getAttributeIntValue(ns, "widgetFeatures", -1);
        assertEquals("reconfigurable only", AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE, features);
        assertEquals(KeepADBWidgetConfigActivity.class.getName(),
                parser.getAttributeValue(ns, "configure"));
    }

    @Test
    public void aLegacyWidgetWithoutReportedOptionsShowsTwoControls() {
        assertEquals("the declared 2x1 default", 2, tiles(widgets.getViewFor(id)).size());
    }

    @Test
    public void theReportedSizeSelectsTheLayoutAndResizingBackAndForthDoesNotStackTiles() {
        assertEquals("1x1", 1, tilesAfterResizeTo(72, 92));
        assertEquals("2x1: the hero and one square button", 2, tilesAfterResizeTo(152, 92));
        assertEquals("4x1: the hero and two tiles", 3, tilesAfterResizeTo(304, 92));
        assertEquals("2x2", 3, tilesAfterResizeTo(152, 196));
        assertEquals("1x2: the hero alone", 1, tilesAfterResizeTo(72, 196));
        assertEquals(1, tilesAfterResizeTo(72, 92));
        assertEquals("4x2", 3, tilesAfterResizeTo(304, 196));
        assertTrue("the panel shows the status line",
                widgets.getViewFor(id).findViewById(R.id.widget_status_row).getVisibility()
                        == View.VISIBLE);
        assertEquals("back to a short widget: the status line is gone", 3, tilesAfterResizeTo(304, 92));
        assertTrue(widgets.getViewFor(id).findViewById(R.id.widget_status_row) == null);
    }

    @Test
    public void thePerWidgetConfigurationAppliesToTheFallbackLayoutToo() {
        KeepADBWidgetConfig.save(context, id, KeepADBWidgetConfig.of(
                KeepADBWidgetConfig.Action.KEEP_ALIVE, null, false));
        assertEquals(1, tilesAfterResizeTo(72, 92));
        assertEquals(java.util.Arrays.asList(KeepADBWidgetConfig.Action.KEEP_ALIVE),
                KeepADBWidgetTestSupport.actions(context, widgets.getViewFor(id)));
        assertEquals(java.util.Arrays.asList(KeepADBWidgetConfig.Action.KEEP_ALIVE,
                        KeepADBWidgetConfig.Action.ADB),
                tilesAfterResizeToActions(152, 92));
    }

    private java.util.List<KeepADBWidgetConfig.Action> tilesAfterResizeToActions(int w, int h) {
        tilesAfterResizeTo(w, h);
        return KeepADBWidgetTestSupport.actions(context, widgets.getViewFor(id));
    }

    /**
     * Review finding: the Android 11 path built views for the current orientation only. A rotation
     * changes neither min nor max and sends no update, so a widget that was a 2x2 grid in portrait
     * stayed one when the screen turned (where it is a 4x1 strip). Both orientations are supplied.
     */
    @Test
    public void bothOrientationsAreSuppliedSoARotationNeedsNoUpdate() {
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 152);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 196);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 304);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 92);
        android.widget.RemoteViews views = KeepADBWidget.legacyViews(context, id,
                KeepADBWidgetConfig.defaults(), KeepADBWidgetState.project(context, false), options, 1f);

        // The same RemoteViews, applied on a portrait and on a landscape screen.
        android.content.res.Configuration config =
                new android.content.res.Configuration(context.getResources().getConfiguration());
        config.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
        View portrait = views.apply(context.createConfigurationContext(config),
                new android.widget.FrameLayout(context));
        config.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        View landscape = views.apply(context.createConfigurationContext(config),
                new android.widget.FrameLayout(context));

        // Portrait 152 x 196 dp: the grid, the hero names itself and the pills have labels.
        assertEquals(3, tiles(portrait).size());
        assertEquals(context.getString(R.string.widget_action_adb),
                KeepADBWidgetTestSupport.label(KeepADBWidgetTestSupport.hero(portrait)));
        assertTrue(KeepADBWidgetTestSupport.label(tiles(portrait).get(1)) != null);
        // Landscape 304 x 92 dp: the strip, a compact hero without a label and upright tiles.
        assertEquals(3, tiles(landscape).size());
        assertTrue(KeepADBWidgetTestSupport.label(KeepADBWidgetTestSupport.hero(landscape)) == null);
        assertTrue(KeepADBWidgetTestSupport.label(tiles(landscape).get(1)) != null);
        assertTrue("the strip has no status line, the grid has none either",
                !KeepADBWidgetTestSupport.statusVisible(landscape));
    }
}
