package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #654/#769: the Networks list follows the language chosen in the app, like every other screen.
 * Below Android 13 the app applies the choice itself by wrapping the activity's base context
 * ({@link KeepADBLocaleHelper#wrapContext}), which is the path this pins -- a new activity that
 * forgets it would stay in the system language. On Android 13+ the platform owns the choice.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class NetworkListActivityLocaleTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        java.util.Locale.setDefault(java.util.Locale.US);
    }

    private String title() {
        NetworkListActivity activity = Robolectric.buildActivity(NetworkListActivity.class,
                NetworkListActivity.intent(context)).setup().get();
        return ((TextView) activity.findViewById(R.id.network_list_title)).getText().toString();
    }

    @Test
    public void theViewFollowsTheAppLanguageAndOtherwiseTheSystemOne() {
        String english = title();
        assertEquals("Networks", english);

        KeepADBPreferences.setAppLanguage(context, "de");
        String german = title();
        assertEquals("Netzwerke", german);
        assertNotEquals(english, german);

        KeepADBPreferences.setAppLanguage(context, "ja");
        assertEquals("ネットワーク", title());
    }
}
