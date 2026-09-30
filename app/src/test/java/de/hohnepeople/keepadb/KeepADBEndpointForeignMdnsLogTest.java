package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

/** #685: the warning for a foreign mDNS address must not put that address into logcat. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBEndpointForeignMdnsLogTest {

    @Test
    public void foreignMdnsAddressWarningDoesNotLogAddressInCleartext() throws Exception {
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(
                RuntimeEnvironment.getApplication(), nsdProbe, scheduler);
        endpoint.discover(new KeepADBEndpoint.Listener() {
            @Override
            public void onEndpoint(String host, int port) {
            }

            @Override
            public void onUnavailable() {
            }
        }, false);

        NsdServiceInfo service = new NsdServiceInfo();
        service.setServiceType(KeepADBEndpoint.SERVICE_TYPE);
        service.setServiceName("adb-foreign");
        nsdProbe.discoveryListener.onServiceFound(service);

        NsdServiceInfo resolved = new NsdServiceInfo();
        resolved.setHost(InetAddress.getByName("203.0.113.77"));
        resolved.setPort(40000);
        ShadowLog.clear();
        nsdProbe.resolveListener.onServiceResolved(resolved);

        String warning = null;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg.contains("Ignoring mDNS ADB service")) warning = item.msg;
        }
        assertNotNull("foreign-address warning must be logged", warning);
        assertFalse(warning, warning.contains("203.0.113.77"));
        endpoint.stop();
    }
}
