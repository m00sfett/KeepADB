package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #912: what the control widget says and what each control does, as plain values computed from
 * the shared read-only projection ({@link KeepADBWidgetState}) and nothing else. No view, no
 * PendingIntent and no I/O here, so the wording and the click targets are testable on their own and
 * rendering them can neither send nor poll.
 *
 * <p>Every tile carries a word for its state ("On", "Off", "Waiting", "Set up" ...), so no state is
 * told by color alone. What a projection withholds (see {@link KeepADBWidgetState}: the widget
 * privacy switch and the per-widget option) never reaches a string here, and the webhook URL is not
 * part of the projection at all.
 */
final class KeepADBWidgetContent {

    /** How a tile is drawn; the word on it carries the meaning, this only reinforces it. */
    enum Style {
        OFF, ON, ATTENTION
    }

    /** What a tap does. The renderer maps each to a PendingIntent; none of them is decided there. */
    enum Click {
        /** Flip Wireless Debugging through the shared click semantics (permission, trust, debounce). */
        TOGGLE_ADB,
        TOGGLE_KEEP_ALIVE,
        SYNC_ENABLE,
        SYNC_DISABLE,
        REPORT_NOW,
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
        final String state;
        /** Spoken by TalkBack: the full name of the control and its state. */
        final String description;
        final Style style;
        final Click click;
        /** The drawable of the control; the same whatever the state, the words carry the state. */
        final int iconRes;

        Tile(KeepADBWidgetConfig.Action action, String label, String state, String description,
                Style style, Click click, int iconRes) {
            this.iconRes = iconRes;
            this.action = action;
            this.label = label;
            this.state = state;
            this.description = description;
            this.style = style;
            this.click = click;
        }
    }

    /**
     * The status box below the tiles. The first line is the endpoint (when it may be shown) or,
     * without it, {@code system}: the reason the state is what it is, in the home screen's wording.
     * {@code click} leads to where the first actionable reason is solved.
     */
    static final class Details {
        /** The verified endpoint, or null while it is unknown or withheld. */
        final String endpoint;
        /** The state or the reason, or null while the endpoint line stands in for it. */
        final String system;
        final String webhook;
        final Click click;

        Details(String endpoint, String system, String webhook, Click click) {
            this.endpoint = endpoint;
            this.system = system;
            this.webhook = webhook;
            this.click = click;
        }

        /** What TalkBack reads for the box. */
        String description() {
            return (endpoint != null ? endpoint : system) + ". " + webhook;
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
            case REPORT_NOW:
                return ctx.getString(R.string.webhook_alert_action_report_now);
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
            case REPORT_NOW:
                return reportTile(ctx, p);
            case ADB:
            default:
                return adbTile(ctx, p);
        }
    }

    private static Tile adbTile(Context ctx, KeepADBWidgetState p) {
        String state;
        Style style;
        Click click = Click.TOGGLE_ADB;
        if (p.adb == KeepADBWidgetState.Adb.PERMISSION_FAILURE) {
            // The one-time grant is missing: a tap leads to the setup instead of a failing write.
            state = ctx.getString(R.string.tile_state_permission_missing);
            style = Style.ATTENTION;
            click = Click.OPEN_APP;
        } else if (p.adb == KeepADBWidgetState.Adb.PENDING) {
            state = ctx.getString(R.string.state_pending);
            style = Style.OFF;
        } else if (p.systemState == KeepADB.State.ENABLED_CONNECTED) {
            // One word per tile: a port in a 60 dp tile would be cut or broken. The endpoint has
            // its own line in the status box of the larger sizes.
            state = ctx.getString(R.string.tile_state_connected);
            style = Style.ON;
        } else if (p.systemState == KeepADB.State.ENABLED_DISCONNECTED) {
            state = ctx.getString(p.endpoint == KeepADBWidgetState.EndpointAvailability.SEARCHING
                    ? R.string.tile_state_searching : R.string.tile_state_disconnected);
            style = Style.ON;
        } else if (p.systemState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            state = ctx.getString(R.string.widget_state_off_waiting);
            style = Style.OFF;
        } else {
            state = ctx.getString(R.string.tile_state_off);
            style = Style.OFF;
        }
        return build(ctx, KeepADBWidgetConfig.Action.ADB, ctx.getString(R.string.widget_action_adb),
                ctx.getString(R.string.toggle_label), state, style, click, R.drawable.ic_widget_adb);
    }

    private static Tile keepAliveTile(Context ctx, KeepADBWidgetState p) {
        String state;
        Style style;
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
                break;
            case DISABLED:
            default:
                state = ctx.getString(R.string.tile_state_off);
                style = Style.OFF;
                break;
        }
        // The setting itself is flipped; the trust, Wi-Fi and backoff rules stay with the service.
        return build(ctx, KeepADBWidgetConfig.Action.KEEP_ALIVE,
                ctx.getString(R.string.widget_action_keep_alive),
                ctx.getString(R.string.settings_section_keep_alive), state, style,
                Click.TOGGLE_KEEP_ALIVE, R.drawable.ic_widget_keep_alive);
    }

    private static Tile syncTile(Context ctx, KeepADBWidgetState p) {
        String state;
        Style style;
        Click click;
        switch (p.webhook.sync) {
            case ON:
                state = ctx.getString(R.string.widget_state_on);
                style = Style.ON;
                click = Click.SYNC_DISABLE;
                break;
            case OFF:
                state = ctx.getString(R.string.tile_state_off);
                style = Style.OFF;
                click = Click.SYNC_ENABLE;
                break;
            case UNCONFIGURED:
            default:
                state = ctx.getString(R.string.widget_state_setup);
                style = Style.ATTENTION;
                click = Click.SETUP_WEBHOOK;
                break;
        }
        return build(ctx, KeepADBWidgetConfig.Action.WEBHOOK_SYNC,
                ctx.getString(R.string.widget_action_sync),
                ctx.getString(R.string.widget_action_sync_description), state, style, click,
                R.drawable.ic_widget_sync);
    }

    private static Tile reportTile(Context ctx, KeepADBWidgetState p) {
        KeepADBWidgetState.Webhook w = p.webhook;
        Click click = Click.REPORT_NOW;
        String state;
        Style style;
        switch (w.reportNow) {
            case NEEDS_SETUP:
                state = ctx.getString(R.string.widget_state_setup);
                style = Style.ATTENTION;
                click = Click.SETUP_WEBHOOK;
                break;
            case NO_ENDPOINT:
                // A report would send nothing; the app shows why.
                state = ctx.getString(R.string.widget_state_no_endpoint);
                style = Style.ATTENTION;
                click = Click.OPEN_APP;
                break;
            case SYNC_OFF:
                // Not reached by the widget: with synchronization off the Report now control takes
                // no place at all (KeepADBWidgetViews). Treated like "ready" so the tile stays valid.
            case READY:
            default:
                if (w.activity == KeepADBWidgetState.Activity.SENDING) {
                    state = ctx.getString(R.string.widget_state_sending);
                    style = Style.OFF;
                } else if (w.activity == KeepADBWidgetState.Activity.RETRY_WAITING
                        || w.latestOutcome == KeepADBWidgetState.Outcome.FAILED) {
                    state = ctx.getString(R.string.widget_state_failed);
                    style = Style.ATTENTION;
                } else if (w.delivery == KeepADBWidgetState.Delivery.CURRENT) {
                    // Only a report that names the endpoint and destination that are current now.
                    state = ctx.getString(R.string.widget_state_sent);
                    style = Style.ON;
                } else {
                    state = ctx.getString(R.string.widget_state_ready);
                    style = Style.OFF;
                }
                break;
        }
        String name = ctx.getString(R.string.webhook_alert_action_report_now);
        return build(ctx, KeepADBWidgetConfig.Action.REPORT_NOW, name, name, state, style, click,
                R.drawable.ic_widget_report);
    }

    private static Tile build(Context ctx, KeepADBWidgetConfig.Action action, String label,
            String fullName, String state, Style style, Click click, int iconRes) {
        return new Tile(action, label, state,
                ctx.getString(R.string.widget_description_format, fullName, state), style, click,
                iconRes);
    }

    // -- details -------------------------------------------------------------------------------

    /**
     * @param compactEndpoint true for the narrow status box: "host:port" without the word, so the
     *         port stays on the line
     */
    static Details details(Context ctx, KeepADBWidgetState p, boolean compactEndpoint) {
        Click[] systemClick = {null};
        boolean endpointShown = p.systemState == KeepADB.State.ENABLED_CONNECTED
                && p.adb != KeepADBWidgetState.Adb.PERMISSION_FAILURE
                && p.adb != KeepADBWidgetState.Adb.PENDING
                && p.endpointHost != null && p.endpointPort > 0;
        String endpoint = null;
        String system = null;
        if (endpointShown) {
            String host = hostForDisplay(p.endpointHost);
            endpoint = compactEndpoint
                    ? ctx.getString(R.string.tile_state_connected_format, host, p.endpointPort)
                    : ctx.getString(R.string.endpoint_format, host, p.endpointPort);
        } else {
            system = systemLine(ctx, p, systemClick);
        }
        Click[] webhookClick = {null};
        String webhook = webhookLine(ctx, p, webhookClick);
        Click click = systemClick[0] != null ? systemClick[0]
                : webhookClick[0] != null ? webhookClick[0] : Click.OPEN_APP;
        return new Details(endpoint, system, webhook, click);
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
        if (w.reportNow == KeepADBWidgetState.ReportNow.NO_ENDPOINT) {
            return ctx.getString(R.string.widget_webhook_no_endpoint);
        }
        return ctx.getString(R.string.widget_webhook_not_current);
    }

    private static String hostForDisplay(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
