package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowTileService;

/**
 * #586: the Quick Settings tile must not enable wireless debugging on a locked, secured device
 * without an unlock; disabling from the lock screen stays immediate.
 *
 * <p>Both directions of the gate are pinned: an enable while locked must be deferred to the
 * unlock callback, and a disable while locked (or an enable while unlocked) must not be deferred.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTileUnlockGateTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();
    private ServerSocket endpointServer;

    /** Holds the unlock callback back instead of Robolectric's run-immediately shadow. */
    public static class CapturingTileService extends KeepADBTileService {
        final List<Runnable> pendingUnlockCallbacks = new ArrayList<>();

        @Override
        void requestUnlockAndRun(Runnable afterUnlock) {
            pendingUnlockCallbacks.add(afterUnlock);
        }
    }

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        resetState();
    }

    @After
    public void tearDown() {
        closeEndpointServer();
        setDeviceLocked(false);
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBNotification.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
    }

    @Test
    public void lockedEnableIsDeferredUntilUnlockAndThenEnablesOnce() {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingTileService tile = startTile(CapturingTileService.class);
        try {
            tile.onClick();

            assertEquals("A locked enable must not write before the unlock",
                    Collections.emptyList(), gateway.writes);
            assertFalse(gateway.isEnabled(context));
            assertEquals("The enable must be routed through unlockAndRun()",
                    1, tile.pendingUnlockCallbacks.size());
            assertTrue(diagnostics().contains("outcome=unlock_required"));

            setDeviceLocked(false);
            tile.pendingUnlockCallbacks.get(0).run();

            assertEquals(Collections.singletonList(true), gateway.writes);
            assertTrue(gateway.isEnabled(context));
        } finally {
            tile.onStopListening();
        }
    }

    @Test
    public void unlockCallbackRederivesStateAndNeverDisables() {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingTileService tile = startTile(CapturingTileService.class);
        try {
            // Two taps while the unlock prompt is up queue two callbacks.
            tile.onClick();
            tile.onClick();
            assertEquals(2, tile.pendingUnlockCallbacks.size());
            assertTrue(gateway.writes.isEmpty());

            setDeviceLocked(false);
            for (Runnable callback : tile.pendingUnlockCallbacks) {
                callback.run();
            }

            assertEquals("Only the first callback may enable; the second must see 'on' and"
                    + " skip, not toggle back off", Collections.singletonList(true), gateway.writes);
            assertTrue(gateway.isEnabled(context));
            assertTrue(diagnostics().contains("outcome=skipped"));
        } finally {
            tile.onStopListening();
        }
    }

    @Test
    public void unlockCallbackSkipsWhenSomethingElseEnabledMeanwhile() throws Exception {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingTileService tile = startTile(CapturingTileService.class);
        try {
            tile.onClick();
            assertNotNull(tile.pendingUnlockCallbacks.get(0));

            // Keep-Alive (or another surface) switched it on while the prompt was showing, and the
            // endpoint is up -- a stale "want" would now be a disable.
            gateway.write(context, true);
            gateway.writes.clear();
            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
            seedEndpoint("127.0.0.1", openEndpointServer());
            assertEquals(KeepADB.State.ENABLED_CONNECTED, KeepADB.getState(context));

            setDeviceLocked(false);
            tile.pendingUnlockCallbacks.get(0).run();

            assertTrue("The unlock callback must never write, least of all a disable",
                    gateway.writes.isEmpty());
            assertTrue(gateway.isEnabled(context));
        } finally {
            tile.onStopListening();
        }
    }

    @Test
    public void lockedEnableUsesTheRealUnlockAndRun() {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        KeepADBTileService tile = startTile(KeepADBTileService.class);
        ShadowTileService shadowTile = shadowOf(tile);
        shadowTile.setLocked(true);
        try {
            tile.onClick();

            // Robolectric's unlockAndRun() shadow models a successful unlock: it clears the
            // shadow's locked flag and runs the callback right away. Both prove the production
            // seam delegates to the real TileService#unlockAndRun().
            assertFalse("unlockAndRun() was not called", tile.isLocked());
            assertEquals(Collections.singletonList(true), gateway.writes);
        } finally {
            tile.onStopListening();
        }
    }

    @Test
    public void lockedDisableIsImmediateAndNotGated() throws Exception {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        seedEndpoint("127.0.0.1", openEndpointServer());
        assertEquals(KeepADB.State.ENABLED_CONNECTED, KeepADB.getState(context));
        setDeviceLocked(true);

        CapturingTileService tile = startTile(CapturingTileService.class);
        try {
            tile.onClick();

            assertTrue("A disable must never ask for an unlock",
                    tile.pendingUnlockCallbacks.isEmpty());
            assertEquals(Collections.singletonList(false), gateway.writes);
            assertFalse(gateway.isEnabled(context));
        } finally {
            tile.onStopListening();
        }
    }

    @Test
    public void unlockedEnableIsImmediate() {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(false);
        CapturingTileService tile = startTile(CapturingTileService.class);
        try {
            tile.onClick();

            assertTrue(tile.pendingUnlockCallbacks.isEmpty());
            assertEquals(Collections.singletonList(true), gateway.writes);
            assertFalse(diagnostics().contains("outcome=unlock_required"));
        } finally {
            tile.onStopListening();
        }
    }

    @Test
    public void swipeOnlyKeyguardDoesNotGateTheEnable() {
        // Keyguard shown but no credential required: isDeviceLocked() is false, nothing to bypass.
        KeepADBFakeSettingsGateway gateway = offState();
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        shadowOf(keyguardManager).setKeyguardLocked(true);
        shadowOf(keyguardManager).setIsKeyguardSecure(false);
        shadowOf(keyguardManager).setIsDeviceLocked(false);
        CapturingTileService tile = startTile(CapturingTileService.class);
        try {
            tile.onClick();

            assertTrue(tile.pendingUnlockCallbacks.isEmpty());
            assertEquals(Arrays.asList(true), gateway.writes);
        } finally {
            shadowOf(keyguardManager).setKeyguardLocked(false);
            tile.onStopListening();
        }
    }

    private KeepADBFakeSettingsGateway offState() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADB.recordExplicitIntent(context, false);
        assertEquals(KeepADB.State.OFF, KeepADB.getState(context));
        return gateway;
    }

    private <T extends KeepADBTileService> T startTile(Class<T> type) {
        // Like the other tile suites: no destroy() (Robolectric's TileService shadow does not
        // support it); onStopListening() is the cleanup.
        T tile = Robolectric.buildService(type).create().get();
        tile.onStartListening();
        return tile;
    }

    private void setDeviceLocked(boolean locked) {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        shadowOf(keyguardManager).setIsKeyguardSecure(locked);
        shadowOf(keyguardManager).setIsDeviceLocked(locked);
    }

    private String diagnostics() {
        return KeepADBDiagnostics.export(context);
    }

    private void seedEndpoint(String host, int port) throws Exception {
        synchronized (KeepADBNotification.class) {
            setStaticNotificationField("currentHost", host);
            setStaticNotificationField("currentPort", port);
        }
    }

    private void setStaticNotificationField(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private int openEndpointServer() throws java.io.IOException {
        closeEndpointServer();
        endpointServer = new ServerSocket(0);
        return endpointServer.getLocalPort();
    }

    private void closeEndpointServer() {
        if (endpointServer == null) return;
        try {
            endpointServer.close();
        } catch (java.io.IOException ignored) {
        }
        endpointServer = null;
    }
}
