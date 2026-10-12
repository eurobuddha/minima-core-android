#!/usr/bin/env bash
# Fetch the Bare Kit Android prebuild (pinned) and package it as app/libs/bare-kit.aar.
# Bare Kit is the mobile JS runtime that hosts the keets sidecar as a worklet. The AAR is
# ~250MB unpacked (the runtime ships per-ABI); it is NEVER committed — this script is how
# CI and a fresh clone get it. Pin bumps are deliberate, like the parlons jar.
set -euo pipefail
cd "$(dirname "$0")/.."

BARE_KIT_VERSION="${BARE_KIT_VERSION:-v2.5.5}"
AAR="app/libs/bare-kit.aar"

if [ -f "$AAR" ] && unzip -p "$AAR" META-INF/bare-kit-version 2>/dev/null | grep -q "^$BARE_KIT_VERSION$"; then
  echo "bare-kit $BARE_KIT_VERSION already present: $AAR"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "fetching bare-kit $BARE_KIT_VERSION prebuilds…"
gh release download "$BARE_KIT_VERSION" --repo holepunchto/bare-kit --pattern "prebuilds.zip" --dir "$WORK"
unzip -q "$WORK/prebuilds.zip" "android/bare-kit/*" -d "$WORK"

mkdir -p "$WORK/android/bare-kit/META-INF"
echo "$BARE_KIT_VERSION" > "$WORK/android/bare-kit/META-INF/bare-kit-version"
mkdir -p app/libs
( cd "$WORK/android/bare-kit" && zip -qr "$OLDPWD/$AAR" . )
echo "built $AAR ($(du -h "$AAR" | cut -f1)) from bare-kit $BARE_KIT_VERSION"
