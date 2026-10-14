# Signaling protocol (v2)

The camera and viewers exchange small JSON messages through public MQTT brokers, then switch to WebRTC.
Brokers are untrusted: anyone can subscribe to every topic, so every message is encrypted and authenticated.

Brokers (the same list in `Signaling.BROKERS` and `web/js/config.js`; a unit test keeps them in sync). Both sides connect
to all of them at once, so any single broker can be down:

| Broker | Address |
|---|---|
| EMQX | `wss://broker.emqx.io:8084/mqtt` |
| HiveMQ | `wss://broker.hivemq.com:8884/mqtt` |
| Eclipse Mosquitto | `wss://test.mosquitto.org:8081/mqtt` |
| shiftr.io | `wss://public.cloud.shiftr.io` (shared login `public` / `public`, written as `wss://public:public@…`) |

`./gradlew testGithubDebugUnitTest` with `NETWORK_TESTS=1` checks a real round trip through each of them.

**Own broker.** Instead of (or in front of) the public list, the camera can use the user's own broker
(`signaling/Brokers.kt`, `web/js/config.js`): presets for HiveMQ Cloud (`wss://HOST:8884/mqtt`), EMQX Cloud
(`wss://HOST:8084/mqtt`), CloudAMQP/LavinMQ (`wss://HOST/ws/mqtt`, login `vhost:user`), or any `wss://` address.
Only `wss://` is accepted (a browser viewer needs WebSocket + TLS). Viewers receive it in the QR code / link below.

## Keys and topics

```
master = PBKDF2-HMAC-SHA256(password (UTF-8), salt = "camerold-v2:" + code, 600 000 iterations, 32 bytes)
encKey = HMAC-SHA256(master, "camerold enc")                      → AES-256-GCM key
topic  = "camerold/v2/" + hex(HMAC-SHA256(master, "camerold topic"))[0..32]
```

`code` is the camera code, normalized (trimmed, lower‑case, spaces removed).

- The **topic depends on the password too**. An eavesdropper can't find a camera's topic from its code
  alone, and an offline guess has to go through 600k PBKDF2 iterations per candidate password.
- Camera listens on `<topic>/c`. Each viewer picks a random id and listens on `<topic>/v/<viewerId>`.
- Camera publishes its battery state on `<topic>/s` as a **retained** message (see *Battery status* below).

### Two codes: full control and view only

A camera has a second, random password: the **view-only code**. Keys and topic are derived from it the same way
(same camera code), so it is simply a second topic with its own key. The camera listens on both `<topic>/c` and
`<viewTopic>/c`, and answers each viewer on the topic it came from, with that topic's key.

- Messages that arrive on the view-only topic can watch (`hello`, `answer`, `ice`, `bye`) but a `cfg` or `strobe` is ignored,
  and over the data channel `del` is ignored. This is enforced by the camera, not by the viewer.
- Connections are kept apart by link (`o:<viewerId>` / `v:<viewerId>`), so a view-only viewer can't reach or replace
  someone else's connection by reusing their viewer id.
- When the camera is full, view-only viewers make room first, and never push out a full-control viewer.
- Every `offer` / `info` carries `role`: `"owner"` or `"view"`. Owners also get `viewPass` (to share it); view-only
  viewers don't get `turn`.
- The battery `status` is published on both topics.
- Changing the view-only code (camera app, while not streaming) shuts out everyone who had the old one. The QR code
  for it is the same `camerold://join` link with the view-only password in `k`.

## Packet format

```
packet = IV (12 random bytes) || AES-256-GCM(ciphertext || 16-byte tag)
AAD    = the topic the packet is published to (UTF-8)
```

Binding the AAD to the topic means a packet can't be replayed onto another topic.

## Message envelope

Every JSON message contains:

| Field | Meaning |
|---|---|
| `t` | type (see below) |
| `id` | random id (≥ 8 chars), used for de‑duplication across brokers and replay protection |
| `ts` | sender time in ms; receivers drop messages more than 15 minutes off |
| `v` | viewer id (viewer → camera) |
| `s` | session number; a viewer increments it whenever it reconnects |

Receivers remember seen ids for 30 minutes and drop duplicates. All brokers carry the same message, so the
first copy wins.

## Message types

| `t` | Direction | Payload |
|---|---|---|
| `hello` | viewer → camera | `ice`: optional extra ICE servers from the viewer |
| `offer` | camera → viewer | `sdp`, `ice` (camera's TURN servers), plus all `info` fields |
| `answer` | viewer → camera | `sdp` |
| `ice` | both | `c`: `{candidate, sdpMid, sdpMLineIndex}` |
| `cfg` | viewer → camera | any of `res`, `fps`, `aspect` (`"4:3"` / `"16:9"`), `lens`, `zoom`, `torch`, `night`, `ev`, `focus`, `fx`, `fy`, `fdist`, `stab`, `heatGuard` (bool), `heatStart` (°C, 38–52), `rec` (continuous recording, any of `{on, q (720/1080), fps (10/15), days (3, 7, 14, 30, 0 = until storage runs short), h264 (bool: H.264 instead of HEVC), viewOnly (bool: view-only viewers may watch recordings), rot}`), `turn` (`{urls, user, pass}` relay server typed on the viewer; empty `urls` clears it; the camera tests it), `tl` (timelapse: `{op: "start", interval (s: 1, 2, 5, 10, 30, 60, 300), q (720/1080), minutes (auto-stop, 0 = none), stamp, rot, gallery}` or `{op: "stop"}`) |
| `strobe` | viewer → camera | `on` (bool). Owner holding the light button: the camera flashes the light (250 ms on, 250 ms off). Repeated about every second while held; the camera stops by itself 2.5 s after the last one, and on `on: false`. Ignored on the view-only topic |
| `info` | camera → viewer | current settings, `name` (camera name, may be empty), `lenses`, `caps` (capabilities), `audio`, `camBusy`, `model`, `heat` (0–3: phone is hot, quality temporarily lowered), `heatGuard` / `heatStart` (heat protection settings), `turn` (`{urls, user, ok}`: relay server in use, never the password; `ok` = the camera's own test result or null), `viewers` (people watching right now, sent again whenever it changes), `health` (`bat` %, `chg` charging, `temp` °C, `free` bytes, `up` seconds streaming; resent every 30 s while someone watches), `tl` (timelapse: `on`, `frames`, `busy` (making a video), `ver`, `free`, `event` (`storage` / `encode`), and while on: `interval`, `q`, `stopAt`, `stamp`, `startedAt`, `gallery`; also sent alone, at most every 5 s while it runs), `rec` (recording: `on`, `active` (writing now), `since`, `q`, `fps`, `days`, `h264`, `viewOnly`, `hevc` (the phone can encode HEVC), `codec` (`hevc`/`h264`), `event` (`storage`: paused, storage full / `encode`: no encoder), `ver`, `used` / `count` / `first` (recordings kept), `rate` (bytes an hour, measured), `free`, `total`, `minFree`; also sent alone when it changes) |
| `bye` | viewer → camera | viewer is leaving |
| `status` | camera → `<topic>/s` (retained) | `on` (false once the camera was stopped), `bat` %, `chg` charging, `name` (if set) |

`fx`/`fy` are normalized (0..1) coordinates on the frame as the viewer displays it (upright). The camera maps
them back to sensor coordinates (`ProCapturer.meteringRegion`).

## Battery status (background alerts)

The camera publishes a `status` message on `<topic>/s` with the MQTT retain flag, so the broker keeps the latest one.
It is sent when the battery level or charging state changes, at least every 15 minutes, and with `on: false` when
streaming is stopped. A camera that reconnects to a broker publishes it again.

The Android app on viewer phones checks the cameras saved in its viewer every ~15 minutes with a system job, even when
the app is closed: it connects, subscribes to `<topic>/s`, takes the newest message from any broker, and notifies once
when `bat` ≤ 20 % and not charging (again only after the battery went above 25 % or was charged). A status older than
45 minutes is ignored. It keeps only the derived key and topic, not the password. Retained messages may be lost if a
public broker restarts; the next refresh puts it back.

## Timelapse videos (data channel)

The camera adds a reliable, ordered data channel labelled `tl` to every offer. It carries JSON text messages and
binary file chunks, inside the same DTLS‑encrypted connection as the video.

| Viewer → camera | Camera → viewer |
|---|---|
| `{op: "list"}` | `{op: "list", items: [{id, name, created, frames, interval, dur, w, h, size, file}]}`, then `{op: "thumb", id, data}` (base64 JPEG) per video. Also sent unasked when the list changes. |
| `{op: "get", id}` | `{op: "file", id, size, name}`, binary chunks of 16 KB, then `{op: "end", id}` (`cancelled: true` if cancelled) or `{op: "err", id}` |
| `{op: "cancel"}` | stops the current download |
| `{op: "del", id}` | the video is deleted; a new `list` follows (full-control viewers only) |

`id` is the start time, `yyyyMMdd-HHmmss`; the camera only accepts ids of that form. While a timelapse runs the
camera keeps one frame every `interval` seconds as a JPEG (crash‑safe), and makes a 30 fps H.264 MP4 when it stops.

## Recordings (data channel)

With continuous recording on, the camera encodes its own copy of the picture (HEVC in hardware when the phone has it,
else H.264, variable bitrate, a key frame every 4 s) in one-minute pieces, joined without re-encoding into one video
per half hour on the clock (`files/rec/<yyyy-MM-dd>/<HHmmss>.mp4`, with a thumbnail and details). A crash loses at
most the minute being written; leftover pieces are joined at the next start. The oldest recordings are deleted after
`days`, and whenever free storage falls under 1 GB (500 MB on phones under 16 GB); kept ones never are.

They travel over a second data channel, `rec`, like the `tl` one (JSON messages, then 16 KB binary chunks):

| Viewer → camera | Camera → viewer |
|---|---|
| `{op: "days"}` | `{op: "days", items: [{day, n, size, dur}]}`, newest first |
| `{op: "list", day, have}` | `{op: "list", day, items: [{id, start, end, dur, size, w, h, codec, fps, keep}]}` (the one being written last, with `live: true`), then `{op: "thumb", id, data}` for each id not in `have` |
| `{op: "get", id}` | the whole recording: `file` / chunks / `end` or `err`, as for timelapses. For the one being written: what's closed so far |
| `{op: "clip", id, from, to}` | `{op: "prep", id}` while the camera cuts seconds `from`–`to` (at most an hour, from the key frame before `from`, no re-encoding), then `file` / chunks / `end` |
| `{op: "cancel"}` | stops the current transfer |
| `{op: "keep", id, on}` | kept recordings are never deleted automatically (full-control viewers only) |
| `{op: "del", id}` | deleted (full-control viewers only) |
| | `{op: "changed", ver}` unasked when the list changes |

View-only viewers get `{op: "denied"}` for everything unless the owner set `viewOnly`.

## Media

WebRTC with `bundlePolicy: max-bundle`. The camera adds send‑only video (and audio when enabled). Media is
DTLS‑SRTP encrypted end to end. The DTLS fingerprints travel inside the encrypted offer/answer, so a party
without the password can't insert itself in the middle.

## QR code / deep link

```
camerold://join?r=<code>&k=<password>[&b=<broker>][&p=0]     (percent-encoded)
https://<viewer>/#r=<code>&k=<password>[&b=<broker>][&p=0]&auto=1
```

`b` is the camera's own broker as one URL with its login (`wss://user:pass@host:port/path`); `p=0` means the camera
doesn't also use the public brokers. Without `b`, everyone uses the public brokers. The QR code uses error
correction H, or Q when the link is longer than 150 characters. The web viewer reads the QR code with the camera,
or from an image (upload, paste, drag and drop) for computers without a webcam.

The web viewer removes the fragment from the address bar after reading it.
