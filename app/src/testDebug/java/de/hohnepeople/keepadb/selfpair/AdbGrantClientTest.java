package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;

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
            });
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
            });
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
            });
            new AdbGrantClient(PASS_THROUGH).grant(server.getLocalPort(), null, "a.b");
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
