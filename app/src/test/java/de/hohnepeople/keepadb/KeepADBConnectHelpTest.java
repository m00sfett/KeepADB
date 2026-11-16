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
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

/**
 * #952: the "Connect a computer" help: three steps, reachable from the link below the endpoint and
 * from the last page of the setup assistant, one button to Android's developer options. It never
 * claims a pairing state and shows no pairing code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBConnectHelpTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBOnboarding.markCompleted(context);
        KeepADB.resetForTesting(context);
        KeepADBPreferences.setAppLanguage(context, "en");
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        for (ActivityController<?> controller : controllers) {
            try {
                controller.pause().stop().destroy();
            } catch (RuntimeException ignored) {
                // Already finished.
            }
        }
        shadowOf((android.app.Application) context).checkActivities(false);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting(context);
    }

    // ---- From the home screen ------------------------------------------------------------------------

    @Test
    public void theLinkBelowTheEndpointOpensTheHelpWithThreeStepsAndMonospaceCommands() {
        MainActivity activity = openHome();
        View link = activity.findViewById(R.id.connect_help);
        assertTrue("a button, not another card", link instanceof Button);
        assertTrue(link.getMinimumHeight() >= 48 * context.getResources().getDisplayMetrics().density - 1
                || ((Button) link).getMinHeight() >= 48 * context.getResources().getDisplayMetrics().density - 1);
        assertEquals(context.getString(R.string.connect_help_link), ((Button) link).getText().toString());
        // Directly below the endpoint row.
        ViewGroup block = (ViewGroup) link.getParent();
        assertEquals(block.indexOfChild(activity.findViewById(R.id.endpoint_row)) + 1,
                block.indexOfChild(link));

        link.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals(context.getString(R.string.connect_help_title),
                shadowOf(dialog).getTitle().toString());

        String all = allText(dialog);
        assertTrue(all, all.contains("1. " + context.getString(R.string.connect_help_step_phone)));
        assertTrue(all, all.contains("2. " + context.getString(R.string.connect_help_step_pair)));
        assertTrue(all, all.contains("3. " + context.getString(R.string.connect_help_step_connect)));
        TextView pair = textView(dialog, "adb pair <ip>:<pairing-port>");
        TextView connect = textView(dialog, "adb connect <ip>:<port>");
        assertEquals(Typeface.MONOSPACE, pair.getTypeface());
        assertEquals(Typeface.MONOSPACE, connect.getTypeface());
        assertTrue(pair.isTextSelectable());
    }

    @Test
    public void theDialogHasTheDeveloperOptionsButtonAndClose() {
        MainActivity activity = openHome();
        activity.findViewById(R.id.connect_help).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();

        assertEquals(context.getString(R.string.connect_help_open_settings),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(context.getString(R.string.onboarding_close),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertNull("closing starts nothing", shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void theButtonOpensAndroidsDeveloperOptions() {
        MainActivity activity = openHome();
        activity.findViewById(R.id.connect_help).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, started.getAction());
    }

    @Test
    public void aDeviceWithoutThatScreenGetsTheNoAppFoundToast() {
        shadowOf((android.app.Application) context).checkActivities(true);
        MainActivity activity = openHome();
        activity.findViewById(R.id.connect_help).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();

        assertEquals(context.getString(R.string.settings_no_browser_found),
                ShadowToast.getTextOfLatestToast());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    @Config(fontScale = 1.3f)
    public void theStepsScrollSoTheyFitAtFontScale130() {
        MainActivity activity = openHome();
        activity.findViewById(R.id.connect_help).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();

        assertTrue(dialog.isShowing());
        assertTrue("the content scrolls", findScrollView(dialog.getWindow().getDecorView()) != null);
        assertNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
    }

    @Test
    public void theCommandsAreTheSameInEveryLanguage() {
        for (String language : new String[] {"de", "ar", "ja"}) {
            KeepADBPreferences.setAppLanguage(context, language);
            MainActivity activity = openHome();
            activity.findViewById(R.id.connect_help).performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(language, textView(dialog, "adb pair <ip>:<pairing-port>"));
            assertNotNull(language, textView(dialog, "adb connect <ip>:<port>"));
            dialog.dismiss();
        }
    }

    @Test
    public void theHelpClaimsNoPairingStateAndShowsNoCode() {
        MainActivity activity = openHome();
        activity.findViewById(R.id.connect_help).performClick();
        String all = allText(ShadowAlertDialog.getLatestAlertDialog());

        // No digits but the step numbers: no pairing code, no port.
        String withoutNumbers = all.replace("1. ", "").replace("2. ", "").replace("3. ", "");
        assertFalse(withoutNumbers, withoutNumbers.matches("(?s).*\\d.*"));
        for (String state : new String[] {"is paired", "was paired", "not paired", "paired:"}) {
            assertFalse(state, all.contains(state));
        }
    }

    @Test
    public void theHelpSurvivesARotation() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(controller);
        controller.get().findViewById(R.id.connect_help).performClick();
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());

        controller.recreate();
        AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the help is open again", again.isShowing());
        assertNotNull(textView(again, "adb connect <ip>:<port>"));
    }

    // ---- From the setup assistant --------------------------------------------------------------------

    @Test
    public void theLastPageOfTheAssistantHasTheLinkAndEarlierPagesDoNot() {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, OnboardingActivity.fullIntent(context))
                        .setup();
        controllers.add(controller);
        OnboardingActivity assistant = controller.get();
        assertNull("not on the intro", assistant.findViewById(R.id.onboarding_connect_help));

        int pages = OnboardingActivity.buildSteps().size();
        for (int i = 0; i < pages; i++) {
            assistant.findViewById(R.id.onboarding_next).performClick();
            assertNull("not on a step", assistant.findViewById(R.id.onboarding_connect_help));
        }
        assistant.findViewById(R.id.onboarding_next).performClick(); // the last page

        Button link = assistant.findViewById(R.id.onboarding_connect_help);
        assertNotNull("the summary has the link", link);
        assertEquals(context.getString(R.string.connect_help_link), link.getText().toString());
        link.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertNotNull(textView(dialog, "adb pair <ip>:<pairing-port>"));
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private MainActivity openHome() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(controller);
        return controller.get();
    }

    private static String allText(AlertDialog dialog) {
        StringBuilder out = new StringBuilder();
        collect(dialog.getWindow().getDecorView(), out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView && !(view instanceof Button)) {
            out.append(((TextView) view).getText()).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static TextView textView(AlertDialog dialog, String exactText) {
        return find(dialog.getWindow().getDecorView(), exactText);
    }

    private static TextView find(View view, String exactText) {
        if (view instanceof TextView && exactText.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView hit = find(group.getChildAt(i), exactText);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static ScrollView findScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView hit = findScrollView(group.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
