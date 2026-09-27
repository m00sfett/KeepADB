package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

/**
 * #601: behavior replacement for {@code KeepADBAsyncSurfaceRefreshContractTest
 * #widgetStateRefreshRunsOnMainThreadWithoutStartingDiscovery}, itself flagged as follow-up work
 * by #596. That test grepped {@link KeepADBWidget}'s source for the {@code Looper.myLooper() !=
 * Looper.getMainLooper()}/{@code MAIN_HANDLER.post(...)} guard and for the absence of a direct
 * {@code KeepADBEndpointCoordinator.refresh(context)} call. This drives {@link
 * KeepADBWidget#refreshAllState(Context)} from a genuine background {@link Thread} against a real
 * {@link ShadowAppWidgetManager}-backed widget and asserts on the actually rendered {@code
 * RemoteViews} text plus the coordinator's observable discovery state instead.
 *
 * <p>Robolectric's default {@code LooperMode} is {@code PAUSED}: a {@link android.os.Handler}
 * post from off the main thread queues a message that only runs once the main looper is
 * explicitly idled ({@link ShadowLooper#idleMainLooper()}), so the queued-vs-applied window is
 * directly observable here without any additional test seam.
 *
 * <p>The before/after states are distinguished by toggling {@code adb_wifi_enabled} itself (OFF
 * vs. {@code ENABLED_DISCONNECTED}) rather than by a cached endpoint: {@link
 * ShadowAppWidgetManager#createWidget} synchronously fires the provider's real {@code onUpdate()}
 * (with {@code refreshNotification=true}) as a side effect of placing the widget, and a cached
 * endpoint at that point would incidentally start {@link
 * KeepADBEndpointCoordinator}'s real reachability verification against an unroutable test
 * address -- exactly the kind of extra, timing-dependent background work this test must stay
 * independent of. Neither state used here touches the endpoint cache at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetMainThreadRefreshTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        // Starts OFF: refresh()'s cached-endpoint/discovery branches are all unreachable from an
        // adb_wifi_enabled=false state, so ShadowAppWidgetManager#createWidget's own internal
        // onUpdate() call below is guaranteed side-effect-free.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
    }

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
    }

    @Test
    public void widgetStateRefreshRunsOnMainThreadWithoutStartingDiscovery() throws Exception {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ShadowAppWidgetManager shadowManager = shadowOf(manager);
        int widgetId = shadowManager.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);

        String initialText = widgetText(shadowManager.getViewFor(widgetId));
        assertTrue("expected the initial OFF text in the widget: " + initialText,
                initialText.contains(context.getString(R.string.widget_text_off)));
        assertFalse("no discovery attempt must be running before the refresh either",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());

        // Flip to ENABLED_DISCONNECTED (still no cached endpoint, so still no discovery branch
        // reachable) so the eventual widget update is distinguishable from the initial render.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        CountDownLatch backgroundCallReturned = new CountDownLatch(1);
        Thread background = new Thread(() -> {
            KeepADBWidget.refreshAllState(context);
            backgroundCallReturned.countDown();
        });
        background.start();
        assertTrue("refreshAllState() did not return from the background thread",
                backgroundCallReturned.await(5, TimeUnit.SECONDS));
        background.join(5000);

        // The post to the main Handler is now queued but not yet run -- the widget must still
        // show the pre-refresh text, proving the update itself happens on the main thread rather
        // than synchronously on the calling background thread.
        assertEqualsText("refreshAllState() must not update the widget before the main looper runs",
                initialText, shadowManager.getViewFor(widgetId));
        assertFalse("refreshAllState() must never start a discovery attempt",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());

        ShadowLooper.idleMainLooper();

        String updatedText = widgetText(shadowManager.getViewFor(widgetId));
        assertTrue("expected the DISCONNECTED text once the main looper ran the posted refresh: "
                        + updatedText,
                updatedText.contains(context.getString(R.string.widget_text_disconnected)));
        assertFalse("refreshAllState() must never start a discovery attempt, even once applied",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
    }

    private static void assertEqualsText(String message, String expected, View widget) {
        String actual = widgetText(widget);
        assertTrue(message + " (expected \"" + expected + "\", got \"" + actual + "\")",
                expected.equals(actual));
    }

    private static String widgetText(View widget) {
        TextView label = widget.findViewById(R.id.widget_label);
        return label.getText().toString();
    }
}
