# Lap avg / Lap NP

Tap the Lap avg tile to switch between the lap average and the lap's normalised power, labelled
"Lap NP" (first tried as just "NP", screenshots 02 and 04). Remembered between rides, like the
Target tile. Works on the free-ride screen too (Karoo's own lap there).

NP: the 30-second rolling average of power, raised to the 4th power, averaged over the lap, then
the 4th root. It shows `--` for the first 30 s of each lap. See `lap-np-toggle.html`.

`device/`: 01 Lap avg, 02 NP in the first 30 s, 03 back to Lap avg, 04 NP with a value,
05 the final "Lap NP" label.
