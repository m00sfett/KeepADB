package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #904: the screens of the app that show a webhook address or the shared diagnostics follow
 * the app privacy switch; a deliberate "Show" reveals one screen until it stops. Each test has its counter-check with the switch off, so a mask applied unconditionally
 * (or a "Show" that does nothing) cannot pass.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAppPrivacySurfacesTest {
    private static final String WEBHOOK = "http://100.111.111.21:50829/register/s20";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBPreferences.setAppLanguage(context, "en");
    }

    // --- Webhook field ---------------------------------------------------------------------------

    @Test
    public void theWebhookFieldIsMaskedWithShowWhileTheAppSwitchIsOn() {
        KeepADBPreferences.setRegisterWebhookUrl(context, WEBHOOK);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        EditText field = activity.findViewById(R.id.settings_webhook_url);
        TextView masked = activity.findViewById(R.id.settings_webhook_url_masked);
        assertEquals("the editable field is not shown", View.GONE, field.getVisibility());
        assertEquals(View.VISIBLE, masked.getVisibility());
        assertTrue(masked.getText().toString(), masked.getText().toString().contains("100.*.*.*"));
        String shown = visibleText(activity.getWindow().getDecorView());
        assertFalse("no visible view holds the host: " + shown, shown.contains("111.111.21"));

        activity.findViewById(R.id.settings_webhook_url_show).performClick();
        assertEquals(View.VISIBLE, field.getVisibility());
        assertEquals(WEBHOOK, field.getText().toString());
        assertEquals(View.GONE, masked.getVisibility());
        assertTrue("Show leaves the stored switch alone", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals("and the stored URL", WEBHOOK, KeepADBPreferences.getRegisterWebhookUrl(context));
        controller.pause().stop().destroy();
    }

    @Test
    public void theWebhookFieldIsPlainWhileTheAppSwitchIsOff() {
        KeepADBPreferences.setRegisterWebhookUrl(context, WEBHOOK);

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_webhook_url).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.settings_webhook_url_masked).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.settings_webhook_url_show).getVisibility());
        assertEquals(WEBHOOK,
                ((EditText) activity.findViewById(R.id.settings_webhook_url)).getText().toString());
    }

    @Test
    public void savingWhileTheFieldIsMaskedKeepsTheStoredUrl() {
        KeepADBPreferences.setRegisterWebhookUrl(context, WEBHOOK);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        activity.findViewById(R.id.settings_webhook_save).performClick();

        assertEquals("the masked text is display only, never saved", WEBHOOK,
                KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    // --- Shared diagnostics ----------------------------------------------------------------------

    @Test
    public void sharedDiagnosticsRedactAddressesWhileTheAppSwitchIsOnWhateverIsRevealed() {
        KeepADBDiagnostics.event(context, "user_action", "test", "seen",
                "bssid=02:aa:bb:cc:dd:ee host=192.0.2.44 port=41234");
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        String masked = shared(activity);
        assertTrue(masked, masked.contains("bssid=[REDACTED]"));
        assertFalse(masked, masked.contains("02:aa:bb"));
        assertFalse(masked, masked.contains("192.0.2.44"));

        // The Settings share is an eye-only surface: a "Show" elsewhere on the screen is no say.
        KeepADBPrivacy.reveal(activity, KeepADBPrivacy.SCOPE_WEBHOOK);
        String stillMasked = shared(activity);
        assertTrue(stillMasked, stillMasked.contains("bssid=[REDACTED]"));
        assertFalse(stillMasked, stillMasked.contains("192.0.2.44"));
        controller.pause().stop().destroy();
    }

    @Test
    public void sharedDiagnosticsKeepTheUsualExportWhileTheAppSwitchIsOff() {
        KeepADBDiagnostics.event(context, "user_action", "test", "seen",
                "bssid=02:aa:bb:cc:dd:ee host=192.0.2.44 port=41234");
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();

        String export = shared(controller.get());

        assertTrue(export, export.contains("bssid=02:aa:bb:*:*:*"));
        assertTrue(export, export.contains("host=192.0.2.44"));
        controller.pause().stop().destroy();
    }

    private String shared(SettingsActivity activity) {
        activity.findViewById(R.id.settings_diagnostics_export).performClick();
        ShadowLooper.idleMainLooper();
        Intent chooser = shadowOf(activity).getNextStartedActivity();
        assertNotNull(chooser);
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        return send.getStringExtra(Intent.EXTRA_TEXT);
    }

    // --- When the screen comes back, the content is masked again (rendered, not only the flag) ---------------

    @Test
    public void aRevealedWebhookFieldIsMaskedAgainWhenTheScreenComesBackEvenWithFocus() {
        KeepADBPreferences.setRegisterWebhookUrl(context, WEBHOOK);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();
        EditText field = activity.findViewById(R.id.settings_webhook_url);
        activity.findViewById(R.id.settings_webhook_url_show).performClick();
        assertEquals(View.VISIBLE, field.getVisibility());
        field.requestFocus();
        assertTrue("Control: the field keeps focus", field.hasFocus());

        controller.pause().stop();
        controller.start().resume().visible();
        ShadowLooper.idleMainLooper();

        assertEquals(View.GONE, field.getVisibility());
        String shown = visibleText(activity.getWindow().getDecorView());
        assertFalse("no visible view holds the saved host after returning: " + shown,
                shown.contains("111.111.21"));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_webhook_url_show).getVisibility());
        controller.pause().stop().destroy();
    }

    @Test
    public void aFocusedMaskedFieldNeverShowsTheSavedUrl() {
        KeepADBPreferences.setRegisterWebhookUrl(context, WEBHOOK);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();
        EditText field = activity.findViewById(R.id.settings_webhook_url);

        // Focus arrives while the field is masked (a keyboard, a focus search), then Settings redraws.
        field.setVisibility(View.VISIBLE);
        field.requestFocus();
        KeepADBEyeMenuTestSupport.choose(activity, KeepADBPrivacyToggle.ITEM_APP); // off
        KeepADBEyeMenuTestSupport.choose(activity, KeepADBPrivacyToggle.ITEM_APP); // on again
        ShadowLooper.idleMainLooper();

        assertEquals(View.GONE, field.getVisibility());
        assertFalse(visibleText(activity.getWindow().getDecorView()).contains("111.111.21"));
        controller.pause().stop().destroy();
    }

    @Test
    public void textTypedIntoAnEmptyWebhookFieldIsShownAsTypedAndMaskedOnceSaved() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();
        EditText field = activity.findViewById(R.id.settings_webhook_url);

        field.setText("http://192.168.7.7:50829/register/s20"); // typed by the user
        controller.pause().stop();
        controller.start().resume().visible();
        ShadowLooper.idleMainLooper();
        assertEquals("the user's own new input is not hidden from them", View.VISIBLE,
                field.getVisibility());
        assertEquals("http://192.168.7.7:50829/register/s20", field.getText().toString());

        activity.findViewById(R.id.settings_webhook_save).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("once saved it is the saved URL and masked", View.GONE, field.getVisibility());
        assertTrue(((TextView) activity.findViewById(R.id.settings_webhook_url_masked)).getText()
                .toString().contains("192.*.*.*"));
        controller.pause().stop().destroy();
    }

    // --- A posted trust notice follows the notification switch at once ---------------------------------------

    @Test
    public void turningNotificationPrivacyOnCancelsAPostedTrustNoticeWithDetails() {
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS);
        connectTo("Cafe-WLAN", "aa:bb:cc:dd:ee:77");
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        android.app.Notification before = shadowOf(manager)
                .getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
        assertEquals("Control: the posted notice has the Allow and Block buttons", 2,
                before.actions.length);
        assertTrue(String.valueOf(before.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
                .contains("Cafe-WLAN"));

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.privacy_switch_notifications).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        // Cancelled, not re-posted: the prompt history cannot reliably name the access point of
        // the posted notice, so a neutral re-post could open the decision for the wrong one.
        assertNull("the notice with name, BSSID and buttons is gone", shadowOf(manager)
                .getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
        controller.pause().stop().destroy();
    }

    @Test
    public void aNeutralPostedTrustNoticeIsLeftAloneWhenTheSwitchTurnsOn() {
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);
        connectTo("Cafe-WLAN", "aa:bb:cc:dd:ee:77");
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        android.app.Notification before = shadowOf(manager)
                .getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);

        KeepADBNetworkTrustPrompt.applyNotificationPrivacy(context);

        assertEquals(before, shadowOf(manager).getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
    }

    // --- Helpers ---------------------------------------------------------------------------------

    /** The text of every view that is actually shown (a gone view and its subtree are skipped). */
    private static String visibleText(View root) {
        StringBuilder out = new StringBuilder();
        collect(root, out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view.getContentDescription() != null) out.append(view.getContentDescription()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private void connectTo(String ssid, String bssid) {
        android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager)
                context.getSystemService(Context.WIFI_SERVICE);
        android.net.wifi.WifiInfo info = org.robolectric.shadows.ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private static int count(View root, String label) {
        int found = 0;
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) found++;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found += count(group.getChildAt(i), label);
        }
        return found;
    }

    private static Button button(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) {
            return (Button) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
