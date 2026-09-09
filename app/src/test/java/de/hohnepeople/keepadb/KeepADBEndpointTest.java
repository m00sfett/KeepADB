package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class KeepADBEndpointTest {

    @Test
    public void testNonBlockingSocketChannelScanWithEphemeralPort() throws Exception {
        int boundPort;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            boundPort = serverSocket.getLocalPort();
            int startPort = boundPort - 20;
            int endPort = boundPort + 20;
            int totalPorts = endPort - startPort + 1;
            int numWorkers = 2;
            int chunkSize = (totalPorts + numWorkers - 1) / numWorkers;

            List<Integer> allOpen = Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();

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
            assertTrue(allOpen.contains(boundPort));
        }
    }

    @Test
    public void endpointConstantsArePlausible() {
        assertEquals("_adb-tls-connect._tcp.", KeepADBEndpoint.SERVICE_TYPE);
        assertEquals(30000, KeepADBEndpoint.PROBE_START_PORT);
        assertEquals(50000, KeepADBEndpoint.PROBE_END_PORT);
    }

    @Test
    public void formatEndpointHandlesIpv4AndIpv6() {
        assertEquals("192.168.178.50:41234", KeepADBEndpoint.formatEndpoint("192.168.178.50", 41234));
        assertEquals("[fe80::1]:41234", KeepADBEndpoint.formatEndpoint("fe80::1", 41234));
        assertEquals("[2001:db8::1]:41234", KeepADBEndpoint.formatEndpoint("[2001:db8::1]", 41234));
        assertEquals(":41234", KeepADBEndpoint.formatEndpoint(null, 41234));
    }

    /**
     * #314: loopback and link-local addresses used to be accepted unconditionally here, which
     * is exactly what let a foreign local service pass as an endpoint. The candidate now has to
     * be bound to our active Wi-Fi network; without a context none can be, so all of these are
     * rejected. The accepting direction is covered in
     * {@link KeepADBEndpointAddressBindingTest}, which can supply a candidate address set.
     */
    @Test
    public void addressesAreRejectedWhenNoActiveWifiAddressCanBeDetermined() throws Exception {
        InetAddress loopbackV4 = InetAddress.getByName("127.0.0.1");
        InetAddress loopbackV6 = InetAddress.getByName("::1");
        InetAddress linkLocalV6 = InetAddress.getByName("fe80::1");
        InetAddress routableV4 = InetAddress.getByName("192.168.178.50");

        assertFalse(KeepADBEndpoint.isOwnWifiAddress(null, loopbackV4));
        assertFalse(KeepADBEndpoint.isOwnWifiAddress(null, loopbackV6));
        assertFalse(KeepADBEndpoint.isOwnWifiAddress(null, linkLocalV6));
        assertFalse(KeepADBEndpoint.isOwnWifiAddress(null, routableV4));
        assertFalse(KeepADBEndpoint.isOwnWifiAddress(null, null));
    }
}
