package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * #911: what the widgets attach to their webhook controls and what the receiver does with it.
 * The PendingIntents are explicit and immutable and reach only the non-exported receiver; the
 * receiver's actions go through the same entry points as the rest of the app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWebhookActionsTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;

    private Context context;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBReportHistory.clear(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeout) -> true);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    @After
    public void tearDown() {
        transport.setRequestCallback(null);
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting(null);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private void settle() {
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    private int count(String method) {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request : new ArrayList<>(transport.recordedRequests)) {
            if (method.equals(request.method)) count++;
        }
        return count;
    }

    /** What the system does when the user taps the widget: delivers the saved intent. */
    private void tap(PendingIntent pendingIntent) {
        Intent intent = shadowOf(pendingIntent).getSavedIntent();
        new KeepADBReceiver().onReceive(context, intent);
    }

    /** The notification button: an explicit broadcast to the non-exported receiver. */
    private void tapReportNow() {
        new KeepADBReceiver().onReceive(context, new Intent(context, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW));
    }

    // ---- PendingIntent contracts -------------------------------------------------------------

    private void assertExplicitImmutableBroadcast(PendingIntent pendingIntent, String action) {
        ShadowPendingIntent shadow = shadowOf(pendingIntent);
        assertTrue("a broadcast", shadow.isBroadcastIntent());
        assertTrue("immutable: nobody can fill in or redirect it",
                (shadow.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent intent = shadow.getSavedIntent();
        assertEquals(action, intent.getAction());
        assertNotNull("explicit component", intent.getComponent());
        assertEquals(KeepADBReceiver.class.getName(), intent.getComponent().getClassName());
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
    }

    @Test
    public void everyBroadcastActionIsExplicitImmutableAndForTheNonExportedReceiver() {
        assertExplicitImmutableBroadcast(KeepADBWebhookActions.setAutomaticSync(context, true),
                KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE);
        assertExplicitImmutableBroadcast(KeepADBWebhookActions.setAutomaticSync(context, false),
                KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE);
    }

    @Test
    public void theSetupOpensTheWebhookFormAsAnExplicitImmutableActivityIntent() {
        ShadowPendingIntent shadow = shadowOf(KeepADBWebhookActions.setup(context));

        assertTrue(shadow.isActivityIntent());
        assertTrue((shadow.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent intent = shadow.getSavedIntent();
        assertEquals(SettingsActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, false));
    }

    /** One PendingIntent per control: a widget's control never replaces another one. */
    @Test
    public void everyControlHasItsOwnPendingIntentIdentity() {
        Set<Integer> codes = new HashSet<>();
        codes.add(shadowOf(KeepADBWebhookActions.setAutomaticSync(context, true)).getRequestCode());
        codes.add(shadowOf(KeepADBWebhookActions.setAutomaticSync(context, false)).getRequestCode());
        codes.add(shadowOf(KeepADBWebhookActions.setup(context)).getRequestCode());
        assertEquals(3, codes.size());
    }

    @Test
    public void theReceiverIsNotExportedAndTheWidgetActionsAreNotInItsIntentFilter()
            throws IOException {
        String manifest = readManifest();
        int start = manifest.indexOf("android:name=\".KeepADBReceiver\"");
        assertTrue(start > 0);
        String receiver = manifest.substring(start, manifest.indexOf("</receiver>", start));

        assertTrue(receiver.contains("android:exported=\"false\""));
        assertFalse("only an explicit intent can reach it",
                receiver.contains(KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW));
        assertFalse(receiver.contains(KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE));
        assertFalse(receiver.contains(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE));
    }

    // ---- Report now, tapped on the webhook failure notification (the only place it is offered) ------------------------------------------------------------

    @Test
    public void aReportNowTapWithSyncOffSendsNothingAndLeavesItOff() {
        tapReportNow();
        tapReportNow();
        settle();

        assertEquals("report now is a push of an enabled synchronization", 0,
                transport.getRequestCount());
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void aReportNowTapWithSyncOnReportsOnce() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        tapReportNow();
        settle();

        assertEquals(1, count("POST"));
        assertEquals(URL, transport.getLastRequest().url);
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    /** The receiver goes through the one entry point, which does not reuse a cached address. */
    @Test
    public void aReportNowTapWithoutWifiSendsNothingEvenWithSyncOnAndACachedEndpoint() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        tapReportNow();
        settle();

        assertEquals(0, transport.getRequestCount());
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void tapsOnTwoButtonsWhileAReportIsOnTheWireSendOneRequest() throws Exception {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.setRequestCallback(request -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        tapReportNow();   // the first notification button
        assertTrue(started.await(5, TimeUnit.SECONDS));
        tapReportNow();   // a second button, for example after the shade was refreshed
        tapReportNow();   // and a double tap
        release.countDown();
        settle();

        assertEquals(1, count("POST"));
    }

    @Test
    public void aReportNowTapWithoutADestinationSendsNothingAndChangesNothing() {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);

        tapReportNow();
        settle();

        assertEquals(0, transport.getRequestCount());
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    // ---- the synchronization switch, tapped on a widget -------------------------------------------

    @Test
    public void theEnableActionSwitchesSyncOnThroughTheValidatedPath() {
        tap(KeepADBWebhookActions.setAutomaticSync(context, true));
        settle();

        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals("the cached endpoint is reported by the established path", 1, count("POST"));
        assertEquals(KeepADBWidgetState.Sync.ON, KeepADBWidgetState.project(context).webhook.sync);
    }

    @Test
    public void theEnableActionWithoutAValidDestinationChangesAndSendsNothing() {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);

        tap(KeepADBWebhookActions.setAutomaticSync(context, true));
        settle();

        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(0, transport.getRequestCount());
        assertEquals(KeepADBWidgetState.Sync.UNCONFIGURED,
                KeepADBWidgetState.project(context).webhook.sync);
    }

    @Test
    public void theDisableActionKeepsTheOptOutSemantics() {
        tap(KeepADBWebhookActions.setAutomaticSync(context, true));
        settle();

        tap(KeepADBWebhookActions.setAutomaticSync(context, false));
        settle();

        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals("the registration is withdrawn", 1, count("DELETE"));
        assertEquals(KeepADBWidgetState.Sync.OFF, KeepADBWidgetState.project(context).webhook.sync);
    }

    /** A double tap on the switch asks for the same state twice: the second one is a no-op. */
    @Test
    public void aRepeatedSwitchTapCannotUndoItself() {
        PendingIntent enable = KeepADBWebhookActions.setAutomaticSync(context, true);
        tap(enable);
        tap(enable);
        settle();

        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(1, count("POST"));
        assertEquals(0, count("DELETE"));
    }

    private static String readManifest() throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return new String(Files.readAllBytes(directory.resolve("app/src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
    }
}
