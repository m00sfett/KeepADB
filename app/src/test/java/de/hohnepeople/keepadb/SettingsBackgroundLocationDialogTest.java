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
import android.widget.RadioButton;
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
 * #644: step 2 of the two-step location flow. Once trusted-network (allowlist) mode is on without
 * the optional ACCESS_BACKGROUND_LOCATION grant -- and before the "Check background access" button
 * jumps to the system page -- the settings show one rationale dialog with three actions: open the
 * app's system settings, trust all Wi-Fi networks instead, or decide later.
 *
 * <p>Every test drives the real entry point (the trusted-network switch, the permission result
 * callback, or the settings button) and reads the observable effect: the started intent, the
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

    /** The "Only allowed access points" option of the mode choice (#654); clicking it opts in. */
    private static RadioButton trustedToggle(SettingsActivity activity) {
        return activity.findViewById(R.id.network_mode_allowlist);
    }

    private static RadioButton allWifiOption(SettingsActivity activity) {
        return activity.findViewById(R.id.network_mode_all_wifi);
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

    /** Clicks the allowlist option and accepts the rationale; returns the system permission request. */
    private static ShadowActivity.PermissionsRequest askForTheGrant(SettingsActivity activity) {
        trustedToggle(activity).performClick();
        click(latestDialog(), AlertDialog.BUTTON_POSITIVE);
        ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request);
        assertEquals(KeepADBNetworkCard.TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST,
                request.requestCode);
        return request;
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

    @Test
    public void enablingAllowlistWithFineAlreadyGrantedShowsStepTwoWithAllThreeActions() {
        SettingsActivity activity = openSettings().get();
        assertNoDialogShown();

        trustedToggle(activity).performClick();

        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        AlertDialog dialog = latestDialog();
        assertIsStepTwoDialog(dialog);
        assertEquals(context.getString(R.string.location_permission_settings_button),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(context.getString(R.string.location_permission_panel_fallback_button),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        assertEquals(context.getString(R.string.background_location_dialog_later),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
        assertNoRuntimeRequest(activity);
    }

    @Test
    public void enablingAllowlistWithFreshFineGrantShowsStepTwoAfterTheResultOnly() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        SettingsActivity activity = openSettings().get();

        trustedToggle(activity).performClick();
        // Step 1 rationale, not step 2.
        AlertDialog rationale = latestDialog();
        assertEquals(context.getString(R.string.settings_trusted_network_permission_title),
                shadowOf(rationale).getTitle().toString());
        click(rationale, AlertDialog.BUTTON_POSITIVE);

        ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request);
        for (String requested : request.requestedPermissions) {
            assertFalse("Step 1 must never ask for the background grant",
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(requested));
        }
        assertFalse("Not enabled before the user answered the system dialog",
                KeepADBTrustedNetwork.isAllowlistMode(context));
        assertTrue("The choice shows the stored mode, not the option that was just tapped",
                allWifiOption(activity).isChecked());
        assertFalse(trustedToggle(activity).isChecked());
        ShadowDialog.reset();

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                new int[]{PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED});

        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertIsStepTwoDialog(latestDialog());
    }

    @Test
    public void deniedFineGrantEnablesNothingAndShowsNoStepTwo() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        SettingsActivity activity = openSettings().get();
        trustedToggle(activity).performClick();
        click(latestDialog(), AlertDialog.BUTTON_POSITIVE);
        ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
        ShadowDialog.reset();

        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                new int[]{PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED});

        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertNoDialogShown();
    }

    /**
     * #697: only a FINE grant opens allowlist mode. The user may answer the system dialog with
     * "approximate" (COARSE granted, FINE denied): that enables nothing, shows no step 2 and says
     * so, and the choice keeps showing the stored mode.
     */
    @Test
    public void anApproximateOnlyGrantEnablesNothingAndShowsNoStepTwo() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        SettingsActivity activity = openSettings().get();
        ShadowActivity.PermissionsRequest request = askForTheGrant(activity);
        ShadowDialog.reset();
        org.robolectric.shadows.ShadowToast.reset();

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                new int[]{PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED});

        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertNoDialogShown();
        assertTrue(allWifiOption(activity).isChecked());
        assertFalse(trustedToggle(activity).isChecked());
        assertEquals(context.getString(R.string.settings_trusted_network_permission_denied_toast),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    /**
     * An interrupted request (the app was backgrounded while the system dialog was up) can arrive
     * with empty result arrays: with FINE still missing it enables nothing and shows no step 2.
     */
    @Test
    public void anInterruptedRequestEnablesNothingWhileTheGrantIsMissing() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        SettingsActivity activity = openSettings().get();
        ShadowActivity.PermissionsRequest request = askForTheGrant(activity);
        ShadowDialog.reset();
        org.robolectric.shadows.ShadowToast.reset();

        activity.onRequestPermissionsResult(request.requestCode, new String[0], new int[0]);

        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertNoDialogShown();
        assertTrue(allWifiOption(activity).isChecked());
        assertEquals(context.getString(R.string.settings_trusted_network_permission_denied_toast),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    /**
     * The other side of the two tests above: the actual permission state decides, not the result
     * arrays. An interrupted (empty) result while FINE was in fact granted still enables the mode
     * and follows up with step 2; results that claim a grant while FINE is in fact denied enable
     * nothing.
     */
    @Test
    public void theActualPermissionStateDecidesNotTheResultArrays() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        SettingsActivity activity = openSettings().get();
        ShadowActivity.PermissionsRequest request = askForTheGrant(activity);

        // Claims "granted", but FINE is denied: nothing is enabled.
        ShadowDialog.reset();
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                new int[]{PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED});
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertNoDialogShown();

        // Empty result, but FINE is granted by now: enabled, step 2 follows.
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        activity.onRequestPermissionsResult(request.requestCode, new String[0], new int[0]);
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertIsStepTwoDialog(latestDialog());
    }

    /**
     * The card's other request code (the observation grant) and any foreign code only re-render:
     * they never switch the mode and never raise a dialog, whatever the permission state is.
     */
    @Test
    public void onlyTheAllowlistRequestEnablesTheModeNoOtherResultDoes() {
        SettingsActivity activity = openSettings().get();
        assertTrue("FINE is granted in this setup", activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
        ShadowDialog.reset();

        for (int code : new int[]{KeepADBNetworkCard.WIFI_APS_LOCATION_PERMISSION_REQUEST, 9999, 0,
                -1}) {
            activity.onRequestPermissionsResult(code,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertFalse("Request code " + code, KeepADBTrustedNetwork.isAllowlistMode(context));
            assertNoDialogShown();
        }
    }

    @Test
    public void noStepTwoWhenBackgroundGrantAlreadyThereInEitherBranch() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);

        // Branch 1: FINE already granted.
        SettingsActivity activity = openSettings().get();
        trustedToggle(activity).performClick();
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertNoDialogShown();

        // Branch 2: FINE freshly granted through the system dialog.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        activity = openSettings().get();
        trustedToggle(activity).performClick();
        click(latestDialog(), AlertDialog.BUTTON_POSITIVE);
        ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
        ShadowDialog.reset();
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                new int[]{PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED});
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertNoDialogShown();
    }

    @Test
    public void switchingTheRestrictionOffShowsNoDialog() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity activity = openSettings().get();
        assertTrue(trustedToggle(activity).isChecked());

        allWifiOption(activity).performClick();

        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertTrue(allWifiOption(activity).isChecked());
        assertNoDialogShown();
    }

    /**
     * #654: the mode is a choice, so the already selected option can be clicked again. That must
     * neither ask for anything nor show the step-2 rationale a second time.
     */
    @Test
    public void clickingTheAlreadySelectedAllowlistOptionAgainShowsNoDialog() {
        SettingsActivity activity = openSettings().get();
        trustedToggle(activity).performClick();
        click(latestDialog(), AlertDialog.BUTTON_NEGATIVE);
        ShadowDialog.reset();
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));

        trustedToggle(activity).performClick();

        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertTrue(trustedToggle(activity).isChecked());
        assertNoDialogShown();
        assertNoRuntimeRequest(activity);
    }

    // --- The three actions ------------------------------------------------------------------

    @Test
    public void openSettingsActionJumpsToThisAppsDetailsAndKeepsAllowlistWithoutRequesting() {
        SettingsActivity activity = openSettings().get();
        trustedToggle(activity).performClick();
        AlertDialog dialog = latestDialog();
        assertNull("Nothing may start before the user chooses",
                shadowOf(activity).getNextStartedActivity());

        click(dialog, AlertDialog.BUTTON_POSITIVE);

        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
        assertEquals("package:" + context.getPackageName(), intent.getData().toString());
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertFalse(KeepADBBackgroundLocation.isGranted(context));
        assertFalse(dialog.isShowing());
        assertNoRuntimeRequest(activity);
    }

    @Test
    public void trustAllActionSwitchesToAllWifiAndRefreshesTheSwitchAndStatus() {
        SettingsActivity activity = openSettings().get();
        trustedToggle(activity).performClick();
        assertTrue(trustedToggle(activity).isChecked());
        AlertDialog dialog = latestDialog();

        click(dialog, AlertDialog.BUTTON_NEUTRAL);

        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertFalse("refresh() must re-render the switch from the new mode",
                trustedToggle(activity).isChecked());
        TextView status = activity.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_missing_inactive),
                status.getText().toString());
        assertNull("Trusting all networks is not a jump to system settings",
                shadowOf(activity).getNextStartedActivity());
        assertFalse(dialog.isShowing());
        assertNoRuntimeRequest(activity);
    }

    @Test
    public void laterActionKeepsAllowlistAndStartsNothing() {
        SettingsActivity activity = openSettings().get();
        trustedToggle(activity).performClick();
        AlertDialog dialog = latestDialog();

        click(dialog, AlertDialog.BUTTON_NEGATIVE);

        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertTrue(trustedToggle(activity).isChecked());
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(dialog.isShowing());
        assertNoRuntimeRequest(activity);
        TextView status = activity.findViewById(R.id.settings_background_location_status);
        assertEquals("The missing grant stays visible after \"later\"",
                context.getString(R.string.background_location_status_missing),
                status.getText().toString());
    }

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
    public void settingsButtonTrustAllAndLaterActionsWork() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity activity = openSettings().get();

        activity.findViewById(R.id.settings_background_location_button).performClick();
        click(latestDialog(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertNull(shadowOf(activity).getNextStartedActivity());

        activity.findViewById(R.id.settings_background_location_button).performClick();
        click(latestDialog(), AlertDialog.BUTTON_NEUTRAL);
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertFalse(trustedToggle(activity).isChecked());
        assertNull(shadowOf(activity).getNextStartedActivity());
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
        ActivityController<SettingsActivity> controller = openSettings();
        SettingsActivity activity = controller.get();
        trustedToggle(activity).performClick();
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
        assertTrue(trustedToggle(activity).isChecked());
    }

    @Test
    public void returningWithoutGrantDoesNotNagOnItsOwn() {
        ActivityController<SettingsActivity> controller = openSettings();
        SettingsActivity activity = controller.get();
        trustedToggle(activity).performClick();
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
