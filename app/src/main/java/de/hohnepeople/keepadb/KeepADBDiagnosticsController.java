package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Owns the diagnostics export and the feedback report dialog of {@link SettingsActivity}: the two
 * button bindings, the active dialog with its editable preview and the opt-in checkbox, and their
 * {@link Bundle} round-trip and dismiss-time cleanup.
 *
 * <p>Extracted by #698 as a pure refactor: no behavior, Bundle key, string resource or layout id
 * changed. What goes into a report body is still decided exclusively by {@link
 * KeepADBIssueReporter} (body and section builder), and what an export contains and how it is
 * redacted still decides exclusively {@link KeepADBDiagnostics#export} and {@link
 * KeepADBDiagnostics#exportForIssueReport}. This class adds no second path to either: the
 * diagnostics section enters the preview only through the opt-in checkbox's listener, and it
 * leaves the text again on un-check, on "Open feedback page" and on "Share" whenever the box is
 * not checked.
 *
 * <p>The checkbox state is set before its listener is attached when a dialog is restored: a
 * restored draft already is the user's text -- including a diagnostics section they kept -- so
 * restoring must neither build that section a second time nor read diagnostics at all.
 *
 * <p>{@link SettingsActivity} stays the screen composer and Android lifecycle owner: it creates one
 * instance in {@code onCreate} and calls {@link #restore} (onCreate), {@link #saveState}
 * (onSaveInstanceState) and {@link #destroy} (onDestroy). The reset-app dialog, although it sits
 * in the same card, stays entirely in the activity. Opening the feedback page goes through the
 * activity's own {@code openWebLink}, so a device without a browser is handled by the one shared
 * path (a toast instead of a crash) and not by a second copy of that handling here. A fresh
 * instance is created on every {@code onCreate}, so no dialog or view reference here survives a
 * real activity recreation.
 */
final class KeepADBDiagnosticsController {
    // Bundle keys of the issue report dialog (#698: owned here; value unchanged).
    static final String STATE_ISSUE_REPORT_SHOWING = "settings_issue_report_showing";
    static final String STATE_ISSUE_REPORT_DRAFT = "settings_issue_report_draft";
    static final String STATE_ISSUE_REPORT_TYPE = "settings_issue_report_type";
    private static final String[] ISSUE_TYPES = {"bug", "translation", "suggestion", "other"};
    static final String STATE_ISSUE_REPORT_DIAGNOSTICS = "settings_issue_report_diagnostics";

    private final Activity activity;
    private final Consumer<String> openWebLink;

    private AlertDialog activeIssueReportDialog;
    private EditText activeIssueReportPreview;
    private CheckBox activeIssueReportDiagnostics;
    private Spinner activeIssueReportType;

    /**
     * Binds the export and the feedback report buttons; call from {@code SettingsActivity#onCreate}.
     *
     * @param openWebLink the activity's shared browser path ({@code SettingsActivity#openWebLink}),
     *         which already catches a missing browser.
     */
    KeepADBDiagnosticsController(Activity activity, Consumer<String> openWebLink) {
        this.activity = activity;
        this.openWebLink = openWebLink;

        activity.findViewById(R.id.settings_diagnostics_export)
                .setOnClickListener(v -> shareDiagnostics());
        activity.findViewById(R.id.settings_issue_report)
                .setOnClickListener(v -> showIssueReportDialog());
        activity.findViewById(R.id.settings_general_feedback)
                .setOnClickListener(v -> showIssueReportDialog(null, false, "suggestion", true));
    }

    /** Call from {@code SettingsActivity#onCreate} with the incoming (possibly null) state. */
    void restore(Bundle savedInstanceState) {
        if (savedInstanceState == null
                || !savedInstanceState.getBoolean(STATE_ISSUE_REPORT_SHOWING, false)) {
            return;
        }
        String draftBody = savedInstanceState.getString(STATE_ISSUE_REPORT_DRAFT);
        boolean includeDiagnostics = savedInstanceState.getBoolean(
                STATE_ISSUE_REPORT_DIAGNOSTICS, false);
        showIssueReportDialog(draftBody, includeDiagnostics,
                savedInstanceState.getString(STATE_ISSUE_REPORT_TYPE, "bug"), false);
    }

    /** Call from {@code SettingsActivity#onSaveInstanceState}. */
    void saveState(Bundle outState) {
        if (activeIssueReportDialog != null && activeIssueReportDialog.isShowing()) {
            outState.putBoolean(STATE_ISSUE_REPORT_SHOWING, true);
            outState.putString(STATE_ISSUE_REPORT_DRAFT,
                    activeIssueReportPreview != null && activeIssueReportPreview.getText() != null
                            ? activeIssueReportPreview.getText().toString() : "");
            outState.putString(STATE_ISSUE_REPORT_TYPE, selectedIssueType());
            outState.putBoolean(STATE_ISSUE_REPORT_DIAGNOSTICS,
                    activeIssueReportDiagnostics != null && activeIssueReportDiagnostics.isChecked());
        }
    }

    /** Call from {@code SettingsActivity#onDestroy} to dismiss a showing dialog and drop refs. */
    void destroy() {
        if (activeIssueReportDialog != null) {
            if (activeIssueReportDialog.isShowing()) {
                activeIssueReportDialog.dismiss();
            }
            activeIssueReportDialog = null;
        }
        activeIssueReportPreview = null;
        activeIssueReportDiagnostics = null;
        activeIssueReportType = null;
    }

    AlertDialog getActiveIssueReportDialog() {
        return activeIssueReportDialog;
    }

    private void shareDiagnostics() {
        KeepADBDiagnostics.event(activity, "diagnostics_export", "settings", "requested",
                "share_sheet");
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT,
                        activity.getString(R.string.settings_diagnostics_export_subject))
                .putExtra(Intent.EXTRA_TEXT, KeepADBDiagnostics.export(activity));
        activity.startActivity(Intent.createChooser(share,
                activity.getString(R.string.settings_diagnostics_export)));
    }

    private String selectedIssueType() {
        int position = activeIssueReportType == null ? 0
                : activeIssueReportType.getSelectedItemPosition();
        return position >= 0 && position < ISSUE_TYPES.length ? ISSUE_TYPES[position] : "other";
    }

    private boolean copyDraft(String body) {
        try {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            ClipData clip = ClipData.newPlainText(activity.getString(R.string.issue_report_title), body);
            // Free text may contain private details: hide Android's clipboard preview.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(activity, R.string.feedback_draft_copied, Toast.LENGTH_LONG).show();
            }
            return true;
        } catch (RuntimeException exception) {
            Toast.makeText(activity, R.string.feedback_copy_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void showIssueReportDialog() {
        showIssueReportDialog(null, false, "bug", false);
    }

    private void showIssueReportDialog(String draftBody, boolean includeDiagnostics,
            String issueType, boolean generalFeedback) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);

        TextView intro = new TextView(activity);
        intro.setText(R.string.settings_issue_report_dialog_message);
        intro.setTextSize(13);
        content.addView(intro);

        TextView typeLabel = new TextView(activity);
        typeLabel.setText(R.string.feedback_type);
        content.addView(typeLabel);
        Spinner type = new Spinner(activity);
        type.setContentDescription(activity.getString(R.string.feedback_type));
        String[] labels = {activity.getString(R.string.feedback_type_bug),
                activity.getString(R.string.feedback_type_translation),
                activity.getString(R.string.feedback_type_suggestion),
                activity.getString(R.string.feedback_type_other)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        type.setAdapter(adapter);
        for (int index = 0; index < ISSUE_TYPES.length; index++) {
            if (ISSUE_TYPES[index].equals(issueType)) type.setSelection(index);
        }
        content.addView(type);

        CheckBox diagnostics = new CheckBox(activity);
        diagnostics.setText(R.string.settings_issue_report_include_diagnostics);
        diagnostics.setContentDescription(
                activity.getString(R.string.settings_issue_report_include_diagnostics));
        content.addView(diagnostics);

        ScrollView previewScroll = new ScrollView(activity);
        previewScroll.setFillViewport(true);
        previewScroll.setScrollbarFadingEnabled(false);
        LinearLayout.LayoutParams previewScrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (280 * activity.getResources().getDisplayMetrics().density));
        previewScrollParams.topMargin = (int) (8 * activity.getResources().getDisplayMetrics().density);
        previewScroll.setLayoutParams(previewScrollParams);

        EditText preview = new EditText(activity);
        preview.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        preview.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        preview.setTextSize(13);
        preview.setBackgroundResource(R.drawable.bg_input);
        preview.setPadding(padding, padding, padding, padding);
        preview.setHint(R.string.settings_issue_report_preview_hint);
        preview.setContentDescription(activity.getString(R.string.settings_issue_report_preview));
        previewScroll.addView(preview);
        content.addView(previewScroll);

        String withoutDiagnostics = KeepADBIssueReporter.buildBody(activity, false, generalFeedback);
        final String[] diagnosticsSection = {null};
        String diagnosticsTitle = activity.getString(R.string.issue_report_diagnostics_section);
        preview.setText(withoutDiagnostics);
        if (draftBody != null) {
            preview.setText(draftBody);
        }
        if (includeDiagnostics) {
            diagnostics.setChecked(true);
        }
        diagnostics.setOnCheckedChangeListener((button, checked) -> {
            String current = preview.getText().toString();
            if (checked && !KeepADBIssueReporter.containsDiagnosticsSection(current, diagnosticsTitle)) {
                if (diagnosticsSection[0] == null) {
                    diagnosticsSection[0] = KeepADBIssueReporter.buildDiagnosticsSection(activity);
                }
                preview.setText(KeepADBIssueReporter.addDiagnosticsSection(current,
                        diagnosticsSection[0]));
            } else if (!checked) {
                String withoutSection = KeepADBIssueReporter.removeDiagnosticsSection(
                        current, diagnosticsTitle);
                if (!withoutSection.equals(current)) preview.setText(withoutSection);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_issue_report_dialog_title)
                .setView(content)
                .setPositiveButton(R.string.settings_issue_report_open_feedback, null)
                .setNeutralButton(R.string.settings_issue_report_share, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeIssueReportDialog = dialog;
        activeIssueReportPreview = preview;
        activeIssueReportDiagnostics = diagnostics;
        activeIssueReportType = type;
        dialog.setOnDismissListener(d -> {
            if (activeIssueReportDialog == d) {
                activeIssueReportDialog = null;
                activeIssueReportPreview = null;
                activeIssueReportDiagnostics = null;
                activeIssueReportType = null;
            }
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!diagnostics.isChecked()) {
                    preview.setText(KeepADBIssueReporter.removeDiagnosticsSection(
                            preview.getText().toString(), diagnosticsTitle));
                }
                if (!copyDraft(preview.getText().toString())) return;
                // Keep the editable draft available after returning, including browser failures.
                intro.setText(R.string.feedback_draft_copied);
                openWebLink.accept(KeepADBIssueReporter.buildFeedbackUrl(activity,
                        selectedIssueType()));
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String body = preview.getText().toString();
                if (!diagnostics.isChecked()) {
                    body = KeepADBIssueReporter.removeDiagnosticsSection(body, diagnosticsTitle);
                    preview.setText(body);
                }
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.issue_report_title))
                        .putExtra(Intent.EXTRA_TEXT, body);
                activity.startActivity(Intent.createChooser(
                        share, activity.getString(R.string.settings_issue_report_share)));
            });
        });
        dialog.show();
    }
}
