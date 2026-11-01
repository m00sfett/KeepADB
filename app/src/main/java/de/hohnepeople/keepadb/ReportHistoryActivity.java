package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * #892: the local report history of {@link KeepADBReportHistory}, newest first. Opened from the
 * webhook section of the Settings and from the webhook alert (#893). Addresses are shown in full
 * (the history stays on the device; a notice on top says so) and masked only while the privacy
 * mode is on. There is no share or export path.
 */
public class ReportHistoryActivity extends Activity {

    static Intent intent(Context context) {
        return new Intent(context, ReportHistoryActivity.class);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report_history);
        KeepADBWindowInsets.apply(getWindow(), findViewById(R.id.header_bar),
                findViewById(R.id.report_history_scroll));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.report_history_clear).setOnClickListener(v -> {
            KeepADBReportHistory.clear(this);
            Toast.makeText(this, R.string.report_history_cleared_toast, Toast.LENGTH_SHORT).show();
            render();
        });
        KeepADBPrivacyToggle.bind(this, this::render);
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepADBPrivacyToggle.update(this);
        render();
    }

    private void render() {
        List<KeepADBReportHistory.Entry> entries = KeepADBReportHistory.entries(this);
        LinearLayout rows = findViewById(R.id.report_history_rows);
        rows.removeAllViews();
        findViewById(R.id.report_history_empty)
                .setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.report_history_clear)
                .setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        boolean mask = KeepADBPreferences.isPrivacyModeEnabled(this);
        java.text.DateFormat time = timeFormat(this);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        for (KeepADBReportHistory.Entry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, pad, 0, pad);

            TextView title = new TextView(this);
            title.setText(time.format(new Date(entry.timeMs)) + " · " + eventLabel(this, entry));
            title.setTextColor(getColor(entry.ok ? R.color.text_yellow : R.color.link_red));
            title.setTextSize(15);
            title.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                    android.graphics.Typeface.BOLD));
            row.addView(title);

            String details = details(this, entry, mask);
            if (!details.isEmpty()) {
                TextView detail = new TextView(this);
                detail.setText(details);
                detail.setTextColor(getColor(R.color.night_muted));
                detail.setTextSize(13);
                row.addView(detail);
            }
            rows.addView(row);
        }
    }

    /** Weekday plus time to the second: retries are seconds apart, the list spans a day. */
    static java.text.DateFormat timeFormat(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String skeleton = DateFormat.is24HourFormat(context) ? "EEEHHmmss" : "EEEhhmmssa";
        return new java.text.SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, skeleton), locale);
    }

    static String eventLabel(Context context, KeepADBReportHistory.Entry entry) {
        switch (entry.event) {
            case ENDPOINT_FOUND:
                return context.getString(R.string.report_history_event_endpoint_found);
            case ENDPOINT_LOST:
                return context.getString(R.string.report_history_event_endpoint_lost);
            case UNREGISTER:
                return context.getString(entry.ok ? R.string.report_history_event_unregister_ok
                        : R.string.report_history_event_unregister_failed);
            case REPORT:
            default:
                return context.getString(entry.ok ? R.string.report_history_event_report_ok
                        : R.string.report_history_event_report_failed);
        }
    }

    /** Endpoint, result and retry, joined with " · "; empty parts are left out. */
    static String details(Context context, KeepADBReportHistory.Entry entry, boolean mask) {
        List<String> parts = new ArrayList<>();
        if (!entry.endpoint.isEmpty()) {
            parts.add(mask ? KeepADBAddressMask.maskEndpointForDisplay(entry.endpoint) : entry.endpoint);
        }
        String outcome = outcomeLabel(context, entry.outcome);
        if (!outcome.isEmpty()) parts.add(outcome);
        if (entry.retry > 0) {
            parts.add(entry.maxRetries > 0
                    ? context.getString(R.string.report_history_retry, entry.retry, entry.maxRetries)
                    : context.getString(R.string.report_history_retry_open, entry.retry));
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    static String outcomeLabel(Context context, String outcome) {
        if (outcome == null || outcome.isEmpty() || "unknown".equals(outcome)) return "";
        if (outcome.startsWith("http=")) {
            return "HTTP " + outcome.substring(5);
        }
        if (outcome.startsWith("error=")) {
            String error = outcome.substring(6);
            switch (error) {
                case "SocketTimeoutException":
                    return context.getString(R.string.report_history_outcome_timeout);
                case "ConnectException":
                    return context.getString(R.string.report_history_outcome_refused);
                case "UnknownHostException":
                    return context.getString(R.string.report_history_outcome_unknown_host);
                default:
                    return context.getString(R.string.report_history_outcome_error, error);
            }
        }
        return "";
    }
}
