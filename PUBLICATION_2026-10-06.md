# PandaBear / BlackBear publication — 2026-10-06

Source changes, reviewed fixes and release artifacts are committed, pushed and published. The [PandaBear / BlackBear explainer](PANDABEAR_BLACKBEAR.md) is attached to both core releases and linked from their store descriptions.

## Releases

| Release | Source commit | Public release |
| --- | --- | --- |
| PandaBear 1.7.4-PandaBear | `ead5dce1bd671e5d246bd56cc090704aa72c5f27` | [APK, SDK and checksums](https://github.com/eurobuddha/minima-core-android/releases/tag/v1.7.4-PandaBear) |
| BlackBear 1.7.4-BlackBear | `ead5dce1bd671e5d246bd56cc090704aa72c5f27` | [APK and checksum](https://github.com/eurobuddha/minima-core-android/releases/tag/v1.7.4-BlackBear) |
| AtomiX 0.1.71 | `2c504caefe9ff8c06a2e23f05abf647ca5126bcc` | [APK and checksum](https://github.com/eurobuddha/minima-core-android-atomix/releases/tag/v0.1.71) |
| PandaPools Android 0.9.64 | `dd6928ebc7273808c4b542169f2b0ca0c756261a` | [APK and checksum](https://github.com/eurobuddha/minima-core-android-pandapools/releases/tag/v0.9.64) |
| PandaPools MiniDapp 0.6.32 | `e6498c4ed12937dd171fa4e40e3df5b03d1d8e15` | [MiniDapp archive](https://github.com/eurobuddha/pandapools-mds/releases/tag/v0.6.32) |

The bundled Minima core is **1.1.2.31-rex3**, commit `8d088065c`, pushed to the core fork's `merge/upstream-1.1.2.31` branch. Both core APKs use version code **69**. PandaBear remains the repository's latest release for existing classic wallets; BlackBear has its own release and separate store entry.

## Stores

- Native APK catalog: `eurobuddha/minima-core-apks` commit `ec442fec8c17fe9af10d61221eaa9312321968c6`, used by the web store and PandaApps. Includes all four updated APKs and preserves the concurrent minimaDocs 0.3.1 publication. Catalog validation passed for **52 entries and 41 binaries**.
- MiniDapp catalog: `eurobuddha/dappstore` commit `68390f4091b203498d8216c738b991212dc3a582`. PandaPools 0.6.32 is verified live at both [primary](https://eurobuddha.com/pandadapps.json) and [mirror](https://store.eurobuddha.com/pandadapps.json) catalogs.
- [IPFS store](https://ipfs.eurobuddha.com/) published and IPNS updated to `bafybeieb7xx3v4dfvtpszl7dxyjde5c52kzvzj7gv32ijoadcjlzydsfl4`. All four APKs and the MiniDapp archive were downloaded from this immutable snapshot and their SHA-256 hashes matched the released artifacts. The live IPFS catalogs also contain the new versions.

The IPFS host initially received a stale GitHub CDN response for the native catalog. The existing deployed publisher was run with only its catalog URL replaced in memory by the exact validated commit URL. Its installed configuration was unchanged. Optional Filebase remote pinning reported an unavailable pin list; the local IPFS pin, IPNS publication and public downloads succeeded.

## Evidence and limits

- [Review findings, test results and limitations](REVIEW_BLACKBEAR_2026-10-06.md)
- [Public download hashes and catalog readbacks](review/2026-10-06/public-download-verification.json)
- [Catalog validation](review/2026-10-06/store-check.log)
- [Final IPFS publication log](review/2026-10-06/ipfs-publish-pinned.log)

Automated tests, release builds and lint passed as recorded in the review. No device installation, multi-day device soak or mainnet-scale import test was performed for this publication. Pre-existing graph/cache changes and unrelated local artifacts were left out of the release commits.
