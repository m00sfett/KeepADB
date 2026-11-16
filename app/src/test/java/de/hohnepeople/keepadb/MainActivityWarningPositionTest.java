package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #958: the general security advice left the home screen for the setup assistant. What is left
 * above the main controls are the state warnings (and the feedback bar of a closed one); nothing
 * sits between them and the switches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWarningPositionTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBOnboarding.markCompleted(context);
        KeepADB.resetForTesting(context);
    }

    @Test
    public void theWarningCardsStayAboveTheMainSwitchesWithNoAdviceCardBetween() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        View toggle = activity.findViewById(R.id.toggle);
        assertNotNull("toggle switch must exist", toggle);
        View switchesPanel = (View) toggle.getParent();
        View warningLimited = activity.findViewById(R.id.warning_limited);
        View feedback = activity.findViewById(R.id.warning_feedback);
        assertNotNull("warning_limited must exist", warningLimited);

        ViewGroup parent = (ViewGroup) warningLimited.getParent();
        assertEquals("warnings and switches must share the same parent container",
                parent, switchesPanel.getParent());
        int warningIndex = parent.indexOfChild(warningLimited);
        int feedbackIndex = parent.indexOfChild(feedback);
        int switchesIndex = parent.indexOfChild(switchesPanel);
        assertTrue("the warning cards come before the switches", warningIndex < switchesIndex);
        assertEquals("only the feedback bar sits between the last warning and the switches",
                warningIndex + 1, feedbackIndex);
        assertEquals("the feedback bar is directly above the switches", feedbackIndex + 1, switchesIndex);
    }

    @Test
    public void theHomeScreenShowsNoGeneralAdviceAnyMore() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertFalse(containsText(activity.getWindow().getDecorView(),
                context.getString(R.string.advice_banner_title)));
        assertFalse(containsText(activity.getWindow().getDecorView(),
                context.getString(R.string.advice_banner_text)));
    }

    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }
}
