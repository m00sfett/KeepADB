package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #959: the one-line summary of the Protection area, shown on the "Protection" row of Settings:
 * the protection level and how many Wi-Fi networks are trusted and blocked. It only reads.
 * The privacy mode hides the counts like every list does.
 */
final class KeepADBProtectionSummary {
    private KeepADBProtectionSummary() {}

    /** "Protection level: Balanced · 3 trusted · 1 blocked". */
    static String text(Context context) {
        String level = context.getString(R.string.networks_level,
                KeepADBForceNotice.levelLabel(context));
        return level + " · " + counts(context);
    }

    /** "3 trusted · 1 blocked", or the hidden placeholder while names are masked. */
    static String counts(Context context) {
        if (KeepADBNetworkDisplay.hidden(context)) {
            return context.getString(R.string.networks_count_hidden);
        }
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        boolean wifiConnected = identity.isKnown() || KeepADBService.isWifiConnected(context);
        KeepADBNetworkList.Snapshot networks =
                KeepADBNetworkList.build(context, identity, wifiConnected);
        return context.getString(R.string.networks_count, networks.trusted, networks.blocked);
    }
}
