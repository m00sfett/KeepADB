package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

/**
 * #700: the real {@link KeepADBHttpTransport} against a loopback socket server. The fake-transport tests
 * never reach {@code HttpURLConnection}; these pin what the adapter itself does on the wire: only
 * 2xx is success, a redirect is never followed, the timeout is 2000 ms, the body is fixed-length
 * UTF-8, the connection is released on every path and the logs carry no raw URL or address.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBHttpTransportTest {
    private final KeepADBHttpTransport transport = new KeepADBHttpTransport();
    private ServerSocket listener;
    private Thread acceptor;
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final Map<String, String> locationByPath = new ConcurrentHashMap<>();
    private final Set<String> hangingPaths = ConcurrentHashMap.newKeySet();
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    /** One request as the server saw it. */
    private static final class Req {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    @Before
    public void setUp() throws IOException {
        ShadowLog.clear();
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    Thread t = new Thread(() -> serve(socket));
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void tearDown() throws IOException {
        release.countDown();
        listener.close();
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0) return;
                head.write(b);
                matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
            Req req = new Req();
            String[] first = lines[0].split(" ");
            req.method = first[0];
            req.path = first[1];
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    req.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }
            String length = req.headers.get("content-length");
            if (length != null) {
                byte[] body = new byte[Integer.parseInt(length)];
                int off = 0;
                while (off < body.length) {
                    int n = in.read(body, off, body.length - off);
                    if (n < 0) break;
                    off += n;
                }
                req.body = body;
            }
            requests.add(req);
            if (hangingPaths.contains(req.path)) {
                release.await(6, TimeUnit.SECONDS);
                return;
            }
            int code = statusByPath.getOrDefault(req.path, 200);
            String location = locationByPath.get(req.path);
            String response = "HTTP/1.1 " + code + " X\r\nContent-Length: 0\r\nConnection: close\r\n"
                    + (location != null ? "Location: " + location + "\r\n" : "") + "\r\n";
            s.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
        } catch (IOException | InterruptedException ignored) {
            // The client may legitimately abandon the connection.
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + listener.getLocalPort() + path;
    }

    private String logs() {
        StringBuilder all = new StringBuilder();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) all.append(item.msg).append('\n');
        return all.toString();
    }

    @Test
    public void onlyA2xxResponseIsSuccessForPostAndDelete() {
        int[] codes = {200, 201, 204, 299, 300, 304, 400, 404, 500, 503};
        for (int code : codes) statusByPath.put("/c" + code, code);

        for (int code : codes) {
            boolean expected = code >= 200 && code < 300;
            assertEquals("POST " + code, expected, transport.postJson(url("/c" + code), "{}", "x"));
            assertEquals("DELETE " + code, expected, transport.delete(url("/c" + code)));
        }
    }

    @Test
    public void aRedirectIsNeverFollowedEvenToASuccessfulTarget() {
        statusByPath.put("/moved", 302);
        locationByPath.put("/moved", url("/target"));
        statusByPath.put("/target", 200);

        assertFalse(transport.postJson(url("/moved"), "{}", "x"));
        assertFalse(transport.delete(url("/moved")));
        for (Req req : requests) {
            assertEquals("a redirect target must never be contacted", "/moved", req.path);
        }
        assertEquals(2, requests.size());
    }

    @Test
    public void postSendsAFixedLengthUtf8JsonBody() {
        String payload = "{\"name\":\"K\u00e4se \u20ac\"}";
        assertTrue(transport.postJson(url("/p"), payload, "x"));

        byte[] expected = payload.getBytes(StandardCharsets.UTF_8);
        assertTrue("the payload must contain multi-byte characters", expected.length > payload.length());
        Req req = requests.get(0);
        assertEquals("POST", req.method);
        assertEquals("application/json; charset=utf-8", req.headers.get("content-type"));
        assertEquals(String.valueOf(expected.length), req.headers.get("content-length"));
        assertNull("fixed-length streaming must not fall back to chunked",
                req.headers.get("transfer-encoding"));
        assertEquals(payload, new String(req.body, StandardCharsets.UTF_8));
    }

    @Test
    public void deleteSendsADeleteRequestWithoutABody() {
        assertTrue(transport.delete(url("/d")));
        Req req = requests.get(0);
        assertEquals("DELETE", req.method);
        assertEquals(0, req.body.length);
        String length = req.headers.get("content-length");
        assertTrue(length == null || "0".equals(length));
    }

    @Test
    public void aSlowServerIsGivenUpOnAfterTheTwoSecondTimeout() throws Exception {
        hangingPaths.add("/slow");
        try {
            for (String kind : new String[] {"POST", "DELETE"}) {
                long start = System.nanoTime();
                boolean ok = kind.equals("POST")
                        ? transport.postJson(url("/slow"), "{}", "x")
                        : transport.delete(url("/slow"));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertFalse(kind, ok);
                assertTrue(kind + " gave up too early: " + elapsedMs, elapsedMs >= 1800);
                assertTrue(kind + " gave up too late: " + elapsedMs, elapsedMs < 3800);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    public void aFailedRequestLogsOnlyTheRedactedUrl() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        String secretUrl = "http://user:hunter2@127.0.0.1:" + closedPort + "/hook?token=s3cr3t";

        assertFalse(transport.postJson(secretUrl, "{}", "127.0.0.1:" + closedPort));
        assertFalse(transport.delete(secretUrl));

        String logged = logs();
        assertTrue("the failures must be logged", logged.contains("Could not update register at ")
                && logged.contains("Could not reach register to unregister at "));
        assertTrue(logged.contains(KeepADBRegisterClient.sanitizeUrl(secretUrl)));
        for (String raw : new String[] {"hunter2", "s3cr3t", "user:", "/hook", "127.0.0.1:" + closedPort}) {
            assertFalse("the log must not contain '" + raw + "': " + logged, logged.contains(raw));
        }
    }

    @Test
    public void aSuccessfulPostLogsTheMaskedEndpointLabelOnly() {
        assertTrue(transport.postJson(url("/ok"), "{}", "192.168.7.42:5555"));

        String logged = logs();
        assertTrue(logged.contains("Register update for " + KeepADBAddressMask.maskEndpointForDisplay("192.168.7.42:5555")
                + " returned HTTP 200"));
        assertFalse(logged.contains("192.168.7.42"));
        assertFalse(logged.contains("127.0.0.1"));
    }

    /**
     * The success response announces a body that is never sent in full and the adapter never
     * reads it, so the only thing that closes the client's socket is {@code disconnect()}; without
     * it the connection would stay open. The broken response is a truncated status line, which
     * makes the request fail with an IOException.
     */
    @Test
    public void theConnectionIsClosedAfterASuccessAndAfterAnIoFailure() throws Exception {
        for (boolean broken : new boolean[] {false, true}) {
            for (String kind : new String[] {"POST", "DELETE"}) {
                try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                    raw.setSoTimeout(5000);
                    AtomicReference<Boolean> closed = new AtomicReference<>();
                    CountDownLatch finished = new CountDownLatch(1);
                    Thread serverThread = new Thread(() -> {
                        try (Socket socket = raw.accept()) {
                            socket.setSoTimeout(4500);
                            InputStream in = socket.getInputStream();
                            byte[] buffer = new byte[4096];
                            in.read(buffer);
                            socket.getOutputStream().write(broken
                                    ? "HTTP/1.1 2".getBytes(StandardCharsets.UTF_8)
                                    : "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nab".getBytes(StandardCharsets.UTF_8));
                            socket.getOutputStream().flush();
                            // For the broken case the status line is truncated and then silent:
                            // the client must time out, fail with an IOException and still
                            // release the connection.
                            closed.set(drainUntilEof(in));
                        } catch (IOException e) {
                            closed.set(Boolean.FALSE);
                        } finally {
                            finished.countDown();
                        }
                    });
                    serverThread.start();

                    String target = "http://127.0.0.1:" + raw.getLocalPort() + "/x";
                    boolean ok = kind.equals("POST")
                            ? transport.postJson(target, "{}", "x")
                            : transport.delete(target);
                    assertEquals(kind + " broken=" + broken, !broken, ok);
                    assertTrue(finished.await(6, TimeUnit.SECONDS));
                    assertEquals("the client must close the socket (" + kind + ", broken=" + broken + ")",
                            Boolean.TRUE, closed.get());
                }
            }
        }
    }

    private static Boolean drainUntilEof(InputStream in) {
        byte[] buffer = new byte[256];
        try {
            while (true) {
                int n = in.read(buffer);
                if (n < 0) return Boolean.TRUE;
            }
        } catch (SocketTimeoutException e) {
            return Boolean.FALSE;
        } catch (IOException e) {
            // A reset also means the client closed its end.
            return Boolean.TRUE;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
