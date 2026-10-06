package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #616: the optional ACCESS_BACKGROUND_LOCATION grant: what its absence means for trust, and the
 * always-available status/setup entry in the trusted-network settings. (#764: the home screen's
 * card for it moved into the assistant; its warning is covered by MainActivityWarningsTest.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackgroundLocationGrantTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void missingGrantNeverMakesAnUnreadableIdentityTrusted() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Home");
        assertFalse(KeepADBBackgroundLocation.isGranted(context));
        // What a background-started service sees without the grant: masked SSID/BSSID.
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity(android.net.wifi.WifiManager.UNKNOWN_SSID,
                        KeepADBNetworkIdentity.REDACTED_BSSID)));
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity(null, null)));
    }

    @Test
    public void settingsShowStatusAndSetupEntry() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_missing), status.getText().toString());
        settings.findViewById(R.id.settings_background_location_button).performClick();
        // #644: the rationale dialog comes first; the jump is its "Open settings" action.
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Intent intent = shadowOf(settings).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_granted), status.getText().toString());
    }

    @Test
    public void settingsExplainGrantIsOnlyNeededInTrustedNetworkMode() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_missing_inactive),
                status.getText().toString());
    }
}
