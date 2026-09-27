package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Behavior tests for {@link KeepADBWebhookForm} in isolation from {@link SettingsActivity}'s own
 * lifecycle. {@link SettingsActivityTest#unsavedWebhookDraftSurvivesActivityRecreation()} and
 * {@link SettingsActivityTest#emptyWebhookDraftSurvivesActivityRecreation()} already cover the
 * end-to-end wiring through a real activity recreation and {@code onCreate}/{@code onResume}; these
 * pin the form's own {@link Bundle} contract and the normalization it now performs once instead of
 * twice, driven directly against a bare {@link Activity} carrying only the settings layout -- proof
 * that a future normalization change only needs to touch this one class.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWebhookFormTest {

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBRegisterClient.resetHttpTransport();
    }

    private Activity newActivityWithSettingsLayout() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_settings);
        return activity;
    }

    @Test
    public void restoreDraftAndSaveStateRoundTripTheUrlWithoutChangingPersistedState() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBPreferences.setRegisterWebhookUrl(activity, "https://saved.example/register/device");
        KeepADBPreferences.setRegisterWebhookEnabled(activity, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        int[] changeCount = {0};
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> changeCount[0]++);

        Bundle incoming = new Bundle();
        incoming.putString(KeepADBWebhookForm.STATE_WEBHOOK_DRAFT_URL, "https://draft.example/boundary");
        form.restoreDraft(incoming);

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        assertEquals("restoreDraft must write the draft into the field, not the saved URL",
                "https://draft.example/boundary", input.getText().toString());

        Bundle outgoing = new Bundle();
        form.saveState(outgoing);
        assertEquals("https://draft.example/boundary",
                outgoing.getString(KeepADBWebhookForm.STATE_WEBHOOK_DRAFT_URL));

        assertEquals("Restoring/saving the draft must not persist it",
                "https://saved.example/register/device", KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertTrue("Restoring/saving the draft must not toggle the enabled flag",
                KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertTrue("Restoring/saving the draft must not trigger a webhook network request",
                transport.recordedRequests.isEmpty());
        assertEquals("Neither restoreDraft nor saveState is an action and must not run the "
                + "onChange callback", 0, changeCount[0]);
    }

    @Test
    public void ensureDraftInitializedFallsBackToTheSavedUrlOnlyOnce() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBPreferences.setRegisterWebhookUrl(activity, "https://saved.example/register/device");
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> { });
        EditText input = activity.findViewById(R.id.settings_webhook_url);

        form.ensureDraftInitialized();
        assertEquals("https://saved.example/register/device", input.getText().toString());

        // Simulate the field having since been edited by the user; a second call (as onResume
        // would trigger on e.g. a config change without a real recreation) must be a no-op.
        input.setText("https://edited.example/still-unsaved");
        form.ensureDraftInitialized();
        assertEquals("A second ensureDraftInitialized() call must not clobber an edited draft",
                "https://edited.example/still-unsaved", input.getText().toString());
    }

    @Test
    public void savingWithCredentialsNormalizesOnceAndStripsUserinfo() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> { });

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Button save = activity.findViewById(R.id.settings_webhook_save);
        input.setText("http://user:secret@100.111.111.21:50829/register/s20#frag");
        save.performClick();

        assertEquals("http://100.111.111.21:50829/register/s20", input.getText().toString());
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
    }

    @Test
    public void enablingWithCredentialsNormalizesTheSameWayAsSaving() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> { });

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        input.setText("http://admin:pass@100.111.111.21:50829/register/s20");
        toggle.performClick();

        assertEquals("http://100.111.111.21:50829/register/s20", input.getText().toString());
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
    }

    @Test
    public void refreshVisualRendersTheToggleAndCleartextWarningFromPersistedState() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBPreferences.setRegisterWebhookUrl(activity, "http://100.111.111.21:50829/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(activity, true);
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> { });

        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        View warning = activity.findViewById(R.id.settings_webhook_cleartext_warning);
        // Matches the real onResume -> refresh() order: the draft is seeded from the persisted
        // URL first, then refreshVisual() derives the cleartext check from that field content.
        form.ensureDraftInitialized();
        form.refreshVisual();

        assertTrue("Toggle must reflect the persisted enabled flag", toggle.isChecked());
        assertEquals("Cleartext warning must be shown for a persisted http:// URL",
                View.VISIBLE, warning.getVisibility());
    }
}
