package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
    public void everyRedTitleBarPageHasTheEyeInsideItsHeader() {
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
            assertSame(name + ": the eye is in the title bar", header, eye.getParent());
            assertEquals(name + ": visible", View.VISIBLE, eye.getVisibility());
            assertEquals(name + ": tap target 48dp", Math.round(48 * context.getResources()
                    .getDisplayMetrics().density), eye.getLayoutParams().width);
        }
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
                if (xml.contains("@color/banner_red") && xml.contains("@+id/header_bar")) {
                    redBars++;
                    assertTrue(file.getFileName() + " has a red header bar and needs the eye",
                            xml.contains("@layout/view_privacy_toggle"));
                }
            }
        }
        assertEquals("main, settings, network list", 3, redBars);
        assertTrue(new File(layouts.toFile(), "view_privacy_toggle.xml").isFile());
    }

    // --- one shared state ----------------------------------------------------------------------

    @Test
    public void switchingInTheListViewIsSeenByTheMainViewAndSettings() {
        assertFalse(KeepADBPreferences.isPrivacyModeEnabled(context));
        NetworkListActivity list = openList();
        list.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        assertTrue(KeepADBPreferences.isPrivacyModeEnabled(context));

        assertEquals(context.getString(R.string.privacy_toggle_disable_accessibility),
                Robolectric.buildActivity(MainActivity.class).setup().get()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
        assertEquals(context.getString(R.string.privacy_toggle_disable_accessibility),
                Robolectric.buildActivity(SettingsActivity.class).setup().get()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
    }

    @Test
    public void switchingOnTheMainViewIsSeenByTheListViewAndSettings() {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        main.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        assertTrue(KeepADBPreferences.isPrivacyModeEnabled(context));

        assertEquals(context.getString(R.string.privacy_toggle_disable_accessibility),
                openList()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
        assertEquals(context.getString(R.string.privacy_toggle_disable_accessibility),
                Robolectric.buildActivity(SettingsActivity.class).setup().get()
                        .findViewById(R.id.btn_toggle_privacy_mode).getContentDescription());
    }

    @Test
    public void settingsEyeFlipsTheSameSettingBackAndForth() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        View eye = settings.findViewById(R.id.btn_toggle_privacy_mode);
        eye.performClick();
        assertTrue(KeepADBPreferences.isPrivacyModeEnabled(context));
        assertEquals(context.getString(R.string.privacy_toggle_disable_accessibility),
                eye.getContentDescription());
        eye.performClick();
        assertFalse(KeepADBPreferences.isPrivacyModeEnabled(context));
        assertEquals(context.getString(R.string.privacy_toggle_enable_accessibility),
                eye.getContentDescription());
    }

    // --- immediate redraw ----------------------------------------------------------------------

    @Test
    public void theListViewMasksAndUnmasksAtOnceWithoutReopening() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        NetworkListActivity list = openList();
        assertTrue(shown(list).contains("Cafe-WLAN"));

        list.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        ShadowLooper.idleMainLooper();
        String masked = shown(list);
        assertFalse("Name is hidden right after the tap: " + masked, masked.contains("Cafe-WLAN"));
        assertFalse(masked.contains("HomeMesh"));
        assertFalse(masked.contains("EE:02"));

        list.findViewById(R.id.btn_toggle_privacy_mode).performClick();
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

        settings.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse("Settings hides the name right after the tap: " + shown(settings),
                shown(settings).contains("HomeMesh"));
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
