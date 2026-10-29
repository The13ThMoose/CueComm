#!/bin/bash
# Builds build/CueComm.app (no Xcode needed; Command Line Tools are enough).
# By default the app is universal (Apple Silicon + Intel). Pass --native for a faster host-only build.
set -euo pipefail
cd "$(dirname "$0")"
APP="build/CueComm.app"
BIN="$APP/Contents/MacOS/CueComm"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

if [[ "${1:-}" == "--native" ]]; then
    swift build -c release
    cp .build/release/CueComm "$BIN"
else
    for arch in arm64 x86_64; do
        swift build -c release --triple "$arch-apple-macosx14.0" --scratch-path ".build-$arch"
    done
    lipo -create .build-arm64/release/CueComm .build-x86_64/release/CueComm -output "$BIN"
fi

cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/MeltveitLogo.png "$APP/Contents/Resources/MeltveitLogo.png"

ICONSET="$(mktemp -d)/AppIcon.iconset"
swift scripts/make_icon.swift "$ICONSET"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

codesign --force --deep --sign - "$APP"
echo "Built $APP ($(lipo -archs "$BIN"))"
