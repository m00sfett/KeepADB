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
 * #561: the "Tailscale might be disconnected" hint must appear only for a webhook that is
 * currently failing AND whose configured URL looks tailnet-bound -- never for a merely unusual
 * host, never while the last report still succeeded, and it must clear again once a later report
 * succeeds. See {@link KeepADBTailnetHeuristicTest} for the classifier itself.
 *
 * <p>#875: it must also stay hidden while Tailscale is detected as active, and show for every
 * other status (inactive, unknown, not installed).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTailnetHintTest {

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
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting(null);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(null);
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBRegisterClient.clearRegisterStateListener();
        KeepADB.resetForTesting();
    }

    /** #875: Tailscale installed, with a tun interface that is up (with a CGNAT address) or not. */
    private static void tailscale(boolean tunnelUp) {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((ctx, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> java.util.Collections.singletonList(
                new KeepADBTailscaleStatus.InterfaceSnapshot("tun0", tunnelUp, new byte[]{100, 101, 2, 3})));
    }

    private MainActivity launchWithWebhook(String url, String reportStatus) {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setRegisterWebhookUrl(context, url);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportStatus(context, reportStatus);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get();
    }

    @Test
    public void hintShowsForFailedCgnatTarget() {
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
    }

    @Test
    public void hintShowsForFailedMagicDnsTarget() {
        MainActivity activity = launchWithWebhook("http://phone-register.tailxyz.ts.net/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
    }

    @Test
    public void hintStaysHiddenForFailedLanTarget() {
        MainActivity activity = launchWithWebhook("http://192.168.1.100:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals(View.GONE, hint.getVisibility());
    }

    @Test
    public void hintStaysHiddenWhenLastReportSucceededEvenForTailnetTarget() {
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals(View.GONE, hint.getVisibility());
    }

    @Test
    public void hintNeverLeaksTheConfiguredUrlOrHost() {
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        String text = hint.getText().toString();
        org.junit.Assert.assertFalse("Hint must not leak the host: " + text,
                text.contains("100.111.111.21"));
    }

    @Test
    public void hintStaysHiddenForFailedTailnetTargetWhileTailscaleIsActive() {
        tailscale(true);
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals("an active tunnel rules Tailscale out as the cause",
                View.GONE, hint.getVisibility());
    }

    @Test
    public void hintShowsForFailedTailnetTargetWhileTailscaleIsInactive() {
        tailscale(false);
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
    }

    @Test
    public void hintShowsForFailedTailnetTargetWhenTheTailscaleStatusIsUnknown() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((ctx, packageName) -> null);
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        assertNotNull(hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
    }

    /**
     * #875 review: Tailscale comes up while the screen is open, then a report completes. Only the
     * register listener fires; the hint and the Tailscale line must follow the same new status.
     */
    @Test
    public void reportCallbackRedrawsHintAndTailscaleLineFromOneStatus() throws Exception {
        KeepADBBuildFlags.setOverrideForTesting(true);
        tailscale(false);
        MainActivity activity = launchWithWebhook("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        TextView hint = activity.findViewById(R.id.webhook_tailnet_hint);
        TextView line = activity.findViewById(R.id.tailscale_status);
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertEquals(activity.getString(R.string.tailscale_status_inactive), line.getText().toString());

        tailscale(true);
        java.lang.reflect.Field field = KeepADBRegisterClient.class.getDeclaredField("registerStateListener");
        field.setAccessible(true);
        ((KeepADBRegisterClient.RegisterStateListener) field.get(null)).onRegisterStateChanged();

        assertEquals(View.GONE, hint.getVisibility());
        assertEquals(activity.getString(R.string.tailscale_status_active), line.getText().toString());
    }
}
