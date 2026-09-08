package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Contracts for preserving the webhook URL draft without changing persisted state. */
public class KeepADBSettingsWebhookDraftContractTest {
    @Test
    public void emptyDraftIsPreserved() {
        assertEquals("", SettingsActivity.resolveWebhookDraft("https://saved.example", "", true));
    }

    @Test
    public void savedUrlIsUsedOnlyForAnUninitializedDraft() {
        assertEquals("https://saved.example",
                SettingsActivity.resolveWebhookDraft("https://saved.example", null, false));
    }

    @Test
    public void partiallyEditedDraftWinsOverSavedUrl() {
        assertEquals("https://draft.example/pa",
                SettingsActivity.resolveWebhookDraft("https://saved.example", "https://draft.example/pa", true));
    }

    @Test
    public void lifecycleRestorationDoesNotPersistOrToggleTheDraft() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(activity.contains("onSaveInstanceState(Bundle outState)"));
        assertTrue(activity.contains("STATE_WEBHOOK_DRAFT_URL"));
        assertTrue(activity.contains("if (!webhookDraftInitialized)"));

        int onResumeStart = activity.indexOf("protected void onResume()");
        int onResumeEnd = activity.indexOf("protected void onSaveInstanceState", onResumeStart);
        String onResume = activity.substring(onResumeStart, onResumeEnd);
        assertFalse(onResume.contains("setRegisterWebhookUrl"));
        assertFalse(onResume.contains("setRegisterWebhookEnabled"));
        assertFalse(onResume.contains("unregisterAndDisableAsync"));
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
