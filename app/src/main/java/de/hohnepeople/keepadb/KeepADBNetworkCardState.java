package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #654/#655: pure derivation of what the Settings "Network" card tells the user -- the active
 * mode, the effect of the optional Wi-Fi-name switch, the current connection with its cause and
 * the fitting action, and the two background-access facts.
 *
 * <p>This class only <em>describes</em>. It never decides trust and holds no state: whether an
 * automatic re-enable is allowed stays exclusively with {@link KeepADBTrustedNetwork} (known
 * identity required, a block beats everything, then a listed BSSID or, with a name rule switched
 * on, an exactly matching name). {@link #derive} mirrors that rule on plain booleans so the card
 * can explain it, and {@code KeepADBNetworkCardStateTrustAgreementTest} pins that both never
 * disagree.
 *
 * <p>The facts the card shows are kept apart on purpose:
 * <ul>
 *   <li>{@link Connection} -- which network, and whether it is allowed;</li>
 *   <li>{@link Cause}/{@link Action} -- why, and what would help;</li>
 *   <li>{@link Detection} -- whether the identity is readable <em>right now</em>;</li>
 *   <li>{@link Background} -- whether the optional "Allow all the time" grant is there.</li>
 * </ul>
 * A readable network right now proves nothing about a later background start, and a missing
 * grant claims nothing about the current reading.
 */
final class KeepADBNetworkCardState {

    /** The active automatic re-enable policy as the user reads it. */
    enum Mode { ALL_WIFI, ALLOWED_APS, ALLOWED_APS_AND_NAMES }

    /** What the stored Wi-Fi-name switch currently does. */
    enum NameMatching {
        /** The switch is off: names are not used. */
        OFF,
        /** The switch is on and allowlist mode is active: listed names are accepted. */
        ACTIVE,
        /** The switch is on but "all Wi-Fi networks" is active: saved, without effect. */
        NO_EFFECT
    }

    /** The connection as the card presents it. */
    enum Connection {
        NO_WIFI, UNREADABLE, ALLOWED_AP, ALLOWED_NAME, NOT_ALLOWED,
        /** #762: the access point or its Wi-Fi name is blocked; not the same as "not allowed". */
        BLOCKED
    }

    /** Why the connection is in its state, for the explanatory line. */
    enum Cause {
        NO_WIFI,
        PERMISSION_MISSING,
        LOCATION_OFF,
        /** Permission and location are fine, yet the platform still masks the identity. */
        IDENTITY_HIDDEN,
        /** Unreadable, but "all Wi-Fi networks" is active so nothing is paused. */
        UNREADABLE_NOT_NEEDED,
        NOT_ALLOWED,
        /** #762: a block holds; nothing the card could offer (allow) would change that. */
        BLOCKED,
        ALLOWED,
        ALLOWED_BY_NAME,
        /** "All Wi-Fi networks" is active: the lists are not consulted. */
        ALL_WIFI
    }

    /** The one action that fits the current situation. */
    enum Action {
        NONE,
        ALLOW_ACCESS_POINT,
        GRANT_LOCATION,
        OPEN_LOCATION_SETTINGS,
        OPEN_WIFI_SETTINGS,
        SET_UP_BACKGROUND
    }

    /** The optional background grant, judged for the active mode. */
    enum Background { ALLOWED, RESTRICTED, NOT_REQUIRED }

    /** Whether the identity can be read at this very moment. */
    enum Detection { READABLE, NOT_READABLE, NO_WIFI }

    /** Plain inputs; no Android types, so the derivation is a pure function. */
    static final class Inputs {
        final boolean allowlistMode;
        final boolean ssidMatching;
        final boolean wifiConnected;
        final boolean identityKnown;
        final boolean bssidListed;
        final boolean ssidListed;
        final boolean fineLocationGranted;
        final boolean locationServicesOn;
        final boolean backgroundGranted;
        /** #760: the access point or its name is blocked; a block beats every kind of trust. */
        final boolean blocked;

        Inputs(boolean allowlistMode, boolean ssidMatching, boolean wifiConnected,
               boolean identityKnown, boolean bssidListed, boolean ssidListed,
               boolean fineLocationGranted, boolean locationServicesOn,
               boolean backgroundGranted) {
            this(allowlistMode, ssidMatching, wifiConnected, identityKnown, bssidListed, ssidListed,
                    fineLocationGranted, locationServicesOn, backgroundGranted, false);
        }

        Inputs(boolean allowlistMode, boolean ssidMatching, boolean wifiConnected,
               boolean identityKnown, boolean bssidListed, boolean ssidListed,
               boolean fineLocationGranted, boolean locationServicesOn,
               boolean backgroundGranted, boolean blocked) {
            this.blocked = blocked;
            this.allowlistMode = allowlistMode;
            this.ssidMatching = ssidMatching;
            this.wifiConnected = wifiConnected;
            this.identityKnown = identityKnown;
            this.bssidListed = bssidListed;
            this.ssidListed = ssidListed;
            this.fineLocationGranted = fineLocationGranted;
            this.locationServicesOn = locationServicesOn;
            this.backgroundGranted = backgroundGranted;
        }
    }

    /** The derived facts. */
    static final class Snapshot {
        final Mode mode;
        final NameMatching nameMatching;
        final Connection connection;
        final Cause cause;
        final Action action;
        final Background background;
        final Detection detection;

        Snapshot(Mode mode, NameMatching nameMatching, Connection connection, Cause cause,
                 Action action, Background background, Detection detection) {
            this.mode = mode;
            this.nameMatching = nameMatching;
            this.connection = connection;
            this.cause = cause;
            this.action = action;
            this.background = background;
            this.detection = detection;
        }
    }

    private KeepADBNetworkCardState() {}

    /**
     * Reads the persisted policy and allowlists into {@link Inputs}. The transport and permission
     * facts are passed in because only the caller knows how to read them without side effects;
     * everything that decides trust comes from {@link KeepADBTrustedNetwork} itself, using the
     * same comparisons it applies (BSSID ignoring case, SSID exactly, both only for a known
     * identity).
     */
    static Inputs read(Context context, KeepADBNetworkIdentity identity, boolean wifiConnected,
                       boolean fineLocationGranted, boolean locationServicesOn,
                       boolean backgroundGranted) {
        boolean known = identity != null && identity.isKnown();
        boolean bssidListed = false;
        boolean ssidListed = false;
        // #760: the same block store the policy consults, with the same matching.
        // #762: a block on a readable Wi-Fi name holds without a readable address too, exactly as
        // the policy applies it ("never" holds whenever there is evidence of the network).
        boolean blocked = identity != null && KeepADBNetworkBlocklist.isBlocked(context,
                known ? identity.bssid : null, identity.displaySsid());
        if (known) {
            for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
                if (entry.bssid.equalsIgnoreCase(identity.bssid)) {
                    bssidListed = true;
                    break;
                }
            }
            // #760: the name rules (derived comfort switch, legacy name list) as the policy
            // applies them, each only while its own switch is on.
            ssidListed = KeepADBTrustedNetwork.isNameTrusted(context, identity.displaySsid());
        }
        return new Inputs(KeepADBTrustedNetwork.isAllowlistMode(context),
                KeepADBTrustedNetwork.isSsidMatchingEnabled(context)
                        || KeepADBTrustedNetwork.isTrustByNameEnabled(context),
                wifiConnected, known,
                bssidListed, ssidListed, fineLocationGranted, locationServicesOn,
                backgroundGranted, blocked);
    }

    static Mode mode(boolean allowlistMode, boolean ssidMatching) {
        if (!allowlistMode) return Mode.ALL_WIFI;
        return ssidMatching ? Mode.ALLOWED_APS_AND_NAMES : Mode.ALLOWED_APS;
    }

    static NameMatching nameMatching(boolean allowlistMode, boolean ssidMatching) {
        if (!ssidMatching) return NameMatching.OFF;
        return allowlistMode ? NameMatching.ACTIVE : NameMatching.NO_EFFECT;
    }

    static Snapshot derive(Inputs in) {
        Mode mode = mode(in.allowlistMode, in.ssidMatching);
        NameMatching nameMatching = nameMatching(in.allowlistMode, in.ssidMatching);

        Connection connection;
        if (in.blocked) {
            // #762: a block is stated as such, before anything else is asked: a blocked network
            // is not "not allowed yet", and no allow action can change it.
            connection = Connection.BLOCKED;
        } else if (in.identityKnown) {
            // A readable, unmasked BSSID means an association exists, so the transport flag is
            // not consulted here. Mirrors KeepADBTrustedNetwork#evaluate: a block first (#760),
            // then a listed BSSID, or -- only while name matching is really active -- an exactly
            // listed name.
            if (in.bssidListed) {
                connection = Connection.ALLOWED_AP;
            } else if (nameMatching == NameMatching.ACTIVE && in.ssidListed) {
                connection = Connection.ALLOWED_NAME;
            } else {
                connection = Connection.NOT_ALLOWED;
            }
        } else if (!in.wifiConnected) {
            connection = Connection.NO_WIFI;
        } else {
            // Fail closed in allowlist mode: an unreadable identity is never allowed, whatever
            // the lists contain.
            connection = Connection.UNREADABLE;
        }

        Cause cause;
        Action action;
        switch (connection) {
            case NO_WIFI:
                cause = Cause.NO_WIFI;
                action = Action.OPEN_WIFI_SETTINGS;
                break;
            case UNREADABLE: {
                Cause reason;
                Action fix;
                if (!in.fineLocationGranted) {
                    reason = Cause.PERMISSION_MISSING;
                    fix = Action.GRANT_LOCATION;
                } else if (!in.locationServicesOn) {
                    reason = Cause.LOCATION_OFF;
                    fix = Action.OPEN_LOCATION_SETTINGS;
                } else {
                    reason = Cause.IDENTITY_HIDDEN;
                    // The background grant only matters while the allowlist is the active mode.
                    fix = in.allowlistMode && !in.backgroundGranted
                            ? Action.SET_UP_BACKGROUND : Action.NONE;
                }
                cause = in.allowlistMode ? reason : Cause.UNREADABLE_NOT_NEEDED;
                action = fix;
                break;
            }
            case BLOCKED:
                // Lifting a block is explicit and lives in the network list, never in a card
                // action that cannot work: trusting a blocked network is refused (#760).
                cause = Cause.BLOCKED;
                action = Action.NONE;
                break;
            case NOT_ALLOWED:
                cause = in.allowlistMode ? Cause.NOT_ALLOWED : Cause.ALL_WIFI;
                action = Action.ALLOW_ACCESS_POINT;
                break;
            case ALLOWED_NAME:
                cause = Cause.ALLOWED_BY_NAME;
                // The name allows it; the access point itself can still be allowed separately.
                action = Action.ALLOW_ACCESS_POINT;
                break;
            case ALLOWED_AP:
            default:
                cause = in.allowlistMode ? Cause.ALLOWED : Cause.ALL_WIFI;
                action = Action.NONE;
                break;
        }

        Background background = in.backgroundGranted ? Background.ALLOWED
                : (in.allowlistMode ? Background.RESTRICTED : Background.NOT_REQUIRED);
        Detection detection = in.identityKnown ? Detection.READABLE
                : (in.wifiConnected ? Detection.NOT_READABLE : Detection.NO_WIFI);

        return new Snapshot(mode, nameMatching, connection, cause, action, background, detection);
    }
}
