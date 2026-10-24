<table border="0" style="border-collapse: collapse; border: none;">
  <tr style="border: none;">
    <td valign="top" style="border: none;">
      <h1>wcast</h1>
      <p><b>Listen to Wikipedia articles using the Android TTS service</b></p>
      <p>Wcast is an open-source (MIT license) Android app that lets you search Wikipedia, read a clutter-free version of any article, and have it read aloud — the whole article or a single section at a time — using the Android text-to-speech service. That service can be either the default one that ships with your device, or can be replaced to use other TTS services that allow use of other models (e.g., <a href="https://github.com/maxwhipw/marmalade-tts-android">Marmalade-TTS</a>).</p>
      <p>While there are other services that work on server-side creation of audio of Wikipedia articles such as <a href="https://ttsreader.com/wiki/">WikiTTS</a> or <a href="https://wiki-tts.toolforge.org/"> Wikipedia's own experiments</a> (which do have the advantage of caching high quality model output for many users to share), this app seeks to do as much as possible within Android system, allowing the user to: (1) up-to-date revisions of articles and (2) giving the user the choice of what TTS voices to use, be that the default TTS engine that ships with their phone, or other local or cloud-based models, such as those available through Marmalade (and any other app that functions as a Android TTS service). 
      <p>Made with Kotlin and Jetpack Compose. Coding by DeepSeek V4 Pro0813, with additional advice and planning from Gemini. The text normalization rules are based upon my experiments a few years ago in <a href="https://github.com/xenotropic/wiki-tts">wiki-tts</a>.</p>
    </td>
    <td valign="top" width="280" style="border: none;">
      <img src="https://raw.githubusercontent.com/xenotropic/wcast/refs/heads/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1-wcast.png" width="260" alt="Wcast Screenshot" />
    </td>
  </tr>
</table>
For now if you want to try it out please see the <a href="https://github.com/xenotropic/wcast/releases">releases tab</a> and sideload the latest release's apk. I have submitted to F-Droid and likely will submit to Play soon. 


## What it does

- **Search** — type a topic and it fetches the matching Wikipedia article, with
  suggestions when there are several close matches.
- **A cleaner reading view** — infoboxes, citation markers, tables, nav boxes, and
  edit links are stripped out so you mostly get prose.
- **Playback** — read a whole article, or tap a section to read just that part. A
  marker on the left tracks which paragraph is being spoken, and the view scrolls
  to follow it.
- **Text normalization** — before anything is read aloud, the article is rewritten
  so a text-to-speech engine is more likely to say it correctly ("Henry VIII" →
  "Henry the eighth", "120 km/h" → "120 kilometers per hour", "$4 billion" →
  "four billion dollars", plus Wikipedia-specific cases like units, roman
  numerals, and ordinals). This is a small set of hand-written rules tuned to
  Wikipedia prose, not a general NLP pipeline, so it catches a lot of common
  cases but far from everything.
- **Bookmarks** — save your place in an article and resume it later.
- **Background playback** — while it reads, a media notification shows pause/skip
  controls, and wired/Bluetooth headset buttons work, like a podcast or music app.
- **A few settings** — choose whether playback stops at the end of a section or
  continues to the end of the article, resize the playback bar, and jump into
  Android's text-to-speech settings to change the voice.

Wcast is an independent project; it isn't affiliated with or endorsed by
Wikipedia or the Wikimedia Foundation.

## What it does not do

- **No built-in voices.** It speaks with whatever Android text-to-speech engine is
  installed and selected on the device, so quality and speed depend entirely on
  that engine — a slow local engine will read slowly.
- **English-only, rule-based normalization.** It mispronounces plenty of things
  and is only tuned for Wikipedia-style prose.
- **Pre-release.** The app is at version 0.x and isn't published on an app store
  yet; for now you build it from this repo (see below).
- **Paragraph pauses.** Rendering and playback happen a paragraph at a time, so a
  slow text-to-speech engine can leave a gap between paragraphs. This is actively
  being worked on.

## How text-to-speech works

It uses the Android system text-to-speech engine — the one set under
**Settings → System → Languages & input → Text-to-speech output** (usually
"Google Speech Services"). Wcast doesn't ship any voices. It renders each
paragraph to a short audio file with that engine, then plays the file back
itself; that's how it can pause, resume, and skip at paragraph boundaries and
keep playing in the background. Change the engine or voice in system settings
and Wcast follows suit (including third-party engines such as Marmalade-TTS).

Pronunciation can be tuned without touching code: the simpler find-and-replace
rules live in editable files bundled with the app
(`app/src/main/assets/normalization/`):

| File | Purpose |
|------|---------|
| `bulk_replace.csv` | One-to-one substitutions (e.g. `Mr.` → `Mister`). |
| `pronounced_acronyms.csv` | How specific acronyms are said (e.g. `NATO` → `Nayto`). |
| `regex_rules.txt` | Ordered regex rules for things like number ranges and temperature. |

More involved cases (money, fractions, ordinals, roman numerals) live in the
Kotlin normalization code.

## Permissions

- **`INTERNET`** — fetch articles and page metadata from Wikipedia.
- **`POST_NOTIFICATIONS`** — on Android 13 and newer, to show the media
  notification while an article is reading. Older Android versions don't need to
  prompt for notifications. If it's declined, playback and headset controls still
  work, you just don't get the card.
- It also declares the usual foreground-service permission so playback can keep
  running with the screen off or another app in front.

No microphone, camera, location, or storage permissions are requested, and no
audio is recorded.

## Building

A normal Android Gradle project. Prerequisites: Android Studio (or the Android
SDK) and JDK 17. Minimum Android version is 8.0 (API 26).

```bash
./gradlew assembleDebug   # debug APK lands in app/build/outputs/apk/debug/
./gradlew test            # JVM unit tests for the normalization logic
```

Or open the project in Android Studio and press Run.

## Tech stack

Kotlin and Jetpack Compose (Material 3); MVVM with `StateFlow` for UI state;
jsoup for HTML parsing; coroutines for concurrency. Speech goes through the
Android `TextToSpeech` API and standard `MediaPlayer`/`MediaSession`, so there's
no bundled voice engine and no audio codec dependencies.

## License

[MIT](LICENSE) © 2026 Joseph Morris
