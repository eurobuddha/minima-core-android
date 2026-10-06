PandaPools Android 0.9.64 supports PandaBear and BlackBear through the updated companion SDK. Commands are pinned to one responding node; ambiguous two-node discovery refuses commands. Node-launch links work on block-only installations.

Includes the per-key capacity support from 0.9.62–0.9.63: healthy 128⁴ block-mode keys are no longer judged against the legacy 64³ limit. Counter-regression checks, restored-key quarantine and automatic-exit restrictions remain enforced. 299 tests, release build and full lint pass.

PandaBear is the classic wallet app; BlackBear is a separate block-as-key-uses app. The same seed produces different addresses. Close or migrate positions while the original wallet still holds the required owner keys. Run one node at a time.

[Full PandaBear / BlackBear explainer](https://github.com/eurobuddha/minima-core-android/blob/ui/full-redesign/PANDABEAR_BLACKBEAR.md)
