#!/bin/bash
# Builds the app and packs it into a drag-to-Applications disk image.
# Usage: ./make_dmg.sh [output.dmg]     (default: ~/Desktop/CueComm.dmg)
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-$HOME/Desktop/CueComm.dmg}"

./build.sh

STAGE="$(mktemp -d)/CueComm"
mkdir -p "$STAGE"
cp -R build/CueComm.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"
cp "Resources/Read Me First.txt" "$STAGE/Read Me First.txt"

rm -f "$OUT"
hdiutil create -volname "CueComm" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$OUT" >/dev/null
hdiutil verify "$OUT" >/dev/null
echo "Created $OUT ($(du -h "$OUT" | cut -f1))"
