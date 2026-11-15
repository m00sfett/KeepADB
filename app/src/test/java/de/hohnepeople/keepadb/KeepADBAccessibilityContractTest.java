package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
        View protection = runtimeView(R.layout.activity_protection);
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
        measureAndLayout(protection, 360, 2400);
        measureAndLayout(widget, 360, 160);

        int[] mainControls = {
                R.id.btn_open_settings, R.id.btn_toggle_privacy_mode,
                R.id.toggle, R.id.keep_alive_toggle
        };
        for (int id : mainControls) assertMinSize(main.findViewById(id));
        assertMinSize(systemWarningAction);

        int[] settingsControls = {
                R.id.btn_back, R.id.btn_toggle_privacy_mode, R.id.btn_settings_language,
                R.id.settings_privacy_header, R.id.privacy_switch_app,
                R.id.privacy_switch_notifications, R.id.privacy_switch_lockscreen,
                R.id.privacy_switch_widgets,
                R.id.settings_webhook_header, R.id.settings_webhook_toggle,
                R.id.settings_webhook_url, R.id.settings_webhook_clear, R.id.settings_webhook_save,
                R.id.settings_protection_row,
                R.id.settings_misc_header,
                R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle,
                R.id.settings_diagnostics_header,
                R.id.settings_diagnostics_export, R.id.settings_issue_report,
                R.id.settings_reset_app,
                R.id.settings_website_link
        };
        for (int id : settingsControls) assertMinSize(settings.findViewById(id));
        // #959: the Protection page: the status action, the force row, the Wi-Fi networks entry,
        // the background button, the two switches and the help lines with "More".
        int[] protectionControls = {
                R.id.btn_back, R.id.btn_toggle_privacy_mode,
                R.id.network_status_action, R.id.network_status_decide,
                R.id.settings_untrusted_shutdown_toggle, R.id.protection_untrusted_shutdown_hint,
                R.id.settings_security_check_toggle, R.id.settings_security_check_hint,
                R.id.settings_force_activate,
                R.id.network_networks_row,
                R.id.settings_background_location_status,
                R.id.settings_background_location_button
        };
        for (int id : protectionControls) {
            View control = protection.findViewById(id);
            if (control.getVisibility() == View.GONE) continue;
            assertMinSize(control);
        }
        assertMinSize(widget.findViewById(R.id.widget_tile));
    }

    /** #471: clicks every collapsible settings card's header so its body (and everything inside)
     * becomes part of the measured/shown layout -- cards start collapsed, and a GONE view's
     * children never receive a measured size or count as isShown(). #478: the language and
     * version headers are excluded -- they are permanently visible and no longer clickable. */
    private void expandAllSettingsCards(View settings) {
        int[] headers = {
                R.id.settings_webhook_header,
                // #521: the outer "Sonstiges" card must be expanded to make its four directly
                // nested sections' controls measurable/reachable; they are not independently collapsible sub-cards with headers of their own.
                R.id.settings_privacy_header,
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
        assertEquals(main.getString(R.string.home_state_setup),
                ((TextView) main.findViewById(R.id.status)).getText().toString());
        assertEquals(main.getString(R.string.home_reason_permission),
                ((TextView) main.findViewById(R.id.status_reason)).getText().toString());
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        gateway.write(context, true);
        gateway.writes.clear();
        // #764: coming back from the computer's grant redraws the home screen (no check button).
        mainController.pause().resume();
        assertEquals(View.GONE, main.findViewById(R.id.warning_system).getVisibility());
        assertTrue(main.findViewById(R.id.toggle).isEnabled());
        assertTrue(((android.widget.Switch) main.findViewById(R.id.toggle)).isChecked());
        assertEquals(main.getString(R.string.home_state_on),
                ((TextView) main.findViewById(R.id.status)).getText().toString());
        assertEquals(main.getString(R.string.home_reason_no_wifi),
                ((TextView) main.findViewById(R.id.status_reason)).getText().toString());
        assertTrue(gateway.writes.isEmpty());
        assertNotNull(main.findViewById(R.id.toggle));
        assertTrue(main.findViewById(R.id.btn_open_settings).hasOnClickListeners());
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
        menu.onMenuItemClicked(menu.getItems().get(0));
        assertEquals(KeepADBSettingsMenu.ID_ALL_SETTINGS, menu.getItems().get(0).id);
        Intent openedSettings = shadowOf(main).getNextStartedActivity();
        assertNotNull(openedSettings);
        assertEquals(SettingsActivity.class.getName(), openedSettings.getComponent().getClassName());

        ActivityController<SettingsActivity> settingsController =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity settings = settingsController.get();
        int[] settingsControls = {
                R.id.btn_back, R.id.btn_toggle_privacy_mode, R.id.btn_settings_language,
                R.id.settings_webhook_toggle,
                R.id.settings_webhook_clear, R.id.settings_webhook_save,
                R.id.settings_protection_row,
                R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle,
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
        // #959: the Protection page's controls carry runtime listeners as well.
        ActivityController<ProtectionActivity> protectionController =
                Robolectric.buildActivity(ProtectionActivity.class).setup();
        ProtectionActivity protection = protectionController.get();
        for (int id : new int[] {R.id.btn_back, R.id.btn_toggle_privacy_mode,
                R.id.settings_untrusted_shutdown_toggle, R.id.protection_untrusted_shutdown_hint,
                R.id.settings_security_check_toggle, R.id.settings_security_check_hint,
                R.id.settings_force_activate, R.id.network_networks_row,
                R.id.settings_background_location_status,
                R.id.settings_background_location_button, R.id.network_status_decide,
                R.id.network_status_action}) {
            View control = protection.findViewById(id);
            assertNotNull("Missing Protection control " + id, control);
            assertTrue("Protection control has no runtime listener: " + id,
                    control.hasOnClickListeners());
        }
        protectionController.pause().stop().destroy();
        assertEquals(settings.getString(R.string.back),
                settings.findViewById(R.id.btn_back).getContentDescription());
        assertEquals(settings.getString(R.string.settings_website_link_accessibility),
                settings.findViewById(R.id.settings_website_link).getContentDescription());
        String languageTag = KeepADBLocaleHelper.getSelectedLanguageTag(settings);
        String languageName = KeepADBLocaleHelper.getLanguageDisplayName(settings, languageTag);
        assertEquals(settings.getString(R.string.settings_language_accessibility, languageName),
                settings.findViewById(R.id.btn_settings_language).getContentDescription());

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
        View protection = runtimeView(R.layout.activity_protection);

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
        // #654/#959: every section of the Protection page starts with a non-interactive
        // accessibility heading (the Wi-Fi networks entry is a row of its own, not a heading).
        for (int id : new int[] {R.id.protection_title, R.id.network_status_heading,
                R.id.protection_level_heading, R.id.protection_automatic_heading,
                R.id.network_background_heading}) {
            TextView heading = protection.findViewById(id);
            assertTrue(heading.isAccessibilityHeading());
            assertFalse(heading.isClickable());
            assertFalse(heading.isFocusable());
        }
        assertPoliteLiveRegion(protection.findViewById(R.id.network_status_label));
        assertPoliteLiveRegion(protection.findViewById(R.id.settings_background_location_status));

        // #957: state and reason are one polite live region, so a change is announced as one
        // message; the two lines inside it are not live regions of their own.
        View summary = main.findViewById(R.id.status_summary);
        assertPoliteLiveRegion(summary);
        assertSame(summary, main.findViewById(R.id.status).getParent());
        assertSame(summary, main.findViewById(R.id.status_reason_row).getParent());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                main.findViewById(R.id.status).getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                main.findViewById(R.id.status_reason).getAccessibilityLiveRegion());
        assertPoliteLiveRegion(main.findViewById(R.id.webhook_status));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_error));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_cleartext_warning));
    }

    @Test
    public void securityAdviceIsRenderedOnTheAssistantIntroOnly() {
        // #958: no home card, no Settings switch; the assistant's intro page shows it as a heading
        // and its text, without a dismiss button.
        View main = runtimeView(R.layout.activity_main);
        View settings = runtimeView(R.layout.activity_settings);
        assertFalse(containsText(main, context.getString(R.string.advice_banner_title)));
        assertFalse(containsText(main, context.getString(R.string.advice_banner_text)));
        assertFalse(containsText(settings, context.getString(R.string.advice_banner_title)));
        assertFalse(containsText(settings, context.getString(R.string.advice_banner_text)));

        ActivityController<OnboardingActivity> controller = Robolectric.buildActivity(
                OnboardingActivity.class, OnboardingActivity.fullIntent(context)).setup();
        OnboardingActivity assistant = controller.get();
        TextView title = assistant.findViewById(R.id.onboarding_advice_title);
        assertEquals(assistant.getString(R.string.advice_banner_title), title.getText().toString());
        assertTrue(title.isAccessibilityHeading());
        assertHasText(assistant.findViewById(R.id.onboarding_page_content),
                assistant.getString(R.string.advice_banner_text));
        controller.pause().stop().destroy();
    }

    @Test
    public void settingsProtectionRowIsTheFirstCardAfterTheConditionalPermissionWarning() {
        // #735/#959: the Protection row is the first card on the settings page, before webhook and
        // all others; only the hidden-by-default permission warning may precede it.
        View settings = runtimeView(R.layout.activity_settings);
        ViewGroup content = (ViewGroup) ((android.widget.ScrollView)
                settings.findViewById(R.id.settings_scroll_view)).getChildAt(0);
        View permission = content.findViewById(R.id.settings_permission_panel);
        View protection = content.findViewById(R.id.settings_protection_row);
        assertNotNull(permission);
        assertNotNull(protection);
        assertTrue("Only the permission warning may precede the Protection row",
                content.indexOfChild(protection) <= content.indexOfChild(permission) + 1);
        assertTrue(content.indexOfChild(protection) < content.indexOfChild(
                content.findViewById(R.id.settings_webhook_panel)));
        // #959: the row opens a page; nothing of the former card expands inline any more.
        assertNull(content.findViewById(R.id.settings_network_status_panel));
        assertNull(content.findViewById(R.id.network_networks_row));
    }

    @Test
    public void settingsPanelsFollowProductOrderInTheInflatedHierarchy() {
        View settings = runtimeView(R.layout.activity_settings);
        ViewGroup content = (ViewGroup) ((android.widget.ScrollView)
                settings.findViewById(R.id.settings_scroll_view)).getChildAt(0);
        // #735/#959: the Protection row comes first, as the most important settings area. The
        // remaining cards keep their relative order: the webhook card, then the "Sonstiges" card.
        // #518: the language panel no longer exists in this content column at all -- it moved to
        // the header (a language button since #942) -- so it is no longer part of this table.
        int[] panels = {
                R.id.settings_protection_row,
                R.id.settings_webhook_panel,
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

        // #521: persistent notification, keep-display-on and battery-optimization
        // are no longer direct children of the settings content column either -- they sit
        // directly inside the "Sonstiges" card's body (not as sub-cards with their own panel ids),
        // so their relative order is checked by switch id within that body instead.
        ViewGroup miscBody = content.findViewById(R.id.settings_misc_body);
        assertNotNull(miscBody);
        View hideNotificationToggle = miscBody.findViewById(R.id.settings_hide_notification_toggle);
        View keepDisplayOnToggle = miscBody.findViewById(R.id.settings_keep_display_on_toggle);
        assertNotNull(hideNotificationToggle);
        assertNotNull(keepDisplayOnToggle);
        assertTrue("Persistent notification must come before keep-display-on inside Sonstiges",
                miscBody.indexOfChild(hideNotificationToggle) < miscBody.indexOfChild(keepDisplayOnToggle));
        // #764: the battery-optimization toggle is gone with the home card it controlled, #958 the
        // advice-banner switch: the keep-display-on switch (with its subtext) closes Sonstiges.
        assertEquals(miscBody.getChildCount() - 2, miscBody.indexOfChild(keepDisplayOnToggle));
    }

    /**
     * #959: the Protection page reads, top to bottom: current connection, protection level,
     * automatic behavior, force mode, the Wi-Fi networks entry and the background detection last.
     */
    @Test
    public void protectionSectionsFollowTheProductOrderInTheInflatedHierarchy() {
        View protection = runtimeView(R.layout.activity_protection);
        ViewGroup content = (ViewGroup) ((android.widget.ScrollView)
                protection.findViewById(R.id.protection_scroll)).getChildAt(0);
        int[] sections = {
                R.id.settings_network_status_panel,
                R.id.protection_level_panel,
                R.id.protection_automatic_panel,
                R.id.settings_force_panel,
                R.id.settings_network_manage_panel,
                R.id.settings_network_background_panel
        };
        int previous = -1;
        for (int id : sections) {
            View section = content.findViewById(id);
            assertNotNull("Missing Protection section " + id, section);
            int index = content.indexOfChild(section);
            assertTrue("Protection section is out of order: " + id, index > previous);
            previous = index;
        }
        assertEquals("The background detection is the last content of the page",
                R.id.settings_network_background_panel,
                content.getChildAt(content.getChildCount() - 1).getId());
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
                ? startActivity(MainActivity.class)
                : layout == R.layout.activity_protection
                ? startActivity(ProtectionActivity.class) : startActivity(SettingsActivity.class);
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
