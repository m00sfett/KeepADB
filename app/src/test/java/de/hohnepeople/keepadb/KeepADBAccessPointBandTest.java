package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowScanResult;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #714: the band of an access point comes from data Android already holds -- the frequency of the
 * current connection and the cached scan results -- one frequency per BSSID, and never from a scan
 * KeepADB starts itself. Both directions are asserted: a measured BSSID shows its own band, an
 * unmeasured one shows none, and no BSSID takes over a sibling's band.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAccessPointBandTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void everyFrequencyMapsToItsBandAndOutOfRangeValuesAreUnknown() {
        int[][] expected = {
                {2412, KeepADBAccessPointBand.GHZ_2_4}, {2437, KeepADBAccessPointBand.GHZ_2_4},
                {2484, KeepADBAccessPointBand.GHZ_2_4},
                {5180, KeepADBAccessPointBand.GHZ_5}, {5500, KeepADBAccessPointBand.GHZ_5},
                {5825, KeepADBAccessPointBand.GHZ_5}, {5885, KeepADBAccessPointBand.GHZ_5},
                {5955, KeepADBAccessPointBand.GHZ_6}, {6415, KeepADBAccessPointBand.GHZ_6},
                {7115, KeepADBAccessPointBand.GHZ_6},
                {0, KeepADBAccessPointBand.UNKNOWN}, {-1, KeepADBAccessPointBand.UNKNOWN},
                {2400, KeepADBAccessPointBand.UNKNOWN}, {2500, KeepADBAccessPointBand.UNKNOWN},
                {4900, KeepADBAccessPointBand.UNKNOWN}, {7125, KeepADBAccessPointBand.UNKNOWN},
                {58320, KeepADBAccessPointBand.UNKNOWN},
        };
        for (int[] pair : expected) {
            assertEquals("frequency " + pair[0], pair[1], KeepADBAccessPointBand.bandOf(pair[0]));
        }
        assertEquals("The 5 and the 6 GHz range meet at 5925 MHz without overlap",
                KeepADBAccessPointBand.GHZ_5, KeepADBAccessPointBand.bandOf(5925));
        assertEquals(KeepADBAccessPointBand.GHZ_6, KeepADBAccessPointBand.bandOf(5926));
    }

    @Test
    public void everyBandHasItsOwnLabelAndUnknownReadsAsSuch() {
        int[] bands = {KeepADBAccessPointBand.GHZ_2_4, KeepADBAccessPointBand.GHZ_5,
                KeepADBAccessPointBand.GHZ_6, KeepADBAccessPointBand.UNKNOWN};
        java.util.Set<Integer> labels = new java.util.HashSet<>();
        for (int band : bands) labels.add(KeepADBAccessPointBand.labelRes(band));
        assertEquals("Four bands, four different labels", 4, labels.size());
        assertEquals("Band unknown",
                context.getString(KeepADBAccessPointBand.labelRes(KeepADBAccessPointBand.UNKNOWN)));
        assertEquals("2.4 GHz",
                context.getString(KeepADBAccessPointBand.labelRes(KeepADBAccessPointBand.GHZ_2_4)));
    }

    /** Two radios of one router share the SSID but are two BSSIDs, each with its own band. */
    @Test
    public void cachedScanResultsAssignOneBandPerBssidWithoutGroupingThem() {
        WifiManager wifiManager = wifiManager();
        shadowOf(wifiManager).setScanResults(Arrays.asList(
                scan("HomeMesh", "aa:aa:aa:aa:aa:01", 2412),
                scan("HomeMesh", "aa:aa:aa:aa:aa:02", 5180),
                scan("HomeMesh", "aa:aa:aa:aa:aa:03", 6415),
                scan("Other", "bb:bb:bb:bb:bb:01", 0)));

        Map<String, Integer> frequencies = KeepADBAccessPointBand.read(context);

        assertEquals(KeepADBAccessPointBand.GHZ_2_4,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:01"));
        assertEquals(KeepADBAccessPointBand.GHZ_5,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:02"));
        assertEquals(KeepADBAccessPointBand.GHZ_6,
                KeepADBAccessPointBand.bandOf(frequencies, "aa:aa:aa:aa:aa:03"));
        assertEquals("A scan entry without a usable frequency stays unknown",
                KeepADBAccessPointBand.UNKNOWN,
                KeepADBAccessPointBand.bandOf(frequencies, "BB:BB:BB:BB:BB:01"));
        assertEquals("A BSSID Android never reported stays unknown", KeepADBAccessPointBand.UNKNOWN,
                KeepADBAccessPointBand.bandOf(frequencies, "CC:CC:CC:CC:CC:01"));
        assertEquals(3, frequencies.size());
    }

    @Test
    public void theLiveConnectionFrequencyWinsOverTheCachedScanOfItsOwnBssid() {
        WifiManager wifiManager = wifiManager();
        shadowOf(wifiManager).setScanResults(Collections.singletonList(
                scan("HomeMesh", "aa:aa:aa:aa:aa:01", 2412)));
        connectTo(wifiManager, "HomeMesh", "aa:aa:aa:aa:aa:01", 5180);

        Map<String, Integer> frequencies = KeepADBAccessPointBand.read(context);

        assertEquals(KeepADBAccessPointBand.GHZ_5,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:01"));
    }

    @Test
    public void theConnectionAloneSuppliesItsBandWhenTheCacheIsEmpty() {
        WifiManager wifiManager = wifiManager();
        connectTo(wifiManager, "HomeMesh", "aa:aa:aa:aa:aa:01", 2437);

        Map<String, Integer> frequencies = KeepADBAccessPointBand.read(context);

        assertEquals(KeepADBAccessPointBand.GHZ_2_4,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:01"));
        assertEquals(KeepADBAccessPointBand.UNKNOWN,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:02"));
    }

    @Test
    public void aMaskedOrUnsetConnectionAddressIsNeverRecordedAsABssid() {
        WifiManager wifiManager = wifiManager();
        connectTo(wifiManager, "HomeMesh", KeepADBNetworkIdentity.REDACTED_BSSID, 5180);
        assertTrue(KeepADBAccessPointBand.read(context).isEmpty());
        connectTo(wifiManager, "HomeMesh", KeepADBNetworkIdentity.UNSET_BSSID, 5180);
        assertTrue(KeepADBAccessPointBand.read(context).isEmpty());
    }

    /** Without location access Android refuses the cache; that is "unknown", never a crash. */
    @Test
    public void aRefusedScanCacheOnlyMeansUnknownAndKeepsTheLiveConnection() {
        WifiInfo connected = info("HomeMesh", "aa:aa:aa:aa:aa:01", 5180);

        Map<String, Integer> frequencies = KeepADBAccessPointBand.read(
                () -> { throw new SecurityException("location off"); }, () -> connected);

        assertEquals(KeepADBAccessPointBand.GHZ_5,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:01"));
    }

    @Test
    public void aRefusedConnectionReadKeepsTheCachedScan() {
        List<ScanResult> cached = Collections.singletonList(scan("HomeMesh", "aa:aa:aa:aa:aa:02", 2412));

        Map<String, Integer> frequencies = KeepADBAccessPointBand.read(
                () -> cached, () -> { throw new SecurityException("no wifi state"); });

        assertEquals(KeepADBAccessPointBand.GHZ_2_4,
                KeepADBAccessPointBand.bandOf(frequencies, "AA:AA:AA:AA:AA:02"));
    }

    @Test
    public void everythingRefusedOrMissingYieldsAnEmptyMapAndUnknownBands() {
        assertTrue(KeepADBAccessPointBand.read(
                () -> { throw new SecurityException("no"); },
                () -> { throw new IllegalStateException("no"); }).isEmpty());
        assertTrue(KeepADBAccessPointBand.read((WifiManager) null).isEmpty());
        assertTrue(KeepADBAccessPointBand.read((Context) null).isEmpty());
        assertTrue(KeepADBAccessPointBand.read(() -> null, () -> null).isEmpty());
        assertEquals(KeepADBAccessPointBand.UNKNOWN,
                KeepADBAccessPointBand.bandOf(null, "AA:AA:AA:AA:AA:01"));
        assertEquals(KeepADBAccessPointBand.UNKNOWN,
                KeepADBAccessPointBand.bandOf(new java.util.HashMap<>(), null));
    }

    /**
     * The band is a presentation detail: no scan is started by KeepADB (no {@code startScan}
     * anywhere in the production code), no scan-result receiver is registered, and no trust path
     * references the band class -- so band data can neither cost scan budget nor change a decision.
     */
    @Test
    public void bandDataIsReadOnlyAndNeverReachesATrustDecision() throws IOException {
        List<Path> sources = mainSources();
        assertFalse(sources.isEmpty());
        for (Path source : sources) {
            String code = stripComments(read(source));
            assertFalse(source + " must not start a Wi-Fi scan", code.contains("startScan"));
            assertFalse(source + " must not listen for scan results",
                    code.contains("SCAN_RESULTS_AVAILABLE_ACTION"));
            String name = source.getFileName().toString();
            boolean allowedUser = name.equals("KeepADBAccessPointBand.java")
                    || name.equals("KeepADBNetworkDisplay.java")
                    || name.equals("NetworkListActivity.java");
            if (!allowedUser) {
                assertFalse(name + " must not use the display-only band data",
                        code.contains("KeepADBAccessPointBand"));
            }
        }
        assertNotEquals("Sanity: the scan includes the band class itself", 0,
                sources.stream().filter(p -> p.getFileName().toString()
                        .equals("KeepADBAccessPointBand.java")).count());
    }

    private WifiManager wifiManager() {
        return (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    private static ScanResult scan(String ssid, String bssid, int frequency) {
        return ShadowScanResult.newInstance(ssid, bssid, "[WPA2-PSK-CCMP]", -50, frequency);
    }

    private static WifiInfo info(String ssid, String bssid, int frequency) {
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(info).setFrequency(frequency);
        return info;
    }

    private static void connectTo(WifiManager wifiManager, String ssid, String bssid, int frequency) {
        shadowOf(wifiManager).setConnectionInfo(info(ssid, bssid, frequency));
    }

    private static List<Path> mainSources() throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        try (Stream<Path> files = Files.walk(
                directory.resolve("app/src/main/java/de/hohnepeople/keepadb"))) {
            List<Path> result = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
            return result;
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
