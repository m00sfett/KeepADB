package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

/** Runtime accessibility contracts backed by Robolectric-inflated Android views. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAccessibilityContractTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBNetwork.resetForTesting();
        KeepADBNotification.resetForTesting();
        KeepADB.resetForTesting(context);
    }

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
        KeepADBNotification.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void interactiveViewsKeep48DpTouchTargetsAfterMeasurement() {
        View main = inflate(R.layout.activity_main);
        View settings = inflate(R.layout.activity_settings);
        View widget = inflate(R.layout.widget_keepadb);
        main.findViewById(R.id.notification_permission_panel).setVisibility(View.VISIBLE);
        main.findViewById(R.id.webhook_setup_button).setVisibility(View.VISIBLE);
        measureAndLayout(main, 360, 2400);
        measureAndLayout(settings, 360, 2400);
        measureAndLayout(widget, 360, 160);

        int[] mainControls = {
                R.id.btn_open_settings, R.id.btn_dismiss_advice_banner, R.id.setup_refresh,
                R.id.btn_open_notification_settings, R.id.btn_open_battery_settings,
                R.id.toggle, R.id.keep_alive_toggle, R.id.webhook_setup_button
        };
        for (int id : mainControls) assertMinSize(main.findViewById(id));

        int[] settingsControls = {
                R.id.btn_back, R.id.settings_language_selector, R.id.settings_webhook_toggle,
                R.id.settings_webhook_url, R.id.settings_webhook_clear, R.id.settings_webhook_save,
                R.id.settings_usb_notification_toggle, R.id.settings_usb_profile_notification_toggle,
                R.id.settings_usb_profile_action, R.id.settings_usb_handover_selector,
                R.id.settings_trusted_network_toggle, R.id.settings_trusted_network_add,
                R.id.settings_trusted_network_manage, R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle, R.id.settings_advice_banner_toggle,
                R.id.settings_diagnostics_export, R.id.settings_issue_report,
                R.id.settings_website_link
        };
        for (int id : settingsControls) assertMinSize(settings.findViewById(id));
        assertMinSize(widget.findViewById(R.id.widget_label));
    }

    @Test
    public void activitiesInstallInteractiveAndAccessibleRuntimeContracts() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        ActivityController<MainActivity> mainController =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = mainController.get();
        assertNotNull(main.findViewById(R.id.toggle));
        assertTrue(main.findViewById(R.id.btn_open_settings).hasOnClickListeners());
        assertTrue(main.findViewById(R.id.btn_dismiss_advice_banner).hasOnClickListeners());
        assertTrue(main.findViewById(R.id.toggle).hasOnClickListeners());
        assertTrue(main.findViewById(R.id.keep_alive_toggle).hasOnClickListeners());

        ImageView mainIcon = (ImageView) ((ViewGroup) main.findViewById(R.id.header_bar)).getChildAt(0);
        assertNotNull("The main header must render the app icon", mainIcon.getDrawable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, mainIcon.getImportantForAccessibility());
        TextView mainTitle = (TextView) ((ViewGroup) main.findViewById(R.id.header_bar)).getChildAt(1);
        assertEquals(main.getString(R.string.title_keepadb), mainTitle.getText().toString());
        assertEquals(main.getString(R.string.action_settings),
                main.findViewById(R.id.btn_open_settings).getContentDescription());

        ActivityController<SettingsActivity> settingsController =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity settings = settingsController.get();
        int[] settingsControls = {
                R.id.btn_back, R.id.settings_language_selector, R.id.settings_webhook_toggle,
                R.id.settings_webhook_clear, R.id.settings_webhook_save,
                R.id.settings_usb_notification_toggle, R.id.settings_usb_profile_notification_toggle,
                R.id.settings_usb_profile_action, R.id.settings_usb_handover_selector,
                R.id.settings_trusted_network_toggle, R.id.settings_trusted_network_add,
                R.id.settings_trusted_network_manage, R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle, R.id.settings_advice_banner_toggle,
                R.id.settings_diagnostics_export, R.id.settings_issue_report,
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
                settings.findViewById(R.id.settings_language_selector).getContentDescription());
        assertEquals(settings.getString(R.string.settings_usb_handover_accessibility,
                        settings.getString(R.string.settings_usb_handover_mode_off)),
                settings.findViewById(R.id.settings_usb_handover_selector).getContentDescription());

        settingsController.pause().stop().destroy();
        mainController.pause().stop().destroy();
    }

    @Test
    public void settingsBackButtonUsesAnAutoMirroredRuntimeDrawable() {
        View settings = inflate(R.layout.activity_settings);
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
        View main = inflate(R.layout.activity_main);
        View settings = inflate(R.layout.activity_settings);

        assertHasText(main.findViewById(R.id.advice_banner),
                context.getString(R.string.advice_banner_title));
        assertHasText(main.findViewById(R.id.advice_banner),
                context.getString(R.string.advice_banner_text));
        assertHasText(main.findViewById(R.id.battery_optimization_panel),
                context.getString(R.string.battery_optimization_title));
        assertHasText(main.findViewById(R.id.battery_optimization_panel),
                context.getString(R.string.battery_optimization_body));
        assertHasText(settings.findViewById(R.id.settings_notification_panel),
                context.getString(R.string.settings_section_notification));
        assertHasText(settings.findViewById(R.id.settings_notification_panel),
                context.getString(R.string.settings_hide_notification_toggle));
        assertHasText(settings.findViewById(R.id.settings_usb_notification_panel),
                context.getString(R.string.settings_section_usb_notification));
        assertHasText(settings.findViewById(R.id.settings_usb_notification_panel),
                context.getString(R.string.settings_usb_profile_notification_toggle));
        assertHasText(settings.findViewById(R.id.settings_usb_notification_panel),
                context.getString(R.string.usb_profile_create_button));

        assertPoliteLiveRegion(main.findViewById(R.id.status));
        assertPoliteLiveRegion(main.findViewById(R.id.webhook_status));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_error));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_cleartext_warning));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_trusted_network_status));
        assertTrue(findTextView(main, context.getString(R.string.advice_banner_title))
                .isAccessibilityHeading());
        assertTrue(findTextView(main, context.getString(R.string.battery_optimization_title))
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

        View settings = inflate(R.layout.activity_settings);
        assertFalse(containsText(settings, context.getString(R.string.advice_banner_title)));
        assertFalse(containsText(settings, context.getString(R.string.advice_banner_text)));
        controller.pause().stop().destroy();
    }

    @Test
    public void settingsPanelsFollowProductOrderInTheInflatedHierarchy() {
        View settings = inflate(R.layout.activity_settings);
        ViewGroup content = (ViewGroup) ((android.widget.ScrollView)
                settings.findViewById(R.id.settings_scroll_view)).getChildAt(0);
        int[] panels = {
                R.id.settings_language_panel,
                R.id.settings_webhook_panel,
                R.id.settings_usb_notification_panel,
                R.id.settings_usb_handover_panel,
                R.id.settings_trusted_network_panel,
                R.id.settings_notification_panel,
                R.id.settings_display_panel,
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
    }

    @Test
    public void batteryActionDoesNotMutateWirelessDebuggingOrKeepAliveState() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = controller.get();
        main.findViewById(R.id.btn_open_battery_settings).performClick();
        assertTrue("The battery-settings action must not write adb_wifi_enabled",
                gateway.writes.isEmpty());
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(main));
        controller.pause().stop().destroy();
    }

    @Test
    public void batteryWarningIsDrivenByTheLivePowerManagerState() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        boolean exempt = KeepADBBatteryOptimization.isExempt(context);
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(exempt ? View.GONE : View.VISIBLE,
                controller.get().findViewById(R.id.battery_optimization_panel).getVisibility());
        assertHasText(controller.get().findViewById(R.id.battery_optimization_panel),
                controller.get().getString(R.string.battery_optimization_button));
        controller.pause().stop().destroy();
    }

    @Test
    public void profileEditAndDeleteContractsRemainInteractiveAtRuntime() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity settings = controller.get();
        KeepADBUsbProfile.add(settings, "RuntimeHost", "192.0.2.10", "runtime.local", "runtime.tail");
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
        assertEquals(settings.getString(R.string.usb_profile_edit_action_accessibility, "RuntimeHost"),
                edit.getContentDescription());
        assertEquals(settings.getString(R.string.usb_profile_delete_action_accessibility, "RuntimeHost"),
                delete.getContentDescription());

        edit.performClick();
        AlertDialog editDialog = settings.getActiveProfileEditDialog();
        assertNotNull(editDialog);
        assertTrue(editDialog.isShowing());
        List<EditText> fields = findViewsByType(editDialog.getWindow().getDecorView(), EditText.class);
        assertEquals(4, fields.size());
        assertEquals("RuntimeHost", fields.get(0).getText().toString());
        assertNotNull(editDialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        assertEquals(settings.getString(android.R.string.cancel),
                editDialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        editDialog.dismiss();
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
                settings.getString(R.string.usb_profile_delete_message, "RuntimeHost"));
        assertNotNull(deleteDialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        deleteDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertFalse(KeepADBUsbProfile.getProfiles(settings).isEmpty());
        controller.pause().stop().destroy();
    }

    @Test
    public void websiteLinkKeepsItsMeasuredTargetAndRuntimeAccessibilityProperties() {
        View settings = inflate(R.layout.activity_settings);
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
    }

    @Test
    public void keepAdbVectorIsAReal24DpDrawableWithTheNotificationVisualContract() throws Exception {
        Drawable drawable = context.getResources().getDrawable(R.drawable.ic_keepadb);
        assertTrue(drawable instanceof VectorDrawable);
        assertEquals(dp(24), drawable.getIntrinsicWidth());
        assertEquals(dp(24), drawable.getIntrinsicHeight());

        android.content.res.XmlResourceParser parser =
                context.getResources().getXml(R.drawable.ic_keepadb);
        boolean vectorSeen = false;
        boolean groupSeen = false;
        boolean fillSeen = false;
        try {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                    event = parser.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                String name = parser.getName();
                if ("vector".equals(name)) {
                    vectorSeen = true;
                    assertEquals("24.0dip", parser.getAttributeValue(ANDROID_NS, "width"));
                    assertEquals("24.0dip", parser.getAttributeValue(ANDROID_NS, "height"));
                    assertEquals("24.0", parser.getAttributeValue(ANDROID_NS, "viewportWidth"));
                    assertEquals("24.0", parser.getAttributeValue(ANDROID_NS, "viewportHeight"));
                } else if ("group".equals(name)) {
                    groupSeen = true;
                    assertEquals(1.31f, parser.getAttributeFloatValue(ANDROID_NS, "scaleX", 0), 0.001f);
                    assertEquals(1.31f, parser.getAttributeFloatValue(ANDROID_NS, "scaleY", 0), 0.001f);
                } else if ("path".equals(name)
                        && parser.getAttributeResourceValue(ANDROID_NS, "fillColor", 0)
                        == R.color.bright_yellow) {
                    fillSeen = true;
                }
            }
        } finally {
            parser.close();
        }
        assertTrue(vectorSeen);
        assertTrue(groupSeen);
        assertTrue(fillSeen);
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

    private View inflate(int layout) {
        return LayoutInflater.from(context).inflate(layout, null, false);
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
