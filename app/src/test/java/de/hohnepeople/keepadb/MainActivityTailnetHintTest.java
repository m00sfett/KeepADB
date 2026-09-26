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
        KeepADB.resetForTesting();
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
}
