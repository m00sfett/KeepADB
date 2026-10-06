package de.hohnepeople.keepadb;

import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowWifiInfo;

/** Shared fixtures of the force mode tests (#763): a controllable clock and small Wi-Fi helpers. */
final class KeepADBForceTestSupport {
    static final String SSID = "Cafe-WLAN";
    static final String BSSID = "aa:bb:cc:dd:ee:01";

    static final long MINUTE = 60_000L;
    static final long HOUR = 60L * MINUTE;
    static final long DAY = 24L * HOUR;

    private KeepADBForceTestSupport() {}

    /**
     * The three clocks of {@link KeepADBForceMode.Clock}, each moved on its own: {@link #advance}
     * is the normal passing of time, {@link #setWallClock} a clock set (NTP, the user, a daylight
     * saving time shift is not one: epoch time does not move) and {@link #reboot} a restart.
     */
    static final class TestClock implements KeepADBForceMode.Clock {
        long wall = 1_800_000_000_000L;
        long elapsed = 5L * HOUR;
        int boot = 41;
        boolean bootCountReadable = true;

        @Override
        public long wallMs() {
            return wall;
        }

        @Override
        public long elapsedMs() {
            return elapsed;
        }

        @Override
        public int bootCount(Context context) {
            return bootCountReadable ? boot : -1;
        }

        void advance(long ms) {
            wall += ms;
            elapsed += ms;
        }

        void setWallClock(long newWall) {
            wall = newWall;
        }

        /**
         * A restart after {@code offMs} of real time: the monotonic clock starts over, the boot
         * count moves on, the wall clock kept running (a real-time clock).
         */
        void reboot(long offMs, long bootedForMs) {
            wall += offMs + bootedForMs;
            elapsed = bootedForMs;
            boot++;
        }
    }

    static void connectTo(Context context, String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    static Notification posted(Context context, int id) {
        ShadowNotificationManager shadow = shadowOf(context.getSystemService(NotificationManager.class));
        return shadow.getNotification(id);
    }
}
