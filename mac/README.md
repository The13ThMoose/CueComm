# CueComm (macOS)

Real-time voice intelligence for live production: transcribe, translate, automate.

## Build & run
Needs only Xcode Command Line Tools (macOS 14+, macOS 15+ for translation).

    ./build.sh && open build/CueComm.app

Grant Microphone, Speech Recognition and Local Network access when asked.
Config and history live in `~/Library/Application Support/CueComm/`.

## Sessions across networks
Bonjour discovery only reaches the local subnet. A Mac hosting a session shows its address and port (default
47800) in the Session sheet. On any other Mac, type that address under "Join by IP address" and press Join:
the Mac asks the address which session it hosts, switches to that session and connects at once, so no session
name has to be typed. The address is kept and redialled every 5 s if the link drops, and is forgotten when you
leave the session. A Mac already in a session can add another address with "Add a Mac by address" if it is in
the same session. Both sides may dial each other; one connection is kept. Each Mac connects straight to every
other Mac (no relaying). Traffic is plain TCP, so use a VPN over the internet.

A listener answers a normal hello only when the session name matches. The join request (hello with
`join: true`) is the one exception: a hosting Mac replies with a `welcome` frame carrying its session name and
closes, so anyone who can reach the port can learn the session name, but nothing else.

Test hooks: `CUECOMM_SUPPORT_DIR` (separate config per copy), `CUECOMM_NO_DISCOVERY=1` (disable Bonjour),
`CUECOMM_DEMO=manual` (open the Session sheet scrolled to the address section), `CUECOMM_JOIN=host:port`
(join by address at launch and print the result to stderr).

## Soundcards, Dante Virtual Soundcard and recognition quality
A meter that moves says there is signal, not that the signal suits a speech recogniser. A console or Dante send can be
30 dB quieter than a microphone, an open mic carries room noise, and a card can change its sample rate or drop buffers.
Each listening channel is therefore conditioned before it reaches Apple's recogniser (`InputConditioner.swift`):

1. resampled to a fixed 16 kHz mono stream (never changes under the recogniser when the card changes rate),
2. high-passed at 100 Hz (rumble, hum),
3. gated: speech passes with a 0.5 s lead-in, the noise between speech is dropped (`Ignore noise between speech`),
4. levelled to a steady speech level with a soft limiter (`Even out the level`), plus a manual gain (-12 to +36 dB).

The channel editor shows the peak level of the selected input in dBFS with a plain verdict (no signal, very quiet,
good, clipping). While listening, the channel's status line says when the signal is very quiet or clips, when the card
drops audio buffers (raise the latency in the Dante Virtual Soundcard window), and when the chosen input number does
not exist on the device (it used to silently listen to the last input instead).

Audio that arrives while the recogniser restarts after a pause is kept (up to 3 s) and handed to the next task, and
errors back off instead of looping.

Measurement: `CueComm.app/Contents/MacOS/CueComm` cannot be started from a shell for speech (macOS ties the permission to the
app), so run `open -n -W --stdout out.txt CueComm.app --args --speechtest <folder> --locale nb-NO`. The folder holds mono
clips (`c01.wav`) with the spoken text in `c01.txt`. It reports the word error rate of the old path (raw device audio)
against each conditioning stage at several levels and noise levels (`--levels -20,-35,-50 --snrs none,15 --noise-only
--e2e --verbose`). It runs on a temporary support folder, never your setup.

## Source map
- `Capture.swift` – CoreAudio device capture, per-channel extraction, on-device speech recognition
- `InputConditioner.swift` – resample, high-pass, speech gate, level control per channel
- `SpeechTest.swift` – `--speechtest` measurement harness
- `Store.swift` – state, automation engine, persistence, import/export
- `OSC.swift`, `MIDIManager.swift` – OSC (UDP) and CoreMIDI in/out, incl. a virtual "CueComm" MIDI port
- `NetworkSync.swift` – Bonjour/TCP sharing of channels and groups, plus dialling by address
- `TranslationHost.swift` – Apple Translation framework bridge
- `*View.swift`, `ChannelEditor.swift` – SwiftUI interface
