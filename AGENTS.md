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

7. **Prime git credentials at task start.** Before doing any other git work in
   a task, warm the credential cache so pushes/pulls don't re-prompt mid-task.
   Run a no-change write-auth probe (a *dry-run* — it never updates the remote,
   so it is an explicit exception to rule 1):

   ```bash
   git config --global credential.helper 'cache --timeout=28800'  # idempotent; 8h in-memory
   git push --dry-run origin HEAD                                  # prompts if the PAT is uncached; changes nothing
   ```

   Credentials are held in-memory only (the `git-credential-cache` daemon) for
   at most 8 hours and are never written to disk, so a PAT can't be replayed
   after the agent has stepped away. The Dev Containers extension injects a
   host-keychain credential helper into `/etc/gitconfig` at container start;
   `GIT_CONFIG_NOSYSTEM=1` (set in `.devcontainer/devcontainer.json`) disables
   it so git never falls back to the indefinite host keychain.
## Todo index (.todo/index)

The file `.todo/index` is a quick-reference listing of every ticket in this
repo and must be kept current. Agents update it whenever they create, resolve,
or close a ticket. Format:

- Two sections — `## Open` (top) and `## Closed` (bottom) — each sorted by
  ticket number, highest first.
- The first line states the **next open ticket number** (highest number in
  use + 1) so a new ticket can be created without hunting for the max.
- Every open-ticket line ends with `(Cline Difficulty Rating: X)`, a quick
  0–9 guess at difficulty: `0` = trivial / 100% confidence, `9` = "can't even
  guess how hard without a deep architectural dive". Closed tickets need no
  rating.
- A ticket's slug is its filename minus the leading `N-` and any trailing
  `-CLOSED`.

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
- **`1.0` is the first Google Play release.**
- `app/build.gradle.kts` defaults `versionName` to `1.0`. CI overrides it from
  the pushed tag (`-PversionName=<tag without the leading "v">`).
- **Every release tag requires a `versionCode` increment first.** Google Play
  rejects uploads whose `versionCode` isn't higher than the last uploaded
  artifact, so bump `versionCode` in `app/build.gradle.kts` before cutting any
  `v*` tag. (It currently sits at `2`.)
- **Keep the build identity in sync (IMPORTANT).** The in-app About screen
  (`SettingsActivity`) shows both a version tag and a build timestamp
  (generated as `BuildConfig.BUILD_TIME_PT`, in `America/Los_Angeles` time).
  Whenever you change the version, make sure the build produces a *fresh*
  timestamp and that the `versionName` matches the intended tag, so the About
  screen never reports a stale version/time and you can always confirm a given
  APK was built from the expected tag.

## Releasing (a quick reference for when instructed)

To cut a release, push an annotated `v*` tag. **Release tags must carry a
human-readable summary of what changed** — the GitHub release list and changelog
render the tag message, so a bare "bump for version 0.x" is not acceptable.
Before tagging:

1. Bump `app/build.gradle.kts`'s default `versionName` to the new version and
   **increment `versionCode`** (a hard requirement — Google Play rejects a
   lower/equal `versionCode`), so local dev builds and the in-app About screen
   stay in sync with the release.
2. Review `git log <previous-tag>..HEAD` and summarise the *user-facing*
   features and bug fixes in this release, with ticket numbers where they exist.
3. Create an annotated tag whose message is that summary, e.g.:

   ```bash
   git tag -a v0.9 -m "Wcast v0.9

   Features:
   - Article thumbnails with captions, also used as media-player artwork (#33)
   - Lock-to-portrait setting and re-centre reading position after rotation (#27)

   Bug fixes:
   - Player-bar play button starts the whole article on a fresh article (#31)"
   git push origin v0.9
   ```

This triggers the release workflow, which builds the debug APK and creates a
GitHub Release attached to the tag (CI overrides `versionName` from the pushed
tag via `-PversionName=<tag without the leading "v">`). Only cut a release when
explicitly instructed.

## Miscellaneous

- Throwaway planning notes/scratch files that should not be published live in
  the gitignored `resources/` directory.
