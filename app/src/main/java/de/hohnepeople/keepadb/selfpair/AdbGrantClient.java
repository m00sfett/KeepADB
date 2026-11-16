package de.hohnepeople.keepadb.selfpair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * A deliberately crippled ADB client (#962): it connects to {@code adbd} on this device, runs
 * exactly one hard-coded command, the {@code WRITE_SECURE_SETTINGS} grant for one package, and
 * disconnects. There is no other command path.
 */
final class AdbGrantClient {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_STLS = 0x534c5453;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_VERSION = 0x01000000;
    private static final int A_STLS_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    /** Upper bound for a payload we accept; adbd's banner and any error text are far smaller. */
    private static final int MAX_ACCEPTED_PAYLOAD = 256 * 1024;
    private static final int MAX_OUTPUT = 64 * 1024;
    private static final int LOCAL_ID = 1;
    private static final int TIMEOUT_MS = 10_000;
    private static final int OPERATION_TIMEOUT_MS = 20_000;
    /** More output packets than {@code pm grant} could ever need, error text included. */
    private static final int MAX_OUTPUT_PACKETS = 64;

    private final TlsChannel.Factory tls;

    AdbGrantClient(TlsChannel.Factory tls) {
        this.tls = tls;
    }

    /** The one command this client can run. Package names cannot contain shell metacharacters. */
    static String command(String packageName) {
        if (!packageName.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("not a package name");
        }
        return "pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS";
    }

    /**
     * Runs the grant and returns what the command printed; an empty string means {@code pm}
     * reported no error. The caller still has to check the permission itself.
     */
    String grant(int port, AdbIdentity identity, String packageName)
            throws IOException, GeneralSecurityException {
        String command = command(packageName);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            try (Deadline deadline = Deadline.closeAfter(socket, OPERATION_TIMEOUT_MS)) {
                write(socket.getOutputStream(), A_CNXN, A_VERSION, MAX_DATA,
                        nulTerminated("host::"));
                Message reply = read(socket.getInputStream());
                if (reply.command != A_STLS) {
                    throw new IOException("adbd did not offer TLS");
                }
                write(socket.getOutputStream(), A_STLS, A_STLS_VERSION, 0, new byte[0]);
                try (TlsChannel channel = tls.handshake(socket, identity)) {
                    return runCommand(channel.input(), channel.output(), command);
                }
            }
        } finally {
            socket.close();
        }
    }

    private static String runCommand(InputStream in, OutputStream out, String command)
            throws IOException {
        if (read(in).command != A_CNXN) {
            throw new IOException("adbd did not accept the connection");
        }
        write(out, A_OPEN, LOCAL_ID, 0, nulTerminated("shell:" + command));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int packets = 0;
        Message message = read(in);
        if (message.command == A_OKAY) {
            while (true) {
                message = read(in);
                if (message.command == A_WRTE) {
                    if (++packets > MAX_OUTPUT_PACKETS
                            || output.size() + message.data.length > MAX_OUTPUT) {
                        throw new IOException("command output too long");
                    }
                    output.write(message.data, 0, message.data.length);
                    write(out, A_OKAY, LOCAL_ID, message.arg0, new byte[0]);
                } else if (message.command == A_CLSE) {
                    break;
                } else {
                    throw new IOException("unexpected adb message");
                }
            }
        } else if (message.command != A_CLSE) {
            throw new IOException("adbd refused the command stream");
        }
        write(out, A_CLSE, LOCAL_ID, message.arg0, new byte[0]);
        return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static byte[] nulTerminated(String text) {
        return Bytes.concat(text.getBytes(StandardCharsets.UTF_8), new byte[1]);
    }

    private static void write(OutputStream out, int command, int arg0, int arg1, byte[] data)
            throws IOException {
        int checksum = 0;
        for (byte b : data) {
            checksum += b & 0xff;
        }
        ByteBuffer buffer = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum)
                .putInt(~command).put(data);
        out.write(buffer.array());
        out.flush();
    }

    private static Message read(InputStream in) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(Bytes.readFully(in, 24))
                .order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt();
        int arg0 = header.getInt();
        header.getInt();
        int length = header.getInt();
        header.getInt();
        int magic = header.getInt();
        if (magic != ~command || length < 0 || length > MAX_ACCEPTED_PAYLOAD) {
            throw new IOException("malformed adb message");
        }
        return new Message(command, arg0, Bytes.readFully(in, length));
    }

    private static final class Message {
        final int command;
        final int arg0;
        final byte[] data;

        Message(int command, int arg0, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.data = data;
        }
    }
}
