package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #654: the one place that applies the privacy mode to network names and addresses on the Network
 * card and its views. Both states of the mode are asserted for every accessor, so a helper that
 * always shows and one that always hides both fail.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkDisplayTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private String hiddenName() {
        return context.getString(R.string.network_privacy_name_hidden);
    }

    @Test
    public void withThePrivacyModeOffNamesAndAddressesAreShownAsTheyAre() {
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        assertFalse(KeepADBNetworkDisplay.hidden(context));
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();
        assertEquals("HomeMesh", KeepADBNetworkDisplay.ssid(context, "HomeMesh", numbering));
        assertEquals("HomeMesh", KeepADBNetworkDisplay.ssid(context, "HomeMesh", null));
        assertEquals("AA:BB:CC:DD:EE:01", KeepADBNetworkDisplay.bssid(context, "AA:BB:CC:DD:EE:01"));
        assertEquals("AA:BB:CC:DD:EE:01", KeepADBNetworkDisplay.bssid(context, "aa:bb:cc:dd:ee:01"));
        assertEquals("DE:11:22:33:44:AD", KeepADBNetworkDisplay.bssid(context, "de:11:22:33:44:ad"));
        assertEquals("HomeMesh",
                KeepADBNetworkDisplay.label(context, "HomeMesh", "AA:BB:CC:DD:EE:01", numbering));
        assertEquals("AA:BB:CC:DD:EE:01",
                KeepADBNetworkDisplay.label(context, "", "AA:BB:CC:DD:EE:01", numbering));
        assertEquals("HomeMesh", KeepADBNetworkDisplay.quoted(context, "HomeMesh"));
    }

    @Test
    public void withThePrivacyModeOnNamesAreReplacedAndAddressesMasked() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertTrue(KeepADBNetworkDisplay.hidden(context));
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();
        assertEquals(hiddenName() + " #1", KeepADBNetworkDisplay.ssid(context, "HomeMesh", numbering));
        assertEquals("A single shown name needs no number", hiddenName(),
                KeepADBNetworkDisplay.ssid(context, "HomeMesh", null));
        assertEquals("AA:*:*:*:*:01", KeepADBNetworkDisplay.bssid(context, "AA:BB:CC:DD:EE:01"));
        assertEquals(hiddenName() + " #1",
                KeepADBNetworkDisplay.label(context, "HomeMesh", "AA:BB:CC:DD:EE:01", numbering));
        assertEquals("An unnamed access point falls back to the masked address",
                "AA:*:*:*:*:01",
                KeepADBNetworkDisplay.label(context, null, "AA:BB:CC:DD:EE:01", numbering));
        assertEquals(hiddenName(), KeepADBNetworkDisplay.quoted(context, "HomeMesh"));
        assertEquals("A stored label that is a BSSID copy is hidden too", hiddenName(),
                KeepADBNetworkDisplay.quoted(context, "AA:BB:CC:DD:EE:01"));
    }

    @Test
    public void anUnknownNameIsNeverDisguisedAsAHiddenOne() {
        for (boolean privacy : new boolean[] {false, true}) {
            KeepADBPreferences.setPrivacyModeEnabled(context, privacy);
            String unknown = context.getString(R.string.wifi_aps_ssid_unknown);
            KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();
            assertEquals(unknown, KeepADBNetworkDisplay.ssid(context, null, numbering));
            assertEquals(unknown, KeepADBNetworkDisplay.ssid(context, "", numbering));
            assertNotEquals(hiddenName(), KeepADBNetworkDisplay.ssid(context, null, numbering));
        }
    }

    @Test
    public void nullInputsNeverThrowAndNeverInventText() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertEquals("", KeepADBNetworkDisplay.bssid(context, null));
        assertEquals("", KeepADBNetworkDisplay.quoted(context, null));
    }

    @Test
    public void aQuotedBssidCopyIsUppercaseWhileAnSsidKeepsItsCase() {
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        String bssid = "aa:bb:cc:dd:ee:01";
        assertEquals("AA:BB:CC:DD:EE:01",
                KeepADBNetworkDisplay.quoted(context, bssid, bssid));
        assertEquals("HomeMesh", KeepADBNetworkDisplay.quoted(context, "HomeMesh", bssid));
        assertEquals("The stored BSSID remains in its original case", "aa:bb:cc:dd:ee:01", bssid);
    }

    // --- band and own name (#714) -------------------------------------------------------------

    @Test
    public void theBandStandsInBracketsDirectlyBehindTheBssidAndUnknownShowsNone() {
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        assertEquals("AA:BB:CC:DD:EE:01 (2.4 GHz)", KeepADBNetworkDisplay.bssidWithBand(
                context, "aa:bb:cc:dd:ee:01", KeepADBAccessPointBand.GHZ_2_4));
        assertEquals("AA:BB:CC:DD:EE:01 (5 GHz)", KeepADBNetworkDisplay.bssidWithBand(
                context, "AA:BB:CC:DD:EE:01", KeepADBAccessPointBand.GHZ_5));
        assertEquals("AA:BB:CC:DD:EE:01 (6 GHz)", KeepADBNetworkDisplay.bssidWithBand(
                context, "AA:BB:CC:DD:EE:01", KeepADBAccessPointBand.GHZ_6));
        // #721: no known band means no text at all -- no brackets, no placeholder.
        assertEquals("AA:BB:CC:DD:EE:01", KeepADBNetworkDisplay.bssidWithBand(
                context, "aa:bb:cc:dd:ee:01", KeepADBAccessPointBand.UNKNOWN));
    }

    @Test
    public void thePrivacyModeMasksTheAddressBehindTheBandButNotTheBand() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertEquals("AA:*:*:*:*:01 (5 GHz)", KeepADBNetworkDisplay.bssidWithBand(
                context, "AA:BB:CC:DD:EE:01", KeepADBAccessPointBand.GHZ_5));
        assertEquals("The masked address stands alone without a band", "AA:*:*:*:*:01",
                KeepADBNetworkDisplay.bssidWithBand(context, "AA:BB:CC:DD:EE:01",
                        KeepADBAccessPointBand.UNKNOWN));
    }

    @Test
    public void anOwnNameIsShownAsTypedAndHiddenByThePrivacyMode() {
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        assertEquals("Kitchen", KeepADBNetworkDisplay.customName(context, "Kitchen"));
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertEquals(hiddenName(), KeepADBNetworkDisplay.customName(context, "Kitchen"));
    }

    // --- numbering of hidden names (#654) -----------------------------------------------------

    private String shown(KeepADBNetworkDisplay.Numbering numbering, String ssid) {
        return KeepADBNetworkDisplay.ssid(context, ssid, numbering);
    }

    @Test
    public void theSameNameGetsTheSameNumberAndADifferentNameADifferentOne() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

        assertEquals(hiddenName() + " #1", shown(numbering, "HomeMesh"));
        assertEquals(hiddenName() + " #2", shown(numbering, "Cafe-WLAN"));
        assertEquals("Asking again must not hand out a new number", hiddenName() + " #1",
                shown(numbering, "HomeMesh"));
        assertEquals(hiddenName() + " #3", shown(numbering, "Hotel-WLAN"));
        assertEquals(hiddenName() + " #2", shown(numbering, "Cafe-WLAN"));
        assertEquals(hiddenName() + " #1", shown(numbering, "HomeMesh"));
    }

    @Test
    public void theCurrentNameAndTheListEntriesOfAViewShareOneNumbering() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

        String current = shown(numbering, "HomeMesh");
        assertEquals(hiddenName() + " #1", current);
        // Same name as the current one: same number. Any other name: never the current number.
        assertEquals(current, shown(numbering, "HomeMesh"));
        assertNotEquals(current, shown(numbering, "Cafe-WLAN"));
        assertNotEquals(current, shown(numbering, "Hotel-WLAN"));
        assertNotEquals(shown(numbering, "Cafe-WLAN"), shown(numbering, "Hotel-WLAN"));
    }

    @Test
    public void everyViewCountsOnItsOwnAndStartsAtOne() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBNetworkDisplay.Numbering first = new KeepADBNetworkDisplay.Numbering();
        KeepADBNetworkDisplay.Numbering second = new KeepADBNetworkDisplay.Numbering();

        assertEquals(hiddenName() + " #1", shown(first, "HomeMesh"));
        assertEquals(hiddenName() + " #2", shown(first, "Cafe-WLAN"));
        assertEquals("A second view does not continue the count of the first",
                hiddenName() + " #1", shown(second, "Cafe-WLAN"));
        assertEquals(hiddenName() + " #2", shown(second, "HomeMesh"));
        assertEquals("...and does not change the first view either", hiddenName() + " #2",
                shown(first, "Cafe-WLAN"));
    }

    /**
     * The rule over a long, repetitive sequence, checked from both sides: two rows read alike
     * exactly when their names are equal (not more often, not less often), and the numbers are the
     * dense range 1..n in the order the names first appear. Repeating the whole pass with a fresh
     * numbering reproduces it, so a redraw of a view never renumbers its rows.
     */
    @Test
    public void numbersAreAFunctionOfTheNameAloneAndStayStableOverARepeatedPass() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        String[] names = {"HomeMesh", "Cafe-WLAN", "Hotel-WLAN", "Office", "Guest", "homemesh",
                "HomeMesh ", "Cafe-WLAN", "x"};
        Random random = new Random(654);
        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 300; i++) sequence.add(names[random.nextInt(names.length)]);

        List<String> firstPass = passOver(sequence);
        assertEquals("A second pass with a fresh numbering gives the same rows", firstPass,
                passOver(sequence));

        Map<String, String> shownByName = new HashMap<>();
        Map<String, String> nameByShown = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            String name = sequence.get(i);
            String text = firstPass.get(i);
            assertEquals("Same name, same number: " + name, shownByName.computeIfAbsent(name, n -> text),
                    text);
            assertEquals("Different names never share a number: " + name + " / " + text,
                    name, nameByShown.computeIfAbsent(text, t -> name));
            if (!order.contains(name)) order.add(name);
        }
        for (int i = 0; i < order.size(); i++) {
            assertEquals("Numbers follow the order of first appearance",
                    hiddenName() + " #" + (i + 1), shownByName.get(order.get(i)));
        }
    }

    private List<String> passOver(List<String> sequence) {
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();
        List<String> result = new ArrayList<>();
        for (String name : sequence) result.add(shown(numbering, name));
        return result;
    }

    @Test
    public void namesThatDifferOnlyInCaseAreTwoNetworks() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

        assertEquals(hiddenName() + " #1", shown(numbering, "HomeMesh"));
        assertEquals(hiddenName() + " #2", shown(numbering, "homemesh"));
        assertEquals(hiddenName() + " #1", shown(numbering, "HomeMesh"));
    }

    @Test
    public void rowsWithoutAReadableNameTakeNoNumber() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

        assertEquals("AA:*:*:*:*:01",
                KeepADBNetworkDisplay.label(context, null, "AA:BB:CC:DD:EE:01", numbering));
        assertEquals("AA:*:*:*:*:02",
                KeepADBNetworkDisplay.label(context, "", "AA:BB:CC:DD:EE:02", numbering));
        shown(numbering, null);
        shown(numbering, "");
        assertEquals("The first real name is still #1", hiddenName() + " #1",
                shown(numbering, "HomeMesh"));
    }

    @Test
    public void aHiddenNumberNeverContainsTheNameOrItsLength() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertEquals(shown(new KeepADBNetworkDisplay.Numbering(), "A"),
                shown(new KeepADBNetworkDisplay.Numbering(), "A-very-long-network-name-with-digits-1234567890"));
    }
}
