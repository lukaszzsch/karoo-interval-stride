# Screens for visuals

Clean Interval Screen screens for the README and the Karoo Artboard: real Karoo 3 captures in
Karoo style (debug build, demo data, workout "Stair Climb", interval 3 / 15), with the Karoo's
floating back/play buttons removed. The chart under them is rebuilt from the same workout's chart on
the Workouts page, at the app's own chart sizes; the 100/200/300 W labels at the chart's left edge,
which always sit under the back button on the device, are left out. One status bar per theme.

- `screen-classic-<light|dark>.png`: workout screen, watts with the in-range background.
- `screen-gauge-<light|dark>.png`: workout screen with the power gauge.
- `screen-free-ride-<light|dark>.png`: no workout loaded, Lap HR shown.

`../../images/interval-screen-<dark|light>.webp` (README) put the three in Karoo 3 frames on the
same background, device size and spacing as ClearWind's README images, so both apps look alike on
GitHub. The screens are also in `Karoo/design-assets/` as `31–33-interval-*` so the Karoo Artboard
preloads them.
