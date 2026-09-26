package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * Issue #548: Tailscale stays a debug-only beta diagnostic. A release build must show neither
 * the #537 Tailscale status card nor a #538 Tailscale/VPN transport row on {@code MainActivity},
 * even while both signals independently say Tailscale is active/verified; a debug build keeps
 * showing both, unchanged. Unit tests here always execute under the debug variant's
 * applicationId, so the real package-name check alone cannot exercise the release path -- this
 * test drives {@link KeepADBBuildFlags#setOverrideForTesting} directly instead, which is exactly
 * the seam #548 requires so the release-hides-Tailscale behavior is proven by an automated test
 * rather than incidentally passing because of the test build's own package name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTailscaleBuildVariantTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        clearPreferences();
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((ctx, packageName) -> true);
        // Realistic Android shape (#581): a tun0 interface with a CGNAT address, not the
        // Linux-style tailscale0 name.
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> java.util.Collections.singletonList(
                new KeepADBTailscaleStatus.InterfaceSnapshot("tun0", true, new byte[]{100, 101, 2, 3})));
    }

    @After
    public void tearDown() throws Exception {
        clearPreferences();
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBNotification.resetForTesting();
        setNotificationStatic("currentHost", null);
        setNotificationStatic("currentPort", 0);
        setNotificationStatic("currentEndpointVerifiedAtMs", 0L);
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting(null);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(null);
        KeepADB.resetForTesting();
    }

    @Test
    public void releaseBuildHidesTailscaleStatusAndTransportRowEvenWhenBothAreActive()
            throws Exception {
        KeepADBBuildFlags.setOverrideForTesting(false);
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        MainActivity activity = launch();
        TextView tailscaleStatus = activity.findViewById(R.id.tailscale_status);
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals("release build must hide the Tailscale status card",
                View.GONE, tailscaleStatus.getVisibility());
        assertEquals("release build must not add a Tailscale/VPN transport row",
                0, panel.getChildCount());
        assertEquals(View.GONE, panel.getVisibility());
    }

    @Test
    public void debugBuildKeepsShowingTailscaleStatusAndTransportRow() throws Exception {
        KeepADBBuildFlags.setOverrideForTesting(true);
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        MainActivity activity = launch();
        TextView tailscaleStatus = activity.findViewById(R.id.tailscale_status);
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals("debug build must keep showing the Tailscale status card",
                View.VISIBLE, tailscaleStatus.getVisibility());
        assertEquals("debug build must keep the verified Tailscale/VPN transport row",
                1, panel.getChildCount());
        assertEquals(View.VISIBLE, panel.getVisibility());
    }

    private MainActivity launch() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        Method render = MainActivity.class.getDeclaredMethod("renderTransportOverview");
        render.setAccessible(true);
        render.invoke(activity);
        Method renderStatus = MainActivity.class.getDeclaredMethod("renderTailscaleStatus");
        renderStatus.setAccessible(true);
        renderStatus.invoke(activity);
        return activity;
    }

    private ViewGroup transportOverviewPanel(MainActivity activity) {
        ViewGroup panel = activity.findViewById(R.id.transport_overview_panel);
        assertNotNull(panel);
        return panel;
    }

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

    /** Same reflective construction as {@code MainActivityTransportOverviewTest#addVpnNetwork}:
     * the compile-time Android stub jar exposes neither {@link LinkAddress}'s {@code String}
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
