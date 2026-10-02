# Riff Buddy icon — three variations (the spec, brief in the spec)

Pick one and say which; the chosen one becomes the launcher icon, the splash mark
and later the Windows `.ico`. **A is wired in right now** so the app builds and
installs with something real on the home screen.

| | Variation | Concept | Why it might win |
|---|---|---|---|
| **A** ✅ | `variation-a-body.svg` | The brief as written: an original body-and-neck silhouette on a diagonal, with the pickup punched out as negative space. | Unmistakably a guitar app at a glance. Closest to what the spec asked for. |
| **B** | `variation-b-headstock.svg` | Just the headstock, three tuner pegs as negative-space holes. | Far more distinctive at 48 dp than another guitar body, and nothing else on a home screen looks like it. Reads as "guitar" without being a guitar outline. |
| **C** | `variation-c-knob.svg` | An amber amp knob, turned off centre, its pointer cut clean through. | Points at what the app is actually *for* — the tone card — rather than at the instrument. A knob that has been *set*, not one sitting at zero. Most original of the three; least literal. |

All three follow the brief: charcoal ground with a warm radial glow, one amber
shape, one negative-space detail, no letters, at most one gradient, and an
original outline that copies no manufacturer's body or headstock.

## Looking at them

`preview/icon-sheet.png` has all three side by side, each shown large and again
at a true 48 dp — open that first. `preview/icon-A.png` and friends are the
individual renders. The previews are drawn from the same geometry as the SVGs,
so they are accurate, but they are not the real renderer: the amber gradient and
the glow will be slightly smoother on the device.

Or open the `.svg` files in a browser. They are drawn on a **108-unit viewBox** —
the same coordinate space Android's adaptive icons use — so the numbers in the
SVG and in `res/drawable/ic_launcher_*.xml` are identical and a tweak to one is
a tweak to the other. To judge them honestly, shrink the browser window until
the icon is about a centimetre wide; that is the size it will actually be.

Everything sits inside the 66-unit safe zone, so no launcher mask — circle,
squircle, rounded square — can clip any of it.
