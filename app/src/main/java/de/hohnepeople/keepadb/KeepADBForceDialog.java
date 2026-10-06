package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.Toast;

/**
 * The confirmation dialog of the force mode (#763), the only place that can turn it on (a static
 * test pins that {@link KeepADBForceMode#activate} has no other caller). One dialog, one step: the
 * duration list on top, below it the warning block that grows with the duration, so the user sees
 * the consequences of the duration before confirming.
 *
 * <ul>
 *   <li><b>Mandatory time limit.</b> The five choices of {@link KeepADBForceMode.Span} are the only
 *       ones; the preselection is the shortest, one hour.</li>
 *   <li><b>Warning stages</b> ({@link KeepADBForceMode.Span#warningStage}): the general warning for
 *       every duration, plus the several-days warning from 7 days on, plus the no-end-time warning
 *       without one.</li>
 *   <li><b>Without an end time</b> the confirm button stays disabled until the acknowledgment box
 *       is ticked; the click handler and {@link KeepADBForceMode#activate} refuse it as well, so a
 *       programmatic click or another caller cannot get around it.</li>
 *   <li><b>Keep-Alive (F7):</b> when it is off the dialog says it is switched on with the mode.</li>
 * </ul>
 *
 * Reusable by the onboarding assistant (#761): {@code new KeepADBForceDialog(activity, onChanged)}
 * and {@link #show()}. The selection survives a rotation ({@link #saveState}, {@link #restore}); a
 * restored dialog still needs its own tap on the confirm button.
 */
final class KeepADBForceDialog {
    static final String STATE_SHOWING = "force_dialog_showing";
    static final String STATE_SPAN = "force_dialog_span";
    static final String STATE_ACK = "force_dialog_ack";

    private final Activity activity;
    private final Runnable onChanged;

    private AlertDialog dialog;
    private RadioGroup spans;
    private CheckBox acknowledgment;
    private Button confirm;
    private View warningDays;
    private View warningUnlimited;

    KeepADBForceDialog(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void show() {
        show(KeepADBForceMode.Span.DEFAULT, false);
    }

    void show(KeepADBForceMode.Span preselected, boolean acknowledged) {
        if (isShowing()) return;
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_force_mode, null);
        spans = content.findViewById(R.id.force_dialog_spans);
        acknowledgment = content.findViewById(R.id.force_dialog_ack);
        confirm = content.findViewById(R.id.force_dialog_confirm);
        warningDays = content.findViewById(R.id.force_dialog_warning_days);
        warningUnlimited = content.findViewById(R.id.force_dialog_warning_unlimited);
        content.findViewById(R.id.force_dialog_keepalive_hint).setVisibility(
                KeepADBPreferences.isKeepAliveEnabled(activity) ? View.GONE : View.VISIBLE);

        spans.check(radioIdFor(preselected));
        acknowledgment.setChecked(acknowledged && preselected.isUnlimited());
        spans.setOnCheckedChangeListener((group, checkedId) -> render());
        acknowledgment.setOnCheckedChangeListener((button, checked) -> render());
        confirm.setOnClickListener(v -> confirm());
        content.findViewById(R.id.force_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        render();

        AlertDialog created = new AlertDialog.Builder(activity)
                .setTitle(R.string.force_dialog_title)
                .setView(content)
                .create();
        dialog = created;
        created.setOnDismissListener(d -> {
            if (dialog == d) dialog = null;
        });
        created.show();
    }

    void dismiss() {
        if (isShowing()) dialog.dismiss();
        dialog = null;
    }

    /** The warning block, the checkbox and the confirm button for the current selection. */
    private void render() {
        KeepADBForceMode.Span span = selectedSpan();
        warningDays.setVisibility(span.warningStage >= 2 ? View.VISIBLE : View.GONE);
        warningUnlimited.setVisibility(span.warningStage >= 3 ? View.VISIBLE : View.GONE);
        acknowledgment.setVisibility(span.isUnlimited() ? View.VISIBLE : View.GONE);
        // The acknowledgment belongs to the unlimited choice, not to the dialog: leaving that
        // choice takes the tick back, so coming back to it needs a fresh one.
        if (!span.isUnlimited() && acknowledgment.isChecked()) acknowledgment.setChecked(false);
        confirm.setText(confirmLabel(span));
        boolean confirmable = !span.isUnlimited() || acknowledgment.isChecked();
        confirm.setEnabled(confirmable);
        // The button background has no disabled state of its own.
        confirm.setAlpha(confirmable ? 1f : 0.45f);
    }

    private void confirm() {
        KeepADBForceMode.Span span = selectedSpan();
        boolean acknowledged = acknowledgment.isChecked();
        // The button is disabled in this case; a direct performClick() or a test must not get
        // around that, and activate() refuses it once more.
        if (span.isUnlimited() && !acknowledged) return;
        if (!KeepADBForceMode.activate(activity, span, acknowledged)) {
            // Nothing was started or stored (a limited mode needs the boot counter to keep its
            // limit, or the state could not be saved). A button that silently does nothing reads
            // as broken, and the user must not believe the mode is on: say it, keep the dialog.
            Toast.makeText(activity, R.string.force_not_started_toast, Toast.LENGTH_LONG).show();
            return;
        }
        dismiss();
        onChanged.run();
    }

    KeepADBForceMode.Span selectedSpan() {
        int checked = spans.getCheckedRadioButtonId();
        for (KeepADBForceMode.Span span : KeepADBForceMode.Span.values()) {
            if (radioIdFor(span) == checked) return span;
        }
        return KeepADBForceMode.Span.DEFAULT;
    }

    static int radioIdFor(KeepADBForceMode.Span span) {
        switch (span) {
            case HOURS_24:
                return R.id.force_span_24h;
            case DAYS_7:
                return R.id.force_span_7d;
            case DAYS_30:
                return R.id.force_span_30d;
            case UNLIMITED:
                return R.id.force_span_unlimited;
            case HOUR_1:
            default:
                return R.id.force_span_1h;
        }
    }

    static int confirmLabel(KeepADBForceMode.Span span) {
        switch (span) {
            case HOURS_24:
                return R.string.force_confirm_24h;
            case DAYS_7:
                return R.string.force_confirm_7d;
            case DAYS_30:
                return R.string.force_confirm_30d;
            case UNLIMITED:
                return R.string.force_confirm_unlimited;
            case HOUR_1:
            default:
                return R.string.force_confirm_1h;
        }
    }

    // ---- Rotation -------------------------------------------------------------------------------------------

    void saveState(Bundle outState) {
        boolean showing = isShowing();
        outState.putBoolean(STATE_SHOWING, showing);
        if (showing) {
            outState.putString(STATE_SPAN, selectedSpan().token);
            outState.putBoolean(STATE_ACK, acknowledgment.isChecked());
        }
    }

    /** Re-shows the dialog with the earlier selection; nothing is activated by restoring. */
    void restore(Bundle savedState) {
        if (savedState == null || !savedState.getBoolean(STATE_SHOWING, false)) return;
        KeepADBForceMode.Span span = KeepADBForceMode.Span.fromToken(savedState.getString(STATE_SPAN));
        show(span == null ? KeepADBForceMode.Span.DEFAULT : span, savedState.getBoolean(STATE_ACK, false));
    }

    // ---- Test access --------------------------------------------------------------------------------------------

    AlertDialog getDialogForTesting() {
        return dialog;
    }
}
