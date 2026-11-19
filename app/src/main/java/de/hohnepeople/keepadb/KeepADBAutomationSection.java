package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

/**
 * #998: the "Automation" card of the Settings page: the opt-in switch with its risk dialog (Q13),
 * the masked token with reveal, copy and regenerate (Q6, Q12), the rejection counter (Q7) and the
 * link to the setup guide (Q8). The token is shown only after a tap and is hidden again whenever
 * the screen is left.
 */
final class KeepADBAutomationSection {
    static final String DOCS_URL =
            "https://github.com/m00sfett/KeepADB/blob/master/docs/automation.md";
    private static final String MASK = "••••••••••••••••";

    interface LinkOpener {
        void open(String url);
    }

    private final Activity activity;
    private final Switch toggle;
    private final View details;
    private final TextView tokenText;
    private final Button revealButton;
    private final TextView rejectedText;
    private boolean revealed;
    private AlertDialog activeDialog;

    KeepADBAutomationSection(Activity activity, LinkOpener links) {
        this.activity = activity;
        toggle = activity.findViewById(R.id.settings_automation_toggle);
        details = activity.findViewById(R.id.settings_automation_details);
        tokenText = activity.findViewById(R.id.settings_automation_token);
        revealButton = activity.findViewById(R.id.settings_automation_token_reveal);
        rejectedText = activity.findViewById(R.id.settings_automation_rejected);

        toggle.setOnClickListener(v -> {
            if (toggle.isChecked()) {
                // The switch only follows a confirmed dialog.
                toggle.setChecked(false);
                showEnableDialog();
            } else {
                KeepADBAutomation.disable(activity);
                revealed = false;
                refresh();
            }
        });
        revealButton.setOnClickListener(v -> {
            revealed = !revealed;
            refresh();
        });
        activity.findViewById(R.id.settings_automation_token_copy).setOnClickListener(v -> {
            String token = KeepADBPreferences.getAutomationToken(activity);
            if (token == null) return;
            KeepADBClipboard.copy(activity, activity.getString(R.string.settings_automation_token_label),
                    token, true, R.string.settings_automation_token_copied);
        });
        activity.findViewById(R.id.settings_automation_token_regenerate)
                .setOnClickListener(v -> showRegenerateDialog());
        activity.findViewById(R.id.settings_automation_docs)
                .setOnClickListener(v -> links.open(DOCS_URL));
    }

    void refresh() {
        boolean enabled = KeepADBAutomation.isEnabled(activity);
        toggle.setChecked(enabled);
        details.setVisibility(enabled ? View.VISIBLE : View.GONE);
        String token = KeepADBPreferences.getAutomationToken(activity);
        boolean show = enabled && revealed && token != null;
        tokenText.setText(show ? token : MASK);
        tokenText.setContentDescription(show ? token
                : activity.getString(R.string.settings_automation_token_label));
        revealButton.setText(show ? R.string.settings_automation_token_hide
                : R.string.settings_automation_token_show);
        KeepADBAutomation.flushRejections(activity);
        int count = KeepADBPreferences.getAutomationRejectedCount(activity);
        long lastAt = KeepADBPreferences.getAutomationLastRejectedAt(activity);
        rejectedText.setText(count <= 0 || lastAt <= 0
                ? activity.getString(R.string.settings_automation_rejected_none)
                : activity.getString(R.string.settings_automation_rejected, count,
                        DateUtils.formatDateTime(activity, lastAt,
                                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)));
    }

    /** Leaving the screen hides the token again, also in the recents thumbnail. */
    void onPause() {
        if (!revealed) return;
        revealed = false;
        refresh();
    }

    void destroy() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = null;
    }

    AlertDialog getActiveDialog() {
        return activeDialog;
    }

    private void showEnableDialog() {
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_automation_dialog_title)
                .setMessage(R.string.settings_automation_dialog_message)
                .setPositiveButton(R.string.settings_automation_dialog_confirm, (d, which) -> {
                    KeepADBAutomation.enable(activity);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void showRegenerateDialog() {
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_automation_regenerate_title)
                .setMessage(R.string.settings_automation_regenerate_message)
                .setPositiveButton(R.string.settings_automation_regenerate_confirm, (d, which) -> {
                    KeepADBAutomation.regenerateToken(activity);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void show(AlertDialog dialog) {
        destroy();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == d) activeDialog = null;
        });
        dialog.show();
    }
}
