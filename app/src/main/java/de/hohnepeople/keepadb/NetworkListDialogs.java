package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * #1051: the dialogs of the "Networks" list, split out of {@link NetworkListRenderer}: what a tap
 * on a saved access point or on the Wi-Fi name of a group offers, the confirmation before a name
 * block is lifted, and the toast that says what an answer did. They only change what is already
 * saved (#762); the texts, badges and the privacy scope come from the renderer.
 */
final class NetworkListDialogs {
    private final Activity activity;
    private final NetworkListRenderer renderer;
    private final Runnable onChanged;
    private AlertDialog dialog;

    NetworkListDialogs(Activity activity, NetworkListRenderer renderer, Runnable onChanged) {
        this.activity = activity;
        this.renderer = renderer;
        this.onChanged = onChanged;
    }

    /** Dismisses the open dialog, if any; derived from live data and never restored. */
    void dismiss() {
        if (dialog != null) {
            if (dialog.isShowing()) dialog.dismiss();
            dialog = null;
        }
    }

    /** What a tap on a saved access point offers: only what changes something in its state. */
    void showAccessPointDialog(KeepADBNetworkList.Row row) {
        LinearLayout content = dialogContent();
        String bssidLine = renderer.bssidLine(row.bssid);
        String custom = row.customName == null ? null
                : KeepADBNetworkDisplay.customName(renderer.shown(), row.customName);

        TextView title = renderer.views().condensed(custom != null ? custom : bssidLine, 18, R.color.title_yellow);
        title.setAccessibilityHeading(true);
        content.addView(title);
        if (row.ssid != null) {
            content.addView(renderer.views().text(row.ssid, 15, R.color.night_text));
        }
        if (custom != null) content.addView(renderer.views().monoLine(bssidLine));
        LinearLayout status = new LinearLayout(activity);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(0, KeepADBUiKit.dp(activity, 8), 0, 0);
        status.addView(renderer.views().statusBadge(row.status));
        content.addView(status);
        if (row.status == KeepADBNetworkList.Status.BLOCKED) {
            content.addView(renderer.views().text(renderer.views().reasonText(row.reason, row.ssid, row.entry != null), 13,
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
                                activity, row.bssid, row.labelSsid()), ssid));
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
                    () -> answer(KeepADBNetworkListActions.blockAccessPoint(
                            activity, row.bssid, ssid), ssid));
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
        } else if (addressBlocked) {
            // #802: a blocked access point without a trusted entry keeps its own name with the block.
            addDialogButton(content, activity.getString(R.string.networks_action_rename), false,
                    () -> {
                        AlertDialog rename = KeepADBNetworkActions.editBlockedAccessPointName(
                                activity, row.bssid, row.customName, onChanged);
                        showDialog(rename);
                    });
        }
        addDialogButton(content, activity.getString(android.R.string.cancel), null, null);
        showContent(content);
    }

    /** What a tap on the Wi-Fi name of a group offers. */
    void showNameDialog(String ssid, KeepADBNetworkList.Group group) {
        LinearLayout content = dialogContent();
        TextView title = renderer.views().condensed(activity.getString(R.string.networks_group_dialog_head, ssid), 18,
                R.color.title_yellow);
        title.setAccessibilityHeading(true);
        content.addView(title);
        if (group != null && !group.rows.isEmpty()) {
            content.addView(renderer.views().text(activity.getString(R.string.networks_group_saved, group.rows.size()),
                    13, R.color.night_muted));
        }
        boolean blocked = KeepADBNetworkBlocklist.isSsidBlocked(activity, ssid);
        if (!blocked && KeepADBTrustedNetwork.isTrustByNameEnabled(activity)) {
            TextView note = renderer.views().text(activity.getString(R.string.networks_group_comfort), 13,
                    R.color.text_yellow);
            note.setPadding(0, KeepADBUiKit.dp(activity, 8), 0, 0);
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
        String quoted = ssid == null ? "" : KeepADBNetworkDisplay.toastLabel(renderer.shown(), ssid, null);
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
        content.setPadding(KeepADBUiKit.dp(activity, 20), KeepADBUiKit.dp(activity, 16), KeepADBUiKit.dp(activity, 20), KeepADBUiKit.dp(activity, 8));
        return content;
    }

    /**
     * Adds a full-width button; {@code primary} null makes it a plain text button (cancel). Every
     * button closes the dialog first, then runs its action.
     */
    private void addDialogButton(LinearLayout content, String label, Boolean primary,
                                 Runnable action) {
        View.OnClickListener listener = v -> {
            dismiss();
            if (action != null) action.run();
        };
        Button button = primary == null
                ? renderer.views().plainButton(label, listener) : renderer.views().stackedButton(label, primary, listener);
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

    private void toast(String message, int duration) {
        Toast.makeText(activity, message, duration).show();
    }
}
