package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The setup assistant (#761) through its real entry points: the home screen's hand-over, the
 * Settings rows and the activity's own buttons. The invariants of the issue each get both sides:
 * "Next" without input changes nothing, and a changed card is stored.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingActivityTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBOnboarding.setAutoStartEnabledForTesting(true);
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

    // ---- Hand-over from the home screen -----------------------------------------------------------

    @Test
    public void homeScreenHandsOverUntilTheAssistantWasClosed() {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(main);
        Intent handOver = shadowOf(main.get()).getNextStartedActivity();

        assertTrue("the home screen steps aside", main.get().isFinishing());
        assertEquals(OnboardingActivity.class.getName(), handOver.getComponent().getClassName());

        OnboardingActivity assistant = start(handOver);
        click(assistant, R.id.onboarding_secondary); // Later
        assertTrue(assistant.isFinishing());
        assertEquals(KeepADBOnboarding.CURRENT_VERSION,
                KeepADBPreferences.getOnboardingCompletedVersion(context));
        // Closing opens the home screen; take that start off the queue before the next check.
        assertEquals(MainActivity.class.getName(),
                shadowOf(assistant).getNextStartedActivity().getComponent().getClassName());

        // The other side: once closed, the home screen stays.
        ActivityController<MainActivity> again =
                Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(again);
        assertFalse(again.get().isFinishing());
        assertNull(shadowOf(again.get()).peekNextStartedActivity());
    }

    @Test
    public void closingTheAssistantOpensTheHomeScreenOnlyAfterTheHandOver() {
        OnboardingActivity handedOver = start(OnboardingActivity.autoStartIntent(context));
        click(handedOver, R.id.onboarding_secondary);
        Intent next = shadowOf(handedOver).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());

        // From the Settings the same close returns to the Settings, not to a second home screen.
        OnboardingActivity fromSettings = start(OnboardingActivity.fullIntent(context));
        click(fromSettings, R.id.onboarding_secondary);
        assertNull(shadowOf(fromSettings).getNextStartedActivity());
        assertTrue(fromSettings.isFinishing());
    }

    // ---- New installation: Next on every step == Later on the intro ---------------------------------

    @Test
    public void newInstallNextOnEveryStepStoresWhatLaterStores() {
        Map<String, String> later = runNewInstall(false);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        Map<String, String> walkedThrough = runNewInstall(true);

        assertEquals("Next without input must store exactly what Later stores", later, walkedThrough);
        assertEquals("false", walkedThrough.get(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL));
        assertFalse("no setting key is written by a step", walkedThrough.containsKey("keep_alive_enabled"));
        assertFalse(walkedThrough.containsKey(KeepADBPreferences.KEY_NOTIFICATION_DETAILS_ENABLED));
    }

    @Test
    public void newInstallStartsOnTheSafeChoices() {
        OnboardingActivity assistant = startHandedOver();
        click(assistant, R.id.onboarding_next); // to Keep-Alive
        assertEquals("Off", checkedTitle(assistant)); // F2: Keep-Alive preselected Off
        click(assistant, R.id.onboarding_next); // to protection
        assertEquals(context.getString(R.string.force_level_maximum), checkedTitle(assistant));
        advanceTo(assistant, KeepADBOnboarding.Step.DETAILS); // past permissions and Wi-Fi
        assertEquals("Off", checkedTitle(assistant));
    }

    @Test
    public void newInstallChangedCardIsStored() {
        OnboardingActivity assistant = startHandedOver();
        click(assistant, R.id.onboarding_next);
        cards(assistant).get(1).performClick(); // Keep-Alive: On
        click(assistant, R.id.onboarding_next);
        cards(assistant).get(1).performClick(); // Balanced
        advanceTo(assistant, KeepADBOnboarding.Step.DETAILS);
        cards(assistant).get(1).performClick(); // details: On
        click(assistant, R.id.onboarding_next);
        click(assistant, R.id.onboarding_next); // Done

        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertTrue(KeepADBPreferences.isNotificationDetailsEnabled(context));
    }

    private Map<String, String> runNewInstall(boolean walkThrough) {
        OnboardingActivity assistant = startHandedOver();
        if (!walkThrough) {
            click(assistant, R.id.onboarding_secondary);
        } else {
            click(assistant, R.id.onboarding_next); // intro -> keep-alive
            for (int i = 0; i < OnboardingActivity.buildSteps().size(); i++) {
                click(assistant, R.id.onboarding_next);
            }
            click(assistant, R.id.onboarding_next); // Done on the summary
        }
        assertTrue(assistant.isFinishing());
        // Both ends are followed by the same read of the home screen.
        KeepADBTrustedNetwork.getMode(context);
        return snapshot();
    }

    // ---- Existing installation: Next changes nothing -----------------------------------------------

    @Test
    public void existingInstallNextWithoutInputLeavesEveryKeyUnchanged() {
        for (int variant = 0; variant < 3; variant++) {
            prefs().edit().clear().commit();
            KeepADB.resetForTesting();
            seedExisting(variant);
            OnboardingActivity assistant = startHandedOver();
            Map<String, String> before = snapshotWithoutAssistantKeys();
            assertFalse(before.isEmpty());

            click(assistant, R.id.onboarding_next);
            for (int i = 0; i < OnboardingActivity.buildSteps().size(); i++) {
                click(assistant, R.id.onboarding_next);
            }
            click(assistant, R.id.onboarding_next);

            assertEquals("variant " + variant + ": Next must not move any stored key", before,
                    snapshotWithoutAssistantKeys());
            assertEquals(KeepADBOnboarding.CURRENT_VERSION,
                    KeepADBPreferences.getOnboardingCompletedVersion(context));
        }
    }

    @Test
    public void existingInstallChangedCardIsStoredAndKeepsTheOldLists() {
        seedExisting(0); // all Wi-Fi, details on, webhook over http
        OnboardingActivity assistant = startHandedOver();
        click(assistant, R.id.onboarding_next);
        click(assistant, R.id.onboarding_next); // protection: legacy card is preselected
        List<View> protection = cards(assistant);
        assertEquals(4, protection.size()); // legacy card, two presets, force
        assertEquals(context.getString(R.string.force_level_legacy_all), title(protection.get(0)));
        assertTrue(protection.get(0).isActivated());
        protection.get(1).performClick(); // Maximum security
        advanceTo(assistant, KeepADBOnboarding.Step.DETAILS);
        cards(assistant).get(0).performClick(); // details: Off
        click(assistant, R.id.onboarding_next);
        click(assistant, R.id.onboarding_next);

        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertFalse(KeepADBPreferences.isNotificationDetailsEnabled(context));
        assertTrue("Keep-Alive was not touched", KeepADBPreferences.isKeepAliveEnabled(context));
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertEquals("the name list stays stored", 1, KeepADBTrustedNetwork.getSsidEntries(context).size());
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void leavingThePreviousNameListSwitchesItOffButKeepsItsEntries() {
        seedExisting(1); // allowlist + name list
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        OnboardingActivity assistant = start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PROTECTION));
        List<View> cards = cards(assistant);
        assertEquals(context.getString(R.string.force_level_legacy_names), title(cards.get(0)));
        cards.get(2).performClick(); // Balanced
        click(assistant, R.id.onboarding_next);

        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertEquals(1, KeepADBTrustedNetwork.getSsidEntries(context).size());
    }

    // ---- Markings ------------------------------------------------------------------------------------

    @Test
    public void lessSecureIsMarkedForExactlyTheFourListedValues() {
        assertTrue("defaults carry no mark", KeepADBOnboarding.lessSecure(context).isEmpty());

        // Values the concept does NOT list must stay unmarked.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, "https://example.org/register/x");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertTrue(KeepADBOnboarding.lessSecure(context).isEmpty());
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://example.org/register/x");
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertTrue("a disabled webhook sends nothing", KeepADBOnboarding.lessSecure(context).isEmpty());
        prefs().edit().clear().commit();

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertEquals(List.of(KeepADBOnboarding.LessSecure.PROTECTION_ALL_WIFI),
                KeepADBOnboarding.lessSecure(context));
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        assertEquals(List.of(KeepADBOnboarding.LessSecure.NOTIFICATION_DETAILS),
                KeepADBOnboarding.lessSecure(context));
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);

        KeepADBPreferences.setRegisterWebhookUrl(context, "http://example.org/register/x");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertEquals(List.of(KeepADBOnboarding.LessSecure.WEBHOOK_CLEARTEXT),
                KeepADBOnboarding.lessSecure(context));
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);

        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DEFAULT, true));
        assertEquals(List.of(KeepADBOnboarding.LessSecure.FORCE_MODE),
                KeepADBOnboarding.lessSecure(context));
    }

    @Test
    public void badgesFollowTheStoredValueOnTheCards() {
        // All Wi-Fi + details on: both cards carry "Less secure", the neutral cards do not.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        OnboardingActivity protection = start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PROTECTION));
        assertEquals(List.of("Less secure", "Recommended", "", "Not recommended"), badges(protection));
        OnboardingActivity details = start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.DETAILS));
        assertEquals(List.of("Recommended", "Less secure"), badges(details));

        // Balanced and the name list are a note, never "Less secure".
        prefs().edit().clear().commit();
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertEquals(List.of("Recommended", "Note", "Not recommended"), badges(start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PROTECTION))));

        // The previous name list is a note as well: its card carries "Note", not "Less secure".
        prefs().edit().clear().commit();
        KeepADBTrustedNetwork.addSsid(context, "Garten");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertEquals(List.of("Note", "Recommended", "", "Not recommended"), badges(start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PROTECTION))));

        // Defaults: nothing is marked less secure on any step.
        prefs().edit().clear().commit();
        for (KeepADBOnboarding.Step step : KeepADBOnboarding.Step.values()) {
            for (String badge : badges(start(OnboardingActivity.stepIntent(context, step)))) {
                assertFalse(step.id, "Less secure".equals(badge));
            }
        }
    }

    @Test
    public void existingIntroCountsTheMarkedSettings() {
        seedExisting(0);
        OnboardingActivity assistant = startHandedOver();
        assertEquals(context.getString(R.string.onboarding_intro_title_existing), pageTitle(assistant));
        assertTrue(allText(assistant).contains(
                context.getString(R.string.onboarding_intro_less_secure)));

        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        OnboardingActivity fresh = startHandedOver();
        assertEquals(context.getString(R.string.onboarding_intro_title_new,
                OnboardingActivity.buildSteps().size()), pageTitle(fresh));
    }

    // ---- Single-step mode ---------------------------------------------------------------------------

    @Test
    public void singleStepModeWorksForEveryStep() {
        for (KeepADBOnboarding.Step step : KeepADBOnboarding.Step.values()) {
            prefs().edit().clear().commit();
            OnboardingStep expected = null;
            for (OnboardingStep candidate : OnboardingActivity.buildSteps()) {
                if (candidate.id == step) expected = candidate;
            }
            assertNotNull("a step without a class: " + step, expected);

            OnboardingActivity assistant = start(OnboardingActivity.stepIntent(context, step));
            assertEquals(step.id, context.getString(expected.titleRes),
                    text(assistant, R.id.onboarding_header_title));
            assertEquals(View.GONE, assistant.findViewById(R.id.onboarding_header_counter).getVisibility());
            assertEquals(View.GONE, assistant.findViewById(R.id.onboarding_progress).getVisibility());
            assertEquals(View.GONE, assistant.findViewById(R.id.onboarding_back).getVisibility());
            assertEquals(View.GONE, assistant.findViewById(R.id.onboarding_secondary).getVisibility());
            assertEquals(context.getString(R.string.onboarding_done),
                    text(assistant, R.id.onboarding_next));
            assertTrue("a step shows something: " + step,
                    ((ViewGroup) assistant.findViewById(R.id.onboarding_page_content))
                            .getChildCount() > 0);

            click(assistant, R.id.onboarding_next);
            assertTrue(assistant.isFinishing());
            assertEquals("the single step never marks the assistant closed", 0,
                    KeepADBPreferences.getOnboardingCompletedVersion(context));
        }
    }

    /**
     * A notification target tapped before the assistant was ever closed (#782): leaving the single
     * step opens the home screen, which stays -- the one answered question does not turn into the
     * full assistant. Only the next plain start hands over, once; every way out of that (here Back
     * on its intro) marks it closed. No detour after the step, and no loop.
     */
    @Test
    public void aNotificationTargetBeforeTheFirstCloseDoesNotHandOverAfterTheStepAndNoLoop() {
        OnboardingActivity single = start(OnboardingActivity.notificationIntent(context,
                KeepADBOnboarding.Step.PERMISSIONS, OnboardingActionSteps.Permissions.ITEM_SYSTEM));
        click(single, R.id.onboarding_next); // Done
        Intent home = shadowOf(single).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), home.getComponent().getClassName());
        assertEquals(0, KeepADBPreferences.getOnboardingCompletedVersion(context));

        ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class, home).setup();
        controllers.add(main);
        assertFalse("the home screen stays after the single step", main.get().isFinishing());
        assertNull(shadowOf(main.get()).peekNextStartedActivity());
        assertEquals("the assistant is still not closed", 0,
                KeepADBPreferences.getOnboardingCompletedVersion(context));

        // The next plain start hands over to the full assistant once, as before.
        ActivityController<MainActivity> plain = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(plain);
        Intent handOver = shadowOf(plain.get()).getNextStartedActivity();
        assertTrue(plain.get().isFinishing());
        assertEquals(OnboardingActivity.class.getName(), handOver.getComponent().getClassName());
        assertNull("the hand-over is the full assistant", handOver.getStringExtra(OnboardingActivity.EXTRA_STEP));

        OnboardingActivity full = start(handOver);
        full.onBackPressed(); // Back on the intro acts as Later
        Intent again = shadowOf(full).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), again.getComponent().getClassName());
        ActivityController<MainActivity> second =
                Robolectric.buildActivity(MainActivity.class, again).setup();
        controllers.add(second);
        assertFalse("the home screen stays: no second hand-over", second.get().isFinishing());
        assertNull(shadowOf(second.get()).peekNextStartedActivity());
    }

    /** Single-step mode: the close button leaves too, and never marks the assistant closed. */
    @Test
    public void theCloseButtonOfASingleStepDoesNotMarkTheAssistantClosed() {
        OnboardingActivity single = start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.DETAILS));
        click(single, R.id.onboarding_close);
        assertTrue(single.isFinishing());
        assertEquals(0, KeepADBPreferences.getOnboardingCompletedVersion(context));
    }

    @Test
    public void singleStepDoneStoresTheChoiceAndBackDoesNot() {
        OnboardingActivity done = start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.DETAILS));
        cards(done).get(1).performClick();
        click(done, R.id.onboarding_next);
        assertTrue(KeepADBPreferences.isNotificationDetailsEnabled(context));

        prefs().edit().clear().commit();
        OnboardingActivity back = start(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.DETAILS));
        cards(back).get(1).performClick();
        back.onBackPressed();
        assertTrue(back.isFinishing());
        assertFalse(KeepADBPreferences.isNotificationDetailsEnabled(context));
    }

    @Test
    public void unknownStepFallsBackToTheFullAssistant() {
        OnboardingActivity assistant = start(
                OnboardingActivity.fullIntent(context).putExtra(OnboardingActivity.EXTRA_STEP, "nope"));
        assertEquals(context.getString(R.string.onboarding_intro_title_existing), pageTitle(assistant));
    }

    // ---- Back --------------------------------------------------------------------------------------

    @Test
    public void systemBackGoesOneStepBackAndOnTheIntroActsAsLater() {
        OnboardingActivity assistant = startHandedOver();
        click(assistant, R.id.onboarding_next); // Keep-Alive
        click(assistant, R.id.onboarding_next); // protection
        assertEquals(context.getString(R.string.onboarding_protection_title), pageTitle(assistant));

        assistant.onBackPressed();
        assertEquals(context.getString(R.string.settings_section_keep_alive), pageTitle(assistant));
        assistant.onBackPressed();
        assertEquals(context.getString(R.string.onboarding_intro_title_new,
                OnboardingActivity.buildSteps().size()), pageTitle(assistant));
        assertFalse(assistant.isFinishing());
        assertEquals(0, KeepADBPreferences.getOnboardingCompletedVersion(context));

        assistant.onBackPressed(); // on the intro: Later
        assertTrue(assistant.isFinishing());
        assertEquals(KeepADBOnboarding.CURRENT_VERSION,
                KeepADBPreferences.getOnboardingCompletedVersion(context));
    }

    @Test
    public void backDiscardsThePendingChoiceOfTheLeftPage() {
        OnboardingActivity assistant = startHandedOver();
        click(assistant, R.id.onboarding_next);
        cards(assistant).get(1).performClick(); // Keep-Alive On, not yet taken over
        assistant.onBackPressed();
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));
        click(assistant, R.id.onboarding_next);
        assertEquals("Off", checkedTitle(assistant));
    }

    // ---- Settings entries ---------------------------------------------------------------------------

    @Test
    public void settingsRowsOpenTheAssistantAndTheProtectionStep() {
        ActivityController<SettingsActivity> settings =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controllers.add(settings);
        SettingsActivity activity = settings.get();

        assertTrue(text(activity, R.id.settings_onboarding_summary).contains(
                context.getString(R.string.force_level_maximum)));
        activity.findViewById(R.id.settings_onboarding_row).performClick();
        Intent whole = shadowOf(activity).getNextStartedActivity();
        assertEquals(new ComponentName(context, OnboardingActivity.class), whole.getComponent());
        assertFalse(whole.hasExtra(OnboardingActivity.EXTRA_STEP));

        activity.findViewById(R.id.network_level_line).performClick();
        Intent step = shadowOf(activity).getNextStartedActivity();
        assertEquals(KeepADBOnboarding.Step.PROTECTION.id,
                step.getStringExtra(OnboardingActivity.EXTRA_STEP));
        assertTrue(activity.findViewById(R.id.network_level_line).isClickable());
    }

    // ---- Rotation and accessibility -----------------------------------------------------------------

    @Test
    public void rotationKeepsThePageAndThePendingChoice() {
        ActivityController<OnboardingActivity> controller = Robolectric.buildActivity(
                OnboardingActivity.class, OnboardingActivity.fullIntent(context)).setup();
        controllers.add(controller);
        click(controller.get(), R.id.onboarding_next);
        cards(controller.get()).get(1).performClick(); // Keep-Alive On, pending
        controller.recreate();
        OnboardingActivity again = controller.get();

        assertEquals(context.getString(R.string.settings_section_keep_alive), pageTitle(again));
        assertEquals("On", checkedTitle(again));
        assertFalse("pending, not stored", KeepADBPreferences.isKeepAliveEnabled(context));
    }

    @Test
    public void choiceCardIsOneRadioButtonNodeWithItsTextAndState() {
        OnboardingActivity assistant = startHandedOver();
        click(assistant, R.id.onboarding_next);
        List<View> cards = cards(assistant);
        AccessibilityNodeInfo off = cards.get(0).createAccessibilityNodeInfo();
        AccessibilityNodeInfo on = cards.get(1).createAccessibilityNodeInfo();

        assertEquals("android.widget.RadioButton", off.getClassName().toString());
        assertTrue(off.isCheckable());
        assertTrue(off.isChecked());
        assertFalse(on.isChecked());
        assertTrue(off.getContentDescription().toString().contains(
                context.getString(R.string.onboarding_keep_alive_off_body)));
    }

    @Test
    @Config(sdk = 34, qualifiers = "w320dp-h640dp")
    public void narrowDisplayWithLargeFontKeepsEverythingInsideAndStacksTheBar() {
        RuntimeEnvironment.setFontScale(2.0f);
        OnboardingActivity assistant = startHandedOver();
        assertEquals(2.0f, assistant.getResources().getConfiguration().fontScale, 0.001f);
        assertEquals(320, assistant.getResources().getConfiguration().screenWidthDp);
        int steps = OnboardingActivity.buildSteps().size();
        for (int page = 0; page <= steps + 1; page++) {
            View root = assistant.getWindow().getDecorView();
            int width = Math.round(320 * context.getResources().getDisplayMetrics().density);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(8000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            assertInside(root, 0, width, "page " + page);

            android.widget.LinearLayout bar = assistant.findViewById(R.id.onboarding_bottom_bar);
            assertEquals("stacked bar", android.widget.LinearLayout.VERTICAL, bar.getOrientation());
            View next = assistant.findViewById(R.id.onboarding_next);
            assertEquals("Next fills the bar", bar.getMeasuredWidth() - bar.getPaddingLeft()
                    - bar.getPaddingRight(), next.getMeasuredWidth());
            if (page <= steps) click(assistant, R.id.onboarding_next);
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------------

    /** Every visible view lies within the screen width; no text is cut by an ellipsis or a line cap. */
    private void assertInside(View view, int parentLeft, int width, String where) {
        if (view.getVisibility() != View.VISIBLE) return;
        int left = parentLeft + view.getLeft();
        assertTrue(where + ": " + view.getClass().getSimpleName() + " ends at " + (left + view.getWidth())
                + " of " + width, left + view.getWidth() <= width + 1);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNull(where + ": no ellipsis", text.getEllipsize());
            // An input field is one line on purpose (a URL scrolls sideways); it cuts no text.
            if (!(view instanceof android.widget.EditText)) {
                assertTrue(where + ": no line cap", text.getMaxLines() >= 100);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertInside(group.getChildAt(i), left - view.getScrollX(), width, where);
            }
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private OnboardingActivity start(Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller.get();
    }

    /** Opens the assistant the way a user reaches it: through the home screen's hand-over. */
    private OnboardingActivity startHandedOver() {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(main);
        Intent handOver = shadowOf(main.get()).peekNextStartedActivity();
        assertNotNull("the home screen did not hand over", handOver);
        return start(handOver);
    }

    /** Presses "Next" until the page of {@code step} shows (from the intro or from an earlier step). */
    private void advanceTo(OnboardingActivity assistant, KeepADBOnboarding.Step step) {
        String wanted = null;
        for (OnboardingStep candidate : OnboardingActivity.buildSteps()) {
            if (candidate.id == step) wanted = context.getString(candidate.titleRes);
        }
        assertNotNull(step.id, wanted);
        for (int i = 0; i < 12 && !wanted.equals(pageTitle(assistant)); i++) {
            click(assistant, R.id.onboarding_next);
        }
        assertEquals(wanted, pageTitle(assistant));
    }

    private void click(android.app.Activity activity, int id) {
        View view = activity.findViewById(id);
        assertEquals("button " + id + " must be visible", View.VISIBLE, view.getVisibility());
        view.performClick();
    }

    private String text(android.app.Activity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private String pageTitle(OnboardingActivity activity) {
        return text(activity, R.id.onboarding_page_title);
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

    private String title(View card) {
        return ((TextView) card.findViewById(R.id.choice_title)).getText().toString();
    }

    private String checkedTitle(android.app.Activity activity) {
        String found = null;
        for (View card : cards(activity)) {
            if (card.isActivated()) {
                assertNull("exactly one card is chosen", found);
                found = title(card);
            }
        }
        return found;
    }

    private List<String> badges(android.app.Activity activity) {
        List<String> result = new ArrayList<>();
        for (View card : cards(activity)) {
            TextView badge = card.findViewById(R.id.choice_badge);
            result.add(badge.getVisibility() == View.VISIBLE ? badge.getText().toString() : "");
        }
        return result;
    }

    private String allText(android.app.Activity activity) {
        StringBuilder out = new StringBuilder();
        appendText(activity.findViewById(R.id.onboarding_page), out);
        return out.toString();
    }

    private void appendText(View view, StringBuilder out) {
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) appendText(group.getChildAt(i), out);
        }
    }

    /**
     * An existing installation, three flavours. 0: all Wi-Fi, details on, Keep-Alive on, webhook
     * over http, previous name list stored; 1: allowlist plus an active name list; 2: balanced,
     * everything else default except unrelated settings.
     */
    private void seedExisting(int variant) {
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean("last_desired_on", true);
        editor.putString("app_language", "");
        editor.putBoolean("privacy_mode_enabled", variant == 2);
        editor.putBoolean("advice_banner_visible", false);
        editor.commit();
        KeepADBTrustedNetwork.addBssid(context, "AA:BB:CC:11:22:33", "Heimnetz");
        KeepADBTrustedNetwork.addSsid(context, "Garten");
        if (variant == 0) {
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
            prefs().edit().putBoolean("keep_alive_enabled", true)
                    .putBoolean(KeepADBPreferences.KEY_NOTIFICATION_DETAILS_ENABLED, true)
                    .commit();
            KeepADBPreferences.setRegisterWebhookUrl(context, "http://100.111.111.21:50829/register/s20");
            KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        } else if (variant == 1) {
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        } else {
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
            KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
            prefs().edit().putBoolean("keep_alive_enabled", true).commit();
        }
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

    private Map<String, String> snapshotWithoutAssistantKeys() {
        Map<String, String> result = snapshot();
        result.remove(KeepADBPreferences.KEY_ONBOARDING_COMPLETED_VERSION);
        result.remove(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL);
        return result;
    }
}
