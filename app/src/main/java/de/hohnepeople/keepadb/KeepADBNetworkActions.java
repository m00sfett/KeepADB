package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

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
     * Allows exactly {@code bssid} and nothing else; no other access point is ever allowed
     * implicitly (the former offer of further access points of the same name is gone, #788).
     *
     * @param onChanged run after every change so the caller can re-render; may be null.
     */
    static void allowAccessPoint(Activity activity, String bssid, String label,
                                 Runnable onChanged) {
        KeepADBTrustedNetwork.Entry added = KeepADBReceiver.allowBssidOnly(activity, bssid, label);
        if (added == null) {
            // #762: a refusal because of a block is not a failure of the app; say what holds.
            boolean blocked = KeepADBNetworkBlocklist.isBlocked(activity, bssid,
                    KeepADBTrustedNetwork.ssidFromLabel(label, bssid));
            Toast.makeText(activity, blocked ? R.string.network_decision_trust_refused_toast
                            : R.string.settings_trusted_network_add_failed_toast,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, activity.getString(R.string.network_ap_allowed_toast,
                            KeepADBNetworkDisplay.quoted(activity, added.label, added.bssid)),
                    Toast.LENGTH_SHORT).show();
        }
        if (onChanged != null) onChanged.run();
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
     * #714: the small popup in which the user names one allowed access point, or resets the name.
     * OK stores the typed name (an empty field means "no own name", like Reset), Cancel and
     * dismissing change nothing. Only the display name of the entry is written; its address, its
     * label and the allowance stay as they are.
     *
     * @param entry the entry as shown; its number titles the popup and its current name, if any,
     *     pre-fills the field and enables Reset.
     * @return the dialog; the caller must show it, drop its reference on dismissal and dismiss it
     *     when its activity is destroyed (like the mesh question, #686).
     */
    static AlertDialog editAccessPointName(Activity activity, KeepADBTrustedNetwork.Entry entry,
                                           Runnable onChanged) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(KeepADBTrustedNetwork.MAX_CUSTOM_NAME_LENGTH)});
        input.setHint(R.string.network_ap_rename_hint);
        if (entry.customName != null) {
            input.setText(entry.customName);
            input.setSelection(input.getText().length());
        }
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        FrameLayout content = new FrameLayout(activity);
        content.setPadding(padding, 0, padding, 0);
        content.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.network_ap_rename_title, entry.id))
                .setMessage(R.string.network_ap_rename_message)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    KeepADBTrustedNetwork.setCustomName(activity, entry.id,
                            input.getText().toString());
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (entry.customName != null) {
            builder.setNeutralButton(R.string.network_ap_rename_reset, (dialog, which) -> {
                KeepADBTrustedNetwork.setCustomName(activity, entry.id, null);
                if (onChanged != null) onChanged.run();
            });
        }
        return builder.create();
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
