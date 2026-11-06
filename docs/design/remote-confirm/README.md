# Remote confirm

How to confirm an interval jump without touching the screen.

- [remote-confirm.html](remote-confirm.html): mockup with two options.
  - **A (chosen):** a choice made with a remote button confirms itself. The mockup showed 3 s;
    the build uses **5 s**. Confirm move fills from darker green to the usual green and reads
    "Confirming… 5" down to 1. Each press starts the wait over. Cancel with the opposite button
    (back to where you are) or ✕. On-screen ⏭ / ⏮ taps still need Confirm move.
  - B: a separate "Confirm move" remote action (not built).
- device/ (Karoo, dark mode, demo data; remote presses faked with the debug-only
  `DebugRemoteReceiver`):
  - 01: remote ⏭ pressed, "Confirming… 5", 1/5 filled
  - 02: "Confirming… 1", full
  - 03: confirmed by itself, the 5 s jump countdown runs
  - 04: pressed again, the wait starts over ("Jumping 2 ahead", "Confirming… 4")
  - 05: on-screen ⏭ tap: still plain "Confirm move" after 6 s

Testing from a computer:
`adb shell am broadcast -a com.daviddokakis.karoointerval.DEBUG_REMOTE -p com.daviddokakis.karoointerval --es id skip`
(also `previous`, `pause`). Debug builds only.
