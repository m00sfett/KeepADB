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

**Open KeepADB once after installing it or after "Force stop".** Android delivers no broadcasts to
an app in that "stopped" state, so every request is lost without a reply until KeepADB has been
opened once. A reboot does not cause this; only a new installation or "Force stop" in Android's app
info does.

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

## Tasker plugin (#999)

Instead of typing action names and the token, you can add KeepADB as a **plugin action**. In Tasker:
*Add action → Plugin → KeepADB action*. MacroDroid and Automate list Locale/Tasker plugins as well
(see the compatibility note below).

The KeepADB screen that opens offers one request per action: Wifi-ADB on, off or toggle, Keep-Alive
on or off, or only read the status. KeepADB puts the token into the saved action itself.

- **The interface must be on.** While Settings → Automation is off, the screen only says so and
  links to the Settings, and a saved action does nothing (`%kadb_result` = `disabled`).
- **The same rules as a broadcast apply.** The plugin hands the request to the same check as the
  broadcasts above: token, network protection, flood damping and the rejection counter are shared.
- **The token is stored in the automation app.** On, toggle and status save it in the action's
  configuration; it is part of that app's backups, exports and shared tasks or projects. Share such
  a task only without the KeepADB action, or create a new token afterwards. The two "off" requests
  need no token and do not store one.
- **A new token** stops saved on, toggle and status actions (`rejected_token`). Open each saved
  KeepADB action once and save it again; KeepADB then inserts the new token.
- **The screen names the app it saves for.** Any app can open it, so it shows who receives the
  configuration. For an app other than Tasker, MacroDroid, Automate or Locale it asks again before
  saving, and opened without a calling app it saves nothing.

Tasker runs the action synchronously and then sets these local variables:

| Variable | Value |
|---|---|
| `%kadb_result` | `ok`, `rejected_token`, `rejected_network`, `failed_permission`, `failed`, `disabled` (interface off) or `invalid_config` |
| `%kadb_state` | Wifi-ADB `on` or `off` (not set while the interface is off) |
| `%kadb_keep_alive` | Keep-Alive `on` or `off` (not set while the interface is off) |
| `%kadb_ip`, `%kadb_port` | Only for an action with a valid token, while Wifi-ADB is on and KeepADB knows the endpoint |

Any result other than `ok` makes the action fail, with the result as error text (`%errmsg`). Use
Tasker's *Continue Task After Error* to react to it.

The address and port follow the rule of the broadcast reply: only with the token. The privacy
switches under Settings → Privacy mask what KeepADB shows on its own screens, notifications and
widgets; they do not apply to the plugin, which only answers the app the user configured.

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
`de.hohnepeople.keepadb.action.RESULT`; its output variable *Dictionary with broadcast extras*
holds the reply.

Checked on 2026-11-19 with Automate 1.53.2 (Android 13): in a second flow, a *Broadcast receive*
started before the request got the reply (`result`, `wifi_adb`, `keep_alive` and the
`request_id`). Start the receiving flow first: a reply sent before *Broadcast receive* is waiting is
not kept.

### adb (for testing)

```bash
adb shell am broadcast -a de.hohnepeople.keepadb.action.STATUS -p de.hohnepeople.keepadb --es token TOKEN
```

`am broadcast` shows no reply extras. Use the journal under Settings → Diagnostics to see what
KeepADB did (`automation ... accepted/rejected/ignored`, never the token).

**adb puts the token into the system log.** Android's adb daemon writes every `adb shell` command
line to logcat, token included. KeepADB itself never logs the token, but anyone who can read logcat
(a computer connected with adb, a bug report) can then see it. Test with the token only on a
device you control, and use **New token** in Settings afterwards.

## Not yet included

- Plugin conditions ("Wifi-ADB is on", "endpoint known") (#999, second part).
- A timer action ("on for N minutes").

Whether each automation app receives a broadcast sent to its package depends on the app. Only the
Automate example has been checked on a device so far (see above); the Tasker and MacroDroid
examples have not.

The plugin follows the public Locale plugin API with the Tasker extensions for variables and
synchronous execution. It has been tested with unit tests and on an emulator with a simulated
host; tests with Tasker, MacroDroid and Automate on a device are still open.
