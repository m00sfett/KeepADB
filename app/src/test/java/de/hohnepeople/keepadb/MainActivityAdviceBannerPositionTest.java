package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityAdviceBannerPositionTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBOnboarding.markCompleted(context);
        KeepADB.resetForTesting(context);
    }

    @Test
    public void adviceBannerIsPositionedAboveSwitchesAndBelowWarnings() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        View adviceBanner = activity.findViewById(R.id.advice_banner);
        assertNotNull("advice_banner must exist", adviceBanner);

        View toggle = activity.findViewById(R.id.toggle);
        assertNotNull("toggle switch must exist", toggle);
        View switchesPanel = (View) toggle.getParent();
        assertNotNull("switchesPanel must exist", switchesPanel);

        View warningLimited = activity.findViewById(R.id.warning_limited);
        assertNotNull("warning_limited must exist", warningLimited);

        ViewGroup parent = (ViewGroup) adviceBanner.getParent();
        assertEquals("warnings, advice banner and switches must share the same parent container",
                parent, warningLimited.getParent());
        assertEquals("switches and advice banner must share the same parent container",
                parent, switchesPanel.getParent());

        int adviceIndex = parent.indexOfChild(adviceBanner);
        int warningIndex = parent.indexOfChild(warningLimited);
        int switchesIndex = parent.indexOfChild(switchesPanel);

        assertTrue("advice_banner must be placed below the warning cards", adviceIndex > warningIndex);
        assertTrue("advice_banner must be placed above the main controls / switches", adviceIndex < switchesIndex);
        assertEquals("advice_banner must be directly adjacent to the switches panel", switchesIndex, adviceIndex + 1);
    }

    @Test
    public void dismissingAdviceBannerPersistsPreferenceAndHidesView() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View adviceBanner = activity.findViewById(R.id.advice_banner);

        assertTrue(KeepADBPreferences.isAdviceBannerVisible(activity));
        assertEquals(View.VISIBLE, adviceBanner.getVisibility());

        activity.findViewById(R.id.btn_dismiss_advice_banner).performClick();

        assertFalse(KeepADBPreferences.isAdviceBannerVisible(activity));
        assertEquals(View.GONE, adviceBanner.getVisibility());

        // Recreated activity reflects the hidden state
        MainActivity recreated = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(View.GONE, recreated.findViewById(R.id.advice_banner).getVisibility());
    }
}
