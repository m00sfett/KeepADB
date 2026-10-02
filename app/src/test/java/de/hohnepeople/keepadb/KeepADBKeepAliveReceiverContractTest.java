package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * #744: guards the #734 Keep-Alive toggle. The receiver must stay non-exported and the
 * notification action must be an explicit, immutable broadcast to it, so no other app can
 * trigger or tamper with the toggle.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBKeepAliveReceiverContractTest {

    @Test
    public void manifestKeepsKeepADBReceiverNonExportedAndOwnsToggleAction() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("<receiver\n            android:name=\".KeepADBReceiver\"");
        assertTrue("KeepADBReceiver <receiver> element not found", start >= 0);
        int end = manifest.indexOf("</receiver>", start);
        assertTrue(end > start);
        String receiver = manifest.substring(start, end);

        assertTrue("KeepADBReceiver must declare android:exported=\"false\"",
                receiver.contains("android:exported=\"false\""));
        assertTrue("ACTION_TOGGLE_KEEP_ALIVE must belong to KeepADBReceiver",
                receiver.contains("<action android:name=\"" + KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE + "\" />"));
    }

    @Test
    public void keepAliveActionIsExplicitImmutableBroadcastToReceiver() {
        Context context = RuntimeEnvironment.getApplication();
        Notification.Action action = KeepADBNotification.keepAliveAction(context);
        assertNotNull(action.actionIntent);

        ShadowPendingIntent shadow = shadowOf(action.actionIntent);
        assertTrue("Keep-Alive action must be a broadcast", shadow.isBroadcastIntent());
        assertTrue("Keep-Alive PendingIntent must be FLAG_IMMUTABLE",
                (shadow.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent intent = shadow.getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE, intent.getAction());
        assertNotNull("Keep-Alive intent must carry an explicit component", intent.getComponent());
        assertEquals(KeepADBReceiver.class.getName(), intent.getComponent().getClassName());
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
