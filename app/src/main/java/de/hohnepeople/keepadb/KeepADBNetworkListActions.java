package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #762: what a tap in the network list changes, kept apart from the views so the same answer is
 * given wherever it is asked. Trusting a current, undecided network is not here: the list asks
 * {@link NetworkDecisionView} (#766) for that and has no dialog of its own.
 *
 * <p>The rules every answer follows:
 * <ul>
 *   <li>A block is only ever lifted explicitly, by {@link #liftAccessPointBlock} or {@link
 *       #liftNameBlock}. Trusting never lifts one, and a trust that a block still refuses says so.
 *   <li>"Stop trusting" and "lift the block" of an access point both end in <em>unknown</em>: the
 *       user is asked again at the next connection. Lifting an access point's block therefore also
 *       drops a trust that was stored under it, so lifting can never turn a network the user
 *       blocked into a trusted one without a word.
 *   <li>Lifting a <em>name</em> block leaves what is stored as it is: the access points trusted
 *       under that name apply again, the unknown ones are asked about.
 *   <li>Nothing here switches Wireless Debugging on or off.
 * </ul>
 */
final class KeepADBNetworkListActions {

    /** What an answer did, for the message the view shows. */
    enum Outcome {
        TRUSTED,
        /** The Wi-Fi name is blocked: trusting one of its access points is refused (#760). */
        TRUST_REFUSED_NAME_BLOCKED,
        STOPPED_TRUSTING,
        BLOCKED_ACCESS_POINT,
        ACCESS_POINT_BLOCK_LIFTED,
        BLOCKED_NAME,
        NAME_BLOCK_LIFTED,
        /** Nothing was changed: the address or name is not usable, or it was not stored. */
        FAILED
    }

    private KeepADBNetworkListActions() {}

    /**
     * "Trust" on a blocked access point: lifts the block of exactly that access point, then trusts
     * it. Refused, with nothing lifted, while its Wi-Fi name is blocked: the name block stays and
     * would hold anyway.
     *
     * <p>{@code ssid} is the name the trust label may carry (the live name of the current
     * connection), as before. The names stored with the block (#796, #802) are carried over
     * without touching the label: the stored Wi-Fi name becomes the entry's stored name and counts
     * for the refusal, and the own name the user gave the blocked access point becomes the entry's
     * own name, so lifting the block does not lose what the user typed.
     */
    static Outcome trustBlockedAccessPoint(Context context, String bssid, String ssid) {
        KeepADBNetworkBlocklist.BlockedAccessPoint block =
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, bssid);
        String storedSsid = block == null ? null : block.ssid;
        if (KeepADBNetworkBlocklist.isSsidBlocked(context, ssid)
                || KeepADBNetworkBlocklist.isSsidBlocked(context, storedSsid)) {
            return Outcome.TRUST_REFUSED_NAME_BLOCKED;
        }
        KeepADBNetworkBlocklist.unblockBssid(context, bssid);
        String label = ssid == null ? bssid : ssid;
        KeepADBTrustedNetwork.Entry entry = KeepADBReceiver.allowBssidOnly(context, bssid, label,
                KeepADBNetworkBlocklist.isUsableSsid(ssid) ? ssid : storedSsid);
        if (entry != null && entry.customName == null && block != null && block.customName != null) {
            KeepADBTrustedNetwork.setCustomName(context, entry.id, block.customName);
        }
        return entry == null ? Outcome.FAILED : Outcome.TRUSTED;
    }

    /** "Stop trusting": the access point becomes unknown again. */
    static Outcome stopTrusting(Context context, String bssid) {
        boolean removed = removeTrust(context, bssid);
        refresh(context);
        return removed ? Outcome.STOPPED_TRUSTING : Outcome.FAILED;
    }

    /**
     * "Block" an access point. A trust stored for it is dropped, so lifting the block later leaves
     * it unknown instead of silently trusted again.
     */
    static Outcome blockAccessPoint(Context context, String bssid) {
        return blockAccessPoint(context, bssid, null);
    }

    /**
     * As {@link #blockAccessPoint(Context, String)}; {@code ssid} is the Wi-Fi name the row is
     * filed under, stored with the block (#796). The own name the user gave the trust that is
     * dropped moves to the block (#802), unless the block already has one, so blocking does not
     * lose a name the user typed.
     */
    static Outcome blockAccessPoint(Context context, String bssid, String ssid) {
        String carried = null;
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid) && entry.customName != null) {
                carried = entry.customName;
            }
        }
        KeepADBNetworkDecision.Outcome outcome =
                KeepADBNetworkDecision.blockAccessPoint(context, bssid, ssid);
        if (outcome == KeepADBNetworkDecision.Outcome.BLOCK_FAILED) return Outcome.FAILED;
        if (carried != null) {
            KeepADBNetworkBlocklist.BlockedAccessPoint block =
                    KeepADBNetworkBlocklist.getBlockedAccessPoint(context, bssid);
            if (block != null && block.customName == null) {
                KeepADBNetworkBlocklist.setBlockedCustomName(context, bssid, carried);
            }
        }
        removeTrust(context, bssid);
        refresh(context);
        return Outcome.BLOCKED_ACCESS_POINT;
    }

    /** "Lift block" on an access point: it becomes unknown, whatever was stored for it before. */
    static Outcome liftAccessPointBlock(Context context, String bssid) {
        boolean lifted = KeepADBNetworkBlocklist.unblockBssid(context, bssid);
        removeTrust(context, bssid);
        refresh(context);
        return lifted ? Outcome.ACCESS_POINT_BLOCK_LIFTED : Outcome.FAILED;
    }

    /** "Always block this Wi-Fi name": every access point with that name, whatever its address. */
    static Outcome blockName(Context context, String ssid) {
        return KeepADBNetworkDecision.blockName(context, ssid) == KeepADBNetworkDecision.Outcome.BLOCK_FAILED
                ? Outcome.FAILED : Outcome.BLOCKED_NAME;
    }

    /** Lifts the block on a Wi-Fi name; the access points stored under it stay as they are. */
    static Outcome liftNameBlock(Context context, String ssid) {
        boolean lifted = KeepADBNetworkBlocklist.unblockSsid(context, ssid);
        refresh(context);
        return lifted ? Outcome.NAME_BLOCK_LIFTED : Outcome.FAILED;
    }

    private static boolean removeTrust(Context context, String bssid) {
        boolean removed = false;
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) {
                removed |= KeepADBTrustedNetwork.remove(context, entry.id);
            }
        }
        return removed;
    }

    /** The surfaces that show or act on the trust state re-read it. */
    private static void refresh(Context context) {
        KeepADBService.sync(context);
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBWidget.refreshAll(context);
    }
}
