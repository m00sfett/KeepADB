package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.allText;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.applyAndMeasure;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowWifiInfo;

import de.hohnepeople.keepadb.KeepADBOverviewViews.Shape;
import de.hohnepeople.keepadb.KeepADBWidgetContent.Click;

/**
 * #913: the overview widget. Its information rows (Wireless Debugging, Wi-Fi, Keep-Alive) can only
 * open the app, never toggle; its one control is the shared auto-sync action; the delivery wording
 * keeps history apart from the current-endpoint claim; details hide per widget under the global
 * switch; and it shares the control widget's lifecycle (no extra loop, consistent instances).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBOverviewWidgetTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "192.168.178.40";
    private static final int PORT = 38203;
    private static final String SSID = "Moosnetz";

    private final AtomicBoolean wifi = new AtomicBoolean(true);
    private Context context;
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBFakeHttpTransport transport;
    private ShadowAppWidgetManager widgets;

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
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
        connectTo(SSID);
        widgets = shadowOf(AppWidgetManager.getInstance(context));
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

    private void connectTo(String ssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID("aa:bb:cc:dd:ee:ff");
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    /** The sender's own record of the last report, as the register client stores it. */
    private void storeLastReport(String status, String endpoint) {
        KeepADBPreferences.setWebhookLastReportStatus(context, status);
        KeepADBPreferences.setWebhookLastReportedUrl(context, URL);
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, endpoint);
        KeepADBPreferences.setWebhookLastReportedAtNow(context);
    }

    private KeepADBWidgetState projection(boolean hide) {
        return KeepADBWidgetState.project(context, hide);
    }

    private KeepADBOverviewContent content(boolean hide) {
        return KeepADBOverviewContent.of(context, projection(hide));
    }

    private View render(Shape shape, boolean hide) {
        int[] room = shape == Shape.WIDE ? new int[] {296, 176} : new int[] {144, 176};
        return applyAndMeasure(context,
                KeepADBOverviewViews.forShape(context, projection(hide), shape), room[0], room[1]);
    }

    private String name(int resId, Object... args) {
        return context.getString(resId, args);
    }

    private static View descendantWithText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = descendantWithText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    // -- content ---------------------------------------------------------------------------------

    @Test
    public void showsTheStateTheEndpointTheWifiNameAndKeepAliveWithItsReason() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBOverviewContent c = content(false);
        assertEquals(HOST + ":" + PORT, c.adb.value);
        assertEquals(SSID, c.wifi.value);
        assertEquals(name(R.string.widget_state_on), c.keepAlive.value);

        for (Shape shape : Shape.values()) {
            String text = allText(render(shape, false));
            assertTrue(shape + ": endpoint", text.contains(HOST + ":" + PORT));
            assertTrue(shape + ": Wi-Fi name", text.contains(SSID));
        }
    }

    @Test
    public void keepAliveNamesWhyItIsWaitingOrPaused() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        wifi.set(false);
        assertEquals(KeepADBOverviewContent.withDetail(name(R.string.widget_state_waiting),
                name(R.string.overview_reason_no_wifi)), content(false).keepAlive.value);
        assertEquals(name(R.string.overview_wifi_none), content(false).wifi.value);
    }

    @Test
    public void anUnreadableWifiNameSaysSoInsteadOfInventingOne() {
        connectTo(WifiManager.UNKNOWN_SSID);
        assertEquals(name(R.string.overview_wifi_name_unavailable), content(false).wifi.value);
    }

    // -- informational taps ------------------------------------------------------------------------

    @Test
    public void theInformationCardOnlyEverOpensTheAppAndNeverToggles() {
        // Every state the card can be in leads to an activity, never to a toggle broadcast.
        for (boolean adbOn : new boolean[] {true, false}) {
            for (boolean keepAlive : new boolean[] {true, false}) {
                for (boolean wifiUp : new boolean[] {true, false}) {
                    gateway = new KeepADBFakeSettingsGateway(adbOn);
                    KeepADB.setGatewayForTesting(gateway);
                    KeepADBPreferences.setKeepAliveEnabled(context, keepAlive);
                    wifi.set(wifiUp);
                    Click click = content(false).cardClick;
                    assertTrue(click.toString(), click == Click.OPEN_APP || click == Click.OPEN_NETWORK);
                    assertTrue(shadowOf(KeepADBWidgetActions.forClick(context, click)).isActivityIntent());
                }
            }
        }
    }

    @Test
    public void tappingTheCardLeavesWirelessDebuggingAndKeepAliveAsTheyAre() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        boolean keepAliveBefore = KeepADBPreferences.isKeepAliveEnabled(context);
        for (Shape shape : Shape.values()) {
            gateway.writes.clear();
            View card = render(shape, false).findViewById(R.id.widget_ov_card);
            assertTrue(card.isClickable());
            card.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(shape + ": no write to adb_wifi_enabled", Arrays.asList(), gateway.writes);
            assertEquals(keepAliveBefore, KeepADBPreferences.isKeepAliveEnabled(context));
            Intent started = shadowOf((Application) context).getNextStartedActivity();
            assertNotNull(shape + ": the tap opened a screen", started);
            assertEquals(MainActivity.class.getName(), started.getComponent().getClassName());
        }
    }

    @Test
    public void anUntrustedNetworkLeadsToTheNetworkCardAndSaysWhy() {
        // Keep-Alive on, Wireless Debugging off, connected to a network the allowlist lacks.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBEndpointCoordinator.resetForTesting();

        KeepADBOverviewContent c = content(false);
        assertEquals(Click.OPEN_NETWORK, c.cardClick);
        assertEquals(KeepADBOverviewContent.withDetail(name(R.string.widget_state_paused),
                name(R.string.overview_reason_untrusted)), c.keepAlive.value);
        assertEquals(SettingsActivity.class.getName(), shadowOf(
                KeepADBWidgetActions.forClick(context, c.cardClick)).getSavedIntent()
                .getComponent().getClassName());
    }

    // -- the one control ----------------------------------------------------------------------------

    @Test
    public void theAutoSyncPillIsTheSharedControlAndSetupWithoutAUrl() {
        KeepADBOverviewContent on = content(false);
        assertEquals(KeepADBWidgetConfig.Action.WEBHOOK_SYNC, on.sync.action);
        assertEquals(Click.SYNC_DISABLE, on.sync.click);

        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertEquals(Click.SYNC_ENABLE, content(false).sync.click);

        KeepADBPreferences.setRegisterWebhookUrl(context, "");
        KeepADBOverviewContent unconfigured = content(false);
        assertEquals(Click.SETUP_WEBHOOK, unconfigured.sync.click);
        assertEquals(Click.SETUP_WEBHOOK, unconfigured.deliveryClick);
        assertEquals(name(R.string.overview_delivery_unconfigured), unconfigured.delivery.value);
    }

    @Test
    public void theWidgetNeverOffersReportNow() {
        String report = name(R.string.webhook_alert_action_report_now);
        for (Shape shape : Shape.values()) {
            assertFalse(shape.toString(), allText(render(shape, false)).contains(report));
        }
    }

    // -- delivery wording ---------------------------------------------------------------------------

    @Test
    public void historicalSuccessNeverMakesTheCurrentEndpointLookReported() {
        // An earlier report for another endpoint was accepted: history, not the current claim.
        storeLastReport(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, "192.168.178.40:40000");
        KeepADBOverviewContent c = content(false);
        assertEquals(name(R.string.overview_delivery_not_current), c.delivery.value);
        assertFalse(c.lastDelivery.equals(name(R.string.overview_last_delivery_none)));

        storeLastReport(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, HOST + ":" + PORT);
        assertEquals(name(R.string.overview_delivery_current), content(false).delivery.value);

        // Without a verified endpoint nothing is claimed, whatever the history says.
        KeepADBEndpointCoordinator.resetForTesting();
        assertEquals(name(R.string.overview_delivery_no_endpoint), content(false).delivery.value);
    }

    @Test
    public void syncOffAndFailureAreTheirOwnStates() {
        storeLastReport(KeepADBPreferences.WEBHOOK_STATUS_FAILED, HOST + ":" + PORT);
        KeepADBOverviewContent failed = content(false);
        assertEquals(name(R.string.overview_delivery_failed), failed.delivery.value);
        assertEquals(Click.SETUP_WEBHOOK, failed.deliveryClick);

        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBOverviewContent off = content(false);
        assertEquals(name(R.string.overview_delivery_sync_off), off.delivery.value);
        assertEquals(Click.OPEN_APP, off.deliveryClick);
    }

    @Test
    public void withoutAnyDeliveryTheHistorySaysSo() {
        assertEquals(name(R.string.overview_last_delivery_none), content(false).lastDelivery);
    }

    // -- privacy ------------------------------------------------------------------------------------

    @Test
    public void hiddenDetailsShowNoAddressPortOrNetworkNameAndNeverTheUrl() {
        for (boolean global : new boolean[] {false, true}) {
            KeepADBPreferences.setWidgetPrivacyEnabled(context, global);
            boolean perWidget = !global;
            for (Shape shape : Shape.values()) {
                String text = allText(render(shape, perWidget));
                assertFalse(shape + ": host", text.contains(HOST));
                assertFalse(shape + ": port", text.contains(String.valueOf(PORT)));
                assertFalse(shape + ": Wi-Fi name", text.contains(SSID));
                assertFalse(shape + ": URL", text.contains("register.example"));
                assertTrue(shape + ": masked", text.contains(KeepADBOverviewContent.HIDDEN));
            }
        }
        // Visible by default, and the URL never at all.
        KeepADBPreferences.setWidgetPrivacyEnabled(context, false);
        String visible = allText(render(Shape.WIDE, false));
        assertTrue(visible.contains(SSID));
        assertFalse(visible.contains("register.example"));
    }

    @Test
    public void hiddenDetailsDoNotEvenReadTheWifiName() {
        assertNull(projection(true).wifiName);
        assertTrue(projection(true).wifiConnected);
    }

    // -- accessibility and shapes -------------------------------------------------------------------

    @Test
    public void everyStateHasWordsForTalkBackAndTheCardSpeaksForItsRows() {
        for (Shape shape : Shape.values()) {
            View root = render(shape, false);
            CharSequence card = root.findViewById(R.id.widget_ov_card).getContentDescription();
            assertNotNull(card);
            assertTrue(card.toString().contains(name(R.string.widget_action_adb)));
            assertTrue(card.toString().contains(name(R.string.overview_label_wifi)));
            assertTrue(card.toString().contains(name(R.string.widget_action_keep_alive)));
        }
        View wide = render(Shape.WIDE, false);
        assertNotNull(wide.findViewById(R.id.widget_ov_delivery).getContentDescription());
    }

    @Test
    public void theShapeFollowsTheWidthAndGrowsWithTheFontSize() {
        assertEquals(Shape.COMPACT, KeepADBOverviewViews.choose(144f, 1f));
        assertEquals(Shape.WIDE, KeepADBOverviewViews.choose(296f, 1f));
        assertEquals(Shape.COMPACT, KeepADBOverviewViews.choose(296f, 1.3f));
        assertEquals(Shape.WIDE, KeepADBOverviewViews.choose(330f, 1.3f));
    }

    @Test
    public void controlsKeepTheirTouchTargets() {
        for (Shape shape : Shape.values()) {
            View root = render(shape, false);
            assertTrue(root.findViewById(R.id.widget_ov_card).getHeight() >= 48);
            assertTrue(root.findViewById(R.id.widget_ov_sync).getHeight() >= 48);
        }
    }

    // -- lifecycle ----------------------------------------------------------------------------------

    @Test
    public void overviewAndControlInstancesStayConsistentAndIndependent() {
        int control = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        int overview = widgets.createWidget(KeepADBOverviewWidget.class,
                R.layout.widget_overview_compact);
        int hidden = widgets.createWidget(KeepADBOverviewWidget.class,
                R.layout.widget_overview_compact);
        KeepADBWidgetConfig.save(context, hidden, KeepADBWidgetConfig.of(null, null, true));
        KeepADBWidget.refreshAllState(context);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(allText(widgets.getViewFor(overview)).contains(HOST + ":" + PORT));
        assertFalse("the per-widget option hides only its own widget",
                allText(widgets.getViewFor(hidden)).contains(HOST));
        assertTrue(allText(widgets.getViewFor(control)).contains(String.valueOf(PORT)));

        // One state change reaches both kinds through the shared refresh.
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBWidget.refreshAllState(context);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(allText(widgets.getViewFor(overview)).contains(name(R.string.tile_state_off)));
        assertFalse(allText(widgets.getViewFor(control)).contains(String.valueOf(PORT)));

        // Saving through the configure path redraws the right kind.
        KeepADBWidget.refresh(context, overview);
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(descendantWithText(widgets.getViewFor(overview),
                name(R.string.tile_state_off)));
    }

    @Test
    public void removingTheLastWidgetOfOneKindKeepsTheOtherKindsSettings() {
        // No widget of either kind is left: the cleanup runs and removes every entry.
        KeepADBWidgetConfig.save(context, 99, KeepADBWidgetConfig.of(null, null, true));
        new KeepADBOverviewWidget().onDisabled(context);
        assertFalse(KeepADBWidgetConfig.isStored(context, 99));

        // The last control widget is gone while an overview widget is placed: its option stays.
        int overview = widgets.createWidget(KeepADBOverviewWidget.class,
                R.layout.widget_overview_compact);
        KeepADBWidgetConfig.save(context, overview, KeepADBWidgetConfig.of(null, null, true));
        new KeepADBWidget().onDisabled(context);
        assertTrue("the overview keeps its option", KeepADBWidgetConfig.isStored(context, overview));
        assertTrue(KeepADBWidget.anyPlaced(context));
    }

    @Test
    public void renderingAndResizingNeverSendOrPoll() {
        int id = widgets.createWidget(KeepADBOverviewWidget.class, R.layout.widget_overview_compact);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();
        transport.recordedRequests.clear();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        android.os.Bundle options = new android.os.Bundle();
        for (int width : new int[] {110, 180, 250, 330}) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
            new KeepADBOverviewWidget().onAppWidgetOptionsChanged(context, manager, id, options);
        }
        KeepADBWidget.refreshAllState(context);
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals(0, transport.getRequestCount());
        assertFalse(KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
    }
}
