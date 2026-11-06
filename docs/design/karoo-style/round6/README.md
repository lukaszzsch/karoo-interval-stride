# Karoo colours for jumps (round 6, preview, not built yet)

Idea: bring in more of the Karoo's own colours in Karoo style: yellow for Confirm move, purple for
the countdown.

The Karoo's own palette (from the ride app's resources): yellow `#FFE900` (k2YellowV2, its action
colour and workout marker), darker yellow `#CBBA00` (yellow_700); purple `#590D80` (purple_600:
`workout_tab_color`, `lap_button_color`), night `#EABFFF` (purple_400), `#A252CC` (purple_500,
lap icon).

`preview.html`, on Karoo 3 screenshots:

- Confirm move: Karoo yellow with a black check and "CONFIRM MOVE". Chosen with a remote: the
  button fills darker yellow as it confirms itself ("CONFIRMING… 3").
- Countdown: Karoo workout purple with white text (same layout and spacing as round 5). In dark
  mode it would use the Karoo's night purple `#EABFFF` with dark text, like its lap button.
- Everything that marks the coming jump turns purple too (Karoo style), while choosing and during
  the countdown: the "→ 181" next target in the Target cell and the chart's ▼ marker / landing
  step (blue now).
- The countdown box fills with the Karoo's darker purple `#36084D` (purple_700,
  `lap_tab_background`) as the seconds run down, like the yellow Confirm move fills when a remote
  chose the jump. The preview shows it at 4 and at 2.
- The next target in the Target cell ("300 → 181") was hard to read as purple text. Options:
  A: "181" in the text colour (black / white), only the arrow purple. B: "→ 181" as white text on
  a small purple pill. Both shown with 1.6× close-ups.

## Built (next target: option A)

- Confirm move: `bg_confirm_karoo` (Karoo yellow), black check and caps text; remote choice fills
  with `karoo_yellow_dark` (`progress_confirm_karoo`).
- Countdown: a FrameLayout with `countdown_fill` (ProgressBar, `progress_countdown_karoo`): Karoo
  purple filling with the darker purple, (5 − n + 1) / 5; white text (night: lilac box, dark text).
- Next target: "→ 181" in the text colour, Plex Sans Medium; only the arrow purple (a span).
- Chart: the landing step and its ▼ in `karoo_purple` in Karoo style.

`device/`: `01-confirm-yellow-chart-purple-light`, `02-confirming-remote-light`,
`03-countdown-4-purple-light`, `04-countdown-1-purple-light`.

## Brought to the regular style

Layout and readability, keeping the regular colours and fonts:

- Countdown: number left, "Jumping to" + step right, spaced like the regular Next up (11sp label,
  4dp gap, 20dp line up to 17sp); the pale blue box fills with `countdown_fill` (darker blue).
- Next target: "→ 181" with the number in the text colour and only the arrow blue.
- Classic watts: capped at 107sp and centred between the HR and the interval row.
- Gauge: "Target 293 – 308" on one line; the gauge moved up 4dp (`GAUGE_REGULAR_DP` 119.2).

Kept Karoo-only: fonts, caps and icons, the compact cells, yellow / purple, black ⏭, yellow resume.

`device/`: `10-regular-classic-light`, `11-regular-countdown-light`, `12-regular-gauge-light`.

Testing note: once, after switching Karoo style in Settings and going back to the ride, the Karoo
started the interval view and stopped it 30 ms later and showed a frozen frame; restarting the
ride app fixed it and the same steps then worked. Not reproduced.
