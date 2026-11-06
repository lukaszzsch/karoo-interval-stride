# Jump flow and "Next up" box

Design sketches (open the `.html` files in a browser) and screenshots from the Karoo (`device/`).

**Agreed behaviour**
- "Next up" box, 48 dp: ⏮ and ⏭ (34 dp) near the edges, invisible tap areas covering each end,
  centred "Next up" over time and watts. No zone.
- Each ⏭ / ⏮ tap moves the target. The label says how far: "Jumping 1 ahead", "Jumping 2 back",
  or after a single ⏮ "Restarting interval". On the chart the target bar turns solid blue with a ▼,
  and the chart shows the whole workout.
- While choosing, a small ✕ (left) and "✓ Confirm move" (right) replace the Lap avg / Target
  tiles. Confirm sits right above ⏭, the most used button, so it's the closest tap; the layout is
  the same after ⏮ (`confirm-swap.html`, option A; `device/35`–`37`). Nothing moves
  until Confirm; the choice cancels itself after 10 s.
- Confirm → 5-second countdown with beeps → jump (one lap marked). Tap the countdown to start
  right away. No ✕ on the countdown: once confirmed, the move happens (a ✕ there read as
  "close the countdown"). The countdown text is centred in the box.
- Tiles, Confirm move + ✕ and "Move cancelled" share one fixed 52 dp row, so nothing shifts.
- Cancel while choosing: ✕, tap the middle of the box, or tap back to the current interval.
- The ✕ next to Confirm move gives a short beep and shows "ⓘ Move cancelled" in that row for
  3 s; tap it to hide it sooner (`device/32`–`34`). It's an inverted, solid box (dark in light
  mode, light in dark mode) so it sits above the range tint as a message, not a tile
  (`move-cancelled-solid.html`, option B; `device/38`).

Earlier iterations (kept for history): `jump-flow.html` (3–4 s auto-confirm fill, replaced by
Confirm move) and `target-clarity-options.html` (option C chosen).
