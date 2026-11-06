# Karoo style, round 4 (preview, not built yet)

Feedback: with a route loaded the gauge got very small; the cell values were almost as big as the
watts. The watts must always be the focus, and what's under them must look the same everywhere.

`preview.html` (from `preview.src.html` via `build.py`), drawn on real Karoo 3 screenshots with and
without a route (`base-*.png`):

- Everything under the watts / gauge is one fixed, compact block, identical on every screen (route
  or not, classic or gauge): 13sp labels, 30sp values, 96 px cells (were 145 px with 48sp values),
  a smaller timer (44 px digits).
- The watts and the gauge get all the height that's left.
- Gauge: as the space gets shorter (a route), the arc gets flatter and wider (smaller sweep, larger
  radius) instead of the whole gauge shrinking; the watts take the biggest size that fits under it.
- Gauge top row: the target icon sits before the range on one line; the word "TARGET" is gone.

Round 4b:
- Watts a step thicker everywhere: Plex Sans Medium (the Karoo's `sans-serif`), still 90% wide.
- Gauge without a route: the watts sit higher, level with the arc's ends, when there's room.
- Classic: the watts are capped at a set size (200 px ≈ 107sp) and kept 20 px clear of the
  interval row.
- Round 4c: the no-route classic preview was drawn on an older screenshot (outline heart, thin
  number); its top row is now redrawn with the HR as built (red filled heart, Plex Sans SemiBold
  20sp), the same as with a route. Measured on the device, the top-row HR and the gauge's target
  range are identical (26 px digits, 5 px stroke).
- Round 4d: classic watts centred in their space: the gap from the HR row to the digits equals
  the gap from the digits to "INTERVAL" (still capped at 200 px), with and without a route.
- Round 4e: ⏭ inverted (text colour: black with a white symbol in light mode, white with a black
  symbol in dark), so Karoo's yellow stays for its own navigation (route pill, page arrows). Grey
  while paused, as before. The resume button stays Karoo yellow.

## Built

- Lower block (Karoo style): 54dp cells (13sp labels, 28sp values, a bit more room between label
  and value than the 51dp / 30sp first try), 13sp interval label, 23sp time, same on every screen.
- Watts: Plex Sans Medium (`sans-serif`), 90% wide. Classic: autosize up to 107sp, centred
  between the HR row and the interval row (24dp top padding, -8dp bottom margin, measured).
- Gauge: `GaugeRenderer` draws into the gauge's real space and picks the arc (flatter when short)
  that gives the watts the biggest size. The space isn't reported by the Karoo, so it's
  `GAUGE_KAROO_DP` / `GAUGE_REGULAR_DP` (measured with `uiautomator dump`) minus `ROUTE_DP` (79 px)
  while the Karoo is navigating (`OnNavigationState`).
- Gauge top row: target icon + range on one line, no "TARGET".
- ⏭ inverted in Karoo style (`bg_btn_skip_inverted`).
- Paused box: centred on the time's line (was baseline-aligned and cut off by the progress bar),
  "WORKOUT PAUSED" in caps like the other labels.

Device screenshots (`device/`, all with a route loaded): `00-before-gauge-route` (before),
`01-gauge-route-light`, `02-classic-route-light`, `03-gauge-route-dark`, `04-classic-route-dark`,
`05-paused-route-dark`. The yellow card in 01/02 is the Karoo's own turn-by-turn popup.
- Cell values (Lap avg, Target, HR) now Plex Sans Medium like the watts: at 28sp the Regular cut
  read too thin. `06-cell-values-medium-light.png`
- Checked without a route: gauge space 242 px (calculated 241), classic watts at their 107sp cap,
  centred (73 px above, 67 px below). `07-gauge-no-route-light`, `08-classic-no-route-coloured-light`
- One weight for the numbers (Karoo style): countdown, cell values, top-row HR and the gauge's
  top-row target range all in Plex Sans Medium (`sans-serif`, not bold), like the watts.
  `09-one-weight-gauge-light`, `10-one-weight-classic-light`
