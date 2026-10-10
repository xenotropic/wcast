# Wcast

**Turn Wikipedia articles into clean, natural-sounding audio.**

Wcast is an open-source Android app that lets you search Wikipedia, read a
clutter-free version of any article, and have it read aloud — the whole article
or a single section at a time — using the text-to-speech voice already on your
phone.

Made with Kotlin and Jetpack Compose. Coding by DeepSeek V4 Pro0813, with additional
advice and planning from Gemini. The text normalization rules are based upon my experiments a few years ago in [wiki-tts](https://github.com/xenotropic/wiki-tts).

---

## What it does

- **Search Wikipedia** straight from the app. Type a topic and Wcast finds the
  article, or shows you close matches to tap.
- **A clean reading view.** Wcast strips out the cruft — infoboxes, citation
  markers, tables, nav links, and edit controls — leaving just the readable
  prose, section headings, and paragraphs.
- **Listen to any article.** Tap **Play** next to the title to hear the whole
  article from start to finish, or tap **Play** next to a section to hear just
  that part.
- **Natural-sounding speech.** Before anything is spoken, Wcast rewrites the
  text so it reads correctly out loud — turning "Henry VIII" into "Henry the
  eighth", "120 km/h" into "120 kilometers per hour", "$4 billion" into "four
  billion dollars", and so on.

---

## How the text-to-speech works

Wcast does **not** include its own voice engine. It uses the
**[Android system text-to-speech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
engine** that is already installed and configured on your device.

This means:

- **The voice comes from your phone.** Whatever TTS engine you have selected in
  Android's **Settings → System → Languages & input → Text-to-speech output**
  (usually "Google Speech Services") is what Wcast speaks with. Switch the
  engine or voice there, and Wcast's voice changes with it.
- **No extra permissions beyond network access.** Wcast only requests the
  `INTERNET` permission to fetch articles from Wikipedia. It talks to the TTS
  service you already have, so there's no audio permission to grant.
- **The spoken text is normalized, not the text on screen.** Wikipedia is full
  of typography that looks fine but reads badly aloud — Unicode dashes and
  quotes, "3rd", "U.S.", "°F", fractions, roman numerals, and more. Wcast
  rewrites only what it *speaks* through a multi-stage normalization pipeline,
  while the article you read on screen is left exactly as written.

### Tuning the pronunciation

Most of the normalization rules (some are too complex and have to exist as procedural code) live in plain, editable text files bundled with the app
(`app/src/main/assets/normalization/`):

| File | Purpose |
|------|---------|
| `bulk_replace.csv` | One-to-one substitutions (e.g. `Mr.` → `Mister`). |
| `pronounced_acronyms.csv` | How specific acronyms should be said (e.g. `NATO` → `Nayto`). |
| `regex_rules.txt` | Ordered regex rules for things like number ranges and temperature. |

The simpler transforms (find → replace) are all data-driven, so tweaking how a
word or phrase is spoken can be done by editing these files without touching
code. More involved transforms (money, fractions, ordinals, roman numerals)
live in the Kotlin normalization code.

---

## Features at a glance

- 🔎 Wikipedia search with exact-match + suggestions
- 🧹 Cleaned article body (no infoboxes, references, or edit chrome)
- ▶️ Whole-article and per-section playback
- 🗣️ Uses your device's built-in Android TTS engine
- ✍️ Data-driven text normalization for natural pronunciation

---

## Building

Wcast is a standard Android Gradle project.

**Prerequisites:** Android Studio (or the Android SDK), JDK 17.

```bash
# Build a debug APK
./gradlew assembleDebug

# Run the unit tests (normalization logic)
./gradlew test
```

The debug APK is written to `app/build/outputs/apk/debug/`.

> You can also open the project in Android Studio and press **Run**.

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Architecture:** MVVM + unidirectional data flow with `StateFlow`
- **HTML parsing:** jsoup
- **Concurrency:** Kotlin coroutines
- **Speech:** Android system `TextToSpeech`

---

## Permissions

Wcast requests a single permission:

- **`android.permission.INTERNET`** — to fetch Wikipedia articles.

No microphone, storage, or audio-record permissions are ever requested.

---

## License

[MIT](LICENSE) © 2026 Joseph Morris
