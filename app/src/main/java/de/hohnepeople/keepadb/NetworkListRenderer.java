package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #762: draws the single "Networks" list into {@link NetworkListActivity} -- the current network on
 * top, the saved ones grouped by Wi-Fi name below, each with one status badge -- and hosts its
 * dialogs. What is shown comes from {@link KeepADBNetworkList}, what a tap changes from {@link
 * KeepADBNetworkListActions}; this class decides neither.
 *
 * <p><strong>Decisions are not built here.</strong> An undecided current network is asked through
 * the embedded {@link NetworkDecisionView} (#766), the same component the notification dialog uses;
 * the dialogs of this class only change what is already saved (stop trusting, block, lift a block,
 * rename).
 *
 * <p><strong>Privacy mode.</strong> While it is on, nothing of the list is built at all: the page
 * shows a placeholder and no view and no content description holds a Wi-Fi name or an address. The
 * embedded decision view, which shows the name on purpose (#766), is only bound while the privacy
 * mode is off. The glossary and the comfort switch carry no network data and stay.
 */
final class NetworkListRenderer {

    static final int REQUEST_LOCATION = 762;

    /** A group with more access points than this folds to {@link #COLLAPSED_ROWS} rows. */
    static final int COLLAPSE_ABOVE = 5;
    static final int COLLAPSED_ROWS = 3;

    private enum Badge { OK, OK_DASHED, DANGER, NEUTRAL, WARN }

    private final Activity activity;
    private final LinearLayout root;
    private final Runnable onChanged;
    private final Set<String> expandedGroups = new HashSet<>();
    /** #767: the current-network card drawn for the setup assistant, see {@link #renderCurrentForAssistant}. */
    private boolean assistantMode;
    private boolean glossaryOpen;
    private AlertDialog dialog;

    private Map<String, Integer> frequencies;

    NetworkListRenderer(Activity activity, LinearLayout root, Runnable onChanged) {
        this.activity = activity;
        this.root = root;
        this.onChanged = onChanged;
    }

    /** Dismisses the open dialog, if any; derived from live data and never restored. */
    void dismissDialog() {
        if (dialog != null) {
            if (dialog.isShowing()) dialog.dismiss();
            dialog = null;
        }
    }

    void render() {
        dismissDialog();
        root.removeAllViews();
        root.setVisibility(View.VISIBLE);
        frequencies = KeepADBAccessPointBand.read(activity);

        addGlossary();
        addProtection();
        addForceNote();
        if (KeepADBNetworkDisplay.hidden(activity)) {
            addHiddenPlaceholder();
            return;
        }
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(activity);
        boolean wifiConnected = identity.isKnown() || KeepADBService.isWifiConnected(activity);
        KeepADBNetworkList.Snapshot snapshot =
                KeepADBNetworkList.build(activity, identity, wifiConnected);
        addCurrent(snapshot.current, identity);
        addSaved(snapshot);
    }

    /**
     * #767: the current-network card alone, for the setup assistant's "Trusted Wi-Fi" step. It is
     * the same card and the same embedded decision (#766) as in the list, with three differences:
     * no section heading (the step has its title), no tap-to-change on a decided network (the
     * assistant answers a question, it does not edit the list; a blocked network offers the way to
     * the list instead), and a "check again" button where the state depends on the user acting
     * elsewhere (no Wi-Fi, not readable). The name and the address are shown even while the
     * privacy mode is on: the step is the deliberate exception of #758, like the decision itself.
     */
    void renderCurrentForAssistant() {
        assistantMode = true;
        dismissDialog();
        root.removeAllViews();
        root.setVisibility(View.VISIBLE);
        frequencies = KeepADBAccessPointBand.read(activity);
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(activity);
        boolean wifiConnected = identity.isKnown() || KeepADBService.isWifiConnected(activity);
        KeepADBNetworkList.Snapshot snapshot =
                KeepADBNetworkList.build(activity, identity, wifiConnected);
        addCurrent(snapshot.current, identity);
    }

    // --- Glossary, protection, force ---------------------------------------------------------

    private void addGlossary() {
        TextView toggle = text(activity.getString(R.string.networks_glossary_toggle)
                + (glossaryOpen ? "  ▴" : "  ▾"), 13, R.color.text_yellow);
        toggle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        toggle.setMinHeight(dp(48));
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setClickable(true);
        toggle.setFocusable(true);
        toggle.setBackgroundResource(android.R.drawable.list_selector_background);
        toggle.setStateDescription(activity.getString(glossaryOpen
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
        toggle.setOnClickListener(v -> {
            glossaryOpen = !glossaryOpen;
            render();
        });
        root.addView(toggle);
        if (glossaryOpen) {
            root.addView(text(activity.getString(R.string.networks_glossary_ssid), 13,
                    R.color.night_text));
            TextView bssid = text(activity.getString(R.string.networks_glossary_bssid), 13,
                    R.color.night_text);
            bssid.setPadding(0, dp(8), 0, 0);
            root.addView(bssid);
        }
    }

    /** The one switch strict / convenience, and the protection level it belongs to. */
    private void addProtection() {
        LinearLayout card = panel(R.drawable.bg_panel);
        setTopMargin(card, 8);

        Switch comfort = new Switch(activity);
        comfort.setText(R.string.networks_comfort_title);
        comfort.setTextColor(activity.getColor(R.color.text_yellow));
        comfort.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        comfort.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        comfort.setMinHeight(dp(48));
        comfort.setChecked(KeepADBTrustedNetwork.isTrustByNameEnabled(activity));
        comfort.setOnCheckedChangeListener((button, checked) -> {
            KeepADBTrustedNetwork.setTrustByNameEnabled(activity, checked);
            KeepADBDiagnostics.event(activity, "user_action", "network_list",
                    checked ? "enable" : "disable", "trust_by_name");
            onChanged.run();
        });
        card.addView(comfort);
        card.addView(text(activity.getString(R.string.networks_comfort_hint), 13, R.color.night_muted));
        if (KeepADBTrustedNetwork.getProtectionLevel(activity)
                == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI) {
            TextView note = text(activity.getString(R.string.networks_comfort_no_effect), 13,
                    R.color.text_yellow);
            note.setPadding(0, dp(4), 0, 0);
            card.addView(note);
        }
        TextView level = text(activity.getString(R.string.networks_level,
                KeepADBForceNotice.levelLabel(activity)), 13, R.color.night_text);
        level.setPadding(0, dp(8), 0, 0);
        card.addView(level);
        root.addView(card);
    }

    private void addForceNote() {
        String line = KeepADBForceNotice.activeLine(activity);
        if (line == null) return;
        LinearLayout card = panel(R.drawable.bg_panel_advice);
        setTopMargin(card, 8);
        TextView title = condensed(line, 15, R.color.title_yellow);
        title.setAccessibilityHeading(true);
        card.addView(title);
        card.addView(text(activity.getString(R.string.networks_force_note), 13, R.color.night_text));
        root.addView(card);
    }

    private void addHiddenPlaceholder() {
        LinearLayout card = panel(R.drawable.bg_panel);
        setTopMargin(card, 16);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_privacy_eye_off);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView title = condensed(activity.getString(R.string.networks_hidden_title), 16,
                R.color.night_text);
        title.setAccessibilityHeading(true);
        title.setPadding(0, dp(8), 0, 0);
        card.addView(title);
        card.addView(text(activity.getString(R.string.networks_hidden_text), 13, R.color.night_muted));
        Button show = stackedButton(activity.getString(R.string.networks_hidden_show), false,
                v -> {
                    // The same as the eye in the title bar: one switch, one effect.
                    View eye = activity.findViewById(R.id.btn_toggle_privacy_mode);
                    if (eye != null) eye.performClick();
                });
        card.addView(show);
        root.addView(card);
    }

    // --- Current network ---------------------------------------------------------------------

    private void addCurrent(KeepADBNetworkList.Current current, KeepADBNetworkIdentity identity) {
        if (!assistantMode) root.addView(sectionHeading(R.string.networks_section_current, 16));
        LinearLayout card = panel(R.drawable.bg_panel);
        setTopMargin(card, 8);

        switch (current.status) {
            case NO_WIFI:
                addNoWifi(card);
                break;
            case UNREADABLE:
                addUnreadable(card, identity);
                break;
            default:
                addReadableCurrent(card, current);
                break;
        }
        root.addView(card);
    }

    private void addNoWifi(LinearLayout card) {
        TextView title = condensed(activity.getString(R.string.network_status_no_wifi), 16,
                R.color.night_text);
        card.addView(title);
        card.addView(text(activity.getString(R.string.network_cause_no_wifi), 13, R.color.night_muted));
        card.addView(stackedButton(activity.getString(R.string.network_action_wifi_settings), false,
                v -> KeepADBNetworkActions.openWifiSettings(activity)));
        if (assistantMode) addRecheck(card);
    }

    /** #767: the state changes outside this screen, so the assistant offers to read it again. */
    private void addRecheck(LinearLayout card) {
        card.addView(stackedButton(activity.getString(R.string.onboarding_recheck), false,
                v -> onChanged.run()));
    }

    private void addUnreadable(LinearLayout card, KeepADBNetworkIdentity identity) {
        boolean fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        LocationManager location = activity.getSystemService(LocationManager.class);
        boolean locationOn = location == null || location.isLocationEnabled();
        KeepADBNetworkCardState.Snapshot state = KeepADBNetworkCardState.derive(
                KeepADBNetworkCardState.read(activity, identity, true, fine, locationOn,
                        KeepADBBackgroundLocation.isGranted(activity)));
        card.addView(titleLine(activity.getString(R.string.network_status_unreadable), false,
                badge(Badge.WARN, activity.getString(R.string.network_status_unreadable)), 16));
        card.addView(text(activity.getString(KeepADBNetworkCardText.cause(state.cause)), 13,
                R.color.night_text));
        switch (state.action) {
            case GRANT_LOCATION:
                card.addView(stackedButton(activity.getString(
                        KeepADBNetworkCardText.action(state.action)), true, v -> requestLocation()));
                break;
            case OPEN_LOCATION_SETTINGS:
                card.addView(stackedButton(activity.getString(
                        KeepADBNetworkCardText.action(state.action)), true,
                        v -> KeepADBNetworkActions.openLocationSettings(activity)));
                break;
            case SET_UP_BACKGROUND:
                if (assistantMode) {
                    // The identity is masked although everything is allowed: the lasting fix is
                    // the background grant, which only the app's system page can give.
                    card.addView(stackedButton(activity.getString(
                            KeepADBNetworkCardText.action(state.action)), true,
                            v -> KeepADBBackgroundLocation.openSettings(activity)));
                }
                break;
            default:
                // The background grant is set up in the Settings card; the cause text names it.
                break;
        }
        if (assistantMode) addRecheck(card);
    }

    private void addReadableCurrent(LinearLayout card, KeepADBNetworkList.Current current) {
        String name = current.ssid == null
                ? activity.getString(R.string.wifi_aps_ssid_unknown) : current.ssid;
        KeepADBNetworkList.Row row = current.row;
        int band = KeepADBAccessPointBand.bandOf(frequencies, current.bssid);
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(activity, current.bssid, band);

        card.addView(titleLine(name, false, statusBadge(current.status), 18));
        card.addView(monoLine(bssidLine));

        boolean decide = current.status == KeepADBNetworkList.Status.UNKNOWN
                || current.status == KeepADBNetworkList.Status.TRUSTED_BY_NAME
                || current.legacyOnly;
        switch (current.status) {
            case BLOCKED:
                card.addView(text(reasonText(current.reason, current.ssid, row != null
                        && row.entry != null), 13, R.color.night_text));
                break;
            case TRUSTED_BY_NAME:
                card.addView(text(activity.getString(R.string.networks_current_trusted_name), 13,
                        R.color.night_text));
                break;
            case TRUSTED:
                card.addView(text(activity.getString(current.legacyOnly
                        ? R.string.networks_current_legacy : R.string.networks_current_trusted),
                        13, R.color.night_text));
                break;
            default:
                break;
        }

        if (decide) {
            // The question is the one of the notification dialog (#766), not a dialog of this list.
            NetworkDecisionView decision = new NetworkDecisionView(activity);
            decision.setDecideLaterVisible(false);
            decision.bind(new KeepADBNetworkDecision.Pending(current.bssid, current.ssid),
                    new NetworkDecisionView.Listener() {
                        @Override
                        public void onDecided(KeepADBNetworkDecision.Outcome outcome) {
                            onChanged.run();
                        }

                        @Override
                        public void onDecideLater() {
                            // Hidden in this host; nothing to do.
                        }
                    });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(12);
            card.addView(decision, params);
        } else if (assistantMode) {
            if (current.status == KeepADBNetworkList.Status.BLOCKED) {
                card.addView(stackedButton(activity.getString(R.string.onboarding_network_open_list),
                        false, v -> activity.startActivity(NetworkListActivity.intent(activity))));
            }
        } else if (row != null || (current.status == KeepADBNetworkList.Status.BLOCKED
                && current.ssid != null)) {
            card.addView(text(activity.getString(R.string.networks_tap_to_change), 12,
                    R.color.night_muted));
            card.setBackgroundResource(R.drawable.bg_card_clickable);
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                if (row != null) {
                    showAccessPointDialog(row);
                } else {
                    showNameDialog(current.ssid, null);
                }
            });
            card.setContentDescription(describe(name, current.ssid, bssidLine,
                    statusLabel(current.status), current.status == KeepADBNetworkList.Status.BLOCKED
                            ? reasonText(current.reason, current.ssid, row != null && row.entry != null)
                            : null));
        }
    }

    // --- Saved networks ----------------------------------------------------------------------

    private void addSaved(KeepADBNetworkList.Snapshot snapshot) {
        root.addView(sectionHeading(R.string.networks_section_saved, 24));
        if (snapshot.isEmpty()) {
            LinearLayout empty = panel(R.drawable.bg_panel);
            setTopMargin(empty, 8);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(R.drawable.ic_wifi);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            empty.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView title = condensed(activity.getString(R.string.networks_empty_title), 16,
                    R.color.night_text);
            title.setPadding(0, dp(8), 0, 0);
            empty.addView(title);
            empty.addView(text(activity.getString(R.string.networks_empty_text), 13,
                    R.color.night_muted));
            root.addView(empty);
            return;
        }
        for (KeepADBNetworkList.Group group : snapshot.groups) {
            addGroup(group);
        }
    }

    private void addGroup(KeepADBNetworkList.Group group) {
        root.addView(groupHeader(group));
        if (group.rows.isEmpty() && group.nameBlocked) {
            TextView note = text(activity.getString(R.string.networks_group_all_blocked), 12,
                    R.color.night_muted);
            note.setPadding(dp(4), 0, 0, dp(4));
            root.addView(note);
        }
        String key = group.ssid == null ? "" : group.ssid;
        boolean collapsible = group.rows.size() > COLLAPSE_ABOVE;
        boolean showAll = !collapsible || expandedGroups.contains(key);
        int visible = showAll ? group.rows.size() : COLLAPSED_ROWS;
        for (int i = 0; i < visible; i++) {
            root.addView(rowView(group.rows.get(i)));
        }
        if (collapsible) {
            TextView toggle = text(showAll
                    ? activity.getString(R.string.wifi_aps_show_less_button)
                    : activity.getString(R.string.wifi_aps_show_more_button,
                            group.rows.size() - COLLAPSED_ROWS), 13, R.color.text_yellow);
            toggle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            toggle.setGravity(Gravity.CENTER);
            toggle.setMinHeight(dp(48));
            toggle.setClickable(true);
            toggle.setFocusable(true);
            toggle.setBackgroundResource(android.R.drawable.list_selector_background);
            toggle.setOnClickListener(v -> {
                if (!expandedGroups.remove(key)) expandedGroups.add(key);
                render();
            });
            root.addView(toggle);
        }
    }

    private View groupHeader(KeepADBNetworkList.Group group) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        header.setLayoutParams(params);

        String title = group.ssid == null
                ? activity.getString(R.string.networks_group_unnamed) : group.ssid;
        TextView name = condensed(title, 15, R.color.night_text);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAccessibilityHeading(true);
        header.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (group.nameBlocked) {
            TextView badge = badge(Badge.DANGER, activity.getString(R.string.networks_badge_name_blocked));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMarginStart(dp(8));
            header.addView(badge, badgeParams);
        }
        if (group.ssid != null) {
            ImageView more = new ImageView(activity);
            more.setImageResource(R.drawable.ic_more_horiz);
            more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            more.setPadding(dp(12), dp(12), dp(12), dp(12));
            header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.setClickable(true);
            header.setFocusable(true);
            header.setBackgroundResource(android.R.drawable.list_selector_background);
            header.setContentDescription(describe(activity.getString(
                    R.string.networks_a11y_group_options, group.ssid), null, null,
                    group.nameBlocked ? activity.getString(R.string.networks_badge_name_blocked)
                            : null, null));
            header.setOnClickListener(v -> showNameDialog(group.ssid, group));
        }
        return header;
    }

    private View rowView(KeepADBNetworkList.Row row) {
        LinearLayout view = panel(R.drawable.bg_card_clickable);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setMinimumHeight(dp(48));
        setTopMargin(view, 8);

        String custom = row.entry == null || row.entry.customName == null ? null
                : KeepADBNetworkDisplay.customName(activity, row.entry.customName);
        int band = KeepADBAccessPointBand.bandOf(frequencies, row.bssid);
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(activity, row.bssid, band);
        String title = custom != null ? custom : bssidLine;
        view.addView(titleLine(title, custom == null, statusBadge(row.status), 15));
        if (custom != null) view.addView(monoLine(bssidLine));

        String reason = null;
        if (row.status == KeepADBNetworkList.Status.BLOCKED) {
            reason = reasonText(row.reason, row.ssid, row.entry != null);
            view.addView(text(reason, 12, R.color.night_muted));
        }
        if (row.current) {
            view.addView(text(activity.getString(R.string.networks_connected), 12, R.color.night_muted));
        }
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(describe(custom != null ? custom : bssidLine, row.ssid,
                custom != null ? bssidLine : null, statusLabel(row.status), reason));
        view.setOnClickListener(v -> showAccessPointDialog(row));
        return view;
    }

    // --- Dialogs -----------------------------------------------------------------------------

    /** What a tap on a saved access point offers: only what changes something in its state. */
    private void showAccessPointDialog(KeepADBNetworkList.Row row) {
        LinearLayout content = dialogContent();
        int band = KeepADBAccessPointBand.bandOf(frequencies, row.bssid);
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(activity, row.bssid, band);
        String custom = row.entry == null || row.entry.customName == null ? null
                : KeepADBNetworkDisplay.customName(activity, row.entry.customName);

        TextView title = condensed(custom != null ? custom : bssidLine, 18, R.color.title_yellow);
        title.setAccessibilityHeading(true);
        content.addView(title);
        if (row.ssid != null) {
            content.addView(text(row.ssid, 15, R.color.night_text));
        }
        if (custom != null) content.addView(monoLine(bssidLine));
        LinearLayout status = new LinearLayout(activity);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(0, dp(8), 0, 0);
        status.addView(statusBadge(row.status));
        content.addView(status);
        if (row.status == KeepADBNetworkList.Status.BLOCKED) {
            content.addView(text(reasonText(row.reason, row.ssid, row.entry != null), 13,
                    R.color.night_text));
        }

        String ssid = row.ssid;
        boolean nameUsable = KeepADBNetworkBlocklist.isUsableSsid(ssid);
        boolean nameBlocked = nameUsable && KeepADBNetworkBlocklist.isSsidBlocked(activity, ssid);
        boolean addressBlocked = KeepADBNetworkBlocklist.isBssidBlocked(activity, row.bssid);

        if (addressBlocked) {
            if (!nameBlocked) {
                addDialogButton(content, activity.getString(R.string.network_decision_trust), true,
                        () -> answer(KeepADBNetworkListActions.trustBlockedAccessPoint(
                                activity, row.bssid, ssid), ssid));
            }
            addDialogButton(content, activity.getString(R.string.networks_action_unblock), nameBlocked,
                    () -> answer(
                            KeepADBNetworkListActions.liftAccessPointBlock(activity, row.bssid), ssid));
        }
        if (nameBlocked) {
            addDialogButton(content, activity.getString(R.string.networks_action_unblock_name), false,
                    () -> confirmLiftNameBlock(ssid));
        }
        if (!addressBlocked && !nameBlocked) {
            addDialogButton(content, activity.getString(R.string.networks_action_block), false,
                    () -> answer(KeepADBNetworkListActions.blockAccessPoint(activity, row.bssid), ssid));
            if (row.entry != null) {
                addDialogButton(content, activity.getString(R.string.networks_action_untrust), false,
                        () -> answer(KeepADBNetworkListActions.stopTrusting(activity, row.bssid), ssid));
            }
            if (nameUsable) {
                addDialogButton(content, activity.getString(R.string.network_decision_block_name, ssid),
                        false, () -> answer(KeepADBNetworkListActions.blockName(activity, ssid), ssid));
            }
        }
        if (row.entry != null) {
            addDialogButton(content, activity.getString(R.string.networks_action_rename), false,
                    () -> {
                        AlertDialog rename = KeepADBNetworkActions.editAccessPointName(activity,
                                row.entry, onChanged);
                        showDialog(rename);
                    });
        }
        addDialogButton(content, activity.getString(android.R.string.cancel), null, null);
        showContent(content);
    }

    /** What a tap on the Wi-Fi name of a group offers. */
    private void showNameDialog(String ssid, KeepADBNetworkList.Group group) {
        LinearLayout content = dialogContent();
        TextView title = condensed(activity.getString(R.string.networks_group_dialog_head, ssid), 18,
                R.color.title_yellow);
        title.setAccessibilityHeading(true);
        content.addView(title);
        if (group != null && !group.rows.isEmpty()) {
            content.addView(text(activity.getString(R.string.networks_group_saved, group.rows.size()),
                    13, R.color.night_muted));
        }
        boolean blocked = KeepADBNetworkBlocklist.isSsidBlocked(activity, ssid);
        if (!blocked && KeepADBTrustedNetwork.isTrustByNameEnabled(activity)) {
            TextView note = text(activity.getString(R.string.networks_group_comfort), 13,
                    R.color.text_yellow);
            note.setPadding(0, dp(8), 0, 0);
            content.addView(note);
        }
        if (blocked) {
            addDialogButton(content, activity.getString(R.string.networks_action_unblock_name), true,
                    () -> confirmLiftNameBlock(ssid));
        } else {
            addDialogButton(content, activity.getString(R.string.network_decision_block_name, ssid),
                    false, () -> answer(KeepADBNetworkListActions.blockName(activity, ssid), ssid));
        }
        addDialogButton(content, activity.getString(android.R.string.cancel), null, null);
        showContent(content);
    }

    private void confirmLiftNameBlock(String ssid) {
        AlertDialog confirm = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.networks_unblock_name_title, ssid))
                .setMessage(R.string.networks_unblock_name_text)
                .setPositiveButton(R.string.networks_unblock_name_confirm, (d, which) ->
                        answer(KeepADBNetworkListActions.liftNameBlock(activity, ssid), ssid))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showDialog(confirm);
    }

    /** Tells the user what an answer did, then redraws. */
    private void answer(KeepADBNetworkListActions.Outcome outcome, String ssid) {
        String quoted = ssid == null ? "" : KeepADBNetworkDisplay.quoted(activity, ssid);
        switch (outcome) {
            case TRUSTED:
                toast(activity.getString(R.string.network_ap_allowed_toast,
                        ssid == null ? "" : quoted), Toast.LENGTH_SHORT);
                break;
            case TRUST_REFUSED_NAME_BLOCKED:
                toast(activity.getString(R.string.network_decision_trust_refused_toast),
                        Toast.LENGTH_LONG);
                break;
            case STOPPED_TRUSTING:
            case ACCESS_POINT_BLOCK_LIFTED:
                toast(activity.getString(R.string.networks_untrusted_toast), Toast.LENGTH_SHORT);
                break;
            case BLOCKED_ACCESS_POINT:
                toast(activity.getString(R.string.network_decision_blocked_toast), Toast.LENGTH_SHORT);
                break;
            case BLOCKED_NAME:
                toast(activity.getString(R.string.networks_name_blocked_toast, quoted),
                        Toast.LENGTH_SHORT);
                break;
            case NAME_BLOCK_LIFTED:
                toast(activity.getString(R.string.networks_name_unblocked_toast, quoted),
                        Toast.LENGTH_SHORT);
                break;
            case FAILED:
            default:
                toast(activity.getString(R.string.network_decision_failed_toast), Toast.LENGTH_LONG);
                break;
        }
        onChanged.run();
    }

    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(8));
        return content;
    }

    /**
     * Adds a full-width button; {@code primary} null makes it a plain text button (cancel). Every
     * button closes the dialog first, then runs its action.
     */
    private void addDialogButton(LinearLayout content, String label, Boolean primary,
                                 Runnable action) {
        View.OnClickListener listener = v -> {
            dismissDialog();
            if (action != null) action.run();
        };
        Button button = primary == null
                ? plainButton(label, listener) : stackedButton(label, primary, listener);
        content.addView(button);
    }

    private void showContent(LinearLayout content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        showDialog(new AlertDialog.Builder(activity).setView(scroll).create());
    }

    private void showDialog(AlertDialog next) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = next;
        next.setOnDismissListener(d -> {
            if (dialog == d) dialog = null;
        });
        next.show();
    }

    // --- Location ----------------------------------------------------------------------------

    private void requestLocation() {
        OnboardingPermissions.requestLocation(activity, REQUEST_LOCATION);
    }

    // --- Texts and badges --------------------------------------------------------------------

    private String reasonText(KeepADBNetworkList.Reason reason, String ssid, boolean trustedStored) {
        if (reason == KeepADBNetworkList.Reason.NAME && ssid != null) {
            return activity.getString(trustedStored ? R.string.networks_reason_name_trusted
                    : R.string.networks_reason_name, ssid);
        }
        return activity.getString(R.string.networks_reason_access_point);
    }

    private TextView statusBadge(KeepADBNetworkList.Status status) {
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

    private String statusLabel(KeepADBNetworkList.Status status) {
        return activity.getString(statusLabelRes(status));
    }

    /** Title, Wi-Fi name, address, status, reason -- in this order, as one spoken line. */
    private String describe(String title, String ssid, String bssid, String status, String reason) {
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

    private TextView badge(Badge kind, String label) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        view.setPadding(dp(6), dp(2), dp(6), dp(2));
        // The assistant must not cut a word at a large font size (#761, #767).
        if (!assistantMode) view.setSingleLine(true);
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
        view.setBackgroundResource(background);
        view.setTextColor(activity.getColor(color));
        // The word is part of the row's spoken line; a badge is no focus target of its own.
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    // --- View helpers ------------------------------------------------------------------------

    /** Title and badge: side by side, or the badge below the title on a narrow or large display. */
    private View titleLine(String title, boolean mono, TextView badge, int sp) {
        TextView titleView = mono ? monoLine(title) : condensed(title, sp, R.color.night_text);
        if (mono) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            titleView.setTextColor(activity.getColor(R.color.night_text));
        }
        if (!assistantMode) {
            titleView.setMaxLines(2);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
        }
        Configuration config = activity.getResources().getConfiguration();
        boolean stacked = config.fontScale >= 1.3f || config.screenWidthDp < 360;
        LinearLayout line = new LinearLayout(activity);
        if (stacked) {
            line.setOrientation(LinearLayout.VERTICAL);
            line.addView(titleView);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            line.addView(badge, params);
        } else {
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(titleView, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(dp(8));
            line.addView(badge, params);
        }
        return line;
    }

    private TextView monoLine(String value) {
        TextView view = text(value, 13, R.color.night_muted);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private TextView sectionHeading(int textRes, int topMarginDp) {
        TextView heading = condensed(activity.getString(textRes), 12, R.color.night_muted);
        heading.setAllCaps(true);
        heading.setAccessibilityHeading(true);
        setTopMargin(heading, topMarginDp);
        return heading;
    }

    private LinearLayout panel(int background) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(background);
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private void setTopMargin(View view, int topDp) {
        LinearLayout.LayoutParams params = view.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) view.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        view.setLayoutParams(params);
    }

    private Button stackedButton(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setTextColor(activity.getColor(primary ? R.color.title_yellow : R.color.text_yellow));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private Button plainButton(String label, View.OnClickListener listener) {
        Button button = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        button.setMinHeight(dp(48));
        button.setTextColor(activity.getColor(R.color.night_muted));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int sp, int colorRes) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(activity.getColor(colorRes));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private TextView condensed(String value, int sp, int colorRes) {
        TextView view = text(value, sp, colorRes);
        view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return view;
    }

    private void toast(String message, int duration) {
        Toast.makeText(activity, message, duration).show();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
