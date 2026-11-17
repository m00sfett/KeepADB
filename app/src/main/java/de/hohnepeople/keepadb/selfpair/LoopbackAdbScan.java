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
    private static final int EPHEMERAL_FIRST_PORT = 30000;
    private static final int LAST_PORT = 65535;
    private static final int LOW_FIRST_PORT = 1024;
    private static final int THREADS = 8;
    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int PROBE_TIMEOUT_MS = 500;
    private static final int RANGE_SCAN_TIMEOUT_MS = 15_000;
    private static final int PROBE_BUDGET_MS = 5_000;
    private static final int PROBE_THREADS = 16;
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_STLS = 0x534c5453;

    private LoopbackAdbScan() {
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    static List<Integer> find() throws InterruptedException {
        List<Integer> found = scanRange(EPHEMERAL_FIRST_PORT, LAST_PORT);
        if (!found.isEmpty()) {
            return found;
        }
        return scanRange(LOW_FIRST_PORT, EPHEMERAL_FIRST_PORT - 1);
    }

    static List<Integer> scanRange(int firstPort, int lastPort) throws InterruptedException {
        if (firstPort > lastPort) {
            return Collections.emptyList();
        }
        InetAddress loopback = ipv4Loopback();
        InetAddress defaultLoopback = InetAddress.getLoopbackAddress();
        List<Integer> open = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        int count = lastPort - firstPort + 1;
        int slice = Math.max(1, (count + THREADS - 1) / THREADS);
        for (int start = firstPort; start <= lastPort; start += slice) {
            final int from = start;
            final int to = Math.min(lastPort, start + slice - 1);
            pool.execute(() -> {
                for (int port = from; port <= to && !Thread.currentThread().isInterrupted();
                        port++) {
                    if (isPortBusy(loopback, port)) {
                        open.add(port);
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(RANGE_SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdownNow();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        List<Integer> candidates;
        synchronized (open) {
            candidates = new ArrayList<>(open);
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> adb = Collections.synchronizedList(new ArrayList<>());
        ExecutorService probePool = Executors.newFixedThreadPool(
                Math.min(PROBE_THREADS, candidates.size()));
        // Probe busy ports in parallel so unrelated silent listeners cannot exhaust the budget.
        for (int port : candidates) {
            probePool.execute(() -> {
                if (!Thread.currentThread().isInterrupted()
                        && (offersAdbOverTls(loopback, port)
                                || (!defaultLoopback.equals(loopback)
                                        && offersAdbOverTls(defaultLoopback, port)))) {
                    adb.add(port);
                }
            });
        }
        probePool.shutdown();
        try {
            probePool.awaitTermination(PROBE_BUDGET_MS, TimeUnit.MILLISECONDS);
        } finally {
            probePool.shutdownNow();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        synchronized (adb) {
            return new ArrayList<>(adb);
        }
    }

    /**
     * Checks via a non-reusable socket bind whether {@code port} is already occupied in the kernel
     * port table (avoids Android's per-{@code connect()} {@code fwmarkd} IPC bottleneck).
     */
    private static boolean isPortBusy(InetAddress loopback, int port) {
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(loopback, port), 1);
            return false;
        } catch (IOException | SecurityException e) {
            return true;
        }
    }

    /** Sends ADB's connect message and checks that the reply is the TLS upgrade request. */
    static boolean offersAdbOverTls(int port) {
        return offersAdbOverTls(InetAddress.getLoopbackAddress(), port);
    }

    private static boolean offersAdbOverTls(InetAddress loopback, int port) {
        try (Socket socket = new Socket();
                Deadline deadline = Deadline.closeAfter(socket, 2 * PROBE_TIMEOUT_MS)) {
            socket.connect(new InetSocketAddress(loopback, port), CONNECT_TIMEOUT_MS);
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
