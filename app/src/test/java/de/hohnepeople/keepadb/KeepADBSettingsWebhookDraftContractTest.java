package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Contracts for preserving the webhook URL draft without changing persisted state.
 *
 * <p>The three tests below exercise {@link SettingsActivity#resolveWebhookDraft} directly as a
 * pure function -- genuine behavior tests, not source greps, and unaffected by #596.
 *
 * <p>#596 (codequality review CQ-03) removed the fourth test that used to live here,
 * {@code lifecycleRestorationDoesNotPersistOrToggleTheDraft}: it grepped {@code onResume()}'s
 * source body (sliced off at the next method's declaration) for the *absence* of three setter/
 * network method names. A refactor that renamed or inlined any of those calls could turn it red
 * with no behavior change, and it never actually drove a real activity restore to prove the draft
 * itself came through intact. Replaced by two real Robolectric activity-recreation tests in
 * {@link SettingsActivityTest}:
 * <ul>
 *   <li>{@link SettingsActivityTest#unsavedWebhookDraftSurvivesActivityRecreation()} (partially
 *       edited draft; pre-existing since #579, now also asserting the enabled flag and network
 *       request stay untouched).</li>
 *   <li>{@link SettingsActivityTest#emptyWebhookDraftSurvivesActivityRecreation()} (#596: the
 *       empty-draft case the source-content check never actually exercised either).</li>
 * </ul>
 * Both drive a real {@code saveInstanceState -> new instance -> setup(bundle)} cycle and assert
 * on the actual outcome: the restored field text, {@code KeepADBPreferences}' persisted URL and
 * enabled flag, and a {@link KeepADBFakeHttpTransport}'s recorded requests -- the same three
 * things the removed check named by method name, but proven by effect instead of by grep.
 */
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
}
