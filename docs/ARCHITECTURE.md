# Architecture

Camerold has two parts that talk to each other only through an encrypted signaling channel and WebRTC:

- **Camera** – the Android app in camera mode (`app/`).
- **Viewer** – a static web page (`web/`), used both in browsers and inside the Android app (WebView).

## Android app

The app is installed as `com.vanloctech.camerold` (applicationId); the Kotlin code lives in the `vn.camerold` packages (namespace).

| Package | Responsibility |
|---|---|
| `vn.camerold.ui` | Activities: `MainActivity` (home: pick a role), `SettingsActivity` (theme, language, about), `CameraActivity` (preview, status, how viewers connect, start/stop), `CameraSettingsActivity` (picture, sound, 4G relay, background running, advanced), `ViewerActivity` (WebView hosting `web/`). `BaseActivity` applies theme (`ThemePref`) and language (`LangPref`); `Rows` builds the grouped settings lists; `QrCode` draws the QR code. |
| `vn.camerold.camera` | `CameraService` (foreground service, type `camera|microphone`), `CameraEngine` (WebRTC peer connections, one per viewer, max 3), `SafeEncoderFactory` (hardware encoder with software fallback; adds `org.webrtc.LegacyHwEncoderFactory` on Android 8–9 for non‑Qualcomm/Exynos chips), `Rtc` (WebRTC init, TURN test, highest quality the hardware encoder supports), `ThermalGuard` (heat level from thermal status and battery temperature; the engine lowers quality while hot and turns the camera off when nobody watches). |
| `vn.camerold.signaling` | `MqttWs` (minimal MQTT 3.1.1 over WebSocket), `Signaling` (multi‑broker, encryption, replay protection), `Brokers` (public list or the user's own broker, provider presets, server test), `SigCrypto` (key derivation and AES‑GCM). |
| `vn.camerold.data` | `Prefs` / `CamConfig` (persisted camera settings). |
| `org.webrtc.ProCapturer` | Camera2 capturer replacing WebRTC's `Camera2Capturer` to control lens, zoom ratio, torch, exposure, focus and stabilization. It lives in the `org.webrtc` package to reuse package‑private helpers for frame rotation. |

### Threads

- `CameraEngine` runs all its logic on one single‑threaded executor; WebRTC and MQTT callbacks post onto it.
- `ProCapturer` runs on the `SurfaceTextureHelper` camera thread.
- UI updates go through `runOnUiThread`.

### Lifecycle

1. `CameraActivity` saves settings and starts `CameraService`. The service must be started while the app
   is in the foreground (Android 14 rule for camera/microphone foreground services).
2. `CameraService` creates a `CameraEngine`. The engine opens the camera, derives keys and connects to the MQTT brokers.
3. A viewer sends `hello`. The engine creates a `PeerConnection`, sends an `offer`, receives an `answer`, and ICE candidates are exchanged.
4. Viewer commands (`cfg`) change capture settings live. Camera state is broadcast back as `info`.

## Web viewer

Plain scripts (no build step, no framework), loaded in order by `web/index.html`:

| File | Responsibility |
|---|---|
| `i18n.js` | Translations and `t()` / `applyI18n()` |
| `config.js` | Brokers, STUN servers, small helpers |
| `crypto.js` | Key derivation, AES‑GCM (must match `SigCrypto.kt`) |
| `mqtt.js`, `signaling.js` | MQTT client and encrypted signaling |
| `cams.js` | Saved cameras list (several cameras per viewer, each with its own server) |
| `snapshot.js` | Save the current picture (download, or the app's Pictures/Camerold) |
| `theme.js`, `settings.js` | Light/dark theme; the Settings page (theme and language when standalone, relay server, network check, video format) |
| `viewer.js` | Connection flow, camera info, sound, YouTube‑style controls, settings panel |
| `stats.js`, `netcheck.js` | Connection info, network diagnostics |
| `gestures.js` | Pinch zoom, pan, rotate, tap‑to‑focus coordinate mapping |
| `qr.js`, `main.js` | QR scanner (BarcodeDetector, jsQR fallback), bindings and startup |
| `share.js` | Show/hide password, QR of a saved camera for another viewer (`vendor/qrcode.js`) |

Inside the app the page is served from `https://appassets.androidplatform.net/assets/` (a secure context,
required by WebCrypto). The app passes `?app=1&theme=…&lang=…`.

## Keeping both sides compatible

Anything that crosses the wire is specified in [PROTOCOL.md](PROTOCOL.md). Crypto changes must update
`test-vectors/crypto-v2.json` (via `tools/gen-test-vectors.mjs`); both `SigCryptoTest.kt` and
`tests/web-crypto.test.mjs` run against it in CI.
