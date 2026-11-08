package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

/**
 * #910: placed widgets are re-rendered by the actions that change what they show, not by polling
 * and not by being rendered. The shadow widget manager recreates the view on every update, so a new
 * view instance is the observation that a widget was rendered again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetStateRefreshTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";

    private Context context;
    private ShadowAppWidgetManager widgets;
    private int widgetId;
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
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        // Placed while Wireless Debugging is off: the provider's own first update (legacy, it asks
        // the coordinator for a refresh) then starts nothing.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        widgets = shadowOf(AppWidgetManager.getInstance(context));
        widgets.setAlwaysRecreateViewsDuringUpdate(true);
        widgetId = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void tearDown() {
        transport.setRequestCallback(null);
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private View view() {
        return widgets.getViewFor(widgetId);
    }

    @Test
    public void aChangeOfKeepAliveOrOfTheWebhookSettingsRendersThePlacedWidgetAgain() {
        View before = view();
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        View afterKeepAlive = view();
        assertNotSame("Keep-Alive change", before, afterKeepAlive);

        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        View afterUrl = view();
        assertNotSame("webhook URL change", afterKeepAlive, afterUrl);

        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertNotSame("webhook switch change", afterUrl, view());

        assertEquals("rendering for a setting is state only: nothing was sent",
                0, transport.getRequestCount());
        assertEquals(KeepADBRegisterClient.Activity.IDLE, KeepADBRegisterClient.activity());
    }

    /** "Sending" shows when the report is queued, the outcome when it is answered. */
    @Test
    public void aReportRendersThePlacedWidgetWhenItIsQueuedAndAgainWhenItIsAnswered()
            throws Exception {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBEndpointCoordinator.seedEndpointForTesting("10.20.30.40", 41873, 1L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.setRequestCallback(request -> {
            if (!"POST".equals(request.method)) return;
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        shadowOf(Looper.getMainLooper()).idle();
        View before = view();

        KeepADBRegisterClient.updateEndpointAsync(context, "10.20.30.40", 41873);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
        View whileSending = view();
        assertNotSame("queued: sending shows", before, whileSending);
        assertEquals(KeepADBWidgetState.Activity.SENDING,
                KeepADBWidgetState.project(context).webhook.activity);

        release.countDown();
        KeepADBRegisterClientResetRule.awaitIdle();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotSame("answered: the outcome shows", whileSending, view());
        assertEquals(KeepADBWidgetState.Delivery.CURRENT,
                KeepADBWidgetState.project(context).webhook.delivery);
    }
}
