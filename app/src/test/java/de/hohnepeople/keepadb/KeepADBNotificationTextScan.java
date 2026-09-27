package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.Notification;
import android.os.Bundle;

/**
 * #592 test helper: asserts that no user-visible text of a notification -- every CharSequence or
 * CharSequence[] in its extras (title, text, big text, sub text, info text, summary, text lines,
 * ...), the ticker and the action labels -- nor of its publicVersion mentions any of the given
 * values. Scanning all extras rather than a fixed key list keeps a detail moved into a field the
 * test did not think of (e.g. subText) from slipping through.
 *
 * <p>Not covered: custom RemoteViews content and intent extras of the action PendingIntents (the
 * latter are not rendered).
 */
final class KeepADBNotificationTextScan {
    private KeepADBNotificationTextScan() {}

    static void assertMentionsNone(Notification notification, String... values) {
        assertNotNull(notification);
        StringBuilder visible = new StringBuilder();
        collect(notification, visible);
        if (notification.publicVersion != null) collect(notification.publicVersion, visible);
        String all = visible.toString();
        for (String value : values) {
            assertFalse("Notification must not mention '" + value + "': " + all, all.contains(value));
        }
    }

    private static void collect(Notification notification, StringBuilder out) {
        if (notification.tickerText != null) out.append(notification.tickerText).append('\n');
        Bundle extras = notification.extras;
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof CharSequence) {
                    out.append(key).append('=').append(value).append('\n');
                } else if (value instanceof CharSequence[]) {
                    for (CharSequence line : (CharSequence[]) value) {
                        out.append(key).append('=').append(line).append('\n');
                    }
                }
            }
        }
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                out.append("action=").append(action.title).append('\n');
            }
        }
    }
}
