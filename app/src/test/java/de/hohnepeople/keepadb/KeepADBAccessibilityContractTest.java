package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/** Static contracts for UI/accessibility resources that do not require an Android runtime. */
public class KeepADBAccessibilityContractTest {

    @Test
    public void interactiveLayoutsKeepMinimumTouchTargets() throws IOException {
        String main = read("app/src/main/res/layout/activity_main.xml");
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        String widget = read("app/src/main/res/layout/widget_keepadb.xml");

        assertTrue(main.contains("android:layout_width=\"48dp\""));
        assertTrue(main.contains("android:layout_height=\"48dp\""));
        assertTrue(main.contains("android:minHeight=\"48dp\""));
        assertTrue(settings.contains("android:layout_width=\"48dp\""));
        assertTrue(settings.contains("android:layout_height=\"48dp\""));
        assertTrue(settings.contains("android:minHeight=\"48dp\""));
        assertTrue(widget.contains("android:minWidth=\"48dp\""));
        assertTrue(widget.contains("android:minHeight=\"48dp\""));
    }

    @Test
    public void backNavigationIsRtlAdaptive() throws IOException {
        String layout = read("app/src/main/res/layout/activity_settings.xml");
        String drawable = read("app/src/main/res/drawable/ic_arrow_back.xml");

        assertTrue(layout.contains("android:src=\"@drawable/ic_arrow_back\""));
        assertTrue(drawable.contains("android:autoMirrored=\"true\""));
    }

    @Test
    public void everyLocaleProvidesAccessibilityAndPermissionText() throws IOException {
        Path valuesRoot = projectPath("app/src/main/res");
        try (Stream<Path> paths = Files.list(valuesRoot)) {
            List<Path> localeDirectories = paths
                    .filter(path -> path.getFileName().toString().startsWith("values"))
                    .toList();
            assertTrue(localeDirectories.size() >= 19);
            for (Path directory : localeDirectories) {
                Path strings = directory.resolve("strings.xml");
                assertTrue("Missing strings.xml in " + directory, Files.exists(strings));
                String content = readFile(strings);
                assertTrue("Missing notification permission title in " + directory,
                        content.contains("name=\"notification_permission_panel_title\""));
                assertTrue("Missing notification settings action in " + directory,
                        content.contains("name=\"notification_permission_settings_button\""));
                assertTrue("Missing battery optimization title in " + directory,
                        content.contains("name=\"battery_optimization_title\""));
                assertTrue("Missing battery optimization body in " + directory,
                        content.contains("name=\"battery_optimization_body\""));
                assertTrue("Missing battery optimization action in " + directory,
                        content.contains("name=\"battery_optimization_button\""));
                assertTrue("Missing back description in " + directory,
                        content.contains("name=\"back\""));
                assertTrue("Missing standardized tile label in " + directory,
                        content.contains("<string name=\"tile_label\">@string/app_name</string>"));
                assertTrue("Missing advice banner title in " + directory,
                        content.contains("name=\"advice_banner_title\""));
                assertTrue("Missing advice banner text in " + directory,
                        content.contains("name=\"advice_banner_text\""));
                assertTrue("Missing notification section title in " + directory,
                        content.contains("name=\"settings_section_notification\""));
                assertTrue("Missing hide notification toggle label in " + directory,
                        content.contains("name=\"settings_hide_notification_toggle\""));
                assertTrue("Missing hide notification subtext in " + directory,
                        content.contains("name=\"settings_hide_notification_subtext\""));
                assertTrue("Missing notification hidden toast in " + directory,
                        content.contains("name=\"settings_notification_hidden_toast\""));
                assertTrue("Missing notification visible toast in " + directory,
                        content.contains("name=\"settings_notification_visible_toast\""));
                assertTrue("Missing USB notification title in " + directory,
                        content.contains("name=\"settings_section_usb_notification\""));
                assertTrue("Missing USB profile notification toggle in " + directory,
                        content.contains("name=\"settings_usb_profile_notification_toggle\""));
                assertTrue("Missing USB profile notification subtext in " + directory,
                        content.contains("name=\"settings_usb_profile_notification_subtext\""));
                assertTrue("Missing USB profile action in " + directory,
                        content.contains("name=\"usb_profile_create_button\""));
                assertTrue("Missing USB profile edit action in " + directory,
                        content.contains("name=\"usb_profile_edit_button\""));
                assertTrue("Missing USB profile edit title in " + directory,
                        content.contains("name=\"usb_profile_edit_title\""));
                assertTrue("Missing USB profile delete action in " + directory,
                        content.contains("name=\"usb_profile_delete_button\""));
                assertTrue("Missing USB profile delete title in " + directory,
                        content.contains("name=\"usb_profile_delete_title\""));
                assertTrue("Missing USB profile delete message in " + directory,
                        content.contains("name=\"usb_profile_delete_message\""));
                assertTrue("Missing trusted network delete accessibility in " + directory,
                        content.contains("name=\"settings_trusted_network_delete_accessibility\""));
                assertTrue("Missing USB profile edit accessibility in " + directory,
                        content.contains("name=\"usb_profile_edit_action_accessibility\""));
                assertTrue("Missing USB profile delete accessibility in " + directory,
                        content.contains("name=\"usb_profile_delete_action_accessibility\""));
                assertTrue("Missing notification disable action in " + directory,
                        content.contains("name=\"notification_action_disable\""));
            }
        }
    }

    @Test
    public void mainActivityHeaderIncludesVisualAppIcon() throws IOException {
        String main = read("app/src/main/res/layout/activity_main.xml");
        assertTrue(main.contains("android:src=\"@drawable/ic_keepadb\""));
        assertTrue(main.contains("android:importantForAccessibility=\"no\""));
        int iconIndex = main.indexOf("android:src=\"@drawable/ic_keepadb\"");
        int titleIndex = main.indexOf("android:text=\"@string/title_keepadb\"");
        int settingsIndex = main.indexOf("android:id=\"@+id/btn_open_settings\"");
        assertTrue(iconIndex < titleIndex);
        assertTrue(titleIndex < settingsIndex);
    }

    @Test
    public void vectorDrawableKeepADBMatchesNotificationStandard() throws IOException {
        String xml = read("app/src/main/res/drawable/ic_keepadb.xml");
        assertTrue(xml.contains("<vector"));
        assertTrue(xml.contains("android:width=\"24dp\""));
        assertTrue(xml.contains("android:height=\"24dp\""));
        assertTrue(xml.contains("android:viewportWidth=\"24\""));
        assertTrue(xml.contains("android:viewportHeight=\"24\""));
        assertTrue(xml.contains("android:fillColor=\"@color/bright_yellow\""));
        assertTrue(xml.contains("<group"));
        assertTrue(xml.contains("android:scaleX=\"1.31\""));
        assertTrue(xml.contains("android:scaleY=\"1.31\""));
    }

    /** #228: the security advice lives in the main-screen banner, not in Settings. */
    @Test
    public void securityAdviceLivesInMainBannerOnly() throws IOException {
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        assertFalse(settings.contains("settings_security_panel"));
        assertFalse(settings.contains("@string/settings_section_security"));
        assertFalse(settings.contains("@string/settings_security_body"));

        String main = read("app/src/main/res/layout/activity_main.xml");
        assertTrue(main.contains("android:id=\"@+id/advice_banner\""));
        assertTrue(main.contains("android:background=\"@drawable/bg_advice_banner\""));
        assertTrue(main.contains("android:src=\"@drawable/ic_warning\""));
        assertTrue(main.contains("android:text=\"@string/advice_banner_title\""));
        assertTrue(main.contains("android:text=\"@string/advice_banner_text\""));
        assertTrue(main.contains("android:id=\"@+id/btn_dismiss_advice_banner\""));
    }

    @Test
    public void settingsPanelsFollowProductOrder() throws IOException {
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        String[] panels = {
                "settings_language_panel",
                "settings_webhook_panel",
                "settings_usb_notification_panel",
                "settings_usb_handover_panel",
                "settings_notification_panel",
                "settings_diagnostics_panel",
                "settings_version_panel",
        };
        int previous = -1;
        for (String panel : panels) {
            int current = settings.indexOf("android:id=\"@+id/" + panel + "\"");
            assertTrue("Missing settings panel " + panel, current >= 0);
            assertTrue("Settings panel out of order: " + panel, current > previous);
            previous = current;
        }
    }

    @Test
    public void settingsActivityIncludesNotificationSettingsPanel() throws IOException {
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        assertTrue(settings.contains("android:id=\"@+id/settings_notification_panel\""));
        assertTrue(settings.contains("android:id=\"@+id/settings_hide_notification_toggle\""));
        assertTrue(settings.contains("android:text=\"@string/settings_section_notification\""));
        assertTrue(settings.contains("android:text=\"@string/settings_hide_notification_toggle\""));
        assertTrue(settings.contains("android:text=\"@string/settings_hide_notification_subtext\""));
    }

    @Test
    public void settingsActivityIncludesUsbNotificationAndProfilePanel() throws IOException {
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(settings.contains("android:id=\"@+id/settings_usb_notification_panel\""));
        assertTrue(settings.contains("android:id=\"@+id/settings_usb_notification_toggle\""));
        assertTrue(settings.contains("android:id=\"@+id/settings_usb_profile_notification_toggle\""));
        assertTrue(settings.contains("android:id=\"@+id/settings_usb_profile_action\""));
        assertTrue(activity.contains("KeepADBUsbProfile.setNotificationEnabled"));
        assertTrue(activity.contains("KeepADBUsbProfile.setProfileNotificationEnabled"));
        assertTrue(activity.contains("showProfileDialog"));
        assertTrue(activity.contains("showProfileEditDialog"));
        assertTrue(activity.contains("usb_profile_edit_button"));
    }

    @Test
    public void mainActivityIncludesBatteryOptimizationWarningAndFallback() throws IOException {
        String layout = read("app/src/main/res/layout/activity_main.xml");
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String helper = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBBatteryOptimization.java");
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(layout.contains("android:id=\"@+id/battery_optimization_panel\""));
        assertTrue(layout.contains("@string/battery_optimization_title"));
        assertTrue(layout.contains("@string/battery_optimization_body"));
        assertTrue(layout.contains("@+id/btn_open_battery_settings"));
        assertTrue(layout.contains("android:accessibilityHeading=\"true\""));
        assertTrue(activity.contains("KeepADBBatteryOptimization.isExempt(this)"));
        assertTrue(activity.contains("KeepADBBatteryOptimization.openSettings(this)"));
        assertTrue(activity.contains("batteryOptimizationPanel.setVisibility"));
        assertTrue(activity.contains("protected void onResume()"));
        assertTrue(activity.contains("refresh();\n        KeepADBNotification.refresh(this);"));
        assertTrue(helper.contains("isIgnoringBatteryOptimizations"));
        assertTrue(helper.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
        assertTrue(helper.contains("Uri.parse(\"package:\" + activity.getPackageName())"));
        assertTrue(helper.contains("ACTION_APPLICATION_DETAILS_SETTINGS"));
        assertTrue(helper.contains("ActivityNotFoundException"));
        assertTrue(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
    }

    @Test
    public void batteryOptimizationFeatureDoesNotChangeWifiOrKeepAliveState() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String helper = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBBatteryOptimization.java");
        int batteryClickIndex = activity.indexOf("btn_open_battery_settings");
        int nextMethodIndex = activity.indexOf("if (shouldRequestNotificationPermission())", batteryClickIndex);
        assertTrue(batteryClickIndex >= 0);
        assertTrue(nextMethodIndex > batteryClickIndex);
        String batteryPath = activity.substring(batteryClickIndex, nextMethodIndex);
        assertFalse(batteryPath.contains("KeepADB.setEnabled"));
        assertFalse(batteryPath.contains("setKeepAliveEnabled"));
        assertFalse(helper.contains("adb_wifi_enabled"));
        assertFalse(helper.contains("setKeepAliveEnabled"));
    }

    @Test
    public void profileEditDialogKeepsCancelAndRefreshContracts() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        int profileDialogIndex = activity.indexOf("private void showProfileDialog");
        int editDialogIndex = activity.indexOf("private void showProfileEditDialog");
        assertTrue(profileDialogIndex >= 0);
        assertTrue(editDialogIndex >= 0);
        String profileDialogs = activity.substring(profileDialogIndex);
        String editDialog = activity.substring(editDialogIndex);
        assertTrue(profileDialogs.contains("android.widget.RadioButton"));
        assertTrue(profileDialogs.contains("select.setChecked(current != null && current.id == profile.id)"));
        assertTrue(editDialog.contains("if (profile != null)"));
        assertTrue(editDialog.contains("name.setText(profile.name)"));
        assertTrue(editDialog.contains("ip.setText(profile.ipAddress)"));
        assertTrue(editDialog.contains("hostname.setText(profile.hostname)"));
        assertTrue(editDialog.contains("tailnet.setText(profile.tailnetHostname)"));
        assertTrue(editDialog.contains("setNegativeButton(android.R.string.cancel, null)"));
        assertTrue(editDialog.contains("KeepADBUsbProfile.update(this, profile.id"));
        assertTrue(editDialog.contains("KeepADBUsbReceiver.refresh(this)"));
        assertTrue(editDialog.contains("refresh();"));
    }

    @Test
    public void profileDeleteRequiresConfirmationAndRefreshesBothSurfaces() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbNotification.java");
        int deleteDialogIndex = activity.indexOf("private void showProfileDeleteDialog");
        int profileFieldIndex = activity.indexOf("private EditText profileField");
        assertTrue(deleteDialogIndex >= 0);
        assertTrue(profileFieldIndex > deleteDialogIndex);
        String deleteDialog = activity.substring(deleteDialogIndex, profileFieldIndex);
        assertTrue(deleteDialog.contains("getString(R.string.usb_profile_delete_message, profile.name)"));
        int positiveIndex = deleteDialog.indexOf("setPositiveButton(R.string.usb_profile_delete_button");
        int deleteIndex = deleteDialog.indexOf("KeepADBUsbProfile.delete(this, profile.id)");
        int negativeIndex = deleteDialog.indexOf("setNegativeButton(android.R.string.cancel, null)");
        assertTrue(positiveIndex >= 0);
        assertTrue(deleteIndex > positiveIndex);
        assertTrue(negativeIndex > deleteIndex);
        assertFalse(deleteDialog.contains("setOnCancelListener"));
        assertTrue(deleteDialog.contains("KeepADBUsbReceiver.refresh(this)"));
        assertTrue(deleteDialog.contains("refresh();"));
        assertFalse(notification.contains("ACTION_DELETE"));
    }

    @Test
    public void dynamicStatusAndErrorViewsDeclarePoliteLiveRegions() throws IOException {
        String main = read("app/src/main/res/layout/activity_main.xml");
        String settings = read("app/src/main/res/layout/activity_settings.xml");

        int statusIndex = main.indexOf("android:id=\"@+id/status\"");
        assertTrue("Missing status view in main", statusIndex >= 0);
        int statusEndIndex = main.indexOf("/>", statusIndex);
        String statusBlock = main.substring(statusIndex, statusEndIndex);
        assertTrue("status must declare polite live region",
                statusBlock.contains("android:accessibilityLiveRegion=\"polite\""));

        int webhookStatusIndex = main.indexOf("android:id=\"@+id/webhook_status\"");
        assertTrue("Missing webhook_status view in main", webhookStatusIndex >= 0);
        int webhookStatusEndIndex = main.indexOf("/>", webhookStatusIndex);
        String webhookStatusBlock = main.substring(webhookStatusIndex, webhookStatusEndIndex);
        assertTrue("webhook_status must declare polite live region",
                webhookStatusBlock.contains("android:accessibilityLiveRegion=\"polite\""));

        int webhookErrorIndex = settings.indexOf("android:id=\"@+id/settings_webhook_error\"");
        assertTrue("Missing settings_webhook_error in settings", webhookErrorIndex >= 0);
        int webhookErrorEndIndex = settings.indexOf("/>", webhookErrorIndex);
        String webhookErrorBlock = settings.substring(webhookErrorIndex, webhookErrorEndIndex);
        assertTrue("settings_webhook_error must declare polite live region",
                webhookErrorBlock.contains("android:accessibilityLiveRegion=\"polite\""));

        int cleartextWarningIndex = settings.indexOf("android:id=\"@+id/settings_webhook_cleartext_warning\"");
        assertTrue("Missing settings_webhook_cleartext_warning in settings", cleartextWarningIndex >= 0);
        int cleartextWarningEndIndex = settings.indexOf("/>", cleartextWarningIndex);
        String cleartextWarningBlock = settings.substring(cleartextWarningIndex, cleartextWarningEndIndex);
        assertTrue("settings_webhook_cleartext_warning must declare polite live region",
                cleartextWarningBlock.contains("android:accessibilityLiveRegion=\"polite\""));

        int trustedNetworkStatusIndex = settings.indexOf("android:id=\"@+id/settings_trusted_network_status\"");
        assertTrue("Missing settings_trusted_network_status in settings", trustedNetworkStatusIndex >= 0);
        int trustedNetworkStatusEndIndex = settings.indexOf("/>", trustedNetworkStatusIndex);
        String trustedNetworkStatusBlock = settings.substring(trustedNetworkStatusIndex, trustedNetworkStatusEndIndex);
        assertTrue("settings_trusted_network_status must declare polite live region",
                trustedNetworkStatusBlock.contains("android:accessibilityLiveRegion=\"polite\""));
    }

    @Test
    public void settingsWebsiteLinkHasAccessibleTouchTarget() throws IOException {
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        int linkIndex = settings.indexOf("android:id=\"@+id/settings_website_link\"");
        assertTrue("Missing settings_website_link", linkIndex >= 0);
        int linkEndIndex = settings.indexOf("/>", linkIndex);
        String linkBlock = settings.substring(linkIndex, linkEndIndex);

        assertTrue("settings_website_link must specify minHeight 48dp",
                linkBlock.contains("android:minHeight=\"48dp\""));
        assertTrue("settings_website_link must specify minWidth 48dp",
                linkBlock.contains("android:minWidth=\"48dp\""));
        assertTrue("settings_website_link must specify center_vertical gravity",
                linkBlock.contains("android:gravity=\"center_vertical\""));
        assertTrue("settings_website_link must specify vertical padding",
                linkBlock.contains("android:paddingTop=\"8dp\"")
                        && linkBlock.contains("android:paddingBottom=\"8dp\""));
        assertTrue("settings_website_link must specify horizontal padding",
                linkBlock.contains("android:paddingStart=\"4dp\"")
                        && linkBlock.contains("android:paddingEnd=\"4dp\""));
    }

    @Test
    public void themeColorsMeetWcagContrastRequirements() throws IOException {
        String colors = read("app/src/main/res/values/colors.xml");
        String groundHex = extractColorHex(colors, "ground");
        String panelHex = extractColorHex(colors, "panel");
        String panelStrongHex = extractColorHex(colors, "panel_strong");
        String linkRedHex = extractColorHex(colors, "link_red");
        String borderRedHex = extractColorHex(colors, "border_red");

        // WCAG AA for normal text requires >= 4.5:1
        assertTrue("link_red must achieve >= 4.5:1 contrast against ground",
                contrastRatio(linkRedHex, groundHex) >= 4.5);
        assertTrue("link_red must achieve >= 4.5:1 contrast against panel",
                contrastRatio(linkRedHex, panelHex) >= 4.5);
        assertTrue("link_red must achieve >= 4.5:1 contrast against panel_strong",
                contrastRatio(linkRedHex, panelStrongHex) >= 4.5);

        // WCAG 1.4.11 Non-text Contrast for graphical objects / UI boundaries requires >= 3.0:1
        assertTrue("border_red must achieve >= 3.0:1 contrast against ground",
                contrastRatio(borderRedHex, groundHex) >= 3.0);
        assertTrue("border_red must achieve >= 3.0:1 contrast against panel",
                contrastRatio(borderRedHex, panelHex) >= 3.0);
        assertTrue("border_red must achieve >= 3.0:1 contrast against panel_strong",
                contrastRatio(borderRedHex, panelStrongHex) >= 3.0);
    }

    private static String extractColorHex(String colorsXml, String colorName) {
        Matcher matcher = Pattern.compile(
                "<color\\s+name=\\\"" + Pattern.quote(colorName) + "\\\">#?([0-9a-fA-F]{6})</color>")
                .matcher(colorsXml);
        assertTrue("Could not find color " + colorName, matcher.find());
        return matcher.group(1);
    }

    private static double contrastRatio(String hex1, String hex2) {
        double l1 = relativeLuminance(hex1);
        double l2 = relativeLuminance(hex2);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return 0.2126 * linearComponent(r / 255.0)
                + 0.7152 * linearComponent(g / 255.0)
                + 0.0722 * linearComponent(b / 255.0);
    }

    private static double linearComponent(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static String read(String relativePath) throws IOException {
        return readFile(projectPath(relativePath));
    }

    private static String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory.resolve(relativePath);
    }
}
