package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Stores manually maintained USB-ADB host profiles. */
final class KeepADBUsbProfile {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_ENABLED = "usb_notification_enabled";
    private static final String KEY_NEXT_ID = "usb_profile_next_id";
    private static final String KEY_SELECTED_ID = "usb_profile_selected_id";
    private static final String KEY_IDS = "usb_profile_ids";
    private static final String PREFIX = "usb_profile_";

    static final class Profile {
        final int id;
        final String name;
        final String ipAddress;
        final String hostname;
        final String tailnetHostname;

        Profile(int id, String name, String ipAddress, String hostname, String tailnetHostname) {
            this.id = id;
            this.name = name;
            this.ipAddress = ipAddress;
            this.hostname = hostname;
            this.tailnetHostname = tailnetHostname;
        }

        String summary() {
            StringBuilder value = new StringBuilder(name);
            append(value, ipAddress);
            append(value, hostname);
            append(value, tailnetHostname);
            return value.toString();
        }

        private static void append(StringBuilder value, String part) {
            if (part != null && !part.isEmpty()) value.append(" · ").append(part);
        }
    }

    private KeepADBUsbProfile() {}

    static boolean isNotificationEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setNotificationEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static List<Profile> getProfiles(Context context) {
        String ids = prefs(context).getString(KEY_IDS, "");
        List<Profile> profiles = new ArrayList<>();
        for (String value : ids.split(",")) {
            if (value.isEmpty()) continue;
            try {
                Profile profile = read(context, Integer.parseInt(value));
                if (profile != null) profiles.add(profile);
            } catch (NumberFormatException ignored) {
                // Ignore a malformed local entry and keep the remaining profiles usable.
            }
        }
        return profiles;
    }

    static Profile getSelected(Context context) {
        int selectedId = prefs(context).getInt(KEY_SELECTED_ID, 0);
        for (Profile profile : getProfiles(context)) {
            if (profile.id == selectedId) return profile;
        }
        return null;
    }

    static Profile add(Context context, String name, String ipAddress, String hostname,
                       String tailnetHostname) {
        SharedPreferences preferences = prefs(context);
        int id = preferences.getInt(KEY_NEXT_ID, 1);
        Profile profile = new Profile(id, clean(name), clean(ipAddress), clean(hostname),
                clean(tailnetHostname));
        write(preferences, profile);
        String ids = preferences.getString(KEY_IDS, "");
        preferences.edit()
                .putString(KEY_IDS, ids.isEmpty() ? String.valueOf(id) : ids + "," + id)
                .putInt(KEY_NEXT_ID, id + 1)
                .putInt(KEY_SELECTED_ID, id)
                .apply();
        return profile;
    }

    static void select(Context context, int id) {
        for (Profile profile : getProfiles(context)) {
            if (profile.id == id) {
                prefs(context).edit().putInt(KEY_SELECTED_ID, id).apply();
                return;
            }
        }
    }

    private static Profile read(Context context, int id) {
        SharedPreferences preferences = prefs(context);
        String name = preferences.getString(PREFIX + id + "_name", null);
        if (name == null || name.trim().isEmpty()) return null;
        return new Profile(id, name, preferences.getString(PREFIX + id + "_ip", ""),
                preferences.getString(PREFIX + id + "_host", ""),
                preferences.getString(PREFIX + id + "_tailnet", ""));
    }

    private static void write(SharedPreferences preferences, Profile profile) {
        preferences.edit()
                .putString(PREFIX + profile.id + "_name", profile.name)
                .putString(PREFIX + profile.id + "_ip", profile.ipAddress)
                .putString(PREFIX + profile.id + "_host", profile.hostname)
                .putString(PREFIX + profile.id + "_tailnet", profile.tailnetHostname)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
