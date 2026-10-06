package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.widget.TextView;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #601: behavior replacement for {@code KeepADBAsyncSurfaceRefreshContractTest
 * #activityDropsQueuedEndpointCallbacksAfterPauseAndRecreation}, itself flagged as follow-up work
 * by #596. That test grepped {@link MainActivity}'s source for the field-write order of {@code
 * endpointListenerGeneration}/{@code endpointSurfaceActive}; this drives a real {@link
 * MainActivity} through {@link ActivityController} pause/resume/recreate and asserts on the
 * actually rendered {@code R.id.endpoint} text instead.
 *
 * <p>{@link KeepADBEndpointCoordinator#setEndpointListener} hands its listener to whichever
 * {@link MainActivity} instance most recently registered one, but the listener itself sits in a
 * private static field with no production accessor. Rather than add a new seam for it, these
 * tests read that field directly via reflection -- the same {@code setStatic}-style pattern
 * {@link KeepADBEndpointCoordinatorTest} and {@link MainActivityTransportOverviewTest} already use
 * for this class's other private statics -- and then invoke the captured listener's callbacks
 * directly, exactly reproducing how a real discovery result reaches it (see the direct {@code
 * endpointListener.onEndpoint(...)}/{@code .onUnavailable()} call sites in {@link
 * KeepADBEndpointCoordinator}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityEndpointListenerGenerationTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @org.junit.Before
    public void setUp() {
        // #791: an endpoint is only ever reported once the system permission is there; without it
        // the screen now clears the address on every refresh.
        org.robolectric.Shadows.shadowOf((android.app.Application)
                org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    @After
    public void tearDown() {
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void activityDropsQueuedEndpointCallbacksAfterPauseAndRecreation() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity firstActivity = controller.get();
        TextView firstEndpointView = firstActivity.findViewById(R.id.endpoint);

        KeepADBEndpointCoordinator.EndpointListener listenerBeforePause = currentEndpointListener();
        assertNotNull(listenerBeforePause);

        // A callback for the CURRENT generation, delivered while still resumed, does update the
        // display -- establishes a known, distinguishable baseline for the negative checks below.
        listenerBeforePause.onEndpoint("192.0.2.10", 10001);
        String textWhileResumed = firstEndpointView.getText().toString();
        assertTrue("expected the freshly delivered port in the resumed screen: " + textWhileResumed,
                textWhileResumed.contains("10001"));

        controller.pause();
        ShadowLooper.idleMainLooper();
        // The exact same (now superseded) listener delivers a second, later callback -- the
        // "queued callback that arrives after pause" scenario the generation guard exists for.
        listenerBeforePause.onEndpoint("192.0.2.11", 10002);
        assertEqualsText("a callback queued before pause must not move the paused screen",
                textWhileResumed, firstEndpointView);

        controller.resume();
        ShadowLooper.idleMainLooper();
        assertSame("pause()/resume() must reuse the same Activity instance",
                firstActivity, controller.get());
        KeepADBEndpointCoordinator.EndpointListener listenerAfterResume = currentEndpointListener();
        assertNotSame("resume() must register a fresh listener/generation, not reuse the paused one",
                listenerBeforePause, listenerAfterResume);

        // The stale, pre-pause listener delivering yet again -- now after a resume -- must still
        // be dropped: the generation it captured is behind the current one.
        listenerBeforePause.onEndpoint("192.0.2.12", 10003);
        String textAfterResume = firstEndpointView.getText().toString();
        assertFalse("a stale pre-pause callback must never reach the resumed screen: " + textAfterResume,
                textAfterResume.contains("10003"));

        // A callback for the CURRENT (post-resume) generation does update the display.
        listenerAfterResume.onEndpoint("192.0.2.13", 10004);
        String textAfterCurrentDelivery = firstEndpointView.getText().toString();
        assertTrue("expected the current generation's port after resume: " + textAfterCurrentDelivery,
                textAfterCurrentDelivery.contains("10004"));

        // Recreate: a brand-new MainActivity instance. The pre-recreate listener is now bound to a
        // finishing/destroyed Activity; delivering through it must neither throw nor touch the new
        // instance's own endpoint text.
        controller.recreate();
        ShadowLooper.idleMainLooper();
        MainActivity recreatedActivity = controller.get();
        assertNotSame("recreate() must produce a new Activity instance",
                firstActivity, recreatedActivity);
        TextView recreatedEndpointView = recreatedActivity.findViewById(R.id.endpoint);
        String recreatedInitialText = recreatedEndpointView.getText().toString();

        listenerAfterResume.onEndpoint("192.0.2.14", 10005);
        assertEqualsText("a callback queued before recreate must not move the recreated screen",
                recreatedInitialText, recreatedEndpointView);

        // A callback for the new instance's own current generation still updates it normally.
        KeepADBEndpointCoordinator.EndpointListener listenerAfterRecreate = currentEndpointListener();
        listenerAfterRecreate.onEndpoint("192.0.2.15", 10006);
        String textAfterRecreateDelivery = recreatedEndpointView.getText().toString();
        assertTrue("expected the recreated screen's own current-generation port: " + textAfterRecreateDelivery,
                textAfterRecreateDelivery.contains("10006"));

        controller.pause().close();
    }

    private static void assertEqualsText(String message, String expected, TextView view) {
        String actual = view.getText().toString();
        assertTrue(message + " (expected \"" + expected + "\", got \"" + actual + "\")",
                expected.equals(actual));
    }

    private static KeepADBEndpointCoordinator.EndpointListener currentEndpointListener() throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField("endpointListener");
        field.setAccessible(true);
        return (KeepADBEndpointCoordinator.EndpointListener) field.get(null);
    }
}
