package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Regression tests for issue #318: the main switch must mirror {@code adb_wifi_enabled} and
 * nothing else (acceptance criterion 1), and MainActivity, the tile and the widget must derive
 * the meaning of a click from one shared definition (acceptance criterion 2).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBStatusDecouplingTest {

    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf((Application) context)
                .grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private void withAdbSetting(boolean enabled) {
        gateway = new KeepADBFakeSettingsGateway(enabled);
        KeepADB.setGatewayForTesting(gateway);
    }

    /**
     * The exact situation the issue reports: wireless debugging is off in the Android system,
     * Keep-Alive is on and the last explicit intent was "on", so the standby service is waiting to
     * switch it back on. Before the fix this produced {@code ENABLED_DISCONNECTED}, i.e. an
     * ON state, while the system setting read 0.
     */
    @Test
    public void keepAliveWaitingWhileTheSettingIsOffIsAnOffState() {
        Context context = RuntimeEnvironment.getApplication();
        withAdbSetting(false);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);

        assertEquals(KeepADB.State.OFF_KEEP_ALIVE_WAITING, KeepADB.getState(context));
        assertFalse("An OFF_* state must never read as enabled", KeepADB.isEnabled(context));
    }

    /**
     * Acceptance criterion 1, guarded from both sides. The one-sided version ("unchecked while the
     * setting is 0") would still pass if the switch were simply never checked at all, so the
     * checked direction is asserted too: any change that decouples the switch from the setting in
     * either direction turns this red.
     */
    @Test
    public void mainSwitchTracksTheRealSettingInBothDirections() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);

        withAdbSetting(false);
        ActivityController<MainActivity> offController =
                Robolectric.buildActivity(MainActivity.class).setup();
        Switch offToggle = offController.get().findViewById(R.id.toggle);
        TextView offStatus = offController.get().findViewById(R.id.status);
        assertFalse("adb_wifi_enabled == 0 must never render as a checked switch",
                offToggle.isChecked());
        assertEquals("The Keep-Alive wait belongs in the subtext, not in the switch",
                context.getString(R.string.status_off_keep_alive_waiting),
                offStatus.getText().toString());
        offController.pause().close();

        withAdbSetting(true);
        ActivityController<MainActivity> onController =
                Robolectric.buildActivity(MainActivity.class).setup();
        Switch onToggle = onController.get().findViewById(R.id.toggle);
        assertTrue("adb_wifi_enabled == 1 must render as a checked switch", onToggle.isChecked());
        onController.pause().close();
    }

    /** Acceptance criterion 2: one definition, exhaustive over every state. */
    @Test
    public void clickSemanticsAreDefinedOnceForEveryState() {
        assertTrue(KeepADB.desiredOnForClick(KeepADB.State.OFF));
        assertTrue(KeepADB.desiredOnForClick(KeepADB.State.OFF_KEEP_ALIVE_WAITING));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.ENABLED_DISCONNECTED));
        assertFalse(KeepADB.desiredOnForClick(KeepADB.State.ENABLED_CONNECTED));
        // Every enum constant must be covered, so a future state cannot slip in unconsidered.
        assertEquals(5, KeepADB.State.values().length);
    }

    /**
     * Acceptance criterion 2 across the three surfaces. A behavioral test cannot compare a tile
     * tap, a widget broadcast and a switch tap in one process without three framework harnesses,
     * so the invariant is pinned where it can actually diverge again: each surface must ask
     * {@link KeepADB#desiredOnForClick} instead of computing its own desired value.
     */
    @Test
    public void everySurfaceDerivesItsClickActionFromTheSharedDefinition() throws IOException {
        String main = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String widget = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java");

        assertTrue("MainActivity's switch must use the shared click semantics",
                main.contains("KeepADB.desiredOnForClick(KeepADB.getState(this))"));
        assertTrue("The tile must use the shared click semantics",
                tile.contains("KeepADB.desiredOnForClick(state)"));
        assertTrue("The widget must use the shared click semantics",
                widget.contains("KeepADB.desiredOnForClick(state)"));

        assertFalse("The tile must not recompute a desired value of its own",
                tile.contains("boolean want = (state == KeepADB.State.OFF);"));
        assertFalse("The widget must not recompute a desired value of its own",
                widget.contains("boolean want = (state == KeepADB.State.OFF);"));
        assertFalse("MainActivity must not derive the desired value from the view state",
                main.contains("boolean want = toggle.isChecked();"));
    }

    /** The debounce window must be observable instead of silently showing the old value. */
    @Test
    public void aScheduledButUnappliedToggleIsReportedAsPending() {
        Context context = RuntimeEnvironment.getApplication();
        withAdbSetting(false);
        KeepADBFakeScheduler fakeScheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(fakeScheduler);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());

        assertFalse(KeepADB.isTogglePending());
        // An automatic source keeps the #114 cooldown, so this write is scheduled, not immediate.
        KeepADB.setEnabled(context, true, "content_observer");
        assertTrue("A write applied straight away is not pending", KeepADB.isTogglePending());
        assertFalse("Nothing may be written before the cooldown elapses", KeepADB.isEnabled(context));

        fakeScheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertFalse("Once applied, nothing is pending any more", KeepADB.isTogglePending());
        assertTrue(KeepADB.isEnabled(context));
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
