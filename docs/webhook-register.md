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
KeepADB sends only to the URL saved by the user.

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

"Report now" (the webhook warning's button and the widgets) is a manual push into the normal flow
of an enabled synchronization, and nothing else. A tap reports the current, verified Wi-Fi ADB
endpoint to the saved URL at once, with the same message as any other report, whether or not the
connected network is trusted. It behaves exactly like the automatic report would: a request that is
already running is joined, a retry that is only waiting for its timer is sent at once, and a
debounced resync that is still waiting is dropped, so one tap never sends two requests. Afterwards
everything continues as usual: a failure starts or continues the retry series and the failure
episode, a success ends them, and the opt-out, a URL change and the endpoint loss withdraw the
registration as before.

**With synchronization off, "Report now" does nothing:** no request, no change of any preference,
nothing recorded. It never switches synchronization on; the widget offers the switch instead.
Without a valid saved URL, or without a verified Wi-Fi endpoint (Wi-Fi is up and the app holds a
discovered endpoint), nothing is sent either. The cause is returned to the caller and written to the
diagnostics as `register_report skipped reason=needs_setup`, `reason=sync_off` or
`reason=no_endpoint`, never with the URL or the endpoint. It never switches Wireless Debugging on
and never reuses an address while Wi-Fi is down.

The widgets' synchronization switch asks for a target state (on or off), never a flip, so a
repeated tap cannot undo itself. It uses the same enable and disable paths as the settings form:
switching on needs a valid saved URL (without one nothing changes and nothing is sent, and the
widget's setup action leads to the settings). The app, the notification and every widget are
refreshed afterwards.

**Switching off: what happens in which order.** The preference goes off at once; the withdrawal
does not wait for the network. The DELETE at the last reported URL is queued on the sender's single
background thread (behind any request already running or due) and sent from there; the URL is read
when the switch is tapped. Until the DELETE has succeeded, the stored report record (the last
reported URL and endpoint) still names the registration; its success clears the record and shows
the report as withdrawn, its failure keeps the record and shows the last report as failed.

- **While the process is needed:** a switch tapped on a widget keeps its broadcast open until the
  DELETE has been attempted (or the hold limit of the broadcast has passed, which is shorter than
  the system's window), so the process is not stopped with the request still queued. Switching on
  keeps it open until the work queued behind it has run, such as the report of an endpoint the
  process already knows. A freshly started process that still has to discover its endpoint is not
  awaited: the broadcast ends once the refresh is dispatched, synchronization stays on, and the
  report is sent by the next normal synchronization trigger, such as the endpoint being found or a
  network change.
- **If the process dies mid-DELETE, the hold limit passes or the request fails:** the preference is
  already off, so the registration may remain at the receiver. The stored report record still names
  it. Only a DELETE that returned a failure marks the last report as failed; after process death or
  an expired hold the stored status can still read as the earlier success. Nothing sends the DELETE
  again on its own, not at the next start of the app and not on a timer. Tapping "off" again is the
  retry: it withdraws the report record only while the record names the URL that is saved now (a
  record of a replaced or cleared URL is not retried from there), and, like every opt-out, it also
  flushes withdrawals already queued for earlier URLs whose DELETE failed (pending cleanups of
  registrations the app made itself). Without a record and without queued cleanups, "off" while
  already off sends nothing.

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
- **Report now:** needs setup (no valid destination), sync off (the switch is the offer, "Report
  now" does nothing), no endpoint (no verified Wi-Fi endpoint) or ready (synchronization is on and
  both exist). It follows the facts the action checks, in the order the action checks them; the
  action checks them again when it is tapped.
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
