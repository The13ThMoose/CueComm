# CueComm for Android

The Android companion to CueComm for Mac: live speech to text on named channels, translation, cue words that
send OSC and flash every screen, and sessions that connect Android and Mac devices to each other.
Kotlin, Jetpack Compose, Android 8.0 (API 26) and newer. The Mac app is not touched by this project.

## Install on a phone

Download `CueComm.apk` from the latest GitHub release onto the phone, open it and allow "install unknown apps" for the app you opened it from.
Allow the Microphone (and Notifications, so a cue shows up while the screen is off).

## What it does

- **Channels, groups, pins, colours, keywords** with the same setup file format as the Mac
  (Settings > Export / Import setup).
- **Listen**: Android's speech recogniser on the channel you are viewing (or the one chosen with "Speak on this
  channel"). Partial text shows live, a pause ends a message, keyword automations can fire before the sentence ends.
  Prefers the offline language pack and falls back to online recognition when it is not installed.
- **Translation** per channel with on-device ML Kit models (about 30 MB per language pair, downloaded on first use).
- **Cue words and automations**: say a word, send OSC (UDP) and flash. Triggers: keyword, any message, incoming OSC.
  Actions: send OSC, flash, select channel, scroll, clear, mark read, text size. MIDI is Mac only.
- **Sessions**, compatible with the Mac: Bonjour/NSD discovery on one network; **Join by IP address** for another
  network or subnet (type the address, you join that session at once); automatic reconnect; flash on every device.
- A foreground service keeps the session and cue words alive with the screen off.

## Sessions across networks

Same as the Mac: the hosting device shows its address and port (default 47800) under "Others can join this session
at". Anyone types that under "Join by IP address". Over the internet, forward TCP port 47800 or use a VPN such as
Tailscale. Traffic is not encrypted. There is no relaying, so in a larger session each device connects straight to
every other one. Android speaks the Mac's wire protocol (`core/Protocol.kt`, `core/NetworkSync.kt`): newline
delimited JSON, UUIDs upper case, dates ISO 8601 without fractions, a `hello` with `join: true` answered by a
`welcome` carrying the session name.

## Build

You need JDK 17 and the Android SDK (platform 35 and build-tools 35). Open this folder in Android Studio, or from a
terminal:

```bash
./gradlew :app:testDebugUnitTest      # the JVM tests
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

A release build is signed with `keystore.properties` when that file exists next to `settings.gradle.kts`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; it is git-ignored) and with the debug key otherwise. An
update only installs over an app signed with the same key, so keep a backup of yours. Point Gradle at your SDK with
`sdk.dir` in `local.properties` if Android Studio has not already.

### Checking against a real CueComm for Mac

```bash
./gradlew testDebugUnitTest --tests '*MacCrossCheck*' -Pmac=127.0.0.1:47821 -PmacDir=<mac support dir> -PmacOsc=9021
```

The Mac must host a session and have an OSC-triggered flash automation on `/test/cue`
(run it with `CUECOMM_SUPPORT_DIR` and `CUECOMM_NO_DISCOVERY=1`). `-Plisten=47830` instead waits for a Mac that
dials this device.

## Layout

```
app/src/main/java/no/meltveit/cuecomm/
  core/      plain Kotlin, tested on the JVM: models, wire protocol, NetworkSync, OSC, store, automations
  android/   NsdDiscovery, AndroidSpeech, MlKitTranslator, SessionService, MainActivity, application
  ui/        Compose screens: main, session, channel editor, cues, automations, settings
app/src/test/  codec, wire-format, session and speech tests, and the Mac cross-check
```

## Limits

- One microphone at a time: a device speaks into one channel. The Mac's per-channel soundcard inputs have no
  Android equivalent; a USB or Bluetooth microphone that Android routes as the input works.
- The flash is shown inside the app (plus vibration and a notification when the app is in the background), not over
  other apps.
- Not tested on a physical phone, over a real internet connection, or with speech from a real microphone.
