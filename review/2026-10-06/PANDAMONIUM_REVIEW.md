# Pandamonium 1.8.0 integration review

## Summary

Pandamonium adds a separate Android product flavor containing the existing minimaCore node and eleven native companion apps. The integration reuses their activities, services, transaction confirmations and recovery logic. A shared right-hand drawer switches between retained activities. The node forces block-based key uses and starts with low-RAM SQL storage. The separate application ID avoids replacing an existing PandaBear or BlackBear wallet.

Review focused on the new integration boundaries and differences from the imported sources. This is not a new independent audit of every copied financial strategy or dependency.

## Findings fixed

### Major — Shared Android resources, helpers and providers collided

The standalone apps reuse resource identifiers and provider classes; ETH Wallet and AtomiX also contain different implementations of the same helper package. `scripts/import-pandamonium.py` prefixes resources, relocates those helpers, generates separate provider classes/authorities, and makes feature activities private. PandaDEX's bounded SDK remains separate. Compilation and merged-manifest checks passed.

### Major — Internal apps could otherwise target a different wallet

The base SDK historically discovers the classic and block packages. Bundled callers now register only with `org.minimarex.pandamonium`; PandaDEX's separate SDK and node-launch/file-grant targets also point there. Unit tests verify that other running wallets cannot receive integrated commands, and the embedded client does not fall back when its node is unavailable.

The receiver provisions its private pairing at startup using the existing secure random generator and receiver database. It does not grant internal privileges based on an incoming broadcast's package-name claim. Existing token and sender checks remain intact.

### Major — Shared keystore alias could destroy another app's secrets

ETH Wallet recovery deletes its master-key alias when its vault is unreadable. The standalone ETH Wallet and casino both used the default alias. Their imported versions now use distinct aliases, preserving their individual recovery behavior without cross-app key deletion.

### Major — Shared transport cache allowed cross-app cleanup

PandaPools and PandaDEX used the same `node-transport` cache directory. Each now has its own directory and transport process. The casino foreground notification also has a distinct ID so it cannot replace the node notification.

### Major — Internal registration could time out during first startup

The emulator exposed delayed Android broadcast delivery during startup. An authenticated embedded-node registration now completes immediately on reply, with a ten-second timeout instead of the external discovery window. External classic/block discovery is unchanged. Regression tests verify immediate connection and exactly-once completion even after the timeout callback runs.

### Minor — Drawer header overlapped the status bar

DrawerLayout does not position its children using its own padding. The shared shell now applies system-bar margins to both content and drawer panel, and forwards remaining bottom/IME insets to native screens. The emulator test checks hamburger clearance on every screen.

### Minor — Imported tests referenced standalone filesystem paths

PandaDEX's archived creation fixtures and Entropy's wordlist path were adapted to the imported module layout. No assertions were removed. The importer reproduces these changes and original source hashes.

## Validation

- `:app:assemblePandamoniumRelease` and `:app:lintPandamoniumRelease`: pass.
- Unit tests: 1,638 passed across all three base debug flavors, SDK and available imported suites.
- Release lint: no errors; 317 warnings remain, chiefly inherited text/resource/layout warnings. Bouncy Castle dependency trust-manager warnings also remain; no new network trust manager was introduced.
- Offline disposable Android 36.1 emulator: node startup; real block-key-use and SQL-storage flags; internal registration and `status`; all eleven native destinations through the right drawer; retained minimaCore activity; Back closes drawer; status-bar clearance. Result: `OK (1 test)`.
- One launcher; all feature activities non-exported; five unique providers; backups disabled.
- Import reproducibility: `python3 scripts/import-pandamonium.py --verify` passed.
- Signature verified against the existing Minima family release certificate, SHA-256 `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- Artifact: `dist/pandamonium-1.8.0.apk`, version name `1.8.0-Pandamonium`, version code 70, package `org.minimarex.pandamonium`.

## Limits and verdict

Approve the local integrated build. Mainnet transaction flows, extended background settlement, restore scenarios and long-running device memory behavior have not been exercised as one combined APK. Existing financial unit tests passed; this emulator run did not send funds or sign transactions. All bundled features share one Android UID and node wallet, as documented in `integrated/README.md`.

No physical phone was modified. No store publication or new remote release was performed for Pandamonium.
