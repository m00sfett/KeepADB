package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #881: a failed webhook report, and the success that ends a failure episode, leave a diagnostics
 * event naming how the request ended (HTTP status or exception class), so an episode can be
 * explained afterwards -- without a URL, host or endpoint in it. Routine successes add nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterDiagnosticsTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "192.168.1.10";
    private static final int PORT = 41000;

    private Context context;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBBuildFlags.setOverrideForTesting(true);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookRetryCount(context, 0);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting(HOST + ":" + PORT);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void failedReportRecordsTheErrorClassWithoutUrlOrEndpoint() {
        transport.setPostSuccess(false);

        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        String event = lastReportEvent();
        assertTrue(event, event.contains("outcome=failed"));
        assertTrue(event, event.contains("method=post error=FakeIOException retry=0"));
        assertFalse("no URL in the event: " + event, event.contains("register.example"));
        assertFalse("no endpoint in the event: " + event, event.contains(HOST));
    }

    @Test
    public void routineSuccessAddsNoEventButTheEpisodeEndingSuccessDoes() {
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertFalse("a routine success writes nothing extra (#317)", hasReportEvent());

        transport.setPostSuccess(false);
        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        transport.setPostSuccess(true);
        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        String last = lastReportEvent();
        assertTrue(last, last.contains("outcome=success"));
        assertTrue(last, last.contains("method=post http=200"));
    }

    /** Codex review: DELETE events count the deregistration retries, not the POST series. */
    @Test
    public void deleteEventsCarryTheDeregistrationRetryNumber() {
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.setMarkUnavailableNowForTesting(1_000L);
        KeepADBRegisterClient.markUnavailableAsync(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        KeepADBRegisterClient.setMarkUnavailableNowForTesting(10_000_000L);
        KeepADBRegisterClient.markUnavailableAsync(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        String last = lastReportEvent();
        assertTrue(last, last.contains("method=delete error=FakeIOException retry=1"));
    }

    /** Codex review: a success of a superseded POST is not recorded as the end of the episode. */
    @Test
    public void supersededSuccessIsNotRecordedAsRecovery() {
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        transport.setPostSuccess(true);
        transport.setRequestCallback(request -> {
            if ("POST".equals(request.method)) KeepADBRegisterClient.markUnavailableAsync(context);
        });
        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        transport.setRequestCallback(null);

        for (String line : KeepADBDiagnostics.export(context).split("\n")) {
            assertFalse("stale success logged as recovery: " + line,
                    line.contains("event=register_report") && line.contains("method=post http=200"));
        }
    }

    /** The real transport, against a closed local port: the class is recorded, not the message. */
    @Test
    public void realTransportReportsOnlyTheExceptionClass() {
        KeepADBRegisterClient.noteTransportOutcome("unknown");

        boolean ok = new KeepADBHttpTransport().postJson("http://127.0.0.1:1/register/dev1", "{}", "x");

        assertFalse(ok);
        String outcome = KeepADBRegisterClient.lastTransportOutcomeForTesting();
        assertTrue(outcome, outcome.matches("error=[A-Za-z]+Exception"));
        assertFalse(outcome, outcome.contains("127.0.0.1"));
    }

    private boolean hasReportEvent() {
        return KeepADBDiagnostics.export(context).contains("event=register_report");
    }

    private String lastReportEvent() {
        String found = null;
        for (String line : KeepADBDiagnostics.export(context).split("\n")) {
            if (line.contains("event=register_report")) found = line;
        }
        if (found == null) throw new AssertionError("no register_report event in the journal export");
        return found;
    }
}
