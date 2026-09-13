package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;

import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Edge-to-edge inset handling (#324). The pure padding rule is covered directly; the wiring
 * between the activities and {@link KeepADBWindowInsets} is covered by actually starting each
 * activity with Robolectric and dispatching a real {@link WindowInsets} event to the header and
 * content views, instead of parsing the activities' source text for expected method calls (#382)
 * — a source-text match survives a broken wiring just as easily as a working one, since it never
 * exercises the listener that {@link KeepADBWindowInsets#apply} installs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWindowInsetsTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final int SYSTEM_BARS_BOTTOM = 48;
    private static final int SYSTEM_BARS_TOP = 24;
    private static final int IME_BOTTOM = 720;

    @Test
    public void keyboardWinsOverNavigationBar() {
        assertEquals(720, KeepADBWindowInsets.contentBottomInset(48, 720));
    }

    @Test
    public void navigationBarAppliesWhileKeyboardIsHidden() {
        assertEquals(48, KeepADBWindowInsets.contentBottomInset(48, 0));
    }

    @Test
    public void noInsetsMeanNoExtraSpace() {
        assertEquals(0, KeepADBWindowInsets.contentBottomInset(0, 0));
    }

    @Test
    public void mainActivityDispatchesRealInsetsToHeaderAndContent() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        View header = activity.findViewById(R.id.header_bar);
        View content = activity.findViewById(R.id.content_scroll);
        assertContentBottomMarginFollowsDispatchedInsets(header, content);
    }

    @Test
    public void settingsActivityDispatchesRealInsetsToHeaderAndContent() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View header = activity.findViewById(R.id.header_bar);
        View content = activity.findViewById(R.id.settings_scroll_view);
        assertContentBottomMarginFollowsDispatchedInsets(header, content);
    }

    /**
     * Dispatches a genuine {@link WindowInsets} event (built with the real system bar/IME insets
     * the production code reads) into the view tree via {@link View#dispatchApplyWindowInsets},
     * which invokes whatever listener {@link KeepADBWindowInsets#apply} installed — exactly the
     * path a real device takes, not a parsed source string.
     */
    private static void assertContentBottomMarginFollowsDispatchedInsets(View header, View content) {
        int headerTopBefore = header.getPaddingTop();
        ViewGroup.MarginLayoutParams contentParamsBefore =
                (ViewGroup.MarginLayoutParams) content.getLayoutParams();
        int contentBottomMarginBefore = contentParamsBefore.bottomMargin;

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(),
                        Insets.of(0, SYSTEM_BARS_TOP, 0, SYSTEM_BARS_BOTTOM))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, IME_BOTTOM))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.NONE)
                .build();

        header.dispatchApplyWindowInsets(insets);
        content.dispatchApplyWindowInsets(insets);

        assertEquals("Header should gain the status bar top inset as padding",
                headerTopBefore + SYSTEM_BARS_TOP, header.getPaddingTop());

        ViewGroup.MarginLayoutParams contentParamsAfter =
                (ViewGroup.MarginLayoutParams) content.getLayoutParams();
        int expectedBottomInset =
                KeepADBWindowInsets.contentBottomInset(SYSTEM_BARS_BOTTOM, IME_BOTTOM);
        assertEquals("Content bottom margin should grow by max(system bars, ime)",
                contentBottomMarginBefore + expectedBottomInset, contentParamsAfter.bottomMargin);
    }
}
