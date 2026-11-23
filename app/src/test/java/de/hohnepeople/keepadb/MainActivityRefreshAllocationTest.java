package de.hohnepeople.keepadb;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.view.View;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #1048 (point 5): the periodic refresh of the home screen keeps the tap-target setup of the
 * Keep-Alive line instead of rebuilding it (theme lookup and accessibility delegate) every time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityRefreshAllocationTest {
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private ActivityController<MainActivity> controller;

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        KeepADB.resetForTesting();
        RuntimeEnvironment.getApplication().getSharedPreferences("keepadb_prefs", 0).edit().clear().commit();
    }

    @Test
    public void refreshKeepsTheKeepAliveLineDelegate() throws Exception {
        KeepADBOnboarding.setAutoStartEnabledForTesting(false);
        try {
            controller = Robolectric.buildActivity(MainActivity.class).setup();
            MainActivity activity = controller.get();
            View line = activity.findViewById(R.id.keep_alive_subtext);
            View.AccessibilityDelegate first = line.getAccessibilityDelegate();
            assertNotNull("outside force mode the line is a button for TalkBack", first);

            Method refresh = MainActivity.class.getDeclaredMethod("refresh");
            refresh.setAccessible(true);
            refresh.invoke(activity);
            refresh.invoke(activity);

            assertSame("the refresh reuses the line's delegate", first, line.getAccessibilityDelegate());
        } finally {
            KeepADBOnboarding.setAutoStartEnabledForTesting(true);
        }
    }
}
