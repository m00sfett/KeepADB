package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
        assertEquals("HomeMesh", KeepADBNetworkDisplay.ssid(context, "HomeMesh", 3));
        assertEquals("AA:BB:CC:DD:EE:01", KeepADBNetworkDisplay.bssid(context, "AA:BB:CC:DD:EE:01"));
        assertEquals("HomeMesh",
                KeepADBNetworkDisplay.label(context, "HomeMesh", "AA:BB:CC:DD:EE:01", 3));
        assertEquals("AA:BB:CC:DD:EE:01",
                KeepADBNetworkDisplay.label(context, "", "AA:BB:CC:DD:EE:01", 3));
        assertEquals("HomeMesh", KeepADBNetworkDisplay.quoted(context, "HomeMesh"));
    }

    @Test
    public void withThePrivacyModeOnNamesAreReplacedAndAddressesMasked() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertTrue(KeepADBNetworkDisplay.hidden(context));
        assertEquals(hiddenName() + " #3", KeepADBNetworkDisplay.ssid(context, "HomeMesh", 3));
        assertEquals("A single shown name needs no position", hiddenName(),
                KeepADBNetworkDisplay.ssid(context, "HomeMesh", 0));
        assertEquals("AA:*:*:*:*:01", KeepADBNetworkDisplay.bssid(context, "AA:BB:CC:DD:EE:01"));
        assertEquals(hiddenName() + " #2",
                KeepADBNetworkDisplay.label(context, "HomeMesh", "AA:BB:CC:DD:EE:01", 2));
        assertEquals("An unnamed access point falls back to the masked address",
                "AA:*:*:*:*:01", KeepADBNetworkDisplay.label(context, null, "AA:BB:CC:DD:EE:01", 2));
        assertEquals(hiddenName(), KeepADBNetworkDisplay.quoted(context, "HomeMesh"));
        assertEquals("A stored label that is a BSSID copy is hidden too", hiddenName(),
                KeepADBNetworkDisplay.quoted(context, "AA:BB:CC:DD:EE:01"));
    }

    @Test
    public void anUnknownNameIsNeverDisguisedAsAHiddenOne() {
        for (boolean privacy : new boolean[] {false, true}) {
            KeepADBPreferences.setPrivacyModeEnabled(context, privacy);
            String unknown = context.getString(R.string.wifi_aps_ssid_unknown);
            assertEquals(unknown, KeepADBNetworkDisplay.ssid(context, null, 1));
            assertEquals(unknown, KeepADBNetworkDisplay.ssid(context, "", 1));
            assertNotEquals(hiddenName(), KeepADBNetworkDisplay.ssid(context, null, 1));
        }
    }

    @Test
    public void nullInputsNeverThrowAndNeverInventText() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertEquals("", KeepADBNetworkDisplay.bssid(context, null));
        assertEquals("", KeepADBNetworkDisplay.quoted(context, null));
    }
}
