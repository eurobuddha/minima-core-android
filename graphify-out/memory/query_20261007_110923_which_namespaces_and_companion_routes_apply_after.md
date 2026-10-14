---
type: "query"
date: "2026-10-07T11:09:23.642633+00:00"
question: "Which namespaces and companion routes apply after the family migration?"
contributor: "graphify"
outcome: "corrected"
correction: "The existing graph source paths predate the namespace migration. Use com/eurobuddha paths and the current README/review report; do not restore historical package IDs."
source_nodes: ["MinimaAPI", "MinimaReceiver", "MinimaApplication"]
---

# Q: Which namespaces and companion routes apply after the family migration?

## Answer

Expanded graph vocabulary: api application backup receiver register restore. Current source and final 1.9.4 core candidates use com.eurobuddha.minimacore, com.eurobuddha.minimablock and com.eurobuddha.pandamonium. SDK source is minimaapi/src/main/java/com/eurobuddha/minimaapi. All external companions remain supported; registration discovers three candidates and commands pin one responder. PandaDEX retains its custom bounded SDK. Forty-one new APKs were scanned without the former namespace; forty are family-signed and original Mininotes is unsigned. All three core variants passed real Mail and PandaDEX registration/permission tests on a disposable emulator. Review details: review/2026-10-07/NAMESPACE_MIGRATION_REVIEW.txt. Published catalogue, historical archives and Git history are not migrated. Cross-identity wallet/app data migration is not yet verified; nothing was published or installed on physical devices.

## Outcome

- Signal: corrected
- Correction: The existing graph source paths predate the namespace migration. Use com/eurobuddha paths and the current README/review report; do not restore historical package IDs.

## Source Nodes

- MinimaAPI
- MinimaReceiver
- MinimaApplication