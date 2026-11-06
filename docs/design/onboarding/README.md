# First-run introduction

New users used to land straight on the Account page. Instead, the first time the app opens, four
short pages: 1 Welcome + connect intervals.icu (API key, athlete ID 0), 2 Choose a look
(Appearance and Karoo style, as on Settings), 3 Add it to a ride profile (with the Karoo's own
Data Pages and Select Data screens), 4 Tap the watts: the app's key trick gets the last page, with
all five versions (Number, Colour, Background, Both, Gauge) as real crops of the ride screen, then
Pick a workout (opens the Workouts page and syncs). Skip on pages 1–3; shown once (a flag in
`PlanStore`); users who already have an API key never see it. Everything stays changeable in
Settings, which also gets "Show introduction again".

`preview.html` (from `preview.src.html` via `build.py`, rendered as `preview.png`), drawn on real
Karoo 3 screenshots in `base/` (the Settings page for the status bar; the Karoo's Profiles →
Data Pages and Select Data → Interval Screen screens for step 3).

Round 1 had step 2 options A (watts on step 2) and B (watts as a tip on the last page). Feedback:
tapping the watts is a key function, so it became its own final page; step 2 is B.

`base/watts-*.png`: the Interval Screen on the Karoo's pre-ride screen (not recording, demo data),
one tap apart.

## Built

- `IntroActivity`: four pages, step dots, Skip (pages 1–3), Back / Next. Page 1 saves the key on
  Next or Skip (an empty key just moves on). Page 2 recreates itself when Appearance changes.
  Page 3 uses the Karoo's screenshots (`drawable-nodpi/intro_data_pages`, `intro_select_data`)
  with the taps ringed in code. Page 4 uses crops of the ride screen, light and dark
  (`drawable-nodpi/` and `drawable-night-nodpi/intro_watts_*`).
- Shown by `SetupActivity` when there's no API key and `PlanStore.introDone` is false; Finish or
  Skip sets it. Riders updating with a key never see it.
- Last button: **Pick a workout** when connected (closes the introduction; the Workouts page
  underneath syncs), **Done** when not, or when opened from Settings → **Show introduction**.
- The Settings controls (heading, segmented control, help, divider) moved to `Ui.kt`, shared by
  Settings and the introduction.
- Copy shortened on the device so nothing scrolls: "Runs your intervals.icu workouts…",
  "Karoo's fonts, icons and buttons.", "Change the rest in ⚙ Settings."

Device screenshots (`device/`, demo data; the key in 06 is a made-up test key):
`01-welcome-light`, `02-look-light`, `03-add-to-ride-light`, `04-tap-the-watts-light`,
`05-look-dark`, `06-welcome-dark`, `07-tap-the-watts-dark` (Pick a workout),
`08-after-pick-a-workout-dark` (the Workouts page that follows), `09-settings-show-introduction-light`.
