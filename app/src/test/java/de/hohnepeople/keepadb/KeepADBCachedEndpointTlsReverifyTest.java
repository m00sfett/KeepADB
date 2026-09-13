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
 * Regression coverage for #394: {@code KeepADBNotification.verifyCachedEndpointAsync()} used to
 * re-verify an already-registered/cached endpoint with the plain-connect
 * {@code KeepADBEndpoint.isPortReachable()} -- pure TCP {@code connect()} success, no protocol
 * check at all. #363 closed this gap for the *registration* path (the quick probe's
 * {@code probeAdbTlsPort()}), but left the periodic re-verification of a cached endpoint on the
 * old plain-connect check: if a foreign, non-adbd service later took over the same host:port, the
 * heartbeat/roam-triggered re-verification would keep treating it as a valid, reachable adb
 * endpoint.
 *
 * <p>This test drives the real (non-faked) {@link KeepADBNotification.ReachabilityProbe} default
 * -- now {@code KeepADBEndpoint::probeAdbTlsPort} -- against a real loopback {@link ServerSocket}
 * standing in for a foreign, non-adbd service that happily completes a TCP connect() but replies
 * with something that is not TLS-shaped. The cached endpoint must be invalidated, not confirmed
 * reachable.
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
    public void aForeignNonAdbdServiceOnTheCachedEndpointIsInvalidatedNotConfirmed() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread foreignService = new Thread(() -> {
                try (Socket client = server.accept()) {
                    // Drain whatever the probe sent, then reply with a plain, non-TLS-shaped
                    // banner -- exactly the "unrelated service took over the port" scenario #394
                    // is about.
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
            // default (KeepADBEndpoint::probeAdbTlsPort as of #394) end-to-end against the
            // foreign loopback service above.
            KeepADBNotification.verifyEndpointHealth(context);
            awaitVerificationIdle();
            foreignService.join(5000);

            assertNull("a foreign, non-adbd service on the cached host:port must not be confirmed "
                    + "as a still-reachable adb endpoint", KeepADBNotification.getCurrentHost());
        }
    }

    @Test
    public void aGenuineAdbTlsListenerOnTheCachedEndpointIsConfirmedReachable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread adbdLikeService = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.getInputStream().read(new byte[64]);
                    OutputStream out = client.getOutputStream();
                    // A minimal TLS-shaped reply (handshake record, TLS 1.2 version bytes), as a
                    // real adbd TLS listener would produce in response to our ClientHello probe.
                    out.write(new byte[] { 0x16, 0x03, 0x03, 0x00, 0x02, (byte) 0xAB, (byte) 0xCD });
                    out.flush();
                } catch (Exception ignored) {
                    // Test cleanup races are not the point here.
                }
            });
            adbdLikeService.start();

            setStatic("currentHost", "127.0.0.1");
            setStatic("currentPort", port);

            KeepADBNotification.verifyEndpointHealth(context);
            awaitVerificationIdle();
            adbdLikeService.join(5000);

            assertTrue("a genuine TLS-shaped adbd-like listener on the cached host:port must "
                    + "still be confirmed reachable", "127.0.0.1".equals(KeepADBNotification.getCurrentHost()));
        }
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
