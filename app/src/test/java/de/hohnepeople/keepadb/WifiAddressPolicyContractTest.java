package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * #699: structural contracts for the split between the stateless {@link WifiAddressPolicy} and the
 * single owner {@link KeepADBNetwork}. {@link KeepADBWifiAddressPolicyWiringTest}, {@link
 * KeepADBEndpointAddressBindingTest} and {@link KeepADBScopeFallbackVisibilityTest} pin what the
 * decision does; these pin what a behavior test cannot see, namely that the policy stays free of
 * state, observation and I/O, that nothing but {@link KeepADBNetwork} calls it, and that no second
 * copy of the decision grew back next to the tracker.
 */
public class WifiAddressPolicyContractTest {
    private static final String SOURCE_DIRECTORY = "app/src/main/java/de/hohnepeople/keepadb/";

    /**
     * No field means no cache, tracker, lock or listener can live in the policy, and no instance
     * method or constructor means it cannot be created and kept either. The nested types are the
     * two the extraction moved; a third one would be a place to put state.
     */
    @Test
    public void thePolicyHoldsNoStateAndCannotBeInstantiated() {
        assertTrue(Modifier.isFinal(WifiAddressPolicy.class.getModifiers()));
        assertEquals("the policy must declare no field at all", 0,
                WifiAddressPolicy.class.getDeclaredFields().length);
        Constructor<?>[] constructors = WifiAddressPolicy.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue("the only constructor must be private",
                Modifier.isPrivate(constructors[0].getModifiers()));
        for (Method method : WifiAddressPolicy.class.getDeclaredMethods()) {
            assertTrue("the policy must only declare static methods: " + method,
                    Modifier.isStatic(method.getModifiers()));
        }

        Set<String> nested = new HashSet<>();
        for (Class<?> type : WifiAddressPolicy.class.getDeclaredClasses()) {
            nested.add(type.getSimpleName());
        }
        assertEquals("the policy declares exactly the moved types",
                new HashSet<>(Arrays.asList("ScopeCandidate", "ScopeFallbackSink")), nested);

        for (Field field : WifiAddressPolicy.ScopeCandidate.class.getDeclaredFields()) {
            assertTrue("a ScopeCandidate is an immutable snapshot, field must be final: " + field,
                    Modifier.isFinal(field.getModifiers()));
            assertFalse("a ScopeCandidate must not hold static state: " + field,
                    Modifier.isStatic(field.getModifiers()));
        }
        assertEquals("the sink stays a single-method callback", 1,
                WifiAddressPolicy.ScopeFallbackSink.class.getDeclaredMethods().length);
    }

    /**
     * The policy does not observe the device: no Android class, no I/O, no threading primitive, and
     * no call on a {@link java.net.NetworkInterface}, which it only carries as an opaque value. The
     * enumeration and the reading of a live interface stay in {@link KeepADBNetwork}.
     */
    @Test
    public void thePolicyPerformsNoIoAndHasNoAndroidDependency() throws IOException {
        String code = withoutComments(read(SOURCE_DIRECTORY + "WifiAddressPolicy.java"));

        Set<String> imports = new HashSet<>();
        Matcher matcher = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+);").matcher(code);
        while (matcher.find()) imports.add(matcher.group(1));
        assertEquals("the policy may only depend on the address types and collections it decides on",
                new HashSet<>(Arrays.asList("java.net.Inet6Address", "java.net.InetAddress",
                        "java.net.NetworkInterface", "java.util.Arrays", "java.util.List")),
                imports);

        for (String forbidden : new String[] {"android.", "java.io.", "java.nio.", "javax.",
                "java.util.concurrent", "Context", "ConnectivityManager", "NetworkCallback", "synchronized", "volatile", "Thread", "Handler", "Looper", "SharedPreferences",
                "KeepADBDiagnostics", "KeepADBNetwork.", "KeepADBPreferences", "System.",
                "getNetworkInterfaces", "getByInetAddress", "getInetAddresses", "getInterfaceAddresses",
                "getHardwareAddress", "InetAddress.getByName", "InetAddress.getAllByName",
                "InetAddress.getByAddress", "Inet6Address.getByAddress"}) {
            assertFalse("the policy must not contain '" + forbidden + "'", code.contains(forbidden));
        }
        // Not a single method is called on a NetworkInterface, neither statically nor on a value.
        assertFalse("no static call on NetworkInterface",
                Pattern.compile("\\bNetworkInterface\\s*\\.").matcher(code).find());
        assertFalse("no call on a NetworkInterface value",
                Pattern.compile("\\.networkInterface\\s*\\.|\\bnetworkInterface\\s*\\.\\s*\\w+\\s*\\(")
                        .matcher(code).find());
        for (String interfaceCall : new String[] {".isUp(", ".isLoopback(", ".getIndex(", ".getName(",
                ".getDisplayName(", ".isVirtual(", ".isPointToPoint(", ".getMTU("}) {
            assertFalse("the policy must not call " + interfaceCall, code.contains(interfaceCall));
        }
    }

    /**
     * The decision has one caller. {@link KeepADBNetwork} owns the callbacks, the tracked maps, the
     * fallback permission and the interface lookups that feed it, so nothing else may call the
     * policy with data of its own.
     */
    @Test
    public void onlyKeepADBNetworkCallsThePolicy() throws IOException {
        List<String> users = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> files = Files.list(projectPath(SOURCE_DIRECTORY))) {
            sources = files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        assertTrue("The scan must see the main sources", sources.size() > 50);
        for (Path file : sources) {
            String name = file.getFileName().toString();
            if (name.equals("WifiAddressPolicy.java")) continue;
            String code = withoutComments(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (code.contains("WifiAddressPolicy")) users.add(name);
        }
        assertEquals("Only KeepADBNetwork may use the policy: " + users,
                List.of("KeepADBNetwork.java"), users);
    }

    /**
     * No second copy of the decision, the sink type or the snapshot type grew back into the
     * tracker, and the tracker still reads the live interface itself: the snapshot adapter and the
     * calls on {@code NetworkInterface} are here, in code and not only in a comment.
     */
    @Test
    public void keepADBNetworkHoldsNoCopyOfTheDecisionButKeepsTheInterfaceIo() throws IOException {
        String network = withoutComments(read(SOURCE_DIRECTORY + "KeepADBNetwork.java"));

        for (String moved : new String[] {"boolean matchesActiveWifiAddress(",
                "resolveScopeInterfaceByByteMatch(List", "interface ScopeFallbackSink",
                "class ScopeCandidate", "isMulticastAddress", "isAnyLocalAddress", "getScopeId",
                "hasAddress("}) {
            assertFalse("KeepADBNetwork must not hold its own copy of '" + moved + "' (#699)",
                    network.contains(moved));
        }
        assertEquals("the live path calls the policy for the decision", 1,
                count(network, "WifiAddressPolicy.matchesActiveWifiAddress("));
        assertEquals("the live path calls the policy for the byte match", 1,
                count(network, "WifiAddressPolicy.resolveScopeInterfaceByByteMatch("));
        assertEquals("the live path hands the diagnostics sink to the policy", 1,
                count(network, "this::reportScopeFallback"));

        for (String interfaceIo : new String[] {"NetworkInterface.getByName(",
                "NetworkInterface.getNetworkInterfaces()", "networkInterface.isUp()",
                "networkInterface.isLoopback()", "networkInterface.getInetAddresses()",
                "WifiAddressPolicy.ScopeCandidate scopeCandidateOf(NetworkInterface"}) {
            assertEquals("KeepADBNetwork must keep the interface I/O '" + interfaceIo + "' (#699)", 1,
                    count(network, interfaceIo));
        }
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
    }

    /** The code without {@code //} and block comments, so documentation cannot satisfy or trip a guard. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory.resolve(relativePath);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectPath(relativePath)), StandardCharsets.UTF_8);
    }
}
