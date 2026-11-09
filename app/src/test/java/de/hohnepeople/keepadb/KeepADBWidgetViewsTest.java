package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.actions;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.applyAndMeasure;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.description;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.hero;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.label;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.port;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.state;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.statusDescription;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.statusText;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.statusVisible;
import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.tiles;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.hohnepeople.keepadb.KeepADBWidgetConfig.Action;
import de.hohnepeople.keepadb.KeepADBWidgetLayout.SizeClass;

/**
 * #912: what the control widget shows per size class, built from the shared projection: the hero
 * and the other controls, their shapes, the words and symbols that carry each state, the port rules,
 * the status line, the privacy precedence and the touch targets. The views are the real RemoteViews
 * applied and laid out, not a source scan.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetViewsTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1?token=s3cret";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;
    private static final int WIDGET_ID = 7;

    private final AtomicBoolean wifi = new AtomicBoolean(true);
    private Context context;
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBWidgetConfig.clearAll(context);
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBReportHistory.clear(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(wifi::get);
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport());
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBWidgetConfig.clearAll(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // -- helpers -------------------------------------------------------------------------------

    /** A realistic room for a class: the smallest the launcher gives it, at normal font size. */
    private static int[] room(SizeClass sizeClass) {
        switch (sizeClass) {
            case ONE:
                return new int[] {68, 89};
            case HERO:
                return new int[] {140, 72};
            case ROW1:
                return new int[] {144, 89};
            case ROW2:
                return new int[] {220, 89};
            case STACK:
                return new int[] {114, 150};
            case STRIP:
                return new int[] {296, 89};
            case GRID:
                return new int[] {144, 193};
            case PANEL:
            default:
                return new int[] {296, 193};
        }
    }

    private View render(KeepADBWidgetConfig config, SizeClass sizeClass) {
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, config.hideDetails);
        int[] room = room(sizeClass);
        return applyAndMeasure(context,
                KeepADBWidgetViews.forSize(context, WIDGET_ID, config, projection, sizeClass),
                room[0], room[1]);
    }

    private View render(SizeClass sizeClass) {
        return render(KeepADBWidgetConfig.defaults(), sizeClass);
    }

    private void setAdb(boolean on) {
        gateway = new KeepADBFakeSettingsGateway(on);
        KeepADB.setGatewayForTesting(gateway);
    }

    private String name(int resId, Object... args) {
        return context.getString(resId, args);
    }

    private static KeepADBWidgetConfig config(Action primary, Action... order) {
        return KeepADBWidgetConfig.of(primary, Arrays.asList(order), false);
    }

    private static String ico(View tile) {
        // The square button is one ImageView: the tile itself carries the symbol.
        android.widget.ImageView icon = tile instanceof android.widget.ImageView
                ? (android.widget.ImageView) tile
                : (android.widget.ImageView) tile.findViewById(R.id.widget_tile_icon);
        return icon.getDrawable() == null ? "none" : "icon";
    }

    // -- controls per size class ---------------------------------------------------------------

    @Test
    public void eachSizeClassShowsItsControlsHeroFirst() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        assertEquals(Arrays.asList(Action.ADB), actions(context, render(defaults, SizeClass.ONE)));
        assertEquals(Arrays.asList(Action.ADB), actions(context, render(defaults, SizeClass.HERO)));
        assertEquals(Arrays.asList(Action.ADB, Action.KEEP_ALIVE),
                actions(context, render(defaults, SizeClass.ROW1)));
        for (SizeClass sizeClass : new SizeClass[] {SizeClass.ROW2, SizeClass.STACK, SizeClass.STRIP,
                SizeClass.GRID, SizeClass.PANEL}) {
            assertEquals(sizeClass.name(),
                    Arrays.asList(Action.ADB, Action.KEEP_ALIVE, Action.WEBHOOK_SYNC),
                    actions(context, render(defaults, sizeClass)));
        }
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = render(defaults, sizeClass);
            assertEquals(sizeClass.name() + " control count", sizeClass.actionCount(),
                    tiles(widget).size());
            assertEquals(sizeClass.name() + " status line", sizeClass == SizeClass.PANEL,
                    statusVisible(widget));
            assertEquals(sizeClass.name() + " settings gear", sizeClass == SizeClass.PANEL,
                    widget.findViewById(R.id.widget_customize) != null);
        }
    }

    @Test
    public void everyClassHasItsOwnShapes() {
        // 1x1: symbol, the short label (ADB, not translated) and a short state word; no port.
        View one = hero(render(SizeClass.ONE));
        assertEquals("ADB", label(one));
        assertEquals(name(R.string.widget_short_on), state(one));
        assertNull("never a port in the smallest shape", port(one));

        // Compact hero: only the port under the symbol.
        View compact = hero(render(SizeClass.ROW1));
        assertNull(label(compact));
        assertNull("the port replaces the state word", state(compact));
        assertEquals(":" + PORT, port(compact));

        // 2x2: label in a row with the symbol, the port large; no state word beside the port.
        View m = hero(render(SizeClass.GRID));
        assertEquals(name(R.string.widget_action_adb), label(m));
        assertEquals(":" + PORT, port(m));
        assertNull(state(m));

        // 4x2: label, the full state word and the port.
        View l = hero(render(SizeClass.PANEL));
        assertEquals(name(R.string.widget_action_adb), label(l));
        assertEquals(name(R.string.tile_state_connected), state(l));
        assertEquals(":" + PORT, port(l));

        // The other controls: square buttons (no text), upright tiles, pills.
        for (SizeClass sizeClass : new SizeClass[] {SizeClass.ROW2, SizeClass.STACK}) {
            View square = tiles(render(sizeClass)).get(1);
            assertNull(sizeClass + " square button has no label", label(square));
            assertNull(sizeClass + " square button has no state word", state(square));
        }
        for (View tile : tiles(render(SizeClass.STRIP)).subList(1, 3)) {
            assertNotNull("upright tile has a label", label(tile));
            assertNotNull("and a state word", state(tile));
        }
        for (SizeClass sizeClass : new SizeClass[] {SizeClass.GRID, SizeClass.PANEL}) {
            for (View tile : tiles(render(sizeClass)).subList(1, 3)) {
                assertNotNull(sizeClass + " pill has a label", label(tile));
                assertNotNull(sizeClass + " pill has a state word", state(tile));
            }
        }
    }

    @Test
    public void theMainActionIsTheHeroAndTheOrderIsTheSequenceOfTheOthers() {
        KeepADBWidgetConfig config = config(Action.WEBHOOK_SYNC,
                Action.KEEP_ALIVE, Action.ADB, Action.WEBHOOK_SYNC);
        assertEquals(Arrays.asList(Action.WEBHOOK_SYNC), actions(context, render(config, SizeClass.ONE)));
        assertEquals(Arrays.asList(Action.WEBHOOK_SYNC, Action.KEEP_ALIVE),
                actions(context, render(config, SizeClass.ROW1)));
        for (SizeClass sizeClass : new SizeClass[] {SizeClass.ROW2, SizeClass.STRIP, SizeClass.GRID,
                SizeClass.PANEL, SizeClass.STACK}) {
            assertEquals(sizeClass.name(),
                    Arrays.asList(Action.WEBHOOK_SYNC, Action.KEEP_ALIVE, Action.ADB),
                    actions(context, render(config, sizeClass)));
        }
        // The hero keeps its shape whichever control it is: the sync control as the hero in 4x2
        // shows its label, state word and no port (it has none).
        View l = hero(render(config, SizeClass.PANEL));
        assertEquals(name(R.string.widget_action_sync), label(l));
        assertEquals(name(R.string.widget_state_on), state(l));
        assertNull(port(l));
        // As the hero of the compact shapes, the state word stands where the port would be.
        assertEquals(name(R.string.widget_state_on), state(hero(render(config, SizeClass.ROW1))));
        assertEquals(name(R.string.widget_state_on), state(hero(render(config, SizeClass.GRID))));
    }

    @Test
    public void growingOnlyAddsControlsAndNeverReordersThem() {
        KeepADBWidgetConfig config = config(Action.ADB, Action.WEBHOOK_SYNC, Action.KEEP_ALIVE, Action.ADB);
        assertEquals(Arrays.asList(Action.ADB, Action.WEBHOOK_SYNC),
                actions(context, render(config, SizeClass.ROW1)));
        assertEquals(Arrays.asList(Action.ADB, Action.WEBHOOK_SYNC, Action.KEEP_ALIVE),
                actions(context, render(config, SizeClass.ROW2)));
        assertEquals(Arrays.asList(Action.ADB, Action.WEBHOOK_SYNC, Action.KEEP_ALIVE),
                actions(context, render(config, SizeClass.PANEL)));
    }

    /** Report now left the widget in #912: no size, no state and no configuration brings it back. */
    @Test
    public void theWidgetNeverOffersReportNowAndAlwaysKeepsItsThreeControls() {
        String report = name(R.string.webhook_alert_action_report_now);
        KeepADBWidgetConfig legacy = KeepADBWidgetConfig.decode(
                "report_now;report_now,webhook_sync,keep_alive,adb;0");
        for (boolean syncOn : new boolean[] {true, false}) {
            KeepADBPreferences.setRegisterWebhookEnabled(context, syncOn);
            for (SizeClass sizeClass : SizeClass.values()) {
                assertFalse(sizeClass.name(), KeepADBWidgetTestSupport.allText(
                        render(KeepADBWidgetConfig.defaults(), sizeClass)).contains(report));
                assertFalse(sizeClass.name(), KeepADBWidgetTestSupport.allText(
                        render(legacy, sizeClass)).contains(report));
            }
            assertEquals(3, tiles(render(SizeClass.STRIP)).size());
        }
        assertEquals("the legacy main action fell back to ADB", Arrays.asList(Action.ADB),
                actions(context, render(legacy, SizeClass.ONE)));
    }

    // -- states in words and symbols -------------------------------------------------------------

    @Test
    public void theWirelessDebuggingControlSaysConnectedOffWaitingSearchingAndSwitching() {
        KeepADBWidgetContent.Tile connected = tile(Action.ADB);
        assertEquals(name(R.string.tile_state_connected), connected.state);
        assertEquals(name(R.string.widget_short_on), connected.shortState);
        assertEquals(KeepADBWidgetContent.Style.ON, connected.style);
        assertEquals(R.drawable.ic_widget_adb, connected.iconRes);
        assertEquals(":" + PORT, connected.port);

        setAdb(false);
        KeepADBWidgetContent.Tile off = tile(Action.ADB);
        assertEquals(name(R.string.tile_state_off), off.state);
        assertEquals(name(R.string.widget_short_off), off.shortState);
        assertEquals(KeepADBWidgetContent.Style.OFF, off.style);
        assertEquals("the outlined variant, not only another color", R.drawable.ic_widget_adb_off,
                off.iconRes);
        assertNull(off.port);

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertEquals(name(R.string.widget_state_off_waiting), tile(Action.ADB).state);

        // On, Wi-Fi up, no endpoint yet: searching, a busy fill and the symbol with three dots.
        setAdb(true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        KeepADBWidgetContent.Tile searching = tile(Action.ADB);
        assertEquals(name(R.string.tile_state_searching), searching.state);
        assertEquals(name(R.string.widget_short_scan), searching.shortState);
        assertEquals(KeepADBWidgetContent.Style.BUSY, searching.style);
        assertEquals(R.drawable.ic_widget_adb_busy, searching.iconRes);
        assertNull(searching.port);
    }

    @Test
    public void aPendingToggleIsVisibleAsSwitching() {
        setAdb(false);
        KeepADB.setSchedulerForTesting(new KeepADBFakeScheduler());
        KeepADB.setEnabled(context, true, "content_observer");
        assertTrue(KeepADB.isTogglePending());

        View widget = render(SizeClass.PANEL);
        assertEquals(name(R.string.state_pending), state(hero(widget)));
        assertEquals(KeepADBWidgetContent.Style.BUSY, tile(Action.ADB).style);
        assertTrue(statusText(widget).startsWith(name(R.string.status_pending)));
    }

    @Test
    public void aMissingPermissionIsVisibleOnTheControlAndInTheStatusLine() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);

        View widget = render(SizeClass.PANEL);
        assertEquals(name(R.string.tile_state_permission_missing), state(hero(widget)));
        assertEquals(KeepADBWidgetContent.Style.ATTENTION, tile(Action.ADB).style);
        assertEquals(name(R.string.widget_state_setup), tile(Action.ADB).shortState);
        assertNull("no port without a connection", port(hero(widget)));
        assertTrue(statusText(widget).startsWith(name(R.string.status_permission_missing)));
    }

    @Test
    public void keepAliveSaysOnWaitingPausedAndOffWithADifferentSymbolWhenItIsNotRunning() {
        assertEquals(name(R.string.tile_state_off), tile(Action.KEEP_ALIVE).state);
        assertEquals(R.drawable.ic_widget_keep_alive_off, tile(Action.KEEP_ALIVE).iconRes);

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertEquals(name(R.string.widget_state_on), tile(Action.KEEP_ALIVE).state);
        assertEquals(KeepADBWidgetContent.Style.ON, tile(Action.KEEP_ALIVE).style);
        assertEquals(R.drawable.ic_widget_keep_alive, tile(Action.KEEP_ALIVE).iconRes);

        // Off but resolving by itself (no Wi-Fi yet): waiting.
        setAdb(false);
        wifi.set(false);
        assertEquals(name(R.string.widget_state_waiting), tile(Action.KEEP_ALIVE).state);

        // The last explicit intent was off: paused until the user acts, in words and attention fill.
        wifi.set(true);
        KeepADB.recordExplicitIntent(context, false);
        assertEquals(name(R.string.widget_state_paused), tile(Action.KEEP_ALIVE).state);
        assertEquals(KeepADBWidgetContent.Style.ATTENTION, tile(Action.KEEP_ALIVE).style);
    }

    @Test
    public void theSyncControlFollowsTheSharedActionState() {
        KeepADBWidgetContent.Tile on = tile(Action.WEBHOOK_SYNC);
        assertEquals(name(R.string.widget_state_on), on.state);
        assertEquals(R.drawable.ic_widget_sync, on.iconRes);
        assertEquals(KeepADBWidgetContent.Click.SYNC_DISABLE, on.click);

        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBWidgetContent.Tile off = tile(Action.WEBHOOK_SYNC);
        assertEquals(name(R.string.tile_state_off), off.state);
        assertEquals(R.drawable.ic_widget_sync_off, off.iconRes);
        assertEquals(KeepADBWidgetContent.Click.SYNC_ENABLE, off.click);

        // No destination at all: the control leads to the setup, in words, with the marked symbol.
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        KeepADBWidgetContent.Tile setup = tile(Action.WEBHOOK_SYNC);
        assertEquals(name(R.string.widget_state_setup), setup.state);
        assertEquals(R.drawable.ic_widget_sync_warn, setup.iconRes);
        assertEquals(KeepADBWidgetContent.Style.ATTENTION, setup.style);
        assertEquals(KeepADBWidgetContent.Click.SETUP_WEBHOOK, setup.click);
    }

    @Test
    public void everyStateHasAWordAndTheFillAloneNeverCarriesIt() {
        // Each state pairs a fill with a symbol variant and a word, so no state depends on color.
        setAdb(false);
        for (Action action : Action.values()) {
            KeepADBWidgetContent.Tile tile = tile(action);
            assertTrue(action + " has a state word", tile.state.length() > 0);
            assertTrue(action + " has a short word", tile.shortState.length() > 0);
            assertTrue(action + " has a symbol", tile.iconRes != 0);
        }
        java.util.Set<Integer> icons = new java.util.HashSet<>();
        for (Action action : Action.values()) icons.add(tile(action).iconRes);
        assertEquals("three different symbols", 3, icons.size());
    }

    private KeepADBWidgetContent.Tile tile(Action action) {
        return KeepADBWidgetContent.tile(context, action, KeepADBWidgetState.project(context));
    }

    // -- the port ------------------------------------------------------------------------------

    @Test
    public void thePortIsShownFromTheCompactHeroUpAndNeverInTheSmallestShape() {
        for (SizeClass sizeClass : SizeClass.values()) {
            String shown = port(hero(render(sizeClass)));
            if (sizeClass == SizeClass.ONE) {
                assertNull("no port in the smallest shape", shown);
            } else {
                assertEquals(sizeClass.name(), ":" + PORT, shown);
            }
        }
        // The state word stands in for it in the compact shapes while there is no port.
        setAdb(false);
        for (SizeClass sizeClass : new SizeClass[] {SizeClass.HERO, SizeClass.ROW2, SizeClass.STACK,
                SizeClass.STRIP, SizeClass.GRID}) {
            View hero = hero(render(sizeClass));
            assertNull(sizeClass.name(), port(hero));
            assertNotNull(sizeClass.name() + " shows a word instead", state(hero));
        }
    }

    @Test
    public void theGlobalSwitchAndThePerWidgetOptionShowDotsInsteadOfThePort() {
        KeepADBWidgetConfig hidden = KeepADBWidgetConfig.of(Action.ADB, null, true);
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = render(hidden, sizeClass);
            String shown = port(hero(widget));
            if (sizeClass == SizeClass.ONE) {
                assertNull(shown);
            } else {
                assertEquals(sizeClass.name(), "•••••", shown);
            }
            assertNoEndpoint(widget);
        }
        assertEquals(name(R.string.widget_status_details_hidden) + " · "
                        + name(R.string.widget_webhook_not_current),
                statusText(render(hidden, SizeClass.PANEL)));
    }

    // -- TalkBack ------------------------------------------------------------------------------

    @Test
    public void everyControlSpeaksNameStateAndPortInFullWhateverIsVisible() {
        String adb = name(R.string.toggle_label);
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = render(sizeClass);
            List<View> tiles = tiles(widget);
            for (View tile : tiles) {
                assertTrue(sizeClass + " " + description(tile), description(tile).contains(": "));
                assertTrue(description(tile).length() > 0);
            }
            assertEquals(sizeClass.name(), name(R.string.widget_description_port_format,
                            name(R.string.widget_description_format, adb,
                                    name(R.string.tile_state_connected)), PORT),
                    description(tiles.get(0)));
        }
        // A square button has no text at all, but still names the control and its state.
        View square = tiles(render(SizeClass.ROW1)).get(1);
        assertEquals(name(R.string.widget_description_format,
                name(R.string.settings_section_keep_alive), name(R.string.tile_state_off)),
                description(square));
        // With the details withheld the port is not spoken either.
        String hidden = description(hero(render(KeepADBWidgetConfig.of(Action.ADB, null, true),
                SizeClass.PANEL)));
        assertEquals(name(R.string.widget_description_format, adb, name(R.string.tile_state_connected)),
                hidden);
    }

    // -- the status line -------------------------------------------------------------------------

    @Test
    public void theStatusLineIsOneTextWithTheEndpointAndTheWebhookState() {
        View widget = render(SizeClass.PANEL);
        assertEquals(HOST + ":" + PORT + " · " + name(R.string.widget_webhook_not_current),
                statusText(widget));
        assertEquals(HOST + ":" + PORT + ". " + name(R.string.widget_webhook_not_current),
                statusDescription(widget));
        android.widget.TextView text = widget.findViewById(R.id.widget_status_text);
        assertTrue("two lines at most, as high as its text", text.getMaxLines() <= 2);
        assertEquals(android.text.TextUtils.TruncateAt.END, text.getEllipsize());
        // The endpoint part is stressed, the webhook part keeps the muted color.
        android.text.Spanned spanned = (android.text.Spanned) text.getText();
        assertTrue(spanned.getSpans(0, HOST.length() + 1 + String.valueOf(PORT).length(),
                android.text.style.StyleSpan.class).length > 0);
    }

    @Test
    public void aFailedReportIsVisibleAndDeliveryIsClaimedOnlyForTheCurrentEndpoint() {
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        assertTrue(statusText(render(SizeClass.PANEL)).endsWith(name(R.string.notification_text_webhook_failed)));

        // A success for exactly the current endpoint and destination: current.
        KeepADBPreferences.setWebhookLastReportedUrl(context, KeepADBPreferences.getRegisterWebhookUrl(context));
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, KeepADBEndpoint.formatEndpoint(HOST, PORT));
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);
        assertTrue(statusText(render(SizeClass.PANEL)).endsWith(name(R.string.widget_webhook_current)));

        // With synchronization off the line is about that, not about an earlier report.
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertTrue(statusText(render(SizeClass.PANEL)).endsWith(name(R.string.widget_webhook_sync_off)));
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);

        // An old completion must not mark a newer endpoint delivered.
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT + 1, 2L);
        assertTrue(statusText(render(SizeClass.PANEL)).endsWith(name(R.string.widget_webhook_not_current)));

        // Without a verified endpoint there is nothing to report.
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        assertTrue(statusText(render(SizeClass.PANEL)).endsWith(name(R.string.widget_webhook_no_endpoint)));
    }

    @Test
    public void theStatusLineNamesTheReasonsThatCanBeSolved() {
        setAdb(false);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);

        // No Wi-Fi yet: Keep-Alive is waiting for the network.
        wifi.set(false);
        assertTrue(statusText(render(SizeClass.PANEL))
                .startsWith(name(R.string.status_off_keep_alive_waiting)));

        // Wi-Fi up but the network cannot be read (no location access): paused, with the reason.
        wifi.set(true);
        assertTrue(statusText(render(SizeClass.PANEL))
                .startsWith(name(R.string.status_off_keep_alive_blocked_identity_unavailable)));

        // No destination: the webhook part says it is not set up.
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertTrue(statusText(render(SizeClass.PANEL)).endsWith(name(R.string.widget_webhook_unconfigured)));
    }

    // -- privacy -------------------------------------------------------------------------------

    @Test
    public void theEndpointIsShownOnlyWhenNeitherTheGlobalSwitchNorTheWidgetHidesIt() {
        View open = render(SizeClass.PANEL);
        assertTrue(statusText(open).startsWith(HOST + ":" + PORT));

        // The per-widget option hides more: "Details hidden", no address, no port.
        KeepADBWidgetConfig hidden = KeepADBWidgetConfig.of(Action.ADB, null, true);
        View closed = render(hidden, SizeClass.PANEL);
        assertNoEndpoint(closed);
        assertTrue(statusText(closed).startsWith(name(R.string.widget_status_details_hidden)));
        assertEquals("the state itself stays readable", name(R.string.tile_state_connected),
                state(hero(closed)));
    }

    @Test
    public void theGlobalWidgetPrivacySwitchTakesPrecedenceOverAVisibleWidget() {
        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);

        // The widget itself says "show": the switch still wins.
        KeepADBWidgetConfig visible = KeepADBWidgetConfig.of(Action.ADB, null, false);
        for (SizeClass sizeClass : SizeClass.values()) {
            assertNoEndpoint(render(visible, sizeClass));
        }
        // Both on: still hidden, and the state itself stays readable.
        View both = render(KeepADBWidgetConfig.of(Action.ADB, null, true), SizeClass.PANEL);
        assertNoEndpoint(both);
        assertEquals(name(R.string.tile_state_connected), state(hero(both)));
        assertTrue(statusText(both).startsWith(name(R.string.widget_status_details_hidden)));
    }

    @Test
    public void theWebhookUrlIsNeverOnTheWidgetInAnySizeOrPrivacyState() {
        for (boolean global : new boolean[] {false, true}) {
            KeepADBPreferences.setWidgetPrivacyEnabled(context, global);
            for (boolean widgetHides : new boolean[] {false, true}) {
                KeepADBWidgetConfig config = KeepADBWidgetConfig.of(Action.ADB, null, widgetHides);
                for (SizeClass sizeClass : SizeClass.values()) {
                    String text = KeepADBWidgetTestSupport.allText(render(config, sizeClass));
                    assertFalse(text, text.contains("register.example"));
                    assertFalse(text, text.contains("s3cret"));
                    assertFalse(text, text.contains("dev1"));
                }
            }
        }
    }

    private void assertNoEndpoint(View widget) {
        String text = KeepADBWidgetTestSupport.allText(widget);
        assertFalse(text, text.contains(HOST));
        assertFalse(text, text.contains(String.valueOf(PORT)));
        assertFalse(text, text.contains(name(R.string.endpoint_format, HOST, PORT)));
    }

    // -- text fitting and touch targets ------------------------------------------------------------

    @Test
    public void textsAreOneShrinkingLineUpTo85PercentAndThePortNeverWraps() {
        float density = context.getResources().getDisplayMetrics().scaledDensity;
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = render(sizeClass);
            for (View tile : tiles(widget)) {
                for (int id : new int[] {R.id.widget_tile_label, R.id.widget_tile_state,
                        R.id.widget_tile_port}) {
                    View view = tile.findViewById(id);
                    if (!(view instanceof android.widget.TextView)) continue;
                    android.widget.TextView text = (android.widget.TextView) view;
                    assertEquals(sizeClass + " " + id, 1, text.getMaxLines());
                    assertEquals(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM,
                            text.getAutoSizeTextType());
                    assertEquals("never cut with an ellipsis", null, text.getEllipsize());
                    float max = text.getAutoSizeMaxTextSize();
                    float min = text.getAutoSizeMinTextSize();
                    if (id == R.id.widget_tile_port) {
                        assertTrue("the port may shrink further than words, but never wraps",
                                min >= 0.45f * max);
                    } else {
                        assertTrue(sizeClass + " " + id + " min " + min + " max " + max,
                                min >= 0.8f * max && min <= 0.92f * max);
                    }
                    assertTrue(max <= 22 * density + 0.5f);
                }
            }
        }
    }

    /**
     * The geometry budget without text: Robolectric's text metrics are not a device's, so the tests
     * measure the structure the layout guarantees (control and button sizes at the smallest room
     * of a class) and leave the text out.
     */
    private View renderWithoutText(SizeClass sizeClass) {
        View widget = render(sizeClass);
        blank(widget);
        int[] room = room(sizeClass);
        KeepADBWidgetTestSupport.measure(widget, room[0], room[1]);
        return widget;
    }

    private static void blank(View view) {
        if (view instanceof android.widget.TextView) view.setVisibility(View.GONE);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) blank(group.getChildAt(i));
        }
    }

    @Test
    public void everyControlAndButtonKeepsAtLeast48DpInTheRoomOfItsClass() {
        int minimum = 48;
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = renderWithoutText(sizeClass);
            for (View tile : tiles(widget)) {
                assertTrue(sizeClass + " control width " + tile.getMeasuredWidth(),
                        tile.getMeasuredWidth() >= minimum);
                assertTrue(sizeClass + " control height " + tile.getMeasuredHeight(),
                        tile.getMeasuredHeight() >= minimum);
            }
            if (sizeClass == SizeClass.PANEL) {
                View gear = widget.findViewById(R.id.widget_customize);
                assertEquals(View.VISIBLE, gear.getVisibility());
                assertTrue(gear.getMeasuredWidth() >= minimum);
                assertTrue(gear.getMeasuredHeight() >= minimum);
                assertTrue(widget.findViewById(R.id.widget_status_row).getMeasuredHeight() >= minimum);
            }
        }
    }

    @Test
    public void tilesGrowWithTheHeightAndTheStatusLineStaysAsHighAsItsText() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, false);
        View low = applyAndMeasure(context, KeepADBWidgetViews.forSize(context, WIDGET_ID, defaults,
                projection, SizeClass.PANEL), 304, 160);
        View high = applyAndMeasure(context, KeepADBWidgetViews.forSize(context, WIDGET_ID, defaults,
                projection, SizeClass.PANEL), 304, 300);
        assertTrue("the pills grow with the height", tiles(high).get(1).getMeasuredHeight()
                > tiles(low).get(1).getMeasuredHeight());
        assertTrue("the hero grows with it", hero(high).getMeasuredHeight() > hero(low).getMeasuredHeight());
        assertEquals("the status line does not", low.findViewById(R.id.widget_status_row).getMeasuredHeight(),
                high.findViewById(R.id.widget_status_row).getMeasuredHeight());
    }

    @Test
    public void everyControlOfEveryClassHasASymbol() {
        for (SizeClass sizeClass : SizeClass.values()) {
            View widget = renderWithoutText(sizeClass);
            for (View tile : tiles(widget)) {
                assertEquals(sizeClass + " has a symbol", "icon", ico(tile));
            }
        }
    }

    /**
     * Round 3 follow-up: at a large font a one-row class can be 190 dp high (a 4x2 at font 2.0), and
     * the square buttons used to stretch to that height and become pillars. They stay square.
     */
    @Test
    public void theSquareButtonsStaySquareHoweverHighTheWidgetIs() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, false);
        for (SizeClass sizeClass : new SizeClass[] {SizeClass.ROW1, SizeClass.ROW2}) {
            for (int height : new int[] {89, 193, 260}) {
                View widget = applyAndMeasure(context, KeepADBWidgetViews.forSize(context, WIDGET_ID,
                        defaults, projection, sizeClass), 296, height);
                for (View square : tiles(widget).subList(1, tiles(widget).size())) {
                    assertEquals(sizeClass + " @" + height + " width", 52, square.getMeasuredWidth());
                    assertEquals(sizeClass + " @" + height + " is square", 52,
                            square.getMeasuredHeight());
                }
                assertTrue("the hero still fills the height",
                        hero(widget).getMeasuredHeight() >= height - 20);
            }
        }
    }

    /**
     * The port uses tabular figures and sits in a box of fixed height (the font is sans-serif-condensed
     * bold, which has a real bold face; the monospace family has none), so uniform autosize can use
     * the full nominal size instead of collapsing around wrap_content.
     */
    @Test
    public void thePortIsRealBoldTabularAndInAFixedHeightBoxSoItIsNotShrunkNeedlessly() {
        float density = context.getResources().getDisplayMetrics().scaledDensity;
        int[][] expected = {{22, 28}, {22, 28}, {16, 21}};
        SizeClass[] classes = {SizeClass.PANEL, SizeClass.GRID, SizeClass.ROW1};
        for (int i = 0; i < classes.length; i++) {
            android.widget.TextView port = hero(render(classes[i])).findViewById(R.id.widget_tile_port);
            assertEquals(classes[i] + " tabular figures", "tnum", port.getFontFeatureSettings());
            assertEquals(classes[i] + " max size", expected[i][0] * density, port.getAutoSizeMaxTextSize(), 0.6f);
            assertEquals(classes[i] + " box height", Math.round(expected[i][1] * density),
                    port.getLayoutParams().height);
            assertEquals(classes[i] + " one line", 1, port.getMaxLines());
        }
    }

    /**
     * Review finding: at 152 x 140 dp the grid left about 24 dp for its hero. Each tall class is
     * chosen only from its content height (KeepADBWidgetLayout), so at exactly that height - the
     * worst case a launcher can give it - every control still has its 48 dp, the hero its symbol
     * and port box, and the status line its row.
     */
    /** A context whose configuration has the font scale: the sp boxes of the layouts follow it. */
    private Context withFontScale(float fontScale) {
        android.content.res.Configuration config =
                new android.content.res.Configuration(context.getResources().getConfiguration());
        config.fontScale = fontScale;
        return context.createConfigurationContext(config);
    }

    /** Hides the texts, but keeps the port's box (fixed height, so no text metrics are involved). */
    private static void blankExceptPort(View view) {
        if (view instanceof android.widget.TextView) {
            view.setVisibility(view.getId() == R.id.widget_tile_port && view.getVisibility() == View.VISIBLE
                    ? View.INVISIBLE : View.GONE);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) blankExceptPort(group.getChildAt(i));
        }
    }

    @Test
    public void atTheSmallestHeightOfATallClassEveryControlStillHasItsRoom() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, false);
        for (float fs : new float[] {1f, 1.3f, 2f}) {
            // The font scale is applied to the context the views are inflated with, so the port
            // box (28 sp, 21 sp) really has its scaled height; the other texts are left out
            // because Robolectric's text metrics are not a device's.
            Context scaled = withFontScale(fs);
            android.util.DisplayMetrics metrics = scaled.getResources().getDisplayMetrics();
            assertEquals("the font scale reaches the context", fs * metrics.density, metrics.scaledDensity, 0.01f);
            for (SizeClass sizeClass : new SizeClass[] {SizeClass.STACK, SizeClass.GRID, SizeClass.PANEL}) {
                int width = sizeClass.minWidthDp(fs);
                int height = sizeClass.minHeightDp(fs);
                View widget = applyAndMeasure(scaled, KeepADBWidgetViews.forSize(scaled, WIDGET_ID,
                        defaults, projection, sizeClass), width, height);
                blankExceptPort(widget);
                KeepADBWidgetTestSupport.measure(widget, width, height);
                String where = sizeClass + " @" + fs + " in " + width + " x " + height;
                for (View tile : tiles(widget)) {
                    assertTrue(where + " width " + tile.getMeasuredWidth(), tile.getMeasuredWidth() >= 48);
                    assertTrue(where + " height " + tile.getMeasuredHeight(), tile.getMeasuredHeight() >= 48);
                }
                // The hero holds its symbol and the port box (28 sp in the grid and the panel, 21 sp
                // in the stack) at the scaled size: the box is there and fits.
                View port = hero(widget).findViewById(R.id.widget_tile_port);
                float boxSp = sizeClass == SizeClass.STACK ? 21 : 28;
                int box = Math.round(android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, boxSp, metrics));
                assertEquals(where + " the port box keeps its scaled height", box, port.getMeasuredHeight());
                float fixed = sizeClass == SizeClass.STACK ? 16 + 26 + 4
                        : sizeClass == SizeClass.GRID ? 12 + 24 + 4 : 24 + 34;
                // The test density is 1, where each 2.5 dp frame or gap rounds up to 3 px: up to
                // 3 px more of the room goes to those than the exact sum, on a real screen less.
                assertTrue(where + " hero " + hero(widget).getMeasuredHeight() + " needs " + (fixed + box),
                        hero(widget).getMeasuredHeight() >= fixed + box - 4);
                if (sizeClass == SizeClass.PANEL) {
                    assertTrue(where + " status row",
                            widget.findViewById(R.id.widget_status_row).getMeasuredHeight() >= 48);
                }
            }
        }
    }
}
