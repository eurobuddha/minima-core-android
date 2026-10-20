# Keets in PandaBear, BlackBear and Pandamonium

Normal Gradle builds prepare Keets automatically. This applies to the stable repair
branch and the Atelier/Salon integration branch. No sibling Keets checkout, global
npm installation, GitHub login, or manual binary copying is required.

## Build

Use the existing Android build prerequisites: macOS or Linux (arm64 or x64), Bash,
Python 3.9+, curl, the Android SDK, and a compatible JDK (the project's Android
Studio JBR works). Gradle and the Android Gradle plugin retain their pinned versions;
the Gradle wrapper now also checks its distribution checksum.

```sh
./gradlew :app:assembleClassicRelease :app:assembleBlockRelease :app:assemblePandamoniumRelease
```

Release signing continues to use the existing `MINIMA_FAMILY_RELEASE_*` Gradle
properties outside Git. This change does not create signing credentials, alter
app versions, install applications, or publish artifacts.

For each build, `prepareKeets` runs before asset/JNI merging and the generated
Bare AAR dependency's transforms. `verifyKeetsArtifacts` then checks pinned input
hashes and bundle/addon completeness. Each variant's assemble task also verifies
the actual APK: exact bundle bytes, every required addon version, ABI correctness,
and native runtime code/data matching the source libraries after symbol stripping.
Any preparation or verification failure fails the build. The Gradle APK verifier
also removes a rejected APK from its generated output directory. Treat only a
successful assemble command as a usable artifact.

## Pins and checksums

`scripts/keets-dependencies.lock.json` records:

- Keets source commit `d10f32afd81f48c1bf66c2b7a7bdf6231b2586d9`, its archive SHA-256,
  and its npm lockfile SHA-256. The source's complete dependency tree uses npm
  integrity hashes, verified by `npm ci`.
- Node **24.8.0** and its bundled npm **11.6.0**, with official per-host archive hashes.
- Bare **1.34.1**, bare-pack **2.2.4**, and the complete tools dependency lock in
  `scripts/keets-tools/`. Every downloaded npm package has an integrity checksum.
- Bare Kit **2.5.5**, with the GitHub release asset SHA-256. Its Android AAR is
  repacked deterministically without changing runtime files.
- SHA-256 hashes for the resulting bundle, runtime AAR and all 21 required native
  addon versions for each of the three ABIs. The native addons retain the Fold-tested Keets repair bytes. The 1.2.2
  bundle also recovers failed/closed media storage while preserving chat and public
  Salon drives, hosted files and pin subscriptions. See the source recovery record.

Preparation reuses `fetch-bare-kit.sh` and `pack-keets-worklet.sh`. The packer still
walks dependencies recursively: both versions of nested addons such as bare-signals,
bare-tcp and sodium-native must be retained. `verify-keets-artifacts.py` derives
requirements from the actual bundle instead of assuming one version per module.

Downloads are checked before extraction. npm runs with isolated configuration and
`--ignore-scripts`; host executables and native prebuilds come from the integrity-locked
packages. No install hook can silently download an unpinned binary. Partial builds
are staged away from app inputs and installed only after all output hashes match.

## Cache and offline builds

The default shared cache is `~/.gradle/caches/minima-keets`. Set `KEETS_CACHE_DIR`
to use another location. All generated files and caches stay outside Git. Source
outputs remain under the existing ignored asset, JNI and AAR paths.

```sh
./gradlew --offline :app:assemblePandamoniumRelease
# Or prepare/verify inputs without assembling:
python3 scripts/prepare-keets.py --offline
python3 scripts/verify-keets-artifacts.py
```

`--offline` is passed from Gradle to Keets. `KEETS_OFFLINE=1` also enables it for
manual scripts. Already verified local outputs require no tools or network. A fresh
worktree can restore verified outputs from the shared cache. If only the downloaded
source/tool/runtime archives and npm cache are present, preparation can rebuild
from those offline. Gradle's own Android/Maven dependencies must also be cached.

A cache miss offline fails with the missing URL. A checksum mismatch fails clearly;
no corrupt cached download or prepared output is trusted. Remove the specific corrupt
cache file/directory identified by the error and rerun a connected build. Concurrent
preparations use a shared cache lock; cached outputs are published atomically.

The existing `scripts/fetch-bare-kit.sh` and `scripts/pack-keets-worklet.sh` remain
supported entry points and use the same pins/cache. They no longer accept floating
runtime versions, arbitrary sibling source or globally installed packing tools.

## Verification and pin updates

```sh
python3 scripts/test-keets-artifacts.py
python3 scripts/test-keets-preparation.py
python3 scripts/verify-keets-artifacts.py --apk-dir app/build/outputs/apk/pandamonium/release
```

Pin updates are explicit reviewed source changes. Update the immutable source/runtime
hashes and tools lock together, rebuild in isolation, inspect every changed bundle
and native component, then record new output hashes. Never bypass the checksum gate
or accept new hashes automatically during a normal build. Test a fresh worktree,
offline restore/rebuild, and all three assembled APKs before pushing an update.
