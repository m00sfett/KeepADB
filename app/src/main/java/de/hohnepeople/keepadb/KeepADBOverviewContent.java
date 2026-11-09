package de.hohnepeople.keepadb;

import android.content.Context;
import android.text.format.DateUtils;

/**
 * #913: what the overview widget says, as plain values computed from the shared read-only
 * projection ({@link KeepADBWidgetState}) and nothing else. No view, no PendingIntent and no I/O,
 * so the wording and the tap targets are testable on their own.
 *
 * <p><b>Informational rows.</b> Wireless Debugging, Wi-Fi and Keep-Alive are read-only here: the
 * whole information card has one tap target, and it only ever opens the app (or, for an untrusted
 * network, the network card of the settings). No value here can toggle Wireless Debugging or
 * Keep-Alive. The one control is the automatic webhook synchronization, the same tile and action
 * path as the control widget's ({@link KeepADBWidgetContent#tile}).
 *
 * <p><b>Delivery wording.</b> The current-endpoint claim comes only from {@link
 * KeepADBWidgetState.Webhook#delivery}; the time of the last accepted report is shown separately as
 * history ("Last delivery 14:03") and never makes the current endpoint read as reported.
 *
 * <p><b>Privacy.</b> What the projection withholds (address, port, network name) never reaches a
 * string here; the webhook URL is not part of the projection at all.
 */
final class KeepADBOverviewContent {

    /** Masks a withheld value; the same five dots as the control widget's port. */
    static final String HIDDEN = "•••••";

    /** Joins a state and its detail ("Waiting · no Wi-Fi"); a symbol, not a word to translate. */
    static String withDetail(String state, String detail) {
        return state + " · " + detail;
    }

    /** One informational row: a value and the full sentence TalkBack reads for it. */
    static final class Row {
        final String label;
        final String value;
        final KeepADBWidgetContent.Style style;
        final int iconRes;

        Row(String label, String value, KeepADBWidgetContent.Style style, int iconRes) {
            this.label = label;
            this.value = value;
            this.style = style;
            this.iconRes = iconRes;
        }

        String description(Context ctx) {
            return ctx.getString(R.string.widget_description_format, label, value);
        }
    }

    final Row adb;
    final Row wifi;
    final Row keepAlive;
    final Row delivery;
    /** "Last delivery 14:03" or "No delivery yet": history, never the current claim. */
    final String lastDelivery;
    /** Where a tap on the information card leads; never a toggle. */
    final KeepADBWidgetContent.Click cardClick;
    /** Where a tap on the delivery block leads: the webhook form to solve it, else the app. */
    final KeepADBWidgetContent.Click deliveryClick;
    /** The automatic synchronization switch, shared with the control widget. */
    final KeepADBWidgetContent.Tile sync;

    private KeepADBOverviewContent(Row adb, Row wifi, Row keepAlive, Row delivery,
            String lastDelivery, KeepADBWidgetContent.Click cardClick,
            KeepADBWidgetContent.Click deliveryClick, KeepADBWidgetContent.Tile sync) {
        this.adb = adb;
        this.wifi = wifi;
        this.keepAlive = keepAlive;
        this.delivery = delivery;
        this.lastDelivery = lastDelivery;
        this.cardClick = cardClick;
        this.deliveryClick = deliveryClick;
        this.sync = sync;
    }

    /** What TalkBack reads for the information card: every row, in reading order. */
    String cardDescription(Context ctx) {
        return adb.description(ctx) + ". " + wifi.description(ctx) + ". "
                + keepAlive.description(ctx);
    }

    /** What TalkBack reads for the delivery block. */
    String deliveryDescription(Context ctx) {
        return delivery.description(ctx) + ". " + lastDelivery;
    }

    static KeepADBOverviewContent of(Context ctx, KeepADBWidgetState p) {
        return new KeepADBOverviewContent(adbRow(ctx, p), wifiRow(ctx, p), keepAliveRow(ctx, p),
                deliveryRow(ctx, p), lastDelivery(ctx, p), cardClick(p), deliveryClick(p),
                KeepADBWidgetContent.tile(ctx, KeepADBWidgetConfig.Action.WEBHOOK_SYNC, p));
    }

    // -- rows ------------------------------------------------------------------------------------

    private static Row adbRow(Context ctx, KeepADBWidgetState p) {
        String label = ctx.getString(R.string.widget_action_adb);
        if (p.adb == KeepADBWidgetState.Adb.PERMISSION_FAILURE) {
            return new Row(label, ctx.getString(R.string.tile_state_permission_missing),
                    KeepADBWidgetContent.Style.ATTENTION, R.drawable.ic_widget_adb_off);
        }
        if (p.adb == KeepADBWidgetState.Adb.PENDING) {
            return new Row(label, ctx.getString(R.string.state_pending),
                    KeepADBWidgetContent.Style.BUSY, R.drawable.ic_widget_adb_busy);
        }
        if (p.systemState == KeepADB.State.ENABLED_CONNECTED) {
            String value = p.endpointHost != null && p.endpointPort > 0
                    ? ctx.getString(R.string.tile_state_connected_format,
                            hostForDisplay(p.endpointHost), p.endpointPort)
                    : p.detailsHidden
                            ? withDetail(ctx.getString(R.string.tile_state_connected), HIDDEN)
                            : ctx.getString(R.string.tile_state_connected);
            return new Row(label, value, KeepADBWidgetContent.Style.ON, R.drawable.ic_widget_adb);
        }
        if (p.systemState == KeepADB.State.ENABLED_DISCONNECTED) {
            if (p.endpoint == KeepADBWidgetState.EndpointAvailability.SEARCHING) {
                return new Row(label, ctx.getString(R.string.tile_state_searching),
                        KeepADBWidgetContent.Style.BUSY, R.drawable.ic_widget_adb_busy);
            }
            return new Row(label, ctx.getString(R.string.tile_state_disconnected),
                    KeepADBWidgetContent.Style.ON, R.drawable.ic_widget_adb);
        }
        return new Row(label, ctx.getString(R.string.tile_state_off),
                KeepADBWidgetContent.Style.OFF, R.drawable.ic_widget_adb_off);
    }

    private static Row wifiRow(Context ctx, KeepADBWidgetState p) {
        String label = ctx.getString(R.string.overview_label_wifi);
        if (!p.wifiConnected) {
            return new Row(label, ctx.getString(R.string.overview_wifi_none),
                    KeepADBWidgetContent.Style.OFF, R.drawable.ic_widget_wifi_off);
        }
        String value;
        if (p.detailsHidden) {
            value = HIDDEN;
        } else if (p.wifiName == null || p.wifiName.trim().isEmpty()) {
            value = ctx.getString(R.string.overview_wifi_name_unavailable);
        } else {
            value = p.wifiName;
        }
        return new Row(label, value, KeepADBWidgetContent.Style.ON, R.drawable.ic_widget_wifi);
    }

    private static Row keepAliveRow(Context ctx, KeepADBWidgetState p) {
        String label = ctx.getString(R.string.widget_action_keep_alive);
        switch (p.keepAlive) {
            case ACTIVE:
                return new Row(label, ctx.getString(R.string.widget_state_on),
                        KeepADBWidgetContent.Style.ON, R.drawable.ic_widget_keep_alive);
            case WAITING:
                return new Row(label, withReason(ctx, R.string.widget_state_waiting,
                        p.keepAliveReason), KeepADBWidgetContent.Style.ON,
                        R.drawable.ic_widget_keep_alive);
            case BLOCKED:
                return new Row(label, withReason(ctx, R.string.widget_state_paused,
                        p.keepAliveReason), KeepADBWidgetContent.Style.ATTENTION,
                        R.drawable.ic_widget_keep_alive_off);
            case DISABLED:
            default:
                return new Row(label, ctx.getString(R.string.tile_state_off),
                        KeepADBWidgetContent.Style.OFF, R.drawable.ic_widget_keep_alive_off);
        }
    }

    private static String withReason(Context ctx, int stateRes, KeepADBWidgetState.Reason reason) {
        String state = ctx.getString(stateRes);
        int reasonRes = reasonText(reason);
        return reasonRes == 0 ? state
                : withDetail(state, ctx.getString(reasonRes));
    }

    /** A short reason, or 0 when there is none to name. */
    static int reasonText(KeepADBWidgetState.Reason reason) {
        switch (reason) {
            case PERMISSION_NOT_GRANTED:
            case SETTING_UNREADABLE:
                return R.string.overview_reason_permission;
            case MANUAL_OFF:
                return R.string.overview_reason_manual_off;
            case NO_WIFI:
                return R.string.overview_reason_no_wifi;
            case UNTRUSTED_NETWORK:
                return R.string.overview_reason_untrusted;
            case NETWORK_IDENTITY_UNAVAILABLE:
                return R.string.overview_reason_identity;
            case RECOVERY_BACKOFF:
                return R.string.overview_reason_backoff;
            case NONE:
            default:
                return 0;
        }
    }

    /**
     * The delivery state in the same precedence as the control widget's status line: not set up,
     * sync off, sending, failure, then the current-endpoint claim from the projection.
     */
    private static Row deliveryRow(Context ctx, KeepADBWidgetState p) {
        String label = ctx.getString(R.string.overview_label_webhook);
        KeepADBWidgetState.Webhook w = p.webhook;
        if (w.sync == KeepADBWidgetState.Sync.UNCONFIGURED) {
            return new Row(label, ctx.getString(R.string.overview_delivery_unconfigured),
                    KeepADBWidgetContent.Style.ATTENTION, R.drawable.ic_widget_sync_warn);
        }
        if (w.sync == KeepADBWidgetState.Sync.OFF) {
            return new Row(label, ctx.getString(R.string.overview_delivery_sync_off),
                    KeepADBWidgetContent.Style.OFF, R.drawable.ic_widget_sync_off);
        }
        if (w.activity == KeepADBWidgetState.Activity.SENDING) {
            return new Row(label, ctx.getString(R.string.overview_delivery_sending),
                    KeepADBWidgetContent.Style.BUSY, R.drawable.ic_widget_sync);
        }
        if (w.activity == KeepADBWidgetState.Activity.RETRY_WAITING
                || w.latestOutcome == KeepADBWidgetState.Outcome.FAILED) {
            return new Row(label, ctx.getString(R.string.overview_delivery_failed),
                    KeepADBWidgetContent.Style.ATTENTION, R.drawable.ic_widget_sync_warn);
        }
        if (w.delivery == KeepADBWidgetState.Delivery.CURRENT) {
            return new Row(label, ctx.getString(R.string.overview_delivery_current),
                    KeepADBWidgetContent.Style.ON, R.drawable.ic_widget_sync);
        }
        if (w.delivery == KeepADBWidgetState.Delivery.UNKNOWN) {
            return new Row(label, ctx.getString(R.string.overview_delivery_no_endpoint),
                    KeepADBWidgetContent.Style.OFF, R.drawable.ic_widget_sync);
        }
        return new Row(label, ctx.getString(R.string.overview_delivery_not_current),
                KeepADBWidgetContent.Style.OFF, R.drawable.ic_widget_sync);
    }

    private static String lastDelivery(Context ctx, KeepADBWidgetState p) {
        long at = p.webhook.lastSuccessAtMs;
        if (at <= 0) return ctx.getString(R.string.overview_last_delivery_none);
        return ctx.getString(R.string.overview_last_delivery,
                DateUtils.formatSameDayTime(at, System.currentTimeMillis(),
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString());
    }

    /** Where the information card leads: the network card for a trust decision, else the app. */
    private static KeepADBWidgetContent.Click cardClick(KeepADBWidgetState p) {
        return p.keepAliveReason == KeepADBWidgetState.Reason.UNTRUSTED_NETWORK
                ? KeepADBWidgetContent.Click.OPEN_NETWORK : KeepADBWidgetContent.Click.OPEN_APP;
    }

    /** Not set up or failing: the webhook form is where it is solved. Otherwise the app. */
    private static KeepADBWidgetContent.Click deliveryClick(KeepADBWidgetState p) {
        KeepADBWidgetState.Webhook w = p.webhook;
        boolean solvable = w.sync == KeepADBWidgetState.Sync.UNCONFIGURED
                || (w.sync == KeepADBWidgetState.Sync.ON
                        && w.activity != KeepADBWidgetState.Activity.SENDING
                        && (w.activity == KeepADBWidgetState.Activity.RETRY_WAITING
                                || w.latestOutcome == KeepADBWidgetState.Outcome.FAILED));
        return solvable ? KeepADBWidgetContent.Click.SETUP_WEBHOOK
                : KeepADBWidgetContent.Click.OPEN_APP;
    }

    private static String hostForDisplay(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
