package de.hohnepeople.keepadb;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * #1051: the texts, status badges and view builders of the "Networks" list, split out of {@link
 * NetworkListRenderer} and shared with {@link NetworkListDialogs}: what a status or a block reason
 * reads as, the spoken line of a row, and the list's own wrappers around {@link KeepADBUiKit}.
 * The privacy scope and the assistant mode come from the renderer.
 */
final class NetworkListViews {
    enum Badge { OK, OK_DASHED, DANGER, NEUTRAL, WARN }

    private final Activity activity;
    private final NetworkListRenderer renderer;

    NetworkListViews(Activity activity, NetworkListRenderer renderer) {
        this.activity = activity;
        this.renderer = renderer;
    }

    /** #767: the assistant's card must not cut a word at a large font size (#761). */
    private boolean assistantMode() {
        return renderer.isAssistantMode();
    }

    // --- Texts and badges --------------------------------------------------------------------

    String reasonText(KeepADBNetworkList.Reason reason, String ssid, boolean trustedStored) {
        if (reason == KeepADBNetworkList.Reason.NAME && ssid != null) {
            return activity.getString(trustedStored ? R.string.networks_reason_name_trusted
                    : R.string.networks_reason_name, KeepADBNetworkDisplay.quoted(renderer.shown(), ssid));
        }
        return activity.getString(R.string.networks_reason_access_point);
    }

    TextView statusBadge(KeepADBNetworkList.Status status) {
        return badge(statusBadgeKind(status), activity.getString(statusLabelRes(status)));
    }

    private static Badge statusBadgeKind(KeepADBNetworkList.Status status) {
        switch (status) {
            case TRUSTED:
                return Badge.OK;
            case TRUSTED_BY_NAME:
                return Badge.OK_DASHED;
            case BLOCKED:
                return Badge.DANGER;
            case UNREADABLE:
                return Badge.WARN;
            case UNKNOWN:
            case NO_WIFI:
            default:
                return Badge.NEUTRAL;
        }
    }

    private static int statusLabelRes(KeepADBNetworkList.Status status) {
        switch (status) {
            case TRUSTED:
                return R.string.networks_badge_trusted;
            case TRUSTED_BY_NAME:
                return R.string.networks_badge_trusted_name;
            case BLOCKED:
                return R.string.network_badge_blocked;
            case UNREADABLE:
                return R.string.network_status_unreadable;
            case NO_WIFI:
                return R.string.network_status_no_wifi;
            case UNKNOWN:
            default:
                return R.string.networks_badge_unknown;
        }
    }

    String statusLabel(KeepADBNetworkList.Status status) {
        return activity.getString(statusLabelRes(status));
    }

    /** Title, Wi-Fi name, address, status, reason -- in this order, as one spoken line. */
    String describe(String title, String ssid, String bssid, String status, String reason) {
        List<String> parts = new ArrayList<>();
        parts.add(title);
        if (ssid != null && !ssid.equals(title)) {
            parts.add(activity.getString(R.string.networks_a11y_wifi, ssid));
        }
        if (bssid != null) parts.add(bssid);
        if (status != null) parts.add(status);
        if (reason != null) parts.add(reason);
        return TextUtils.join(", ", parts);
    }

    TextView badge(Badge kind, String label) {
        int background;
        int color;
        switch (kind) {
            case OK:
                background = R.drawable.bg_badge_ok;
                color = R.color.status_ok_green;
                break;
            case OK_DASHED:
                background = R.drawable.bg_badge_ok_dashed;
                color = R.color.status_ok_green;
                break;
            case DANGER:
                background = R.drawable.bg_badge_danger;
                color = R.color.link_red;
                break;
            case WARN:
                background = R.drawable.bg_badge_warn;
                color = R.color.text_yellow;
                break;
            case NEUTRAL:
            default:
                background = R.drawable.bg_badge_neutral;
                color = R.color.night_muted;
                break;
        }
        // The word is part of the row's spoken line; a badge is no focus target of its own.
        TextView view = KeepADBUiKit.badge(activity, label, background, color);
        // The assistant must not cut a word at a large font size (#761, #767).
        if (!assistantMode()) view.setSingleLine(true);
        return view;
    }

    // --- View helpers ------------------------------------------------------------------------

    /** Title and badge: side by side, or the badge below the title on a narrow or large display. */
    View titleLine(String title, boolean mono, TextView badge, int sp) {
        TextView titleView = mono ? monoLine(title) : condensed(title, sp, R.color.night_text);
        if (mono) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            titleView.setTextColor(activity.getColor(R.color.night_text));
        }
        if (!assistantMode()) {
            titleView.setMaxLines(2);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
        }
        return KeepADBUiKit.titleLine(activity, titleView, badge);
    }

    TextView monoLine(String value) {
        TextView view = text(value, 13, R.color.night_muted);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    TextView sectionHeading(int textRes, int topMarginDp) {
        TextView heading = condensed(activity.getString(textRes), 12, R.color.night_muted);
        heading.setAllCaps(true);
        heading.setAccessibilityHeading(true);
        setTopMargin(heading, topMarginDp);
        return heading;
    }

    LinearLayout panel(int background) {
        return KeepADBUiKit.panel(activity, background);
    }

    void setTopMargin(View view, int topDp) {
        LinearLayout.LayoutParams params = view.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) view.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        view.setLayoutParams(params);
    }

    Button stackedButton(String label, boolean primary, View.OnClickListener listener) {
        return KeepADBUiKit.actionButton(activity, label, primary, listener, 8);
    }

    Button plainButton(String label, View.OnClickListener listener) {
        return KeepADBUiKit.plainButton(activity, label, listener);
    }

    TextView text(String value, int sp, int colorRes) {
        return KeepADBUiKit.text(activity, value, sp, colorRes);
    }

    TextView condensed(String value, int sp, int colorRes) {
        return KeepADBUiKit.condensed(activity, value, sp, colorRes);
    }

    int dp(int value) {
        return KeepADBUiKit.dp(activity, value);
    }
}
