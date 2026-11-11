# ScreenMeter

Use an Android phone camera as a poor-man's display colorimeter. An Android app reads RAW sensor data with locked
exposure while a companion web page cycles test patterns on the monitor. Together they measure gamma, grey tracking,
contrast ratio, white point, gamut coverage, uniformity and PWM flicker. A live-meter mode reads any website, game or
app you put in front of the lens.

Read [docs/RESEARCH.md](docs/RESEARCH.md) first: it explains what a phone can and cannot replace. Short version:
relative measurements are solid on a phone with RAW + manual exposure, absolute nits are impossible without a
reference, and colour is indicative rather than certified.

## Layout

```
android/   Kotlin + Jetpack Compose app (Camera2, RAW_SENSOR, manual exposure, OkHttp WebSocket)
web/       Companion page: pairing, fullscreen patterns, report with charts, manual visual patterns
server/    Node relay: serves web/ and forwards JSON between phone ("meter") and browser ("display")
docs/      Research notes
```

## Quick start

1. **Relay + page** (any machine on the same Wi-Fi as the phone):
   ```bash
   cd server && npm install && npm start      # http://<your-ip>:8787
   ```
2. **Phone app**: open `android/` in Android Studio or run `./gradlew :app:assembleDebug`, install
   `app/build/outputs/apk/debug/app-debug.apk`. The CI workflow also uploads the APK as an artifact.
3. Open the page on the monitor you want to test, note the 5-character code, enter the relay URL and code in the app,
   tap **Connect**.
4. Dim the room, put the monitor in its sRGB/standard mode, disable dynamic contrast and Night Light.
5. On the page click **Enter display mode**. Press the phone's main camera flat against the screen centre and tap
   **Full test** (about 2 minutes) or **Quick test**. Tick *uniformity* to be prompted through nine zones.
6. Press **Esc** on the page to see the report. Enter a known white luminance to scale readings to nits.

## Protocol (phone ⇄ relay ⇄ browser)

All messages are JSON with a `type`. The relay forwards between roles in a room keyed by the session code.

| From | Type | Payload |
| --- | --- | --- |
| both | `hello` | `role`, phone adds `device`, browser adds `screen {w,h,dpr,depth}` |
| phone | `camera` | capability summary shown on the page |
| phone | `show` | `id`, `rgb [r,g,b]`, `zone 0..9`, `label`, `index`, `total` |
| browser | `shown` | `id` after two animation frames |
| phone | `measure` | `id, group, zone, rgb, camRgb, xyz, Y, x, y_, u, v, exposureNs, iso, saturated, atNoiseFloor, snr, rowModulation, flickerIndex?, isRaw, frames` |
| phone | `run_start` / `run_done` / `status` / `live` | run lifecycle, live meter readings |
| browser | `start` (`suite: full|quick`) / `stop` | start a run from the page |
| relay | `peer_present` / `peer_left` | presence |

## Tests

```bash
cd server && npm test            # relay room logic
cd web/js && node --test         # analysis: gamma fit, gamut coverage, CCT
```

## Requirements and caveats

- Android 8.0+, best on a phone whose main camera advertises `RAW` and `MANUAL_SENSOR` (most Pixels, Samsung S
  series, OnePlus). The home screen grades the camera. Without those the app falls back to YUV and marks results
  approximate.
- The relay uses plain WebSockets; put it behind TLS if it leaves your LAN.
- Not a replacement for a colorimeter when you need certified accuracy or an ICC profile.
