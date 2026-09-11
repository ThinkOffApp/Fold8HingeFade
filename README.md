# Fold8 Hinge Fade

The iPhone-Duo-style fold transition on a Galaxy Z Fold 8, as a normal app.

Fold the phone and the screen you were looking at shrinks into a card and dims as the hinge closes,
then the same picture lands on the cover screen as it wakes and fades out over the real cover UI.
One capture, both screens, driven live by the hinge angle sensor.

## How it works

1. The app is an accessibility service, because that is the one thing a normal app can be that
   takes a screenshot without a consent dialog per session and keeps running across folds
   (`AccessibilityService.takeScreenshot`, 20 to 60 ms measured on a Pixel Fold emulator).
2. `TYPE_HINGE_ANGLE` reports the hinge in degrees. At the first degree below flat (168°) the
   service takes one screenshot of the inner screen.
3. On the inner screen nothing shrinks: an overlay only darkens the live screen a little (up to
   35 %) as the hinge closes between 168° and 40°.
4. When a lit display much smaller than the inner one appears (the cover panel waking, either as a
   new `Display` or as display 0 changing size, both are handled) the frame is shown on it at the
   size it had, centre-cropped 1:1 by pixel density, holds 120 ms, then dissolves over 420 ms into
   the live cover UI underneath (AGSL `RuntimeShader`, one draw call).
5. Unfolding back past 172° re-arms it. If the hinge stops moving for 2.5 s without a cover
   appearing, or the dissolve somehow does not end, the overlay is removed anyway.

## Permissions

- *Draw over other apps*: the overlay windows.
- *Accessibility*: enable "Fold8 Hinge Fade" once under Settings > Accessibility. That is what
  allows the screenshot; the service reads no screen content and handles no events.

The app never stores or sends a capture; the frame lives in memory for one fold.

## Verified so far

On a Pixel Fold emulator (API 36, `adb emu fold` / hinge sensor sweep): capture at the first degree
of fold in 17 to 60 ms, the inner-screen card crossfade following the angle, the cover panel detected
as display 0 changing size, hand-off animation started, re-arm on unfold. Not yet seen on a real
Galaxy Z Fold 8.

## Known limit: banking apps

Banking apps refuse to run while any third-party accessibility service is enabled, and the
screenshot path needs exactly that. Turn the service off under Settings > Accessibility before
using the bank; there is no version of this approach that keeps both.

## Known limit: the phone must not lock on fold

App overlays draw beneath the lock screen. If folding the phone locks it (the default on most
foldables, and what the emulator does), the cover shows the lock screen and the second half of the
effect is invisible. On a Galaxy Z Fold, allow the app you are using under
*Settings > Display > Continue apps on cover screen*, or turn off lock-on-fold, and the cover hand-off
can show. Debug knob to slow the cover finish to 4 s for screenshots:
`adb shell settings put global hingefade_slow 1`.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kotlin, AGP 9.3, minSdk 33 (AGSL). Target: Galaxy Z Fold 8; exercised on the Pixel Fold emulator.

## Limits

This is a proof of concept, not the system transition: Samsung's own display switch still happens
underneath, and the app cannot replace it, only paint over it. The look of the last few degrees on
the cover side depends on how fast the cover panel is reported lit.
