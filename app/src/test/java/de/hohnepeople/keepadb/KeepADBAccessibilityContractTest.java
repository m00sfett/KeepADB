package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
                assertTrue("Missing security section title in " + directory,
                        content.contains("name=\"settings_section_security\""));
                assertTrue("Missing security body in " + directory,
                        content.contains("name=\"settings_security_body\""));
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

    @Test
    public void settingsActivityIncludesSecurityAdvicePanel() throws IOException {
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        assertTrue(settings.contains("android:id=\"@+id/settings_security_panel\""));
        assertTrue(settings.contains("android:text=\"@string/settings_section_security\""));
        assertTrue(settings.contains("android:text=\"@string/settings_security_body\""));
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
