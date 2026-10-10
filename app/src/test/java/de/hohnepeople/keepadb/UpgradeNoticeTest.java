package de.hohnepeople.keepadb;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
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

/** Exercises the notice through MainActivity's real hand-over, not just the eligibility rule. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UpgradeNoticeTest {
    @Rule public final KeepADBNetworkResetRule reset = new KeepADBNetworkResetRule();
    private Context context;
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs().edit().clear().commit();
        KeepADBOnboarding.setAutoStartEnabledForTesting(true);
    }

    @After public void tearDown() {
        for (int i = controllers.size() - 1; i >= 0; i--) {
            controllers.get(i).pause().stop().destroy();
        }
        prefs().edit().clear().commit();
        java.util.Locale.setDefault(java.util.Locale.US);
    }

    @Test public void legacyPreferencesShowAuditedNoticeAndPersistMarker() {
        seedLegacy();
        OnboardingActivity intro = handOver();
        TextView notice = intro.findViewById(R.id.onboarding_upgrade_notice);
        assertNotNull(notice);
        assertEquals(intro.getString(R.string.onboarding_upgrade_notice), notice.getText());
        assertTrue(prefs().getBoolean(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, false));
        assertTrue(prefs().getBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, false));
        assertEquals(false, prefs().getBoolean("keep_alive_enabled", true));
        assertEquals("http://example.invalid/register/audit", prefs().getString("register_webhook_url", ""));
        assertEquals("legacy", prefs().getString("bssid_history_audit", ""));
    }

    @Test public void absentAndFalseExistingMarkerTakeOppositePaths() {
        seedLegacy();
        assertNotNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
        prefs().edit().clear().putBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, false)
                .putBoolean("keep_alive_enabled", false).commit();
        assertNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
        assertFalse(prefs().contains(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN));
    }

    @Test public void existingMarkerTrueAndNoticeAlreadyShownSuppressesNotice() {
        seedLegacy();
        prefs().edit().putBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, true)
                .putBoolean(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, true).commit();
        assertNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
    }

    @Test public void freshInstallDoesNotReceiveNoticeOrBecomeExistingOnRestart() {
        assertNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
        assertNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
        assertFalse(prefs().getBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, true));
        assertFalse(prefs().contains(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN));
    }

    @Test public void onlyMigrationBookkeepingDoesNotMakeFreshInstallExisting() {
        prefs().edit().putBoolean(KeepADBBssidHistory.KEY_LEGACY_DISCARDED, true)
                .putBoolean(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, true).commit();
        assertNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
        assertFalse(prefs().getBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, true));
    }

    @Test public void migrationAlreadyRunBeforeIntroStillAllowsRelevantLegacyNotice() {
        seedLegacy();
        KeepADBBssidHistory.discardLegacyOnce(context);
        KeepADBWarningState.observe(context);
        assertNotNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
    }

    @Test public void completedAssistantHasNoRelevantFirstAdoptionAndNoNotice() {
        seedLegacy();
        prefs().edit().putInt(KeepADBPreferences.KEY_ONBOARDING_COMPLETED_VERSION, 1).commit();
        assertNull(start(OnboardingActivity.fullIntent(context)).get()
                .findViewById(R.id.onboarding_upgrade_notice));
        assertFalse(prefs().contains(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN));
    }

    @Test public void standaloneStepDoesNotConsumeNotice() {
        seedLegacy();
        assertNull(start(OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.DETAILS))
                .get().findViewById(R.id.onboarding_upgrade_notice));
        assertFalse(prefs().contains(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN));
        assertNotNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
    }

    @Test public void restartRecreationAndBackNavigationDoNotRepeatNotice() {
        seedLegacy();
        ActivityController<OnboardingActivity> first = start(OnboardingActivity.autoStartIntent(context));
        assertNotNull(first.get().findViewById(R.id.onboarding_upgrade_notice));
        first.recreate();
        assertNull(first.get().findViewById(R.id.onboarding_upgrade_notice));
        first.get().findViewById(R.id.onboarding_next).performClick();
        first.get().onBackPressed();
        assertNull(first.get().findViewById(R.id.onboarding_upgrade_notice));
        assertNull(handOver().findViewById(R.id.onboarding_upgrade_notice));
        assertNull(start(OnboardingActivity.fullIntent(context)).get()
                .findViewById(R.id.onboarding_upgrade_notice));
    }

    @Test public void closingIntroKeepsCompletionAndReturnHomeContract() {
        seedLegacy();
        OnboardingActivity intro = handOver();
        intro.findViewById(R.id.onboarding_secondary).performClick();
        assertEquals(KeepADBOnboarding.CURRENT_VERSION,
                KeepADBPreferences.getOnboardingCompletedVersion(context));
        assertEquals(MainActivity.class.getName(),
                shadowOf(intro).getNextStartedActivity().getComponent().getClassName());
        assertTrue(prefs().getBoolean(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, false));
    }

    @Test @Config(sdk = 32, qualifiers = "w320dp-h640dp")
    public void allNineteenTranslationsAtDoubleFontFitAndFollowIntroBody() {
        RuntimeEnvironment.setFontScale(2f);
        String[] locales = {"en", "de", "es", "fr", "it", "nl", "pt", "pl", "ru",
                "uk", "tr", "id", "vi", "ar", "hi", "ja", "ko", "zh-rCN", "zh-rTW"};
        String english = null;
        for (String locale : locales) {
            RuntimeEnvironment.setQualifiers(locale + "-w320dp-h640dp");
            prefs().edit().clear().commit();
            seedLegacy();
            KeepADBPreferences.setAppLanguage(context, locale.replace("-r", "-"));
            OnboardingActivity intro = handOver();
            TextView notice = intro.findViewById(R.id.onboarding_upgrade_notice);
            assertNotNull(locale, notice);
            String text = notice.getText().toString();
            if (english == null) english = text;
            else assertNotEquals("real translation for " + locale, english, text);
            assertTrue(locale + " uses visible overview label", text.contains(intro.getString(R.string.warnings_title)));
            assertEquals(2f, intro.getResources().getConfiguration().fontScale, 0.001f);
            OnboardingLayoutAssertions.assertFits(intro, 320, locale);
            assertEquals(R.id.onboarding_page_question, notice.getAccessibilityTraversalAfter());
            assertEquals(text, notice.createAccessibilityNodeInfo().getText().toString());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, notice.getImportantForAccessibility());
            assertEquals(notice, ((ViewGroup) intro.findViewById(R.id.onboarding_page_content)).getChildAt(0));
        }
    }

    private void seedLegacy() {
        prefs().edit().putBoolean("keep_alive_enabled", false)
                .putString("register_webhook_url", "http://example.invalid/register/audit")
                .putString("bssid_history_audit", "legacy").commit();
    }
    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
    private ActivityController<OnboardingActivity> start(Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller;
    }
    private OnboardingActivity handOver() {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(main);
        Intent intent = shadowOf(main.get()).peekNextStartedActivity();
        assertNotNull("real home hand-over", intent);
        return start(intent).get();
    }
}
