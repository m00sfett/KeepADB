package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The webhook step and the force preset of the setup assistant (#768). Each rule is pinned from
 * both sides: what "Next" and "Skip" must not write next to what the form's own buttons write,
 * https next to http, and a card that only opens the dialog next to the dialog's confirmation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingWebhookForceTest {
    private static final String HTTPS_URL = "https://example.org/register/test";
    private static final String HTTP_URL = "http://example.org/register/test";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
    }

    @After
    public void tearDown() {
        for (int i = controllers.size() - 1; i >= 0; i--) {
            try {
                controllers.get(i).pause().stop().destroy();
            } catch (RuntimeException ignored) {
                // Already finished.
            }
        }
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBForceMode.resetForTesting();
    }

    // ---- Webhook: Next and Skip write nothing ---------------------------------------------------------------

    @Test
    public void nextAndSkipNeverSaveATypedUrlButTheFormsOwnButtonDoes() {
        for (int id : new int[] {R.id.onboarding_next, R.id.onboarding_secondary}) {
            prefs().edit().clear().commit();
            OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
            advanceTo(assistant, KeepADBOnboarding.Step.WEBHOOK);
            type(assistant, HTTPS_URL);
            Map<String, String> before = snapshot();

            click(assistant, id);

            assertEquals("leaving the step writes nothing", before, snapshot());
            assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
            assertNull(KeepADBPreferences.getRegisterWebhookUrl(context));
        }

        // The other side: the form's own "Save URL" stores and enables.
        OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
        advanceTo(assistant, KeepADBOnboarding.Step.WEBHOOK);
        type(assistant, HTTPS_URL);
        click(assistant, R.id.settings_webhook_save);
        assertEquals(HTTPS_URL, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void skipIsOfferedAndNothingIsPrefilled() {
        OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
        advanceTo(assistant, KeepADBOnboarding.Step.WEBHOOK);

        assertEquals(View.VISIBLE, assistant.findViewById(R.id.onboarding_secondary).getVisibility());
        assertEquals("", ((EditText) assistant.findViewById(R.id.settings_webhook_url))
                .getText().toString());
        assertFalse(((android.widget.Switch) assistant.findViewById(R.id.settings_webhook_toggle))
                .isChecked());
        assertEquals("the webhook is the last step", "Step 6 of 6",
                text(assistant, R.id.onboarding_header_counter));
    }

    @Test
    public void walkingAFreshInstallThroughTheWebhookStepStoresNoWebhookKey() {
        OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
        click(assistant, R.id.onboarding_next);
        for (int i = 0; i < OnboardingActivity.buildSteps().size(); i++) {
            click(assistant, R.id.onboarding_next);
        }
        click(assistant, R.id.onboarding_next);
        assertFalse(prefs().contains("register_webhook_enabled"));
        assertFalse(prefs().contains("register_webhook_url"));
    }

    // ---- Webhook: validation and the plaintext mark -------------------------------------------------------

    @Test
    public void httpsIsStoredWithoutAMarkAndHttpIsStoredWithTheLessSecureMark() {
        OnboardingActivity https = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.WEBHOOK));
        assertEquals(View.GONE, marker(https).getVisibility());
        type(https, HTTPS_URL);
        click(https, R.id.settings_webhook_save);
        assertEquals(HTTPS_URL, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals("https carries no mark", View.GONE, marker(https).getVisibility());
        assertEquals(View.GONE,
                https.findViewById(R.id.settings_webhook_cleartext_warning).getVisibility());

        prefs().edit().clear().commit();
        OnboardingActivity http = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.WEBHOOK));
        type(http, HTTP_URL);
        click(http, R.id.settings_webhook_save);
        assertEquals(HTTP_URL, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(View.VISIBLE, marker(http).getVisibility());
        assertEquals("Less secure", ((TextView) marker(http)).getText().toString());
        assertEquals("the plaintext warning of the Settings shows as well", View.VISIBLE,
                http.findViewById(R.id.settings_webhook_cleartext_warning).getVisibility());
    }

    @Test
    public void anInvalidUrlIsRefusedAndAnEmptyOneCannotEnable() {
        OnboardingActivity assistant = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.WEBHOOK));
        type(assistant, "ftp://example.org/x");
        click(assistant, R.id.settings_webhook_save);
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(context));
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(View.VISIBLE, assistant.findViewById(R.id.settings_webhook_error).getVisibility());
        assertEquals(context.getString(R.string.settings_webhook_error_invalid_url),
                text(assistant, R.id.settings_webhook_error));

        type(assistant, "");
        android.widget.Switch toggle = assistant.findViewById(R.id.settings_webhook_toggle);
        toggle.setChecked(true);
        toggle.callOnClick(); // the click listener reads the switch the way a tap leaves it
        assertFalse("no URL, no webhook", KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertFalse(toggle.isChecked());
    }

    @Test
    public void aStoredHttpWebhookIsMarkedFromTheStartAndNextLeavesItUntouched() {
        KeepADBPreferences.setRegisterWebhookUrl(context, HTTP_URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        prefs().edit().putBoolean("last_desired_on", true).commit();
        OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
        advanceTo(assistant, KeepADBOnboarding.Step.WEBHOOK);
        assertEquals(View.VISIBLE, marker(assistant).getVisibility());
        assertEquals(HTTP_URL, ((EditText) assistant.findViewById(R.id.settings_webhook_url))
                .getText().toString());
        Map<String, String> before = snapshot();

        click(assistant, R.id.onboarding_next);

        assertEquals("an existing setting is not moved by Next", before, snapshot());

        prefs().edit().clear().commit();
        KeepADBPreferences.setRegisterWebhookUrl(context, HTTPS_URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        OnboardingActivity secure = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.WEBHOOK));
        assertEquals("an https webhook is not marked", View.GONE, marker(secure).getVisibility());
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBPreferences.setRegisterWebhookUrl(context, HTTP_URL);
        OnboardingActivity off = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.WEBHOOK));
        assertEquals("a disabled webhook sends nothing and is not marked", View.GONE,
                marker(off).getVisibility());
    }

    @Test
    public void aTypedUrlSurvivesARotationAndIsStillNotStored() {
        ActivityController<OnboardingActivity> controller = Robolectric.buildActivity(
                OnboardingActivity.class,
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.WEBHOOK)).setup();
        controllers.add(controller);
        type(controller.get(), HTTPS_URL);

        controller.recreate();

        assertEquals(HTTPS_URL, ((EditText) controller.get().findViewById(R.id.settings_webhook_url))
                .getText().toString());
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    @Test
    public void singleStepDoneStoresNoTypedUrl() {
        OnboardingActivity assistant = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.WEBHOOK));
        type(assistant, HTTPS_URL);
        click(assistant, R.id.onboarding_next);
        assertTrue(assistant.isFinishing());
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    // ---- Force preset: only the dialog's confirmation starts it -----------------------------------------------

    @Test
    public void tappingTheForceCardOpensTheDialogAndChangesNothing() {
        OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
        advanceTo(assistant, KeepADBOnboarding.Step.PROTECTION);
        Map<String, String> before = snapshot();

        forceCard(assistant).performClick();

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertFalse("the card alone starts nothing", KeepADBForceMode.isActive(context));
        assertEquals("the earlier card stays chosen", "Maximum security", checkedTitle(assistant));
        assertEquals(before, snapshot());

        dialog.findViewById(R.id.force_dialog_cancel).performClick();
        assertFalse(dialog.isShowing());
        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals("Maximum security", checkedTitle(assistant));
        assertFalse("Keep-Alive stays as it was", KeepADBPreferences.isKeepAliveEnabled(context));
        click(assistant, R.id.onboarding_next);
        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals("Next after a cancelled dialog writes nothing", before, snapshot());
    }

    @Test
    public void confirmingTheDialogStartsForceSelectsTheCardAndSwitchesKeepAliveOn() {
        OnboardingActivity assistant = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        assertTrue("the card says Keep-Alive is switched on with it",
                cardBody(assistant, 2).contains("Keep-Alive is switched on with it"));
        assertEquals("Not recommended", cardBadge(assistant, 2));

        forceCard(assistant).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.findViewById(R.id.force_dialog_confirm).performClick();

        assertTrue(KeepADBForceMode.isActive(context));
        assertEquals(KeepADBForceTestSupport.HOUR, KeepADBForceMode.status(context).remainingMs);
        assertTrue("F7: Keep-Alive is switched on with the mode",
                KeepADBPreferences.isKeepAliveEnabled(context));
        assertEquals("Maximum convenience (force)", checkedTitle(assistant));
        assertEquals("Less secure", cardBadge(assistant, 2));
        assertTrue(cardBody(assistant, 2), cardBody(assistant, 2).startsWith("Active until"));
        assertFalse("the comfort switch has no effect during the mode",
                assistant.findViewById(R.id.onboarding_comfort_switch).isEnabled());
        assertEquals(View.VISIBLE,
                assistant.findViewById(R.id.onboarding_force_comfort_note).getVisibility());
    }

    @Test
    public void noEndTimeStaysRefusedWithoutTheAcknowledgmentThroughTheAssistant() {
        OnboardingActivity assistant = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        forceCard(assistant).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ((RadioButton) dialog.findViewById(R.id.force_span_unlimited)).setChecked(true);
        View confirm = dialog.findViewById(R.id.force_dialog_confirm);
        assertFalse(confirm.isEnabled());

        confirm.performClick();

        assertFalse("a click on the disabled button starts nothing", KeepADBForceMode.isActive(context));
        assertTrue(dialog.isShowing());
        assertEquals("Maximum security", checkedTitle(assistant));

        ((android.widget.CheckBox) dialog.findViewById(R.id.force_dialog_ack)).setChecked(true);
        confirm.performClick();
        assertTrue(KeepADBForceMode.isActive(context));
        assertTrue(KeepADBForceMode.status(context).isUnlimited());
        assertEquals("Active, no end time", cardBody(assistant, 2));
    }

    @Test
    public void nextWithForceChosenKeepsItAndALevelChosenAfterwardsEndsIt() {
        OnboardingActivity first = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        forceCard(first).performClick();
        ShadowAlertDialog.getLatestAlertDialog().findViewById(R.id.force_dialog_confirm).performClick();
        click(first, R.id.onboarding_next); // Done with the force card chosen
        assertTrue("Done keeps the mode the dialog started", KeepADBForceMode.isActive(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                KeepADBTrustedNetwork.getProtectionLevel(context));

        // Reopened with the mode running: the force card is the chosen one; Done changes nothing.
        OnboardingActivity second = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        assertEquals("Maximum convenience (force)", checkedTitle(second));
        Map<String, String> before = snapshot();
        click(second, R.id.onboarding_next);
        assertEquals(before, snapshot());
        assertTrue(KeepADBForceMode.isActive(context));

        // The other side: a level card chosen instead is the user's decision to leave the mode.
        OnboardingActivity third = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        cards(third).get(1).performClick(); // Balanced
        assertTrue("nothing is written before the step is left", KeepADBForceMode.isActive(context));
        click(third, R.id.onboarding_next);
        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                KeepADBTrustedNetwork.getProtectionLevel(context));
    }

    @Test
    public void theForceRowInCustomizeEndsTheModeAndTheSelectionFallsBack() {
        OnboardingActivity assistant = start(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        assertEquals("Off", text(assistant, R.id.settings_force_status));
        forceCard(assistant).performClick();
        ShadowAlertDialog.getLatestAlertDialog().findViewById(R.id.force_dialog_confirm).performClick();
        assertEquals(View.VISIBLE, assistant.findViewById(R.id.settings_force_end).getVisibility());

        assistant.findViewById(R.id.settings_force_end).performClick();

        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals("Maximum security", checkedTitle(assistant));
        assertEquals("Not recommended", cardBadge(assistant, 2));
        assertTrue(assistant.findViewById(R.id.onboarding_comfort_switch).isEnabled());
    }

    @Test
    public void aRotationWithTheDialogOpenKeepsItAndStartsNothingByItself() {
        ActivityController<OnboardingActivity> controller = Robolectric.buildActivity(
                OnboardingActivity.class,
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PROTECTION)).setup();
        controllers.add(controller);
        forceCard(controller.get()).performClick();

        controller.recreate();

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertFalse("restoring starts nothing", KeepADBForceMode.isActive(context));
        assertEquals("Maximum security", checkedTitle(controller.get()));
        dialog.findViewById(R.id.force_dialog_confirm).performClick();
        assertTrue(KeepADBForceMode.isActive(context));
        assertEquals("Maximum convenience (force)", checkedTitle(controller.get()));
    }

    @Test
    public void walkingTheWholeAssistantWithNextNeverStartsForce() {
        OnboardingActivity assistant = start(OnboardingActivity.fullIntent(context));
        click(assistant, R.id.onboarding_next);
        for (int i = 0; i < OnboardingActivity.buildSteps().size(); i++) {
            click(assistant, R.id.onboarding_next);
        }
        click(assistant, R.id.onboarding_next);
        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse(prefs().contains(KeepADBForceMode.KEY_STATE));
    }

    // ---- Helpers --------------------------------------------------------------------------------------------

    private OnboardingActivity start(android.content.Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller.get();
    }

    private void advanceTo(OnboardingActivity assistant, KeepADBOnboarding.Step step) {
        String wanted = null;
        for (OnboardingStep candidate : OnboardingActivity.buildSteps()) {
            if (candidate.id == step) wanted = context.getString(candidate.titleRes);
        }
        assertNotNull(step.id, wanted);
        for (int i = 0; i < 12 && !wanted.equals(text(assistant, R.id.onboarding_page_title)); i++) {
            click(assistant, R.id.onboarding_next);
        }
        assertEquals(wanted, text(assistant, R.id.onboarding_page_title));
    }

    private void click(android.app.Activity activity, int id) {
        View view = activity.findViewById(id);
        assertEquals("button " + id + " must be visible", View.VISIBLE, view.getVisibility());
        view.performClick();
    }

    private void type(OnboardingActivity assistant, String url) {
        ((EditText) assistant.findViewById(R.id.settings_webhook_url)).setText(url);
    }

    private View marker(OnboardingActivity assistant) {
        ViewGroup content = assistant.findViewById(R.id.onboarding_page_content);
        View first = content.getChildAt(0);
        assertTrue("the mark sits above the form", first instanceof TextView);
        assertEquals("Less secure", ((TextView) first).getText().toString());
        return first;
    }

    private String text(android.app.Activity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private List<View> cards(android.app.Activity activity) {
        List<View> result = new ArrayList<>();
        collect(activity.findViewById(R.id.onboarding_page_content), result);
        return result;
    }

    private void collect(View view, List<View> out) {
        if (view.getId() == R.id.choice_card) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private View forceCard(android.app.Activity activity) {
        List<View> cards = cards(activity);
        assertEquals("two presets and the force card", 3, cards.size());
        return cards.get(2);
    }

    private String checkedTitle(android.app.Activity activity) {
        String found = null;
        for (View card : cards(activity)) {
            if (card.isActivated()) {
                assertNull("exactly one card is chosen", found);
                found = ((TextView) card.findViewById(R.id.choice_title)).getText().toString();
            }
        }
        return found;
    }

    private String cardBody(android.app.Activity activity, int index) {
        return ((TextView) cards(activity).get(index).findViewById(R.id.choice_body)).getText().toString();
    }

    private String cardBadge(android.app.Activity activity, int index) {
        TextView badge = cards(activity).get(index).findViewById(R.id.choice_badge);
        return badge.getVisibility() == View.VISIBLE ? badge.getText().toString() : "";
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private Map<String, String> snapshot() {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof Set
                    ? new TreeSet<>((Set<?>) value).toString() : String.valueOf(value));
        }
        return result;
    }
}
