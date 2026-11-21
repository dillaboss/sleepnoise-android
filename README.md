# Sleep Noise

A dedicated bedside brown-noise player for an old Android phone (built for a Galaxy S8 on Android 9 driving an Anker Soundcore 2 over AUX).

- Brown noise generated on the phone. The default sound is fitted to a reference recording: within about 1 dB from 30 Hz to 2.5 kHz, at the same loudness
- Giant play/pause button, black anti-burn-in night screen with a wandering dim clock
- Intro jingle, with an adjustable delay before the noise starts
- Offline wake word ("hey brownie", changeable) and voice commands
- Daily auto-start and auto-stop
- Opens at boot, can be the home app, and resumes after a restart

## How it works

| Part | What it does |
|---|---|
| `SleepService` | Foreground service (media playback + microphone) that owns everything below, so sound, voice and schedule keep working with the screen off or the UI closed |
| `NoiseSynth` | Builds a 2^21-frame (≈44 s) stereo loop by shaping a random spectrum and inverse-FFTing it, so the loop is seamless and left and right are independent. Tone, rumble cut and colour are baked into the spectrum. A slider change regenerates the loop and crossfades to it |
| `Renderer` + `AudioEngine` | `AudioTrack` (float) on an urgent-audio thread. Per sample it only reads the loop and applies smoothed gains: volume, width, swell, fades, duck, jingle, chime and a soft limiter |
| `VoiceEngine` + `WakeLogic` | `AudioRecord` → 220 Hz high-pass → [vosk-android](https://github.com/alphacep/vosk-api), with a grammar of only the wake phrase and commands |
| `Scheduler` | `AlarmManager.setAlarmClock` for the daily start and stop |
| `assets/ui/index.html` | The screen, a thin control panel in a WebView that talks to the service |

## Building

GitHub Actions builds every push to `main`:

1. Downloads the Vosk small English model (0.15, Apache-2.0) and checks it against `scripts/model.sha256`. The model isn't stored in git.
2. Runs the unit tests in `app/src/test`.
3. Signs the APK with the key from the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. No key is ever stored in the repo.
4. Publishes `SleepNoise.apk`, the build log and the test results to the [`apk-builds`](../../tree/apk-builds) branch.

For a local build, run `scripts/fetch-model.sh` and then `gradle assembleRelease`. Without the signing variables set, the APK comes out unsigned.

Versions before 2.0 (the WebView-only app) were signed with a key that was committed here. That key is no longer used.
