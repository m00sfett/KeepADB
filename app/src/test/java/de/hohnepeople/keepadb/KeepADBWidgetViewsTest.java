package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.applyAndMeasure;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.detailsVisible;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.endpointLine;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.systemLineVisible;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.label;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.labelVisible;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.labels;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.state;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.systemLine;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.tiles;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.webhookLine;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.webhookLineVisible;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.hohnepeople.keepadb.KeepADBWidgetConfig.Action;
import de.hohnepeople.keepadb.KeepADBWidgetLayout.SizeClass;

/**
 * #912: what the control widget shows per size class, built from the shared projection: the tiles
 * and their order, the words that carry each state, the details, the privacy precedence and the
 * touch targets. The views are the real RemoteViews applied and laid out, not a source scan.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetViewsTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1?token=s3cret";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;
    private static final int WIDGET_ID = 7;

    private final AtomicBoolean wifi = new AtomicBoolean(true);
    private Context context;
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
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
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport());
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        // Automatic synchronization on: only then the fourth slot is "Report now".
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBWidgetConfig.clearAll(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // -- helpers -------------------------------------------------------------------------------

    private View render(KeepADBWidgetConfig config, SizeClass sizeClass) {
        return render(config, sizeClass, 1f);
    }

    private View render(KeepADBWidgetConfig config, SizeClass sizeClass, float fontScale) {
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, config.hideDetails);
        return applyAndMeasure(context,
                KeepADBWidgetViews.forSize(context, WIDGET_ID, config, projection, sizeClass,
                        KeepADBWidgetLayout.showLabels(fontScale)),
                Math.max(56, sizeClass.minWidthDp), Math.max(56, sizeClass.minHeightDp));
    }

    private void setAdb(boolean on) {
        gateway = new KeepADBFakeSettingsGateway(on);
        KeepADB.setGatewayForTesting(gateway);
    }

    private String name(int resId, Object... args) {
        return context.getString(resId, args);
    }

    private static KeepADBWidgetConfig order(Action primary, Action... order) {
        return KeepADBWidgetConfig.of(primary, Arrays.asList(order), false);
    }

    // -- tiles per size class ------------------------------------------------------------------

    @Test
    public void eachSizeClassShowsItsControlsAndDetails() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        String adb = name(R.string.widget_action_adb);
        String keepAlive = name(R.string.widget_action_keep_alive);
        String sync = name(R.string.widget_action_sync);
        String report = name(R.string.webhook_alert_action_report_now);

        assertEquals(Arrays.asList(adb), labels(render(defaults, SizeClass.SINGLE)));
        assertEquals(Arrays.asList(adb, keepAlive), labels(render(defaults, SizeClass.STACK)));
        assertEquals(Arrays.asList(adb, keepAlive), labels(render(defaults, SizeClass.DUO)));
        assertEquals(Arrays.asList(adb, keepAlive, sync, report),
                labels(render(defaults, SizeClass.GRID)));
        assertEquals(Arrays.asList(adb, keepAlive, sync, report),
                labels(render(defaults, SizeClass.GRID_STATUS)));
        assertEquals(Arrays.asList(adb, keepAlive, sync, report),
                labels(render(defaults, SizeClass.STRIP)));
        assertEquals(Arrays.asList(adb, keepAlive, sync, report),
                labels(render(defaults, SizeClass.PANEL)));

        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = render(defaults, sizeClass);
            assertEquals(sizeClass.name() + " details", sizeClass.hasDetails(), detailsVisible(widget));
            assertEquals(sizeClass.name() + " webhook line", sizeClass.hasDetails(),
                    detailsVisible(widget) && webhookLineVisible(widget));
            assertEquals(sizeClass.name() + " second row", sizeClass.secondRowTiles > 0,
                    widget.findViewById(R.id.widget_row_2).getVisibility() == View.VISIBLE);
        }
    }

    @Test
    public void theGridPutsTwoControlsInEachRowAndTheStackOneUnderTheOther() {
        View grid = render(KeepADBWidgetConfig.defaults(), SizeClass.GRID);
        assertEquals(2, ((android.view.ViewGroup) grid.findViewById(R.id.widget_row_1)).getChildCount());
        assertEquals(2, ((android.view.ViewGroup) grid.findViewById(R.id.widget_row_2)).getChildCount());

        View stack = render(KeepADBWidgetConfig.defaults(), SizeClass.STACK);
        assertEquals(1, ((android.view.ViewGroup) stack.findViewById(R.id.widget_row_1)).getChildCount());
        assertEquals(1, ((android.view.ViewGroup) stack.findViewById(R.id.widget_row_2)).getChildCount());
    }

    @Test
    public void theMainActionIsWhatTheSmallestLayoutKeeps() {
        KeepADBWidgetConfig config = order(Action.REPORT_NOW,
                Action.ADB, Action.KEEP_ALIVE, Action.WEBHOOK_SYNC, Action.REPORT_NOW);
        String report = name(R.string.webhook_alert_action_report_now);

        assertEquals(Arrays.asList(report), labels(render(config, SizeClass.SINGLE)));
        // Two controls: the main one plus the first of the others, in the order's sequence.
        assertEquals(Arrays.asList(name(R.string.widget_action_adb), report),
                labels(render(config, SizeClass.DUO)));
    }

    @Test
    public void theOrderFixesEveryPositionAndGrowingOnlyAddsControls() {
        KeepADBWidgetConfig config = order(Action.ADB,
                Action.REPORT_NOW, Action.WEBHOOK_SYNC, Action.KEEP_ALIVE, Action.ADB);
        String adb = name(R.string.widget_action_adb);
        String keepAlive = name(R.string.widget_action_keep_alive);
        String sync = name(R.string.widget_action_sync);
        String report = name(R.string.webhook_alert_action_report_now);

        assertEquals(Arrays.asList(report, sync, keepAlive, adb), labels(render(config, SizeClass.STRIP)));
        assertEquals(Arrays.asList(report, sync, keepAlive, adb), labels(render(config, SizeClass.GRID)));
        // Row by row in the grid: the first two, then the last two.
        List<View> grid = tiles(render(config, SizeClass.GRID));
        assertEquals(report, label(grid.get(0)));
        assertEquals(sync, label(grid.get(1)));
        assertEquals(keepAlive, label(grid.get(2)));
        assertEquals(adb, label(grid.get(3)));
        // Smaller: the main action (ADB) and the first other; each keeps its place relative to
        // the others, nothing swaps when the widget grows.
        assertEquals(Arrays.asList(report, adb), labels(render(config, SizeClass.DUO)));
        assertEquals(Arrays.asList(adb), labels(render(config, SizeClass.SINGLE)));
    }

    // -- states in words, pending and errors ----------------------------------------------------

    @Test
    public void everyTileStatesItsStateInWordsAndTalkBackNamesControlAndState() {
        View widget = render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        List<View> tiles = tiles(widget);
        assertEquals(4, tiles.size());
        for (View tile : tiles) {
            assertTrue("a state word on " + label(tile), state(tile).length() > 0);
            assertNotNull(tile.getContentDescription());
            assertTrue(tile.getContentDescription().toString().contains(state(tile)));
        }
        assertEquals(name(R.string.toggle_label) + ": " + state(tiles.get(0)),
                tiles.get(0).getContentDescription().toString());
        assertTrue(tiles.get(1).getContentDescription().toString()
                .startsWith(name(R.string.settings_section_keep_alive)));
        assertTrue(tiles.get(2).getContentDescription().toString()
                .startsWith(name(R.string.widget_action_sync_description)));
        assertTrue(tiles.get(3).getContentDescription().toString()
                .startsWith(name(R.string.webhook_alert_action_report_now)));
    }

    @Test
    public void theWirelessDebuggingTileSaysOffConnectedWaitingAndSwitching() {
        // Connected: Wi-Fi up, a verified endpoint, the setting on.
        View connected = render(KeepADBWidgetConfig.defaults(), SizeClass.SINGLE);
        assertEquals("one word, the port has its own line in the status box",
                name(R.string.tile_state_connected), state(tiles(connected).get(0)));

        setAdb(false);
        assertEquals(name(R.string.tile_state_off),
                state(tiles(render(KeepADBWidgetConfig.defaults(), SizeClass.SINGLE)).get(0)));

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertEquals(name(R.string.widget_state_off_waiting),
                state(tiles(render(KeepADBWidgetConfig.defaults(), SizeClass.SINGLE)).get(0)));
    }

    @Test
    public void aPendingToggleIsVisibleAsSwitching() {
        setAdb(false);
        KeepADB.setSchedulerForTesting(new KeepADBFakeScheduler());
        KeepADB.setEnabled(context, true, "content_observer");
        assertTrue(KeepADB.isTogglePending());

        View widget = render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        assertEquals(name(R.string.state_pending), state(tiles(widget).get(0)));
        assertEquals(name(R.string.status_pending), systemLine(widget));
    }

    @Test
    public void aMissingPermissionIsVisibleOnTheTileAndInTheDetails() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);

        View widget = render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        assertEquals(name(R.string.tile_state_permission_missing), state(tiles(widget).get(0)));
        assertEquals(name(R.string.status_permission_missing), systemLine(widget));
    }

    @Test
    public void keepAliveSaysOnWaitingPausedAndOff() {
        Action keepAlive = Action.KEEP_ALIVE;
        KeepADBWidgetConfig config = order(keepAlive, Action.KEEP_ALIVE, Action.ADB,
                Action.WEBHOOK_SYNC, Action.REPORT_NOW);
        assertEquals(name(R.string.tile_state_off), state(tiles(render(config, SizeClass.SINGLE)).get(0)));

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertEquals(name(R.string.widget_state_on), state(tiles(render(config, SizeClass.SINGLE)).get(0)));

        // Off but resolving by itself (no Wi-Fi yet): waiting.
        setAdb(false);
        wifi.set(false);
        assertEquals(name(R.string.widget_state_waiting),
                state(tiles(render(config, SizeClass.SINGLE)).get(0)));

        // The last explicit intent was off: paused until the user acts, in words.
        wifi.set(true);
        KeepADB.recordExplicitIntent(context, false);
        View paused = render(config, SizeClass.PANEL);
        assertEquals(name(R.string.widget_state_paused), state(tiles(paused).get(0)));
    }

    @Test
    public void theWebhookControlsFollowTheSharedActionState() {
        KeepADBWidgetConfig sync = order(Action.WEBHOOK_SYNC, Action.WEBHOOK_SYNC, Action.REPORT_NOW,
                Action.ADB, Action.KEEP_ALIVE);
        KeepADBWidgetConfig report = order(Action.REPORT_NOW, Action.REPORT_NOW, Action.WEBHOOK_SYNC,
                Action.ADB, Action.KEEP_ALIVE);

        // Synchronization on, a valid destination and a verified endpoint: on, ready.
        assertEquals(name(R.string.widget_state_on), state(tiles(render(sync, SizeClass.SINGLE)).get(0)));
        View ready = tiles(render(report, SizeClass.SINGLE)).get(0);
        assertEquals(name(R.string.widget_state_ready), state(ready));
        assertEquals(name(R.string.webhook_alert_action_report_now), label(ready));

        // No endpoint: a report would send nothing, the tile says so.
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        assertEquals(name(R.string.widget_state_no_endpoint),
                state(tiles(render(report, SizeClass.SINGLE)).get(0)));
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);

        // A saved but invalid destination with synchronization on: Report now leads to the setup.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putString("register_webhook_url", "ftp://register.example/x").commit();
        assertEquals(name(R.string.widget_state_setup), state(tiles(render(report, SizeClass.SINGLE)).get(0)));

        // No destination at all: the sync control leads to the setup, in words.
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertEquals(name(R.string.widget_state_setup), state(tiles(render(sync, SizeClass.SINGLE)).get(0)));
    }

    @Test
    public void reportNowTakesNoPlaceWhileSynchronizationIsOffAndTheOthersWiden() {
        String report = name(R.string.webhook_alert_action_report_now);
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        View on = render(defaults, SizeClass.STRIP);
        assertEquals(4, tiles(on).size());
        int widthWithFour = tiles(on).get(0).getMeasuredWidth();

        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        View off = render(defaults, SizeClass.STRIP);
        assertEquals(Arrays.asList(name(R.string.widget_action_adb), name(R.string.widget_action_keep_alive),
                name(R.string.widget_action_sync)), labels(off));
        assertTrue("the remaining tiles widen", tiles(off).get(0).getMeasuredWidth() > widthWithFour);
        assertEquals("the sync control says off", name(R.string.tile_state_off), state(tiles(off).get(2)));

        // Nowhere in any size, and not as a main action either.
        KeepADBWidgetConfig reportFirst = order(Action.REPORT_NOW, Action.REPORT_NOW, Action.ADB,
                Action.KEEP_ALIVE, Action.WEBHOOK_SYNC);
        for (SizeClass sizeClass : SizeClass.values()) {
            assertFalse(sizeClass.name(), labels(render(reportFirst, sizeClass)).contains(report));
        }
        assertEquals("the main action's place goes to the first of the others",
                Arrays.asList(name(R.string.widget_action_adb)), labels(render(reportFirst, SizeClass.SINGLE)));
        // A grid with three controls: two, then one that fills its row.
        View grid = render(defaults, SizeClass.GRID);
        assertEquals(2, ((android.view.ViewGroup) grid.findViewById(R.id.widget_row_1)).getChildCount());
        assertEquals(1, ((android.view.ViewGroup) grid.findViewById(R.id.widget_row_2)).getChildCount());

        // Without a destination it is gone as well; with synchronization on again it is back.
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertFalse(labels(render(defaults, SizeClass.STRIP)).contains(report));
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertTrue(labels(render(defaults, SizeClass.STRIP)).contains(report));
    }

    @Test
    public void everyControlHasItsOwnIconAndTheStateWordStaysWithoutTheLabel() {
        View widget = render(KeepADBWidgetConfig.defaults(), SizeClass.STRIP);
        java.util.Set<Integer> icons = new java.util.HashSet<>();
        for (View tile : tiles(widget)) {
            android.widget.ImageView icon = tile.findViewById(R.id.widget_tile_icon);
            assertNotNull(label(tile), icon.getDrawable());
            icons.add(icon.getDrawable().getConstantState().hashCode());
        }
        assertEquals("four different icons", 4, icons.size());

        View large = render(KeepADBWidgetConfig.defaults(), SizeClass.STRIP, 2f);
        for (View tile : tiles(large)) {
            assertNotNull("icon stays", ((android.widget.ImageView) tile.findViewById(R.id.widget_tile_icon)).getDrawable());
            assertEquals("icon stays visible", View.VISIBLE, tile.findViewById(R.id.widget_tile_icon).getVisibility());
            assertTrue("state word stays", state(tile).length() > 0);
            assertFalse("the title gives way", labelVisible(tile));
        }
    }

    @Test
    public void textsAreOneShrinkingLineSoNoWordOrNumberIsBrokenInTheMiddle() {
        View widget = render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        for (View tile : tiles(widget)) {
            for (int id : new int[] {R.id.widget_tile_label, R.id.widget_tile_state}) {
                android.widget.TextView text = tile.findViewById(id);
                assertEquals(1, text.getMaxLines());
                assertEquals(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM, text.getAutoSizeTextType());
                assertTrue("never below 9sp", text.getAutoSizeMinTextSize() >= 9 * context.getResources().getDisplayMetrics().scaledDensity - 0.5f);
            }
        }
        // The endpoint is one line cut in the middle: both ends, including the port, stay readable.
        android.widget.TextView endpoint = widget.findViewById(R.id.widget_detail_endpoint);
        assertEquals(1, endpoint.getMaxLines());
        assertEquals(android.text.TextUtils.TruncateAt.MIDDLE, endpoint.getEllipsize());
        assertEquals(1, ((android.widget.TextView) widget.findViewById(R.id.widget_detail_webhook)).getMaxLines());
    }

    @Test
    public void aFailedReportIsVisibleAndSentIsClaimedOnlyForTheCurrentEndpoint() {
        KeepADBWidgetConfig report = order(Action.REPORT_NOW, Action.REPORT_NOW, Action.WEBHOOK_SYNC,
                Action.ADB, Action.KEEP_ALIVE);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        View failed = render(report, SizeClass.PANEL);
        assertEquals(name(R.string.widget_state_failed), state(tiles(failed).get(0)));
        assertEquals(name(R.string.notification_text_webhook_failed), webhookLine(failed));

        // A success for exactly the current endpoint and destination: sent.
        KeepADBPreferences.setWebhookLastReportedUrl(context, KeepADBPreferences.getRegisterWebhookUrl(context));
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, KeepADBEndpoint.formatEndpoint(HOST, PORT));
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);
        View sent = render(report, SizeClass.PANEL);
        assertEquals(name(R.string.widget_state_sent), state(tiles(sent).get(0)));
        assertEquals(name(R.string.widget_webhook_current), webhookLine(sent));

        // With synchronization off the webhook line is about that, not about an earlier report.
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertEquals(name(R.string.widget_webhook_sync_off), webhookLine(render(report, SizeClass.PANEL)));
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);

        // An old completion must not mark a newer endpoint delivered.
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT + 1, 2L);
        View moved = render(report, SizeClass.PANEL);
        assertEquals(name(R.string.widget_state_ready), state(tiles(moved).get(0)));
        assertEquals(name(R.string.widget_webhook_not_current), webhookLine(moved));
    }

    @Test
    public void theDetailsNameTheReasonsThatCanBeSolved() {
        setAdb(false);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);

        // No Wi-Fi yet: Keep-Alive is waiting for the network.
        wifi.set(false);
        assertEquals(name(R.string.status_off_keep_alive_waiting),
                systemLine(render(KeepADBWidgetConfig.defaults(), SizeClass.GRID_STATUS)));

        // Wi-Fi up but the network cannot be read (no location access): paused, with the reason.
        wifi.set(true);
        assertEquals(name(R.string.status_off_keep_alive_blocked_identity_unavailable),
                systemLine(render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL)));

        // No destination: the webhook line says it is not set up.
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertEquals(name(R.string.widget_webhook_unconfigured),
                webhookLine(render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL)));
    }

    // -- privacy -------------------------------------------------------------------------------

    @Test
    public void theEndpointIsShownOnlyWhenNeitherTheGlobalSwitchNorTheWidgetHidesIt() {
        View open = render(KeepADBWidgetConfig.defaults(), SizeClass.PANEL);
        assertEquals(name(R.string.endpoint_format, HOST, PORT), endpointLine(open));
        assertFalse("the endpoint line stands in for the state line", systemLineVisible(open));
        // The narrow box drops the word so the port stays on the line.
        assertEquals(name(R.string.tile_state_connected_format, HOST, PORT),
                endpointLine(render(KeepADBWidgetConfig.defaults(), SizeClass.GRID_STATUS)));

        // The per-widget option hides more: only the state is left.
        KeepADBWidgetConfig hidden = KeepADBWidgetConfig.of(Action.ADB, null, true);
        View closed = render(hidden, SizeClass.PANEL);
        assertNoEndpoint(closed);
        assertFalse(KeepADBWidgetTestSupport.endpointLineVisible(closed));
        assertEquals(name(R.string.status_on), systemLine(closed));
    }

    @Test
    public void theGlobalWidgetPrivacySwitchTakesPrecedenceOverAVisibleWidget() {
        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);

        // The widget itself says "show": the switch still wins.
        KeepADBWidgetConfig visible = KeepADBWidgetConfig.of(Action.ADB, null, false);
        for (SizeClass sizeClass : SizeClass.values()) {
            assertNoEndpoint(render(visible, sizeClass));
        }
        // Both on: still hidden, and the state itself stays readable.
        View both = render(KeepADBWidgetConfig.of(Action.ADB, null, true), SizeClass.PANEL);
        assertNoEndpoint(both);
        assertEquals(name(R.string.tile_state_connected), state(tiles(both).get(0)));
        assertEquals(name(R.string.status_on), systemLine(both));
    }

    @Test
    public void theWebhookUrlIsNeverOnTheWidgetInAnySizeOrPrivacyState() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        for (boolean global : new boolean[] {false, true}) {
            KeepADBPreferences.setWidgetPrivacyEnabled(context, global);
            for (boolean widgetHides : new boolean[] {false, true}) {
                KeepADBWidgetConfig config = KeepADBWidgetConfig.of(Action.ADB, null, widgetHides);
                for (SizeClass sizeClass : SizeClass.values()) {
                    String text = KeepADBWidgetTestSupport.allText(render(config, sizeClass));
                    assertFalse(text, text.contains("register.example"));
                    assertFalse(text, text.contains("s3cret"));
                    assertFalse(text, text.contains("dev1"));
                }
            }
        }
    }

    private void assertNoEndpoint(View widget) {
        String text = KeepADBWidgetTestSupport.allText(widget);
        assertFalse(text, text.contains(HOST));
        assertFalse(text, text.contains(String.valueOf(PORT)));
        assertFalse(text, text.contains(name(R.string.endpoint_format, HOST, PORT)));
    }

    // -- fonts, touch targets, clipping --------------------------------------------------------

    @Test
    public void aLargeFontKeepsTheStateAndLetsTheLabelGiveWay() {
        View normal = render(KeepADBWidgetConfig.defaults(), SizeClass.STRIP, 1f);
        View large = render(KeepADBWidgetConfig.defaults(), SizeClass.STRIP, 2f);
        for (View tile : tiles(normal)) assertTrue(labelVisible(tile));
        for (View tile : tiles(large)) {
            assertFalse("the label gives way first", labelVisible(tile));
            assertTrue("the state stays", state(tile).length() > 0);
            assertTrue("TalkBack still has the name",
                    tile.getContentDescription().toString().contains(": "));
        }
    }

    /**
     * The geometry budget without text: Robolectric's text metrics are not a device's (a line is
     * several times too tall), so the tests measure the structure the layout guarantees (tile,
     * details row and button sizes at the smallest room of a class) and leave text out. Text is cut
     * with an ellipsis by the layout, never allowed to grow a tile.
     */
    private View renderWithoutText(SizeClass sizeClass) {
        View widget = render(KeepADBWidgetConfig.defaults(), sizeClass);
        blank(widget);
        KeepADBWidgetTestSupport.measure(widget, Math.max(56, sizeClass.minWidthDp),
                Math.max(56, sizeClass.minHeightDp));
        return widget;
    }

    private static void blank(View view) {
        if (view instanceof android.widget.TextView) view.setVisibility(View.GONE);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) blank(group.getChildAt(i));
        }
    }

    @Test
    public void everyTileAndButtonKeepsAtLeast48DpAtTheSmallestSizeOfItsClass() {
        int minimum = 48;
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = renderWithoutText(sizeClass);
            for (View tile : tiles(widget)) {
                assertTrue(sizeClass + " tile width " + tile.getMeasuredWidth(),
                        tile.getMeasuredWidth() >= minimum);
                assertTrue(sizeClass + " tile height " + tile.getMeasuredHeight(),
                        tile.getMeasuredHeight() >= minimum);
            }
            if (sizeClass.hasDetails()) {
                View details = widget.findViewById(R.id.widget_details);
                assertTrue(sizeClass + " details height " + details.getMeasuredHeight(),
                        details.getMeasuredHeight() >= minimum - 4);
                View customize = widget.findViewById(R.id.widget_customize);
                if (sizeClass == SizeClass.PANEL) {
                    assertEquals(View.VISIBLE, customize.getVisibility());
                    assertTrue(customize.getMeasuredWidth() >= minimum);
                    assertTrue(customize.getMeasuredHeight() >= minimum);
                } else {
                    assertEquals("a narrow box keeps its room for the text",
                            View.GONE, customize.getVisibility());
                }
            }
        }
    }

    @Test
    public void rowsAndDetailsFitTheSmallestRoomOfEveryClass() {
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = renderWithoutText(sizeClass);
            int height = Math.max(56, sizeClass.minHeightDp);
            int used = widget.findViewById(R.id.widget_row_1).getMeasuredHeight();
            if (widget.findViewById(R.id.widget_row_2).getVisibility() == View.VISIBLE) {
                used += widget.findViewById(R.id.widget_row_2).getMeasuredHeight();
            }
            if (detailsVisible(widget)) {
                used += widget.findViewById(R.id.widget_details_row).getMeasuredHeight();
            }
            assertTrue(sizeClass + ": rows and details (" + used + ") fit the height " + height,
                    used <= height);
            assertTrue(sizeClass + " has tiles", tiles(widget).size() == sizeClass.actionCount());
        }
    }
}
