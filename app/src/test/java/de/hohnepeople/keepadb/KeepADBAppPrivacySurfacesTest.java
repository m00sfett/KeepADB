package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * #904: the screens of the app that show a webhook address, a USB host profile or the shared
 * diagnostics follow the app privacy switch; a deliberate "Show" reveals one screen until it
 * stops. Each test has its counter-check with the switch off, so a mask applied unconditionally
 * (or a "Show" that does nothing) cannot pass.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAppPrivacySurfacesTest {
    private static final String WEBHOOK = "http://100.111.111.21:50829/register/s20";
    private static final String PROFILE = "Laptop-Tobias";
    private static final String PROFILE_IP = "10.0.0.99";
    private static final String PROFILE_HOST = "laptop.fritz.box";
    private static final String PROFILE_REMOTE = "laptop.tailnet.example";

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

    // --- USB host profiles -----------------------------------------------------------------------

    @Test
    public void theUsbProfileListIsMaskedWithShowAndEditNeedsTheReveal() {
        KeepADBUsbProfile.add(context, PROFILE, PROFILE_IP, PROFILE_HOST, PROFILE_REMOTE);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        String line = ((TextView) activity.findViewById(R.id.settings_usb_profile_summary))
                .getText().toString();
        assertFalse("the Settings line names no profile: " + line, line.contains(PROFILE));

        activity.findViewById(R.id.settings_usb_profile_action).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveSwitchProfileDialog();
        assertNotNull(dialog);
        String masked = visibleText(dialog.getWindow().getDecorView());
        for (String secret : new String[] {PROFILE, PROFILE_IP, PROFILE_HOST, PROFILE_REMOTE}) {
            assertFalse("masked list must not show '" + secret + "': " + masked, masked.contains(secret));
        }
        assertTrue(masked, masked.contains(context.getString(R.string.usb_profile_hidden_numbered, 1)));
        assertTrue(masked, masked.contains("10.*.*.*"));
        assertEquals("no way to edit what is hidden", 0, count(dialog.getWindow().getDecorView(),
                context.getString(R.string.usb_profile_edit_button)));
        Button show = button(dialog.getWindow().getDecorView(),
                context.getString(R.string.networks_hidden_show));
        assertNotNull(show);

        show.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog revealed = activity.getActiveSwitchProfileDialog();
        assertNotNull("Show reopens the list unmasked", revealed);
        String plain = visibleText(revealed.getWindow().getDecorView());
        assertTrue(plain, plain.contains(PROFILE) && plain.contains(PROFILE_IP));
        assertEquals(1, count(revealed.getWindow().getDecorView(),
                context.getString(R.string.usb_profile_edit_button)));
        assertTrue(KeepADBPreferences.isAppPrivacyEnabled(context));
        controller.pause().stop().destroy();
    }

    @Test
    public void theUsbProfileListIsPlainWhileTheAppSwitchIsOff() {
        KeepADBUsbProfile.add(context, PROFILE, PROFILE_IP, PROFILE_HOST, PROFILE_REMOTE);
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        activity.findViewById(R.id.settings_usb_profile_action).performClick();
        ShadowLooper.idleMainLooper();

        String plain = visibleText(activity.getActiveSwitchProfileDialog().getWindow().getDecorView());
        assertTrue(plain, plain.contains(PROFILE) && plain.contains(PROFILE_IP)
                && plain.contains(PROFILE_HOST));
        assertEquals(0, count(activity.getActiveSwitchProfileDialog().getWindow().getDecorView(),
                context.getString(R.string.networks_hidden_show)));
    }

    @Test
    public void aRecreatedScreenDoesNotPutASavedProfileBackIntoAMaskedEditDialog() {
        int id = KeepADBUsbProfile.add(context, PROFILE, PROFILE_IP, PROFILE_HOST, PROFILE_REMOTE).id;
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        android.os.Bundle saved = new android.os.Bundle();
        saved.putBoolean(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING, true);
        saved.putInt(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_ID, id);
        saved.putString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_NAME, PROFILE);
        saved.putString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_IP, PROFILE_IP);

        ActivityController<SettingsActivity> masked = Robolectric.buildActivity(SettingsActivity.class)
                .create(saved).start().resume().visible();
        ShadowLooper.idleMainLooper();
        assertEquals("the revealed state did not survive the recreation, so no edit dialog",
                null, masked.get().getActiveProfileEditDialog());
        masked.pause().stop().destroy();

        // Counter-check: with the switch off the very same state restores the dialog.
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        ActivityController<SettingsActivity> plain = Robolectric.buildActivity(SettingsActivity.class)
                .create(saved).start().resume().visible();
        ShadowLooper.idleMainLooper();
        assertNotNull(plain.get().getActiveProfileEditDialog());
        plain.pause().stop().destroy();
    }

    // --- Shared diagnostics ----------------------------------------------------------------------

    @Test
    public void sharedDiagnosticsRedactAddressesWhileTheAppSwitchIsOnAndNotAfterShow() {
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

        KeepADBPrivacy.reveal(activity);
        String revealed = shared(activity);
        assertTrue("after Show the usual export (OUI kept) is shared: " + revealed,
                revealed.contains("bssid=02:aa:bb:*:*:*"));
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
