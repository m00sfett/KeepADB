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
        // THE ONE DELIBERATE EXCEPTION to the shared click semantics of #318, kept by explicit
        // user decision on issue #318 (comment 5605497911) so the behavior introduced by #267
        // survives. Scope of the exception: this surface, this state, nothing else.
        //
        // Why the tile and not the other two: the tile is the surface a user reaches *while*
        // waiting for an endpoint, and #267 established that a tap there must retry discovery
        // rather than switch WLAN-ADB off. MainActivity's switch and the widget stay on the
        // uniform semantics -- a switch that refuses to switch off would be worse than the
        // inconsistency #318 set out to remove.
        //
        // Note what is *not* special-cased here any more: since #318 split the enum, reaching
        // ENABLED_DISCONNECTED proves adb_wifi_enabled == 1, so refreshForTile() genuinely
        // retriggers discovery instead of being the no-op that #267's follow-up review found.
        // The "WLAN-ADB actually off, Keep-Alive merely waiting" sub-case is now
        // OFF_KEEP_ALIVE_WAITING and is handled by desiredOnForClick() as a plain enable, on
        // all three surfaces alike.
        if (state == KeepADB.State.ENABLED_DISCONNECTED) {
            KeepADBDiagnostics.event(this, "user_action", "tile", "reconnect", "tap");
            KeepADBNotification.refreshForTile(this, this);
            updateTile();
            return;
        }
        // Everything below is the shared definition, identical to MainActivity and the widget.
        boolean want = KeepADB.desiredOnForClick(state);
        KeepADBDiagnostics.event(this, "user_action", "tile", want ? "enable" : "disable", "tap");
        if (!KeepADB.setEnabled(this, want, "tile")) {
            showToggleErrorToast();
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
            case OFF_KEEP_ALIVE_WAITING:
                // #318: off is off. The tile stays INACTIVE like any other off state; only the
                // subtitle says that Keep-Alive will switch it back on by itself.
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.tile_state_keep_alive_waiting));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_keepadb_disconnected));
                break;
            case ENABLED_DISCONNECTED:
                // #318: adb_wifi_enabled is 1 here, so the tile reads ACTIVE. It used to render
                // INACTIVE, which is what made a tap that disables wireless debugging feel wrong.
                tile.setState(Tile.STATE_ACTIVE);
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
        // #318: a scheduled but not yet written toggle overrides the subtitle, so the debounce
        // window is visible instead of silently showing the old value.
        if (state != KeepADB.State.PERMISSION_MISSING && KeepADB.isTogglePending()) {
            tile.setSubtitle(getString(R.string.state_pending));
        }
        tile.updateTile();
    }

    /**
     * #318: distinguishes a missing permission from a Settings.Global write that was rejected
     * despite the permission being granted, instead of blaming setup for both.
     */
    private void showToggleErrorToast() {
        Toast.makeText(this, getString(KeepADB.hasPermission(this)
                        ? R.string.toggle_failed_toast : R.string.tile_permission_error),
                Toast.LENGTH_LONG).show();
    }

    /**
     * Issue #267 (1): tells the two {@code ENABLED_DISCONNECTED} sub-cases apart -- only one is a
     * genuine dead end:
     * <ul>
     *     <li>WLAN-ADB is on and Wi-Fi is connected, but mDNS discovery has not found an endpoint
     *         yet -- transitional, so it should read as "searching", not "disconnected".</li>
     *     <li>WLAN-ADB is on but there is no Wi-Fi connection at all -- nothing is in flight, so
     *         "disconnected" remains accurate.</li>
     * </ul>
     *
     * <p>#318 moved the third former sub-case (WLAN-ADB off, Keep-Alive waiting) out of
     * {@code ENABLED_DISCONNECTED} into {@code OFF_KEEP_ALIVE_WAITING}, which has its own subtitle.
     * The {@code !KeepADB.isEnabled(this)} check below is kept as a defensive fallback: state and
     * this helper read {@code adb_wifi_enabled} at two different moments, so the setting can flip
     * in between, and "searching" is the better reading of that race than "disconnected".
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
