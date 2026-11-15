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
}
