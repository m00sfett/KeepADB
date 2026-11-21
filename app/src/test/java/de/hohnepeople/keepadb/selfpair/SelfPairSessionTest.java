package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.nsd.NsdManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SelfPairSessionTest {
    @Test
    public void aCodeIsExactlySixAsciiDigits() {
        assertTrue(SelfPairSession.isCode("012345"));
        assertFalse(SelfPairSession.isCode(null));
        assertFalse(SelfPairSession.isCode(""));
        assertFalse(SelfPairSession.isCode("12345"));
        assertFalse(SelfPairSession.isCode("1234567"));
        assertFalse(SelfPairSession.isCode("12 345"));
        assertFalse(SelfPairSession.isCode("12345a"));
        // Digits of other scripts are not what Android's dialog shows.
        assertFalse(SelfPairSession.isCode("١٢٣٤٥٦"));
    }

    @Test
    public void cancelIsHarmlessBeforeAnyRunAndMayBeRepeated() {
        Context context = ApplicationProvider.getApplicationContext();
        NsdManager nsd = context.getSystemService(NsdManager.class);
        SelfPairSession session = new SelfPairSession(nsd, "de.hohnepeople.keepadb", visible -> {});
        assertNull("pairedFingerprint is initially null", session.pairedFingerprint());
        session.cancel();
        // safe to call again
        session.cancel();
    }

    /**
     * #1039: a local listener announced after the real dialog answers the TLS handshake with a
     * fatal alert. The genuine candidate behind it must still get its connection.
     */
    @Test(timeout = 60_000)
    public void aFailingRogueListenerDoesNotHideTheGenuineDialog() throws Exception {
        AdbIdentity identity = AdbIdentity.create();
        try (ServerSocket rogue = new ServerSocket(0); ServerSocket genuine = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (Socket peer = rogue.accept()) {
                    // handshake_failure alert, plaintext, instead of a ServerHello.
                    OutputStream out = peer.getOutputStream();
                    out.write(new byte[] {21, 3, 3, 0, 2, 2, 40});
                    out.flush();
                    drain(peer.getInputStream());
                } catch (IOException e) {
                    // the client hangs up
                }
            }, TestThreads.EXECUTOR);
            CompletableFuture<Boolean> reached = CompletableFuture.supplyAsync(() -> {
                try (Socket peer = genuine.accept()) {
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }, TestThreads.EXECUTOR);
            SelfPairSession.Failure failure = SelfPairSession.pairFirst(
                    Arrays.asList(rogue.getLocalPort(), genuine.getLocalPort()),
                    port -> new AdbPairingClient(Tls13Channel.FACTORY)
                            .pair(port, "123456", identity, "test"),
                    () -> false);
            assertTrue("genuine candidate never connected", reached.get(10, TimeUnit.SECONDS));
            // The stand-in for the dialog hangs up without pairing.
            assertEquals(SelfPairSession.Failure.PAIRING_FAILED, failure);
        }
    }

    @Test
    public void aWrongCodeEndsTheAttemptWithoutTryingFurtherCandidates() {
        List<Integer> tried = new ArrayList<>();
        SelfPairSession.Failure failure = SelfPairSession.pairFirst(Arrays.asList(1, 2, 3),
                port -> {
                    tried.add(port);
                    throw new AdbPairingClient.WrongCodeException();
                }, () -> false);
        assertEquals(SelfPairSession.Failure.WRONG_CODE, failure);
        assertEquals(Arrays.asList(1), tried);
    }

    @Test
    public void unauthenticatedFailuresMoveOnUntilOneCandidatePairs() {
        List<Integer> tried = new ArrayList<>();
        SelfPairSession.Failure failure = SelfPairSession.pairFirst(Arrays.asList(1, 2, 3),
                port -> {
                    tried.add(port);
                    if (port == 1) {
                        throw new IOException("TLS alert 40 from peer");
                    }
                    if (port == 2) {
                        throw new ConnectException("closed dialog");
                    }
                }, () -> false);
        assertNull(failure);
        assertEquals(Arrays.asList(1, 2, 3), tried);
    }

    @Test
    public void onlyRefusedConnectionsMeanThereIsNoDialog() {
        assertEquals(SelfPairSession.Failure.NO_PAIRING_DIALOG,
                SelfPairSession.pairFirst(Arrays.asList(1, 2), port -> {
                    throw new ConnectException("closed dialog");
                }, () -> false));
        // A protocol failure is remembered even when a refused connection follows.
        assertEquals(SelfPairSession.Failure.PAIRING_FAILED,
                SelfPairSession.pairFirst(Arrays.asList(1, 2), port -> {
                    if (port == 1) {
                        throw new IOException("malformed");
                    }
                    throw new ConnectException("closed dialog");
                }, () -> false));
    }

    @Test
    public void triesAtMostTheCandidateBudget() {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < SelfPairSession.MAX_PAIR_CANDIDATES + 3; i++) {
            candidates.add(i);
        }
        List<Integer> tried = new ArrayList<>();
        SelfPairSession.Failure failure = SelfPairSession.pairFirst(candidates, port -> {
            tried.add(port);
            throw new IOException("broken listener");
        }, () -> false);
        assertEquals(SelfPairSession.Failure.PAIRING_FAILED, failure);
        assertEquals(SelfPairSession.MAX_PAIR_CANDIDATES, tried.size());
    }

    @Test
    public void aCancelStopsTheFailover() {
        boolean[] cancelled = {false};
        List<Integer> tried = new ArrayList<>();
        SelfPairSession.Failure failure = SelfPairSession.pairFirst(Arrays.asList(1, 2, 3),
                port -> {
                    tried.add(port);
                    cancelled[0] = true;
                    throw new IOException("closed by the cancel");
                }, () -> cancelled[0]);
        assertEquals(SelfPairSession.Failure.CANCELLED, failure);
        assertEquals(Arrays.asList(1), tried);
    }

    private static void drain(InputStream in) throws IOException {
        while (in.read() >= 0) {
            // until the client gives up
        }
    }
}
