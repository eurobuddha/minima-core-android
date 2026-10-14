# Graph Report - base  (2026-08-17)

## Corpus Check
- 75 files · ~30,329 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 700 nodes · 1289 edges · 78 communities (42 shown, 36 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 22 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `eff38d03`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Context
- BroadcastReceiver
- MainActivity
- MinimaService
- ReceiverDB
- BalanceAdapter
- MinimaAPI
- LogsView
- MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md
- CoinsDialog
- ImageLoader
- SeedSyncServiceActivity
- Activity
- SendView
- minimacore/ExampleInstrumentedTest.java
- gradlew
- minimaapi/ExampleInstrumentedTest.java
- minimacore/ExampleUnitTest.java
- minimaapi/ExampleUnitTest.java
- CoinDetailDialog
- AlertDialog
- Bundle
- ListView
- logger.java
- MinimaApplication
- BroadcastReceiver
- MainAdapter
- TokenMeta
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- Context
- Bundle
- ComponentName
- Handler
- ProgressDialog
- Intent
- Handler
- Button
- ImageView
- BalanceAdapter
- Minima
- SimpleDateFormat
- IBinder
- MinimaService
- Button
- MinimaServiceListener
- TextView
- JSONObject
- CoinsAdapter
- Intent
- JSONObject
- Minima
- Override
- install.sh
- pre-commit
- StartServiceActivity
- SeedSyncActivity.java
- AppCompatActivity
- Identicon
- MinimaReceiver
- ParamsActivity
- TerminalActivity.java
- RestoreWalletSyncActivity.java
- LauncherActivity.java
- MinimaCMDListener
- BaseView
- AppsView
- Alarm.java
- TerminalView.java
- MinimaServiceListener
- MinimaCMD.java
- StartActivity.java
- MinimaReceiver.java
- Context
- Intent

## God Nodes (most connected - your core abstractions)
1. `MinimaService` - 40 edges
2. `MainActivity` - 36 edges
3. `CoinsDialog` - 25 edges
4. `ReceiverDB` - 23 edges
5. `MinimaReceiver` - 20 edges
6. `BaseView` - 19 edges
7. `MinimaAPI` - 17 edges
8. `SendView` - 17 edges
9. `BalanceAdapter` - 17 edges
10. `SeedSyncServiceActivity` - 16 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `MinimaService`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/MainActivity.java → app/src/main/java/org/minimarex/minimacore/service/MinimaService.java
- `MainActivity` --implements--> `MinimaServiceListener`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/MainActivity.java → app/src/main/java/org/minimarex/minimacore/service/MinimaServiceListener.java
- `BalanceView` --references--> `CoinsDialog`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/views/balance/BalanceView.java → app/src/main/java/org/minimarex/minimacore/main/views/balance/coins/CoinsDialog.java
- `SeedSyncServiceActivity` --references--> `MinimaService`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/launcher/restore/SeedSyncServiceActivity.java → app/src/main/java/org/minimarex/minimacore/service/MinimaService.java
- `StartServiceActivity` --references--> `MinimaService`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/launcher/StartServiceActivity.java → app/src/main/java/org/minimarex/minimacore/service/MinimaService.java

## Import Cycles
- None detected.

## Communities (78 total, 36 thin omitted)

### Community 1 - "BroadcastReceiver"
Cohesion: 0.36
Nodes (5): BootReceiver, Context, Intent, Override, BroadcastReceiver

### Community 2 - "MainActivity"
Cohesion: 0.10
Nodes (11): IBinder, Override, TextView, MainActivity, ComponentName, MainAdapter, Menu, MenuItem (+3 more)

### Community 3 - "MinimaService"
Cohesion: 0.12
Nodes (16): Alarm, IBinder, JSONObject, MinimaService, MyBinder, Binder, Handler, Minima (+8 more)

### Community 4 - "ReceiverDB"
Cohesion: 0.09
Nodes (16): AppsAdapter, Context, JSONArray, LayoutInflater, Override, View, ViewGroup, Context (+8 more)

### Community 5 - "BalanceAdapter"
Cohesion: 0.08
Nodes (18): BalanceAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup (+10 more)

### Community 6 - "MinimaAPI"
Cohesion: 0.12
Nodes (13): android.content.Context, android.content.Intent, android.net.Uri, MinimaAPI, JSONObject, MinimaAPIListener, MinimaAPILogger, Context (+5 more)

### Community 7 - "LogsView"
Cohesion: 0.09
Nodes (16): Activity, Button, EditText, JSONObject, Override, ScrollView, TextView, LogsView (+8 more)

### Community 8 - "MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md"
Cohesion: 0.08
Nodes (25): 1.1 SECURITY — `Wallet.signData` is an unsynchronized read-modify-write, 1.2 `sendsign` burns a key leaf per input coin, 1.3 MegaMMR import on constrained heaps, 2.1 Run companion commands off the broadcast (main) thread, 2.2 Large results as a `content://` file hand-off (unbounded response size), 2.3 Admin-gated FILE bridge (node base-folder access for companions), 2.4 Split the reply size cap — replies hand off at 100K chars, 3.1 `allowBackup` off — the node's data dir was going to the cloud (+17 more)

### Community 9 - "CoinsDialog"
Cohesion: 0.10
Nodes (13): AlertDialog, CoinsDialog, Activity, JSONArray, JSONObject, TextView, Result, Feedback (+5 more)

### Community 10 - "ImageLoader"
Cohesion: 0.16
Nodes (7): ImageLoader, Activity, Bitmap, ImageView, Activity, WebValidate, LruCache

### Community 11 - "SeedSyncServiceActivity"
Cohesion: 0.23
Nodes (6): Bundle, ComponentName, IBinder, Override, ProgressDialog, SeedSyncServiceActivity

### Community 13 - "SendView"
Cohesion: 0.09
Nodes (20): ActivityResultLauncher, Activity, Override, TextView, ReceiveView, Override, SendActivity, Activity (+12 more)

### Community 14 - "minimacore/ExampleInstrumentedTest.java"
Cohesion: 0.60
Nodes (3): ExampleInstrumentedTest, RunWith, Test

### Community 15 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 16 - "minimaapi/ExampleInstrumentedTest.java"
Cohesion: 0.60
Nodes (3): ExampleInstrumentedTest, RunWith, Test

### Community 23 - "CoinDetailDialog"
Cohesion: 0.30
Nodes (6): CoinDetailDialog, Activity, AlertDialog, JSONObject, LayoutInflater, LinearLayout

### Community 27 - "logger.java"
Cohesion: 0.19
Nodes (7): Bundle, EditText, Override, NewWalletRestoreActivity, Context, logger, ServiceConnection

### Community 28 - "MinimaApplication"
Cohesion: 0.36
Nodes (4): Context, Override, MinimaApplication, Application

### Community 30 - "MainAdapter"
Cohesion: 0.19
Nodes (5): Override, View, ViewGroup, MainAdapter, PagerAdapter

### Community 31 - "TokenMeta"
Cohesion: 0.23
Nodes (4): IconResolver, JSONObject, TokenMeta, Pattern

### Community 32 - "User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly."
Cohesion: 0.50
Nodes (3): RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions., User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly., Versioning guardrail — every code change ships with a version bump

### Community 51 - "CoinsAdapter"
Cohesion: 0.20
Nodes (8): CoinsAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup

### Community 58 - "StartServiceActivity"
Cohesion: 0.27
Nodes (6): Bundle, ComponentName, IBinder, Override, ProgressDialog, StartServiceActivity

### Community 59 - "SeedSyncActivity.java"
Cohesion: 0.25
Nodes (7): Bundle, Button, EditText, Override, SeedSyncActivity, Context, Peers

### Community 60 - "AppCompatActivity"
Cohesion: 0.25
Nodes (7): Bundle, Override, NewWalletActivity, Bundle, Override, ReceiveActivity, AppCompatActivity

### Community 61 - "Identicon"
Cohesion: 0.42
Nodes (4): Identicon, Bitmap, Canvas, Paint

### Community 62 - "MinimaReceiver"
Cohesion: 0.17
Nodes (6): MinimaReceiver, Context, Intent, JSONObject, Override, ReceiverDB

### Community 63 - "ParamsActivity"
Cohesion: 0.33
Nodes (5): Bundle, EditText, Override, ParamsActivity, SwitchMaterial

### Community 64 - "TerminalActivity.java"
Cohesion: 0.36
Nodes (6): Bundle, EditText, Override, ScrollView, TextView, TerminalActivity

### Community 65 - "RestoreWalletSyncActivity.java"
Cohesion: 0.32
Nodes (4): Bundle, EditText, Override, RestoreWalletSyncActivity

### Community 66 - "LauncherActivity.java"
Cohesion: 0.43
Nodes (3): Bundle, Override, LauncherActivity

### Community 67 - "MinimaCMDListener"
Cohesion: 0.26
Nodes (4): Override, MinimaCMD, JSONObject, MinimaCMDListener

### Community 68 - "BaseView"
Cohesion: 0.24
Nodes (5): BaseView, Activity, View, Activity, PendingView

### Community 69 - "AppsView"
Cohesion: 0.25
Nodes (6): AppsView, Activity, JSONObject, ListView, Override, AppsAdapter

### Community 70 - "Alarm.java"
Cohesion: 0.39
Nodes (4): Alarm, Context, Intent, Override

### Community 71 - "TerminalView.java"
Cohesion: 0.43
Nodes (5): Activity, EditText, ScrollView, TextView, TerminalView

### Community 73 - "MinimaCMD.java"
Cohesion: 0.43
Nodes (4): HomeView, Activity, SimpleDateFormat, TextView

### Community 74 - "StartActivity.java"
Cohesion: 0.50
Nodes (3): Bundle, Override, StartActivity

## Knowledge Gaps
- **24 isolated node(s):** `Priority guide — what we'd ship first if we were you`, `1.1 SECURITY — `Wallet.signData` is an unsynchronized read-modify-write`, `1.2 `sendsign` burns a key leaf per input coin`, `1.3 MegaMMR import on constrained heaps`, `2.1 Run companion commands off the broadcast (main) thread` (+19 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **36 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MinimaService` connect `MinimaService` to `BroadcastReceiver`, `MainActivity`, `MinimaServiceListener`, `SeedSyncServiceActivity`, `StartServiceActivity`, `logger.java`, `MinimaReceiver`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Why does `MainActivity` connect `MainActivity` to `TerminalActivity.java`, `MinimaService`, `AppsView`, `MinimaServiceListener`, `logger.java`, `AppCompatActivity`?**
  _High betweenness centrality (0.065) - this node is a cross-community bridge._
- **Why does `ReceiverDB` connect `ReceiverDB` to `LauncherActivity.java`?**
  _High betweenness centrality (0.060) - this node is a cross-community bridge._
- **What connects `Priority guide — what we'd ship first if we were you`, `1.1 SECURITY — `Wallet.signData` is an unsynchronized read-modify-write`, `1.2 `sendsign` burns a key leaf per input coin` to the rest of the system?**
  _24 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.09716599190283401 - nodes in this community are weakly interconnected._
- **Should `MinimaService` be split into smaller, more focused modules?**
  _Cohesion score 0.12315270935960591 - nodes in this community are weakly interconnected._
- **Should `ReceiverDB` be split into smaller, more focused modules?**
  _Cohesion score 0.09102564102564102 - nodes in this community are weakly interconnected._