---
type: "query"
date: "2026-10-07T12:13:43.826437+00:00"
question: "What caused the October 7 Pandamonium navigation, PandaPools metadata and Mail identity issues?"
contributor: "graphify"
outcome: "corrected"
correction: "Use current source paths and distinguish updated SDK registration tests from untested identity and funded flows."
source_nodes: ["MainActivity", "MinimaReceiver", "MinimaAPI"]
---

# Q: What caused the October 7 Pandamonium navigation, PandaPools metadata and Mail identity issues?

## Answer

Source verification: the graph predates the namespace migration. Current code uses com/eurobuddha. Fold Mail 0.5.8 and NFT Wallet 0.1.7 targeted the old core package; updated in place to 0.5.9 and 0.1.8, and NFT Wallet was observed reading live node balances. New local Pandamonium 1.9.5 adds toolbar-row navigation, replaces Terminal with the existing History screen, and decodes serialized PandaPools token metadata. Mail 0.5.10 adds non-resetting encrypted persistence of derived identity keys. Git blame dates the old memory-only/manual fallback behavior to June 27; loss of Admin access after the new core package is a probable trigger, not verified on the Fold. New APKs remain local, uninstalled. Vesta vs Vestr identity is awaiting user clarification; do not silently substitute. Preserve all release history and backups. See review/2026-10-07/PANDAMONIUM_1.9.5_FIX_REVIEW.txt.

## Outcome

- Signal: corrected
- Correction: Use current source paths and distinguish updated SDK registration tests from untested identity and funded flows.

## Source Nodes

- MainActivity
- MinimaReceiver
- MinimaAPI