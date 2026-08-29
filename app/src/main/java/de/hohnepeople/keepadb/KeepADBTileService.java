package de.hohnepeople.keepadb;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class KeepADBTileService extends TileService {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    public void onStartListening() {
        KeepADBNotification.refresh(this);
        updateTile();
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

    static void requestRefresh(Context context) {
        if (context == null) return;
        try {
            requestListeningState(context.getApplicationContext(),
                    new ComponentName(context.getApplicationContext(), KeepADBTileService.class));
        } catch (RuntimeException ignored) {
        }
    }
}
