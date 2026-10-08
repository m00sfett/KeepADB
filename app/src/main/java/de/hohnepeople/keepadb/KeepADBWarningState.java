package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Serialized, additive display state. It never changes a protection or ADB preference. */
final class KeepADBWarningState {
    static final Object LOCK = new Object();
    static final String KEY = "warnings_v1_state";
    static final String FORCE_EPISODE = "warnings_force_episode";
    static final String FORCE_FINGERPRINT = "warnings_force_fingerprint";
    enum Card { SYSTEM, FORCE, LESS_SECURE, PAUSED, LIMITED }
    enum Reason {
        SYSTEM_PERMISSION("system_permission", Card.SYSTEM, true, false,
                R.string.home_warning_system_title),
        FORCE_MODE("force_mode", Card.FORCE, true, false, R.string.force_card_title),
        PROTECTION_ALL_WIFI("protection_all_wifi", Card.LESS_SECURE, true, true,
                R.string.network_mode_option_all_wifi),
        NOTIFICATION_DETAILS("notification_details", Card.LESS_SECURE, true, true,
                R.string.settings_notification_details_toggle),
        WEBHOOK_CLEARTEXT("webhook_cleartext", Card.LESS_SECURE, true, true,
                R.string.settings_webhook_cleartext_warning),
        NETWORK_IDENTITY_UNAVAILABLE("network_identity_unavailable", Card.PAUSED, false, true,
                R.string.home_warning_paused_title),
        NOTIFICATIONS_MISSING("notifications_missing", Card.LIMITED, false, true,
                R.string.notification_permission_panel_title),
        BACKGROUND_LOCATION_MISSING("background_location_missing", Card.LIMITED, false, true,
                R.string.onboarding_perm_background_title),
        BATTERY_EXEMPTION_MISSING("battery_exemption_missing", Card.LIMITED, false, true,
                R.string.onboarding_perm_battery_title);
        final String id;
        final Card card;
        final boolean security;
        final boolean mutable;
        final int label;
        Reason(String id, Card card, boolean security, boolean mutable, int label) {
            this.id = id; this.card = card; this.security = security;
            this.mutable = mutable; this.label = label;
        }
    }
    static final class Snapshot {
        final Set<Reason> active;
        final Set<Reason> muted;
        final Set<Card> visible;
        final String forceEpisode;
        Snapshot(Set<Reason> active, Set<Reason> muted, Set<Card> visible, String episode) {
            this.active = Collections.unmodifiableSet(EnumSet.copyOf(active));
            this.muted = Collections.unmodifiableSet(EnumSet.copyOf(muted));
            this.visible = Collections.unmodifiableSet(EnumSet.copyOf(visible));
            this.forceEpisode = episode;
        }
        Set<Reason> reasons(Card card) {
            Set<Reason> result = EnumSet.noneOf(Reason.class);
            for (Reason reason : active) if (reason.card == card && !muted.contains(reason)) {
                result.add(reason);
            }
            return result;
        }
        List<Reason> security() {
            List<Reason> result = new ArrayList<>();
            for (Reason reason : active) if (reason.security && !muted.contains(reason)) result.add(reason);
            return result;
        }
    }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
    private static boolean writable(SharedPreferences prefs) {
        if (!prefs.contains(KEY)) return true;
        try {
            JSONObject state = new JSONObject(prefs.getString(KEY, ""));
            Object schema = state.opt("schema");
            if (!(schema instanceof Number) || ((Number) schema).doubleValue() != 1d) return false;
            java.util.Iterator<String> keys = state.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.equals("muted") || key.endsWith("_observed") || key.endsWith("_dismissed")) {
                    Object value = state.get(key);
                    if (!(value instanceof String)) return false;
                    for (String id : ((String) value).split("\\|")) {
                        if (id.isEmpty()) continue;
                        boolean known = false;
                        for (Reason reason : Reason.values()) if (reason.id.equals(id)) known = true;
                        if (!known) return false;
                    }
                }
            }
            return true;
        } catch (Exception invalid) { return false; }
    }
    private static JSONObject read(SharedPreferences prefs) {
        if (!writable(prefs)) return new JSONObject();
        try {
            JSONObject state = new JSONObject(prefs.getString(KEY, "{}"));
            return state.optInt("schema") == 1 ? state : new JSONObject();
        } catch (Exception invalid) { return new JSONObject(); }
    }
    private static String string(SharedPreferences prefs, String key) {
        try {
            String value = prefs.getString(key, "");
            return value == null ? "" : value;
        } catch (ClassCastException invalid) { return ""; }
    }
    private static Set<Reason> decode(String signature, Card card, boolean mutes) {
        Set<Reason> result = EnumSet.noneOf(Reason.class);
        if (signature == null) return result;
        for (Reason reason : Reason.values()) {
            if ((card == null || reason.card == card) && (!mutes || reason.mutable)) {
                for (String id : signature.split("\\|")) if (reason.id.equals(id)) result.add(reason);
            }
        }
        return result;
    }
    private static String signature(Set<Reason> reasons) {
        Set<String> ids = new TreeSet<>();
        for (Reason reason : reasons) ids.add(reason.id);
        return String.join("|", ids);
    }
    private static void put(JSONObject state, String key, Object value) {
        try { state.put(key, value); } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static boolean save(SharedPreferences prefs, JSONObject state) {
        if (!writable(prefs)) return false;
        put(state, "schema", 1);
        return prefs.edit().putString(KEY, state.toString()).commit();
    }
    /** Unknown force writes are a new conservative episode; known rebases preserve the token. */
    static SharedPreferences.Editor forceWrite(SharedPreferences prefs,
            SharedPreferences.Editor editor, String oldState, String newState, boolean activation) {
        String previous = string(prefs, FORCE_FINGERPRINT);
        boolean known = previous.equals(fingerprint(oldState));
        if (activation || !known || string(prefs, FORCE_EPISODE).isEmpty()) {
            editor.putString(FORCE_EPISODE, UUID.randomUUID().toString());
        }
        return editor.putString(FORCE_FINGERPRINT, fingerprint(newState));
    }
    private static String fingerprint(String raw) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((raw == null ? "" : raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    static Snapshot observe(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            JSONObject state = read(prefs);
            Set<Reason> active = KeepADBHomeWarnings.reasons(context);
            Set<Reason> muted = decode(state.optString("muted"), null, true);
            String episode = string(prefs, FORCE_EPISODE);
            if (active.contains(Reason.FORCE_MODE)) {
                String raw = string(prefs, KeepADBForceMode.KEY_STATE);
                if (episode.isEmpty() || !string(prefs, FORCE_FINGERPRINT).equals(fingerprint(raw))) {
                    episode = UUID.randomUUID().toString();
                    // If persistence fails, the current read still displays force conservatively.
                    prefs.edit().putString(FORCE_EPISODE, episode)
                            .putString(FORCE_FINGERPRINT, fingerprint(raw)).commit();
                }
            }
            Set<Card> visible = EnumSet.noneOf(Card.class);
            for (Card card : Card.values()) {
                String key = card.name();
                Set<Reason> current = EnumSet.noneOf(Reason.class);
                for (Reason reason : active) if (reason.card == card) current.add(reason);
                Set<Reason> acknowledged = decode(state.optString(key + "_dismissed"), card, false);
                acknowledged.retainAll(current);
                if (card == Card.FORCE && !episode.equals(state.optString("force_dismissed_episode"))) {
                    acknowledged.clear();
                }
                put(state, key + "_observed", signature(current));
                put(state, key + "_dismissed", signature(acknowledged));
                Set<Reason> eligible = EnumSet.copyOf(current);
                eligible.removeAll(muted);
                if (!eligible.isEmpty() && !acknowledged.containsAll(eligible)) visible.add(card);
            }
            if (!save(prefs, state)) {
                // Failed storage must not hide a critical warning.
                if (active.contains(Reason.SYSTEM_PERMISSION)) visible.add(Card.SYSTEM);
                if (active.contains(Reason.FORCE_MODE)) visible.add(Card.FORCE);
            }
            // Suppression is applied only after recording all raw causes.
            if (active.contains(Reason.SYSTEM_PERMISSION)) {
                visible.remove(Card.PAUSED); visible.remove(Card.LIMITED);
            } else if (active.contains(Reason.NETWORK_IDENTITY_UNAVAILABLE)) {
                visible.remove(Card.LIMITED);
            }
            int count = 0;
            for (Card card : Card.values()) if (visible.contains(card) && ++count > 3) visible.remove(card);
            return new Snapshot(active, muted, visible, episode);
        }
    }
    static boolean dismiss(Context context, Card card, Snapshot shown) {
        synchronized (LOCK) {
            Snapshot current = observe(context);
            JSONObject state = read(prefs(context));
            Set<Reason> acknowledged = shown.reasons(card);
            acknowledged.retainAll(current.active);
            if (card == Card.FORCE && !shown.forceEpisode.equals(current.forceEpisode)) return false;
            put(state, card.name() + "_dismissed", signature(acknowledged));
            if (card == Card.FORCE) put(state, "force_dismissed_episode", shown.forceEpisode);
            return save(prefs(context), state);
        }
    }
    static boolean undo(Context context, Card card, Snapshot shown) {
        synchronized (LOCK) {
            Snapshot current = observe(context);
            if (card == Card.FORCE && !shown.forceEpisode.equals(current.forceEpisode)) return false;
            JSONObject state = read(prefs(context));
            Set<Reason> acknowledged = decode(state.optString(card.name() + "_dismissed"), card, false);
            acknowledged.removeAll(shown.reasons(card));
            put(state, card.name() + "_dismissed", signature(acknowledged));
            return save(prefs(context), state);
        }
    }
    static boolean mute(Context context, Reason reason, boolean muted) {
        if (!reason.mutable) return false;
        synchronized (LOCK) {
            observe(context);
            SharedPreferences prefs = prefs(context);
            JSONObject state = read(prefs);
            Set<Reason> reasons = decode(state.optString("muted"), null, true);
            if (muted) reasons.add(reason); else reasons.remove(reason);
            put(state, "muted", signature(reasons));
            if (!muted) {
                String key = reason.card.name() + "_dismissed";
                Set<Reason> acknowledged = decode(state.optString(key), reason.card, false);
                acknowledged.remove(reason);
                put(state, key, signature(acknowledged));
            }
            return save(prefs, state);
        }
    }
    /** The feedback action cannot mute a reason that was never shown or has since gone away. */
    static boolean muteClosed(Context context, Reason reason, Card card, Snapshot shown) {
        synchronized (LOCK) {
            Snapshot current = observe(context);
            if (!shown.reasons(card).contains(reason) || !current.active.contains(reason)) return false;
            return mute(context, reason, true);
        }
    }

    static void resetMutes(Context context) {
        synchronized (LOCK) {
            observe(context);
            SharedPreferences prefs = prefs(context);
            JSONObject state = read(prefs);
            Set<Reason> muted = decode(state.optString("muted"), null, true);
            for (Card card : Card.values()) {
                String key = card.name() + "_dismissed";
                Set<Reason> acknowledged = decode(state.optString(key), card, false);
                acknowledged.removeAll(muted);
                put(state, key, signature(acknowledged));
            }
            put(state, "muted", "");
            save(prefs, state);
        }
    }
    static Intent reviewIntent(Context context, Reason reason) {
        Intent settings = new Intent(context, SettingsActivity.class);
        switch (reason) {
            case SYSTEM_PERMISSION: return KeepADBHomeWarnings.systemIntent(context);
            case FORCE_MODE: return settings.putExtra(SettingsActivity.EXTRA_FOCUS_FORCE, true);
            case PROTECTION_ALL_WIFI: return settings.putExtra(SettingsActivity.EXTRA_FOCUS_NETWORK, true);
            case NOTIFICATION_DETAILS: return settings.putExtra(SettingsActivity.EXTRA_FOCUS_DETAILS, true);
            case WEBHOOK_CLEARTEXT: return settings.putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true);
            case NETWORK_IDENTITY_UNAVAILABLE:
                return KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(context);
            default:
                String item = reason == Reason.NOTIFICATIONS_MISSING
                        ? OnboardingActionSteps.Permissions.ITEM_NOTIFICATIONS
                        : reason == Reason.BACKGROUND_LOCATION_MISSING
                        ? OnboardingActionSteps.Permissions.ITEM_BACKGROUND_LOCATION
                        : OnboardingActionSteps.Permissions.ITEM_BATTERY;
                return OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS)
                        .putExtra(OnboardingActivity.EXTRA_FOCUS_ITEM, item);
        }
    }
}
