package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBWidgetTestSupport.applyAndMeasure;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #913: the overview widget on Android 11, which has no sized RemoteViews. Both orientations are
 * supplied from the classic options (landscape: max width; portrait: min width) and the host draws
 * the one that matches the screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class KeepADBOverviewWidgetApi30Test {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBWidgetConfig.clearAll(context);
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBEndpointCoordinator.seedEndpointForTesting("192.168.178.40", 38203, 1L);
    }

    @After
    public void tearDown() {
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBWidgetConfig.clearAll(context);
        KeepADB.resetForTesting();
    }

    private static Bundle options(int minWidth, int maxWidth) {
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 176);
        return options;
    }

    @Test
    public void eachOrientationGetsTheShapeOfItsOwnWidth() {
        // 4 cells: portrait 214 dp (compact), landscape 291 dp (wide).
        Bundle options = options(214, 291);
        assertEquals(KeepADBOverviewViews.Shape.COMPACT,
                KeepADBOverviewViews.choose(options, false, 1f));
        assertEquals(KeepADBOverviewViews.Shape.WIDE,
                KeepADBOverviewViews.choose(options, true, 1f));

        KeepADBWidgetState projection = KeepADBWidgetState.project(context);
        RemoteViews both = KeepADBOverviewWidget.legacyViews(context, projection, options, 1f);

        RuntimeEnvironment.setQualifiers("port");
        View portrait = applyAndMeasure(context, both, 214, 176);
        assertNull("portrait is compact", portrait.findViewById(R.id.widget_ov_delivery));

        RuntimeEnvironment.setQualifiers("land");
        View landscape = applyAndMeasure(ApplicationProvider.getApplicationContext(), both, 291, 150);
        assertNotNull("landscape is wide", landscape.findViewById(R.id.widget_ov_delivery));
    }

    @Test
    public void withoutOptionsTheDefaultSizeIsCompact() {
        assertEquals(KeepADBOverviewViews.Shape.COMPACT,
                KeepADBOverviewViews.choose((Bundle) null, false, 1f));
    }
}
