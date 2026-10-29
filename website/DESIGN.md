---
name: CueComm download site
description: A live session on a page: matte black, a hue per channel, orange to download, green for live, yellow for cues.
colors:
  ground: "#09090B"
  panel: "#131318"
  raised: "#1C1C23"
  hairline: "#2C2C35"
  ink: "#F2F2F6"
  dim: "#C7CAD4"
  ghost: "#9EA3B0"
  action-orange: "#FF9F1C"
  live-green: "#39FF88"
  cue-yellow: "#FFE14D"
  channel-coral: "#FF5F4A"
  channel-pink: "#FF4FA3"
  channel-magenta: "#E23DFF"
  channel-violet: "#8F7BFF"
  channel-blue: "#4D8DFF"
  channel-cyan: "#35D8FF"
  channel-teal: "#22C7B8"
  channel-periwinkle: "#A8B4FF"
typography:
  display:
    fontFamily: "PB Display, DIN Condensed, Barlow Condensed, Arial Narrow, sans-serif"
    fontSize: "clamp(52px, 7.4vw, 96px)"
    fontWeight: 700
    lineHeight: 0.94
    letterSpacing: "-0.005em"
  headline:
    fontFamily: "PB Display, DIN Condensed, Barlow Condensed, Arial Narrow, sans-serif"
    fontSize: "clamp(40px, 5.2vw, 72px)"
    fontWeight: 700
    lineHeight: 0.98
  title:
    fontFamily: "PB Display, DIN Condensed, Barlow Condensed, Arial Narrow, sans-serif"
    fontSize: "clamp(26px, 2.6vw, 34px)"
    fontWeight: 700
    lineHeight: 1.05
    letterSpacing: "0.01em"
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, SF Pro Text, system-ui, Segoe UI, Roboto, Helvetica Neue, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "PB Label, Avenir Next Condensed, Barlow Condensed, Arial Narrow, sans-serif"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "0.12em"
  code:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.5
rounded:
  sm: "3px"
  chip: "4px"
  frame: "12px"
spacing:
  gutter: "clamp(16px, 4vw, 56px)"
  header: "56px"
  row: "14px"
  section: "clamp(64px, 8vw, 112px)"
components:
  key:
    backgroundColor: "{colors.action-orange}"
    textColor: "#0B0B0E"
    typography: "{typography.display}"
    padding: "18px 16px"
  key-hover:
    backgroundColor: "{colors.action-orange}"
    textColor: "#0B0B0E"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "9px 12px"
  button-download:
    backgroundColor: "transparent"
    textColor: "{colors.action-orange}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "9px 12px"
  flag:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    padding: "5px 8px"
  flag-current:
    backgroundColor: "{colors.live-green}"
    textColor: "#0B0B0E"
  cue-flag:
    backgroundColor: "{colors.cue-yellow}"
    textColor: "#0B0B0E"
    padding: "3px 5px"
  cue-banner:
    backgroundColor: "{colors.cue-yellow}"
    textColor: "#0B0B0E"
    typography: "{typography.title}"
    rounded: "{rounded.sm}"
    padding: "12px 18px"
  chip:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "10px 14px"
  chip-pressed:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink}"
  channel-chip:
    textColor: "#0B0B0E"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "5px 8px"
---

# Design System: CueComm download site

## Overview

**Creative North Star: "The Live Session"**

The page is a session, not a stack of sections. A crew member lands on a call line (SAY / headline / two download keys) and, beneath it, four lanes of speech, one hue per channel, with yellow cue flags and a green playhead. Scrolling moves the playhead along the lanes; the sections are flags on that map. It is the same skeleton as the StageTracks download site (call line, map as navigation, scroll as playhead, gutter flags) so the two read as one maker's work, but it lives in CueComm's own world: matte black like a dim FOH desk, with colour doing jobs rather than decorating.

The ground is the app's matte black. Colour is allotted by role and never mixed: hues belong to channels, orange to the act of downloading, green to anything live, yellow to cues. Nothing glows, casts a shadow or fades into a gradient. The page is read in dark rooms at arm's length, so the type is large, the contrast high and the surfaces flat.

**Key Characteristics:**
- Dark by use scene: FOH positions, monitor world and show-caller desks are dim, so the ground is #09090B and text never pure white.
- One colour per job: channel hues, orange action, green live, yellow cue.
- Condensed display type for anything read at a glance; a system face for sentences.
- Flat: hairlines and 3px corners, no shadow, glow or gradient.
- Real renders only, of demo data, and labelled as demos.

## Colors

The palette is the app's, used with the same role discipline.

### Primary
- **Meltveit Orange** (#FF9F1C): the download keys and the header download button. It is the brand's own orange and the only colour that means "take the action".

### Secondary
- **Live Green** (#39FF88): live state only: the playhead, the current section flag, the nav underline, the device dots in the cue demo.

### Tertiary
- **Cue Yellow** (#FFE14D): a cue and nothing else: cue flags on the lanes, keyword marks, the cue banner and the page flash.

### Neutral
- **Matte Booth Black** (#09090B): the page. **Rail Black** (#131318): footer and panels. **Lifted Black** (#1C1C23): flags, chips at rest, language switch. **Rule Grey** (#2C2C35): every hairline.
- **Cue-Sheet White** (#F2F2F6): text. **Soft White** (#C7CAD4): secondary text, 12:1 on the ground. **Floor Grey** (#9EA3B0): the lowest text colour allowed, 7.8:1.

### Channel hues
Coral #FF5F4A, Pink #FF4FA3, Magenta #E23DFF, Violet #8F7BFF, Blue #4D8DFF, Cyan #35D8FF, Teal #22C7B8, Periwinkle #A8B4FF. The page uses four as lanes (coral, pink, magenta, cyan) and magenta again on the "Show Caller" chip in the demo.

**The Three Roles Rule.** Orange, green and yellow each keep one job (action, live, cue). A yellow section flag, an orange playhead or a green cue would break the grammar the apps teach.
**The Lane Rule.** Channel hues appear only as lane waveforms, lane chips and channel chips. They never colour a heading, a button or a divider.

## Typography

**Display Font:** PB Display, i.e. DIN Condensed where installed (every Mac, iPad and iPhone), otherwise the self-hosted Barlow Condensed 700 (SIL Open Font License).
**Label Font:** PB Label, Avenir Next Condensed where installed, otherwise Barlow Condensed 500/600.
**Body Font:** the system sans stack.
**Mono:** the system monospace, only for checksums and commands.

**Character:** tall, narrow and plain, like cue-sheet lettering. Headlines run at 96/72px with tight leading and balanced wrapping; labels are small caps-style condensed with wide tracking.

### Hierarchy
- **Display** (700, clamp 52-96px, 0.94): the hero headline.
- **Headline** (700, clamp 40-72px, 0.98): section headings, kept to about 20 characters per line.
- **Title** (700, clamp 26-34px): row terms, step headings, FAQ questions at 24-30px.
- **Body** (400, 17px, 1.55): sentences, 64ch measure.
- **Label** (600, 13-15px, 0.12em, uppercase): flags, nav, fact names, buttons.

**The Display Face Rule.** Anything read at a glance is the condensed display face. A system sans is never the display voice.

## Layout

A 1440px maximum with a fluid gutter. Each section is a two-column grid: a 180px sticky gutter holding the section flag and its map position (Q-number and time), and the content beside it. The hero is a three-part call line (224px SAY block, headline, 260px key column) above the lane map and a requirements row. Rows are hairline-ruled definition lists: term left, text right, collapsing to one column in half-width splits and on phones.

Below 900px the sticky gutter becomes a small pennant at the section's top edge, the nav collapses into the mini map in the header, the keys sit side by side, the dense lane waveforms swap for sparse ones and cue labels shrink to marks. The Install section stacks two platform blocks, each facts and key on the left and steps on the right.

**The Playhead Rule.** Scroll position is the playhead on the lanes; section flags are placed by where the sections really are, never evenly. Everything stays readable and navigable with the script off.

## Elevation & Depth

Flat by design. Depth comes from tonal steps (ground, rail, lifted) and one-pixel rules. There are no drop shadows, glows or blurs. The only layered elements are the cue bar (a yellow strip that slides in under the header) and the page flash (a 14px yellow frame with a faint wash, four pulses, one under reduced motion); both are state, not decoration.

**The Flat Rule.** If something needs to stand out, change its role colour or its type, not its shadow.

## Shapes

3px corners on every control, panel and screenshot frame; 4px on channel chips; 12px on phone screenshots, which are the apps' own screens. Flags are plain rectangles with an inset hairline. The phone-width section flag is a pennant clipped from a rectangle. There are no pills and no circles except the 8px live dots.

## Components

### Download key
A solid orange block with the platform name in display type (44-76px) and a two-line label. No rounding in the hero (it fills the call line's right column); 3px when it stands alone in Install. Hover draws an inset ink outline. Android shows first on an Android phone.

### Flag and playhead
Section flags: lifted black with an inset grey hairline and a label; the current one turns live green. The playhead is a 2px green line with a small triangle. Cue flags are yellow ticks on a lane with a tiny yellow label.

### Cue banner and flash
The page's version of the app's banner: yellow, near-black text, display type, 3px. It slides in under the header for about four seconds and announces itself politely to screen readers. The flash is non-interactive and never fires without a click.

### Demo
A bordered panel with a transcript line (a magenta channel chip, the keyword marked in yellow), an output strip (banner left, device list right) and phrase chips. It shows what the apps do with a phrase and says so.

### Facts, steps and code rows
Hairline-ruled definition rows; steps numbered in 44px display figures; commands and checksums in monospace with a quiet Copy button.

### Questions
Native disclosure elements, display-face questions, a drawn plus that turns minus.

## Do's and Don'ts

### Do:
- **Do** keep orange for downloading, green for live and yellow for cues, and nothing else.
- **Do** show real renders of the apps, labelled as demos, with synthetic data only.
- **Do** state limits next to claims: Android speaks into one channel, there is no iPhone version, the connection is not encrypted.
- **Do** keep the page working without script: links, anchors, forms of disclosure and downloads are plain HTML.
- **Do** keep the skeleton shared with the StageTracks site when adding a section.

### Don't:
- **Don't** add shadows, glows, blurs or gradients.
- **Don't** put a channel hue on a heading, button or rule.
- **Don't** use a system font for display type.
- **Don't** invent prices, testimonials, download counts, contacts or licences.
- **Don't** fire a flash without a user action, and always honour reduced motion.
- **Don't** pad the page with equal cards of icon, heading and text.
