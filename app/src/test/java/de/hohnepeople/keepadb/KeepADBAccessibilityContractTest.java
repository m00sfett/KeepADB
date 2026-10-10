package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/** Runtime accessibility contracts backed by Robolectric-inflated Android views. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAccessibilityContractTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();
    private Context context;
    private final List<ActivityController<?>> activities = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBNetwork.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
        for (int i = activities.size() - 1; i >= 0; i--) {
            activities.get(i).pause().stop().destroy();
        }
        activities.clear();
        KeepADBNetwork.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void interactiveViewsKeep48DpTouchTargetsAfterMeasurement() {
        // #764: the missing system permission is the home screen's warning card, shown by
        // refresh() itself rather than by making a hidden fixture visible ourselves.
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        MainActivity activity = startActivity(MainActivity.class);
        View main = activity.getWindow().getDecorView();
        View settings = runtimeView(R.layout.activity_settings);
        View widget = runtimeView(R.layout.widget_keepadb);
        View systemWarningAction = main.findViewById(R.id.warning_system)
                .findViewById(R.id.home_warning_action);
        assertTrue(systemWarningAction.isShown());
        // #471: every settings card starts collapsed; expand them all so the controls inside are
        // actually part of the measured layout (a GONE body's children never get a measured
        // size).
        expandAllSettingsCards(settings);
        measureAndLayout(main, 360, 2400);
        measureAndLayout(settings, 360, 2400);
        measureAndLayout(widget, 360, 160);

        int[] mainControls = {
                R.id.btn_open_settings, R.id.btn_dismiss_advice_banner,
                R.id.toggle, R.id.keep_alive_toggle
        };
        for (int id : mainControls) assertMinSize(main.findViewById(id));
        assertMinSize(systemWarningAction);

        int[] settingsControls = {
                R.id.btn_back, R.id.btn_toggle_privacy_mode,
                R.id.settings_webhook_header, R.id.settings_webhook_toggle,
                R.id.settings_webhook_url, R.id.settings_webhook_clear, R.id.settings_webhook_save,
                R.id.settings_usb_adb_header,
                R.id.settings_usb_notification_toggle,
                R.id.settings_usb_profile_notification_toggle,
                R.id.settings_usb_profile_action, R.id.settings_usb_handover_selector,
                R.id.network_status_action,
                R.id.settings_trust_by_name_toggle,
                R.id.network_networks_row,
                R.id.settings_background_location_button,
                R.id.settings_misc_header,
                R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle,
                R.id.settings_advice_banner_toggle,
                R.id.settings_diagnostics_header,
                R.id.settings_diagnostics_export, R.id.settings_issue_report,
                R.id.settings_reset_app,
                R.id.settings_website_link
        };
        for (int id : settingsControls) assertMinSize(settings.findViewById(id));
        assertMinSize(widget.findViewById(R.id.widget_label));
    }

    /** #471: clicks every collapsible settings card's header so its body (and everything inside)
     * becomes part of the measured/shown layout -- cards start collapsed, and a GONE view's
     * children never receive a measured size or count as isShown(). #478: the language and
     * version headers are excluded -- they are permanently visible and no longer clickable. */
    private void expandAllSettingsCards(View settings) {
        int[] headers = {
                R.id.settings_webhook_header,
                // #529: expanding the sole USB-ADB header makes both direct sections visible.
                R.id.settings_usb_adb_header,
                // #519/#618: expanding the sole Network header makes both direct sections visible.
                R.id.settings_network_beta_header,
                // #521: the outer "Sonstiges" card must be expanded to make its four directly
                // nested sections' controls measurable/reachable; like #529's USB sections,
                // they are not independently collapsible sub-cards with headers of their own.
                R.id.settings_misc_header,
                R.id.settings_diagnostics_header
        };
        for (int id : headers) settings.findViewById(id).performClick();
    }

    @Test
    public void activitiesInstallInteractiveAndAccessibleRuntimeContracts() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        ActivityController<MainActivity> mainController =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = mainController.get();
        assertEquals(View.VISIBLE, main.findViewById(R.id.warning_system).getVisibility());
        assertEquals(main.getString(R.string.status_permission_missing),
                ((TextView) main.findViewById(R.id.status)).getText().toString());
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        gateway.write(context, true);
        gateway.writes.clear();
        // #764: coming back from the computer's grant redraws the home screen (no check button).
        mainController.pause().resume();
        assertEquals(View.GONE, main.findViewById(R.id.warning_system).getVisibility());
        assertTrue(main.findViewById(R.id.toggle).isEnabled());
        assertTrue(((android.widget.Switch) main.findViewById(R.id.toggle)).isChecked());
        assertEquals(main.getString(R.string.status_enabled_disconnected),
                ((TextView) main.findViewById(R.id.status)).getText().toString());
        assertTrue(gateway.writes.isEmpty());
        assertNotNull(main.findViewById(R.id.toggle));
        assertTrue(main.findViewById(R.id.btn_open_settings).hasOnClickListeners());
        assertTrue(main.findViewById(R.id.btn_dismiss_advice_banner).hasOnClickListeners());
        assertTrue(main.findViewById(R.id.toggle).hasOnClickListeners());
        assertTrue(main.findViewById(R.id.keep_alive_toggle).hasOnClickListeners());
        assertNull(main.findViewById(R.id.settings_hide_notification_toggle));

        ImageView mainIcon = (ImageView) ((ViewGroup) main.findViewById(R.id.header_bar)).getChildAt(0);
        assertNotNull("The main header must render the app icon", mainIcon.getDrawable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, mainIcon.getImportantForAccessibility());
        TextView mainTitle = (TextView) ((ViewGroup) main.findViewById(R.id.header_bar)).getChildAt(1);
        assertEquals(main.getString(R.string.title_keepadb), mainTitle.getText().toString());
        assertEquals(main.getString(R.string.action_settings),
                main.findViewById(R.id.btn_open_settings).getContentDescription());
        assertTrue(main.findViewById(R.id.btn_open_settings).performClick());
        KeepADBSettingsMenu menu = main.getSettingsMenu();
        assertNotNull(menu);
        assertTrue(menu.isShowing());
        menu.onMenuItemClicked(new KeepADBSettingsMenu.MenuItem(
                KeepADBSettingsMenu.ID_ALL_SETTINGS, R.string.menu_item_all_settings));
        Intent openedSettings = shadowOf(main).getNextStartedActivity();
        assertNotNull(openedSettings);
        assertEquals(SettingsActivity.class.getName(), openedSettings.getComponent().getClassName());

        ActivityController<SettingsActivity> settingsController =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity settings = settingsController.get();
        int[] settingsControls = {
                R.id.btn_back, R.id.btn_toggle_privacy_mode, R.id.settings_webhook_toggle,
                R.id.settings_webhook_clear, R.id.settings_webhook_save,
                R.id.settings_usb_notification_toggle, R.id.settings_usb_profile_notification_toggle,
                R.id.settings_usb_profile_action, R.id.settings_usb_handover_selector,
                R.id.network_status_action, R.id.settings_trust_by_name_toggle,
                R.id.network_networks_row, R.id.settings_background_location_button,
                R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle, R.id.settings_advice_banner_toggle,
                R.id.settings_diagnostics_export, R.id.settings_issue_report,
                R.id.settings_reset_app,
                R.id.settings_website_link
        };
        for (int id : settingsControls) {
            View control = settings.findViewById(id);
            assertNotNull("Missing settings control " + id, control);
            assertTrue("Settings control has no runtime listener: " + id,
                    control.hasOnClickListeners());
        }
        assertEquals(settings.getString(R.string.back),
                settings.findViewById(R.id.btn_back).getContentDescription());
        assertEquals(settings.getString(R.string.settings_website_link_accessibility),
                settings.findViewById(R.id.settings_website_link).getContentDescription());
        String languageTag = KeepADBLocaleHelper.getSelectedLanguageTag(settings);
        String languageName = KeepADBLocaleHelper.getLanguageDisplayName(settings, languageTag);
        assertEquals(settings.getString(R.string.settings_language_accessibility, languageName),
                ((android.widget.Toolbar) settings.findViewById(R.id.header_bar)).getMenu()
                        .findItem(R.id.settings_language_menu_item).getContentDescription());
        assertEquals(settings.getString(R.string.settings_usb_handover_accessibility,
                        settings.getString(R.string.settings_usb_handover_mode_off)),
                settings.findViewById(R.id.settings_usb_handover_selector).getContentDescription());

        settingsController.pause().stop().destroy();
        mainController.pause().stop().destroy();
    }

    @Test
    public void settingsBackButtonUsesAnAutoMirroredRuntimeDrawable() {
        View settings = runtimeView(R.layout.activity_settings);
        ImageButton back = (ImageButton) settings.findViewById(R.id.btn_back);
        Drawable drawable = back.getDrawable();
        assertNotNull(drawable);
        assertTrue(drawable.isAutoMirrored());
        assertEquals(context.getString(R.string.back), back.getContentDescription());
        assertTrue(drawable.getIntrinsicWidth() > 0);
        assertTrue(drawable.getIntrinsicHeight() > 0);
    }

    @Test
    public void importantAccessibilityTextAndLiveRegionsResolveOnRuntimeViews() {
        View main = runtimeView(R.layout.activity_main);
        View settings = runtimeView(R.layout.activity_settings);

        assertHasText(main.findViewById(R.id.advice_banner),
                context.getString(R.string.advice_banner_title));
        assertHasText(main.findViewById(R.id.advice_banner),
                context.getString(R.string.advice_banner_text));
        // #764: every warning card has a heading and announces changes politely.
        for (int id : new int[] {R.id.warning_system, R.id.warning_less_secure,
                R.id.warning_paused, R.id.warning_limited}) {
            assertTrue(main.findViewById(id).findViewById(R.id.home_warning_title)
                    .isAccessibilityHeading());
            assertPoliteLiveRegion(main.findViewById(id).findViewById(R.id.home_warning_text));
        }
        assertHasText(settings.findViewById(R.id.settings_misc_panel),
                context.getString(R.string.settings_section_notification));
        assertHasText(settings.findViewById(R.id.settings_misc_panel),
                context.getString(R.string.settings_hide_notification_toggle));
        assertHasText(settings.findViewById(R.id.settings_usb_notification_panel),
                context.getString(R.string.settings_section_usb_notification));
        assertHasText(settings.findViewById(R.id.settings_usb_notification_panel),
                context.getString(R.string.settings_usb_profile_notification_toggle));
        assertHasText(settings.findViewById(R.id.settings_usb_notification_panel),
                context.getString(R.string.usb_profile_create_button));
        TextView usbNotificationTitle = settings.findViewById(R.id.settings_usb_notification_title);
        TextView usbHandoverTitle = settings.findViewById(R.id.settings_usb_handover_title);
        assertTrue(usbNotificationTitle.isAccessibilityHeading());
        assertTrue(usbHandoverTitle.isAccessibilityHeading());
        assertFalse(usbNotificationTitle.isClickable());
        assertFalse(usbHandoverTitle.isClickable());
        assertFalse(usbNotificationTitle.isFocusable());
        assertFalse(usbHandoverTitle.isFocusable());
        // #654: every Network section starts with a non-interactive accessibility heading.
        for (int id : new int[] {R.id.network_status_heading,
                R.id.network_background_heading, R.id.network_manage_heading}) {
            TextView heading = settings.findViewById(id);
            assertTrue(heading.isAccessibilityHeading());
            assertFalse(heading.isClickable());
            assertFalse(heading.isFocusable());
        }
        assertPoliteLiveRegion(settings.findViewById(R.id.network_status_label));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_background_location_status));

        assertPoliteLiveRegion(main.findViewById(R.id.status));
        assertPoliteLiveRegion(main.findViewById(R.id.webhook_status));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_error));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_cleartext_warning));
        assertTrue(findTextView(main, context.getString(R.string.advice_banner_title))
                .isAccessibilityHeading());
    }

    @Test
    public void securityAdviceIsRenderedAsDismissibleMainBannerOnly() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = controller.get();
        View banner = main.findViewById(R.id.advice_banner);
        assertEquals(View.VISIBLE, banner.getVisibility());
        assertNotNull(((ViewGroup) banner).getChildAt(0));
        assertNotNull(((ImageView) ((ViewGroup) banner).getChildAt(0)).getDrawable());
        assertHasText(banner, main.getString(R.string.advice_banner_title));
        assertHasText(banner, main.getString(R.string.advice_banner_text));
        assertTrue(main.findViewById(R.id.btn_dismiss_advice_banner).hasOnClickListeners());

        main.findViewById(R.id.btn_dismiss_advice_banner).performClick();
        assertEquals(View.GONE, banner.getVisibility());

        View settings = runtimeView(R.layout.activity_settings);
        assertFalse(containsText(settings, context.getString(R.string.advice_banner_title)));
        assertFalse(containsText(settings, context.getString(R.string.advice_banner_text)));
        controller.pause().stop().destroy();
    }

    @Test
    public void settingsNetworkCardIsTheFirstCardAfterTheConditionalPermissionWarning() {
        // #735: the network card is the first card on the settings page, before webhook and all
        // others; only the hidden-by-default permission warning may precede it. #470 had placed
        // it after the webhook and USB-ADB cards.
        View settings = runtimeView(R.layout.activity_settings);
        ViewGroup content = (ViewGroup) ((android.widget.ScrollView)
                settings.findViewById(R.id.settings_scroll_view)).getChildAt(0);
        View permission = content.findViewById(R.id.settings_permission_panel);
        View network = content.findViewById(R.id.settings_network_beta_panel);
        assertNotNull(permission);
        assertNotNull(network);
        assertTrue("Only the permission warning may precede the network card",
                content.indexOfChild(network) <= content.indexOfChild(permission) + 1);
        assertTrue(content.indexOfChild(network) < content.indexOfChild(
                content.findViewById(R.id.settings_webhook_panel)));
    }

    @Test
    public void settingsPanelsFollowProductOrderInTheInflatedHierarchy() {
        View settings = runtimeView(R.layout.activity_settings);
        ViewGroup content = (ViewGroup) ((android.widget.ScrollView)
                settings.findViewById(R.id.settings_scroll_view)).getChildAt(0);
        // #735 (replaces #470's order): the "Network" card now comes first, as the most important
        // settings area. The remaining cards keep their relative order: the webhook card,
        // then the "USB-ADB" card, with notification and USB -> Wifi-ADB handover shown as direct
        // sections after the sole outer expand step.
        // Below that sits the "Network" card -- itself collapsible since #519, with its sections
        // shown directly inside its body (#618/#654) -- then the "Sonstiges" card, itself collapsible since #521,
        // with the four notice/display-preference sections shown directly inside its body,
        // matching #529's one-level USB-ADB structure.
        // #518: the language panel no longer exists in this content column at all -- it moved to
        // native toolbar overflow in the header -- so it is no longer part of this table.
        int[] panels = {
                R.id.settings_network_beta_panel,
                R.id.settings_webhook_panel,
                R.id.settings_usb_adb_panel,
                R.id.settings_misc_panel,
                R.id.settings_diagnostics_panel,
                R.id.settings_version_panel
        };
        int previous = -1;
        for (int panelId : panels) {
            View panel = content.findViewById(panelId);
            assertNotNull("Missing settings panel " + panelId, panel);
            int current = content.indexOfChild(panel);
            assertTrue("Settings panel is out of product order: " + panelId, current > previous);
            previous = current;
        }

        // #520/#529: both USB sections live directly in the outer card body, so their relative
        // order is checked there instead of in the settings content column.
        ViewGroup usbAdbBody = content.findViewById(R.id.settings_usb_adb_body);
        assertNotNull(usbAdbBody);
        View usbNotificationPanel = usbAdbBody.findViewById(R.id.settings_usb_notification_panel);
        View usbHandoverPanel = usbAdbBody.findViewById(R.id.settings_usb_handover_panel);
        assertNotNull(usbNotificationPanel);
        assertNotNull(usbHandoverPanel);
        assertTrue("USB-ADB notification must come before USB -> Wifi-ADB handover inside USB-ADB",
                usbAdbBody.indexOfChild(usbNotificationPanel)
                        < usbAdbBody.indexOfChild(usbHandoverPanel));

        // #519/#618: both Network sections live directly in the outer card body, so their relative
        // order is checked there instead of in the settings content column.
        ViewGroup networkBetaBody = content.findViewById(R.id.settings_network_beta_body);
        assertNotNull(networkBetaBody);
        // #654/#769: the Network sections read current connection, protection level and comfort
        // switch, force, the Networks entry -- and the background access is last.
        int[] networkSections = {
                R.id.settings_network_status_panel,
                R.id.settings_network_level_panel,
                R.id.settings_force_panel,
                R.id.settings_network_manage_panel,
                R.id.settings_network_background_panel
        };
        int previousSection = -1;
        for (int sectionId : networkSections) {
            View section = networkBetaBody.findViewById(sectionId);
            assertNotNull("Missing Network section " + sectionId, section);
            int index = networkBetaBody.indexOfChild(section);
            assertTrue("Network section is out of order: " + sectionId, index > previousSection);
            previousSection = index;
        }
        View lastNetworkChild = networkBetaBody.getChildAt(networkBetaBody.getChildCount() - 1);
        assertEquals("The background access is the last content of the Network card",
                R.id.settings_network_background_panel, lastNetworkChild.getId());

        // #521: persistent notification, keep-display-on, advice-banner and battery-optimization
        // are no longer direct children of the settings content column either -- they sit
        // directly inside the "Sonstiges" card's body (not as sub-cards with their own panel ids
        // like USB-ADB/Network (Beta) above), so their relative order is checked by switch id
        // within that body instead.
        ViewGroup miscBody = content.findViewById(R.id.settings_misc_body);
        assertNotNull(miscBody);
        View hideNotificationToggle = miscBody.findViewById(R.id.settings_hide_notification_toggle);
        View keepDisplayOnToggle = miscBody.findViewById(R.id.settings_keep_display_on_toggle);
        View adviceBannerToggle = miscBody.findViewById(R.id.settings_advice_banner_toggle);
        assertNotNull(hideNotificationToggle);
        assertNotNull(keepDisplayOnToggle);
        assertNotNull(adviceBannerToggle);
        assertTrue("Persistent notification must come before keep-display-on inside Sonstiges",
                miscBody.indexOfChild(hideNotificationToggle) < miscBody.indexOfChild(keepDisplayOnToggle));
        assertTrue("Keep-display-on must come before the advice banner inside Sonstiges",
                miscBody.indexOfChild(keepDisplayOnToggle) < miscBody.indexOfChild(adviceBannerToggle));
        // #764: the battery-optimization toggle is gone with the home card it controlled; the
        // advice-banner switch (with its subtext) closes Sonstiges again.
        assertEquals(miscBody.getChildCount() - 2, miscBody.indexOfChild(adviceBannerToggle));
    }

    @Test
    public void profileEditAndDeleteContractsRemainInteractiveAtRuntime() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity settings = controller.get();
        KeepADBUsbProfile.Profile original = KeepADBUsbProfile.add(
                settings, "RuntimeHost", "192.0.2.10", "runtime.local", "runtime.tail");
        settings.findViewById(R.id.settings_usb_profile_action).performClick();
        AlertDialog switchDialog = settings.getActiveSwitchProfileDialog();
        assertNotNull(switchDialog);
        assertTrue(switchDialog.isShowing());
        assertNotNull(findViewByType(switchDialog.findViewById(android.R.id.custom), ScrollView.class));

        List<Button> profileButtons = findViewsByType(switchDialog.getWindow().getDecorView(), Button.class);
        Button edit = findButtonWithText(profileButtons,
                settings.getString(R.string.usb_profile_edit_button));
        Button delete = findButtonWithText(profileButtons,
                settings.getString(R.string.usb_profile_delete_button));
        assertNotNull(edit);
        assertNotNull(delete);
        measureAndLayout(switchDialog.getWindow().getDecorView(), 480, 800);
        assertMinSize(edit);
        assertMinSize(delete);
        assertEquals(settings.getString(R.string.usb_profile_edit_action_accessibility, "RuntimeHost"),
                edit.getContentDescription());
        assertEquals(settings.getString(R.string.usb_profile_delete_action_accessibility, "RuntimeHost"),
                delete.getContentDescription());

        edit.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog editDialog = settings.getActiveProfileEditDialog();
        assertNotNull(editDialog);
        assertTrue(editDialog.isShowing());
        List<EditText> fields = findViewsByType(editDialog.getWindow().getDecorView(), EditText.class);
        assertEquals(4, fields.size());
        assertEquals("RuntimeHost", fields.get(0).getText().toString());
        measureAndLayout(editDialog.getWindow().getDecorView(), 480, 800);
        for (EditText field : fields) assertMinSize(field);
        assertMinSize(editDialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertMinSize(editDialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        assertNotNull(editDialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        assertEquals(settings.getString(android.R.string.cancel),
                editDialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        fields.get(0).setText("EditedHost");
        fields.get(1).setText("192.0.2.20");
        fields.get(2).setText("edited.local");
        fields.get(3).setText("edited.tail");
        assertTrue(editDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(editDialog.isShowing());
        assertEquals(1, KeepADBUsbProfile.getProfiles(settings).size());
        KeepADBUsbProfile.Profile saved = KeepADBUsbProfile.getSelected(settings);
        assertNotNull(saved);
        assertEquals(original.id, saved.id);
        assertEquals("EditedHost", saved.name);
        assertEquals("192.0.2.20", saved.ipAddress);
        assertEquals("edited.local", saved.hostname);
        assertEquals("edited.tail", saved.tailnetHostname);
        assertEquals(settings.getString(R.string.usb_profile_selected,
                        "EditedHost · 192.0.2.20 · edited.local · edited.tail"),
                ((TextView) settings.findViewById(R.id.settings_usb_profile_summary)).getText().toString());
        ShadowLooper.idleMainLooper();

        settings.findViewById(R.id.settings_usb_profile_action).performClick();
        switchDialog = settings.getActiveSwitchProfileDialog();
        delete = findButtonWithText(findViewsByType(switchDialog.getWindow().getDecorView(), Button.class),
                settings.getString(R.string.usb_profile_delete_button));
        assertNotNull(delete);
        delete.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog deleteDialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(deleteDialog);
        assertTrue(deleteDialog.isShowing());
        assertHasText(deleteDialog.getWindow().getDecorView(),
                settings.getString(R.string.usb_profile_delete_title));
        assertHasText(deleteDialog.getWindow().getDecorView(),
                settings.getString(R.string.usb_profile_delete_message, "EditedHost"));
        measureAndLayout(deleteDialog.getWindow().getDecorView(), 480, 800);
        assertMinSize(deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertMinSize(deleteDialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        assertNotNull(deleteDialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        deleteDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(KeepADBUsbProfile.getProfiles(settings).isEmpty());
        settings.findViewById(R.id.settings_usb_profile_action).performClick();
        switchDialog = settings.getActiveSwitchProfileDialog();
        delete = findButtonWithText(findViewsByType(switchDialog.getWindow().getDecorView(), Button.class),
                settings.getString(R.string.usb_profile_delete_button));
        assertNotNull(delete);
        assertTrue(delete.performClick());
        ShadowLooper.idleMainLooper();
        deleteDialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(deleteDialog.isShowing());
        assertTrue(KeepADBUsbProfile.getProfiles(settings).isEmpty());
        assertEquals(null, KeepADBUsbProfile.getSelected(settings));
        assertEquals(settings.getString(R.string.usb_profile_none),
                ((TextView) settings.findViewById(R.id.settings_usb_profile_summary)).getText().toString());
        assertEquals(settings.getString(R.string.usb_profile_create_button),
                ((TextView) settings.findViewById(R.id.settings_usb_profile_action)).getText().toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void websiteLinkKeepsItsMeasuredTargetAndRuntimeAccessibilityProperties() {
        View settings = runtimeView(R.layout.activity_settings);
        // #478: the version card (which holds the website link) is permanently visible now, so
        // there is no header to expand first.
        measureAndLayout(settings, 360, 2400);
        TextView link = (TextView) settings.findViewById(R.id.settings_website_link);
        int minimum = dp(48);
        assertTrue(link.getMeasuredWidth() >= minimum);
        assertTrue(link.getMeasuredHeight() >= minimum);
        assertEquals(Gravity.CENTER_VERTICAL, link.getGravity() & Gravity.VERTICAL_GRAVITY_MASK);
        assertTrue(link.getPaddingTop() >= dp(8));
        assertTrue(link.getPaddingBottom() >= dp(8));
        assertTrue(link.getPaddingStart() >= dp(4));
        assertTrue(link.getPaddingEnd() >= dp(4));
        assertEquals(context.getString(R.string.settings_website_link_accessibility),
                link.getContentDescription());
        assertEquals(255, Color.alpha(link.getCurrentTextColor()));
        assertEquals("The website link and its parents must remain fully opaque", 1f,
                effectiveAlpha(link), 0.0001f);
        assertTrue("The actual link text must contrast with its actual background",
                contrastRatio(link.getCurrentTextColor(), backgroundColor(link)) >= 4.5);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void keepAdbVectorIsAReal24DpDrawableWithTheNotificationVisualContract() throws Exception {
        Drawable drawable = context.getResources().getDrawable(R.drawable.ic_keepadb);
        assertTrue(drawable instanceof VectorDrawable);
        assertEquals(dp(24), drawable.getIntrinsicWidth());
        assertEquals(dp(24), drawable.getIntrinsicHeight());

        // Native Canvas is necessary: legacy Robolectric records draw calls without proving
        // their visible result. Inspect the compiled drawable, including all transforms/tints.
        Bitmap bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 240, 240);
        drawable.draw(new Canvas(bitmap));
        int visible = 0;
        int opaque = 0;
        int left = 240, top = 240, right = -1, bottom = -1;
        int yellow = context.getColor(R.color.bright_yellow);
        assertTrue("Notification icon color must remain visible on the app background",
                contrastRatio(yellow, context.getColor(R.color.ground)) >= 3.0);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 240; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) > 0) {
                    visible++;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
                if (Color.alpha(pixel) == 255) {
                    opaque++;
                    assertEquals("Visible icon pixels must use the notification yellow", yellow, pixel);
                }
            }
        }
        // Broad visual bounds, not a pixel-perfect golden: the centered, enlarged mark must
        // occupy the viewport and keep its thin strokes rather than disappear or fill it.
        assertTrue("Expected visible strokes, got " + visible, visible > 5000 && visible < 10000);
        assertTrue("Expected opaque colored strokes", opaque > 3000);
        assertTrue("Horizontal transform clipped or shrank the mark: " + left + ".." + right,
                left >= 10 && left <= 30 && right >= 210 && right <= 230);
        assertTrue("Vertical transform clipped or shrank the mark: " + top + ".." + bottom,
                top >= 10 && top <= 30 && bottom >= 210 && bottom <= 230);
        bitmap.recycle();
    }

    @Test
    public void themeColorsMeetWcagContrastRequirementsAtRuntime() {
        int ground = context.getColor(R.color.ground);
        int panel = context.getColor(R.color.panel);
        int panelStrong = context.getColor(R.color.panel_strong);
        int linkRed = context.getColor(R.color.link_red);
        int borderRed = context.getColor(R.color.border_red);

        assertTrue(contrastRatio(linkRed, ground) >= 4.5);
        assertTrue(contrastRatio(linkRed, panel) >= 4.5);
        assertTrue(contrastRatio(linkRed, panelStrong) >= 4.5);
        assertTrue(contrastRatio(borderRed, ground) >= 3.0);
        assertTrue(contrastRatio(borderRed, panel) >= 3.0);
        assertTrue(contrastRatio(borderRed, panelStrong) >= 3.0);
    }

    private View runtimeView(int layout) {
        if (layout == R.layout.widget_keepadb) {
            org.robolectric.shadows.ShadowAppWidgetManager manager = shadowOf(
                    android.appwidget.AppWidgetManager.getInstance(context));
            int id = manager.createWidget(KeepADBWidget.class, layout);
            return manager.getViewFor(id);
        }
        Activity activity = layout == R.layout.activity_main
                ? startActivity(MainActivity.class) : startActivity(SettingsActivity.class);
        View root = activity.getWindow().getDecorView();
        measureAndLayout(root, 360, 2400);
        return root;
    }

    private <T extends Activity> T startActivity(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).setup().visible();
        activities.add(controller);
        return controller.get();
    }

    private int backgroundColor(View view) {
        Drawable background = view.getBackground();
        if (background != null) {
            Drawable current = background.getCurrent();
            Integer color = current instanceof ColorDrawable ? ((ColorDrawable) current).getColor()
                    : current instanceof GradientDrawable && ((GradientDrawable) current).getColor() != null
                            ? ((GradientDrawable) current).getColor().getColorForState(view.getDrawableState(), 0)
                            : null;
            assertNotNull("Unsupported background; resolve its actual visible color", color);
            if (Color.alpha(color) != 0) {
                assertEquals("Translucent background needs compositing", 255, Color.alpha(color));
                return color;
            }
        }
        assertTrue("No opaque background in the actual view hierarchy", view.getParent() instanceof View);
        return backgroundColor((View) view.getParent());
    }

    private float effectiveAlpha(View view) {
        float result = 1f;
        View current = view;
        while (current != null) {
            result *= current.getAlpha();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return result;
    }

    private void measureAndLayout(View root, int widthDp, int heightDp) {
        int width = dp(widthDp);
        int height = dp(heightDp);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private void assertMinSize(View view) {
        assertNotNull(view);
        int minimum = dp(48);
        String name;
        try {
            name = context.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException ignored) {
            name = String.valueOf(view.getId());
        }
        assertTrue(name + " measured width must be at least 48dp",
                view.getMeasuredWidth() >= minimum);
        assertTrue(name + " measured height must be at least 48dp",
                view.getMeasuredHeight() >= minimum);
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void assertHasText(View view, CharSequence expected) {
        assertTrue("Expected text not found: " + expected, containsText(view, expected));
    }

    private boolean containsText(View view, CharSequence expected) {
        if (view instanceof TextView && expected.toString().contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private TextView findTextView(View view, CharSequence expected) {
        if (view instanceof TextView && expected.toString().contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                try {
                    TextView found = findTextView(group.getChildAt(index), expected);
                    if (found != null) return found;
                } catch (AssertionError ignored) {
                    // Continue searching sibling views in the inflated hierarchy.
                }
            }
        }
        throw new AssertionError("Expected TextView not found: " + expected);
    }

    private <T extends View> T findViewByType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findViewByType(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private <T extends View> List<T> findViewsByType(View view, Class<T> type) {
        List<T> result = new ArrayList<>();
        collectViewsByType(view, type, result);
        return result;
    }

    private <T extends View> void collectViewsByType(View view, Class<T> type, List<T> result) {
        if (type.isInstance(view)) result.add(type.cast(view));
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectViewsByType(group.getChildAt(index), type, result);
        }
    }

    private Button findButtonWithText(List<Button> buttons, String text) {
        for (Button button : buttons) {
            if (text.equals(button.getText().toString())) return button;
        }
        return null;
    }

    private void assertPoliteLiveRegion(View view) {
        assertNotNull(view);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, view.getAccessibilityLiveRegion());
    }

    private double contrastRatio(int first, int second) {
        double firstLuminance = relativeLuminance(first);
        double secondLuminance = relativeLuminance(second);
        double lighter = Math.max(firstLuminance, secondLuminance);
        double darker = Math.min(firstLuminance, secondLuminance);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private double relativeLuminance(int color) {
        return 0.2126 * linearComponent(Color.red(color) / 255.0)
                + 0.7152 * linearComponent(Color.green(color) / 255.0)
                + 0.0722 * linearComponent(Color.blue(color) / 255.0);
    }

    private double linearComponent(double component) {
        return component <= 0.04045 ? component / 12.92
                : Math.pow((component + 0.055) / 1.055, 2.4);
    }
}
