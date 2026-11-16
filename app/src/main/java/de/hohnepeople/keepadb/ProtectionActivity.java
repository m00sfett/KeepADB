package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

/**
 * #959: the Protection page, opened from the "Protection" row of {@link SettingsActivity}. It holds
 * what the collapsible Network card of Settings held, in the order the user reads it: the current
 * connection, the protection level (chosen right here, #959), the automatic behavior, the force
 * mode, the entry to the Wi-Fi networks list and the detection in the background.
 *
 * <p>Nothing is decided here. What each part shows and does is owned by the classes that owned it
 * before: {@link KeepADBNetworkCard} (connection, switches, networks entry, background access),
 * {@link KeepADBForceSection} (force mode) and the new {@link KeepADBProtectionLevelSection}
 * (level). Whether a network is trusted still decides {@link KeepADBTrustedNetwork} alone.
 *
 * <p>Entry points land on a section through one of the {@code EXTRA_FOCUS_*} extras below; each is
 * consumed once. Their predecessors in {@link SettingsActivity} are forwarded here for
 * notifications that were posted by an older version and still carry them.
 */
public class ProtectionActivity extends Activity {
    /** The force row, scrolled into view with its button focused (#763). */
    public static final String EXTRA_FOCUS_FORCE = "focus_protection_force";
    /**
     * The protection level section, scrolled into view with the stored level focused (#801: the
     * Keep-Alive line of the home screen, the "all Wi-Fi networks" warning, the widget).
     */
    public static final String EXTRA_FOCUS_LEVEL = "focus_protection_level";

    private static final String STATE_CONSUMED_FOCUS = "protection_consumed_focus";
    private final java.util.ArrayList<String> consumedFocus = new java.util.ArrayList<>();

    private ScrollView scrollView;
    private KeepADBNetworkCard networkCard;
    private KeepADBProtectionLevelSection levelSection;
    private KeepADBForceSection forceSection;

    /** Opens the page at its top. */
    static Intent intent(Context context) {
        return new Intent(context, ProtectionActivity.class);
    }

    /** Opens the page at the protection level section. */
    static Intent levelIntent(Context context) {
        return intent(context).putExtra(EXTRA_FOCUS_LEVEL, true);
    }

    /** Opens the page at the force mode row. */
    static Intent forceIntent(Context context) {
        return intent(context).putExtra(EXTRA_FOCUS_FORCE, true);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_protection);
        KeepADBWindowInsets.apply(
                getWindow(),
                findViewById(R.id.header_bar),
                findViewById(R.id.protection_scroll));
        scrollView = findViewById(R.id.protection_scroll);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // #725: same eye as on the main view; refresh() redraws the masked page.
        KeepADBPrivacyToggle.bind(this, this::refresh);

        levelSection = new KeepADBProtectionLevelSection(this, this::refresh);
        networkCard = new KeepADBNetworkCard(this, this::refresh, this::focusLevel);
        forceSection = new KeepADBForceSection(this, this::refresh);

        if (savedInstanceState != null) {
            java.util.ArrayList<String> restoredFocus =
                    savedInstanceState.getStringArrayList(STATE_CONSUMED_FOCUS);
            if (restoredFocus != null) consumedFocus.addAll(restoredFocus);
            // #672/#682: the page re-shows its own background-location dialog and the force dialog.
            networkCard.restore(savedInstanceState);
            forceSection.restore(savedInstanceState);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        networkCard.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepADBPrivacyToggle.update(this);
        // #763: finish an expired force mode before drawing, and redraw when it starts or ends.
        KeepADBForceMode.finishIfExpired(this);
        KeepADBForceMode.setStateListener(this::refresh);
        refresh();

        if (consumeFocus(EXTRA_FOCUS_FORCE)) {
            focusForcePanel();
        }
        if (consumeFocus(EXTRA_FOCUS_LEVEL)) {
            focusLevel();
        }
    }

    @Override
    protected void onPause() {
        KeepADBForceMode.clearStateListener();
        super.onPause();
    }

    @Override
    protected void onStop() {
        networkCard.stop();
        super.onStop();
    }

    /**
     * #697: every permission result is handed to the network card; the card owns the request code
     * it asks for and acts only on that.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        networkCard.onRequestPermissionsResult(requestCode);
        KeepADBWarningState.observe(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_CONSUMED_FOCUS, new java.util.ArrayList<>(consumedFocus));
        networkCard.saveState(outState);
        forceSection.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        networkCard.destroy();
        forceSection.destroy();
        super.onDestroy();
    }

    void refresh() {
        KeepADBWarningState.observe(this);
        networkCard.refresh();
        levelSection.refresh();
        forceSection.refresh();
    }

    private boolean consumeFocus(String extra) {
        if (!getIntent().hasExtra(extra)) return false;
        getIntent().removeExtra(extra);
        if (consumedFocus.contains(extra)) return false;
        consumedFocus.add(extra);
        return true;
    }

    private void focusForcePanel() {
        // #763: bring the force row into view and put the focus on its first button.
        View forcePanel = forceSection.getPanel();
        if (forcePanel == null) return;
        scrollTo(forcePanel);
        focusTarget(findViewById(KeepADBForceMode.isActive(this)
                ? R.id.settings_force_end : R.id.settings_force_activate));
    }

    /**
     * #801/#959: brings the protection level section into view and puts the screen reader on the
     * card of the stored level. Also the way the background-location dialog's "Choose protection
     * level" button leads to the choice.
     */
    private void focusLevel() {
        View panel = levelSection.getPanel();
        if (panel == null) return;
        // The panel's heading stays in view; the stored level's card takes the focus without
        // scrolling the heading away.
        scrollTo(panel);
        focusTarget(levelSection.selectedCard());
    }

    private void scrollTo(View target) {
        scrollView.post(() -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            target.getDrawingRect(rect);
            scrollView.offsetDescendantRectToMyCoords(target, rect);
            scrollView.smoothScrollTo(0, rect.top);
        });
    }

    private void focusTarget(View target) {
        if (target == null) return;
        target.post(() -> {
            target.setFocusable(true);
            Runnable focusTarget = () -> {
                target.requestFocusFromTouch();
                target.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            };
            focusTarget.run();
            // A window arriving in touch mode can clear an early focus request on Android 11.
            if (!target.hasWindowFocus()) {
                target.getViewTreeObserver().addOnWindowFocusChangeListener(
                        new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
                            @Override public void onWindowFocusChanged(boolean hasFocus) {
                                if (!hasFocus) return;
                                target.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                                focusTarget.run();
                            }
                        });
            }
        });
    }
}
