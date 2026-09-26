package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

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
 * Verifies MainActivity's rendering of the optional Tailscale status card (#537) for the four
 * acceptance-criteria states, driven through {@link KeepADBTailscaleStatus}'s test seams so no
 * real Tailscale installation or {@code tailscale0} interface is needed on the test machine.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTailscaleStatusTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting(null);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(null);
    }

    @Test
    public void hiddenWhenTailscaleNotInstalled() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> false);

        TextView tailscaleStatus = launchAndGetTailscaleStatusView();

        assertEquals(View.GONE, tailscaleStatus.getVisibility());
    }

    @Test
    public void visibleAndActiveWhenInstalledAndInterfaceUp() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        // Realistic Android shape (#581): Tailscale's VpnService tunnel is named tun0, not
        // tailscale0, and carries a CGNAT address.
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> java.util.Collections.singletonList(
                new KeepADBTailscaleStatus.InterfaceSnapshot("tun0", true, new byte[]{100, 101, 2, 3})));

        TextView tailscaleStatus = launchAndGetTailscaleStatusView();

        assertEquals(View.VISIBLE, tailscaleStatus.getVisibility());
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.tailscale_status_active),
                tailscaleStatus.getText().toString());
    }

    @Test
    public void visibleAndInactiveWhenInstalledButInterfaceNotActive() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(java.util.Collections::emptyList);

        TextView tailscaleStatus = launchAndGetTailscaleStatusView();

        assertEquals(View.VISIBLE, tailscaleStatus.getVisibility());
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.tailscale_status_inactive),
                tailscaleStatus.getText().toString());
    }

    @Test
    public void visibleAndUnknownWhenDetectionUnreliable() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> null);

        TextView tailscaleStatus = launchAndGetTailscaleStatusView();

        assertEquals(View.VISIBLE, tailscaleStatus.getVisibility());
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.tailscale_status_unknown),
                tailscaleStatus.getText().toString());
    }

    private TextView launchAndGetTailscaleStatusView() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        TextView tailscaleStatus = activity.findViewById(R.id.tailscale_status);
        assertNotNull(tailscaleStatus);
        return tailscaleStatus;
    }
}
