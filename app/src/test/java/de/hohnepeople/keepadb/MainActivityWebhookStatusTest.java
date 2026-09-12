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

    @Test
    public void webhookStatusUsesMaskedUrlForIpv4() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://100.111.111.21:50829/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView webhookStatus = activity.findViewById(R.id.webhook_status);
        assertNotNull(webhookStatus);
        String text = webhookStatus.getText().toString();

        assertTrue("Expected masked URL in status text: " + text,
                text.contains("http://100.111.***.**:50829/register/s20"));
        assertFalse("Raw IP should not be exposed in status text: " + text,
                text.contains("100.111.111.21"));
    }

    @Test
    public void webhookStatusNeverExposesCredentialsEvenIfStored() {
        Context context = RuntimeEnvironment.getApplication();
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
        assertTrue("Expected masked URL in status text: " + text,
                text.contains("http://100.111.***.**:50829/register/s20"));
    }

    @Test
    public void failedUsbWebhookStatusIsVisibleAlongsideWlanStatus() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setRegisterWebhookUrl(context, "https://register.example/device");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setUsbWebhookLastReportStatus(
                context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView usbStatus = activity.findViewById(R.id.webhook_usb_status);
        assertNotNull(usbStatus);
        assertEquals(View.VISIBLE, usbStatus.getVisibility());
        assertEquals(activity.getString(R.string.webhook_status_usb_failed),
                usbStatus.getText().toString());
    }
}
