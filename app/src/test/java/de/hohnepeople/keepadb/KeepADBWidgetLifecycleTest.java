package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.applyAndMeasure;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.label;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.tiles;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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
        shadowOf(Looper.getMainLooper()).idle();
        TreeMap<String, ?> before = new TreeMap<>(
                context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).getAll());

        KeepADBWidget.refreshAllState(context);
        KeepADBWidget.refresh(context, id);
        new KeepADBWidget().onAppWidgetOptionsChanged(context, manager, id, new Bundle());
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(before, new TreeMap<>(
                context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).getAll()));
        assertFalse(KeepADBWidgetConfig.isStored(context, id));
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

    // -- responsive keys agree with the code path used below Android 12 ---------------------------

    @Test
    public void theLaunchersBestFitOfTheResponsiveKeysEqualsTheFallbackChoice() {
        // The platform shows the largest entry that fits the room, else the smallest. Replaying that
        // rule over many rooms and font scales must give the class the Android 11 path computes.
        for (float scale : new float[] {1f, 1.15f, 1.3f, 1.5f, 2f}) {
            for (int width = 40; width <= 420; width += 7) {
                for (int height = 40; height <= 320; height += 7) {
                    assertEquals(width + "x" + height + " @" + scale,
                            KeepADBWidgetLayout.choose(width, height, scale),
                            bestFit(width, height, scale));
                }
            }
        }
    }

    private static SizeClass bestFit(int width, int height, float scale) {
        SizeClass best = null;
        float bestArea = -1f;
        SizeClass smallest = null;
        float smallestArea = Float.MAX_VALUE;
        for (SizeClass sizeClass : SizeClass.values()) {
            float w = KeepADBWidgetLayout.responsiveWidthDp(sizeClass, scale);
            float h = KeepADBWidgetLayout.responsiveHeightDp(sizeClass, scale);
            float area = w * h;
            if (area < smallestArea) {
                smallestArea = area;
                smallest = sizeClass;
            }
            if (w <= width && h <= height && area > bestArea) {
                bestArea = area;
                best = sizeClass;
            }
        }
        return best != null ? best : smallest;
    }

    @Test
    public void theResponsiveViewsBuildAndTheSmallestIsShownWhenNoSizeIsKnown() {
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, false);
        RemoteViews responsive = KeepADBWidgetViews.responsive(context, 9,
                KeepADBWidgetConfig.defaults(), projection, 1f);
        View view = responsive.apply(context, new FrameLayout(context));
        assertEquals("one control before any size is known", 1, tiles(view).size());
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
