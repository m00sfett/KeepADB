package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * #1001: the Settings card that exports and imports the settings file
 * ({@link KeepADBSettingsBackup}). Both directions go through the Storage Access Framework, so the
 * user picks the file and KeepADB needs no storage permission. Exporting first says what the file
 * contains and offers to leave the webhook URL out; importing validates the whole file and asks
 * before it replaces anything.
 */
final class KeepADBSettingsBackupController {
    static final int REQUEST_EXPORT = 1001;
    static final int REQUEST_IMPORT = 1002;
    private static final String STATE_INCLUDE_WEBHOOK = "settings_backup_include_webhook";
    private static final String STATE_PENDING_IMPORT = "settings_backup_pending_import";

    private final Activity activity;
    private final Runnable onChange;
    /** Re-seeds what the screen holds as unsaved input (the webhook URL field) from the imported values. */
    private final Runnable onImported;
    private boolean includeWebhookUrl;
    /** The picked file while its confirmation dialog is open; re-read after a recreation. */
    private Uri pendingImport;
    private AlertDialog activeDialog;
    /** Counts file reads; a read that a later pick superseded publishes nothing. */
    private int readGeneration;

    KeepADBSettingsBackupController(Activity activity, Runnable onChange, Runnable onImported) {
        this.activity = activity;
        this.onChange = onChange;
        this.onImported = onImported;
        activity.findViewById(R.id.settings_backup_export).setOnClickListener(v -> showExportDialog());
        activity.findViewById(R.id.settings_backup_import).setOnClickListener(v -> pickImportFile());
    }

    void saveState(Bundle outState) {
        outState.putBoolean(STATE_INCLUDE_WEBHOOK, includeWebhookUrl);
        if (pendingImport != null) outState.putParcelable(STATE_PENDING_IMPORT, pendingImport);
    }

    void restore(Bundle savedState) {
        includeWebhookUrl = savedState.getBoolean(STATE_INCLUDE_WEBHOOK, false);
        Uri pending = savedState.getParcelable(STATE_PENDING_IMPORT);
        if (pending != null) readImport(pending);
    }

    void destroy() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = null;
    }

    AlertDialog getActiveDialog() {
        return activeDialog;
    }

    /** Handles the two document picker results; returns false for any other request. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) return false;
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return true;
        if (requestCode == REQUEST_EXPORT) {
            writeExport(uri, includeWebhookUrl);
        } else {
            readImport(uri);
        }
        return true;
    }

    // --- Export ---------------------------------------------------------------------------------

    private void showExportDialog() {
        boolean hasWebhookUrl =
                WebhookUrlCodec.isValid(KeepADBPreferences.getRegisterWebhookUrl(activity));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_backup_export)
                .setNegativeButton(android.R.string.cancel, null);
        if (hasWebhookUrl) {
            builder.setMessage(R.string.settings_backup_export_message)
                    .setPositiveButton(R.string.settings_backup_export_with_webhook,
                            (d, w) -> pickExportFile(true))
                    .setNeutralButton(R.string.settings_backup_export_without_webhook,
                            (d, w) -> pickExportFile(false));
        } else {
            builder.setMessage(R.string.settings_backup_export_message_no_webhook)
                    .setPositiveButton(R.string.settings_backup_export, (d, w) -> pickExportFile(false));
        }
        show(builder.create());
    }

    private void pickExportFile(boolean withWebhookUrl) {
        includeWebhookUrl = withWebhookUrl;
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(KeepADBSettingsBackup.MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, "keepadb-settings-" + date + ".json");
        startPicker(intent, REQUEST_EXPORT);
    }

    private void writeExport(Uri uri, boolean withWebhookUrl) {
        Context app = activity.getApplicationContext();
        String content;
        try {
            content = KeepADBSettingsBackup.export(app, withWebhookUrl, System.currentTimeMillis(),
                    appVersion());
        } catch (Exception e) {
            toast(R.string.settings_backup_export_failed);
            return;
        }
        new Thread(() -> {
            boolean ok;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("no stream");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                ok = true;
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            final boolean written = ok;
            KeepADBDiagnostics.event(app, "user_action", "settings_backup", "export",
                    written ? (withWebhookUrl ? "with_webhook" : "without_webhook") : "failed");
            activity.runOnUiThread(() -> toast(written ? R.string.settings_backup_export_done
                    : R.string.settings_backup_export_failed));
        }, "keepadb-settings-export").start();
    }

    private String appVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    // --- Import ---------------------------------------------------------------------------------

    private void pickImportFile() {
        // A new pick supersedes every earlier attempt: its read publishes nothing, and its
        // confirmation (possibly still open behind the picker) is closed and can no longer apply.
        readGeneration++;
        pendingImport = null;
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        // "*/*": file managers label a .json file differently (application/json, text/plain,
        // application/octet-stream); the strict parser decides what is a settings file.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startPicker(intent, REQUEST_IMPORT);
    }

    private void readImport(Uri uri) {
        // Recorded before the read, so a recreation while a slow provider serves the file
        // re-reads it instead of losing the import.
        pendingImport = uri;
        final int generation = ++readGeneration;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            KeepADBSettingsBackup.Snapshot snapshot = null;
            KeepADBSettingsBackup.Error error = null;
            try {
                snapshot = KeepADBSettingsBackup.parse(read(app, uri));
            } catch (KeepADBSettingsBackup.ImportException e) {
                error = e.error;
            }
            final KeepADBSettingsBackup.Snapshot parsed = snapshot;
            final KeepADBSettingsBackup.Error failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (generation != readGeneration) return; // superseded by a later pick
                if (parsed != null) {
                    confirmImport(uri, parsed);
                } else {
                    pendingImport = null;
                    KeepADBDiagnostics.event(app, "user_action", "settings_backup", "import",
                            "rejected_" + failure.name().toLowerCase(Locale.ROOT));
                    showError(failure);
                }
            });
        }, "keepadb-settings-import").start();
    }

    /** Reads at most {@link KeepADBSettingsBackup#MAX_FILE_BYTES} plus one byte, as UTF-8. */
    static String read(Context context, Uri uri) throws KeepADBSettingsBackup.ImportException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no stream");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > KeepADBSettingsBackup.MAX_FILE_BYTES) {
                    throw new KeepADBSettingsBackup.ImportException(
                            KeepADBSettingsBackup.Error.TOO_LARGE, "size");
                }
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new KeepADBSettingsBackup.ImportException(
                    KeepADBSettingsBackup.Error.UNREADABLE, e.getClass().getSimpleName());
        }
    }

    private void confirmImport(Uri uri, KeepADBSettingsBackup.Snapshot snapshot) {
        final int generation = readGeneration;
        pendingImport = uri;
        String message = activity.getString(R.string.settings_backup_import_message,
                snapshot.trustedAccessPointCount(), snapshot.blockedCount())
                + "\n\n" + activity.getString(snapshot.hasWebhookUrl()
                        ? R.string.settings_backup_import_webhook_included
                        : R.string.settings_backup_import_webhook_absent);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_backup_import_title)
                .setMessage(message)
                .setPositiveButton(R.string.settings_backup_import_confirm,
                        (d, w) -> {
                            if (generation == readGeneration) applyImport(snapshot);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (generation == readGeneration) pendingImport = null;
        });
        show(dialog);
    }

    private void applyImport(KeepADBSettingsBackup.Snapshot snapshot) {
        pendingImport = null;
        Context app = activity.getApplicationContext();
        KeepADBPeriodicReport.Interval intervalBefore = KeepADBPreferences.getPeriodicWebhookInterval(app);
        KeepADBSettingsBackup.ApplyResult result = KeepADBSettingsBackup.apply(app, snapshot);
        KeepADBDiagnostics.event(app, "user_action", "settings_backup", "import",
                !result.saved ? "write_failed"
                        : result.webhookUrlKept ? "applied_webhook_kept"
                        : result.webhookUrlChanged ? "applied_webhook_changed" : "applied");
        // Even when the write to storage failed, Android already applied the values in memory:
        // reconcile everything with them, then say that they may not survive.
        // Let every surface and rule pick up the new values. The shutdown rules only ever turn
        // Wifi-ADB off; nothing here turns it or Keep-Alive on.
        KeepADBWarningState.observe(app);
        KeepADBWidget.refreshAllState(app);
        // A changed interval replaces a pending alarm, as the interval picker does; otherwise only
        // arm or cancel it for the imported switch.
        if (intervalBefore != KeepADBPreferences.getPeriodicWebhookInterval(app)) {
            KeepADBPeriodicReport.reschedule(app);
        } else {
            KeepADBPeriodicReport.sync(app);
        }
        KeepADBEndpointCoordinator.refresh(app);
        KeepADBUntrustedShutdown.evaluate(app, "settings_import");
        // The privacy switches may have changed: mask this screen, and take back a posted
        // "new Wi-Fi" notice that shows the network, as the switches themselves do.
        KeepADBPrivacy.concealAll(activity);
        KeepADBPrivacyToggle.update(activity);
        if (KeepADBPreferences.isNotificationPrivacyEnabled(app)
                || KeepADBPreferences.isLockScreenPrivacyEnabled(app)) {
            KeepADBNetworkTrustPrompt.applyNotificationPrivacy(app);
        }
        onImported.run();
        if (!result.saved) {
            showError(KeepADBSettingsBackup.Error.WRITE_FAILED);
        } else if (result.webhookUrlKept) {
            toast(R.string.settings_backup_import_done_webhook_kept);
        } else if (result.webhookUrlChanged) {
            toast(R.string.settings_backup_import_done_webhook_off);
        } else {
            toast(R.string.settings_backup_import_done);
        }
        onChange.run();
    }

    private void showError(KeepADBSettingsBackup.Error error) {
        int message;
        switch (error) {
            case UNSUPPORTED_VERSION:
                message = R.string.settings_backup_error_version;
                break;
            case INVALID_VALUE:
                message = R.string.settings_backup_error_invalid;
                break;
            case UNREADABLE:
                message = R.string.settings_backup_error_unreadable;
                break;
            case WRITE_FAILED:
                message = R.string.settings_backup_error_write_failed;
                break;
            case NOT_A_SETTINGS_FILE:
            case TOO_LARGE:
            default:
                message = R.string.settings_backup_error_not_settings;
                break;
        }
        show(new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_backup_error_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    // --- Helpers --------------------------------------------------------------------------------

    private void startPicker(Intent intent, int requestCode) {
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(R.string.settings_backup_no_picker);
        }
    }

    private void show(AlertDialog dialog) {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.show();
    }

    private void toast(int message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
