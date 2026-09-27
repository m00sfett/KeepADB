package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Owns the USB profile dialog family (switch/edit/delete) for {@link SettingsActivity}: dialog
 * creation, the in-progress edit draft, its {@link Bundle} round-trip and dismiss-time cleanup.
 *
 * <p>Extracted by #595 (codequality review CQ-02) so a new profile field has one clear place to
 * touch -- the field list here, {@link #showEditDialog(KeepADBUsbProfile.Profile, String, String,
 * String, String)}, {@link #restore}/{@link #saveState} and {@link #destroy} -- instead of five
 * separate spots spread across {@code SettingsActivity}'s own lifecycle methods.
 *
 * <p>{@link SettingsActivity} stays the lifecycle owner: it creates one instance in {@code
 * onCreate} and calls {@link #restore}, {@link #saveState} and {@link #destroy} from its own
 * onCreate/onSaveInstanceState/onDestroy. A fresh instance is created on every {@code onCreate},
 * so no dialog or view reference here ever survives a real activity recreation.
 */
final class KeepADBUsbProfileEditor {
    // Bundle keys of the in-progress edit draft (#595: owned here, next to the field list, so a
    // new profile field needs no change in SettingsActivity; key strings unchanged).
    static final String STATE_PROFILE_EDIT_SHOWING = "settings_profile_edit_showing";
    static final String STATE_PROFILE_EDIT_ID = "settings_profile_edit_id";
    static final String STATE_PROFILE_EDIT_NAME = "settings_profile_edit_name";
    static final String STATE_PROFILE_EDIT_IP = "settings_profile_edit_ip";
    static final String STATE_PROFILE_EDIT_HOSTNAME = "settings_profile_edit_hostname";
    static final String STATE_PROFILE_EDIT_TAILNET = "settings_profile_edit_tailnet";

    private final Activity activity;
    private final Runnable onProfileChanged;

    private AlertDialog activeEditDialog;
    private Integer activeEditId;
    private EditText activeEditName;
    private EditText activeEditIp;
    private EditText activeEditHostname;
    private EditText activeEditTailnet;

    private AlertDialog activeSwitchDialog;
    private AlertDialog activeDeleteDialog;

    /**
     * @param onProfileChanged invoked after a profile is selected, saved or deleted -- matches the
     *         {@code KeepADBUsbReceiver.refresh(this); refresh();} pair every mutation used to call
     *         directly.
     */
    KeepADBUsbProfileEditor(Activity activity, Runnable onProfileChanged) {
        this.activity = activity;
        this.onProfileChanged = onProfileChanged;
    }

    /** Call from {@code SettingsActivity#onCreate} with the incoming (possibly null) state. */
    void restore(Bundle savedInstanceState) {
        if (savedInstanceState == null
                || !savedInstanceState.getBoolean(STATE_PROFILE_EDIT_SHOWING, false)) {
            return;
        }
        int profileId = savedInstanceState.getInt(STATE_PROFILE_EDIT_ID, -1);
        KeepADBUsbProfile.Profile editingProfile = null;
        if (profileId != -1) {
            for (KeepADBUsbProfile.Profile p : KeepADBUsbProfile.getProfiles(activity)) {
                if (p.id == profileId) {
                    editingProfile = p;
                    break;
                }
            }
        }
        String draftName = savedInstanceState.getString(STATE_PROFILE_EDIT_NAME);
        String draftIp = savedInstanceState.getString(STATE_PROFILE_EDIT_IP);
        String draftHostname = savedInstanceState.getString(STATE_PROFILE_EDIT_HOSTNAME);
        String draftTailnet = savedInstanceState.getString(STATE_PROFILE_EDIT_TAILNET);
        showEditDialog(editingProfile, draftName, draftIp, draftHostname, draftTailnet);
    }

    /** Call from {@code SettingsActivity#onSaveInstanceState}. */
    void saveState(Bundle outState) {
        if (activeEditDialog == null || !activeEditDialog.isShowing()) {
            return;
        }
        outState.putBoolean(STATE_PROFILE_EDIT_SHOWING, true);
        outState.putInt(STATE_PROFILE_EDIT_ID,
                activeEditId != null ? activeEditId : -1);
        outState.putString(STATE_PROFILE_EDIT_NAME,
                activeEditName != null && activeEditName.getText() != null
                        ? activeEditName.getText().toString() : "");
        outState.putString(STATE_PROFILE_EDIT_IP,
                activeEditIp != null && activeEditIp.getText() != null
                        ? activeEditIp.getText().toString() : "");
        outState.putString(STATE_PROFILE_EDIT_HOSTNAME,
                activeEditHostname != null && activeEditHostname.getText() != null
                        ? activeEditHostname.getText().toString() : "");
        outState.putString(STATE_PROFILE_EDIT_TAILNET,
                activeEditTailnet != null && activeEditTailnet.getText() != null
                        ? activeEditTailnet.getText().toString() : "");
    }

    /** Call from {@code SettingsActivity#onDestroy} to dismiss any showing dialog and drop refs. */
    void destroy() {
        if (activeEditDialog != null) {
            if (activeEditDialog.isShowing()) {
                activeEditDialog.dismiss();
            }
            activeEditDialog = null;
        }
        activeEditId = null;
        activeEditName = null;
        activeEditIp = null;
        activeEditHostname = null;
        activeEditTailnet = null;

        if (activeSwitchDialog != null) {
            if (activeSwitchDialog.isShowing()) {
                activeSwitchDialog.dismiss();
            }
            activeSwitchDialog = null;
        }

        if (activeDeleteDialog != null) {
            if (activeDeleteDialog.isShowing()) {
                activeDeleteDialog.dismiss();
            }
            activeDeleteDialog = null;
        }
    }

    AlertDialog getActiveEditDialog() {
        return activeEditDialog;
    }

    AlertDialog getActiveSwitchDialog() {
        return activeSwitchDialog;
    }

    AlertDialog getActiveDeleteDialog() {
        return activeDeleteDialog;
    }

    /**
     * Entry point for both the settings row's action button and a profile-action notification
     * intent extra: {@code KeepADBUsbNotification.ACTION_SWITCH} shows the switch-profile list when
     * profiles already exist, {@code ACTION_CREATE} (or SWITCH with no profiles yet) opens the edit
     * dialog directly for a new profile.
     */
    void showDialog(String action) {
        List<KeepADBUsbProfile.Profile> profiles = KeepADBUsbProfile.getProfiles(activity);
        if (KeepADBUsbNotification.ACTION_SWITCH.equals(action) && !profiles.isEmpty()) {
            showSwitchDialog(profiles);
            return;
        }
        showEditDialog(null);
    }

    private void showSwitchDialog(List<KeepADBUsbProfile.Profile> profiles) {
        KeepADBUsbProfile.Profile current = KeepADBUsbProfile.getSelected(activity);
        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        options.setPadding(padding, 0, padding, 0);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        int rowSpacing = (int) (8 * activity.getResources().getDisplayMetrics().density);
        for (int i = 0; i < profiles.size(); i++) {
            KeepADBUsbProfile.Profile profile = profiles.get(i);
            int minTouch = (int) (48 * activity.getResources().getDisplayMetrics().density);

            // #593: name and details stacked in two lines instead of one radio label, so a
            // profile with a long IP/host/tailnet combination wraps as whole words instead of
            // character-by-character.
            LinearLayout textColumn = new LinearLayout(activity);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = new TextView(activity);
            nameView.setText(profile.name);
            textColumn.addView(nameView);
            String details = profile.details();
            if (!details.isEmpty()) {
                TextView detailsView = new TextView(activity);
                detailsView.setText(details);
                textColumn.addView(detailsView);
            }
            textColumn.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            RadioButton select = new RadioButton(activity);
            select.setContentDescription(profile.summary());
            select.setChecked(current != null && current.id == profile.id);
            select.setMinHeight(minTouch);
            select.setMinWidth(minTouch);

            LinearLayout selectRow = new LinearLayout(activity);
            selectRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            selectRow.setMinimumHeight(minTouch);
            View.OnClickListener selectListener = v -> {
                KeepADBUsbProfile.select(activity, profile.id);
                dialogHolder[0].dismiss();
                onProfileChanged.run();
            };
            selectRow.setOnClickListener(selectListener);
            select.setOnClickListener(selectListener);
            selectRow.addView(select);
            selectRow.addView(textColumn);

            // #593: edit/delete moved to their own row below the text instead of sharing the
            // selection row, which used to force the row into a character-wide wrap on long
            // profile summaries.
            LinearLayout actionRow = new LinearLayout(activity);
            actionRow.setGravity(android.view.Gravity.END);
            Button edit = new Button(activity);
            edit.setText(R.string.usb_profile_edit_button);
            edit.setContentDescription(activity.getString(
                    R.string.usb_profile_edit_action_accessibility, profile.name));
            edit.setMinHeight(minTouch);
            edit.setOnClickListener(v -> {
                dialogHolder[0].dismiss();
                showEditDialog(profile);
            });
            Button delete = new Button(activity);
            delete.setText(R.string.usb_profile_delete_button);
            delete.setContentDescription(activity.getString(
                    R.string.usb_profile_delete_action_accessibility, profile.name));
            delete.setMinHeight(minTouch);
            delete.setOnClickListener(v -> {
                dialogHolder[0].dismiss();
                showDeleteDialog(profile);
            });
            actionRow.addView(edit);
            actionRow.addView(delete);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, rowSpacing, 0, rowSpacing);
            row.addView(selectRow);
            row.addView(actionRow);
            options.addView(row);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(options);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.usb_profile_switch_title)
                .setView(scroll)
                .setPositiveButton(R.string.usb_profile_new_button, (buttonDialog, which) ->
                        showEditDialog(null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogHolder[0] = dialog;
        activeSwitchDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeSwitchDialog == d) {
                activeSwitchDialog = null;
            }
        });
        dialog.show();
    }

    private void showEditDialog(KeepADBUsbProfile.Profile profile) {
        showEditDialog(profile, null, null, null, null);
    }

    private void showEditDialog(KeepADBUsbProfile.Profile profile,
            String draftName, String draftIp, String draftHostname, String draftTailnet) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        EditText name = profileField(R.string.usb_profile_name_hint);
        EditText ip = profileField(R.string.usb_profile_ip_hint);
        EditText hostname = profileField(R.string.usb_profile_hostname_hint);
        EditText tailnet = profileField(R.string.usb_profile_tailnet_hint);
        if (profile != null) {
            name.setText(profile.name);
            ip.setText(profile.ipAddress);
            hostname.setText(profile.hostname);
            tailnet.setText(profile.tailnetHostname);
        }
        if (draftName != null) {
            name.setText(draftName);
        }
        if (draftIp != null) {
            ip.setText(draftIp);
        }
        if (draftHostname != null) {
            hostname.setText(draftHostname);
        }
        if (draftTailnet != null) {
            tailnet.setText(draftTailnet);
        }
        fields.addView(name);
        fields.addView(ip);
        fields.addView(hostname);
        fields.addView(tailnet);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(profile == null ? R.string.usb_profile_create_title
                        : R.string.usb_profile_edit_title)
                .setView(scroll)
                .setPositiveButton(R.string.usb_profile_save_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeEditDialog = dialog;
        activeEditId = profile != null ? profile.id : null;
        activeEditName = name;
        activeEditIp = ip;
        activeEditHostname = hostname;
        activeEditTailnet = tailnet;
        dialog.setOnDismissListener(d -> {
            activeEditDialog = null;
            activeEditId = null;
            activeEditName = null;
            activeEditIp = null;
            activeEditHostname = null;
            activeEditTailnet = null;
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) {
                name.setError(activity.getString(R.string.usb_profile_name_required));
                return;
            }
            if (profile == null) {
                KeepADBUsbProfile.add(activity, name.getText().toString(), ip.getText().toString(),
                        hostname.getText().toString(), tailnet.getText().toString());
            } else if (KeepADBUsbProfile.update(activity, profile.id, name.getText().toString(),
                    ip.getText().toString(), hostname.getText().toString(),
                    tailnet.getText().toString()) == null) {
                name.setError(activity.getString(R.string.usb_profile_name_required));
                return;
            }
            dialog.dismiss();
            onProfileChanged.run();
        }));
        dialog.show();
    }

    private void showDeleteDialog(KeepADBUsbProfile.Profile profile) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.usb_profile_delete_title)
                .setMessage(activity.getString(R.string.usb_profile_delete_message, profile.name))
                .setPositiveButton(R.string.usb_profile_delete_button, (d, which) -> {
                    if (KeepADBUsbProfile.delete(activity, profile.id)) {
                        onProfileChanged.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeDeleteDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDeleteDialog == d) {
                activeDeleteDialog = null;
            }
        });
        dialog.show();
    }

    private EditText profileField(int hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setSingleLine(true);
        return field;
    }
}
