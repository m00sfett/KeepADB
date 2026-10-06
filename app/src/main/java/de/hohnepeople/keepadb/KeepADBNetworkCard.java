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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Owns the Network card of {@link SettingsActivity} (#654/#655): its views, the access point
 * the card showed last (what its action button acts on), the Wi-Fi status callback and the
 * dialog of the card -- the background-location rationale and the mesh question after allowing an
 * access point. (The trust confirmation of the "new Wi-Fi" prompt lived here until #766 moved it
 * to {@link NetworkDecisionActivity}; the mode choice, the observation option and the legacy
 * Wi-Fi-name list were removed with #769 and what the card shows now is the level, the comfort
 * switch, force, the one Networks list and the background access, see docs/trusted-networks.md.)
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
 * (the request code below belongs to this card). A fresh instance is created on every
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

    /** #507/#654: permission request code of the card's "grant location" action. */
    static final int WIFI_APS_LOCATION_PERMISSION_REQUEST = 3002;
    private static final String LOCATION_PERMISSION_REQUESTED = "location_permission_requested";

    private final Activity activity;
    private final Runnable onChange;

    private final TextView networkSubtitle;
    private final TextView networkStatusLabel;
    private final TextView networkConnectionLine;
    private final TextView networkStatusCause;
    private final Button networkStatusAction;
    private final TextView networkPrivacyHint;
    private final TextView networkLevelLine;
    private final Switch trustByNameToggle;
    private final TextView comfortNoEffect;
    private final TextView backgroundLocationStatus;
    private final TextView networkDetectionNow;
    private final Button backgroundLocationButton;
    private final TextView networkNetworksCount;

    /**
     * What {@link #networkStatusAction} does right now and which access point it is bound to,
     * captured when the card was rendered: the button acts on the access point the user saw,
     * never on whatever the device happens to be connected to at click time.
     */
    private KeepADBNetworkCardState.Action networkStatusActionKind =
            KeepADBNetworkCardState.Action.NONE;
    private String networkActionBssid;
    private String networkActionLabel;

    /** #644: the step-2 rationale dialog for the optional background location grant, if showing. */
    private AlertDialog activeBackgroundLocationDialog;

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
        networkLevelLine = activity.findViewById(R.id.network_level_line);
        trustByNameToggle = activity.findViewById(R.id.settings_trust_by_name_toggle);
        comfortNoEffect = activity.findViewById(R.id.network_comfort_no_effect);
        backgroundLocationStatus = activity.findViewById(R.id.settings_background_location_status);
        networkDetectionNow = activity.findViewById(R.id.network_detection_now);
        backgroundLocationButton = activity.findViewById(R.id.settings_background_location_button);
        networkNetworksCount = activity.findViewById(R.id.network_networks_count);

        networkStatusAction.setOnClickListener(v -> onNetworkStatusActionClicked());
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
                        NetworkListActivity.intent(activity)));

        // #769: the one comfort switch (#760). OnClick, not a checked-change listener: refresh()
        // re-renders it from the stored value and must never write it.
        trustByNameToggle.setOnClickListener(v -> {
            boolean enabled = trustByNameToggle.isChecked();
            KeepADBTrustedNetwork.setTrustByNameEnabled(activity, enabled);
            KeepADBDiagnostics.event(activity, "user_action", "settings_network",
                    enabled ? "enable" : "disable", "trust_by_name");
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
     * Call from {@code SettingsActivity#refresh()} after the other cards: re-renders the card from
     * the persisted settings and the current connection. It records nothing (#769): the Wi-Fi
     * observation history is no longer fed from here, and opening Settings writes no setting.
     */
    void refresh() {
        render(KeepADBNetworkIdentity.current(activity));
    }

    /** Call from {@code SettingsActivity#onSaveInstanceState}, before the dialogs are destroyed. */
    void saveState(Bundle outState) {
        outState.putBoolean(STATE_BACKGROUND_LOCATION_SHOWING,
                isShowing(activeBackgroundLocationDialog));
    }

    /** Call from {@code SettingsActivity#onDestroy} to dismiss every showing dialog and drop refs. */
    void destroy() {
        dismissIfShowing(activeBackgroundLocationDialog);
        activeBackgroundLocationDialog = null;
    }

    /**
     * Call from {@code SettingsActivity#onRequestPermissionsResult} with the request code; only
     * this card's two codes are acted on. The result arrays are deliberately not taken.
     */
    void onRequestPermissionsResult(int requestCode) {
        if (requestCode == WIFI_APS_LOCATION_PERMISSION_REQUEST) {
            onChange.run();
        }
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

        // Head: the active mode, read back from the stored settings, visible while closed.
        networkSubtitle.setText(activity.getString(R.string.network_head_mode,
                activity.getString(KeepADBNetworkCardText.modeOption(state.mode))));

        // Current connection: decision, cause and the fitting action come before the level and the switches.
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

        // Protection level (read-only here, changed in the assistant) and the comfort switch,
        // both rendered from the stored policy.
        networkLevelLine.setText(activity.getString(R.string.networks_level,
                KeepADBForceNotice.levelLabel(activity)));
        trustByNameToggle.setChecked(KeepADBTrustedNetwork.isTrustByNameEnabled(activity));
        comfortNoEffect.setVisibility(KeepADBTrustedNetwork.getProtectionLevel(activity)
                == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI
                ? View.VISIBLE : View.GONE);

        // Background access (#616/#645) and the current reading are two separate facts.
        backgroundLocationStatus.setText(KeepADBNetworkCardText.background(state.background));
        backgroundLocationStatus.setTextColor(
                activity.getColor(KeepADBNetworkCardText.backgroundColor(state.background)));
        networkDetectionNow.setText(KeepADBNetworkCardText.detection(state.detection));
        backgroundLocationButton.setText(
                state.background == KeepADBNetworkCardState.Background.RESTRICTED
                        ? R.string.network_background_setup_button
                        : R.string.background_location_settings_button);

        // #762: "3 trusted · 1 blocked"; the privacy mode hides the counts like every list.
        if (KeepADBNetworkDisplay.hidden(activity)) {
            networkNetworksCount.setText(R.string.networks_count_hidden);
        } else {
            KeepADBNetworkList.Snapshot networks = KeepADBNetworkList.build(activity, identity,
                    wifiConnected);
            networkNetworksCount.setText(activity.getString(R.string.networks_count,
                    networks.trusted, networks.blocked));
        }
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
                    KeepADBNetworkActions.allowAccessPoint(activity, networkActionBssid,
                            networkActionLabel, onChange);
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

    private static boolean isShowing(AlertDialog dialog) {
        return dialog != null && dialog.isShowing();
    }

    private static void dismissIfShowing(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}
