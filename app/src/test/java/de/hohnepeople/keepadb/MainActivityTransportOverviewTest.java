package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * Pins what the #538 transport panel is allowed to say about Tailscale/VPN.
 *
 * <p>#537 already owns the "Tailscale is up" statement, derived from the installed package plus
 * the {@code tailscale0} interface. This panel derives its own view from the CGNAT range plus a
 * real ADB socket probe. Two independently derived answers to the same question can disagree, so
 * the panel is only allowed to speak once its own ADB verification succeeded -- which is exactly
 * the extra information it contributes. These tests fail if a merely active VPN ever grows a row
 * here again.
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
        // Tailscale's own #537 card is a separate surface; keep it out of this test's way.
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((ctx, packageName) -> false);
    }

    @After
    public void tearDown() throws Exception {
        clearPreferences();
        KeepADBNotification.resetForTesting();
        setNotificationStatic("currentHost", null);
        setNotificationStatic("currentPort", 0);
        setNotificationStatic("currentEndpointVerifiedAtMs", 0L);
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting(null);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(null);
        KeepADB.resetForTesting();
    }

    /**
     * The exact state that used to produce a duplicated, potentially contradicting Tailscale
     * line: a live Tailscale-range VPN address whose ADB port does not answer.
     */
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

    /** A non-Tailscale VPN is likewise no reason for this panel to say anything. */
    @Test
    public void genericVpnWithoutTailscaleRange_rendersNoTransportRow() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("10.8.0.5");

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals(0, panel.getChildCount());
        assertEquals(View.GONE, panel.getVisibility());
    }

    /**
     * The counterpart: once ADB really answers over the Tailscale address, the panel does show
     * the endpoint -- and its label states the ADB relationship rather than repeating #537's
     * "Tailscale: active" wording.
     */
    @Test
    public void tailscaleAdbVerified_rendersAnAdbLabelledEndpointRow() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals(1, panel.getChildCount());
        assertEquals(View.VISIBLE, panel.getVisibility());
        String row = ((TextView) panel.getChildAt(0)).getText().toString();
        String label = context.getString(R.string.transport_tailscale_label);
        assertTrue("The row must carry the ADB-verified transport label: " + row,
                row.contains(label));
        assertTrue("The row must carry the verified endpoint: " + row, row.contains("100.101.2.3"));
        assertTrue("The panel label must not be the plain #537 status wording",
                !label.equals(context.getString(R.string.tailscale_status_active)));
    }

    /**
     * Launches the activity and then drives one more {@code renderTransportOverview()} pass
     * explicitly. The activity's own lifecycle also calls {@code KeepADBNotification.refresh()},
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
     * A Tailscale-range address makes {@code KeepADBTransportOverview.currentAsync} hop to a real
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
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Same reflective construction as {@code KeepADBTransportOverviewTest#addVpnNetwork}: the
     * compile-time Android stub jar exposes neither {@link LinkAddress}'s {@code String}
     * constructor nor {@code LinkProperties.addLinkAddress}. */
    private void addVpnNetwork(String ipv4) throws Exception {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        Network vpnNetwork = ShadowNetwork.newInstance(9001);
        NetworkInfo vpnNetworkInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, true);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);

        shadowConnectivityManager.addNetwork(vpnNetwork, vpnNetworkInfo);
        shadowConnectivityManager.setNetworkCapabilities(vpnNetwork, capabilities);
        shadowConnectivityManager.setLinkProperties(vpnNetwork, linkPropertiesWithIpv4(ipv4));
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
