package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #957/#951: how an endpoint is written for people. One format string for the home screen, the
 * notification and the transport rows: "Port 5555 @ 192.168.0.5" ({@code endpoint_port_at_ip}).
 */
final class KeepADBEndpointText {
    private KeepADBEndpointText() {}

    /**
     * "Port &lt;port&gt; @ &lt;host&gt;". {@code displayHost} is what may be shown (already masked
     * where a privacy switch applies); an IPv6 literal is bracketed, as in the notification before.
     */
    static String display(Context context, String displayHost, int port) {
        String host = displayHost;
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return context.getString(R.string.endpoint_port_at_ip, port, host);
    }

    /** The command people paste at the computer; technical, so never translated. IPv6 is bracketed. */
    static String adbConnectCommand(String host, int port) {
        return "adb connect " + KeepADBEndpoint.formatEndpoint(host, port);
    }

    /**
     * Whether a copied endpoint is flagged sensitive: while "Hide in the app" is on (the switch, or
     * "Hide on this screen" of the eye menu), so the system's clipboard preview does not print what
     * the screen hides. Without it the clip is a plain one. A tap is an explicit user action, so
     * what is copied is always the real address, never the masked one.
     */
    static boolean isCopySensitive(Context context) {
        return KeepADBPreferences.isAppPrivacyEnabled(context) || KeepADBPrivacy.isAppMasked(context);
    }

    /**
     * Copies {@code adb connect <host>:<port>} (the real host, whatever the screen shows).
     *
     * @param source where the user asked for it: "app" or "notification" (diagnostics only; the
     *        event carries no address)
     * @return whether it is on the clipboard
     */
    static boolean copyAdbConnect(Context context, String host, int port, String source) {
        if (host == null || port <= 0) return false;
        KeepADBDiagnostics.event(context, "user_action", source, "copy", "adb_connect");
        return KeepADBClipboard.copy(context, ADB_CONNECT_LABEL, adbConnectCommand(host, port),
                isCopySensitive(context), R.string.onboarding_copy_done);
    }

    /** The label of the clip, as the system's clipboard preview names it. */
    static final String ADB_CONNECT_LABEL = "adb connect";
}
