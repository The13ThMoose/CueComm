#!/usr/bin/env python3
"""Builds the CueComm download site (docs/index.html in English, docs/no/index.html in Norwegian) from one set of texts.

    python3 -I scripts/build_site.py [--dmg CueComm.dmg] [--apk CueComm.apk] [--repo owner/name] [--base-url https://example.com/] [--version 1.0]

The two files decide the size and the SHA-256 printed on the page. Without --repo the download buttons point at docs/download/
(the files are copied there), so the folder works on any web host. With --repo they point at
https://github.com/<repo>/releases/latest/download/CueComm.dmg and .apk (upload the files as release assets with those exact names).
The look is in docs/assets/site.css and the behaviour in docs/assets/site.js; this script writes only the two HTML pages.
"""
import argparse, datetime, hashlib, math, os, random, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(ROOT, "docs")

ap = argparse.ArgumentParser()
ap.add_argument("--dmg", default=os.path.expanduser("~/Desktop/CueComm.dmg"))
ap.add_argument("--apk", default=os.path.expanduser("~/Desktop/CueComm.apk"))
ap.add_argument("--repo", default=os.environ.get("CUECOMM_GITHUB_REPO", ""))
ap.add_argument("--base-url", default=os.environ.get("CUECOMM_SITE_URL", ""))
ap.add_argument("--version", default="1.0")
ap.add_argument("--date", default="", help="release date YYYY-MM-DD (default: today)")
ap.add_argument("--sibling", default="https://the13thmoose.github.io/Stagetracks/")
args = ap.parse_args()

for f in (args.dmg, args.apk):
    if not os.path.exists(f): sys.exit(f"missing file: {f}")
dmg, apk = os.path.abspath(args.dmg), os.path.abspath(args.apk)
mb = lambda p: os.path.getsize(p) / 1_000_000
sha = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
dmg_mb, apk_mb, dmg_sha, apk_sha = mb(dmg), mb(apk), sha(dmg), sha(apk)
when = datetime.date.fromisoformat(args.date) if args.date else datetime.date.today()
version = args.version
repo = args.repo.strip().strip("/")
base = args.base_url.rstrip("/") + "/" if args.base_url else ""
if not repo:
    os.makedirs(os.path.join(SITE, "download"), exist_ok=True)
    shutil.copyfile(dmg, os.path.join(SITE, "download", "CueComm.dmg"))
    shutil.copyfile(apk, os.path.join(SITE, "download", "CueComm.apk"))
elif os.path.isdir(os.path.join(SITE, "download")):
    shutil.rmtree(os.path.join(SITE, "download"))

SECTIONS = ["why", "channels", "cues", "sessions", "install", "faq"]
FLAG_DEFAULT = {"why": 3.5, "channels": 14, "cues": 30, "sessions": 52, "install": 74, "faq": 90}
LANES = ["MO", "FO", "SC", "SL"]                       # the demo channels: Monitors, FOH, Show Caller, Stage Left
CUES = [(0, 9.5, "GO"), (2, 21, "STANDBY"), (2, 36, "GO"), (1, 52, "GO"), (3, 68, "STANDBY"), (0, 83, "GO")]

# ---------------------------------------------------------------- lanes (decoration: who spoke when, the same for every visit)
def lane_path(n, seed, w=1200, h=60):
    """Speech, not music: bursts of talking with silence between them."""
    rnd = random.Random(seed)
    pitch = w / n
    bursts, x = [], rnd.random() * .06
    while x < 1:
        ln = .03 + rnd.random() * .09
        bursts.append((x, x + ln, .35 + rnd.random() * .6))
        x += ln + .02 + rnd.random() * .12
    d = []
    for i in range(n):
        px = i * pitch + pitch / 2
        env = next((a for s, e, a in bursts if s <= px / w < e), 0.0)
        v = env * (.4 + .6 * rnd.random()) if env else .035 + .03 * rnd.random()
        hh = max(1.5, v * h / 2)
        d.append(f"M{px:.1f} {h/2-hh:.1f}V{h/2+hh:.1f}")
    return "".join(d)

DEFS = '<svg width="0" height="0" style="position:absolute" aria-hidden="true" focusable="false"><defs>' + "".join(
    f'<path id="ld{i}" vector-effect="non-scaling-stroke" d="{lane_path(260, 11 + i * 7)}"/>'
    f'<path id="ls{i}" vector-effect="non-scaling-stroke" d="{lane_path(90, 31 + i * 5)}"/>' for i in range(4)) + '</defs></svg>'

def lanes_html():
    out = '<div class="lanes" aria-hidden="true">'
    for i, ini in enumerate(LANES):
        out += (f'<div class="lane"><i class="lc">{ini}</i>'
                f'<svg class="r dense" viewBox="0 0 1200 60" preserveAspectRatio="none"><use href="#ld{i}"/></svg>'
                f'<svg class="r sparse" viewBox="0 0 1200 60" preserveAspectRatio="none"><use href="#ls{i}"/></svg>'
                f'<svg class="p dense" viewBox="0 0 1200 60" preserveAspectRatio="none"><use href="#ld{i}"/></svg>'
                f'<svg class="p sparse" viewBox="0 0 1200 60" preserveAspectRatio="none"><use href="#ls{i}"/></svg></div>')
    for lane, x, q in CUES:
        out += f'<b class="cf" data-q="{q}" style="left:{x}%;top:{lane * 25}%;height:25%"></b>'
    return out + "</div>"

LANES_HTML = lanes_html()

# ---------------------------------------------------------------- texts
def fmt_date(lang):
    if lang == "en": return when.strftime("%-d %b %Y")
    months = ["januar", "februar", "mars", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "desember"]
    return f"{when.day}. {months[when.month-1]} {when.year}"

def mbs(x): return f"{x:.0f} MB" if x >= 10 else f"{x:.1f} MB"

T = {
"en": dict(
    lang="en", title="CueComm: live crew chat, translation and cues for Mac and Android",
    desc="CueComm turns live speech on your production channels into a transcript, translates it and fires cues: OSC to your desk and a flash on every Mac and Android device in the session. Free beta.",
    skip="Skip to the content", sections_label="Sections", map_label="Session map", mini_label="Session map, current position",
    flags=dict(why="Why", channels="Channels", cues="Cues", sessions="Sessions", install="Install", faq="Questions"),
    flags_short=dict(why="Why", channels="Channels", cues="Cues", sessions="Sessions", install="Install", faq="FAQ"),
    nav_dl="Download",
    say_lab="Say", say_sub="Press to try", say_aria="Try it: fire a GO cue. The page flashes.",
    say_title="CUE · GO", say_detail="OSC /cue/go · Flash on every device in the session",
    h1="Say GO.<br>Every screen flashes.",
    lead="<b>CueComm</b> turns live speech on your production channels into a transcript the whole crew can read, translates it, and fires a cue when a chosen word is said: OSC to your desk and a flash on every Mac and Android device in the session.",
    key_mac="Mac", key_android="Android", key_dl="Download",
    reqs=["<b>Mac</b> with macOS 14 or later", "<b>Android</b> 8.0 or later", "<b>Free</b> during the beta", "<b>One session</b> for both"],
    ch_h="Every channel is a colour. Every word has a time.",
    ch_p=["Give every source its own channel: monitors, FOH, the show caller, a stage position. CueComm turns what is said on each one into a chat-style transcript: who said what, and when, in the channel’s own colour.",
          "Words you care about are marked in yellow. If one arrives while you are looking at another channel, that channel’s badge turns yellow with a flag, so a request is never missed."],
    ch_rows=[("A channel for every source", "Name it, colour it, pin it, group it. On a Mac each channel listens to its own input on your audio interface, with a live level meter to confirm you picked the right one. On Android a device speaks into one channel at a time."),
             ("Keywords", "Mark words per channel. Matches are highlighted in the transcript and flagged on the channel."),
             ("Translation", "Turn translation on per channel and the translation sits under the original. Apple’s Translation does it on a Mac with macOS 15 or later; on-device models do it on Android."),
             ("Type back", "Type a reply into any channel, and everyone sharing it sees it."),
             ("Export and share a setup", "Export a transcript as text or CSV. Save your channels, groups and cues as a setup file; the Mac and Android apps read the same file.")],
    mac_alt="The CueComm Mac app in a session: channels on the left, the transcript with keywords marked in yellow, a CUE · GO banner on top and the cue words panel on the right.",
    mac_cap="The Mac app in a demo session called Sunday Service, with a phone in it. The transcript is demo chatter made for this page.",
    cue_h="Say the word. OSC goes out, every screen flashes.",
    cue_p=["A cue word is a word with two jobs. When it is heard, CueComm sends an OSC message to a lighting desk or show-control system, and flashes the screen. Add words like GO and STANDBY, and set the OSC address and the colour of the flash.",
           "In a session the flash goes to every Mac and Android device, so the director sees the cue even when the word was said on another device. OSC goes out from the device that heard the word, so your desk gets one GO, not one for every device."],
    heard_ch="Show Caller", cue_label="CUE", idle_title="NO CUE",
    phrases=[("go lights forty two", "go", "go", "OSC /cue/go → 127.0.0.1:8000 · Flash on every device"),
             ("standby video", "standby", "standby", "OSC /cue/standby → 127.0.0.1:8000 · Flash on every device"),
             ("check one two, check", "", "", "No cue word in this line, so nothing fires.")],
    dev_names=["FOH Mac", "Monitor phone"],
    demo_note="Demo: what the apps do with a phrase. Press one to hear the cue arrive on both devices.",
    cue_rows=[("OSC out", "Send OSC over UDP to any host and port, with the address and arguments you choose. The text, the channel and the keyword can be part of the message."),
              ("OSC in", "Listen for incoming OSC and let it trigger the same actions. On a Mac, MIDI in and out work too."),
              ("Automations", "Triggers: a word heard, any message, an incoming OSC message (and MIDI on a Mac). Actions: send OSC (and MIDI on a Mac), flash, select a channel, scroll, clear, mark as read, change the text size."),
              ("Flash every device", "On by default for a flash; switch it off per cue. Each device can also choose to ignore flashes from the others.")],
    cue_alt="CueComm on an Android phone: a yellow CUE · GO banner reading From FOH Mac above the transcript.",
    cue_cap="The same cue on a phone in the session: the banner says who sent it. Demo data.",
    ses_h="One session. Macs and phones, on any network.",
    ses_p=["Host a session on any Mac or Android device. Devices on the same network find it by themselves: tap Join. Everyone sees the same channels and transcript, except channels you mark private, and a device that joins late catches up with the recent transcript.",
           "A device on another network or subnet joins by IP address: type the address of any device in the session under Join by IP address and you are in that session at once. No session name to type, no waiting. If the link drops, it reconnects by itself."],
    ses_rows=[("Same network", "Devices find each other automatically."),
              ("Another network", "Type the host’s address and port, for example 192.168.1.20:47800. The default port is 47800."),
              ("Over the internet", "Forward TCP port 47800 on the host’s router, or put the devices on a VPN such as Tailscale. Connections are not encrypted, so use a VPN on networks you do not trust."),
              ("Mac and Android together", "One session can mix both. Each device connects straight to every other one: there is no server, no account and no relay.")],
    sheet_alt="The Session sheet of the Mac app: Sunday Service is live with two devices, and an address field for adding a device on another network.",
    sheet_cap="The Mac’s Session sheet with a phone in the session.",
    join_alt="The Android Session screen with a session found nearby and the Join by IP address field filled in with an address.",
    join_cap="Join by IP address on a phone.",
    live_alt="The Android Session screen after joining: Sunday Service is live with the phone and a Mac, and the address shows Connected to FOH Mac.",
    live_cap="Joined: both devices are in the session.",
    install_h="Download CueComm",
    facts_mac=lambda: [("Version", f"v{version} beta"), ("Released", fmt_date("en")), ("File", f"CueComm.dmg, {mbs(dmg_mb)}"), ("Needs", "macOS 14 or later, Intel or Apple Silicon"), ("Price", "Free during the beta")],
    facts_and=lambda: [("Version", f"v{version} beta"), ("Released", fmt_date("en")), ("File", f"CueComm.apk, {mbs(apk_mb)}"), ("Needs", "Android 8.0 or later"), ("Price", "Free during the beta")],
    all_versions="All versions and changes", sha_label="SHA-256", copy="Copy", copied="Copied",
    mac_steps=[("Open the disk image", ["Double-click CueComm.dmg and drag CueComm into Applications."]),
               ("Open it once, past the macOS warning", ["CueComm is not notarised by Apple yet, so macOS says it cannot check the app.",
                  "<b>macOS 15 or later:</b> double-click CueComm and press Done. Open System Settings ▸ Privacy &amp; Security, scroll to Security and press <b>Open Anyway</b>. <b>macOS 14:</b> Control-click the app, choose Open, then Open. You do this once."]),
               ("Allow the microphone and the network", ["CueComm asks for the microphone and speech recognition, and for Local Network access to find other devices. The rest of the app works without them."])],
    and_steps=[("Open the file", ["Copy CueComm.apk to the phone, or download it there, and open it. Android asks you to allow installs from the app you opened it with (your browser or Files): allow it for this one install."]),
               ("Allow the microphone", ["CueComm asks for the microphone, and for notifications so a cue shows up while the screen is off."]),
               ("Join a session", ["Open the menu, then the session box. Tap a session found nearby, or type the host’s address under Join by IP address."])],
    terminal="Prefer Terminal? Remove the quarantine flag instead:",
    mac_why_h="Why the warning?", mac_why_p="Notarisation needs a paid Apple developer account, which this beta does not have yet. To check that the file is the one published here, compare its SHA-256 with the line on the left:",
    and_why_h="Why not Google Play?", and_why_p="The app is not on Google Play, so Android shows its warning for installing from outside the store. The file is signed with the maker’s own key. To check it, compare its SHA-256 with the line on the left:",
    faq_h="Questions",
    faq=[("Is it free?", ["Yes, while it is in beta. What happens after the beta will be announced here."]),
         ("Which devices does it run on?", ["Macs with macOS 14 or later, Intel or Apple Silicon, and Android phones and tablets with Android 8.0 or later. There is no iPhone or iPad version yet. This is a beta: so far it has been tested on an Apple Silicon Mac and in an Android 15 emulator, and real phones and Intel Macs are still to be tried."]),
         ("Do the devices have to be on the same network?", ["No. On one network they find each other. On different networks, one device types the other’s IP address and joins its session. Over the internet that needs a forwarded port or a VPN."]),
         ("Is the connection encrypted?", ["No. Sessions use plain TCP, with no server in between. On a network you do not trust, put the devices on a VPN such as Tailscale."]),
         ("Which audio interface can I use?", ["On a Mac, any CoreAudio interface: each channel listens to its own input. On Android, a phone listens through one microphone at a time, so it speaks into one channel; a USB or Bluetooth microphone that Android uses as its input works as usual."]),
         ("Which lighting or show-control systems does it work with?", ["Any system that accepts OSC over UDP. You choose the host, port, address and arguments for each cue."]),
         ("Does anything leave my device?", ["There is no CueComm server and no account. Transcripts and cues go only to the other devices in your session. On a Mac, speech recognition is set to run on the device wherever the language supports it; you can change that per channel. On Android, recognition prefers the offline language pack; if it is not installed, the phone falls back to Google’s online recogniser, which sends audio to Google. Translation language packs download once and then run on the device."]),
         ("Which languages are supported?", ["Speech: the languages your Mac’s or phone’s own speech recogniser supports; you pick one per channel. Translation: the languages Apple’s Translation (Mac) and the on-device translator (Android) offer. The apps themselves are in English; this page comes in English and Norwegian."]),
         ("Is the source code available?", ["Yes, on GitHub. No open-source licence has been chosen yet, so by default you may read it but not reuse it."])],
    faq_issue=("Something is wrong. Where do I report it?", "Open an issue on <a href=\"{url}\">GitHub</a>. Say which macOS or Android version you use and what you did."),
    foot_made="Made by Meltveit Lydproduksjon.", foot_demo="The channels, transcripts and screenshots on this page are demos made for it; no real crew’s conversation is shown.",
    foot_tm="macOS is a trademark of Apple Inc. Android is a trademark of Google LLC. CueComm is not affiliated with either.",
    logo_alt="Meltveit Lydproduksjon", releases="Releases", issues="Report a problem", source="Source code", home="CueComm home", lang_label="Language",
    sib_lab="Also from the same maker", sib_name="StageTracks", sib_desc="Multitrack backing tracks for live shows on a Mac.",
),
"no": dict(
    lang="no", title="CueComm: mannskapsprat, oversettelse og cuer for Mac og Android",
    desc="CueComm gjør live tale på produksjonskanalene dine om til tekst, oversetter den og utløser cuer: OSC til pulten din og et blink på alle Mac- og Android-enheter i økten. Gratis beta.",
    skip="Hopp til innholdet", sections_label="Seksjoner", map_label="Øktkart", mini_label="Øktkart, gjeldende posisjon",
    flags=dict(why="Hvorfor", channels="Kanaler", cues="Cuer", sessions="Økter", install="Last ned", faq="Spørsmål"),
    flags_short=dict(why="Hvorfor", channels="Kanaler", cues="Cuer", sessions="Økter", install="Last ned", faq="FAQ"),
    nav_dl="Last ned",
    say_lab="Si", say_sub="Trykk for å prøve", say_aria="Prøv selv: utløs en GO-cue. Siden blinker.",
    say_title="CUE · GO", say_detail="OSC /cue/go · Blink på alle enheter i økten",
    h1="Si GO.<br>Alle skjermer blinker.",
    lead="<b>CueComm</b> gjør live tale på produksjonskanalene dine om til en tekst hele mannskapet kan lese, oversetter den, og utløser en cue når et valgt ord blir sagt: OSC til pulten din og et blink på alle Mac- og Android-enheter i økten.",
    key_mac="Mac", key_android="Android", key_dl="Last ned",
    reqs=["<b>Mac</b> med macOS 14 eller nyere", "<b>Android</b> 8.0 eller nyere", "<b>Gratis</b> under betaen", "<b>Én økt</b> for begge"],
    ch_h="Hver kanal har sin farge. Hvert ord har et klokkeslett.",
    ch_p=["Gi hver kilde sin egen kanal: monitor, FOH, show caller, en posisjon på scenen. CueComm gjør det som sies på hver av dem om til en samtale i chatformat: hvem som sa hva, og når, i kanalens egen farge.",
          "Ord du bryr deg om markeres med gult. Kommer ett mens du ser på en annen kanal, blir merket på den kanalen gult med et flagg, så ingen forespørsel går tapt."],
    ch_rows=[("En kanal for hver kilde", "Gi den navn og farge, fest den, legg den i en gruppe. På Mac lytter hver kanal til sin egen inngang på lydgrensesnittet, med en levende nivåmåler som viser at du valgte riktig. På Android snakker en enhet inn i én kanal om gangen."),
             ("Nøkkelord", "Marker ord per kanal. Treff utheves i teksten og flagges på kanalen."),
             ("Oversettelse", "Slå på oversettelse per kanal, så står oversettelsen under originalen. På Mac gjør Apples Translation jobben med macOS 15 eller nyere; på Android gjør modeller på enheten den."),
             ("Skriv tilbake", "Skriv et svar i hvilken som helst kanal, så ser alle som deler den det."),
             ("Eksport og delte oppsett", "Eksporter en transkripsjon som tekst eller CSV. Lagre kanaler, grupper og cuer som en oppsettfil; Mac- og Android-appen leser den samme filen.")],
    mac_alt="CueComm Mac-appen i en økt: kanalene til venstre, teksten med nøkkelord markert med gult, et CUE · GO-banner øverst og panelet med cue-ord til høyre.",
    mac_cap="Mac-appen i en demoøkt som heter Sunday Service, med en telefon i økten. Samtalen er demoprat laget for denne siden.",
    cue_h="Si ordet. OSC går ut, alle skjermer blinker.",
    cue_p=["Et cue-ord er et ord med to jobber. Når det høres, sender CueComm en OSC-melding til en lyspult eller et show-kontrollsystem, og blinker skjermen. Legg til ord som GO og STANDBY, og velg OSC-adressen og fargen på blinket.",
           "I en økt går blinket til alle Mac- og Android-enheter, så regissøren ser cuen selv om ordet ble sagt på en annen enhet. OSC går ut fra enheten som hørte ordet, så pulten din får én GO, ikke én fra hver enhet."],
    heard_ch="Show Caller", cue_label="CUE", idle_title="INGEN CUE",
    phrases=[("go lights forty two", "go", "go", "OSC /cue/go → 127.0.0.1:8000 · Blink på alle enheter"),
             ("standby video", "standby", "standby", "OSC /cue/standby → 127.0.0.1:8000 · Blink på alle enheter"),
             ("check one two, check", "", "", "Ingen cue-ord i denne linjen, så ingenting utløses.")],
    dev_names=["FOH Mac", "Monitor phone"],
    demo_note="Demo: slik reagerer appene på en frase. Trykk på en for å høre cuen komme fram på begge enhetene.",
    cue_rows=[("OSC ut", "Send OSC over UDP til hvilken som helst vert og port, med adressen og argumentene du velger. Teksten, kanalen og nøkkelordet kan være med i meldingen."),
              ("OSC inn", "Lytt etter innkommende OSC og la det utløse de samme handlingene. På Mac fungerer også MIDI inn og ut."),
              ("Automatiseringer", "Utløsere: et ord som høres, en hvilken som helst melding, en innkommende OSC-melding (og MIDI på Mac). Handlinger: send OSC (og MIDI på Mac), blink, velg en kanal, bla, tøm, marker som lest, endre tekststørrelsen."),
              ("Blink på alle enheter", "Er på som standard for et blink; slå det av per cue. Hver enhet kan også velge å ignorere blink fra de andre.")],
    cue_alt="CueComm på en Android-telefon: et gult CUE · GO-banner med teksten Fra FOH Mac over samtalen.",
    cue_cap="Den samme cuen på en telefon i økten: banneret sier hvem som sendte den. Demodata.",
    ses_h="Én økt. Mac og telefon, på hvilket som helst nettverk.",
    ses_p=["Lag en økt på en hvilken som helst Mac- eller Android-enhet. Enheter på samme nettverk finner den selv: trykk Bli med. Alle ser de samme kanalene og samtalen, bortsett fra kanaler du markerer som private, og en enhet som kommer sent får med seg den nyeste samtalen.",
           "En enhet på et annet nettverk eller subnett blir med via IP-adresse: skriv adressen til en hvilken som helst enhet i økten under Bli med via IP-adresse, så er du i den økten med en gang. Ikke noe øktnavn å skrive, ingen venting. Brytes forbindelsen, kobler den seg til igjen av seg selv."],
    ses_rows=[("Samme nettverk", "Enhetene finner hverandre automatisk."),
              ("Et annet nettverk", "Skriv vertens adresse og port, for eksempel 192.168.1.20:47800. Standardporten er 47800."),
              ("Over internett", "Videresend TCP-port 47800 i ruteren til verten, eller legg enhetene på et VPN som Tailscale. Forbindelsene er ikke kryptert, så bruk VPN på nettverk du ikke stoler på."),
              ("Mac og Android sammen", "Én økt kan blande begge. Hver enhet kobler seg rett til alle de andre: ingen tjener, ingen konto og ingen mellomledd.")],
    sheet_alt="Øktvinduet i Mac-appen: Sunday Service er live med to enheter, og et adressefelt for å legge til en enhet på et annet nettverk.",
    sheet_cap="Mac-appens øktvindu med en telefon i økten.",
    join_alt="Android-skjermen for økter, med en økt funnet i nærheten og feltet Bli med via IP-adresse utfylt med en adresse.",
    join_cap="Bli med via IP-adresse på en telefon.",
    live_alt="Android-skjermen for økter etter at telefonen ble med: Sunday Service er live med telefonen og en Mac, og adressen viser Tilkoblet FOH Mac.",
    live_cap="Med i økten: begge enhetene er der.",
    install_h="Last ned CueComm",
    facts_mac=lambda: [("Versjon", f"v{version} beta"), ("Utgitt", fmt_date("no")), ("Fil", f"CueComm.dmg, {mbs(dmg_mb)}"), ("Krever", "macOS 14 eller nyere, Intel eller Apple Silicon"), ("Pris", "Gratis under betaen")],
    facts_and=lambda: [("Versjon", f"v{version} beta"), ("Utgitt", fmt_date("no")), ("Fil", f"CueComm.apk, {mbs(apk_mb)}"), ("Krever", "Android 8.0 eller nyere"), ("Pris", "Gratis under betaen")],
    all_versions="Alle versjoner og endringer", sha_label="SHA-256", copy="Kopier", copied="Kopiert",
    mac_steps=[("Åpne diskbildet", ["Dobbeltklikk CueComm.dmg og dra CueComm til Programmer."]),
               ("Åpne den første gang, forbi macOS-advarselen", ["CueComm er ikke notarisert av Apple ennå, så macOS sier at den ikke kan sjekke appen.",
                  "<b>macOS 15 eller nyere:</b> dobbeltklikk CueComm og trykk Ferdig. Åpne Systeminnstillinger ▸ Personvern og sikkerhet, bla til Sikkerhet og trykk <b>Åpne likevel</b>. <b>macOS 14:</b> Ctrl-klikk på appen, velg Åpne, og deretter Åpne. Du gjør dette bare én gang."]),
               ("Tillat mikrofonen og nettverket", ["CueComm ber om mikrofonen og talegjenkjenning, og om tilgang til lokalt nettverk for å finne andre enheter. Resten av appen virker uten."])],
    and_steps=[("Åpne filen", ["Kopier CueComm.apk til telefonen, eller last den ned der, og åpne den. Android ber deg tillate installasjon fra appen du åpnet den med (nettleseren eller Filer): tillat det for denne ene installasjonen."]),
               ("Tillat mikrofonen", ["CueComm ber om mikrofonen, og om varsler så en cue vises mens skjermen er av."]),
               ("Bli med i en økt", ["Åpne menyen og så øktboksen. Trykk på en økt funnet i nærheten, eller skriv vertens adresse under Bli med via IP-adresse."])],
    terminal="Foretrekker du Terminal? Fjern karantene-merket i stedet:",
    mac_why_h="Hvorfor advarselen?", mac_why_p="Notarisering krever en betalt Apple-utviklerkonto, som denne betaen ikke har ennå. For å sjekke at filen er den som er lagt ut her, sammenlign SHA-256 med linjen til venstre:",
    and_why_h="Hvorfor ikke Google Play?", and_why_p="Appen ligger ikke på Google Play, så Android viser advarselen for installasjon utenfra butikken. Filen er signert med produsentens egen nøkkel. For å sjekke den, sammenlign SHA-256 med linjen til venstre:",
    faq_h="Spørsmål",
    faq=[("Er den gratis?", ["Ja, så lenge den er i beta. Hva som skjer etter betaen, kunngjøres her."]),
         ("Hvilke enheter går den på?", ["Mac med macOS 14 eller nyere, Intel eller Apple Silicon, og Android-telefoner og -nettbrett med Android 8.0 eller nyere. Det finnes ingen versjon for iPhone eller iPad ennå. Dette er en beta: så langt er den testet på en Mac med Apple Silicon og i en Android 15-emulator, og ekte telefoner og Mac med Intel gjenstår."]),
         ("Må enhetene være på samme nettverk?", ["Nei. På ett nettverk finner de hverandre. På ulike nettverk skriver én enhet IP-adressen til den andre og blir med i økten. Over internett trenger det en videresendt port eller et VPN."]),
         ("Er forbindelsen kryptert?", ["Nei. Øktene bruker vanlig TCP, uten tjener imellom. På et nettverk du ikke stoler på, legg enhetene på et VPN som Tailscale."]),
         ("Hvilket lydgrensesnitt kan jeg bruke?", ["På Mac, alle CoreAudio-grensesnitt: hver kanal lytter til sin egen inngang. På Android lytter en telefon gjennom én mikrofon om gangen, så den snakker inn i én kanal; en USB- eller Bluetooth-mikrofon som Android bruker som inngang virker som vanlig."]),
         ("Hvilke lys- eller show-kontrollsystemer virker den med?", ["Alle systemer som tar imot OSC over UDP. Du velger vert, port, adresse og argumenter for hver cue."]),
         ("Forlater noe enheten min?", ["Det finnes ingen CueComm-tjener og ingen konto. Samtaler og cuer går bare til de andre enhetene i økten din. På Mac er talegjenkjenningen satt til å kjøre på enheten der språket støtter det; du kan endre det per kanal. På Android foretrekker gjenkjenningen den frakoblede språkpakken; er den ikke installert, faller telefonen tilbake til Googles nettbaserte gjenkjenner, som sender lyd til Google. Språkpakker for oversettelse lastes ned én gang og kjører så på enheten."]),
         ("Hvilke språk støttes?", ["Tale: språkene Macens eller telefonens egen talegjenkjenner støtter; du velger ett per kanal. Oversettelse: språkene Apples Translation (Mac) og oversetteren på enheten (Android) tilbyr. Selve appene er på engelsk; denne siden finnes på engelsk og norsk."]),
         ("Er kildekoden tilgjengelig?", ["Ja, på GitHub. Ingen åpen kildekode-lisens er valgt ennå, så som standard kan du lese den, men ikke gjenbruke den."])],
    faq_issue=("Noe er galt. Hvor melder jeg fra?", "Åpne en sak på <a href=\"{url}\">GitHub</a>. Si hvilken macOS- eller Android-versjon du bruker og hva du gjorde."),
    foot_made="Laget av Meltveit Lydproduksjon.", foot_demo="Kanalene, samtalene og skjermbildene på denne siden er demoer laget for den; ingen virkelig samtale blant et mannskap vises.",
    foot_tm="macOS er et varemerke som tilhører Apple Inc. Android er et varemerke som tilhører Google LLC. CueComm er ikke tilknyttet noen av dem.",
    logo_alt="Meltveit Lydproduksjon", releases="Utgivelser", issues="Meld fra om et problem", source="Kildekode", home="CueComm forside", lang_label="Språk",
    sib_lab="Også fra samme produsent", sib_name="StageTracks", sib_desc="Multitracks og backingtracks for live bruk på Mac.",
),
}

# ---------------------------------------------------------------- html pieces
def songmap(t, cls, label):
    fl = ""
    for i, s in enumerate(SECTIONS):
        f = FLAG_DEFAULT[s]
        bar = 1 + round(f / 100 * 95); secs = round((bar - 1) * 2.4)
        flip = " flip" if f > 86 else ""
        on = " on" if i == 0 else ""
        fl += (f'<a class="fl{flip}{on}" href="#{s}" data-f="{s}" title="{t["flags"][s]}" style="--f:{f}%"><em>{t["flags_short"][s]}</em>'
               f'<small data-bar="Q">Q{bar} · {secs//60}:{secs%60:02d}</small></a>')
    return f'<nav class="map {cls}" aria-label="{label}">{LANES_HTML}{fl}<div class="ph"></div></nav>'

def part(sid, t, inner, h2):
    f = FLAG_DEFAULT[sid]; bar = 1 + round(f / 100 * 95); secs = round((bar - 1) * 2.4)
    return (f'<section class="part" id="{sid}" data-part="{sid}" aria-labelledby="{sid}-h"><div class="in">'
            f'<div class="gut"><span class="flag">{t["flags"][sid]}</span><span class="pos" data-bar="Q">Q{bar} · {secs//60}:{secs%60:02d}</span></div>'
            f'<div class="main"><h2 id="{sid}-h">{h2}</h2>{inner}</div></div></section>')

def rows(items): return '<dl class="rows">' + "".join(f"<div><dt>{a}</dt><dd>{b}</dd></div>" for a, b in items) + "</dl>"

def img(src, alt, w, h, lazy=True, pan=True):
    ll = ' loading="lazy" decoding="async"' if lazy else ""
    tag = f'<img src="{src}" alt="{alt}" width="{w}" height="{h}"{ll}>'
    return f'<div class="pan">{tag}</div>' if pan else tag

def page(lang):
    t = T[lang]; other = "no" if lang == "en" else "en"
    A = "assets/" if lang == "en" else "../assets/"
    home_rel = "./" if lang == "en" else "../"
    if repo:
        dl_mac = f"https://github.com/{repo}/releases/latest/download/CueComm.dmg"
        dl_and = f"https://github.com/{repo}/releases/latest/download/CueComm.apk"
    else:
        dl_mac, dl_and = home_rel + "download/CueComm.dmg", home_rel + "download/CueComm.apk"
    releases_url = f"https://github.com/{repo}/releases" if repo else ""
    nav = "".join(f'<a href="#{s}">{t["flags"][s]}</a>' for s in SECTIONS)
    cur = ' aria-current="true"'
    mini = songmap(t, "mini", t["mini_label"])
    head = (f'<header class="top"><div class="top-in"><a class="brand" href="{home_rel}" aria-label="{t["home"]}"><img src="{A}img/favicon-64.png" alt="" width="28" height="28"><span>CUECOMM</span></a>'
            f'<div class="slot"><nav class="nav" aria-label="{t["sections_label"]}">{nav}</nav>{mini}</div>'
            f'<div class="tools"><div class="lang" role="group" aria-label="{t["lang_label"]}">'
            f'<a href="{home_rel if lang == "en" else "../"}" hreflang="en" lang="en"{cur if lang == "en" else ""}>EN</a>'
            f'<a href="{"no/" if lang == "en" else "./"}" hreflang="no" lang="no"{cur if lang == "no" else ""}>NO</a></div>'
            f'<a class="btn dl-btn" href="#install">{t["nav_dl"]}</a></div></div></header>')
    keys = (f'<div class="keys"><a class="key" href="{dl_mac}" data-os="mac"><b>{t["key_mac"].upper()}</b><span>{t["key_dl"]} · v{version}</span><span>.dmg · {mbs(dmg_mb)}</span></a>'
            f'<a class="key" href="{dl_and}" data-os="android"><b>{t["key_android"].upper()}</b><span>{t["key_dl"]} · v{version}</span><span>.apk · {mbs(apk_mb)}</span></a></div>')
    hero = (f'<section class="hero" id="why" data-part="why" aria-label="CueComm"><div class="call">'
            f'<button class="nx" type="button" data-fire data-title="{t["say_title"]}" data-detail="{t["say_detail"]}" aria-label="{t["say_aria"]}">'
            f'<span class="lab">{t["say_lab"]}</span><span class="cue">GO</span><span class="lab">{t["say_sub"]}</span></button>'
            f'<div class="mid"><h1>{t["h1"]}</h1><p class="lead">{t["lead"]}</p></div>{keys}</div>'
            f'{songmap(t, "hero-map", t["map_label"])}'
            f'<ul class="reqs">{"".join(f"<li>{r}</li>" for r in t["reqs"])}</ul></section>')
    # channels
    ch_html = "".join(f"<p>{p}</p>" for p in t["ch_p"]) + rows(t["ch_rows"]) + \
        f'<figure class="shot">{img(A + "img/app-mac.png", t["mac_alt"], 2200, 1224)}<figcaption>{t["mac_cap"]}</figcaption></figure>'
    # cues
    chips = ""
    for i, (said, hit, cue, det) in enumerate(t["phrases"]):
        chips += (f'<button class="chip" type="button" aria-pressed="{"true" if i == 0 else "false"}" data-text="{said}" data-hit="{hit}" data-cue="{cue}" data-detail="{det}">{said}</button>')
    devs = "".join(f'<div class="dev"><i></i>{n}</div>' for n in t["dev_names"])
    demo = (f'<div class="demo" data-demo data-cue-label="{t["cue_label"]}" data-idle-title="{t["idle_title"]}">'
            f'<div class="heard"><span class="hue">{t["heard_ch"]}</span><q data-heard></q></div>'
            f'<div class="out"><div class="banner" data-state="idle" aria-live="polite"><div><span class="t">{t["idle_title"]}</span><span class="d"></span></div></div>'
            f'<div class="devs">{devs}</div></div>'
            f'<div class="chips" role="group" aria-label="{t["heard_ch"]}">{chips}</div><p class="demo-note">{t["demo_note"]}</p></div>')
    cue_rows = rows(t["cue_rows"]).replace('class="rows"', 'class="rows" style="margin-top:0"')
    cue_html = ("".join(f"<p>{p}</p>" for p in t["cue_p"]) + demo +
                f'<div class="split"><div>{cue_rows}</div>'
                f'<div class="phones"><figure>{img(A + "img/phone-cue.jpg", t["cue_alt"], 750, 1667, pan=False)}<figcaption>{t["cue_cap"]}</figcaption></figure></div></div>')
    # sessions
    ses_html = ("".join(f"<p>{p}</p>" for p in t["ses_p"]) + rows(t["ses_rows"]) +
                f'<div class="phones trio"><figure style="flex:1.06 1 0">{img(A + "img/app-mac-session.png", t["sheet_alt"], 1240, 1170, pan=False)}<figcaption>{t["sheet_cap"]}</figcaption></figure>'
                f'<figure style="flex:.45 1 0">{img(A + "img/phone-join.jpg", t["join_alt"], 750, 1667, pan=False)}<figcaption>{t["join_cap"]}</figcaption></figure>'
                f'<figure style="flex:.45 1 0">{img(A + "img/phone-live.jpg", t["live_alt"], 750, 1667, pan=False)}<figcaption>{t["live_cap"]}</figcaption></figure></div>')
    # install
    def facts(items): return "".join(f"<div><dt>{a}</dt><dd>{b}</dd></div>" for a, b in items)
    def steps(items): return "".join(f"<li><div><h3>{h}</h3>{''.join(f'<p>{p}</p>' for p in ps)}</div></li>" for h, ps in items)
    def copy_row(cid, text): return f'<div class="code"><code id="{cid}">{text}</code><button class="btn" type="button" data-copy="{cid}" data-done="{t["copied"]}">{t["copy"]}</button></div>'
    xattr = "xattr -dr com.apple.quarantine /Applications/CueComm.app"
    mac_block = (f'<div class="plat"><h3>Mac</h3><div><dl class="facts">{facts(t["facts_mac"]())}</dl>'
                 f'<a class="key xl" href="{dl_mac}"><b>MAC</b><span>{t["key_dl"]} · v{version}</span><span>.dmg · {mbs(dmg_mb)}</span></a>'
                 + (f'<p class="dim" style="margin-top:16px"><a href="{releases_url}">{t["all_versions"]}</a></p>' if repo else "")
                 + f'<div class="sha"><span class="lab">{t["sha_label"]} · CueComm.dmg</span>{copy_row("sha-mac", dmg_sha)}</div></div>'
                 f'<div><ol class="steps">{steps(t["mac_steps"])}</ol><div class="why"><p>{t["terminal"]}</p>{copy_row("xattr", xattr)}'
                 f'<h3 style="margin-top:28px">{t["mac_why_h"]}</h3><p>{t["mac_why_p"]}</p>{copy_row("shacmd-mac", "shasum -a 256 ~/Downloads/CueComm.dmg")}</div></div></div>')
    and_block = (f'<div class="plat"><h3>Android</h3><div><dl class="facts">{facts(t["facts_and"]())}</dl>'
                 f'<a class="key xl" href="{dl_and}"><b>ANDROID</b><span>{t["key_dl"]} · v{version}</span><span>.apk · {mbs(apk_mb)}</span></a>'
                 f'<div class="sha"><span class="lab">{t["sha_label"]} · CueComm.apk</span>{copy_row("sha-and", apk_sha)}</div></div>'
                 f'<div><ol class="steps">{steps(t["and_steps"])}</ol><div class="why">'
                 f'<h3>{t["and_why_h"]}</h3><p>{t["and_why_p"]}</p>{copy_row("shacmd-and", "shasum -a 256 CueComm.apk")}</div></div></div>')
    install_html = mac_block + and_block
    # faq
    faq = "".join(f'<details><summary>{q}<i aria-hidden="true"></i></summary><div class="ans">{"".join(f"<p>{p}</p>" for p in a)}</div></details>' for q, a in t["faq"])
    if repo:
        q, a = t["faq_issue"]
        faq += f'<details><summary>{q}<i aria-hidden="true"></i></summary><div class="ans"><p>{a.format(url=f"https://github.com/{repo}/issues")}</p></div></details>'
    faq_html = f'<div class="faq">{faq}</div>'
    links = ""
    if repo: links = (f'<ul><li><a href="{releases_url}">{t["releases"]}</a></li><li><a href="https://github.com/{repo}">{t["source"]}</a></li>'
                      f'<li><a href="https://github.com/{repo}/issues">{t["issues"]}</a></li></ul>')
    foot = (f'<footer class="foot"><div class="foot-in"><img src="{A}img/meltveit-logo.png" alt="{t["logo_alt"]}" width="900" height="225" loading="lazy">'
            f'<div><p>{t["foot_made"]} © {when.year}</p><p>{t["foot_demo"]}</p><p>{t["foot_tm"]}</p></div>{links}'
            f'<div class="sibling"><span class="lab">{t["sib_lab"]}</span><a href="{args.sibling}">{t["sib_name"].upper()}</a><span>{t["sib_desc"]}</span></div></div></footer>')
    canon = f'<link rel="canonical" href="{base}{"" if lang == "en" else "no/"}">' if base else ""
    og = (f'<meta property="og:title" content="{t["title"]}"><meta property="og:description" content="{t["desc"]}"><meta property="og:type" content="website">'
          + (f'<meta property="og:image" content="{base}assets/img/og.png"><meta name="twitter:card" content="summary_large_image">' if base and os.path.exists(os.path.join(SITE, "assets/img/og.png")) else ""))
    alt = (f'<link rel="alternate" hreflang="{other}" href="{base}{"no/" if lang == "en" else ""}">' if base else
           f'<link rel="alternate" hreflang="{other}" href="{"no/" if lang == "en" else "../"}">')
    html = f'''<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{t["title"]}</title>
<meta name="description" content="{t["desc"]}">
<meta name="theme-color" content="#09090B">
<meta name="color-scheme" content="dark">
{canon}{alt}{og}
<link rel="icon" type="image/png" sizes="32x32" href="{A}img/favicon-32.png">
<link rel="apple-touch-icon" href="{A}img/apple-touch-icon.png">
<link rel="preload" href="{A}fonts/barlow-condensed-700.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="{A}site.css">
</head>
<body>
<a class="skip" href="#main">{t["skip"]}</a>
{DEFS}
{head}
<div class="flash" aria-hidden="true"></div>
<div class="cuebar" role="status"><b></b><span></span></div>
<main id="main">
{hero}
{part("channels", t, ch_html, t["ch_h"])}
{part("cues", t, cue_html, t["cue_h"])}
{part("sessions", t, ses_html, t["ses_h"])}
{part("install", t, install_html, t["install_h"])}
{part("faq", t, faq_html, t["faq_h"])}
</main>
{foot}
<script src="{A}site.js" defer></script>
</body>
</html>
'''
    out = os.path.join(SITE, "index.html" if lang == "en" else "no/index.html")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w").write(html)
    return out

for l in ("en", "no"):
    print("wrote", page(l))
print(f"version {version} · dmg {dmg_mb:.1f} MB {dmg_sha[:12]}… · apk {apk_mb:.1f} MB {apk_sha[:12]}… · {'GitHub releases: ' + repo if repo else 'files copied to docs/download/'}")
