package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #580: an OEM may grant {@code WRITE_SECURE_SETTINGS} yet still restrict reading {@code
 * adb_wifi_enabled} beyond what {@link KeepADBAndroidSettingsGateway}'s javadoc already
 * anticipates, so the read throws {@link SecurityException} despite the write permission being
 * present. Three read paths must fall back instead of propagating that exception: {@link
 * KeepADB#getState}, the {@code observed} read at the top of {@link KeepADB#setEnabled}, and
 * {@link KeepADBEndpoint#maybeSendRecoveryPulse}, which runs as a Handler callback on the main
 * looper where an uncaught exception would crash the app. Each fallback also logs exactly one
 * {@code read_failed} diagnostics event, and the normal (non-throwing) path is re-verified here
 * so a fallback that swallows every read -- not just a failing one -- cannot pass unnoticed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBReadSecurityExceptionFallbackTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBNotification.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
        KeepADBNotification.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // -- getState() -----------------------------------------------------------------------

    @Test
    public void getStateFallsBackToPermissionMissingWhenReadThrows() {
        KeepADB.setGatewayForTesting(new KeepADBThrowingSettingsGateway());

        assertEquals(KeepADB.State.PERMISSION_MISSING, KeepADB.getState(context));

        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event for get_state: " + diagnostics,
                diagnostics.contains("event=read_failed") && diagnostics.contains("source=get_state"));
    }

    @Test
    public void getStateGegenprobeStillReportsRealOnState() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        assertEquals(KeepADB.State.ENABLED_DISCONNECTED, KeepADB.getState(context));
    }

    @Test
    public void getStateGegenprobeStillReportsRealOffState() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertEquals(KeepADB.State.OFF, KeepADB.getState(context));
    }

    // -- setEnabled()'s observed read -------------------------------------------------------

    @Test
    public void setEnabledDoesNotCrashWhenObservedReadThrows() {
        // Throws only on the very first isEnabled() call (the "observed" read at the top of
        // setEnabled, this test's target) and returns the real, write-tracked state afterwards --
        // isolating that one call site from the other, still-unguarded isEnabled() reads reachable
        // from setEnabled's write path (applyNow's post-write readback, and transitively
        // surfaces.refreshAll() -> KeepADBService.sync(); both out of #580's scope, see #582).
        KeepADBThrowingSettingsGateway gateway = new KeepADBThrowingSettingsGateway(false, false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(new KeepADBFakeScheduler());

        // Must not throw: the top-of-method observed read used to propagate the SecurityException
        // straight to the caller before even reaching the write.
        boolean result = KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);

        assertTrue("the observed-read failure must not block the write attempt from succeeding",
                result);
        assertEquals(Arrays.asList(true), gateway.writes);

        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event for the observed read: " + diagnostics,
                diagnostics.contains("event=read_failed") && diagnostics.contains("source=app"));
    }

    @Test
    public void setEnabledGegenprobeStillReportsRealObservedValue() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(new KeepADBFakeScheduler());

        boolean result = KeepADB.setEnabled(context, false, KeepADB.SOURCE_APP);

        assertTrue("a normal disable with a working gateway must still succeed", result);
        assertEquals(Collections.singletonList(false), gateway.writes);
        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("the real (true) observed value must still reach diagnostics: " + diagnostics,
                diagnostics.contains("observed=true"));
        assertFalse("a successful read must not log a read_failed event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }

    // -- KeepADBEndpoint.maybeSendRecoveryPulse() -------------------------------------------

    @Test
    public void maybeSendRecoveryPulseDoesNotCrashWhenIsEnabledReadThrows() {
        KeepADBThrowingSettingsGateway gateway = new KeepADBThrowingSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        // Must not throw: this runs as a Handler callback on the main looper in production,
        // where an uncaught exception here would crash the app (see #580 report).
        endpoint.maybeSendRecoveryPulse(0L);

        assertTrue("a failed read must be treated like 'not enabled': no pulse write may happen",
                gateway.writes.isEmpty());
        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("expected a read_failed diagnostics event for the endpoint read: " + diagnostics,
                diagnostics.contains("event=read_failed") && diagnostics.contains("source=endpoint"));
    }

    @Test
    public void maybeSendRecoveryPulseGegenprobeStillSkipsWhenReallyDisabled() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        endpoint.maybeSendRecoveryPulse(0L);

        assertTrue("a real 'off' read must still skip the pulse exactly like before",
                gateway.writes.isEmpty());
        String diagnostics = KeepADBDiagnostics.export(context);
        assertFalse("a successful read must not log a read_failed event: " + diagnostics,
                diagnostics.contains("event=read_failed"));
    }
}
