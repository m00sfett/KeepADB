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
import android.widget.CheckBox;
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
 * #823: Unit tests for the main view's settings dropdown menu (order, target intents,
 * inline privacy mode toggle without dismiss, dismiss on destroy).
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

    @Test
    public void menuItemsMatchExactPrescribedOrderAndCount() {
        List<KeepADBSettingsMenu.MenuItem> items = KeepADBSettingsMenu.buildMenuItems();
        assertEquals(10, items.size());

        assertEquals(KeepADBSettingsMenu.ID_LANGUAGE, items.get(0).id);
        assertEquals(R.string.menu_item_language, items.get(0).titleResId);
        assertFalse(items.get(0).isDivider);
        assertFalse(items.get(0).isCheckable);

        assertEquals(KeepADBSettingsMenu.ID_PRIVACY_MODE, items.get(1).id);
        assertEquals(R.string.menu_item_privacy_mode, items.get(1).titleResId);
        assertFalse(items.get(1).isDivider);
        assertTrue(items.get(1).isCheckable);

        assertEquals(KeepADBSettingsMenu.ID_DIVIDER_1, items.get(2).id);
        assertTrue(items.get(2).isDivider);

        assertEquals(KeepADBSettingsMenu.ID_ALL_SETTINGS, items.get(3).id);
        assertEquals(R.string.menu_item_all_settings, items.get(3).titleResId);
        assertFalse(items.get(3).isDivider);

        assertEquals(KeepADBSettingsMenu.ID_DIVIDER_2, items.get(4).id);
        assertTrue(items.get(4).isDivider);

        assertEquals(KeepADBSettingsMenu.ID_SETUP_ASSISTANT, items.get(5).id);
        assertEquals(R.string.settings_onboarding_title, items.get(5).titleResId);

        assertEquals(KeepADBSettingsMenu.ID_NETWORK, items.get(6).id);
        assertEquals(R.string.settings_section_network, items.get(6).titleResId);

        assertEquals(KeepADBSettingsMenu.ID_WEBHOOK, items.get(7).id);
        assertEquals(R.string.settings_section_webhook, items.get(7).titleResId);

        assertEquals(KeepADBSettingsMenu.ID_USB_ADB, items.get(8).id);
        assertEquals(R.string.settings_section_usb_adb, items.get(8).titleResId);

        assertEquals(KeepADBSettingsMenu.ID_MISC, items.get(9).id);
        assertEquals(R.string.settings_section_misc, items.get(9).titleResId);
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
    public void privacyModeItemTogglesPreferenceWithoutDismissingMenu() {
        View settingsBtn = activity.findViewById(R.id.btn_open_settings);
        settingsBtn.performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();
        assertTrue(menu.isShowing());

        assertFalse(KeepADBPreferences.isPrivacyModeEnabled(context));

        KeepADBSettingsMenu.MenuItem privacyItem = menu.getItems().get(1);
        menu.onMenuItemClicked(privacyItem);

        // Preference flipped to true
        assertTrue(KeepADBPreferences.isPrivacyModeEnabled(context));
        // Menu remains open
        assertTrue(menu.isShowing());

        // Toggle back
        menu.onMenuItemClicked(privacyItem);
        assertFalse(KeepADBPreferences.isPrivacyModeEnabled(context));
        assertTrue(menu.isShowing());
    }

    @Test
    public void languageItemLaunchesLanguageDialogAndDismissesMenu() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();
        assertTrue(menu.isShowing());

        KeepADBSettingsMenu.MenuItem langItem = menu.getItems().get(0);
        menu.onMenuItemClicked(langItem);

        assertFalse(menu.isShowing());
        AlertDialog latestDialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("Language selection dialog must be shown", latestDialog);
        assertTrue(latestDialog.isShowing());
    }

    @Test
    public void allSettingsItemLaunchesSettingsActivityAndDismissesMenu() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();

        KeepADBSettingsMenu.MenuItem item = menu.getItems().get(3);
        menu.onMenuItemClicked(item);

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertFalse(nextIntent.hasExtra(SettingsActivity.EXTRA_FOCUS_NETWORK_CARD));
    }

    @Test
    public void setupAssistantItemLaunchesOnboardingActivityDirectlyAndDismissesMenu() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();

        KeepADBSettingsMenu.MenuItem item = menu.getItems().get(5);
        menu.onMenuItemClicked(item);

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(OnboardingActivity.class.getName(), nextIntent.getComponent().getClassName());
    }

    @Test
    public void networkItemLaunchesSettingsWithNetworkCardExtra() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();

        KeepADBSettingsMenu.MenuItem item = menu.getItems().get(6);
        menu.onMenuItemClicked(item);

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertTrue(nextIntent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_NETWORK_CARD, false));
    }

    @Test
    public void webhookItemLaunchesSettingsWithWebhookExtra() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();

        KeepADBSettingsMenu.MenuItem item = menu.getItems().get(7);
        menu.onMenuItemClicked(item);

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertTrue(nextIntent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, false));
    }

    @Test
    public void usbAdbItemLaunchesSettingsWithUsbExtra() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();

        KeepADBSettingsMenu.MenuItem item = menu.getItems().get(8);
        menu.onMenuItemClicked(item);

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertTrue(nextIntent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_USB, false));
    }

    @Test
    public void miscItemLaunchesSettingsWithMiscExtra() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();

        KeepADBSettingsMenu.MenuItem item = menu.getItems().get(9);
        menu.onMenuItemClicked(item);

        assertFalse(menu.isShowing());
        Intent nextIntent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertEquals(SettingsActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertTrue(nextIntent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_MISC, false));
    }

    @Test
    public void dividersAreDisabledAndUnclickable() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();
        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();

        assertFalse(adapter.areAllItemsEnabled());
        assertTrue(adapter.isEnabled(0)); // Language
        assertTrue(adapter.isEnabled(1)); // Privacy
        assertFalse(adapter.isEnabled(2)); // Divider 1
        assertTrue(adapter.isEnabled(3)); // All settings
        assertFalse(adapter.isEnabled(4)); // Divider 2
        assertTrue(adapter.isEnabled(5)); // Assistant
        assertTrue(adapter.isEnabled(6)); // Network
        assertTrue(adapter.isEnabled(7)); // Webhook
        assertTrue(adapter.isEnabled(8)); // USB
        assertTrue(adapter.isEnabled(9)); // Misc

        // Clicking a divider does nothing
        menu.onMenuItemClicked(menu.getItems().get(2));
        assertTrue(menu.isShowing());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void menuAdapterRendersCheckboxForPrivacyModeOnly() {
        activity.findViewById(R.id.btn_open_settings).performClick();
        KeepADBSettingsMenu menu = activity.getSettingsMenu();
        KeepADBSettingsMenu.MenuAdapter adapter = menu.getAdapter();

        // Language item
        View langView = adapter.getView(0, null, null);
        assertEquals(View.GONE, langView.findViewById(R.id.menu_item_checkbox).getVisibility());
        assertEquals(activity.getString(R.string.menu_item_language),
                ((TextView) langView.findViewById(R.id.menu_item_title)).getText().toString());

        // Privacy item
        View privView = adapter.getView(1, null, null);
        assertEquals(View.VISIBLE, privView.findViewById(R.id.menu_item_checkbox).getVisibility());
        assertFalse(((CheckBox) privView.findViewById(R.id.menu_item_checkbox)).isChecked());

        // Toggle preference and re-render
        KeepADBPreferences.setPrivacyModeEnabled(activity, true);
        View privViewChecked = adapter.getView(1, null, null);
        assertTrue(((CheckBox) privViewChecked.findViewById(R.id.menu_item_checkbox)).isChecked());
    }
}
