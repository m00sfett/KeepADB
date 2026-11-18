package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #958: the general security advice moved from the home screen into the setup assistant, so the
 * stored dismiss flag of the old home card is of no use. The first start after the update deletes
 * it, whether the card was dismissed or not, and leaves everything else alone. The cleanup runs
 * from the application object, like the one for the removed USB-ADB area (#956).
 *
 * <p>Each test writes the old state first and then runs the application's {@code onCreate} again,
 * which is what the next process start does. Without the cleanup the "gone" assertions are red.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAdviceBannerRemovalMigrationTest {
    private static final String KEY = "advice_banner_visible";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Application app = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
    }

    private void nextProcessStart() {
        ((KeepADBApplication) app).onCreate();
    }

    @Test
    public void aDismissedAdviceFlagIsGoneAfterTheFirstStartAndOtherSettingsAreUntouched() {
        prefs().edit()
                .putBoolean(KEY, false)
                .putBoolean("keep_alive_enabled", true)
                .putString("trusted_network_mode", "allowlist")
                .putString("app_language", "de")
                .commit();
        Map<String, ?> before = prefs().getAll();
        assertTrue("control: the old flag is really stored", before.containsKey(KEY));

        nextProcessStart();

        Map<String, Object> expected = new TreeMap<>();
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            if (!KEY.equals(entry.getKey())) expected.put(entry.getKey(), entry.getValue());
        }
        expected.put("privacy_split_initialized", true);
        expected.put("notification_privacy_enabled", true);
        expected.put("widget_privacy_enabled", false);
        expected.put("onboarding_existing_install", true);
        expected.put("open_network_shutdown_enabled", false); // #997: existing installation
        assertEquals("only the advice flag was removed", expected, new TreeMap<>(prefs().getAll()));
    }

    @Test
    public void aVisibleAdviceFlagIsGoneToo() {
        prefs().edit().putBoolean(KEY, true).putBoolean("keep_alive_enabled", false).commit();

        nextProcessStart();

        assertFalse(prefs().contains(KEY));
        assertTrue(prefs().contains("keep_alive_enabled"));
    }

    @Test
    public void aSecondStartChangesNothing() {
        prefs().edit().putBoolean(KEY, false).putBoolean("keep_alive_enabled", true).commit();
        nextProcessStart();
        Map<String, ?> afterFirst = prefs().getAll();

        nextProcessStart();

        assertEquals(new TreeMap<>(afterFirst), new TreeMap<>(prefs().getAll()));
    }

    @Test
    public void aNewInstallationGetsNoWriteFromTheCleanup() {
        // Same as for the other cleanups: a new installation keeps exactly the privacy marker, and
        // the assistant's existing/new decision stays open for the first real question.
        prefs().edit().clear().commit();
        KeepADBPreferences.migrateOpenNetworkShutdown(app);
        KeepADBPreferences.migratePrivacySplit(app);
        Map<String, ?> before = prefs().getAll();

        nextProcessStart();

        assertEquals(new TreeMap<>(before), new TreeMap<>(prefs().getAll()));
        assertFalse(prefs().contains("onboarding_existing_install"));
    }

    @Test
    public void anInstallationWhoseOnlyKeyWasTheAdviceFlagStaysAnExistingInstallation() {
        prefs().edit().clear().commit();
        prefs().edit().putBoolean(KEY, false).commit();

        nextProcessStart();

        assertFalse(prefs().contains(KEY));
        assertTrue("it was not a new installation before the update, and still is not",
                KeepADBOnboarding.isExistingInstall(app));
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
