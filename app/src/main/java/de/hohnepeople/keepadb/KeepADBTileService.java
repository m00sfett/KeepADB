package de.hohnepeople.keepadb;

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
        updateTile();
    }

    @Override
    public void onClick() {
        boolean want = !KeepADB.isEnabled(this);
        if (!KeepADB.setEnabled(this, want)) {
            Toast.makeText(this, getString(R.string.tile_permission_error),
                    Toast.LENGTH_LONG).show();
        }
        updateTile();
        KeepADBWidget.refreshAll(this);
        KeepADBNotification.refresh(this);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = KeepADB.isEnabled(this);
        KeepADBNotification.refresh(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_keepadb));
        tile.updateTile();
    }
}
