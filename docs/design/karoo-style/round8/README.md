# Karoo style round 8: free ride LAP TIME row

On the free-ride screen (no workout) there's room to spare, so the "LAP TIME" row now matches
the Lap avg / HR boxes: label 15sp (was 13sp), icon 12dp with a 3dp gap, and icon and label are
centred on the lap time's height instead of sitting on its baseline.

- `preview.src.html` + `build.py`: approved preview, drawn on device screenshots (run `python3 build.py`).
- `base-light.png`, `base-dark.png`: device screenshots before the change.
- `device/01-lap-row-light.png`, `device/02-lap-row-dark.png`: built, on the Karoo 3.
