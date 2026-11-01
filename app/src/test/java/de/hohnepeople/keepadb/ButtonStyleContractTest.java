package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * #895: no button falls back to the platform default (flat gray) look. A layout button carries
 * its own background or a style; a button built in code passes one of the app's button styles or
 * sets one of the app's button backgrounds.
 */
public class ButtonStyleContractTest {

    @Test
    public void everyLayoutButtonHasTheAppLook() throws IOException {
        List<String> plain = new ArrayList<>();
        Pattern button = Pattern.compile("<Button\\b(.*?)/?>", Pattern.DOTALL);
        for (Path layout : files("app/src/main/res", ".xml")) {
            if (!layout.getParent().getFileName().toString().startsWith("layout")) continue;
            Matcher matcher = button.matcher(read(layout));
            while (matcher.find()) {
                String attributes = matcher.group(1);
                if (!attributes.contains("android:background=") && !attributes.contains("style=")) {
                    plain.add(layout.getParent().getFileName() + "/" + layout.getFileName());
                }
            }
        }
        assertTrue("buttons with the platform default look: " + plain, plain.isEmpty());
    }

    @Test
    public void everyButtonBuiltInCodeUsesAnAppStyle() throws IOException {
        List<String> plain = new ArrayList<>();
        Pattern created = Pattern.compile("Button (\\w+) = new Button\\(([^;]*)\\);");
        for (Path source : files("app/src/main/java", ".java")) {
            String code = read(source);
            Matcher matcher = created.matcher(code);
            int checked = 0;
            int all = code.split("new Button\\(", -1).length - 1;
            while (matcher.find()) {
                checked++;
                String arguments = matcher.group(2);
                // Accepted: an app style, the deliberate borderless text button, or a button that
                // sets one of the app's button backgrounds right after it is created.
                String following = code.substring(matcher.end(),
                        Math.min(code.length(), matcher.end() + 200));
                boolean styled = arguments.contains("R.style.Widget_KeepADB_Button_")
                        || arguments.contains("borderlessButtonStyle")
                        || following.contains(matcher.group(1) + ".setBackgroundResource(")
                                && following.contains("R.drawable.bg_btn_");
                if (!styled) {
                    plain.add(source.getFileName() + ": new Button(" + arguments + ")");
                }
            }
            if (checked != all) plain.add(source.getFileName() + ": a button not built as 'Button x = new Button(...)'");
        }
        assertTrue("buttons built without an app style: " + plain, plain.isEmpty());
    }

    private static List<Path> files(String relativeDirectory, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root().resolve(relativeDirectory))) {
            return walk.filter(path -> path.toString().endsWith(suffix)).collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path root() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("project root not found");
        return directory;
    }
}
