package de.hohnepeople.keepadb;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

/** Actual intent-to-Activity and current-page dialog contracts for #821. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class SettingsEntryPointsTest {
    @Rule public final KeepADBNetworkResetRule reset = new KeepADBNetworkResetRule();

    @Test public void webhookIntentExpandsOnlyItsCardAndConsumesTheExtra() {
        assertEntry(SettingsActivity.EXTRA_FOCUS_WEBHOOK, R.id.settings_webhook_header,
                R.id.settings_webhook_body, R.id.settings_webhook_arrow, R.id.settings_misc_body);
    }

    @Test public void otherIntentExpandsOnlyItsCardAndConsumesTheExtra() {
        assertEntry(SettingsActivity.EXTRA_FOCUS_MISC, R.id.settings_misc_header,
                R.id.settings_misc_body, R.id.settings_misc_arrow, R.id.settings_webhook_body);
    }

    @Test public void networkMenuIntentExpandsTheHeadingCard() {
        assertEntry(SettingsActivity.EXTRA_FOCUS_NETWORK_CARD, R.id.settings_network_beta_header,
                R.id.settings_network_beta_body, R.id.settings_network_beta_arrow, R.id.settings_misc_body);
    }

    private void assertEntry(String extra, int header, int body, int arrow, int otherBody) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(extra, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class, intent).setup();
        SettingsActivity activity = controller.get();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 360, 800);
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, activity.findViewById(body).getVisibility());
        assertEquals(View.GONE, activity.findViewById(otherBody).getVisibility());
        assertEquals("−", ((TextView) activity.findViewById(arrow)).getText().toString());
        assertEquals(activity.getString(R.string.card_state_expanded),
                activity.findViewById(header).getStateDescription());
        assertFalse(activity.getIntent().hasExtra(extra));
        controller.pause().stop().destroy();
    }

    @Test public void defaultEntryLeavesEveryTargetCollapsed() {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        for (int body : new int[]{R.id.settings_webhook_body, R.id.settings_misc_body,
                R.id.settings_usb_adb_body, R.id.settings_network_beta_body}) {
            assertEquals(View.GONE, controller.get().findViewById(body).getVisibility());
        }
        controller.pause().stop().destroy();
    }

    @Test public void restoredOriginalLaunchIntentDoesNotRepeatTheFocusAction() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_FOCUS_MISC, true);
        ActivityController<SettingsActivity> first = Robolectric.buildActivity(SettingsActivity.class, intent).setup();
        ShadowLooper.idleMainLooper();
        Bundle state = new Bundle();
        first.pause().saveInstanceState(state).stop().destroy();
        // The OS can restore the original launch intent rather than the mutated local copy.
        Intent original = new Intent(context, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_FOCUS_MISC, true);
        ActivityController<SettingsActivity> second = Robolectric.buildActivity(SettingsActivity.class, original)
                .create(state).start().restoreInstanceState(state).resume().visible();
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, second.get().findViewById(R.id.settings_misc_body).getVisibility());
        assertFalse(second.get().getIntent().hasExtra(SettingsActivity.EXTRA_FOCUS_MISC));
        second.pause().stop().destroy();
    }

    @Test public void languageDialogStaysOnHomeAndSelectionRequestsItsReload() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = controller.get();
        main.showLanguageSelectionDialog();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Home must show its own picker", dialog);
        assertTrue(dialog.isShowing());
        assertSame(main, dialog.getOwnerActivity());
        assertNull(shadowOf(main).getNextStartedActivity());
        int german = 0;
        for (int i = 0; i < KeepADBLocaleHelper.SUPPORTED_LANGUAGES.length; i++) {
            if ("de".equals(KeepADBLocaleHelper.SUPPORTED_LANGUAGES[i].tag)) german = i;
        }
        dialog.getListView().performItemClick(null, german, german);
        assertEquals("de", KeepADBLocaleHelper.getSelectedLanguageTag(main));
        ShadowLooper.idleMainLooper();
        assertNotSame("Selecting a language must reload the current page", main, controller.get());
        assertEquals("de", controller.get().getResources().getConfiguration().getLocales().get(0).getLanguage());
        assertFalse(dialog.isShowing());
        controller.pause().stop().destroy();
    }

    @Test public void homeLanguageDialogRestoresOnceAfterRotation() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().showLanguageSelectionDialog();
        AlertDialog old = ShadowAlertDialog.getLatestAlertDialog();
        controller.recreate();
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse(old.isShowing());
        assertNotSame(old, restored);
        assertTrue(restored.isShowing());
        controller.get().showLanguageSelectionDialog();
        assertSame(restored, ShadowAlertDialog.getLatestAlertDialog());
        controller.pause().stop().destroy();
        assertFalse(restored.isShowing());
    }

    @Test public void assistantLaunchesDirectlyFromHomeWithAllSteps() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = controller.get();
        main.openSetupAssistant();
        Intent intent = shadowOf(main).getNextStartedActivity();
        assertEquals(OnboardingActivity.class.getName(), intent.getComponent().getClassName());
        assertFalse(intent.hasExtra(OnboardingActivity.EXTRA_STEP));
        assertFalse(intent.hasExtra(OnboardingActivity.EXTRA_OPEN_HOME));
        controller.pause().stop().destroy();
    }
}
