# Keets build preparation review — 2026-10-18

## Summary

Reviewed the preparation helper, the retained fetch/pack scripts, dependency and
output pins, npm lock, Gradle task ordering, APK verifier, tests and build guide.
The bundle and every linked addon are byte-identical to the working Fold repair.
All 13 regular files inside the repacked Bare Kit AAR also match the previously
used runtime; only ZIP container metadata/compression changed.

## Findings resolved during implementation

- Fresh worktrees previously omitted ignored Keets binaries. Gradle now prepares
  and verifies them before asset merging, JNI merging and AAR transforms.
- Global tools, sibling source and version-marker-only runtime checks were not
  reproducible. Immutable source archives, Node/npm, tools lock, runtime archive,
  npm package integrity and final output hashes now establish every Keets input.
- APK verification now checks all nested addon versions and runtime code/data
  after Android strips debug symbols. Rejected generated APKs are removed.
- The combined Atelier/Salon build needed the same two metadata exclusions already
  present in both standalone app build files. Those exact exclusions were reused.
- Isolated npm configuration uses separate empty user/global files. The packer's
  imports map retains its required `.json` extension in a disposable directory.

No unresolved blocking findings in this build-system change.

## Evidence

- 19 artifact/APK tests and nine preparation tests pass. Coverage includes missing
  nested addon versions, truncated bundles, wrong architectures, changed native
  code, corrupt downloads/caches, failed staged builds, and offline cache misses.
- A real CLI test rejected and removed an intentionally corrupt generated test APK.
- A real offline empty-cache invocation failed clearly before creating app inputs.
- The pinned worklet rebuilt offline using cached archives/npm packages and matched
  the existing bundle SHA-256 `b64b381c10c5a08ea229ee88742549a9b76a131ef8ebefd949fbd36637dbbf1e`.
- Clean integration worktree `build/keets-clean-integration-2026-10-18`: all generated
  Keets inputs and the chosen dependency cache were absent. The normal three-flavor
  Gradle assemble command downloaded, verified, prepared and built everything;
  953 tasks executed successfully.
- Clean stable worktree `build/keets-clean-stable-2026-10-18`: no generated Keets inputs.
  The normal three-flavor Gradle assemble command with `--offline` restored the
  verified shared cache and completed all 883 tasks successfully.
- All six final APKs passed exact bundle and native code/data checks, family release
  signature verification, package/version checks, non-debuggable checks and 16 KiB
  ZIP alignment. Version values are unchanged (integration 1.9.84/155, stable 1.9.85/156).
- Tests and clean builds ran on macOS arm64. Checksummed Node pins also cover macOS
  x64 and Linux arm64/x64; those hosts were not executed in this session.

Local evidence is in `apks/base/build/keets-clean-{integration,stable}-build.log`
and the corresponding `*-validation.json` files. Generated binaries, caches and
credentials are excluded from Git. No APK was installed, copied into release dist,
or published as part of this change.

## Verdict

Approve. The build preparation requirements are verified. This review does not
claim completion of the ongoing Atelier/Salon feature and language integration.
