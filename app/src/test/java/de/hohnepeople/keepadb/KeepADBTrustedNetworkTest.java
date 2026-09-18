package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class KeepADBTrustedNetworkTest {

    @Before
    public void resetVerifiedTrust() {
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    /**
     * #492: only the exact string {@code allowlist} turns the restriction on. Anything else --
     * including a corrupted or differently-cased value -- is not an opt-in and therefore leaves
     * the app unrestricted. This deliberately reverses the pre-#492 reading, where an
     * unrecognized value fell back to allowlist mode: with the restriction now being an explicit
     * user decision taken against a warning, a value nobody chose must not stand in for it.
     *
     * <p>Note what this does *not* weaken: it changes which networks may trigger an *automatic*
     * re-enable, not how a restricted installation evaluates one. An installation that really is
     * in allowlist mode still fails closed on every unlisted or unreadable identity, which the
     * tests below pin.
     */
    @Test
    public void onlyTheExactAllowlistValueEnablesTheRestriction() {
        for (String mode : new String[] { null, "", "unknown", "ALLOWLIST", "all_wifi" }) {
            FakeContext context = new FakeContext();
            context.getSharedPreferences("keepadb_prefs", 0).edit()
                    .putString("trusted_network_mode", mode).apply();
            assertEquals("Must not read '" + mode + "' as an opt-in",
                    KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
            assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        }
        FakeContext context = new FakeContext();
        context.getSharedPreferences("keepadb_prefs", 0).edit()
                .putString("trusted_network_mode", KeepADBTrustedNetwork.MODE_ALLOWLIST).apply();
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void onlyExactRedactedBssidMayReuseVerifiedTrust() {
        FakeContext context = new FakeContext();
        // #354: the masked-BSSID fallback is only offered while a NetworkCallback is live to
        // invalidate the cache. This test is about *which BSSID values* may reuse verified
        // trust, so it states that precondition and holds it constant.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified =
                new KeepADBNetworkIdentity("Home", "aa:bb:cc:dd:ee:ff");
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("Home", KeepADBNetworkIdentity.REDACTED_BSSID);
        for (String bssid : new String[] { null, "", KeepADBNetworkIdentity.UNSET_BSSID,
                "unknown", " ", KeepADBNetworkIdentity.REDACTED_BSSID + " " }) {
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
            assertFalse("Must reject BSSID: " + bssid, KeepADBTrustedNetwork.isTrustedForTesting(
                    context, new KeepADBNetworkIdentity("Home", bssid)));
            assertFalse("Must discard stale trust after BSSID: " + bssid,
                    KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
        }
    }

    @Test
    public void maskedBssidWithUnavailableOrChangedSsidClearsVerifiedTrust() {
        FakeContext context = new FakeContext();
        // #354 precondition: a live invalidator, so this test only varies the SSID.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified =
                new KeepADBNetworkIdentity("Home", "aa:bb:cc:dd:ee:ff");
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("Home", KeepADBNetworkIdentity.REDACTED_BSSID);
        for (String ssid : new String[] { null, android.net.wifi.WifiManager.UNKNOWN_SSID,
                "", "\"\"", "Neighbor" }) {
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
            assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                    new KeepADBNetworkIdentity(ssid, KeepADBNetworkIdentity.REDACTED_BSSID)));
            assertFalse("Must discard stale trust after SSID: " + ssid,
                    KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
        }
    }

    /**
     * Covers the cache semantics of {@link KeepADBTrustedNetwork#forgetVerifiedTrust()} alone:
     * after it runs, a masked BSSID is no longer trusted until a fresh verified sighting
     * restores it.
     *
     * <p>This test deliberately calls the production method directly, so it proves nothing
     * about the caller. That {@code KeepADBService.onLost()} actually invokes it -- and does so
     * unconditionally, ahead of the {@code foregroundReady} early return -- is asserted
     * separately by {@code KeepADBTrustedNetworkContractTest
     * .networkLossInvalidatesVerifiedTrustBeforeAnyEarlyReturn}. Both are required: drop the
     * contract test and a silently removed {@code onLost()} call would still leave this one
     * green.
     */
    @Test
    public void forgetVerifiedTrustClearsCacheForMaskedReconnect() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true); // #354 precondition
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified =
                new KeepADBNetworkIdentity("Home", "aa:bb:cc:dd:ee:ff");
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("Home", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));

        // Direct call -- the wiring from onLost() is the contract test's job, not this one's.
        KeepADBTrustedNetwork.forgetVerifiedTrust();
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    @Test
    public void freshInstallDefaultsToAllWifiSoTheRestrictionIsOptIn() {
        FakeContext context = new FakeContext();
        // #492 reversed #260's default: a fresh install (no stored mode, no entries) is not
        // restricted, because allowlist mode cannot confirm a network in the background at all
        // (see docs/trusted-networks-measurement.md) and silently shipping it broke Keep-Alive.
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        assertFalse("The SSID alternative is a second, separate opt-in",
                KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
    }

    @Test
    public void allowlistModeFailsClosedWithNoKnownIdentity() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        // Without a real WifiInfo, KeepADBNetworkIdentity.current() can't know the network --
        // allowlist mode must fail closed rather than trusting it.
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    /**
     * #492: the default flip must not widen an existing installation. One that never wrote a mode
     * but does hold allowlist entries was running restricted under the old default, so the
     * migration writes that mode down explicitly instead of letting it fall through to the new,
     * broader default.
     */
    @Test
    public void upgradeWithExistingEntriesKeepsAllowlistModeAndPersistsIt() {
        FakeContext context = new FakeContext();
        // Simulates the pre-#492 on-disk state: entries, but no mode key and no initialized flag.
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        assertFalse(context.getSharedPreferences("keepadb_prefs", 0)
                .contains("trusted_network_mode"));

        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertEquals("The migrated decision must be persisted, not recomputed on every read",
                KeepADBTrustedNetwork.MODE_ALLOWLIST,
                context.getSharedPreferences("keepadb_prefs", 0)
                        .getString("trusted_network_mode", null));

        // And it must stay put once the user empties the list again -- recomputing the proxy would
        // silently flip them to the broader default here.
        KeepADBTrustedNetwork.remove(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id);
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
    }

    /**
     * #492, the other direction: an explicit opt-out must never be re-migrated into allowlist mode
     * by an entry that gets added afterwards (adding one is possible in either mode, via the
     * per-access-point trust button or the notification's allow action).
     */
    @Test
    public void explicitAllWifiChoiceIsNeverMigratedBackToAllowlist() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
    }

    /** #492: an unset mode with no entries is the fresh-install case even after the flag was
     * written once -- the migration is idempotent and must not keep rewriting. */
    @Test
    public void modeMigrationIsIdempotent() {
        FakeContext context = new FakeContext();
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        // Adding an entry afterwards must not retroactively turn this install into an upgrade.
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
    }

    /**
     * #492: the SSID allowlist matches exactly -- equal after quote stripping, with no case
     * folding, trimming, prefix or substring rule -- and only while its own opt-in is on.
     */
    @Test
    public void ssidMatchingIsExactAndGatedBehindItsOwnOptIn() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        KeepADBNetworkIdentity onListedSsid =
                new KeepADBNetworkIdentity("\"MeshHome\"", "aa:bb:cc:dd:ee:01");

        assertFalse("The list must not act while its opt-in is off",
                KeepADBTrustedNetwork.isTrustedForTesting(context, onListedSsid));

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, onListedSsid));

        for (String near : new String[] { "meshhome", "MESHHOME", " MeshHome", "MeshHome ",
                "MeshHome2", "Mesh", "" }) {
            assertFalse("Must not match near-miss SSID: '" + near + "'",
                    KeepADBTrustedNetwork.isTrustedForTesting(context,
                            new KeepADBNetworkIdentity(near, "aa:bb:cc:dd:ee:02")));
        }
    }

    /**
     * #492: the point of the SSID list is that one entry covers several access points sharing that
     * name -- measured as real on the test network, where one SSID is broadcast by two BSSIDs.
     * That widening is the trade-off, and it is what this asserts.
     */
    @Test
    public void oneSsidEntryCoversEveryAccessPointSharingThatName() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "MeshHome");

        for (String bssid : new String[] { "2c:91:ab:0f:13:05", "2c:91:ab:0f:13:04",
                "ff:ee:dd:cc:bb:aa" }) {
            assertTrue("Any access point broadcasting the listed name is trusted: " + bssid,
                    KeepADBTrustedNetwork.isTrustedForTesting(context,
                            new KeepADBNetworkIdentity("MeshHome", bssid)));
        }
    }

    /**
     * #492: the SSID list must never rescue an unreadable reading. This is the property that keeps
     * it from becoming the SSID-only fallback the class javadoc rejects -- a masked BSSID means a
     * masked SSID too (measured), and even a hypothetical readable-SSID/masked-BSSID reading must
     * not be matched against the list.
     */
    @Test
    public void ssidMatchingNeverAppliesToAMaskedOrUnknownIdentity() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        // No prior verification, and no live invalidator either: strictly fail-closed.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);

        for (String bssid : new String[] { KeepADBNetworkIdentity.REDACTED_BSSID,
                KeepADBNetworkIdentity.UNSET_BSSID, null, "" }) {
            assertFalse("A listed SSID must not rescue BSSID: " + bssid,
                    KeepADBTrustedNetwork.isTrustedForTesting(context,
                            new KeepADBNetworkIdentity("MeshHome", bssid)));
        }
        // The placeholder SSID must not match a listed entry either, even with a readable BSSID.
        KeepADBTrustedNetwork.addSsid(context, android.net.wifi.WifiManager.UNKNOWN_SSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity(android.net.wifi.WifiManager.UNKNOWN_SSID,
                        "aa:bb:cc:dd:ee:03")));
    }

    /** #492: turning the SSID opt-in off revokes what it allowed, and removing an entry does too
     * -- both must also drop the connection-scoped verified-trust cache. */
    @Test
    public void disablingOrEmptyingTheSsidListRevokesWhatItAllowed() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.SsidEntry entry = KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        KeepADBNetworkIdentity identity = new KeepADBNetworkIdentity("MeshHome", "aa:bb:cc:dd:ee:04");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, identity));

        assertTrue(KeepADBTrustedNetwork.removeSsid(context, entry.id));
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, identity));
        assertTrue(KeepADBTrustedNetwork.getSsidEntries(context).isEmpty());

        KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, identity));
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, identity));
    }

    /** #492: SSID entries round-trip and dedup exactly (case-sensitively), like BSSID entries. */
    @Test
    public void ssidEntriesRoundTripAndDedupExactly() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.SsidEntry first = KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        assertEquals(first.id, KeepADBTrustedNetwork.addSsid(context, "MeshHome").id);
        assertEquals(1, KeepADBTrustedNetwork.getSsidEntries(context).size());
        // Two names differing only in case are two different networks.
        KeepADBTrustedNetwork.addSsid(context, "meshhome");
        assertEquals(2, KeepADBTrustedNetwork.getSsidEntries(context).size());
        assertNull(KeepADBTrustedNetwork.addSsid(context, "  "));
        assertNull(KeepADBTrustedNetwork.addSsid(context, null));
        assertFalse(KeepADBTrustedNetwork.removeSsid(context, 9999));
        assertEquals(2, KeepADBTrustedNetwork.getSsidEntries(context).size());
    }

    @Test
    public void allWifiModeTrustsEveryNetworkWhenExplicitlySelected() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        // No known network identity is available in a plain JVM test (no real WifiInfo), yet
        // MODE_ALL_WIFI must still trust the network -- this preserves pre-#245 behavior for
        // users who explicitly opt out of the allowlist default.
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.NONE, KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void addCurrentNetworkFailsWithoutAKnownIdentity() {
        FakeContext context = new FakeContext();
        assertNull(KeepADBTrustedNetwork.addCurrentNetwork(context, "Home"));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void removeIsANoOpForAnUnknownId() {
        FakeContext context = new FakeContext();
        assertFalse(KeepADBTrustedNetwork.remove(context, 999));
    }

    @Test
    public void findAndRemoveCurrentNetworkFailWithoutAKnownIdentity() {
        FakeContext context = new FakeContext();
        // Same JVM-test limitation as addCurrentNetworkFailsWithoutAKnownIdentity: no real
        // WifiInfo is available, so the current network's identity is always unknown here.
        assertNull(KeepADBTrustedNetwork.findEntryForCurrentNetwork(context));
        assertNull(KeepADBTrustedNetwork.removeCurrentNetwork(context));
    }

    @Test
    public void entriesRoundTripThroughPreferences() {
        FakeContext context = new FakeContext();
        // Exercise the persistence layer directly with a fabricated id list, since a JVM unit
        // test can't produce a real WifiInfo for addCurrentNetwork() to persist through.
        context.getSharedPreferences("keepadb_prefs", 0).edit()
                .putString("trusted_network_ids", "1,2")
                .putString("trusted_network_1_label", "Home")
                .putString("trusted_network_1_bssid", "aa:bb:cc:dd:ee:ff")
                .putString("trusted_network_2_label", "Office")
                .putString("trusted_network_2_bssid", "11:22:33:44:55:66")
                .apply();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(2, entries.size());
        assertEquals("Home", entries.get(0).label);
        assertEquals("aa:bb:cc:dd:ee:ff", entries.get(0).bssid);

        assertTrue(KeepADBTrustedNetwork.remove(context, 1));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals("Office", KeepADBTrustedNetwork.getEntries(context).get(0).label);
    }

    @Test
    public void addBssidAddsAnArbitraryEntryIndependentOfTheCurrentNetwork() {
        // #266: addBssid() is how the mesh "add other access points too?" flow adds further
        // BSSIDs of an already-trusted SSID, none of which is necessarily the one currently
        // connected -- so it must work without relying on KeepADBNetworkIdentity.current() at
        // all (unlike addCurrentNetwork(), which is unusable in this JVM test environment).
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Mesh AP 2");
        assertEquals("aa:bb:cc:dd:ee:01", entry.bssid);
        assertEquals("Mesh AP 2", entry.label);
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void addBssidIsANoOpForABlankBssid() {
        FakeContext context = new FakeContext();
        assertNull(KeepADBTrustedNetwork.addBssid(context, null, "label"));
        assertNull(KeepADBTrustedNetwork.addBssid(context, "  ", "label"));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void addBssidReturnsTheExistingEntryWithoutDuplicatingIt() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.Entry first = KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "First");
        KeepADBTrustedNetwork.Entry second = KeepADBTrustedNetwork.addBssid(context, "AA:BB:CC:DD:EE:01", "Second");
        assertEquals(first.id, second.id);
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    // #270: Android 12+ masks BSSID to REDACTED_BSSID for backgrounded apps without
    // background-location access. These tests exercise the fallback directly through
    // isTrustedForTesting()/an explicit KeepADBNetworkIdentity, since isCurrentNetworkTrusted()
    // always sees an unknown identity in a plain JVM test (no real WifiManager).

    @Test
    public void maskedBssidStaysTrustedForTheSamePreviouslyVerifiedSsid() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        // #354: KeepADBService's Wi-Fi callback is registered, i.e. the situation #270 was
        // actually written for (background Keep-Alive re-enable with a running service).
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        // Foreground read (or an earlier background read before masking kicked in): real BSSID,
        // matches the allowlist -- this is what must have verified trust before any fallback.
        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        // App goes to background; the platform now masks BSSID, but SSID still reads the same.
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertTrue("masked BSSID on the same SSID as a just-verified trusted network must "
                        + "still be treated as trusted (#270)",
                KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    @Test
    public void maskedBssidFailsClosedWithoutAnyPriorVerification() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        // No real BSSID was ever verified trusted in this process -- the masked reading alone,
        // even with a plausible-looking SSID, must not be trusted.
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    @Test
    public void maskedBssidFailsClosedForADifferentSsidThanLastVerified() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        // A different SSID shows up with a masked BSSID (e.g. roamed to a neighbor's network
        // whose BSSID also happens to get masked) -- must not inherit the earlier trust.
        KeepADBNetworkIdentity maskedOther =
                new KeepADBNetworkIdentity("\"Neighbor\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, maskedOther));
    }

    @Test
    public void disconnectClearsVerifiedTrustSoASubsequentMaskedReadingFailsClosed() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        // An observed disconnect (not associated to anything) breaks the "uninterrupted
        // connection" the fallback relies on.
        KeepADBNetworkIdentity unset =
                new KeepADBNetworkIdentity(null, KeepADBNetworkIdentity.UNSET_BSSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, unset));

        // Even though the SSID matches what was verified before the disconnect, the fallback
        // must not resurrect trust for a new, separate connection.
        KeepADBNetworkIdentity maskedAfterReconnect =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, maskedAfterReconnect));
    }

    @Test
    public void roamingToAKnownUntrustedNetworkClearsVerifiedTrust() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        // Device roams to a different, readable (unmasked) network that shares the same SSID
        // label but is genuinely not listed -- must be rejected, and must not leave stale trust
        // behind for a later masked reading to exploit.
        KeepADBNetworkIdentity untrustedSameSsid =
                new KeepADBNetworkIdentity("\"Home\"", "11:22:33:44:55:66");
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, untrustedSameSsid));

        KeepADBNetworkIdentity maskedAfterRoam =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, maskedAfterRoam));
    }

    @Test
    public void removingTheAllowlistEntryClearsVerifiedTrustSoAMaskedReadingFailsClosed() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.Entry entry =
                KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        // User explicitly revokes trust for "Home" (e.g. from the Settings list) while still
        // connected to it -- KeepADBTrustedNetwork.remove() doesn't require the identity to be
        // known/unmasked, so this can happen entirely independently of the next Wi-Fi read.
        assertTrue(KeepADBTrustedNetwork.remove(context, entry.id));

        // A subsequent masked-BSSID background reading of the same SSID must not resurrect the
        // just-revoked trust from the stale in-process cache.
        KeepADBNetworkIdentity maskedAfterRemoval =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse("removing the allowlist entry must clear the verified-trust cache too",
                KeepADBTrustedNetwork.isTrustedForTesting(context, maskedAfterRemoval));
    }

    /**
     * #353: a mode switch is the same security-relevant event as {@link
     * KeepADBTrustedNetwork#remove}, which already clears the cache (see {@link
     * #removingTheAllowlistEntryClearsVerifiedTrustSoAMaskedReadingFailsClosed}). Reproduces the
     * issue's attack scenario: verified trust in ALLOWLIST mode must not survive a round trip
     * through ALL_WIFI and back -- a masked-BSSID reading after switching back must require fresh
     * BSSID verification instead of resurrecting the stale cache entry.
     */
    @Test
    public void setModeClearsVerifiedTrustSoAMaskedReadingFailsClosedAfterAModeRoundTrip() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));

        // User switches to ALL_WIFI, then back to ALLOWLIST -- e.g. after an intervening
        // connection to a rogue AP impersonating "Home" whose BSSID was never checked while
        // ALL_WIFI was active.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        assertFalse("a mode switch must clear the verified-trust cache like remove() does",
                KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
        // A fresh, real BSSID verification must still work afterwards.
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    /**
     * Also covers switching directly between allowlist entries -- even without ever visiting
     * ALL_WIFI, re-selecting ALLOWLIST mode (e.g. after toggling it off and on again in the
     * Settings UI) must require fresh verification rather than trusting the leftover cache.
     */
    @Test
    public void setModeToTheSameModeStillClearsVerifiedTrust() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse("re-setting the same mode must still invalidate the cache",
                KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    // #354: the masked-BSSID fallback claims "this is still the connection whose BSSID was
    // verified moments ago". Only an invalidator that sees every connection change can back that
    // claim, so the fallback is gated on one being live.

    /**
     * The exact hole from #354's secondary finding: {@code KeepADBEndpoint}'s recovery pulse and
     * {@code KeepADBUsbHandover}'s automatic handover read the policy on paths that do not
     * require {@code KeepADBService} to be running, and {@code onLost()} only fires while it is.
     * A process kept alive with the service stopped (app opened once, USB broadcast, ...) would
     * otherwise carry a verified SSID across arbitrarily many unobserved network changes.
     */
    @Test
    public void maskedBssidFailsClosedWhileNoCallbackIsInvalidatingTheCache() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting(); // no observer: service not running
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        // Foreground reading (BSSID unmasked because the app is in the foreground) fills the
        // cache -- this part must keep working, it is a genuine allowlist match.
        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        // App goes to background, device silently moves to a rogue AP advertising the same SSID.
        // Nothing was watching, so no onLost()/onAvailable() ever ran: fail closed.
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse("a masked reading must not reuse verified trust while nothing is invalidating it",
                KeepADBTrustedNetwork.isTrustedForTesting(context, masked));

        // The rejected reading must also have dropped the entry, so a service starting up later
        // cannot hand out trust that was never re-verified in the meantime.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        assertFalse("a later observer must not resurrect a cache entry that already failed closed",
                KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    /**
     * The other direction of the same invariant: an observer going away (service stopped, e.g.
     * Keep-Alive switched off) invalidates the cache immediately, rather than leaving an entry
     * behind that the next service start would silently inherit.
     */
    @Test
    public void observerTransitionsDropVerifiedTrustInBothDirections() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);

        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));

        // Service stops; anything can happen to the connection now without being observed.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(false);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));

        // Service starts again -- a fresh verified sighting is required, the old one is gone.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        assertFalse("a restarted observer must not inherit trust verified before it existed",
                KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    /**
     * #354's primary case, at the level this class can express it: {@code ConnectivityManager}
     * may deliver {@code onAvailable(new)} before {@code onLost(old)}, so the invalidation the
     * service performs on {@code onAvailable} is what has to reject the new connection -- the
     * cache is still fully populated from the old one at that moment. Whether {@code
     * KeepADBService.onAvailable()} actually performs it (and before it rechecks) is asserted by
     * {@code KeepADBTrustedNetworkContractTest.networkAvailabilityInvalidatesVerifiedTrust...}.
     */
    @Test
    public void reconnectInvalidationRejectsANewConnectionReusingTheOldSsid() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff")));

        // onAvailable(new network) arrives first; onLost(old network) has not run yet.
        KeepADBTrustedNetwork.forgetVerifiedTrust();

        // recheckAndEnable() now evaluates the *new* connection, whose BSSID is masked and whose
        // SSID happens to match the old one. Before #354 this inherited the old network's trust.
        KeepADBNetworkIdentity maskedNewConnection =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse("a reconnect must require fresh BSSID verification even on the same SSID",
                KeepADBTrustedNetwork.isTrustedForTesting(context, maskedNewConnection));
    }

    private static final class FakeContext extends android.content.ContextWrapper {
        private final android.content.SharedPreferences preferences = new MemoryPreferences();

        FakeContext() {
            super(null);
        }

        @Override
        public android.content.Context getApplicationContext() {
            return this;
        }

        @Override
        public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class MemoryPreferences implements android.content.SharedPreferences {
        private final java.util.Map<String, Object> values = new java.util.HashMap<>();

        @Override
        public java.util.Map<String, ?> getAll() {
            return new java.util.HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof java.util.Set ? java.util.Set.copyOf((java.util.Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private final class MemoryEditor implements Editor {
            private final java.util.Map<String, Object> updates = new java.util.HashMap<>();
            private final java.util.Set<String> removals = new java.util.HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, java.util.Set<String> values) {
                updates.put(key, values == null ? null : java.util.Set.copyOf(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) values.clear();
                for (String k : removals) values.remove(k);
                values.putAll(updates);
            }
        }
    }
}
