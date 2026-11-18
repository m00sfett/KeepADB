package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * #951: tapping the endpoint copies exactly {@code adb connect <host>:<port>}; with "Hide in the
 * app" on the row shows the masked address, the clip holds the real one and is flagged sensitive;
 * the status notification had a "Copy" action until #1000 removed it; the tests below keep it gone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBEndpointCopyTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBOnboarding.markCompleted(context);
        KeepADB.resetForTesting(context);
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        KeepADBPreferences.setAppLanguage(context, "en");
        // A new installation shows details in notifications; the default is stored explicitly so
        // that the other keys of this test do not make it look like an upgrade (details hidden).
        KeepADBPreferences.setNotificationPrivacyEnabled(context, false);
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        ShadowToast.reset();
    }

    @After
    public void tearDown() throws Exception {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBEndpointCoordinator.resetForTesting();
        setCoordinatorStatic("currentHost", null);
        setCoordinatorStatic("currentPort", 0);
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
        context.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("", ""));
    }

    // ---- The command ---------------------------------------------------------------------------------

    @Test
    public void theCommandIsAdbConnectWithHostAndPortAndBracketsIpv6() {
        assertEquals("adb connect 192.0.2.10:40123",
                KeepADBEndpointText.adbConnectCommand("192.0.2.10", 40123));
        assertEquals("adb connect [fe80::1]:40123",
                KeepADBEndpointText.adbConnectCommand("fe80::1", 40123));
        assertEquals("adb connect [fe80::1]:40123",
                KeepADBEndpointText.adbConnectCommand("[fe80::1]", 40123));
        assertEquals("adb connect phone.example:5555",
                KeepADBEndpointText.adbConnectCommand("phone.example", 5555));
    }

    // ---- The home screen row -------------------------------------------------------------------------

    @Test
    public void tappingTheRowCopiesTheCommandAndTheClipIsNotFlaggedWithoutPrivacy() throws Exception {
        MainActivity activity = open();
        endpointListener().onEndpoint("192.0.2.10", 40123);

        View row = activity.findViewById(R.id.endpoint_row);
        assertEquals("Port 40123 @ 192.0.2.10",
                ((TextView) activity.findViewById(R.id.endpoint)).getText().toString());
        assertTrue(row.isClickable());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.endpoint_copy_icon).getVisibility());
        row.performClick();

        ClipData clip = clip();
        assertEquals("adb connect 192.0.2.10:40123", clip.getItemAt(0).getText().toString());
        assertFalse("not flagged without the privacy switch", isSensitive(clip));
        assertEquals("From Android 13 the system confirms the copy, no toast of ours",
                0, ShadowToast.shownToastCount());
    }

    @Test
    public void withHideInTheAppTheRowShowsTheMaskButTheClipHasTheRealValueAndIsSensitive()
            throws Exception {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        MainActivity activity = open();
        endpointListener().onEndpoint("192.0.2.10", 40123);

        String shown = ((TextView) activity.findViewById(R.id.endpoint)).getText().toString();
        assertEquals("Port 40123 @ 192.*.*.*", shown);
        activity.findViewById(R.id.endpoint_row).performClick();

        ClipData clip = clip();
        assertEquals("adb connect 192.0.2.10:40123", clip.getItemAt(0).getText().toString());
        assertTrue("flagged sensitive", isSensitive(clip));
    }

    @Test
    public void anIpv6EndpointIsBracketedInTheRowAndInTheCommand() throws Exception {
        MainActivity activity = open();
        endpointListener().onEndpoint("fe80::1", 40123);

        assertEquals("Port 40123 @ [fe80::1]",
                ((TextView) activity.findViewById(R.id.endpoint)).getText().toString());
        activity.findViewById(R.id.endpoint_row).performClick();
        assertEquals("adb connect [fe80::1]:40123", clip().getItemAt(0).getText().toString());
    }

    @Test
    public void whileNoAddressIsKnownTheRowIsNoTapTargetAndCopiesNothing() throws Exception {
        MainActivity activity = open();
        View row = activity.findViewById(R.id.endpoint_row);

        assertFalse(row.isClickable());
        assertFalse(row.isFocusable());
        assertEquals(View.GONE, activity.findViewById(R.id.endpoint_copy_icon).getVisibility());
        row.performClick();
        assertNull("nothing was copied", clipTextOrNull());

        // An address that goes away takes the tap target with it.
        endpointListener().onEndpoint("192.0.2.10", 40123);
        assertTrue(row.isClickable());
        endpointListener().onUnavailable();
        assertFalse(row.isClickable());
        assertEquals(View.GONE, activity.findViewById(R.id.endpoint_copy_icon).getVisibility());
    }

    @Test
    public void talkBackAnnouncesTheRowAsACopyActionWithoutRewritingItsText() throws Exception {
        MainActivity activity = open();
        endpointListener().onEndpoint("192.0.2.10", 40123);

        View row = activity.findViewById(R.id.endpoint_row);
        AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
        String label = null;
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                label = String.valueOf(action.getLabel());
            }
        }
        assertEquals(context.getString(R.string.endpoint_copy_action), label);
        assertNull("the content description is not rewritten", row.getContentDescription());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                activity.findViewById(R.id.endpoint_copy_icon).getImportantForAccessibility());
    }

    @Test
    public void theRowIsA48dpBorderlessTargetAndNotTheCardBackground() throws Exception {
        MainActivity activity = open();
        endpointListener().onEndpoint("192.0.2.10", 40123);

        View row = activity.findViewById(R.id.endpoint_row);
        assertTrue(row.getMinimumHeight() >= 48 * context.getResources().getDisplayMetrics().density - 1);
        assertNull("no card background", row.getBackground());
        assertNotNull("a ripple as foreground", row.getForeground());
    }

    // ---- Below Android 13 ----------------------------------------------------------------------------

    @Test
    @Config(sdk = 31)
    public void belowAndroid13ATokenAndAToastReplaceTheSystemConfirmation() {
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        assertTrue(KeepADBEndpointText.copyAdbConnect(context, "192.0.2.10", 40123, "app"));

        ClipData clip = clip();
        assertEquals("adb connect 192.0.2.10:40123", clip.getItemAt(0).getText().toString());
        PersistableBundle extras = clip.getDescription().getExtras();
        assertNotNull(extras);
        assertTrue("the string key of API 30 to 32",
                extras.getBoolean("android.content.extra.IS_SENSITIVE"));
        assertEquals(context.getString(R.string.onboarding_copy_done), ShadowToast.getTextOfLatestToast());
    }

    // ---- The notification ----------------------------------------------------------------------------

    @Test
    public void theCardOffersNoCopyActionAnyMore() throws Exception {
        // #1000: the notification's "Copy" (#951) is gone; the app copies the endpoint.
        setEndpoint("192.0.2.10", 40123);
        KeepADBNotification.renderEndpoint(context, "192.0.2.10", 40123);

        Notification card = KeepADBForceTestSupport.posted(context, KeepADBNotification.NOTIFICATION_ID);
        assertEquals(2, card.actions.length);
        assertEquals("Turn off Wifi-ADB", card.actions[0].title.toString());
        assertEquals(KeepADBNotification.keepAliveAction(context).title.toString(),
                card.actions[1].title.toString());
        for (Notification.Action action : card.actions) {
            assertFalse(shadowOf(action.actionIntent).getSavedIntent().getAction().contains("COPY"));
        }
    }

    @Test
    public void noCopyActionOnThePlaceholdersEither() {
        KeepADBNotification.renderSearching(context);

        Notification card = KeepADBForceTestSupport.posted(context, KeepADBNotification.NOTIFICATION_ID);
        assertNotNull(card);
        if (card.actions != null) {
            for (Notification.Action action : card.actions) {
                assertFalse(shadowOf(action.actionIntent).getSavedIntent().getAction().contains("COPY"));
            }
        }
    }

    @Test
    public void aCopyTapFromACardPostedBeforeTheUpdateCopiesNothing() throws Exception {
        setEndpoint("192.0.2.10", 40123);
        new KeepADBReceiver().onReceive(context, new Intent(context, KeepADBReceiver.class)
                .setAction("de.hohnepeople.keepadb.ACTION_COPY_ENDPOINT"));

        assertNull(clipTextOrNull());
        String manifest = new String(java.nio.file.Files.readAllBytes(
                projectFile("app/src/main/AndroidManifest.xml")), java.nio.charset.StandardCharsets.UTF_8);
        int start = manifest.indexOf("android:name=\".KeepADBReceiver\"");
        assertTrue(manifest.substring(start, manifest.indexOf("</receiver>", start))
                .contains("android:exported=\"false\""));
    }

    /** One copy routine, shared by the assistant's command and the endpoint (no second copy). */
    @Test
    public void onlyTheSharedHelperTalksToTheClipboardManagerForCommands() throws Exception {
        for (String file : new String[] {"OnboardingActionSteps", "MainActivity", "KeepADBReceiver",
                "KeepADBNotification", "KeepADBEndpointText"}) {
            String source = new String(java.nio.file.Files.readAllBytes(projectFile(
                    "app/src/main/java/de/hohnepeople/keepadb/" + file + ".java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(file + " has its own copy routine", source.contains("setPrimaryClip"));
        }
        String helper = new String(java.nio.file.Files.readAllBytes(projectFile(
                "app/src/main/java/de/hohnepeople/keepadb/KeepADBClipboard.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helper.contains("setPrimaryClip"));
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private MainActivity open() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private ClipData clip() {
        ClipData clip = context.getSystemService(ClipboardManager.class).getPrimaryClip();
        assertNotNull("something was copied", clip);
        return clip;
    }

    private String clipTextOrNull() {
        ClipData clip = context.getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).getText();
        return text == null || text.length() == 0 ? null : text.toString();
    }

    private static boolean isSensitive(ClipData clip) {
        PersistableBundle extras = clip.getDescription().getExtras();
        return extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false);
    }

    private void setEndpoint(String host, int port) throws Exception {
        setCoordinatorStatic("currentHost", host);
        setCoordinatorStatic("currentPort", port);
    }

    private static void setCoordinatorStatic(String name, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static KeepADBEndpointCoordinator.EndpointListener endpointListener() throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField("endpointListener");
        field.setAccessible(true);
        return (KeepADBEndpointCoordinator.EndpointListener) field.get(null);
    }

    private static java.nio.file.Path projectFile(String relative) {
        java.nio.file.Path directory = java.nio.file.Paths.get("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        return directory.resolve(relative);
    }
}
