# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

(This record covers the public download site for CueComm. CueComm itself is a native macOS app and a native Android app; their own product record is `mac/PRODUCT.md` in this repository. The site is a sibling of the StageTracks download site, https://the13thmoose.github.io/Stagetracks/.)

## Stack

Static HTML, CSS and a little JavaScript, English and Norwegian pages generated from one Python script (`scripts/build_site.py`), exactly like the StageTracks site, so the two can share structure, fonts and tooling. Hosted on GitHub Pages from the `docs/` folder of the public repo `The13ThMoose/CueComm`; downloads are GitHub Release assets. The user chose this pattern for StageTracks and asked for the same family here.

## Users

Live-production crews: FOH and monitor engineers, show callers, music directors, tour managers, church and theatre tech leads. They find the page on a laptop or a phone, often from a colleague's recommendation, and need to know in seconds whether it fits their rig, what it needs, and how to install it. The same people already know StageTracks (same maker) or ProdCom (the product category).

## Product Purpose

CueComm turns live speech on production channels into a chat-style transcript (who said what, when), translates it, and fires cues when a chosen word is said: OSC out, and a screen flash on every device in the session. Success: nobody on the crew misses a spoken request, and a spoken cue arrives on every screen. The page's job is to get a crew member to the right download and past the install warnings.

## Positioning

Voice intelligence for live production built around crew workflow, working across Mac and Android in one session, including devices on different networks (join by IP address). An independent implementation in the product category of ProdCom, with its own name; the page must not use ProdCom's name, logo or wording as its own.

## Operating Context

- Sessions: devices on one network find each other automatically; devices on other networks or subnets join by typing the host's IP address and land in that session at once. Over the internet: port forwarding or a VPN such as Tailscale; traffic is not encrypted.
- Cues: a keyword fires OSC (UDP) to a lighting desk or show-control system and flashes the screen of every device in the session.
- Mac: CoreAudio soundcard inputs per channel (any interface), Apple speech recognition, Apple Translation (macOS 15+), MIDI and OSC.
- Android: one microphone at a time (a device speaks into one channel), Android speech recognition (offline pack preferred, online fallback), ML Kit translation, a foreground service keeps the session alive with the screen off.
- Both apps read and write the same setup files and speak the same network protocol.

## Capabilities and Constraints

- Mac build: universal (Intel and Apple Silicon), macOS 14 or later, ad-hoc signed and not notarised (Gatekeeper steps needed). File `CueComm.dmg`, 2.1 MB.
- Android build: Android 8.0 (API 26) or later, signed release APK, installs by allowing unknown apps for the app that opened it. File `CueComm.apk`, about 65 MB.
- No iPhone or iPad version exists. The page may say so plainly; it must not promise one.
- Not tested on a physical Android phone or with real microphone speech, and not tested across a real internet connection; the page must not claim those. Real renders only: the Mac app's demo mode and an Android emulator running synthetic demo data.
- Out of scope: accounts, subscriptions, licensing, billing.

## Brand Commitments

- Maker: Meltveit Lydproduksjon (logo `mac/Resources/MeltveitLogo.png`: white wordmark, orange "LYDPRODUKSJON", orange-to-pink waveform bars with a green playhead). Same maker and same footer as StageTracks.
- The app's palette is the brand palette: matte black, one vibrant hue per channel, orange for actions, green for live, yellow for cues (`mac/DESIGN.md`).
- Price statement (user's answer): "Free during the beta", the same wording as StageTracks. What happens after the beta will be announced on the page.
- Repository (user's answer): everything public, source for both apps included. No open-source licence has been chosen; the page and README must not claim one.
- English and Norwegian, like StageTracks. The Norwegian copy is unproofread by the user.

## Evidence on Hand

- Real renders from a synthetic demo (no one's real crew chatter or channels) are produced for the page: Mac demo window shots and Android emulator shots.
- Absent and not to be invented: testimonials, customer names, benchmarks, download counts, a contact email, a licence, a price after the beta, an iPhone or iPad release date.

## Product Principles

- Say what a crew member can do with it in the first screen: speak, read, cue.
- Name what each platform cannot do (Android one microphone, no iOS) next to what it can.
- The install steps are part of the product: the warnings are explained, with the checksum to verify.
- Same family as StageTracks: recognisably from the same maker without borrowing its colours or claims.
