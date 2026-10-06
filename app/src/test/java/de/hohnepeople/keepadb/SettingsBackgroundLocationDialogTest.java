package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * #644: step 2 of the two-step location flow. While trusted-network (allowlist) mode is on without
 * the optional ACCESS_BACKGROUND_LOCATION grant -- and before the "Check background access" button
 * jumps to the system page -- the settings show one rationale dialog with three actions: open the
 * app's system settings, trust all Wi-Fi networks instead, or decide later.
 *
 * <p>#769: the mode choice that used to open this dialog after enabling the allowlist is gone, so
 * every test drives the settings button and reads the observable effect: the started intent, the
 * persisted mode, the dialog. The background grant is never requested through a runtime dialog.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsBackgroundLocationDialogTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        ShadowDialog.reset();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private ActivityController<SettingsActivity> openSettings() {
        return Robolectric.buildActivity(SettingsActivity.class).setup();
    }

    private static AlertDialog latestDialog() {
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    /** Asserts the dialog is the step-2 rationale: privacy text, not a permission request. */
    private void assertIsStepTwoDialog(AlertDialog dialog) {
        assertNotNull("The step-2 dialog must be showing", dialog);
        assertTrue(dialog.isShowing());
        ShadowAlertDialog shadow = shadowOf(dialog);
        assertEquals(context.getString(R.string.background_location_panel_title),
                shadow.getTitle().toString());
        assertEquals(context.getString(R.string.background_location_panel_body),
                shadow.getMessage().toString());
    }

    /** Dialog button handlers run through a Handler message, so the looper must be drained. */
    private static void click(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static void assertNoDialogShown() {
        assertTrue("No dialog must appear", ShadowDialog.getShownDialogs().isEmpty());
    }

    private static void assertNoRuntimeRequest(SettingsActivity activity) {
        assertNull("ACCESS_BACKGROUND_LOCATION is never requested through a runtime dialog",
                shadowOf(activity).getLastRequestedPermission());
    }

    // --- Gap A: after allowlist mode became active ---------------------------------------

    // --- The three actions ------------------------------------------------------------------

    // --- Gap B: the settings button -------------------------------------------------------

    @Test
    public void settingsButtonWithoutGrantShowsStepTwoBeforeAnyJump() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity activity = openSettings().get();

        activity.findViewById(R.id.settings_background_location_button).performClick();

        assertNull("The jump must wait for the user's choice",
                shadowOf(activity).getNextStartedActivity());
        AlertDialog dialog = latestDialog();
        assertIsStepTwoDialog(dialog);

        click(dialog, AlertDialog.BUTTON_POSITIVE);
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
        assertNoRuntimeRequest(activity);
    }

    @Test
    public void settingsButtonLaterKeepsTheModeAndTrustAllLeadsToTheProtectionStep() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity activity = openSettings().get();

        activity.findViewById(R.id.settings_background_location_button).performClick();
        click(latestDialog(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertEquals("The missing grant stays visible after \"later\"",
                context.getString(R.string.background_location_status_missing),
                ((TextView) activity.findViewById(R.id.settings_background_location_status))
                        .getText().toString());

        activity.findViewById(R.id.settings_background_location_button).performClick();
        click(latestDialog(), AlertDialog.BUTTON_NEUTRAL);
        // #782: the button leads to the protection step instead of switching the mode itself.
        assertEquals("The button itself changes nothing", KeepADBTrustedNetwork.MODE_ALLOWLIST,
                KeepADBTrustedNetwork.getMode(context));
        Intent assistant = shadowOf(activity).getNextStartedActivity();
        assertNotNull(assistant);
        assertEquals(OnboardingActivity.class.getName(), assistant.getComponent().getClassName());
        assertEquals(KeepADBOnboarding.Step.PROTECTION.id,
                assistant.getStringExtra(OnboardingActivity.EXTRA_STEP));
    }

    @Test
    public void settingsButtonWithGrantJumpsDirectlyWithoutDialog() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        SettingsActivity activity = openSettings().get();

        activity.findViewById(R.id.settings_background_location_button).performClick();

        assertNoDialogShown();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
    }

    @Test
    public void settingsButtonInAllWifiModeOffersNoPointlessTrustAllAction() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        SettingsActivity activity = openSettings().get();

        activity.findViewById(R.id.settings_background_location_button).performClick();

        AlertDialog dialog = latestDialog();
        assertIsStepTwoDialog(dialog);
        assertEquals("Already trusting all networks: the fallback would change nothing",
                View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
        click(dialog, AlertDialog.BUTTON_POSITIVE);
        assertNotNull(shadowOf(activity).getNextStartedActivity());
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
    }

    // --- Return and repetition --------------------------------------------------------------

    @Test
    public void returningWithGrantShowsNoDialogAgain() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ActivityController<SettingsActivity> controller = openSettings();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_background_location_button).performClick();
        click(latestDialog(), AlertDialog.BUTTON_POSITIVE);
        assertNotNull(shadowOf(activity).getNextStartedActivity());
        ShadowDialog.reset();

        // User picks "Allow all the time" on the system page and comes back.
        controller.pause();
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        controller.resume();

        assertNoDialogShown();
        TextView status = activity.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_granted),
                status.getText().toString());
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
    }

    @Test
    public void returningWithoutGrantDoesNotNagOnItsOwn() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ActivityController<SettingsActivity> controller = openSettings();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_background_location_button).performClick();
        click(latestDialog(), AlertDialog.BUTTON_NEGATIVE);
        ShadowDialog.reset();

        controller.pause().resume();

        assertNoDialogShown();
    }

    @Test
    public void repeatedClicksNeverStackTwoStepTwoDialogs() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity activity = openSettings().get();

        activity.findViewById(R.id.settings_background_location_button).performClick();
        AlertDialog first = latestDialog();
        activity.findViewById(R.id.settings_background_location_button).performClick();

        assertSame(first, latestDialog());
        assertEquals(1, ShadowDialog.getShownDialogs().size());
    }
}
