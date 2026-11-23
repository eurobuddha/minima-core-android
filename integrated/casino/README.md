# Zero Edge Casino (integrated) — REMOVED from the Pandamonium APK

Removed 23 Nov 2026 (Pandamonium 2.2.111) on the owner's instruction, pending an audit and a rebuild.

Why: state-to-command injection. Every node command this module sends runs as ADMIN on the embedded
node, and the node splits command text at every `;`. The casino copies the raw state of any coin at the
shared covenant address into `txnstate … value:`, `hash data:` and `txnoutput address:` commands
(CasinoTxn.reveal / resolve, triggered automatically by AutoProcessor). A STRING state value such as
`[1];send amount:N address:0x…;[2]` therefore ran a `send` on the house's or the player's node. A
stranger needed no take: any coin at the script address carrying the victim's public key and an old
commitment was enough.

This directory is kept, unreferenced (not in settings.gradle, not a dependency of any flavour), so it can
be audited and added back. Before adding it back:

- validate every state port before use (hex ports 0,1,2,8,9,10,12; numeric ports 3-7,11), as the MDS
  donor 2.9.4 does (archive/Ideas/universal-casino 38836520e2d355a17e07c07e914a686f892e5f4f), and refuse
  `;` and control characters in NodeApi.cmd;
- but do NOT make the HOUSE skip a reveal because the TAKER wrote a non-hex value into port 8, 9 or 10:
  the covenant lets the taker claim the whole pot after the timeout when the house does not reveal, so a
  strict check there lets anyone steal the house stake with a plain `txnstate … value:[x]` take. Those
  three ports are only copied by the reveal; copy any single token without `;`, whitespace, quotes or
  control characters;
- the house auto-reveals for ANY phase-1 coin carrying its key and one of its commitments. A forged
  phase-1 coin that reuses the commitment of a LIVE open offer makes the house publish that offer's secret
  before it is taken, so the forger can take the real offer with a winning pick. Reveal a commitment only
  once its own offer has been taken;
- `Util.shorten` truncates identifiers (unused today): delete it.

The bet secrets stay on users' devices (EncryptedSharedPreferences `casino_secrets`, master key
`pandamonium_casino_master`), so a later build that brings the casino back can still reveal and resolve.
The host cancels the old `casino_auto_process` WorkManager job and deletes the casino notification
channels at every start (PandamoniumHooks.retireCasino).
