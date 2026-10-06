package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * #714: the Wi-Fi band of an access point, for display behind its BSSID. An access point out of
 * the scan cache shows no band at all -- there is no placeholder text and no stored value (#788).
 *
 * <p>Only data Android already holds is used, and it is read once per drawing of a list: the
 * frequency of the current connection and the access points in the system's cached scan results
 * ({@link WifiManager#getScanResults()}). Nothing here starts a scan ({@code startScan} is never
 * called), registers a receiver or schedules anything, so no scan-throttling budget is spent and
 * no background work exists. Android hands scan results only to apps with precise location access
 * and enabled location services; without them the read yields nothing and every band reads as
 * unknown. No permission is added: the manifest already declares the location permissions the
 * trusted-network feature needs.
 *
 * <p>A frequency belongs to exactly one BSSID. Two BSSIDs of one mesh or one router (its 2.4 and
 * its 5 GHz radio) are never merged here. Display only: nothing in this class is consulted by the
 * trust decision, which keys on the BSSID alone.
 */
final class KeepADBAccessPointBand {
    static final int UNKNOWN = 0;
    static final int GHZ_2_4 = 1;
    static final int GHZ_5 = 2;
    static final int GHZ_6 = 3;

    private KeepADBAccessPointBand() {}

    /** Maps a centre frequency in MHz to a band; anything unusable or out of range is unknown. */
    static int bandOf(int frequencyMhz) {
        if (frequencyMhz > 2400 && frequencyMhz < 2500) return GHZ_2_4;
        if (frequencyMhz > 4900 && frequencyMhz <= 5925) return GHZ_5;
        if (frequencyMhz > 5925 && frequencyMhz < 7125) return GHZ_6;
        return UNKNOWN;
    }

    /**
     * The band label of a BSSID from a frequency map as built by {@link #read}; unknown when the
     * BSSID is missing from it. {@code bssid} may be in any letter case.
     */
    static int bandOf(Map<String, Integer> frequencies, String bssid) {
        if (frequencies == null || bssid == null) return UNKNOWN;
        Integer frequency = frequencies.get(bssid.toUpperCase(Locale.ROOT));
        return frequency == null ? UNKNOWN : bandOf(frequency);
    }

    /** Whether {@code band} is one of the three real bands (not {@link #UNKNOWN}, not garbage). */
    static boolean isKnown(int band) {
        return band == GHZ_2_4 || band == GHZ_5 || band == GHZ_6;
    }

    /**
     * The label of a known band, or 0 for {@link #UNKNOWN}: an unknown band has no text (#721),
     * so there is nothing to show behind the BSSID.
     */
    static int labelRes(int band) {
        switch (band) {
            case GHZ_2_4:
                return R.string.network_band_24;
            case GHZ_5:
                return R.string.network_band_5;
            case GHZ_6:
                return R.string.network_band_6;
            case UNKNOWN:
            default:
                return 0;
        }
    }

    /**
     * Frequencies in MHz by upper-case BSSID, from the cached scan results and the current
     * connection. Never throws and never scans: a denied permission, disabled location services or
     * a missing {@link WifiManager} just give a smaller (possibly empty) map.
     */
    static Map<String, Integer> read(Context context) {
        if (context == null) return new HashMap<>();
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return read(wifiManager);
        } catch (RuntimeException ignored) {
            return new HashMap<>();
        }
    }

    static Map<String, Integer> read(WifiManager wifiManager) {
        if (wifiManager == null) return new HashMap<>();
        return read(wifiManager::getScanResults, wifiManager::getConnectionInfo);
    }

    /**
     * The same read with its two Android calls passed in, so that a refusal of either one is
     * testable: each is guarded on its own, and a failure of the cache never costs the live
     * connection's frequency or the other way round.
     */
    static Map<String, Integer> read(Supplier<List<ScanResult>> scanResults,
                                     Supplier<WifiInfo> connectionInfo) {
        Map<String, Integer> frequencies = new HashMap<>();
        try {
            addScanResults(frequencies, scanResults.get());
        } catch (RuntimeException ignored) {
            // SecurityException without precise location access or with location switched off:
            // the cache simply is not ours to read, which only means "band unknown".
        }
        try {
            WifiInfo info = connectionInfo.get();
            if (info != null) putIfUsable(frequencies, info.getBSSID(), info.getFrequency(), true);
        } catch (RuntimeException ignored) {
            // The live connection is only a better source than the cache, never a requirement.
        }
        return frequencies;
    }

    private static void addScanResults(Map<String, Integer> frequencies, List<ScanResult> results) {
        if (results == null) return;
        for (ScanResult result : results) {
            if (result == null) continue;
            putIfUsable(frequencies, result.BSSID, result.frequency, false);
        }
    }

    /**
     * Records {@code frequency} for {@code bssid} when both are usable. {@code override} lets the
     * live connection replace a cached scan value for its own BSSID; among scan results the
     * first entry for a BSSID stays.
     */
    static void putIfUsable(Map<String, Integer> frequencies, String bssid, int frequency,
                            boolean override) {
        if (bssid == null || bssid.isEmpty() || bandOf(frequency) == UNKNOWN) return;
        if (KeepADBNetworkIdentity.REDACTED_BSSID.equalsIgnoreCase(bssid)
                || KeepADBNetworkIdentity.UNSET_BSSID.equalsIgnoreCase(bssid)) {
            return;
        }
        String key = bssid.toUpperCase(Locale.ROOT);
        if (override) {
            frequencies.put(key, frequency);
        } else {
            frequencies.putIfAbsent(key, frequency);
        }
    }
}
