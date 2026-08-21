package de.moos.wifiadb;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class AdbWifiTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        boolean want = !AdbWifi.isEnabled(this);
        if (!AdbWifi.setEnabled(this, want)) {
            Toast.makeText(this, getString(R.string.tile_permission_error),
                    Toast.LENGTH_LONG).show();
        } else if (!want) {
            AdbWifiPreferences.setKeepAliveEnabled(this, false);
            AdbWifiService.stop(this);
        }
        updateTile();
        AdbWifiWidget.refreshAll(this);
        AdbWifiNotification.refresh(this);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = AdbWifi.isEnabled(this);
        AdbWifiNotification.refresh(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_adb));
        tile.updateTile();
    }
}
