# Jukebox

Android app that lets voice commands start music in Metrolist and AIMP, since Gemini won't.

- Say "Hey Google, open Jukebox", then e.g. "play Radiohead on Metrolist", "play jazz on AIMP", "pause", "next".
- Optional always-on mode: an offline Vosk wake phrase (default "hey music") listens in a foreground service, then Google SpeechRecognizer takes the command.
- Metrolist is driven through its media3 MediaBrowser/MediaController; AIMP through its MediaBrowserService, with a play-from-search intent fallback.

## Build

The Vosk model is not committed (about 60 MB). Download a small English model from https://alphacephei.com/vosk/models, unzip it, and put its contents in `app/src/main/assets/model/`. Then:

```
./gradlew assembleDebug
```

## Test without a microphone

```
adb shell "am start -n com.djcohen.voiceplay/.MainActivity --es text 'play creep by radiohead on metrolist'"
```
