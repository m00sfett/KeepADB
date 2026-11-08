package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #912: what the control widget says and what each control does, as plain values computed from
 * the shared read-only projection ({@link KeepADBWidgetState}) and nothing else. No view, no
 * PendingIntent and no I/O here, so the wording and the click targets are testable on their own and
 * rendering them can neither send nor poll.
 *
 * <p>Every control carries a word for its state ("On", "Off", "Waiting", "Set up" ...) and a symbol
 * variant (filled, outlined, with dots, with an exclamation mark), so no state is told by color
 * alone. What a projection withholds (see {@link KeepADBWidgetState}: the widget
 * privacy switch and the per-widget option) never reaches a string here, and the webhook URL is not
 * part of the projection at all.
 */
final class KeepADBWidgetContent {

    /**
     * How a control is drawn: the fill and the symbol colors. The word and the symbol variant carry
     * the meaning, this only reinforces it.
     */
    enum Style {
        OFF, ON,
        /** Wireless Debugging while it looks for its port or switches. */
        BUSY,
        ATTENTION
    }

    /** What a tap does. The renderer maps each to a PendingIntent; none of them is decided there. */
    enum Click {
        /** Flip Wireless Debugging through the shared click semantics (permission, trust, debounce). */
        TOGGLE_ADB,
        TOGGLE_KEEP_ALIVE,
        SYNC_ENABLE,
        SYNC_DISABLE,
        /** The settings at the webhook form. */
        SETUP_WEBHOOK,
        /** The app's home screen: the setup, the status and the full reasons. */
        OPEN_APP,
        /** The settings at the network card, where a network is trusted or blocked. */
        OPEN_NETWORK
    }

    static final class Tile {
        final KeepADBWidgetConfig.Action action;
        final String label;
        /** The label of the smallest layout (about 1x1); equal to {@link #label} except for ADB. */
        final String shortLabel;
        final String state;
        /** A word short enough for the narrowest tiles ("On", "Off", "Scan..."). */
        final String shortState;
        /**
         * What the main control shows beside its state: the port (":38203"), five dots while the
         * details are withheld, or null (not connected, or not the Wireless Debugging control).
         */
        final String port;
        /** Spoken by TalkBack: the full name of the control, its state and, if shown, the port. */
        final String description;
        final Style style;
        final Click click;
        /** The symbol: a filled, outlined or marked variant per state. */
        final int iconRes;

        Tile(KeepADBWidgetConfig.Action action, String label, String shortLabel, String state,
                String shortState, String port, String description, Style style, Click click,
                int iconRes) {
            this.iconRes = iconRes;
            this.action = action;
            this.label = label;
            this.shortLabel = shortLabel;
            this.state = state;
            this.shortState = shortState;
            this.port = port;
            this.description = description;
            this.style = style;
            this.click = click;
        }
    }

    /**
     * The status line of the 4x2 widget: first the endpoint (when it may be shown), the words
     * "Details hidden" (while it is withheld) or the reason the state is what it is, in the home
     * screen's wording; then the webhook state. {@code click} leads to where the first actionable
     * reason is solved.
     */
    static final class Details {
        /** The verified {@code host:port}, "Details hidden" or the state/reason. */
        final String lead;
        /** True when {@link #lead} is data (the endpoint or the hidden notice), shown stronger. */
        final boolean leadIsData;
        final String webhook;
        final Click click;

        Details(String lead, boolean leadIsData, String webhook, Click click) {
            this.lead = lead;
            this.leadIsData = leadIsData;
            this.webhook = webhook;
            this.click = click;
        }

        /** What TalkBack reads for the line. */
        String description() {
            return lead + ". " + webhook;
        }
    }

    private KeepADBWidgetContent() {}

    /** The full name of a control, as the settings and TalkBack call it. */
    static String fullName(Context ctx, KeepADBWidgetConfig.Action action) {
        switch (action) {
            case KEEP_ALIVE:
                return ctx.getString(R.string.settings_section_keep_alive);
            case WEBHOOK_SYNC:
                return ctx.getString(R.string.widget_action_sync_description);
            case ADB:
            default:
                return ctx.getString(R.string.toggle_label);
        }
    }

    static Tile tile(Context ctx, KeepADBWidgetConfig.Action action, KeepADBWidgetState p) {
        switch (action) {
            case KEEP_ALIVE:
                return keepAliveTile(ctx, p);
            case WEBHOOK_SYNC:
                return syncTile(ctx, p);
            case ADB:
            default:
                return adbTile(ctx, p);
        }
    }

    private static Tile adbTile(Context ctx, KeepADBWidgetState p) {
        String state;
        String shortState;
        Style style;
        int icon;
        Click click = Click.TOGGLE_ADB;
        String on = ctx.getString(R.string.widget_short_on);
        String off = ctx.getString(R.string.widget_short_off);
        if (p.adb == KeepADBWidgetState.Adb.PERMISSION_FAILURE) {
            // The one-time grant is missing: a tap leads to the setup instead of a failing write.
            state = ctx.getString(R.string.tile_state_permission_missing);
            shortState = ctx.getString(R.string.widget_state_setup);
            style = Style.ATTENTION;
            icon = R.drawable.ic_widget_adb_off;
            click = Click.OPEN_APP;
        } else if (p.adb == KeepADBWidgetState.Adb.PENDING) {
            state = ctx.getString(R.string.state_pending);
            shortState = state;
            style = Style.BUSY;
            icon = R.drawable.ic_widget_adb_busy;
        } else if (p.systemState == KeepADB.State.ENABLED_CONNECTED) {
            state = ctx.getString(R.string.tile_state_connected);
            shortState = on;
            style = Style.ON;
            icon = R.drawable.ic_widget_adb;
        } else if (p.systemState == KeepADB.State.ENABLED_DISCONNECTED) {
            if (p.endpoint == KeepADBWidgetState.EndpointAvailability.SEARCHING) {
                state = ctx.getString(R.string.tile_state_searching);
                shortState = ctx.getString(R.string.widget_short_scan);
                style = Style.BUSY;
                icon = R.drawable.ic_widget_adb_busy;
            } else {
                state = ctx.getString(R.string.tile_state_disconnected);
                shortState = on;
                style = Style.ON;
                icon = R.drawable.ic_widget_adb;
            }
        } else if (p.systemState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            state = ctx.getString(R.string.widget_state_off_waiting);
            shortState = off;
            style = Style.OFF;
            icon = R.drawable.ic_widget_adb_off;
        } else {
            state = ctx.getString(R.string.tile_state_off);
            shortState = off;
            style = Style.OFF;
            icon = R.drawable.ic_widget_adb_off;
        }
        String fullName = ctx.getString(R.string.toggle_label);
        return build(ctx, KeepADBWidgetConfig.Action.ADB, ctx.getString(R.string.widget_action_adb),
                ctx.getString(R.string.widget_label_adb_short), fullName, state, shortState,
                portOf(p), style, click, icon, portNumber(p));
    }

    /**
     * The port the main control shows: ":38203" while connected and visible, five dots while the
     * widget privacy or the per-widget option withholds it (still connected), otherwise null.
     */
    private static String portOf(KeepADBWidgetState p) {
        if (endpointShown(p)) return ":" + p.endpointPort;
        if (p.detailsHidden && connected(p)) return "•••••";
        return null;
    }

    /** The port for TalkBack, only where it may be shown. */
    private static int portNumber(KeepADBWidgetState p) {
        return endpointShown(p) ? p.endpointPort : 0;
    }

    private static boolean connected(KeepADBWidgetState p) {
        return p.systemState == KeepADB.State.ENABLED_CONNECTED
                && p.adb != KeepADBWidgetState.Adb.PERMISSION_FAILURE
                && p.adb != KeepADBWidgetState.Adb.PENDING;
    }

    private static boolean endpointShown(KeepADBWidgetState p) {
        return connected(p) && p.endpointHost != null && p.endpointPort > 0;
    }

    private static Tile keepAliveTile(Context ctx, KeepADBWidgetState p) {
        String state;
        Style style;
        int icon = R.drawable.ic_widget_keep_alive;
        switch (p.keepAlive) {
            case ACTIVE:
                state = ctx.getString(R.string.widget_state_on);
                style = Style.ON;
                break;
            case WAITING:
                state = ctx.getString(R.string.widget_state_waiting);
                style = Style.ON;
                break;
            case BLOCKED:
                state = ctx.getString(R.string.widget_state_paused);
                style = Style.ATTENTION;
                icon = R.drawable.ic_widget_keep_alive_off;
                break;
            case DISABLED:
            default:
                state = ctx.getString(R.string.tile_state_off);
                style = Style.OFF;
                icon = R.drawable.ic_widget_keep_alive_off;
                break;
        }
        // The setting itself is flipped; the trust, Wi-Fi and backoff rules stay with the service.
        String name = ctx.getString(R.string.widget_action_keep_alive);
        return build(ctx, KeepADBWidgetConfig.Action.KEEP_ALIVE, name, name,
                ctx.getString(R.string.settings_section_keep_alive), state, state, null, style,
                Click.TOGGLE_KEEP_ALIVE, icon, 0);
    }

    private static Tile syncTile(Context ctx, KeepADBWidgetState p) {
        String state;
        Style style;
        Click click;
        int icon;
        switch (p.webhook.sync) {
            case ON:
                state = ctx.getString(R.string.widget_state_on);
                style = Style.ON;
                click = Click.SYNC_DISABLE;
                icon = R.drawable.ic_widget_sync;
                break;
            case OFF:
                state = ctx.getString(R.string.tile_state_off);
                style = Style.OFF;
                click = Click.SYNC_ENABLE;
                icon = R.drawable.ic_widget_sync_off;
                break;
            case UNCONFIGURED:
            default:
                state = ctx.getString(R.string.widget_state_setup);
                style = Style.ATTENTION;
                click = Click.SETUP_WEBHOOK;
                icon = R.drawable.ic_widget_sync_warn;
                break;
        }
        String name = ctx.getString(R.string.widget_action_sync);
        return build(ctx, KeepADBWidgetConfig.Action.WEBHOOK_SYNC, name, name,
                ctx.getString(R.string.widget_action_sync_description), state, state, null, style,
                click, icon, 0);
    }

    private static Tile build(Context ctx, KeepADBWidgetConfig.Action action, String label,
            String shortLabel, String fullName, String state, String shortState, String port,
            Style style, Click click, int iconRes, int portNumber) {
        String description = ctx.getString(R.string.widget_description_format, fullName, state);
        if (portNumber > 0) {
            description = ctx.getString(R.string.widget_description_port_format, description,
                    portNumber);
        }
        return new Tile(action, label, shortLabel, state, shortState, port, description, style,
                click, iconRes);
    }

    // -- details -------------------------------------------------------------------------------

    static Details details(Context ctx, KeepADBWidgetState p) {
        Click[] systemClick = {null};
        String lead;
        boolean data = true;
        if (endpointShown(p)) {
            lead = ctx.getString(R.string.tile_state_connected_format,
                    hostForDisplay(p.endpointHost), p.endpointPort);
        } else if (p.detailsHidden && connected(p)) {
            lead = ctx.getString(R.string.widget_status_details_hidden);
        } else {
            lead = systemLine(ctx, p, systemClick);
            data = false;
        }
        Click[] webhookClick = {null};
        String webhook = webhookLine(ctx, p, webhookClick);
        Click click = systemClick[0] != null ? systemClick[0]
                : webhookClick[0] != null ? webhookClick[0] : Click.OPEN_APP;
        return new Details(lead, data, webhook, click);
    }

    /** The home screen's own wording for the state, so the widget never says something else. */
    private static String systemLine(Context ctx, KeepADBWidgetState p, Click[] click) {
        if (p.adb == KeepADBWidgetState.Adb.PERMISSION_FAILURE) {
            click[0] = Click.OPEN_APP;
            return ctx.getString(R.string.status_permission_missing);
        }
        if (p.adb == KeepADBWidgetState.Adb.PENDING) {
            return ctx.getString(R.string.status_pending);
        }
        if (p.systemState == KeepADB.State.ENABLED_CONNECTED) {
            // Details withheld (#904): the state only, never the address.
            return ctx.getString(R.string.status_on);
        }
        if (p.systemState == KeepADB.State.ENABLED_DISCONNECTED) {
            return ctx.getString(R.string.status_enabled_disconnected);
        }
        switch (p.keepAlive) {
            case BLOCKED:
                if (p.keepAliveReason == KeepADBWidgetState.Reason.UNTRUSTED_NETWORK) {
                    click[0] = Click.OPEN_NETWORK;
                    return ctx.getString(R.string.status_off_keep_alive_blocked_untrusted);
                }
                if (p.keepAliveReason == KeepADBWidgetState.Reason.NETWORK_IDENTITY_UNAVAILABLE) {
                    click[0] = Click.OPEN_APP;
                    return ctx.getString(R.string.status_off_keep_alive_blocked_identity_unavailable);
                }
                // The last explicit intent was off: nothing to solve, the plain state is the answer.
                return ctx.getString(R.string.status_off);
            case WAITING:
                if (p.keepAliveReason == KeepADBWidgetState.Reason.RECOVERY_BACKOFF) {
                    click[0] = Click.OPEN_APP;
                    return ctx.getString(R.string.status_off_keep_alive_blocked_recovery_backoff);
                }
                return ctx.getString(R.string.status_off_keep_alive_waiting);
            case ACTIVE:
            case DISABLED:
            default:
                return ctx.getString(R.string.status_off);
        }
    }

    private static String webhookLine(Context ctx, KeepADBWidgetState p, Click[] click) {
        KeepADBWidgetState.Webhook w = p.webhook;
        if (w.sync == KeepADBWidgetState.Sync.UNCONFIGURED) {
            click[0] = Click.SETUP_WEBHOOK;
            return ctx.getString(R.string.widget_webhook_unconfigured);
        }
        if (w.sync == KeepADBWidgetState.Sync.OFF) {
            // Whatever an earlier report did: with synchronization off nothing is being synced, so
            // neither a failure nor a delivery is the state of the sync.
            return ctx.getString(R.string.widget_webhook_sync_off);
        }
        if (w.activity == KeepADBWidgetState.Activity.SENDING) {
            return ctx.getString(R.string.widget_webhook_sending);
        }
        if (w.activity == KeepADBWidgetState.Activity.RETRY_WAITING
                || w.latestOutcome == KeepADBWidgetState.Outcome.FAILED) {
            click[0] = Click.SETUP_WEBHOOK;
            return ctx.getString(R.string.notification_text_webhook_failed);
        }
        if (w.delivery == KeepADBWidgetState.Delivery.CURRENT) {
            return ctx.getString(R.string.widget_webhook_current);
        }
        if (w.delivery == KeepADBWidgetState.Delivery.UNKNOWN) {
            // Synchronization is on and a destination is saved, but there is no verified endpoint.
            return ctx.getString(R.string.widget_webhook_no_endpoint);
        }
        return ctx.getString(R.string.widget_webhook_not_current);
    }

    private static String hostForDisplay(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
