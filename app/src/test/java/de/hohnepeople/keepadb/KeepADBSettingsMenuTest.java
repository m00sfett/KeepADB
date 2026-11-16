package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * #823, #940, #964: Unit tests for the main view's settings dropdown menu (the four entries, their
 * order, icons, target intents, dismiss on open).
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

    /** #964: exactly these four entries, in this order, without dividers. */
    private static final int[] ORDER = {
            KeepADBSettingsMenu.ID_ALL_SETTINGS, KeepADBSettingsMenu.ID_NETWORKS,
            KeepADBSettingsMenu.ID_SETUP_ASSISTANT, KeepADBSettingsMenu.ID_LANGUAGE};
    private static final int[] TITLES = {
            R.string.menu_item_all_settings, R.string.networks_title,
            R.string.settings_onboarding_title, R.string.menu_item_language};
    private static final int[] ICONS = {
            R.drawable.ic_settings_gear, R.drawable.ic_wifi,
            R.drawable.ic_settings_assistant, R.drawable.ic_language};

    @Test
    public void menuItemsMatchExactPrescribedOrderTitlesAndIcons() {
        List<KeepADBSettingsMenu.MenuItem> items = KeepADBSettingsMenu.buildMenuItems();
        assertEquals("the gear menu has exactly four entries (#964)", 4, items.size());
        assertEquals(ORDER.length, items.size());
        for (int i = 0; i < ORDER.length; i++) {
            KeepADBSettingsMenu.MenuItem item = items.get(i);
            assertEquals("id at " + i, ORDER[i], item.id);
            assertFalse("no divider at " + i, item.isDivider);
            assertFalse("no check entry in the home menu (#940)", item.isCheckable);
            assertEquals("title at " + i, TITLES[i], item.titleResId);
            assertEquals("icon at " + i, ICONS[i], item.iconResId);
        }
    }

    @Test
    public void menuLabelsAreSettingsAndWifiNetworks() {
        assertEquals("Settings", activity.getString(R.string.menu_item_all_settings));
        assertEquals("Wi-Fi networks", activity.getString(R.string.networks_title));
        assertEquals("the menu entry and the list screen share one title",
                activity.getString(R.string.networks_title),
                activity.getString(KeepADBSettingsMenu.buildMenuItems().get(1).titleResId));
    }

    @Test
    public void everyMenuIdIsOneOfTheFourEntries() throws Exception {
        // A removed entry must not leave its id (and so a dead dispatch case) behind.
        java.util.Set<Integer> declared = new java.util.HashSet<>();
        for (java.lang.reflect.Field field : KeepADBSettingsMenu.class.getDeclaredFields()) {
            if (field.getName().startsWith("ID_")) declared.add(field.getInt(null));
        }
        java.util.Set<Integer> expected = new java.util.HashSet<>();
        for (int id : ORDER) expected.add(id);
        assertEquals(expected, declared);
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
    public void networksItemOpensTheNetworksListDirectly() {
        KeepADBSettingsMenu menu = openMenu();
        menu.onMenuItemClicked(item(menu, KeepADBSettingsMenu.ID_NETWORKS));

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(NetworkListActivity.class.getName(), nextIntent.getComponent().getClassName());
    }

    @Test
    public void everyRowIsEnabled() {
        KeepADBSettingsMenu menu = openMenu();
        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();

        assertEquals(ORDER.length, adapter.getCount());
        for (int i = 0; i < ORDER.length; i++) {
            assertTrue("enabled at " + i, adapter.isEnabled(i));
        }
    }

    @Test
    public void menuAdapterRendersIconsAndNoCheckboxes() {
        KeepADBSettingsMenu menu = openMenu();
        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();
        for (int i = 0; i < ORDER.length; i++) {
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
}
