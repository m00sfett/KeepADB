package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.wifi.WifiManager;
import org.junit.Test;

public class KeepADBNetworkIdentityTest {

    @Test
    public void nullWifiInfoIsNeverKnown() {
        assertFalse(KeepADBNetworkIdentity.from(null).isKnown());
    }

    @Test
    public void nullContextIsNeverKnown() {
        assertFalse(KeepADBNetworkIdentity.current(null).isKnown());
    }

    @Test
    public void redactedBssidIsNeverKnown() {
        KeepADBNetworkIdentity redacted =
                new KeepADBNetworkIdentity(KeepADBNetworkIdentity.REDACTED_BSSID, KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse(redacted.isKnown());
    }

    @Test
    public void nullOrEmptyBssidIsNeverKnown() {
        assertFalse(new KeepADBNetworkIdentity("\"home\"", null).isKnown());
        assertFalse(new KeepADBNetworkIdentity("\"home\"", "").isKnown());
    }

    @Test
    public void unsetBssidIsNeverKnown() {
        assertFalse(new KeepADBNetworkIdentity(null, KeepADBNetworkIdentity.UNSET_BSSID).isKnown());
        assertFalse(new KeepADBNetworkIdentity("<unknown ssid>", "00:00:00:00:00:00").isKnown());
    }

    @Test
    public void aRealBssidIsKnown() {
        KeepADBNetworkIdentity identity = new KeepADBNetworkIdentity("\"home\"", "aa:bb:cc:dd:ee:ff");
        assertTrue(identity.isKnown());
    }

    @Test
    public void displaySsidStripsSurroundingQuotes() {
        assertEquals("home", new KeepADBNetworkIdentity("\"home\"", "aa:bb:cc:dd:ee:ff").displaySsid());
        assertEquals(KeepADBNetworkIdentity.REDACTED_BSSID,
                new KeepADBNetworkIdentity(KeepADBNetworkIdentity.REDACTED_BSSID, "aa:bb:cc:dd:ee:ff").displaySsid());
    }

    @Test
    public void displaySsidTreatsUnknownSsidPlaceholderAsNoSsid() {
        // #269: BSSID known and readable, but the platform couldn't read the SSID at query
        // time and returned WifiManager.UNKNOWN_SSID instead of null -- must not be filed as a
        // real SSID bucket/label.
        KeepADBNetworkIdentity identity =
                new KeepADBNetworkIdentity(WifiManager.UNKNOWN_SSID, "aa:bb:cc:dd:ee:ff");
        assertTrue(identity.isKnown());
        assertNull(identity.displaySsid());
    }
}
