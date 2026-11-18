# Wifi-ADB timer (#1000)

The timer switches Wifi-ADB on for a chosen time and off again when the time is up. It suits the
occasional "I need ADB for this one tool" case without leaving Wifi-ADB on for good.

## Starting, extending, ending

| Where | What |
|---|---|
| Home screen, below the Wifi-ADB switch | "On for 1 h" starts the default duration; the chevron offers 15 min, 30 min, 1 h, 2 h, 4 h and 8 h. While Wifi-ADB is already on without a timer, the row reads "Off in 1 h" and sets an end. |
| Launcher shortcut "Wifi-ADB with timer" | Starts the default duration; a toast names it. On a device locked behind a secure lock it waits for the unlock, like the toggle shortcut. |
| Settings → Other → "Timer default duration" | The duration preselected above (default 1 h). It is part of the settings backup; a running timer is not. |
| Notification while the timer runs | Countdown to the end, "Off now" and "+1 h". |
| Home screen while the timer runs | "Off at 14:35 · 42 min left", "+1 h" and "No limit". |

- **"+1 h"** adds one hour to the time that is left, per tap. No timer ever has more than 24 hours
  left; at the cap the button disappears. On the notification it needs an unlocked device.
- **"No limit"** removes the timer and leaves Wifi-ADB as it is; from then on only the Keep-Alive
  setting decides.
- **A manual on or off** on any surface (app, tile, widget, notification, shortcut) ends a running
  timer. Not ending it: "Allow" on the trust prompt (it answers a question about the network, the
  timer keeps running) and the self-pairing setup. A new timer start replaces the running one with the new duration from now.

## What the timer does while it runs

Starting a timer is a manual "on", exactly like the switch: it switches Wifi-ADB on wherever the
switch would, and the current Wi-Fi gets the same manual-session exemption from "Turn off on
untrusted / open Wi-Fi" (#864) until the network changes. From then on the network rules decide.

The timer is a temporary Keep-Alive: it keeps Wifi-ADB alive whether or not Keep-Alive is switched
on in the app, and it never changes the stored Keep-Alive setting. It does not override the network
rules: on an untrusted Wi-Fi it holds nothing ("Timer running · network not trusted") and the time
keeps running. The home row names the same reason as the home status: no Wi-Fi, an untrusted, open
or unreadable network, paused retries, or "switching on" when nothing blocks it. Force mode, which may run at the same time, is what lets Wifi-ADB stay on in
every Wi-Fi; the timer then still ends it on time. With both running, the red Force mode
notification and a separate timer notification are shown.

## The end

When the time is up, Wifi-ADB is switched off as if the user had switched it off: the last explicit
intent becomes "off", so a Keep-Alive that is on in the app waits for the next deliberate "on" and
does not bring Wifi-ADB back. A silent notice "Wifi-ADB off – timer ended" appears once Wifi-ADB is
actually seen off (not while the switch-off is still outstanding) and disappears by itself after an
hour.

**Timing.** From the first moment after the deadline nothing re-enables Wifi-ADB automatically, even
before the end has been processed. The switch-off itself is best effort without a fixed upper bound:
it is triggered by an inexact alarm (no exact alarm permission is requested), the service's minute
check and opening the app. Usually it follows within seconds; deep sleep and Doze can delay it, and
Android may batch idle alarms by many minutes.

**The switch-off is an obligation.** The end is stored in one commit together with the "off" intent
and an outstanding switch-off (`timer_shutdown_pending`). Until a readback shows Wifi-ADB off,
Keep-Alive stays suppressed and every driver (and the next process start, retried every minute by
the alarm) asserts the "off" intent again and writes the off. A process death before the write, or a
write Android rejects, therefore cannot leave Wifi-ADB on with Keep-Alive allowed. A manual off that
fails while a timer runs leaves the same obligation; a deliberate manual "on" clears it.

## Reboot, process death, clock changes

The state is one value in the app preferences (`timer_state`), written with `commit()`, so a killed
process loses nothing. The time rules are those of Force mode (shared in `KeepADBDeadline`): the
remaining time is the smaller of wall-clock and monotonic time, so a clock change can only end the
timer early, never extend it.

- **Reboot with time left:** the timer continues for the remaining time and Keep-Alive's boot path
  switches Wifi-ADB on again.
- **Time ran out while the phone was off,** or after the boot but before KeepADB ran (Android
  delivers the boot broadcast after the first unlock): the boot path ends the timer before it looks
  at Keep-Alive, Wifi-ADB stays off, and the notice says so.
- **Untrustworthy clock** (unreadable boot count, or after a reboot a wall clock before the timer's
  base): the timer ends at once (fail closed) and the notice says why. A timer cannot be started
  without a readable boot count.

## Diagnostics

The journal records `timer` events: `started` (with the duration), `extended`, `ended`
(`manual_off`, `manual_on_without_timer`, `no_limit`), `expired` (`deadline_passed`, `while_off`,
`safety`), `cancelled` when a start's switch-on did not go through (`result=<outcome>`, plus
`previous_kept` when a timer that was already running keeps its deadline), `rebound` after a
reboot or clock change, and whether the notice was posted. No network
names or addresses are recorded.

## Rollback

Four additive keys (`timer_state`, `timer_expired_notice_pending`, `timer_expired_reason`,
`timer_shutdown_pending`) plus the
exported `timer_default_duration`. An older app version ignores them: the limit is gone, and
Wifi-ADB behaves as Keep-Alive and the last intent say.
