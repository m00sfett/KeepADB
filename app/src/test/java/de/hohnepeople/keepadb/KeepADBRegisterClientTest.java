package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class KeepADBRegisterClientTest {

    private ServerSocket testServer;
    private int testServerPort;
    private Thread serverThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<String> recordedRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger responseCode = new AtomicInteger(200);

    @Before
    public void setUp() throws Exception {
        KeepADBRegisterClient.resetForTesting();
        testServer = new ServerSocket(0);
        testServerPort = testServer.getLocalPort();
        running.set(true);
        recordedRequests.clear();
        responseCode.set(200);

        serverThread = new Thread(() -> {
            while (running.get() && !testServer.isClosed()) {
                try (Socket socket = testServer.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if (line != null) {
                        recordedRequests.add(line);
                    }
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    if (contentLength > 0) {
                        char[] bodyChars = new char[contentLength];
                        int read = reader.read(bodyChars, 0, contentLength);
                        if (read > 0) {
                            recordedRequests.add(new String(bodyChars, 0, read));
                        }
                    }
                    OutputStream out = socket.getOutputStream();
                    int code = responseCode.get();
                    String statusText = (code == 200) ? "OK" : "Error";
                    String response = "HTTP/1.1 " + code + " " + statusText + "\r\nContent-Length: 0\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        });
        serverThread.start();
    }

    @After
    public void tearDown() {
        running.set(false);
        if (testServer != null) {
            try {
                testServer.close();
            } catch (Exception ignored) {
            }
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
        KeepADBRegisterClient.resetForTesting();
    }

    @Test
    public void testPostEndpointSuccess() {
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.postEndpoint(url, "192.168.1.50:41234");
        assertTrue(success);
        assertFalse(recordedRequests.isEmpty());
        assertTrue(recordedRequests.get(0).startsWith("POST"));
        assertTrue(recordedRequests.toString().contains("192.168.1.50:41234"));
    }

    @Test
    public void testDeleteEndpointSuccess() {
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.deleteEndpoint(url);
        assertTrue(success);
        assertFalse(recordedRequests.isEmpty());
        assertTrue(recordedRequests.get(0).startsWith("DELETE"));
    }

    @Test
    public void testPostEndpointFailureOn500() {
        responseCode.set(500);
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.postEndpoint(url, "192.168.1.50:41234");
        assertFalse(success);
    }

    @Test
    public void testWebhookUrlValidation() {
        assertTrue(KeepADBPreferences.isValidWebhookUrl("http://192.168.1.1:8080/hook"));
        assertTrue(KeepADBPreferences.isValidWebhookUrl("https://example.com/api/register"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("ftp://example.com"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("invalid-url"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl(null));
        assertFalse(KeepADBPreferences.isValidWebhookUrl(""));
    }

    @Test
    public void testSanitizeUrl() {
        assertEquals("https://example.com:8443",
                KeepADBRegisterClient.sanitizeUrl("https://user:password@example.com:8443/api/register?token=secret#fragment"));
        assertEquals("http://192.168.1.10:8080",
                KeepADBRegisterClient.sanitizeUrl("http://192.168.1.10:8080/hook?secret=12345"));
        assertEquals("null", KeepADBRegisterClient.sanitizeUrl(null));
    }

    @Test
    public void testNetworkSecurityConfigPermitsCleartextTraffic() throws Exception {
        java.nio.file.Path directory = java.nio.file.Paths.get("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        String xml = new String(java.nio.file.Files.readAllBytes(directory.resolve("app/src/main/res/xml/network_security_config.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<base-config cleartextTrafficPermitted=\"true\">"));
    }
}
