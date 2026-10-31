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

## Transport and privacy

HTTPS is recommended. The project also allows HTTP for local or self-hosted destinations
configured by the user; HTTP encrypts neither the URL nor the message body. Android's network
security configuration explicitly allows this sender path, and the app displays a warning for
HTTP. Use unencrypted HTTP only on a network you trust. Remove credentials before sharing a URL or
diagnostic log.

The webhook URL is read from private app preferences. Uninstalling the app deletes this value with
the other app data. If a build changes, check the current sender code against this document and
the sender contract tests again.
