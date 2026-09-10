package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runtime state contracts; no production-source parsing is used here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBMultiStateContractTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void clickIntentMapsOnlyRealOffStatesToEnable() {
        assertTrue(KeepADB.desiredOnForClick(KeepADB.State.OFF));
        assertTrue(KeepADB.desiredOnForClick(KeepADB.State.OFF_KEEP_ALIVE_WAITING));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.ENABLED_DISCONNECTED));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.ENABLED_CONNECTED));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.PERMISSION_MISSING));
    }

    @Test
    public void widgetInflatesWithResourceBackedStateText() {
        View widget = LayoutInflater.from(context).inflate(R.layout.widget_keepadb, null, false);
        assertNotNull(widget.findViewById(R.id.widget_label));
        assertTrue(((android.widget.TextView) widget.findViewById(R.id.widget_label)).getText().length() > 0);
    }

    @Test
    public void allOperationalStateLabelsResolve() {
        assertTrue(context.getString(R.string.status_enabled_disconnected).length() > 0);
        assertTrue(context.getString(R.string.tile_state_disconnected).length() > 0);
        assertTrue(context.getString(R.string.tile_state_searching).length() > 0);
        assertTrue(context.getString(R.string.tile_state_connected).length() > 0);
        assertTrue(context.getString(R.string.widget_text_disconnected).length() > 0);
    }
}
