package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import java.util.regex.Pattern;

/** Builds a user-editable, privacy-safe GitHub issue draft. */
final class KeepADBIssueReporter {
    static final String ISSUE_URL = "https://github.com/m00sfett/KeepADB/issues/new";
    private static final Pattern NETWORK_HOST = Pattern.compile("(?i)\\bhost=[^\\s]+");
    private static final Pattern NETWORK_PORT = Pattern.compile("(?i)\\bport=\\d+");

    private KeepADBIssueReporter() {}

    static String buildBody(Context context, boolean includeDiagnostics) {
        String body = buildBaseBody(context);
        return includeDiagnostics
                ? addDiagnosticsSection(body, buildDiagnosticsSection(context)) : body;
    }

    private static String buildBaseBody(Context context) {
        String unavailable = context.getString(R.string.issue_report_unavailable);
        String version = unavailable;
        String versionCode = unavailable;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            version = info.versionName == null || info.versionName.isEmpty()
                    ? unavailable : info.versionName;
            versionCode = Long.toString(info.getLongVersionCode());
        } catch (Exception ignored) {
            // Keep the draft usable when package metadata is unavailable.
        }

        String locale = context.getResources().getConfiguration().getLocales().isEmpty()
                ? unavailable
                : context.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        String androidVersion = Build.VERSION.RELEASE == null || Build.VERSION.RELEASE.isEmpty()
                ? unavailable : Build.VERSION.RELEASE;
        String model = Build.MODEL == null || Build.MODEL.isEmpty() ? unavailable : Build.MODEL;

        String body = context.getString(R.string.issue_report_body,
                context.getString(R.string.issue_report_placeholder_problem_type), locale,
                context.getString(R.string.issue_report_placeholder_expected),
                context.getString(R.string.issue_report_placeholder_actual),
                context.getString(R.string.issue_report_placeholder_steps), version, versionCode,
                androidVersion, Integer.toString(Build.VERSION.SDK_INT), model,
                context.getString(R.string.issue_report_placeholder_logs),
                context.getString(R.string.issue_report_placeholder_screenshots),
                context.getString(R.string.issue_report_placeholder_notes));
        return body.replaceAll("\\s+##\\s+", "\n## ");
    }

    static String buildUrl(Context context, boolean includeDiagnostics) {
        return buildUrl(context, buildBody(context, includeDiagnostics));
    }

    static String buildUrl(Context context, String body) {
        return Uri.parse(ISSUE_URL).buildUpon()
                .appendQueryParameter("title", context.getString(R.string.issue_report_title))
                .appendQueryParameter("body", body)
                .build().toString();
    }

    static String buildDiagnosticsSection(Context context) {
        return context.getString(R.string.issue_report_diagnostics_section) + "\n\n"
                + redactDiagnostics(KeepADBDiagnostics.export(context));
    }

    static String redactDiagnostics(String diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return KeepADBDiagnostics.redact(diagnostics);
        }
        StringBuilder result = new StringBuilder(diagnostics.length());
        String[] lines = diagnostics.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result.append('\n');
            String line = lines[index];
            if (!line.isEmpty()) {
                line = KeepADBDiagnostics.redact(line);
                line = NETWORK_HOST.matcher(line).replaceAll("host=[REDACTED]");
                line = NETWORK_PORT.matcher(line).replaceAll("port=[REDACTED]");
            }
            result.append(line);
        }
        return result.toString();
    }

    static String addDiagnosticsSection(String body, String diagnosticsSection) {
        int notesStart = body.lastIndexOf("\n\n## ");
        if (notesStart < 0) return body + "\n\n" + diagnosticsSection;
        return body.substring(0, notesStart) + "\n\n" + diagnosticsSection
                + body.substring(notesStart);
    }

    static boolean containsDiagnosticsSection(String body, String sectionTitle) {
        return body.contains("\n\n" + sectionTitle + "\n\n");
    }

    static String removeDiagnosticsSection(String body, String sectionTitle) {
        String marker = "\n\n" + sectionTitle + "\n\n";
        int start = body.indexOf(marker);
        if (start < 0) return body;
        int nextHeading = body.indexOf("\n\n## ", start + marker.length());
        return nextHeading < 0
                ? body.substring(0, start)
                : body.substring(0, start) + body.substring(nextHeading);
    }
}
