package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class KeepADBTrustedNetworkTest {

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

    /**
     * #625: every placeholder or masked BSSID fails closed -- also when the very same SSID was
     * verified trusted on the call immediately before, and also once it is verified again
     * afterwards. Pins both halves of the pure-function invariant: a masked reading never
     * inherits trust from an earlier call, and it never spoils a later genuine match either.
     */
    @Test
    public void placeholderBssidsNeverInheritTrustFromAnEarlierVerifiedReading() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified =
                new KeepADBNetworkIdentity("Home", "aa:bb:cc:dd:ee:ff");
        for (String bssid : new String[] { KeepADBNetworkIdentity.REDACTED_BSSID, null, "",
                KeepADBNetworkIdentity.UNSET_BSSID, "unknown", " ",
                KeepADBNetworkIdentity.REDACTED_BSSID + " " }) {
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
            assertFalse("Must reject BSSID right after a verified reading: " + bssid,
                    KeepADBTrustedNetwork.isTrustedForTesting(context,
                            new KeepADBNetworkIdentity("Home", bssid)));
            assertTrue("A rejected reading must not spoil the next genuine match",
                    KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        }
    }

    /**
     * #625: a masked BSSID is not trusted whatever the SSID reads -- the verified one, an
     * unavailable one, or a different one. There is no SSID-continuity rescue for masked readings.
     */
    @Test
    public void maskedBssidIsNeverTrustedWhateverTheSsidReads() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified =
                new KeepADBNetworkIdentity("Home", "aa:bb:cc:dd:ee:ff");
        for (String ssid : new String[] { "Home", "\"Home\"", null,
                android.net.wifi.WifiManager.UNKNOWN_SSID, "", "\"\"", "Neighbor" }) {
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
            assertFalse("Masked BSSID must fail closed for SSID: " + ssid,
                    KeepADBTrustedNetwork.isTrustedForTesting(context,
                            new KeepADBNetworkIdentity(ssid, KeepADBNetworkIdentity.REDACTED_BSSID)));
        }
    }

    @Test
    public void freshInstallDefaultsToAllWifiSoTheRestrictionIsOptIn() {
        FakeContext context = new FakeContext();
        // #492 reversed #260's default: a fresh install (no stored mode, no entries) is not
        // restricted. Allowlist mode can only confirm a network while the platform exposes its
        // identity, which needs location access (see docs/trusted-networks-measurement.md) --
        // silently shipping it as the default broke Keep-Alive for everyone without it.
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
     * it from becoming an SSID-only fallback for masked readings -- a masked BSSID means a masked
     * SSID too (measured), and even a hypothetical readable-SSID/masked-BSSID reading must not be
     * matched against the list, not even right after the same SSID was matched readably (#625).
     */
    @Test
    public void ssidMatchingNeverAppliesToAMaskedOrUnknownIdentity() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        KeepADBNetworkIdentity readable = new KeepADBNetworkIdentity("MeshHome", "aa:bb:cc:dd:ee:05");

        for (String bssid : new String[] { KeepADBNetworkIdentity.REDACTED_BSSID,
                KeepADBNetworkIdentity.UNSET_BSSID, null, "" }) {
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, readable));
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

    /** #492: turning the SSID opt-in off revokes what it allowed, and removing an entry does too. */
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

    // #625: KeepADBTrustedNetwork.isTrusted() is a pure function of the identity and the
    // persisted allowlist. The former SSID continuity cache (#270/#313/#353/#354/#355/#620) was
    // removed; the tests below pin that no sequence of earlier calls or policy edits can make a
    // masked or unknown identity trusted, and that trust decisions leave no state behind.

    @Test
    public void maskedIdentityFailsClosedWithoutAnyPriorVerification() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
    }

    /**
     * #625, both trust routes: neither a BSSID match nor an opt-in SSID match on a readable
     * reading may carry over to an immediately following masked reading of the same SSID.
     */
    @Test
    public void maskedIdentityFailsClosedRightAfterEitherTrustRouteMatched() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");

        KeepADBNetworkIdentity viaBssid = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        KeepADBNetworkIdentity viaSsid = new KeepADBNetworkIdentity("\"Mesh\"", "11:22:33:44:55:66");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, viaBssid));
        assertFalse("A BSSID-verified SSID must not rescue a masked reading",
                KeepADBTrustedNetwork.isTrustedForTesting(context,
                        new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID)));
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, viaSsid));
        assertFalse("An SSID-matched network must not rescue a masked reading",
                KeepADBTrustedNetwork.isTrustedForTesting(context,
                        new KeepADBNetworkIdentity("\"Mesh\"", KeepADBNetworkIdentity.REDACTED_BSSID)));
    }

    /**
     * #625: the events that used to invalidate the cache (mode switch, entry removal, SSID opt-in
     * toggle, observed disconnect) no longer carry any meaning for the trust decision -- a masked
     * reading is rejected before and after each of them, while the genuine match keeps working.
     */
    @Test
    public void maskedIdentityStaysFailClosedAcrossFormerInvalidationEvents() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        KeepADBNetworkIdentity masked =
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID);
        Runnable[] events = {
                () -> KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI),
                () -> KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST),
                () -> KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true),
                () -> KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false),
                () -> KeepADBTrustedNetwork.isTrustedForTesting(context,
                        new KeepADBNetworkIdentity(null, KeepADBNetworkIdentity.UNSET_BSSID)),
                () -> KeepADBTrustedNetwork.removeSsid(context,
                        KeepADBTrustedNetwork.addSsid(context, "Other").id),
        };
        for (Runnable event : events) {
            assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
            assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
            event.run();
            assertFalse("A masked reading must stay untrusted after a policy/connection event",
                    KeepADBTrustedNetwork.isTrustedForTesting(context, masked));
        }
    }

    @Test
    public void readableButUnlistedAccessPointSharingATrustedSsidIsNotTrusted() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff")));
        // Without the SSID opt-in, a same-name access point with an unlisted BSSID stays untrusted.
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity("\"Home\"", "11:22:33:44:55:66")));
    }

    @Test
    public void removingTheAllowlistEntryRevokesTrustImmediately() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.Entry entry =
                KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBNetworkIdentity verified = new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));

        assertTrue(KeepADBTrustedNetwork.remove(context, entry.id));
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, verified));
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID)));
    }

    /**
     * #625: trust decisions and the UI reads built on them leave no state behind -- neither in the
     * persisted preferences (once the one-time mode migration has run) nor in anything that could
     * change a later answer. The same inputs give the same answers in any order.
     */
    @Test
    public void trustDecisionsLeaveNoStateBehind() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        KeepADBNetworkIdentity[] identities = {
                new KeepADBNetworkIdentity("\"Home\"", "aa:bb:cc:dd:ee:ff"),
                new KeepADBNetworkIdentity("\"Mesh\"", "11:22:33:44:55:66"),
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.REDACTED_BSSID),
                new KeepADBNetworkIdentity("\"Mesh\"", KeepADBNetworkIdentity.REDACTED_BSSID),
                new KeepADBNetworkIdentity("\"Home\"", "11:22:33:44:55:77"),
                new KeepADBNetworkIdentity(null, KeepADBNetworkIdentity.UNSET_BSSID),
        };
        boolean[] expected = { true, true, false, false, false, false };
        java.util.Map<String, ?> before =
                context.getSharedPreferences("keepadb_prefs", 0).getAll();

        for (int round = 0; round < 3; round++) {
            for (int i = identities.length - 1; i >= 0; i--) {
                assertEquals("Answer for identity " + i + " must not depend on call history",
                        expected[i], KeepADBTrustedNetwork.isTrustedForTesting(context, identities[i]));
            }
            // UI reads (JVM tests only ever see an unknown current identity).
            assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
            assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                    KeepADBTrustedNetwork.getBlockReason(context));
            for (int i = 0; i < identities.length; i++) {
                assertEquals("Answer for identity " + i + " must not depend on call history",
                        expected[i], KeepADBTrustedNetwork.isTrustedForTesting(context, identities[i]));
            }
        }
        assertEquals("Trust decisions must not write preferences", before,
                context.getSharedPreferences("keepadb_prefs", 0).getAll());
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
