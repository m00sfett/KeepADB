package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * #952: "Connect a computer", the short help for the step Android requires before a new computer can
 * connect to Wireless debugging: pairing it once. Opened from the link below the endpoint of the
 * home screen and from the last page of the setup assistant.
 *
 * <p>KeepADB cannot read whether a computer is paired, pairs nothing itself and shows no pairing
 * code, so the help never claims a pairing state: it says what to do, in three steps. The commands
 * are technical text and stay as they are in every language; Android's own screens keep Android's
 * names in quotation marks, KeepADB's switch is called "Wifi-ADB".
 */
final class KeepADBConnectHelp {
    /** Step 2: run once on each computer; the code is entered at its prompt. Not translated. */
    static final String COMMAND_PAIR = "adb pair <ip>:<pairing-port>";
    /** Step 3: the endpoint KeepADB shows (the tap on the address copies this command). Not translated. */
    static final String COMMAND_CONNECT = "adb connect <ip>:<port>";

    private KeepADBConnectHelp() {}

    /**
     * Shows the help as a dialog: its three steps scroll, so the content also fits at a large font
     * scale, and the two buttons are "Open developer options" and "Close".
     */
    static AlertDialog show(Activity activity) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.connect_help_title)
                .setView(buildContent(activity))
                .setPositiveButton(R.string.connect_help_open_settings,
                        (d, which) -> openDeveloperOptions(activity))
                .setNegativeButton(R.string.onboarding_close, null)
                .create();
        dialog.show();
        return dialog;
    }

    /** The three steps with their commands, in a scroll view. */
    static View buildContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        // #905: a custom view gets no dialog inset of its own; the title's 24 dp keep it aligned.
        int inset = dp(context, 24);
        content.setPadding(inset, dp(context, 8), inset, 0);

        content.addView(step(context, 1, context.getString(R.string.connect_help_step_phone)));
        content.addView(step(context, 2, context.getString(R.string.connect_help_step_pair)));
        content.addView(command(context, COMMAND_PAIR));
        content.addView(step(context, 3, context.getString(R.string.connect_help_step_connect)));
        content.addView(command(context, COMMAND_CONNECT));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * Android's developer options, where "Wireless debugging" and its pairing live. A device that
     * has no such screen gets the neutral "no app found" toast of the other system links.
     */
    static void openDeveloperOptions(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.settings_no_browser_found, Toast.LENGTH_SHORT).show();
        }
    }

    private static TextView step(Context context, int number, String text) {
        TextView view = new TextView(context);
        view.setText(number + ". " + text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(context.getColor(R.color.night_text));
        view.setLineSpacing(dp(context, 3), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 12);
        view.setLayoutParams(params);
        return view;
    }

    private static TextView command(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(context.getColor(R.color.text_yellow));
        view.setTextIsSelectable(true);
        // A command reads left to right in every language, also in a right-to-left layout.
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 4);
        view.setLayoutParams(params);
        return view;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
