package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Covers the #539 multi-transport contract on its real production path: the app's own
 * {@code KeepADBRegisterClient.updateEndpointAsync} trigger, not a hand-assembled call into
 * {@code postTransports}.
 *
 * <p>The live {@code phone-register-server} currently accepts only {@code wlan-adb} (see
 * {@link KeepADBRegisterPayload#SERVER_SUPPORTED_METHODS}). Reporting a verified USB or Tailscale
 * transport to it would earn a guaranteed HTTP 400 on every endpoint change, so these tests pin
 * that such events are built but held back -- and that widening the constant is all it takes to
 * start sending them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterMultiTransportWiringTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String URL = "http://127.0.0.1:50829/register/s20";

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<String> sentPayloads = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        sentPayloads.clear();
        KeepADBRegisterClient.setHttpTransport(new KeepADBRegisterClient.HttpTransport() {
            @Override
            public boolean postJson(String targetUrl, String payload, String logLabel) {
                sentPayloads.add(payload);
                return true;
            }

            @Override
            public boolean delete(String targetUrl) {
                return true;
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADBNotification.resetForTesting();
        setNotificationStatic("currentHost", null);
        setNotificationStatic("currentPort", 0);
        setNotificationStatic("currentEndpointVerifiedAtMs", 0L);
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        setUsbAdbConnected(false);
        KeepADBRegisterPayload.setServerSupportedMethodsForTesting(null);
        KeepADB.resetForTesting();
    }

    /**
     * The whole point of the wiring: a USB transport that is genuinely verified alongside WLAN is
     * fed into {@code postTransports}, and held back there because the deployed server has no
     * {@code usb-adb} slot yet. The WLAN report must go out unaffected.
     */
    @Test
    public void verifiedUsbTransport_isHeldBackWhileTheWlanReportStillGoesOut() throws Exception {
        enableWebhook();
        setUsbAdbConnected(true);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
        awaitSuccessfulReport();

        assertEquals("Exactly one event may reach the server: the WLAN one",
                1, sentPayloads.size());
        String wlan = sentPayloads.get(0);
        assertTrue(wlan, wlan.contains("\"method\":\"wlan-adb\""));
        assertTrue(wlan, wlan.contains("\"endpoint\":\"192.168.1.50:41234\""));
        assertTrue(wlan, wlan.contains("\"contract_version\":2"));
        assertFalse("A usb-adb event would be an automatic HTTP 400 against the live register",
                wlan.contains("usb-adb"));
    }

    /**
     * The load-bearing counter-test: the assertion above ("only the WLAN event is sent") would
     * pass just as well if {@code postTransports} were never reached from production code at all.
     * With the server-supported set widened to the future state, the exact same
     * {@code updateEndpointAsync} trigger must now also emit the USB event -- which it can only
     * do if {@code performUpdateTransaction} really calls into the multi-transport path. Deleting
     * that call makes this test fail.
     */
    @Test
    public void withAWidenedServer_theSameTriggerAlsoReportsTheUsbTransport() throws Exception {
        KeepADBRegisterPayload.setServerSupportedMethodsForTesting(new java.util.HashSet<>(
                java.util.Arrays.asList("wlan-adb", "usb-adb")));
        enableWebhook();
        setUsbAdbConnected(true);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
        awaitSuccessfulReport();
        waitUntil(() -> sentPayloads.size() >= 2, 5_000L);

        assertEquals(2, sentPayloads.size());
        assertTrue(sentPayloads.get(0).contains("\"method\":\"wlan-adb\""));
        String usb = sentPayloads.get(1);
        assertTrue(usb, usb.contains("\"method\":\"usb-adb\""));
        assertTrue("An active USB transport carries no fabricated network endpoint: " + usb,
                !usb.contains("\"endpoint\""));
        assertTrue(usb, usb.contains("\"active\":true"));
    }

    /**
     * Counter-test at the {@code postTransports} boundary itself: an unsupported method produces
     * no request, a supported one does.
     */
    @Test
    public void wideningTheServerSupportedSet_makesTheHeldBackEventFlow() throws Exception {
        List<KeepADBRegisterPayload.VerifiedTransport> usbOnly = Collections.singletonList(
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.USB, null, 0, 1_700_000_000_000L));

        assertTrue(KeepADBRegisterClient.postTransports(URL, usbOnly));
        assertTrue("usb-adb is not in SERVER_SUPPORTED_METHODS, so nothing may be sent",
                sentPayloads.isEmpty());

        List<KeepADBRegisterPayload.VerifiedTransport> wlanOnly = Collections.singletonList(
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.WLAN_LAN, "192.168.1.50", 41234,
                        1_700_000_000_000L));

        assertTrue(KeepADBRegisterClient.postTransports(URL, wlanOnly));
        assertEquals("A supported method must actually be sent", 1, sentPayloads.size());
        assertTrue(sentPayloads.get(0).contains("\"method\":\"wlan-adb\""));
    }

    /**
     * The multi-transport report must not become a back door around the webhook opt-in: with the
     * feature switched off, the whole path -- including the new transport reporting -- stays
     * silent even though a USB transport is verified.
     */
    @Test
    public void withTheWebhookOptInOff_nothingIsSentAtAll() throws Exception {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        setUsbAdbConnected(true);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
        for (int i = 0; i < 20; i++) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10L);
        }

        assertTrue("A disabled webhook must produce no traffic whatsoever", sentPayloads.isEmpty());
    }

    private void enableWebhook() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
    }

    private void awaitSuccessfulReport() throws Exception {
        waitUntil(() -> "192.168.1.50:41234".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 5_000L);
        // The additional-transport reporting happens on the same executor right after the WLAN
        // POST; give it its turn before counting what was sent.
        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return true;
        }, 1_000L);
        Thread.sleep(100L);
    }

    private static void waitUntil(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Condition not met within " + timeoutMs + "ms");
    }

    private void clearPreferences() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void setUsbAdbConnected(boolean connected) {
        Intent intent = new Intent(KeepADBUsbReceiver.ACTION_USB_STATE);
        intent.putExtra("connected", connected);
        intent.putExtra("configured", connected);
        intent.putExtra("adb", connected);
        context.sendStickyBroadcast(intent);
    }

    private static void setNotificationStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
