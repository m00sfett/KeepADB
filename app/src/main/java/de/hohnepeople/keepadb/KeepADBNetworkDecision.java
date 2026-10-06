package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * The logic behind the "Trust this network?" decision (#766): what a prompt for one access point
 * may still ask, and the three answers -- trust the access point, block the access point, block
 * the Wi-Fi name -- on the paths that already exist for them. The decision surfaces ({@link
 * NetworkDecisionView} in {@link NetworkDecisionActivity}, later the network list) show and call;
 * they decide nothing themselves, and "decide later", Back and a tap outside write nothing at all.
 *
 * <p>Trusting goes through {@link KeepADBReceiver#handleTrustNetworkAction}, the same path as the
 * notification's allow action (locked-device gate, BSSID validation, the block check of #760).
 * Blocking goes through {@link KeepADBNetworkBlocklist}. Neither answer lifts the other: a block
 * is only ever lifted explicitly, and trusting a blocked network is refused (#760).
 *
 * <p>The network a decision is about is the access point the prompt was raised for, resolved from
 * the app's own record by BSSID ({@link KeepADBNetworkTrustPrompt#pendingConfirmation}), never from
 * an intent label and never from the current connection: a roam in between changes nothing.
 */
final class KeepADBNetworkDecision {

    /** What a prompt for one BSSID still is when the user opens it. */
    enum Status {
        /** Still undecided: the dialog offers the choices. */
        PENDING,
        /** Trusted or blocked in the meantime (for instance through the network list). */
        ALREADY_DECIDED,
        /** Nothing recorded for it any more (evicted, never seen, or a placeholder BSSID). */
        EXPIRED
    }

    /** What an answer did. */
    enum Outcome {
        TRUSTED,
        /** A block holds on this access point or its Wi-Fi name; nothing was stored (#760). */
        TRUST_REFUSED_BLOCKED,
        /** Nothing was stored (the device reported itself locked, or the BSSID was unusable). */
        TRUST_FAILED,
        BLOCKED_ACCESS_POINT,
        BLOCKED_NAME,
        /** The access point or name cannot be blocked (placeholder or empty). */
        BLOCK_FAILED
    }

    /** The access point a decision is about, as the app itself recorded it. */
    static final class Pending {
        final String bssid;
        /** The Wi-Fi name recorded for it, or null when it was not readable. */
        final String ssid;

        Pending(String bssid, String ssid) {
            this.bssid = bssid;
            this.ssid = ssid;
        }

        /** Whether the Wi-Fi name can be blocked as well (there is a readable name). */
        boolean canBlockName() {
            return KeepADBNetworkBlocklist.isUsableSsid(ssid);
        }
    }

    static final class Resolution {
        final Status status;
        /** The access point to decide on; non-null exactly for {@link Status#PENDING}. */
        final Pending pending;

        Resolution(Status status, Pending pending) {
            this.status = status;
            this.pending = pending;
        }
    }

    /** The result of a trust answer; {@code enabled} tells whether Wireless Debugging came on. */
    static final class TrustResult {
        final Outcome outcome;
        final boolean enabled;

        TrustResult(Outcome outcome, boolean enabled) {
            this.outcome = outcome;
            this.enabled = enabled;
        }
    }

    private KeepADBNetworkDecision() {}

    /**
     * Whether, and for what, the prompt for {@code bssid} can be answered. Decided means a block
     * on the address or on the recorded name, a trusted access point, or a trusted name; the
     * recorded entry is what supplies the name.
     */
    static Resolution resolve(Context context, String bssid) {
        String clean = bssid == null ? "" : bssid.trim();
        if (clean.isEmpty()
                || KeepADBNetworkIdentity.REDACTED_BSSID.equalsIgnoreCase(clean)
                || KeepADBNetworkIdentity.UNSET_BSSID.equalsIgnoreCase(clean)) {
            return new Resolution(Status.EXPIRED, null);
        }
        KeepADBBlockedNetworkHistory.Entry recorded = findRecord(context, clean);
        String ssid = recorded == null || recorded.ssid == null || recorded.ssid.isEmpty()
                ? null : recorded.ssid;
        if (isDecided(context, clean, ssid)) {
            return new Resolution(Status.ALREADY_DECIDED, null);
        }
        if (recorded == null) {
            return new Resolution(Status.EXPIRED, null);
        }
        return new Resolution(Status.PENDING, new Pending(recorded.bssid, ssid));
    }

    /**
     * Trusts exactly {@code pending.bssid}. A blocked network is refused and reported as such, not
     * as a failure of the app (#760).
     */
    static TrustResult trust(Context context, Pending pending) {
        String label = pending.ssid == null ? pending.bssid : pending.ssid;
        boolean enabled = KeepADBReceiver.handleTrustNetworkAction(context, pending.bssid, label);
        if (isListedAsTrusted(context, pending.bssid)) {
            return new TrustResult(Outcome.TRUSTED, enabled);
        }
        return new TrustResult(KeepADBNetworkBlocklist.isBlocked(context, pending.bssid, pending.ssid)
                ? Outcome.TRUST_REFUSED_BLOCKED : Outcome.TRUST_FAILED, false);
    }

    /**
     * Blocks the access point {@code bssid} only: KeepADB then never switches Wireless Debugging
     * on there by itself and never asks about it again. The Wi-Fi name stays as it is.
     */
    static Outcome blockAccessPoint(Context context, String bssid) {
        if (!KeepADBNetworkBlocklist.blockBssid(context, bssid)
                && !KeepADBNetworkBlocklist.isBssidBlocked(context, bssid)) {
            KeepADBDiagnostics.event(context, "user_action", "network_block", "failed",
                    "invalid_bssid");
            return Outcome.BLOCK_FAILED;
        }
        KeepADBBlockedNetworkHistory.remove(context, bssid);
        afterBlock(context, "access_point");
        return Outcome.BLOCKED_ACCESS_POINT;
    }

    /** Blocks every access point broadcasting {@code ssid} (the "never" for a Wi-Fi name, #760). */
    static Outcome blockName(Context context, String ssid) {
        if (!KeepADBNetworkBlocklist.blockSsid(context, ssid)
                && !KeepADBNetworkBlocklist.isSsidBlocked(context, ssid)) {
            KeepADBDiagnostics.event(context, "user_action", "network_block", "failed",
                    "unusable_name");
            return Outcome.BLOCK_FAILED;
        }
        // Blocked networks are not "recently prevented" (#760): drop every recorded access point
        // of that name.
        for (KeepADBBlockedNetworkHistory.Entry entry
                : KeepADBBlockedNetworkHistory.getEntries(context)) {
            if (ssid.equals(entry.ssid)) {
                KeepADBBlockedNetworkHistory.remove(context, entry.bssid);
            }
        }
        afterBlock(context, "wifi_name");
        return Outcome.BLOCKED_NAME;
    }

    private static void afterBlock(Context context, String scope) {
        KeepADBDiagnostics.event(context, "user_action", "network_block", "blocked", scope);
        // The question this answered is gone; the surfaces re-read their state.
        KeepADBNetworkTrustPrompt.cancel(context);
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBWidget.refreshAll(context);
    }

    private static boolean isDecided(Context context, String bssid, String ssid) {
        return KeepADBNetworkBlocklist.isBlocked(context, bssid, ssid)
                || isListedAsTrusted(context, bssid)
                || (ssid != null && KeepADBTrustedNetwork.isNameTrusted(context, ssid));
    }

    private static boolean isListedAsTrusted(Context context, String bssid) {
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return true;
        }
        return false;
    }

    private static KeepADBBlockedNetworkHistory.Entry findRecord(Context context, String bssid) {
        for (KeepADBBlockedNetworkHistory.Entry entry
                : KeepADBBlockedNetworkHistory.getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return entry;
        }
        return null;
    }
}
