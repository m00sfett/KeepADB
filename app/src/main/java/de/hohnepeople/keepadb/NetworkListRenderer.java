package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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
import android.widget.TextView;

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
 * <p><strong>Privacy (#904).</strong> While the app privacy switch is on and the screen has no
 * "Show", nothing of the list is built at all: the page shows a placeholder and no view and no
 * content description holds a Wi-Fi name or an address. "Show" lifts that for this screen until it
 * is left; it does not change the switch. The glossary and the protection level row carry no network
 * data and stay. The assistant's current-network card (see {@link #renderCurrentForAssistant})
 * masks its name and address the same way and offers the same "Show".
 */
final class NetworkListRenderer {

    static final int REQUEST_LOCATION = 762;

    /** A group with more access points than this folds to {@link #COLLAPSED_ROWS} rows. */
    static final int COLLAPSE_ABOVE = 5;
    static final int COLLAPSED_ROWS = 3;

    private final Activity activity;
    private final LinearLayout root;
    private final Runnable onChanged;
    private final Set<String> expandedGroups = new HashSet<>();
    /** #767: the current-network card drawn for the setup assistant, see {@link #renderCurrentForAssistant}. */
    private boolean assistantMode;
    /** #904: the reveal scope of the part this renderer draws (the list screen or the assistant page). */
    private String scope = KeepADBPrivacy.SCOPE_NETWORK_LIST;
    private boolean glossaryOpen;
    private final NetworkListDialogs dialogs;
    private final NetworkListViews views;

    private Map<String, Integer> frequencies;
    /** #799: the access point to highlight once, and the view drawn for it by the last render. */
    private String highlightBssid;
    private View highlightView;
    private View currentCard;

    NetworkListRenderer(Activity activity, LinearLayout root, Runnable onChanged) {
        this.activity = activity;
        this.root = root;
        this.onChanged = onChanged;
        this.views = new NetworkListViews(activity, this);
        this.dialogs = new NetworkListDialogs(activity, this, onChanged);
    }

    Context shown() {
        return KeepADBPrivacy.scoped(activity, scope);
    }

    /** Dismisses the open dialog, if any; derived from live data and never restored. */
    void dismissDialog() {
        dialogs.dismiss();
    }

    /**
     * #799: marks the row of this access point on the next {@link #render()} (selected state) and
     * lets the caller scroll to {@link #highlightedView()}. A group folded by "show more" is
     * unfolded for it. Without a matching row the card of the current network is used when it is
     * that access point; otherwise nothing is marked.
     */
    void highlightOnNextRender(String bssid) {
        highlightBssid = bssid;
    }

    /** The view marked by the last render, or null (nothing requested, nothing matched). */
    View highlightedView() {
        return highlightView;
    }

    void render() {
        dismissDialog();
        highlightView = null;
        root.removeAllViews();
        root.setVisibility(View.VISIBLE);
        frequencies = KeepADBAccessPointBand.read(activity);

        // #949: the networks come first, the settings and the glossary after them, so the place
        // to act on the current or a recently asked network is at the top of the page.
        if (KeepADBNetworkDisplay.hidden(shown())) {
            highlightBssid = null;
            addHiddenPlaceholder();
        } else {
            KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(activity);
            boolean wifiConnected = identity.isKnown() || KeepADBService.isWifiConnected(activity);
            KeepADBNetworkList.Snapshot snapshot =
                    KeepADBNetworkList.build(activity, identity, wifiConnected);
            addCurrent(snapshot.current, identity);
            addRecent(identity.isKnown() ? identity.bssid : null);
            addSaved(snapshot);
            markHighlight(snapshot);
        }
        root.addView(views.sectionHeading(R.string.protection_title, 24));
        addProtection();
        addForceNote();
        addGlossary();
    }

    private boolean isHighlight(String bssid) {
        return highlightBssid != null && bssid != null && highlightBssid.equalsIgnoreCase(bssid);
    }

    /** Falls back to the current-network card when no saved row carries the requested address. */
    private void markHighlight(KeepADBNetworkList.Snapshot snapshot) {
        if (highlightBssid == null) return;
        if (highlightView == null && snapshot.current != null
                && isHighlight(snapshot.current.bssid)) {
            highlightView = currentCard;
            if (highlightView != null) highlightView.setSelected(true);
        }
        highlightBssid = null;
    }

    /**
     * #767: the current-network card alone, for the setup assistant's "Trusted Wi-Fi" step. It is
     * the same card and the same embedded decision (#766) as in the list, with three differences:
     * no section heading (the step has its title), no tap-to-change on a decided network (the
     * assistant answers a question, it does not edit the list; a blocked network offers the way to
     * the list instead), and a "check again" button where the state depends on the user acting
     * elsewhere (no Wi-Fi, not readable). #904: the name and the address are masked while the app
     * privacy switch is on, with a deliberate "Show" for this screen; the former exception of
     * #758 is gone.
     */
    void renderCurrentForAssistant() {
        assistantMode = true;
        scope = KeepADBPrivacy.SCOPE_ASSISTANT;
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
        TextView toggle = views.text(activity.getString(R.string.networks_glossary_toggle)
                + (glossaryOpen ? "  ▴" : "  ▾"), 13, R.color.text_yellow);
        toggle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        toggle.setMinHeight(views.dp(48));
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
            root.addView(views.text(activity.getString(R.string.networks_glossary_ssid), 13,
                    R.color.night_text));
            TextView bssid = views.text(activity.getString(R.string.networks_glossary_bssid), 13,
                    R.color.night_text);
            bssid.setPadding(0, views.dp(8), 0, 0);
            root.addView(bssid);
        }
    }

    /**
     * #959: the protection level this list works under, as one row. The level is chosen on the
     * Protection page (the "Also trust by Wi-Fi name" switch that used to sit here is gone), so a
     * tap leads there, to the level choice.
     */
    private void addProtection() {
        TextView row = views.text(activity.getString(R.string.networks_level,
                KeepADBForceNotice.levelLabel(activity)), 15, R.color.night_text);
        row.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        row.setBackgroundResource(R.drawable.bg_card_clickable);
        row.setMinHeight(views.dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(views.dp(16), views.dp(8), views.dp(12), views.dp(8));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> activity.startActivity(ProtectionActivity.levelIntent(activity)));
        views.setTopMargin(row, 8);
        root.addView(row);
    }

    private void addForceNote() {
        String line = KeepADBForceNotice.activeLine(activity);
        if (line == null) return;
        LinearLayout card = views.panel(R.drawable.bg_panel_advice);
        views.setTopMargin(card, 8);
        TextView title = views.condensed(line, 15, R.color.title_yellow);
        title.setAccessibilityHeading(true);
        card.addView(title);
        card.addView(views.text(activity.getString(R.string.networks_force_note), 13, R.color.night_text));
        root.addView(card);
    }

    private void addHiddenPlaceholder() {
        LinearLayout card = views.panel(R.drawable.bg_panel);
        views.setTopMargin(card, 16);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_privacy_eye_off);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon, new LinearLayout.LayoutParams(views.dp(32), views.dp(32)));
        TextView title = views.condensed(activity.getString(R.string.networks_hidden_title), 16,
                R.color.night_text);
        title.setAccessibilityHeading(true);
        title.setPadding(0, views.dp(8), 0, 0);
        card.addView(title);
        card.addView(views.text(activity.getString(R.string.networks_hidden_text), 13, R.color.night_muted));
        Button show = views.stackedButton(activity.getString(R.string.networks_hidden_show), false,
                v -> {
                    // #904: "Show" reveals this screen until it is left; the switch stays on.
                    KeepADBPrivacy.reveal(activity, scope);
                    onChanged.run();
                });
        card.addView(show);
        root.addView(card);
    }

    // --- Current network ---------------------------------------------------------------------

    private void addCurrent(KeepADBNetworkList.Current current, KeepADBNetworkIdentity identity) {
        if (!assistantMode) root.addView(views.sectionHeading(R.string.networks_section_current, 16));
        LinearLayout card = views.panel(R.drawable.bg_panel);
        views.setTopMargin(card, 8);

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
        currentCard = card;
    }

    private void addNoWifi(LinearLayout card) {
        TextView title = views.condensed(activity.getString(R.string.network_status_no_wifi), 16,
                R.color.night_text);
        card.addView(title);
        card.addView(views.text(activity.getString(R.string.network_cause_no_wifi), 13, R.color.night_muted));
        card.addView(views.stackedButton(activity.getString(R.string.network_action_wifi_settings), false,
                v -> KeepADBNetworkActions.openWifiSettings(activity)));
        if (assistantMode) addRecheck(card);
    }

    /** #767: the state changes outside this screen, so the assistant offers to read it again. */
    private void addRecheck(LinearLayout card) {
        card.addView(views.stackedButton(activity.getString(R.string.onboarding_recheck), false,
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
        card.addView(views.titleLine(activity.getString(R.string.network_status_unreadable), false,
                views.badge(NetworkListViews.Badge.WARN, activity.getString(R.string.network_status_unreadable)), 16));
        card.addView(views.text(activity.getString(KeepADBNetworkCardText.cause(state.cause)), 13,
                R.color.night_text));
        switch (state.action) {
            case GRANT_LOCATION:
                card.addView(views.stackedButton(activity.getString(
                        KeepADBNetworkCardText.action(state.action)), true, v -> requestLocation()));
                break;
            case OPEN_LOCATION_SETTINGS:
                card.addView(views.stackedButton(activity.getString(
                        KeepADBNetworkCardText.action(state.action)), true,
                        v -> KeepADBNetworkActions.openLocationSettings(activity)));
                break;
            case SET_UP_BACKGROUND:
                if (assistantMode) {
                    // The identity is masked although everything is allowed: the lasting fix is
                    // the background grant, which only the app's system page can give.
                    card.addView(views.stackedButton(activity.getString(
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
        // #904: masked while the app privacy switch is on and the screen has no "Show".
        String name = KeepADBNetworkDisplay.ssid(shown(), current.ssid, null);
        KeepADBNetworkList.Row row = current.row;
        int band = KeepADBAccessPointBand.bandOf(frequencies, current.bssid);
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(shown(), current.bssid, band);

        card.addView(views.titleLine(name, false, views.statusBadge(current.status), 18));
        card.addView(views.monoLine(bssidLine));

        boolean decide = current.status == KeepADBNetworkList.Status.UNKNOWN
                || current.status == KeepADBNetworkList.Status.TRUSTED_BY_NAME
                || current.legacyOnly;
        if (KeepADBNetworkDisplay.hidden(shown()) && !decide) {
            // A decision view brings its own "Show"; a card without one needs this.
            card.addView(views.stackedButton(activity.getString(R.string.networks_hidden_show), false,
                    v -> {
                        KeepADBPrivacy.reveal(activity, scope);
                        onChanged.run();
                    }));
        }
        switch (current.status) {
            case BLOCKED:
                card.addView(views.text(views.reasonText(current.reason, current.ssid, row != null
                        && row.entry != null), 13, R.color.night_text));
                break;
            case TRUSTED_BY_NAME:
                card.addView(views.text(activity.getString(R.string.networks_current_trusted_name), 13,
                        R.color.night_text));
                break;
            case TRUSTED:
                card.addView(views.text(activity.getString(current.legacyOnly
                        ? R.string.networks_current_legacy : R.string.networks_current_trusted),
                        13, R.color.night_text));
                break;
            default:
                break;
        }

        if (decide) {
            // The question is the one of the notification dialog (#766), not a dialog of this list.
            NetworkDecisionView decision = new NetworkDecisionView(activity);
            decision.setPrivacyScope(scope);
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

                        @Override
                        public void onRevealed() {
                            // The card around the view shows the name and the address as well.
                            onChanged.run();
                        }
                    });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = views.dp(12);
            card.addView(decision, params);
        } else if (assistantMode) {
            if (current.status == KeepADBNetworkList.Status.BLOCKED) {
                card.addView(views.stackedButton(activity.getString(R.string.onboarding_network_open_list),
                        false, v -> activity.startActivity(NetworkListActivity.intent(activity))));
            }
        } else if (row != null || (current.status == KeepADBNetworkList.Status.BLOCKED
                && current.ssid != null)) {
            card.addView(views.text(activity.getString(R.string.networks_tap_to_change), 12,
                    R.color.night_muted));
            // The clickable background has no padding of its own (the panel's 16 dp are lost when
            // it replaces the panel background), so give the content the same inset back (#791).
            card.setBackgroundResource(R.drawable.bg_card_clickable);
            card.setPadding(views.dp(16), views.dp(16), views.dp(16), views.dp(16));
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                if (row != null) {
                    dialogs.showAccessPointDialog(row);
                } else {
                    dialogs.showNameDialog(current.ssid, null);
                }
            });
            card.setContentDescription(views.describe(name, current.ssid, bssidLine,
                    views.statusLabel(current.status), current.status == KeepADBNetworkList.Status.BLOCKED
                            ? views.reasonText(current.reason, current.ssid, row != null && row.entry != null)
                            : null));
        }
    }

    // --- Recently asked (#949) ---------------------------------------------------------------

    /**
     * #949: the access points KeepADB asked about and nobody answered, for instance after the
     * prompt was swiped away on a public Wi-Fi. Each one opens the same decision as the
     * notification (#766) or can be removed from the record. Drawn only while the list itself is
     * shown, so the privacy mode (#904) hides it with everything else; nothing of it reaches the
     * diagnostics but the bare action.
     */
    private void addRecent(String currentBssid) {
        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBNetworkDecision.recentUndecided(activity, currentBssid);
        if (entries.isEmpty()) return;
        root.addView(views.sectionHeading(R.string.networks_section_recent, 24));
        TextView hint = views.text(activity.getString(R.string.networks_recent_hint), 12,
                R.color.night_muted);
        hint.setPadding(0, views.dp(4), 0, 0);
        root.addView(hint);
        for (KeepADBBlockedNetworkHistory.Entry entry : entries) {
            View row = recentRow(entry);
            // #799: "Block or decide…" in Settings focuses the access point it showed; after a roam
            // that is no longer the current card but may be one of these rows.
            if (highlightView == null && isHighlight(entry.bssid)) {
                highlightView = row;
                row.setSelected(true);
            }
            root.addView(row);
        }
    }

    private View recentRow(KeepADBBlockedNetworkHistory.Entry entry) {
        LinearLayout card = views.panel(R.drawable.bg_panel);
        card.setPadding(views.dp(12), views.dp(12), views.dp(12), views.dp(12));
        views.setTopMargin(card, 8);

        boolean named = entry.ssid != null && !entry.ssid.isEmpty();
        int band = KeepADBAccessPointBand.bandOf(frequencies, entry.bssid);
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(shown(), entry.bssid, band);
        String title = named ? KeepADBNetworkDisplay.ssid(shown(), entry.ssid, null) : bssidLine;
        card.addView(views.titleLine(title, !named, views.statusBadge(KeepADBNetworkList.Status.UNKNOWN), 15));
        if (named) card.addView(views.monoLine(bssidLine));
        // An absolute date and time: a relative span ("Vor 9 Stunden") starts upper case in some
        // languages and reads wrong in the middle of a sentence (seen on the s20).
        String asked = activity.getString(R.string.networks_recent_asked,
                android.text.format.DateUtils.formatDateTime(activity, entry.lastSeenAt,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE
                                | android.text.format.DateUtils.FORMAT_SHOW_TIME
                                | android.text.format.DateUtils.FORMAT_ABBREV_MONTH));
        card.addView(views.text(asked, 12, R.color.night_muted));

        Button decide = views.stackedButton(activity.getString(R.string.networks_recent_decide), true,
                v -> activity.startActivity(
                        KeepADBNetworkTrustPrompt.decisionIntent(activity, entry.bssid)));
        // Several access points can share a name: the spoken target carries the address too.
        String target = named ? title + ", " + bssidLine : bssidLine;
        decide.setContentDescription(activity.getString(R.string.networks_recent_decide_a11y, target));
        Button remove = views.plainButton(activity.getString(R.string.networks_recent_remove), v -> {
            KeepADBBlockedNetworkHistory.remove(activity, entry.bssid);
            KeepADBDiagnostics.event(activity, "user_action", "network_list", "removed",
                    "recent_prompt");
            onChanged.run();
        });
        remove.setContentDescription(activity.getString(R.string.networks_recent_remove_a11y, target));
        // Side by side, so a few entries do not fill the screen; stacked where it gets narrow.
        Configuration config = activity.getResources().getConfiguration();
        boolean stacked = config.fontScale >= 1.3f || config.screenWidthDp < 360;
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        if (!stacked) {
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            half.topMargin = views.dp(8);
            decide.setLayoutParams(half);
            LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            other.topMargin = views.dp(8);
            other.setMarginStart(views.dp(8));
            remove.setLayoutParams(other);
        }
        actions.addView(decide);
        actions.addView(remove);
        card.addView(actions);
        return card;
    }

    // --- Saved networks ----------------------------------------------------------------------

    private void addSaved(KeepADBNetworkList.Snapshot snapshot) {
        root.addView(views.sectionHeading(R.string.networks_section_saved, 24));
        if (snapshot.isEmpty()) {
            LinearLayout empty = views.panel(R.drawable.bg_panel);
            views.setTopMargin(empty, 8);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(R.drawable.ic_wifi);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            empty.addView(icon, new LinearLayout.LayoutParams(views.dp(32), views.dp(32)));
            TextView title = views.condensed(activity.getString(R.string.networks_empty_title), 16,
                    R.color.night_text);
            title.setPadding(0, views.dp(8), 0, 0);
            empty.addView(title);
            empty.addView(views.text(activity.getString(R.string.networks_empty_text), 13,
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
            TextView note = views.text(activity.getString(R.string.networks_group_all_blocked), 12,
                    R.color.night_muted);
            note.setPadding(views.dp(4), 0, 0, views.dp(4));
            root.addView(note);
        }
        String key = group.ssid == null ? "" : group.ssid;
        for (KeepADBNetworkList.Row row : group.rows) {
            if (highlightView == null && isHighlight(row.bssid)) expandedGroups.add(key);
        }
        boolean collapsible = group.rows.size() > COLLAPSE_ABOVE;
        boolean showAll = !collapsible || expandedGroups.contains(key);
        int visible = showAll ? group.rows.size() : COLLAPSED_ROWS;
        for (int i = 0; i < visible; i++) {
            View rowView = rowView(group.rows.get(i));
            if (highlightView == null && isHighlight(group.rows.get(i).bssid)) {
                highlightView = rowView;
                rowView.setSelected(true);
            }
            root.addView(rowView);
        }
        if (collapsible) {
            TextView toggle = views.text(showAll
                    ? activity.getString(R.string.wifi_aps_show_less_button)
                    : activity.getString(R.string.wifi_aps_show_more_button,
                            group.rows.size() - COLLAPSED_ROWS), 13, R.color.text_yellow);
            toggle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            toggle.setGravity(Gravity.CENTER);
            toggle.setMinHeight(views.dp(48));
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
        header.setMinimumHeight(views.dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = views.dp(8);
        header.setLayoutParams(params);

        String title = group.ssid == null
                ? activity.getString(R.string.networks_group_unnamed) : group.ssid;
        TextView name = views.condensed(title, 15, R.color.night_text);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAccessibilityHeading(true);
        header.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (group.nameBlocked) {
            TextView badge = views.badge(NetworkListViews.Badge.DANGER, activity.getString(R.string.networks_badge_name_blocked));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMarginStart(views.dp(8));
            header.addView(badge, badgeParams);
        }
        if (group.ssid != null) {
            ImageView more = new ImageView(activity);
            more.setImageResource(R.drawable.ic_more_horiz);
            more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            more.setPadding(views.dp(12), views.dp(12), views.dp(12), views.dp(12));
            header.addView(more, new LinearLayout.LayoutParams(views.dp(48), views.dp(48)));
            header.setClickable(true);
            header.setFocusable(true);
            header.setBackgroundResource(android.R.drawable.list_selector_background);
            header.setContentDescription(views.describe(activity.getString(
                    R.string.networks_a11y_group_options, group.ssid), null, null,
                    group.nameBlocked ? activity.getString(R.string.networks_badge_name_blocked)
                            : null, null));
            header.setOnClickListener(v -> dialogs.showNameDialog(group.ssid, group));
        }
        return header;
    }

    private View rowView(KeepADBNetworkList.Row row) {
        LinearLayout view = views.panel(R.drawable.bg_card_clickable);
        view.setPadding(views.dp(12), views.dp(12), views.dp(12), views.dp(12));
        view.setMinimumHeight(views.dp(48));
        views.setTopMargin(view, 8);

        String custom = row.customName == null ? null
                : KeepADBNetworkDisplay.customName(shown(), row.customName);
        int band = KeepADBAccessPointBand.bandOf(frequencies, row.bssid);
        String bssidLine = KeepADBNetworkDisplay.bssidWithBand(shown(), row.bssid, band);
        String title = custom != null ? custom : bssidLine;
        view.addView(views.titleLine(title, custom == null, views.statusBadge(row.status), 15));
        if (custom != null) view.addView(views.monoLine(bssidLine));

        String reason = null;
        if (row.status == KeepADBNetworkList.Status.BLOCKED) {
            reason = views.reasonText(row.reason, row.ssid, row.entry != null);
            view.addView(views.text(reason, 12, R.color.night_muted));
        }
        if (row.current) {
            view.addView(views.text(activity.getString(R.string.networks_connected), 12, R.color.night_muted));
        }
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(views.describe(custom != null ? custom : bssidLine, row.ssid,
                custom != null ? bssidLine : null, views.statusLabel(row.status), reason));
        view.setOnClickListener(v -> dialogs.showAccessPointDialog(row));
        return view;
    }

    /** The address of {@code bssid} with its band, as the list shows it (#1051: also for the dialog). */
    String bssidLine(String bssid) {
        int band = KeepADBAccessPointBand.bandOf(frequencies, bssid);
        return KeepADBNetworkDisplay.bssidWithBand(shown(), bssid, band);
    }

    /** #767: whether this renderer draws the assistant's current-network card. */
    boolean isAssistantMode() {
        return assistantMode;
    }

    NetworkListViews views() {
        return views;
    }

    // --- Location ----------------------------------------------------------------------------

    private void requestLocation() {
        OnboardingPermissions.requestLocation(activity, REQUEST_LOCATION);
    }

}
