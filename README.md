# Fold8 Hinge Fade

The iPhone-Duo-style fold transition on a Galaxy Z Fold 8, as a normal app.

Fold the phone and the screen you were looking at shrinks into a card and dims as the hinge closes,
then the same picture lands on the cover screen as it wakes and fades out over the real cover UI.
One capture, both screens, driven live by the hinge angle sensor.

## How it works

1. A foreground service keeps a screen-capture session (MediaProjection) alive, mirroring the inner
   screen at half resolution into an `ImageReader`. The latest frame is always at hand, so the
   capture at fold time costs a buffer copy, not a round trip.
2. `TYPE_HINGE_ANGLE` reports the hinge in degrees. At the first degree below flat (168°) the
   service grabs the latest frame once.
3. An overlay window on the inner screen draws that frame through an AGSL `RuntimeShader`:
   the card scales to 62 %, rounds its corners and dims, over a black veil that thickens, all as a
   function of the angle between 168° and 40°.
4. When a lit display much smaller than the inner one appears (the cover panel waking, either as a
   new `Display` or as display 0 changing size, both are handled) the same frame is shown there,
   grows to fill the cover and fades out in 650 ms, revealing the live cover UI underneath.
5. Unfolding back past 172° re-arms it.

## Permissions

- *Draw over other apps*: the overlay windows.
- *Screen capture*: one consent tap after each start of the service (Android 14 rules).
- A foreground-service notification with a Stop action while armed.

The app never stores or sends a capture; the frame lives in memory for one fold.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kotlin, AGP 9.3, minSdk 33 (AGSL). Tested target: Galaxy Z Fold 8.

## Limits

This is a proof of concept, not the system transition: Samsung's own display switch still happens
underneath, and the app cannot replace it, only paint over it. The look of the last few degrees on
the cover side depends on how fast the cover panel is reported lit.
