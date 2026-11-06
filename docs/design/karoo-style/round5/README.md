# Countdown and "Move cancelled" (round 5, preview, not built yet)

Feedback: "Move cancelled" text and icon aren't centred in their box; the countdown's number and
step aren't centred either; maybe the countdown should change the background; and the step (time
and watts) can go to the right again.

`preview.html` (from `preview.src.html` via `build.py`), on Karoo 3 screenshots (`base-*.png`):

- Countdown: the number left, a small "JUMPING TO" label and the step right, all centred on the
  box's middle line; the step bold like Next up.
- How the countdown stands out:
  - A: inverted box (black in light mode, white in dark), like ⏭.
  - B: solid blue box, white text.
  - C: B plus a blue tint behind the top of the screen. Looks like the "below range" watts tint
    (also blue), so it can be misread.
- "Move cancelled": icon and text centred together on the box's middle line, in caps like the
  other labels.

## Built: option A

- Karoo style countdown: inverted box (`bg_countdown_inverted`, the text colour), the number in
  Plex Sans Medium on the left, "JUMPING TO" (11sp caps) and the step (20sp, bold) on the right,
  centred on the box's middle line.
- "MOVE CANCELLED": caps, no font padding, so text and icon share the box's middle line.
- Regular style: same centring fix (no font padding) for "Move cancelled" and the countdown step.

`device/`: `00-countdown-before`, `00-move-cancelled-before`, `01-countdown-inverted-light`,
`02-move-cancelled-centred-light`.
- Countdown text block now identical to Next up's: 11sp label, 1dp gap, the step on a 24dp line
  (up to 20sp, shrinks to fit), centred in the 48dp box, aligned right.
  `03-countdown-same-spacing-light`, `04-next-up-for-comparison-light`
