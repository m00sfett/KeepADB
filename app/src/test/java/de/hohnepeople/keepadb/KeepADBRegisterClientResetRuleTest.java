package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #806: the reset rule must fail visibly when the register executor does not drain. #1020: a test
 * under the rule never reaches the real network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterClientResetRuleTest {

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

    @Test
    public void everyTestStartsWithTheOfflineTransport() throws Exception {
        Field field = KeepADBRegisterClient.class.getDeclaredField("httpTransport");
        field.setAccessible(true);
        assertSame(KeepADBRegisterClientResetRule.OFFLINE_TRANSPORT, field.get(null));
    }

    /**
     * #1020: a register request of a test without a fake of its own is refused by the offline
     * transport. The outcome proves it: the real transport would report a socket error for the
     * unroutable address instead. The wait is generous on purpose, so a slow CI runner cannot fail
     * this test the way the drain used to fail (Codex review of #1023).
     */
    @Test
    public void aRequestWithoutItsOwnFakeIsRefusedWithoutTheNetwork() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://10.255.255.1:9/register/test");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 40000);

        assertTrue("the register executor must drain",
                KeepADBRegisterClient.awaitIdleForTesting(5000));
        assertEquals("error=OfflineTestTransport",
                KeepADBRegisterClient.lastTransportOutcomeForTesting());
    }

    @Test
    public void resetFailsVisiblyWhenTheExecutorDoesNotDrainInTime() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        KeepADBRegisterClient.setHttpTransport(new KeepADBRegisterClient.HttpTransport() {
            @Override
            public boolean postJson(String targetUrl, String payload, String logLabel) {
                inFlight.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }

            @Override
            public boolean delete(String targetUrl) {
                return true;
            }
        });
        try {
            KeepADBPreferences.setRegisterWebhookUrl(context, "http://127.0.0.1:1/register/test");
            KeepADBPreferences.setRegisterWebhookEnabled(context, true);
            KeepADBRegisterClient.updateEndpointAsync(context, "127.0.0.1", 40000);
            assertTrue("register task never started", inFlight.await(5, TimeUnit.SECONDS));

            try {
                KeepADBRegisterClientResetRule.reset(50);
                fail("a drain timeout must not be swallowed");
            } catch (AssertionError expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("did not drain"));
            }
            assertTrue("A timed-out reset must leave the in-flight client state intact",
                    KeepADBRegisterClient.isWlanUpdateInFlightForTesting());
        } finally {
            release.countDown();
            assertTrue("The released register task must drain before fixture cleanup",
                    KeepADBRegisterClient.awaitIdleForTesting(5000));
            KeepADBRegisterClient.resetForTesting();
            context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
