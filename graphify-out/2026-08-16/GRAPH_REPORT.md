# Graph Report - base  (2026-08-09)

## Corpus Check
- 72 files · ~26,994 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 661 nodes · 1257 edges · 56 communities (25 shown, 31 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 22 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `b326c7ae`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Context
- CoinsAdapter
- MainActivity
- MinimaService
- ReceiverDB
- BalanceAdapter
- MinimaAPI
- LogsView
- BroadcastReceiver
- CoinsDialog
- ImageLoader
- StartServiceActivity.java
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
- AppCompatActivity
- MinimaApplication
- BroadcastReceiver
- BaseView
- TokenMeta
- TokenSpinnerAdapter
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
- WebValidate
- Intent
- JSONObject
- Minima
- Override

## God Nodes (most connected - your core abstractions)
1. `MinimaService` - 40 edges
2. `MainActivity` - 36 edges
3. `CoinsDialog` - 25 edges
4. `ReceiverDB` - 23 edges
5. `MinimaReceiver` - 20 edges
6. `BaseView` - 19 edges
7. `SendView` - 17 edges
8. `BalanceAdapter` - 17 edges
9. `MinimaAPI` - 17 edges
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

## Communities (56 total, 31 thin omitted)

### Community 1 - "CoinsAdapter"
Cohesion: 0.20
Nodes (8): CoinsAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup

### Community 2 - "MainActivity"
Cohesion: 0.10
Nodes (10): IBinder, Override, TextView, MainActivity, ComponentName, MainAdapter, Menu, MenuItem (+2 more)

### Community 3 - "MinimaService"
Cohesion: 0.07
Nodes (25): Alarm, MinimaReceiver, IBinder, JSONObject, Override, MinimaService, MyBinder, Binder (+17 more)

### Community 4 - "ReceiverDB"
Cohesion: 0.09
Nodes (16): AppsAdapter, Context, JSONArray, LayoutInflater, Override, View, ViewGroup, Context (+8 more)

### Community 5 - "BalanceAdapter"
Cohesion: 0.06
Nodes (23): BalanceAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup (+15 more)

### Community 6 - "MinimaAPI"
Cohesion: 0.14
Nodes (11): Context, Intent, MinimaAPI, JSONObject, MinimaAPIListener, MinimaAPILogger, Context, Intent (+3 more)

### Community 7 - "LogsView"
Cohesion: 0.07
Nodes (21): Override, Activity, Button, EditText, JSONObject, Override, ScrollView, TextView (+13 more)

### Community 8 - "BroadcastReceiver"
Cohesion: 0.23
Nodes (9): Alarm, Context, Intent, Override, BootReceiver, Context, Intent, Override (+1 more)

### Community 9 - "CoinsDialog"
Cohesion: 0.15
Nodes (10): AlertDialog, CoinsDialog, Activity, JSONArray, JSONObject, TextView, Result, CoinDetailDialog (+2 more)

### Community 10 - "ImageLoader"
Cohesion: 0.16
Nodes (9): Identicon, Bitmap, ImageLoader, Activity, Bitmap, ImageView, Canvas, LruCache (+1 more)

### Community 11 - "StartServiceActivity.java"
Cohesion: 0.11
Nodes (14): Bundle, ComponentName, IBinder, Override, ProgressDialog, SeedSyncServiceActivity, Bundle, ComponentName (+6 more)

### Community 13 - "SendView"
Cohesion: 0.08
Nodes (18): ActivityResultLauncher, Override, SendActivity, Activity, JSONArray, Override, TextView, SendView (+10 more)

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

### Community 27 - "AppCompatActivity"
Cohesion: 0.05
Nodes (35): Bundle, Override, LauncherActivity, Bundle, Override, NewWalletActivity, Bundle, EditText (+27 more)

### Community 28 - "MinimaApplication"
Cohesion: 0.36
Nodes (4): Context, Override, MinimaApplication, Application

### Community 30 - "BaseView"
Cohesion: 0.06
Nodes (25): BaseView, Activity, View, Override, View, ViewGroup, MainAdapter, AppsView (+17 more)

### Community 31 - "TokenMeta"
Cohesion: 0.23
Nodes (4): IconResolver, JSONObject, TokenMeta, Pattern

### Community 32 - "TokenSpinnerAdapter"
Cohesion: 0.31
Nodes (5): Context, JSONArray, JSONObject, TokenSpinnerAdapter, ArrayAdapter

## Knowledge Gaps
- **1 isolated node(s):** `MinimaAPIMessages`
  These have ≤1 connection - possible missing edges or undocumented components.
- **31 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MinimaService` connect `MinimaService` to `BroadcastReceiver`, `MainActivity`, `StartServiceActivity.java`?**
  _High betweenness centrality (0.114) - this node is a cross-community bridge._
- **Why does `MainActivity` connect `MainActivity` to `MinimaService`, `LogsView`, `StartServiceActivity.java`, `AppCompatActivity`, `BaseView`?**
  _High betweenness centrality (0.073) - this node is a cross-community bridge._
- **Why does `ReceiverDB` connect `ReceiverDB` to `AppCompatActivity`?**
  _High betweenness centrality (0.067) - this node is a cross-community bridge._
- **What connects `MinimaAPIMessages` to the rest of the system?**
  _1 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.10099573257467995 - nodes in this community are weakly interconnected._
- **Should `MinimaService` be split into smaller, more focused modules?**
  _Cohesion score 0.06830601092896176 - nodes in this community are weakly interconnected._
- **Should `ReceiverDB` be split into smaller, more focused modules?**
  _Cohesion score 0.09102564102564102 - nodes in this community are weakly interconnected._