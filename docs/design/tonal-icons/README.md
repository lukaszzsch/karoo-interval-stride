# Tonal corner icons and ✕

- The gear and pause icons use the text colour at 55% (`ICON_ALPHA`) instead of solid grey, so
  the watts range tint shows through. That's stronger than the tiles' 8% wash, because the icons
  need to be seen. The free-ride screen's gear matches.
- The ✕ next to Confirm move uses the tiles' tonal background (`bg_tile_tonal`).

On the Karoo a translucent tint colour comes out solid, so the icons get the opaque text colour
plus image alpha instead.

See `tonal-icons.html`. `device/`: 01 below the range, 02 in the range, 03 the tonal ✕.
Light mode only so far.
