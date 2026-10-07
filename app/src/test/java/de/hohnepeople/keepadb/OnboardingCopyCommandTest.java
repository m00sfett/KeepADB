package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** #791 (F8): the missing system permission offers to copy its command. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingCopyCommandTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBPreferences.setAppLanguage(context, "en");
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    @After
    public void tearDown() {
        for (ActivityController<?> controller : controllers) {
            try {
                controller.pause().stop().destroy();
            } catch (RuntimeException ignored) {
                // Already finished.
            }
        }
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private OnboardingActivity open(android.content.Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller.get();
    }

    @Test
    public void theMissingSystemPermissionOffersToCopyTheCommand() {
        OnboardingActivity assistant = open(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS));
        Button copy = find(assistant.getWindow().getDecorView(),
                context.getString(R.string.onboarding_copy_command));
        assertNotNull("a copy button next to the command", copy);
        copy.performClick();

        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        assertEquals(context.getString(R.string.setup_command, context.getPackageName()),
                clipboard.getPrimaryClip().getItemAt(0).getText().toString());

        // Granted: command and button are gone together.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        find(assistant.getWindow().getDecorView(), context.getString(R.string.setup_refresh))
                .performClick();
        assertEquals(null, find(assistant.getWindow().getDecorView(),
                context.getString(R.string.onboarding_copy_command)));
    }

    private static Button find(View root, String label) {
        if (root instanceof Button && label.contentEquals(((TextView) root).getText())) {
            return (Button) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button hit = find(group.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
