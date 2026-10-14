#!/bin/bash
# Build and publish the IPFS snapshot of the PandaDapps store, the official
# Minima store mirror, and the native minimaCore APK catalog.
#
# Runs on the Pi as root (cron + manually after store updates). The snapshot
# is staged in /var/ipfs-store, added to the local kubo node, published under
# the 'pandastore' IPNS key, and remote-pinned to $PIN_SERVICE (if configured).
#
# Deployed copy: /usr/local/bin/build_ipfs_store.sh
set -euo pipefail

# single-instance guard: a slow run (big downloads) must not overlap the next cron fire
exec 9>/var/lock/build_ipfs_store.lock
flock -n 9 || { echo "[ipfs-store] another build is running - exiting"; exit 0; }

STAGE=/var/ipfs-store
WEB=/var/www/html
GATEWAY_BASE=https://ipfs.eurobuddha.com
IPNS_KEY=pandastore
APKS_JSON_URL=https://raw.githubusercontent.com/eurobuddha/minima-core-apks/main/apks.json
SITE_SRC=/usr/local/share/ipfs-store/index.html
STATE_DIR=/var/lib/ipfs-store
CACHE=$STATE_DIR/cache
# Name of the kubo remote-pinning service to mirror snapshots to (registered via
# `ipfs pin remote service add <name> <endpoint> <token>`). Filebase (5 GB free) —
# was Pinata (1 GB), too small for the ~615 MB snapshot with rotation.
PIN_SERVICE="${PIN_SERVICE:-filebase}"

as_ipfs(){ sudo -u ipfs env IPFS_PATH=/home/ipfs/.ipfs "$@"; }
log(){ echo "[ipfs-store $(date '+%F %T')] $*"; }

# Ensure $1 is pinned at the remote service and drop superseded pins. Safe to
# re-run: called on publish AND on no-change runs, so a pin that failed (or a
# service registered after the last content change) gets retried rather than
# waiting for a new CID.
#
# UNPIN-FIRST rotation: on a small free tier two ~600 MB snapshots won't fit at
# once, so every superseded pin is dropped BEFORE the new one is added — peak usage
# stays at roughly one snapshot, not two. The Pi keeps the content pinned locally and
# is directly reachable, so the brief window where the service holds at most one (or
# no) pin is low-risk: the remote pin is redundancy, the Pi is the primary source.
#
# REMOTE PINNING IS REDUNDANCY, NEVER A GATE. Every failure here must be a warning, because the
# publish has ALREADY happened by the time this runs — steps 7 and 8 cannot be undone, so aborting
# leaves the store live on a CID that was never recorded in ipfs-cid.txt, with last.hash stale and
# superseded local pins never collected.
#
# That is not hypothetical. Filebase revoked the free tier's Pinning Service API
#   Error: reason: "FORBIDDEN", details: "The Pinning Service API requires a paid account": 403
# and because `set -euo pipefail` is in force, that 403 inside the `pin remote ls` PIPELINE killed
# the script mid-run — after the IPNS publish, before the bookkeeping. Every hourly run since
# 2026-08-02 re-published and died at the same line, so the published "current CID" pointed at a
# snapshot two builds old while the store itself was correctly live.
#
# The `else` branch below always meant to tolerate this. It never ran: the pipeline failed first.
# Hence `remote_ls`, which swallows the failure and yields nothing rather than exiting.
#
# UNPIN-FIRST rotation: on a small free tier two ~600 MB snapshots won't fit at
# once, so every superseded pin is dropped BEFORE the new one is added — peak usage
# stays at roughly one snapshot, not two. The Pi keeps the content pinned locally and
# is directly reachable, so the brief window where the service holds at most one (or
# no) pin is low-risk: the remote pin is redundancy, the Pi is the primary source.

# Never fails: prints the service's pin list, or nothing at all if it cannot be read.
remote_ls(){
    as_ipfs ipfs pin remote ls --service="$PIN_SERVICE" --status=queued,pinning,pinned 2>/dev/null || true
}

sync_remote_pin(){
    local cid="$1" listing
    as_ipfs ipfs pin remote service ls 2>/dev/null | awk '{print $1}' | grep -qx "$PIN_SERVICE" || {
        log "$PIN_SERVICE not configured - skipping remote pin"; return 0; }

    listing=$(remote_ls)
    if [ -z "$listing" ]; then
        # Unreadable is not the same as empty: it means the service is down, unauthorised or
        # unpaid. Say so, change nothing remotely, and let the caller finish its bookkeeping.
        log "WARN cannot read $PIN_SERVICE pin list - skipping remote pin (local pin is unaffected)"
        return 0
    fi

    # 1. free space first — unpin every remote pin that isn't the target CID
    printf '%s\n' "$listing" \
        | awk -v cid="$cid" '$1 ~ /^(baf|Qm)/ && $1 != cid {print $1}' \
        | while read -r old; do
            as_ipfs ipfs pin remote rm --service="$PIN_SERVICE" --cid="$old" --force >/dev/null 2>&1 \
                && log "unpinned superseded $old" || true
          done

    # 2. now add the current snapshot (skip if already pinned/queued)
    if printf '%s\n' "$listing" | grep -q "$cid"; then
        log "already pinned/queued at $PIN_SERVICE: $cid"
    elif as_ipfs ipfs pin remote add --service="$PIN_SERVICE" --background \
             --name "pandastore-$(date +%Y%m%d-%H%M)" "/ipfs/$cid"; then
        log "queued pin at $PIN_SERVICE: $cid"
    else
        log "WARN $PIN_SERVICE pin failed (will retry next run)"
    fi
    return 0
}

mkdir -p "$STAGE" "$STATE_DIR" "$CACHE"

# ── 1. Mirror the two MiniDapp catalogs from the live webroot ────────────────
rsync -a --delete "$WEB/panda_dapps/" "$STAGE/panda_dapps/"
rsync -a --delete "$WEB/store/" "$STAGE/store/"
if [ -d "$WEB/skills" ]; then rsync -a --delete "$WEB/skills/" "$STAGE/skills/"; fi
cp "$WEB/pandadapps.json" "$STAGE/pandadapps.src.json"
if [ -f "$WEB/skills.json" ]; then cp "$WEB/skills.json" "$STAGE/skills.src.json"; fi

# ── 2-4. Download external assets, rewrite catalogs (relative + absolute) ────
STAGE=$STAGE CACHE=$CACHE GATEWAY_BASE=$GATEWAY_BASE APKS_JSON_URL=$APKS_JSON_URL \
python3 - <<'PY'
import hashlib, json, os, re, shutil, sys, urllib.request

STAGE = os.environ["STAGE"]
CACHE = os.environ["CACHE"]
GATEWAY = os.environ["GATEWAY_BASE"].rstrip("/")
APKS_URL = os.environ["APKS_JSON_URL"]

def fetch(url, dest, force=False, tries=2):
    """Download url to dest (skip if present unless force). True on success."""
    if os.path.exists(dest) and not force:
        return True
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "pandastore-ipfs-mirror"})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest + ".tmp", "wb") as f:
                shutil.copyfileobj(r, f)
            os.replace(dest + ".tmp", dest)
            return True
        except Exception as e:
            print(f"WARN download failed ({attempt+1}/{tries}) {url}: {e}", file=sys.stderr)
    return False

def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def relativize(url, owner_slug):
    """Map a catalog URL to a snapshot-root-relative path.

    eurobuddha.com URLs are already mirrored by rsync; external URLs are
    downloaded into a cache (staging dirs get rsync --delete'd) and copied
    under panda_dapps/external/<owner>/.
    """
    if not isinstance(url, str) or not url.startswith(("http://", "https://")):
        return url
    for pref in ("https://eurobuddha.com/", "http://eurobuddha.com/",
                 "https://store.eurobuddha.com/", "http://store.eurobuddha.com/"):
        if url.startswith(pref):
            return url[len(pref):]
    base = url.rsplit("/", 1)[-1]
    rel = f"panda_dapps/external/{owner_slug}/{base}"
    cached = os.path.join(CACHE, rel)
    # 'releases/latest' URLs change content without changing name: always refetch
    if fetch(url, cached, force=("releases/latest" in url)):
        dest = os.path.join(STAGE, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copy2(cached, dest)
        return rel
    print(f"WARN keeping original URL (download failed): {url}", file=sys.stderr)
    return url

def write_pair(rel_path, data, url_fields):
    """Write <name>.json (root-relative paths) + <name>.abs.json (gateway URLs)."""
    def deep(obj, absolute):
        if isinstance(obj, dict):
            return {k: (f"{GATEWAY}/{v}" if absolute and k in url_fields
                        and isinstance(v, str) and not v.startswith("http")
                        else deep(v, absolute) if not (k in url_fields) else v)
                    for k, v in obj.items()}
        if isinstance(obj, list):
            return [deep(x, absolute) for x in obj]
        return obj
    with open(os.path.join(STAGE, rel_path), "w") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
    absname = rel_path[:-5] + ".abs.json"
    with open(os.path.join(STAGE, absname), "w") as f:
        json.dump(deep(data, True), f, indent=1, ensure_ascii=False)

URL_FIELDS = {"file", "icon"}

# --- pandadapps.json --------------------------------------------------------
with open(os.path.join(STAGE, "pandadapps.src.json")) as f:
    panda = json.load(f)
panda["icon"] = relativize(panda.get("icon", ""), "store")
for dapp in panda.get("dapps", []):
    s = slug(dapp.get("name", "dapp"))
    dapp["file"] = relativize(dapp.get("file", ""), s)
    dapp["icon"] = relativize(dapp.get("icon", ""), s)
write_pair("pandadapps.json", panda, URL_FIELDS)
os.remove(os.path.join(STAGE, "pandadapps.src.json"))
print(f"pandadapps: {len(panda.get('dapps', []))} dapps")

# --- store/minimadapps.json -------------------------------------------------
mpath = os.path.join(STAGE, "store", "minimadapps.json")
if os.path.exists(mpath):
    with open(mpath) as f:
        official = json.load(f)
    if isinstance(official.get("icon"), str):
        official["icon"] = relativize(official["icon"], "official-store")
    for dapp in official.get("dapps", []):
        s = slug(dapp.get("name", "dapp"))
        for k in ("file", "icon"):
            if isinstance(dapp.get(k), str):
                dapp[k] = relativize(dapp[k], s)
    write_pair("store/minimadapps.json", official, URL_FIELDS)
    print(f"official store: {len(official.get('dapps', []))} dapps")
else:
    print("WARN store/minimadapps.json missing", file=sys.stderr)

# --- skills.json (AI skills) ------------------------------------------------
spath = os.path.join(STAGE, "skills.src.json")
if os.path.exists(spath):
    with open(spath) as f:
        skills = json.load(f)
    if isinstance(skills.get("icon"), str):
        skills["icon"] = relativize(skills["icon"], "skills")
    for it in skills.get("skills", []):
        s = slug(it.get("name", "skill"))
        for k in ("file", "icon"):
            if isinstance(it.get(k), str):
                it[k] = relativize(it[k], s)
    write_pair("skills.json", skills, URL_FIELDS)
    os.remove(spath)
    print(f"skills: {len(skills.get('skills', []))} skills")

# --- apks/apks.json (native minimaCore catalog) -----------------------------
apks_dir = os.path.join(STAGE, "apks")
os.makedirs(os.path.join(apks_dir, "icons"), exist_ok=True)
apks_tmp = os.path.join(apks_dir, "apks.src.json")
apks = None
if fetch(APKS_URL, apks_tmp, force=True):
    with open(apks_tmp) as f:
        apks = json.load(f)
    os.remove(apks_tmp)
elif os.path.exists(os.path.join(apks_dir, "apks.json")):
    print("WARN apks.json fetch failed - keeping previous snapshot copy", file=sys.stderr)

if apks is not None:
    referenced = set()
    for app in apks.get("apps", []):
        fbase = app["file"].rsplit("/", 1)[-1]
        frel = f"apks/{fbase}"
        if fetch(app["file"], os.path.join(STAGE, frel)):
            want = app.get("sha256")
            if want:
                got = sha256_file(os.path.join(STAGE, frel))
                if got != want.lower():
                    print(f"WARN sha256 mismatch for {fbase} - refetching", file=sys.stderr)
                    if fetch(app["file"], os.path.join(STAGE, frel), force=True):
                        got = sha256_file(os.path.join(STAGE, frel))
                    if got != want.lower():
                        print(f"WARN sha256 STILL wrong for {fbase} (catalog carries "
                              f"the expected hash; client will verify)", file=sys.stderr)
            app["file"] = frel
            referenced.add(fbase)
        else:
            print(f"WARN apk unavailable, keeping original URL: {app['file']}", file=sys.stderr)
        icon = app.get("icon")
        if isinstance(icon, str) and icon.startswith("http"):
            ibase = icon.rsplit("/", 1)[-1]
            irel = f"apks/icons/{ibase}"
            if fetch(icon, os.path.join(STAGE, irel)):
                app["icon"] = irel
    # prune APKs no longer referenced (keeps snapshot at current-versions-only)
    for fn in os.listdir(apks_dir):
        p = os.path.join(apks_dir, fn)
        if os.path.isfile(p) and fn not in referenced and not fn.endswith(".json"):
            os.remove(p)
            print(f"pruned stale {fn}")
    write_pair("apks/apks.json", apks, URL_FIELDS)
    print(f"apks: {len(apks.get('apps', []))} apps")

# --- desktop placeholder ----------------------------------------------------
os.makedirs(os.path.join(STAGE, "desktop"), exist_ok=True)
readme = os.path.join(STAGE, "desktop", "README.txt")
if not os.path.exists(readme):
    with open(readme, "w") as f:
        f.write("minimaDesktop builds will be published here once released.\n")
PY

# ── 5. Store front-end ───────────────────────────────────────────────────────
cp "$SITE_SRC" "$STAGE/index.html"

# ── 6. Change detection ──────────────────────────────────────────────────────
chmod -R a+rX "$STAGE"
HASH=$(cd "$STAGE" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
LAST=$(cat "$STATE_DIR/last.hash" 2>/dev/null || echo none)
if [ "$HASH" = "$LAST" ]; then
    log "no changes since last publish"
    # still reconcile the remote pin - covers a pin that failed earlier or a
    # pinning service registered after the most recent content change
    sync_remote_pin "$(cat "$WEB/ipfs-cid.txt" 2>/dev/null || echo '')"
    exit 0
fi

# ── 7. Add + IPNS publish ────────────────────────────────────────────────────
CID=$(as_ipfs ipfs add -r --cid-version 1 -Q "$STAGE")
log "snapshot CID: $CID"
as_ipfs ipfs name publish --key="$IPNS_KEY" --lifetime 48h "/ipfs/$CID"
log "IPNS published"

# ── 8. Record what was just published, BEFORE anything optional ──────────────
# Order matters. This used to run after the remote pin, so when the pin step died the store was
# live on a CID that nothing had recorded — ipfs-cid.txt named a stale snapshot and last.hash was
# never advanced, leaving every later run to redo the publish and die at the same line. The
# publish is irreversible by this point, so the record of it must not sit behind anything that
# can fail.
echo "$CID"  > "$WEB/ipfs-cid.txt" && chmod 644 "$WEB/ipfs-cid.txt"
echo "$HASH" > "$STATE_DIR/last.hash"

# ── 9. Remote pin (redundancy only) + local GC of superseded snapshots ───────
sync_remote_pin "$CID"
as_ipfs ipfs pin ls --type=recursive -q | grep -v "^$(as_ipfs ipfs cid base32 "$CID" 2>/dev/null || echo "$CID")$" | \
while read -r old; do as_ipfs ipfs pin rm "$old" >/dev/null 2>&1 || true; done
log "done - https://ipfs.eurobuddha.com/  |  /ipns/ipfs.eurobuddha.com  |  /ipfs/$CID"
