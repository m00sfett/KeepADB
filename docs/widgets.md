# Control widget (#912)

The home-screen widget **KeepADB controls** holds up to three controls. The larger the widget, the
more of them it shows and the more it explains. It only reads the app's state
(`KeepADBWidgetState`); drawing or resizing it never sends a report, never starts a discovery and
never polls.

## The three controls

| Control | Tap | State words |
|---|---|---|
| Debugging (Wireless Debugging) | switches it, with the permission, trust and debounce behavior of the app and the tile | Off, Off, waiting, Switching…, Searching…, Disconnected, Connected, Permission missing |
| Keep-Alive | switches the Keep-Alive setting; Wi-Fi, trust and backoff rules stay with the service | Off, On, Waiting, Paused |
| Auto-sync | switches automatic webhook synchronization to the opposite state; without a saved URL it opens the webhook form | Off, On, Set up |

There is no "Report now" control: that action exists only as the button of the webhook failure
notification (see [Webhook contract](webhook-register.md)). A stored widget configuration that
still names it keeps working: the entry is read, the retired control is ignored and a main action
that was "Report now" becomes Debugging.

## Design: hero and rail

The **main action** (default Debugging, see "Settings per widget") is the *hero*: a large tile that
is a yellow gradient while it is on. The other two controls sit beside or under it as flat pills,
upright tiles or square symbol buttons, depending on the room. There are no borders and no switch
look; the state is never told by color alone:

| State | Fill | Symbol | Word |
|---|---|---|---|
| on / connected | yellow (the hero: gradient), dark text and symbol | filled (`adb`, arrows, cloud with arrow) | On, Connected |
| off | dark, yellow symbol | outlined or struck through | Off |
| searching or switching (Debugging only) | lighter dark, yellow symbol | outlined bug with three dots | Searching…, Switching… |
| needs you (permission, set up, paused) | dark red, pink text and symbol | cloud with an exclamation mark, struck-through arrows or outlined bug | Set up, Paused, Permission missing |

All text and symbol colors have at least 4.5:1 contrast on their fill (`KeepADBWidgetColorsTest`).
The surface is `#0B0806` with a 28 dp radius, the controls have a 23 dp radius and a 5 dp gap.
Controls are at least 48 dp high and wide. The words shrink (not below 85 percent of their size)
before they could be cut, and never get an ellipsis; the port is one line in monospace and never
wraps. TalkBack reads "name: state, port N" from the control's description, in full, even where only
"ADB" or a symbol is visible.

## Sizes

The layout follows the size the launcher reports (and, on Android 12 and newer, the launcher picks
it while the user resizes). With the font scale `fs`: wide = 250 dp x `fs`, medium = 136 dp x `fs`,
tall = 140 dp x `fs`. All thresholds grow with the font scale, so a larger font falls back to a
layout with fewer, still readable controls. The hero is in every class:

| About | Class | Shows |
|---|---|---|
| 1x1 | `ONE` | the hero alone: symbol, "ADB" (not translated) and a short state word (On, Off, Scan…); no port |
| 2x1 | `ROW1` | the hero (symbol and port) and one square symbol button |
| 3x1 | `ROW2` | the hero and two square symbol buttons |
| 4x1 (wide, not tall) | `STRIP` | the hero and two upright tiles (symbol, label, state word) |
| 2x2 (medium and tall) | `GRID` | the hero (symbol, label, large port) and two 48 dp pills under it |
| 4x2 (wide and tall) | `PANEL` | the hero over two rows on the left, two pills growing with the height on the right, and under them one status line with the settings gear |
| tall, narrower than medium | `STACK` | the hero on top, two square buttons below (needs 114 dp) |
| short, under 130 dp | `ONE`; from 130 dp `HERO` | the hero alone (with its port from 130 dp) |

In a short widget the hero gets as many square buttons (at most two) as fit beside 70 dp x `fs` of
room for it. The launchers report less than the cell grid suggests (two cells: 144 dp on Android 14, 137 dp on
Android 11; two rows: 193 and 176 dp), so the thresholds sit a little below the round numbers.
Examples: at font 1.3 (Android 14 launcher; on Android 11 two rows are 176 dp, too little for the
grid at this font, so it stays one row) a 2x2 is the stack, a 2x1 only the hero, a 4x1 the hero with two
square buttons; at font 2.0 a 2x2 is only the hero.

The **port** is shown in the hero from the compact shape (130 dp) up, never in the smallest one
(`ONE`); without a port the short or full state word stands there. While the widget privacy switch
or the per-widget option withholds the details, five dots (`•••••`) stand where the port would be
and the status line says "Details hidden".

The **status line** (only the 4x2) is one text as high as its text, at least 48 dp for the gear: the
endpoint (`host:port`, stronger) or "Details hidden" or the reason for the state in the home
screen's own wording (permission missing, Keep-Alive waiting or paused and why), then the webhook
state ("not set up", "sync off", sending, failed, current endpoint reported, not reported yet, no
endpoint), at most two lines. Tapping it opens the relevant place in the app: the home screen for a
missing permission, the network settings for an untrusted network, the webhook form for a missing
or failing webhook. The round gear at its end opens the widget's settings; it exists only here,
everywhere else the settings are the launcher's "reconfigure". The Wi-Fi name and network details
are part of the overview widget, not of this one.

## Settings per widget

Each placed widget has its own settings. On Android 12 and newer a widget is placed with the
defaults (`configuration_optional`) and configured afterwards, from the gear or the launcher's
"reconfigure"; on Android 11 the settings screen opens at placement:

- **Main action:** the hero, the control shown alone when the widget is small.
- **Order:** the sequence of the other controls beside or under the hero. A small widget shows
  the hero plus the first of the others, in this order; a position never changes when the widget
  grows, a control only appears.
- **Hide connection details in this widget:** an additional, stricter option.

The settings live in their own private file (`keepadb_widget_config`), keyed by the widget id,
and are removed with the widget. They never change the app's settings or another widget. A widget
placed before this version has no entry and shows the defaults: Debugging, Keep-Alive, Auto-sync,
Debugging as the main action, details visible.

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
projection (no report, no discovery, no polling), then schedules itself again. Measured on an
Android 14 emulator: a change by `settings put global adb_wifi_enabled` redraws the widget within
a few seconds, and the job survives `install -r`.

A force-stop (`am force-stop`, "Force stop" in the system settings) deletes the app's scheduled
jobs, and the system does not update a placed widget again by itself. So every process start
(`KeepADBApplication.onCreate`) schedules the job again when at least one widget is placed and no
job is pending: one lookup, idempotent, no network, no polling. **Remaining limit:** between a
force-stop and the next process start (opening the app, tapping the widget, the tile or a
notification, or a reboot) no job exists and the widget shows its last drawn state.

## Why placement still asks for a refresh

`updatePeriodMillis` is 0, so the system updates the widget only at placement and after a restart.
`KeepADBWidget.onUpdate` then asks the endpoint coordinator for one refresh: after a restart the
boot receiver starts the service only when Keep-Alive is on, so with Keep-Alive off the placed
widget is what makes the notification and the endpoint discovery pick up an already enabled
Wireless Debugging. Drawing, resizing and state refreshes do not.
