package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #799: the Networks list opened with {@link NetworkListActivity#EXTRA_FOCUS_BSSID} marks that
 * access point's row (selected state) and scrolls it into view; opened without the extra, or with
 * an address the list does not know, nothing is marked and nothing scrolls.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetworkListDeepLinkTest {

    private static final String HOME = "Heimnetz";
    private static final String CURRENT_AP = "aa:bb:cc:11:22:33";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        connect(HOME, CURRENT_AP);
        KeepADBTrustedNetwork.addBssid(context, CURRENT_AP, HOME);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void theExtraMarksTheRowOfThatAccessPointAndScrollsItIntoView() {
        String target = addNetworks(12);

        NetworkListActivity activity = open(NetworkListActivity.intent(context, target));

        List<View> marked = selectedViews(activity);
        assertEquals("exactly one row is marked", 1, marked.size());
        assertTrue(marked.get(0).getContentDescription().toString().toLowerCase(Locale.ROOT)
                .contains(target));
        ScrollView scroll = activity.findViewById(R.id.network_list_scroll);
        assertTrue("the marked row is below the first screen, so the list had to scroll: "
                + scroll.getScrollY(), scroll.getScrollY() > 0);
        assertTrue("the marked row starts inside the visible part",
                top(scroll, marked.get(0)) >= scroll.getScrollY());
    }

    /** Control: same list, no extra -- nothing marked, scroll position untouched. */
    @Test
    public void withoutTheExtraNothingIsMarkedAndTheListStaysAtTheTop() {
        addNetworks(12);

        NetworkListActivity activity = open(NetworkListActivity.intent(context));

        assertTrue(selectedViews(activity).isEmpty());
        assertEquals(0, ((ScrollView) activity.findViewById(R.id.network_list_scroll)).getScrollY());
    }

    /** Control: an address the list does not hold behaves like no extra. */
    @Test
    public void anUnknownAddressMarksNothingAndDoesNotScroll() {
        addNetworks(12);

        NetworkListActivity activity = open(
                NetworkListActivity.intent(context, "de:ad:be:ef:00:01"));

        assertTrue(selectedViews(activity).isEmpty());
        assertEquals(0, ((ScrollView) activity.findViewById(R.id.network_list_scroll)).getScrollY());
    }

    @Test
    public void aRowFoldedAwayByShowMoreIsUnfoldedAndMarked() {
        String last = null;
        for (int i = 0; i < NetworkListRenderer.COLLAPSE_ABOVE + 3; i++) {
            last = String.format(Locale.ROOT, "12:34:56:78:9a:%02x", i);
            KeepADBTrustedNetwork.addBssid(context, last, "Cafe Sonne");
        }

        NetworkListActivity folded = open(NetworkListActivity.intent(context));
        assertFalse("Control: the last row is folded away without the extra",
                everythingDescribed(folded).contains(last));

        NetworkListActivity activity = open(NetworkListActivity.intent(context, last));
        List<View> marked = selectedViews(activity);
        assertEquals(1, marked.size());
        assertTrue(marked.get(0).getContentDescription().toString().toLowerCase(Locale.ROOT)
                .contains(last));
    }

    /** The mark is for the opening only; a later redraw (resume, privacy eye) does not repeat it. */
    @Test
    public void theMarkIsNotRepeatedByALaterRedraw() {
        String target = addNetworks(4);
        org.robolectric.android.controller.ActivityController<NetworkListActivity> controller =
                Robolectric.buildActivity(NetworkListActivity.class,
                        NetworkListActivity.intent(context, target)).setup();
        ShadowLooper.idleMainLooper();
        assertEquals(1, selectedViews(controller.get()).size());

        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertTrue(selectedViews(controller.get()).isEmpty());
    }

    @Test
    public void theIntentFactoryOmitsTheExtraForNoAddress() {
        assertNull(NetworkListActivity.intent(context, null)
                .getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
        assertNull(NetworkListActivity.intent(context, "")
                .getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
        assertEquals("aa:bb", NetworkListActivity.intent(context, "aa:bb")
                .getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
    }

    // --- Helpers -----------------------------------------------------------------------------

    /** Saves {@code count} access points, each under its own name; returns the last one's BSSID. */
    private String addNetworks(int count) {
        String last = null;
        for (int i = 0; i < count; i++) {
            last = String.format(Locale.ROOT, "12:34:56:78:9a:%02x", i);
            KeepADBTrustedNetwork.addBssid(context, last, "Netz " + (char) ('A' + i));
        }
        return last;
    }

    private NetworkListActivity open(Intent intent) {
        NetworkListActivity activity = Robolectric.buildActivity(NetworkListActivity.class, intent)
                .setup().get();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private void connect(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private static int top(ScrollView scroll, View view) {
        android.graphics.Rect rect = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight());
        scroll.offsetDescendantRectToMyCoords(view, rect);
        return rect.top;
    }

    private static List<View> selectedViews(NetworkListActivity activity) {
        List<View> out = new ArrayList<>();
        collectSelected(activity.findViewById(R.id.networks_root), out);
        return out;
    }

    private static void collectSelected(View view, List<View> out) {
        // A selected row propagates the state to its children; only the row itself is wanted.
        if (view.isSelected() && view.isClickable()) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectSelected(group.getChildAt(i), out);
        }
    }

    private static String everythingDescribed(NetworkListActivity activity) {
        StringBuilder out = new StringBuilder();
        describe(activity.findViewById(R.id.networks_root), out);
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static void describe(View view, StringBuilder out) {
        if (view.getContentDescription() != null) out.append(view.getContentDescription()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) describe(group.getChildAt(i), out);
        }
    }
}
