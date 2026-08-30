package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/** Static contracts for localized resources and visible UI literals. */
public class KeepADBResourceContractTest {
    private static final Pattern STRING_PATTERN = Pattern.compile(
            "<string\\s+name=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private static final Pattern VISIBLE_XML_LITERAL = Pattern.compile(
            "android:(?:text|contentDescription|label)=\\\"(?!@string/)([^\\\"]+)\\\"");
    private static final Pattern VISIBLE_JAVA_LITERAL = Pattern.compile(
            "\\b(?:setContentDescription|setContentTitle|setContentText|setMessage|setTitle|setHint|"
                    + "setLabel|setSubtitle|setTextViewText|setText)(?=\\s*\\()\\s*\\([^;\\r\\n]*?\\\"[^\\\"]*[A-Za-z][^\\\"]*\\\"");

    @Test
    public void everyLocaleMatchesDefaultKeysAndFormatArguments() throws IOException {
        Path resources = projectPath("app/src/main/res");
        Map<String, String> defaultStrings = parseStrings(read(resources.resolve("values/strings.xml")));

        try (Stream<Path> paths = Files.list(resources)) {
            paths.filter(path -> path.getFileName().toString().startsWith("values"))
                    .map(path -> path.resolve("strings.xml"))
                    .forEach(path -> {
                        try {
                            Map<String, String> localeStrings = parseStrings(read(path));
                            assertEquals(path + " must have the default key set", defaultStrings.keySet(),
                                    localeStrings.keySet());
                            for (String key : defaultStrings.keySet()) {
                                assertEquals(path + " has different format arguments for " + key,
                                        formatArguments(defaultStrings.get(key)), formatArguments(localeStrings.get(key)));
                            }
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }

    @Test
    public void visibleTextUsesResourcesExceptDocumentedSymbols() throws IOException {
        Path project = projectPath("");
        try (Stream<Path> paths = Files.walk(project.resolve("app/src/main"))) {
            paths.filter(path -> path.toString().endsWith(".xml"))
                    .forEach(path -> {
                        try {
                            Matcher matcher = VISIBLE_XML_LITERAL.matcher(read(path));
                            while (matcher.find()) {
                                assertEquals(path + " contains an unexpected visible literal", "▼", matcher.group(1));
                            }
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }

        try (Stream<Path> paths = Files.walk(project.resolve("app/src/main/java"))) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = read(path);
                            assertTrue(path + " contains a hard-coded user-facing UI literal",
                                    !containsVisibleJavaLiteral(source));
                            assertTrue(path + " contains a hard-coded user-facing toast literal",
                                    !source.matches("(?s).*Toast\\.makeText\\s*\\([^,]+,\\s*\\\"[^\\\"]*[A-Za-z][^\\\"]*\\\".*"));
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }

    private static Map<String, String> parseStrings(String source) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = STRING_PATTERN.matcher(source);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    private static List<String> formatArguments(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = FORMAT_PATTERN.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static boolean containsVisibleJavaLiteral(String source) {
        String normalized = source.replaceAll("\\s+", " ");
        for (String statement : normalized.split(";")) {
            if (VISIBLE_JAVA_LITERAL.matcher(statement).find()) {
                return true;
            }
        }
        return false;
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

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
