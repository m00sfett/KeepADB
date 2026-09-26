package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.widget.Switch;

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
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #577: the Keep-Alive toggle's immediate "turn it on right now" side effect must respect the same
 * trust/WLAN guard the automatic re-enable paths already use ({@link
 * KeepADBService#isAutoEnableStillPermitted}) instead of writing {@code adb_wifi_enabled}
 * unconditionally like a manual override. "Keep-Alive ON" means "keep it alive wherever that's
 * permitted", not "switch it on here regardless of trust" (user decision, Variante A).
 *
 * <p>The main switch, the tile and the widget stay manual overrides without this gate (#245) -- the
 * second test below is the counter-proof that the main switch is untouched by this change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityKeepAliveTrustGateTest {

    private static final String SSID = "UntrustedCafeWifi";
    private static final String BSSID = "aa:bb:cc:dd:ee:10";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    /**
     * Acceptance criterion 1 / criterion 2 (red without the fix): on an untrusted network, turning
     * Keep-Alive on must not write {@code adb_wifi_enabled} immediately -- the existing trust-prompt
     * path takes over instead, exactly like {@code KeepADBService#recheckAndEnable} already does.
     */
    @Test
    public void keepAliveToggleDoesNotEnableImmediatelyOnUntrustedNetwork() {
        Context context = grantAutoEnableInfrastructure();
        // #245/#492: MODE_ALLOWLIST with no entries at all is the untrusted case -- the default
        // MODE_ALL_WIFI would trust unconditionally and defeat this test.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo(SSID, BSSID);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        assertFalse("Precondition: Keep-Alive starts off", keepAliveToggle.isChecked());

        keepAliveToggle.performClick();

        assertTrue("The preference itself must still flip on",
                KeepADBPreferences.isKeepAliveEnabled(activity));
        assertFalse("Wireless Debugging must NOT be switched on immediately on an untrusted "
                        + "network -- the trust-prompt path must take over instead",
                KeepADB.isEnabled(activity));
    }

    /**
     * Counter-proof #1 (acceptance criterion 3): the main switch is a manual override and keeps
     * writing immediately, without any trust gate, on the very same untrusted network.
     */
    @Test
    public void mainToggleStillEnablesImmediatelyOnUntrustedNetwork() {
        Context context = grantAutoEnableInfrastructure();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo(SSID, BSSID);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        Switch mainToggle = activity.findViewById(R.id.toggle);
        assertFalse("Precondition: Wireless Debugging starts off", mainToggle.isChecked());

        mainToggle.performClick();

        assertTrue("The main switch must keep enabling immediately regardless of trust -- it is "
                        + "a manual override (#245), never gated by the trust policy",
                KeepADB.isEnabled(activity));
    }

    /**
     * Counter-proof #2 (acceptance criterion 3): on a trusted network (or with the allowlist
     * inactive, i.e. the default MODE_ALL_WIFI), Keep-Alive ON must keep enabling immediately --
     * this change must not regress the common case.
     */
    @Test
    public void keepAliveToggleStillEnablesOnTrustedNetwork() {
        Context context = grantAutoEnableInfrastructure();
        // Default mode (MODE_ALL_WIFI): trusted unconditionally, i.e. "allowlist not active".
        connectTo(SSID, BSSID);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        assertFalse("Precondition: Keep-Alive starts off", keepAliveToggle.isChecked());

        keepAliveToggle.performClick();

        assertTrue("The preference itself must flip on",
                KeepADBPreferences.isKeepAliveEnabled(activity));
        assertTrue("Wireless Debugging must still be switched on immediately on a trusted network",
                KeepADB.isEnabled(activity));
    }

    /**
     * Shared setup mirroring {@code MainActivityTrustedNetworkTest#grantAutoEnableForTesting}:
     * grants {@code WRITE_SECURE_SETTINGS}, forces the Wi-Fi-connectivity check to report
     * connected, and installs a gateway that starts switched off.
     */
    private Context grantAutoEnableInfrastructure() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        return context;
    }

    private void connectTo(String ssid, String bssid) {
        Context context = RuntimeEnvironment.getApplication();
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
