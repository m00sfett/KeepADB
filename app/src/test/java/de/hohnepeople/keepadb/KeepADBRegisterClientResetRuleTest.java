package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** #806: the reset rule must fail visibly when the register executor does not drain. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterClientResetRuleTest {

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
        } finally {
            release.countDown();
            KeepADBRegisterClient.awaitIdleForTesting(5000);
            KeepADBRegisterClient.resetForTesting();
            context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
