package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.text.DateFormat;

/**
 * #1048: the webhook panel of the home screen, split out of {@link MainActivity}: a short hint
 * with a way to the settings while synchronization is not set up or off (#981), otherwise one row
 * per fact with copy buttons for the URL and the last reported endpoint (#1036).
 */
final class HomeWebhookCard {
    private final Activity activity;
    private final TextView status;
    private final View panel;
    private final Button action;
    private final View details;
    private final TextView urlValue;
    private final TextView endpointValue;
    private final TextView lastReported;
    private final View urlCopy;
    private final View endpointCopy;
    /** #1036 review: the real values behind the rows as rendered; a tap copies exactly these. */
    private String urlCopyValue;
    private String endpointCopyValue;

    HomeWebhookCard(Activity activity) {
        this.activity = activity;
        status = activity.findViewById(R.id.webhook_status);
        panel = activity.findViewById(R.id.webhook_status_panel);
        action = activity.findViewById(R.id.webhook_status_action);
        details = activity.findViewById(R.id.webhook_status_details);
        urlValue = activity.findViewById(R.id.webhook_url_value);
        endpointValue = activity.findViewById(R.id.webhook_endpoint_value);
        lastReported = activity.findViewById(R.id.webhook_last_reported);
        urlCopy = activity.findViewById(R.id.webhook_url_copy);
        endpointCopy = activity.findViewById(R.id.webhook_endpoint_copy);
        urlCopy.setOnClickListener(v -> KeepADBWebhookStatusText.copy(activity,
                KeepADBWebhookStatusText.URL_CLIP_LABEL, urlCopyValue, "webhook_url"));
        endpointCopy.setOnClickListener(v -> KeepADBWebhookStatusText.copy(activity,
                KeepADBWebhookStatusText.ENDPOINT_CLIP_LABEL, endpointCopyValue,
                "webhook_endpoint"));
        // #981: the Webhook card of Settings, expanded and in view (same as the widget's setup).
        action.setOnClickListener(v -> activity.startActivity(new Intent(activity, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true)));
    }

    void render() {
        String url = KeepADBPreferences.getRegisterWebhookUrl(activity);
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(activity);
        // #981: unconfigured or off stays visible as a short hint with a way to the settings.
        if (!WebhookUrlCodec.isValid(url)) {
            showSetupHint(R.string.webhook_status_unconfigured, R.string.webhook_status_action_setup);
            return;
        }
        if (!enabled) {
            showSetupHint(R.string.webhook_status_disabled, R.string.webhook_status_action_open);
            return;
        }
        action.setVisibility(View.GONE);
        String reportStatus = WebhookReportPrefs.getWebhookLastReportStatus(activity);
        String lastReportedText = KeepADBWebhookStatusText.lastReported(activity,
                DateFormat.MEDIUM, DateFormat.MEDIUM);
        // #483: display only -- the stored endpoint stays as reported.
        String storedEndpoint = WebhookReportPrefs.getWebhookLastReportedEndpoint(activity);
        String lastEndpoint = KeepADBPrivacy.maskEndpointForDisplay(activity, storedEndpoint);
        boolean deregistered = WebhookReportPrefs.WEBHOOK_STATUS_DEREGISTERED.equals(reportStatus);
        if (deregistered || lastEndpoint == null || lastEndpoint.trim().isEmpty()) {
            // Distinguish "never reported anything yet" from "was reported, then successfully
            // deregistered" -- both leave no current endpoint, but reusing the same "none yet"
            // text for a just-completed deregistration reads as if one were still pending.
            // #1036 review: a deregistered state shows no endpoint, even a leftover one.
            lastEndpoint = deregistered
                    ? activity.getString(R.string.webhook_status_deregistered)
                    : activity.getString(R.string.webhook_status_no_endpoint);
            storedEndpoint = null;
        }
        // #1036: one row per fact; the copy buttons copy the real values.
        status.setVisibility(View.GONE);
        urlValue.setText(activity.getString(R.string.webhook_status_url,
                KeepADBPrivacy.maskWebhookUrlForDisplay(activity, url)));
        endpointValue.setText(activity.getString(R.string.webhook_status_endpoint, lastEndpoint));
        lastReported.setText(activity.getString(R.string.webhook_status_last, lastReportedText));
        urlCopyValue = KeepADBWebhookStatusText.copyableUrl(url);
        endpointCopyValue = KeepADBWebhookStatusText.copyableEndpoint(storedEndpoint);
        urlCopy.setVisibility(urlCopyValue != null ? View.VISIBLE : View.GONE);
        endpointCopy.setVisibility(endpointCopyValue != null ? View.VISIBLE : View.GONE);
        details.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
    }

    private void showSetupHint(int textRes, int actionRes) {
        urlCopyValue = null;
        endpointCopyValue = null;
        details.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(textRes);
        action.setText(actionRes);
        action.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
    }
}
