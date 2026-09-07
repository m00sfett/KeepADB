package de.hohnepeople.keepadb;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class KeepADBTileService extends TileService {

    private static final Object LISTENING_INSTANCE_LOCK = new Object();
    private static KeepADBTileService listeningInstance;
    private boolean listening;

    // Issue #267 (3): a brief Quick Settings panel open/close (e.g. an accidental swipe)
    // must not abort a discovery that is still in flight. onStopListening() therefore only
    // *schedules* the cancellation after this grace period; onStartListening() cancels the
    // pending cancellation if the panel is reopened before it fires. onDestroy() always
    // cancels immediately, since the service is actually going away there.
    private static final long STOP_LISTENING_CANCEL_GRACE_MS = 3000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingDiscoveryCancel;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    public void onStartListening() {
        cancelPendingDiscoveryCancel();
        registerListeningInstance(this);
        KeepADBNotification.refreshForTile(this, this);
        updateTile();
    }

    @Override
    public void onStopListening() {
        discardListeningInstance(this);
        schedulePendingDiscoveryCancel();
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        discardListeningInstance(this);
        cancelPendingDiscoveryCancel();
        KeepADBNotification.cancelTileDiscovery(this);
        super.onDestroy();
    }

    @Override
    public void onClick() {
        KeepADB.State state = KeepADB.getState(this);
        if (state == KeepADB.State.ENABLED_DISCONNECTED) {
            // Issue #267 (2): WLAN-ADB is already on (or Keep-Alive is waiting to turn it back
            // on) but no endpoint is known yet. A tap here must trigger a fresh discovery /
            // reconnect attempt, not read as "currently off" and switch WLAN-ADB off.
            KeepADBDiagnostics.event(this, "user_action", "tile", "reconnect", "tap");
            if (!KeepADB.isEnabled(this)) {
                // WLAN-ADB itself is actually off here -- Keep-Alive is only waiting for its own
                // timer/observer to turn it back on. KeepADBNotification.refreshForTile() is a
                // no-op in this case (refreshInternal() just calls stop() while disabled), so a
                // tap would otherwise do nothing. Force the reconnect immediately instead, the
                // same way the keep-alive check and the "re-enable" button in MainActivity do.
                if (!KeepADB.setEnabled(this, true, "tile")) {
                    Toast.makeText(this, getString(R.string.tile_permission_error),
                            Toast.LENGTH_LONG).show();
                }
            } else {
                KeepADBNotification.refreshForTile(this, this);
            }
            updateTile();
            return;
        }
        boolean want = (state == KeepADB.State.OFF);
        KeepADBDiagnostics.event(this, "user_action", "tile", want ? "enable" : "disable", "tap");
        if (!KeepADB.setEnabled(this, want, "tile")) {
            Toast.makeText(this, getString(R.string.tile_permission_error),
                    Toast.LENGTH_LONG).show();
        }
        KeepADBService.sync(this);
        updateTile();
        KeepADBWidget.refreshAll(this);
        KeepADBNotification.refresh(this);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        KeepADB.State state = KeepADB.getState(this);
        tile.setLabel(getString(R.string.tile_label));
        switch (state) {
            case PERMISSION_MISSING:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setSubtitle(getString(R.string.tile_state_permission_missing));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_keepadb_disconnected));
                break;
            case OFF:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.tile_state_off));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_keepadb));
                break;
            case ENABLED_DISCONNECTED:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(isSearchingForEndpoint()
                        ? R.string.tile_state_searching : R.string.tile_state_disconnected));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_keepadb_disconnected));
                break;
            case ENABLED_CONNECTED:
                tile.setState(Tile.STATE_ACTIVE);
                String host = KeepADBNotification.getCurrentHost();
                int port = KeepADBNotification.getCurrentPort();
                if (host != null && port > 0) {
                    tile.setSubtitle(getString(R.string.tile_state_connected_format, host, port));
                } else {
                    tile.setSubtitle(getString(R.string.tile_state_connected));
                }
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_keepadb));
                break;
        }
        tile.updateTile();
    }

    /**
     * Issue #267 (1): {@code ENABLED_DISCONNECTED} is reached for two different underlying
     * reasons (see {@link KeepADB#getState}) -- only one of them is a genuine dead end:
     * <ul>
     *     <li>WLAN-ADB is off but Keep-Alive is still enabled and waiting to turn it back on, or
     *         WLAN-ADB is on and Wi-Fi is connected but mDNS discovery has not found an endpoint
     *         yet -- both are transitional and should read as "searching", not "disconnected".</li>
     *     <li>WLAN-ADB is on but there is no Wi-Fi connection at all -- nothing is in flight, so
     *         "disconnected" remains accurate.</li>
     * </ul>
     */
    private boolean isSearchingForEndpoint() {
        if (!KeepADB.isEnabled(this)) {
            return true;
        }
        return KeepADBService.isWifiConnected(this);
    }

    private void schedulePendingDiscoveryCancel() {
        cancelPendingDiscoveryCancel();
        pendingDiscoveryCancel = () -> {
            pendingDiscoveryCancel = null;
            KeepADBNotification.cancelTileDiscovery(this);
        };
        handler.postDelayed(pendingDiscoveryCancel, STOP_LISTENING_CANCEL_GRACE_MS);
    }

    private void cancelPendingDiscoveryCancel() {
        if (pendingDiscoveryCancel != null) {
            handler.removeCallbacks(pendingDiscoveryCancel);
            pendingDiscoveryCancel = null;
        }
    }

    /** Refreshes the only tile instance whose QS tile is valid at this moment. */
    static void refreshListeningTile() {
        synchronized (LISTENING_INSTANCE_LOCK) {
            KeepADBTileService instance = listeningInstance;
            if (instance == null || !instance.listening) return;
            try {
                instance.updateTile();
            } catch (RuntimeException ignored) {
                // The system may invalidate a tile while an asynchronous callback is in flight.
            }
        }
    }

    static void requestRefresh(Context context) {
        if (context == null) return;
        refreshListeningTile();
    }

    private static void registerListeningInstance(KeepADBTileService instance) {
        synchronized (LISTENING_INSTANCE_LOCK) {
            if (listeningInstance != null && listeningInstance != instance) {
                listeningInstance.listening = false;
            }
            listeningInstance = instance;
            instance.listening = true;
        }
    }

    private static void discardListeningInstance(KeepADBTileService instance) {
        synchronized (LISTENING_INSTANCE_LOCK) {
            instance.listening = false;
            if (listeningInstance == instance) {
                listeningInstance = null;
            }
        }
    }
}
