package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * #654/#655: the user actions shared by the Network card in {@link SettingsActivity} and the list
 * views in {@link NetworkListActivity}, kept in one place so both behave identically: allowing an
 * access point only grants the allowance (never switches Wireless Debugging on, see {@link
 * KeepADBReceiver#allowBssidOnly}), removing one only removes it, and every message that quotes a
 * name respects the privacy mode through {@link KeepADBNetworkDisplay}.
 */
final class KeepADBNetworkActions {

    private KeepADBNetworkActions() {}

    /**
     * Allows exactly {@code bssid} and nothing else. With {@code offerMesh} the user is then asked
     * whether other observed access points broadcasting the same name should be allowed too -- a
     * separate, explicit question; nothing beyond {@code bssid} is ever allowed implicitly.
     *
     * @param onChanged run after every change so the caller can re-render; may be null.
     */
    static void allowAccessPoint(Activity activity, String bssid, String label, boolean offerMesh,
                                 Runnable onChanged) {
        KeepADBTrustedNetwork.Entry added = KeepADBReceiver.allowBssidOnly(activity, bssid, label);
        if (added == null) {
            Toast.makeText(activity, R.string.settings_trusted_network_add_failed_toast,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, activity.getString(R.string.network_ap_allowed_toast,
                            KeepADBNetworkDisplay.quoted(activity, added.label, added.bssid)),
                    Toast.LENGTH_SHORT).show();
        }
        if (onChanged != null) onChanged.run();
        if (added != null && offerMesh) {
            offerAdditionalMeshBssids(activity, onChanged);
        }
    }

    /** Removes the allowlist entry for {@code bssid}; no other entry is touched. */
    static void removeAccessPoint(Activity activity, String bssid, Runnable onChanged) {
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(activity)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) {
                if (KeepADBTrustedNetwork.remove(activity, entry.id)) {
                    Toast.makeText(activity, activity.getString(R.string.network_ap_removed_toast,
                                    KeepADBNetworkDisplay.quoted(activity, entry.label, entry.bssid)),
                            Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }
        if (onChanged != null) onChanged.run();
    }

    /**
     * Offers the other access points KeepADB has seen under the current network name (#266). Only
     * shown for a readable identity with a known name and at least one access point that is not
     * allowed yet; accepting allows exactly those, via the grant-only path.
     */
    static void offerAdditionalMeshBssids(Activity activity, Runnable onChanged) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(activity);
        if (!identity.isKnown()) return;
        String ssid = identity.displaySsid();
        if (ssid == null || ssid.isEmpty()) return;

        List<String> alreadyListed = new ArrayList<>();
        for (KeepADBTrustedNetwork.Entry listed : KeepADBTrustedNetwork.getEntries(activity)) {
            alreadyListed.add(listed.bssid);
        }
        List<String> additional = KeepADBBssidHistory.getAdditionalBssids(activity, ssid, alreadyListed);
        if (additional.isEmpty()) return;

        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_trusted_network_mesh_title)
                .setMessage(activity.getString(R.string.settings_trusted_network_mesh_message,
                        additional.size(), KeepADBNetworkDisplay.quoted(activity, ssid)))
                .setPositiveButton(R.string.settings_trusted_network_mesh_add_button, (dialog, which) -> {
                    for (String bssid : additional) {
                        KeepADBReceiver.allowBssidOnly(activity, bssid, ssid);
                    }
                    Toast.makeText(activity,
                            activity.getString(R.string.settings_trusted_network_mesh_added_toast,
                                    additional.size()),
                            Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static void openLocationSettings(Activity activity) {
        open(activity, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    static void openWifiSettings(Activity activity) {
        open(activity, new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    private static void open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Some customized devices expose no such surface; nothing else to offer.
        }
    }
}
