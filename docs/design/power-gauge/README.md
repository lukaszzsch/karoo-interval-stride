# Power gauge

**The gauge is the 5th watts option** (tap the watts: plain → coloured number → coloured
background → both → gauge → plain). Options 1–4 keep the classic screen (HR in the top row, big
watts, interval row, Lap avg / Target tiles). Only the gauge option uses the layout below
(`field_interval_gauge.xml`).

In the gauge option, the big 3 s watts become a gauge: a grey arc with the target range in green and a needle at the
current watts, with the watts number inside the arc.

**Chosen: `gauge-b4.html`** (dark in range, dark below range, light in range).

- Top row: gear · "Target" label with the range under it · pause. Heart rate leaves the top row.
- Gauge: arc spans target −25 % to +25 %, rounded ends, needle stops at the ends.
- Then interval number, time left and progress bar, and the Lap avg | ♥ HR tiles (heart beside
  the "HR" label), in the same place as on options 1–4 (changed after the first build, which had
  the tiles right under the gauge).
- Then the "Next up" box (no interval number), then the chart.

Tried and dropped:

- `gauge-a.html`: range under the watts, HR tile labelled "Heart rate".
- `gauge-ab.html`: A (range under watts) vs B (range in the top row). B won.
- `gauge-b2.html`, `gauge-b3.html`: steps towards b4 (Target label above range, tiles moved up).
- `gauge-b5.html`: tiles without backgrounds, text centred.
- `gauge-b6.html`: icons instead of labels (lap + average, heart).
- `gauge-calm.html`: fewer sizes and weights, no box around Next up. Felt too far from b4.

Built (first device build): the arc spans the target ± 4 × the range's half width (at least ± 10 %
of the target), so a narrow intervals.icu range (±2.5 %) still fills about a quarter of the arc.

Device screenshots (`device/`):

- `00-first-build-below-range-light.png`: first build, too much space above the interval row.
- `01-gauge-in-range-light.png`: spacing fixed, 295 W in a 293–308 W range.
- `02-gauge-above-range-dark.png`: 325 W, needle near the right end.
- `20-gauge-classic-order-light.png`, `21-gauge-classic-order-dark.png`: final order, interval
  row and tiles where options 1–4 have them.
- `10-mode1-plain-light.png` … `14-mode5-gauge-light.png`: tapping through all five watts options.
