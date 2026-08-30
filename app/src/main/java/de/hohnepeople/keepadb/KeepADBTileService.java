package de.hohnepeople.keepadb;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class KeepADBTileService extends TileService {

    private static final Object LISTENING_INSTANCE_LOCK = new Object();
    private static KeepADBTileService listeningInstance;
    private boolean listening;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    public void onStartListening() {
        registerListeningInstance(this);
        KeepADBNotification.refreshForTile(this, this);
        updateTile();
    }

    @Override
    public void onStopListening() {
        discardListeningInstance(this);
        KeepADBNotification.cancelTileDiscovery(this);
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        discardListeningInstance(this);
        KeepADBNotification.cancelTileDiscovery(this);
        super.onDestroy();
    }

    @Override
    public void onClick() {
        KeepADB.State state = KeepADB.getState(this);
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
                tile.setSubtitle(getString(R.string.tile_state_disconnected));
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
