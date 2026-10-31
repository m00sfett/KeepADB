package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * Issue #548: the VPN transport row is a debug-only diagnostic. A release build must not show a
 * #538 VPN transport row on {@code MainActivity} even while ADB is verified over the VPN; a debug
 * build keeps showing it. Unit tests here always execute under the debug variant's
 * applicationId, so the real package-name check alone cannot exercise the release path -- this
 * test drives {@link KeepADBBuildFlags#setOverrideForTesting} directly instead.
 *
 * <p>#877: the VPN address is deliberately outside 100.64.0.0/10 -- any VPN product counts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityVpnBuildVariantTest {

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
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBEndpointCoordinator.resetForTesting();
        setNotificationStatic("currentHost", null);
        setNotificationStatic("currentPort", 0);
        setNotificationStatic("currentEndpointVerifiedAtMs", 0L);
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        KeepADB.resetForTesting();
    }

    @Test
    public void releaseBuildHidesTheVerifiedVpnTransportRow() throws Exception {
        KeepADBBuildFlags.setOverrideForTesting(false);
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("10.8.0.2");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals("release build must not add a VPN transport row",
                0, panel.getChildCount());
        assertEquals(View.GONE, panel.getVisibility());
    }

    @Test
    public void debugBuildShowsTheVerifiedVpnTransportRow() throws Exception {
        KeepADBBuildFlags.setOverrideForTesting(true);
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("10.8.0.2");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        MainActivity activity = launch();
        ViewGroup panel = transportOverviewPanel(activity);
        awaitSnapshotApplied(activity);

        assertEquals("debug build must keep the verified VPN transport row",
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
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Same reflective construction as {@code MainActivityTransportOverviewTest#addVpnNetwork}:
     * the compile-time Android stub jar exposes neither {@link LinkAddress}'s {@code String}
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
