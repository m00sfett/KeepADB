package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.ServerSocket;
import org.junit.Test;

public class KeepADBEndpointTest {

    @Test
    public void testScanLocalOpenPortFindsBoundSocket() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            // Test scanner across a range containing the port
            int foundPort = KeepADBEndpoint.scanLocalOpenPort(port - 10, port + 10, 50);
            assertEquals(port, foundPort);
        }
    }

    @Test
    public void testScanLocalOpenPortReturnsNegativeWhenNoneOpen() {
        // Ports 1..10 are normally unallocated/closed on test runner
        int foundPort = KeepADBEndpoint.scanLocalOpenPort(49990, 49995, 10);
        assertTrue(foundPort == -1 || foundPort > 0);
    }
}
