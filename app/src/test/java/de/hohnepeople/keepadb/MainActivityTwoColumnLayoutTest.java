package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
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

/**
 * Unit tests for #838: two task areas starting at 600 dp with full-width advice/warning banners
 * for the main screen (MainActivity).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTwoColumnLayoutTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADBOnboarding.markCompleted(context);
        KeepADB.resetForTesting(context);
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting(context);
    }

    @Test
    public void standard360dpConfiguration_preservesSingleColumnOrder() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        // View bindings must all exist
        Switch toggle = activity.findViewById(R.id.toggle);
        TextView subtext = activity.findViewById(R.id.toggle_subtext);
        TextView status = activity.findViewById(R.id.status);
        TextView endpoint = activity.findViewById(R.id.endpoint);
        TextView tailscaleStatus = activity.findViewById(R.id.tailscale_status);
        View transportOverview = activity.findViewById(R.id.transport_overview_panel);
        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        TextView keepAliveSubtext = activity.findViewById(R.id.keep_alive_subtext);
        View adviceBanner = activity.findViewById(R.id.advice_banner);
        View webhookPanel = activity.findViewById(R.id.webhook_status_panel);

        assertNotNull(toggle);
        assertNotNull(subtext);
        assertNotNull(status);
        assertNotNull(endpoint);
        assertNotNull(tailscaleStatus);
        assertNotNull(transportOverview);
        assertNotNull(keepAliveToggle);
        assertNotNull(keepAliveSubtext);
        assertNotNull(adviceBanner);
        assertNotNull(webhookPanel);

        // In standard single column layout, main_task_areas_panel does not exist
        assertNull("Standard single-column layout must not have main_task_areas_panel",
                activity.findViewById(R.id.main_task_areas_panel));

        // In standard single-column layout, toggle and keep_alive_toggle share the same parent panel
        assertEquals("Toggle and Keep-Alive share same panel in single column",
                toggle.getParent(), keepAliveToggle.getParent());
    }

    @Test
    @Config(qualifiers = "sw600dp")
    public void sw600dpConfiguration_displaysTwoColumnsWithFullWidthBanners() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        // 1. All view bindings must exist
        Switch toggle = activity.findViewById(R.id.toggle);
        TextView subtext = activity.findViewById(R.id.toggle_subtext);
        TextView status = activity.findViewById(R.id.status);
        TextView endpoint = activity.findViewById(R.id.endpoint);
        TextView tailscaleStatus = activity.findViewById(R.id.tailscale_status);
        View transportOverview = activity.findViewById(R.id.transport_overview_panel);
        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        TextView keepAliveSubtext = activity.findViewById(R.id.keep_alive_subtext);
        View adviceBanner = activity.findViewById(R.id.advice_banner);
        View forcePanel = activity.findViewById(R.id.force_warning_panel);
        View warningLimited = activity.findViewById(R.id.warning_limited);
        View webhookPanel = activity.findViewById(R.id.webhook_status_panel);

        assertNotNull(toggle);
        assertNotNull(subtext);
        assertNotNull(status);
        assertNotNull(endpoint);
        assertNotNull(tailscaleStatus);
        assertNotNull(transportOverview);
        assertNotNull(keepAliveToggle);
        assertNotNull(keepAliveSubtext);
        assertNotNull(adviceBanner);
        assertNotNull(forcePanel);
        assertNotNull(warningLimited);
        assertNotNull(webhookPanel);

        // 2. Two-column panel container must exist and be horizontal
        LinearLayout mainPanel = activity.findViewById(R.id.main_task_areas_panel);
        assertNotNull("sw600dp must have main_task_areas_panel", mainPanel);
        assertEquals("Task areas panel must be horizontal in sw600dp",
                LinearLayout.HORIZONTAL, mainPanel.getOrientation());

        // 3. Left column (Wireless Debugging)
        LinearLayout adbColumn = activity.findViewById(R.id.panel_adb_column);
        assertNotNull("ADB column must exist", adbColumn);
        assertEquals(mainPanel, adbColumn.getParent());
        assertEquals(adbColumn, toggle.getParent());
        assertEquals(adbColumn, subtext.getParent());
        assertEquals(adbColumn, status.getParent());
        assertEquals(adbColumn, endpoint.getParent());
        assertEquals(adbColumn, tailscaleStatus.getParent());
        assertEquals(adbColumn, transportOverview.getParent());

        // 4. Vertical divider
        View divider = activity.findViewById(R.id.task_areas_divider);
        assertNotNull("Divider must exist", divider);
        assertEquals(mainPanel, divider.getParent());

        // 5. Right column (Keep-Alive)
        LinearLayout keepAliveColumn = activity.findViewById(R.id.panel_keep_alive_column);
        assertNotNull("Keep-Alive column must exist", keepAliveColumn);
        assertEquals(mainPanel, keepAliveColumn.getParent());
        assertEquals(keepAliveColumn, keepAliveToggle.getParent());
        assertEquals(keepAliveColumn, keepAliveSubtext.getParent());

        // 6. Child order within task areas panel: Left column -> Divider -> Right column
        int adbIndex = mainPanel.indexOfChild(adbColumn);
        int dividerIndex = mainPanel.indexOfChild(divider);
        int keepAliveIndex = mainPanel.indexOfChild(keepAliveColumn);
        assertTrue("ADB column must precede divider", adbIndex < dividerIndex);
        assertTrue("Divider must precede Keep-Alive column", dividerIndex < keepAliveIndex);

        // 7. Warnings and advice banner must be full-width above the two-column panel
        ViewGroup contentContainer = (ViewGroup) mainPanel.getParent();
        assertNotNull(contentContainer);
        assertEquals("Advice banner and task areas panel must share parent content container",
                contentContainer, adviceBanner.getParent());
        assertEquals("Warning cards and task areas panel must share parent content container",
                contentContainer, warningLimited.getParent());

        int adviceIndex = contentContainer.indexOfChild(adviceBanner);
        int warningIndex = contentContainer.indexOfChild(warningLimited);
        int mainPanelIndex = contentContainer.indexOfChild(mainPanel);
        int webhookIndex = contentContainer.indexOfChild(webhookPanel);

        assertTrue("Warning cards must be above task areas panel", warningIndex < mainPanelIndex);
        assertTrue("Advice banner must be above task areas panel", adviceIndex < mainPanelIndex);
        assertTrue("Advice banner must be directly above task areas panel", adviceIndex == mainPanelIndex - 1);
        assertTrue("Webhook status panel must be below task areas panel", mainPanelIndex < webhookIndex);
    }

    @Test
    @Config(qualifiers = "w600dp-h400dp")
    public void w600dpLandscapePhoneConfiguration_displaysTwoColumns() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        LinearLayout mainPanel = activity.findViewById(R.id.main_task_areas_panel);
        assertNotNull("w600dp landscape phone must have main_task_areas_panel", mainPanel);
        assertEquals(LinearLayout.HORIZONTAL, mainPanel.getOrientation());

        assertNotNull(activity.findViewById(R.id.panel_adb_column));
        assertNotNull(activity.findViewById(R.id.task_areas_divider));
        assertNotNull(activity.findViewById(R.id.panel_keep_alive_column));
        assertNotNull(activity.findViewById(R.id.toggle));
        assertNotNull(activity.findViewById(R.id.keep_alive_toggle));
    }

    @Test
    @Config(qualifiers = "w840dp-h600dp")
    public void w840dpConfiguration_capsContentWidthAndCenters() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        LinearLayout mainPanel = activity.findViewById(R.id.main_task_areas_panel);
        assertNotNull("w840dp must have main_task_areas_panel", mainPanel);

        View contentContainer = activity.findViewById(R.id.main_content_container);
        assertNotNull("main_content_container must exist", contentContainer);

        ViewGroup.LayoutParams params = contentContainer.getLayoutParams();
        int expectedMaxWidth = activity.getResources().getDimensionPixelSize(R.dimen.main_content_max_width);
        assertEquals("Content container width must be capped to main_content_max_width on w840dp",
                expectedMaxWidth, params.width);
    }

    @Test
    @Config(qualifiers = "w1024dp-h600dp")
    public void w1024dpConfiguration_capsContentWidthAndCenters() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        LinearLayout mainPanel = activity.findViewById(R.id.main_task_areas_panel);
        assertNotNull("w1024dp must have main_task_areas_panel", mainPanel);

        View contentContainer = activity.findViewById(R.id.main_content_container);
        assertNotNull("main_content_container must exist", contentContainer);

        ViewGroup.LayoutParams params = contentContainer.getLayoutParams();
        int expectedMaxWidth = activity.getResources().getDimensionPixelSize(R.dimen.main_content_max_width);
        assertEquals("Content container width must be capped to main_content_max_width on w1024dp",
                expectedMaxWidth, params.width);
    }

    @Test
    @Config(qualifiers = "sw600dp")
    public void sw600dpConfiguration_switchSemanticsAndLiveStatusRefreshes() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        Switch toggle = activity.findViewById(R.id.toggle);
        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        TextView status = activity.findViewById(R.id.status);

        assertNotNull(toggle);
        assertNotNull(keepAliveToggle);
        assertNotNull(status);

        // Initial state
        assertFalse(toggle.isChecked());
        assertFalse(keepAliveToggle.isChecked());

        // Toggle Wireless Debugging
        toggle.performClick();
        assertTrue("Toggle must be checked after click", toggle.isChecked());
        assertTrue("Wireless ADB must be enabled in gateway", KeepADB.isEnabled(activity));

        // Toggle Keep-Alive
        keepAliveToggle.performClick();
        assertTrue("Keep-Alive toggle must be checked after click", keepAliveToggle.isChecked());
        assertTrue("Keep-Alive must be enabled in preferences", KeepADBPreferences.isKeepAliveEnabled(activity));

        // Trigger resume lifecycle
        controller.resume();
        assertNotNull(status.getText());
        assertTrue(status.getText().length() > 0);
    }

    @Test
    @Config(qualifiers = "sw600dp", fontScale = 1.3f)
    public void sw600dpConfiguration_supportsFontScale130Percent() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull(activity.findViewById(R.id.toggle));
        assertNotNull(activity.findViewById(R.id.keep_alive_toggle));
        assertNotNull(activity.findViewById(R.id.main_task_areas_panel));
    }

    @Test
    @Config(qualifiers = "sw600dp", fontScale = 2.0f)
    public void sw600dpConfiguration_supportsFontScale200Percent() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull(activity.findViewById(R.id.toggle));
        assertNotNull(activity.findViewById(R.id.keep_alive_toggle));
        assertNotNull(activity.findViewById(R.id.main_task_areas_panel));
        assertNotNull(activity.findViewById(R.id.status));
        assertNotNull(activity.findViewById(R.id.endpoint));
    }

    @Test
    @Config(qualifiers = "w599dp-h400dp")
    public void boundary599dp_staysSingleColumn() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNull("599dp must not use two-column layout",
                activity.findViewById(R.id.main_task_areas_panel));
        assertNotNull(activity.findViewById(R.id.toggle));
        assertNotNull(activity.findViewById(R.id.keep_alive_toggle));
    }

    @Test
    @Config(qualifiers = "w601dp-h400dp")
    public void boundary601dp_usesTwoColumns() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull("601dp must use two-column layout",
                activity.findViewById(R.id.main_task_areas_panel));
        assertNotNull(activity.findViewById(R.id.panel_adb_column));
        assertNotNull(activity.findViewById(R.id.panel_keep_alive_column));
    }

    @Test
    @Config(qualifiers = "sw600dp")
    public void focusTraversalOrder_followsLogicalPath() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        View adviceBanner = activity.findViewById(R.id.advice_banner);
        View adbColumn = activity.findViewById(R.id.panel_adb_column);
        View keepAliveColumn = activity.findViewById(R.id.panel_keep_alive_column);
        View mainPanel = activity.findViewById(R.id.main_task_areas_panel);
        View webhookPanel = activity.findViewById(R.id.webhook_status_panel);

        ViewGroup contentContainer = (ViewGroup) mainPanel.getParent();
        int adviceIndex = contentContainer.indexOfChild(adviceBanner);
        int mainPanelIndex = contentContainer.indexOfChild(mainPanel);
        int webhookIndex = contentContainer.indexOfChild(webhookPanel);

        // Top banners -> Main task areas -> Webhook status
        assertTrue("Top advice banner precedes task areas panel", adviceIndex < mainPanelIndex);
        assertTrue("Task areas panel precedes webhook panel", mainPanelIndex < webhookIndex);

        // Left column precedes right column within task areas
        ViewGroup taskAreasGroup = (ViewGroup) mainPanel;
        int adbColIndex = taskAreasGroup.indexOfChild(adbColumn);
        int keepAliveColIndex = taskAreasGroup.indexOfChild(keepAliveColumn);
        assertTrue("ADB task area precedes Keep-Alive task area", adbColIndex < keepAliveColIndex);
    }
}
