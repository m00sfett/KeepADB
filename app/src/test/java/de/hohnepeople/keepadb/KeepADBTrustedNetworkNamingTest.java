package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #714: the stable entry number and the optional own name of an allowed access point. The number
 * is the entry id: handed out once, never reused, untouched by other entries coming and going.
 * The name is display only; the trust decision keys on the BSSID alone, which is asserted from both
 * sides (a name never grants trust, and naming never removes it).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTrustedNetworkNamingTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        clear();
    }

    @After
    public void tearDown() {
        clear();
    }

    private void clear() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static int idOf(List<KeepADBTrustedNetwork.Entry> entries, String bssid) {
        for (KeepADBTrustedNetwork.Entry entry : entries) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return entry.id;
        }
        return -1;
    }

    // --- stable number ------------------------------------------------------------------------

    @Test
    public void entriesGetConsecutiveNumbersInTheOrderTheyWereAdded() {
        KeepADBTrustedNetwork.Entry first = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.Entry second = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:02", "Home");
        KeepADBTrustedNetwork.Entry third = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:03", "Home");
        assertEquals(1, first.id);
        assertEquals(2, second.id);
        assertEquals(3, third.id);
    }

    @Test
    public void removingAnEntryNeverRenumbersTheOthers() {
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:02", "Home");
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:03", "Home");

        assertTrue(KeepADBTrustedNetwork.remove(context, 2));

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(2, entries.size());
        assertEquals(1, idOf(entries, "aa:00:00:00:00:01"));
        assertEquals("The entry behind a gap keeps its number", 3, idOf(entries, "aa:00:00:00:00:03"));
    }

    @Test
    public void addingAnEntryNeverRenumbersAndNeverReusesANumberOfARemovedOne() {
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:02", "Home");
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:03", "Home");
        KeepADBTrustedNetwork.remove(context, 2);

        KeepADBTrustedNetwork.Entry added = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:04", "Home");

        assertEquals("A removed number is not handed out again", 4, added.id);
        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, idOf(entries, "aa:00:00:00:00:01"));
        assertEquals(3, idOf(entries, "aa:00:00:00:00:03"));
        assertEquals(4, idOf(entries, "aa:00:00:00:00:04"));
    }

    @Test
    public void theNumberSurvivesRemovingTheLastEntryAndAddingAgain() {
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.remove(context, 1);
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        assertEquals(2, KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home").id);
    }

    @Test
    public void addingAnAlreadyListedBssidKeepsItsNumberAndItsName() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kitchen");

        KeepADBTrustedNetwork.Entry again = KeepADBTrustedNetwork.addBssid(context, "AA:00:00:00:00:01", "Other");

        assertEquals(entry.id, again.id);
        assertEquals("Kitchen", again.customName);
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    // --- own name -----------------------------------------------------------------------------

    @Test
    public void anEntryHasNoOwnNameUntilOneIsSet() {
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
    }

    @Test
    public void anOwnNameCanBeSavedChangedAndReset() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");

        assertTrue(KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kitchen"));
        assertEquals("Kitchen", KeepADBTrustedNetwork.getEntries(context).get(0).customName);

        assertTrue(KeepADBTrustedNetwork.setCustomName(context, entry.id, "Living room"));
        assertEquals("Living room", KeepADBTrustedNetwork.getEntries(context).get(0).customName);

        assertTrue(KeepADBTrustedNetwork.setCustomName(context, entry.id, null));
        assertNull("Reset returns to the default display",
                KeepADBTrustedNetwork.getEntries(context).get(0).customName);
        assertFalse("Reset leaves no stored value behind", context
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .contains("trusted_network_" + entry.id + "_name"));
    }

    @Test
    public void aBlankNameResetsLikeNull() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kitchen");

        assertTrue(KeepADBTrustedNetwork.setCustomName(context, entry.id, "  \t "));

        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
    }

    @Test
    public void aNameBelongsToExactlyOneEntry() {
        KeepADBTrustedNetwork.Entry first = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.Entry second = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:02", "Home");

        KeepADBTrustedNetwork.setCustomName(context, second.id, "Garage");

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertNull(entries.get(0).customName);
        assertEquals(first.id, entries.get(0).id);
        assertEquals("Garage", entries.get(1).customName);
    }

    @Test
    public void namingAnUnknownEntryChangesNothing() {
        KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");

        assertFalse(KeepADBTrustedNetwork.setCustomName(context, 99, "Ghost"));

        assertFalse(context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .contains("trusted_network_99_name"));
        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
    }

    @Test
    public void namingNeverTouchesTheAddressTheLabelOrTheNumber() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");

        KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kitchen");

        KeepADBTrustedNetwork.Entry stored = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals(entry.id, stored.id);
        assertEquals("aa:00:00:00:00:01", stored.bssid);
        assertEquals("Home", stored.label);
    }

    @Test
    public void removingAnEntryTakesItsNameWithIt() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kitchen");

        KeepADBTrustedNetwork.remove(context, entry.id);

        assertFalse(context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .contains("trusted_network_" + entry.id + "_name"));
        KeepADBTrustedNetwork.Entry readded = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        assertNull("A re-added access point starts without the old name", readded.customName);
        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
    }

    @Test
    public void aNameIsTrimmedSingleLineAndCutAtTheLimit() {
        assertEquals("Kitchen", KeepADBTrustedNetwork.normalizeCustomName("  Kitchen \n"));
        assertEquals("A line break becomes a space: a name is one line", "A B",
                KeepADBTrustedNetwork.normalizeCustomName("A\nB"));
        assertEquals("", KeepADBTrustedNetwork.normalizeCustomName(null));
        assertEquals("", KeepADBTrustedNetwork.normalizeCustomName("\u0007 \n"));

        String exact = repeat('x', KeepADBTrustedNetwork.MAX_CUSTOM_NAME_LENGTH);
        assertEquals("The limit itself is allowed", exact, KeepADBTrustedNetwork.normalizeCustomName(exact));
        assertEquals("One more character is cut", exact,
                KeepADBTrustedNetwork.normalizeCustomName(exact + "y"));
    }

    @Test
    public void theNameLimitIsFortyCharacters() {
        // Literal on purpose: the other tests measure with the constant itself.
        assertEquals(40, KeepADBTrustedNetwork.MAX_CUSTOM_NAME_LENGTH);
        String forty = repeat('x', 40);
        assertEquals(forty, KeepADBTrustedNetwork.normalizeCustomName(forty));
        assertEquals(forty, KeepADBTrustedNetwork.normalizeCustomName(forty + "y"));
    }

    @Test
    public void cuttingNeverSplitsASurrogatePair() {
        String emoji = new String(Character.toChars(0x1F4F6));
        String name = repeat('x', KeepADBTrustedNetwork.MAX_CUSTOM_NAME_LENGTH - 1) + emoji;

        String cut = KeepADBTrustedNetwork.normalizeCustomName(name);

        assertEquals(KeepADBTrustedNetwork.MAX_CUSTOM_NAME_LENGTH - 1, cut.length());
        assertFalse("No lone high surrogate is left", Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    private static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(c);
        return text.toString();
    }

    // --- trust is unchanged -------------------------------------------------------------------

    /**
     * Both sides: a named entry is trusted exactly as before (by its BSSID), and a name never
     * trusts anything -- not an access point whose network name equals the own name, not even with
     * the optional network-name rule switched on.
     */
    @Test
    public void ownNamesNeverChangeAnyTrustDecision() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, "aa:00:00:00:00:01", "Home");
        KeepADBNetworkIdentity listed = new KeepADBNetworkIdentity("Home", "aa:00:00:00:00:01");
        KeepADBNetworkIdentity sibling = new KeepADBNetworkIdentity("Home", "aa:00:00:00:00:02");
        KeepADBNetworkIdentity sameAsOwnName = new KeepADBNetworkIdentity("Kitchen", "aa:00:00:00:00:03");
        KeepADBNetworkIdentity masked = new KeepADBNetworkIdentity("Kitchen",
                KeepADBNetworkIdentity.REDACTED_BSSID);
        boolean[] ssidMatching = {false, true};

        for (boolean matching : ssidMatching) {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, matching);
            boolean listedBefore = KeepADBTrustedNetwork.isTrustedForTesting(context, listed);
            boolean siblingBefore = KeepADBTrustedNetwork.isTrustedForTesting(context, sibling);

            KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kitchen");

            assertTrue(listedBefore);
            assertEquals(listedBefore, KeepADBTrustedNetwork.isTrustedForTesting(context, listed));
            assertEquals(siblingBefore, KeepADBTrustedNetwork.isTrustedForTesting(context, sibling));
            assertFalse("A name is no trust key (ssid matching " + matching + ")",
                    KeepADBTrustedNetwork.isTrustedForTesting(context, sameAsOwnName));
            assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context, masked));

            KeepADBTrustedNetwork.setCustomName(context, entry.id, null);
            assertEquals("Resetting the name changes nothing either", listedBefore,
                    KeepADBTrustedNetwork.isTrustedForTesting(context, listed));
        }
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
    }
}
