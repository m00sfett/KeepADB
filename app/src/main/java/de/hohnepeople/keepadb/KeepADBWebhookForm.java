package de.hohnepeople.keepadb;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Owns the register-webhook form for {@link SettingsActivity}: input/toggle/error/cleartext-
 * warning views, the URL-normalization rule, the unsaved-draft round-trip through a {@link Bundle},
 * and the three explicit actions (save, enable/disable, clear).
 *
 * <p>Extracted by #595 (codequality review CQ-02). The URL-trim-and-sanitize step used to be
 * duplicated between the enable path and the save path; it now lives once in {@link
 * #readAndNormalizeInput()}. The different empty-input rules between save (silently clears the
 * saved URL unless the webhook is enabled) and enable (always rejects an empty/invalid URL) are
 * unchanged -- they are a real product distinction, not incidental duplication.
 *
 * <p>{@link SettingsActivity} stays the lifecycle owner: it creates one instance in {@code
 * onCreate} and calls {@link #restoreDraft}, {@link #ensureDraftInitialized} (from {@code
 * onResume}), {@link #saveState} and {@link #refreshVisual} (from its own {@code refresh()}) at the
 * matching lifecycle points. {@link SettingsActivity#resolveWebhookDraft} stays put -- it was
 * already a good, narrow boundary (a pure function with its own direct contract tests) and moving
 * it here would only have added an indirection without changing what it guards.
 */
final class KeepADBWebhookForm {
    // Bundle key of the unsaved URL draft (#595: owned by the form; value unchanged).
    static final String STATE_WEBHOOK_DRAFT_URL = "settings_webhook_draft_url";

    private final Activity activity;
    private final Runnable onChange;

    private final Switch toggle;
    private final EditText urlInput;
    private final TextView error;
    private final TextView cleartextWarning;

    private boolean draftInitialized;

    /** @param onChange invoked after every action that used to end its click listener in refresh(). */
    KeepADBWebhookForm(Activity activity, Runnable onChange) {
        this(activity, activity.getWindow().getDecorView(), onChange);
    }

    /**
     * The form inside {@code root}: the Settings and the setup assistant (#768) both inflate
     * {@code view_webhook_form} and get the same validated save, enable and clear paths.
     */
    KeepADBWebhookForm(Activity activity, View root, Runnable onChange) {
        this.activity = activity;
        this.onChange = onChange;

        Switch toggle = root.findViewById(R.id.settings_webhook_toggle);
        EditText urlInput = root.findViewById(R.id.settings_webhook_url);
        TextView error = root.findViewById(R.id.settings_webhook_error);
        TextView cleartextWarning = root.findViewById(R.id.settings_webhook_cleartext_warning);
        android.widget.Button save = root.findViewById(R.id.settings_webhook_save);
        android.widget.Button clear = root.findViewById(R.id.settings_webhook_clear);
        this.toggle = toggle;
        this.urlInput = urlInput;
        this.error = error;
        this.cleartextWarning = cleartextWarning;

        // The field wraps long text and the hint at large font sizes (#791); a URL never holds a
        // line break, so Enter must not add one.
        urlInput.setFilters(new android.text.InputFilter[] {
                (source, start, end, dest, dstart, dend) -> {
                    for (int i = start; i < end; i++) {
                        char c = source.charAt(i);
                        if (c == '\n' || c == '\r') {
                            return source.subSequence(start, end).toString().replaceAll("[\\r\\n]", "");
                        }
                    }
                    return null;
                }});

        // #803: the multi-line input type keeps the field wrapping, but makes the keyboard show an
        // Enter key that only inserts a (filtered) line break. A raw URI type without the multi-line
        // flag makes the IME offer Done again; the TextView stays non-single-line, so it still wraps.
        urlInput.setRawInputType(
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        urlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                String text = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                boolean isHttp = text.startsWith("http://");
                cleartextWarning.setVisibility(isHttp ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        toggle.setOnClickListener(v -> onToggleClicked());
        save.setOnClickListener(v -> onSaveClicked());
        clear.setOnClickListener(v -> onClearClicked());
    }

    /** Call from {@code SettingsActivity#onCreate} with the incoming (possibly null) state. */
    void restoreDraft(Bundle savedInstanceState) {
        if (savedInstanceState != null
                && savedInstanceState.containsKey(STATE_WEBHOOK_DRAFT_URL)) {
            urlInput.setText(SettingsActivity.resolveWebhookDraft(
                    KeepADBPreferences.getRegisterWebhookUrl(activity),
                    savedInstanceState.getString(STATE_WEBHOOK_DRAFT_URL), true));
            draftInitialized = true;
        }
    }

    /**
     * Call from {@code SettingsActivity#onResume}: on a fresh (non-restored) instance the draft
     * still needs to be seeded from the persisted URL exactly once.
     */
    void ensureDraftInitialized() {
        if (!draftInitialized) {
            urlInput.setText(SettingsActivity.resolveWebhookDraft(
                    KeepADBPreferences.getRegisterWebhookUrl(activity), null, false));
            draftInitialized = true;
        }
    }

    /** Call from {@code SettingsActivity#onSaveInstanceState}. */
    void saveState(Bundle outState) {
        outState.putString(STATE_WEBHOOK_DRAFT_URL,
                urlInput.getText() == null ? "" : urlInput.getText().toString());
    }

    /** Call from {@code SettingsActivity#refresh()} to re-render the toggle and cleartext warning. */
    void refreshVisual() {
        boolean webhookEnabled = KeepADBPreferences.isRegisterWebhookEnabled(activity);
        toggle.setChecked(webhookEnabled);

        String urlToCheck = (urlInput != null && urlInput.getText() != null)
                ? urlInput.getText().toString().trim()
                : KeepADBPreferences.getRegisterWebhookUrl(activity);
        boolean showCleartextWarning = urlToCheck != null
                && urlToCheck.toLowerCase(Locale.ROOT).startsWith("http://");
        cleartextWarning.setVisibility(showCleartextWarning ? View.VISIBLE : View.GONE);
    }

    /**
     * Trims the field, runs {@link KeepADBPreferences#sanitizeWebhookUrl}, and writes the
     * sanitized value back into the field when it differs -- the one normalization step the enable
     * and save actions used to duplicate.
     */
    private String readAndNormalizeInput() {
        String inputUrl = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        String sanitized = KeepADBPreferences.sanitizeWebhookUrl(inputUrl);
        if (sanitized != null && !sanitized.equals(inputUrl)) {
            urlInput.setText(sanitized);
            inputUrl = sanitized;
        }
        return inputUrl;
    }

    /**
     * The one enable path shared by the toggle and "save URL" (#733): persists the already valid,
     * normalized URL, turns the webhook on, re-renders the toggle and kicks the coordinator.
     */
    private void activateWebhook(String validUrl) {
        error.setVisibility(View.GONE);
        KeepADBPreferences.setRegisterWebhookUrl(activity, validUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(activity, true);
        toggle.setChecked(true);
        KeepADBEndpointCoordinator.refresh(activity);
        Toast.makeText(activity, R.string.settings_webhook_enabled_toast, Toast.LENGTH_SHORT).show();
    }

    private void onToggleClicked() {
        boolean wantEnabled = toggle.isChecked();
        if (wantEnabled) {
            String inputUrl = readAndNormalizeInput();
            if (!KeepADBPreferences.isValidWebhookUrl(inputUrl)) {
                toggle.setChecked(false);
                error.setText(R.string.settings_webhook_error_missing_url);
                error.setVisibility(View.VISIBLE);
                urlInput.requestFocus();
                return;
            }
            activateWebhook(inputUrl);
        } else {
            error.setVisibility(View.GONE);
            KeepADBRegisterClient.unregisterAndDisableAsync(activity);
            KeepADBPreferences.setRegisterWebhookEnabled(activity, false);
            Toast.makeText(activity, R.string.settings_webhook_disabled_toast, Toast.LENGTH_SHORT).show();
        }
        onChange.run();
    }

    private void onSaveClicked() {
        String inputUrl = readAndNormalizeInput();
        if (inputUrl.isEmpty()) {
            if (KeepADBPreferences.isRegisterWebhookEnabled(activity)) {
                error.setText(R.string.settings_webhook_error_missing_url);
                error.setVisibility(View.VISIBLE);
                return;
            }
            KeepADBPreferences.setRegisterWebhookUrl(activity, null);
            error.setVisibility(View.GONE);
            Toast.makeText(activity, R.string.settings_webhook_saved_toast, Toast.LENGTH_SHORT).show();
            onChange.run();
            return;
        }
        if (!KeepADBPreferences.isValidWebhookUrl(inputUrl)) {
            error.setText(R.string.settings_webhook_error_invalid_url);
            error.setVisibility(View.VISIBLE);
            return;
        }
        if (!KeepADBPreferences.isRegisterWebhookEnabled(activity)) {
            // #733: saving a valid URL also switches the webhook on; its toast replaces "saved".
            activateWebhook(inputUrl);
            onChange.run();
            return;
        }
        error.setVisibility(View.GONE);
        KeepADBPreferences.setRegisterWebhookUrl(activity, inputUrl);
        KeepADBEndpointCoordinator.refresh(activity);
        Toast.makeText(activity, R.string.settings_webhook_saved_toast, Toast.LENGTH_SHORT).show();
        onChange.run();
    }

    private void onClearClicked() {
        urlInput.setText("");
        error.setVisibility(View.GONE);
        if (KeepADBPreferences.isRegisterWebhookEnabled(activity)) {
            KeepADBRegisterClient.unregisterAndDisableAsync(activity);
            KeepADBPreferences.setRegisterWebhookEnabled(activity, false);
        }
        KeepADBPreferences.setRegisterWebhookUrl(activity, null);
        Toast.makeText(activity, R.string.settings_webhook_cleared_toast, Toast.LENGTH_SHORT).show();
        onChange.run();
    }
}
