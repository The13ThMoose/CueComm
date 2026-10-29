# The website

`docs/` at the repository root is the site, served by GitHub Pages: `index.html` (English), `no/index.html` (Norwegian) and
`assets/` (CSS, a little JavaScript, fonts, images). It is static and works on any web host. This folder holds what
generates it.

- `build_site.py` writes both pages from one set of texts and stamps the size and SHA-256 of the two downloads on them.
- `shoot.py` takes screenshots of the pages in Chrome (Chrome DevTools protocol, standard library only).
- `PRODUCT.md` and `DESIGN.md` record who the site is for and what it looks like, in the format the
  [Impeccable](https://impeccable.style) design skill reads.

## Rebuild

```bash
python3 -I website/build_site.py --dmg CueComm.dmg --apk CueComm.apk \
  --repo The13ThMoose/CueComm --base-url https://the13thmoose.github.io/CueComm/
```

With `--repo` the download buttons point at the latest release's `CueComm.dmg` and `CueComm.apk`, so upload the two files
to a release under exactly those names. Without it the files are copied into `docs/download/`. Every text, in both
languages, is in `build_site.py`; the look is `docs/assets/site.css` and the scroll-as-playhead behaviour is
`docs/assets/site.js`.

```bash
python3 -m http.server 8765 --directory docs
python3 -I website/shoot.py http://127.0.0.1:8765/ 1440 900 1 out/desktop top channels cues sessions install faq
```

## The screenshots

The pictures of the apps are real: the Mac app's own demo mode (`CUECOMM_DEMO=1`) hosting a session called Sunday Service
with the Android app, running in an emulator, joined to it. The channels, chatter and device names are invented for the
page; nothing comes from a real crew. A cue is fired on the Mac by an OSC message to an automation. See the Mac README for
the test hooks (`CUECOMM_SUPPORT_DIR`, `CUECOMM_DEMO`).

## Fonts

Display and label faces are DIN Condensed and Avenir Next Condensed where installed (every Mac, iPad and iPhone); other
visitors get the self-hosted Barlow Condensed (SIL Open Font License, see `docs/assets/fonts/FONT-LICENSE.txt`).

The site shares its structure, fonts and tooling with the [StageTracks](https://the13thmoose.github.io/Stagetracks/) site.
