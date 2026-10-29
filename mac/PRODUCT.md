# Product

<!-- impeccable:product-schema 1 -->

## Platform

macos

(Native macOS app in SwiftUI/AppKit, built with SwiftPM, macOS 14+. Not web, iOS or Android; none of the mobile platform references apply.)

## Stack
SwiftUI on macOS 14+, CoreAudio / AVFoundation / Speech / CoreMIDI / Network / Translation frameworks. SwiftPM only (Command Line Tools, no Xcode); `build.sh` assembles and ad-hoc signs `build/CueComm.app`.

## Users
Live-production crews: FOH and monitor engineers, show callers, music directors, tour managers. They sit at a laptop or desk monitor at arm's length, usually in dim or dark rooms (FOH position, monitor world, show-caller desk), mid-show, with their eyes and hands busy on a console or cue system. They glance at CueComm to see who said what and when, and must catch a missed request in under a second.

## Product Purpose
CueComm transcribes live multi-channel audio into a chat-style readout (who said what, when), translates it, and turns spoken keywords into actions (MIDI, OSC, window flash, channel select). Success: nobody on the crew misses a spoken request, and a spoken cue fires reliably without extra hardware.

## Positioning
Voice intelligence for live production built around crew workflow: channel-per-source transcripts, keyword alerts for missed words, and hands-free automation that talks MIDI/OSC to show-control gear. Modelled on the product category of ProdCom (prodcom.io); CueComm is an independent implementation with its own name.

## Operating Context
- Soundcards / interfaces via CoreAudio (Dante, MADI, AVB, SoundGrid, USB). Each channel is bound to one device input; the user picks the device and input number per channel, with a live level meter to confirm they have the right input.
- Several machines on the same LAN join one named show session (host a session, others join; everyone sees the same channels and transcript live) and may additionally share only chosen channels or groups.
- Dark rooms, glance-reading, gloved or busy hands, a laptop at arm's length.

## Capabilities and Constraints
- Channels with colour, group, pin, keywords, language, translation, device + input, on-device recognition.
- Transcript with live partials, jump-to-current, clear, offensive-word filter, export, text size.
- Unread badges, amber when a keyword was missed.
- Automations: keyword / any message / MIDI / OSC triggers; MIDI, OSC, flash, show/hide window, select channel, scroll, clear, mark read, font size actions.
- Network: Bonjour/TCP. Hosted sessions plus per-channel/group sharing.
- Out of scope: subscriptions, licensing, billing.
- Undecided: none open.

## Brand Commitments
- The Meltveit Lydproduksjon logo (`Resources/MeltveitLogo.png`: white "MELTVEIT" wordmark, orange "LYDPRODUKSJON", orange-to-pink waveform bars with a green playhead line, transparent background) must stay visible in the GUI, not large. Its orange, coral, pink and green are the brand palette and should inform the interface colours.
- The user explicitly requires: very easy to read, vibrant colours.

## Evidence on Hand
- Logo at `Resources/MeltveitLogo.png`.
- ProdCom marketing site (prodcom.io) as a feature reference only; no assets from it are reused.
- No real customer quotes, benchmarks or pricing exist for CueComm; none may be invented.

## Product Principles
1. A missed word is the failure to design against: what needs attention is unmistakable at a glance.
2. Readable from arm's length in a dark room: large, high-contrast, generous type; colour carries meaning, not decoration.
3. Show the crew's world: who (channel colour), when, and what, in that order.
4. Setup is a pre-show job, performance is a glance job; both must be fast, and setup never interrupts the transcript.
5. Networking and routing choices are always visible and verifiable (meters, peer status), never silent.

## Accessibility & Inclusion
Contrast well above WCAG AA for transcript text; never colour alone for state (badges carry numbers and icons); respect macOS text-size and reduced-motion settings; full keyboard operation.
