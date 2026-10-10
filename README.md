<p align="center"><img src="docs/logo.svg" width="112" alt="Camerold logo"></p>

<h1 align="center">Camerold</h1>

<p align="center">
  <b>Turn an old Android phone into a live security camera.</b><br>
  Watch it from another phone or any browser, over Wi‑Fi or 4G.<br>
  Free · no account · no server to run · end‑to‑end encrypted · nothing is recorded.
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84">
  <img alt="Viewer: Android or browser" src="https://img.shields.io/badge/viewer-Android%20%7C%20browser-555">
  <img alt="No server needed" src="https://img.shields.io/badge/server-none%20needed-orange">
</p>

<p align="center">
  <a href="../../releases/latest/download/Camerold.apk"><img alt="Download Camerold.apk" src="https://img.shields.io/badge/Download-Camerold.apk-2e6bf0?style=for-the-badge&logo=android&logoColor=white"></a>
</p>
<p align="center"><a href="../../releases">All releases</a> · <a href="README.vi.md">Tiếng Việt</a></p>

---

## Contents

- [Why Camerold](#why-camerold)
- [Features](#features)
- [Getting started](#getting-started)
- [Watching from a computer](#watching-from-a-computer)
- [Watching over 4G](#watching-over-4g)
- [Connection server](#connection-server)
- [Running an old phone 24/7](#running-an-old-phone-247)
- [Supported phones](#supported-phones)
- [Privacy and security](#privacy-and-security)
- [Troubleshooting](#troubleshooting)
- [How it works](#how-it-works)
- [Building from source](#building-from-source)
- [Support the project](#support-the-project) · [Contributing](#contributing) · [License](#license)

## Why Camerold

Most homes have an old phone in a drawer. It already has a good camera, a microphone, Wi‑Fi, and a battery that
works like a small UPS. Camerold turns it into a live camera you can check from anywhere: a room, a baby, a pet,
the front door, a 3D printer… without buying hardware, creating an account, or trusting a cloud service with
your video.

## Features

**Picture and sound**
- Live video up to **4K** with the phone's hardware encoder (H.264, or VP8 for compatibility), plus **sound**.
- **Lenses**: ultra‑wide (e.g. 0.6×), main, telephoto and the front camera, whatever the phone offers.
- Sensor zoom, **flashlight**, **night mode**, brightness, **focus** (auto, tap to focus, lock, manual, far),
  stabilization, quality, frame rate and picture shape (4:3 shows the whole sensor, 16:9 fits wide screens).
- **Sharper ultra‑wide**: extra sharpening and bitrate where the small sensor needs it.

**Watching**
- From the **Android app or any modern browser**; both use the same viewer.
- **YouTube‑style player**: details below the video when the phone is upright; turn it sideways for full screen with
  auto‑hiding controls and a zoom bar in place of the progress bar. Pinch to zoom; fit or fill the screen.
- **Several cameras** in one list, each with its own name. Tap to watch; share a camera's QR code or remove it.
- **Snapshots** at the resolution being received, saved to the gallery (app) or downloaded (browser).
- **Camera phone health**: battery, charging, temperature, free storage and time running, with a notice when the
  battery runs low without a charger (usually a loose cable).
- Up to **3 viewers** at the same time.

**Connecting**
- **Quick connect with a QR code**: scan it with the app, the web viewer or the phone's own camera app. On a
  computer without a webcam, upload, paste or drop a screenshot of it.
- **Peer‑to‑peer and end‑to‑end encrypted**: video and sound go directly between the devices.
- **No server to run**: devices find each other through free public MQTT brokers, or your own
  (see [Connection server](#connection-server)).
- **Watching over 4G** with an optional relay (TURN) server, which can be typed on the viewer and sent to the
  camera, so you never have to type on the old phone.

**Made for an old phone that never stops**
- **Runs in the background** with the screen off, and takes the camera back when another app releases it.
- **Rests when nobody watches**: the camera turns off and comes back on by itself when someone opens it.
- **Heat protection**: lowers frame rate, then resolution, when the battery gets hot, and recovers once it cools.
  The starting temperature is adjustable, or it can be turned off.
- Uses the hardware video encoder even on Android 8–9 phones with MediaTek, Kirin or Unisoc chips, and only offers
  the qualities the phone can actually encode.
- Readable crash reports (the crashed thread and the last log lines) that you can copy into a bug report.

**And**
- Light and dark themes. **English and Vietnamese**; another language only needs a translation file.

## Getting started

You need two devices: the old phone (the camera) and the device you watch from.

1. **Install the app** on the old phone, and on any Android phone you want to watch from.
   **[Download the latest Camerold.apk](../../releases/latest/download/Camerold.apk)** (or pick a version in
   [Releases](../../releases)) and allow installing it when Android asks.
2. On the **camera phone**:
   1. Open Camerold → **Use as camera**.
   2. Optionally set a **Camera name** (e.g. "Living room").
   3. Tap 🎲 next to the password to create a strong one. Nobody has to remember it.
   4. Tap **Start streaming**. If a yellow notice says Android may stop the camera in the background, tap **Allow**.
3. On the **viewing device**:
   1. Open Camerold → **Watch a camera** → **Scan the QR code on the camera**.
   2. On the camera phone, tap the **QR code** tile and point the viewer at it.
   3. The camera is saved under **Your cameras**. Next time, just tap it.

You can also type the camera code and password instead of scanning.

> **Tip:** keep the camera phone on a charger, turn its screen off with the button next to **Stop streaming**, and
> look at *Camera settings → When the phone gets hot* if it lives in a warm spot.

## Watching from a computer

The viewer is a static web page in [`web/`](web), so it can be hosted anywhere.

- **GitHub Pages** (workflow included): in your fork, open **Settings → Pages → Source: GitHub Actions**.
  The viewer is published at `https://<user>.github.io/<repo>/`.
- **Locally**: `python3 -m http.server -d web 8080`, then open <http://localhost:8080>.

To connect, scan the camera's QR code with a webcam, or **upload, paste (Ctrl+V) or drop a screenshot** of it.
The page needs HTTPS (or `localhost`) because it uses the browser's encryption and camera features.

## Watching over 4G

At home, the two devices usually connect directly. Many mobile networks use carrier‑grade NAT, which blocks direct
connections; then a **relay (TURN) server** is needed. It's only used when a direct connection isn't possible.

1. Get a TURN server: sign up with a TURN service (several have free plans) or run your own, e.g. with the
   open‑source [coturn](https://github.com/coturn/coturn). You need a server address (like `turn.example.com:3478`),
   a user name and a password.
2. Enter it **either**
   - on the camera phone: *Camera settings → Watch over 4G* → **Test server**, **or**
   - from a viewer while connected: *⚙ (camera settings) → Watching over 4G (relay server)* → **Send to camera**.
     The camera saves it, tests it and reports back.
3. Viewers receive it from the camera automatically, inside the encrypted messages.

Good to know:
- An address without a protocol is tried over both UDP and TCP, since many networks block UDP on port 3478.
- In the viewer, *Settings → Troubleshooting → Check network* tells you whether your current network needs a relay.
- While watching, the info panel (ⓘ) shows whether the connection is direct or goes through the relay.
- A mesh VPN such as Tailscale on both devices also works, without a relay.

## Connection server

Before the video starts, the camera and viewer exchange a few small encrypted messages to find each other.
By default this goes through **four free public MQTT brokers** at once (EMQX, HiveMQ, Mosquitto, shiftr.io), so any
one of them can be down. They only see encrypted data and never carry video.

To use your own instead: *Camera settings → Connection server → My own server*. There are presets for
**HiveMQ Cloud** and **EMQX Cloud** (both have free plans with WebSocket + TLS), **CloudAMQP (LavinMQ)**, or any
`wss://` address. **Test server** checks it for real, and the public servers can stay on as a backup.
The QR code carries the server, so viewers don't need to type it.

> CloudAMQP documents MQTT over WebSocket for its dedicated (paid) RabbitMQ plans. The LavinMQ preset may work on the
> free plan; **Test server** will tell you.

## Running an old phone 24/7

- **Keep it cool**: remove the case, keep it out of the sun and off fabric. A slow charger (5 V / 1 A) heats less
  than a fast one, and a small USB fan helps a lot.
- **Turn the screen off** while streaming (button next to *Stop streaming*), or just lock the phone; streaming continues.
- **Allow background running.** Some brands (Xiaomi, Samsung, Oppo, Huawei…) stop background apps aggressively:
  *Camera settings → Background running → Settings for this phone* opens the steps for your brand
  ([dontkillmyapp.com](https://dontkillmyapp.com)).
- **Heat protection** starts at a battery temperature of 44 °C by default (then 47 and 50 °C). If it kicks in while the
  phone only feels warm, raise it in *Camera settings → When the phone gets hot*; the viewer's ⓘ panel shows the
  current temperature.
- Full HD at 24–30 fps is a good balance for long sessions; 4K runs noticeably hotter.

## Supported phones

Any phone with **Android 8.0 or newer** and a camera can be the camera. Any Android phone or modern browser
(Chrome, Edge, Firefox, Safari) can watch. Nothing is tied to one model: lenses, sizes, frame rates, focus, flash and
the highest quality are read from the phone itself, and controls it doesn't have are hidden.

- **4K** needs a phone whose camera *and* video chip support it; otherwise the best supported quality is offered.
- **Ultra‑wide** appears when the phone makes it available to apps (most phones from Android 11, many older ones too;
  cameras hidden by older Samsung, Xiaomi or Oppo phones are found automatically when possible).

Developed and tested on a Pixel 5 (Android 14). Reports from other phones are very welcome; please include the model
and Android version.

## Privacy and security

- **Video and sound** travel peer‑to‑peer over WebRTC, encrypted with DTLS‑SRTP. A relay server, when used, only
  forwards encrypted packets it can't read.
- **Signaling messages** are encrypted with AES‑256‑GCM, with a key derived from the camera password using PBKDF2
  (600 000 iterations). The broker topic is derived from the password too, so a camera can't be found from its code
  alone. Timestamps and message IDs prevent replays.
- **Nothing is recorded or uploaded**, and there is no account and no analytics.
- **Anyone with the camera code and password (or its QR code) can watch and change camera settings.** Use the
  generated password and share the QR code only with people you trust. The QR code contains the password, so it
  closes by itself after 90 seconds.

Details: [docs/PROTOCOL.md](docs/PROTOCOL.md) · [Privacy policy](https://vanloctech.github.io/camerold/privacy.html). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Troubleshooting

| Problem | What to try |
|---|---|
| Works at home, not on 4G | Set up a relay server ([Watching over 4G](#watching-over-4g)); the ⓘ panel should then show the relay. |
| The viewer can't find the camera | Is the camera streaming and online? Same code and password? If the camera uses its own server, add it by scanning its QR code. |
| The camera stops after a while | Allow background running and follow *Settings for this phone*; keep the phone on a charger. |
| Quality drops, heat notice | The phone is warm: improve cooling, or raise the start temperature in *When the phone gets hot*. |
| Black or frozen picture | Another app may be using the camera (it resumes automatically). Otherwise try *Video format → Compatible*. |
| No webcam on the computer | Upload, paste or drop a screenshot of the camera's QR code. |
| The app crashed | Reopen it and tap **Copy details**, then attach them to a [bug report](../../issues/new/choose). |

## How it works

```
 Camera phone (home Wi‑Fi)                               Viewer (phone / browser, Wi‑Fi or 4G)
 ┌────────────────────────┐  1. find each other          ┌─────────────────────────┐
 │ Camera2 → H.264/VP8    │ ◄──── MQTT brokers ────────► │ Android app or web page │
 │ encoder + microphone   │   (AES‑256‑GCM messages)     │                         │
 │                        │                              │                         │
 │                        │ ══ 2. WebRTC, peer‑to‑peer ═►│ video + sound, controls │
 └────────────────────────┘    (DTLS‑SRTP encrypted)     └─────────────────────────┘
                    optional 3. TURN relay when a network blocks direct connections
```

The web viewer is bundled into the Android app at build time, so there is a single viewer implementation.
More: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Building from source

Requirements: JDK 17, Android SDK (platform 35), Node.js 20+ (web tests only).

```bash
./gradlew assembleGithubDebug                    # app/build/outputs/apk/github/debug/app-github-debug.apk
./gradlew assembleGithubRelease                  # GitHub build (APK); signed with the debug key unless a release key is configured
./gradlew bundlePlayRelease                      # Google Play build (bundle, no donation links)
./gradlew testGithubDebugUnitTest                # Kotlin unit tests
NETWORK_TESTS=1 ./gradlew testGithubDebugUnitTest # plus real round trips through the public brokers
node --test tests/*.test.mjs                 # web viewer tests
python3 -m http.server -d web 8080           # web viewer at http://localhost:8080
```

### Releasing

1. Add a `## 3.2.0` section to [CHANGELOG.md](CHANGELOG.md) (it becomes the release notes).
2. Tag and push: `git tag v3.2.0 && git push origin v3.2.0`.

[`release.yml`](.github/workflows/release.yml) then runs the tests, builds the APK with the version taken from the tag
(`v3.2.0` → versionName 3.2.0, versionCode 30200), and publishes a GitHub release with `Camerold.apk` and its SHA‑256.
Tags like `v3.2.0-beta.1` become pre‑releases. The file name never changes, so the download link above always points
to the newest stable version.

**Signing.** Add these repository secrets so every release has the same signature and users can update in place:
`ANDROID_KEYSTORE_BASE64` (the keystore, base64‑encoded), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. Without them the release is signed with a temporary key and says so in its notes.

```bash
keytool -genkeypair -v -keystore camerold-release.jks -alias camerold -keyalg RSA -keysize 4096 -validity 10000
base64 -i camerold-release.jks | pbcopy   # macOS; on Linux: base64 -w0 camerold-release.jks
```

Keep the keystore and its passwords safe (and out of the repository): without them you can't publish updates that
install over existing copies.

**Google Play.** Releases also build the Play bundle and can publish it automatically; setup steps are in
[docs/PUBLISHING.md](docs/PUBLISHING.md). Package name: `com.vanloctech.camerold`.

### Project structure

```
app/src/main/java/vn/camerold/
  ui/          Screens: home, settings, camera, camera settings, viewer (WebView); theme, language, QR code
  camera/      Foreground service, WebRTC engine, encoder selection, heat protection, TURN test
  signaling/   MQTT over WebSocket, brokers (public / own), encrypted signaling, crypto
  data/        Saved settings
app/src/main/java/org/webrtc/   Camera2 capturer (lenses, zoom, focus, torch…), encoders for Android 8–9
app/src/main/res/values*/       App translations (strings.xml)
web/                            Viewer (HTML/CSS/JS, no build step); web/js/i18n.js holds its translations
test-vectors/                   Shared crypto test vectors (Kotlin and JS must agree byte for byte)
docs/                           Architecture, protocol, logo
```

## Support the project

Camerold is free and open source, with no ads and no tracking. If it's useful to you, you can support its development:

<p>
  <a href="https://github.com/sponsors/vanloctech"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-%E2%9D%A4-db61a2?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://buymeacoffee.com/vanloctech"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-ffdd00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

Starring the repository, reporting how it works on your phone and sharing it also help a lot.

## Contributing

Bug reports, phone compatibility reports, translations and pull requests are welcome; see
[CONTRIBUTING.md](CONTRIBUTING.md). A new language only needs a translation file for the app and one block in
`web/js/i18n.js`. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md).

## License

[GNU General Public License v3.0](LICENSE). You may use, study, share and modify Camerold; if you distribute a
modified version, it must also be released under the GPL with its source code.

Third‑party components keep their own GPL‑compatible licenses: WebRTC (BSD, via `io.github.webrtc-sdk`),
OkHttp (Apache‑2.0), ZXing (Apache‑2.0), jsQR (Apache‑2.0, `web/vendor/jsQR.js`),
qrcode-generator (MIT, `web/vendor/qrcode.js`),
Lucide icons (ISC, generated by `tools/icons.py`).
