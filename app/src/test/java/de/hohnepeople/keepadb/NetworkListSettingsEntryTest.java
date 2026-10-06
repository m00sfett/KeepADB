package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
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
import org.robolectric.shadows.ShadowLooper;

/**
 * #762: the entry of the Network card in the Settings that opens the single "Networks" list and
 * shows how many networks are trusted and blocked there (nothing of it in the privacy mode).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetworkListSettingsEntryTest {

    private static final String KITCHEN = "aa:bb:cc:11:22:33";
    private static final String HALL = "aa:bb:cc:11:22:44";
    private static final String CAFE_AP = "12:34:56:78:9a:bc";

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
        KeepADB.resetForTesting();
    }

    @Test
    public void theSettingsEntryOpensTheViewAndCountsTrustedAndBlockedAndHidesThemInPrivacyMode() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, "Heimnetz");
        KeepADBTrustedNetwork.addBssid(context, HALL, "Heimnetz");
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP);
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        TextView count = settings.findViewById(R.id.network_networks_count);
        assertEquals(context.getString(R.string.networks_count, 2, 1), count.getText().toString());

        settings.findViewById(R.id.network_networks_row).performClick();
        Intent started = shadowOf(settings).getNextStartedActivity();
        assertEquals(NetworkListActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(NetworkListActivity.VIEW_NETWORKS,
                started.getStringExtra(NetworkListActivity.EXTRA_VIEW));

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity hiddenSettings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        assertEquals(context.getString(R.string.networks_count_hidden),
                ((TextView) hiddenSettings.findViewById(R.id.network_networks_count)).getText().toString());
    }

}
