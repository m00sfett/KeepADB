package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import java.util.ArrayList;
import java.util.List;

/** #791 (F5, F6, F8): the setup assistant's bottom bar, URL field and intro icon. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingDisplayFindingsTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBPreferences.setAppLanguage(context, "en");
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
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
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private OnboardingActivity open(android.content.Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller.get();
    }

    // ---- F5 ---------------------------------------------------------------------------------------

    @Test
    @Config(sdk = 34, qualifiers = "w320dp-h640dp")
    public void onANarrowLargeFontDisplayBackAndSkipShareOneRowBelowNext() {
        RuntimeEnvironment.setFontScale(2.0f);
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        int rowsWithPair = 0;
        for (int page = 0; page < 5; page++) {
            Button back = assistant.findViewById(R.id.onboarding_back);
            Button skip = assistant.findViewById(R.id.onboarding_secondary);
            LinearLayout bar = assistant.findViewById(R.id.onboarding_bottom_bar);
            if (back.getVisibility() == View.VISIBLE && skip.getVisibility() == View.VISIBLE) {
                rowsWithPair++;
                assertSame("Back and Skip sit in one row", back.getParent(), skip.getParent());
                assertNotSame("... which is not the bar itself", bar, back.getParent());
                assertEquals("the bar is Next plus that row", 2, bar.getChildCount());
                assertSame("Next stays on top", assistant.findViewById(R.id.onboarding_next),
                        bar.getChildAt(0));
            }
            assistant.findViewById(R.id.onboarding_next).performClick();
        }
        assertTrue("some page has both buttons", rowsWithPair > 0);
    }

    @Test
    @Config(sdk = 34, qualifiers = "w320dp-h640dp")
    public void aPageWithOnlyOneSecondaryButtonStaysStackedAndKeepsItsButtons() {
        RuntimeEnvironment.setFontScale(2.0f);
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        // The intro has "Later" and no Back: stacked, not paired.
        LinearLayout bar = assistant.findViewById(R.id.onboarding_bottom_bar);
        assertSame(bar, assistant.findViewById(R.id.onboarding_secondary).getParent());
        assistant.findViewById(R.id.onboarding_next).performClick();
        assistant.findViewById(R.id.onboarding_back).performClick();
        assertSame("coming back rebuilds the bar without a stale pair row", bar,
                assistant.findViewById(R.id.onboarding_secondary).getParent());
        assertEquals(View.VISIBLE, assistant.findViewById(R.id.onboarding_next).getVisibility());
    }

    // ---- F6 ---------------------------------------------------------------------------------------

    @Test
    public void theWebhookUrlFieldMayWrapSoTheHintIsNotCutAndStillTakesNoLineBreak() {
        OnboardingActivity assistant = open(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.WEBHOOK));
        EditText url = assistant.findViewById(R.id.settings_webhook_url);
        assertTrue("multi-line input lets the hint wrap",
                (url.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        assertTrue("it stays a URL field",
                (url.getInputType() & InputType.TYPE_TEXT_VARIATION_URI) != 0);
        url.setText("http://host:80/\nregister/x\r");
        assertEquals("a URL has no line breaks", "http://host:80/register/x",
                url.getText().toString());
    }

    // ---- F8 ---------------------------------------------------------------------------------------

    @Test
    public void theIntroIconStandsAboveTheTitleAndIsGoneOnTheNextPage() {
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        View icon = assistant.findViewById(R.id.onboarding_page_icon);
        View title = assistant.findViewById(R.id.onboarding_page_title);
        assertEquals(View.VISIBLE, icon.getVisibility());
        ViewGroup page = (ViewGroup) title.getParent();
        assertSame(page, icon.getParent());
        assertTrue("icon above title", page.indexOfChild(icon) < page.indexOfChild(title));

        assistant.findViewById(R.id.onboarding_next).performClick();
        assertEquals(View.GONE, icon.getVisibility());
    }

    private static Button find(View root, String label) {
        if (root instanceof Button && label.contentEquals(((TextView) root).getText())) {
            return (Button) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button hit = find(group.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
