# Control widget (#912)

The home-screen widget **KeepADB controls** holds up to four controls. The larger the widget, the
more of them it shows and the more it explains. It only reads the app's state
(`KeepADBWidgetState`); drawing or resizing it never sends a report, never starts a discovery and
never polls.

## The four controls

| Control | Tap | State words |
|---|---|---|
| Debugging (Wireless Debugging) | switches it, with the permission, trust and debounce behavior of the app and the tile | Off, Off, waiting, Switching…, Searching…, Disconnected, Connected, Permission missing |
| Keep-Alive | switches the Keep-Alive setting; Wi-Fi, trust and backoff rules stay with the service | Off, On, Waiting, Paused |
| Auto-sync | switches automatic webhook synchronization to the opposite state; without a saved URL it opens the webhook form | Off, On, Set up |
| Report now | pushes the current endpoint to the webhook, see [Webhook contract](webhook-register.md) | Ready, Sending…, Sent, Failed, No endpoint, Set up. It exists only while automatic synchronization is on: with it off (or without a saved URL) the control is not shown at all, the others widen, and the way to switch synchronization on is the Auto-sync control |

Every tile has an icon of its own, a label and its state in words, so no state depends on color
alone. A tile that needs the user (setup, paused, failed) has a dashed outline, an active one a
heavier green outline. The texts are one line each that shrinks (not below 9sp) before it is cut,
so no word or number is broken in the middle. The tile shows "Connected" only; the endpoint has
its own line in the status box of the larger sizes.

## Sizes

The layout follows the size the launcher reports (and, on Android 12 and newer, the launcher picks
it while the user resizes). All thresholds grow with the font scale, so a larger font falls back to
a layout with fewer, still readable controls; above a font scale of 1.5 the label gives way to the
icon and the state word (TalkBack still reads the full name and the state).

| About | Shows |
|---|---|
| 1x1 | the main action |
| 1x2 and taller, narrow | two actions on top of each other |
| 2x1 | two actions side by side |
| 2x2 | all four actions in a grid; taller (about 2x3): plus the status box |
| 4x1 | the four actions in a row |
| 4x2 | the four actions, flat, plus the status box and the settings gear |

The status box (tall 2x2 and 4x2; the tiles keep their flat height and the box takes the rest)
shows the endpoint on one line while details are visible (cut in the middle if it must be, so the
port stays readable; the narrow box shows `host:port` without the word), or the reason for the
state in the home screen's own wording (permission missing, Keep-Alive waiting or paused and why),
and one line for the webhook ("not set up", "sync off", sending, failed, current endpoint reported,
not reported yet, no endpoint). Tapping it opens the relevant place in the app: the home screen
for a missing permission, the network settings for an untrusted network, the webhook form for a
missing or failing webhook. In the 4x2 a small gear opens the widget's settings. Tiles are at
least 48 dp high and wide. The Wi-Fi name and network details are part of the overview widget,
not of this one.

## Settings per widget

Each placed widget has its own settings. On Android 12 and newer a widget is placed with the
defaults (`configuration_optional`) and configured afterwards, from the gear or the launcher's
"reconfigure"; on Android 11 the settings screen opens at placement:

- **Main action:** the control shown alone when the widget is small.
- **Order:** the position of the four controls in every size. A small widget shows the main
  action plus the first of the others, in this order; a position never changes when the widget
  grows, a control only appears.
- **Hide connection details in this widget:** an additional, stricter option.

The settings live in their own private file (`keepadb_widget_config`), keyed by the widget id,
and are removed with the widget. They never change the app's settings or another widget. A widget
placed before this version has no entry and shows the defaults: Debugging, Keep-Alive, Auto-sync,
Report now, Debugging as the main action, details visible.

## Privacy

The privacy switch for widgets and the tile ([defaults](defaults.md)) takes precedence: while it
is on, no widget shows a host, port, IP address or Wi-Fi name, whatever its own option says. The
per-widget option can only hide more. The webhook URL is never shown on the widget.

## Staying in step

While a screen or the Keep-Alive service runs, the app observes `adb_wifi_enabled` and the
widgets follow. With neither running, a change made elsewhere (Developer options, `settings put`)
would leave the widget on the old state until the app was opened. `KeepADBWidgetRefreshJob`
closes that gap: a `JobScheduler` job with a content trigger on the setting, scheduled while a
widget exists, runs when the setting changes and re-renders the widgets from the read-only
projection (no report, no discovery, no polling), then schedules itself again. Limit: a
force-stopped app has no scheduled jobs; its widgets show the last drawn state until the app is
started again (by the user, a widget update or a reboot).

## Why placement still asks for a refresh

`updatePeriodMillis` is 0, so the system updates the widget only at placement and after a restart.
`KeepADBWidget.onUpdate` then asks the endpoint coordinator for one refresh: after a restart the
boot receiver starts the service only when Keep-Alive is on, so with Keep-Alive off the placed
widget is what makes the notification and the endpoint discovery pick up an already enabled
Wireless Debugging. Drawing, resizing and state refreshes do not.
