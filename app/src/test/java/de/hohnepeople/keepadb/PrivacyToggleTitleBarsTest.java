package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #725: the privacy-mode eye sits in every red title bar, is one shared button with one shared
 * setting, and redraws the page at once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PrivacyToggleTitleBarsTest {

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
        KeepADB.resetForTesting();
    }

    // --- presence ------------------------------------------------------------------------------

    @Test
    public void mainViewSettingsAndNetworkListHaveTheEyeInsideTheirHeader() {
        // #941: the main view has the eye again, left of the gear (replacing #823's menu entries).
        List<Activity> pages = new ArrayList<>();
        pages.add(Robolectric.buildActivity(MainActivity.class).setup().get());
        pages.add(Robolectric.buildActivity(SettingsActivity.class).setup().get());
        pages.add(openList());
        for (Activity page : pages) {
            String name = page.getClass().getSimpleName();
            View header = page.findViewById(R.id.header_bar);
            View eye = page.findViewById(R.id.btn_toggle_privacy_mode);
            assertNotNull(name + " has the privacy eye", eye);
            assertTrue(name + ": eye is an ImageButton", eye instanceof ImageButton);
            assertTrue(name + ": the eye is inside the title bar",
                    isDescendantOf(eye, header));
            assertEquals(name + ": visible", View.VISIBLE, eye.getVisibility());
            assertEquals(name + ": tap target 48dp", Math.round(48 * context.getResources()
                    .getDisplayMetrics().density), eye.getLayoutParams().width);
        }
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == ancestor) return true;
            if (!(parent instanceof View)) return false;
            parent = ((View) parent).getParent();
        }
        return false;
    }

    /** Inventory guard: a future page with a red header must not forget the eye. */
    @Test
    public void everyLayoutWithARedHeaderBarIncludesThePrivacyToggle() throws Exception {
        Path layouts = Paths.get("src/main/res/layout").toAbsolutePath();
        int redBars = 0;
        try (Stream<Path> files = Files.list(layouts)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                // #761: the setup assistant shows no network name or address (its steps are choices
                // between stored settings), so it has nothing for the eye to hide.
                if (file.getFileName().toString().equals("activity_onboarding.xml")) continue;
                // #897: the warnings screen lists warning reasons and mute switches only, never a
                // network name or address.
                if (file.getFileName().toString().equals("activity_warnings.xml")) continue;
                // #912: the widget settings page lists the four actions and one switch; it shows
                // no network name or address either.
                if (file.getFileName().toString().equals("activity_widget_config.xml")) continue;
                if (xml.contains("@color/banner_red") && xml.contains("@+id/header_bar")) {
                    redBars++;
                    assertTrue(file.getFileName() + " has a red header bar and needs the eye",
                            xml.contains("@layout/view_privacy_toggle"));
                }
            }
        }
        assertEquals("main view, settings, network list, report history", 4, redBars);
        assertTrue(new File(layouts.toFile(), "view_privacy_toggle.xml").isFile());
    }

    // --- one shared state ----------------------------------------------------------------------

    @Test
    public void switchingInTheListViewIsSeenByTheMainViewAndSettings() {
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        NetworkListActivity list = openList();
        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_APP);
        assertTrue(KeepADBPreferences.isAppPrivacyEnabled(context));

        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                Robolectric.buildActivity(SettingsActivity.class).setup().get()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
    }

    @Test
    public void switchingOnTheMainViewIsSeenByTheListViewAndSettings() {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        KeepADBEyeMenuTestSupport.choose(main, KeepADBPrivacyToggle.ITEM_APP);
        assertTrue(KeepADBPreferences.isAppPrivacyEnabled(context));

        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                openList()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                Robolectric.buildActivity(SettingsActivity.class).setup().get()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
    }

    @Test
    public void settingsEyeFlipsTheSameSettingBackAndForth() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        View eye = settings.findViewById(R.id.btn_toggle_privacy_mode);
        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_APP);
        assertTrue(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                eye.getContentDescription());
        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_APP);
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals(context.getString(R.string.privacy_eye_shown_state),
                eye.getContentDescription());
    }

    // --- immediate redraw ----------------------------------------------------------------------

    @Test
    public void theListViewMasksAndUnmasksAtOnceWithoutReopening() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        NetworkListActivity list = openList();
        assertTrue(shown(list).contains("Cafe-WLAN"));

        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_APP);
        ShadowLooper.idleMainLooper();
        String masked = shown(list);
        assertFalse("Name is hidden right after the tap: " + masked, masked.contains("Cafe-WLAN"));
        assertFalse(masked.contains("HomeMesh"));
        assertFalse(masked.contains("EE:02"));

        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_APP);
        ShadowLooper.idleMainLooper();
        assertTrue("Name is back right after the second tap", shown(list).contains("Cafe-WLAN"));
    }

    @Test
    public void settingsRedrawsItsNetworkCardAtOnce() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "HomeMesh");
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        assertTrue("Precondition: Settings shows the name: " + shown(settings),
                shown(settings).contains("HomeMesh"));

        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_APP);
        ShadowLooper.idleMainLooper();
        assertFalse("Settings hides the name right after the tap: " + shown(settings),
                shown(settings).contains("HomeMesh"));
    }

    // --- tap and long press (#941) ------------------------------------------------------------

    @Test
    public void aTapTogglesOnlyThisScreenAndOpensNoMenu() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertFalse(KeepADBPrivacy.isScreenMasked(settings));
        settings.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        assertTrue("the tap hides this screen", KeepADBPrivacy.isScreenMasked(settings));
        assertNull("the tap opens no menu", KeepADBPrivacyToggle.menuOf(settings));
        assertFalse("no stored switch changes", KeepADBPreferences.isAppPrivacyEnabled(context));
        settings.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        assertFalse("a second tap shows it again", KeepADBPrivacy.isScreenMasked(settings));
    }

    @Test
    public void theLongPressOpensTheFourStoredSwitchesAndTheSettingsLink() {
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);
        KeepADBPreferences.setLockScreenPrivacyEnabled(context, true);
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        KeepADBSettingsMenu menu = KeepADBEyeMenuTestSupport.open(settings);
        List<KeepADBSettingsMenu.MenuItem> items = menu.getItems();
        int[] order = {KeepADBPrivacyToggle.ITEM_APP, KeepADBPrivacyToggle.ITEM_NOTIFICATIONS,
                KeepADBPrivacyToggle.ITEM_LOCKSCREEN, KeepADBPrivacyToggle.ITEM_WIDGETS,
                KeepADBPrivacyToggle.ITEM_DIVIDER, KeepADBPrivacyToggle.ITEM_PRIVACY_SETTINGS};
        assertEquals(order.length, items.size());
        for (int i = 0; i < order.length; i++) assertEquals(order[i], items.get(i).id);
        for (int i = 0; i < 4; i++) assertTrue(items.get(i).isCheckable);
        assertTrue(items.get(4).isDivider);
        assertFalse(items.get(5).isCheckable);
        assertEquals(R.string.privacy_menu_settings, items.get(5).titleResId);

        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();
        boolean[] checked = {false, true, true, false};
        for (int i = 0; i < 4; i++) {
            assertEquals("entry " + i, checked[i], ((android.widget.CheckBox) adapter
                    .getView(i, null, null).findViewById(R.id.menu_item_checkbox)).isChecked());
        }
        assertFalse("Opening the menu changes nothing", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertFalse("nor the screen", KeepADBPrivacy.isScreenMasked(settings));
    }

    @Test
    public void aCheckInTheLongPressMenuKeepsItOpen() {
        NetworkListActivity list = openList();
        KeepADBSettingsMenu menu = KeepADBEyeMenuTestSupport.open(list);
        menu.onMenuItemClicked(KeepADBEyeMenuTestSupport.find(menu,
                KeepADBPrivacyToggle.ITEM_LOCKSCREEN));
        assertTrue(KeepADBPreferences.isLockScreenPrivacyEnabled(context));
        assertTrue("the menu stays open", menu.isShowing());
        menu.onMenuItemClicked(KeepADBEyeMenuTestSupport.find(menu,
                KeepADBPrivacyToggle.ITEM_LOCKSCREEN));
        assertFalse(KeepADBPreferences.isLockScreenPrivacyEnabled(context));
        assertTrue(menu.isShowing());
    }

    @Test
    public void theSettingsLinkOpensSettingsAtThePrivacyCard() {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        KeepADBSettingsMenu menu = KeepADBEyeMenuTestSupport.open(main);
        menu.onMenuItemClicked(KeepADBEyeMenuTestSupport.find(menu,
                KeepADBPrivacyToggle.ITEM_PRIVACY_SETTINGS));
        assertFalse("the link closes the menu", menu.isShowing());
        android.content.Intent next = shadowOf(main).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(SettingsActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(next.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_PRIVACY, false));
    }

    @Test
    public void onSettingsTheLinkOpensThePrivacyCardInPlace() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals(View.GONE, settings.findViewById(R.id.settings_privacy_body).getVisibility());
        KeepADBSettingsMenu menu = KeepADBEyeMenuTestSupport.open(settings);
        menu.onMenuItemClicked(KeepADBEyeMenuTestSupport.find(menu,
                KeepADBPrivacyToggle.ITEM_PRIVACY_SETTINGS));
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, settings.findViewById(R.id.settings_privacy_body).getVisibility());
        assertNull("no second Settings screen", shadowOf(settings).getNextStartedActivity());
    }

    @Test
    public void talkBackNamesTheTapAndTheLongPress() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        View eye = settings.findViewById(R.id.btn_toggle_privacy_mode);
        android.view.accessibility.AccessibilityNodeInfo info = eye.createAccessibilityNodeInfo();
        String click = null;
        String longClick = null;
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action
                : info.getActionList()) {
            if (action.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                click = String.valueOf(action.getLabel());
            } else if (action.getId()
                    == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                longClick = String.valueOf(action.getLabel());
            }
        }
        assertEquals(context.getString(R.string.privacy_menu_hide_screen), click);
        assertEquals(context.getString(R.string.privacy_eye_long_press_action), longClick);
    }

    @Test
    public void showOnThisScreenUnmasksTheListUntilItIsLeftAndNeverChangesTheSwitch() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        org.robolectric.android.controller.ActivityController<NetworkListActivity> controller =
                Robolectric.buildActivity(NetworkListActivity.class,
                        NetworkListActivity.intent(context)).setup();
        NetworkListActivity list = controller.get();
        ShadowLooper.idleMainLooper();
        assertFalse(shown(list).contains("Cafe-WLAN"));

        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_SCREEN);
        assertTrue("Show on this screen unmasks: " + shown(list), shown(list).contains("Cafe-WLAN"));
        assertTrue("the stored switch stays on", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals("the eye shows what the screen does",
                context.getString(R.string.privacy_eye_shown_state),
                list.findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());

        controller.pause().stop();
        controller.start().resume().visible();
        ShadowLooper.idleMainLooper();
        assertFalse("leaving the screen ends it: " + shown(list), shown(list).contains("Cafe-WLAN"));
        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                list.findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
        controller.pause().stop().destroy();
    }

    @Test
    public void hideOnThisScreenMasksWhileTheSwitchIsOffUntilTheScreenIsLeft() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        org.robolectric.android.controller.ActivityController<NetworkListActivity> controller =
                Robolectric.buildActivity(NetworkListActivity.class,
                        NetworkListActivity.intent(context)).setup();
        NetworkListActivity list = controller.get();
        ShadowLooper.idleMainLooper();
        assertTrue(shown(list).contains("Cafe-WLAN"));

        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_SCREEN);
        String hidden = shown(list);
        assertFalse("Hide on this screen masks: " + hidden, hidden.contains("Cafe-WLAN"));
        assertFalse(hidden.contains("EE:02"));
        assertFalse("the stored switch stays off", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                list.findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());

        controller.pause().stop();
        controller.start().resume().visible();
        ShadowLooper.idleMainLooper();
        assertTrue("leaving the screen ends it", shown(list).contains("Cafe-WLAN"));
        controller.pause().stop().destroy();
    }

    @Test
    public void theEyeMenuSetsTheNotificationAndWidgetSwitchesAndTheSettingsBlockFollows() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_NOTIFICATIONS);
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertTrue(((android.widget.Switch) settings.findViewById(R.id.privacy_switch_notifications))
                .isChecked());
        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_WIDGETS);
        assertTrue(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertTrue(((android.widget.Switch) settings.findViewById(R.id.privacy_switch_widgets))
                .isChecked());
        assertFalse("the app switch is untouched", KeepADBPreferences.isAppPrivacyEnabled(context));
    }

    @Test
    public void theHomeEyeTapShowsTheHomeScreenWithoutTouchingTheSwitch() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue(KeepADBPrivacy.isScreenMasked(main));
        main.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        assertFalse("the home screen is shown now", KeepADBPrivacy.isScreenMasked(main));
        assertTrue("the stored switch stays on", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals(context.getString(R.string.privacy_eye_shown_state),
                main.findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
    }

    @Test
    public void hideOnThisScreenAlsoHidesAPartThatWasRevealedBefore() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        NetworkListActivity list = openList();
        KeepADBPrivacy.reveal(list, KeepADBPrivacy.SCOPE_NETWORK_LIST);
        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_SCREEN); // show
        assertTrue(shown(list).contains("Cafe-WLAN"));

        KeepADBEyeMenuTestSupport.choose(list, KeepADBPrivacyToggle.ITEM_SCREEN); // hide
        assertFalse("Hide on this screen hides the revealed list too: " + shown(list),
                shown(list).contains("Cafe-WLAN"));
    }

    @Test
    public void changingTheNotificationOrWidgetSwitchEndsShowOnThisScreen() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_SCREEN);
        assertFalse(KeepADBPrivacy.isScreenMasked(settings));
        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_NOTIFICATIONS);
        assertTrue("a switch change ends the override", KeepADBPrivacy.isScreenMasked(settings));

        KeepADBEyeMenuTestSupport.choose(settings, KeepADBPrivacyToggle.ITEM_SCREEN);
        assertFalse(KeepADBPrivacy.isScreenMasked(settings));
        settings.findViewById(R.id.privacy_switch_widgets).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("also from the Settings switch", KeepADBPrivacy.isScreenMasked(settings));
        assertEquals(context.getString(R.string.privacy_eye_masked_state),
                settings.findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
    }

    @Test
    public void anExportStaysRedactedUnderShowOnThisScreenAndIsRedactedUnderHide() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertFalse(KeepADBPrivacy.isExportRedacted(settings));
        KeepADBPrivacy.setScreenMasked(settings, true);
        assertTrue("hidden screen redacts", KeepADBPrivacy.isExportRedacted(settings));
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPrivacy.setScreenMasked(settings, false);
        assertTrue("Show on this screen does not unredact", KeepADBPrivacy.isExportRedacted(settings));
    }

    // --- helpers -------------------------------------------------------------------------------

    private NetworkListActivity openList() {
        NetworkListActivity activity = Robolectric.buildActivity(NetworkListActivity.class,
                NetworkListActivity.intent(context)).setup().get();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private static String shown(Activity activity) {
        StringBuilder all = new StringBuilder();
        collect(activity.getWindow().getDecorView(), all);
        return all.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView) {
            out.append(((TextView) view).getText()).append('\n');
        }
        if (view.getContentDescription() != null) {
            out.append(view.getContentDescription()).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), out);
            }
        }
    }
}
