package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * #697: static contracts for the ownership split between {@link SettingsActivity} and {@link
 * KeepADBNetworkCard}. The behavior tests ({@code SettingsNetworkCardLifecycleTest} and the other
 * Settings tests) pin what happens; these two pin what a behavior test cannot see in Robolectric:
 * that there is one single owner of the card's state, and on which thread the Wi-Fi callback is
 * delivered (the shadow ignores the handler).
 */
public class KeepADBNetworkCardContractTest {

    /**
     * The activity keeps the screen composition and the Android lifecycle; everything the card
     * owns -- rendered action snapshot, Wi-Fi callback, its four dialogs, its request codes and
     * the preference flag of the grant request -- lives in the card only. A second owner would
     * bring back the split state the extraction removed.
     */
    @Test
    public void theNetworkCardIsTheOnlyOwnerOfItsStateDialogsAndRequestCodes() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        String card = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNetworkCard.java");

        String[] ownedByTheCard = {
                "NetworkCallback", "registerNetworkCallback", "wifiStatusCallback",
                "networkStatusActionKind", "networkActionBssid", "networkActionLabel",
                "activeTrustConfirmationDialog", "activeTrustConfirmationBssid",
                "activeBackgroundLocationDialog", "activeAllowlistPermissionDialog",
                "activeMeshDialog", "TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST",
                "WIFI_APS_LOCATION_PERMISSION_REQUEST", "LOCATION_PERMISSION_REQUESTED",
                "requestPermissions(", "KeepADBNetworkActions.allowAccessPoint",
                "handleTrustNetworkAction", "pendingConfirmation"};
        for (String name : ownedByTheCard) {
            assertFalse("SettingsActivity must not own '" + name + "' any more (#697)",
                    activity.contains(name));
        }
        for (String name : new String[] {"wifiStatusCallback", "networkStatusActionKind",
                "networkActionBssid", "activeTrustConfirmationDialog",
                "activeBackgroundLocationDialog", "activeAllowlistPermissionDialog",
                "activeMeshDialog", "requestPermissions(", "handleTrustNetworkAction",
                "pendingConfirmation"}) {
            assertTrue("KeepADBNetworkCard must own '" + name + "'", card.contains(name));
        }

        // The activity reaches the card only through its explicit lifecycle hooks.
        for (String hook : new String[] {"new KeepADBNetworkCard(this, this::refresh)",
                "networkCard.restore(", "networkCard.start()", "networkCard.refresh()",
                "networkCard.stop()", "networkCard.saveState(", "networkCard.destroy()",
                "networkCard.onRequestPermissionsResult(requestCode)",
                "networkCard.showTrustConfirmationDialog("}) {
            assertEquals("SettingsActivity must call '" + hook + "' exactly once", 1,
                    count(activity, hook));
        }
    }

    /**
     * The Wi-Fi callback refreshes views, so it must be delivered on the main thread. Robolectric's
     * ConnectivityManager shadow drops the handler argument, so the registration call itself is
     * pinned: exactly one registration, with a main-looper handler, and exactly one removal.
     */
    @Test
    public void theWifiCallbackIsRegisteredOnceWithAMainLooperHandlerAndRemovedOnce()
            throws IOException {
        String card = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNetworkCard.java");

        assertEquals(1, count(card, "connectivityManager.registerNetworkCallback("));
        Matcher registration = Pattern.compile(
                "connectivityManager\\.registerNetworkCallback\\(\\s*request,\\s*callback,\\s*"
                        + "new Handler\\(Looper\\.getMainLooper\\(\\)\\)\\)").matcher(card);
        assertTrue("The callback must be registered with a main-looper handler",
                registration.find());
        assertEquals(1, count(card, "connectivityManager.unregisterNetworkCallback("));
        assertTrue("Only a Wi-Fi transport request is registered",
                card.contains(".addTransportType(NetworkCapabilities.TRANSPORT_WIFI)"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
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
