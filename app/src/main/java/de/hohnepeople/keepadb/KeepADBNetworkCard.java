package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Owns the Network card of {@link SettingsActivity} (#654/#655): its views, the access point
 * the card showed last (what its action button acts on), the Wi-Fi status callback and the
 * dialogs of the card -- the location-permission rationale for allowlist mode, the background-
 * location rationale and the mesh question after allowing an access point. (The trust confirmation
 * of the "new Wi-Fi" prompt lived here until #766 moved it to {@link NetworkDecisionActivity}.)
 *
 * <p>Extracted by #697 as a pure refactor: no behavior, preference key, Bundle key, request code
 * or layout id changed. What the card shows still comes exclusively from {@link
 * KeepADBNetworkCardState} and {@link KeepADBNetworkCardText}; whether anything is trusted still
 * decides exclusively {@link KeepADBTrustedNetwork} together with the gates of {@link
 * KeepADBReceiver}. This class keeps no second access point list and no trust cache: the only
 * remembered access point is the one the status button was rendered for.
 *
 * <p>{@link SettingsActivity} stays the screen composer and Android lifecycle owner (including the
 * one-time consumption of the notification intent) and calls explicit hooks: construction binds
 * the views in {@code onCreate}, then {@link #restore} (onCreate), {@link #start} (onStart),
 * {@link #refresh} (from its own {@code refresh()}), {@link #stop} (onStop), {@link #saveState}
 * (onSaveInstanceState), {@link #destroy} (onDestroy) and {@link #onRequestPermissionsResult}
 * (the two request codes below belong to this card). A fresh instance is created on every
 * {@code onCreate}, so no view or dialog reference here survives a real activity recreation.
 *
 * <p>Every switch and choice uses OnClick, not a checked-change listener: {@link #refresh()}
 * re-renders them from the persisted state, and a checked-change listener would fire on that
 * programmatic write too (and so could change a stored setting just by opening Settings).
 */
final class KeepADBNetworkCard {
    /**
     * #672: "was showing" marker of the background-location rationale dialog at the time of a
     * {@code recreate()}; a restored dialog is only re-shown and never grants anything.
     */
    static final String STATE_BACKGROUND_LOCATION_SHOWING = "settings_background_location_showing";
    /**
     * #682: flag for the trusted-network location permission rationale dialog. A restored dialog
     * is only re-shown; the permission request still runs solely from the user's own tap.
     */
    static final String STATE_ALLOWLIST_PERMISSION_SHOWING = "settings_allowlist_permission_showing";

    /** #507/#654: permission request code of the Wi-Fi observation grant (the card's action). */
    static final int WIFI_APS_LOCATION_PERMISSION_REQUEST = 3002;
    /** Permission request code for enabling trusted-network mode. */
    static final int TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST = 3001;
    private static final String LOCATION_PERMISSION_REQUESTED = "location_permission_requested";

    private final Activity activity;
    private final Runnable onChange;

    private final TextView networkSubtitle;
    private final TextView networkStatusLabel;
    private final TextView networkConnectionLine;
    private final TextView networkStatusCause;
    private final Button networkStatusAction;
    private final TextView networkPrivacyHint;
    private final RadioGroup networkModeGroup;
    private final RadioButton networkModeAllWifi;
    private final RadioButton networkModeAllowlist;
    private final TextView backgroundLocationStatus;
    private final TextView networkDetectionNow;
    private final Button backgroundLocationButton;
    private final TextView networkNetworksCount;
    private final TextView networkAllowedCount;
    private final TextView networkPreventedCount;
    private final TextView networkListsInactiveHint;
    private final Switch wifiApsFeatureToggle;
    private final View wifiApsContent;
    private final View networkSsidHeader;
    private final View networkSsidBody;
    private final TextView networkSsidArrow;
    private final TextView networkSsidState;
    private final TextView networkSsidEffect;
    private final Switch trustedSsidToggle;
    private final LinearLayout wifiSsidsCurrentRow;
    private final LinearLayout wifiSsidsList;
    private final TextView wifiSsidsEmpty;

    /**
     * What {@link #networkStatusAction} does right now and which access point it is bound to,
     * captured when the card was rendered: the button acts on the access point the user saw,
     * never on whatever the device happens to be connected to at click time.
     */
    private KeepADBNetworkCardState.Action networkStatusActionKind =
            KeepADBNetworkCardState.Action.NONE;
    private String networkActionBssid;
    private String networkActionLabel;

    /** #682: the trusted-network location permission rationale dialog, if showing. */
    private AlertDialog activeAllowlistPermissionDialog;
    /** #644: the step-2 rationale dialog for the optional background location grant, if showing. */
    private AlertDialog activeBackgroundLocationDialog;
    /** #686: the mesh question after allowing an access point; not restored, see {@link #destroy}. */
    private AlertDialog activeMeshDialog;
    /** #731: the "delete the history?" question after turning the observation off; not restored. */
    private AlertDialog activeObservationOffDialog;

    /** #661: refreshes the visible Network card while a Wi-Fi network changes. */
    private ConnectivityManager.NetworkCallback wifiStatusCallback;

    /**
     * Binds the card's views and click handlers; call from {@code SettingsActivity#onCreate}.
     *
     * @param onChange invoked after every action and every Wi-Fi change that used to end in the
     *         activity's own {@code refresh()}, so the whole screen is re-rendered exactly as before.
     */
    KeepADBNetworkCard(Activity activity, Runnable onChange) {
        this.activity = activity;
        this.onChange = onChange;

        networkSubtitle = activity.findViewById(R.id.settings_network_beta_subtitle);
        networkStatusLabel = activity.findViewById(R.id.network_status_label);
        networkConnectionLine = activity.findViewById(R.id.network_connection_line);
        networkStatusCause = activity.findViewById(R.id.network_status_cause);
        networkStatusAction = activity.findViewById(R.id.network_status_action);
        networkPrivacyHint = activity.findViewById(R.id.network_privacy_hint);
        networkModeGroup = activity.findViewById(R.id.network_mode_group);
        networkModeAllWifi = activity.findViewById(R.id.network_mode_all_wifi);
        networkModeAllowlist = activity.findViewById(R.id.network_mode_allowlist);
        backgroundLocationStatus = activity.findViewById(R.id.settings_background_location_status);
        networkDetectionNow = activity.findViewById(R.id.network_detection_now);
        backgroundLocationButton = activity.findViewById(R.id.settings_background_location_button);
        networkNetworksCount = activity.findViewById(R.id.network_networks_count);
        networkAllowedCount = activity.findViewById(R.id.network_allowed_count);
        networkPreventedCount = activity.findViewById(R.id.network_prevented_count);
        networkListsInactiveHint = activity.findViewById(R.id.network_lists_inactive_hint);
        wifiApsFeatureToggle = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        wifiApsContent = activity.findViewById(R.id.settings_wifi_aps_content);
        networkSsidHeader = activity.findViewById(R.id.network_ssid_header);
        networkSsidBody = activity.findViewById(R.id.network_ssid_body);
        networkSsidArrow = activity.findViewById(R.id.network_ssid_arrow);
        networkSsidState = activity.findViewById(R.id.network_ssid_state);
        networkSsidEffect = activity.findViewById(R.id.network_ssid_effect);
        trustedSsidToggle = activity.findViewById(R.id.settings_trusted_ssid_toggle);
        wifiSsidsCurrentRow = activity.findViewById(R.id.wifi_ssids_current_row);
        wifiSsidsList = activity.findViewById(R.id.wifi_ssids_list);
        wifiSsidsEmpty = activity.findViewById(R.id.wifi_ssids_empty);

        networkStatusAction.setOnClickListener(v -> onNetworkStatusActionClicked());
        networkModeAllWifi.setOnClickListener(v -> {
            KeepADBTrustedNetwork.setMode(activity, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            onChange.run();
        });
        networkModeAllowlist.setOnClickListener(v -> onAllowlistOptionClicked());

        // #616/#644: never grants anything; the user picks "Allow all the time" on the system
        // page. Without the grant, the rationale dialog comes first (step 2); with it, the button
        // goes straight to the page so the grant can still be checked or revoked.
        backgroundLocationButton.setOnClickListener(v -> {
            if (KeepADBBackgroundLocation.isGranted(activity)) {
                KeepADBBackgroundLocation.openSettings(activity);
            } else {
                showBackgroundLocationDialog();
            }
        });

        // #762: the single list of trusted and blocked networks.
        activity.findViewById(R.id.network_networks_row).setOnClickListener(v ->
                activity.startActivity(
                        NetworkListActivity.intent(activity, NetworkListActivity.VIEW_NETWORKS)));
        activity.findViewById(R.id.network_allowed_row).setOnClickListener(v ->
                activity.startActivity(
                        NetworkListActivity.intent(activity, NetworkListActivity.VIEW_ALLOWED)));
        activity.findViewById(R.id.network_prevented_row).setOnClickListener(v ->
                activity.startActivity(
                        NetworkListActivity.intent(activity, NetworkListActivity.VIEW_PREVENTED)));
        activity.findViewById(R.id.network_observed_row).setOnClickListener(v ->
                activity.startActivity(
                        NetworkListActivity.intent(activity, NetworkListActivity.VIEW_OBSERVED)));

        // The observation option only controls the observation and its list (#654).
        wifiApsFeatureToggle.setOnClickListener(v -> {
            boolean enabled = wifiApsFeatureToggle.isChecked();
            // Turning it off always applies right away (recording stops, bands are deleted);
            // the history is only deleted after an explicit "Yes" (#731).
            KeepADBPreferences.setWifiApsFeatureEnabled(activity, enabled);
            onChange.run();
            if (!enabled) {
                showObservationOffDialog();
            }
        });

        networkSsidHeader.setOnClickListener(v ->
                setSsidSectionExpanded(networkSsidBody.getVisibility() != View.VISIBLE));
        setSsidSectionExpanded(false);
        trustedSsidToggle.setOnClickListener(v -> {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(activity, trustedSsidToggle.isChecked());
            onChange.run();
        });
    }

    /**
     * Call from {@code SettingsActivity#onCreate} with the incoming (possibly null) state: re-shows
     * the background-location rationale and the allowlist permission rationale. The mesh question is never restored (see {@link #destroy}).
     */
    void restore(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        // #672: only re-shown; the rationale grants nothing and requests nothing by itself.
        if (savedInstanceState.getBoolean(STATE_BACKGROUND_LOCATION_SHOWING, false)) {
            showBackgroundLocationDialog();
        }
        // #682: only re-show the rationale; neither the permission request nor the mode
        // change happens without the user's own tap on the positive button.
        if (savedInstanceState.getBoolean(STATE_ALLOWLIST_PERMISSION_SHOWING, false)) {
            showAllowlistPermissionDialog();
        }
    }

    /** Call from {@code SettingsActivity#onStart}: starts following Wi-Fi changes. */
    void start() {
        registerWifiStatusCallback();
    }

    /** Call from {@code SettingsActivity#onStop}: stops following Wi-Fi changes. */
    void stop() {
        unregisterWifiStatusCallback();
    }

    /**
     * Call from {@code SettingsActivity#refresh()} after the other cards: records the mesh-BSSID
     * observation and re-renders the card from the persisted settings and the current connection.
     */
    void refresh() {
        // Piggyback the mesh-BSSID observation history (#266) on this already-happening
        // identity read instead of adding a new background poll/service for it. The user's
        // observation option gates every write, including reads caused by opening Settings.
        KeepADBNetworkIdentity currentIdentity = KeepADBNetworkIdentity.current(activity);
        if (currentIdentity.isKnown() && KeepADBPreferences.isWifiApsFeatureEnabled(activity)) {
            // #721: the band is stored together with the observation -- it is the one the
            // connection reports right now, and only while the option is on.
            int band = KeepADBAccessPointBand.bandOf(
                    KeepADBAccessPointBand.read(activity), currentIdentity.bssid);
            KeepADBBssidHistory.recordObservation(
                    activity, currentIdentity.displaySsid(), currentIdentity.bssid, band);
        }

        render(currentIdentity);
    }

    /** Call from {@code SettingsActivity#onSaveInstanceState}, before the dialogs are destroyed. */
    void saveState(Bundle outState) {
        outState.putBoolean(STATE_BACKGROUND_LOCATION_SHOWING,
                isShowing(activeBackgroundLocationDialog));
        outState.putBoolean(STATE_ALLOWLIST_PERMISSION_SHOWING,
                isShowing(activeAllowlistPermissionDialog));
    }

    /** Call from {@code SettingsActivity#onDestroy} to dismiss every showing dialog and drop refs. */
    void destroy() {
        dismissIfShowing(activeBackgroundLocationDialog);
        activeBackgroundLocationDialog = null;

        dismissIfShowing(activeAllowlistPermissionDialog);
        activeAllowlistPermissionDialog = null;

        // #686: derived from live data and only offered right after an allow; not restored.
        dismissIfShowing(activeMeshDialog);
        activeMeshDialog = null;

        // #731: an interrupted question counts as "No"; the history stays and nothing is restored.
        dismissIfShowing(activeObservationOffDialog);
        activeObservationOffDialog = null;
    }

    /**
     * Call from {@code SettingsActivity#onRequestPermissionsResult} with the request code; only
     * this card's two codes are acted on. The result arrays are deliberately not taken.
     */
    void onRequestPermissionsResult(int requestCode) {
        if (requestCode == TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST) {
            // grantResults can be shorter than permissions (even empty) if the request was interrupted
            // (e.g. the app was backgrounded while the system dialog was up), so re-query the actual
            // permission state instead of indexing into it.
            boolean granted = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                enableAllowlistMode();
            } else {
                Toast.makeText(activity, R.string.settings_trusted_network_permission_denied_toast,
                        Toast.LENGTH_LONG).show();
                onChange.run();
            }
        } else if (requestCode == WIFI_APS_LOCATION_PERMISSION_REQUEST) {
            onChange.run();
        }
    }

    AlertDialog getActiveAllowlistPermissionDialog() {
        return activeAllowlistPermissionDialog;
    }

    private void registerWifiStatusCallback() {
        if (wifiStatusCallback != null) return;
        ConnectivityManager connectivityManager = activity.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                onChange.run();
            }

            @Override
            public void onLost(Network network) {
                onChange.run();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                onChange.run();
            }
        };
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManager.registerNetworkCallback(
                    request, callback, new Handler(Looper.getMainLooper()));
            wifiStatusCallback = callback;
        } catch (RuntimeException e) {
            android.util.Log.w("KeepADB", "Failed to register Settings Wi-Fi callback", e);
        }
    }

    private void unregisterWifiStatusCallback() {
        ConnectivityManager.NetworkCallback callback = wifiStatusCallback;
        if (callback == null) return;
        wifiStatusCallback = null;
        ConnectivityManager connectivityManager = activity.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (RuntimeException e) {
            android.util.Log.w("KeepADB", "Failed to unregister Settings Wi-Fi callback", e);
        }
    }

    /**
     * #655: the advanced Wi-Fi-name section starts collapsed like every card and, like the cards,
     * keeps no expand state across openings. Its closed header already names the state and the
     * number of names, so collapsing it never hides an active setting.
     */
    private void setSsidSectionExpanded(boolean expanded) {
        SettingsActivity.setCardExpanded(
                activity, networkSsidHeader, networkSsidBody, networkSsidArrow, expanded);
        networkSsidHeader.setStateDescription(activity.getString(expanded
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
    }

    /**
     * Enables the explicit network allowlist only after the required precise-location grant.
     * Android may mask Wi-Fi identity after a background service start; the optional background
     * grant is set by the user on the system permission page and the app remains fail-closed
     * without a readable identity. See docs/trusted-networks.md.
     */
    private void onAllowlistOptionClicked() {
        if (KeepADBTrustedNetwork.isAllowlistMode(activity)) {
            onChange.run();
            return;
        }
        // Keep the choice on "all networks" until permission is confirmed; refresh() re-derives
        // the shown choice from the persisted mode either way.
        networkModeGroup.check(R.id.network_mode_all_wifi);
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            enableAllowlistMode();
            return;
        }
        showAllowlistPermissionDialog();
    }

    /**
     * #731: asks whether the BSSID observation history should be deleted now that the observation
     * is off. Only "Yes" deletes; Cancel, back, touch outside, rotation and process death all
     * count as "No" (the history stays). Not restored after a recreate, see {@link #destroy}.
     */
    private void showObservationOffDialog() {
        if (isShowing(activeObservationOffDialog)) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_wifi_aps_off_title)
                .setMessage(R.string.settings_wifi_aps_off_message)
                .setPositiveButton(R.string.settings_wifi_aps_off_delete, (d, which) -> {
                    KeepADBBssidHistory.clearHistory(activity);
                    onChange.run();
                })
                .setNegativeButton(R.string.settings_wifi_aps_off_keep, null)
                .create();
        activeObservationOffDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeObservationOffDialog == d) {
                activeObservationOffDialog = null;
            }
        });
        dialog.show();
    }

    /** #682: the rationale shown before ACCESS_FINE_LOCATION is requested for allowlist mode. */
    private void showAllowlistPermissionDialog() {
        if (isShowing(activeAllowlistPermissionDialog)) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_trusted_network_permission_title)
                .setMessage(R.string.settings_trusted_network_permission_message)
                .setPositiveButton(R.string.settings_trusted_network_permission_grant, (d, which) ->
                        // Requested together per Android's guidance for FINE: the system then
                        // offers the user a precise/approximate choice in one dialog. Only a FINE
                        // grant is actually usable here (see onRequestPermissionsResult).
                        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION},
                                TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeAllowlistPermissionDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeAllowlistPermissionDialog == d) {
                activeAllowlistPermissionDialog = null;
            }
        });
        dialog.show();
    }

    /**
     * Step 1 is done (ACCESS_FINE_LOCATION is granted): switches to allowlist mode and, unless the
     * optional background grant is already there, follows up with the step-2 rationale (#644).
     */
    private void enableAllowlistMode() {
        KeepADBTrustedNetwork.setMode(activity, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        onChange.run();
        if (!KeepADBBackgroundLocation.isGranted(activity)) {
            showBackgroundLocationDialog();
        }
    }

    /**
     * #644, step 2: explains why "Allow all the time" is wanted and lets the user act on it. This
     * never requests ACCESS_BACKGROUND_LOCATION itself -- since Android 11 the only way is the
     * app's system permission page, which "Open settings" jumps to.
     *
     * <p>"Trust all Wi-Fi networks instead" is only offered while allowlist mode is on; in
     * all-Wi-Fi mode it would be a no-op. "Later" keeps whatever mode is set (allowlist then runs
     * with foreground location only, and the status line keeps showing the missing grant).
     */
    private void showBackgroundLocationDialog() {
        if (activeBackgroundLocationDialog != null && activeBackgroundLocationDialog.isShowing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.background_location_panel_title)
                .setMessage(R.string.background_location_panel_body)
                .setPositiveButton(R.string.location_permission_settings_button, (d, which) ->
                        KeepADBBackgroundLocation.openSettings(activity))
                .setNegativeButton(R.string.background_location_dialog_later, null);
        if (KeepADBTrustedNetwork.isAllowlistMode(activity)) {
            builder.setNeutralButton(R.string.location_permission_panel_fallback_button, (d, which) -> {
                KeepADBTrustedNetwork.setMode(activity, KeepADBTrustedNetwork.MODE_ALL_WIFI);
                onChange.run();
            });
        }
        AlertDialog dialog = builder.create();
        activeBackgroundLocationDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeBackgroundLocationDialog == d) {
                activeBackgroundLocationDialog = null;
            }
        });
        dialog.show();
    }

    /**
     * #654/#655: renders the Network card from the persisted settings and the current connection,
     * top to bottom in the order the user reads it. All facts come from {@link
     * KeepADBNetworkCardState}; this method only shows them. It never writes a setting: opening or
     * refreshing Settings can therefore never switch the mode or the Wi-Fi-name matching, whatever
     * the connection looks like.
     */
    private void render(KeepADBNetworkIdentity identity) {
        boolean fineLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        // A readable BSSID proves an association, so the transport is only asked without one.
        boolean wifiConnected = identity.isKnown() || KeepADBService.isWifiConnected(activity);
        KeepADBNetworkCardState.Snapshot state = KeepADBNetworkCardState.derive(
                KeepADBNetworkCardState.read(activity, identity, wifiConnected, fineLocation,
                        isLocationServiceOn(), KeepADBBackgroundLocation.isGranted(activity)));
        boolean ssidMatching = KeepADBTrustedNetwork.isSsidMatchingEnabled(activity);

        // Head: the active mode, read back from the stored settings, visible while closed.
        networkSubtitle.setText(activity.getString(R.string.network_head_mode,
                activity.getString(KeepADBNetworkCardText.modeOption(state.mode))));

        // Current connection: decision, cause and the fitting action come before the mode choice.
        networkStatusLabel.setText(KeepADBNetworkCardText.connectionLabel(state.connection));
        networkStatusLabel.setTextColor(activity.getColor(
                KeepADBNetworkCardText.connectionColor(state.connection, state.mode)));
        if (identity.isKnown()) {
            networkConnectionLine.setText(
                    KeepADBNetworkDisplay.ssid(activity, identity.displaySsid(), null)
                    + " · " + KeepADBNetworkDisplay.bssid(activity, identity.bssid));
            networkConnectionLine.setVisibility(View.VISIBLE);
        } else {
            networkConnectionLine.setVisibility(View.GONE);
        }
        networkStatusCause.setText(KeepADBNetworkCardText.cause(state.cause));
        renderStatusAction(state, identity);
        networkPrivacyHint.setVisibility(
                KeepADBNetworkDisplay.hidden(activity) ? View.VISIBLE : View.GONE);

        // Mode choice: rendered from the persisted mode, so a cancelled opt-in can never leave the
        // choice claiming a mode that is not stored. The second option names the Wi-Fi names
        // while the matching setting is on.
        networkModeGroup.check(state.mode == KeepADBNetworkCardState.Mode.ALL_WIFI
                ? R.id.network_mode_all_wifi : R.id.network_mode_allowlist);
        networkModeAllowlist.setText(KeepADBNetworkCardText.modeOption(
                KeepADBNetworkCardState.mode(true, ssidMatching)));

        // Background access (#616/#645) and the current reading are two separate facts.
        backgroundLocationStatus.setText(KeepADBNetworkCardText.background(state.background));
        backgroundLocationStatus.setTextColor(
                activity.getColor(KeepADBNetworkCardText.backgroundColor(state.background)));
        networkDetectionNow.setText(KeepADBNetworkCardText.detection(state.detection));
        backgroundLocationButton.setText(
                state.background == KeepADBNetworkCardState.Background.RESTRICTED
                        ? R.string.network_background_setup_button
                        : R.string.background_location_settings_button);

        // Management entries stay reachable in every mode and whatever the observation says.
        int allowedCount = KeepADBTrustedNetwork.getEntries(activity).size();
        // #762: "3 trusted · 1 blocked"; the privacy mode hides the counts like every list.
        if (KeepADBNetworkDisplay.hidden(activity)) {
            networkNetworksCount.setText(R.string.networks_count_hidden);
        } else {
            KeepADBNetworkList.Snapshot networks = KeepADBNetworkList.build(activity, identity,
                    wifiConnected);
            networkNetworksCount.setText(activity.getString(R.string.networks_count,
                    networks.trusted, networks.blocked));
        }
        networkAllowedCount.setText(String.valueOf(allowedCount));
        networkPreventedCount.setText(
                String.valueOf(KeepADBBlockedNetworkHistory.getEntries(activity).size()));
        networkListsInactiveHint.setText(
                KeepADBNetworkCardText.inactiveListHint(activity, ssidMatching));
        networkListsInactiveHint.setVisibility(
                state.mode == KeepADBNetworkCardState.Mode.ALL_WIFI && allowedCount > 0
                        ? View.VISIBLE : View.GONE);

        // Observation: controls only the observation and its list (#654).
        boolean observing = KeepADBPreferences.isWifiApsFeatureEnabled(activity);
        wifiApsFeatureToggle.setChecked(observing);
        wifiApsContent.setVisibility(observing ? View.VISIBLE : View.GONE);

        // Advanced Wi-Fi-name section (#655): the header states the effect and the number of
        // names, never a name. The switch stays inoperable while it would change nothing.
        int nameCount = KeepADBTrustedNetwork.getSsidEntries(activity).size();
        switch (state.nameMatching) {
            case ACTIVE:
                networkSsidState.setText(
                        activity.getString(R.string.network_ssid_state_on, nameCount));
                break;
            case NO_EFFECT:
                networkSsidState.setText(
                        activity.getString(R.string.network_ssid_state_no_effect, nameCount));
                break;
            case OFF:
            default:
                networkSsidState.setText(R.string.network_ssid_state_off);
                break;
        }
        networkSsidEffect.setText(state.nameMatching == KeepADBNetworkCardState.NameMatching.NO_EFFECT
                ? KeepADBNetworkCardText.inactiveListHint(activity, ssidMatching)
                : activity.getString(KeepADBNetworkCardText.nameMatchingEffect(state.nameMatching)));
        trustedSsidToggle.setEnabled(KeepADBTrustedNetwork.isAllowlistMode(activity));
        trustedSsidToggle.setChecked(ssidMatching);
        renderSsidNames(identity);
    }

    private void renderStatusAction(KeepADBNetworkCardState.Snapshot state,
                                    KeepADBNetworkIdentity identity) {
        networkStatusActionKind = state.action;
        String ssid = identity.isKnown() ? identity.displaySsid() : null;
        networkActionBssid = identity.isKnown() ? identity.bssid : null;
        networkActionLabel = (ssid == null || ssid.isEmpty()) ? networkActionBssid : ssid;

        int label = KeepADBNetworkCardText.action(state.action);
        if (label == 0) {
            networkStatusAction.setVisibility(View.GONE);
            return;
        }
        if (state.action == KeepADBNetworkCardState.Action.GRANT_LOCATION
                && isLocationPermissionPermanentlyDenied()) {
            label = R.string.location_permission_settings_button;
        }
        networkStatusAction.setText(label);
        networkStatusAction.setContentDescription(
                state.action == KeepADBNetworkCardState.Action.ALLOW_ACCESS_POINT
                        ? activity.getString(R.string.network_action_allow_ap_accessibility,
                                KeepADBNetworkDisplay.label(activity, ssid, identity.bssid, null))
                        : null);
        networkStatusAction.setVisibility(View.VISIBLE);
    }

    private void onNetworkStatusActionClicked() {
        switch (networkStatusActionKind) {
            case ALLOW_ACCESS_POINT:
                if (networkActionBssid != null) {
                    // Grants exactly the access point the card showed; never switches anything on.
                    activeMeshDialog = KeepADBNetworkActions.allowAccessPoint(activity,
                            networkActionBssid, networkActionLabel, true, onChange);
                    if (activeMeshDialog != null) {
                        activeMeshDialog.setOnDismissListener(dialog -> {
                            if (activeMeshDialog == dialog) {
                                activeMeshDialog = null;
                            }
                        });
                        activeMeshDialog.show();
                    }
                }
                break;
            case GRANT_LOCATION:
                onLocationPermissionActionClick();
                break;
            case OPEN_LOCATION_SETTINGS:
                KeepADBNetworkActions.openLocationSettings(activity);
                break;
            case OPEN_WIFI_SETTINGS:
                KeepADBNetworkActions.openWifiSettings(activity);
                break;
            case SET_UP_BACKGROUND:
                showBackgroundLocationDialog();
                break;
            case NONE:
            default:
                break;
        }
    }

    private boolean isLocationServiceOn() {
        LocationManager manager = activity.getSystemService(LocationManager.class);
        return manager == null || manager.isLocationEnabled();
    }

    private boolean isLocationPermissionPermanentlyDenied() {
        boolean previouslyRequested = activity.getPreferences(Context.MODE_PRIVATE)
                .getBoolean(LOCATION_PERMISSION_REQUESTED, false);
        return previouslyRequested
                && !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void onLocationPermissionActionClick() {
        if (isLocationPermissionPermanentlyDenied()) {
            openAppSettings();
            return;
        }
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(LOCATION_PERMISSION_REQUESTED, true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},
                WIFI_APS_LOCATION_PERMISSION_REQUEST);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }

    /**
     * #655: the Wi-Fi-name list of the advanced section -- the current name with its add action,
     * then every saved name with its remove action. It renders whatever the switch says, so saved
     * names stay reachable in every mode; the effect line above it says whether they count.
     */
    private void renderSsidNames(KeepADBNetworkIdentity identity) {
        wifiSsidsCurrentRow.removeAllViews();
        wifiSsidsList.removeAllViews();
        // One numbering for the current name and the list, so a hidden name reads alike in both.
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

        String currentSsid = identity.isKnown() ? identity.displaySsid() : null;
        if (currentSsid == null || currentSsid.isEmpty()) {
            TextView unknown = new TextView(activity);
            unknown.setText(R.string.wifi_ssids_current_unknown);
            unknown.setTextColor(activity.getColor(R.color.night_muted));
            unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            wifiSsidsCurrentRow.addView(unknown);
        } else {
            wifiSsidsCurrentRow.addView(buildCurrentSsidRow(currentSsid, numbering));
        }

        List<KeepADBTrustedNetwork.SsidEntry> entries = KeepADBTrustedNetwork.getSsidEntries(activity);
        for (KeepADBTrustedNetwork.SsidEntry entry : entries) {
            wifiSsidsList.addView(buildTrustedSsidRow(entry, numbering));
        }
        wifiSsidsEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View buildCurrentSsidRow(String currentSsid, KeepADBNetworkDisplay.Numbering numbering) {
        boolean listed = KeepADBTrustedNetwork.findSsidEntryForCurrentNetwork(activity) != null;
        String shownName = KeepADBNetworkDisplay.ssid(activity, currentSsid, numbering);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(activity);
        label.setText(activity.getString(R.string.wifi_aps_current_badge) + " · " + shownName);
        label.setTextColor(activity.getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(label);

        if (!listed) {
            Button add = newRowActionButton(R.drawable.bg_btn_primary, R.color.title_yellow);
            add.setText(R.string.wifi_ssids_add_button);
            add.setContentDescription(activity.getString(R.string.wifi_ssids_add_accessibility, shownName));
            add.setOnClickListener(v -> {
                KeepADBTrustedNetwork.SsidEntry added = KeepADBTrustedNetwork.addCurrentSsid(activity);
                if (added == null) {
                    Toast.makeText(activity, R.string.settings_trusted_network_add_failed_toast,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, activity.getString(R.string.wifi_ssids_added_toast,
                            KeepADBNetworkDisplay.ssid(activity, added.ssid, null)),
                            Toast.LENGTH_SHORT).show();
                }
                onChange.run();
            });
            row.addView(add);
        }
        return row;
    }

    /**
     * #655: the Allow/Remove action of a Wi-Fi-name row, laid out like the row buttons of {@link
     * NetworkListActivity}. The Material default button has no horizontal padding, so at a large
     * font the label filled the whole button and touched both edges; here the padding is explicit,
     * the height is at least 48dp, and the button sits at the start edge below the name, so a long
     * label wraps inside the row instead of being cut.
     */
    private Button newRowActionButton(int backgroundRes, int textColorRes) {
        Button button = new Button(activity);
        button.setBackgroundResource(backgroundRes);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setTextColor(activity.getColor(textColorRes));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        params.gravity = android.view.Gravity.START;
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private View buildTrustedSsidRow(KeepADBTrustedNetwork.SsidEntry entry,
                                     KeepADBNetworkDisplay.Numbering numbering) {
        String shownName = KeepADBNetworkDisplay.ssid(activity, entry.ssid, numbering);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int topMargin = (int) (12 * activity.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = topMargin;
        row.setLayoutParams(rowParams);

        TextView label = new TextView(activity);
        label.setText(shownName);
        label.setTextColor(activity.getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(label);

        Button remove = newRowActionButton(R.drawable.bg_btn_secondary, R.color.text_yellow);
        remove.setText(R.string.wifi_ssids_remove_button);
        remove.setContentDescription(
                activity.getString(R.string.wifi_ssids_remove_accessibility, shownName));
        remove.setOnClickListener(v -> {
            if (KeepADBTrustedNetwork.removeSsid(activity, entry.id)) {
                Toast.makeText(activity, activity.getString(R.string.wifi_ssids_removed_toast, shownName),
                        Toast.LENGTH_SHORT).show();
            }
            onChange.run();
        });
        row.addView(remove);
        return row;
    }

    private static boolean isShowing(AlertDialog dialog) {
        return dialog != null && dialog.isShowing();
    }

    private static void dismissIfShowing(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}
