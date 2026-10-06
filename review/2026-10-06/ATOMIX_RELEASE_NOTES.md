AtomiX 0.1.71 supports the PandaBear and BlackBear node packages through the updated companion SDK. It discovers one responding node and pins all commands there; if both reply, it refuses commands rather than sending a trade to an ambiguous wallet. Node-launch links also work on block-only installations.

Fixes an Android 9–11 incompatibility in contract timestamp decoding while preserving exact overflow rejection. Camera hardware is optional. 260 tests, release build and full lint pass.

PandaBear keeps the classic wallet mode; BlackBear is a separate app with block-as-key-uses. The same seed produces different addresses. Complete all active swaps before changing wallet mode, then establish a valid identity in the destination wallet and share the new maker key with counterparties. Run one node at a time.

[Full PandaBear / BlackBear explainer](https://github.com/eurobuddha/minima-core-android/blob/ui/full-redesign/PANDABEAR_BLACKBEAR.md)
