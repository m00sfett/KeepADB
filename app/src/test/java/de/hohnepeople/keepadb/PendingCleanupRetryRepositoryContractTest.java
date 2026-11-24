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
 * #701: structural contracts for the split between the persisted retry record in {@link
 * PendingCleanupRetryRepository} and the single transaction owner {@link KeepADBRegisterClient}.
 * {@link PendingCleanupRetryRepositoryTest}, {@link KeepADBRegisterCleanupLifecycleTest} and {@link
 * KeepADBRegisterClientTest} pin what the record and the client do; these pin what a behavior test
 * cannot see: that the repository stays a store without state, scheduling or generation, that the
 * client keeps the only generation, executor, monitor and HTTP, that the persisted record and the
 * in-memory #562 retry gate keep their separate clocks, and the order the commit blocks write in.
 */
public class PendingCleanupRetryRepositoryContractTest {
    private static final String SOURCE_DIRECTORY = "app/src/main/java/de/hohnepeople/keepadb/";
    private static final String CLIENT = "KeepADBRegisterClient.java";
    private static final String REPOSITORY = "PendingCleanupRetryRepository.java";
    private static final String HTTP_ADAPTER = "KeepADBHttpTransport.java";
    /** #1049: the deferred withdrawals and the #562 gate moved out of the client. */
    private static final String COORDINATOR = "PendingCleanupCoordinator.java";
    private static final String BACKOFF = "UnavailableDeleteBackoff.java";
    private static final String SEAM = "RegisterTransport.java";
    /** #1054: the two transactions moved out of the client; they still use its monitor. */
    private static final String TRANSACTIONS = "RegisterTransactions.java";

    /**
     * No instance and no mutable static field means no queue, cache, lock, timer or generation can
     * live in the repository, and no instance method or constructor means it cannot be created and
     * kept either. The one nested type is the immutable record; a second one would be a place to
     * put state.
     */
    @Test
    public void theRepositoryHoldsNoStateAndCannotBeInstantiated() {
        Class<?> type = PendingCleanupRetryRepository.class;
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Set<String> fields = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            int modifiers = field.getModifiers();
            assertTrue("only static final constants may be declared: " + field,
                    Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers));
            assertTrue("a constant must be a primitive or a String: " + field,
                    field.getType().isPrimitive() || field.getType() == String.class);
            fields.add(field.getName());
        }
        assertEquals("the repository declares exactly the constants it was extracted with",
                new HashSet<>(Arrays.asList("TAG", "PREFS_NAME", "KEY_PENDING_CLEANUP_RETRY_STATE",
                        "MAX_ATTEMPTS", "EXPIRY_MS", "INITIAL_BACKOFF_MS", "MAX_BACKOFF_MS")),
                fields);

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue("the only constructor must be private",
                Modifier.isPrivate(constructors[0].getModifiers()));
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertTrue("the repository must only declare static methods: " + method,
                    Modifier.isStatic(method.getModifiers()));
        }

        Set<String> nested = new HashSet<>();
        for (Class<?> inner : type.getDeclaredClasses()) {
            nested.add(inner.getSimpleName());
        }
        assertEquals(new HashSet<>(Arrays.asList("RetryState")), nested);
        Field[] recordFields = PendingCleanupRetryRepository.RetryState.class.getDeclaredFields();
        assertEquals("a record is attempts, nextAttemptAt and expiresAt", 3, recordFields.length);
        for (Field field : recordFields) {
            assertTrue("a RetryState is an immutable snapshot, field must be final: " + field,
                    Modifier.isFinal(field.getModifiers()) && !Modifier.isStatic(field.getModifiers()));
        }
    }

    /**
     * The repository stores, it neither schedules, observes the clock, talks to the network nor
     * knows the client: no threading or locking primitive, no generation, no clock, no HTTP and no
     * collection that could cache. It depends on the preferences store, a logger and nothing else.
     */
    @Test
    public void theRepositoryNeitherSchedulesNorReadsAClockNorCallsTheNetwork() throws IOException {
        String code = withoutComments(read(SOURCE_DIRECTORY + REPOSITORY));

        Set<String> imports = new HashSet<>();
        Matcher matcher = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+);").matcher(code);
        while (matcher.find()) imports.add(matcher.group(1));
        assertEquals(new HashSet<>(Arrays.asList("android.content.Context",
                "android.content.SharedPreferences", "android.util.Log")), imports);

        String logic = withoutStrings(code);
        for (String forbidden : new String[] {"synchronized", "volatile", "Thread", "Executor",
                "Handler", "Looper", "Timer", "Scheduler", "Lock", "Atomic", "Generation", "opGen",
                "SystemClock", "currentTimeMillis", "elapsedRealtime", "System", "HttpURLConnection",
                "java.net", "java.util", "KeepADBRegisterClient", "KeepADBPreferences",
                "markUnavailable", "Map", "Cache", "Queue", "List", "Set"}) {
            assertFalse("the repository must not contain '" + forbidden + "'",
                    Pattern.compile("\\b" + Pattern.quote(forbidden) + "\\b").matcher(logic).find());
        }
    }

    /**
     * The record has one caller, and the client is the only class that holds the generation and the
     * executor; the HTTP connection lives in the stateless adapter {@code KeepADBHttpTransport}
     * (#700), the only other class that may open one. A second owner of any of them would be a
     * second transaction owner, which is exactly what this extraction must not create.
     */
    @Test
    public void onlyTheClientUsesTheRepositoryAndOwnsGenerationExecutorAndHttp() throws IOException {
        List<String> users = new ArrayList<>();
        List<String> generationOwners = new ArrayList<>();
        List<String> executors = new ArrayList<>();
        List<String> connections = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> files = Files.list(projectPath(SOURCE_DIRECTORY))) {
            sources = files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        assertTrue("The scan must see the main sources", sources.size() > 50);
        for (Path file : sources) {
            String name = file.getFileName().toString();
            String code = withoutComments(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            // #1049: the #562 gate shares only the saturating addition, not the record.
            String recordUse = code.replace("PendingCleanupRetryRepository.saturatingAdd(", "");
            if (!name.equals(REPOSITORY) && recordUse.contains("PendingCleanupRetryRepository")) {
                users.add(name);
            }
            if (Pattern.compile("\\bvolatile\\s+long\\s+currentOpGeneration\\b").matcher(code).find()) {
                generationOwners.add(name);
            }
            if (code.contains("newSingleThreadExecutor") && code.contains("KeepADBRegisterPush")) {
                executors.add(name);
            }
            if (code.contains("HttpURLConnection")) connections.add(name);
        }
        assertEquals("Only the cleanup coordinator may use the repository (#1049): " + users,
                List.of(COORDINATOR), users);
        assertEquals("The client is the only owner of currentOpGeneration: " + generationOwners,
                List.of(CLIENT), generationOwners);
        assertEquals("The client is the only owner of the register executor: " + executors,
                List.of(CLIENT), executors);
        assertEquals("The HTTP adapter is the only class that opens a connection: " + connections,
                List.of(HTTP_ADAPTER), connections);
    }

    /**
     * No second copy of the record grew back into the client, which also does not touch the
     * preferences file itself any more, while the decision and the clocks stay in it: the persisted
     * record keeps the wall clock, the in-memory #562 gate keeps the monotonic one, and the two are
     * not mixed (the behavior tests install a seam for either clock and would not notice).
     */
    @Test
    public void theClientHoldsNoCopyOfTheRecordButKeepsTheDecisionAndBothClocks() throws IOException {
        String client = withoutComments(read(SOURCE_DIRECTORY + CLIENT));
        String coordinator = withoutComments(read(SOURCE_DIRECTORY + COORDINATOR));
        String backoff = withoutComments(read(SOURCE_DIRECTORY + BACKOFF));
        String transactions = withoutComments(read(SOURCE_DIRECTORY + TRANSACTIONS));

        for (String moved : new String[] {"register_webhook_pending_cleanup_retry_state",
                "getSharedPreferences", "SharedPreferences", "PREFS_NAME", "PendingCleanupRetryState",
                "pendingCleanupBackoffMs", "readPendingCleanupRetryState",
                "writePendingCleanupRetryState", "removePendingCleanupRetryState",
                "pendingCleanupRetryStateKey", "MAX_PENDING_CLEANUP_ATTEMPTS",
                "PENDING_CLEANUP_EXPIRY_MS", "PENDING_CLEANUP_INITIAL_BACKOFF_MS",
                "PENDING_CLEANUP_MAX_BACKOFF_MS"}) {
            for (String code : new String[] {client, coordinator, backoff, transactions}) {
                assertFalse("no copy of '" + moved + "' outside the repository (#701)",
                        code.contains(moved));
            }
        }
        assertFalse("#1049: the client leaves the record to the coordinator",
                client.contains("PendingCleanupRetryRepository"));
        assertFalse("#1054: and so do the transactions",
                transactions.contains("PendingCleanupRetryRepository"));
        assertEquals("the coordinator reads a record in exactly one place", 1,
                count(coordinator, "PendingCleanupRetryRepository.read("));
        assertEquals("the coordinator books a failure in exactly one place", 1,
                count(coordinator, "PendingCleanupRetryRepository.recordFailure("));
        assertEquals("the coordinator removes a record when it discards or drops an entry, and "
                + "(#707) for the entry a full backlog evicted when it queues one", 3,
                count(coordinator, "PendingCleanupRetryRepository.remove("));
        assertEquals("the coordinator sweeps the records of dead entries in exactly one place (#707)",
                1, count(coordinator, "PendingCleanupRetryRepository.removeOrphans("));

        String persistedClock = bodyOf(coordinator, "static long now()");
        assertTrue(persistedClock.contains("System.currentTimeMillis()"));
        assertFalse("the persisted record must stay on the wall clock, a monotonic one restarts "
                + "near zero on every reboot", coordinator.contains("SystemClock"));
        String gateClock = bodyOf(backoff, "long now()");
        assertTrue(gateClock.contains("SystemClock.elapsedRealtime()"));
        assertFalse("the in-memory #562 gate must stay on the monotonic clock",
                backoff.contains("currentTimeMillis"));
        assertTrue("the persisted record is handed the wall clock and nothing else",
                bodyOf(coordinator, "private static PendingCleanupRetryRepository.RetryState due(")
                        .contains("now()"));
        assertTrue(bodyOf(coordinator, "private static void recordFailure(").contains("now()"));
        for (String gate : new String[] {"UnavailableDeleteBackoff", "UNAVAILABLE",
                "elapsedRealtime"}) {
            assertFalse("the persisted record must not use the #562 gate's clock: " + gate,
                    coordinator.contains(gate));
        }
        assertFalse("and the client keeps no clock of either kind for them",
                client.contains("currentTimeMillis"));
        assertFalse("nor do the transactions (#1054)", transactions.contains("currentTimeMillis"));
    }

    /**
     * The generation keeps its four increments: the three dispatching entry points and the test
     * seam. A fifth would be another place that decides which operation is current.
     */
    @Test
    public void theClientStaysTheOnlyPlaceThatAdvancesTheGeneration() throws IOException {
        String client = withoutComments(read(SOURCE_DIRECTORY + CLIENT));

        assertEquals("one volatile generation field", 1,
                count(client, "static volatile long currentOpGeneration"));
        assertEquals("update, markUnavailable, unregister and the test seam advance it", 4,
                count(client, "++currentOpGeneration"));
        assertEquals("a generation is only ever advanced; it is assigned only by its declaration "
                + "and the test reset", 2, count(client, "currentOpGeneration = "));
    }

    /**
     * No network request may run while the class monitor is held: the monitor only commits state,
     * and a blocked request inside it would stall every other register call and every reader of
     * the report state. The behavior test asserts this at run time for every request; this scans
     * every synchronized block and method for the calls that reach the transport.
     */
    @Test
    public void noSynchronizedBlockOrMethodReachesTheTransport() throws IOException {
        List<String> guarded = new ArrayList<>();
        // #1054: the transactions take the client's monitor too; both files are scanned.
        for (String file : new String[] {CLIENT, TRANSACTIONS}) {
            String code = withoutComments(read(SOURCE_DIRECTORY + file));
            int before = guarded.size();
            Matcher block = Pattern.compile(
                    "synchronized\\s*\\(\\s*KeepADBRegisterClient\\.class\\s*\\)\\s*\\{")
                    .matcher(code);
            while (block.find()) {
                guarded.add(matchBraces(code, block.end() - 1));
            }
            Matcher method = Pattern.compile(
                    "static\\s+synchronized\\s+[\\w<>\\[\\]]+\\s+\\w+\\([^)]*\\)\\s*\\{")
                    .matcher(code);
            while (method.find()) {
                guarded.add(matchBraces(code, method.end() - 1));
            }
            assertTrue("the scan must see the monitor sections of " + file + ": "
                    + (guarded.size() - before), guarded.size() - before >= 10);
        }
        assertTrue("the scan must see the commit blocks and synchronized methods: " + guarded.size(),
                guarded.size() >= 30);
        // #1049: the coordinator and the seam run outside the monitor and take no lock of their own.
        for (String outside : new String[] {COORDINATOR, SEAM}) {
            String code = withoutComments(read(SOURCE_DIRECTORY + outside));
            assertFalse(outside + " must not synchronize", code.contains("synchronized"));
        }

        for (String body : guarded) {
            for (String transport : new String[] {"RegisterTransport.", "deleteEndpoint(",
                    "postEndpoint(", "postTransports(", "sendJsonPost(", "httpTransport",
                    ".postJson(", ".delete(", "reportAdditionalVerifiedTransports(",
                    "PendingCleanupCoordinator.flush(",
                    "PendingCleanupCoordinator.recordFailuresLeftToTransaction(",
                    "PendingCleanupRetryRepository."}) {
                assertFalse("no request or retry decision may run inside the class monitor: '"
                        + transport + "' in {" + body.replaceAll("\\s+", " ").trim() + "}",
                        body.contains(transport));
            }
        }
    }

    /**
     * Write-ahead, in the order the source gives it: after the primary POST the operation is
     * checked once more before any secondary transport is sent, and in the commit block the cleanup
     * to remember is persisted before the obsolete cleanups are dropped and before the local report
     * snapshot moves on to the new URL. {@link KeepADBRegisterCleanupLifecycleTest} proves the same
     * order on the preferences file; the source order is what keeps it from being refactored away.
     */
    @Test
    public void theUpdateTransactionKeepsItsCheckAndWriteAheadOrder() throws IOException {
        String transactions = withoutComments(read(SOURCE_DIRECTORY + TRANSACTIONS));
        String update = bodyOf(transactions, "private static void performUpdateTransaction(");

        int post = update.indexOf("RegisterTransport.postEndpoint(targetUrl, targetEndpoint)");
        assertTrue(post > 0);
        int recheck = update.indexOf("if (!KeepADBRegisterClient.isCurrent(opGen)) {", post);
        int secondary = update.indexOf(
                "RegisterTransport.reportAdditionalVerifiedTransports(context, targetUrl);");
        assertTrue("the operation is checked after the POST", recheck > post);
        assertTrue("and before any secondary transport is reported", secondary > recheck);
        // #923: a superseded POST may still have registered; its withdrawal is queued before the
        // transaction gives up.
        String stale = update.substring(recheck, secondary);
        int remembered = stale.indexOf(
                "PendingCleanupCoordinator.rememberPossibleRegistration(context, targetUrl, false);");
        assertTrue(stale, remembered >= 0 && remembered < stale.indexOf("return;"));

        int commit = update.indexOf("synchronized (KeepADBRegisterClient.class)", secondary);
        int remember = update.indexOf("PendingCleanupCoordinator.queue(context, cleanupToRemember)",
                commit);
        int drop = update.indexOf("PendingCleanupCoordinator.removeForResource(context, targetUrl)",
                commit);
        int stateMove = update.indexOf(
                "KeepADBRegisterClient.commitRegistrationLocked(targetUrl, targetEndpoint);", commit);
        int snapshot = update.indexOf("WebhookReportPrefs.setWebhookReportSnapshot(", commit);
        assertTrue("the cleanup to remember is persisted in the commit block", remember > commit);
        assertTrue("before the obsolete cleanups of the new URL are dropped", drop > remember);
        assertTrue("before the in-memory report state moves on", stateMove > remember);
        assertTrue("and before the stored report snapshot moves on", snapshot > remember);

        // #707: queuing removes the retry record of the entry that the add evicted, right behind
        // the write that evicted it, and only that one (#1049: in the coordinator's queue).
        String queue = bodyOf(withoutComments(read(SOURCE_DIRECTORY + COORDINATOR)),
                "static void queue(");
        assertTrue("the entry to forget is the one the eviction reported, nothing else, and only "
                + "when there was one",
                Pattern.compile("\\{\\s*String\\s+evicted\\s*=\\s*"
                        + "PendingWebhookCleanupStore\\.add\\(context, url\\);\\s*"
                        + "if\\s*\\(\\s*evicted\\s*!=\\s*null\\s*\\)\\s*"
                        + "PendingCleanupRetryRepository\\.remove\\(context, evicted\\);\\s*\\}")
                        .matcher(queue).find());
    }

    /**
     * #707: the sweep of dead records is bookkeeping on the preferences file, run where the cleanup
     * flush already runs: in {@code flushPendingCleanups}, which the two transactions call first,
     * before they take the monitor, and which only the register executor runs. It has one call
     * site, none inside a synchronized block or method (the guard above), no helper that could be
     * called from elsewhere, and the repository holds neither scheduling nor state for it (the
     * guards above), so no worker, timer, cache, lock or generation can have been added for it.
     */
    @Test
    public void theOrphanSweepHasOneCallSiteInTheFlushThatOnlyTheExecutorTransactionsRun()
            throws IOException {
        String client = withoutComments(read(SOURCE_DIRECTORY + CLIENT));
        String coordinator = withoutComments(read(SOURCE_DIRECTORY + COORDINATOR));

        String flush = bodyOf(coordinator, "static List<String> flush(");
        assertEquals(1, count(flush, "PendingCleanupRetryRepository.removeOrphans("));
        assertTrue("the sweep is the first thing the flush does, before it decides about any entry",
                flush.indexOf("PendingCleanupRetryRepository.removeOrphans(")
                        < flush.indexOf("for (String url :"));
        assertTrue("it is handed the entries the backlog holds now, read through the preferences "
                + "(which also migrates a legacy set), and nothing else",
                Pattern.compile("removeOrphans\\(context,\\s*PendingWebhookCleanupStore"
                        + "\\.get\\(context\\)\\.toArray\\(new String\\[0\\]\\)\\);")
                        .matcher(flush).find());

        // The flush is reached from the two transactions and the test hook only.
        String transactions = withoutComments(read(SOURCE_DIRECTORY + TRANSACTIONS));
        assertEquals("the test hook", 1, count(client, "PendingCleanupCoordinator.flush("));
        assertEquals("update transaction and delete transaction (#1054)", 2,
                count(transactions, "PendingCleanupCoordinator.flush("));
        assertEquals("the coordinator never flushes from anywhere else", 1,
                count(coordinator, "flush("));
        for (String transaction : new String[] {"private static void performUpdateTransaction(",
                "private static void performDeleteTransaction("}) {
            String body = bodyOf(transactions, transaction);
            Matcher flushCall = Pattern.compile("(?:List<String>\\s+\\w+\\s*=\\s*)?"
                    + "PendingCleanupCoordinator\\.flush\\(context, targetUrl, (true|false)\\);")
                    .matcher(body);
            int call = flushCall.find() ? flushCall.start() : -1;
            assertTrue("the transaction flushes first: " + transaction,
                    call > 0 && body.substring(1, call).trim().isEmpty());
            assertTrue("and before it takes the monitor: " + transaction,
                    call < body.indexOf("synchronized (KeepADBRegisterClient.class)"));
        }

        // The two transactions run on the register executor only: every call sits in a task of it.
        int inExecutorTasks = 0;
        Matcher task = Pattern.compile("EXECUTOR\\.execute\\(\\(\\)\\s*->\\s*\\{").matcher(client);
        while (task.find()) {
            String body = matchBraces(client, task.end() - 1);
            inExecutorTasks += count(body, "RegisterTransactions.update(")
                    + count(body, "RegisterTransactions.delete(");
        }
        int calls = count(client, "RegisterTransactions.update(")
                + count(client, "RegisterTransactions.delete(");
        assertEquals("one call of the update and two of the delete transaction", 3, calls);
        assertEquals("every call of a transaction is made inside a register executor task", calls,
                inExecutorTasks);
        // #1054: each entry of the transactions reaches its transaction once, and nothing else in
        // the main sources calls them, so the client's executor tasks stay the only way in.
        assertEquals(2, count(transactions, "performUpdateTransaction("));
        assertEquals(2, count(transactions, "performDeleteTransaction("));
        try (Stream<Path> files = Files.list(projectPath(SOURCE_DIRECTORY))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (name.equals(CLIENT) || !name.endsWith(".java")) continue;
                String code = withoutComments(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                assertFalse(name + " must not start a transaction",
                        code.contains("RegisterTransactions.update(")
                                || code.contains("RegisterTransactions.delete("));
            }
        }
    }

    /**
     * #707: the repository is the only owner of the record key, so the scan over the stored keys
     * lives there; no other class spells the key or its prefix, and {@link KeepADBPreferences},
     * which keeps the entries, knows neither the key nor the repository (the other direction is
     * guarded by {@code onlyTheClientUsesTheRepositoryAndOwnsGenerationExecutorAndHttp}).
     */
    @Test
    public void onlyTheRepositoryKnowsTheRecordKey() throws IOException {
        List<String> spellers = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectPath(SOURCE_DIRECTORY))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList())) {
                String code = withoutComments(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                if (code.contains("pending_cleanup_retry_state")) {
                    spellers.add(file.getFileName().toString());
                }
            }
        }
        assertEquals("Only the repository may know the record key: " + spellers,
                List.of(REPOSITORY), spellers);

        // #1050: the entries live in PendingWebhookCleanupStore, split out of KeepADBPreferences.
        String store = withoutComments(read(SOURCE_DIRECTORY + "PendingWebhookCleanupStore.java"));
        assertTrue("the entries it keeps are still there",
                store.contains("register_webhook_pending_cleanup\""));
        for (String file : new String[] {"KeepADBPreferences.java", "PendingWebhookCleanupStore.java"}) {
            String code = withoutComments(read(SOURCE_DIRECTORY + file));
            for (String foreign : new String[] {"retry_state", "RetryState", "PendingCleanupRetry",
                    "removeOrphans"}) {
                assertFalse(file + " must not know the retry record: '" + foreign + "'",
                        code.contains(foreign));
            }
        }
    }

    // ---- helpers ----

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
    }

    /** The body (braces included) of the first member whose declaration starts with {@code head}. */
    private static String bodyOf(String code, String head) {
        int start = code.indexOf(head);
        assertTrue("member not found: " + head, start >= 0);
        int open = code.indexOf('{', start);
        return matchBraces(code, open);
    }

    /** The text from the opening brace at {@code open} to its matching closing brace. */
    private static String matchBraces(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i++;
                while (i < code.length() && code.charAt(i) != c) {
                    if (code.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return code.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced braces");
    }

    /**
     * The code without {@code //} and block comments, so documentation cannot satisfy or trip a
     * guard. String and character literals are skipped as units: the client builds
     * {@code "://"}, which a plain line-comment pattern would cut in half.
     */
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

    /** Additionally without string literals, so a log tag or a key cannot trip a word guard. */
    private static String withoutStrings(String code) {
        return code.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
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
