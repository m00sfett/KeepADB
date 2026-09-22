package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

/** Tests that MainActivity masks the webhook URL and never displays plaintext credentials (Issue #319). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWebhookStatusTest {

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

    /**
     * #550: privacy mode off is an explicit "show me everything" choice for the webhook display,
     * so the host is no longer masked at all -- unlike the pre-#550 behaviour, which still applied
     * the #350 default two-octet IPv4 mask here.
     */
    @Test
    public void webhookStatusShowsFullUrlWhenPrivacyModeIsOff() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://100.111.111.21:50829/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView webhookStatus = activity.findViewById(R.id.webhook_status);
        assertNotNull(webhookStatus);
        String text = webhookStatus.getText().toString();

        assertTrue("Expected the full URL in status text with privacy mode off: " + text,
                text.contains("http://100.111.111.21:50829/register/s20"));
    }

    /** #483: privacy mode on keeps masking the host down to its first IPv4 octet, unchanged. */
    @Test
    public void webhookStatusMasksUrlWhenPrivacyModeIsOn() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://100.111.111.21:50829/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView webhookStatus = activity.findViewById(R.id.webhook_status);
        assertNotNull(webhookStatus);
        String text = webhookStatus.getText().toString();

        assertTrue("Expected masked URL in status text with privacy mode on: " + text,
                text.contains("http://100.*.*.*:50829/register/s20"));
        assertFalse("Raw IP should not be exposed while privacy mode is on: " + text,
                text.contains("100.111.111.21"));
    }

    /**
     * Credentials and the fragment are secret material unrelated to the privacy toggle (#350/#378)
     * and must stay hidden in both toggle states -- even with privacy mode off, where the host
     * itself is now shown in full (#550).
     */
    @Test
    public void webhookStatusNeverExposesCredentialsEvenIfStored() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("register_webhook_url", "http://user:secret123@100.111.111.21:50829/register/s20#section")
                .putBoolean("register_webhook_enabled", true)
                .commit();

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView webhookStatus = activity.findViewById(R.id.webhook_status);
        assertNotNull(webhookStatus);
        String text = webhookStatus.getText().toString();

        assertFalse("Username must not be exposed: " + text, text.contains("user"));
        assertFalse("Password must not be exposed: " + text, text.contains("secret123"));
        assertFalse("Fragment must not be exposed: " + text, text.contains("#section"));
        assertTrue("Expected the full host in status text with privacy mode off: " + text,
                text.contains("http://100.111.111.21:50829/register/s20"));
    }
}
