package de.hohnepeople.keepadb;

import static org.junit.Assert.*;
import org.junit.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KeepADBEndpointTest {

    @Test
    public void testNonBlockingSocketChannelScan() throws Exception {
        ServerSocket serverSocket = new ServerSocket(42888);
        int numWorkers = 4;
        int startPort = 30000;
        int endPort = 50000;
        int totalPorts = endPort - startPort + 1;
        int chunkSize = (totalPorts + numWorkers - 1) / numWorkers;

        List<Integer> allOpen = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < numWorkers; i++) {
            int cs = startPort + i * chunkSize;
            int ce = Math.min(cs + chunkSize - 1, endPort);
            Thread t = new Thread(() -> {
                byte[] loopbackBytes = new byte[]{127, 0, 0, 1};
                try {
                    InetAddress loopback = InetAddress.getByAddress(loopbackBytes);
                    for (int p = cs; p <= ce; p++) {
                        try (SocketChannel ch = SocketChannel.open()) {
                            ch.configureBlocking(false);
                            boolean connected = ch.connect(new InetSocketAddress(loopback, p));
                            if (connected || ch.finishConnect()) {
                                allOpen.add(p);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            });
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) t.join();
        long t1 = System.currentTimeMillis();

        System.out.println(">>> 4-worker non-blocking SocketChannel scan of 20000 ports took: " + (t1 - t0) + "ms! Found: " + allOpen);
        serverSocket.close();

        assertTrue(allOpen.contains(42888));
    }
}
