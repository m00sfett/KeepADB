package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

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

import java.util.List;

/**
 * #823, #940: Unit tests for the main view's settings dropdown menu (order, icons, target
 * intents, dismiss on open).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBSettingsMenuTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private static final int[] ORDER = {
            KeepADBSettingsMenu.ID_LANGUAGE, KeepADBSettingsMenu.ID_DIVIDER_1,
            KeepADBSettingsMenu.ID_ALL_SETTINGS, KeepADBSettingsMenu.ID_DIVIDER_2,
            KeepADBSettingsMenu.ID_SETUP_ASSISTANT, KeepADBSettingsMenu.ID_NETWORK,
            KeepADBSettingsMenu.ID_WEBHOOK, KeepADBSettingsMenu.ID_USB_ADB,
            KeepADBSettingsMenu.ID_PRIVACY, KeepADBSettingsMenu.ID_MISC,
            KeepADBSettingsMenu.ID_DIAGNOSTICS};
    private static final int[] TITLES = {
            R.string.menu_item_language, 0, R.string.menu_item_all_settings, 0,
            R.string.settings_onboarding_title, R.string.settings_section_network,
            R.string.settings_section_webhook, R.string.settings_section_usb_adb,
            R.string.privacy_switches_title, R.string.settings_section_misc,
            R.string.settings_diagnostics_title};
    private static final int[] ICONS = {
            R.drawable.ic_language, 0, R.drawable.ic_settings_gear, 0,
            R.drawable.ic_settings_assistant, R.drawable.ic_settings_network,
            R.drawable.ic_settings_sync, R.drawable.ic_usb, R.drawable.ic_settings_privacy,
            R.drawable.ic_more_horiz, R.drawable.ic_settings_diagnostics};

    @Test
    public void menuItemsMatchExactPrescribedOrderTitlesAndIcons() {
        List<KeepADBSettingsMenu.MenuItem> items = KeepADBSettingsMenu.buildMenuItems();
        assertEquals(ORDER.length, items.size());
        for (int i = 0; i < ORDER.length; i++) {
            KeepADBSettingsMenu.MenuItem item = items.get(i);
            assertEquals("id at " + i, ORDER[i], item.id);
            assertEquals("divider at " + i, ORDER[i] < 0, item.isDivider);
            assertFalse("no check entry in the home menu (#940)", item.isCheckable);
            assertEquals("title at " + i, TITLES[i], item.titleResId);
            assertEquals("icon at " + i, ICONS[i], item.iconResId);
        }
    }

    @Test
    public void settingsButtonClickOpensMenu() {
        View settingsBtn = activity.findViewById(R.id.btn_open_settings);
        assertNotNull(settingsBtn);
        settingsBtn.performClick();

        KeepADBSettingsMenu menu = activity.getSettingsMenu();
        assertNotNull(menu);
        assertTrue(menu.isShowing());
    }

    @Test
    public void languageItemLaunchesLanguageDialogAndDismissesMenu() {
        KeepADBSettingsMenu menu = openMenu();
        menu.onMenuItemClicked(item(menu, KeepADBSettingsMenu.ID_LANGUAGE));

        assertFalse(menu.isShowing());
        AlertDialog latestDialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("Language selection dialog must be shown", latestDialog);
        assertTrue(latestDialog.isShowing());
    }

    @Test
    public void allSettingsItemLaunchesSettingsActivityAndDismissesMenu() {
        KeepADBSettingsMenu menu = openMenu();
        menu.onMenuItemClicked(item(menu, KeepADBSettingsMenu.ID_ALL_SETTINGS));

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertFalse(nextIntent.hasExtra(SettingsActivity.EXTRA_FOCUS_NETWORK_CARD));
    }

    @Test
    public void setupAssistantItemLaunchesOnboardingActivityDirectlyAndDismissesMenu() {
        KeepADBSettingsMenu menu = openMenu();
        menu.onMenuItemClicked(item(menu, KeepADBSettingsMenu.ID_SETUP_ASSISTANT));

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(OnboardingActivity.class.getName(), nextIntent.getComponent().getClassName());
    }

    @Test
    public void networkItemLaunchesSettingsWithNetworkCardExtra() {
        assertOpensSettingsWith(KeepADBSettingsMenu.ID_NETWORK,
                SettingsActivity.EXTRA_FOCUS_NETWORK_CARD);
    }

    @Test
    public void webhookItemLaunchesSettingsWithWebhookExtra() {
        assertOpensSettingsWith(KeepADBSettingsMenu.ID_WEBHOOK, SettingsActivity.EXTRA_FOCUS_WEBHOOK);
    }

    @Test
    public void usbAdbItemLaunchesSettingsWithUsbExtra() {
        assertOpensSettingsWith(KeepADBSettingsMenu.ID_USB_ADB, SettingsActivity.EXTRA_FOCUS_USB);
    }

    @Test
    public void privacyItemLaunchesSettingsWithPrivacyExtra() {
        assertOpensSettingsWith(KeepADBSettingsMenu.ID_PRIVACY, SettingsActivity.EXTRA_FOCUS_PRIVACY);
    }

    @Test
    public void miscItemLaunchesSettingsWithMiscExtra() {
        assertOpensSettingsWith(KeepADBSettingsMenu.ID_MISC, SettingsActivity.EXTRA_FOCUS_MISC);
    }

    @Test
    public void diagnosticsItemLaunchesSettingsWithDiagnosticsExtra() {
        assertOpensSettingsWith(KeepADBSettingsMenu.ID_DIAGNOSTICS,
                SettingsActivity.EXTRA_FOCUS_DIAGNOSTICS);
    }

    @Test
    public void dividersAreDisabledAndUnclickable() {
        KeepADBSettingsMenu menu = openMenu();
        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();

        assertFalse(adapter.areAllItemsEnabled());
        for (int i = 0; i < ORDER.length; i++) {
            assertEquals("enabled at " + i, ORDER[i] >= 0, adapter.isEnabled(i));
        }

        menu.onMenuItemClicked(item(menu, KeepADBSettingsMenu.ID_DIVIDER_1));
        assertTrue(menu.isShowing());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void menuAdapterRendersIconsAndNoCheckboxes() {
        KeepADBSettingsMenu menu = openMenu();
        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] < 0) continue;
            View row = adapter.getView(i, null, null);
            assertEquals("icon at " + i, View.VISIBLE,
                    row.findViewById(R.id.menu_item_icon).getVisibility());
            assertEquals("no checkbox at " + i, View.GONE,
                    row.findViewById(R.id.menu_item_checkbox).getVisibility());
            assertEquals(activity.getString(TITLES[i]),
                    ((TextView) row.findViewById(R.id.menu_item_title)).getText().toString());
        }
    }

    private KeepADBSettingsMenu openMenu() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();
        assertTrue(menu.isShowing());
        return menu;
    }

    private static KeepADBSettingsMenu.MenuItem item(KeepADBSettingsMenu menu, int id) {
        for (KeepADBSettingsMenu.MenuItem item : menu.getItems()) {
            if (item.id == id) return item;
        }
        throw new AssertionError("no menu item " + id);
    }

    private void assertOpensSettingsWith(int id, String extra) {
        KeepADBSettingsMenu menu = openMenu();
        menu.onMenuItemClicked(item(menu, id));

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertTrue(nextIntent.getBooleanExtra(extra, false));
    }
}
