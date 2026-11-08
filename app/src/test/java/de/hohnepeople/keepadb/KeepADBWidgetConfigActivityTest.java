package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

import de.hohnepeople.keepadb.KeepADBWidgetConfig.Action;

/** #912: a placed widget can be configured and reconfigured; only its own entry changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetConfigActivityTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private Context context;
    private ShadowAppWidgetManager widgets;
    private int widgetId;
    private int otherId;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBWidgetConfig.clearAll(context);
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        // Synchronization on: only then "Report now" has a place in the widget.
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://register.example/register/dev1");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        widgets = shadowOf(AppWidgetManager.getInstance(context));
        widgetId = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        otherId = widgets.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBWidgetConfig.clearAll(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private ActivityController<KeepADBWidgetConfigActivity> open(int id) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setClassName(context, KeepADBWidgetConfigActivity.class.getName());
        if (id != 0) intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return Robolectric.buildActivity(KeepADBWidgetConfigActivity.class, intent).setup();
    }

    private static RadioButton primaryChoice(Activity activity, Action action) {
        RadioGroup group = activity.findViewById(R.id.widget_config_primary);
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getTag() == action) return (RadioButton) group.getChildAt(i);
        }
        throw new AssertionError("no choice for " + action);
    }

    private static View orderButton(Activity activity, int row, int button) {
        LinearLayout rows = activity.findViewById(R.id.widget_config_order_rows);
        return ((ViewGroup) rows.getChildAt(row)).getChildAt(button);
    }

    private static final int UP = 1;
    private static final int DOWN = 2;

    @Test
    public void aNewWidgetStartsFromTheDefaults() {
        Activity activity = open(widgetId).get();
        assertTrue(primaryChoice(activity, Action.ADB).isChecked());
        assertFalse(((Switch) activity.findViewById(R.id.widget_config_hide_details)).isChecked());
        assertFalse("the first cannot move up", orderButton(activity, 0, UP).isEnabled());
        assertFalse("the last cannot move down", orderButton(activity, 2, DOWN).isEnabled());
        assertTrue(orderButton(activity, 1, UP).isEnabled());
    }

    @Test
    public void savingStoresThisWidgetsChoicesReturnsOkAndRendersTheWidgetAgain() {
        ActivityController<KeepADBWidgetConfigActivity> controller = open(widgetId);
        Activity activity = controller.get();

        primaryChoice(activity, Action.WEBHOOK_SYNC).setChecked(true);
        // Move the synchronization (3rd) to the top: up, up.
        orderButton(activity, 2, UP).performClick();
        orderButton(activity, 1, UP).performClick();
        ((Switch) activity.findViewById(R.id.widget_config_hide_details)).setChecked(true);
        activity.findViewById(R.id.widget_config_save).performClick();

        KeepADBWidgetConfig stored = KeepADBWidgetConfig.load(context, widgetId);
        assertEquals(Action.WEBHOOK_SYNC, stored.primary);
        assertEquals(Arrays.asList(Action.WEBHOOK_SYNC, Action.ADB, Action.KEEP_ALIVE),
                stored.order);
        assertTrue(stored.hideDetails);
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
        assertEquals(widgetId, shadowOf(activity).getResultIntent()
                .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
        assertTrue(activity.isFinishing());

        shadowOf(Looper.getMainLooper()).idle();
        View rendered = widgets.getViewFor(widgetId);
        assertEquals("the widget shows the new main action",
                java.util.Arrays.asList(Action.WEBHOOK_SYNC),
                KeepADBWidgetTestSupport.actions(context, rendered).subList(0, 1));
    }

    @Test
    public void otherWidgetsAndTheAppSettingsAreUntouched() {
        KeepADBWidgetConfig other = KeepADBWidgetConfig.of(Action.WEBHOOK_SYNC,
                Arrays.asList(Action.WEBHOOK_SYNC, Action.ADB, Action.KEEP_ALIVE), true);
        KeepADBWidgetConfig.save(context, otherId, other);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        java.util.TreeMap<String, ?> before = new java.util.TreeMap<>(
                context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).getAll());

        Activity activity = open(widgetId).get();
        primaryChoice(activity, Action.KEEP_ALIVE).setChecked(true);
        activity.findViewById(R.id.widget_config_save).performClick();

        KeepADBWidgetConfig stillOther = KeepADBWidgetConfig.load(context, otherId);
        assertEquals(other.order, stillOther.order);
        assertEquals(Action.WEBHOOK_SYNC, stillOther.primary);
        assertTrue(stillOther.hideDetails);
        assertEquals(before, new java.util.TreeMap<>(
                context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).getAll()));
    }

    @Test
    public void reconfiguringShowsAndChangesThePreviousChoices() {
        KeepADBWidgetConfig.save(context, widgetId, KeepADBWidgetConfig.of(Action.WEBHOOK_SYNC,
                Arrays.asList(Action.KEEP_ALIVE, Action.WEBHOOK_SYNC, Action.ADB), true));

        Activity activity = open(widgetId).get();
        assertTrue(primaryChoice(activity, Action.WEBHOOK_SYNC).isChecked());
        assertTrue(((Switch) activity.findViewById(R.id.widget_config_hide_details)).isChecked());
        assertFalse(orderButton(activity, 0, UP).isEnabled());

        orderButton(activity, 0, DOWN).performClick();
        activity.findViewById(R.id.widget_config_save).performClick();
        assertEquals(Arrays.asList(Action.WEBHOOK_SYNC, Action.KEEP_ALIVE, Action.ADB),
                KeepADBWidgetConfig.load(context, widgetId).order);
    }

    @Test
    public void goingBackChangesNothingAndReportsCancelled() {
        KeepADBWidgetConfig before = KeepADBWidgetConfig.load(context, widgetId);
        Activity activity = open(widgetId).get();
        primaryChoice(activity, Action.WEBHOOK_SYNC).setChecked(true);
        orderButton(activity, 1, UP).performClick();
        activity.findViewById(R.id.btn_back).performClick();

        assertFalse(KeepADBWidgetConfig.isStored(context, widgetId));
        assertEquals(before.order, KeepADBWidgetConfig.load(context, widgetId).order);
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertEquals("the id is echoed back as the contract wants", widgetId,
                shadowOf(activity).getResultIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void anIdThatIsNotAWidgetOfThisAppFinishesWithoutWritingAnything() {
        for (int id : new int[] {0, 99999}) {
            ActivityController<KeepADBWidgetConfigActivity> controller = open(id);
            assertTrue("id " + id, controller.get().isFinishing());
            assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).getResultCode());
        }
        assertFalse(KeepADBWidgetConfig.isStored(context, 99999));
        assertFalse(KeepADBWidgetConfig.isStored(context, 0));
    }

    @Test
    public void theGlobalPrivacySwitchIsExplainedNextToThePerWidgetOption() {
        Activity off = open(widgetId).get();
        assertEquals(View.GONE, off.findViewById(R.id.widget_config_hide_details_note).getVisibility());

        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        Activity on = open(widgetId).get();
        assertEquals(View.VISIBLE, on.findViewById(R.id.widget_config_hide_details_note).getVisibility());
        assertNotNull(on.findViewById(R.id.widget_config_hide_details));
    }

    @Test
    public void theSaveButtonStaysOnTheScreenAboveTheNavigationBar() {
        Activity activity = open(widgetId).get();
        View save = activity.findViewById(R.id.widget_config_save);
        View body = activity.findViewById(R.id.widget_config_body);
        View scroll = activity.findViewById(R.id.widget_config_scroll);
        assertEquals("not inside the scrolling part, so it cannot scroll away",
                body, save.getParent());
        assertEquals(body, scroll.getParent());

        // The navigation bar's room goes to the body that holds the button.
        android.graphics.Insets bar = android.graphics.Insets.of(0, 0, 0, 120);
        body.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.systemBars(), bar).build());
        int margin = ((android.view.ViewGroup.MarginLayoutParams) body.getLayoutParams()).bottomMargin;
        assertTrue("bottom margin " + margin, margin >= 120);
    }

    @Test
    public void everyMoveButtonIsNamedForTalkBackAndBigEnough() {
        Activity activity = open(widgetId).get();
        LinearLayout rows = activity.findViewById(R.id.widget_config_order_rows);
        for (int row = 0; row < rows.getChildCount(); row++) {
            for (int button : new int[] {UP, DOWN}) {
                View view = orderButton(activity, row, button);
                assertNotNull(view.getContentDescription());
                assertTrue(view.getContentDescription().length() > 0);
                assertTrue(view.getLayoutParams().height >= 48);
                assertTrue(((android.widget.Button) view).getMinWidth() >= 48);
            }
        }
    }
}
