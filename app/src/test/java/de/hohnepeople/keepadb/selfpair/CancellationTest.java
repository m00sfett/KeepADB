package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** #1038: what a cancel of a self-pairing attempt guarantees. */
public class CancellationTest {
    @Test
    public void aStepNeverRunsAfterTheCancel() throws Exception {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        boolean[] ran = {false};
        try {
            cancellation.runUnlessCancelled(() -> ran[0] = true);
            fail("a cancelled attempt must refuse the step");
        } catch (Cancellation.CancelledException expected) {
            assertFalse(ran[0]);
        }
    }

    /** The other half of the race: a cancel cannot slip in while the step is running. */
    @Test(timeout = 10_000)
    public void aCancelWaitsForARunningStep() throws Exception {
        Cancellation cancellation = new Cancellation();
        CountDownLatch stepStarted = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        CompletableFuture<Void> step = CompletableFuture.runAsync(() -> {
            try {
                cancellation.runUnlessCancelled(() -> {
                    stepStarted.countDown();
                    try {
                        releaseStep.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, TestThreads.EXECUTOR);
        assertTrue(stepStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> cancel =
                CompletableFuture.runAsync(cancellation::cancel, TestThreads.EXECUTOR);
        try {
            cancel.get(300, TimeUnit.MILLISECONDS);
            fail("the cancel overtook a running step");
        } catch (TimeoutException expected) {
            // still waiting for the step
        }
        releaseStep.countDown();
        step.get(5, TimeUnit.SECONDS);
        cancel.get(5, TimeUnit.SECONDS);
        assertTrue(cancellation.isCancelled());
    }

    @Test
    public void attachAfterTheCancelIsRefused() throws Exception {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        try (Socket socket = new Socket()) {
            cancellation.attach(socket);
            fail("a cancelled attempt must not open new connections");
        } catch (Cancellation.CancelledException expected) {
            // fine
        }
    }

    /** A pairing connection stuck waiting for its peer ends with the cancel, not its timeout. */
    @Test(timeout = 30_000)
    public void aCancelClosesThePairingConnection() throws Exception {
        AdbIdentity identity = AdbIdentity.create();
        Cancellation cancellation = new Cancellation();
        CountDownLatch accepted = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    accepted.countDown();
                    while (peer.getInputStream().read() >= 0) {
                        // say nothing, until the client hangs up
                    }
                } catch (IOException e) {
                    // the client hung up
                }
            }, TestThreads.EXECUTOR);
            CompletableFuture<Void> pairing = CompletableFuture.runAsync(() -> {
                try {
                    new AdbPairingClient(Tls13Channel.FACTORY).pair(server.getLocalPort(),
                            "123456", identity, "test", cancellation);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            assertTrue(accepted.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            cancellation.cancel();
            try {
                // Far below the 10 s read timeout.
                pairing.get(3, TimeUnit.SECONDS);
                fail("pairing must not succeed");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().getCause() instanceof IOException);
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
        }
    }
}
