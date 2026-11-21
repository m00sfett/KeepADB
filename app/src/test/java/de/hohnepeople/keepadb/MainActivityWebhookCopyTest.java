package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
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

/** #1036: copy buttons for the webhook URL and the last reported endpoint on the home screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWebhookCopyTest {
    private static final String URL = "http://100.111.111.21:50829/register/s20";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

    private Context context;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport(true));
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void theUrlButtonCopiesTheStoredUrlCharacterForCharacterAndUnmasked() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        MainActivity activity = start();

        String shown = ((TextView) activity.findViewById(R.id.webhook_url_value)).getText().toString();
        assertFalse("the screen stays masked: " + shown, shown.contains("100.111.111.21"));
        View copy = activity.findViewById(R.id.webhook_url_copy);
        assertEquals(View.VISIBLE, copy.getVisibility());
        assertEquals(context.getString(R.string.webhook_copy_url_action), copy.getContentDescription());

        copy.performClick();

        ClipData clip = clipboard().getPrimaryClip();
        assertNotNull(clip);
        assertEquals(URL, clip.getItemAt(0).getText().toString());
        assertTrue("not flagged sensitive (#1036 decision)", clip.getDescription().getExtras() == null
                || !clip.getDescription().getExtras().containsKey(KeepADBClipboard.EXTRA_IS_SENSITIVE));
    }

    @Test
    public void theEndpointButtonCopiesHostAndPortOnly() {
        // Wifi-ADB on, so starting the screen does not deregister the reported endpoint.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookReportSnapshot(context, URL, "192.168.178.24:45149",
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        MainActivity activity = start();

        View copy = activity.findViewById(R.id.webhook_endpoint_copy);
        assertEquals("stored: " + KeepADBPreferences.getWebhookLastReportedEndpoint(context),
                View.VISIBLE, copy.getVisibility());
        copy.performClick();

        assertEquals("192.168.178.24:45149",
                clipboard().getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test
    public void anIpv6EndpointIsCopiedBracketed() {
        assertEquals("[fe80::1]:5555", KeepADBWebhookStatusText.copyableEndpoint(
                KeepADBEndpoint.formatEndpoint("fe80::1", 5555)));
        assertEquals(URL, KeepADBWebhookStatusText.copyableUrl(" " + URL + " "));
        assertNull(KeepADBWebhookStatusText.copyableEndpoint(""));
        assertNull(KeepADBWebhookStatusText.copyableUrl(null));
    }

    @Test
    public void withoutAReportedEndpointOnlyTheUrlCanBeCopied() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        MainActivity activity = start();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.webhook_url_copy).getVisibility());
        assertEquals("no icon next to none yet",
                View.GONE, activity.findViewById(R.id.webhook_endpoint_copy).getVisibility());
        assertTrue(((TextView) activity.findViewById(R.id.webhook_endpoint_value)).getText()
                .toString().endsWith(context.getString(R.string.webhook_status_no_endpoint)));
    }

    @Test
    public void notSetUpShowsTheHintAndNoCopyButtons() {
        MainActivity activity = start();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.webhook_status).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.webhook_status_details).getVisibility());
        assertFalse(activity.findViewById(R.id.webhook_url_copy).isShown());
        assertFalse(activity.findViewById(R.id.webhook_endpoint_copy).isShown());
    }

    private MainActivity start() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get();
    }

    private ClipboardManager clipboard() {
        return context.getSystemService(ClipboardManager.class);
    }
}
