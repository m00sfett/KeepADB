package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link KeepADBAccessPointOverview}'s pure data-preparation logic (#461):
 * ordering, mesh-SSID grouping and trust matching. These exercise the plain
 * {@code buildItems(KeepADBNetworkIdentity, List, List)} overload directly, so they run as
 * ordinary JVM unit tests -- no Robolectric, no real {@code WifiManager} -- matching how the
 * card's on-device rendering is tested separately in {@code MainActivityAccessPointOverviewTest}.
 */
public class KeepADBAccessPointOverviewTest {

    @Test
    public void currentConnectionIsFirstAndMarkedCurrent() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");
        List<KeepADBBssidHistory.Observation> history = Collections.singletonList(
                new KeepADBBssidHistory.Observation("OfficeMesh", "bb:bb:bb:bb:bb:01"));

        List<KeepADBAccessPointOverview.ApItem> items =
                KeepADBAccessPointOverview.buildItems(current, history, Collections.emptyList());

        assertEquals(2, items.size());
        assertTrue(items.get(0).current);
        assertEquals("AA:AA:AA:AA:AA:01", items.get(0).bssid);
        assertEquals("HomeMesh", items.get(0).ssid);
        assertFalse(items.get(1).current);
    }

    @Test
    public void historyIsOrderedMostRecentlyObservedFirstAfterCurrent() {
        List<KeepADBBssidHistory.Observation> history = Arrays.asList(
                new KeepADBBssidHistory.Observation("Newest", "cc:cc:cc:cc:cc:01"),
                new KeepADBBssidHistory.Observation("Oldest", "cc:cc:cc:cc:cc:02"));

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                unknownIdentity(), history, Collections.emptyList());

        assertEquals(2, items.size());
        assertEquals("Newest", items.get(0).ssid);
        assertEquals("Oldest", items.get(1).ssid);
    }

    @Test
    public void currentBssidIsNotDuplicatedWhenAlsoPresentInHistory() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");
        List<KeepADBBssidHistory.Observation> history = Arrays.asList(
                new KeepADBBssidHistory.Observation("HomeMesh", "AA:AA:AA:AA:AA:01"),
                new KeepADBBssidHistory.Observation("OfficeMesh", "bb:bb:bb:bb:bb:01"));

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, history, Collections.emptyList());

        assertEquals(2, items.size());
        assertTrue(items.get(0).current);
    }

    @Test
    public void itemMatchingATrustedBssidIsMarkedTrusted() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");
        List<KeepADBTrustedNetwork.Entry> trusted = Collections.singletonList(
                new KeepADBTrustedNetwork.Entry(1, "HomeMesh", "AA:AA:AA:AA:AA:01"));

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, Collections.emptyList(), trusted);

        assertTrue(items.get(0).trusted);
    }

    @Test
    public void itemNotOnTheAllowlistIsMarkedUntrusted() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, Collections.emptyList(), Collections.emptyList());

        assertFalse(items.get(0).trusted);
    }

    @Test
    public void twoAccessPointsSharingAnSsidAreLabeledAsAMeshGroup() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");
        List<KeepADBBssidHistory.Observation> history = Collections.singletonList(
                new KeepADBBssidHistory.Observation("HomeMesh", "aa:aa:aa:aa:aa:02"));

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, history, Collections.emptyList());

        assertEquals(2, items.size());
        for (KeepADBAccessPointOverview.ApItem item : items) {
            assertTrue(item.isMeshMember());
            assertEquals(2, item.meshCount);
            assertTrue(item.meshPosition >= 1 && item.meshPosition <= 2);
        }
        assertTrue(items.get(0).meshPosition != items.get(1).meshPosition);
    }

    @Test
    public void aSingleAccessPointForItsSsidIsNotAMeshMember() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, Collections.emptyList(), Collections.emptyList());

        assertFalse(items.get(0).isMeshMember());
        assertEquals(0, items.get(0).meshPosition);
        assertEquals(1, items.get(0).meshCount);
    }

    @Test
    public void differentSsidsAreNeverGroupedIntoTheSameMesh() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");
        List<KeepADBBssidHistory.Observation> history = Collections.singletonList(
                new KeepADBBssidHistory.Observation("OfficeMesh", "bb:bb:bb:bb:bb:01"));

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, history, Collections.emptyList());

        for (KeepADBAccessPointOverview.ApItem item : items) {
            assertFalse(item.isMeshMember());
        }
    }

    @Test
    public void anUnknownCurrentIdentityYieldsNoCurrentItem() {
        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                unknownIdentity(), Collections.emptyList(), Collections.emptyList());

        assertTrue(items.isEmpty());
    }

    @Test
    public void emptyHistoryAndUnknownTrustNeverThrow() {
        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                unknownIdentity(), null, null);

        assertTrue(items.isEmpty());
    }

    @Test
    public void resultIsCappedAtMaxItems() {
        List<KeepADBBssidHistory.Observation> history = new ArrayList<>();
        for (int i = 0; i < KeepADBAccessPointOverview.MAX_ITEMS + 10; i++) {
            history.add(new KeepADBBssidHistory.Observation("Ssid" + i, bssid(i)));
        }

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                unknownIdentity(), history, Collections.emptyList());

        assertEquals(KeepADBAccessPointOverview.MAX_ITEMS, items.size());
    }

    @Test
    public void currentConnectionAlwaysSurvivesTheCapEvenWithLotsOfHistory() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity("\"HomeMesh\"", "aa:aa:aa:aa:aa:01");
        List<KeepADBBssidHistory.Observation> history = new ArrayList<>();
        for (int i = 0; i < KeepADBAccessPointOverview.MAX_ITEMS + 10; i++) {
            history.add(new KeepADBBssidHistory.Observation("Ssid" + i, bssid(i)));
        }

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, history, Collections.emptyList());

        assertEquals(KeepADBAccessPointOverview.MAX_ITEMS, items.size());
        assertTrue(items.get(0).current);
    }

    @Test
    public void anUnknownSsidFallsBackToTheBssidAsLabel() {
        KeepADBNetworkIdentity current = new KeepADBNetworkIdentity(null, "aa:aa:aa:aa:aa:01");

        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(
                current, Collections.emptyList(), Collections.emptyList());

        assertNull(items.get(0).ssid);
        assertEquals("AA:AA:AA:AA:AA:01", items.get(0).label());
    }

    private static KeepADBNetworkIdentity unknownIdentity() {
        return new KeepADBNetworkIdentity(null, null);
    }

    private static String bssid(int index) {
        return String.format(java.util.Locale.US, "aa:aa:aa:aa:aa:%02x", index % 256);
    }
}
