PandaPools MiniDapp 0.6.32 fixes the remaining legacy 262144 limits in the actual signing boundary, recovery confirmation and stored use floor. Block-mode keys are checked against their own tree capacity. Legacy exhaustion, restored-key quarantine and counter-regression checks remain enforced. All 32 tests pass.

PandaBear uses classic wallet keys; BlackBear uses block-as-key-uses. The same seed produces different addresses across these modes. Restore with the matching mode and close or migrate existing pools while their original owner keys can still sign.

[Full PandaBear / BlackBear explainer](https://github.com/eurobuddha/minima-core-android/blob/ui/full-redesign/PANDABEAR_BLACKBEAR.md)
