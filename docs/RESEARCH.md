# Research: replacing a monitor colorimeter with an Android phone camera

## 1. What a hardware calibrator does

Consumer calibrators (X-Rite / Calibrite i1Display Pro, Datacolor SpyderX) are **tristimulus colorimeters**: three
photodiodes behind glass filters shaped to approximate the CIE 1931 colour-matching functions. They sit flat on
the panel under a rubber hood that excludes room light and read absolute luminance in cd/m² (nits).

| Property | i1Display Pro / Plus | SpyderX | Phone camera |
| --- | --- | --- | --- |
| Sensor | 3 filtered photodiodes | 3 filtered photodiodes | Bayer CMOS, millions of pixels |
| Spectral match to eye | Engineered filters, per-panel correction matrices | Engineered filters | Bayer dyes, designed for pleasing photos |
| Absolute luminance | Yes, factory calibrated | Yes | No reference, relative only |
| Low-light floor | ~0.0025 nits | ~0.02 nits | Noise floor far higher; blacks are a lower bound |
| Ambient rejection | Hood | Hood | Phone body pressed to glass, imperfect |
| Output | ICC profile + video LUT via DisplayCAL / CalMAN / vendor app | Same | Measurements, report, no LUT yet |

Sources: [ChromaPure meter comparison](https://chromapure.com/newgear-new.asp),
[ChromaPure i1Display Pro accuracy](https://www.chromapure.com/newgear_i1%20Display%20Pro%20Accuracy.asp),
[Which meter is right for me](https://www.curtpalme.com/ChromaPure_which_meter_is_right_for_me.shtm).

## 2. Can a phone camera do the job?

**Relative luminance, gamma, grey tracking, contrast, uniformity, flicker: yes, with care.**
RAW sensor data is close to linear after black-level subtraction. Published calibrations of phone cameras as
luminance meters report 6 to 12 percent error after calibration, and RAW is far more linear than JPEG.
That is plenty to measure the shape of a gamma curve and relative differences between patches.

**Absolute nits: no.** Without a known reference light the camera cannot report cd/m². Apps that print nits are
guessing from assumed sensor constants. ScreenMeter lets the user type a known white luminance to scale results.

**Colour accuracy: limited.** The Bayer filters do not satisfy the Luther condition, so two colours that differ to
the eye can read identical to the sensor (metamerism). Every phone model differs. The camera's own
`SENSOR_COLOR_TRANSFORM` matrices from the Android HAL give a usable camera-to-XYZ conversion, good enough for
white point to a few hundred kelvin and an indicative gamut triangle, not for certification.

**Deep blacks: poor.** OLED contrast is reported as a lower bound whenever the black patch sits at the sensor
noise floor.

Sources: [Smartphone colorimetry accuracy](https://www.tandfonline.com/doi/full/10.1080/20548923.2024.2444168),
[Standardized calibration of consumer cameras (Leiden)](https://arxiv.org/pdf/1906.04155),
[Photometric testing of smartphone cameras, 2024 pilot](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11548313/),
[Smartphones as a light measurement tool](https://doi.org/10.3390/app7060616),
[Patent: monitor calibration with a mobile camera](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12651547).

## 3. Prior art

- **TruHu** (iOS + desktop): two photos, grey target and colour target, writes an ICC profile. Reviews say it
  matches monitors to each other well, which is a relative task. Subscription.
- **Display Calibration (Windows) + Camera Colorimeter (Android)**: requires Camera2 manual exposure and white
  balance, uses `RAW_SENSOR` where available, sRGB only, transfers readings over Bluetooth.
- **Screen Brightness Nits Meter** (iOS): camera-based luminance guess, no absolute calibration.

Sources: [TruHu](https://truhu.app/), [MakeUseOf review](https://www.makeuseof.com/calibrated-monitors-with-phone-camera-they-finally-matched/),
[Display Calibration on Microsoft Store](https://www.microsoft.com/en-us/p/display-calibration/9nblggh4wd9s),
[Camera Colorimeter](https://apkpure.net/camera-colorimeter/com.auralisoft.colorimeter).

## 4. Physics of pressing a lens against a screen

- **Defocus is good.** Phones cannot focus closer than roughly 5 to 10 cm, so a screen a few millimetres away is
  always blurred. The blur averages sub-pixels exactly like a colorimeter's diffuser and suppresses moiré.
  ScreenMeter sets focus to infinity to make the blur as strong as possible.
- **Vignetting.** Only the central 25 percent of the frame is used.
- **PWM flicker + rolling shutter.** A PWM-dimmed backlight sampled with a short exposure shows dark bands across
  rows. Measurement exposures are auto-ranged toward long values at base ISO; a separate flicker test deliberately
  uses ~1 ms exposures and reports frame-to-frame spread and row banding.
- **Ambient light.** The phone body is the only hood. Run in a dark room.

Sources: [Flicker-banding and moiré removal (arXiv 2602.01559)](https://arxiv.org/html/2602.01559v1),
[Testing PWM flicker with a camera](https://displaymaster.pages.dev/blog/pwm-flicker-test-monitor/).

## 5. Android camera technology used

| Need | Android API | Notes |
| --- | --- | --- |
| Linear sensor data | `ImageFormat.RAW_SENSOR` via Camera2 `ImageReader` | Requires `REQUEST_AVAILABLE_CAPABILITIES_RAW`; CameraX 1.5 also added `OUTPUT_FORMAT_RAW` but Camera2 gives direct control |
| Locked exposure | `CONTROL_AE_MODE_OFF`, `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `SENSOR_FRAME_DURATION` | Requires `MANUAL_SENSOR`; some devices (e.g. Galaxy A51) advertise it but ignore exposure time |
| No colour processing | `CONTROL_AWB_MODE_OFF`, `NOISE_REDUCTION_MODE_OFF`, `EDGE_MODE_OFF` | Only matters for the YUV fallback |
| Black level | `SENSOR_BLACK_LEVEL_PATTERN`, per-frame `SENSOR_DYNAMIC_BLACK_LEVEL` | Per 2x2 CFA position |
| White level | `SENSOR_INFO_WHITE_LEVEL`, per-frame `SENSOR_DYNAMIC_WHITE_LEVEL` | Normalises to 0..1 |
| Sensor to XYZ | inverse of `SENSOR_COLOR_TRANSFORM2` when `SENSOR_REFERENCE_ILLUMINANT2` is D65, else `..._TRANSFORM1` | The AOSP CTS `RawConverter` documents the full DNG pipeline |
| CFA layout | `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` | RGGB, GRBG, GBRG, BGGR, MONO |

Why not a web app on the phone: browser `getUserMedia` exposes manual exposure only on Chrome for Android and
never RAW; Safari exposes nothing. A native capture layer is unavoidable.

Sources: [Camera2 CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest),
[CameraCharacteristics reference](https://developer.android.google.cn/reference/kotlin/android/hardware/camera2/CameraCharacteristics),
[AOSP CTS RawConverter](https://android.googlesource.com/platform/cts/+/master/tests/camera/src/android/hardware/camera2/cts/rs/RawConverter.java),
[CameraX RAW in 1.5](https://www.androidauthority.com/jetpack-camerax-raw-photos-3498039/),
[Samsung A51 manual exposure quirk](https://forum.developer.samsung.com/t/a51-sensor-controls/7454),
[W3C image-capture implementation status](https://github.com/w3c/mediacapture-image/blob/main/implementation-status.md),
[webrtcHacks on manual exposure in browsers](https://webrtchacks.com/?p=3807).

## 6. Measurement pipeline implemented

1. Companion page shows a full-screen patch and acknowledges after two animation frames; the phone waits a further
   400 ms for panel response.
2. Phone auto-ranges exposure at base ISO so the green channel sits between 15 and 70 percent of full scale with no
   clipping. Bright patches average 3 frames.
3. ROI mean per CFA channel, black subtracted, divided by (white − black), then divided by exposure seconds × ISO/100.
   This yields exposure-independent "camera relative units".
4. Camera RGB → XYZ through the HAL colour matrix. Y is relative luminance, xy chromaticity, u'v' for Δu'v'.
5. Companion page fits gamma (least squares in log-log), computes contrast (flagged as lower bound at noise floor),
   white point CCT (McCamy), grey tracking Δu'v', gamut coverage vs sRGB and P3 (polygon clipping), 9-zone uniformity
   and a flicker index.

## 7. Absolute luminance from a camera

A camera with a known f-number N, exposure time t and ISO speed S is a radiometer with a loose calibration.
Sensor-plane illuminance from an extended source of luminance L is E = π L T / (4 N²) (T = lens transmission).
ISO 12232 saturation-based speed defines the exposure that saturates the sensor as H_sat = 78 / S lux·s, so a RAW
value at fraction v of full scale means H = v · 78 / S = E · t. Hence

    L = 4 N² v 78 / (π T S t)   [cd/m²]

Error budget, uncalibrated: ISO convention (SOS vs saturation, up to 1.4×), lens transmission and vignetting
(±10 %), the reported f-number (nominal), panel angular emission (LCDs fall off faster than Lambertian, which makes
the lens collect less light than the equation assumes, perhaps −10 to −25 %). One calibration against a known screen
removes the systematic part and leaves sensor shot noise and panel non-uniformity, a few percent.

Second, independent estimate: the phone's ambient light sensor. Laid face-down on a large uniform screen, the screen
fills the sensor's hemisphere and E = π L, so nits ≈ lux / π. ALS calibration is typically ±10 to 20 % and its
spectral response is not photopic, so treat it as a cross-check rather than a reference.

Sources: ISO 12232:2019 (exposure index definitions), the exposure equation N²/t = L S / K with K ≈ 12.5
(reflected-light meter calibration constant), and the Camera2 fields `SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`,
`LENS_APERTURE`, `SENSOR_INFO_WHITE_LEVEL`.

## 8. Known limits and next steps

- No ICC profile or LUT output yet. The data needed (grey ramp, primaries, white) is all captured, so a
  DisplayCAL-style `.cal` or a 1D LUT export is a natural next step.
- No per-phone correction. A one-time comparison against a real colorimeter would give a 3x3 correction matrix
  that could be stored per device model.
- HDR and wide-gamut OS modes are not handled; the page assumes the browser draws sRGB.
- Devices without `MANUAL_SENSOR` fall back to AE lock and YUV, flagged as approximate.
