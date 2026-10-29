---
name: CueComm
description: Live-production voice transcript where every channel is a hue on matte black and speaking decodes to colour.
colors:
  ground: "#09090B"
  panel: "#131318"
  raised: "#1C1C23"
  hairline: "#2C2C35"
  text: "#FFFFFF"
  text-dim: "#C7CAD4"
  text-faint: "#9EA3B0"
  ink: "#0B0B0E"
  action-orange: "#FF9F1C"
  live-green: "#39FF88"
  cue-yellow: "#FFE14D"
  danger-coral: "#FF5F4A"
  channel-coral: "#FF5F4A"
  channel-pink: "#FF4FA3"
  channel-magenta: "#E23DFF"
  channel-violet: "#8F7BFF"
  channel-blue: "#4D8DFF"
  channel-cyan: "#35D8FF"
  channel-teal: "#22C7B8"
  channel-periwinkle: "#A8B4FF"
typography:
  title:
    fontFamily: "SF Pro (system sans)"
    fontSize: "26-34pt"
    fontWeight: 700
  transcript:
    fontFamily: "SF Pro (system sans)"
    fontSize: "22pt default, user-adjustable (store.settings.fontSize)"
    fontWeight: 500
  section:
    fontFamily: "SF Pro (system sans)"
    fontSize: "17pt"
    fontWeight: 600
  body:
    fontFamily: "SF Pro (system sans)"
    fontSize: "13-16pt"
    fontWeight: 400
  chip:
    fontFamily: "SF Pro (system sans)"
    fontSize: "13pt"
    fontWeight: 800
    letterSpacing: "0.8pt"
rounded:
  chip: "6px"
  button-quiet: "10px"
  button-action: "11px"
  card: "12px"
  message: "14px"
  hue-block: "24% of block size"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "14px"
  xl: "24px"
components:
  button-action:
    backgroundColor: "{colors.action-orange}"
    textColor: "{colors.ink}"
    rounded: "{rounded.button-action}"
    padding: "9px 16px"
  button-action-disabled:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text-faint}"
  button-quiet:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    rounded: "{rounded.button-quiet}"
    padding: "8px 13px"
  button-quiet-hover:
    backgroundColor: "{colors.hairline}"
  hue-chip:
    textColor: "{colors.ink}"
    rounded: "{rounded.chip}"
    padding: "3px 9px"
  cue-banner:
    backgroundColor: "{colors.cue-yellow}"
    textColor: "{colors.ink}"
    rounded: "{rounded.message}"
    padding: "12px 18px"
  card:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.card}"
---

# Design System: CueComm

Native macOS SwiftUI (macOS 14+). Tokens live in `Theme` (Sources/CueComm/Theme.swift); channel hues in `Palette.hexes` (Models.swift).

## Overview

**Creative North Star: "The Scribble Strip"**

Crews already speak colour: gaff tape, laminates, console scribble strips. CueComm decodes speech into that language. The ground is matte black, each channel owns one vibrant hue, and the words are white and large enough to read at arm's length in a dark room. At rest, hues sit dim; the channel that is speaking lights to full colour, and that decoding is the signature.

The system is dense with colour but strict about meaning. Three hues are reserved and never assigned to channels: orange is the action, green is live or connected, yellow is a cue or keyword hit. Everything else is neutral dark surface, so colour is information, not decoration. The Meltveit logo (orange, coral, pink, green) supplies the palette and stays visible, small, at the foot of the sidebar.

Native controls and macOS conventions hold: a standard split view (300pt sidebar, flexible transcript, 380pt Cue Words panel), keyboard operable, reduced motion respected.

**Key Characteristics:**
- Matte black layered by tone, hairline-edged, no light mode.
- One hue per channel; speaking = dim block to full-hue block.
- Reserved meanings: orange action, green live, yellow cue.
- White transcript text at 22pt; secondary text never below 9EA3B0.
- Near-black ink on every solid hue fill.

## Colors

Matte black with a lit-signage palette: saturated hues, used only where they carry a meaning.

### Primary
- **Action Orange** (`action-orange`): the single primary action (Listen, Add, confirm). Never a channel colour.

### Secondary
- **Live Green** (`live-green`): connected session dot, "LIVE" listening state. The logo's playhead. Never a channel colour.
- **Cue Yellow** (`cue-yellow`): keyword highlights, the CUE banner, unread badge when a keyword was missed, fired-cue dot. Never a channel colour.

### Tertiary (channel hues)
Eight assignable hues (`channel-*`): coral, pink, magenta, violet, blue, cyan, teal, periwinkle. They exclude orange, green and yellow. Colour is chosen via SwatchButton, which carries an accessibility name per hue. `danger-coral` shares coral's value for destructive and warning cases.

### Neutral
- **Ground** (`ground`): window and transcript background.
- **Panel** (`panel`): sidebar and Cue Words panel surface.
- **Raised** (`raised`): cards, quiet buttons, disabled fills, fields.
- **Hairline** (`hairline`): 1px borders and quiet-button hover fill.
- **Text** (`text`, white): primary text. **Dim** (`text-dim`): secondary. **Faint** (`text-faint`): the lowest permitted text colour. **Ink** (`ink`): text on any hue fill.

### Named Rules
**The Reserved Hue Rule.** Orange, green and yellow carry fixed meaning (action, live, cue); channel hues never use them, and no decorative element borrows them.
**The Ink Rule.** Text on a solid hue fill is Ink, never white.
**The Faint Floor Rule.** No text colour dimmer than Faint (about 7.8:1 on Ground).

## Typography

**Display / Body / Label Font:** the system sans (SF Pro) throughout, set via `.font(.system(size:weight:))`. No custom or monospaced face; timestamps use system sans with `monospacedDigit()`.

**Character:** large, heavy, generous. Weight, not size, separates roles in the sidebar; size is spent on the transcript.

### Hierarchy
- **Title** (bold, 26-34pt): window header channel name, panel and sheet titles.
- **Transcript** (medium, 22pt default, user-scalable): message text, white.
- **Section** (semibold, 17pt, white): sentence-case section headings via SectionLabel; 14pt minimum, dim only below 17pt.
- **Body** (regular to semibold, 13-16pt): rows, field text, meta, buttons (15-16pt).
- **Chip** (heavy 13pt, 0.8pt tracking, uppercase): channel names inside HueChip only.

### Named Rules
**The Arm's Length Rule.** Transcript text is 22pt white by default; nothing the crew must read mid-show is below 13pt.
**The Sentence Case Heading Rule.** Section headings are sentence case at 14pt or more; the faint all-caps kicker is not a heading style.

## Layout

Three-column split view: 300pt sidebar (wordmark, session pill, All Channels row, grouped channel rows, Channel and Group buttons, small logo), flexible main column (header, transcript, composer), optional 380pt Cue Words panel that slides in with a 0.2s ease-out. Transcript rows read who/when/what: a fixed timestamp column, then the hue chip, then text. Horizontal page padding is 24pt; stack spacing steps are 4, 8, 10, 12, 14, 16. Density is generous; setup surfaces (sheets, editor) use the same tokens as the transcript.

## Elevation & Depth

Flat by tonal layering: Ground, Panel, Raised, with Hairline borders. Shadows are used only as light, not as lift: an active HueBlock gets a glow in its own hue (`color.opacity(0.55)`, radius 10, y 2), and the CUE banner drops a soft black shadow (opacity 0.5, radius 12, y 6) so it separates from the transcript it overlays.

### Named Rules
**The Glow Is Speech Rule.** A hue glow means that channel has audio right now. It is not used for hover or decoration.

## Shapes

Continuous (squircle) rounded rectangles throughout: 6px chips, 10-11px buttons, 12px cards and fields, 14px message rows and the CUE banner. HueBlock and StripBlock corners scale at 24% of their size; strip segments are 3px; SegmentMeter segments are 1.5px. Swatches are circles. Selected items take a 1.5-3px outline, white for swatch and focus, hue-tinted for selected rows and sessions.

## Components

### Buttons
- **Action (ActionButtonStyle):** orange fill, Ink bold text (16pt default), 9/16px padding, 11px radius. Hover fills at 0.88 opacity, pressed 0.7. Disabled swaps to a Raised fill with Faint text. Keyboard focus draws a 2px white ring outside the shape. Listen turns green (LIVE) while listening.
- **Quiet (QuietButtonStyle):** Raised fill, hairline border, white semibold text (15pt), 8/13px padding, 10px radius. Hover fills Hairline; `active` fills with its tint and Ink text; same disabled and focus treatment.

### HueChip
Solid channel hue, uppercase heavy 13pt Ink text, 6px radius. The channel's name wherever it labels a message.

### HueBlock (signature)
Square colour block with the channel's two-letter initials. Silent: 22% hue fill with hue-coloured text. Speaking: full hue fill, Ink text, hue glow, animated in 0.15s ease-out (disabled under Reduce Motion). Sizes 42pt in rows, 52pt in the editor.

### StripBlock
Four coloured strips on a Raised tile; the All Channels mark.

### SegmentMeter
14 segments, 6pt tall, 2pt gaps; lit segments in the channel hue, unlit at 18% opacity. Hidden from accessibility. Used for sidebar levels and soundcard input checks.

### Transcript message row
14px-radius row. Keyword hit: Cue Yellow wash (11%) with a 1.5px Yellow border, keyword highlighted in yellow with Ink text, and a flagged "KEYWORD" label (state never colour alone). Timestamp in a fixed column, monospaced digits.

### CUE banner
Solid Cue Yellow, Ink text, bolt glyph, 22pt heavy title, slides in on firing.

### Cards and fields
Raised fill, 12px radius, hairline stroke; session pill strokes with the live hue at 0.7 opacity while in a session. Cue rows are collapsible cards.

### SwatchButton
30pt circle in the channel hue; selected adds a white 3px ring; announces its colour name to VoiceOver.

## Do's and Don'ts

### Do:
- **Do** keep orange for the action, green for live, yellow for cue, and nothing else.
- **Do** put Ink text on every solid hue fill.
- **Do** build new buttons from ActionButtonStyle or QuietButtonStyle so disabled, hover and focus are inherited.
- **Do** pair colour state with a word, number or icon (KEYWORD flag, unread count, LIVE).
- **Do** gate animation on `accessibilityReduceMotion`.

### Don't:
- **Don't** assign orange, green or yellow to a channel.
- **Don't** use a text colour dimmer than `text-faint`, or sentence-case headings under 14pt.
- **Don't** use a grey system sidebar with a single blue accent.
- **Don't** add light surfaces or system-default tint colours to Settings or Automations; restyle them to this palette.
