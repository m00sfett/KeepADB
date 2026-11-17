package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
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

/** #791 / #961: the missing system permission offers browser setup and command sharing. */
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
    public void theMissingSystemPermissionOffersToShareTheCommand() {
        OnboardingActivity assistant = open(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS));
        // #961/#962: the manual USB command sits behind its own fold below browser setup.
        openFold(assistant.getWindow().getDecorView(),
                context.getString(R.string.selfpair_computer_toggle));
        Button share = find(assistant.getWindow().getDecorView(),
                context.getString(R.string.onboarding_share_command));
        assertNotNull("a share button next to the command", share);
        share.performClick();

        android.content.Intent chooser = shadowOf(assistant).getNextStartedActivity();
        assertNotNull("a share chooser was launched", chooser);
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.getAction());
        android.content.Intent send = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
        assertNotNull(send);
        assertEquals(android.content.Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(context.getString(R.string.setup_command, context.getPackageName()),
                send.getStringExtra(android.content.Intent.EXTRA_TEXT));

        // Granted: command and share button are gone together.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        find(assistant.getWindow().getDecorView(), context.getString(R.string.setup_refresh))
                .performClick();
        assertEquals(null, find(assistant.getWindow().getDecorView(),
                context.getString(R.string.onboarding_share_command)));
    }

    @Test
    public void theMissingSystemPermissionOffersBrowserSetupWithDeveloperOptions() {
        OnboardingActivity assistant = open(
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS));
        openFold(assistant.getWindow().getDecorView(),
                context.getString(R.string.onboarding_perm_browser_toggle));
        Button devOptions = find(assistant.getWindow().getDecorView(),
                context.getString(R.string.selfpair_open_developer_options));
        assertNotNull("developer options button inside browser setup", devOptions);
        devOptions.performClick();

        android.content.Intent devIntent = shadowOf(assistant).getNextStartedActivity();
        assertNotNull(devIntent);
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                devIntent.getAction());

        // Granted: browser setup fold and button are gone.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        find(assistant.getWindow().getDecorView(), context.getString(R.string.setup_refresh))
                .performClick();
        assertEquals(null, find(assistant.getWindow().getDecorView(),
                context.getString(R.string.selfpair_open_developer_options)));
    }

    /** Taps the fold line whose text starts with {@code label}. */
    private static boolean openFold(View root, String label) {
        if (root instanceof TextView && !(root instanceof Button)
                && ((TextView) root).getText().toString().startsWith(label)) {
            return root.performClick();
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (openFold(group.getChildAt(i), label)) return true;
            }
        }
        return false;
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
