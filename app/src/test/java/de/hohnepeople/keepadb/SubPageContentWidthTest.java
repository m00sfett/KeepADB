package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #975: limit content width of sub-pages on tablets and wide windows.
 * Sub-pages keep a centered column capped at {@code R.dimen.main_content_max_width} (800dp)
 * on wide displays (&gt; 800dp), while remaining full-width on phones (&lt; 800dp).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SubPageContentWidthTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- layout invariant checks ----------------------------------------------------------------

    @Test
    public void contentColumnLayoutCapsAtMaximumWidthOnWideScreen() {
        RuntimeEnvironment.setQualifiers("w1024dp-h768dp");
        float density = context.getResources().getDisplayMetrics().density;
        int maxPx = context.getResources().getDimensionPixelSize(R.dimen.main_content_max_width);
        assertEquals(Math.round(800 * density), maxPx);

        KeepADBContentColumnLayout layout = new KeepADBContentColumnLayout(context);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int width1024Px = Math.round(1024 * density);
        layout.measure(View.MeasureSpec.makeMeasureSpec(width1024Px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(768 * density), View.MeasureSpec.EXACTLY));
        assertEquals("Caps at 800dp on 1024dp width", maxPx, layout.getMeasuredWidth());

        // Without the cap, a standard LinearLayout measures the full 1024dp
        LinearLayout plain = new LinearLayout(context);
        plain.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        plain.measure(View.MeasureSpec.makeMeasureSpec(width1024Px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(768 * density), View.MeasureSpec.EXACTLY));
        assertEquals("Standard layout would stretch to 1024dp", width1024Px, plain.getMeasuredWidth());
    }

    @Test
    public void contentColumnLayoutPreservesFullWidthOnPhone() {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp");
        float density = context.getResources().getDisplayMetrics().density;
        int width360Px = Math.round(360 * density);

        KeepADBContentColumnLayout layout = new KeepADBContentColumnLayout(context);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        layout.measure(View.MeasureSpec.makeMeasureSpec(width360Px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(640 * density), View.MeasureSpec.EXACTLY));
        assertEquals("Measures full 360dp on phone and does NOT force 800dp", width360Px, layout.getMeasuredWidth());
    }

    @Test
    public void contentColumnLayoutCapsWhenMeasureSpecUnspecified() {
        float density = context.getResources().getDisplayMetrics().density;
        int maxPx = context.getResources().getDimensionPixelSize(R.dimen.main_content_max_width);

        KeepADBContentColumnLayout layout = new KeepADBContentColumnLayout(context);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        layout.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(Math.round(600 * density), View.MeasureSpec.EXACTLY));
        assertEquals("Caps at 800dp when UNSPECIFIED", maxPx, layout.getMeasuredWidth());
    }

    @Test
    public void networkDecisionViewCapsAtMaximumWidthOnWideScreen() {
        RuntimeEnvironment.setQualifiers("w1024dp-h768dp");
        float density = context.getResources().getDisplayMetrics().density;
        int maxPx = context.getResources().getDimensionPixelSize(R.dimen.main_content_max_width);
        int width1024Px = Math.round(1024 * density);

        NetworkDecisionView view = new NetworkDecisionView(context);
        view.measure(View.MeasureSpec.makeMeasureSpec(width1024Px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(768 * density), View.MeasureSpec.AT_MOST));
        assertEquals("Decision view caps at 800dp on 1024dp", maxPx, view.getMeasuredWidth());
    }

    @Test
    public void networkDecisionViewPreservesFullWidthOnPhone() {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp");
        float density = context.getResources().getDisplayMetrics().density;
        int width360Px = Math.round(360 * density);

        NetworkDecisionView view = new NetworkDecisionView(context);
        view.measure(View.MeasureSpec.makeMeasureSpec(width360Px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(640 * density), View.MeasureSpec.AT_MOST));
        assertEquals("Decision view preserves 360dp on phone", width360Px, view.getMeasuredWidth());
    }

    // --- sub-page activity and layout checks on tablet / wide screen ---------------------------

    @Test
    public void allSubPagesCapAndCenterContentColumnOnTabletWideScreen() {
        RuntimeEnvironment.setQualifiers("w1024dp-h768dp");
        float density = context.getResources().getDisplayMetrics().density;
        int maxPx = context.getResources().getDimensionPixelSize(R.dimen.main_content_max_width);
        int width1024Px = Math.round(1024 * density);
        assertEquals(Math.round(800 * density), maxPx);

        // 1. ProtectionActivity
        ProtectionActivity protection = Robolectric.buildActivity(ProtectionActivity.class).setup().get();
        measureAndLayout(protection, 1024, 768);
        ScrollView protectionScroll = protection.findViewById(R.id.protection_scroll);
        View protectionColumn = protectionScroll.getChildAt(0);
        assertTrue(protectionColumn instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, protectionColumn.getMeasuredWidth());
        assertEquals(maxPx, protectionColumn.getWidth());
        assertCentered(protectionScroll.getWidth(), protectionColumn);

        // 2. SettingsActivity
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        measureAndLayout(settings, 1024, 768);
        ScrollView settingsScroll = settings.findViewById(R.id.settings_scroll_view);
        View settingsColumn = settingsScroll.getChildAt(0);
        assertTrue(settingsColumn instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, settingsColumn.getMeasuredWidth());
        assertEquals(maxPx, settingsColumn.getWidth());
        assertCentered(settingsScroll.getWidth(), settingsColumn);

        // 3. NetworkListActivity
        NetworkListActivity networkList = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context)).setup().get();
        measureAndLayout(networkList, 1024, 768);
        ScrollView networkListScroll = networkList.findViewById(R.id.network_list_scroll);
        View networkListColumn = networkListScroll.getChildAt(0);
        assertTrue(networkListColumn instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, networkListColumn.getMeasuredWidth());
        assertEquals(maxPx, networkListColumn.getWidth());
        assertCentered(networkListScroll.getWidth(), networkListColumn);

        // 4. ReportHistoryActivity
        ReportHistoryActivity reportHistory = Robolectric.buildActivity(ReportHistoryActivity.class).setup().get();
        measureAndLayout(reportHistory, 1024, 768);
        ScrollView reportHistoryScroll = reportHistory.findViewById(R.id.report_history_scroll);
        View reportHistoryColumn = reportHistoryScroll.getChildAt(0);
        assertTrue(reportHistoryColumn instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, reportHistoryColumn.getMeasuredWidth());
        assertEquals(maxPx, reportHistoryColumn.getWidth());
        assertCentered(reportHistoryScroll.getWidth(), reportHistoryColumn);

        // 5. WarningsActivity
        WarningsActivity warnings = Robolectric.buildActivity(WarningsActivity.class).setup().get();
        measureAndLayout(warnings, 1024, 768);
        ScrollView warningsScroll = warnings.findViewById(R.id.warnings_scroll);
        View warningsColumn = warnings.findViewById(R.id.warnings_content);
        assertTrue(warningsColumn instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, warningsColumn.getMeasuredWidth());
        assertEquals(maxPx, warningsColumn.getWidth());
        assertCentered(warningsScroll.getWidth(), warningsColumn);

        // 6. activity_widget_config layout inflation (scroll content + save button area)
        View widgetConfigRoot = LayoutInflater.from(context).inflate(R.layout.activity_widget_config, null);
        measureAndLayout(widgetConfigRoot, 1024, 768);
        ScrollView widgetConfigScroll = widgetConfigRoot.findViewById(R.id.widget_config_scroll);
        View widgetConfigColumn = widgetConfigScroll.getChildAt(0);
        assertTrue(widgetConfigColumn instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, widgetConfigColumn.getMeasuredWidth());
        assertEquals(maxPx, widgetConfigColumn.getWidth());
        assertCentered(widgetConfigScroll.getWidth(), widgetConfigColumn);

        View saveButton = widgetConfigRoot.findViewById(R.id.widget_config_save);
        View saveWrapper = (View) saveButton.getParent();
        assertTrue(saveWrapper instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, saveWrapper.getMeasuredWidth());
        assertEquals(maxPx, saveWrapper.getWidth());
        View widgetConfigBody = widgetConfigRoot.findViewById(R.id.widget_config_body);
        assertCentered(widgetConfigBody.getWidth(), saveWrapper);

        // 7. OnboardingActivity (bottom bar)
        OnboardingActivity onboarding = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
        measureAndLayout(onboarding, 1024, 768);
        View onboardingBottomBar = onboarding.findViewById(R.id.onboarding_bottom_bar);
        assertTrue(onboardingBottomBar instanceof KeepADBContentColumnLayout);
        assertEquals(maxPx, onboardingBottomBar.getMeasuredWidth());
        assertEquals(maxPx, onboardingBottomBar.getWidth());
        View onboardingBody = onboarding.findViewById(R.id.onboarding_body);
        assertCentered(onboardingBody.getWidth(), onboardingBottomBar);

        // 8. activity_network_decision (NetworkDecisionView in dialog ScrollView)
        View decisionRoot = LayoutInflater.from(context).inflate(R.layout.activity_network_decision, null);
        measureAndLayout(decisionRoot, 1024, 768);
        NetworkDecisionView decisionView = decisionRoot.findViewById(R.id.network_decision);
        assertNotNull(decisionView);
        assertEquals(maxPx, decisionView.getMeasuredWidth());
        assertEquals(maxPx, decisionView.getWidth());
        assertCentered(decisionRoot.getWidth(), decisionView);
    }

    // --- sub-page activity and layout checks on phone ------------------------------------------

    @Test
    public void allSubPagesMeasureFullViewportWidthOnPhone() {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp");
        float density = context.getResources().getDisplayMetrics().density;
        int width360Px = Math.round(360 * density);

        // 1. ProtectionActivity
        ProtectionActivity protection = Robolectric.buildActivity(ProtectionActivity.class).setup().get();
        measureAndLayout(protection, 360, 640);
        ScrollView protectionScroll = protection.findViewById(R.id.protection_scroll);
        View protectionColumn = protectionScroll.getChildAt(0);
        assertTrue(protectionColumn instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, protectionColumn.getMeasuredWidth());
        assertEquals(width360Px, protectionColumn.getWidth());

        // 2. SettingsActivity
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        measureAndLayout(settings, 360, 640);
        ScrollView settingsScroll = settings.findViewById(R.id.settings_scroll_view);
        View settingsColumn = settingsScroll.getChildAt(0);
        assertTrue(settingsColumn instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, settingsColumn.getMeasuredWidth());
        assertEquals(width360Px, settingsColumn.getWidth());

        // 3. NetworkListActivity
        NetworkListActivity networkList = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context)).setup().get();
        measureAndLayout(networkList, 360, 640);
        ScrollView networkListScroll = networkList.findViewById(R.id.network_list_scroll);
        View networkListColumn = networkListScroll.getChildAt(0);
        assertTrue(networkListColumn instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, networkListColumn.getMeasuredWidth());
        assertEquals(width360Px, networkListColumn.getWidth());

        // 4. ReportHistoryActivity
        ReportHistoryActivity reportHistory = Robolectric.buildActivity(ReportHistoryActivity.class).setup().get();
        measureAndLayout(reportHistory, 360, 640);
        ScrollView reportHistoryScroll = reportHistory.findViewById(R.id.report_history_scroll);
        View reportHistoryColumn = reportHistoryScroll.getChildAt(0);
        assertTrue(reportHistoryColumn instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, reportHistoryColumn.getMeasuredWidth());
        assertEquals(width360Px, reportHistoryColumn.getWidth());

        // 5. WarningsActivity
        WarningsActivity warnings = Robolectric.buildActivity(WarningsActivity.class).setup().get();
        measureAndLayout(warnings, 360, 640);
        View warningsColumn = warnings.findViewById(R.id.warnings_content);
        assertTrue(warningsColumn instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, warningsColumn.getMeasuredWidth());
        assertEquals(width360Px, warningsColumn.getWidth());

        // 6. activity_widget_config layout inflation
        View widgetConfigRoot = LayoutInflater.from(context).inflate(R.layout.activity_widget_config, null);
        measureAndLayout(widgetConfigRoot, 360, 640);
        ScrollView widgetConfigScroll = widgetConfigRoot.findViewById(R.id.widget_config_scroll);
        View widgetConfigColumn = widgetConfigScroll.getChildAt(0);
        assertTrue(widgetConfigColumn instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, widgetConfigColumn.getMeasuredWidth());
        assertEquals(width360Px, widgetConfigColumn.getWidth());

        View saveButton = widgetConfigRoot.findViewById(R.id.widget_config_save);
        View saveWrapper = (View) saveButton.getParent();
        assertTrue(saveWrapper instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, saveWrapper.getMeasuredWidth());
        assertEquals(width360Px, saveWrapper.getWidth());

        // 7. OnboardingActivity (bottom bar)
        OnboardingActivity onboarding = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
        measureAndLayout(onboarding, 360, 640);
        View onboardingBottomBar = onboarding.findViewById(R.id.onboarding_bottom_bar);
        assertTrue(onboardingBottomBar instanceof KeepADBContentColumnLayout);
        assertEquals(width360Px, onboardingBottomBar.getMeasuredWidth());
        assertEquals(width360Px, onboardingBottomBar.getWidth());

        // 8. activity_network_decision
        View decisionRoot = LayoutInflater.from(context).inflate(R.layout.activity_network_decision, null);
        measureAndLayout(decisionRoot, 360, 640);
        NetworkDecisionView decisionView = decisionRoot.findViewById(R.id.network_decision);
        assertNotNull(decisionView);
        assertEquals(width360Px, decisionView.getMeasuredWidth());
        assertEquals(width360Px, decisionView.getWidth());
    }

    // --- helpers -------------------------------------------------------------------------------

    private static void measureAndLayout(Activity activity, int widthDp, int heightDp) {
        measureAndLayout(activity.getWindow().getDecorView(), widthDp, heightDp);
    }

    private static void measureAndLayout(View view, int widthDp, int heightDp) {
        float density = view.getResources().getDisplayMetrics().density;
        int widthPx = Math.round(widthDp * density);
        int heightPx = Math.round(heightDp * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static void assertCentered(int parentWidth, View child) {
        int expectedLeft = (parentWidth - child.getWidth()) / 2;
        assertEquals("Child should be centered horizontally within parent",
                expectedLeft, child.getLeft(), 1.0);
    }
}
