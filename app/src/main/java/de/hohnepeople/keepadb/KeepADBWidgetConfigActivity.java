package de.hohnepeople.keepadb;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #912: the settings of one placed control widget: its main action, the order of its actions and
 * whether it hides the connection details. The launcher opens it when the widget is placed and, on
 * launchers that offer it, from "reconfigure"; the widget itself opens it from its details row.
 *
 * <p>It writes the one instance's entry in {@link KeepADBWidgetConfig} and nothing else: no app
 * setting, no other widget. Backing out leaves the stored entry as it was (for a new placement the
 * launcher then discards the widget, as for every configuration screen).
 *
 * <p>The activity is exported only because the launcher must be able to start it; it accepts an
 * id of a widget that exists for this provider and finishes for anything else.
 */
public class KeepADBWidgetConfigActivity extends Activity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private KeepADBWidgetConfig.Action primary = KeepADBWidgetConfig.Action.ADB;
    private final List<KeepADBWidgetConfig.Action> order = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Standard configuration contract: cancelled unless saved, with the id echoed back.
        appWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        setResult(RESULT_CANCELED, resultIntent());
        if (!isOurWidget(appWidgetId)) {
            finish();
            return;
        }
        setContentView(R.layout.activity_widget_config);
        KeepADBWindowInsets.apply(getWindow(), findViewById(R.id.header_bar),
                findViewById(R.id.widget_config_scroll));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        KeepADBWidgetConfig stored = KeepADBWidgetConfig.load(this, appWidgetId);
        primary = stored.primary;
        order.addAll(stored.order);
        if (savedInstanceState != null) restore(savedInstanceState);

        Switch hide = findViewById(R.id.widget_config_hide_details);
        hide.setChecked(savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_HIDE, stored.hideDetails)
                : stored.hideDetails);
        // The global switch decides first; the per-widget option stays available for when it is off.
        findViewById(R.id.widget_config_hide_details_note).setVisibility(
                KeepADBPreferences.isWidgetPrivacyEnabled(this)
                        ? android.view.View.VISIBLE : android.view.View.GONE);

        buildPrimaryChoices();
        renderOrder();
        findViewById(R.id.widget_config_save).setOnClickListener(v -> save());
    }

    private static final String STATE_PRIMARY = "primary";
    private static final String STATE_ORDER = "order";
    private static final String STATE_HIDE = "hide";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || order.isEmpty()) return;
        outState.putString(STATE_PRIMARY, primary.id);
        StringBuilder ids = new StringBuilder();
        for (KeepADBWidgetConfig.Action action : order) {
            if (ids.length() > 0) ids.append(',');
            ids.append(action.id);
        }
        outState.putString(STATE_ORDER, ids.toString());
        Switch hide = findViewById(R.id.widget_config_hide_details);
        if (hide != null) outState.putBoolean(STATE_HIDE, hide.isChecked());
    }

    private void restore(Bundle state) {
        KeepADBWidgetConfig.Action restoredPrimary =
                KeepADBWidgetConfig.Action.fromId(state.getString(STATE_PRIMARY));
        if (restoredPrimary != null) primary = restoredPrimary;
        String ids = state.getString(STATE_ORDER);
        if (ids != null) {
            List<KeepADBWidgetConfig.Action> restored = new ArrayList<>();
            for (String token : ids.split(",")) {
                restored.add(KeepADBWidgetConfig.Action.fromId(token));
            }
            order.clear();
            order.addAll(KeepADBWidgetConfig.of(primary, restored, false).order);
        }
    }

    private boolean isOurWidget(int id) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            int[] ids = manager == null ? null
                    : manager.getAppWidgetIds(new ComponentName(this, KeepADBWidget.class));
            if (ids == null) return false;
            for (int existing : ids) {
                if (existing == id) return true;
            }
        } catch (RuntimeException ignored) {
            // A caller that may not ask is not one of ours.
        }
        return false;
    }

    private Intent resultIntent() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
    }

    private void buildPrimaryChoices() {
        RadioGroup group = findViewById(R.id.widget_config_primary);
        group.removeAllViews();
        for (KeepADBWidgetConfig.Action action : KeepADBWidgetConfig.Action.values()) {
            RadioButton choice = new RadioButton(this);
            choice.setId(android.view.View.generateViewId());
            choice.setTag(action);
            choice.setText(KeepADBWidgetContent.fullName(this, action));
            choice.setTextColor(getColor(R.color.text_yellow));
            choice.setTextSize(15);
            choice.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                    android.graphics.Typeface.NORMAL));
            choice.setMinHeight(dp(48));
            group.addView(choice, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (action == primary) group.check(choice.getId());
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            android.view.View checked = g.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof KeepADBWidgetConfig.Action) {
                primary = (KeepADBWidgetConfig.Action) checked.getTag();
            }
        });
    }

    private void renderOrder() {
        LinearLayout rows = findViewById(R.id.widget_config_order_rows);
        rows.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            final int position = i;
            KeepADBWidgetConfig.Action action = order.get(i);
            String name = KeepADBWidgetContent.fullName(this, action);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(this);
            label.setText((i + 1) + ". " + name);
            label.setTextColor(getColor(R.color.night_text));
            label.setTextSize(15);
            label.setMinHeight(dp(48));
            label.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(moveButton("▲", getString(R.string.widget_config_move_up, name),
                    position > 0, () -> move(position, -1)));
            row.addView(moveButton("▼", getString(R.string.widget_config_move_down, name),
                    position < order.size() - 1, () -> move(position, 1)));
            rows.addView(row);
        }
    }

    private Button moveButton(String glyph, String description, boolean enabled, Runnable move) {
        Button button = new Button(this, null, 0, R.style.Widget_KeepADB_Button_Secondary);
        button.setText(glyph);
        button.setContentDescription(description);
        button.setEnabled(enabled);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMarginStart(dp(8));
        button.setLayoutParams(params);
        button.setOnClickListener(v -> move.run());
        return button;
    }

    private void move(int position, int delta) {
        int target = position + delta;
        if (target < 0 || target >= order.size()) return;
        Collections.swap(order, position, target);
        renderOrder();
    }

    private void save() {
        Switch hide = findViewById(R.id.widget_config_hide_details);
        KeepADBWidgetConfig.save(this, appWidgetId,
                KeepADBWidgetConfig.of(primary, order, hide.isChecked()));
        KeepADBWidget.refresh(this, appWidgetId);
        setResult(RESULT_OK, resultIntent());
        finish();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
