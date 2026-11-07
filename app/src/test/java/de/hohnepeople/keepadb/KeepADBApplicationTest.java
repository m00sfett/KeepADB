package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import java.util.Arrays;
import java.util.TreeSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #904: what the application object does at the first start of the process. These tests must not
 * clear the preferences first: they look at what the start itself left behind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBApplicationTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void theManifestApplicationDerivesThePrivacySwitchesBeforeAnythingElseWrites() {
        assertTrue(RuntimeEnvironment.getApplication() instanceof KeepADBApplication);

        assertEquals("a new installation stores the marker and nothing else after its first start",
                new TreeSet<>(Arrays.asList("privacy_split_initialized")),
                new TreeSet<>(context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                        .getAll().keySet()));
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));
    }

    private static final String LIST = KeepADBPrivacy.SCOPE_NETWORK_LIST;
    private static final String WEBHOOK = KeepADBPrivacy.SCOPE_WEBHOOK;

    @Test
    public void aRevealEndsWhenTheScreenStops() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context)).setup();
        Activity screen = controller.get();
        assertTrue(KeepADBPrivacy.isMasked(screen, LIST));

        KeepADBPrivacy.reveal(screen, LIST);
        assertFalse(KeepADBPrivacy.isMasked(screen, LIST));
        assertTrue(KeepADBPrivacy.isRevealed(screen, LIST));
        assertTrue("the stored switch is untouched", KeepADBPreferences.isAppPrivacyEnabled(context));

        controller.pause().stop();
        assertTrue("the application's lifecycle callback ended the reveal",
                KeepADBPrivacy.isMasked(screen, LIST));
        controller.destroy();
    }

    @Test
    public void aRevealBelongsToOneScopeAndOneScreenAndNeverToAPlainContext() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        ActivityController<NetworkListActivity> first = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context)).setup();
        ActivityController<SettingsActivity> second = Robolectric.buildActivity(
                SettingsActivity.class).setup();

        KeepADBPrivacy.reveal(second.get(), WEBHOOK);

        assertFalse(KeepADBPrivacy.isMasked(second.get(), WEBHOOK));
        assertTrue("another scope of the same screen stays masked",
                KeepADBPrivacy.isMasked(second.get(), KeepADBPrivacy.SCOPE_USB));
        assertTrue("an eye-only surface (plain activity) reads the switch alone",
                KeepADBPrivacy.isAppMasked(second.get()));
        assertTrue("another screen stays masked", KeepADBPrivacy.isMasked(first.get(), WEBHOOK));
        assertTrue("a context without a screen can never be revealed", KeepADBPrivacy.isAppMasked(context));
        first.pause().stop().destroy();
        second.pause().stop().destroy();
    }

    @Test
    public void aRevealNeedsTheSwitchAndTurningItOnAgainMasksTheScreenAgain() {
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context)).setup();
        Activity screen = controller.get();
        KeepADBPrivacy.reveal(screen, LIST);
        assertFalse("with the switch off there is nothing to reveal",
                KeepADBPrivacy.isRevealed(screen, LIST));

        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPrivacy.reveal(screen, LIST);
        KeepADBPrivacy.reveal(screen, WEBHOOK);
        assertFalse(KeepADBPrivacy.isMasked(screen, LIST));
        KeepADBPrivacy.conceal(screen, LIST);
        assertTrue(KeepADBPrivacy.isMasked(screen, LIST));
        assertFalse("conceal ends one scope only", KeepADBPrivacy.isMasked(screen, WEBHOOK));
        KeepADBPrivacy.concealAll(screen);
        assertTrue(KeepADBPrivacy.isMasked(screen, WEBHOOK));
        controller.pause().stop().destroy();
    }
}
