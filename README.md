<table border="0" style="border-collapse: collapse; border: none;">
  <tr style="border: none;">
    <td valign="top" style="border: none;">
      <h1>wcast</h1>
      <p><b>Listen to Wikipedia articles using the Android TTS service</b></p>
      <p>Wcast is an open-source (MIT license) Android app that lets you search Wikipedia, read a clutter-free version of any article, and have it read aloud — the whole article or a single section at a time — using the Android text-to-speech service. That service can be either the default one that ships with your device, or can be replaced to use other TTS services that allow use of other models (e.g., <a href="https://github.com/maxwhipw/marmalade-tts-android">Marmalade-TTS</a>).</p>
      <p>While there are other services that work on server-side creation of audio of Wikipedia articles such as <a heref="https://ttsreader.com/wiki/">WikiTTS</a> or <a href="https://wiki-tts.toolforge.org/"> Wikipedia's own experiments</a> (which do have the advantage of caching high quality model output for many users to share), this app seeks to do as much as possible within Android system, allowing the user to: (1) up-to-date revisions of articles and (2) giving the user the choice of what TTS voices to use, be that the default TTS engine that ships with their phone, or other local or cloud-based models, such as those available through Marmalade (and any other app that functions as a Android TTS service). 
      <p>Made with Kotlin and Jetpack Compose. Coding by DeepSeek V4 Pro0813, with additional advice and planning from Gemini. The text normalization rules are based upon my experiments a few years ago in <a href="https://github.com/xenotropic/wiki-tts">wiki-tts</a>.</p>
    </td>
    <td valign="top" width="280" style="border: none;">
      <img src="https://github.com/user-attachments/assets/b52d6168-b487-44d8-89b0-784ec9432dc3" width="260" alt="Wcast Screenshot" />
    </td>
  </tr>
</table>

---

## What it does

- **Search Wikipedia** straight from the app. Type a topic and Wcast finds the
  article, or shows you close matches to tap.
- **A clean reading view.** Wcast strips out infoboxes, citation
  markers, tables, nav links, and edit controls in its view. 
- **Listen to any article.** Tap **Play** next to the title to hear the whole
  article from start to finish, or tap **Play** next to a section to hear just
  that part.
- **Natural-sounding speech.** Before anything is spoken, Wcast further rewrites the
  text to help TTS engines read it aloud — turning "Henry VIII" into "Henry the
  eighth", "120 km/h" into "120 kilometers per hour", "$4 billion" into "four
  billion dollars", and so on. While there are other text normalization engines (NemoTTS, others built into various TTS models), this is just a very simple set of rules tuned to Wikipedia, things like scientific units and Roman numerals that can be done quickly by phone hardware on the fly. 

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
- **Only permission is network access.** Wcast only requests the
  `INTERNET` permission to fetch articles from Wikipedia. It talks to the TTS
  service you already have, so there's no audio permission to grant.

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
- 🧹 Cleaned article body (no infoboxes, references, etc.)
- ▶️ Whole-article and per-section playback
- 🗣️ Uses your device's built-in Android TTS engine
- ✍️ Simple text normalization for more straightforward pronunciation

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
