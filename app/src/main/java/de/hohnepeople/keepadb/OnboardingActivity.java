package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The setup assistant (#761, UX concept #758 section 1): intro, the steps, a closing summary.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><b>Full</b>: intro, every step, summary. Opened by the home screen the first time (see
 *       {@link KeepADBOnboarding#shouldAutoStart}) and from the Settings. Every way out (Later, the
 *       close button, Back on the intro, Done) marks the assistant closed; none of them writes
 *       anything the user did not choose.</li>
 *   <li><b>Single step</b>: {@link #EXTRA_STEP} names one step; the screen shows only that step
 *       with "Done". It never marks the assistant closed, and Back leaves without taking the
 *       choice over.</li>
 * </ul>
 *
 * <h2>Writing</h2>
 * A step writes when it is left forward ("Next", "Done"), and only what differs from what it
 * loaded ({@link OnboardingStep#commit}). Back and the close button leave the pending choice of the
 * page that is showing behind; earlier steps keep what they took over.
 *
 * <p>The intent carries no setting and no value, only the step to show.
 */
public class OnboardingActivity extends Activity {
    /** Single-step mode: the {@link KeepADBOnboarding.Step#id} of the one step to show. */
    public static final String EXTRA_STEP = "onboarding_step";
    /**
     * Single-step mode, optional: the item of that step a deep link points at (a row of the
     * permissions step, for instance {@link OnboardingActionSteps.Permissions#ITEM_BACKGROUND_LOCATION}).
     * The step brings it into view; an item the step does not know changes nothing.
     */
    public static final String EXTRA_FOCUS_ITEM = "onboarding_focus_item";
    /** Set by the home screen's hand-over: leaving the assistant opens the home screen. */
    static final String EXTRA_OPEN_HOME = "onboarding_open_home";

    private static final String STATE_PAGE = "onboarding_page";
    private static final int PAGE_INTRO = 0;

    private List<OnboardingStep> steps;
    /** The one step of the single-step mode, or null in the full assistant. */
    private OnboardingStep single;
    private boolean openHome;
    private boolean existing;
    /** 0 = intro, 1..steps = a step, steps + 1 = summary. Always 0 in the single-step mode. */
    private int page;

    private TextView headerTitle;
    private TextView headerCounter;
    private LinearLayout progress;
    private OnboardingPageLayout pageRoot;
    private TextView pageTitle;
    private TextView pageQuestion;
    private LinearLayout pageContent;
    private View scroll;
    private Button backButton;
    private Button secondaryButton;
    private Button nextButton;

    /** The intent the home screen uses to hand over to the assistant. */
    static Intent autoStartIntent(Context context) {
        return new Intent(context, OnboardingActivity.class).putExtra(EXTRA_OPEN_HOME, true);
    }

    /** The full assistant, as the Settings open it. */
    static Intent fullIntent(Context context) {
        return new Intent(context, OnboardingActivity.class);
    }

    /** One step on its own. */
    static Intent stepIntent(Context context, KeepADBOnboarding.Step step) {
        return new Intent(context, OnboardingActivity.class).putExtra(EXTRA_STEP, step.id);
    }

    /**
     * The tap target of a notification that points at one step (#759 phase 2): the step on its
     * own, optionally at one of its items. Leaving it opens the home screen, because the user
     * came from a notification and not from the app; and it starts in a task of its own like the
     * targets it replaces.
     */
    static Intent notificationIntent(Context context, KeepADBOnboarding.Step step, String item) {
        Intent intent = stepIntent(context, step).putExtra(EXTRA_OPEN_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (item != null) intent.putExtra(EXTRA_FOCUS_ITEM, item);
        return intent;
    }

    /** The steps this build has, in the order of the concept. */
    static List<OnboardingStep> buildSteps() {
        List<OnboardingStep> list = new ArrayList<>();
        list.add(new OnboardingSteps.KeepAlive());
        list.add(new OnboardingSteps.Protection());
        list.add(new OnboardingActionSteps.Permissions());
        list.add(new OnboardingActionSteps.Network());
        list.add(new OnboardingSteps.Details());
        list.add(new OnboardingWebhookStep());
        return list;
    }

    /** The 1-based position of {@code step} in the sequence, for texts like "in step 3". */
    static int stepNumber(KeepADBOnboarding.Step step) {
        List<OnboardingStep> list = buildSteps();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == step) return i + 1;
        }
        return 0;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        KeepADBWindowInsets.apply(getWindow(), findViewById(R.id.header_bar),
                findViewById(R.id.onboarding_body));

        steps = buildSteps();
        Intent intent = getIntent();
        KeepADBOnboarding.Step requested = KeepADBOnboarding.Step.fromId(
                intent.getStringExtra(EXTRA_STEP));
        if (requested != null) {
            for (OnboardingStep step : steps) {
                if (step.id == requested) single = step;
            }
        }
        if (single != null) {
            single.standalone = true;
            single.setFocusItem(intent.getStringExtra(EXTRA_FOCUS_ITEM));
        }
        openHome = intent.getBooleanExtra(EXTRA_OPEN_HOME, false);
        // Decided before any step writes, and kept: the intro must read the same after a restart.
        existing = KeepADBOnboarding.isExistingInstall(this) || single != null || !openHome;

        headerTitle = findViewById(R.id.onboarding_header_title);
        headerCounter = findViewById(R.id.onboarding_header_counter);
        progress = findViewById(R.id.onboarding_progress);
        pageRoot = findViewById(R.id.onboarding_page);
        pageTitle = findViewById(R.id.onboarding_page_title);
        pageQuestion = findViewById(R.id.onboarding_page_question);
        pageContent = findViewById(R.id.onboarding_page_content);
        scroll = findViewById(R.id.onboarding_scroll);
        backButton = findViewById(R.id.onboarding_back);
        secondaryButton = findViewById(R.id.onboarding_secondary);
        nextButton = findViewById(R.id.onboarding_next);

        findViewById(R.id.onboarding_close).setOnClickListener(v -> close());
        backButton.setOnClickListener(v -> goBack());
        secondaryButton.setOnClickListener(v -> onSecondary());
        nextButton.setOnClickListener(v -> goForward());
        arrangeBottomBar();

        if (savedInstanceState != null) {
            page = savedInstanceState.getInt(STATE_PAGE, PAGE_INTRO);
            for (OnboardingStep step : steps) step.restoreState(savedInstanceState);
        }
        showPage(single != null ? PAGE_INTRO : clampPage(page));
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepADBWarningState.observe(this);
        if (!KeepADBLocaleHelper.isSelectedLanguageApplied(this)) {
            recreate();
            return;
        }
        OnboardingStep current = currentStep();
        if (current != null) current.onResume(this);
    }

    @Override
    protected void onDestroy() {
        for (OnboardingStep step : steps) step.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        KeepADBWarningState.observe(this);
        // The result arrays are not trusted (they can be empty): the step re-reads the platform.
        OnboardingStep current = currentStep();
        if (current != null) current.onPermissionResult(requestCode);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, page);
        // Only the page that is showing has a pending choice; a left page's is discarded (Back).
        OnboardingStep current = currentStep();
        if (current != null) current.saveState(outState);
    }

    /**
     * The system Back gesture: one page back; on the intro it is "Later"; in the single-step mode
     * it leaves without taking the choice over. ({@code onBackPressed} is the dispatch of every
     * supported release while the manifest does not opt into predictive back.)
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (single != null) {
            leave();
        } else if (page == PAGE_INTRO) {
            close();
        } else {
            goBack();
        }
    }

    // ---- Navigation -------------------------------------------------------------------------------

    private void goForward() {
        if (single != null) {
            single.commit(this);
            leave();
            return;
        }
        if (page == PAGE_INTRO) {
            showPage(1);
            return;
        }
        if (page == summaryPage()) {
            close();
            return;
        }
        steps.get(page - 1).commit(this);
        showPage(page + 1);
    }

    /** The second button: "Later" on the intro, "Skip" on a step with an action. Both write nothing. */
    private void onSecondary() {
        if (page == PAGE_INTRO) {
            close();
        } else if (single == null && page <= steps.size()) {
            // Skipping is "Next" without the commit: the step has nothing the user chose.
            showPage(page + 1);
        }
    }

    private void goBack() {
        if (single != null || page == PAGE_INTRO) return;
        showPage(page - 1);
    }

    /** Later, the close button, Back on the intro and Done: the assistant is closed for good. */
    private void close() {
        if (single == null) KeepADBOnboarding.markCompleted(this);
        leave();
    }

    private void leave() {
        if (openHome) {
            Intent home = new Intent(this, MainActivity.class);
            // #782: a notification target answered one question; the home screen it opens must not
            // turn that into the full assistant once more. The next plain start still hands over.
            if (single != null) home.putExtra(MainActivity.EXTRA_SKIP_ASSISTANT_ONCE, true);
            startActivity(home);
        }
        finish();
    }

    private int summaryPage() {
        return steps.size() + 1;
    }

    private int clampPage(int value) {
        return Math.max(PAGE_INTRO, Math.min(value, summaryPage()));
    }

    private OnboardingStep currentStep() {
        if (single != null) return single;
        return page >= 1 && page <= steps.size() ? steps.get(page - 1) : null;
    }

    // ---- Pages ------------------------------------------------------------------------------------

    private void showPage(int target) {
        page = target;
        OnboardingStep current = currentStep();
        pageRoot.setComparisonPage(current != null
                && (current.id == KeepADBOnboarding.Step.KEEP_ALIVE
                || current.id == KeepADBOnboarding.Step.PROTECTION
                || current.id == KeepADBOnboarding.Step.DETAILS));
        pageContent.removeAllViews();
        findViewById(R.id.onboarding_page_icon).setVisibility(View.GONE);
        scroll.scrollTo(0, 0);
        if (single != null) {
            showSingleStep();
        } else if (page == PAGE_INTRO) {
            showIntro();
        } else if (page == summaryPage()) {
            showSummary();
        } else {
            showStep(steps.get(page - 1));
        }
        announcePage();
    }

    private void showIntro() {
        int count = steps.size();
        headerCounter.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        headerTitle.setText(R.string.onboarding_title);
        List<KeepADBOnboarding.LessSecure> marked = KeepADBOnboarding.lessSecure(this);

        // The mark stands above the title (#791), not below the body text.
        findViewById(R.id.onboarding_page_icon).setVisibility(View.VISIBLE);

        pageTitle.setVisibility(View.VISIBLE);
        pageTitle.setText(existing
                ? getString(R.string.onboarding_intro_title_existing)
                : getString(R.string.onboarding_intro_title_new, count));
        pageQuestion.setVisibility(View.VISIBLE);
        pageQuestion.setText(existing
                ? getString(R.string.onboarding_intro_body_existing)
                : getString(R.string.onboarding_intro_body_new));
        if (KeepADBOnboarding.consumeUpgradeNotice(this)) {
            TextView notice = new TextView(this);
            notice.setId(R.id.onboarding_upgrade_notice);
            notice.setText(R.string.onboarding_upgrade_notice);
            notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            notice.setTextColor(getColor(R.color.night_text));
            notice.setAccessibilityTraversalAfter(R.id.onboarding_page_question);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(16);
            pageContent.addView(notice, params);
        }
        if (existing && !marked.isEmpty()) {
            pageContent.addView(adviceRow(getString(R.string.onboarding_intro_less_secure)));
        }
        backButton.setVisibility(View.GONE);
        secondaryButton.setVisibility(View.VISIBLE);
        secondaryButton.setText(R.string.onboarding_later);
        nextButton.setText(R.string.onboarding_start);
        applyBarOrder();
    }

    private void showStep(OnboardingStep step) {
        int count = steps.size();
        headerTitle.setText(R.string.onboarding_title);
        headerCounter.setVisibility(View.VISIBLE);
        headerCounter.setText(getString(R.string.onboarding_step_counter, page, count));
        buildProgress(count, page);
        pageTitle.setVisibility(View.VISIBLE);
        pageTitle.setText(step.titleRes);
        pageQuestion.setVisibility(View.VISIBLE);
        pageQuestion.setText(step.questionRes);
        step.build(this, pageContent);
        // Back is hidden on the first step; the system gesture still goes back to the intro.
        backButton.setVisibility(page == 1 ? View.GONE : View.VISIBLE);
        secondaryButton.setVisibility(step.hasSkip() ? View.VISIBLE : View.GONE);
        secondaryButton.setText(R.string.onboarding_skip);
        nextButton.setText(R.string.onboarding_next);
        applyBarOrder();
    }

    private void showSingleStep() {
        headerCounter.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        headerTitle.setText(single.titleRes);
        headerTitle.setAccessibilityHeading(true);
        pageTitle.setVisibility(View.GONE);
        pageQuestion.setVisibility(View.VISIBLE);
        pageQuestion.setText(single.questionRes);
        single.build(this, pageContent);
        backButton.setVisibility(View.GONE);
        secondaryButton.setVisibility(View.GONE);
        nextButton.setText(R.string.onboarding_done);
        applyBarOrder();
    }

    private void showSummary() {
        headerTitle.setText(R.string.onboarding_title);
        headerCounter.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        pageTitle.setVisibility(View.VISIBLE);
        pageTitle.setText(R.string.onboarding_summary_title);
        pageQuestion.setVisibility(View.GONE);
        for (int i = 0; i < steps.size(); i++) {
            pageContent.addView(summaryRow(steps.get(i), i + 1));
        }
        TextView hint = new TextView(this);
        hint.setText(R.string.onboarding_summary_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextColor(getColor(R.color.night_muted));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(16);
        pageContent.addView(hint, hintParams);
        if (!KeepADBPreferences.isKeepAliveEnabled(this)) {
            TextView off = new TextView(this);
            off.setText(R.string.onboarding_summary_keep_alive_off);
            off.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            off.setTextColor(getColor(R.color.night_text));
            LinearLayout.LayoutParams offParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            offParams.topMargin = dp(8);
            pageContent.addView(off, offParams);
        }
        backButton.setVisibility(View.VISIBLE);
        secondaryButton.setVisibility(View.GONE);
        nextButton.setText(R.string.onboarding_done);
        applyBarOrder();
    }

    private View summaryRow(OnboardingStep step, int stepPage) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card_clickable);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(step.titleRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTextColor(getColor(R.color.night_text));
        label.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                android.graphics.Typeface.BOLD));
        TextView value = new TextView(this);
        String valueText = step.summary(this);
        value.setText(valueText);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(getColor(R.color.night_muted));
        texts.addView(label);
        texts.addView(value);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));

        row.setContentDescription(getString(step.titleRes) + ", " + valueText);
        row.setOnClickListener(v -> showPage(stepPage));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private View adviceRow(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_panel_advice);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_warning);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.setMarginEnd(dp(12));
        row.addView(icon, iconParams);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(getColor(R.color.night_text));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        row.setLayoutParams(params);
        return row;
    }

    private void buildProgress(int count, int current) {
        progress.setVisibility(View.VISIBLE);
        progress.removeAllViews();
        for (int i = 1; i <= count; i++) {
            View segment = new View(this);
            segment.setBackgroundColor(getColor(i <= current ? R.color.title_yellow
                    : R.color.muted_red));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(4), 1f);
            if (i > 1) params.setMarginStart(dp(4));
            progress.addView(segment, params);
        }
    }

    /** The pane title (a page change is announced) and the heading the screen reader lands on. */
    private void announcePage() {
        CharSequence title = single != null ? getString(single.titleRes) : pageTitle.getText();
        if (single == null && page >= 1 && page <= steps.size()) {
            title = getString(R.string.onboarding_pane_title, page, steps.size(), title);
        }
        pageRoot.setAccessibilityPaneTitle(title);
        View target = pageTitle.getVisibility() == View.VISIBLE ? pageTitle : headerTitle;
        target.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    // ---- Bottom bar -------------------------------------------------------------------------------

    /**
     * Side by side on a normal display; stacked, full width, "Next" on top, when the display is
     * narrower than 360dp or the font is scaled to 1.3 or more (UX concept 7.3): three buttons in
     * a row would cut their labels.
     */
    private boolean stackedBar;

    private void arrangeBottomBar() {
        Configuration config = getResources().getConfiguration();
        stackedBar = config.screenWidthDp < 360 || config.fontScale >= 1.3f;
    }

    private void applyBarOrder() {
        LinearLayout bar = findViewById(R.id.onboarding_bottom_bar);
        View spacer = findViewById(R.id.onboarding_bar_spacer);
        // The buttons may sit in the pair row of an earlier arrangement; free them first.
        for (Button button : new Button[] {backButton, secondaryButton, nextButton}) {
            if (button.getParent() instanceof ViewGroup) {
                ((ViewGroup) button.getParent()).removeView(button);
            }
        }
        bar.removeAllViews();
        int barPadding = stackedBar ? dp(12) : dp(16);
        bar.setPadding(barPadding, barPadding, barPadding, barPadding);
        if (stackedBar) {
            bar.setOrientation(LinearLayout.VERTICAL);
            addStacked(bar, nextButton, false);
            if (secondaryButton.getVisibility() == View.VISIBLE
                    && backButton.getVisibility() == View.VISIBLE) {
                // #791: "Skip" and "Back" share one row, so the bar takes two rows, not three
                // (at 200 % on 320 dp three full-width buttons filled 40 % of the screen).
                LinearLayout pair = new LinearLayout(this);
                pair.setOrientation(LinearLayout.HORIZONTAL);
                pair.addView(backButton, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                second.setMarginStart(dp(8));
                pair.addView(secondaryButton, second);
                LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                row.topMargin = dp(8);
                bar.addView(pair, row);
            } else {
                addStacked(bar, secondaryButton, true);
                addStacked(bar, backButton, true);
            }
        } else {
            bar.setOrientation(LinearLayout.HORIZONTAL);
            for (Button button : new Button[] {backButton, secondaryButton, nextButton}) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (button == nextButton && secondaryButton.getVisibility() == View.VISIBLE) {
                    params.setMarginStart(dp(8));
                }
                if (button == backButton) {
                    bar.addView(button, params);
                    bar.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
                } else {
                    bar.addView(button, params);
                }
            }
        }
    }

    private void addStacked(LinearLayout bar, Button button, boolean gapAbove) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gapAbove) params.topMargin = dp(8);
        bar.addView(button, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
