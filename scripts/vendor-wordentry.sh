#!/usr/bin/env bash
# Vendor the guided seed-phrase entry (com.eurobuddha.account.WordEntry) VERBATIM from Dexicon, its single source -
# the same file AtomiX and PandaPools vendor with their Trading Account. One header line names Dexicon's version; the rest
# is Dexicon's bytes. `--check` fails on any drift. Never edit it here: change Dexicon and re-vendor.
set -euo pipefail
cd "$(dirname "$0")/.."
DEX=${DEXICON:-../dexicon}
F=com/eurobuddha/account/WordEntry.java
SRC="$DEX/app/src/main/java/$F" DST="app/src/main/java/$F"
[ -f "$SRC" ] || { echo "SOURCE MISSING: $SRC"; exit 1; }
VER=$(grep -E 'versionName' "$DEX/app/build.gradle" | head -1 | grep -oE '"[^"]+"' | tr -d '"')
SHA=$(git -C "$DEX" rev-parse --short HEAD 2>/dev/null || echo unknown)
if [ "${1:-}" = "--check" ]; then
  [ -f "$DST" ] || { echo "MISSING: $DST"; exit 1; }
  tail -n +2 "$DST" | cmp -s - "$SRC" && echo "WORDENTRY VENDOR PASS — matches apks/dexicon $VER" \
    || { echo "WORDENTRY VENDOR FAIL — run scripts/vendor-wordentry.sh"; exit 1; }
else
  mkdir -p "$(dirname "$DST")"
  { echo "// VENDORED VERBATIM from apks/dexicon $VER ($SHA) $F by scripts/vendor-wordentry.sh — do not edit here; change Dexicon and re-vendor."; cat "$SRC"; } > "$DST"
  echo "vendored WordEntry from apks/dexicon $VER ($SHA)"
fi
