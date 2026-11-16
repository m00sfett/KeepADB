package de.hohnepeople.keepadb;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.widget.Toast;

/**
 * #951: the one place that puts text on the clipboard: the setup command of the assistant and the
 * {@code adb connect} command of the endpoint rows and the notification.
 */
final class KeepADBClipboard {
    /** The key of {@code ClipDescription.EXTRA_IS_SENSITIVE}, which exists from API 33. */
    static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";

    private KeepADBClipboard() {}

    /**
     * Copies {@code text}. {@code sensitive} flags the clip so the system's clipboard preview does
     * not print it. From Android 13 the system confirms a copy itself; below that a short toast
     * with {@code confirmationRes} does. A failure is a toast as well.
     *
     * @return whether the text is on the clipboard
     */
    static boolean copy(Context context, CharSequence label, CharSequence text, boolean sensitive,
                        int confirmationRes) {
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            ClipData clip = ClipData.newPlainText(label, text);
            if (sensitive) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(sensitiveKey(), true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            // From Android 13 the system confirms a copy itself.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, confirmationRes, Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (RuntimeException exception) {
            Toast.makeText(context, R.string.feedback_copy_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static String sensitiveKey() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? ClipDescription.EXTRA_IS_SENSITIVE : EXTRA_IS_SENSITIVE;
    }
}
