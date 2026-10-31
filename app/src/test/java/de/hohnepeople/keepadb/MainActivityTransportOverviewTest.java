package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Pins what the #538 transport panel is allowed to say about a VPN: it only speaks once its own
 * ADB verification succeeded. These tests fail if a merely active VPN ever grows a row here
 * again. #877: no VPN product is named or detected; any VPN address counts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTransportOverviewTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        clearPreferences();
    }

    @After
    public void tearDown() throws Exception {
        clearPreferences();
        KeepADBEndpointCoordinator.resetForTesting();
        setNotificationStatic("currentHost", null);
        setNotificationStatic("currentPort", 0);
        setNotificationStatic("currentEndpointVerifiedAtMs", 0L);
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        KeepADB.resetForTesting();
    }

    /** A live VPN address whose ADB port does not answer. */
    @Test
    public void vpnActiveButAdbUnverified_rendersNoTransportRowAtAll() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> false);

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals("An unverified VPN must not add a row", 0, panel.getChildCount());
        assertEquals(View.GONE, panel.getVisibility());
    }

    /** Any VPN product is treated alike: unverified means no row, whatever the address range. */
    @Test
    public void otherVpnAddressButAdbUnverified_rendersNoTransportRow() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("10.8.0.5");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> false);

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals(0, panel.getChildCount());
        assertEquals(View.GONE, panel.getVisibility());
    }

    /**
     * The counterpart: once ADB really answers over the VPN address, the panel does show the
     * endpoint under the product-neutral "ADB via VPN" label (#877).
     */
    @Test
    public void vpnAdbVerified_rendersAnAdbLabelledEndpointRow() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("10.8.0.5");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals(1, panel.getChildCount());
        assertEquals(View.VISIBLE, panel.getVisibility());
        String row = ((TextView) panel.getChildAt(0)).getText().toString();
        String label = context.getString(R.string.transport_vpn_label);
        assertTrue("The row must carry the ADB-verified transport label: " + row,
                row.contains(label));
        assertTrue("The row must carry the verified endpoint: " + row, row.contains("10.8.0.5"));
        assertFalse("No VPN product is named: " + row, row.toLowerCase(java.util.Locale.ROOT).contains("tailscale"));
    }

    /**
     * Launches the activity and then drives one more {@code renderTransportOverview()} pass
     * explicitly. The activity's own lifecycle also calls {@code KeepADBEndpointCoordinator.refresh()},
     * which -- with wireless debugging off on this virtual device -- drops the cached endpoint
     * again. Re-seeding and re-rendering here keeps the scenario under test intact while still
     * going through the real, private render path rather than a hand-built snapshot.
     */
    private MainActivity launch() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        Method render = MainActivity.class.getDeclaredMethod("renderTransportOverview");
        render.setAccessible(true);
        render.invoke(activity);
        return activity;
    }

    private ViewGroup transportOverviewPanel(MainActivity activity) {
        ViewGroup panel = activity.findViewById(R.id.transport_overview_panel);
        assertNotNull(panel);
        return panel;
    }

    /**
     * A VPN address makes {@code KeepADBTransportOverview.currentAsync} hop to a real
     * background thread, so the snapshot lands after {@code setup()} returns. Waiting for a fixed
     * duration would only hide that; instead this waits for the one side effect that proves the
     * snapshot was applied: {@code updateEndpointPrimaryAccessibility} marks the verified WLAN
     * endpoint as the primary transport for screen readers, which nothing else does. Every test
     * here seeds a verified WLAN endpoint, so this is always the expected outcome.
     */
    private void awaitSnapshotApplied(MainActivity activity) throws Exception {
        TextView endpoint = activity.findViewById(R.id.endpoint);
        assertNotNull(endpoint);
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle();
            if (endpoint.getContentDescription() != null) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Transport snapshot was never applied to the activity");
    }

    private void clearPreferences() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void setWlanEndpoint(String host, int port, long verifiedAtMs) throws Exception {
        setNotificationStatic("currentHost", host);
        setNotificationStatic("currentPort", port);
        setNotificationStatic("currentEndpointVerifiedAtMs", verifiedAtMs);
    }

    private static void setNotificationStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Same reflective construction as {@code KeepADBTransportOverviewTest#addVpnNetwork}: the
     * compile-time Android stub jar exposes neither {@link LinkAddress}'s {@code String}
     * constructor nor {@code LinkProperties.addLinkAddress}. */
    private void addVpnNetwork(String ipv4) throws Exception {
        KeepADBVpnTestSupport.deliverVpnNetwork(context, 9001, linkPropertiesWithIpv4(ipv4));
    }

    private static LinkProperties linkPropertiesWithIpv4(String ipv4) throws Exception {
        Constructor<LinkAddress> constructor = LinkAddress.class.getConstructor(String.class);
        LinkAddress address = constructor.newInstance(ipv4 + "/32");
        LinkProperties properties = new LinkProperties();
        Method addLinkAddress = LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class);
        addLinkAddress.invoke(properties, address);
        return properties;
    }
}
