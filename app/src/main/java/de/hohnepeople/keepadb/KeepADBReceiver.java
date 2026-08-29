package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Handles explicit KeepADB broadcast actions (such as the notification disable action).
 */
public final class KeepADBReceiver extends BroadcastReceiver {
    static final String ACTION_DISABLE = "de.hohnepeople.keepadb.ACTION_DISABLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            handleDisableAction(context);
        }
    }

    static boolean handleDisableAction(Context context) {
        KeepADBDiagnostics.event(context, "user_action", "notification", "disable", "action_button");
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        boolean success = KeepADB.setEnabled(context, false, "notification");
        if (!success) {
            try {
                Toast.makeText(context,
                        localizedContext.getString(R.string.permission_error_toast, context.getPackageName()),
                        Toast.LENGTH_LONG).show();
            } catch (RuntimeException ignored) {
            }
        }
        KeepADBService.sync(context);
        KeepADBNotification.refresh(context);
        KeepADBWidget.refreshAll(context);
        return success;
    }
}
