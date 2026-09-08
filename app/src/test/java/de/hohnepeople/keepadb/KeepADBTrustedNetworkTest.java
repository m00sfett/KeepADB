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

    @Test
    public void unknownModesDefaultToAllowlistAndFailClosed() {
        for (String mode : new String[] { null, "", "unknown", "ALL_WIFI" }) {
            FakeContext context = new FakeContext();
            context.getSharedPreferences("keepadb_prefs", 0).edit()
                    .putString("trusted_network_mode", mode).apply();
            assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST,
                    KeepADBTrustedNetwork.getMode(context));
            assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
            assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
            assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                    KeepADBTrustedNetwork.getBlockReason(context));
        }
    }

    @Test
    public void onlyExactRedactedBssidMayReuseVerifiedTrust() {
        FakeContext context = new FakeContext();
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
    public void defaultModeIsAllowlistAndFailsClosedWithNoKnownIdentity() {
        FakeContext context = new FakeContext();
        // #260: a freshly installed device (no stored mode) defaults to allowlist mode, so it's
        // protected immediately -- there is no fail-open default anymore, and no migration path
        // that special-cases pre-existing installations either.
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        // Without a real WifiInfo, KeepADBNetworkIdentity.current() can't know the network --
        // allowlist mode must fail closed rather than trusting it.
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                KeepADBTrustedNetwork.getBlockReason(context));
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
