# AGENTS.md

Standing instructions for AI coding agents (and any human collaborators)
working in this repository. Read this first; it is the source of truth for how
this project is versioned, built, and committed.

## Project facts

- **Repository:** `github.com/xenotropic/wcast`
- **Default branch:** `main`
- **Language/framework:** Kotlin, Jetpack Compose (Android app)
- **License:** MIT © 2026 Joseph Morris
- **Coding agent:** Cline (the open-source coding agent). The app was originally
  written with DeepSeek; credit wording in the README reflects the project
  history, but this repository is maintained through Cline.
  **General intent:** This is a Wikipedia text-to-speech Application for reading long form audio via the Android TTS system. We want to keep dependencies minimal / apk size small-ish. Everything will be open source. We want it to be as much like listening to a podcast as is practical, but within the constraints of how the Android TTS framework. 

## Git & CI conventions (IMPORTANT — must be followed)

1. **Commit locally only.** Never run `git push` unless the user (or another rule here, see e.g., 6) explicitly
   instructs you to push to the remote.
2. **Never create or push tags** unless the user explicitly instructs you to.
3. **Remote builds happen only on `v*` tags.** The GitHub Actions workflow
   (`.github/workflows/release.yml`) builds a debug APK and publishes a GitHub
   Release **only** when a `v*` tag is pushed. Ordinary commits and pull
   requests do NOT trigger remote builds. All day-to-day testing is done by
   building locally.
4. **Debug builds only (for now).** CI builds `assembleDebug`. Release signing
   for Google Play is handled separately, outside this repository; do not add
   signing configs or secrets here unless explicitly asked.
5. Do not commit build artifacts, local config, or anything already listed in
   `.gitignore`.
6. The directory .todo is in the repository and (exception to point 1) 
   is tracked and should be kept in sync (pull at start of job / push   at end) 
   on each turn by agents unless something else instructs you not to. 

## Building & validating changes locally (IMPORTANT)

- **Always produce a fresh debug APK after code changes.** Compiling
  (`./gradlew compileDebugKotlin`) or running the unit tests does NOT build or
  refresh the APK, so `app/build/outputs/apk/debug/app-debug.apk` can remain
  stale. Whenever a task changes code, run:

  ```bash
  ./gradlew assembleDebug
  ```

  and confirm the APK was actually regenerated (its mtime/size changed) before
  handing it off — otherwise the user installs an out-of-date build and reports
  that "nothing changed".
- **Refresh the About-screen build time.** `BuildConfig.BUILD_TIME_PT` is
  generated at *build* time (in `America/Los_Angeles` time), so every rebuild
  must yield a newer timestamp. After `assembleDebug`, check the generated
  `app/build/generated/source/buildConfig/debug/cloud/morris/wcast/BuildConfig.java`
  for a fresh `BUILD_TIME_PT` (and, when relevant, that `versionName` matches the
  intended tag) so the in-app About screen never shows a stale time.

## Debug logging (in-app, no ADB)

Wcast has an in-app debug logger so a user can capture diagnostics on-device
without `adb logcat`. The toggle is **Settings → Debug → "Debug logs"**; the
captured lines are shown under **Settings → Debug → "View debug logs"**, whose
top bar offers select-all / copy so the text can be pasted back into a ticket.
Logs are kept **in memory only** (a ~1000-line ring buffer in
`cloud.morris.wcast.DebugLog`), so they reset whenever the process dies.

When adding debug statements, follow these rules:

- Log through `cloud.morris.wcast.DebugLog.d(tag, message)` — **never**
  `android.util.Log` for new debug output. `d()` is a no-op unless the setting
  is enabled, so statements are effectively free when debugging is off.
- Every emitted line is prefixed with the key-phrase `WCAST_DEBUG`
  (`DebugLog.KEY_PHRASE`).
- **Grep-ability:** every debug call site is findable by grepping the source for
  `DebugLog.d` (or the constant `DebugLog.KEY_PHRASE` / the literal
  `WCAST_DEBUG`). Because they all route through this one symbol rather than the
  generic `Log.d`, stripping them later is a mechanical find/replace.

## Versioning

- Pre-Google-Play releases use semantic versions starting at `0.1`
  (e.g. `0.1`, `0.2`, `0.3`, ...). These are debug APKs built from `v*` tags.
- **`1.0` is reserved** for the first release published on Google Play.
  Do not use `1.0` (or higher) until then.
- `app/build.gradle.kts` defaults `versionName` to `0.5`. CI overrides it from
  the pushed tag (`-PversionName=<tag without the leading "v">`).
- `versionCode` is currently hardcoded to `1`.
- **Keep the build identity in sync (IMPORTANT).** The in-app About screen
  (`SettingsActivity`) shows both a version tag and a build timestamp
  (generated as `BuildConfig.BUILD_TIME_PT`, in `America/Los_Angeles` time).
  Whenever you change the version, make sure the build produces a *fresh*
  timestamp and that the `versionName` matches the intended tag, so the About
  screen never reports a stale version/time and you can always confirm a given
  APK was built from the expected tag.

## Releasing (a quick reference for when instructed)

To cut a release, push a `v*` tag — e.g.:

```bash
git tag v0.1
git push origin v0.1
```

This triggers the release workflow, which builds the debug APK and creates a
GitHub Release attached to the tag. Only do this when explicitly instructed.

## Miscellaneous

- Throwaway planning notes/scratch files that should not be published live in
  the gitignored `resources/` directory.
