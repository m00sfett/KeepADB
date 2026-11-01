package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.TextView;

/**
 * #866: the two retry selectors of the webhook card in {@link SettingsActivity} -- the delay
 * profile and the number of retries per series. A change only affects series that fail after it;
 * a scheduled retry keeps its delay. (#893 removed the #868 freshness switch: the warning now
 * always names when the disruption started and when a report last got through.)
 */
final class KeepADBWebhookRetrySettings {
    private static final String[] PROFILES = {
            KeepADBPreferences.WEBHOOK_RETRY_PROFILE_FAST,
            KeepADBPreferences.WEBHOOK_RETRY_PROFILE_STANDARD,
            KeepADBPreferences.WEBHOOK_RETRY_PROFILE_GENTLE,
    };
    private static final int[] PROFILE_LABELS = {
            R.string.settings_webhook_retry_timing_fast,
            R.string.settings_webhook_retry_timing_standard,
            R.string.settings_webhook_retry_timing_gentle,
    };

    private final Activity activity;
    private final TextView timingText;
    private final TextView countText;
    private AlertDialog activeDialog;

    KeepADBWebhookRetrySettings(Activity activity) {
        this.activity = activity;
        this.timingText = activity.findViewById(R.id.settings_webhook_retry_timing_text);
        this.countText = activity.findViewById(R.id.settings_webhook_retry_count_text);
        activity.findViewById(R.id.settings_webhook_retry_timing_selector)
                .setOnClickListener(v -> showTimingDialog());
        activity.findViewById(R.id.settings_webhook_retry_count_selector)
                .setOnClickListener(v -> showCountDialog());
    }

    void refreshVisual() {
        String label = activity.getString(PROFILE_LABELS[profileIndex()]);
        timingText.setText(label);
        selectorRow(timingText).setContentDescription(
                activity.getString(R.string.settings_webhook_retry_timing_label) + ": " + label);
        String count = countLabel(KeepADBPreferences.getWebhookRetryCount(activity));
        countText.setText(count);
        selectorRow(countText).setContentDescription(
                activity.getString(R.string.settings_webhook_retry_count_label) + ": " + count);
    }

    /** The selector row around a value text: it, not the text, is the focusable control. */
    private static android.view.View selectorRow(TextView valueText) {
        return (android.view.View) valueText.getParent();
    }

    void dismiss() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = null;
    }

    private int profileIndex() {
        String current = KeepADBPreferences.getWebhookRetryProfile(activity);
        for (int i = 0; i < PROFILES.length; i++) {
            if (PROFILES[i].equals(current)) return i;
        }
        return 1;
    }

    private String countLabel(int count) {
        return activity.getString(R.string.settings_webhook_retry_count_value, count);
    }

    private void showTimingDialog() {
        if (activeDialog != null && activeDialog.isShowing()) return;
        String[] items = new String[PROFILES.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = activity.getString(PROFILE_LABELS[i]);
        }
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_webhook_retry_timing_dialog_title)
                .setSingleChoiceItems(items, profileIndex(), (dialog, which) -> {
                    dialog.dismiss();
                    KeepADBPreferences.setWebhookRetryProfile(activity, PROFILES[which]);
                    refreshVisual();
                }));
    }

    private void showCountDialog() {
        if (activeDialog != null && activeDialog.isShowing()) return;
        String[] items = new String[KeepADBPreferences.WEBHOOK_MAX_RETRIES + 1];
        for (int i = 0; i < items.length; i++) {
            items[i] = countLabel(i);
        }
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_webhook_retry_count_dialog_title)
                .setSingleChoiceItems(items, KeepADBPreferences.getWebhookRetryCount(activity),
                        (dialog, which) -> {
                            dialog.dismiss();
                            KeepADBPreferences.setWebhookRetryCount(activity, which);
                            refreshVisual();
                        }));
    }

    private void show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.setNegativeButton(android.R.string.cancel, null).create();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == d) activeDialog = null;
        });
        dialog.show();
    }
}
