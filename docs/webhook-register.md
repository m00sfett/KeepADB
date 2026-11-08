# Webhook: local sender contract

This page describes only what the KeepADB app code configures and sends locally. It does not
establish an external receiver's current state, a server route, a database effect, or any fields
permanently stored by a receiver. An earlier server inspection is kept as a historical, unverified
design in [issue 539](design/issue-539-multi-transport-register-contract.md).

## Setup and enabling or disabling

Webhook synchronization is off by default. Users enter the target URL and enable synchronization
themselves in app Settings or in the setup assistant. Saving a valid URL also enables
synchronization; in the assistant, "Next" applies a newly typed URL the same way (#888). Both values are stored in private app preferences and are not restored
through Android cloud backup or device transfer. They must be entered again after reinstalling.
KeepADB sends only to the URL saved by the user. The one exception to "synchronization decides
whether anything is sent" is a deliberate "Report now" (below): one request, started by a tap.

## Current sender path

After successfully verifying a Wi-Fi ADB endpoint, the app code creates a JSON POST with the
header **Content-Type: application/json; charset=utf-8**. Currently, only the **wlan-adb** method
is allowed to be transmitted. The code also creates drafts for VPN (`vpn-adb`, any VPN product;
named `tailscale-adb` before #877) and USB events but blocks them before the HTTP call. This local allowlist documents the sender's behavior; it does not say
which methods an external server currently accepts.

The Wi-Fi event contains:

- **contract_version**: numeric value 2
- **method**: wlan-adb
- **active**: true
- **endpoint**: verified Wi-Fi ADB host and port
- **source**: keepadb-app
- **observed_at**: ISO-8601 UTC timestamp added by the Wi-Fi sender when sending
- **event_id**: deterministic, truncated SHA-256-based identifier derived from method, endpoint,
  and active status

Example with placeholder values; the endpoint is shown only to illustrate the host:port form:

~~~json
{"contract_version":2,"method":"wlan-adb","active":true,"endpoint":"192.0.2.10:5555","source":"keepadb-app","observed_at":"2026-10-01T12:34:56Z","event_id":"keepadb-v2-wlan-adb-<truncated-digest>"}
~~~

The sender does not include an SSID, BSSID, USB profile, pairing code, or access token in this
message body. The verified endpoint may still contain a private LAN or VPN address.

Registration uses HTTP POST to the saved URL. The production code also attempts HTTP DELETE when
synchronization is disabled, a previously reported Wi-Fi ADB endpoint is no longer available, or a
previously used target URL has changed. It uses the last locally reported URL when known; if
synchronization is disabled without such a report, the currently saved URL may be the target.
Failed cleanup requests are queued locally and retried during later sender activity under internal
retry rules. The separate builder for an event with `active=false` is not currently called by the
production path. This repository therefore documents outgoing methods and contents, not the meaning
an external receiver assigns to a POST or DELETE.

When a report fails, a short bounded retry series follows (#866). If it is given up on while the
endpoint stays the same, a later network change (#867), saving the URL again (#869), or, while the
failure lasts, one single re-attempt at most every 15 minutes on the Keep-Alive service heartbeat
(#881) reports again. The episode is persisted, so these re-attempts also resume after the app
process restarts during it (#884). A healthy webhook is never polled: without a failure episode nothing is sent
until the endpoint changes.

The HTTP client does not follow redirects. An HTTP status from 200 through 299 counts locally as a
successful request. This does not prove that a receiver accepted the data semantically, stored it
permanently, or made it available to clients.

## Report now and the widgets' synchronization switch (#911)

"Report now" is a deliberate, manual action (the webhook warning's button and the widgets). It
reports the current, verified Wi-Fi ADB endpoint to the saved URL once, with the same message as
any other report, whether or not the connected network is trusted and also while automatic
synchronization is off. It needs a valid saved URL and a verified Wi-Fi endpoint (Wi-Fi is up and
the app holds a discovered endpoint). Without either, nothing is sent; the reason is returned to
the caller and written to the diagnostics as `register_report skipped reason=needs_setup` or
`reason=no_endpoint`, never with the URL or the endpoint. It never switches Wireless Debugging on
and never reuses an address while Wi-Fi is down.

**With synchronization on,** it behaves as before: a retry that is only waiting for its timer is
sent at once, a request that is already running is joined, and a waiting debounced resync is
dropped, so one tap never sends two requests.

**With synchronization off,** it is a one-shot report. The permission is the tap itself: the
destination and the endpoint it saw are captured by value and used by exactly one request. Nothing
is stored that an automatic path could read, so every automatic path keeps requiring the
synchronization preference, which the one-shot never changes (off before, during and after). Right
before the request the endpoint must still answer a connection check (a port that adbd rotated
without notice is not reported), and the app checks again that synchronization is still off and
that the saved destination and the verified endpoint are still the captured ones. If anything
changed, or a newer operation has started, the one-shot ends without a request and the
diagnostics name the reason (`endpoint_unreachable`, `endpoint_lost`, `endpoint_changed`,
`destination_changed` or `sync_enabled`). A late answer of a request that a newer
operation superseded records nothing.

A one-shot leaves nothing running behind it:

- one request, no retry series, even after a failure; a failure is recorded in the report history
  and as the newest outcome but starts no failure episode, so there is no warning and no
  re-attempt on the Keep-Alive heartbeat;
- a tap while a request is queued or running joins it; two widgets or a double tap never send in
  parallel;
- no request to any other destination: cleanups pending for other URLs stay pending for the
  regular register activity, and no secondary transports are attempted;
- no DELETE when the endpoint is lost later, because synchronization is off and that would be
  background sending.

**Registration lifecycle.** A successful one-shot is recorded as the last report (URL and
endpoint), like any other, so the regular paths know what is registered where: switching
synchronization on finds it already reported and sends nothing new; switching it off sends the
DELETE at that URL, as the opt-out always does; a different URL with synchronization on sends the
DELETE at the old one first; clearing the webhook in the settings sends the DELETE as well, also
with synchronization off, instead of dropping the URL and keeping the entry; a registration at
another URL that was on record when the one-shot succeeded becomes a pending cleanup. What the one-shot does not do is withdraw itself: a receiver
keeps whatever it keeps for a report that nothing later withdraws, and how it ages such an entry is
its own business and not specified here.

The widgets' synchronization switch asks for a target state (on or off), never a flip, so a
repeated tap cannot undo itself. It uses the same enable and disable paths as the settings form:
switching on needs a valid saved URL (without one nothing changes and nothing is sent, and the
widget leads to the settings), switching off sends the DELETE before the preference goes off. The
app, the notification and every widget are refreshed afterwards.

## What the widgets and the tile show of the delivery (#910)

The home-screen widgets and the Quick Settings tile read one projection of the app's own state
(`KeepADBWidgetState`); it reads only and never sends a report, starts a discovery or polls. The
widgets are re-rendered when the state they show changes: a toggle, an endpoint change, the
Keep-Alive or webhook settings, a report being queued and its result.

The webhook part keeps these apart:

- **Configuration:** not configured (no destination), synchronization off, or on.
- **Activity:** nothing in flight, a report being sent, or a retry waiting.
- **Newest outcome, last success, last failure:** what the sender recorded. A success from before
  a later failure stays visible as history and is not the current state.
- **Report now:** ready (a valid destination is saved and a verified Wi-Fi endpoint is available),
  needs setup (no valid destination) or no endpoint. It follows the facts the action checks, with
  synchronization on or off; the action checks them again when it is tapped.
- **Current delivery:** claimed only while the last report the server accepted names exactly the
  verified endpoint that is current now and the destination that is configured now, and no later
  report failed or was withdrawn. A changed endpoint, a changed or removed destination, a later
  failure, a deregistration, or an endpoint that is lost (also after the app process restarts and
  before the endpoint is found again) ends the claim; the claim is derived from what is current,
  so a late answer to an older report cannot restore it. Without a current endpoint the delivery is
  reported as unknown rather than as a success or a failure.

This is the sender's own evidence (see above): it does not prove what the receiver stored and
does not mean that a computer is connected over ADB. With the privacy switch for widgets and the
tile on (or the stricter option of a single widget), the projection carries no address, port,
Wi-Fi name or destination; whether an endpoint is available stays visible.

## Transport and privacy

HTTPS is recommended. The project also allows HTTP for local or self-hosted destinations
configured by the user; HTTP encrypts neither the URL nor the message body. Android's network
security configuration explicitly allows this sender path, and the app displays a warning for
HTTP. Use unencrypted HTTP only on a network you trust. Remove credentials before sharing a URL or
diagnostic log.

The webhook URL is read from private app preferences. Uninstalling the app deletes this value with
the other app data. If a build changes, check the current sender code against this document and
the sender contract tests again.
