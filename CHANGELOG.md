# Changelog

## 3.5.0
- **Continuous recording**: the camera phone can record all the time, like a security camera's recorder, also when
  nobody watches. HEVC (or H.264) at HD or Full HD, 10 or 15 fps, one video per half hour, written in one‑minute pieces
  so a crash or power cut loses at most a minute. Old recordings are deleted by themselves (after 3–30 days, or when
  storage runs short); "keep" protects one. Viewers browse them by day on a 24‑hour timeline, download a whole video
  or just a cut of it (no re‑encoding), keep and delete them, end‑to‑end encrypted. Viewers on the view‑only link see
  them only if the owner allows it.
- **Recordings on the camera phone**: Camera settings → Recordings on this phone: storage used, recordings by day,
  watch them, save one to the gallery, delete one, all of them, or those in a date and time span.
- **View‑only link**: a second QR code / password for people who may watch but not change anything; it can be reset
  to lock out everyone who had the old one.
- **App lock**: an optional 6‑digit PIN to open Camerold, unlockable with the phone's fingerprint or face, locking
  as soon as you leave the app or after 1 or 5 minutes. The camera keeps streaming and recording while it's locked.
- **Picture in picture** while watching live, in the app (also when leaving it) and in browsers that have it.
- **Player controls by priority**: full screen, picture in picture, rotate, front/back camera and light sit on the
  video; on narrow screens the least important ones move out of the way (they stay in the chips and settings).
  Holding the light button flashes the camera phone's light until you let go.
- **Tablets and foldables**: the watch screen puts the video and its settings side by side; the camera screen shows
  the preview next to its controls; home and settings use a centered column instead of a stretched phone layout.
- **Your cameras** (web): a grid of picture cards with a colour per camera, the latest one featured on phones, and the
  ways to add a camera as tiles.
- **QR codes**: the app's logo in the middle; save the code as a picture or share it straight to a chat app; on the
  web, paste a copied QR picture or camerold:// link instead of downloading and uploading it.
- The README links the hosted web viewer (vanloctech.github.io/camerold) for watching without hosting your own.
- Fixed: a recording was split into several short pieces whenever a viewer connected (the camera changes its format);
  the viewer's recording list didn't update when recording stopped before the half hour.
- Hardening: view‑only viewers can't delete timelapse videos; GitHub Actions pinned to exact versions, with signing
  secrets only given to the steps that need them; stricter content security policy on the privacy page.

## 3.4.0
- **Timelapse**: start and stop it from a viewer; the camera phone takes a photo every 2 s to 5 min and makes a
  fast‑forward video when it stops. Options: auto‑stop after 1–24 h, HD or Full HD, date and time on the video, also
  save to the camera's gallery. Viewers list, download and delete the videos straight from the camera phone, end‑to‑end
  encrypted. Light on heat: while only a timelapse needs the camera it runs at 10 fps, nothing is encoded until the
  end, and it stops by itself when the camera phone is nearly out of storage. An unfinished timelapse (crash, phone
  restarted) is turned into a video the next time the camera starts.
- **Camera battery alerts**: the app on a viewer phone notifies you when a saved camera is at 20 % or less and not
  charging, even when the app is closed. No push server: the camera leaves an encrypted battery status on the MQTT
  brokers, and the viewer phone checks it about every 15–30 minutes (Settings → Notifications to turn it off).
- **Cleaner watch screen**: smaller player buttons and no red dot; below the video, three tabs (Picture, Timelapse,
  Device) instead of one long list. Timelapse settings open in their own sheet, with estimates (photos, video length
  and size, storage needed) and a bar showing the camera phone's storage.
- **Your cameras**: each saved camera shows the last picture seen from it; adding a camera is one card of options.
- Viewers see how many people are watching (badge on the video, status line, Device tab).
- Tapping the lens already in use (e.g. 1× after zooming to 3×) goes back to that lens's own zoom.
- Group titles and notes line up with the cards' edge, in the app and the web viewer.
- New home screen: colored cards with illustrations for the two roles, a "LIVE" badge while this phone is streaming,
  and colored steps in "How it works".
- In the app's viewer, the sign‑in page has a back arrow and its title in the top bar, like the other screens.
- Fixed an empty band under the status bar in the app's viewer on Android 15+ with recent WebView versions.

## 3.3.0
- **New look**, inspired by SmartThings: dashboard tiles for the main choices and for saved cameras, grouped settings
  cards, an indigo‑blue primary color, and one row style everywhere (same type sizes, same text edge, chevrons on
  tappable rows), so text lines up across every screen, in the app and the web viewer. Light and dark themes.
- Icons from Lucide on Android and the web (`tools/icons.py` generates both).
- Camera screen: quick tiles for the QR code and sound; camera settings summary in its own card; the password field
  no longer switches to a monospace font.
- In the app's viewer, the status bar matches the page (black only under the video).
- **New package name `com.vanloctech.camerold`** (was `vn.camerold`), ready for Google Play. Android treats it as a new
  app: uninstall the old one; saved settings and cameras don't carry over.
- Targets Android 16 (API 36), as Google Play requires: content stays clear of the status and navigation bars on
  Android 15+ (edge‑to‑edge), and Back works with predictive back gestures (it still leaves full screen first).
- Privacy policy page (Settings → About → Privacy policy), published with the web viewer.
- Releases also build the Google Play bundle and can upload it to Play automatically (see docs/PUBLISHING.md).
- The camera re‑checks its permission before opening, in case it was revoked while streaming.
- Support the project: GitHub Sponsors and Buy Me a Coffee links in Settings (GitHub build), the web viewer's
  settings and the README. The Google Play build leaves them out, as Play's payments policy requires.

## 3.2.0
- Viewer remembers the picture rotation (↻) for each saved camera.
- Removed the link to a specific TURN provider and its example addresses; the 4G help is now provider‑neutral.

## 3.1.0
- Picture frame option: 4:3 (widest, whole sensor) or 16:9 (fits landscape screens), also switchable from the viewer.
- Viewer: show/hide the password, and *Let another phone watch* shows a saved camera's QR code.
- Viewer: Fit / Fill screen option in settings (*Show on this device*).
- Viewer app rotates like YouTube: the full-screen button turns the screen sideways, turning the phone sideways
  enters full screen and turning it upright leaves it (follows the system Auto-rotate switch).
- New waiting screen in the viewer: connection steps, clearer problem cards, and a blurred last frame with a
  "Reconnecting…" pill instead of a black screen.
- Camera preview: new placeholder that pulses while the camera opens and fades out on the first frame.
- Sharper 0.6× ultra-wide: stronger sharpening, lighter noise reduction (outside night mode) and about 35% more
  bitrate for its more detailed picture.
- Heat protection for phones left on a charger: when the phone gets warm the camera lowers frame rate, then
  resolution and bitrate, and goes back up once it has cooled for a few minutes. Viewers see a small notice.
- The camera turns itself off when nobody is watching and the camera screen is closed, and turns back on
  when a viewer connects (much less heat and battery wear when running 24/7).
- Crash reports now decode native crashes: the thread that crashed, its backtrace and the app's last log lines
  (before, only unrelated idle threads were shown).
- Works better beyond the Pixel:
  - Android 8–9 phones with MediaTek, Kirin, Unisoc… chips now use their hardware video encoder (WebRTC only
    trusted Qualcomm/Exynos there, so they fell back to slow, hot software encoding).
  - Quality choices are limited to what the phone's video chip can encode (many older phones top out at Full HD);
    unsupported options are greyed out in the viewer.
  - Ultra-wide / telephoto cameras that older Samsung, Xiaomi, Oppo… phones hide from the camera list are found
    by probing; one that refuses to open is dropped and the app goes back to the main lens.
  - Phones from brands that kill background apps (Xiaomi, Samsung, Oppo, Huawei…) get a link to their
    background-running steps.
- New QR code look (rounded modules, blue corner markers, camera badge) and a QR-only popup without extra text,
  on both the camera app and the viewer.
- Reorganized interface:
  - Home only asks what the phone is for; a Settings screen (gear) holds appearance, **language** (phone default,
    English, Tiếng Việt; also in Android 13+ per-app language settings) and about/licenses.
  - Camera screen: top bar with back and camera settings, status shown on the preview, how viewers connect,
    start/stop, and a warning with a fix button only when background running isn't allowed.
  - Camera settings moved to their own screen (picture, sound, watching over 4G, background running, advanced).
  - Web viewer: the connect screen only connects; theme, language, relay server, network check and video format
    are on a Settings page (theme and language follow the app when opened inside it).
  - New neutral palette shared by the app and the web viewer.
  - Language lists are built from the available translations, so adding a language needs no code change.
- New logo: a phone whose screen is a camera lens. Adaptive launcher icon with an Android 13+
  themed (monochrome) version, matching notification icon, web favicon/touch icon; master file `docs/logo.svg`.
- Fourth signaling broker: shiftr.io's public broker (independent from the other three). The MQTT clients can now log
  in (`wss://user:pass@host`); a test keeps the app and web broker lists identical.
- Own connection server: besides the public brokers, the camera can use your own MQTT broker (presets for HiveMQ
  Cloud, EMQX Cloud, CloudAMQP/LavinMQ, or any `wss://` address), with a real "Test server" check and optional
  public backup. The QR code carries it, so viewers don't type it; the web viewer has the same settings.
- Web viewer: connect from a QR code image (upload, paste or drag and drop) for computers without a webcam; the
  app's viewer can pick an image too. QR reading falls back to jsQR when the browser's detector finds nothing.
- Camera name (e.g. "Living room"): set on the camera screen, changeable while streaming; shown to viewers, in the
  camera's notification and title, and remembered by the viewer for that camera.
- Viewer keeps a list of cameras: tap to watch, ⋮ to show a camera's QR code or remove it (with undo); each camera
  remembers its own connection server; the old single saved camera is moved into the list.
- Camera phone health on the viewer: battery, charging, temperature, free storage and streaming time in the info
  panel, plus a notice when the battery is low and not charging (often a loose cable).
- Snapshots from the viewer at the received resolution: downloaded in a browser, saved to Pictures/Camerold in the app.
- Viewer control bar fits phones held sideways: back lenses only (front camera is a chip and a switch in settings),
  a red dot instead of "LIVE", snapshot button in the top bar; the right-hand buttons never get pushed off screen.
- Heat protection settings (camera settings and the viewer's camera panel): turn it off, or choose the battery
  temperature where it starts (default 44 °C, then +3 / +6 °C); changes apply right away, even while streaming.
- Heat protection is less jumpy: based on battery temperature (44 / 47 / 50 °C) plus Android's SEVERE/CRITICAL alarms;
  Android's "moderate" status and thermal forecast no longer trigger it. Viewer warnings show for 5 seconds, then stay
  as a small icon next to the camera name (tap to see again); they pop up again only if things get worse.
- Relay server from the viewer: type or copy it on the viewer (camera settings panel → Watching over 4G) and send it to
  the camera phone, which saves it, tests it and reports back. The password is never sent back to viewers.
- TURN: a plain `turn:host:port` is now tried over both UDP and TCP (UDP 3478 is often blocked on Wi‑Fi and 4G);
  stray spaces around the server password are ignored.
- QR scanner opens on a dark screen with "Opening the camera…" and fades the camera in (no more grey video placeholder
  in the app); the same placeholder is gone from the player. More space between "Your cameras" and the list.
- Releases: pushing a tag like `v3.2.0` builds, tests and publishes `Camerold.apk` (+ SHA‑256) with notes from this
  file; the app version comes from the tag. README links to the latest APK.
- License changed to GNU GPL v3.0.

## 3.0.0
- Project restructured for open source: feature packages, translations (English, Vietnamese), modular web viewer,
  shared crypto test vectors, CI, docs.
- Cleaner UI and simpler wording throughout.
- Fixed: stopping the stream could leave the camera and WebRTC running in the background, which crashed
  the app on the next start (and could abort in native code).

## 2.x
- Sound, YouTube‑style viewer, light/dark theme, tap‑to‑focus and focus modes, lens switching (ultra‑wide),
  QR quick connect, signaling protocol v2 (password‑derived topics, replay protection), relay server support,
  background running improvements, upright video from the accelerometer.

## 1.x
- First version: WebRTC peer‑to‑peer streaming with encrypted signaling over public MQTT brokers.
