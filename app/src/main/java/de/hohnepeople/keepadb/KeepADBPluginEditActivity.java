package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * #999: the edit screen of the Tasker/Locale action plugin. The host (Tasker, MacroDroid, ...)
 * opens it for result; the user picks one request and KeepADB puts the current access token into
 * the returned configuration itself.
 *
 * <p>The activity must be exported for the host to find it, so any app can open it for result
 * and would receive the token once the user saves (Q9). It therefore names the calling app
 * ({@link #getCallingPackage()}, set by the system and not by the caller), saves nothing without
 * one, and asks again before saving for an app outside {@link KeepADBTaskerPlugin#KNOWN_HOSTS}.
 * While the automation interface is off it only explains that and links to the Settings.
 */
public class KeepADBPluginEditActivity extends Activity {
    private static final String STATE_REQUEST = "request";

    /** The id of the chosen request, or of the chosen condition in condition mode. */
    private String selected;
    private AlertDialog activeDialog;

    /** {@code true} in {@link KeepADBPluginConditionEditActivity}: offers the conditions. */
    boolean conditionMode() {
        return false;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Locale contract: cancelled unless saved, so backing out keeps the host's old entry.
        setResult(RESULT_CANCELED);
        setContentView(R.layout.activity_plugin_edit);
        KeepADBWindowInsets.apply(getWindow(), findViewById(R.id.header_bar),
                findViewById(R.id.plugin_body));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        if (conditionMode()) {
            ((TextView) findViewById(R.id.plugin_title)).setText(R.string.plugin_condition_label);
            ((TextView) findViewById(R.id.plugin_request_title))
                    .setText(R.string.plugin_condition_title);
            ((TextView) findViewById(R.id.plugin_token_warning))
                    .setText(R.string.plugin_condition_token_warning);
            ((TextView) findViewById(R.id.plugin_disabled))
                    .setText(R.string.plugin_condition_disabled);
        }
        selected = previousId(safeBundleExtra(getIntent()));
        if (savedInstanceState != null && isChoice(savedInstanceState.getString(STATE_REQUEST))) {
            selected = savedInstanceState.getString(STATE_REQUEST);
        }
        buildChoices();
        findViewById(R.id.plugin_open_settings).setOnClickListener(v -> startActivity(
                new Intent(this, SettingsActivity.class)
                        .putExtra(SettingsActivity.EXTRA_FOCUS_AUTOMATION, true)));
        findViewById(R.id.plugin_save).setOnClickListener(v -> onSaveClicked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may come back from the Settings after turning the interface on.
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_REQUEST, selected);
    }

    @Override
    protected void onDestroy() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = null;
        super.onDestroy();
    }

    AlertDialog getActiveDialog() {
        return activeDialog;
    }

    /** The choices of this mode as {id, label resource} pairs, in screen order. */
    private List<Object[]> choices() {
        List<Object[]> choices = new ArrayList<>();
        if (conditionMode()) {
            for (KeepADBTaskerPlugin.Condition c : KeepADBTaskerPlugin.Condition.values()) {
                choices.add(new Object[] {c.id, c.label});
            }
        } else {
            for (KeepADBTaskerPlugin.Request r : KeepADBTaskerPlugin.Request.values()) {
                choices.add(new Object[] {r.id(), r.label});
            }
        }
        return choices;
    }

    private boolean isChoice(String id) {
        if (id == null) return false;
        for (Object[] choice : choices()) if (choice[0].equals(id)) return true;
        return false;
    }

    /** The id saved in the host's existing configuration, else the first choice. */
    private String previousId(Bundle bundle) {
        if (conditionMode()) {
            KeepADBTaskerPlugin.ConditionConfig previous =
                    KeepADBTaskerPlugin.parseConditionBundle(bundle);
            if (previous != null) return previous.condition.id;
        } else {
            KeepADBTaskerPlugin.Config previous = KeepADBTaskerPlugin.parseBundle(bundle);
            if (previous != null) return previous.request.id();
        }
        return (String) choices().get(0)[0];
    }

    private void buildChoices() {
        RadioGroup group = findViewById(R.id.plugin_requests);
        for (Object[] choice : choices()) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText((Integer) choice[1]);
            button.setTextColor(getColor(R.color.text_yellow));
            button.setTextSize(16);
            button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            button.setTag(choice[0]);
            group.addView(button);
            if (choice[0].equals(selected)) button.setChecked(true);
        }
        group.setOnCheckedChangeListener((g, id) -> {
            View checked = g.findViewById(id);
            if (checked != null) selected = (String) checked.getTag();
        });
    }

    private void render() {
        boolean enabled = KeepADBAutomation.isEnabled(this);
        String caller = getCallingPackage();
        findViewById(R.id.plugin_disabled).setVisibility(enabled ? View.GONE : View.VISIBLE);
        findViewById(R.id.plugin_open_settings).setVisibility(enabled ? View.GONE : View.VISIBLE);
        findViewById(R.id.plugin_config).setVisibility(enabled ? View.VISIBLE : View.GONE);
        TextView callerText = findViewById(R.id.plugin_caller);
        callerText.setText(caller == null
                ? getString(conditionMode() ? R.string.plugin_condition_caller_none
                        : R.string.plugin_caller_none)
                : getString(R.string.plugin_caller, callerLabel(caller)));
        Button save = findViewById(R.id.plugin_save);
        save.setEnabled(enabled && caller != null);
    }

    /** "Label (package)": the package name shows who it really is when a label imitates another. */
    private String callerLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0 && !label.toString().equals(packageName)) {
                return label + " (" + packageName + ")";
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Not visible to this app: the package name alone.
        }
        return packageName;
    }

    private void onSaveClicked() {
        String caller = getCallingPackage();
        if (caller == null || !KeepADBAutomation.isEnabled(this)) {
            render();
            return;
        }
        if (KeepADBTaskerPlugin.KNOWN_HOSTS.contains(caller)) {
            save();
            return;
        }
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.plugin_unknown_title)
                .setMessage(getString(R.string.plugin_unknown_message, callerLabel(caller)))
                .setPositiveButton(R.string.plugin_unknown_confirm, (d, which) -> save())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeDialog.show();
    }

    private void save() {
        String token = KeepADBPreferences.getAutomationToken(this);
        // The interface may have been switched off meanwhile; then there is no token to give.
        if (!KeepADBAutomation.isEnabled(this) || token == null || getCallingPackage() == null) {
            render();
            return;
        }
        Intent result;
        if (conditionMode()) {
            KeepADBTaskerPlugin.Condition condition = KeepADBTaskerPlugin.Condition.fromId(selected);
            if (condition == null) return;
            result = KeepADBTaskerPlugin.conditionResultIntent(this, condition, token);
        } else {
            KeepADBTaskerPlugin.Request request = KeepADBTaskerPlugin.Request.fromId(selected);
            if (request == null) return;
            result = KeepADBTaskerPlugin.resultIntent(this, request, token);
        }
        KeepADBAutomation.journal(this, "plugin_saved",
                (conditionMode() ? "condition=" : "request=") + selected, false);
        setResult(RESULT_OK, result);
        finish();
    }

    private static Bundle safeBundleExtra(Intent intent) {
        try {
            return intent == null ? null : intent.getBundleExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
