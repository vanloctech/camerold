# Publishing on Google Play

Releases are built by [`release.yml`](../.github/workflows/release.yml) when a version tag is pushed. Every run builds
the APK for GitHub Releases (`github` flavor) **and** the Play bundle (`.aab`, `play` flavor). The two are the same app;
the Play build only leaves out the donation links, which Google Play's payments policy doesn't allow. Once the steps below are done, it also uploads the bundle
to Google Play by itself.

- Package name: **`com.vanloctech.camerold`**
- Privacy policy: <https://vanloctech.github.io/camerold/privacy.html> (source: [`web/privacy.html`](../web/privacy.html))
- Target API: 36 (Android 16), as Google Play requires for new apps and updates from 31 August 2026.

## 1. Create the app in Play Console (once)

1. **Create app**: name *Camerold*, default language, *App*, *Free*. Accept the declarations.
2. **App signing** (Setup → App signing). Choose one:
   - **Recommended: use the existing release key as the app signing key**, so the APK on GitHub Releases and the app from
     Play have the same signature and users can switch between them. Choose *Use a different app signing key* →
     *Export and upload a key from Java keystore*, download `pepk.jar` and the encryption key Play shows, then run:
     ```bash
     java -jar pepk.jar --keystore=camerold-release.jks --alias=camerold \
       --output=camerold-signing-key.zip --include-cert --rsa-aes-encryption \
       --encryption-key-path=encryption_public_key.pem
     ```
     and upload `camerold-signing-key.zip`. The same key is then also the upload key.
   - Or let Google generate the app signing key. Play builds will then have a different signature from GitHub builds,
     so a user who installed one must uninstall it before installing the other.
3. **First upload by hand.** Google requires the first bundle to be uploaded in Play Console. Push a tag (or use the
   latest run): in GitHub → *Actions* → *Release* → the run → *Artifacts* → download `camerold-<version>-play-bundle`,
   unzip it, and upload the `.aab` to a **closed testing** track (Testing → Closed testing → Create release).

## 2. App content (Policy → App content)

| Item | What to enter |
|---|---|
| Privacy policy | `https://vanloctech.github.io/camerold/privacy.html` |
| Ads | No ads |
| App access | All functionality available without special access (no login). Explain that it needs two devices: one streams, one watches. |
| Content rating | Questionnaire: utility app, no user‑generated content shared publicly, no violence etc. |
| Target audience | 18+ (security camera); not designed for children |
| Data safety | The developer collects no data: no account, no analytics. Camera/microphone data goes peer‑to‑peer, end‑to‑end encrypted, directly between the user's own devices, never to the developer. Answer according to Google's current definitions. |
| Foreground service types | **camera** and **microphone**: live security camera that keeps streaming while the screen is off or another app is open; the user starts and stops it, with a persistent notification. If asked, record a short video showing *Start streaming*, the notification, and watching from a second device. |
| Government app / financial / health | No |

Also review: **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS** is a restricted permission on Play. Camerold asks for it because a
camera that Android stops in the background stops protecting the home. If review rejects it, the app can open the
battery settings list instead (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`).

## 3. Testing before production (new personal developer accounts)

Personal developer accounts created after 13 November 2023 must run a **closed test with at least 12 testers who stay
opted in for 14 days in a row** before they can apply for production access (Dashboard → *Apply for production*).
Organization accounts are exempt. Invite more than 12 testers so a few dropping out doesn't restart the 14 days.

## 4. Automatic uploads from GitHub Actions

1. **Google Cloud**: create (or pick) a project → enable the **Google Play Android Developer API** → *IAM & Admin →
   Service accounts* → create a service account → *Keys* → *Add key* → JSON. Keep the file private.
2. **Play Console** → *Users and permissions* → *Invite new users* → the service account's e‑mail → give it access to
   Camerold with **Release apps to testing tracks** (and **Release to production** when you want that).
3. **GitHub** → repository *Settings → Secrets and variables → Actions*:
   - secret `PLAY_SERVICE_ACCOUNT_JSON`: the whole JSON file content;
   - variable `PLAY_TRACK` (optional): `internal` (default), `alpha` (closed testing), `beta` (open testing) or `production`;
   - variable `PLAY_STATUS` (optional): `draft` (default, required while the app has never been published) or
     `completed` to roll out right away.
4. Push a tag. Stable tags (`v3.3.0`) go to `PLAY_TRACK`; pre‑release tags (`v3.3.0-beta.1`) always go to `internal`.
   The release notes ("What's new") come from the version's section of [CHANGELOG.md](../CHANGELOG.md).

With `draft`, the release appears in Play Console ready to review and roll out with one click. Version codes come from the
tag (`v3.3.0` → 30300), so every tag must be a higher version than the last one uploaded.
