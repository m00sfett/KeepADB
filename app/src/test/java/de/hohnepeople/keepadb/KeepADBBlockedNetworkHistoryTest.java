package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for the recently-blocked access point log (#446): size-bounded FIFO with
 * oldest-first eviction, de-duplication per BSSID, and the "only actionable identities are
 * recorded" rule that keeps a redacted/unset placeholder BSSID out of the log (and therefore out
 * of reach of the "allow" button, which would otherwise allowlist a fail-open placeholder).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBBlockedNetworkHistoryTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    @After
    public void clearPreferences() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void recordsABlockedAccessPointWithItsSsidAndTimestamp() {
        KeepADBBlockedNetworkHistory.record(context, identity("Cafe", "aa:bb:cc:dd:ee:01"), 1_000L);

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("aa:bb:cc:dd:ee:01", entries.get(0).bssid);
        assertEquals("Cafe", entries.get(0).ssid);
        assertEquals("Cafe", entries.get(0).label());
        assertEquals(1_000L, entries.get(0).lastSeenAt);
    }

    @Test
    public void labelFallsBackToTheBssidWhenNoSsidIsReadable() {
        KeepADBBlockedNetworkHistory.record(context, identity(null, "aa:bb:cc:dd:ee:02"), 1L);

        KeepADBBlockedNetworkHistory.Entry entry =
                KeepADBBlockedNetworkHistory.getEntries(context).get(0);
        assertEquals("", entry.ssid);
        assertEquals("aa:bb:cc:dd:ee:02", entry.label());
    }

    @Test
    public void ignoresIdentitiesWithoutARealBssid() {
        KeepADBBlockedNetworkHistory.record(context, identity("Cafe", null), 1L);
        KeepADBBlockedNetworkHistory.record(context, identity("Cafe", ""), 1L);
        KeepADBBlockedNetworkHistory.record(context,
                identity("Cafe", KeepADBNetworkIdentity.REDACTED_BSSID), 1L);
        KeepADBBlockedNetworkHistory.record(context,
                identity("Cafe", KeepADBNetworkIdentity.UNSET_BSSID), 1L);
        KeepADBBlockedNetworkHistory.record(context, null, 1L);

        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    @Test
    public void reRecordingTheSameAccessPointUpdatesItInsteadOfDuplicatingIt() {
        KeepADBBlockedNetworkHistory.record(context, identity("Cafe", "aa:bb:cc:dd:ee:01"), 1_000L);
        KeepADBBlockedNetworkHistory.record(context, identity("Cafe", "AA:BB:CC:DD:EE:01"), 2_000L);
        KeepADBBlockedNetworkHistory.record(context,
                identity("Cafe-Renamed", "aa:bb:cc:dd:ee:01"), 3_000L);

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("Cafe-Renamed", entries.get(0).ssid);
        assertEquals(3_000L, entries.get(0).lastSeenAt);
    }

    @Test
    public void evictsTheOldestEntryOnceTheLogWouldExceedItsBound() {
        int overflow = KeepADBBlockedNetworkHistory.MAX_ENTRIES + 2;
        for (int i = 1; i <= overflow; i++) {
            KeepADBBlockedNetworkHistory.record(context, identity("Net" + i, bssid(i)), i);
        }

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(KeepADBBlockedNetworkHistory.MAX_ENTRIES, entries.size());
        assertFalse(containsBssid(entries, bssid(1)));
        assertFalse(containsBssid(entries, bssid(2)));
        assertTrue(containsBssid(entries, bssid(3)));
        assertTrue(containsBssid(entries, bssid(overflow)));
        // Oldest first, so the newest survivor is last -- the Settings list reverses this.
        assertEquals(bssid(overflow), entries.get(entries.size() - 1).bssid);
    }

    @Test
    public void reRecordingAnEntryMovesItToTheNewestPositionAndProtectsItFromEviction() {
        for (int i = 1; i <= KeepADBBlockedNetworkHistory.MAX_ENTRIES; i++) {
            KeepADBBlockedNetworkHistory.record(context, identity("Net" + i, bssid(i)), i);
        }
        // Touch the oldest one again, then push the log over its bound.
        KeepADBBlockedNetworkHistory.record(context, identity("Net1", bssid(1)), 500L);
        KeepADBBlockedNetworkHistory.record(context, identity("Extra", bssid(99)), 600L);

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(KeepADBBlockedNetworkHistory.MAX_ENTRIES, entries.size());
        assertTrue(containsBssid(entries, bssid(1)));
        // The genuinely least-recently-blocked one (index 2) is the one that had to go.
        assertFalse(containsBssid(entries, bssid(2)));
    }

    @Test
    public void removeDropsOneEntryCaseInsensitivelyAndLeavesTheRestIntact() {
        KeepADBBlockedNetworkHistory.record(context, identity("A", "aa:bb:cc:dd:ee:01"), 1L);
        KeepADBBlockedNetworkHistory.record(context, identity("B", "aa:bb:cc:dd:ee:02"), 2L);

        assertTrue(KeepADBBlockedNetworkHistory.remove(context, "AA:BB:CC:DD:EE:01"));
        assertFalse(KeepADBBlockedNetworkHistory.remove(context, "aa:bb:cc:dd:ee:99"));
        assertFalse(KeepADBBlockedNetworkHistory.remove(context, "  "));

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("aa:bb:cc:dd:ee:02", entries.get(0).bssid);
    }

    /**
     * Guards the positional slot index: a shrink must not leave a slot behind that the next write
     * re-indexes onto, which would resurrect a removed access point in the transparency list.
     */
    @Test
    public void aRemovalFollowedByANewRecordDoesNotResurrectTheRemovedEntry() {
        KeepADBBlockedNetworkHistory.record(context, identity("A", "aa:bb:cc:dd:ee:01"), 1L);
        KeepADBBlockedNetworkHistory.record(context, identity("B", "aa:bb:cc:dd:ee:02"), 2L);
        KeepADBBlockedNetworkHistory.record(context, identity("C", "aa:bb:cc:dd:ee:03"), 3L);

        KeepADBBlockedNetworkHistory.remove(context, "aa:bb:cc:dd:ee:02");
        KeepADBBlockedNetworkHistory.record(context, identity("D", "aa:bb:cc:dd:ee:04"), 4L);

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(3, entries.size());
        assertFalse(containsBssid(entries, "aa:bb:cc:dd:ee:02"));
    }

    @Test
    public void clearEmptiesTheWholeLog() {
        KeepADBBlockedNetworkHistory.record(context, identity("A", "aa:bb:cc:dd:ee:01"), 1L);
        KeepADBBlockedNetworkHistory.clear(context);
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    private static boolean containsBssid(List<KeepADBBlockedNetworkHistory.Entry> entries,
            String bssid) {
        for (KeepADBBlockedNetworkHistory.Entry entry : entries) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return true;
        }
        return false;
    }

    private static KeepADBNetworkIdentity identity(String ssid, String bssid) {
        return new KeepADBNetworkIdentity(ssid, bssid);
    }

    private static String bssid(int index) {
        return String.format(java.util.Locale.US, "aa:bb:cc:dd:ee:%02x", index);
    }
}
