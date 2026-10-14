# Graph Report - .  (2026-08-17)

## Corpus Check
- 42 files · ~30,329 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 649 nodes · 1446 edges · 47 communities (33 shown, 14 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 56 edges (avg confidence: 0.83)
- Token cost: 0 input · 288,735 output

## Community Hubs (Navigation)
- Service Binding & App Framework
- Broadcast IPC Receiver
- Main Activity & Tabs
- Node Service & Notifications
- Image Loading & Identicons
- Apps List & SQLite
- Wallet Views & Dialogs
- Coins List Adapter
- BaseView Framework
- Balance Adapter
- Logs Tab View
- Send Activity & QR
- Template Tests
- Token Icon Resolution
- Upstream Adoption Doc (1.6.10)
- UI Changes Write-up Sections
- Token Spinner Adapter
- UI Redesign Concepts
- Coin Detail Dialog
- IPC Change Concepts
- Minima Application & FLAG_SECURE
- List Adapters & Token Utils
- UX Improvement Sections
- H2 VerifyError Cluster
- H2 Fix Write-up Sections
- IPC Write-up Sections
- Repo Instructions & Guardrails
- Gradle Wrapper Script
- IPC Doc Part 2 Sections
- Security Fix Sections
- README & minimaapi
- MinimaAPI Listener
- Node Core Fix Sections
- Stock Icon (hdpi round)
- Stock Icon (mdpi)
- Stock Icon (mdpi round)
- Stock Icon (xhdpi)
- Stock Icon (xxxhdpi)
- Hook Install Script
- Pre-commit Hook
- Stock Icon (hdpi)
- Stock Icon (xhdpi round)
- Stock Icon (xxhdpi)
- Stock Icon (xxhdpi round)
- Stock Icon (xxxhdpi round)

## God Nodes (most connected - your core abstractions)
1. `MinimaService` - 41 edges
2. `MainActivity` - 37 edges
3. `BaseView` - 25 edges
4. `CoinsDialog` - 25 edges
5. `ReceiverDB` - 23 edges
6. `MinimaReceiver` - 23 edges
7. `SeedSyncServiceActivity` - 18 edges
8. `MinimaAPI` - 18 edges
9. `BalanceAdapter` - 17 edges
10. `SendView` - 17 edges

## Surprising Connections (you probably didn't know these)
- `MegaMMR import robustness on constrained heaps` --semantically_similar_to--> `ART VerifyError kills node at boot (H2 2.4.240)`  [INFERRED] [semantically similar]
  MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md → H2_VERIFYERROR_FIX.md
- `Runtime Allow Screenshots toggle (FLAG_SECURE)` --semantically_similar_to--> `Allow Screenshots toggle (conditional FLAG_SECURE)`  [INFERRED] [semantically similar]
  MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md → UPSTREAM_UI_CHANGES.md
- `SendActivity / ReceiveActivity thin hosts around unmodified views` --semantically_similar_to--> `Restyle + navigation: Home/Wallet/Terminal/Apps tabs`  [INFERRED] [semantically similar]
  UPSTREAM_UI_CHANGES.md → MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md
- `IPC change 2: content:// file hand-off for large results` --references--> `minimaapi.aar client library (MinimaAPI)`  [INFERRED]
  UPSTREAM_IPC_CHANGES.md → README.md
- `Logs tab — live on-device node log tail` --conceptually_related_to--> `org.minimarex.minimacore.NOTIFY push broadcast`  [INFERRED]
  MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Broadcast-Intent IPC layer changes (Part 2)** — minimacore_changes_for_upstream_1_6_10_ui_h2_ipc_off_main_thread_executor, minimacore_changes_for_upstream_1_6_10_ui_h2_ipc_file_handoff, minimacore_changes_for_upstream_1_6_10_ui_h2_file_bridge, minimacore_changes_for_upstream_1_6_10_ui_h2_max_response_len_split [EXTRACTED 1.00]
- **Android app security fixes (Part 3)** — minimacore_changes_for_upstream_1_6_10_ui_h2_allowbackup_off, minimacore_changes_for_upstream_1_6_10_ui_h2_allow_screenshots_toggle, minimacore_changes_for_upstream_1_6_10_ui_h2_h2_pin, minimacore_changes_for_upstream_1_6_10_ui_h2_clean_boot_hazard [EXTRACTED 1.00]
- **UI full redesign (ui/full-redesign branch)** — upstream_ui_changes_design_system, upstream_ui_changes_tab_restructure, upstream_ui_changes_send_receive_activities, upstream_ui_changes_token_icons, upstream_ui_changes_screenshots_toggle [EXTRACTED 1.00]

## Communities (47 total, 14 thin omitted)

### Community 0 - "Service Binding & App Framework"
Cohesion: 0.05
Nodes (42): android.app.ProgressDialog, android.content.ComponentName, android.content.ServiceConnection, android.os.Bundle, android.os.IBinder, android.widget.Button, android.widget.EditText, android.widget.ScrollView (+34 more)

### Community 1 - "Broadcast IPC Receiver"
Cohesion: 0.06
Nodes (24): android.content.BroadcastReceiver, android.content.Context, android.content.Intent, android.net.Uri, Intent, JSONObject, Override, ReceiverDB (+16 more)

### Community 2 - "Main Activity & Tabs"
Cohesion: 0.08
Nodes (12): android.view.Menu, android.view.MenuItem, android.view.ViewGroup, androidx.viewpager.widget.ViewPager, Override, MainActivity, Override, MainAdapter (+4 more)

### Community 3 - "Node Service & Notifications"
Cohesion: 0.07
Nodes (19): Alarm, android.app.Notification, android.app.NotificationManager, android.app.PendingIntent, android.app.Service, android.os.Binder, android.os.Handler, BroadcastReceiver (+11 more)

### Community 4 - "Image Loading & Identicons"
Cohesion: 0.09
Nodes (12): android.graphics.Bitmap, android.graphics.Canvas, android.graphics.Paint, android.util.LruCache, android.widget.ImageView, Identicon, ImageLoader, WebValidate (+4 more)

### Community 5 - "Apps List & SQLite"
Cohesion: 0.11
Nodes (9): android.database.Cursor, android.database.sqlite.SQLiteDatabase, android.database.sqlite.SQLiteOpenHelper, AppsAdapter, Override, JSONArray, JSONObject, Override (+1 more)

### Community 6 - "Wallet Views & Dialogs"
Cohesion: 0.15
Nodes (6): android.widget.ListView, androidx.appcompat.app.AlertDialog, Clip, Feedback, Format, org.minima.utils.json.JSONObject

### Community 7 - "Coins List Adapter"
Cohesion: 0.16
Nodes (5): CoinsAdapter, JSONArray, Override, CoinsDialog, Result

### Community 8 - "BaseView Framework"
Cohesion: 0.15
Nodes (4): android.app.Activity, android.view.View, BaseView, PendingView

### Community 9 - "Balance Adapter"
Cohesion: 0.16
Nodes (5): BalanceAdapter, Override, BalanceView, JSONArray, Override

### Community 10 - "Logs Tab View"
Cohesion: 0.15
Nodes (5): Override, LogsView, LogBuffer, Sink, com.google.android.material.chip.ChipGroup

### Community 11 - "Send Activity & QR"
Cohesion: 0.15
Nodes (9): android.widget.Spinner, androidx.activity.result.ActivityResultLauncher, Override, SendActivity, Override, SendView, com.journeyapps.barcodescanner.ScanOptions, ScanOptions (+1 more)

### Community 12 - "Template Tests"
Cohesion: 0.22
Nodes (6): ExampleInstrumentedTest, ExampleUnitTest, ExampleInstrumentedTest, ExampleUnitTest, org.junit.runner.RunWith, org.junit.Test

### Community 13 - "Token Icon Resolution"
Cohesion: 0.26
Nodes (3): IconResolver, TokenMeta, java.util.regex.Pattern

### Community 14 - "Upstream Adoption Doc (1.6.10)"
Cohesion: 0.21
Nodes (11): allowBackup off — node data dir was going to the cloud, Latent -clean boot hazard in extra-params path, Family release key + signature-gated IPC receivers, Field testing, minimaCore — every change from the official build to 1.6.10-ui-h2, Part 5 — Release engineering (ours; listed for completeness, not for adoption), Priority guide — what we'd ship first if we were you, sendsign burns a key leaf per input coin (+3 more)

### Community 15 - "UI Changes Write-up Sections"
Cohesion: 0.17
Nodes (12): 1. Node crash fix — H2 `VerifyError` (functional, ship this regardless of the UI), 2. Design system (all in `res/`, no Java), 3. Tab restructure → `Home / Wallet / Terminal / Apps`, 4. The Wallet tab (`BalanceView` + `view_wallet_balance.xml`), 5. Runtime "Allow Screenshots" toggle (replaces the hard `FLAG_SECURE` rule), Behavioural vs cosmetic — at a glance, Files at a glance, `MainActivity.java` (+4 more)

### Community 16 - "Token Spinner Adapter"
Cohesion: 0.31
Nodes (3): android.widget.ArrayAdapter, TokenSpinnerAdapter, org.minima.utils.json.JSONArray

### Community 17 - "UI Redesign Concepts"
Cohesion: 0.28
Nodes (8): Runtime Allow Screenshots toggle (FLAG_SECURE), Restyle + navigation: Home/Wallet/Terminal/Apps tabs, Wallet token icons + web-validation, Material3 design system in res/ (tokens, Manrope, minima_orange), Allow Screenshots toggle (conditional FLAG_SECURE), SendActivity / ReceiveActivity thin hosts around unmodified views, Tab restructure Home/Wallet/Terminal/Apps (MainAdapter/MainActivity), Token icon pipeline: IconResolver/ImageLoader/Identicon/WebValidate/TokenMeta

### Community 19 - "IPC Change Concepts"
Cohesion: 0.39
Nodes (7): Admin-gated FILE bridge (node base-folder access), Large IPC results as content:// file hand-off, Companion commands off the broadcast main thread, IPC reply cap split — MAX_RESPONSE_LEN 100000, IPC change 3: admin-gated FILE broadcast action, IPC change 2: content:// file hand-off for large results, IPC change 1: single-thread ExecutorService in MinimaReceiver

### Community 20 - "Minima Application & FLAG_SECURE"
Cohesion: 0.38
Nodes (3): android.app.Application, Override, MinimaApplication

### Community 21 - "List Adapters & Token Utils"
Cohesion: 0.38
Nodes (3): android.view.LayoutInflater, android.widget.BaseAdapter, TokenUtils

### Community 22 - "UX Improvement Sections"
Cohesion: 0.29
Nodes (7): 4.1 Restyle + navigation, 4.2 Wallet: token icons + web-validation, 4.3 Wallet: truthful numbers, full identifiers, working send, 4.4 Startup Params screen, 4.5 Logs tab, 4.6 MegaMMR jar rollouts, Part 4 — Android app: UI / UX / node-facing features

### Community 23 - "H2 VerifyError Cluster"
Cohesion: 0.40
Nodes (5): ART VerifyError kills node at boot (H2 2.4.240), H2 dependency downgrade 2.4.240 -> 2.1.214, org.h2.security.SHA256.getPBKDF2, H2 pin 2.4.240 -> 2.1.214 (node never starts), MegaMMR import robustness on constrained heaps

### Community 24 - "H2 Fix Write-up Sections"
Cohesion: 0.33
Nodes (6): Fix, Fix: H2 2.4.240 crashes the Minima node at startup on Android (VerifyError), Notes, Root cause, Symptom, Verification (Galaxy S10+, Android 12)

### Community 25 - "IPC Write-up Sections"
Cohesion: 0.33
Nodes (6): 1. Run companion commands off the broadcast (main) thread, 2. Large results as a `content://` file hand-off (unbounded response size), 3. Admin-gated FILE bridge (node base-folder access for companion apps), Adoption notes, Beyond the IPC layer — the rest of the fork, at a glance, Minima Core Android — IPC changes for full-size command output & node file access

### Community 26 - "Repo Instructions & Guardrails"
Cohesion: 0.40
Nodes (4): Pre-commit version-bump hook (.githooks/pre-commit), RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions., User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly., Versioning guardrail — every code change ships with a version bump

### Community 27 - "Gradle Wrapper Script"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 28 - "IPC Doc Part 2 Sections"
Cohesion: 0.40
Nodes (5): 2.1 Run companion commands off the broadcast (main) thread, 2.2 Large results as a `content://` file hand-off (unbounded response size), 2.3 Admin-gated FILE bridge (node base-folder access for companions), 2.4 Split the reply size cap — replies hand off at 100K chars, Part 2 — Android app: broadcast-IPC layer

### Community 29 - "Security Fix Sections"
Cohesion: 0.40
Nodes (5): 3.1 `allowBackup` off — the node's data dir was going to the cloud, 3.2 Runtime "Allow Screenshots" toggle, 3.3 H2 `VerifyError` — node never starts on some devices, 3.4 Latent `-clean` boot hazard in the extra-params path, Part 3 — Android app: security fixes

### Community 30 - "README & minimaapi"
Cohesion: 0.40
Nodes (4): Logs tab — live on-device node log tail, minima-core-android, minimaapi.aar client library (MinimaAPI), org.minimarex.minimacore.NOTIFY push broadcast

### Community 32 - "Node Core Fix Sections"
Cohesion: 0.50
Nodes (4): 1.1 SECURITY — `Wallet.signData` is an unsynchronized read-modify-write, 1.2 `sendsign` burns a key leaf per input coin, 1.3 MegaMMR import on constrained heaps, Part 1 — Node core (minima.jar) changes

## Knowledge Gaps
- **60 isolated node(s):** `MinimaAPIMessages`, `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`, `Symptom`, `Root cause`, `Fix` (+55 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **14 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MinimaService` connect `Node Service & Notifications` to `Service Binding & App Framework`, `Broadcast IPC Receiver`, `Main Activity & Tabs`, `Wallet Views & Dialogs`, `List Adapters & Token Utils`?**
  _High betweenness centrality (0.108) - this node is a cross-community bridge._
- **Why does `MainActivity` connect `Main Activity & Tabs` to `Service Binding & App Framework`, `Node Service & Notifications`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **Why does `ReceiverDB` connect `Apps List & SQLite` to `Service Binding & App Framework`, `Broadcast IPC Receiver`, `List Adapters & Token Utils`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **What connects `MinimaAPIMessages`, `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`, `Symptom` to the rest of the system?**
  _60 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Service Binding & App Framework` be split into smaller, more focused modules?**
  _Cohesion score 0.05190440627333831 - nodes in this community are weakly interconnected._
- **Should `Broadcast IPC Receiver` be split into smaller, more focused modules?**
  _Cohesion score 0.05829420970266041 - nodes in this community are weakly interconnected._
- **Should `Main Activity & Tabs` be split into smaller, more focused modules?**
  _Cohesion score 0.07678075855689177 - nodes in this community are weakly interconnected._