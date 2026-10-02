package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>#714: every allowed access point is shown with its stable entry number ({@code #id}) and
 * can be given a name of its own through the pencil next to its name; the band of every shown
 * BSSID is added behind it in brackets, from data Android already holds ({@link
 * KeepADBAccessPointBand}). Number, name and band are display only: trust still keys on the BSSID.
 *
 * <p>#721: where Android holds no band right now, the last band seen while "Observe access
 * points" is on is shown instead; with neither, the BSSID stands alone, without brackets.
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
    /** #686: the mesh question after allowing an access point; dismissed in onDestroy. */
    private AlertDialog activeMeshDialog;
    /** #714: the popup naming one access point; dismissed in onDestroy like the mesh question. */
    private AlertDialog activeNameDialog;
    /** The allowed entries by upper-case BSSID and the cached band data; replaced on every render. */
    private Map<String, KeepADBTrustedNetwork.Entry> entriesByBssid = new HashMap<>();
    private Map<String, Integer> frequencies = new HashMap<>();
    /** #721: the last band seen per upper-case BSSID while observing; replaced on every render. */
    private Map<String, Integer> storedBands = new HashMap<>();
    /** Numbers the hidden names of the view being shown; replaced on every render (#654). */
    private KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

    static Intent intent(Context context, String view) {
        return new Intent(context, NetworkListActivity.class).putExtra(EXTRA_VIEW, view);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onDestroy() {
        // Derived from live data and only offered right after an allow; not restored (#686).
        if (activeMeshDialog != null) {
            if (activeMeshDialog.isShowing()) {
                activeMeshDialog.dismiss();
            }
            activeMeshDialog = null;
        }
        if (activeNameDialog != null) {
            if (activeNameDialog.isShowing()) {
                activeNameDialog.dismiss();
            }
            activeNameDialog = null;
        }
        super.onDestroy();
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
        // #725: same eye as on the main view; redraws the masked list at once.
        KeepADBPrivacyToggle.bind(this, this::render);
        showMore.setOnClickListener(v -> {
            expanded = !expanded;
            render();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepADBPrivacyToggle.update(this);
        render();
    }

    String getListView() {
        return listView;
    }

    private void render() {
        numbering = new KeepADBNetworkDisplay.Numbering();
        entriesByBssid = new HashMap<>();
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(this)) {
            entriesByBssid.put(entry.bssid.toUpperCase(Locale.ROOT), entry);
        }
        // Read once per drawing from what Android already holds; never starts a scan (#714).
        frequencies = KeepADBAccessPointBand.read(this);
        // #721: the last band seen while observing, used only where the live reading has none.
        storedBands = KeepADBAccessPointBand.readStored(this);
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
                titleView.setText(R.string.network_view_allowed_title);
                introView.setText(R.string.network_view_allowed_intro);
                // The list stays reachable in "all networks" mode but does not count there.
                inactiveHint.setText(KeepADBNetworkCardText.inactiveListHint(this,
                        KeepADBTrustedNetwork.isSsidMatchingEnabled(this)));
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
        addRows(rows.others);
        if (KeepADBTrustedNetwork.getEntries(this).isEmpty()) {
            emptyView.setText(R.string.network_view_allowed_empty);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    // --- Observed access points --------------------------------------------------------------

    private void renderObserved() {
        ApRows rows = accessPointRows(true);
        showCurrent(rows.current);
        addRows(rows.others);
        if (rows.others.isEmpty()) {
            emptyView.setText(R.string.wifi_aps_empty);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    /** The current access point plus either the allowed ones or the observed ones. */
    private static final class ApRows {
        KeepADBAccessPointOverview.ApItem current;
        final List<KeepADBAccessPointOverview.ApItem> others = new ArrayList<>();
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
        return rows;
    }

    /** #721: the live band of {@code bssid}, else the stored one, else none ({@code UNKNOWN}). */
    private int bandOf(String bssid) {
        return KeepADBAccessPointBand.displayBand(frequencies, storedBands, bssid);
    }

    private void showCurrent(KeepADBAccessPointOverview.ApItem current) {
        currentRow.setVisibility(View.VISIBLE);
        if (current == null) {
            TextView unknown = textView(R.string.wifi_aps_current_unknown, 13, R.color.night_muted);
            currentRow.addView(unknown);
            return;
        }
        currentRow.addView(buildAccessPointRow(current, true));
    }

    private void addRows(List<KeepADBAccessPointOverview.ApItem> others) {
        boolean collapsible = others.size() > COLLAPSED_ROWS;
        boolean showAll = expanded && collapsible;
        int visible = showAll || !collapsible ? others.size() : COLLAPSED_ROWS;
        for (int i = 0; i < visible; i++) {
            list.addView(buildAccessPointRow(others.get(i), false));
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

    private View buildAccessPointRow(KeepADBAccessPointOverview.ApItem item,
                                     boolean highlightCurrent) {
        // The numbering is always asked for the network name, so hidden-name numbers do not
        // depend on whether an own name is shown instead.
        String networkName = KeepADBNetworkDisplay.label(this, item.ssid, item.bssid, numbering);
        KeepADBTrustedNetwork.Entry entry = entriesByBssid.get(item.bssid);
        boolean ssidKnown = item.ssid != null && !item.ssid.isEmpty();
        boolean named = entry != null && entry.customName != null;
        String name = named
                ? KeepADBNetworkDisplay.customName(this, entry.customName) : networkName;
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(this, item.bssid,
                bandOf(item.bssid));

        // Lines below the title: the unchanged network name behind an own name, then the BSSID
        // with its band. Without a name and without a network name the BSSID is the title itself,
        // so the band follows it there instead of in a second line.
        List<String> details = new ArrayList<>();
        String title = name;
        if (named && ssidKnown) details.add(networkName);
        if (ssidKnown || named) {
            details.add(bssidLine);
        } else {
            title = bssidLine;
        }
        // #722: the stable number of the stored entry (#714) follows the name in brackets, and
        // only where the same network name is shared by several access points. Rows that are no
        // stored entry (observed, not allowed) have none.
        if (entry != null && ssidKnown && item.meshCount > 1) {
            title = KeepADBNetworkDisplay.withApNumber(title, entry.id);
        }
        String primary = highlightCurrent
                ? getString(R.string.wifi_aps_current_badge) + " · " + title : title;
        boolean allowlist = KeepADBTrustedNetwork.isAllowlistMode(this);
        KeepADBNetworkCardState.Connection state = item.trusted
                ? KeepADBNetworkCardState.Connection.ALLOWED_AP
                : KeepADBNetworkCardState.Connection.NOT_ALLOWED;
        KeepADBNetworkCardState.Mode mode = allowlist
                ? KeepADBNetworkCardState.Mode.ALLOWED_APS : KeepADBNetworkCardState.Mode.ALL_WIFI;

        // The pencil edits the name of a stored entry. While the privacy mode is on it is not
        // offered: the popup would show the current name and a blank field would read as "reset".
        Runnable edit = entry != null && !KeepADBNetworkDisplay.hidden(this)
                ? () -> showNameDialog(item.bssid) : null;
        String editDescription = getString(R.string.network_ap_rename_accessibility, name);

        String actionLabel = getString(item.trusted ? R.string.wifi_ssids_remove_button
                : R.string.wifi_ssids_add_button);
        String actionDescription = getString(item.trusted
                        ? R.string.network_action_remove_ap_accessibility
                        : R.string.network_action_allow_ap_accessibility, name);
        Runnable action = () -> {
            if (item.trusted) {
                KeepADBNetworkActions.removeAccessPoint(this, item.bssid, this::render);
            } else {
                activeMeshDialog = KeepADBNetworkActions.allowAccessPoint(this, item.bssid,
                        item.label(), item.current, this::render);
                if (activeMeshDialog != null) {
                    activeMeshDialog.setOnDismissListener(dialog -> {
                        if (activeMeshDialog == dialog) {
                            activeMeshDialog = null;
                        }
                    });
                    activeMeshDialog.show();
                }
            }
        };
        return buildRow(primary, details,
                getString(KeepADBNetworkCardText.connectionLabel(state)),
                getColor(KeepADBNetworkCardText.connectionColor(state, mode)),
                null, actionLabel, actionDescription, !item.trusted, action, highlightCurrent,
                edit, editDescription);
    }

    /** #714: opens the popup naming the allowed access point {@code bssid}, if it still is one. */
    private void showNameDialog(String bssid) {
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(this)) {
            if (!entry.bssid.equalsIgnoreCase(bssid)) continue;
            if (activeNameDialog != null) {
                activeNameDialog.dismiss();
            }
            AlertDialog dialog = KeepADBNetworkActions.editAccessPointName(this, entry, this::render);
            activeNameDialog = dialog;
            dialog.setOnDismissListener(d -> {
                if (activeNameDialog == d) {
                    activeNameDialog = null;
                }
            });
            dialog.show();
            return;
        }
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
        for (int i = entries.size() - 1; i >= 0; i--) {
            KeepADBBlockedNetworkHistory.Entry entry = entries.get(i);
            String name = KeepADBNetworkDisplay.label(this, entry.ssid, entry.bssid, numbering);
            boolean nameIsBssid = entry.ssid == null || entry.ssid.isEmpty();
            String bssidLine = KeepADBNetworkDisplay.bssidWithBand(this, entry.bssid,
                    bandOf(entry.bssid));
            String title = name;
            String detail = getString(R.string.settings_trusted_network_blocked_detail, bssidLine,
                    DateUtils.getRelativeTimeSpanString(entry.lastSeenAt, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS).toString());
            if (nameIsBssid) {
                // The BSSID already is the title, with its band behind it; only the age is left
                // to say.
                title = bssidLine;
                detail = DateUtils.getRelativeTimeSpanString(entry.lastSeenAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
            }
            list.addView(buildRow(title, Collections.singletonList(detail), null, 0,
                    getString(R.string.network_view_prevented_reason),
                    getString(R.string.wifi_ssids_add_button),
                    getString(R.string.network_action_allow_ap_accessibility, name), true,
                    () -> KeepADBNetworkActions.allowAccessPoint(this, entry.bssid, entry.label(),
                            false, this::render),
                    false, null, null));
        }
    }

    // --- Row building ------------------------------------------------------------------------

    /**
     * One row as a vertical stack -- name (with the pencil, if any), details, status, reason, then
     * the action button -- so a large font or a narrow display never squeezes the text against the
     * button.
     */
    private View buildRow(String primary, List<String> details, String status, int statusColor,
                          String reason, String actionLabel, String actionDescription,
                          boolean primaryAction, Runnable action, boolean highlighted,
                          Runnable edit, String editDescription) {
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
        if (edit == null) {
            row.addView(primaryView);
        } else {
            // #714: the pencil sits directly next to the name; its touch target stays 48dp.
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(primaryView, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton pencil = new ImageButton(this);
            pencil.setImageResource(R.drawable.ic_edit);
            pencil.setBackgroundResource(R.drawable.bg_btn_header);
            pencil.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            pencil.setPadding(dp(12), dp(12), dp(12), dp(12));
            pencil.setContentDescription(editDescription);
            pencil.setOnClickListener(v -> edit.run());
            titleRow.addView(pencil, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.addView(titleRow);
        }
        for (String detail : details) {
            if (detail != null && !detail.isEmpty()) {
                row.addView(textView(detail, 12, R.color.night_muted));
            }
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
