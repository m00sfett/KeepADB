package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * #762: what the single "Networks" list shows -- the current network on top and the saved ones
 * grouped by Wi-Fi name, each with one status. Pure derivation, no view and no write: the list
 * only <em>describes</em>.
 *
 * <p><strong>One rule, not two.</strong> Every status comes from {@link
 * KeepADBTrustedNetwork#evaluate}, the single implementation of the precedence (#760): a block on
 * the access point or on its Wi-Fi name beats every kind of trust. A row whose access point is
 * trusted but whose name is blocked is therefore {@link Status#BLOCKED} with {@link
 * Reason#NAME}, never {@link Status#TRUSTED} -- the list can not promise a network that does not
 * switch on. The trusted entry stays stored and applies again once the name block is lifted.
 *
 * <p>The force mode (#763) is no part of the status: it overrides blocks and trust for a while but
 * changes nothing that is stored, so the list keeps describing what is stored and the view says
 * that the mode is on.
 *
 * <p>Rows are the trusted entries and the blocked access points; a blocked Wi-Fi name without a
 * saved access point is a group of its own. The Wi-Fi name of a row is the one its trust was
 * given under (the label), else the current connection's, else the name stored with its trusted
 * entry or its block (#796); an address with none of them is listed without a name. A stored name
 * is what lets a trusted access point whose label is only its BSSID read "blocked" when its name
 * is blocked: the status is derived from that name like from any other. It is the name the access
 * point was known under when it was trusted or blocked, so it can be stale; it is used for this
 * description only and decides nothing (the connection decision reads the live name).
 */
final class KeepADBNetworkList {

    /** The one status a network is shown with. */
    enum Status {
        TRUSTED,
        /** Not trusted itself, but accepted because its Wi-Fi name is trusted (comfort switch). */
        TRUSTED_BY_NAME,
        BLOCKED,
        UNKNOWN,
        /** Android does not tell which access point the device is connected to. */
        UNREADABLE,
        NO_WIFI
    }

    /** Why a {@link Status#BLOCKED} network is blocked. */
    enum Reason { NONE, ACCESS_POINT, NAME }

    /** One saved access point. */
    static final class Row {
        final String bssid;
        /** The Wi-Fi name it is known under, or null. */
        final String ssid;
        /**
         * Whether {@link #ssid} is only the name stored with the entry or the block (#796), not a
         * name the trust label or the current connection carries.
         */
        final boolean ssidStored;
        /** The own name the user gave it (entry or block, #802), or null. */
        final String customName;
        /** The trusted entry, or null for an access point that is only blocked. */
        final KeepADBTrustedNetwork.Entry entry;
        final Status status;
        final Reason reason;
        final boolean current;

        Row(String bssid, String ssid, boolean ssidStored, String customName,
            KeepADBTrustedNetwork.Entry entry, Status status, Reason reason, boolean current) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.ssidStored = ssidStored;
            this.customName = customName;
            this.entry = entry;
            this.status = status;
            this.reason = reason;
            this.current = current;
        }

        /**
         * The name a trust label may carry for this row: the name of the label or of the current
         * connection, never one that is only stored. Keeps a trust given from the list exactly as
         * it was before names were stored (the label, and with it the derived name rule, does not
         * gain a name).
         */
        String labelSsid() {
            return ssidStored ? null : ssid;
        }

        boolean accessPointBlocked() {
            return status == Status.BLOCKED && reason == Reason.ACCESS_POINT;
        }

        boolean nameBlocked() {
            return status == Status.BLOCKED && reason == Reason.NAME;
        }
    }

    /** The saved access points of one Wi-Fi name, or of no known name ({@code ssid == null}). */
    static final class Group {
        final String ssid;
        final boolean nameBlocked;
        final List<Row> rows;

        Group(String ssid, boolean nameBlocked, List<Row> rows) {
            this.ssid = ssid;
            this.nameBlocked = nameBlocked;
            this.rows = rows;
        }
    }

    /** The network the device is connected to right now. */
    static final class Current {
        final Status status;
        final Reason reason;
        /** Null when the name is not readable. */
        final String ssid;
        /** Null when the address is not readable. */
        final String bssid;
        /**
         * Trusted only because the previous setting "In all Wi-Fi networks" is in force, not
         * because the access point or its name is trusted.
         */
        final boolean legacyOnly;
        /** Its saved row (trusted or blocked), or null. */
        final Row row;

        Current(Status status, Reason reason, String ssid, String bssid, boolean legacyOnly,
                Row row) {
            this.status = status;
            this.reason = reason;
            this.ssid = ssid;
            this.bssid = bssid;
            this.legacyOnly = legacyOnly;
            this.row = row;
        }
    }

    static final class Snapshot {
        final Current current;
        final List<Group> groups;
        /** Rows shown as trusted. */
        final int trusted;
        /** Rows shown as blocked plus blocked names without a saved access point. */
        final int blocked;

        Snapshot(Current current, List<Group> groups, int trusted, int blocked) {
            this.current = current;
            this.groups = groups;
            this.trusted = trusted;
            this.blocked = blocked;
        }

        boolean isEmpty() {
            return groups.isEmpty();
        }
    }

    private KeepADBNetworkList() {}

    /**
     * Builds the list from the stored trust, blocks and the given connection.
     *
     * @param identity what the device is connected to, as read by the caller
     * @param wifiConnected whether there is a Wi-Fi connection at all (an unreadable network and no
     *     network differ in what the user can do about them)
     */
    static Snapshot build(Context context, KeepADBNetworkIdentity identity, boolean wifiConnected) {
        KeepADBNetworkIdentity live = identity == null
                ? new KeepADBNetworkIdentity(null, null) : identity;
        String currentBssid = live.isKnown() ? upper(live.bssid) : null;
        String currentSsid = live.displaySsid();

        Set<String> blockedSsids = new TreeSet<>(KeepADBNetworkBlocklist.getBlockedSsids(context));

        // One row per access point: trusted entries first, then the ones that are only blocked.
        Map<String, KeepADBTrustedNetwork.Entry> entries = new LinkedHashMap<>();
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
            entries.put(upper(entry.bssid), entry);
        }
        Map<String, String> addresses = new LinkedHashMap<>();
        for (KeepADBTrustedNetwork.Entry entry : entries.values()) {
            addresses.put(upper(entry.bssid), entry.bssid);
        }
        Map<String, KeepADBNetworkBlocklist.BlockedAccessPoint> blocks = new HashMap<>();
        for (KeepADBNetworkBlocklist.BlockedAccessPoint block
                : KeepADBNetworkBlocklist.getBlockedAccessPoints(context)) {
            addresses.putIfAbsent(upper(block.bssid), block.bssid);
            blocks.put(upper(block.bssid), block);
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> address : addresses.entrySet()) {
            String key = address.getKey();
            KeepADBTrustedNetwork.Entry entry = entries.get(key);
            KeepADBNetworkBlocklist.BlockedAccessPoint block = blocks.get(key);
            boolean current = key.equals(currentBssid);
            // The name of the label first, then the live one, then what was stored (#796): the
            // order keeps every row that had a name as before and only fills the ones without.
            String ssid = entry == null ? null : entry.ssid();
            if (ssid == null && current) ssid = currentSsid;
            boolean stored = false;
            if (ssid == null) {
                ssid = entry != null && entry.savedSsid != null ? entry.savedSsid
                        : block != null ? block.ssid : null;
                stored = ssid != null;
            }
            String customName = entry != null && entry.customName != null ? entry.customName
                    : block != null ? block.customName : null;
            rows.add(row(context, address.getValue(), ssid, stored, customName, entry, current));
        }

        Map<String, List<Row>> bySsid = new HashMap<>();
        List<Row> unnamed = new ArrayList<>();
        for (Row row : rows) {
            if (row.ssid == null) {
                unnamed.add(row);
            } else {
                bySsid.computeIfAbsent(row.ssid, k -> new ArrayList<>()).add(row);
            }
        }
        for (String blockedName : blockedSsids) {
            bySsid.computeIfAbsent(blockedName, k -> new ArrayList<>());
        }

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Row>> named : bySsid.entrySet()) {
            groups.add(new Group(named.getKey(), blockedSsids.contains(named.getKey()),
                    sortRows(named.getValue())));
        }
        groups.sort(GROUP_ORDER);
        if (!unnamed.isEmpty()) groups.add(new Group(null, false, sortRows(unnamed)));

        int trusted = 0;
        int blocked = 0;
        for (Group group : groups) {
            if (group.rows.isEmpty() && group.nameBlocked) blocked++;
            for (Row row : group.rows) {
                if (row.status == Status.TRUSTED) trusted++;
                if (row.status == Status.BLOCKED) blocked++;
            }
        }

        Row currentRow = null;
        for (Row row : rows) {
            if (row.current) currentRow = row;
        }
        return new Snapshot(current(context, live, wifiConnected, currentRow), groups, trusted,
                blocked);
    }

    /**
     * The status of one access point with the given Wi-Fi name, through the policy. A saved row is
     * either trusted or blocked, so "unknown" is what follows once either is taken away.
     */
    static Status statusOf(Context context, String ssid, String bssid) {
        return row(context, bssid, ssid, false, null, null, false).status;
    }

    private static Row row(Context context, String bssid, String ssid, boolean ssidStored,
                           String customName, KeepADBTrustedNetwork.Entry entry, boolean current) {
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluate(context,
                new KeepADBNetworkIdentity(ssid, bssid));
        Status status;
        Reason reason = Reason.NONE;
        switch (decision) {
            case BLOCKED_ACCESS_POINT:
                status = Status.BLOCKED;
                reason = Reason.ACCESS_POINT;
                break;
            case BLOCKED_NAME:
                status = Status.BLOCKED;
                reason = Reason.NAME;
                break;
            case TRUSTED_ACCESS_POINT:
                status = Status.TRUSTED;
                break;
            case TRUSTED_NAME:
                status = Status.TRUSTED_BY_NAME;
                break;
            default:
                // Neither trusted nor blocked: what is left once a saved row loses its reason to be
                // saved. The legacy "in all networks" policy does not make an access point trusted.
                status = Status.UNKNOWN;
                break;
        }
        return new Row(bssid, ssid, ssidStored, customName, entry, status, reason, current);
    }

    private static Current current(Context context, KeepADBNetworkIdentity live,
                                   boolean wifiConnected, Row savedRow) {
        String ssid = live.displaySsid();
        String bssid = live.isKnown() ? live.bssid : null;
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluate(context, live);
        switch (decision) {
            case BLOCKED_ACCESS_POINT:
                return new Current(Status.BLOCKED, Reason.ACCESS_POINT, ssid, bssid, false, savedRow);
            case BLOCKED_NAME:
                return new Current(Status.BLOCKED, Reason.NAME, ssid, bssid, false, savedRow);
            case TRUSTED_ACCESS_POINT:
                return new Current(Status.TRUSTED, Reason.NONE, ssid, bssid, false, savedRow);
            case TRUSTED_NAME:
                return new Current(Status.TRUSTED_BY_NAME, Reason.NONE, ssid, bssid, false, null);
            case UNKNOWN_NETWORK:
                return new Current(Status.UNKNOWN, Reason.NONE, ssid, bssid, false, null);
            case LEGACY_ALL_WIFI:
                if (live.isKnown()) {
                    return new Current(Status.TRUSTED, Reason.NONE, ssid, bssid, true, null);
                }
                // fall through: unreadable
            case IDENTITY_UNAVAILABLE:
            default:
                return new Current(wifiConnected ? Status.UNREADABLE : Status.NO_WIFI, Reason.NONE,
                        ssid, bssid, false, null);
        }
    }

    private static List<Row> sortRows(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(ROW_ORDER);
        return Collections.unmodifiableList(sorted);
    }

    /** Trusted before blocked, then rows with an own name (alphabetical), then by address. */
    private static final Comparator<Row> ROW_ORDER = (a, b) -> {
        int byStatus = Integer.compare(rank(a.status), rank(b.status));
        if (byStatus != 0) return byStatus;
        // Access points with an own name first (they are the ones the user recognizes).
        boolean aNamed = !ownName(a).isEmpty();
        boolean bNamed = !ownName(b).isEmpty();
        if (aNamed != bNamed) return aNamed ? -1 : 1;
        int byName = String.CASE_INSENSITIVE_ORDER.compare(ownName(a), ownName(b));
        if (byName != 0) return byName;
        return String.CASE_INSENSITIVE_ORDER.compare(a.bssid, b.bssid);
    };

    /**
     * Groups with trusted access points first, then mixed ones, then those that are only blocked;
     * alphabetical within a class.
     */
    private static final Comparator<Group> GROUP_ORDER = (a, b) -> {
        int byClass = Integer.compare(groupClass(a), groupClass(b));
        if (byClass != 0) return byClass;
        return String.CASE_INSENSITIVE_ORDER.compare(a.ssid, b.ssid);
    };

    private static int rank(Status status) {
        return status == Status.TRUSTED ? 0 : 1;
    }

    private static int groupClass(Group group) {
        boolean trusted = false;
        boolean blocked = group.rows.isEmpty() && group.nameBlocked;
        for (Row row : group.rows) {
            if (row.status == Status.TRUSTED) trusted = true; else blocked = true;
        }
        if (trusted && !blocked) return 0;
        if (trusted) return 1;
        return 2;
    }

    private static String ownName(Row row) {
        return row.customName != null ? row.customName : "";
    }

    private static String upper(String bssid) {
        return bssid.toUpperCase(Locale.ROOT);
    }
}
