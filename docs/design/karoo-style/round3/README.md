# Karoo style, round 3 (preview, not built yet)

`preview.html` (built by `build.py` from `preview.src.html`): drawn on real Karoo 3 screenshots
(`base-*.png`) inside the Karoo 3 frame, with the Karoo's own fonts (`Karoo/design-assets/fonts`).

Feedback this round answers:

- Cells the size of Karoo's own data fields. Measured on the Karoo's data page: a field is
  240 × 145 px; labels have 20 px capitals (≈15sp), icons 22 px centred on the capitals, 12 px insets,
  values 63 px digits (≈48sp). The Lap avg and Target / HR cells use exactly that; long values
  ("293–308") shrink to fit. The big watts shrink to make room.
- "INTERVAL 1 / 16" sits on the time's baseline (bottoms line up), at the same label size and with
  the same icon as the cells.
- Same 12 px insets in every cell.
- Settings: a new Settings page with appearance first (Karoo's own segmented control, as in Karoo
  Settings → Display → In-Ride Appearance: Dark / Light / Auto, #F4F4F4 track, #9FB3BD selection),
  the Karoo style switch, and the intervals.icu account as one row that opens the account page.
- Round 3b: interval timer a step smaller (50 px digits, was 60) and shown with a minus
  ("−2:18") so it reads as counting down; the preview has both versions side by side.
- Round 3c: numbers were heavier than Karoo's. Measured on the device: Karoo's digits have strokes
  11–12% of the digit height, ours 15–16%. Karoo's numbers are IBM Plex Sans **Regular**, about 90%
  wide with letters slightly closer (no bundled fonts in the ride app; checked against every Plex
  file on the device in the browser). On the Karoo that's `fontFamily="sans-serif-light"` (weight
  300 maps to IBMPlexSans-Regular), `textScaleX="0.9"`, `letterSpacing="-0.05"`. Labels stay
  IBM Plex Sans Condensed ("LAP TIME" 109 px vs 111 px native). The preview now uses this, with
  Karoo's own fields (`base-native-data-page.png`) next to our cells at the same zoom.

## Built

- Cells: 77dp tall (≈145 px), 6dp insets, 15sp labels with 12dp icons on the capitals' middle
  (`android:baseline`), values 48sp in Karoo's number font (`sans-serif-light` = Plex Sans
  Regular on the Karoo, `textScaleX` 0.9, `letterSpacing` -0.05). Each cell is a FrameLayout:
  header pinned top, value pinned bottom-right. A value too wide for 48sp (a range) is shrunk by
  the renderer and padded up so all values share one baseline (measured: 1 px apart).
- Interval row: label (or paused box) and time are siblings in one baseline-aligned row. Time
  27sp, with a minus: "−2:18" (both styles).
- Settings page (gear on the Workouts page): Appearance Dark / Light / Auto and Karoo style
  Off / On, both as Karoo's segmented control; intervals.icu account as one row.
- Appearance: app pages use a context with the chosen night mode (`AppearanceActivity`). The ride
  screen: `tools/gen_theme_overrides.py` reads the field layouts and generates
  `ThemeOverrides.kt` (every XML colour per view); with Dark / Light forced, the renderer paints
  the field background and re-applies each colour from the forced context (text colours, icon
  colour filters, background tints, progress tints). Re-run the script after changing a field
  layout.

Device screenshots (`device/`): `01-classic-light`, `02-gauge-light`, `03-paused-light`,
`04-forced-dark-on-light-karoo`, `05-forced-light-on-dark-karoo`, `06-settings-light`,
`07-settings-dark`, `08-workouts-dark`.

Note: during testing the Karoo's ride app once ran out of memory (after hours of reinstalls and
day/night switching in one session) and froze; after a restart its Java heap stayed flat at about
36 MB of 128 MB with the interval screen showing.
- Round 3d: HR keeps the red filled heart (top row and the gauge's HR cell), and the top-row HR
  number is Plex Sans SemiBold 20sp (was the light number font at 18sp, too small and thin).
  `09-hr-red-heart-gauge-light.png`, `10-hr-red-heart-classic-tint-light.png`
- Round 3e:
  - Range tint (watts options 3 and 4): same size and fade as before, as the bottom layer (the
    upper part's background, behind everything there), in every theme. (Tried filling the whole
    field; not wanted.)
  - Karoo style: on the tint, the green icons (timer, lap, bolt, target) take the text colour
    (black in light, white in dark) so they show. The timer is an ImageView (`icon_timer`, 14dp)
    so its colour can change.
  - Gauge: the target range in the top row uses the top-row HR's type (Plex Sans SemiBold 20sp).
  - `11-gauge-target-like-hr-light`, `12-tint-black-icons-light`, `13-tint-icons-dark`
- Round 3f: the range tint is one soft vertical fade, strongest at the middle of the watts (35%
  down the upper part), fading out gently to the top edge and the tiles (`bg_range_*`, both
  styles). `14-tint-soft-centred-light`, `15-tint-soft-centred-dark`
