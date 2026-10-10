# Contributing to Camerold

Thanks for helping! Issues and pull requests in **English or Vietnamese** are both welcome.

## Ways to help

- **Report a bug** – include the phone model, Android version, app version (shown in crash reports) and,
  if possible, the text from *Copy details* in the crash dialog.
- **Translate** – see [Translations](#translations).
- **Test on your phone** – camera support differs a lot between manufacturers (lenses, focus, 60 fps, 4K).
  Reports like "works on X, ultra‑wide missing on Y" are very useful.
- **Code** – pick an issue, or open one first for larger changes so we can agree on the approach.

## Development setup

- JDK 17, Android SDK platform 35, Node.js 20+.
- `./gradlew assembleGithubDebug` builds the app; `./gradlew testGithubDebugUnitTest` runs Kotlin tests.
- Two flavors share all the code: `github` (APKs on GitHub Releases) and `play` (Google Play bundle, without donation links).
- `node --test tests/*.test.mjs` runs the web viewer tests.
- `python3 -m http.server -d web 8080` serves the viewer at http://localhost:8080 for quick UI work
  (WebCrypto needs a secure context: `localhost` or `https`).

To test end to end you need one Android phone as the camera; the viewer can be a browser on your computer.

## Guidelines

- Keep the app **free and serverless**: no accounts, no paid services, no analytics.
- **No user‑visible string in code.** Android: `app/src/main/res/values/strings.xml` (+ `values-vi`).
  Web: `web/js/i18n.js`, then reference it with `data-i18n="key"` or `t('key')`.
- Write for everyday users: short sentences, no jargon (say "relay server", not "TURN"; "camera code", not "room id").
- Anything sent between camera and viewer is part of the protocol: update [docs/PROTOCOL.md](docs/PROTOCOL.md).
  Keep it backward compatible where possible.
- **Crypto changes** must keep Kotlin and JavaScript identical: regenerate `test-vectors/` with
  `node tools/gen-test-vectors.mjs` only when the protocol version changes, and make both test suites pass.
- Match the surrounding code style (`.editorconfig`); comments explain *why*, in English.
- UI conventions: a top app bar (back, title, at most one action); dashboard tiles for the main choices; settings
  as grouped cards under small bold section labels. **One row style everywhere**: 24dp icon, title 16sp, value 14sp,
  text starting at the same place (`@dimen/text_start`), chevron on tappable rows. Spacing and sizes live in
  `res/values/dimens.xml` and the `T.*` text styles; the web viewer uses the same palette and metrics
  (`web/css/app.css` variables match `res/values*/colors.xml`). App‑wide settings (theme, language) live on the
  Settings screen, never on task screens.
- Icons are [Lucide](https://lucide.dev): add the name to `tools/icons.py` and regenerate (instructions at the top
  of the file) so the app and the web viewer stay identical.

## Translations

1. **App:** copy `app/src/main/res/values/strings.xml` to `values-<lang>/strings.xml` (e.g. `values-de`, or `values-pt-rBR`
   for a regional variant) and translate the values.
2. **Viewer:** in `web/js/i18n.js`, copy the `en` block to a new language code (e.g. `de`, `pt-BR`) and translate it.
3. Check that text still fits on a small phone (about 360 dp wide).

That's all: no code to change. The language pickers (app and viewer) and Android 13+'s per‑app language setting list
every translation automatically, each language named in its own language.

## Pull requests

- One topic per PR, with a short description and screenshots for UI changes.
- CI must pass (build + tests).
- By contributing you agree that your contribution is licensed under the GNU GPL v3.0, like the rest of the project.
