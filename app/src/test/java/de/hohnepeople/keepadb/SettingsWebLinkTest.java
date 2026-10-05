package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/** #673: external web links must not crash Settings on devices without a browser. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsWebLinkTest {

    private static final String NO_BROWSER = "No app found to open this link.";

    @Test
    public void feedbackMetadataIsEncodedBoundedAndNeverAddsUnexpectedParameters() {
        String version = "1.9 & +/#?=é";
        String model = "Model & +/#?=端末 / Android 13";
        Uri uri = Uri.parse(KeepADBIssueReporter.buildFeedbackUrl("translation", version, model));
        assertEquals("https", uri.getScheme());
        assertEquals("keepadb.roteson.de", uri.getHost());
        assertEquals("/feedback", uri.getPath());
        assertNull(uri.getFragment());
        assertEquals(java.util.Set.of("issueType", "appVersion", "deviceInfo"),
                uri.getQueryParameterNames());
        assertEquals("translation", uri.getQueryParameter("issueType"));
        assertEquals(version, uri.getQueryParameter("appVersion"));
        assertEquals(model, uri.getQueryParameter("deviceInfo"));
        uri = Uri.parse(KeepADBIssueReporter.buildFeedbackUrl("unexpected&text=secret",
                "😀".repeat(101), "端".repeat(201) + "\n\u0000"));
        assertEquals("other", uri.getQueryParameter("issueType"));
        String boundedVersion = uri.getQueryParameter("appVersion");
        assertEquals(100, boundedVersion.codePointCount(0, boundedVersion.length()));
        assertEquals(200, uri.getQueryParameter("deviceInfo").length());
        uri = Uri.parse(KeepADBIssueReporter.buildFeedbackUrl(null, null, "\n\u0000\ud800"));
        assertEquals(java.util.Set.of("issueType"), uri.getQueryParameterNames());
        uri = Uri.parse(KeepADBIssueReporter.buildFeedbackUrl("bug", "v\t1", "a\r\nb"));
        assertEquals("v1", uri.getQueryParameter("appVersion"));
        assertEquals("ab", uri.getQueryParameter("deviceInfo"));
    }

    @Test
    public void websiteLinkWithoutHandlerShowsToastInsteadOfCrashing() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).checkActivities(true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        View link = controller.get().findViewById(R.id.settings_website_link);

        link.performClick();

        assertEquals(NO_BROWSER, ShadowToast.getTextOfLatestToast());
        assertNull(shadowOf(app).getNextStartedActivity());
        controller.pause().stop().destroy();
    }

    @Test
    public void feedbackLinkWithoutHandlerShowsToastInsteadOfCrashing() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).checkActivities(true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();

        controller.get().openWebLink(KeepADBIssueReporter.FEEDBACK_URL);

        assertEquals(NO_BROWSER, ShadowToast.getTextOfLatestToast());
        controller.pause().stop().destroy();
    }

    @Test
    public void websiteLinkWithHandlerStartsViewIntentWithoutToast() {
        Application app = RuntimeEnvironment.getApplication();
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();

        controller.get().openWebLink(KeepADBIssueReporter.FEEDBACK_URL);

        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals(KeepADBIssueReporter.FEEDBACK_URL, started.getDataString());
        assertNull(ShadowToast.getLatestToast());
        controller.pause().stop().destroy();
    }
}
