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

## Source map
- `Capture.swift` – CoreAudio device capture, per-channel extraction, on-device speech recognition
- `Store.swift` – state, automation engine, persistence, import/export
- `OSC.swift`, `MIDIManager.swift` – OSC (UDP) and CoreMIDI in/out, incl. a virtual "CueComm" MIDI port
- `NetworkSync.swift` – Bonjour/TCP sharing of channels and groups, plus dialling by address
- `TranslationHost.swift` – Apple Translation framework bridge
- `*View.swift`, `ChannelEditor.swift` – SwiftUI interface
