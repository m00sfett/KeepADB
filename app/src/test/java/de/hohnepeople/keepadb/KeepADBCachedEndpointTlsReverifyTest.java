package de.hohnepeople.keepadb;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Regression coverage for #394 / #435: {@code KeepADBNotification.verifyCachedEndpointAsync()}
 * periodically re-verifies an already-registered/cached endpoint. #363 briefly closed the "any
 * TCP responder is accepted" gap for this path with a TLS-sniff probe ({@code probeAdbTlsPort()}),
 * but #404 found that probe never actually matches genuine adbd on real devices, so #435 reverted
 * this path to the plain-connect {@code KeepADBEndpoint.isPortReachable()} -- consistent with the
 * #412 decision for the mDNS discovery path.
 *
 * <p>This is a deliberate, documented trade-off (see #412/#424): a plain {@code connect()}
 * success accepts any TCP responder on the cached host:port, not just genuine adbd. The first test
 * below documents that accepted trade-off directly (a foreign, non-adbd service on the cached
 * endpoint is still confirmed reachable); the second confirms the cache is still invalidated when
 * nothing answers at all, which is the actual benefit the periodic re-verification exists for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBCachedEndpointTlsReverifyTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @org.junit.Before
    public void grantNotificationPermission() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
    }

    @After
    public void resetSharedState() throws Exception {
        KeepADBNotification.resetForTesting();
        KeepADB.resetForTesting();
        setStatic("currentHost", null);
        setStatic("currentPort", 0);
    }

    @Test
    public void aForeignNonAdbdServiceOnTheCachedEndpointIsStillConfirmedReachable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread foreignService = new Thread(() -> {
                try (Socket client = server.accept()) {
                    // Drain whatever the probe sent, then reply with a plain, non-TLS-shaped
                    // banner -- exactly the "unrelated service took over the port" scenario #394
                    // originally worried about. Plain-connect (#435) accepts this on purpose.
                    client.getInputStream().read(new byte[64]);
                    OutputStream out = client.getOutputStream();
                    out.write("NOT ADB\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) {
                    // Test cleanup races (probe already timed out/closed) are not the point here.
                }
            });
            foreignService.start();

            setStatic("currentHost", "127.0.0.1");
            setStatic("currentPort", port);

            // Deliberately do NOT install a fake ReachabilityProbe: this exercises the real
            // default (KeepADBEndpoint::isPortReachable as of #435) end-to-end against the
            // foreign loopback service above.
            KeepADBNotification.verifyEndpointHealth(context);
            awaitVerificationIdle();
            foreignService.join(5000);

            assertTrue("a plain TCP responder on the cached host:port is accepted by design "
                    + "(#412/#435 documented trade-off)", "127.0.0.1".equals(KeepADBNotification.getCurrentHost()));
        }
    }

    @Test
    public void aCachedEndpointWithNoResponderIsInvalidated() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = server.getLocalPort();
        }
        // Socket is now closed again, so nothing is listening on this port.

        setStatic("currentHost", "127.0.0.1");
        setStatic("currentPort", port);

        KeepADBNotification.verifyEndpointHealth(context);
        awaitVerificationIdle();

        assertNull("a cached endpoint with no responder at all must be invalidated, not confirmed "
                + "as still reachable", KeepADBNotification.getCurrentHost());
    }

    private static void awaitVerificationIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (KeepADBNotification.isVerificationInFlightForTesting()) {
            if (System.currentTimeMillis() > deadline) {
                fail("verification worker did not finish within the timeout");
            }
            Thread.sleep(10);
        }
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
