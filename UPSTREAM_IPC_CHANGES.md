# Minima Core Android — IPC changes for full-size command output & node file access

**For:** Spartacus Rex / Minima Core Android maintainer
**From:** eurobuddha — fork at https://github.com/eurobuddha/minima-core-android (branch `ui/full-redesign`)
**Date:** 2026-07-28

This documents three additions we made to the broadcast-Intent IPC layer so that companion
apps can (a) receive command results of any size and (b) manage files in the node's base
folder. They are what our on-device **Terminal** (full `history`/`printtree` output) and
**Filez** (file manager for backups/archive exports/txn files) apps run on. All three are
self-contained in the IPC layer — no consensus, wallet, or network code is touched — and all
are fully backward compatible with existing companion apps. You're welcome to take any or all
of it; we're happy to raise PRs in whatever shape suits you.

This follows on from the receiver hardening you adopted in 1.2.4 (the try/catch +
`MAX_MESSAGE_LEN` caps) and completes the remaining recommendation from that write-up.

---

## 1. Run companion commands off the broadcast (main) thread

**Already submitted as PR #1** (`fix/ipc-commands-off-main-thread`) — summary here for
completeness because changes 2 and 3 build on it.

**Problem.** `MinimaReceiver` is registered without a Handler, so `onReceive` runs on the
process main thread and calls `runMinimaCMD` synchronously. Any command that opens a socket
inline (`megammrsync`, `archive resync`) throws `NetworkOnMainThreadException`, which
`sendArchiveReq`'s catch-all swallows — the user sees a misleading
*"Could not connect to Archive host!"* even when the host is fine. Slow commands also block
the main thread (ANR risk).

**Fix.** A single-thread `ExecutorService` in the receiver; the command runs and the
response is broadcast from that thread. Ordering is unchanged (still strictly serialised),
registration/enable/admin checks stay on the receiver thread, and a command that throws now
returns `status:false` instead of failing silently.

---

## 2. Large results as a `content://` file hand-off (unbounded response size)

**Problem.** The response travels as one Intent String extra. Two ceilings sit on that path:
your 256,000-char `MAX_MESSAGE_LEN` stub, and beneath it the Android Binder ~1 MB
transaction limit — an over-limit broadcast throws `TransactionTooLargeException` in the
*receiving* process with no way to catch it. So a companion terminal simply cannot display a
long `history`, `coins`, or `printtree` result, and raising the cap is not an option: the
transport itself has to change for big payloads.

**Design — negotiated per command, three-way compatible.**

- New client → new node: the client puts a boolean extra `…CMD_FILERESP=true` on the CMD
  intent. If the result exceeds `MAX_MESSAGE_LEN`, the node writes it to
  `cacheDir/ipcresponses/<responseid>.json`, exposes it via a **non-exported FileProvider**
  (authority `org.minimarex.minimacore.ipcresponses`), grants **read to exactly the calling
  package** (`grantUriPermission`, plus ClipData grant for newer Android), and broadcasts
  the normal RESPONSE intent carrying `…RESPONSE_URI` + `…RESPONSE_LEN` instead of
  `…RESPONSE_RESULT`. A file descriptor crosses Binder, not the data — size is effectively
  unbounded.
- Old client → new node: no flag → the existing "Result too long!" stub, byte-identical to
  today. Nothing breaks for any already-installed app.
- New client → old node: the unknown extra is ignored → the stub, exactly as before.
- Results ≤ 256 KB keep the existing inline path in all pairings — zero overhead.

**Hygiene.** Response files are pruned after 5 minutes and wiped at receiver start; URI
grants are revoked on prune; a failed file write falls back to the stub (never silence).
The client library reads the URI off the main thread and delivers through the unchanged
`MinimaAPIListener.response(JSONObject)` — apps need only the updated `minimaapi` library,
no code changes.

**Where:** commit `6bcefae` (receiver + manifest provider + `res/xml/ipc_response_paths.xml`
+ minimaapi). New constants in `MinimaAPIMessages`: `MINIMA_API_CMD_FILERESP`,
`MINIMA_API_RESPONSE_URI`, `MINIMA_API_RESPONSE_LEN`.

---

## 3. Admin-gated FILE bridge (node base-folder access for companion apps)

**Problem.** `backup`, `megammr action:export`, `txnexport` etc. write into the node's
private `getFilesDir()` — unreachable by any other app, and users have no way to get those
files off the device (or to place a restore/import file where the node can see it) without
adb. Our Filez app needed a supported path.

**Design.** A third broadcast action, `org.minimarex.minimacore.FILE`, alongside REGISTER
and CMD, with extras `FILE_ACTION`, `FILE_PATH`, `FILE_NEWPATH`, `FILE_URI`. Actions:

| Action | Behaviour |
|---|---|
| `list` | Directory listing (name/size/dir/modified) + the node's **canonical base path** so clients never reconstruct paths |
| `stat` | Resolves a raw terminal-style path exactly like `MiniFile.createBaseFile` and reports what the node actually sees (exists/isdir/size/canonical); when missing, includes a parent listing — ground truth for "file doesn't exist" |
| `get`  | Exports a file: returns a `content://` URI granted read-only to the calling package (same FileProvider; `files-path` root added) |
| `put`  | Imports: streams the caller-provided `content://` URI into the base folder |
| `mkdir` / `move` / `delete` | The obvious operations |

**Security model** (the part we'd most like eyes on):

- **ADMIN-gated** — the same per-app admin flag that gates `Userid 0x00` commands. Rationale:
  an admin app can already run `backup` + read everything via commands, so the bridge adds
  reach-parity, not new privilege. Non-admin callers get a clear
  "File access needs ADMIN" error.
- **Path containment** — every path is canonically resolved and must remain inside the base
  folder (no traversal out).
- **Write-protected `databases/`** — put/mkdir/move/delete refuse the live DB folder; a file
  manager must never be able to corrupt the running wallet/chain DBs. Reads are allowed
  everywhere (see admin rationale above).
- Runs on the same background executor; oversized listings use the change-2 hand-off;
  responses come back on the normal RESPONSE channel keyed by `responseid`.

**Where:** commits `d681355` (bridge) and `e0b418f` (`stat` + canonical paths). On an
unmodified node the `.FILE` broadcasts are simply not in the IntentFilter, so old nodes
ignore them cleanly.

---

## Adoption notes

- All changes live in the IPC layer: `receiver/MinimaReceiver.java`,
  `service/MinimaService.java` (one IntentFilter line), `AndroidManifest.xml` (one provider),
  `res/xml/ipc_response_paths.xml`, and the `minimaapi` module (client side + shared
  constants). Nothing else in the app, and nothing in minima.jar, is required for the above.
- Field-tested since 2026-07-21 across our companion fleet (terminal, terminal-IDE, file
  manager, wallet, DEX/AMM apps) on mainnet daily-driver devices, alongside unmodified
  legacy apps on the same node.
- PR #1 (executor) is open; we can raise the other two as separate PRs on request, or you
  can cherry-pick from the fork branch — whichever you prefer.

---

## Beyond the IPC layer — the rest of the fork, at a glance

The fork is published to our own app store as **"Minima Core — New UI (Preview)"** (currently
1.6.x). Besides the IPC work above (~2,900 insertions across 59 files vs upstream `main` in
the UI/resources alone), it carries:

**Look & feel** — a brand-native, dark-first restyle of every screen (commit `b37fe5b`,
resources-only, no behaviour change), then a structural redesign (`cfe8856`): a **Home
dashboard** tab (block height, node + APK versions, connection state), tab bar
Home / Wallet / Terminal / Apps (+ Logs), consistent typography/colour system, and a
**screenshot privacy toggle** (FLAG_SECURE, persisted, default-blocked).

**Wallet** — the Wallet tab was rebuilt around dedicated **Send** and **Receive** activities
(validated recipient via `checkaddress`, amount clamping, QR on receive) and a token-aware
balance view with **token icons**: remote icon URLs are treated as hostile (size caps, SSRF
blocking, bounded loader pool), validated through the node's own `webvalidate` /
`tokenvalidate`, rendered over a deterministic identicon fallback; SVG token art supported.

**Node-facing UX** — a **Startup Params** screen (server mode / MegaMMR / RPC toggles + a
validated free-text args field wired to the existing `minima_extra_params` pref, with
Save & Restart doing a clean quit-and-relaunch), and a **Logs tab**: a live on-device tail
of `MinimaLogger` output (the `MINIMALOG` notify event the node already fires), with
pause/clear/share, substring filter and per-subsystem verbosity chips driving the `logs`
command. While wiring the params screen we also fixed a latent hazard in the upstream boot
path: a stored `-clean` in the extra params was stripped from the *saved* string but the
*original* string was tokenized into the boot args — the wipe ran on the very boot it was
set.

**Stability** — the H2 2.4.240 VerifyError fix (**PR #2**, open), and a set of MegaMMR
`action:import` robustness changes in the core jar: streamed batched IBD import, in-flight
OOM watermarks in both load phases (a too-large file now reports
"cannot hold this MegaMMR in memory" instead of killing the process), strict truncated-file
detection, and megaprune applied at read time. Measured against a real mainnet
`megammr.mmr`: the MMR tree is ~1.5 M entries / ~1 GB in RAM — details and numbers on
request.

Any of this is available in the same fork; the UI work is opinionated and we don't expect
upstream wants it wholesale, but individual pieces (token-icon validation, the Logs tab,
the params screen, the `-clean` fix) lift out cleanly.

Thanks for Minima — and for taking the 1.2.4 hardening on board.
