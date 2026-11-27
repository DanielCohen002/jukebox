<p align="center">
  <img src="docs/icon.png" alt="Jukebox icon" width="128" height="128">
</p>

<h1 align="center">Jukebox</h1>

Android app that lets voice commands start music in Metrolist, YMusic and AIMP, since Gemini won't.

### [Download the latest signed APK](https://github.com/DanielCohen002/jukebox/releases/latest)

Package name `com.djcohen.voiceplay`. Needs Android 11 or newer.

What it does:

- Say "Hey Google, open Jukebox", then e.g. "play Radiohead on Metrolist", "play jazz on AIMP", "play Creep on YMusic", "pause", "next".
- Optional always-on mode: an offline Vosk wake phrase (default "hey jukebox") listens in a foreground service, then Google SpeechRecognizer takes the command.
- Metrolist and YMusic are driven through its media3 MediaBrowser/MediaController; AIMP through its MediaBrowserService, with a play-from-search intent fallback. Only the apps that are installed are offered.

## Install

Download the APK from the [Releases](../../releases/latest) page and open it on your phone. You may need to allow installs from your browser or file manager.

## Build

The Vosk model is not committed (about 60 MB). Download a small English model from https://alphacephei.com/vosk/models, unzip it, and put its contents in `app/src/main/assets/model/`. Then:

```
./gradlew assembleDebug
```

## Test without a microphone

```
adb shell "am start -n com.djcohen.voiceplay/.MainActivity --es text 'play creep by radiohead on metrolist'"
```

## License

GPL-3.0. See [LICENSE](LICENSE). Free software: you can use, change and share it, and anything you share based on it must stay GPL-3.0 with its source available.
