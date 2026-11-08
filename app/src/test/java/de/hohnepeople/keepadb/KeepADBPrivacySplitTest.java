package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #904: the single privacy mode became three independent opt-in switches. This pins the one-time
 * derivation of the notification and widget switches for installations that already exist (nothing
 * may loosen), and the defaults of a new installation (all off). The stored side is written with
 * the literal key strings the earlier versions used, not through the setters, so a renamed key
 * (which would silently reset every installation) fails here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBPrivacySplitTest {
    private static final String APP = "privacy_mode_enabled";
    private static final String NOTIFICATIONS = "notification_privacy_enabled";
    private static final String WIDGETS = "widget_privacy_enabled";
    private static final String OLD_DETAILS = "notification_details_enabled";
    private static final String MARKER = "privacy_split_initialized";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
    }

    // --- New installation ----------------------------------------------------------------------------

    @Test
    public void aNewInstallationHasAllThreeSwitchesOffAndStoresOnlyTheMarker() {
        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertEquals("no setting key is written, only the bookkeeping marker",
                new TreeSet<>(Arrays.asList(MARKER)), new TreeSet<>(prefs().getAll().keySet()));
    }

    @Test
    public void theMarkerDoesNotMakeANewInstallationLookLikeAnExistingOne() {
        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse(KeepADBOnboarding.isExistingInstall(context));
    }

    // --- Existing installation: nothing may loosen -----------------------------------------------------

    @Test
    public void anExistingInstallWithoutTheOldDetailsKeyKeepsNotificationDetailsHidden() {
        // Before #904 the details were hidden by default, so an absent key meant "hidden".
        prefs().edit().putBoolean("keep_alive_enabled", true).commit();

        KeepADBPreferences.migratePrivacySplit(context);

        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
    }

    @Test
    public void anExistingInstallThatHadTheDetailsSwitchedOffKeepsThemHidden() {
        prefs().edit().putBoolean(OLD_DETAILS, false).commit();

        KeepADBPreferences.migratePrivacySplit(context);

        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
    }

    @Test
    public void anExistingInstallThatHadTheDetailsSwitchedOnKeepsShowingThem() {
        prefs().edit().putBoolean(OLD_DETAILS, true).commit();

        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
    }

    @Test
    public void theOldPrivacyModeBecomesTheAppSwitchAndTurnsTheWidgetSwitchOn() {
        prefs().edit().putBoolean(APP, true).commit();

        KeepADBPreferences.migratePrivacySplit(context);

        assertTrue("the app switch is the old key, unchanged", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertTrue(prefs().getBoolean(APP, false));
        assertTrue("the widget switch follows the old mode", KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
    }

    @Test
    public void theOldPrivacyModeOffLeavesTheAppAndWidgetSwitchesOff() {
        prefs().edit().putBoolean(APP, false).putBoolean(OLD_DETAILS, true).commit();

        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
    }

    @Test
    public void everyCombinationOfTheTwoOldValuesLoosensNothing() {
        for (boolean oldPrivacy : new boolean[] {false, true}) {
            for (Boolean oldDetails : new Boolean[] {null, false, true}) {
                prefs().edit().clear().putBoolean("keep_alive_enabled", true)
                        .putBoolean(APP, oldPrivacy).commit();
                if (oldDetails != null) prefs().edit().putBoolean(OLD_DETAILS, oldDetails).commit();

                KeepADBPreferences.migratePrivacySplit(context);

                String where = "old privacy " + oldPrivacy + ", old details " + oldDetails;
                assertEquals(where, oldPrivacy, KeepADBPreferences.isAppPrivacyEnabled(context));
                assertEquals(where, oldPrivacy, KeepADBPreferences.isWidgetPrivacyEnabled(context));
                // Only an explicit "details shown" may show them still.
                assertEquals(where, !Boolean.TRUE.equals(oldDetails),
                        KeepADBPreferences.isNotificationPrivacyEnabled(context));
            }
        }
    }

    @Test
    public void theRetiredDetailsKeyStaysStoredAndUntouched() {
        prefs().edit().putBoolean(OLD_DETAILS, true).commit();

        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);

        assertTrue("an older app installed again still finds it", prefs().getBoolean(OLD_DETAILS, false));
    }

    // --- Once, and nothing after ---------------------------------------------------------------------

    @Test
    public void theDerivationRunsOnceAndNeverOverwritesALaterChoice() {
        prefs().edit().putBoolean("keep_alive_enabled", true).commit();
        KeepADBPreferences.migratePrivacySplit(context);
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));

        KeepADBPreferences.setNotificationPrivacyEnabled(context, false);
        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        prefs().edit().putBoolean(OLD_DETAILS, false).commit();
        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse("the user's later choice stays", KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertTrue(KeepADBPreferences.isWidgetPrivacyEnabled(context));
    }

    @Test
    public void readingASwitchBeforeTheApplicationRanDerivesItTheSameWay() {
        // Defense in depth: no component can run before the application's onCreate, but should a
        // switch be read first, it must not fall back to the looser new-installation default.
        prefs().edit().putBoolean(APP, true).putBoolean("keep_alive_enabled", true).commit();

        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertTrue(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertTrue(prefs().getBoolean(MARKER, false));
    }

    @Test
    public void aSwitchWrittenOnANewInstallationIsStoredAndRead() {
        KeepADBPreferences.migratePrivacySplit(context);

        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);
        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);

        Map<String, ?> all = prefs().getAll();
        assertEquals(true, all.get(NOTIFICATIONS));
        assertEquals(true, all.get(WIDGETS));
        assertEquals(true, all.get(APP));
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
