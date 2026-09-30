package de.hohnepeople.keepadb;

import android.content.Context;

import de.hohnepeople.keepadb.KeepADBNetworkCardState.Action;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Background;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Cause;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Connection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Detection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Mode;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.NameMatching;

/**
 * #654/#655: the one place that maps {@link KeepADBNetworkCardState} values to string and colour
 * resources, shared by the card in {@link SettingsActivity} and the rows in {@link
 * NetworkListActivity} so the same state never reads differently in two places. Every colour here
 * only reinforces a state whose text is always shown next to it.
 */
final class KeepADBNetworkCardText {

    private KeepADBNetworkCardText() {}

    static int modeOption(Mode mode) {
        switch (mode) {
            case ALLOWED_APS_AND_NAMES:
                return R.string.network_mode_option_aps_names;
            case ALLOWED_APS:
                return R.string.network_mode_option_aps;
            case ALL_WIFI:
            default:
                return R.string.network_mode_option_all_wifi;
        }
    }

    static int connectionLabel(Connection connection) {
        switch (connection) {
            case NO_WIFI:
                return R.string.network_status_no_wifi;
            case UNREADABLE:
                return R.string.network_status_unreadable;
            case ALLOWED_AP:
                return R.string.network_status_allowed_ap;
            case ALLOWED_NAME:
                return R.string.network_status_allowed_name;
            case NOT_ALLOWED:
            default:
                return R.string.network_status_not_allowed;
        }
    }

    /** Green for allowed, amber where Keep-Alive is actually held back, muted otherwise. */
    static int connectionColor(Connection connection, Mode mode) {
        switch (connection) {
            case ALLOWED_AP:
            case ALLOWED_NAME:
                return R.color.status_ok_green;
            case UNREADABLE:
            case NOT_ALLOWED:
                return mode == Mode.ALL_WIFI ? R.color.night_muted : R.color.text_yellow;
            case NO_WIFI:
            default:
                return R.color.night_muted;
        }
    }

    static int cause(Cause cause) {
        switch (cause) {
            case NO_WIFI:
                return R.string.network_cause_no_wifi;
            case PERMISSION_MISSING:
                return R.string.network_cause_permission_missing;
            case LOCATION_OFF:
                return R.string.network_cause_location_off;
            case IDENTITY_HIDDEN:
                // The platform masks the identity although permission and location are fine
                // (typically a background-started service): the existing text that also names
                // the permanent fix, "Allow all the time".
                return R.string.settings_trusted_network_status_identity_unavailable;
            case UNREADABLE_NOT_NEEDED:
                return R.string.network_cause_unreadable_all_wifi;
            case ALLOWED:
                return R.string.network_cause_allowed;
            case ALLOWED_BY_NAME:
                return R.string.network_cause_allowed_by_name;
            case ALL_WIFI:
                return R.string.network_cause_all_wifi;
            case NOT_ALLOWED:
            default:
                return R.string.network_cause_not_allowed;
        }
    }

    /** The button label for {@code action}, or 0 when there is no button. */
    static int action(Action action) {
        switch (action) {
            case ALLOW_ACCESS_POINT:
                return R.string.network_action_allow_ap;
            case GRANT_LOCATION:
                return R.string.location_permission_panel_grant_button;
            case OPEN_LOCATION_SETTINGS:
                return R.string.network_action_location_settings;
            case OPEN_WIFI_SETTINGS:
                return R.string.network_action_wifi_settings;
            case SET_UP_BACKGROUND:
                return R.string.network_background_setup_button;
            case NONE:
            default:
                return 0;
        }
    }

    static int background(Background background) {
        switch (background) {
            case ALLOWED:
                return R.string.background_location_status_granted;
            case RESTRICTED:
                return R.string.background_location_status_missing;
            case NOT_REQUIRED:
            default:
                return R.string.background_location_status_missing_inactive;
        }
    }

    static int backgroundColor(Background background) {
        switch (background) {
            case ALLOWED:
                return R.color.status_ok_green;
            case RESTRICTED:
                return R.color.text_yellow;
            case NOT_REQUIRED:
            default:
                return R.color.night_muted;
        }
    }

    static int detection(Detection detection) {
        switch (detection) {
            case READABLE:
                return R.string.network_detection_readable;
            case NOT_READABLE:
                return R.string.network_detection_unreadable;
            case NO_WIFI:
            default:
                return R.string.network_detection_no_wifi;
        }
    }

    /**
     * The hint that a saved list does not count while "all networks" is active. It names the
     * option the mode choice shows for the allowed mode right now -- the plain one, or the one
     * naming Wi-Fi names while the matching is on -- taken from the same resource as that option's
     * own label, so hint and option can never read differently.
     */
    static String inactiveListHint(Context context, boolean ssidMatching) {
        return context.getString(R.string.network_list_inactive_hint,
                context.getString(modeOption(KeepADBNetworkCardState.mode(true, ssidMatching))));
    }

    /**
     * What the Wi-Fi-name switch does right now, as one explanatory sentence. The saved-without-
     * effect sentence takes the label of the allowed option as argument: render that one through
     * {@link #inactiveListHint}, not through a plain {@code getString}.
     */
    static int nameMatchingEffect(NameMatching nameMatching) {
        switch (nameMatching) {
            case ACTIVE:
                return R.string.network_ssid_effect_on;
            case NO_EFFECT:
                return R.string.network_list_inactive_hint;
            case OFF:
            default:
                return R.string.network_ssid_effect_off;
        }
    }
}
