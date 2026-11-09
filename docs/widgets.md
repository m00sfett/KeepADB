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
before they could be cut, and never get an ellipsis; the port is one line in bold tabular figures and never
wraps. The pill (Auto-sync, Keep-Alive beside the main control) is the exception: its label may wrap
to two lines and shrink to about half its size, so a long label ("Sinkron otomatis", "Автосинхр.")
stays whole at two cells and at large font scales; the floor is in sp and grows with the font scale. TalkBack reads "name: state, port N" from the control's description, in full, even where only
"ADB" or a symbol is visible.

## Sizes

The layout follows the size the launcher reports. On Android 12 and newer the widget gives the
launcher one view per size it reports (`OPTION_APPWIDGET_SIZES`, else the portrait and landscape
sizes of the classic options, else the declared 152 x 92 dp), each keyed 2 dp below that size
and drawn as the class `KeepADBWidgetLayout.choose` gives the key; the launcher picks the entry for the size the widget
has while the user resizes, and a resize rebuilds the entries. (Entries keyed by each class's
minimum size would disagree with the class choice: the host takes the entry with the smallest
distance, not the largest class that fits.) Limits of the platform's selection, which the entries
account for: the platform accepts at most 16 entries (`RemoteViews(Map)` throws above that, so
there are never more, the largest sizes by area are kept, and a refused map falls back to one
view for the first reported size); an entry fits a widget only if the widget is not smaller than
its key (`ceil(width) + 1 > key`), so each key is 2 dp below the reported size and drawn as the
class of that key, because a widget a dp smaller than reported would otherwise fit nothing; and
where nothing fits the platform takes the smallest entry by area, so one more entry keyed 1 x 1
dp shows the hero alone (it fits every widget but is only taken when no other entry fits). On
Android 11 both orientations are supplied, so a
rotation needs no update. With the font scale `fs`: wide = 250 dp x `fs`, medium = 136 dp x `fs`. All thresholds
grow with the font scale, so a larger font falls back to a layout with fewer, still readable
controls. The hero is in every class. A tall class is chosen only when everything it stacks fits
(`KeepADBWidgetLayout.minHeightDp`: the frame, the gaps, the fixed 48 dp pills, 52 dp buttons and
status line, and a hero that holds its symbol and port box, each at least 48 dp); a room that
cannot hold it gets the next smaller class:

| About | Class | Shows | Needs (fs 1.0 / 1.3 / 2.0) |
|---|---|---|---|
| 1x1 | `ONE` | the hero alone: symbol, "ADB" (not translated) and a short state word (On, Off, Scan…); no port | whatever there is |
| 2x1 | `ROW1` | the hero (symbol and port) and one square symbol button | 137 / 158 / 207 dp wide |
| 3x1 | `ROW2` | the hero and two square symbol buttons | 194 / 215 / 264 dp wide |
| 4x1 | `STRIP` | the hero and two upright tiles (symbol, label, state word) | 250 / 325 / 500 dp wide |
| tall, at least 114 dp wide | `STACK` | the hero on top, two square buttons below | 134 / 141 / 155 dp high |
| 2x2 | `GRID` | the hero (symbol, label, large port) and two 48 dp pills under it | 136 / 177 / 272 dp wide, 184 / 193 / 220 dp high |
| 4x2 | `PANEL` | the hero over two rows on the left, two pills growing with the height on the right, and under them one status line with the settings gear | 250 / 325 / 500 dp wide, 176 / 194 / 244 dp high |
| under 130 dp wide | `ONE`; from 130 dp `HERO` | the hero alone (with its port from 130 dp) | |

In a short widget the hero gets as many square buttons (at most two) as fit beside 70 dp x `fs` of
room for it. The launchers report less than the cell grid suggests (two cells: 144 dp on Android 14,
137 dp on Android 11; two rows: 193 and 176 dp), so the thresholds sit a little below the round
numbers. What that gives at the measured sizes:

| | Android 14 launcher (144 / 296 dp wide, 193 dp for two rows) | Android 11 launcher (137 / 291 dp wide, 176 dp for two rows) |
|---|---|---|
| font 1.0 | 2x2 grid, 4x2 panel | 2x2 **stack** (176 < 184 dp), 4x2 panel |
| font 1.3 | 2x2 stack, 4x2 **stack** (its entry is keyed 2 dp below the 193 reported, the grid needs 193), 2x1 only the hero, 4x1 the hero and two buttons | 2x2 and 4x2 stack (176 dp), the rest as on Android 14 |
| font 2.0 | 2x2 and 4x2 stack (193 < 220 dp), 2x1 only the hero, 4x1 the hero and two buttons | the same |

A room that is neither (for example 152 x 140 dp at font 1.0, as on some other launchers) is the
stack, not the grid.

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

## Overview widget (#913)

The second widget, **KeepADB overview** (`KeepADBOverviewWidget`), is for watching rather than
operating. It is about 2x2 and larger (default 4x2 on Android 12+) and has two shapes, chosen from
the width (`KeepADBOverviewViews`, wide from 250 x font scale dp):

| Shape | Content |
|---|---|
| Compact | One card with four rows (symbol and value): Wireless Debugging with the verified `host:port`, the Wi-Fi name, Keep-Alive with its reason, the webhook delivery; under the delivery a quieter line with the last delivery. Under the card the auto-sync pill. |
| Wide | The card with labelled rows (Debugging, Wi-Fi, Keep-Alive); beside it the webhook block (delivery state, last delivery) and the auto-sync pill. |

**Informational rows.** The card is one tap target. It opens the app, or the network card of the
settings when Keep-Alive is paused on an untrusted network. It never toggles Wireless Debugging or
Keep-Alive (`KeepADBOverviewWidgetTest`). TalkBack reads every row from the card's description.

**The one control** is the automatic webhook synchronization: the same tile, words and action path
as the control widget's Auto-sync. Without a saved URL it opens the webhook setup. Report now is not
offered; it stays in the webhook failure notification.

**Delivery.** The block says, in this precedence: not set up, sync off, sending, failed, current
endpoint reported, current endpoint not reported yet, no endpoint to report. The claim "reported"
comes only from the projection's currentness guard (the last accepted report names the current
endpoint and the configured URL). The time of the last accepted report is shown on its own line as
history (in both shapes, drawn, not only spoken) and never turns into that claim. The compact shape
draws short forms of both ("Reported", "Not reported", "No endpoint"; "Last 14:03", "None yet"),
so they stay readable at two cells, in long languages and at large font scales; TalkBack reads the
full sentences in both shapes. A tap on the block opens the webhook form when it is not
set up or failing, otherwise the app.

**Wi-Fi name.** Android withholds the name without location access; the row then says "Name not
readable" instead of inventing one. With details hidden the name is not read at all. The row
follows the same refresh signals as everything else, so a network change is drawn when the app,
the service or a state change next refreshes the widgets.

**Picker.** Android 12+ shows a preview layout (`widget_preview_overview`); Android 11 a picker
image of its own (`ic_widget_preview_overview`), so the two widgets never look alike there.

**Settings and lifecycle.** The overview has one option, "Hide connection details in this widget",
in the same configure screen (it hides the primary and order sections for an overview id) and the
same per-instance store as the control widget; widget ids are unique per host, so entries never
collide. It has no state, timer or report loop of its own: `KeepADBWidget.refreshAllState` draws
both kinds, the refresh job below watches the setting for both, and removing the last widget of
one kind keeps the other kind's settings and the job.

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
job is pending (either widget kind counts): one lookup, idempotent, no network, no polling. **Remaining limit:** between a
force-stop and the next process start (opening the app, tapping the widget, the tile or a
notification, or a reboot) no job exists and the widget shows its last drawn state.

## Why placement still asks for a refresh

`updatePeriodMillis` is 0, so the system updates the widget only at placement and after a restart.
`KeepADBWidget.onUpdate` then asks the endpoint coordinator for one refresh: after a restart the
boot receiver starts the service only when Keep-Alive is on, so with Keep-Alive off the placed
widget is what makes the notification and the endpoint discovery pick up an already enabled
Wireless Debugging. Drawing, resizing and state refreshes do not.
