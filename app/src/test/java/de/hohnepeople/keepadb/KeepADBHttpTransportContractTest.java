package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * #700: structural contracts for the split between the stateless HTTP adapter {@link
 * KeepADBHttpTransport} and the single transaction owner {@link KeepADBRegisterClient}. {@link
 * KeepADBHttpTransportTest} pins what the adapter does on the wire; these pin what a behavior test
 * cannot see: that no second owner of state, scheduling or a connection grew in the adapter, that
 * the client no longer carries HTTP configuration of its own, and that every request path keeps
 * the no-redirect, timeout, fixed-length, release and log-redaction guards in the new file.
 */
public class KeepADBHttpTransportContractTest {
    private static final String SOURCE_DIRECTORY = "app/src/main/java/de/hohnepeople/keepadb/";
    private static final String CLIENT = "KeepADBRegisterClient.java";
    private static final String ADAPTER = "KeepADBHttpTransport.java";

    @Test
    public void theAdapterHoldsNoStateAndSchedulesNothing() throws IOException {
        Class<?> type = KeepADBHttpTransport.class;
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertFalse("package-private, not public API", Modifier.isPublic(type.getModifiers()));
        assertTrue(KeepADBRegisterClient.HttpTransport.class.isAssignableFrom(type));
        assertEquals("no nested type that could carry state", 0, type.getDeclaredClasses().length);

        Set<String> fields = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            int modifiers = field.getModifiers();
            assertTrue("only static final constants may be declared: " + field,
                    Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers));
            fields.add(field.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList("TAG", "TIMEOUT_MS")), fields);

        Set<String> methods = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic()) methods.add(method.getName());
        }
        assertEquals("the adapter implements the seam and nothing else",
                new HashSet<>(Arrays.asList("postJson", "delete")), methods);

        String code = withoutComments(read(SOURCE_DIRECTORY + ADAPTER));
        Set<String> imports = new HashSet<>();
        Matcher matcher = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+);").matcher(code);
        while (matcher.find()) imports.add(matcher.group(1));
        assertEquals(new HashSet<>(Arrays.asList("android.util.Log", "java.io.IOException",
                "java.io.OutputStream", "java.net.HttpURLConnection", "java.net.URL",
                "java.nio.charset.StandardCharsets")), imports);

        String logic = withoutStrings(code);
        for (String forbidden : new String[] {"synchronized", "volatile", "Thread", "Executor",
                "Handler", "Looper", "Timer", "Scheduler", "Lock", "Atomic", "Generation", "opGen",
                "SystemClock", "currentTimeMillis", "elapsedRealtime", "Context",
                "SharedPreferences", "Queue", "List", "Map", "Set", "Runnable",
                "PendingCleanupRetryRepository", "KeepADBPreferences"}) {
            assertFalse("the adapter must not contain '" + forbidden + "'",
                    Pattern.compile("\\b" + Pattern.quote(forbidden) + "\\b").matcher(logic).find());
        }
    }

    @Test
    public void everyRequestKeepsTheHttpConfigurationAndReleasesTheConnection() throws IOException {
        String code = withoutComments(read(SOURCE_DIRECTORY + ADAPTER));

        assertEquals(1, count(code, "private static final int TIMEOUT_MS = 2000;"));
        assertEquals("redirects are disabled for POST and DELETE", 2,
                count(code, "conn.setInstanceFollowRedirects(false);"));
        assertEquals("redirects are never enabled", 0, count(code, "FollowRedirects(true"));
        assertEquals(2, count(code, "conn.setConnectTimeout(TIMEOUT_MS);"));
        assertEquals(2, count(code, "conn.setReadTimeout(TIMEOUT_MS);"));
        assertEquals("only 2xx is success for POST and DELETE", 2,
                count(code, "return code >= 200 && code < 300;"));
        assertEquals("no other way to report success", 0, count(code, "return true"));
        assertEquals(1, count(code, "payload.getBytes(StandardCharsets.UTF_8)"));
        assertEquals(1, count(code, "conn.setFixedLengthStreamingMode(bytes.length);"));
        assertEquals(1, count(code,
                "conn.setRequestProperty(\"Content-Type\", \"application/json; charset=utf-8\");"));

        for (String signature : new String[] {"public boolean postJson(", "public boolean delete("}) {
            String body = bodyOf(code, signature);
            assertTrue(signature + " must release the connection in a finally block",
                    Pattern.compile("finally\\s*\\{\\s*if\\s*\\(\\s*conn\\s*!=\\s*null\\s*\\)\\s*\\{\\s*"
                            + "conn\\.disconnect\\(\\);\\s*\\}\\s*\\}").matcher(body).find());
            assertTrue(signature + " must fail closed on an IOException",
                    Pattern.compile("catch\\s*\\(IOException e\\)\\s*\\{[^}]*return false;").matcher(body).find());
        }
    }

    @Test
    public void everyLogLineUsesTheRedactedUrlOrTheMaskedLabel() throws IOException {
        String raw = read(SOURCE_DIRECTORY + ADAPTER);
        String code = withoutComments(raw);

        int logLines = 0;
        for (String statement : code.split(";")) {
            if (!statement.contains("Log.")) continue;
            logLines++;
            String normalized = statement.replaceAll("\\s+", " ");
            for (String sensitive : new String[] {"targetUrl", "logLabel", "payload", "url"}) {
                String stripped = normalized
                        .replace("KeepADBRegisterClient.sanitizeUrl(targetUrl)", "")
                        .replace("KeepADBAddressMask.maskEndpointForDisplay(logLabel)", "");
                assertFalse("a log statement must not carry '" + sensitive + "' unredacted: "
                        + normalized, Pattern.compile("\\b" + sensitive + "\\b").matcher(stripped).find());
            }
        }
        assertEquals("one success and one failure log per request kind", 4, logLines);
        assertEquals(1, count(code, "KeepADBAddressMask.maskEndpointForDisplay(logLabel)"));
        assertEquals(2, count(code, "KeepADBRegisterClient.sanitizeUrl(targetUrl)"));
    }

    @Test
    public void theClientKeepsTheSeamButNoHttpConfigurationOfItsOwn() throws IOException {
        String client = withoutComments(read(SOURCE_DIRECTORY + CLIENT));

        for (String moved : new String[] {"HttpURLConnection", "java.net.URL", "TIMEOUT_MS",
                "setConnectTimeout", "setReadTimeout", "setInstanceFollowRedirects",
                "setFixedLengthStreamingMode", "DefaultHttpTransport", "openConnection"}) {
            assertFalse("the client must not hold '" + moved + "' any more (#700)",
                    client.contains(moved));
        }
        assertTrue(client.contains(
                "private static final HttpTransport DEFAULT_TRANSPORT = new KeepADBHttpTransport();"));
        assertTrue(client.contains("boolean postJson(String targetUrl, String payload, String logLabel);"));
        assertTrue(client.contains("boolean delete(String targetUrl);"));
        assertEquals("one send path per request kind", 1, count(client, "httpTransport.postJson("));
        assertEquals(1, count(client, "httpTransport.delete("));
        assertTrue("the adapter and the client log under the same tag",
                Pattern.compile("TAG = \"KeepADBRegisterClient\"")
                        .matcher(read(SOURCE_DIRECTORY + ADAPTER)).find());
    }

    @Test
    public void theDefaultTransportIsTheAdapterAndResetRestoresIt() throws Exception {
        Field field = KeepADBRegisterClient.class.getDeclaredField("httpTransport");
        field.setAccessible(true);
        try {
            KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport());
            assertTrue(field.get(null) instanceof KeepADBFakeHttpTransport);
            KeepADBRegisterClient.setHttpTransport(null);
            assertTrue(field.get(null) instanceof KeepADBHttpTransport);
            KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport());
            KeepADBRegisterClient.resetHttpTransport();
            assertTrue(field.get(null) instanceof KeepADBHttpTransport);
        } finally {
            KeepADBRegisterClient.resetHttpTransport();
        }
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) count++;
        return count;
    }

    private static String bodyOf(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return code.substring(open, i + 1);
        }
        throw new IllegalStateException("unbalanced braces");
    }

    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) {
                    if (source.charAt(j) == '\\') j++;
                    j++;
                }
                out.append(source, i, Math.min(j + 1, n));
                i = j + 1;
            } else if (source.startsWith("//", i)) {
                int j = source.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (source.startsWith("/*", i)) {
                int j = source.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String withoutStrings(String code) {
        return code.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
