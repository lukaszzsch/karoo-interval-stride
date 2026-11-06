# Karoo native styling

A setting on the Account page ("Karoo native styling", off by default) that makes the interval
screen look like Karoo's own data pages. It applies to all five watts options (classic screen and
gauge). Free ride is unchanged.

What changes when it's on:

- Font: IBM Plex Sans Condensed (`ibm-plex-sans-condensed`, a Karoo system font), not bold.
- Labels: UPPERCASE ("INTERVAL 1 / 16", "LAP AVG", "TARGET RANGE", "HR", "NEXT UP"), a step smaller,
  with Karoo-green icons (timer, lap + bolt, target). Outline heart, like Karoo's HR field.
- Lap avg and Target / HR are Karoo-style cells: icons left, label and value right, a thin line
  between them, no backgrounds.
- No outline around Next up.
- Skip button in Karoo yellow; grey and not tappable while the ride or workout is paused, so the
  yellow play button is the one in focus. Our resume button is Karoo yellow too.
- Gauge: Karoo-green band and the condensed font for the watts.
- "INTERVAL 1 / 16" sits on the bottom line of the time, as on the classic screen.
- Sizes match the regular screen (numbers are the same height).

Kept: range colours (blue / green / red number and tint) and the chart colours, since they carry
meaning.

Files: `field_interval_karoo.xml` and `field_interval_gauge_karoo.xml` (generated from the regular
layouts; keep them in step), `karoo_*` colours, `ic_target`, `ic_timer`, `ic_heart_outline`.

Sketches: `gauge-karoo-style.html` (first look), `karoo-style-all-modes.html` (all five options).

Device screenshots (`device/`):

- `01-gauge-light.png`, `05-gauge-dark.png`: gauge option.
- `02-classic-light.png`, `04-classic-dark.png`: classic screen (options 1–4).
- `03-workout-paused-light.png`: yellow resume button, grey skip button.
- `06-setting-on.png`: the switch on the Account page (cropped, no account details).

## Round 2 (after the first device build)

- Tonal tiles are back behind Lap avg and Target / HR; the middle line is gone.
- Label and icon sizes match Karoo's own fields: 13sp labels in the text colour, 16dp icons,
  filled bolt, 24sp values, 56dp cells. The Target cell always says "TARGET" (the value shows
  whether it's a range; "TARGET RANGE" doesn't fit at this size).
- Icons are locked to the label's baseline (`android:baseline`), so they sit on the middle of the
  capitals.
- The skip button is grey and not tappable while the ride or workout is paused, in both styles.
- Next up: **border (A) chosen**. Tonal background (B) was tried and dropped.

- `10-A-next-up-border-light.png`, `11-A-next-up-border-paused-light.png`
- `12-B-next-up-tonal-light.png`, `13-B-next-up-tonal-paused-light.png`, `14-B-next-up-tonal-gauge-light.png`
- Next up text: "NEXT UP" toned down (11sp, secondary colour), sits closer to the step, and the
  step is bigger (up to 20sp), bold and in the text colour. `15-next-up-bolder-light.png`
