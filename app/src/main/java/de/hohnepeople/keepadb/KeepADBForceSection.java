package de.hohnepeople.keepadb;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * The "Force mode" row inside Settings' Network area (#763): the status, and the buttons that open
 * the confirmation dialog, change its duration (which is a new full start through the same dialog,
 * never a silent extension) or end it. Everything it shows is read from the pure state, so it is
 * right at the deadline even before the expiry transition has run.
 */
final class KeepADBForceSection {
    private final Activity activity;
    private final Runnable onChanged;
    private final KeepADBForceDialog dialog;
    private final View panel;
    private final TextView status;
    private final Button activate;
    private final Button end;
    private final Button change;

    KeepADBForceSection(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.dialog = new KeepADBForceDialog(activity, onChanged);
        this.panel = activity.findViewById(R.id.settings_force_panel);
        this.status = activity.findViewById(R.id.settings_force_status);
        this.activate = activity.findViewById(R.id.settings_force_activate);
        this.end = activity.findViewById(R.id.settings_force_end);
        this.change = activity.findViewById(R.id.settings_force_change);
        activate.setOnClickListener(v -> dialog.show());
        change.setOnClickListener(v -> dialog.show());
        end.setOnClickListener(v -> {
            if (KeepADBForceMode.endNow(activity)) KeepADBForceNotice.showEndedToast(activity);
            onChanged.run();
        });
    }

    View getPanel() {
        return panel;
    }

    void refresh() {
        KeepADBForceMode.Status current = KeepADBForceMode.status(activity);
        if (current == null) {
            status.setText(R.string.force_status_off);
        } else if (current.isUnlimited()) {
            status.setText(R.string.force_status_unlimited);
        } else {
            status.setText(activity.getString(R.string.force_status_until,
                    KeepADBForceMode.formatEnd(activity, current)));
        }
        activate.setVisibility(current == null ? View.VISIBLE : View.GONE);
        end.setVisibility(current == null ? View.GONE : View.VISIBLE);
        change.setVisibility(current == null ? View.GONE : View.VISIBLE);
    }

    void saveState(Bundle outState) {
        dialog.saveState(outState);
    }

    void restore(Bundle savedState) {
        dialog.restore(savedState);
    }

    void destroy() {
        dialog.dismiss();
    }

    KeepADBForceDialog getDialog() {
        return dialog;
    }
}
