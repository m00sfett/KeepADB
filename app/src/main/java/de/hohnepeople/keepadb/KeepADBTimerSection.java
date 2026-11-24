package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * #1000: the timer row of the home screen's Wifi-ADB card (view_home_status.xml).
 *
 * <ul>
 *   <li>No timer: one "Timer" button that opens the duration list (#1032). With Wifi-ADB off
 *       the choice switches it on for that long; with Wifi-ADB on it only sets an end.</li>
 *   <li>Timer running: "Off at 14:35 · 42 min left" (or "Timer running · no Wi-Fi · 42 min left",
 *       with the other reasons of {@link #waitingReason}, while Keep-Alive cannot hold; #1012),
 *       "+1 h" below the cap and "Timer off".</li>
 * </ul>
 * Everything is read from the pure timer state, so the row is right at the deadline even before
 * the end has been performed; a tick redraws the time left while the screen is in front.
 */
final class KeepADBTimerSection {
    private static final long TICK_MS = 30_000L;

    private final Activity activity;
    private final Runnable onChanged;
    private final View panel;
    private final TextView status;
    private final Button start;
    private final Button extend;
    private final Button noLimit;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            KeepADBTimer.finishIfExpired(activity);
            onChanged.run();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private boolean adbOn;

    KeepADBTimerSection(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.panel = activity.findViewById(R.id.timer_panel);
        this.status = activity.findViewById(R.id.timer_status);
        this.start = activity.findViewById(R.id.timer_start);
        this.extend = activity.findViewById(R.id.timer_extend);
        this.noLimit = activity.findViewById(R.id.timer_no_limit);
        start.setOnClickListener(v -> showChoice());
        extend.setOnClickListener(v -> {
            KeepADBTimer.extend(activity, KeepADB.SOURCE_APP);
            onChanged.run();
        });
        noLimit.setOnClickListener(v -> {
            KeepADBTimer.removeLimit(activity, KeepADB.SOURCE_APP);
            onChanged.run();
        });
    }

    /**
     * #1012: why a running timer waits, in the words the overview widget uses, from the same
     * resolution as the home status ({@link HomeStatusController#resolveKeepAliveWaitingDetail}). That
     * resolver also answers {@code WIFI_DISCONNECTED} for a trusted Wi-Fi with nothing to explain,
     * so the connection is passed in: connected and nothing blocking means it is switching on.
     */
    static int waitingReason(boolean wifiConnected, HomeStatusController.KeepAliveWaitingDetail detail) {
        if (!wifiConnected) return R.string.overview_reason_no_wifi;
        switch (detail) {
            case BLOCKED_UNTRUSTED_NETWORK:
                return R.string.overview_reason_untrusted;
            case BLOCKED_IDENTITY_UNAVAILABLE:
                return R.string.overview_reason_identity;
            case BLOCKED_OPEN_NETWORK:
                return R.string.overview_reason_open_network;
            case BLOCKED_RECOVERY_BACKOFF:
                return R.string.overview_reason_backoff;
            case WIFI_DISCONNECTED:
            default:
                return R.string.timer_waiting_reason_switching;
        }
    }

    /** Starts the minute redraw while the screen is in front. */
    void resume() {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    void pause() {
        handler.removeCallbacks(tick);
    }

    /**
     * @param configured whether the permission is there (without it the row is hidden)
     * @param on whether Wifi-ADB is on right now
     * @param waiting whether Keep-Alive is waiting to switch it on (off, intent on)
     */
    void render(boolean configured, boolean on, boolean waiting) {
        adbOn = on;
        if (!configured) {
            panel.setVisibility(View.GONE);
            return;
        }
        panel.setVisibility(View.VISIBLE);
        KeepADBTimer.Status timer = KeepADBTimer.status(activity);
        if (timer != null) {
            String left = KeepADBTimerText.remaining(activity, timer.remainingMs);
            String text = !on || waiting
                    ? activity.getString(R.string.timer_status_waiting_reason,
                            activity.getString(waitingReason(KeepADBService.isWifiConnected(activity),
                                    HomeStatusController.resolveKeepAliveWaitingDetail(activity))), left)
                    : activity.getString(R.string.timer_status_running,
                            KeepADBForceMode.formatEnd(activity, timer.endsAtWallMs), left);
            if (!TextUtils.equals(status.getText(), text)) status.setText(text);
            status.setVisibility(View.VISIBLE);
            start.setVisibility(View.GONE);
            extend.setVisibility(timer.canExtend() ? View.VISIBLE : View.GONE);
            noLimit.setVisibility(View.VISIBLE);
            return;
        }
        status.setVisibility(View.GONE);
        extend.setVisibility(View.GONE);
        noLimit.setVisibility(View.GONE);
        // The chevron says that a list follows; the dialog title says what the choice does.
        start.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_timer, 0,
                R.drawable.ic_expand_more, 0);
        start.setVisibility(View.VISIBLE);
    }

    private void showChoice() {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(adbOn ? R.string.timer_dialog_title_limit : R.string.timer_dialog_title_start)
                .setItems(KeepADBTimerText.durations(activity), (d, which) -> {
                    d.dismiss();
                    startTimer(KeepADBTimer.Duration.values()[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOwnerActivity(activity);
        dialog.show();
    }

    private void startTimer(KeepADBTimer.Duration duration) {
        boolean wasOn = adbOn;
        KeepADB.ToggleResult result = KeepADBTimer.start(activity, duration, KeepADB.SOURCE_APP);
        if (result == null || !result.isSuccess()) {
            String message = result != null && result.isPermissionFailure()
                    ? activity.getString(R.string.permission_error_toast, activity.getPackageName())
                    : activity.getString(R.string.toggle_failed_toast);
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        } else if (!wasOn) {
            Toast.makeText(activity, activity.getString(R.string.timer_started_toast,
                    KeepADBTimerText.duration(activity, duration)), Toast.LENGTH_SHORT).show();
        }
        onChanged.run();
    }
}
