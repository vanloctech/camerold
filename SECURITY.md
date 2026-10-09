# Security

## Design summary

- **Signaling** goes through public MQTT brokers, which are treated as untrusted. Messages are encrypted with
  AES‑256‑GCM using a key derived from the camera code and password (PBKDF2‑SHA256, 600k iterations).
  The broker topic is also derived from the password, messages are bound to their topic, and timestamps plus
  random ids block replays. See [docs/PROTOCOL.md](docs/PROTOCOL.md).
- **Video and sound** go peer‑to‑peer over WebRTC (DTLS‑SRTP). A relay server (TURN), if used, only forwards
  encrypted packets.
- **Nothing is recorded** and there is no Camerold server.

## What users should know

- The **password is the security**. Use the generated 20‑character password (about 115 bits); short
  passwords can be guessed offline by someone collecting traffic from public brokers.
- **Anyone with the QR code can watch.** Don't share screenshots of it.
- Saved passwords are stored on the device (app storage, or the browser's local storage when *Remember* is on).

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Use GitHub's
*Security → Report a vulnerability* (private advisory) on this repository, with steps to reproduce.
We aim to reply within a week.
