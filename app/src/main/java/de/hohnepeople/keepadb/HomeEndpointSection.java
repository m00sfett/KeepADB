package de.hohnepeople.keepadb;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * #1048: the endpoint row of the home screen and the verified extra transports below it (#538),
 * split out of {@link MainActivity}: the discovered endpoint through the privacy mask (#483), the
 * copy action (#951), and the generation tokens that keep a callback or an async snapshot for an
 * older listener or a paused screen from drawing (#538).
 */
final class HomeEndpointSection {
    private final Activity activity;
    private final Runnable refresh;
    private final View endpointRow;
    private final View endpointCopyIcon;
    private final TextView endpoint;
    /** #538: rows for verified transports beyond WLAN/LAN, filled by {@link #renderTransportOverview}. */
    private final ViewGroup transportOverviewPanel;
    private long listenerGeneration;
    private long transportOverviewGeneration;
    private boolean surfaceActive;
    // #483: last discovered endpoint, unmasked. Display text is derived from it on every render.
    private String lastHost;
    private int lastPort;
    /**
     * #951: a tap copies the newest endpoint (read when tapped), whatever the row showed. One
     * instance, attached while there is something to copy and detached otherwise (#1048 review).
     */
    private final View.OnClickListener copyEndpoint;
    /** #1048: one click label delegate for every copy row instead of one per render. */
    private final View.AccessibilityDelegate copyClickLabel = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    activity.getString(R.string.endpoint_copy_action)));
        }
    };

    HomeEndpointSection(Activity activity, Runnable refresh) {
        this.activity = activity;
        this.refresh = refresh;
        endpointRow = activity.findViewById(R.id.endpoint_row);
        endpointCopyIcon = activity.findViewById(R.id.endpoint_copy_icon);
        endpoint = activity.findViewById(R.id.endpoint);
        transportOverviewPanel = activity.findViewById(R.id.transport_overview_panel);
        copyEndpoint = v -> {
            if (lastHost != null) {
                KeepADBEndpointText.copyAdbConnect(activity, lastHost, lastPort, "app");
            }
        };
    }

    /** The screen is in front: starts a new listener generation and returns it. */
    long resume() {
        surfaceActive = true;
        return ++listenerGeneration;
    }

    /**
     * The screen left the front: callbacks of the current listener and an in-flight transport
     * snapshot must not draw any more.
     */
    void pause() {
        surfaceActive = false;
        listenerGeneration++;
        // #538: invalidates any in-flight renderTransportOverview() result, mirroring the
        // listener generation above.
        transportOverviewGeneration++;
    }

    /** Whether the screen is in front (for the warning focus after returning to it). */
    boolean isSurfaceActive() {
        return surfaceActive;
    }

    void postAvailable(long generation, String host, int port) {
        activity.runOnUiThread(() -> {
            if (!isActive(generation)) return;
            lastHost = host;
            lastPort = port;
            render();
            refresh.run();
        });
    }

    void postUnavailable(long generation) {
        activity.runOnUiThread(() -> {
            if (!isActive(generation)) return;
            lastHost = null;
            render();
            refresh.run();
        });
    }

    /** #791: without the permission nothing is known about the endpoint; a found one must go. */
    void forget() {
        lastHost = null;
        render();
    }

    /**
     * #483: renders the last discovered endpoint through the privacy mask. Kept separate from the
     * discovery callbacks so toggling privacy re-renders immediately instead of waiting for the
     * next discovery tick. Display only -- {@code lastHost} holds the real host.
     */
    void render() {
        if (lastHost == null) {
            // #582: an unreadable value reads as "unavailable", not "searching" -- displaying
            // "searching" would imply wireless debugging is confirmed on, which an unknown read
            // does not establish.
            Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(activity, "app");
            // #791: without the system permission nothing is being searched.
            boolean searching = adbEnabledOrNull != null && adbEnabledOrNull
                    && KeepADB.getState(activity) != KeepADB.State.PERMISSION_MISSING;
            endpoint.setText(searching
                    ? activity.getString(R.string.endpoint_searching)
                    : activity.getString(R.string.endpoint_unavailable));
            // #957: no address yet is a status, not a value: secondary color, normal size, and
            // nothing to copy (#951).
            endpoint.setTextColor(activity.getColor(R.color.night_muted));
            endpoint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            clearCopy();
            return;
        }
        endpoint.setText(KeepADBEndpointText.display(activity,
                KeepADBPrivacy.maskHostForDisplay(activity, lastHost), lastPort));
        // #957: the address is what people came for: primary color and larger.
        endpoint.setTextColor(activity.getColor(R.color.night_text));
        endpoint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bindCopy();
    }

    /**
     * #951: a tap on the row copies {@code adb connect <host>:<port>}. The real host is used
     * whatever the row shows (a tap is an explicit action); {@code lastHost} is read when tapped,
     * so a newer endpoint wins over the one the row was bound with.
     */
    private void bindCopy() {
        endpointCopyIcon.setVisibility(View.VISIBLE);
        endpointRow.setMinimumHeight(dp(48));
        endpointRow.setClickable(true);
        endpointRow.setFocusable(true);
        endpointRow.setOnClickListener(copyEndpoint);
        // TalkBack announces the row as what a tap does through the click action's label.
        endpointRow.setAccessibilityDelegate(copyClickLabel);
    }

    private void clearCopy() {
        endpointCopyIcon.setVisibility(View.GONE);
        endpointRow.setMinimumHeight(0);
        endpointRow.setOnClickListener(null);
        endpointRow.setClickable(false);
        endpointRow.setFocusable(false);
        endpointRow.setAccessibilityDelegate(null);
    }

    private int dp(int value) {
        return KeepADBUiKit.dp(activity, value);
    }

    private boolean isActive(long generation) {
        return surfaceActive && generation == listenerGeneration
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    /**
     * #538: kicks off a background {@link KeepADBTransportOverview} snapshot and applies it once
     * it lands, with the same generation-token pattern as the endpoint callbacks, so a snapshot
     * started for an older refresh (or a since-paused screen) never overwrites a newer one. Off
     * the main thread because {@link KeepADBVpnTransport#verifyAdbReachable} can perform a
     * blocking socket connect.
     */
    void renderTransportOverview() {
        final long token = ++transportOverviewGeneration;
        KeepADBTransportOverview.currentAsync(activity, snapshot -> activity.runOnUiThread(() -> {
            if (!isTransportOverviewActive(token)) return;
            applyTransportOverview(snapshot);
        }));
    }

    /** Like {@link #isActive}, against the independent transport-overview generation. */
    private boolean isTransportOverviewActive(long token) {
        return surfaceActive && token == transportOverviewGeneration
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    /**
     * One row per verified transport beyond WLAN/LAN (which the endpoint text already covers).
     * The panel stays hidden whenever there is nothing beyond the WLAN/LAN case, so the common
     * single-WLAN scenario renders as before #538. A merely active VPN interface produces no row:
     * this panel only speaks about transports whose ADB reachability was verified, and KeepADB
     * names no VPN product (#877). #548: a VPN row is a debug-only diagnostic.
     */
    private void applyTransportOverview(KeepADBTransportOverview.Snapshot snapshot) {
        transportOverviewPanel.removeAllViews();
        boolean wlanPrimary = false;
        boolean debugBuild = KeepADBBuildFlags.isDebugBuild(activity);
        for (KeepADBTransportEndpoint transport : snapshot.transports) {
            if (transport.type == KeepADBTransportEndpoint.Type.WLAN_LAN) {
                wlanPrimary = transport.primary;
                continue;
            }
            if (transport.type == KeepADBTransportEndpoint.Type.VPN && !debugBuild) {
                continue;
            }
            transportOverviewPanel.addView(buildTransportRow(transport));
        }
        transportOverviewPanel.setVisibility(
                transportOverviewPanel.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        updateEndpointPrimaryAccessibility(wlanPrimary);
    }

    /**
     * "<label>: <value> · last checked <time>", masked like the WLAN/LAN endpoint, with a
     * "primary transport" content description on the transport holding {@link
     * KeepADBTransportEndpoint#primary}.
     */
    private TextView buildTransportRow(KeepADBTransportEndpoint transport) {
        String label = activity.getString(R.string.transport_vpn_label);
        String value = KeepADBEndpointText.display(activity,
                KeepADBPrivacy.maskHostForDisplay(activity, transport.host), transport.port);
        DateFormat dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM,
                activity.getResources().getConfiguration().getLocales().get(0));
        String lastChecked = dateTimeFormat.format(new Date(transport.verifiedAtMs));
        String text = activity.getString(R.string.transport_row_format, label, value, lastChecked);
        TextView row = buildStatusRow(text);
        if (transport.primary) {
            row.setContentDescription(activity.getString(R.string.transport_primary_accessibility_format, text));
        }
        // #951: a verified transport row copies its adb connect command like the WLAN/LAN row.
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setMinHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setCompoundDrawablePadding(dp(8));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_copy, 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> KeepADBEndpointText.copyAdbConnect(activity, transport.host,
                transport.port, "app"));
        row.setAccessibilityDelegate(copyClickLabel);
        return row;
    }

    private TextView buildStatusRow(String text) {
        TextView row = new TextView(activity);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.setTextColor(activity.getColor(R.color.night_muted));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setText(text);
        return row;
    }

    /**
     * #538: marks the WLAN/LAN endpoint text as the primary transport for screen readers without
     * changing its visible text -- additive on top of whatever {@link #render()} already set.
     */
    private void updateEndpointPrimaryAccessibility(boolean wlanPrimary) {
        if (wlanPrimary) {
            endpoint.setContentDescription(
                    activity.getString(R.string.transport_primary_accessibility_format, endpoint.getText()));
        } else {
            endpoint.setContentDescription(null);
        }
    }
}
