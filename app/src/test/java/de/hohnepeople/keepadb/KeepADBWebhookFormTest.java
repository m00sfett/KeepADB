package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
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

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

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
        KeepADB.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
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

    /**
     * #595 AC2, save side of the empty-input rules: with the webhook disabled, saving an empty
     * (whitespace-only) field is an explicit "forget the URL" and succeeds.
     */
    @Test
    public void savingAnEmptyInputClearsTheSavedUrlWhileTheWebhookIsDisabled() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBPreferences.setRegisterWebhookUrl(activity, "https://saved.example/register/device");
        int[] changeCount = {0};
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> changeCount[0]++);

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        TextView error = activity.findViewById(R.id.settings_webhook_error);
        input.setText("   ");
        activity.findViewById(R.id.settings_webhook_save).performClick();

        assertNull("An empty save while disabled must clear the persisted URL",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertFalse("An empty save must not switch the toggle on",
                ((Switch) activity.findViewById(R.id.settings_webhook_toggle)).isChecked());
        assertEquals(View.GONE, error.getVisibility());
        assertEquals(1, changeCount[0]);
    }

    /**
     * #595 AC2, the other side of the same save rule: while the webhook is enabled an empty save
     * is rejected and must neither drop the persisted URL nor disable the webhook.
     */
    @Test
    public void savingAnEmptyInputIsRejectedWhileTheWebhookIsEnabled() {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBPreferences.setRegisterWebhookUrl(activity, "https://saved.example/register/device");
        KeepADBPreferences.setRegisterWebhookEnabled(activity, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        int[] changeCount = {0};
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> changeCount[0]++);

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        TextView error = activity.findViewById(R.id.settings_webhook_error);
        input.setText("");
        activity.findViewById(R.id.settings_webhook_save).performClick();

        assertEquals("https://saved.example/register/device",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertEquals(View.VISIBLE, error.getVisibility());
        assertEquals(activity.getString(R.string.settings_webhook_error_missing_url),
                error.getText().toString());
        assertTrue(transport.recordedRequests.isEmpty());
        assertEquals(0, changeCount[0]);
    }

    /**
     * #595 AC2, enable side: unlike save, enabling never accepts an empty input -- the toggle
     * snaps back off and nothing is persisted.
     */
    @Test
    public void enablingWithAnEmptyInputIsRejectedAndSnapsTheToggleBack() {
        Activity activity = newActivityWithSettingsLayout();
        int[] changeCount = {0};
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> changeCount[0]++);

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        TextView error = activity.findViewById(R.id.settings_webhook_error);
        input.setText("  ");
        toggle.performClick();

        assertFalse("The toggle must snap back off", toggle.isChecked());
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertEquals(View.VISIBLE, error.getVisibility());
        assertEquals(activity.getString(R.string.settings_webhook_error_missing_url),
                error.getText().toString());
        assertEquals(0, changeCount[0]);
    }

    /** Counts coordinator refreshes that reach the endpoint listener (cached endpoint seeded). */
    private int[] observeCoordinatorRefreshes() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        java.lang.reflect.Field host = KeepADBEndpointCoordinator.class.getDeclaredField("currentHost");
        host.setAccessible(true);
        host.set(null, "192.168.1.50");
        java.lang.reflect.Field port = KeepADBEndpointCoordinator.class.getDeclaredField("currentPort");
        port.setAccessible(true);
        port.set(null, 39123);
        int[] refreshes = {0};
        KeepADBEndpointCoordinator.setEndpointListener(new KeepADBEndpointCoordinator.EndpointListener() {
            @Override public void onEndpoint(String h, int p) { refreshes[0]++; }
            @Override public void onUnavailable() { }
        });
        refreshes[0] = 0;
        return refreshes;
    }

    @Test
    public void savingAValidUrlWhileDisabledAlsoActivatesTheWebhook() throws Exception {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport());
        int[] refreshes = observeCoordinatorRefreshes();
        int[] changeCount = {0};
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> changeCount[0]++);

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        TextView error = activity.findViewById(R.id.settings_webhook_error);
        input.setText("http://user:pw@100.111.111.21:50829/register/s20");
        activity.findViewById(R.id.settings_webhook_save).performClick();

        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertTrue("The toggle must show 'on'", toggle.isChecked());
        assertEquals(View.GONE, error.getVisibility());
        assertEquals("The coordinator must be refreshed exactly once", 1, refreshes[0]);
        assertEquals(1, changeCount[0]);
        assertEquals("Only the enabled toast, not a second 'saved' toast",
                activity.getString(R.string.settings_webhook_enabled_toast),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount());
    }

    @Test
    public void savingAValidUrlWhileAlreadyEnabledKeepsTheSavedToastAndRefreshes() throws Exception {
        Activity activity = newActivityWithSettingsLayout();
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport());
        KeepADBPreferences.setRegisterWebhookUrl(activity, "https://old.example/register/a");
        KeepADBPreferences.setRegisterWebhookEnabled(activity, true);
        int[] refreshes = observeCoordinatorRefreshes();
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> { });

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        input.setText("https://new.example/register/a");
        activity.findViewById(R.id.settings_webhook_save).performClick();

        assertEquals("https://new.example/register/a", KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertEquals(1, refreshes[0]);
        assertEquals(activity.getString(R.string.settings_webhook_saved_toast),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void savingAnInvalidUrlWhileDisabledActivatesNothing() throws Exception {
        Activity activity = newActivityWithSettingsLayout();
        int[] refreshes = observeCoordinatorRefreshes();
        KeepADBWebhookForm form = new KeepADBWebhookForm(activity, () -> { });

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        TextView error = activity.findViewById(R.id.settings_webhook_error);
        input.setText("not a url");
        activity.findViewById(R.id.settings_webhook_save).performClick();

        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertFalse(toggle.isChecked());
        assertEquals(View.VISIBLE, error.getVisibility());
        assertEquals(activity.getString(R.string.settings_webhook_error_invalid_url),
                error.getText().toString());
        assertEquals(0, refreshes[0]);
    }

    /**
     * #793: a test that calls the reset itself stays green if the {@code @Rule} field is deleted.
     * This pins that {@link KeepADBRegisterClientResetRule} is really applied to the class.
     */
    @Test
    public void theRegisterClientResetRuleIsAppliedToThisClass() {
        boolean applied = false;
        for (java.lang.reflect.Field field : KeepADBWebhookFormTest.class.getFields()) {
            if (field.getType() == KeepADBRegisterClientResetRule.class
                    && field.isAnnotationPresent(org.junit.Rule.class)) {
                applied = true;
            }
        }
        org.junit.Assert.assertTrue(
                "KeepADBRegisterClientResetRule must be a public @Rule field", applied);
    }
}
