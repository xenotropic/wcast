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

## Git & CI conventions (IMPORTANT — must be followed)

1. **Commit locally only.** Never run `git push` unless the user explicitly
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

## Versioning

- Pre-Google-Play releases use semantic versions starting at `0.1`
  (e.g. `0.1`, `0.2`, `0.3`, ...). These are debug APKs built from `v*` tags.
- **`1.0` is reserved** for the first release published on Google Play.
  Do not use `1.0` (or higher) until then.
- `app/build.gradle.kts` defaults `versionName` to `0.1`. CI overrides it from
  the pushed tag (`-PversionName=<tag without the leading "v">`).
- `versionCode` is currently hardcoded to `1`.

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
