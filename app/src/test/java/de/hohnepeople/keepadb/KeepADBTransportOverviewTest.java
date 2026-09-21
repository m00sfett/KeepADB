package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * End-to-end aggregation coverage for #538's four required transport scenarios (only WLAN; WLAN
 * + Tailscale both verified; VPN active but ADB not reachable there; USB only) plus the
 * privacy-masking states of the values {@link KeepADBTransportOverview} hands to the UI.
 *
 * <p>The Tailscale-range address itself is never really dialed here: a real socket connect to an
 * artificial 100.64.0.0/10 address cannot be exercised deterministically in a sandboxed test run
 * (there is no such local interface to bind), so {@link KeepADBVpnTransport}'s reachability
 * probe is substituted via its test seam. {@code addVpnNetwork} below still drives the real
 * {@link ConnectivityManager}/{@link NetworkCapabilities}/{@link LinkProperties} detection path
 * that decides whether a VPN network counts as Tailscale in the first place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTransportOverviewTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() throws Exception {
        KeepADBNotification.resetForTesting();
        setNotificationStatic("currentHost", null);
        setNotificationStatic("currentPort", 0);
        setNotificationStatic("currentEndpointVerifiedAtMs", 0L);
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        setUsbAdbConnected(false);
    }

    /** Acceptance criterion: "Nur WLAN vorhanden: der verifizierte WLAN-Endpunkt wird angezeigt." */
    @Test
    public void onlyWlanVerified_showsSingleWlanTransportAsPrimary() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertEquals(1, snapshot.transports.size());
        KeepADBTransportEndpoint wlan = snapshot.transports.get(0);
        assertEquals(KeepADBTransportEndpoint.Type.WLAN_LAN, wlan.type);
        assertEquals("192.168.1.50", wlan.host);
        assertEquals(40000, wlan.port);
        assertEquals(1_700_000_000_000L, wlan.verifiedAtMs);
        assertTrue("The only verified transport must be primary", wlan.primary);
        assertFalse(snapshot.vpnActiveNotAdbVerified);
    }

    /** Acceptance criterion: "WLAN und Tailscale vorhanden: beide werden getrennt angezeigt,
     * aber nur wenn beide tatsächlich verifiziert sind." */
    @Test
    public void wlanAndTailscaleBothVerified_areListedSeparatelyWithWlanPrimary() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertEquals(2, snapshot.transports.size());
        assertEquals(KeepADBTransportEndpoint.Type.WLAN_LAN, snapshot.transports.get(0).type);
        assertTrue("WLAN/LAN outranks Tailscale/VPN as primary", snapshot.transports.get(0).primary);
        KeepADBTransportEndpoint tailscale = snapshot.transports.get(1);
        assertEquals(KeepADBTransportEndpoint.Type.TAILSCALE_VPN, tailscale.type);
        assertEquals("100.101.2.3", tailscale.host);
        // adbd listens on the same port across every one of the device's own interfaces, so the
        // WLAN-discovered port is reused as the Tailscale-side probe target.
        assertEquals(40000, tailscale.port);
        assertFalse("A non-primary transport must not also claim primary", tailscale.primary);
        assertFalse(snapshot.vpnActiveNotAdbVerified);
    }

    /** Acceptance criterion: "VPN aktiv, ADB dort nicht erreichbar: VPN-Status kann separat
     * sichtbar sein, der VPN-Endpunkt wird nicht als ADB-Endpunkt behauptet." */
    @Test
    public void vpnActiveButAdbUnreachable_isNotSurfacedAsEndpoint() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> false);

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertEquals(1, snapshot.transports.size());
        assertEquals(KeepADBTransportEndpoint.Type.WLAN_LAN, snapshot.transports.get(0).type);
        for (KeepADBTransportEndpoint transport : snapshot.transports) {
            assertFalse("An unverified VPN interface must never appear as an ADB endpoint",
                    transport.type == KeepADBTransportEndpoint.Type.TAILSCALE_VPN);
        }
        assertTrue("The active-but-unverified VPN must still be surfaced as a status, "
                + "separately from the endpoint list", snapshot.vpnActiveNotAdbVerified);
    }

    /** A VPN whose address never falls in Tailscale's CGNAT range (a different VPN product, or
     * no address assigned yet) must never be treated as an ADB endpoint candidate either -- an
     * active VPN interface alone is never enough. */
    @Test
    public void genericNonTailscaleVpn_isNeverTreatedAsAnAdbEndpointCandidate() throws Exception {
        addVpnNetwork("10.8.0.5"); // outside 100.64.0.0/10

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertTrue(snapshot.transports.isEmpty());
        assertTrue("A non-Tailscale VPN is still worth a status line", snapshot.vpnActiveNotAdbVerified);
    }

    /** Acceptance criterion: "USB allein: USB-ADB wird als eigener aktiver Transport angezeigt,
     * ohne erfundenen Netzwerk-Endpunkt." */
    @Test
    public void usbOnly_isSurfacedAsPrimaryWithoutFabricatedNetworkEndpoint() {
        setUsbAdbConnected(true);

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertEquals(1, snapshot.transports.size());
        KeepADBTransportEndpoint usb = snapshot.transports.get(0);
        assertEquals(KeepADBTransportEndpoint.Type.USB, usb.type);
        assertNull("USB has no network endpoint to fabricate", usb.host);
        assertEquals(0, usb.port);
        assertFalse(usb.hasNetworkEndpoint());
        assertTrue("The only verified transport must be primary", usb.primary);
    }

    /** A USB cable merely being plugged in (connected extra without configured+adb) must not be
     * mistaken for a verified USB-ADB transport. */
    @Test
    public void usbCableWithoutAdbConfigured_isNotSurfacedAsAVerifiedTransport() {
        Intent intent = new Intent(KeepADBUsbReceiver.ACTION_USB_STATE);
        intent.putExtra("connected", true);
        intent.putExtra("configured", false);
        intent.putExtra("adb", false);
        context.sendStickyBroadcast(intent);

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertTrue(snapshot.transports.isEmpty());
    }

    @Test
    public void nothingVerifiedAtAll_yieldsAnEmptySnapshot() {
        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertTrue(snapshot.transports.isEmpty());
        assertFalse(snapshot.vpnActiveNotAdbVerified);
    }

    /** Privacy-mode masking (#483's rule) must apply to a Tailscale endpoint exactly like it
     * already applies to the WLAN/LAN one -- KeepADBTransportOverview hands back the raw host so
     * MainActivity's existing masking call sites (unchanged by #538) can mask it consistently;
     * this pins that the raw value survives the aggregation step unmasked, so masking stays a
     * single, display-only responsibility instead of being duplicated here. */
    @Test
    public void aggregatorHandsBackUnmaskedAddresses_maskingIsTheDisplayLayersJob() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);

        KeepADBTransportOverview.Snapshot snapshot = KeepADBTransportOverview.current(context);

        assertEquals("192.168.1.50", snapshot.transports.get(0).host);
        assertEquals("192.*.*.*", KeepADBAddressMask.maskHost(snapshot.transports.get(0).host));
    }

    /** {@link KeepADBTransportOverview#currentAsync} must resolve synchronously, on the calling
     * thread, whenever there is no Tailscale-range VPN address to probe -- the common case for
     * essentially every caller, and the property {@code MainActivity#renderTransportOverview()}
     * relies on to stay deterministic for callers unaware of this class (see its javadoc). */
    @Test
    public void currentAsyncResolvesSynchronouslyWithoutAnActiveTailscaleAddress() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        long callingThreadId = Thread.currentThread().getId();
        java.util.concurrent.atomic.AtomicLong deliveredOnThreadId = new java.util.concurrent.atomic.AtomicLong(-1);

        KeepADBTransportOverview.currentAsync(context,
                snapshot -> deliveredOnThreadId.set(Thread.currentThread().getId()));

        assertEquals("No background thread should be needed without a Tailscale-range address",
                callingThreadId, deliveredOnThreadId.get());
    }

    /** The counterpart of the test above: a real Tailscale-range address does dispatch the
     * blocking probe to a background thread, and the snapshot still arrives correctly once it
     * finishes. */
    @Test
    public void currentAsyncHopsToABackgroundThreadOnlyWhenATailscaleAddressExists() throws Exception {
        setWlanEndpoint("192.168.1.50", 40000, 1_700_000_000_000L);
        addVpnNetwork("100.101.2.3");
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);
        long callingThreadId = Thread.currentThread().getId();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong deliveredOnThreadId = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicReference<KeepADBTransportOverview.Snapshot> result =
                new java.util.concurrent.atomic.AtomicReference<>();

        KeepADBTransportOverview.currentAsync(context, snapshot -> {
            deliveredOnThreadId.set(Thread.currentThread().getId());
            result.set(snapshot);
            latch.countDown();
        });

        assertTrue("The worker must finish within a generous bound",
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse("A real Tailscale-range address must dispatch off the calling thread",
                callingThreadId == deliveredOnThreadId.get());
        assertEquals(2, result.get().transports.size());
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

    private void setUsbAdbConnected(boolean connected) {
        Intent intent = new Intent(KeepADBUsbReceiver.ACTION_USB_STATE);
        intent.putExtra("connected", connected);
        intent.putExtra("configured", connected);
        intent.putExtra("adb", connected);
        context.sendStickyBroadcast(intent);
    }

    /** See {@code KeepADBNetworkRobustnessBehaviorTest}/{@code KeepADBIpv6SynchronousFallbackTest}:
     * the compile-time Android stub jar exposes neither {@link LinkAddress}'s {@code String}
     * constructor nor {@code LinkProperties.addLinkAddress}, and Robolectric ships no shadow seam
     * for either, so both are driven reflectively against the real framework classes loaded at
     * test run time. */
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
