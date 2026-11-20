# Sleep Noise

A dedicated bedside brown-noise player for an old Android phone (built for a Galaxy S8 driving an Anker Soundcore 2 over AUX).

- Brown noise generated on the device: tone, rumble cut, brown↔pink colour, stereo width, slow "ocean swell", fades
- Giant play/pause button, black anti-burn-in night screen with a wandering dim clock
- Intro jingle with an adjustable "noise starts N seconds after the jingle" setting, plus optional daily auto-start and auto-stop
- Offline wake word ("hey brownie", changeable) using Vosk speech recognition. Nothing leaves the phone, and no internet is needed
- Native shell: opens at boot, can be set as the home app, keeps the screen on, mic granted once, resumes after a restart

## Getting the APK

Every push to `main` builds the app on GitHub Actions. The newest APK is published to the
[`apk-builds`](../../tree/apk-builds) branch as `SleepNoise.apk`, and is the download link for every build (no Actions artifacts are used, so it never hits the storage quota).

## Layout

| Path | What |
|---|---|
| `web/template.html` | The app's UI and audio/voice logic |
| `web/build.py` | Inlines the speech engine, jingle, vocabulary and model into one `index.html` |
| `web/vosk.patched.js` | vosk-browser 0.0.8, patched to load the model from memory (no network, no IndexedDB) |
| `web/model-slim.tar.gz` | Vosk small English model 0.15 (Apache-2.0) with its language-model graph stubbed out. Only the fixed command grammar is used |
| `web/IntroJingle.wav` | The intro clip |
| `app/` | Android WebView shell (Java) |

`app/release.keystore` is a throwaway signing key (password `sleepnoise`) so each build installs over the last one. It isn't a secret. Keep this repo private if that matters to you.
