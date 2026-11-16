package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** #959: the spoken description of a help hint carries the More/Less word, never its arrow. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBHelpHintTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void contentDescriptionHasTheWordButNoArrowGlyph() {
        TextView view = new TextView(context);
        KeepADBHelpHint hint = new KeepADBHelpHint(view, null);
        hint.set("Short line", "The full text");

        String collapsed = view.getContentDescription().toString();
        assertEquals("Short line More", collapsed);
        assertFalse(collapsed.contains("▾") || collapsed.contains("▴"));
        assertTrue(view.getText().toString().contains("▾"));

        view.performClick();

        String expanded = view.getContentDescription().toString();
        assertEquals("The full text Less", expanded);
        assertFalse(expanded.contains("▾") || expanded.contains("▴"));
        assertEquals(context.getString(R.string.card_state_expanded),
                view.getStateDescription().toString());
    }
}
