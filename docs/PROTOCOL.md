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

`./gradlew testDebugUnitTest` with `NETWORK_TESTS=1` checks a real round trip through each of them.

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
| `cfg` | viewer → camera | any of `res`, `fps`, `aspect` (`"4:3"` / `"16:9"`), `lens`, `zoom`, `torch`, `night`, `ev`, `focus`, `fx`, `fy`, `fdist`, `stab`, `heatGuard` (bool), `heatStart` (°C, 38–52), `turn` (`{urls, user, pass}` relay server typed on the viewer; empty `urls` clears it; the camera tests it) |
| `info` | camera → viewer | current settings, `name` (camera name, may be empty), `lenses`, `caps` (capabilities), `audio`, `camBusy`, `model`, `heat` (0–3: phone is hot, quality temporarily lowered), `heatGuard` / `heatStart` (heat protection settings), `turn` (`{urls, user, ok}`: relay server in use, never the password; `ok` = the camera's own test result or null), `health` (`bat` %, `chg` charging, `temp` °C, `free` bytes, `up` seconds streaming; resent every 30 s while someone watches) |
| `bye` | viewer → camera | viewer is leaving |

`fx`/`fy` are normalized (0..1) coordinates on the frame as the viewer displays it (upright). The camera maps
them back to sensor coordinates (`ProCapturer.meteringRegion`).

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
