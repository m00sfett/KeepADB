package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.applyAndMeasure;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.label;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.tiles;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowPendingIntent;

import de.hohnepeople.keepadb.KeepADBWidgetConfig.Action;
import de.hohnepeople.keepadb.KeepADBWidgetLayout.SizeClass;

/**
 * #912: the widget's life on a launcher: legacy instances keep working, rendering and resizing
 * never send or poll, placement (onUpdate) is the one place that asks the coordinator for work,
 * and every control routes to its own action for its own instance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetLifecycleTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";

    private final AtomicBoolean wifi = new AtomicBoolean(true);
    private Context context;
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBFakeHttpTransport transport;
    private ServerSocket endpointServer;
    private ShadowAppWidgetManager widgets;
    private AppWidgetManager manager;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBWidgetConfig.clearAll(context);
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBReportHistory.clear(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(wifi::get);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        endpointServer = new ServerSocket(0);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting("127.0.0.1", endpointServer.getLocalPort(), 1L);
        manager = AppWidgetManager.getInstance(context);
        widgets = shadowOf(manager);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void tearDown() throws Exception {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        endpointServer.close();
        KeepADBWidgetConfig.clearAll(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private View panel(int widgetId, KeepADBWidgetConfig config, SizeClass sizeClass) {
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, config.hideDetails);
        return applyAndMeasure(context,
                KeepADBWidgetViews.forSize(context, widgetId, config, projection, sizeClass),
                Math.max(56, sizeClass.minWidthDp(1f)), Math.max(56, sizeClass.minHeightDp(1f)));
    }

    private NotificationManager notifications() {
        return context.getSystemService(NotificationManager.class);
    }

    private boolean endpointNotificationShown() {
        return shadowOf(notifications()).getNotification(KeepADBNotification.NOTIFICATION_ID) != null;
    }

    // -- legacy --------------------------------------------------------------------------------

    @Test
    public void aWidgetPlacedBeforeThisVersionRendersTheDefaultsAndKeepsItsToggle() {
        // No stored entry: exactly the state of every widget placed before #912.
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(KeepADBWidgetConfig.isStored(context, id));

        View view = widgets.getViewFor(id);
        List<View> tiles = tiles(view);
        assertFalse("it renders something", tiles.isEmpty());
        assertEquals("the main control is Wireless Debugging, as it was before",
                Action.ADB, KeepADBWidgetTestSupport.actionOf(context, tiles.get(0)));

        // The tap still reaches the same component with the same action it always did.
        PendingIntent toggle = KeepADBWidgetActions.toggleAdb(context);
        Intent saved = shadowOf(toggle).getSavedIntent();
        assertEquals("de.hohnepeople.keepadb.TOGGLE", saved.getAction());
        assertEquals(KeepADBWidget.class.getName(), saved.getComponent().getClassName());
        assertEquals(0, shadowOf(toggle).getRequestCode());

        gateway.writes.clear();
        new KeepADBWidget().onReceive(context, new Intent(context, KeepADBWidget.class)
                .setAction("de.hohnepeople.keepadb.TOGGLE"));
        assertEquals("the old PendingIntent still toggles", Arrays.asList(false), gateway.writes);
    }

    @Test
    public void renderingAndRestoringLeaveTheAppSettingsAlone() {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        // Placing is an update and may report in the background; let that finish first, so the
        // snapshot measures rendering only (on a slow runner the report landed after it).
        settle();
        TreeMap<String, ?> before = new TreeMap<>(
                context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).getAll());

        KeepADBWidget.refreshAllState(context);
        KeepADBWidget.refresh(context, id);
        new KeepADBWidget().onAppWidgetOptionsChanged(context, manager, id, new Bundle());
        // Anything rendering started in the background would write by now.
        settle();

        TreeMap<String, ?> after = new TreeMap<>(
                context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).getAll());
        assertEquals("changed keys: " + changedKeys(before, after), before, after);
        assertFalse(KeepADBWidgetConfig.isStored(context, id));
    }

    /** Runs the main looper and waits for the report client, twice, so chained work completes. */
    private static void settle() {
        for (int i = 0; i < 2; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            KeepADBRegisterClientResetRule.awaitIdle();
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static java.util.Set<String> changedKeys(java.util.Map<String, ?> before,
            java.util.Map<String, ?> after) {
        java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        return keys;
    }

    // -- no send, no poll on render and resize ----------------------------------------------------

    @Test
    public void renderingAndResizingNeverSendAReportNorStartADiscovery() {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();
        // Placing it was an update, which may report and notify (that is the point of the next
        // tests). From here on only renders and resizes happen.
        transport.recordedRequests.clear();
        notifications().cancelAll();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpointCoordinator.seedEndpointForTesting("127.0.0.1", endpointServer.getLocalPort(), 1L);
        assertFalse(endpointNotificationShown());

        KeepADBWidget provider = new KeepADBWidget();
        Bundle options = new Bundle();
        for (int width : new int[] {56, 110, 250, 330}) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 56 + width / 2);
            provider.onAppWidgetOptionsChanged(context, manager, id, options);
        }
        KeepADBWidget.refreshAll(context);
        KeepADBWidget.refreshAllState(context);
        KeepADBWidget.refresh(context, id);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals("a render or a resize sent nothing", 0, transport.getRequestCount());
        assertFalse("and started no discovery", KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
        assertFalse("and posted no endpoint notification", endpointNotificationShown());
    }

    @Test
    public void theCoordinatorStillSendsWhenAskedSoTheQuietTestMeansSomething() {
        // Counter-check for the test above: the same state does send when the coordinator is asked.
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBRegisterClientResetRule.awaitIdle();
        assertTrue("an asked refresh reports the cached endpoint", transport.getRequestCount() > 0);
    }

    @Test
    public void placementIsTheOneUpdateThatAsksTheCoordinatorForOneRefresh() {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        int other = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();
        // Whatever the shadow's own first update did, start from a clean slate.
        notifications().cancelAll();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpointCoordinator.seedEndpointForTesting("127.0.0.1", endpointServer.getLocalPort(), 1L);
        assertFalse(endpointNotificationShown());

        KeepADBWidget provider = new KeepADBWidget();
        provider.onAppWidgetOptionsChanged(context, manager, id, new Bundle());
        KeepADBWidget.refreshAllState(context);
        assertFalse("resize and re-render do not touch the coordinator", endpointNotificationShown());

        // Boot and placement: with Keep-Alive off nothing else restarts the coordinator.
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));
        provider.onUpdate(context, manager, new int[] {id, other});
        assertTrue("the update refreshed the coordinator, which shows the endpoint",
                endpointNotificationShown());
    }

    // -- Android 12+ placement and picker --------------------------------------------------------

    @Test
    public void android12PlacesWithTheDefaultsAndOffersAPreviewThatFitsItsTargetSize() throws Exception {
        android.content.res.XmlResourceParser parser = context.getResources().getXml(R.xml.widget_info);
        while (parser.getEventType() != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next();
        String ns = "http://schemas.android.com/apk/res/android";
        assertEquals("reconfigurable and configuration optional",
                android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
                        | android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL,
                parser.getAttributeIntValue(ns, "widgetFeatures", -1));
        assertEquals(2, parser.getAttributeIntValue(ns, "targetCellWidth", -1));
        assertEquals(1, parser.getAttributeIntValue(ns, "targetCellHeight", -1));
        assertEquals(R.layout.widget_preview_control,
                parser.getAttributeResourceValue(ns, "previewLayout", 0));

        // The preview shows what a 2x1 widget shows: two tiles, each text one shrinking line.
        View preview = android.view.LayoutInflater.from(context)
                .inflate(R.layout.widget_preview_control, new FrameLayout(context), false);
        android.view.ViewGroup row = (android.view.ViewGroup) preview;
        assertEquals(2, row.getChildCount());
        for (int i = 0; i < row.getChildCount(); i++) {
            android.view.ViewGroup tile = (android.view.ViewGroup) row.getChildAt(i);
            for (int j = 0; j < tile.getChildCount(); j++) {
                if (tile.getChildAt(j) instanceof android.widget.TextView) {
                    android.widget.TextView text = (android.widget.TextView) tile.getChildAt(j);
                    assertEquals(1, text.getMaxLines());
                    assertEquals(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM,
                            text.getAutoSizeTextType());
                }
            }
        }
    }

    // -- a language change redraws the widgets ------------------------------------------------------

    /** After a language change the widget used to keep its old words until something else drew it. */
    @Test
    public void aSystemLanguageChangeRedrawsThePlacedWidgetsWithoutSendingAnything() {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();
        String before = KeepADBWidgetTestSupport.allText(widgets.getViewFor(id));
        assertTrue(before, before.contains("Connected"));
        transport.recordedRequests.clear();

        org.robolectric.RuntimeEnvironment.setQualifiers("+de");
        Context german = ApplicationProvider.getApplicationContext();
        new KeepADBWidget().onReceive(german, new Intent(Intent.ACTION_LOCALE_CHANGED));
        shadowOf(Looper.getMainLooper()).idle();

        String after = KeepADBWidgetTestSupport.allText(widgets.getViewFor(id));
        assertTrue(after, after.contains("Verbunden"));
        assertFalse(after, after.contains("Connected"));
        assertEquals("redrawing sent nothing", 0, transport.getRequestCount());
        assertFalse(KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
    }

    @Test
    public void aConfigurationChangeOfALiveProcessRedrawsOnlyWhenLanguageOrFontScaleChanged() {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBApplication app = (KeepADBApplication) context;
        app.onCreate(); // remembers the current configuration
        assertTrue(KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("Connected"));

        org.robolectric.RuntimeEnvironment.setQualifiers("+de");
        android.content.res.Configuration german = new android.content.res.Configuration(
                context.getResources().getConfiguration());
        app.onConfigurationChanged(german);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the in-app language (Android 13+) arrives as a configuration change",
                KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("Verbunden"));

        // Nothing relevant changed: no redraw (the widget keeps what it shows).
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        app.onConfigurationChanged(german);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("Verbunden"));

        // A font scale change is one: the layout follows it.
        android.content.res.Configuration bigger = new android.content.res.Configuration(german);
        bigger.fontScale = 1.3f;
        app.onConfigurationChanged(bigger);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(KeepADBWidgetTestSupport.allText(widgets.getViewFor(id)).contains("Aus"));
    }

    // -- staying in step while nothing of the app runs ---------------------------------------------

    private android.app.job.JobScheduler jobs() {
        return context.getSystemService(android.app.job.JobScheduler.class);
    }

    @Test
    public void placementWatchesTheSettingForTheWidgetAndTheLastWidgetStopsIt() {
        assertNull("nothing watches before a widget exists", shadowOf(jobs()).getPendingJob(
                KeepADBWidgetRefreshJob.JOB_ID));
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();

        android.app.job.JobInfo job = shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID);
        assertNotNull("onUpdate schedules the watch", job);
        assertEquals(KeepADBWidgetRefreshJob.class.getName(), job.getService().getClassName());
        assertEquals(1, job.getTriggerContentUris().length);
        assertEquals(android.provider.Settings.Global.getUriFor("adb_wifi_enabled"),
                job.getTriggerContentUris()[0].getUri());
        assertFalse("no timer, no polling", job.isPeriodic());

        new KeepADBWidget().onDeleted(context, new int[] {id});
        new KeepADBWidget().onDisabled(context);
        assertNull("the last widget stops the watch",
                shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));
    }

    /**
     * Measured on the emulator (#912 round 3): the job fires on a change of the setting and
     * survives {@code install -r}, but {@code am force-stop} deletes it and nothing re-armed it until
     * the next placement or boot. The process start (the application's onCreate) must do it.
     */
    @Test
    public void aProcessStartWatchesTheSettingAgainWhenAWidgetIsPlacedAndTheJobIsGone() {
        assertTrue("the application object is the one that re-arms the watch",
                context instanceof KeepADBApplication);
        widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));

        // A force-stop deletes the app's jobs; the widget stays placed.
        jobs().cancel(KeepADBWidgetRefreshJob.JOB_ID);
        assertNull(shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));
        transport.recordedRequests.clear();

        ((KeepADBApplication) context).onCreate();

        android.app.job.JobInfo job = shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID);
        assertNotNull("a process start watches the setting again", job);
        assertEquals(android.provider.Settings.Global.getUriFor("adb_wifi_enabled"),
                job.getTriggerContentUris()[0].getUri());
        assertFalse("no timer, no polling", job.isPeriodic());
        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals("scheduling sends nothing", 0, transport.getRequestCount());
        assertFalse("and starts no discovery",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
    }

    @Test
    public void aProcessStartWithoutAWidgetSchedulesNothingAndNeverReplacesAnExistingJob() {
        assertNull(shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));
        ((KeepADBApplication) context).onCreate();
        assertNull("no widget, no watch", shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));

        widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        android.app.job.JobInfo before = shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID);
        ((KeepADBApplication) context).onCreate();
        ((KeepADBApplication) context).onCreate();
        assertSame("idempotent: the pending job is left alone", before,
                shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));
    }

    @Test
    public void aSettingChangeWhileNothingRunsRedrawsTheWidgetWithoutSendingAnything() {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals(context.getString(R.string.tile_state_connected),
                KeepADBWidgetTestSupport.heroStateWord(context, widgets.getViewFor(id)));
        notifications().cancelAll();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpointCoordinator.seedEndpointForTesting("127.0.0.1", endpointServer.getLocalPort(), 1L);
        transport.recordedRequests.clear();

        // Wireless Debugging is switched off from outside (Developer options, settings put).
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        // A content-triggered job is gone once it has fired; the platform starts it now.
        jobs().cancel(KeepADBWidgetRefreshJob.JOB_ID);
        assertNull(shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));
        // The platform starts the job in a process that is started for it.
        KeepADBWidgetRefreshJob job = org.robolectric.Robolectric.buildService(KeepADBWidgetRefreshJob.class)
                .create().get();
        assertFalse(job.onStartJob(null));
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals("the widget shows the new state", context.getString(R.string.tile_state_off),
                KeepADBWidgetTestSupport.heroStateWord(context, widgets.getViewFor(id)));
        assertNotNull("a content-triggered job runs once, so it watches again",
                shadowOf(jobs()).getPendingJob(KeepADBWidgetRefreshJob.JOB_ID));
        assertEquals("redrawing sent nothing", 0, transport.getRequestCount());
        assertFalse(KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
        assertFalse(endpointNotificationShown());
    }

    // -- routing -------------------------------------------------------------------------------

    @Test
    public void everyControlRoutesToItsOwnActionWhateverItsPosition() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        KeepADBWidgetConfig custom = KeepADBWidgetConfig.of(Action.WEBHOOK_SYNC,
                Arrays.asList(Action.WEBHOOK_SYNC, Action.KEEP_ALIVE, Action.ADB), false);
        View a = panel(101, defaults, SizeClass.STRIP);
        View b = panel(102, custom, SizeClass.STRIP);
        Application app = (Application) context;

        // Widget A, first tile: Wireless Debugging, the widget's own toggle broadcast.
        tiles(a).get(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent last = lastBroadcast(app);
        assertEquals("de.hohnepeople.keepadb.TOGGLE", last.getAction());
        assertEquals(KeepADBWidget.class.getName(), last.getComponent().getClassName());

        // Widget B, same position, another control: the synchronization (on, so it asks for off).
        tiles(b).get(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        last = lastBroadcast(app);
        assertEquals(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE, last.getAction());
        assertEquals(KeepADBReceiver.class.getName(), last.getComponent().getClassName());

        // B, second tile: Keep-Alive, the notification's action; A's second tile is the same.
        tiles(b).get(1).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE, lastBroadcast(app).getAction());
        tiles(a).get(1).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE, lastBroadcast(app).getAction());
    }

    private static Intent lastBroadcast(Application app) {
        List<Intent> broadcasts = shadowOf(app).getBroadcastIntents();
        assertFalse("a tap must send a broadcast", broadcasts.isEmpty());
        return broadcasts.get(broadcasts.size() - 1);
    }

    @Test
    public void theSyncControlAsksForTheOppositeStateAndNoReportControlExists() {
        Application app = (Application) context;
        KeepADBWidgetConfig sync = KeepADBWidgetConfig.of(Action.WEBHOOK_SYNC, null, false);

        // Synchronization is on: the sync control asks to switch it off.
        tiles(panel(401, sync, SizeClass.ONE)).get(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE, lastBroadcast(app).getAction());
        KeepADBRegisterClientResetRule.awaitIdle();
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));

        // Now it is off: the sync control asks to switch it on. The widget has no report control
        // in either state (#912; "Report now" lives only on the webhook failure notification).
        tiles(panel(402, sync, SizeClass.ONE)).get(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE, lastBroadcast(app).getAction());
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        View strip = panel(403, KeepADBWidgetConfig.defaults(), SizeClass.STRIP);
        assertEquals(3, tiles(strip).size());
        assertFalse(KeepADBWidgetTestSupport.allText(strip)
                .contains(context.getString(R.string.webhook_alert_action_report_now)));
    }

    @Test
    public void theTargetStateControlsAskForTheStateAndNeverForAFlip() {
        assertEquals(KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE, savedAction(
                KeepADBWidgetActions.forClick(context, KeepADBWidgetContent.Click.SYNC_ENABLE)));
        assertEquals(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE, savedAction(
                KeepADBWidgetActions.forClick(context, KeepADBWidgetContent.Click.SYNC_DISABLE)));
        assertEquals(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE, savedAction(
                KeepADBWidgetActions.forClick(context, KeepADBWidgetContent.Click.TOGGLE_KEEP_ALIVE)));
    }

    private static String savedAction(PendingIntent pendingIntent) {
        return shadowOf(pendingIntent).getSavedIntent().getAction();
    }

    @Test
    public void everyPendingIntentIsExplicitAndImmutable() {
        for (KeepADBWidgetContent.Click click : KeepADBWidgetContent.Click.values()) {
            ShadowPendingIntent shadow = shadowOf(KeepADBWidgetActions.forClick(context, click));
            Intent intent = shadow.getSavedIntent();
            assertNotNull(click + " names its component", intent.getComponent());
            assertEquals(click.name(), context.getPackageName(), intent.getComponent().getPackageName());
            assertTrue(click + " is immutable",
                    (shadow.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        }
        PendingIntent customize = KeepADBWidgetActions.customize(context, 5);
        assertTrue((shadowOf(customize).getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        assertNotNull(shadowOf(customize).getSavedIntent().getComponent());
    }

    @Test
    public void theSettingsOfOneWidgetAreReachedFromThatWidgetOnly() {
        View a = panel(201, KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        View b = panel(202, KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        Application app = (Application) context;

        a.findViewById(R.id.widget_customize).performClick();
        Intent first = shadowOf(app).getNextStartedActivity();
        b.findViewById(R.id.widget_customize).performClick();
        Intent second = shadowOf(app).getNextStartedActivity();

        assertEquals(KeepADBWidgetConfigActivity.class.getName(), first.getComponent().getClassName());
        assertEquals(201, first.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
        assertEquals(202, second.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
        PendingIntent one = KeepADBWidgetActions.customize(context, 201);
        PendingIntent two = KeepADBWidgetActions.customize(context, 202);
        assertNotEquals(shadowOf(one).getRequestCode(), shadowOf(two).getRequestCode());
        assertNotEquals(shadowOf(one).getSavedIntent().getData(), shadowOf(two).getSavedIntent().getData());
    }

    @Test
    public void setupAndReasonsLeadToTheRelevantAppDestination() {
        Application app = (Application) context;

        // No destination: the sync control leads to the webhook form.
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        KeepADBWidgetConfig syncFirst = KeepADBWidgetConfig.of(Action.WEBHOOK_SYNC, null, false);
        tiles(panel(301, syncFirst, SizeClass.ONE)).get(0).performClick();
        Intent setup = shadowOf(app).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), setup.getComponent().getClassName());
        assertTrue(setup.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, false));

        // The grant is missing: the Wireless Debugging tile and the details open the home screen.
        shadowOf(app).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        View widget = panel(302, KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        tiles(widget).get(0).performClick();
        assertEquals(MainActivity.class.getName(),
                shadowOf(app).getNextStartedActivity().getComponent().getClassName());
        widget.findViewById(R.id.widget_status_text).performClick();
        assertEquals(MainActivity.class.getName(),
                shadowOf(app).getNextStartedActivity().getComponent().getClassName());
        assertNull("nothing else started", shadowOf(app).getNextStartedActivity());
    }

    // -- the Android 12+ entries are keyed by the sizes the launcher reports --------------------------

    private static Bundle sizesOptions(android.util.SizeF... sizes) {
        Bundle options = new Bundle();
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                new java.util.ArrayList<>(Arrays.asList(sizes)));
        return options;
    }

    /**
     * The platform's own selection, run for real: {@code RemoteViews.apply(context, parent, handler,
     * size)} (hidden, so by reflection) takes, among the entries whose key fits the widget size
     * ({@code ceil(width) + 1 > key.width}, the same for the height), the one with the smallest
     * squared distance to it, else the smallest by area (AOSP RemoteViews#findBestFitLayout,
     * #fitsIn, #squareDistance, #findSmallestRemoteView).
     */
    private View hostPicks(RemoteViews views, android.util.SizeF widgetSize) throws Exception {
        java.lang.reflect.Method apply = RemoteViews.class.getMethod("apply", Context.class,
                android.view.ViewGroup.class, Class.forName("android.widget.RemoteViews$InteractionHandler"),
                android.util.SizeF.class);
        return (View) apply.invoke(views, context, new FrameLayout(context), null, widgetSize);
    }

    /** What tells the classes apart in a rendered view: counts, status line, labels, button shape. */
    private static String signature(View view) {
        List<View> tiles = tiles(view);
        return tiles.size() + "/" + KeepADBWidgetTestSupport.statusVisible(view) + "/"
                + (KeepADBWidgetTestSupport.label(tiles.get(0)) != null) + "/"
                + (tiles.size() > 1 && tiles.get(1) instanceof android.widget.ImageView) + "/"
                + (tiles.size() > 1 && KeepADBWidgetTestSupport.label(tiles.get(1)) != null);
    }

    private View classView(SizeClass sizeClass) {
        return KeepADBWidgetViews.forSize(context, 9, KeepADBWidgetConfig.defaults(),
                KeepADBWidgetState.project(context, false), sizeClass)
                .apply(context, new FrameLayout(context));
    }

    private RemoteViews responsiveFor(float fs, android.util.SizeF... reported) {
        return KeepADBWidgetViews.responsive(context, 9, KeepADBWidgetConfig.defaults(),
                KeepADBWidgetState.project(context, false), fs, sizesOptions(reported));
    }

    /** The class the entry of a reported size is drawn as: that of its key, a dp or two below it. */
    private static SizeClass classOfEntry(android.util.SizeF reported, float fs) {
        return KeepADBWidgetLayout.choose(KeepADBWidgetLayout.keyFor(reported), fs);
    }

    /**
     * Review R2 and R3: entries keyed at each class's minimum size lost against the host's
     * smallest-distance rule (304 x 140 dp was the stack for choose() and the strip for the host);
     * one entry per reported size is the nearest one for a widget of that size. Run through the
     * platform's own selection, also for a widget a dp or two smaller than reported (143 x 193 dp
     * against a reported 144 x 193, which without the tolerance fits no entry).
     */
    @Test
    public void theHostPicksTheEntryOfTheReportedSizeAlsoWhenTheWidgetIsASliverSmaller() throws Exception {
        android.util.SizeF[][] reported = {
                {new android.util.SizeF(304, 140)},
                {new android.util.SizeF(144, 193), new android.util.SizeF(296, 89)},   // Android 14
                {new android.util.SizeF(137, 176), new android.util.SizeF(291, 80)},   // Android 11
                {new android.util.SizeF(144, 89), new android.util.SizeF(220, 193),
                        new android.util.SizeF(500, 300), new android.util.SizeF(68, 193)}};
        for (float fs : new float[] {1f, 1.3f, 2f}) {
            for (android.util.SizeF[] sizes : reported) {
                RemoteViews views = responsiveFor(fs, sizes);
                for (android.util.SizeF size : sizes) {
                    String expected = signature(classView(classOfEntry(size, fs)));
                    for (int shrink = 0; shrink <= KeepADBWidgetLayout.FIT_TOLERANCE_DP; shrink++) {
                        android.util.SizeF actual = new android.util.SizeF(
                                size.getWidth() - shrink, size.getHeight() - shrink);
                        assertEquals(actual + " of " + size + " @" + fs + " is " + classOfEntry(size, fs),
                                expected, signature(hostPicks(views, actual)));
                    }
                }
            }
        }
        // The size of the review: 304 x 140 dp at font 1.0 is the stack, not the strip.
        assertEquals(SizeClass.STACK, classOfEntry(new android.util.SizeF(304, 140), 1f));
        assertEquals(signature(classView(SizeClass.STACK)), signature(hostPicks(
                responsiveFor(1f, new android.util.SizeF(304, 140)), new android.util.SizeF(304, 140))));
    }

    /** A rotation: the same widget is 144 x 193 dp in portrait and 296 x 89 dp in landscape. */
    @Test
    public void aRotationIsResolvedByTheHostFromThePortraitAndTheLandscapeEntry() throws Exception {
        android.util.SizeF portrait = new android.util.SizeF(144, 193);
        android.util.SizeF landscape = new android.util.SizeF(296, 89);
        RemoteViews views = responsiveFor(1f, portrait, landscape);
        assertEquals(signature(classView(SizeClass.GRID)), signature(hostPicks(views, portrait)));
        assertEquals(signature(classView(SizeClass.STRIP)), signature(hostPicks(views, landscape)));
        assertFalse(signature(hostPicks(views, portrait)).equals(signature(hostPicks(views, landscape))));
    }

    /** Where nothing fits the host would take the smallest by area: the safe entry is the hero alone. */
    @Test
    public void aWidgetSmallerThanEveryEntryGetsTheHeroAloneNotASqueezedStrip() throws Exception {
        RemoteViews views = responsiveFor(1f, new android.util.SizeF(144, 193),
                new android.util.SizeF(296, 89));
        View tiny = hostPicks(views, new android.util.SizeF(100, 60));
        assertEquals(signature(classView(SizeClass.ONE)), signature(tiny));
        assertEquals(1, tiles(tiny).size());
        // A real entry that fits wins over the safe one (which fits every widget).
        assertEquals(signature(classView(SizeClass.STRIP)),
                signature(hostPicks(views, new android.util.SizeF(296, 89))));
    }

    /** The platform throws above 16 entries; there are never more, and a refused map is no crash. */
    @Test
    public void neverMoreThanSixteenEntriesAndARefusedMapFallsBackToOneView() {
        for (int count : new int[] {1, 15, 16, 17, 40}) {
            android.util.SizeF[] many = new android.util.SizeF[count];
            for (int i = 0; i < count; i++) many[i] = new android.util.SizeF(100 + 7 * i, 90 + 5 * i);
            Bundle options = sizesOptions(many);
            List<android.util.SizeF> keys = KeepADBWidgetLayout.entryKeys(options);
            assertTrue(count + " sizes: " + keys.size() + " keys", keys.size() <= KeepADBWidgetLayout.MAX_SIZED_VIEWS);
            assertEquals("the safe entry is always there", KeepADBWidgetLayout.SAFE_KEY, keys.get(keys.size() - 1));
            assertEquals("the same keys every time", keys, KeepADBWidgetLayout.entryKeys(options));
            RemoteViews views = KeepADBWidgetViews.responsive(context, 9, KeepADBWidgetConfig.defaults(),
                    KeepADBWidgetState.project(context, false), 1f, options);
            assertNotNull(views.apply(context, new FrameLayout(context)));
        }
        // More sizes than fit keep the largest ones by area.
        android.util.SizeF[] many = new android.util.SizeF[20];
        for (int i = 0; i < many.length; i++) many[i] = new android.util.SizeF(100 + 7 * i, 90 + 5 * i);
        List<android.util.SizeF> kept = KeepADBWidgetLayout.entryKeys(sizesOptions(many));
        assertTrue(kept.contains(KeepADBWidgetLayout.keyFor(many[19])));
        assertFalse(kept.contains(KeepADBWidgetLayout.keyFor(many[0])));

        // A map the platform refuses (17 entries) gives the fallback instead of an exception.
        java.util.Map<android.util.SizeF, RemoteViews> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 17; i++) {
            tooMany.put(new android.util.SizeF(10 + i, 10 + i), KeepADBWidgetViews.forSize(context, 9,
                    KeepADBWidgetConfig.defaults(), KeepADBWidgetState.project(context, false), SizeClass.ONE));
        }
        RemoteViews fallback = classRemote(SizeClass.ROW1);
        org.junit.Assert.assertSame(fallback, KeepADBWidgetViews.sizedOrFallback(tooMany, fallback));
        tooMany.remove(new android.util.SizeF(10, 10));
        assertNotSame(fallback, KeepADBWidgetViews.sizedOrFallback(tooMany, fallback));
    }

    private RemoteViews classRemote(SizeClass sizeClass) {
        return KeepADBWidgetViews.forSize(context, 9, KeepADBWidgetConfig.defaults(),
                KeepADBWidgetState.project(context, false), sizeClass);
    }

    @Test
    public void withoutAListOfSizesTheClassicOptionsAreUsedThenTheDeclaredDefault() {
        KeepADBWidgetConfig config = KeepADBWidgetConfig.defaults();
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, false);

        // Portrait (min width x max height) and landscape (max width x min height) of the options,
        // keyed a little below, plus the safe entry.
        Bundle classic = new Bundle();
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 144);
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 193);
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 296);
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 89);
        java.util.Map<android.util.SizeF, RemoteViews> entries = KeepADBWidgetViews
                .responsiveEntries(context, 9, config, projection, 1f, classic);
        assertEquals(Arrays.asList(new android.util.SizeF(142, 191), new android.util.SizeF(294, 87),
                KeepADBWidgetLayout.SAFE_KEY), new java.util.ArrayList<>(entries.keySet()));
        assertEquals(3, tiles(entries.get(new android.util.SizeF(142, 191))
                .apply(context, new FrameLayout(context))).size());
        assertFalse(KeepADBWidgetTestSupport.statusVisible(entries.get(new android.util.SizeF(294, 87))
                .apply(context, new FrameLayout(context))));
        assertEquals(1, tiles(entries.get(KeepADBWidgetLayout.SAFE_KEY)
                .apply(context, new FrameLayout(context))).size());

        // An empty list is no list.
        assertEquals(entries.keySet(), KeepADBWidgetViews.responsiveEntries(context, 9, config,
                projection, 1f, mergedWithEmptyList(classic)).keySet());

        // Nothing usable at all: the declared default (about 2x1: the hero and one square button).
        for (Bundle none : new Bundle[] {null, new Bundle()}) {
            java.util.Map<android.util.SizeF, RemoteViews> fallback = KeepADBWidgetViews
                    .responsiveEntries(context, 9, config, projection, 1f, none);
            assertEquals(Arrays.asList(KeepADBWidgetLayout.keyFor(new android.util.SizeF(
                            KeepADBWidgetLayout.DEFAULT_WIDTH_DP, KeepADBWidgetLayout.DEFAULT_HEIGHT_DP)),
                    KeepADBWidgetLayout.SAFE_KEY), new java.util.ArrayList<>(fallback.keySet()));
            assertEquals(2, tiles(fallback.values().iterator().next()
                    .apply(context, new FrameLayout(context))).size());
        }
    }

    private static Bundle mergedWithEmptyList(Bundle classic) {
        Bundle merged = new Bundle(classic);
        merged.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                new java.util.ArrayList<android.util.SizeF>());
        return merged;
    }

    /**
     * The provider reads the options of the widget when it renders, so what the launcher reports
     * after a resize decides the entries. The shadow host applies the views without a size, where
     * the platform takes the smallest entry: the safe hero-alone one.
     */
    @Test
    public void aResizeRebuildsTheEntriesFromTheNewSizes() throws Exception {
        int id = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("without a known size the smallest entry, the hero alone: " ,
                1, tiles(widgets.getViewFor(id)).size());

        manager.updateAppWidgetOptions(id, sizesOptions(new android.util.SizeF(304, 140)));
        new KeepADBWidget().onAppWidgetOptionsChanged(context, manager, id,
                manager.getAppWidgetOptions(id));
        shadowOf(Looper.getMainLooper()).idle();
        RemoteViews stackViews = KeepADBWidgetViews.responsive(context, id, KeepADBWidgetConfig.defaults(),
                KeepADBWidgetState.project(context, false), 1f, manager.getAppWidgetOptions(id));
        View stack = hostPicks(stackViews, new android.util.SizeF(304, 140));
        assertEquals("304 x 140 dp is the stack", 3, tiles(stack).size());
        assertNull(stack.findViewById(R.id.widget_status_row));
        assertNull("the stack's buttons have no label (the strip's tiles do)",
                KeepADBWidgetTestSupport.label(tiles(stack).get(1)));
        assertFalse("and are as wide as their half, not the 52 dp square of a row",
                tiles(stack).get(1) instanceof android.widget.ImageView);

        // A new size from the launcher: 144 x 89 dp, the hero and one square button.
        manager.updateAppWidgetOptions(id, sizesOptions(new android.util.SizeF(144, 89)));
        new KeepADBWidget().onAppWidgetOptionsChanged(context, manager, id,
                manager.getAppWidgetOptions(id));
        shadowOf(Looper.getMainLooper()).idle();
        RemoteViews rowViews = KeepADBWidgetViews.responsive(context, id, KeepADBWidgetConfig.defaults(),
                KeepADBWidgetState.project(context, false), 1f, manager.getAppWidgetOptions(id));
        assertEquals(2, tiles(hostPicks(rowViews, new android.util.SizeF(144, 89))).size());
    }

    @Test
    public void aNewRemoteViewsReappliedOnTheOldViewReplacesTheTilesInsteadOfAddingToThem() {
        // What a launcher does on a resize and on every refresh: reapply onto the view it has.
        KeepADBWidgetConfig config = KeepADBWidgetConfig.defaults();
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, false);
        RemoteViews strip = KeepADBWidgetViews.forSize(context, 9, config, projection, SizeClass.STRIP);
        RemoteViews single = KeepADBWidgetViews.forSize(context, 9, config, projection, SizeClass.ONE);

        View view = strip.apply(context, new FrameLayout(context));
        assertEquals(3, tiles(view).size());
        single.reapply(context, view);
        assertEquals(1, tiles(view).size());
        strip.reapply(context, view);
        assertEquals(3, tiles(view).size());
        strip.reapply(context, view);
        assertEquals("refreshing the same size does not stack tiles", 3, tiles(view).size());
    }
}
