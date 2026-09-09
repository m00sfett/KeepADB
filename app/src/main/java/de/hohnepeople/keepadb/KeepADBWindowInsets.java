package de.hohnepeople.keepadb;

import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;

/**
 * Edge-to-edge window inset handling for the activity surfaces (#324).
 *
 * <p>Android 15 (API 35) draws apps that target SDK 35 edge to edge by default, so activity
 * content would otherwise sit behind the status bar, the navigation bar or the keyboard. The app
 * has no AndroidX runtime dependency, so this uses the platform {@link WindowInsets} API
 * (available since API 30, which matches minSdk 30) instead of {@code ViewCompat}.
 *
 * <p>{@link Window#setDecorFitsSystemWindows(boolean)} is called explicitly so the layout behaves
 * the same on every supported release instead of only on Android 15 — which also makes the fix
 * verifiable on older test devices.
 */
final class KeepADBWindowInsets {

    private KeepADBWindowInsets() {
    }

    /**
     * Bottom inset for the scrolling content area: the keyboard wins whenever it is taller than
     * the navigation bar, so the focused input field stays reachable; otherwise the navigation bar
     * inset applies. Both values are non-negative, so the result never shrinks the layout.
     */
    static int contentBottomInset(int systemBarsBottom, int imeBottom) {
        return Math.max(Math.max(systemBarsBottom, imeBottom), 0);
    }

    /**
     * Wires edge-to-edge insets for an activity built from a header bar plus a scrolling content
     * area: the header keeps clear of the status bar, the content keeps clear of the navigation
     * bar and the keyboard, and both keep clear of a display cutout at the side.
     */
    @SuppressWarnings("deprecation") // setDecorFitsSystemWindows is deprecated as of API 35,
    // where edge-to-edge is the enforced default and the call is a no-op. It stays because it is
    // the documented way to get the same layout — and the same inset callbacks — on API 30..34.
    static void apply(Window window, View header, View content) {
        window.setDecorFitsSystemWindows(false);
        applyHeaderInsets(header);
        applyContentInsets(content);
    }

    private static void applyHeaderInsets(View header) {
        // Capture the layout's own padding once: the listener fires repeatedly and must not
        // accumulate insets on top of a previous run.
        final int left = header.getPaddingLeft();
        final int top = header.getPaddingTop();
        final int right = header.getPaddingRight();
        final int bottom = header.getPaddingBottom();
        header.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = systemBarInsets(insets);
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom);
            return insets;
        });
    }

    /**
     * The bottom inset is applied as a <em>margin</em>, not as padding: it has to shrink the
     * scrolling view itself. {@code ScrollView} decides whether its focused child is on screen
     * from its own height and ignores its bottom padding, so a padded ScrollView makes room for
     * the keyboard but never scrolls the focused input into that room — measured on an API 35
     * emulator. Resizing it instead lets {@code ScrollView.onSizeChanged} do its usual job of
     * scrolling the focused child back into view. Horizontal insets stay padding, so the content
     * background still reaches the screen edge.
     */
    private static void applyContentInsets(View content) {
        final int left = content.getPaddingLeft();
        final int top = content.getPaddingTop();
        final int right = content.getPaddingRight();
        final int bottom = content.getPaddingBottom();
        final ViewGroup.LayoutParams params = content.getLayoutParams();
        final boolean marginsAvailable = params instanceof ViewGroup.MarginLayoutParams;
        final int baseBottomMargin =
                marginsAvailable ? ((ViewGroup.MarginLayoutParams) params).bottomMargin : 0;
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = systemBarInsets(insets);
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            int inset = contentBottomInset(bars.bottom, ime.bottom);
            if (marginsAvailable) {
                view.setPadding(left + bars.left, top, right + bars.right, bottom);
                ViewGroup.MarginLayoutParams current =
                        (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                int wanted = baseBottomMargin + inset;
                if (current.bottomMargin != wanted) {
                    current.bottomMargin = wanted;
                    view.setLayoutParams(current);
                }
            } else {
                // Fallback for a container without margins: at least keep the content clear of
                // the bars, even though the focused input is then not scrolled into view.
                view.setPadding(left + bars.left, top, right + bars.right, bottom + inset);
            }
            return insets;
        });
    }

    private static Insets systemBarInsets(WindowInsets insets) {
        return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    }
}
