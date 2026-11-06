# Free ride in Karoo style (round 7, preview, not built yet)

Question: is there a Karoo-style view of the screen when no workout is chosen? No: the free-ride
screen (`field_free.xml`) follows Dark / Light / Auto but looks the same with Karoo style on or off.

`preview.html`, on a Karoo 3 screenshot of the free-ride screen (`base-free-ride.png`), next to the
workout screen in Karoo style (`base-workout.png`) for comparison. Same building blocks, sizes and
positions as the workout screen:

- Watts: Karoo's number font (Plex Sans Medium, 90% wide), capped, centred between the top row and
  the lap row.
- Lap row where the interval row is: lap icon + "LAP TIME" left, lap time right, one baseline.
- Cells: LAP AVG (lap + bolt icons) and HR (red heart), 54dp, 13sp labels, 28sp values. The HR cell
  keeps its zone tint, and the label shows the zone ("HR · Z4").
- Last lap where Next up is, without an outline: "LAST LAP" and "8:20 · 271 W".

Round 7b: the lap row says "LAP TIME" (was "LAP 4"); Last lap has no outline.

Round 7c: free ride has no Next up box or chart, so everything sits lower; Lap avg and HR are full
Karoo data-field size (145 px, 15sp labels, 48sp values); Last lap at the bottom between the
Karoo's back and next buttons. The watts stay the biggest.

Round 7d: the Lap avg and HR cells use Karoo's own field typography: values in Plex Sans Regular
(90% wide, like Karoo's fields; the compact workout cells use Medium), 28 px caps labels in black,
22 px icons on the capitals, 12 px insets. HR label is plain "HR" like Karoo's field; the zone shows
as the cell's tint. Close-up next to Karoo's own fields (`base-native.png`).
- Dark mode added to the preview (zone 1 and zone 4), in the app's own night colours (text white,
  tonal tile, Karoo green #1DB872, heart #FF5C66, dark zone tints). Simulated from the light
  screenshot (greys flipped); the Karoo's own buttons are approximated that way too.

## Built

`field_free_karoo.xml` (same ids as `field_free.xml`), picked by `renderFree` when Karoo style is
on: watts Plex Sans Medium capped at 107sp and centred; "LAP TIME" row (13sp caps, 23sp time);
Lap avg and HR as 77dp Karoo data fields (15sp caps labels, 12dp icons on the capitals, 48sp Plex
Sans Regular values bottom-right; HR tile takes its zone tint, else the tonal tile); "LAST LAP"
label and the value ("8:20 · 271 W") at the bottom between the Karoo's buttons.

`device/`: `00-free-ride-before-light`, `01-free-ride-karoo-light`, `02-free-ride-karoo-dark`.

Testing note: after a reinstall the Karoo once more showed a frozen frame (the view was started
and stopped right away, then not started again); restarting the ride app fixed it, as before.

## HR ↔ Lap HR, and HR in the last lap

- Free ride (both styles): tap the HR tile to switch between the current heart rate and the lap's
  average ("LAP HR", Karoo's `AVERAGE_LAP_HR`); remembered between rides. The tile's zone tint
  follows the value it shows.
- The last lap line adds the lap's average heart rate (`AVERAGE_HR_LAST_LAP`):
  "8:20 · 271 W · 142 bpm". In Karoo style it fits the 150dp between the Karoo's buttons and
  shrinks if needed.

`device/`: `03-free-ride-hr-last-lap-hr-light`, `04-free-ride-lap-hr-light`. Regular style uses
the same code ("Heart rate" ↔ "Lap HR"); not screenshotted.

## Gauge needle cut off at the top

At the top of the arc the needle's end was cut flat: the gauge image ended at the arc's outer
edge, and the needle sticks out past it. The gauge now keeps a small margin around the arc
(`NEEDLE_OVERHANG` in `GaugeRenderer`), so the needle always fits.

`device/`: `05-gauge-needle-before-zoom` (cut flat), `05-gauge-needle-fixed-zoom`,
`05-gauge-needle-fixed`.
