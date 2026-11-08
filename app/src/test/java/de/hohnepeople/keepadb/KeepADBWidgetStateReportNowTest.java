package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #911: the projection tells a surface what a "Report now" tap would do, from the same facts the
 * action checks, so a widget offers the report, the setup or neither. It is a pure read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetStateReportNowTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private final AtomicBoolean wifi = new AtomicBoolean(true);
    private Context context;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(wifi::get);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting("10.20.30.40", 41873, 1L);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private KeepADBWidgetState.ReportNow reportNow() {
        return KeepADBWidgetState.project(context).webhook.reportNow;
    }

    @Test
    public void aSavedDestinationAndAVerifiedEndpointMakeItReady() {
        assertEquals(KeepADBWidgetState.ReportNow.READY, reportNow());
    }

    @Test
    public void withoutAVerifiedEndpointItHasNothingToReport() {
        wifi.set(false);
        assertEquals("a cached endpoint is not believed without Wi-Fi",
                KeepADBWidgetState.ReportNow.NO_ENDPOINT, reportNow());
        wifi.set(true);

        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        assertEquals(KeepADBWidgetState.ReportNow.NO_ENDPOINT, reportNow());
    }

    @Test
    public void withoutAValidDestinationTheSetupComesFirst() {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertEquals(KeepADBWidgetState.ReportNow.NEEDS_SETUP, reportNow());

        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putString("register_webhook_url", "ftp://register.example/x").commit();
        assertEquals("a saved but invalid URL is no destination",
                KeepADBWidgetState.ReportNow.NEEDS_SETUP, reportNow());

        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        assertEquals("the missing destination is named before the missing endpoint",
                KeepADBWidgetState.ReportNow.NEEDS_SETUP, reportNow());
    }

    @Test
    public void itDoesNotDependOnTheSynchronizationSwitchAndProjectingSendsNothing() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertEquals(KeepADBWidgetState.ReportNow.READY, reportNow());
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertEquals(KeepADBWidgetState.ReportNow.READY, reportNow());

        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals("projecting is a pure read", 0, transport.getRequestCount());
    }
}
