# Automation interface (#998)

From 1.15 automation apps such as Tasker, MacroDroid or Automate can control KeepADB with
broadcast intents. The interface is **off by default**. Switch it on under
**Settings → Automation → Allow automation apps**. KeepADB then creates an **access token**, which
you can show, copy or replace there.

The security model is described in [SECURITY.md](../SECURITY.md#automation-interface).

## What a request may do

| Action (`de.hohnepeople.keepadb.action.…`) | Token | What it does |
|---|---|---|
| `WIFI_ADB_ON` | required | Switches Wifi-ADB on, only on a network that allows it (see below) |
| `WIFI_ADB_OFF` | – | Switches Wifi-ADB off |
| `WIFI_ADB_TOGGLE` | required | Switches on when off, off when on |
| `KEEP_ALIVE_ON` | required | Turns the Keep-Alive setting on (Wifi-ADB itself is left to Keep-Alive) |
| `KEEP_ALIVE_OFF` | – | Turns the Keep-Alive setting off |
| `STATUS` | optional | Only replies; with a valid token the reply also has the address and port |

- **The interface is off:** every request is ignored, with no reply.
- **The network protection still applies:** Wifi-ADB is switched on only while Wi-Fi is connected
  and your settings under **Protection** allow an automatic enable on the current network;
  otherwise the request is refused with `rejected_network`. With the default settings that refuses
  untrusted, blocked, open and sign-in Wi-Fi networks. The request follows exactly the same rules as
  Keep-Alive, including their exceptions: a running **Force mode** allows every network, an open or
  sign-in Wi-Fi is refused only while **Turn off on open Wi-Fi** is on, and the previous "All Wi-Fi
  networks" setting allows every network that is not blocked. A request never exempts a network the
  way a manual tap does.
- **A locked phone is fine:** the token is the authorization.
- **Requests count as your own decision**, like a tap: an automation "off" stays off, and
  Keep-Alive does not switch Wifi-ADB back on. Any on or off from automation ends a running
  timer, and an "off" also ends Force mode.
- **A wrong or missing token** where one is required is rejected and counted. Settings shows the
  number and the time of the last rejected request. A new token resets the count.
- **Floods are damped:** any app can send requests in a loop. Requests without a valid token write
  at most one journal line per kind every 10 seconds (the next line says how many were left out),
  and the rejection count is saved at most every 10 seconds.

## Request

Send an **explicit broadcast** to package `de.hohnepeople.keepadb` (debug builds:
`de.hohnepeople.keepadb.debug`) with one of the actions above.

| Extra | Type | Meaning |
|---|---|---|
| `token` | String | The access token from Settings → Automation |
| `reply_package` | String, optional | The package that should receive the reply, e.g. `net.dinglisch.android.taskerm` |
| `request_id` | String, optional | Up to 128 characters, returned unchanged in the reply so you can match it to your request; never logged |

## Reply

When the request names `reply_package`, KeepADB sends a broadcast to that package only. If the
interface is off, there is no reply.

**A reply is information, not an authorization.** Any app can send a request naming your
automation app as `reply_package`, so your automation app can receive replies it did not ask for.
Do not let a reply alone trigger anything that switches something on; send a `request_id` and act
only on a reply that carries yours.

Action: `de.hohnepeople.keepadb.action.RESULT`

| Extra | Type | Meaning |
|---|---|---|
| `request` | String | The action of the request without its prefix, e.g. `WIFI_ADB_ON` |
| `request_id` | String | The `request_id` of the request, if it had one |
| `result` | String | `ok`, `rejected_token`, `rejected_network`, `failed_permission` (the one-time permission is missing), `failed` |
| `wifi_adb` | boolean | Whether Wifi-ADB is on after the request |
| `keep_alive` | boolean | Whether the Keep-Alive setting is on |
| `ip`, `port` | String, int | Only with a valid token, while Wifi-ADB is on and KeepADB knows the endpoint |

New extras may be added in later versions; existing names and values stay as they are.

## Examples

Replace `TOKEN` with the token you copied from Settings.

### Tasker

**Switch Wifi-ADB on:** Task → *System → Send Intent*

- Action: `de.hohnepeople.keepadb.action.WIFI_ADB_ON`
- Extra: `token:TOKEN`
- Package: `de.hohnepeople.keepadb`
- Target: *Broadcast Receiver*

**Read the status:** the same with action `de.hohnepeople.keepadb.action.STATUS` and a second
extra `reply_package:net.dinglisch.android.taskerm`. Receive the reply with the profile event
*System → Intent Received*, action `de.hohnepeople.keepadb.action.RESULT`. Tasker exposes the
extras as local variables (`%result`, `%wifi_adb`, `%ip`, `%port`).

### MacroDroid

Action *Connectivity → Send Intent*: target *Broadcast*, action
`de.hohnepeople.keepadb.action.WIFI_ADB_ON`, package `de.hohnepeople.keepadb`, extra `token` with
the value `TOKEN`. For a reply add the extra `reply_package` = `com.arlosoft.macrodroid` and use the
trigger *Intent Received* with action `de.hohnepeople.keepadb.action.RESULT`.

### Automate

Block *Broadcast send*: package `de.hohnepeople.keepadb`, action
`de.hohnepeople.keepadb.action.WIFI_ADB_ON`, extras `{"token": "TOKEN"}`. For a reply add
`"reply_package": "com.llamalab.automate"` and wait with *Broadcast receive* for action
`de.hohnepeople.keepadb.action.RESULT`.

### adb (for testing)

```bash
adb shell am broadcast -a de.hohnepeople.keepadb.action.STATUS -p de.hohnepeople.keepadb --es token TOKEN
```

`am broadcast` shows no reply extras. Use the journal under Settings → Diagnostics to see what
KeepADB did (`automation ... accepted/rejected/ignored`, never the token).

## Not yet included

- A Tasker/Locale plugin with its own settings screen, conditions and variables (#999).
- A timer action ("on for N minutes").

Whether each automation app receives a broadcast sent to its package depends on the app; the
examples above have not been checked in every app yet.
