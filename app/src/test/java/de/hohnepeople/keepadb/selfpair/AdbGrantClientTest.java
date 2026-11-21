package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class AdbGrantClientTest {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_STLS = 0x534c5453;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;

    /** "TLS" that passes bytes through, so the ADB framing can be tested on its own. */
    private static final TlsChannel.Factory PASS_THROUGH = (socket, identity) -> new TlsChannel() {
        @Override
        public InputStream input() {
            try {
                return socket.getInputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public OutputStream output() {
            try {
                return socket.getOutputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public byte[] exportKeyingMaterial(String label, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    };

    @Test
    public void commandIsTheGrantForTheGivenPackage() {
        assertEquals("pm grant de.hohnepeople.keepadb.debug "
                + "android.permission.WRITE_SECURE_SETTINGS",
                AdbGrantClient.command("de.hohnepeople.keepadb.debug"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void commandRejectsShellMetacharacters() {
        AdbGrantClient.command("a.b; reboot");
    }

    @Test
    public void runsExactlyTheGrantAndReturnsItsOutput() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> opened = CompletableFuture.supplyAsync(() -> {
                try (Socket peer = server.accept()) {
                    return fakeAdbd(peer, "Exception occurred while executing 'grant'\n");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            String output = new AdbGrantClient(PASS_THROUGH)
                    .grant(server.getLocalPort(), null, "de.hohnepeople.keepadb.debug");
            assertEquals("Exception occurred while executing 'grant'", output);
            assertEquals("shell:pm grant de.hohnepeople.keepadb.debug "
                    + "android.permission.WRITE_SECURE_SETTINGS\0", opened.get());
        }
    }

    @Test(expected = IOException.class)
    public void refusesAPeerThatDoesNotOfferTls() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    read(peer.getInputStream());
                    write(peer.getOutputStream(), A_CNXN, 0, 0, new byte[0]);
                    peer.getInputStream().read();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            new AdbGrantClient(PASS_THROUGH).grant(server.getLocalPort(), null, "a.b");
        }
    }

    /** Review finding: a peer must not be able to keep the client busy with empty output. */
    @Test(expected = IOException.class)
    public void givesUpOnEndlessEmptyOutput() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    InputStream in = peer.getInputStream();
                    OutputStream out = peer.getOutputStream();
                    read(in);
                    write(out, A_STLS, 0x01000000, 0, new byte[0]);
                    read(in);
                    write(out, A_CNXN, 0x01000001, 1 << 20, new byte[0]);
                    Frame open = read(in);
                    write(out, A_OKAY, 7, open.arg0, new byte[0]);
                    while (true) {
                        write(out, A_WRTE, 7, open.arg0, new byte[0]);
                        read(in);
                    }
                } catch (IOException e) {
                    // expected: the client hangs up
                }
            }, TestThreads.EXECUTOR);
            new AdbGrantClient(PASS_THROUGH).grant(server.getLocalPort(), null, "a.b");
        }
    }

    /**
     * #1038: the peer completes the transport setup and holds back its CNXN; the user cancels;
     * then the CNXN arrives. The grant command must never reach the peer.
     */
    @Test(timeout = 30_000)
    public void aCancelBeforeCnxnKeepsTheCommandFromGoingOut() throws Exception {
        Cancellation cancellation = new Cancellation();
        CountDownLatch transportReady = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Boolean> sawOpen = CompletableFuture.supplyAsync(() -> {
                try (Socket peer = server.accept()) {
                    InputStream in = peer.getInputStream();
                    OutputStream out = peer.getOutputStream();
                    read(in);
                    write(out, A_STLS, 0x01000000, 0, new byte[0]);
                    read(in);
                    transportReady.countDown();
                    cancelled.await(10, TimeUnit.SECONDS);
                    try {
                        write(out, A_CNXN, 0x01000001, 1 << 20, new byte[0]);
                        return read(in).command == A_OPEN;
                    } catch (IOException e) {
                        return false; // the client hung up
                    }
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            CompletableFuture<String> grant = CompletableFuture.supplyAsync(() -> {
                try {
                    return new AdbGrantClient(PASS_THROUGH)
                            .grant(server.getLocalPort(), null, "a.b", cancellation);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            assertTrue(transportReady.await(10, TimeUnit.SECONDS));
            cancellation.cancel();
            cancelled.countDown();
            assertFalse("the grant command went out after the cancel",
                    sawOpen.get(10, TimeUnit.SECONDS));
            try {
                grant.get(10, TimeUnit.SECONDS);
                fail("a cancelled grant must not complete");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().getCause() instanceof IOException);
            }
        }
    }

    /**
     * Codex review of #1038: a cancel from the main thread must not wait for a write that is
     * stuck, and it must close the connection so the write fails.
     */
    @Test(timeout = 30_000)
    public void aCancelDoesNotWaitForAStuckCommandWrite() throws Exception {
        Cancellation cancellation = new Cancellation();
        CountDownLatch writing = new CountDownLatch(1);
        TlsChannel.Factory stuckOnOpen = (socket, identity) -> new TlsChannel() {
            @Override
            public InputStream input() {
                return passThroughInput(socket);
            }

            @Override
            public OutputStream output() {
                return new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        // Stands for a write that blocks until the socket is closed.
                        writing.countDown();
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (!socket.isClosed() && System.nanoTime() < until) {
                            Thread.yield();
                        }
                        throw new java.net.SocketException("socket closed");
                    }
                };
            }

            @Override
            public byte[] exportKeyingMaterial(String label, int length) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    InputStream in = peer.getInputStream();
                    OutputStream out = peer.getOutputStream();
                    read(in);
                    write(out, A_STLS, 0x01000000, 0, new byte[0]);
                    read(in);
                    write(out, A_CNXN, 0x01000001, 1 << 20, new byte[0]);
                    while (in.read() >= 0) {
                        // until the client hangs up
                    }
                } catch (IOException e) {
                    // the client hung up
                }
            }, TestThreads.EXECUTOR);
            CompletableFuture<String> grant = CompletableFuture.supplyAsync(() -> {
                try {
                    return new AdbGrantClient(stuckOnOpen)
                            .grant(server.getLocalPort(), null, "a.b", cancellation);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            cancellation.cancel();
            assertTrue("cancel waited for the stuck write",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
            assertTrue(cancellation.commandStarted());
            try {
                grant.get(5, TimeUnit.SECONDS);
                fail("the stuck write must fail once the socket is closed");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().getCause() instanceof IOException);
            }
        }
    }

    private static InputStream passThroughInput(Socket socket) {
        try {
            return socket.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void deadlineClosesTheSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
                Socket socket = new Socket(server.getInetAddress(), server.getLocalPort());
                Deadline deadline = Deadline.closeAfter(socket, 200)) {
            try {
                socket.getInputStream().read();
                org.junit.Assert.fail("read should have been cut off");
            } catch (IOException expected) {
                org.junit.Assert.assertTrue(deadline.expired());
            }
        }
    }

    private static String fakeAdbd(Socket peer, String commandOutput) throws IOException {
        InputStream in = peer.getInputStream();
        OutputStream out = peer.getOutputStream();
        assertEquals(A_CNXN, read(in).command);
        write(out, A_STLS, 0x01000000, 0, new byte[0]);
        assertEquals(A_STLS, read(in).command);
        write(out, A_CNXN, 0x01000001, 1 << 20, "device::".getBytes(StandardCharsets.UTF_8));
        Frame open = read(in);
        assertEquals(A_OPEN, open.command);
        write(out, A_OKAY, 7, open.arg0, new byte[0]);
        write(out, A_WRTE, 7, open.arg0, commandOutput.getBytes(StandardCharsets.UTF_8));
        assertEquals(A_OKAY, read(in).command);
        write(out, A_CLSE, 7, open.arg0, new byte[0]);
        assertEquals(A_CLSE, read(in).command);
        return new String(open.data, StandardCharsets.UTF_8);
    }

    private static void write(OutputStream out, int command, int arg0, int arg1, byte[] data)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(0)
                .putInt(~command).put(data);
        out.write(buffer.array());
        out.flush();
    }

    private static Frame read(InputStream in) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(Bytes.readFully(in, 24))
                .order(ByteOrder.LITTLE_ENDIAN);
        Frame frame = new Frame();
        frame.command = header.getInt();
        frame.arg0 = header.getInt();
        header.getInt();
        frame.data = Bytes.readFully(in, header.getInt());
        return frame;
    }

    private static final class Frame {
        int command;
        int arg0;
        byte[] data;
    }
}
