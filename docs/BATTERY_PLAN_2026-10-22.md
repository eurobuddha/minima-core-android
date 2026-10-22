# Battery plan — Pandamonium, PandaBear, BlackBear (2026-10-22)

Status: **research and plan only.** No code has changed. The baseline measurement on the Fold
(R3GL805K7MB) has not run yet because the Fold was not connected. Every saving below is a hypothesis
until the baseline and the per-step re-measurements give numbers.

## 1. What runs in the background today (code survey, file:line)

| # | Component | Flavors | What it does | Rate |
|---|---|---|---|---|
| A | `MinimaService.java:148-150` PARTIAL_WAKE_LOCK `Minima::MiniPower` | all | acquired with no timeout in onCreate, released only in onDestroy | 24 h/day |
| B | `MinimaService.java:155-157` WifiLock `WIFI_MODE_FULL` | all | acquired with no timeout | 24 h/day (AOSP: this mode is a no-op) |
| C | `chat/ChatDrainer.java:51` POLL_MS 1500 | all, if `keets_enabled` | `keets action:inbox` node command → loopback HTTP → inbox `readdir` | every 1.5 s, forever |
| D | keets Bare sidecar (`keets/KeetsWorklet.java`) | all, if `keets_enabled` | 2 HyperDHT nodes (`index.js:120`, `media.js:11`), dht-rpc tick 5 s ×2, 5 s keepalive per connection, 45 s mailbox fetch, live mailbox subscription; never suspended | continuous |
| E | `maxchat/MaxChat.java` + maxima-core | all, unconditional | 2 relay TCP sockets, NAT keepalive write every 25 s (`HostConnection.java:798`), 60 s upkeep, 20 min maxima loop | continuous |
| F | Minima node | all | auto-mine 50 s on power / 500 s on battery (already switched by `MinimaService` battery receiver), pulse 10 min, P2P loop | continuous |
| G | AtomiX SwapService | pandamonium | FGS, 90 s tick + 30 s discovery, 15 min exact alarm, 15 min WorkManager, 3 min wakelocks, relaunch on task removal; armed forever after one open, not gated on any swap | continuous |
| H | PandaDEX DexKeepAliveService | pandamonium | FGS, 15 min exact alarm + worker, 3 min wakelocks; not gated on open orders (comment says it is) | 15 min |
| I | PandaPools PoolKeepAliveService | pandamonium | Activity path is LP-gated but `BootReceiver` is not | 15 min |
| J | Casino CasinoService | pandamonium | FGS; every block runs `block` + `coins … depth:4096`; never self-stops | ~50 s |
| K | Future Cash GuardianService | pandamonium | only when the guardian is on; per-block pass | ~50 s when on |
| L | Terminal IDE `MinimaNotifyReceiver` | pandamonium | subscribed at process start; SQLite insert per node log line, even if never opened | per log line |
| M | `EmbeddedNodeTransport.publish` | block, pandamonium | deep-copies every node event incl. MINIMALOG | per event |
| N | Salon | pandamonium | per-block prefs check, 6 h periodic work | low |
| O | Hide switch (`pandamonium_navigation/app_disabled`) | pandamonium | hides the drawer entry only; nothing in the background reads it | — |

Already correct and worth copying: NFT Wallet and Atelier stop themselves when they have no work.

## 2. Research summary (sources)

- **A foreground service is largely exempt from Doze.** Its network stays up and its partial wakelocks are
  not disabled in idle ([NetworkPolicyManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/NetworkPolicyManager.java),
  [PowerManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/PowerManagerService.java)).
  Doze will not save us; our own duty cycle decides the drain.
- **Deep Doze** starts about 30 min after screen-off and stillness; idle windows 1 h doubling to 6 h ([DeviceIdleController](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java), [Doze docs](https://developer.android.com/training/monitoring-device-state/doze-standby)).
- **While-idle alarms** fire at most once per 9 min per app in Doze ([Doze docs](https://developer.android.com/training/monitoring-device-state/doze-standby)); exact alarms cannot be batched ([alarms](https://developer.android.com/develop/background-work/services/alarms/schedule)).
- **FGS types:** `specialUse` has no time limit and is the right type for the node. `dataSync` gets 6 h per 24 h on Android 15+ and then `onTimeout()` ([timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)). Six integrated apps declare `specialUse|dataSync`.
- **Radio tail:** each transfer keeps the radio at full power for the transfer plus about 5 s, then low power about 12 s; three small transfers a minute keep it permanently on ([network-access-optimization](https://developer.android.com/develop/connectivity/network-ops/network-access-optimization)). LTE is far less efficient than Wi-Fi, mostly because of the tail ([Huang et al. 2012](https://experts.umn.edu/en/publications/a-close-examination-of-performance-and-power-characteristics-of-4/)).
- **Wakelocks:** Play's 2025 metric calls 2 h or more of partial wakelock per 24 h "excessive", counting FGS time ([vitals](https://developer.android.com/topic/performance/vitals/excessive-wakelock)). Ours is 24 h. Upstream Minima's Android app has the same permanent wakelock ([MinimaService.java](https://github.com/spartacusrex-minima/minima-core-android/blob/main/app/src/main/java/org/minima/core/service/MinimaService.java)); we inherited it.
- **Hyperswarm/Bare suspend:** `swarm.suspend()` disconnects peers and stops discovery and the DHT tick; `Bare.suspend()` / bare-kit `worklet.suspend()` drive it from the host; the Pears guide shows the pattern ([hyperswarm](https://github.com/holepunchto/hyperswarm/blob/main/index.js), [Pears suspension guide](https://docs.pears.com/how-to/run-on-native/handle-app-suspension/)). Keet mobile's own background behaviour is not documented (unverified).
- **Other P2P apps:** Briar added "Tor off on battery"; Tor has a dormant mode; Syncthing-Fork offers run conditions (charging, Wi-Fi, schedule); Element F-Droid offers "battery" vs "real time" sync; ntfy's persistent connection costs about 0–1% per 17 h and moved its ping from 1 to 3 min to save battery ([ntfy FAQ](https://docs.ntfy.sh/faq/), [Syncthing-Fork](https://github.com/Catfriend1/syncthing-android), [Briar #2246](https://code.briarproject.org/briar/briar/-/issues/2246)). User-chosen profiles are the common pattern.
- **Minima flags:** no `-maxpeers` exists (peer targets are hard-coded in `P2PParams`); `-lowram`/`-blockaskeyuses` are fork-only; auto-mine already drops from 50 s to 500 s on battery. No flag lowers duty cycle without touching validation, so node savings must come from wakeup scheduling, not node config.
- **Samsung:** apps can be put to "sleeping" lists after updates; the "Never sleeping apps" list can be deep-linked ([Samsung dev](https://developer.samsung.com/mobile/app-management.html), [dontkillmyapp](https://dontkillmyapp.com/samsung)).

## 3. Design

### Battery profile (three-dot menu → Battery)

| | Instant | Balanced (default) | Saver |
|---|---|---|---|
| CPU wakelock | held (today's behaviour) | held only while the screen is on, while own txns are pending, and in timed windows | timed windows only |
| KeetChat | 1.5 s poll + push | 1.5 s while the app is visible; screen off: rely on push, poll backs off 1.5 s → 30 s → 5 min | sidecar suspended after 5 min screen-off; mailbox catches messages; resume on screen-on |
| Maxima chat relays | 25 s keepalive | 25 s; review after measuring | longer keepalive or reconnect on screen-on |
| Node | full | full | full; peers and validation unchanged |
| Integrated apps | as set | asleep until opened | asleep until opened |
| On charger or Wi-Fi | — | optional "full speed" switch | optional "full speed" switch |

Never changed by any profile: block validation, pending transaction posting and mining, the key-use
counter, and `txndelete` on every error path. Any own transaction in flight holds the wakelock until it
posts or is deleted.

### Per-app state (Settings → Apps): Visible / Hidden / Deep sleep

Extends the existing `app_disabled` pref and `IntegratedAppsAdapter` row; no parallel mechanism.
Deep sleep for an app means: its `BootReceiver`, `HeartbeatReceiver`, `Worker`, `onTaskRemoved`
relaunch and process-start subscriptions all check the state and do nothing; on entering deep sleep its
unique works and heartbeat PendingIntents are cancelled and its FGS stopped. Opening the app wakes it.
Apps that hold user money in flight (an open AtomiX swap, a PandaDEX order, an LP pool, the Future Cash
guardian) refuse deep sleep and say why.

## 4. Ranked plan (expected saving is a hypothesis until measured)

| Rank | Change | Expected effect | Risk / guard |
|---|---|---|---|
| 1 | Measure baseline (1 h screen-off, 1 h light use) | numbers for everything below | Fold only |
| 2 | Drop the no-op WifiLock | none to small; zero risk | — |
| 3 | Gate AtomiX / PandaDEX / PandaPools / Casino background work on real work (open swap, order, pool, bet) and fix the ungated BootReceivers | large in Pandamonium: removes FGSs, 15 min exact alarms and 3 min wakelocks for users with nothing open | money-in-flight must keep its watcher |
| 4 | ChatDrainer adaptive cadence (screen off: push + backoff) | removes ~57,600 node commands per day when idle | measure delivery latency per step |
| 5 | Keets sidecar suspend when screen off > X (Balanced/Saver) | removes 5 s DHT ticks and keepalives; likely large on cellular | mailbox delivery on resume; measure |
| 6 | Wakelock policy per profile (timed windows instead of 24 h) | likely the largest single saving | must not stall own txns, key-use counter or block validation; needs the longest test |
| 7 | Terminal IDE log subscriber only while Terminal IDE is open; stop deep-copying MINIMALOG | small CPU | none |
| 8 | Per-app Visible / Hidden / Deep sleep and the Battery screen | user control | default deep sleep only for apps with nothing in flight |
| 9 | Samsung "Never sleeping apps" deep link on the Battery screen | reliability, not drain | — |

Each row ships as its own version, re-measured on the Fold with the same method, and the
before/after table is filled in here. Nothing is released until the owner says so.

## 5. Measurement method (to run once the Fold is connected)

```
adb -s R3GL805K7MB shell dumpsys battery unplug        # count as on-battery while on USB
adb -s R3GL805K7MB shell dumpsys batterystats --reset
# screen off 60 min, then:
adb -s R3GL805K7MB shell dumpsys batterystats --charged com.eurobuddha.pandamonium
adb -s R3GL805K7MB shell dumpsys alarm
adb -s R3GL805K7MB shell dumpsys power
adb -s R3GL805K7MB shell dumpsys jobscheduler
adb -s R3GL805K7MB shell dumpsys netstats detail
adb -s R3GL805K7MB bugreport
adb -s R3GL805K7MB shell dumpsys battery reset
```

A Perfetto trace with power, wakelock, alarm and network sources runs over the same hour. Node health is
read from `status complete:true`, not from dumpsys, per the diagnosis rules.
