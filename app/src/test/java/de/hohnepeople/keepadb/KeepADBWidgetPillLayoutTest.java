package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #927: the pill's label must never lose a word or a letter at two cells and large font scales.
 * Robolectric does not measure real glyphs, so this pins the layout contract that makes it fit on
 * a device (checked on the API 34 emulator with ru, id and en at font scales 1.0, 1.3 and 2.0).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetPillLayoutTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    private View inflate() {
        return LayoutInflater.from(context).inflate(R.layout.widget_ctl_pill, null, false);
    }

    @Test
    public void theLabelWrapsToTwoLinesInsteadOfDroppingItsSecondWord() {
        TextView label = inflate().findViewById(R.id.widget_tile_label);
        // With maxLines 1, "Sinkron otomatis" showed only "Sinkron".
        assertEquals(2, label.getMaxLines());
        assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM, label.getAutoSizeTextType());
    }

    @Test
    public void autosizeIsBoundedInHeightSoItCanShrink() {
        View pill = inflate();
        for (int id : new int[] {R.id.widget_tile_label, R.id.widget_tile_state}) {
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) pill.findViewById(id).getLayoutParams();
            assertEquals("height must come from the weight, not wrap_content", 0, params.height);
            assertTrue(params.weight > 0);
        }
        View column = (View) pill.findViewById(R.id.widget_tile_label).getParent();
        assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, column.getLayoutParams().height);
    }

    @Test
    public void theLabelFloorLetsALargeFontScaleFitOnOneLine() {
        TextView label = inflate().findViewById(R.id.widget_tile_label);
        // sp scales with the font scale: 7 sp at 2.0 is still larger than 13 sp at 1.0.
        float sevenSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 7,
                context.getResources().getDisplayMetrics());
        assertEquals(sevenSp, label.getAutoSizeMinTextSize(), 0.5f);
    }
}
