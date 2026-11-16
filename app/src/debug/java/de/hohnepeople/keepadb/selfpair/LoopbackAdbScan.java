package de.hohnepeople.keepadb.selfpair;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fallback for finding {@code adbd}'s TLS port when mDNS does not report it (#962). Observed on
 * Android 11: a connect service that was already announced before the discovery started is
 * never delivered. The scan only ever touches the loopback interface: it looks for listening
 * ports and keeps those that answer an ADB connect with a TLS upgrade request.
 */
final class LoopbackAdbScan {
    private static final int FIRST_PORT = 1024;
    private static final int LAST_PORT = 65535;
    private static final int THREADS = 16;
    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int PROBE_TIMEOUT_MS = 500;
    private static final int SCAN_TIMEOUT_MS = 15_000;
    private static final int MAX_PROBES = 32;
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_STLS = 0x534c5453;

    private LoopbackAdbScan() {
    }

    static List<Integer> find() throws InterruptedException {
        List<Integer> open = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        int slice = (LAST_PORT - FIRST_PORT + THREADS) / THREADS;
        for (int start = FIRST_PORT; start <= LAST_PORT; start += slice) {
            final int from = start;
            final int to = Math.min(LAST_PORT, start + slice - 1);
            pool.execute(() -> {
                for (int port = from; port <= to && !Thread.currentThread().isInterrupted();
                        port++) {
                    if (isListening(port)) {
                        open.add(port);
                    }
                }
            });
        }
        pool.shutdown();
        boolean finished;
        try {
            finished = pool.awaitTermination(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<Integer> adb = new ArrayList<>();
        if (!finished) {
            return adb;
        }
        List<Integer> candidates;
        synchronized (open) {
            candidates = new ArrayList<>(open);
        }
        // A device has a handful of loopback listeners; more means something is wrong.
        if (candidates.size() > MAX_PROBES) {
            candidates = candidates.subList(0, MAX_PROBES);
        }
        for (int port : candidates) {
            if (offersAdbOverTls(port)) {
                adb.add(port);
            }
        }
        return adb;
    }

    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Sends ADB's connect message and checks that the reply is the TLS upgrade request. */
    static boolean offersAdbOverTls(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_TIMEOUT_MS);
            byte[] banner = {'h', 'o', 's', 't', ':', ':', 0};
            int checksum = 0;
            for (byte b : banner) {
                checksum += b & 0xff;
            }
            ByteBuffer hello = ByteBuffer.allocate(24 + banner.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            hello.putInt(A_CNXN).putInt(0x01000000).putInt(4096).putInt(banner.length)
                    .putInt(checksum).putInt(~A_CNXN).put(banner);
            OutputStream out = socket.getOutputStream();
            out.write(hello.array());
            out.flush();
            ByteBuffer reply = ByteBuffer.wrap(Bytes.readFully(socket.getInputStream(), 24))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int command = reply.getInt(0);
            return command == A_STLS && reply.getInt(20) == ~command;
        } catch (IOException e) {
            return false;
        }
    }
}
