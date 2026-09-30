package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * #654: the management views behind the Network card in {@link SettingsActivity} -- the allowed
 * access points, the automatic history of recently prevented re-enabling, and the observed access
 * points. Each opens from its own entry in the card, so none of them depends on the observation
 * option, on the mode or on another view.
 *
 * <p>None of the three is a block list. The history fills itself when Keep-Alive holds back a
 * re-enable, cannot be edited by hand and only offers to allow the access point it names; the
 * observed list only records what was seen. Allowing an access point here grants exactly that and
 * never switches Wireless Debugging on ({@link KeepADBNetworkActions#allowAccessPoint}).
 *
 * <p>Names and addresses follow the privacy mode through {@link KeepADBNetworkDisplay}.
 */
public class NetworkListActivity extends Activity {
    /** Intent extra naming the view to show; one of the {@code VIEW_*} values. */
    static final String EXTRA_VIEW = "network_list_view";
    static final String VIEW_ALLOWED = "allowed";
    static final String VIEW_PREVENTED = "prevented";
    static final String VIEW_OBSERVED = "observed";

    /** Longer lists collapse behind a "show more" entry so the screen opens quickly (#468). */
    static final int COLLAPSED_ROWS = 20;

    private String listView = VIEW_ALLOWED;
    private TextView titleView;
    private TextView introView;
    private TextView inactiveHint;
    private TextView privacyHint;
    private LinearLayout currentRow;
    private LinearLayout list;
    private TextView showMore;
    private TextView emptyView;
    private boolean expanded;

    static Intent intent(Context context, String view) {
        return new Intent(context, NetworkListActivity.class).putExtra(EXTRA_VIEW, view);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_list);
        KeepADBWindowInsets.apply(
                getWindow(),
                findViewById(R.id.header_bar),
                findViewById(R.id.network_list_scroll));

        String requested = getIntent().getStringExtra(EXTRA_VIEW);
        if (VIEW_PREVENTED.equals(requested) || VIEW_OBSERVED.equals(requested)) {
            listView = requested;
        }

        titleView = findViewById(R.id.network_list_title);
        introView = findViewById(R.id.network_list_intro);
        inactiveHint = findViewById(R.id.network_list_inactive_hint);
        privacyHint = findViewById(R.id.network_list_privacy_hint);
        currentRow = findViewById(R.id.wifi_aps_current_row);
        list = findViewById(R.id.wifi_aps_list);
        showMore = findViewById(R.id.wifi_aps_toggle);
        emptyView = findViewById(R.id.wifi_aps_empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        showMore.setOnClickListener(v -> {
            expanded = !expanded;
            render();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    String getListView() {
        return listView;
    }

    private void render() {
        currentRow.removeAllViews();
        list.removeAllViews();
        showMore.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        currentRow.setVisibility(View.GONE);
        privacyHint.setVisibility(KeepADBNetworkDisplay.hidden(this) ? View.VISIBLE : View.GONE);
        inactiveHint.setVisibility(View.GONE);

        switch (listView) {
            case VIEW_PREVENTED:
                titleView.setText(R.string.network_row_prevented);
                introView.setText(R.string.network_view_prevented_intro);
                renderPrevented();
                break;
            case VIEW_OBSERVED:
                titleView.setText(R.string.network_view_observed_title);
                introView.setText(R.string.network_view_observed_intro);
                renderObserved();
                break;
            case VIEW_ALLOWED:
            default:
                titleView.setText(R.string.network_row_allowed);
                introView.setText(R.string.network_view_allowed_intro);
                // The list stays reachable in "all networks" mode but does not count there.
                inactiveHint.setVisibility(KeepADBTrustedNetwork.isAllowlistMode(this)
                        ? View.GONE : View.VISIBLE);
                renderAllowed();
                break;
        }
    }

    // --- Allowed access points ---------------------------------------------------------------

    private void renderAllowed() {
        ApRows rows = accessPointRows(false);
        showCurrent(rows.current);
        addRows(rows.others, rows.firstOtherPosition);
        if (KeepADBTrustedNetwork.getEntries(this).isEmpty()) {
            emptyView.setText(R.string.network_view_allowed_empty);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    // --- Observed access points --------------------------------------------------------------

    private void renderObserved() {
        ApRows rows = accessPointRows(true);
        showCurrent(rows.current);
        addRows(rows.others, rows.firstOtherPosition);
        if (rows.others.isEmpty()) {
            emptyView.setText(R.string.wifi_aps_empty);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    /** The current access point plus either the allowed ones or the observed ones. */
    private static final class ApRows {
        KeepADBAccessPointOverview.ApItem current;
        final List<KeepADBAccessPointOverview.ApItem> others = new ArrayList<>();
        int firstOtherPosition = 1;
    }

    private ApRows accessPointRows(boolean observedOnly) {
        ApRows rows = new ApRows();
        Set<String> observed = new HashSet<>();
        if (observedOnly) {
            for (KeepADBBssidHistory.Observation observation
                    : KeepADBBssidHistory.getRecentObservations(this)) {
                if (observation.bssid != null) {
                    observed.add(observation.bssid.toUpperCase(Locale.ROOT));
                }
            }
        }
        for (KeepADBAccessPointOverview.ApItem item : KeepADBAccessPointOverview.buildItems(this)) {
            if (item.current) {
                rows.current = item;
            } else if (observedOnly ? observed.contains(item.bssid) : item.trusted) {
                rows.others.add(item);
            }
        }
        rows.firstOtherPosition = rows.current != null ? 2 : 1;
        return rows;
    }

    private void showCurrent(KeepADBAccessPointOverview.ApItem current) {
        currentRow.setVisibility(View.VISIBLE);
        if (current == null) {
            TextView unknown = textView(R.string.wifi_aps_current_unknown, 13, R.color.night_muted);
            currentRow.addView(unknown);
            return;
        }
        currentRow.addView(buildAccessPointRow(current, 1, true));
    }

    private void addRows(List<KeepADBAccessPointOverview.ApItem> others, int firstPosition) {
        boolean collapsible = others.size() > COLLAPSED_ROWS;
        boolean showAll = expanded && collapsible;
        int visible = showAll || !collapsible ? others.size() : COLLAPSED_ROWS;
        for (int i = 0; i < visible; i++) {
            list.addView(buildAccessPointRow(others.get(i), firstPosition + i, false));
        }
        if (collapsible) {
            showMore.setVisibility(View.VISIBLE);
            showMore.setText(showAll
                    ? getString(R.string.wifi_aps_show_less_button)
                    : getString(R.string.wifi_aps_show_more_button, others.size() - COLLAPSED_ROWS));
        } else {
            expanded = false;
        }
    }

    private View buildAccessPointRow(KeepADBAccessPointOverview.ApItem item, int position,
                                     boolean highlightCurrent) {
        String name = KeepADBNetworkDisplay.label(this, item.ssid, item.bssid, position);
        String primary = highlightCurrent
                ? getString(R.string.wifi_aps_current_badge) + " · " + name : name;
        boolean allowlist = KeepADBTrustedNetwork.isAllowlistMode(this);
        KeepADBNetworkCardState.Connection state = item.trusted
                ? KeepADBNetworkCardState.Connection.ALLOWED_AP
                : KeepADBNetworkCardState.Connection.NOT_ALLOWED;
        KeepADBNetworkCardState.Mode mode = allowlist
                ? KeepADBNetworkCardState.Mode.ALLOWED_APS : KeepADBNetworkCardState.Mode.ALL_WIFI;

        // The secondary line is the BSSID; it is dropped when the name already is the BSSID.
        boolean nameIsBssid = item.ssid == null || item.ssid.isEmpty();
        String secondary = nameIsBssid ? null : KeepADBNetworkDisplay.bssid(this, item.bssid);

        String actionLabel = getString(item.trusted ? R.string.wifi_ssids_remove_button
                : R.string.wifi_ssids_add_button);
        String actionDescription = getString(item.trusted
                        ? R.string.network_action_remove_ap_accessibility
                        : R.string.network_action_allow_ap_accessibility, name);
        Runnable action = () -> {
            if (item.trusted) {
                KeepADBNetworkActions.removeAccessPoint(this, item.bssid, this::render);
            } else {
                KeepADBNetworkActions.allowAccessPoint(this, item.bssid, item.label(),
                        item.current, this::render);
            }
        };
        return buildRow(primary, secondary,
                getString(KeepADBNetworkCardText.connectionLabel(state)),
                getColor(KeepADBNetworkCardText.connectionColor(state, mode)),
                null, actionLabel, actionDescription, !item.trusted, action, highlightCurrent);
    }

    // --- Recently prevented re-enabling ------------------------------------------------------

    private void renderPrevented() {
        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(this);
        if (entries.isEmpty()) {
            emptyView.setText(R.string.network_view_prevented_empty);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        // Newest first: the access point the user just failed to connect on is the one they came
        // here for, and getEntries() returns the log oldest-first.
        int position = 1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            KeepADBBlockedNetworkHistory.Entry entry = entries.get(i);
            String name = KeepADBNetworkDisplay.label(this, entry.ssid, entry.bssid, position);
            boolean nameIsBssid = entry.ssid == null || entry.ssid.isEmpty();
            String detail = getString(R.string.settings_trusted_network_blocked_detail,
                    KeepADBNetworkDisplay.bssid(this, entry.bssid),
                    DateUtils.getRelativeTimeSpanString(entry.lastSeenAt, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS).toString());
            if (nameIsBssid) {
                // The BSSID already is the title; only the age is left to say.
                detail = DateUtils.getRelativeTimeSpanString(entry.lastSeenAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
            }
            list.addView(buildRow(name, detail, null, 0,
                    getString(R.string.network_view_prevented_reason),
                    getString(R.string.wifi_ssids_add_button),
                    getString(R.string.network_action_allow_ap_accessibility, name), true,
                    () -> KeepADBNetworkActions.allowAccessPoint(this, entry.bssid, entry.label(),
                            false, this::render),
                    false));
            position++;
        }
    }

    // --- Row building ------------------------------------------------------------------------

    /**
     * One row as a vertical stack -- name, detail, status, reason, then the action button -- so a
     * large font or a narrow display never squeezes the text against the button.
     */
    private View buildRow(String primary, String secondary, String status, int statusColor,
                          String reason, String actionLabel, String actionDescription,
                          boolean primaryAction, Runnable action, boolean highlighted) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (!highlighted) {
            row.setBackgroundResource(R.drawable.bg_card_clickable);
            int pad = dp(12);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            row.setLayoutParams(params);
        }

        TextView primaryView = textView(primary, 15, R.color.night_text);
        row.addView(primaryView);
        if (secondary != null && !secondary.isEmpty()) {
            TextView secondaryView = textView(secondary, 12, R.color.night_muted);
            row.addView(secondaryView);
        }
        if (status != null) {
            TextView statusView = textView(status, 13, R.color.night_text);
            statusView.setTextColor(statusColor);
            statusView.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                    android.graphics.Typeface.BOLD));
            row.addView(statusView);
        }
        if (reason != null) {
            row.addView(textView(reason, 12, R.color.night_muted));
        }

        Button button = new Button(this);
        button.setBackgroundResource(primaryAction
                ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setTextColor(getColor(primaryAction ? R.color.title_yellow : R.color.text_yellow));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                android.graphics.Typeface.BOLD));
        button.setText(actionLabel);
        button.setContentDescription(actionDescription);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(8);
        buttonParams.gravity = Gravity.START;
        button.setLayoutParams(buttonParams);
        row.addView(button);
        return row;
    }

    private TextView textView(int textRes, int sp, int colorRes) {
        return textView(getString(textRes), sp, colorRes);
    }

    private TextView textView(String text, int sp, int colorRes) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(colorRes));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
