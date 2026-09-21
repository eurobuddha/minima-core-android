#!/bin/bash
# Sample every attached node's Dalvik heap + uptime on an interval, one CSV row each.
#
# WHY: the 2026-09-11 incident was driven by on-disk store size, and a megammrsync resync fixed
# it. On 2026-09-21 the zfold7 pinned its heap AGAIN after 3d21h of uptime — 489,748 KB of the
# 524,288 KB ceiling, blocking GC in a loop, its own block clock frozen 11 blocks behind the
# chain — and this time the store had already been resynced. That points at an uptime-
# proportional retention rather than a data-size one, and only a growth curve tells those apart.
#
# Both phones now run DIFFERENT node builds, which makes this a free A/B:
#   RFCY71KW3LX zfold7  1.6.34-ui-h2 (vc62)
#   R58M307HEEN S10+    1.6.20-ui-h2 (vc48)
# Same climb on both  -> the retention is in the node jar (upstream).
# Only the zfold7      -> it arrived in vc49-62 (the backup/vault/restore work).
# Neither climbs       -> the trigger is episodic, not steady-state; correlate with what ran.
#
# Usage:  ./heapwatch.sh [interval_seconds]   (default 600)
# Output: heapwatch-out/heap-<serial>.csv  (gitignored by diag/.gitignore's `*/`)
#         iso_time,uptime,alloc_kb,heap_kb,pss_kb,gc_waits
#
# Read it with:  column -s, -t heapwatch-out/heap-*.csv
# alloc_kb climbing monotonically across restarts-free uptime IS the leak. A sawtooth that
# returns to baseline after each GC is a working set, not a leak.
set -u
INTERVAL="${1:-600}"
cd "$(dirname "$0")"
mkdir -p heapwatch-out
while :; do
  for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    mem=$(adb -s "$s" shell dumpsys meminfo org.minimarex.minimacore 2>/dev/null) || continue
    alloc=$(echo "$mem" | awk '/Dalvik Heap/{print $3}')
    size=$(echo "$mem"  | awk '/Dalvik Heap/{print $8}')
    pss=$(echo "$mem"   | awk '/TOTAL PSS:/{print $3}')
    [ -z "${alloc:-}" ] && continue          # node not installed on this device
    up=$(adb -s "$s" shell ps -A -o ETIME,NAME 2>/dev/null | awk '/minimacore/{print $1; exit}')
    # The log is a ring buffer, so this is "GC waits currently visible", not a delta. A number
    # that climbs into the thousands is the thrash signature from HEAP_EXHAUSTION_2026-09-11.md.
    gc=$(adb -s "$s" shell logcat -d 2>/dev/null | grep -c "Waiting for a blocking GC")
    f="heapwatch-out/heap-$s.csv"
    [ -f "$f" ] || echo "iso_time,uptime,alloc_kb,heap_kb,pss_kb,gc_waits" > "$f"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),${up:-?},$alloc,$size,${pss:-?},$gc" >> "$f"
  done
  sleep "$INTERVAL"
done
