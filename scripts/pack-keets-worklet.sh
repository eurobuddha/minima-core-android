#!/usr/bin/env bash
# Build the keets worklet from the keets repo sidecar (single source of truth):
#   app/src/main/assets/keets.bundle           — bare-pack output (android-arm64 + android-arm)
#   app/src/main/jniLibs/<abi>/lib<mod>.<ver>.so — the addons' android prebuilds, in the
#                                                  linked: naming bare resolves at runtime
# Neither output is committed; this script is how CI and a fresh clone produce them.
# bare-pack is run under the bare runtime — its native lexer segfaults under node >= 24.
set -euo pipefail
cd "$(dirname "$0")/.."

SIDECAR="${KEETS_SIDECAR_DIR:-../../keets/sidecar}"
[ -f "$SIDECAR/index.js" ] || { echo "keets sidecar not found at $SIDECAR"; exit 1; }
( cd "$SIDECAR" && [ -d node_modules ] || npm ci )

command -v bare >/dev/null || npm install -g bare
PACK="$(npm root -g)/bare-pack/bin.js"
[ -f "$PACK" ] || { npm install -g bare-pack; PACK="$(npm root -g)/bare-pack/bin.js"; }

# node builtins → bare equivalents, applied at pack time only (the sidecar picks them at
# runtime itself; node's loader can't express this map, bare-pack's --imports can).
IMPORTS="$(mktemp).json"
trap 'rm -f "$IMPORTS"' EXIT
cat > "$IMPORTS" <<'EOF'
{ "net": "bare-tcp", "fs": "bare-fs", "path": "bare-path", "process": "bare-process" }
EOF

mkdir -p app/src/main/assets
( cd "$SIDECAR" && bare "$PACK" \
    --host android-arm64 --host android-arm \
    --linked --imports "$IMPORTS" \
    --out "$OLDPWD/app/src/main/assets/keets.bundle" index.js )
echo "packed app/src/main/assets/keets.bundle ($(du -h app/src/main/assets/keets.bundle | cut -f1))"

# addon prebuilds → jniLibs, named lib<module>.<version>.so (bare's linked: convention).
# Walk node_modules RECURSIVELY: npm nests a second copy when versions conflict
# (bare-tty/node_modules/bare-signals@5 vs top-level @4), and the bundle requires the
# nested version — missing it is a native CANNOT_LOAD abort on-device (proven 2026-10-13).
declare -a MAP=("android-arm64:arm64-v8a" "android-arm:armeabi-v7a" "android-x64:x86_64")
COUNT=0
while IFS= read -r predir; do
  moddir="$(dirname "$predir")"
  mod="$(basename "$moddir")"
  for pair in "${MAP[@]}"; do
    host="${pair%%:*}"; abi="${pair##*:}"
    pre="$predir/$host/$mod.bare"
    if [ -f "$pre" ]; then
      ver="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$moddir/package.json" | head -1)"
      mkdir -p "app/src/main/jniLibs/$abi"
      cp "$pre" "app/src/main/jniLibs/$abi/lib$mod.$ver.so"
      COUNT=$((COUNT+1))
    fi
  done
done < <(find "$SIDECAR/node_modules" -type d -name prebuilds)
echo "linked $COUNT addon prebuilds into app/src/main/jniLibs/"
