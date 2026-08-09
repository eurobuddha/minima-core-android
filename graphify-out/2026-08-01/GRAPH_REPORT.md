# Graph Report - base  (2026-08-01)

## Corpus Check
- 71 files · ~26,152 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 636 nodes · 1251 edges · 51 communities (33 shown, 18 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 39 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `94a88678`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- StartServiceActivity
- MinimaCMDListener
- MainActivity
- MinimaService
- ReceiverDB
- BalanceAdapter
- MinimaReceiver
- LogsView
- BaseView
- CoinsDialog
- ImageLoader
- SeedSyncServiceActivity
- ParamsActivity
- SendView
- minimacore/ExampleInstrumentedTest.java
- gradlew
- minimaapi/ExampleInstrumentedTest.java
- minimacore/ExampleUnitTest.java
- minimaapi/ExampleUnitTest.java
- CoinDetailDialog
- SeedSyncActivity.java
- Bundle
- TerminalActivity.java
- AppCompatActivity
- MinimaApplication
- LauncherActivity
- AppsView
- TokenMeta
- TokenSpinnerAdapter
- logger.java
- Bundle
- ComponentName
- IBinder
- ProgressDialog
- WebValidate
- Handler
- Button
- ImageView
- BalanceAdapter
- TerminalView.java
- LogBuffer
- HomeView.java
- Activity
- Button
- JSONArray
- TextView
- JSONObject

## God Nodes (most connected - your core abstractions)
1. `MinimaService` - 38 edges
2. `MainActivity` - 36 edges
3. `ReceiverDB` - 28 edges
4. `CoinsDialog` - 25 edges
5. `MinimaReceiver` - 23 edges
6. `BaseView` - 19 edges
7. `MinimaAPI` - 18 edges
8. `SendView` - 17 edges
9. `BalanceAdapter` - 17 edges
10. `SeedSyncServiceActivity` - 16 edges

## Surprising Connections (you probably didn't know these)
- `BalanceView` --references--> `CoinsDialog`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/views/balance/BalanceView.java → app/src/main/java/org/minimarex/minimacore/main/views/balance/coins/CoinsDialog.java
- `CoinsDialog` --references--> `CoinDetailDialog`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/views/balance/coins/CoinsDialog.java → app/src/main/java/org/minimarex/minimacore/main/views/balance/coins/CoinDetailDialog.java
- `MainAdapter` --references--> `BaseView`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/MainAdapter.java → app/src/main/java/org/minimarex/minimacore/main/BaseView.java
- `AppsView` --inherits--> `BaseView`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/views/apps/AppsView.java → app/src/main/java/org/minimarex/minimacore/main/BaseView.java
- `HomeView` --inherits--> `BaseView`  [EXTRACTED]
  app/src/main/java/org/minimarex/minimacore/main/views/home/HomeView.java → app/src/main/java/org/minimarex/minimacore/main/BaseView.java

## Import Cycles
- None detected.

## Communities (51 total, 18 thin omitted)

### Community 0 - "StartServiceActivity"
Cohesion: 0.27
Nodes (6): Bundle, ComponentName, IBinder, Override, ProgressDialog, StartServiceActivity

### Community 1 - "MinimaCMDListener"
Cohesion: 0.26
Nodes (4): Override, MinimaCMD, JSONObject, MinimaCMDListener

### Community 2 - "MainActivity"
Cohesion: 0.08
Nodes (16): Override, TextView, MainActivity, Override, View, ViewGroup, MainAdapter, ComponentName (+8 more)

### Community 3 - "MinimaService"
Cohesion: 0.08
Nodes (23): Alarm, Context, Intent, Override, BroadcastReceiver, Context, Handler, IBinder (+15 more)

### Community 4 - "ReceiverDB"
Cohesion: 0.08
Nodes (15): AppsAdapter, Context, JSONArray, LayoutInflater, Override, View, ViewGroup, Context (+7 more)

### Community 5 - "BalanceAdapter"
Cohesion: 0.08
Nodes (20): BalanceAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup (+12 more)

### Community 6 - "MinimaReceiver"
Cohesion: 0.07
Nodes (23): Context, Intent, JSONObject, Minima, Override, MinimaReceiver, BootReceiver, Context (+15 more)

### Community 7 - "LogsView"
Cohesion: 0.18
Nodes (9): Activity, Button, EditText, JSONObject, Override, ScrollView, TextView, LogsView (+1 more)

### Community 8 - "BaseView"
Cohesion: 0.24
Nodes (5): BaseView, Activity, View, Activity, PendingView

### Community 9 - "CoinsDialog"
Cohesion: 0.08
Nodes (19): CoinsAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup (+11 more)

### Community 10 - "ImageLoader"
Cohesion: 0.16
Nodes (9): Identicon, Bitmap, ImageLoader, Activity, Bitmap, ImageView, Canvas, LruCache (+1 more)

### Community 11 - "SeedSyncServiceActivity"
Cohesion: 0.23
Nodes (6): Bundle, ComponentName, IBinder, Override, ProgressDialog, SeedSyncServiceActivity

### Community 12 - "ParamsActivity"
Cohesion: 0.33
Nodes (5): Bundle, EditText, Override, ParamsActivity, SwitchMaterial

### Community 13 - "SendView"
Cohesion: 0.08
Nodes (21): Activity, ActivityResultLauncher, Activity, Override, TextView, ReceiveView, Override, SendActivity (+13 more)

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
Cohesion: 0.33
Nodes (6): CoinDetailDialog, Activity, AlertDialog, JSONObject, LayoutInflater, LinearLayout

### Community 24 - "SeedSyncActivity.java"
Cohesion: 0.26
Nodes (7): Bundle, Button, EditText, Override, SeedSyncActivity, Context, Peers

### Community 26 - "TerminalActivity.java"
Cohesion: 0.36
Nodes (6): Bundle, EditText, Override, ScrollView, TextView, TerminalActivity

### Community 27 - "AppCompatActivity"
Cohesion: 0.15
Nodes (11): Bundle, Override, NewWalletActivity, Bundle, EditText, Override, RestoreWalletSyncActivity, Bundle (+3 more)

### Community 28 - "MinimaApplication"
Cohesion: 0.36
Nodes (4): Context, Override, MinimaApplication, Application

### Community 29 - "LauncherActivity"
Cohesion: 0.47
Nodes (3): Bundle, Override, LauncherActivity

### Community 30 - "AppsView"
Cohesion: 0.24
Nodes (7): AppsView, Activity, JSONObject, ListView, Override, AppsAdapter, ReceiverDB

### Community 31 - "TokenMeta"
Cohesion: 0.23
Nodes (4): IconResolver, JSONObject, TokenMeta, Pattern

### Community 32 - "TokenSpinnerAdapter"
Cohesion: 0.31
Nodes (5): Context, JSONArray, JSONObject, TokenSpinnerAdapter, ArrayAdapter

### Community 33 - "logger.java"
Cohesion: 0.19
Nodes (7): Bundle, EditText, Override, NewWalletRestoreActivity, Context, logger, ServiceConnection

### Community 43 - "TerminalView.java"
Cohesion: 0.43
Nodes (5): Activity, EditText, ScrollView, TextView, TerminalView

### Community 45 - "HomeView.java"
Cohesion: 0.53
Nodes (4): HomeView, Activity, SimpleDateFormat, TextView

## Knowledge Gaps
- **1 isolated node(s):** `MinimaAPIMessages`
  These have ≤1 connection - possible missing edges or undocumented components.
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `MinimaService`, `logger.java`, `TerminalActivity.java`, `AppCompatActivity`?**
  _High betweenness centrality (0.094) - this node is a cross-community bridge._
- **Why does `ReceiverDB` connect `ReceiverDB` to `MinimaService`, `AppCompatActivity`, `LauncherActivity`, `MinimaReceiver`?**
  _High betweenness centrality (0.091) - this node is a cross-community bridge._
- **Why does `MinimaService` connect `MinimaService` to `StartServiceActivity`, `logger.java`, `ReceiverDB`, `MinimaReceiver`, `SeedSyncServiceActivity`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **What connects `MinimaAPIMessages` to the rest of the system?**
  _1 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.07529411764705882 - nodes in this community are weakly interconnected._
- **Should `MinimaService` be split into smaller, more focused modules?**
  _Cohesion score 0.07653061224489796 - nodes in this community are weakly interconnected._
- **Should `ReceiverDB` be split into smaller, more focused modules?**
  _Cohesion score 0.08478513356562137 - nodes in this community are weakly interconnected._