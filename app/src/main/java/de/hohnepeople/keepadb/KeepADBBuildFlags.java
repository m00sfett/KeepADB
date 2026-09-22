package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #548: single place for the debug-vs-release build-variant check that gates Tailscale's
 * beta/debug-only diagnostics on {@code MainActivity}. Uses the same dependency-free applicationId
 * suffix signal as {@code SettingsActivity.isDebugBuild()} ({@code app/build.gradle}'s debug build
 * type appends {@code .debug}) rather than {@code BuildConfig.DEBUG}, which would require enabling
 * the buildConfig build feature.
 *
 * <p>Unit tests here always execute under the debug variant's applicationId (see
 * {@code SettingsActivity.isDebugBuild()}'s own doc comment), so the package-name check alone
 * cannot exercise the release path in a test. {@link #setOverrideForTesting} lets a test force
 * either branch regardless of the actual test package name, satisfying the #548 acceptance
 * criterion that the release-only-hides-Tailscale behavior is proven by an automated test rather
 * than incidentally passing because of the test build's own package name.
 */
final class KeepADBBuildFlags {

    private static Boolean debugBuildOverrideForTesting = null;

    private KeepADBBuildFlags() {}

    static boolean isDebugBuild(Context context) {
        if (debugBuildOverrideForTesting != null) return debugBuildOverrideForTesting;
        return context != null && context.getPackageName().endsWith(".debug");
    }

    static void setOverrideForTesting(Boolean debugBuild) {
        debugBuildOverrideForTesting = debugBuild;
    }
}
