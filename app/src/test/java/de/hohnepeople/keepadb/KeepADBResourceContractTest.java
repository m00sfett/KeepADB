package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

/** Runtime resource contracts resolved through Android's Resources implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBResourceContractTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void requiredStringsResolveInDefaultResources() {
        int[] ids = {R.string.notification_permission_panel_title, R.string.notification_permission_settings_button,
                R.string.battery_optimization_title, R.string.battery_optimization_body,
                R.string.battery_optimization_button, R.string.back, R.string.settings_language_accessibility,
                R.string.settings_usb_handover_accessibility, R.string.advice_banner_title, R.string.advice_banner_text};
        for (int id : ids) assertFalse(context.getString(id).trim().isEmpty());
    }

    @Test
    public void formattedAccessibilityStringsAcceptArgumentsAtRuntime() {
        assertTrue(context.getString(R.string.settings_language_accessibility, "Deutsch").contains("Deutsch"));
        assertTrue(context.getString(R.string.settings_usb_handover_accessibility, "Automatic").contains("Automatic"));
    }

    @Test
    public void selectedLocalesResolveTheSameResourceIds() {
        int[] ids = {R.string.back, R.string.settings_language_accessibility,
                R.string.settings_usb_handover_accessibility, R.string.tile_state_connected};
        for (String languageTag : new String[] {"de", "es", "fr", "it", "pl"}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocales(new android.os.LocaleList(Locale.forLanguageTag(languageTag)));
            Resources resources = context.createConfigurationContext(configuration).getResources();
            for (int id : ids) assertFalse(languageTag, resources.getString(id).trim().isEmpty());
        }
    }

    @Test
    public void resourceArgumentsKeepTheirExpectedShape() {
        assertEquals(1, countFormatArguments(context.getString(R.string.settings_language_accessibility)));
        assertEquals(1, countFormatArguments(context.getString(R.string.settings_usb_handover_accessibility)));
    }

    private int countFormatArguments(String value) {
        int count = 0;
        for (int index = value.indexOf("%1$s"); index >= 0; index = value.indexOf("%1$s", index + 1)) count++;
        return count;
    }
}
