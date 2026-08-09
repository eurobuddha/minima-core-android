# Graph Report - base  (2026-08-01)

## Corpus Check
- 72 files · ~26,749 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 660 nodes · 1264 edges · 51 communities (26 shown, 25 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 25 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f226bb7a`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MinimaReceiver
- CoinsAdapter
- MainActivity
- MinimaService
- ReceiverDB
- BalanceAdapter
- MinimaAPI
- logger.java
- Identicon
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
- MainAdapter
- TokenMeta
- LogBuffer
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

## God Nodes (most connected - your core abstractions)
1. `MinimaService` - 40 edges
2. `MainActivity` - 36 edges
3. `ReceiverDB` - 26 edges
4. `CoinsDialog` - 25 edges
5. `MinimaReceiver` - 20 edges
6. `BaseView` - 19 edges
7. `MinimaAPI` - 18 edges
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

## Communities (51 total, 25 thin omitted)

### Community 0 - "MinimaReceiver"
Cohesion: 0.15
Nodes (8): Context, Intent, JSONObject, Minima, Override, MinimaReceiver, MinimaAPILogger, MinimaAPIMessages

### Community 1 - "CoinsAdapter"
Cohesion: 0.22
Nodes (8): CoinsAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup

### Community 2 - "MainActivity"
Cohesion: 0.11
Nodes (12): IBinder, Override, TextView, MainActivity, Bundle, ComponentName, Intent, MainAdapter (+4 more)

### Community 3 - "MinimaService"
Cohesion: 0.07
Nodes (27): Alarm, Alarm, Context, Intent, Override, BootReceiver, Context, Intent (+19 more)

### Community 4 - "ReceiverDB"
Cohesion: 0.09
Nodes (16): AppsAdapter, Context, JSONArray, LayoutInflater, Override, View, ViewGroup, Context (+8 more)

### Community 5 - "BalanceAdapter"
Cohesion: 0.07
Nodes (20): BalanceAdapter, Activity, JSONArray, JSONObject, LayoutInflater, Override, View, ViewGroup (+12 more)

### Community 6 - "MinimaAPI"
Cohesion: 0.15
Nodes (10): Context, Intent, MinimaAPI, JSONObject, MinimaAPIListener, Context, Intent, Override (+2 more)

### Community 7 - "logger.java"
Cohesion: 0.06
Nodes (34): BaseView, Activity, View, HomeView, Activity, Override, SimpleDateFormat, TextView (+26 more)

### Community 8 - "Identicon"
Cohesion: 0.42
Nodes (4): Identicon, Bitmap, Canvas, Paint

### Community 9 - "CoinsDialog"
Cohesion: 0.10
Nodes (13): AlertDialog, CoinsDialog, Activity, JSONArray, JSONObject, TextView, Result, Feedback (+5 more)

### Community 10 - "ImageLoader"
Cohesion: 0.16
Nodes (7): ImageLoader, Activity, Bitmap, ImageView, Activity, WebValidate, LruCache

### Community 11 - "StartServiceActivity.java"
Cohesion: 0.08
Nodes (21): Bundle, ComponentName, IBinder, Override, ProgressDialog, SeedSyncServiceActivity, Bundle, ComponentName (+13 more)

### Community 13 - "SendView"
Cohesion: 0.08
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

### Community 27 - "AppCompatActivity"
Cohesion: 0.07
Nodes (26): Bundle, Override, LauncherActivity, Bundle, Override, NewWalletActivity, Bundle, EditText (+18 more)

### Community 28 - "MinimaApplication"
Cohesion: 0.36
Nodes (4): Context, Override, MinimaApplication, Application

### Community 30 - "MainAdapter"
Cohesion: 0.10
Nodes (12): Override, View, ViewGroup, MainAdapter, AppsView, Activity, JSONObject, ListView (+4 more)

### Community 31 - "TokenMeta"
Cohesion: 0.23
Nodes (4): IconResolver, JSONObject, TokenMeta, Pattern

### Community 32 - "LogBuffer"
Cohesion: 0.16
Nodes (7): Context, JSONArray, JSONObject, TokenSpinnerAdapter, LogBuffer, Sink, ArrayAdapter

## Knowledge Gaps
- **1 isolated node(s):** `MinimaAPIMessages`
  These have ≤1 connection - possible missing edges or undocumented components.
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MinimaService` connect `MinimaService` to `MainActivity`, `StartServiceActivity.java`, `BalanceAdapter`, `MainAdapter`?**
  _High betweenness centrality (0.118) - this node is a cross-community bridge._
- **Why does `ReceiverDB` connect `ReceiverDB` to `MinimaReceiver`, `AppCompatActivity`?**
  _High betweenness centrality (0.082) - this node is a cross-community bridge._
- **Why does `MainActivity` connect `MainActivity` to `MinimaService`, `logger.java`, `StartServiceActivity.java`, `AppCompatActivity`, `MainAdapter`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **What connects `MinimaAPIMessages` to the rest of the system?**
  _1 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MinimaReceiver` be split into smaller, more focused modules?**
  _Cohesion score 0.1477832512315271 - nodes in this community are weakly interconnected._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.11261261261261261 - nodes in this community are weakly interconnected._
- **Should `MinimaService` be split into smaller, more focused modules?**
  _Cohesion score 0.06676342525399129 - nodes in this community are weakly interconnected._