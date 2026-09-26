package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

/**
 * Behavioral coverage for #582: a permanent OEM read restriction on {@code adb_wifi_enabled}
 * (modeled by {@link KeepADBThrowingSettingsGateway}'s "always throw" mode) must not crash any of
 * the call sites #580 deliberately left unguarded, and must never turn an unknown read into an
 * automatic write or a false "on" display. Each test below targets one of the "most important
 * paths" #582 names explicitly: {@link KeepADBService#sync}/{@code recheckAndEnable} (via {@code
 * onStartCommand}), the {@code applyNow} SecurityException-catch cleanup (the crash this issue
 * documents: {@code surfaces.refreshAll() -> KeepADBService.sync() -> shouldRun() ->
 * isEnabled()}), the quick settings tile, the endpoint notification, and {@code
 * MainActivity.refresh()}.
 *
 * <p>Every test below was confirmed red (crash, or an unwanted automatic write) against the
 * pre-#582 code by temporarily reverting the relevant call site's isEnabledOrNull() fix back to
 * the bare isEnabled() in a scratch copy of this worktree and re-running just this class; that
 * scratch copy was discarded afterward and none of it is part of this commit (see the #582 task
 * report for the exact mutation/revert steps).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBPermanentReadRestrictionBehaviorTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        resetState();
    }

    @After
    public void tearDown() {
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_trusted_networks", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBNotification.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
    }

    // -- KeepADBService.shouldRun()/sync() ---------------------------------------------------

    @Test
    public void shouldRunFallsBackToLastExplicitIntentWhenReadIsPermanentlyRestricted() {
        KeepADBThrowingSettingsGateway gateway = new KeepADBThrowingSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);

        // Must not throw, and an unreadable value must not sink the decision to false on its own --
        // wasLastExplicitIntentOff() (which needs no Settings.Global read) still says "keep running".
        assertTrue("shouldRun must fall back to the last explicit intent, not treat an unknown"
                + " read as 'off'", KeepADBService.shouldRun(context));

        KeepADBService.sync(context);
        assertTrue("gateway.write() must never be called by sync()/shouldRun() -- neither is an"
                + " automatic-enable decision", gateway.writes.isEmpty());

        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }

    @Test
    public void shouldRunGegenprobeStillReportsRealOffAndRealOnState() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, false);
        assertFalse("keepAlive on, real state off, last intent off -> must not run",
                KeepADBService.shouldRun(context));

        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        assertTrue("A real 'on' readback must still count", KeepADBService.shouldRun(context));

        String diagnostics = KeepADBDiagnostics.export(context);
        assertFalse("a successful read must not log a read_failed event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }

    // -- KeepADBService.recheckAndEnable() (via onStartCommand) ------------------------------

    /**
     * The central Automatik rule this issue is about: an unreadable current value must never be
     * treated as "off, so enable it". Without the fix, recheckAndEnable()'s bare isEnabled() call
     * both throws (crashing the foreground service's onStartCommand) and, if it hadn't thrown,
     * would have read as {@code false} and driven an unauthenticated automatic write.
     */
    @Test
    public void recheckAndEnableDoesNotAutoEnableOrCrashOnPermanentReadFailure() {
        KeepADBThrowingSettingsGateway gateway = new KeepADBThrowingSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            // Must not throw.
            int result = controller.get()
                    .onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            assertEquals(Service.START_STICKY, result);

            assertTrue("recheckAndEnable() must not write on an unconfirmed read -- 'unknown' is"
                    + " not 'off'", gateway.writes.isEmpty());

            String diagnostics = KeepADBDiagnostics.export(context);
            assertTrue("expected a read_failed diagnostics event: " + diagnostics,
                    diagnostics.contains("event=read_failed"));
        } finally {
            controller.destroy();
        }
    }

    // -- KeepADB.applyNow()'s SecurityException-catch cleanup (the #582 crash) --------------

    /**
     * Reproduces the exact double-fault #582 documents: the write itself loses
     * {@code WRITE_SECURE_SETTINGS} (or is otherwise refused with a SecurityException) *and* the
     * read is separately restricted. Before the fix, applyNow()'s catch(SecurityException) block
     * called surfaces.refreshAll() -&gt; KeepADBService.sync() -&gt; shouldRun() -&gt; the bare
     * isEnabled(), which threw a second, uncaught SecurityException from inside the first
     * exception's own handler.
     */
    @Test
    public void applyNowCatchCleanupDoesNotCrashWhenBothWriteAndReadAreRestricted() {
        KeepADBWriteAndReadThrowingGateway gateway = new KeepADBWriteAndReadThrowingGateway();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);

        // Manual source -> applyNow() runs synchronously, right here, not on a debounce delay.
        boolean result = KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);

        assertFalse("A write that throws SecurityException must be reported as failed", result);
        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected the write's own security_exception failure event: " + diagnostics,
                diagnostics.contains("reason=security_exception"));
    }

    // -- KeepADBTileService ------------------------------------------------------------------

    @Test
    public void tileShowsPermissionMissingStyleAndDoesNotCrashOnPermanentReadFailure() {
        KeepADB.setGatewayForTesting(new KeepADBThrowingSettingsGateway());

        // #582: KeepADBTileService controllers are never destroy()d in this suite (see
        // KeepADBMultiStateContractTest) -- Robolectric's TileService shadow support does not
        // extend to ServiceController#destroy(); onStopListening() is enough cleanup here.
        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        try {
            // Must not throw.
            tileService.onStartListening();
            Tile tile = tileService.getQsTile();

            assertEquals("An unreadable value must render like a missing permission, never a"
                            + " false 'on'", Tile.STATE_UNAVAILABLE, tile.getState());

            // Must not throw, and must not silently flip a state it cannot confirm.
            tileService.onClick();
        } finally {
            tileService.onStopListening();
        }
        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }

    /**
     * The narrower race {@code isSearchingForEndpoint()} itself guards against: {@code
     * onStartListening()} first calls {@link KeepADBNotification#refreshForTile} (one read) and
     * then {@link KeepADB#getState} (a second read, needed to reach {@code ENABLED_DISCONNECTED}
     * here) -- both must succeed for this scenario -- but the tile's own third, follow-up read
     * for the subtitle inside {@code isSearchingForEndpoint()} then fails. Before #582 this bare
     * isEnabled() call would have crashed onStartListening() outright instead of falling back to
     * "searching", exactly like the existing race-fallback already does for a real "off" readback.
     */
    @Test
    public void tileSubtitleFallsBackToSearchingWhenTheFollowUpReadFailsMidRace() {
        KeepADB.setGatewayForTesting(new KeepADBSucceedsNTimesThenThrowsGateway(true, 2));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        try {
            // Must not throw even though the second isEnabled() call (inside
            // isSearchingForEndpoint()) throws.
            tileService.onStartListening();
            Tile tile = tileService.getQsTile();

            assertEquals(Tile.STATE_ACTIVE, tile.getState());
            assertEquals("An unreadable follow-up read must fall back to 'searching', matching"
                            + " the existing race-fallback behavior for a real 'off' readback",
                    context.getString(R.string.tile_state_searching), tile.getSubtitle());
        } finally {
            tileService.onStopListening();
        }
    }

    // -- KeepADBNotification ------------------------------------------------------------------

    @Test
    public void notificationRefreshCancelsRatherThanCrashOrShowFalseOnOnPermanentReadFailure() {
        KeepADBThrowingSettingsGateway gateway = new KeepADBThrowingSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);
        // Keep-Alive off, so shouldRun() (itself now read-safe) is false and refresh() must
        // actually cancel the notification instead of showing the Keep-Alive-waiting placeholder.
        KeepADBPreferences.setKeepAliveEnabled(context, false);

        // Must not throw.
        KeepADBNotification.refresh(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertNull("An unreadable value must not leave (or show) an active-looking notification",
                shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID));
        assertTrue("An unreadable value must never trigger a write",
                gateway.writes.isEmpty());

        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }

    // -- MainActivity.refresh() ---------------------------------------------------------------

    @Test
    public void mainActivityRefreshShowsPermissionMissingAndDoesNotCrashOnPermanentReadFailure() {
        KeepADB.setGatewayForTesting(new KeepADBThrowingSettingsGateway());

        ActivityController<MainActivity> activity =
                Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity mainActivity = activity.get();
            Switch toggle = mainActivity.findViewById(R.id.toggle);
            TextView status = mainActivity.findViewById(R.id.status);

            assertFalse("An unreadable value must never render the main switch as checked",
                    toggle.isChecked());
            assertFalse("The switch must be disabled, exactly like a confirmed missing"
                    + " permission", toggle.isEnabled());
            assertEquals(context.getString(R.string.status_permission_missing),
                    status.getText().toString());
        } finally {
            activity.pause().close();
        }
        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }

    /** Write-and-read-restricted fake for the applyNow double-fault scenario (#582). */
    private static final class KeepADBWriteAndReadThrowingGateway implements KeepADBSettingsGateway {
        @Override
        public boolean isEnabled(Context context) {
            throw new SecurityException("read restricted (test fake, #582)");
        }

        @Override
        public boolean write(Context appContext, boolean on) {
            throw new SecurityException("write restricted (test fake, #582)");
        }
    }

    /**
     * Succeeds on the first {@code successCount} {@link #isEnabled} calls, then throws on every
     * call after that -- the inverse of {@link KeepADBThrowingSettingsGateway}'s "throw once,
     * then succeed" mode, needed to model a state that reads successfully for a while (e.g. the
     * two reads {@link KeepADBNotification#refreshForTile} and {@link KeepADB#getState} each make)
     * and then fails on an immediately following, independent read (#582).
     */
    private static final class KeepADBSucceedsNTimesThenThrowsGateway implements KeepADBSettingsGateway {
        private final boolean value;
        private final int successCount;
        private int calls;

        KeepADBSucceedsNTimesThenThrowsGateway(boolean value, int successCount) {
            this.value = value;
            this.successCount = successCount;
        }

        @Override
        public boolean isEnabled(Context context) {
            calls++;
            if (calls <= successCount) {
                return value;
            }
            throw new SecurityException("read restricted after " + successCount
                    + " calls (test fake, #582)");
        }

        @Override
        public boolean write(Context appContext, boolean on) {
            return true;
        }
    }
}
