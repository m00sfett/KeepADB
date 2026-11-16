package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * #954: the "Toggle Wifi-ADB" launcher shortcut does what the tile does and never more. The unlock
 * gate of #586, the "never disable after an unlock" rule and the tile's failure toast are pinned
 * here the way {@link KeepADBTileUnlockGateTest} pins them for the tile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBToggleShortcutActivityTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();
    private ServerSocket endpointServer;

    /** Holds the unlock answer back instead of asking the (shadowed) keyguard. */
    public static class CapturingShortcutActivity extends KeepADBToggleShortcutActivity {
        final List<Runnable> onUnlocked = new ArrayList<>();
        final List<Runnable> onNotUnlocked = new ArrayList<>();

        @Override
        void requestUnlock(Runnable unlocked, Runnable notUnlocked) {
            onUnlocked.add(unlocked);
            onNotUnlocked.add(notUnlocked);
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
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        ShadowToast.reset();
    }

    @Test
    public void unlockedEnableWritesOnceAndFinishesWithoutAPrompt() {
        KeepADBFakeSettingsGateway gateway = offState();
        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        assertEquals(Collections.singletonList(true), gateway.writes);
        assertTrue(activity.onUnlocked.isEmpty());
        assertTrue("the trampoline must finish", activity.isFinishing());
        assertTrue(diagnostics().contains("outcome=enable"));
    }

    @Test
    public void lockedEnableWaitsForTheUnlockAndThenEnablesOnce() {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        assertEquals("A locked enable must not write before the unlock",
                Collections.emptyList(), gateway.writes);
        assertEquals("The enable must ask the keyguard", 1, activity.onUnlocked.size());
        assertFalse("still waiting for the unlock", activity.isFinishing());
        assertTrue(diagnostics().contains("outcome=unlock_required"));

        setDeviceLocked(false);
        activity.onUnlocked.get(0).run();

        assertEquals(Collections.singletonList(true), gateway.writes);
        assertTrue(activity.isFinishing());
    }

    @Test
    public void aCancelledUnlockTogglesNothing() {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        activity.onNotUnlocked.get(0).run();

        assertTrue("a cancelled or failed unlock must not write", gateway.writes.isEmpty());
        assertFalse(gateway.isEnabled(context));
        assertTrue(activity.isFinishing());
        assertTrue(diagnostics().contains("outcome=skipped"));
    }

    @Test
    public void aFailedUnlockRequestOnAnAlreadyOpenDeviceStillEnables() {
        // The keyguard was already gone when the request arrived: nothing is left to bypass.
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        setDeviceLocked(false);
        activity.onNotUnlocked.get(0).run();

        assertEquals(Collections.singletonList(true), gateway.writes);
        assertTrue(activity.isFinishing());
    }

    @Test
    public void unlockCallbackSkipsWhenSomethingElseEnabledMeanwhile() throws Exception {
        KeepADBFakeSettingsGateway gateway = offState();
        setDeviceLocked(true);
        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        // Keep-Alive (or another surface) switched it on while the prompt was showing, and the
        // endpoint is up: a stale "want" would now be a disable.
        gateway.write(context, true);
        gateway.writes.clear();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        seedEndpoint("127.0.0.1", openEndpointServer());
        assertEquals(KeepADB.State.ENABLED_CONNECTED, KeepADB.getState(context));

        setDeviceLocked(false);
        activity.onUnlocked.get(0).run();

        assertTrue("The unlock callback must never write, least of all a disable",
                gateway.writes.isEmpty());
        assertTrue(gateway.isEnabled(context));
        assertTrue(activity.isFinishing());
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

        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        assertTrue("A disable must never ask for an unlock", activity.onUnlocked.isEmpty());
        assertEquals(Collections.singletonList(false), gateway.writes);
        assertTrue(activity.isFinishing());
    }

    @Test
    public void aMissingPermissionIsReportedWithTheTilesToast() {
        // No WRITE_SECURE_SETTINGS: the state is PERMISSION_MISSING, the write is refused.
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);

        CapturingShortcutActivity activity = start(CapturingShortcutActivity.class);

        assertTrue(gateway.writes.isEmpty());
        assertEquals(activity.getString(R.string.tile_permission_error,
                activity.getString(R.string.app_name)), ShadowToast.getTextOfLatestToast());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void aRecreatedInstanceNeverTogglesAgain() {
        KeepADBFakeSettingsGateway gateway = offState();
        ActivityController<CapturingShortcutActivity> controller =
                Robolectric.buildActivity(CapturingShortcutActivity.class);
        controller.create(new Bundle()).start().resume();

        assertTrue(gateway.writes.isEmpty());
        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void theShortcutHasItsOwnManualToggleSource() {
        assertEquals("shortcut", KeepADB.SOURCE_SHORTCUT);
        assertTrue("a direct tap writes at once, like the tile",
                KeepADB.isManualSource(KeepADB.SOURCE_SHORTCUT));
    }

    private KeepADBFakeSettingsGateway offState() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADB.recordExplicitIntent(context, false);
        assertEquals(KeepADB.State.OFF, KeepADB.getState(context));
        return gateway;
    }

    private <T extends KeepADBToggleShortcutActivity> T start(Class<T> type) {
        return Robolectric.buildActivity(type).setup().get();
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
        synchronized (KeepADBEndpointCoordinator.class) {
            setStaticField("currentHost", host);
            setStaticField("currentPort", port);
        }
    }

    private void setStaticField(String fieldName, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
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
