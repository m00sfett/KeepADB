package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #1001: exports the user's settings to a file the user picks and imports them again, so a
 * reinstall does not mean setting everything up from scratch.
 *
 * <p><b>What is in the file.</b> Only the keys of {@link #RULES}: the protection level with the
 * trusted and blocked networks, the network rules, the privacy switches, the notification and
 * display options, the preselected timer duration and the webhook options, plus the webhook URL
 * unless the user leaves it out.
 * Everything else is deliberately absent: the Keep-Alive switch itself (an import must never lead
 * to Wifi-ADB being turned on), the webhook on/off state (an import never starts reporting),
 * run-time state such as force mode and a running timer, histories, diagnostics and bookkeeping, the app language
 * (Android keeps it per app) and the widgets (their IDs do not survive a reinstall). A secret such
 * as an automation token can never be exported: no rule matches it, and the export reads the rules,
 * never the whole preference file.
 *
 * <p><b>Strict import.</b> {@link #parse} checks the whole file before anything is written: the
 * format name, a supported version, the type and range of every known value and the consistency
 * of the network lists. Unknown keys are ignored (a newer app may add some); any invalid value
 * rejects the file. {@link #apply} then replaces every managed key in a single commit, so a file
 * is applied completely or not at all. The import grants no permission and changes neither
 * Wifi-ADB nor Keep-Alive.
 */
final class KeepADBSettingsBackup {
    static final String FORMAT = "keepadb-settings";
    static final int VERSION = 1;
    static final String MIME_TYPE = "application/json";
    /** Upper bound for a file the import reads; a real export is a few kilobytes. */
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_STRING_LENGTH = 1024;
    private static final int MAX_SET_SIZE = 2000;

    private static final String PREFS_NAME = "keepadb_prefs";
    static final String KEY_WEBHOOK_URL = "register_webhook_url";
    private static final String KEY_WEBHOOK_ENABLED = "register_webhook_enabled";
    private static final String KEY_TRUSTED_MODE = "trusted_network_mode";
    private static final String KEY_TRUSTED_MODE_INITIALIZED = "trusted_network_mode_initialized";
    private static final String KEY_TRUSTED_IDS = "trusted_network_ids";
    private static final String KEY_TRUSTED_NEXT_ID = "trusted_network_next_id";
    private static final String KEY_SSID_IDS = "trusted_ssid_ids";
    private static final String KEY_SSID_NEXT_ID = "trusted_ssid_next_id";
    private static final String KEY_BLOCKED_BSSIDS = KeepADBNetworkBlocklist.KEY_BSSIDS;

    private static final Pattern TRUSTED_FIELD =
            Pattern.compile("trusted_network_(\\d{1,9})_(label|bssid|name|ssid|security)");
    private static final Pattern SSID_FIELD = Pattern.compile("trusted_ssid_(\\d{1,9})_ssid");
    private static final Pattern BLOCKED_FIELD =
            Pattern.compile("blocked_bssid_(?:ssid|name)_(.+)");
    private static final Pattern ID_LIST = Pattern.compile("(\\d{1,9}(,\\d{1,9})*)?");

    enum Type { BOOLEAN, INT, STRING, STRING_SET }

    /** One exported key (or key family) with its type and the values it accepts. */
    static final class Rule {
        final Pattern key;
        final Type type;
        final Set<String> allowed;

        Rule(String keyRegex, Type type, String... allowed) {
            this.key = Pattern.compile(keyRegex);
            this.type = type;
            this.allowed = allowed.length == 0 ? null
                    : Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(allowed)));
        }
    }

    /** The complete list of what the file can contain; anything else is never written or read. */
    static final List<Rule> RULES;

    static {
        List<Rule> rules = new ArrayList<>();
        for (String key : new String[]{
                "hide_notification_enabled",
                "keep_display_on_enabled",
                KeepADBPreferences.KEY_NOTIFICATION_PRIVACY_ENABLED,
                KeepADBPreferences.KEY_WIDGET_PRIVACY_ENABLED,
                KeepADBPreferences.KEY_LOCKSCREEN_PRIVACY_ENABLED,
                "privacy_mode_enabled",
                "untrusted_shutdown_enabled",
                KeepADBPreferences.KEY_OPEN_NETWORK_SHUTDOWN,
                "register_webhook_periodic_enabled",
                "trusted_network_ssid_matching",
                KeepADBTrustedNetwork.KEY_TRUST_BY_NAME,
                KeepADBTrustedNetwork.KEY_SECURITY_CHECK,
        }) {
            rules.add(new Rule(Pattern.quote(key), Type.BOOLEAN));
        }
        List<String> intervals = new ArrayList<>();
        for (KeepADBPeriodicReport.Interval interval : KeepADBPeriodicReport.Interval.values()) {
            intervals.add(interval.id);
        }
        rules.add(new Rule(Pattern.quote("register_webhook_periodic_interval"), Type.STRING,
                intervals.toArray(new String[0])));
        // #1000: the preselected timer duration; the running timer is run-time state and absent.
        List<String> durations = new ArrayList<>();
        for (KeepADBTimer.Duration duration : KeepADBTimer.Duration.values()) {
            durations.add(duration.token);
        }
        rules.add(new Rule(Pattern.quote(KeepADBPreferences.KEY_TIMER_DEFAULT_DURATION), Type.STRING,
                durations.toArray(new String[0])));
        rules.add(new Rule(Pattern.quote(KEY_WEBHOOK_URL), Type.STRING));
        rules.add(new Rule(Pattern.quote(KEY_TRUSTED_MODE), Type.STRING,
                KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.MODE_ALLOWLIST));
        rules.add(new Rule(Pattern.quote(KEY_TRUSTED_IDS), Type.STRING));
        rules.add(new Rule(Pattern.quote(KEY_TRUSTED_NEXT_ID), Type.INT));
        rules.add(new Rule("trusted_network_\\d{1,9}_(label|bssid|name|ssid)", Type.STRING));
        rules.add(new Rule("trusted_network_\\d{1,9}_security", Type.INT));
        rules.add(new Rule(Pattern.quote(KEY_SSID_IDS), Type.STRING));
        rules.add(new Rule(Pattern.quote(KEY_SSID_NEXT_ID), Type.INT));
        rules.add(new Rule("trusted_ssid_\\d{1,9}_ssid", Type.STRING));
        rules.add(new Rule(Pattern.quote(KEY_BLOCKED_BSSIDS), Type.STRING_SET));
        rules.add(new Rule(Pattern.quote(KeepADBNetworkBlocklist.KEY_SSIDS), Type.STRING_SET));
        rules.add(new Rule("blocked_bssid_(ssid|name)_.+", Type.STRING));
        RULES = Collections.unmodifiableList(rules);
    }

    /** Why a file was rejected; the UI maps each to one message. */
    enum Error { NOT_A_SETTINGS_FILE, UNSUPPORTED_VERSION, INVALID_VALUE, TOO_LARGE, UNREADABLE, WRITE_FAILED }

    /** Thrown by {@link #parse}; nothing has been written when it is thrown. */
    static final class ImportException extends Exception {
        final Error error;

        ImportException(Error error, String detail) {
            super(error + ": " + detail);
            this.error = error;
        }
    }

    /** A validated file, ready for {@link #apply}. */
    static final class Snapshot {
        /** Managed key to value: Boolean, Integer, String or Set&lt;String&gt;. Sorted by key. */
        final Map<String, Object> values;

        Snapshot(Map<String, Object> values) {
            this.values = Collections.unmodifiableMap(new TreeMap<>(values));
        }

        boolean hasWebhookUrl() {
            return values.containsKey(KEY_WEBHOOK_URL);
        }

        Snapshot withoutWebhookUrl() {
            Map<String, Object> copy = new TreeMap<>(values);
            copy.remove(KEY_WEBHOOK_URL);
            return new Snapshot(copy);
        }

        int trustedAccessPointCount() {
            return idCount((String) values.get(KEY_TRUSTED_IDS));
        }

        int blockedCount() {
            int count = 0;
            for (String key : new String[]{KEY_BLOCKED_BSSIDS, KeepADBNetworkBlocklist.KEY_SSIDS}) {
                Object set = values.get(key);
                if (set instanceof Set) count += ((Set<?>) set).size();
            }
            return count;
        }
    }

    /** What {@link #apply} did beyond writing the values, for the result message. */
    static final class ApplyResult {
        /**
         * False when the preference file could not be written to storage. The values are then in
         * effect for this process (Android applies a commit in memory first) but may be lost.
         */
        final boolean saved;
        /** The file's different webhook URL was stored; reporting stays off until the user turns it on. */
        final boolean webhookUrlChanged;
        /**
         * The file's different webhook URL was NOT applied, because synchronization is on or a
         * withdrawal at the stored URL is still outstanding; the stored webhook stays as it is.
         */
        final boolean webhookUrlKept;

        ApplyResult(boolean saved, boolean webhookUrlChanged, boolean webhookUrlKept) {
            this.saved = saved;
            this.webhookUrlChanged = webhookUrlChanged;
            this.webhookUrlKept = webhookUrlKept;
        }
    }

    private KeepADBSettingsBackup() {}

    static Rule ruleFor(String key) {
        for (Rule rule : RULES) {
            if (rule.key.matcher(key).matches()) return rule;
        }
        return null;
    }

    // --- Export ---------------------------------------------------------------------------------

    /** The file content for the current settings of {@code context}. */
    static String export(Context context, boolean includeWebhookUrl, long nowMillis,
            String appVersion) throws JSONException {
        // Run the one-time migrations first, so the file names the level and the privacy
        // switches explicitly instead of relying on defaults.
        KeepADBTrustedNetwork.getMode(context);
        KeepADBPreferences.isNotificationPrivacyEnabled(context);
        return export(prefs(context).getAll(), includeWebhookUrl, nowMillis, appVersion);
    }

    /** Pure form of {@link #export(Context, boolean, long, String)} over a preference snapshot. */
    static String export(Map<String, ?> stored, boolean includeWebhookUrl, long nowMillis,
            String appVersion) throws JSONException {
        JSONObject settings = new JSONObject();
        Map<String, Object> managed = new TreeMap<>();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            if (ruleFor(entry.getKey()) != null) managed.put(entry.getKey(), entry.getValue());
        }
        dropOrphans(managed);
        for (Map.Entry<String, ?> entry : managed.entrySet()) {
            String key = entry.getKey();
            if (!includeWebhookUrl && KEY_WEBHOOK_URL.equals(key)) continue;
            Rule rule = ruleFor(key);
            if (rule == null) continue;
            Object value = entry.getValue();
            if (!hasType(value, rule.type)) continue;
            if (KEY_WEBHOOK_URL.equals(key)
                    && !KeepADBPreferences.isValidWebhookUrl((String) value)) {
                continue;
            }
            if (value instanceof Set) {
                List<String> sorted = new ArrayList<>();
                for (Object element : (Set<?>) value) sorted.add(String.valueOf(element));
                Collections.sort(sorted);
                settings.put(key, new JSONArray(sorted));
            } else {
                settings.put(key, value);
            }
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", nowMillis);
        if (appVersion != null) root.put("appVersion", appVersion);
        root.put("settings", settings);
        String content = root.toString(2);
        try {
            parse(content); // Self-check: never hand the user a file the import would reject.
        } catch (ImportException e) {
            throw new JSONException("export failed its own validation: " + e.getMessage());
        }
        return content;
    }

    /**
     * Drops name fields that belong to no listed entry. An older app version could lift a block or
     * drop an entry without knowing every field, so such leftovers can exist; they mean nothing to
     * the app and would make the strict import reject the file.
     */
    private static void dropOrphans(Map<String, Object> managed) {
        Set<Integer> trusted = ids(stringOrNull(managed.get(KEY_TRUSTED_IDS)));
        Set<Integer> ssids = ids(stringOrNull(managed.get(KEY_SSID_IDS)));
        Object blockedValue = managed.get(KEY_BLOCKED_BSSIDS);
        Set<?> blocked = blockedValue instanceof Set ? (Set<?>) blockedValue : Collections.emptySet();
        Iterator<String> keys = managed.keySet().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            Matcher trustedField = TRUSTED_FIELD.matcher(key);
            Matcher ssidField = SSID_FIELD.matcher(key);
            Matcher blockedField = BLOCKED_FIELD.matcher(key);
            if ((trustedField.matches() && !trusted.contains(Integer.valueOf(trustedField.group(1))))
                    || (ssidField.matches() && !ssids.contains(Integer.valueOf(ssidField.group(1))))
                    || (blockedField.matches() && !blocked.contains(blockedField.group(1)))) {
                keys.remove();
            }
        }
    }

    private static String stringOrNull(Object value) {
        return value instanceof String && ID_LIST.matcher((String) value).matches()
                ? (String) value : null;
    }

    private static boolean hasType(Object value, Type type) {
        switch (type) {
            case BOOLEAN: return value instanceof Boolean;
            case INT: return value instanceof Integer;
            case STRING: return value instanceof String;
            case STRING_SET: return value instanceof Set;
            default: return false;
        }
    }

    // --- Import ---------------------------------------------------------------------------------

    /** Validates a whole file; throws without side effects if anything is wrong. */
    static Snapshot parse(String content) throws ImportException {
        if (content == null) throw new ImportException(Error.UNREADABLE, "no content");
        if (content.length() > MAX_FILE_BYTES) throw new ImportException(Error.TOO_LARGE, "size");
        JSONObject root;
        try {
            root = new JSONObject(content);
        } catch (JSONException e) {
            throw new ImportException(Error.NOT_A_SETTINGS_FILE, "not JSON");
        }
        if (!FORMAT.equals(root.opt("format"))) {
            throw new ImportException(Error.NOT_A_SETTINGS_FILE, "format");
        }
        Object version = root.opt("version");
        if (!(version instanceof Integer)) {
            throw new ImportException(Error.NOT_A_SETTINGS_FILE, "version");
        }
        if ((Integer) version != VERSION) {
            throw new ImportException(Error.UNSUPPORTED_VERSION, String.valueOf(version));
        }
        Object settingsValue = root.opt("settings");
        if (!(settingsValue instanceof JSONObject)) {
            throw new ImportException(Error.NOT_A_SETTINGS_FILE, "settings");
        }
        JSONObject settings = (JSONObject) settingsValue;
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Rule rule = ruleFor(key);
            if (rule == null) continue; // Unknown keys are ignored, e.g. from a newer version.
            values.put(key, convert(key, rule, settings.opt(key)));
        }
        checkConsistency(values);
        return new Snapshot(values);
    }

    private static Object convert(String key, Rule rule, Object raw) throws ImportException {
        switch (rule.type) {
            case BOOLEAN:
                if (raw instanceof Boolean) return raw;
                break;
            case INT:
                if (raw instanceof Integer && (Integer) raw >= 0) return raw;
                break;
            case STRING:
                if (raw instanceof String && isAcceptableString(key, rule, (String) raw)) {
                    if (KEY_WEBHOOK_URL.equals(key)) {
                        return KeepADBPreferences.sanitizeWebhookUrl((String) raw).trim();
                    }
                    return raw;
                }
                break;
            case STRING_SET:
                if (raw instanceof JSONArray) return convertSet(key, (JSONArray) raw);
                break;
            default:
                break;
        }
        throw new ImportException(Error.INVALID_VALUE, key);
    }

    private static boolean isAcceptableString(String key, Rule rule, String value) {
        // Control characters are fine in a network name the app itself accepted (JSON escapes
        // them); the fixed values, the URL and the ID lists exclude them by their own rules.
        if (value.length() > MAX_STRING_LENGTH) return false;
        if (rule.allowed != null) return rule.allowed.contains(value);
        if (KEY_WEBHOOK_URL.equals(key)) {
            if (!KeepADBPreferences.isValidWebhookUrl(value)) return false;
            String sanitized = KeepADBPreferences.sanitizeWebhookUrl(value);
            return sanitized != null && KeepADBPreferences.isValidWebhookUrl(sanitized);
        }
        if (KEY_TRUSTED_IDS.equals(key) || KEY_SSID_IDS.equals(key)) {
            return ID_LIST.matcher(value).matches();
        }
        return !value.isEmpty();
    }

    private static Set<String> convertSet(String key, JSONArray array) throws ImportException {
        if (array.length() > MAX_SET_SIZE) throw new ImportException(Error.INVALID_VALUE, key);
        Set<String> set = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof String)) throw new ImportException(Error.INVALID_VALUE, key);
            String value = (String) element;
            if (value.isEmpty() || value.length() > MAX_STRING_LENGTH) {
                throw new ImportException(Error.INVALID_VALUE, key);
            }
            // The blocklist stores and looks up its canonical forms only; any other form would be
            // accepted here and then never match.
            if (KEY_BLOCKED_BSSIDS.equals(key)
                    && !value.equals(KeepADBNetworkBlocklist.normalizeBssid(value))) {
                throw new ImportException(Error.INVALID_VALUE, key);
            }
            if (KeepADBNetworkBlocklist.KEY_SSIDS.equals(key)
                    && !KeepADBNetworkBlocklist.isUsableSsid(value)) {
                throw new ImportException(Error.INVALID_VALUE, key);
            }
            set.add(value);
        }
        return set;
    }

    /**
     * The network lists must hang together the way the app writes them: every listed entry has its
     * address (or name), no field belongs to an unlisted entry, and the next free ID is above every
     * listed one. A file that breaks this was edited or damaged and is rejected as a whole.
     */
    private static void checkConsistency(Map<String, Object> values) throws ImportException {
        Set<Integer> trusted = ids((String) values.get(KEY_TRUSTED_IDS));
        Set<Integer> ssids = ids((String) values.get(KEY_SSID_IDS));
        Object blockedValue = values.get(KEY_BLOCKED_BSSIDS);
        Set<?> blocked = blockedValue instanceof Set ? (Set<?>) blockedValue : Collections.emptySet();
        for (String key : values.keySet()) {
            Matcher trustedField = TRUSTED_FIELD.matcher(key);
            if (trustedField.matches() && !trusted.contains(Integer.valueOf(trustedField.group(1)))) {
                throw new ImportException(Error.INVALID_VALUE, key);
            }
            Matcher ssidField = SSID_FIELD.matcher(key);
            if (ssidField.matches() && !ssids.contains(Integer.valueOf(ssidField.group(1)))) {
                throw new ImportException(Error.INVALID_VALUE, key);
            }
            Matcher blockedField = BLOCKED_FIELD.matcher(key);
            if (blockedField.matches() && !blocked.contains(blockedField.group(1))) {
                throw new ImportException(Error.INVALID_VALUE, key);
            }
        }
        for (Integer id : trusted) {
            if (!values.containsKey("trusted_network_" + id + "_bssid")) {
                throw new ImportException(Error.INVALID_VALUE, KEY_TRUSTED_IDS);
            }
        }
        for (Integer id : ssids) {
            if (!values.containsKey("trusted_ssid_" + id + "_ssid")) {
                throw new ImportException(Error.INVALID_VALUE, KEY_SSID_IDS);
            }
        }
        checkNextId(values, KEY_TRUSTED_NEXT_ID, trusted);
        checkNextId(values, KEY_SSID_NEXT_ID, ssids);
    }

    private static void checkNextId(Map<String, Object> values, String key, Set<Integer> ids)
            throws ImportException {
        if (ids.isEmpty()) return;
        Object next = values.get(key);
        if (!(next instanceof Integer) || (Integer) next <= Collections.max(ids)) {
            throw new ImportException(Error.INVALID_VALUE, key);
        }
    }

    private static Set<Integer> ids(String list) {
        Set<Integer> ids = new HashSet<>();
        if (list == null || list.isEmpty()) return ids;
        for (String part : list.split(",")) ids.add(Integer.valueOf(part));
        return ids;
    }

    private static int idCount(String list) {
        return ids(list).size();
    }

    /**
     * Replaces the managed settings with {@code snapshot} in one commit. A key the file does not
     * name returns to its default, with one exception: without a webhook URL in the file the stored
     * URL and its on/off state stay as they are.
     *
     * <p>A different URL replaces the stored one only while nothing may be registered at the stored
     * one: synchronization off and no withdrawal outstanding ({@link
     * KeepADBRegisterClient#hasOutstandingWithdrawal}). Then synchronization stays off until the
     * user turns it on. Otherwise the file's URL is left out and the stored webhook stays as it is,
     * so the withdrawal and retry rules of the webhook settings keep owning a URL change; the user
     * changes it there. After a reinstall nothing is stored, so the file's URL applies.
     */
    static ApplyResult apply(Context context, Snapshot snapshot) {
        SharedPreferences prefs = prefs(context);
        boolean urlChanged = webhookUrlChanges(prefs, snapshot);
        boolean keepUrl = urlChanged && (prefs.getBoolean(KEY_WEBHOOK_ENABLED, false)
                || KeepADBRegisterClient.hasOutstandingWithdrawal(context));
        Snapshot effective = keepUrl ? snapshot.withoutWebhookUrl() : snapshot;
        boolean saved = commit(prefs, effective);
        return new ApplyResult(saved, urlChanged && !keepUrl, keepUrl);
    }

    private static boolean webhookUrlChanges(SharedPreferences prefs, Snapshot snapshot) {
        return snapshot.hasWebhookUrl()
                && !snapshot.values.get(KEY_WEBHOOK_URL).equals(prefs.getString(KEY_WEBHOOK_URL, null));
    }

    /** The single write of {@link #apply}, separate so tests can run it on a plain preference file. */
    static boolean commit(SharedPreferences prefs, Snapshot snapshot) {
        boolean urlChanged = webhookUrlChanges(prefs, snapshot);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (ruleFor(key) == null) continue;
            if (KEY_WEBHOOK_URL.equals(key) && !snapshot.hasWebhookUrl()) continue;
            editor.remove(key);
        }
        for (Map.Entry<String, Object> entry : snapshot.values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) value;
                editor.putStringSet(entry.getKey(), new HashSet<>(set));
            } else {
                editor.putString(entry.getKey(), (String) value);
            }
        }
        // A new destination never starts reporting on its own; the user switches it on.
        if (urlChanged) editor.putBoolean(KEY_WEBHOOK_ENABLED, false);
        // The imported values are explicit choices: keep the one-time migrations from rewriting them.
        if (snapshot.values.containsKey(KEY_TRUSTED_MODE)) {
            editor.putBoolean(KEY_TRUSTED_MODE_INITIALIZED, true);
        }
        editor.putBoolean(KeepADBPreferences.KEY_PRIVACY_SPLIT_INITIALIZED, true);
        return editor.commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
