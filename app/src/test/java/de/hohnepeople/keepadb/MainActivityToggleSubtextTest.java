package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Pins #553's short explanatory line under the main "Wireless Debugging" switch: it must exist,
 * carry the {@code toggle_subtext} string, be visible, and sit directly between the toggle and
 * the dynamic status line -- never pushed elsewhere by a later layout change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityToggleSubtextTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void subtextIsBoundVisibleAndPlacedBetweenToggleAndStatus() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        Switch toggle = activity.findViewById(R.id.toggle);
        TextView subtext = activity.findViewById(R.id.toggle_subtext);
        TextView status = activity.findViewById(R.id.status);

        assertNotNull("toggle_subtext view must exist", subtext);
        assertEquals(View.VISIBLE, subtext.getVisibility());
        assertEquals(context.getString(R.string.toggle_subtext), subtext.getText().toString());
        assertTrue("subtext must not be empty", subtext.getText().length() > 0);

        // Same parent as the toggle and the status line, ordered strictly between them.
        ViewGroup parent = (ViewGroup) toggle.getParent();
        assertEquals(parent, subtext.getParent());
        assertEquals(parent, status.getParent());

        int toggleIndex = parent.indexOfChild(toggle);
        int subtextIndex = parent.indexOfChild(subtext);
        int statusIndex = parent.indexOfChild(status);

        assertTrue("subtext must come after the toggle", subtextIndex > toggleIndex);
        assertTrue("subtext must come before the status line", subtextIndex < statusIndex);
    }
}
