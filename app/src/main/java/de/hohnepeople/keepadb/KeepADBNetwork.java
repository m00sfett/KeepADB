package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide view of currently connected networks, replacing the deprecated
 * {@code ConnectivityManager.getAllNetworks()} enumeration (deprecated since API 31) used
 * throughout {@link KeepADBService} and {@link KeepADBEndpoint} (#250).
 *
 * <p>Two {@link ConnectivityManager.NetworkCallback}s each keep their own pair of backing maps:
 * one scoped to {@code TRANSPORT_WIFI} (matching the existing Wi-Fi-scoped request already used
 * elsewhere in the codebase), and one from {@link ConnectivityManager#registerDefaultNetworkCallback}
 * (tracking whatever the current default route is -- cellular, VPN, ethernet, etc.). The two are
 * deliberately kept separate rather than sharing one map: a default-network callback's {@code
 * onLost} fires whenever that network stops being the *default* route, not only when it actually
 * disconnects (e.g. turning on a VPN fires {@code onLost} for Wi-Fi from the default callback's
 * perspective, even though Wi-Fi is still connected and the Wi-Fi-scoped callback never saw its
 * own {@code onLost}). Sharing one map would let that default-route change wrongly evict a
 * still-connected Wi-Fi network's cached data.
 *
 * <p>Since #314 the default network's link properties are tracked but deliberately not consulted
 * by any endpoint decision: a wireless-debugging endpoint is only ever valid on an address bound
 * to an eligible Wi-Fi network of this device (see {@link #isActiveWifiAddress(InetAddress)}),
 * never on the current default route when that route is cellular, VPN, USB tethering or ethernet.
 *
 * <p>This combination, rather than a single capabilities-cleared "match everything" request, is
 * also deliberate for a second reason: {@code NetworkRequest.Builder#clearCapabilities()} was
 * added in API 31, but this app's {@code minSdk} is 30, so using it unconditionally would throw
 * {@code NoSuchMethodError} on real API 30 devices. Per the platform contract, {@code
 * onCapabilitiesChanged}/{@code onLinkPropertiesChanged} fire at least once for every network a
 * request matches -- including ones that already existed at registration time -- so no separate
 * {@code onAvailable} bookkeeping is required.
 *
 * <p>#352 hardened three edge cases found in cumulative review R20: {@code onLost} only ever
 * removes the map entries of the specific {@code Network} it names, so losing one eligible Wi-Fi
 * network while another is still tracked never wrongly invalidates the survivor; {@link
 * #getWifiIpv4Address()} evaluates candidates in a fixed order when more than one eligible Wi-Fi
 * network is tracked at once, rather than depending on {@code ConcurrentHashMap}'s unspecified
 * iteration order; and {@link #isWifiCallbackRegistered()} lets callers with their own
 * synchronous fallback (see {@link KeepADBService#isWifiConnected(Context)}) distinguish a
 * genuinely negative, authoritative answer from "this tracker never managed to register its
 * callback and cannot know".
 */
final class KeepADBNetwork {
    private static volatile KeepADBNetwork instance;
    // #303: test-only override for isWifiConnected(), see KeepADBWifiProbe's javadoc for why it
    // lives here instead of on KeepADBService/KeepADBNotification. null in production, where
    // isWifiConnected() always falls through to the real transport-capability check below.
    private static volatile KeepADBWifiProbe wifiConnectivityOverride;
    // #352: test-only override for isWifiCallbackRegistered(), letting tests simulate a
    // registerNetworkCallback() failure without needing ConnectivityManager itself to throw.
    // null in production, where isWifiCallbackRegistered() always reports the real outcome of
    // the registration attempt below.
    private static volatile Boolean wifiCallbackRegisteredOverride;

    private final Context appContext;
    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback wifiCallback;
    private final ConnectivityManager.NetworkCallback defaultCallback;
    private final Map<Network, NetworkCapabilities> wifiCapabilities = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> wifiLinkProperties = new ConcurrentHashMap<>();
    // #352: whether registerNetworkCallback() for wifiCallback actually succeeded. false means
    // this tracker has no way of observing Wi-Fi network state at all -- neither a positive nor
    // a negative one is authoritative -- which is the one case callers may fall back to a
    // synchronous WifiInfo snapshot instead of trusting isWifiConnected()'s (necessarily false)
    // answer. See KeepADBService#isWifiConnected(Context).
    private volatile boolean wifiCallbackRegistered;
    // #390: whether wifiCallback has been invoked at least once. Registration succeeds
    // synchronously, but the framework delivers the first onCapabilitiesChanged/
    // onLinkPropertiesChanged/onLost asynchronously, so "registered" alone does not yet mean
    // "authoritative". Only once this is true do the (possibly empty) tracked maps represent the
    // framework's own answer -- which is when activeWifiAddresses() stops adding the potentially
    // stale synchronous WifiInfo snapshot as an endpoint candidate.
    private volatile boolean wifiCallbackObserved;
    // #314: written by defaultCallback, deliberately read by nothing any more -- the former
    // reader isKnownLocalAddress() was the very leak this issue closed (the default route may be
    // cellular, VPN, USB tethering or ethernet, none of which can host our Wi-Fi endpoint). The
    // callback itself stays registered rather than being dropped along with its last reader:
    // KeepADBNetworkContractTest pins registerDefaultNetworkCallback() as part of the #250
    // contract (it is what keeps the API-31-only clearCapabilities() unnecessary), and keeping
    // the map is what keeps that callback's onLost bookkeeping correct if a reader returns.
    private final Map<Network, LinkProperties> defaultLinkProperties = new ConcurrentHashMap<>();

    private KeepADBNetwork(Context context) {
        appContext = context.getApplicationContext();
        connectivityManager = appContext.getSystemService(ConnectivityManager.class);
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                wifiCapabilities.put(network, capabilities);
                wifiCallbackObserved = true;
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                wifiLinkProperties.put(network, linkProperties);
                wifiCallbackObserved = true;
            }

            @Override
            public void onLost(Network network) {
                wifiCapabilities.remove(network);
                wifiLinkProperties.remove(network);
                wifiCallbackObserved = true;
            }
        };
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                defaultLinkProperties.put(network, linkProperties);
            }

            @Override
            public void onLost(Network network) {
                defaultLinkProperties.remove(network);
            }
        };
        if (connectivityManager != null) {
            try {
                NetworkRequest wifiRequest = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback);
                wifiCallbackRegistered = true;
            } catch (RuntimeException ignored) {
                // Best-effort: isWifiConnected()/getWifiIpv4Address()/isActiveWifiAddress()
                // simply see fewer tracked networks if registration fails -- which for
                // isActiveWifiAddress() means rejecting candidates, never accepting one.
                // wifiCallbackRegistered stays false (#352), signalling callers that this
                // tracker's negative isWifiConnected() answer is not authoritative here.
            }
            try {
                connectivityManager.registerDefaultNetworkCallback(defaultCallback);
            } catch (RuntimeException ignored) {
            }
        }
    }

    static synchronized KeepADBNetwork get(Context context) {
        if (instance == null) {
            instance = new KeepADBNetwork(context);
        }
        return instance;
    }

    static synchronized void resetForTesting() {
        if (instance != null && instance.connectivityManager != null) {
            try {
                instance.connectivityManager.unregisterNetworkCallback(instance.wifiCallback);
            } catch (RuntimeException ignored) {
            }
            try {
                instance.connectivityManager.unregisterNetworkCallback(instance.defaultCallback);
            } catch (RuntimeException ignored) {
            }
        }
        instance = null;
        wifiConnectivityOverride = null;
        wifiCallbackRegisteredOverride = null;
    }

    /**
     * Test-only seam (#303): replaces {@link #isWifiConnected()}'s real transport-capability
     * check with {@code override}, or restores it when passed {@code null}. See {@link
     * KeepADBWifiProbe}'s javadoc for why this lives here rather than as a seam on {@code
     * KeepADBService}/{@code KeepADBNotification}.
     */
    static void setWifiConnectivityOverrideForTesting(KeepADBWifiProbe override) {
        wifiConnectivityOverride = override;
    }

    /**
     * Test-only seam (#352): forces {@link #isWifiCallbackRegistered()} to {@code forcedValue},
     * or restores the real registration outcome when passed {@code null}. Lets tests simulate a
     * {@code registerNetworkCallback()} failure (forcedValue {@code false}) or a successful,
     * live registration (forcedValue {@code true}) without needing a real {@link
     * ConnectivityManager} to actually throw.
     */
    static void setWifiCallbackRegisteredOverrideForTesting(Boolean forcedValue) {
        wifiCallbackRegisteredOverride = forcedValue;
    }

    /**
     * Whether this tracker's Wi-Fi {@link ConnectivityManager.NetworkCallback} is actually
     * registered and therefore authoritative. When {@code false} (registration failed at
     * construction time, see the constructor's catch block), {@link #isWifiConnected()} can only
     * ever answer {@code false} -- not because Wi-Fi is disconnected, but because this tracker
     * has no way of observing it. Callers with a synchronous fallback of their own (see {@link
     * KeepADBService#isWifiConnected(Context)}) must use this to decide whether a negative
     * answer here is trustworthy or merely "unknown" (#352).
     */
    boolean isWifiCallbackRegistered() {
        Boolean override = wifiCallbackRegisteredOverride;
        if (override != null) {
            return override;
        }
        return wifiCallbackRegistered;
    }

    /** Wi-Fi transport, excluding VPN-over-Wi-Fi -- unchanged from the prior getAllNetworks() predicate. */
    static boolean isEligibleWifiTransport(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    boolean isWifiConnected() {
        KeepADBWifiProbe override = wifiConnectivityOverride;
        if (override != null) {
            return override.isWifiConnected();
        }
        for (NetworkCapabilities capabilities : wifiCapabilities.values()) {
            if (isEligibleWifiTransport(capabilities)) {
                return true;
            }
        }
        return false;
    }

    /**
     * First non-loopback, non-link-local IPv4 address bound to an eligible Wi-Fi network, if
     * any. When more than one eligible Wi-Fi network is simultaneously tracked (#352 -- e.g. a
     * multi-internet-capable device, or a brief overlap during a network handover), candidates
     * are evaluated in a fixed, deterministic order rather than {@code wifiCapabilities}'
     * unspecified {@link ConcurrentHashMap} iteration order, so repeated calls against the same
     * tracked state always agree instead of depending on incidental map/hash ordering.
     *
     * <p>#396: unlike {@link #activeWifiAddresses()}, this used to have no synchronous fallback
     * at all, so a call made in the same startup-race window as #390 (callback registered but not
     * yet fired) always returned {@code null} even on a connected network. It now falls back to
     * the same {@link #synchronousWifiIpv4Address()} snapshot exactly while {@link
     * #isWifiTrackingAuthoritative()} is {@code false}, matching {@link
     * KeepADBService#isWifiConnected(Context)}'s already-fixed handling of the same window.
     */
    String getWifiIpv4Address() {
        for (Map.Entry<Network, NetworkCapabilities> entry : eligibleWifiEntriesInDeterministicOrder()) {
            LinkProperties linkProperties = wifiLinkProperties.get(entry.getKey());
            if (linkProperties == null) continue;
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        if (!isWifiTrackingAuthoritative()) {
            InetAddress synchronousIpv4 = synchronousWifiIpv4Address();
            if (synchronousIpv4 != null) {
                return synchronousIpv4.getHostAddress();
            }
        }
        return null;
    }

    /**
     * Snapshot of {@link #wifiCapabilities}' eligible entries, sorted by {@link
     * Network#getNetworkHandle()} -- a stable, monotonically-assigned identifier available on
     * every {@code Network} since API 23 (well below this app's minSdk 30) that does not depend
     * on {@link ConcurrentHashMap}'s unspecified iteration order. Ascending order deterministically
     * prefers the longest-tracked (earliest-connected) eligible network as the "currently active"
     * candidate over one that started coexisting more recently (#352).
     */
    private List<Map.Entry<Network, NetworkCapabilities>> eligibleWifiEntriesInDeterministicOrder() {
        List<Map.Entry<Network, NetworkCapabilities>> entries = new ArrayList<>();
        for (Map.Entry<Network, NetworkCapabilities> entry : wifiCapabilities.entrySet()) {
            if (isEligibleWifiTransport(entry.getValue())) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparingLong(entry -> entry.getKey().getNetworkHandle()));
        return entries;
    }

    /**
     * Whether {@code address} is one of the addresses bound to an <em>eligible Wi-Fi network of
     * this device right now</em> (#314) -- the only addresses a genuine wireless-debugging
     * endpoint of ours can be reached on via {@code adb connect <host>:<port>}.
     *
     * <p>This deliberately replaces the former {@code isKnownLocalAddress()}, which also
     * accepted addresses of the current default route (cellular, VPN, USB tethering, ethernet)
     * and, together with {@link KeepADBEndpoint}'s blanket loopback/link-local shortcut, let any
     * unrelated local TCP listener pass as an endpoint candidate. Two properties matter here:
     *
     * <ul>
     *   <li><b>Bound, not merely "reachable" or "in our subnet":</b> the candidate must equal an
     *       address the Wi-Fi interface itself holds. A neighbour's address on the same subnet,
     *       another device's IPv6 link-local, or a service on our own cellular/VPN interface is
     *       rejected.</li>
     *   <li><b>Fail-closed:</b> with no eligible Wi-Fi network tracked and no synchronous Wi-Fi
     *       IP available (e.g. Wi-Fi off), the candidate set is empty and every candidate is
     *       rejected rather than optimistically accepted.</li>
     * </ul>
     *
     * <p>The synchronous {@link WifiManager} address is kept as an additional candidate <em>only
     * while this tracker cannot know better</em> (#390): {@code onCapabilitiesChanged}/{@code
     * onLinkPropertiesChanged} populate the maps asynchronously, so a query made immediately
     * after the first-ever {@link #get} call in a process (before any callback has fired), or
     * one made when the callback failed to register at all, would otherwise wrongly treat our own
     * Wi-Fi address as foreign. Once the callback is registered and has fired at least once (see
     * {@link #isWifiTrackingAuthoritative()}), the tracked maps are the authoritative answer and
     * the snapshot is dropped: {@code WifiManager.getConnectionInfo()} is known to keep reporting
     * a just-dropped address across fast connection changes, and adding it additively would let
     * exactly that stale address pass as a live endpoint while the callback already says no
     * eligible Wi-Fi network exists -- defeating the fail-closed property above.
     *
     * <p>#365: this synchronous snapshot used to be IPv4-only ({@link WifiInfo} has no IPv6
     * accessor), so an IPv6-only Wi-Fi network was wrongly rejected during this same race window.
     * {@link #synchronousWifiAddresses()} now also tries a synchronous IPv6 lookup via {@link
     * ConnectivityManager} whenever no synchronous IPv4 address is available; see its javadoc for
     * why that lookup is narrower than the IPv4 one.
     *
     * <p>Note what this does <em>not</em> establish: that whatever listens on the port is really
     * adbd rather than some other service the device itself exposes on its Wi-Fi address. That
     * remains open follow-up work (adbd authenticity, R13 on #314) and is out of scope here.
     */
    boolean isActiveWifiAddress(InetAddress address) {
        return matchesActiveWifiAddress(address, activeWifiAddresses(), this::reportScopeFallback);
    }

    /**
     * Makes the scope-blind fallback described on {@link #matchesActiveWifiAddress} observable
     * (#403): before this, a link-local match with an unresolved scope on either side passed
     * completely silently, indistinguishable from a fully scope-verified match in any log or
     * diagnostics export. This does not change the accept/reject outcome -- it only records that
     * the weaker, scope-blind path was the reason a candidate was accepted.
     */
    private void reportScopeFallback(int candidateScope, int activeScope) {
        KeepADBDiagnostics.event(appContext, "scope_fallback", "network", "unresolved",
                "candidateScope=" + candidateScope + " activeScope=" + activeScope);
    }

    /**
     * Whether this tracker's own view of Wi-Fi state is authoritative (#390): its callback is
     * registered <em>and</em> has actually been invoked at least once. Both halves are needed --
     * an unregistered callback can never observe anything, and a registered one that has not
     * fired yet leaves the tracked maps empty for reasons that have nothing to do with Wi-Fi
     * being off. Only when this is {@code true} does an empty candidate set mean "no Wi-Fi",
     * rather than "nothing known yet".
     *
     * <p>Package-visible since the review repair on top of #352/#390: {@link
     * KeepADBService#isWifiConnected(Context)} needs the very same predicate to decide whether its
     * own synchronous fallback may still widen a negative answer. It used to check only {@link
     * #isWifiCallbackRegistered()} there, which treats the pre-first-delivery window as
     * authoritative and wrongly reports "no Wi-Fi".
     */
    boolean isWifiTrackingAuthoritative() {
        return isWifiCallbackRegistered() && wifiCallbackObserved;
    }

    /**
     * Addresses currently bound to an eligible (non-VPN) Wi-Fi network of this device.
     *
     * <p>#364: a link-local ({@code fe80::/10}) {@link Inet6Address} is stamped with this
     * network's own real interface index (via {@link LinkProperties#getInterfaceName()} and
     * {@link NetworkInterface#getByName(String)}) before being added, whenever that lookup
     * succeeds. This gives {@link #matchesActiveWifiAddress} something concrete to compare a
     * scoped candidate's interface against; see its javadoc for why an unresolvable stamp still
     * has to fail open rather than reject.
     */
    private List<InetAddress> activeWifiAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        for (Map.Entry<Network, NetworkCapabilities> entry : wifiCapabilities.entrySet()) {
            if (!isEligibleWifiTransport(entry.getValue())) continue;
            LinkProperties linkProperties = wifiLinkProperties.get(entry.getKey());
            if (linkProperties == null) continue;
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address != null) {
                    addresses.add(stampLinkLocalScope(address, linkProperties.getInterfaceName()));
                }
            }
        }
        if (!isWifiTrackingAuthoritative()) {
            addresses.addAll(synchronousWifiAddresses());
        }
        return addresses;
    }

    /**
     * Stamps a link-local {@code address} with its real scope (interface index), so a later
     * comparison in {@link #matchesActiveWifiAddress} has something concrete to check a scoped
     * candidate against. Returns {@code address} unchanged for anything that is not a link-local
     * {@link Inet6Address}, or when {@link #resolveScopeInterface} cannot resolve a live {@link
     * NetworkInterface} at all -- both of which leave the scope check below unable to reject,
     * exactly like an address that was never stamped.
     */
    private static InetAddress stampLinkLocalScope(InetAddress address, String interfaceName) {
        if (!(address instanceof Inet6Address) || !address.isLinkLocalAddress()) {
            return address;
        }
        try {
            NetworkInterface networkInterface = resolveScopeInterface(interfaceName, address);
            if (networkInterface == null) return address;
            return Inet6Address.getByAddress(null, address.getAddress(), networkInterface.getIndex());
        } catch (Exception ignored) {
            // Best-effort (#364): an unresolvable interface must not turn into a rejection, it
            // must fall back to the pre-#364 scope-blind comparison for this address.
            return address;
        }
    }

    /**
     * Resolves the {@link NetworkInterface} to stamp {@code address} with (#403). Tries the
     * cheap, direct path first -- {@link NetworkInterface#getByName(String)} on {@code
     * interfaceName} straight from {@link LinkProperties#getInterfaceName()} -- and only falls
     * back to scanning every interface for one that already holds an address with the exact same
     * bytes when that direct lookup is unavailable (name {@code null}) or comes back empty
     * (interface momentarily not found by that name, or the lookup throws). This byte-match scan
     * does not depend on the interface name having resolved correctly at all, so it closes the
     * concrete gap named in #403 (name null, interface not (yet) enumerable under that name) while
     * leaving the genuinely-unresolvable case -- {@code address} bound to no interface this
     * process can currently enumerate -- to still fall back to the scope-blind comparison, as
     * documented on {@link #matchesActiveWifiAddress}.
     */
    static NetworkInterface resolveScopeInterface(String interfaceName, InetAddress address) {
        if (interfaceName != null) {
            try {
                NetworkInterface direct = NetworkInterface.getByName(interfaceName);
                if (direct != null) return direct;
            } catch (Exception ignored) {
                // Fall through to the byte-match scan below.
            }
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface candidate = interfaces.nextElement();
                Enumeration<InetAddress> candidateAddresses = candidate.getInetAddresses();
                while (candidateAddresses.hasMoreElements()) {
                    if (Arrays.equals(candidateAddresses.nextElement().getAddress(), address.getAddress())) {
                        return candidate;
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort: an enumeration failure here must not turn into a rejection either.
        }
        return null;
    }

    /**
     * Pure decision behind {@link #isActiveWifiAddress(InetAddress)}, separated so it is
     * testable without a real {@link ConnectivityManager}. Loopback, wildcard and multicast
     * addresses are rejected outright: they are never a usable {@code adb connect} target from
     * another host, so accepting one could only ever register a local service of some other
     * kind.
     *
     * <p>The core comparison is {@link InetAddress#equals}, which is <em>scope-id blind</em> for
     * IPv6: {@code Inet6Address.equals()} compares the 16 address bytes only, so {@code
     * fe80::1%wlan0}, {@code fe80::1%rmnet0} and a scopeless {@code fe80::1} all compare equal.
     * On top of that, for a link-local ({@code fe80::/10}) match, #364 additionally compares
     * {@link Inet6Address#getScopeId()} whenever <em>both</em> sides resolved to a nonzero
     * numeric scope, and rejects a byte-identical candidate whose scope disagrees -- a real
     * device on a different interface (cellular, USB tethering, a VPN endpoint under attacker
     * control) numerically colliding with our own Wi-Fi link-local address, formerly
     * indistinguishable from the real thing. This still fails open rather than closed whenever
     * either side's scope could not be resolved to a concrete interface (scope id {@code 0}):
     * {@code NsdManager} does not always hand back a resolved link-local address with a scope,
     * and the {@code LinkAddress}es of the tracked Wi-Fi network only carry one when {@link
     * #stampLinkLocalScope} (hardened in #403 via {@link #resolveScopeInterface}) could resolve
     * the network's real interface -- either gap must keep accepting our own advertised
     * link-local endpoint, since adbd has been observed advertising IPv6-only and a scope-strict
     * comparison would otherwise reject it. Proving that whatever listens behind a matched
     * address really is adbd remains separate, open follow-up work (R13 on #314).
     *
     * <p>#403: this scope-blind fallback is a <em>permanent, deliberate</em> acceptance for the
     * candidate side, not a temporary gap -- {@code NsdManager} is not documented to always
     * resolve a scope for a link-local address, so a candidate-side scope of {@code 0} is
     * expected steady-state behaviour, not an error condition to eventually close. The own-side
     * (tracked Wi-Fi network) half of the gap is the one this issue narrows, via {@link
     * #resolveScopeInterface}'s byte-match fallback. Whenever either side still falls back to the
     * scope-blind comparison, {@code scopeFallbackSink} (if given) is notified so the event is
     * observable instead of silent; see {@link #isActiveWifiAddress} for the production wiring
     * that turns this into a {@link KeepADBDiagnostics} event.
     */
    static boolean matchesActiveWifiAddress(InetAddress candidate, List<InetAddress> activeWifiAddresses) {
        return matchesActiveWifiAddress(candidate, activeWifiAddresses, null);
    }

    static boolean matchesActiveWifiAddress(InetAddress candidate, List<InetAddress> activeWifiAddresses,
            ScopeFallbackSink scopeFallbackSink) {
        if (candidate == null || activeWifiAddresses == null) return false;
        if (candidate.isLoopbackAddress() || candidate.isAnyLocalAddress()
                || candidate.isMulticastAddress()) {
            return false;
        }
        for (InetAddress address : activeWifiAddresses) {
            if (!candidate.equals(address)) continue;
            if (candidate instanceof Inet6Address && address instanceof Inet6Address
                    && candidate.isLinkLocalAddress()) {
                int candidateScope = ((Inet6Address) candidate).getScopeId();
                int activeScope = ((Inet6Address) address).getScopeId();
                if (candidateScope != 0 && activeScope != 0 && candidateScope != activeScope) {
                    continue; // Byte-identical, but bound to two different real interfaces (#364).
                }
                if ((candidateScope == 0 || activeScope == 0) && scopeFallbackSink != null) {
                    scopeFallbackSink.onScopeFallback(candidateScope, activeScope);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Callback for {@link #matchesActiveWifiAddress(InetAddress, List, ScopeFallbackSink)}: fired
     * whenever a link-local match was accepted via the scope-blind fallback described there
     * (#403), i.e. at least one side's scope id could not be resolved to a concrete interface.
     */
    interface ScopeFallbackSink {
        void onScopeFallback(int candidateScope, int activeScope);
    }

    /**
     * Synchronous best-effort Wi-Fi address candidates for the same startup/registration-failure
     * race window as {@link #synchronousWifiIpv4Address()} (see that method's javadoc). #365:
     * {@link WifiInfo} has never exposed an IPv6 address, so an IPv6-only Wi-Fi network used to
     * fall through this fallback with an empty result during the race window, even though a
     * synchronous IPv6 lookup is available through a different API ({@link
     * #synchronousWifiIpv6Address()}). IPv4 is tried first and, exactly like the pre-#365
     * behaviour, stays the sole candidate whenever it is available -- the IPv6 lookup only runs
     * for an IPv4-less (IPv6-only) network, so a dual-stack network's candidate set is unchanged.
     */
    private List<InetAddress> synchronousWifiAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        InetAddress ipv4 = synchronousWifiIpv4Address();
        if (ipv4 != null) {
            addresses.add(ipv4);
            return addresses;
        }
        InetAddress ipv6 = synchronousWifiIpv6Address();
        if (ipv6 != null) {
            addresses.add(ipv6);
        }
        return addresses;
    }

    /**
     * Synchronous IPv4 Wi-Fi address via {@link WifiManager#getConnectionInfo()}, used only while
     * {@link #isWifiTrackingAuthoritative()} is {@code false} (see {@link
     * #activeWifiAddresses()}). {@code WifiInfo} exposes IPv4 only -- there has never been an
     * IPv6 equivalent on this API -- which is why an IPv6-only network needs the separate lookup
     * in {@link #synchronousWifiIpv6Address()} (#365).
     */
    private InetAddress synchronousWifiIpv4Address() {
        try {
            WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return null;
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            byte[] bytes = {(byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >> 24)};
            return InetAddress.getByAddress(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Synchronous IPv6 Wi-Fi address candidate for the same race window as {@link
     * #synchronousWifiIpv4Address()} (#365). Unlike the IPv4 lookup this cannot go through {@link
     * WifiManager}: {@link WifiInfo} has no IPv6 accessor, and {@code WifiManager.getCurrentNetwork()}
     * -- the one API that would hand back the current Wi-Fi {@link Network} synchronously -- was
     * only added in API 31, above this app's {@code minSdk} 30. Instead this uses {@link
     * ConnectivityManager#getActiveNetwork()} together with {@link
     * ConnectivityManager#getNetworkCapabilities(Network)} and {@link
     * ConnectivityManager#getLinkProperties(Network)}: all three are synchronous, non-deprecated
     * calls available since API 23, so they work on every supported OS version. This is
     * necessarily narrower than the IPv4 path: it only finds an address when the eligible Wi-Fi
     * network also happens to be the device's current <em>default</em> route, so it stays silent
     * (not wrongly negative -- the caller only widens the candidate set with what this finds) if
     * Wi-Fi is connected but VPN/cellular is the active default. A non-link-local, non-loopback
     * address is preferred, mirroring the IPv4 lookup's own loopback/link-local exclusion --
     * link-local addresses are always present regardless of whether a real IPv6 route was
     * assigned, so they are not a useful "this network has connectivity" signal here.
     */
    private InetAddress synchronousWifiIpv6Address() {
        try {
            if (connectivityManager == null) return null;
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return null;
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (!isEligibleWifiTransport(capabilities)) return null;
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProperties == null) return null;
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet6Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
