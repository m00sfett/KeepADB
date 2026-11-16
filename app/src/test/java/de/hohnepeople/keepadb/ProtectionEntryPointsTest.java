package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * #959: every way into the former Network card of Settings (focus extras, warning actions, home
 * lines and buttons, the force-mode notice, the widget, the Wi-Fi networks list) opens the
 * Protection page at the right section. One test per entry; each one runs the intent the sender
 * really builds, resolves it through the manifest and starts the page with it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProtectionEntryPointsTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
    }

    // --- the page itself ------------------------------------------------------------------------------

    @Test
    public void thePageIsDeclaredInTheManifestAndNotExported() {
        Intent intent = ProtectionActivity.intent(context);
        assertNotNull("The page must resolve through the manifest",
                context.getPackageManager().resolveActivity(intent, 0));
        assertFalse(context.getPackageManager().resolveActivity(intent, 0).activityInfo.exported);
    }

    @Test
    public void withoutAnExtraThePageOpensAtItsTopAndFocusesNothing() {
        ProtectionActivity page = start(ProtectionActivity.intent(context));

        assertFalse(page.getIntent().hasExtra(ProtectionActivity.EXTRA_FOCUS_LEVEL));
        assertFalse(page.getIntent().hasExtra(ProtectionActivity.EXTRA_FOCUS_FORCE));
        assertNull("Nothing is focused by default", page.getCurrentFocus());
    }

    @Test
    public void theLevelExtraBringsTheStoredLevelIntoFocusAndIsConsumed() {
        ProtectionActivity page = start(ProtectionActivity.levelIntent(context));

        assertTrue("The stored level's card takes the focus", storedLevelCard(page).hasFocus());
        assertFalse(page.getIntent().hasExtra(ProtectionActivity.EXTRA_FOCUS_LEVEL));
    }

    @Test
    public void theForceExtraFocusesTheForceButtonAndIsConsumed() {
        ProtectionActivity page = start(ProtectionActivity.forceIntent(context));

        assertTrue("The activate button takes the focus while the mode is off",
                page.findViewById(R.id.settings_force_activate).hasFocus());
        assertFalse(page.getIntent().hasExtra(ProtectionActivity.EXTRA_FOCUS_FORCE));

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ProtectionActivity running = start(ProtectionActivity.forceIntent(context));
        assertTrue("The end button takes the focus while the mode runs",
                running.findViewById(R.id.settings_force_end).hasFocus());
    }

    // --- the senders ------------------------------------------------------------------------------------

    @Test
    public void theHomeKeepAliveLineLeadsToTheLevel() {
        MainActivity home = Robolectric.buildActivity(MainActivity.class).setup().get();
        home.findViewById(R.id.keep_alive_subtext).performClick();

        assertOpensPage(shadowOf(home).getNextStartedActivity(),
                ProtectionActivity.EXTRA_FOCUS_LEVEL);
    }

    @Test
    public void theHomeForceCardsSettingsButtonLeadsToTheForceRow() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity home = Robolectric.buildActivity(MainActivity.class).setup().get();
        home.findViewById(R.id.btn_force_settings).performClick();

        assertOpensPage(shadowOf(home).getNextStartedActivity(),
                ProtectionActivity.EXTRA_FOCUS_FORCE);
    }

    @Test
    public void theReviewActionOfTheForceWarningLeadsToTheForceRow() {
        assertOpensPage(KeepADBWarningState.reviewIntent(context,
                KeepADBWarningState.Reason.FORCE_MODE), ProtectionActivity.EXTRA_FOCUS_FORCE);
    }

    @Test
    public void theReviewActionOfTheAllWifiWarningLeadsToTheLevel() {
        assertOpensPage(KeepADBWarningState.reviewIntent(context,
                KeepADBWarningState.Reason.PROTECTION_ALL_WIFI),
                ProtectionActivity.EXTRA_FOCUS_LEVEL);
    }

    @Test
    public void theForceExpiryNoticeLeadsToTheForceRow() {
        assertOpensPage(KeepADBForceNotice.focusForceIntent(context),
                ProtectionActivity.EXTRA_FOCUS_FORCE);
        assertTrue("A notification starts it in a task of its own",
                (KeepADBForceNotice.focusForceIntent(context).getFlags()
                        & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void theWidgetsNetworkTapLeadsToTheLevel() {
        PendingIntent pending = KeepADBWidgetActions.openNetwork(context);
        assertTrue(shadowOf(pending).isActivityIntent());

        assertOpensPage(shadowOf(pending).getSavedIntent(), ProtectionActivity.EXTRA_FOCUS_LEVEL);
    }

    @Test
    public void theSettingsRowLeadsToTheTopOfThePage() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        settings.findViewById(R.id.settings_protection_row).performClick();

        Intent opened = shadowOf(settings).getNextStartedActivity();
        assertOpensPage(opened, null);
    }

    @Test
    public void theLevelRowOfTheWifiNetworksListLeadsToTheLevel() {
        NetworkListActivity list = Robolectric.buildActivity(NetworkListActivity.class,
                NetworkListActivity.intent(context)).setup().get();
        ShadowLooper.idleMainLooper();
        View row = findClickableWithText(list.getWindow().getDecorView(),
                context.getString(R.string.networks_level, KeepADBForceNotice.levelLabel(context)));
        assertNotNull("The list shows the level as a row", row);

        row.performClick();

        assertOpensPage(shadowOf(list).getNextStartedActivity(),
                ProtectionActivity.EXTRA_FOCUS_LEVEL);
    }

    @Test
    public void theBackgroundDialogsChooseLevelButtonScrollsToTheLevelInsteadOfOpeningTheAssistant() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ProtectionActivity page = start(ProtectionActivity.intent(context));
        page.findViewById(R.id.settings_background_location_button).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertNull("Nothing is focused before the choice", page.getCurrentFocus());

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();

        assertNull("The level is chosen on this page: the assistant does not open",
                shadowOf(page).getNextStartedActivity());
        assertTrue("The level section takes the focus", storedLevelCard(page).hasFocus());
    }

    // --- what an older version may still have posted ----------------------------------------------

    /**
     * #959: a notification or shortcut of an older version still carries the extras of the former
     * card on {@link SettingsActivity}. Each one is forwarded to the page at the same section, and
     * used up, so it does not open the page a second time.
     */
    @Test
    public void theFormerSettingsExtrasAreForwardedToTheSameSectionOfThePage() {
        assertForwarded(SettingsActivity.EXTRA_FOCUS_FORCE, ProtectionActivity.EXTRA_FOCUS_FORCE);
        assertForwarded(SettingsActivity.EXTRA_FOCUS_NETWORK, ProtectionActivity.EXTRA_FOCUS_LEVEL);
        assertForwarded(SettingsActivity.EXTRA_FOCUS_NETWORK_CARD, null);
    }

    private void assertForwarded(String formerExtra, String expectedExtra) {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(
                SettingsActivity.class,
                new Intent(context, SettingsActivity.class).putExtra(formerExtra, true)).setup();
        SettingsActivity settings = controller.get();

        Intent forwarded = shadowOf(settings).getNextStartedActivity();
        assertOpensPage(forwarded, expectedExtra);
        assertFalse("The former extra is used up", settings.getIntent().hasExtra(formerExtra));
        assertNull("... and forwards once only", shadowOf(settings).getNextStartedActivity());
        controller.pause().stop().destroy();
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** The intent resolves to the page, names the expected section (or none) and starts it. */
    private void assertOpensPage(Intent intent, String expectedExtra) {
        assertNotNull("The entry must start something", intent);
        assertEquals(ProtectionActivity.class.getName(), intent.getComponent().getClassName());
        assertNotNull("... which resolves through the manifest",
                context.getPackageManager().resolveActivity(intent, 0));
        for (String extra : new String[] {ProtectionActivity.EXTRA_FOCUS_LEVEL,
                ProtectionActivity.EXTRA_FOCUS_FORCE}) {
            assertEquals("Section extra " + extra, extra.equals(expectedExtra),
                    intent.getBooleanExtra(extra, false));
        }
        ActivityController<ProtectionActivity> controller =
                Robolectric.buildActivity(ProtectionActivity.class, intent).setup();
        ShadowLooper.idleMainLooper();
        ProtectionActivity page = controller.get();
        if (ProtectionActivity.EXTRA_FOCUS_LEVEL.equals(expectedExtra)) {
            assertTrue("The level is focused", storedLevelCard(page).hasFocus());
        } else if (ProtectionActivity.EXTRA_FOCUS_FORCE.equals(expectedExtra)) {
            assertTrue("The force row is focused", page.findViewById(
                    KeepADBForceMode.isActive(context) ? R.id.settings_force_end
                            : R.id.settings_force_activate).hasFocus());
        } else {
            assertNull("No section is focused", page.getCurrentFocus());
        }
        controller.pause().stop().destroy();
    }

    private ProtectionActivity start(Intent intent) {
        ProtectionActivity page =
                Robolectric.buildActivity(ProtectionActivity.class, intent).setup().get();
        ShadowLooper.idleMainLooper();
        return page;
    }

    /** The card of the stored protection level on the page. */
    private View storedLevelCard(ProtectionActivity page) {
        android.view.ViewGroup options = page.findViewById(R.id.protection_level_options);
        KeepADBTrustedNetwork.ProtectionLevel stored = KeepADBTrustedNetwork.getProtectionLevel(page);
        int index = stored == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI ? 0
                : stored == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST ? 1
                : stored == KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY ? 2 : 3;
        View card = options.getChildAt(index);
        assertEquals("The stored level's card is shown", View.VISIBLE, card.getVisibility());
        return card;
    }

    private static View findClickableWithText(View view, String text) {
        if (view instanceof android.widget.TextView && view.isClickable()
                && text.contentEquals(((android.widget.TextView) view).getText())) {
            return view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableWithText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
