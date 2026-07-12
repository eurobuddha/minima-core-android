# Minima Core (Android) — UI redesign + H2 fix

## Behavioural vs cosmetic — at a glance

**The node core is untouched.** No `org.minima.*`, `MinimaService`, `MinimaCMD`, DB, consensus or
signing code changed; `SendView`/`ReceiveView` are unmodified; every Minima command (`send`,
`getAddress`, `balance`, `keys`, `network`, `peers`, `vault`) is invoked exactly as before.

| Change | Type | Notes |
|--------|------|-------|
| All `res/**` (colors, themes, type, fonts, dimens, styles, drawables, every `layout/*.xml`) | Cosmetic | `@id`s + view types preserved |
| Launcher title cleared, per-tab toolbar title, `values-night` `on_primary`→white | Cosmetic | — |
| Tabs → Home/Wallet/Terminal/Apps; Send/Receive as sub-activities | **Behavioural** | navigation only; view logic reused unchanged |
| Token icons + web-validation (`main/views/balance/tokens/`) | **Behavioural** | **new outbound network** (icon http/ipfs + `webvalidate` fetch); loopback/LAN blocked |
| "Allow Screenshots" toggle | **Behavioural** | `FLAG_SECURE` now conditional; default still off |
| H2 `2.4.240 → 2.1.214` | **Behavioural** | dependency fix so the node starts |
| `androidsvg-aar:1.4`, 2 new activities, version bump | **Behavioural** | additive |

A write-up of every change in this fork, so it can be reviewed / cherry-picked
upstream. Nothing here touches node, wallet, DB, networking or consensus code —
it is **presentation + one dependency fix**. All command calls
(`MinimaCMD.runMinima(...)`), the `MinimaService`, and `ReceiverDB` are used
exactly as before.

Two things to know up front:

1. **The design is a Material3 design-system applied in `res/`.** Colours, type,
   shapes and component styles are tokens; layouts consume the tokens. Light and
   dark both resolve from `values/` + `values-night/`.
2. **The navigation was restructured** from four tabs
   (`Balance / Send / Receive / Apps`) to four tabs
   (`Home / Wallet / Terminal / Apps`). Send and Receive still exist and work
   unchanged — they are now reached from buttons on the Wallet tab instead of
   being their own tabs.

---

## 1. Node crash fix — H2 `VerifyError` (functional, ship this regardless of the UI)

**Symptom:** on some devices the node never starts — it sits in a "Starting…"
loop. Logcat shows a `java.lang.VerifyError` thrown from the H2 database classes
during startup.

**Cause:** `com.h2database:h2:2.4.240` ships bytecode that the Android ART
verifier rejects on affected devices/ABIs.

**Fix:** pin H2 to the last Android-clean release.

```diff
  //H2 database
- implementation 'com.h2database:h2:2.4.240'
+ implementation 'com.h2database:h2:2.1.214'
```

File: `app/build.gradle`. Full root-cause + stack trace is in
`H2_VERIFYERROR_FIX.md`.

---

## 2. Design system (all in `res/`, no Java)

| File | What it defines |
|------|-----------------|
| `res/font/manrope*.ttf` + `res/font/manrope.xml` | Manrope family (5 weights) — the Minima brand font |
| `values/colors.xml` + `values-night/colors.xml` | Brand palette as tokens: `minima_orange #FF4A2B`, `core_bg/core_surface/core_outline`, `core_text/…_mid/…_faint`, `status_good/warn/bad`, `on_primary`. Light values in `values/`, dark overrides in `values-night/` (dark was previously empty). |
| `values/themes.xml` | `Theme.MinimaCore` (Material3 DayNight). Maps brand tokens onto M3 roles (`colorPrimary`, `colorSurface`, …) and sets component defaults via `tabStyle` / `materialButtonStyle` / `materialCardViewStyle` / `toolbarStyle` / `editTextStyle`. Also the shape scale (12/16/20dp corners) and theme-aware system bars. |
| `values/type.xml` | Manrope type scale: `Display / Title / Body / BodyStrong / Label / Tab / Caption / Eyebrow / Numeral`. |
| `values/styles.xml` | Layout helpers: `HomeRow(.Label/.Value)`, `HomeDivider`, `FormLabel`, `FormField`. |
| `values/dimens.xml`, `values/bools.xml` | 8dp spacing / radii; `light_status_bar` / `light_nav_bar` per theme. |
| `res/drawable/bg_dot`, `bg_pill`, `bg_token_tile`, `ic_arrow_back` | Small brand drawables (status dot, pill, input tile, back arrow). |
| `color/tab_icon_tint.xml` | selected=orange / unselected=faint for tab icons. |

**Every screen layout** was restyled to consume these tokens
(`launcher_activity`, `view_home`, `view_wallet_balance`, `view_wallet_send`,
`view_wallet_receive`, `view_terminal`, `view_apps`, onboarding screens, dialogs).
**All `@id`s and view types were preserved** so the Java bindings keep working
(e.g. `Button` → `MaterialButton` is a safe subclass; `ListView` / `Spinner` /
`ViewPager` / `TabLayout` / `ScrollView` / `CheckBox` unchanged).

> One night-mode token was corrected: `values-night` had
> `on_primary = #12100F` (near-black), which made text on the orange primary
> buttons render dark. Set to `#FFFFFF` so button labels are white on orange in
> both themes (matches the design).

---

## 3. Tab restructure → `Home / Wallet / Terminal / Apps`

Everything needed already existed in the tree (`HomeView`, `TerminalView` were
present but unwired). Two hard constraints were respected so service/DB wiring
is untouched:

* `MainAdapter.getAppsView()` casts `mAllViews[3]` → **Apps stays index 3**.
* `MainActivity.MinimaNewBlock()` → `refreshHomeView()` refreshes `mAllViews[0]`
  → **the dashboard stays index 0**.

### `MainAdapter.java`
```diff
- mAllViews = new BaseView[4];
- //mAllViews[0] = new HomeView(mActivity);
- mAllViews[0] = new BalanceView(mActivity);
- mAllViews[1] = new SendView(mActivity);
- mAllViews[2] = new ReceiveView(mActivity);
- mAllViews[3] = new AppsView(mActivity);
+ mAllViews = new BaseView[4];
+ mAllViews[0] = new HomeView(mActivity);      // node + identity dashboard
+ mAllViews[1] = new BalanceView(mActivity);   // the "Wallet" tab
+ mAllViews[2] = new TerminalView(mActivity);  // page version of the terminal
+ mAllViews[3] = new AppsView(mActivity);      // unchanged (MDS apps)
```
`getCount()` is still `4`.

### `MainActivity.java`
* Tab titles/icons updated to `Home (ic_drawer_home) / Wallet (ic_minima) /
  Terminal (ic_edit_note) / Apps (ic_dapps)`.
* Toolbar title now reflects the current tab (`setToolbarTitle(position)` from
  `onTabSelected`): *Minima Core / Wallet / Terminal / Apps*.
* The overflow **Terminal** menu item was removed (it's a tab now); its
  `case R.id.mainmenu_terminal` handler was removed with it. `TerminalActivity`
  still exists and is still registered, just no longer linked from the menu.

---

## 4. The Wallet tab (`BalanceView` + `view_wallet_balance.xml`)

The Wallet tab is the old balance list plus a header, matching the design:
**Total balance** (the Minima `0x00` sendable amount) + **Send** / **Receive**
buttons, above the existing token `ListView` (`wallet_balance_list`, unchanged).

* `view_wallet_balance.xml`: added the balance header + two `MaterialButton`s
  (`wallet_btn_send`, `wallet_btn_receive`); kept the `ListView` id/type.
* `BalanceView.java`: binds the total `TextView`, wires the two buttons to
  `startActivity(SendActivity/ReceiveActivity)`, and fills the total from the
  same `balance` command result it already reads (`0x00` → `sendable`, falling
  back to `confirmed`). No change to how the token list is populated.

### Send / Receive as host activities
Rather than duplicate any logic, the existing **self-contained** `SendView` /
`ReceiveView` (each is a `BaseView(Activity)` that inflates its layout and wires
itself) are hosted verbatim in two thin activities:

* `main/views/send/SendActivity.java` + `res/layout/activity_send.xml`
* `main/views/receive/ReceiveActivity.java` + `res/layout/activity_receive.xml`

Each is just a `Toolbar` (back arrow → `finish()`) + a container that adds the
view's `getMainView()`. Both registered in `AndroidManifest.xml`. The send
command (`send amount:… address:… tokenid:…`) and the receive `getAddress` /
QR path are byte-for-byte the originals.

### Token icons + web-validation (ported from the utxoWallet / Minima wallet)

The Wallet token rows carry the same icon + verification treatment as the canonical wallet.
New package `main/views/balance/tokens/`:

* `IconResolver` — turns a raw token url/icon into something loadable: `<artimage>`base64
  (the primary real-token format), `data:` URI, `http(s)`, inline `<svg>`, or raw base64.
* `ImageLoader` — async, byte-bounded LRU-cached loader; handles `data:`/`http(s)`/`ipfs://`,
  downsamples to avoid OOM, rasterises SVG via AndroidSVG, and **blocks loopback/LAN hosts**
  so token metadata can't point the app at the local node RPC.
* `Identicon` — deterministic 5×5 identicon from the tokenid (same id → same picture, computed
  locally) + the blue web-validation check badge.
* `WebValidate` — a token is validated when the file at its `token.webvalidate` URL contains the
  tokenid (domain-ownership proof); fetched once per token on a worker thread, result cached.
* `TokenMeta` — defensive parse of Minima's token field (string / JSONObject / stringified JSON;
  name/url/icon/webvalidate at either level).

`BalanceAdapter` draws the identicon (or black-M-on-white tile for native Minima), loads the real
icon over the top, and shows the check badge when validated. `view_balance_row.xml` gained a 40dp
icon slot + badge. New dep: `com.caverock:androidsvg-aar:1.4`. Uses the existing `INTERNET`
permission; all network is off the UI thread.

---

## 5. Runtime "Allow Screenshots" toggle (replaces the hard `FLAG_SECURE` rule)

Upstream sets `FLAG_SECURE` on every activity (blocks all screenshots/recording).
This fork keeps that as the **default** but makes it a user choice.

* `MinimaApplication.java`: on each activity created, apply `FLAG_SECURE`
  **unless** the persisted pref `ALLOW_SCREENSHOTS` (default `false`, in
  `main_prefs`) is on, **or** the build is `debuggable`. Helper
  `MinimaApplication.screenshotsAllowed(context)`.
* `optionsmenu.xml`: new checkable **Allow Screenshots** item.
* `MainActivity.java`: `onPrepareOptionsMenu` reflects the pref;
  `toggleScreenshots()` persists it, applies/clears the flag on the current
  window immediately, and toasts. Other activities pick it up on creation.

Default behaviour for a normal release build is therefore unchanged
(screenshots blocked) until the user opts in.

---

## Files at a glance

**Java** — `MainActivity`, `MainAdapter`, `MinimaApplication`, `BalanceView`,
`LauncherActivity` (title cleared); **new**: `SendActivity`, `ReceiveActivity`.
**Res** — the design-system files in §2; `view_wallet_balance.xml`,
`activity_send.xml`, `activity_receive.xml`, `ic_arrow_back.xml`,
`optionsmenu.xml`, `values-night/colors.xml`.
**Build** — `app/build.gradle` (H2 pin).

No changes under `org.minima.*` (the node), `service/`, or any DB/consensus code.
