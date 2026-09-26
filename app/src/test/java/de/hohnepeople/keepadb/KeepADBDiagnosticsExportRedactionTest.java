package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #574: end-to-end proof that a BSSID written through a real diagnostics event site never
 * reaches {@link KeepADBDiagnostics#export} or {@link KeepADBDiagnostics#exportForIssueReport}
 * unmasked, on both the release ring buffer and the debug journal storage path. The pure-string
 * masking rule itself is covered directly in {@link KeepADBDiagnosticsTest}; this class only
 * proves the two real read paths actually apply it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBDiagnosticsExportRedactionTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void resetBuildFlagsAndJournal() {
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBDiagnosticJournal.resetForTesting();
    }

    @Test
    public void releaseRingBufferExportShortensBssidToOui() {
        KeepADBBuildFlags.setOverrideForTesting(false);
        // #574: invented, locally-administered BSSID -- never a real access point's address.
        KeepADBDiagnostics.event(context, "network_trust_prompt", "trusted_network", "shown",
                "bssid=02:aa:bb:cc:dd:ee");

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("bssid=02:aa:bb:*:*:*"));
        assertFalse(export.contains("cc:dd:ee"));

        String issueReportExport = KeepADBDiagnostics.exportForIssueReport(context);
        assertTrue(issueReportExport.contains("bssid=02:aa:bb:*:*:*"));
        assertFalse(issueReportExport.contains("cc:dd:ee"));
    }

    @Test
    public void debugJournalExportShortensBssidToOui() {
        KeepADBBuildFlags.setOverrideForTesting(true);
        // #574: invented, locally-administered BSSID -- never a real access point's address.
        KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "allowed",
                "bssid=02:11:22:33:44:55");

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("bssid=02:11:22:*:*:*"));
        assertFalse(export.contains("33:44:55"));

        String issueReportExport = KeepADBDiagnostics.exportForIssueReport(context);
        assertTrue(issueReportExport.contains("bssid=02:11:22:*:*:*"));
        assertFalse(issueReportExport.contains("33:44:55"));
    }
}
