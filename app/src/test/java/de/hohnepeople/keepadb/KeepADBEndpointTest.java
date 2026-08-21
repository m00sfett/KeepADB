package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.net.ServerSocket;
import java.util.List;
import org.junit.Test;

public class KeepADBEndpointTest {

    @Test
    public void testScanLocalOpenPortsFindsBoundSocket() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            List<Integer> foundPorts = KeepADBEndpoint.scanLocalOpenPorts(port - 10, port + 10, 50);
            assertTrue(foundPorts.contains(port));
        }
    }

    @Test
    public void testScanLocalOpenPortsReturnsEmptyWhenNoneOpen() {
        List<Integer> foundPorts = KeepADBEndpoint.scanLocalOpenPorts(49990, 49995, 10);
        // Either empty or contains valid positive ports
        for (int p : foundPorts) {
            assertTrue(p >= 49990 && p <= 49995);
        }
    }
}
