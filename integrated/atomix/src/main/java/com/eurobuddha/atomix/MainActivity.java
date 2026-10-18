package com.eurobuddha.atomix;

import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.net.Uri;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.atomix.integratedcomms.CommsIdentity;
import com.eurobuddha.atomix.integratedcomms.CommsScanner;
import com.eurobuddha.atomix.integratedcomms.CommsTransport;
import com.eurobuddha.atomix.integratedcomms.CryptoProvider;
import com.eurobuddha.atomix.integratedcomms.Opened;
import com.eurobuddha.atomix.integratedcomms.Hex;
import com.eurobuddha.atomix.integratedcomms.LocalEcCryptoProvider;
import com.eurobuddha.atomix.integratedcomms.NodeApi;
import com.eurobuddha.atomix.integratedcomms.QrUtil;
import com.eurobuddha.atomix.integratedcomms.Sodium;
import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthSend;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.OtcBook;
import com.eurobuddha.atomix.swap.OtcController;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.OtcMessage;
import com.eurobuddha.atomix.swap.OtcOffer;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.atomix.swap.SwapOrderBook;
import com.eurobuddha.atomix.swap.SwapTake;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * usdtSwap — native cross-chain HTLC atomic swaps (Minima ↔ ERC20) on minimaCore.
 *
 * Home = wallet status (Minima + seed-derived ETH), a live signed on-chain order book, a guided take-order
 * flow, and resumable active-swap cards driven by {@link SwapEngine} (the trustless two-leg state machine)
 * and a background watcher that polls both chains, harvests revealed secrets and fires notifications.
 */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "atomix";
    private static final String PREFS = "atomix";
    private static final String[] PAIR_TOKENS = Order.PAIR_TOKENS;   // single source of truth in Order
    private static final long WATCH_INTERVAL_MS = 90_000;
    private static final long DISCOVERY_INTERVAL_MS = 30_000;   // fast, LIGHT handshake-discovery scan — the heavy poll stays 90s
    private static final long SWEEP_LEG_TIMEOUT_MS = 300_000;    // start-phase watchdog: lock never broadcasts (covers approve+lock)
    private static final long SWEEP_CONFIRM_WATCHDOG_MS = 300_000; // SELL confirm-phase watchdog: confirmMyLock hangs (> the try-budget below)
    private static final long SWEEP_CONFIRM_INTERVAL_MS = 8_000; // SELL poll cadence while waiting for a leg's mxUSDT lock to hit chain
    private static final int  SWEEP_CONFIRM_TRIES = 30;         // ~4 min budget (SELL-only) — a coinage:1 lock lands in ~1 block
    static final long REPUBLISH_INTERVAL_MS = 30 * 60_000;   // keep a published order live + fresh (bridge uses 30m)
    private static final long TERMINAL_GRACE_MS = 10 * 60_000;   // Activity-tab history: finished swaps auto-hide after this
    private static final long SWAP_PANEL_GRACE_MS = 30_000;       // Swap-tab panel: a just-finished swap shows only this long, then clears

    private LazySodium ls;
    private NodeApi node;
    private boolean paired = false;
    private SharedPreferences prefs;

    private final EthWallet wallet = new EthWallet();
    private EthNet net = EthNet.MAINNET;     // mainnet only
    private EthRpc rpc;

    // identity + swap
    private CommsIdentity identity;
    private CryptoProvider crypto;
    private MinimaHtlc minima;
    private String myMinimaPk;
    private SwapDb db;
    private SwapEngine engine;
    private final LinkedHashMap<String, Order> orderBook = new LinkedHashMap<>();
    private final LinkedHashMap<String, OtcOffer> otcBook = new LinkedHashMap<>();   // other LPs' availability
    private OtcDb otcDb;
    private OtcController otc;
    private CommsScanner otcScanner;    // receives OTC negotiation messages
    private String orderStatus = null;
    private CommsScanner takeScanner;   // maker side: receives buyers' hashlock handshakes
    private int historyTab = 0;         // Activity sub-tab: 0 = my swaps, 1 = market
    private ActivityResultLauncher<String> csvLauncher;   // Activity → Export CSV (SAF CreateDocument; no FileProvider/permission)
    // ---- tabs ----
    private static final int TAB_SWAP = 0, TAB_WALLET = 1, TAB_ACTIVITY = 2, TAB_MARKET = 3, TAB_OTC = 4;
    private static final CharSequence[] TAB_LABELS = {Language.msg(l10n_atomix_ui_swap), Language.msg(l10n_tab_wallet), Language.msg(l10n_atomix_ui_activity), Language.msg(l10n_atomix_ui_market), Language.msg(l10n_atomix_ui_otc)};
    private int currentTab = TAB_SWAP;
    private final TextView[] tabPills = new TextView[5];
    private boolean swapSell = true;    // Swap tab direction: true = Sell mxUSDT, false = Buy mxUSDT
    private String otcSellDraft, otcBuyDraft;
    private boolean otcInputFocused;
    private String swapAmount = "";     // Swap tab "you send" amount — persists across tree rebuilds
    private boolean swapInputFocused = false;   // suppress background render() while typing the swap amount
    private boolean swapSyncing = false;        // guard so the send/receive fields don't loop while syncing
    private SweepPlan sweepRun = null;          // non-null while a multi-leg market sweep is executing
    private int sweepIdx = 0, sweepOk = 0;      // current leg index / legs successfully locked
    private Runnable sweepWatchdog = null;      // aborts a sweep whose current leg never calls back
    private static final int USDT_TEAL = 0xFF26A69A;
    private static final int STG_PENDING = 0, STG_ACTIVE = 1, STG_DONE = 2, STG_WARN = 3;
    private static final int STAGE_WARN_COLOR = 0xFFE6A23C;

    // wallet state shown on the home screen
    private String minimaBal = "…";        // sendable (tradeable)
    private String minimaConfirmed = "—", minimaUnconfirmed = "—", minimaCoins = "—";
    private long lastMinimaUpdate = 0;
    private int chainBlock = 0;
    private String ethAddr = null;
    private String ethErr = null;
    private String ethBal = "…";
    // CR-7: volatile — written on the UI thread (fetchEthBalances' ui.post) but read on the io thread in the
    // Send dialog's Max/Review handlers. Without the happens-before edge the io thread could validate a send
    // against a stale (e.g. zero) balance.
    private volatile BigInteger ethWeiRaw = BigInteger.ZERO;    // RAW wei — manual-send validation (never display strings)
    private volatile BigInteger usdtRawBal = BigInteger.ZERO;   // RAW USDT (6dp units)
    private final LinkedHashMap<String, String> tokenBals = new LinkedHashMap<>();
    // MI-12: the peg-preview dialog's 2s self-reposting tick. Held so onPause can stop it (it otherwise kept
    // polling PriceOracle over the network while the app was backgrounded with the dialog open) and onResume
    // can re-arm it; nulled on dismiss.
    private Runnable pegTickRef;

    // The currency pill's glow. render() rebuilds the whole view tree (every ~30s, and on every dialog
    // dismiss and tab change), so the pill is a BRAND NEW TextView each time and this animator must be
    // cancelled and re-started with it. An Animator is not a Handler callback, so the blanket
    // ui.removeCallbacksAndMessages(null) in onDestroy does NOT stop it: without the explicit cancel
    // below we would accumulate one live animator per render, each retaining a detached view.
    private android.animation.ValueAnimator ccyGlowAnim;
    private TextView ccyPillRef;

    // Balance-pulse feedback: bounce the headline balances when a swap NEWLY completes (incl. while backgrounded).
    private TextView minimaBalView, ethBalView;
    private final List<TextView> tokenBalViews = new ArrayList<>();
    private boolean pulseMinimaPending = false, pulseEthPending = false;
    private int lastCompleteCount = -1;                 // -1 until first read from prefs
    private static final String TAG_BAL = "balBig";     // marks the big balance TextView in walletCard/kv

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger notifId = new AtomicInteger(1000);

    private LinearLayout root;
    private ScrollView scroller;
    private LinearLayout tabBar;
    private final ImageView[] tabIcons = new ImageView[5];
    private final LinearLayout[] tabCells = new LinearLayout[5];
    private TextView pairingBanner;
    private boolean animateTab = false;   // fade in ONLY on a tab switch, never on a background refresh
    private boolean watching = false;
    private boolean modalOpen = false;   // suppress background render() while a dialog (with inputs) is open
    private boolean serviceStarted = false;
    /** True while the Activity is resumed — the SwapService stands down then so only one polls. */
    public static volatile boolean FOREGROUND = false;
    public static volatile boolean SWEEP_ACTIVE = false;   // a market sweep owns the wire — SwapService must also stand down

    private long lastRegisterMs = 0;   // pairing-retry throttle while the node is unreachable

    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            if (!paired) {
                // The constructor's one-shot REGISTER is lost if the node app isn't running yet (post-reboot,
                // force-stopped) — keep re-sending until the node answers. Recovery flips onPaired(true).
                if (System.currentTimeMillis() - lastRegisterMs >= 30_000) {
                    lastRegisterMs = System.currentTimeMillis();
                    node.reRegister();
                }
            } else if (identity == null || !wallet.ready() || !minima.ready()) {
                ensureDerivations();   // retry any derivation that failed transiently (was one-shot before)
            } else {
                // Both identities are established — keep ASKING whether they still belong to this node. A
                // reseed while we run leaves the persisted Minima key orphaned and the (never-persisted) ETH
                // key stale until the process dies, and the FGS lives for days. Throttled inside.
                IdentityWatch.check(node, wallet, minima, prefs, ui, notifier);
            }
            if (sweepRun == null || !sweepRun.sell) {  // stand down only mid-SELL-sweep — a poll republish/claim
                                                       // could re-select a sell leg's mxUSDT coins. (A BUY sweep is
                                                       // nonce-serialized, so the poller may run alongside it.)
                // Re-arm the responder guard from the PERSISTED config on EVERY pass, BEFORE poll() — a
                // cancel/edit (in either host) must reach this engine's guard (disabled pairs decline every
                // take), and after a fg/bg handoff the OTHER host may have withdrawn/restored while our
                // engine still held the pre-handoff order (armSafe → empty order while withdrawn).
                if (engine != null) engine.setMyOrder(PriceOracle.armSafe(loadOrder(), prefs));
                if (prefs.getBoolean(PriceOracle.P_ENABLE, false) && prefs.getBoolean("auto_publish", false)) {
                    PriceOracle.refreshAsync();        // keep the peg's price fresh (rate-limited inside)
                    maybePegWithdraw();                // feed down too long → pull the order + disarm the guard
                }
                if (engine != null) engine.poll();
                maybeAutoRepublish();
                maybeRepublishOtc();                   // keep my OTC availability live too
                if (engine != null && prefs.getBoolean("auto_publish", false))
                    engine.maintainLadderCoins(!SWEEP_ACTIVE);   // replenish coins consumed by fills (rate-limited)
            }
            refreshBalances(false);                 // keep balances current without a restart (read-only, always safe)
            if (currentTab == TAB_SWAP || currentTab == TAB_MARKET) scanOrderBook();   // refresh the LIVE book only
                                                    // while a book-rendering tab is open (Swap takes / Market depth)
                                                    // — else the taker trades a stale app-start snapshot and a maker
                                                    // that has since withdrawn (a no-liquidity tombstone) still shows
                                                    // tradeable. Gated to those tabs so we DON'T query the shared
                                                    // sentinel from Wallet/Activity/OTC: trims node traffic and
                                                    // shrinks exposure to a flooded-book `coins` response (the known
                                                    // unbounded-response node crash — not app-fixable, bounded here
                                                    // only by depth:SCAN_DEPTH). Entering a book tab triggers an
                                                    // immediate scan (see setTab). Read-only → safe even mid-sweep.
            ui.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    /** Fast, LIGHT discovery tick (~30s): only the bounded handshake scan (which now kicks checkBuyNow on a new
     *  buy), so a buyer is seen in ~1 block instead of up to a 90s poll. The heavy poll (balances/ladder/ETH
     *  loops) stays at 90s. Read-only scan/add — safe even mid-sweep (the ACT is gated in addIncoming). */
    private final Runnable discoveryTick = new Runnable() {
        @Override public void run() {
            scanTakeRequests();
            scanOtc();
            ui.postDelayed(this, DISCOVERY_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        TradingContext.load(prefs);   // the selected currency — BEFORE Design.load so the theme opens in the right identity
        Design.load(this);   // persisted theme + bundled Inter/JetBrains Mono, before any view is built
        PriceOracle.init(prefs);   // restore the persisted last-good stamp — the withdraw clock survives restarts
        net = EthNet.MAINNET;
        rpc = new EthRpc(rpcUrl(net));
        db = new SwapDb(this);
        // SAF file-picker for the Activity → Export CSV button (registered in onCreate, before the activity starts).
        csvLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> { if (uri != null) doExportCsv(uri); });

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        tabBar = buildTabBar();
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tabBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        applyChrome();
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        // NOTE: this FIRST render runs before node/minima/engine exist — view builders must be null-safe
        // about them (see nodeOfflineText()). 0.1.62 dereferenced node here and crashed every launch.
        render();
        if (!prefs.getBoolean("seen_welcome", false)) ui.post(this::showWelcome);
        node = new NodeApi(this, this::onPaired);
        lastRegisterMs = System.currentTimeMillis();   // the constructor's REGISTER counts as attempt #1
        minima = new MinimaHtlc(node);
        minima.setActiveToken(TradingContext.active().tokenId);   // publish/liquidity in the selected currency
        engine = new SwapEngine(node, minima, db, wallet, ui, notifier);
        otcDb = new OtcDb(this);
        engine.setOtcDb(otcDb);
        otc = new OtcController(node, otcDb, engine, new OtcController.Ui() {
            @Override public void onDealsChanged() { ui.post(() -> { if (currentTab == TAB_OTC && !modalOpen) render(); }); }
            @Override public void note(String title, String body) { postNotification(title, body); }
        });
        engine.setNetwork(rpc, net);
        // armSafe: if a pegged order was withdrawn (stale MEXC feed), arm an EMPTY order — decline every
        // take — rather than the saved ladder, whose prices the market may have left behind.
        engine.setMyOrder(PriceOracle.armSafe(loadOrder(), prefs));
    }

    @Override public void onBackPressed() {
        if (currentTab != TAB_SWAP) { setTab(TAB_SWAP); return; }   // any non-default tab → back to Swap
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;     // Activity polls while visible; SwapService stands down
        startWatcher();
        if (pegTickRef != null) ui.post(pegTickRef);   // MI-12: re-arm the peg-preview poll if its dialog is still open (the tick self-cancels if not)
        if (ccyPillRef != null) startCcyGlow(ccyPillRef);   // the pill survived the pause; re-arm exactly one animator on it
        checkForNewCompletion();   // a swap that completed while we were away → refresh + pulse on return
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;    // hand off to the background SwapService
        stopWatcher();
        if (pegTickRef != null) ui.removeCallbacks(pegTickRef);   // MI-12: stop the 2s peg-preview poll while backgrounded
        stopCcyGlow();   // never breathe a pill nobody is looking at
    }

    @Override protected void onDestroy() {
        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();
        languageDialogs.clear();
        super.onDestroy();
        // Release any in-flight sweep BEFORE tearing down: clear the static SWEEP_ACTIVE (else SwapService would
        // stand down forever and never drive the locked legs), and drop every queued confirm-poll callback so
        // none fires against the shut-down io pool. The background Service resumes the in-flight swaps from the DB.
        SWEEP_ACTIVE = false; sweepRun = null; sweepWatchdog = null;
        ui.removeCallbacksAndMessages(null);
        stopCcyGlow(); ccyPillRef = null;   // an Animator is not a Handler callback - the line above misses it
        stopWatcher();
        if (engine != null) engine.shutdown();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    /** (Re)start the currency pill's glow on the CURRENT pill, cancelling any previous one. */
    private void startCcyGlow(TextView pill) {
        stopCcyGlow();
        ccyPillRef = pill;
        // Gate the START, not just the stop. onPause cancels the animator, but render() is NOT
        // guarded on paused state (only isFinishing/isDestroyed/modalOpen/swapInputFocused) and
        // engine callbacks keep posting it while backgrounded — onSwapsChanged and onFailed both
        // do. A swap settling while the user is in another app would otherwise rebuild the header
        // and start a fresh infinite animator nobody can see, undoing the onPause cancel. onResume
        // re-arms on ccyPillRef, so nothing is lost by declining here.
        if (!FOREGROUND) return;
        // The ring is painted in the OTHER currency's accent — it is pointing at where the tap goes.
        TradingContext active = TradingContext.active();
        CcyRing ring = Design.ccyRing(this, Design.ACCENT_SOFT(), active.other().accent,
                active == TradingContext.MINIMA);
        ccyGlowAnim = Design.glow(pill, ring, Design.glowLevel(this));
    }

    /** Cancel the glow. Safe to call repeatedly and with nothing running. */
    private void stopCcyGlow() {
        if (ccyGlowAnim != null) { ccyGlowAnim.cancel(); ccyGlowAnim = null; }
    }

    private void startWatcher() {
        if (watching || engine == null) return;
        watching = true;
        ui.post(watchTick);
        ui.postDelayed(discoveryTick, DISCOVERY_INTERVAL_MS);
    }
    private void stopWatcher() {
        watching = false;
        ui.removeCallbacks(watchTick);
        ui.removeCallbacks(discoveryTick);
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** Re-colour the persistent chrome (root, tab bar, pairing banner, status bar) for the current theme.
     *  Called once in onCreate and again on a theme toggle — no Activity recreate, so live swaps survive. */
    private void applyChrome() {
        root.setBackgroundColor(Design.BG());
        if (tabBar != null) tabBar.setBackgroundColor(Design.SURFACE());
        if (pairingBanner != null) {
            pairingBanner.setBackgroundColor(Design.ACCENT());
            pairingBanner.setTextColor(Design.ON_ACCENT());
        }
        updateTabBarSelection();
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(!Design.isDark());
    }

    /** Flip Onyx ↔ Daylight and restyle live (re-render, never recreate). */
    private void toggleTheme() {
        Design.toggle(this);
        applyChrome();
        toast(Design.isDark() ? Language.msg(l10n_atomix_ui_dark) : Language.msg(l10n_atomix_ui_light));
        render();
    }

    /** Confirm before switching the traded currency — it changes which market you make and re-themes the app. */
    private void switchCurrencyDialog() {
        final TradingContext target = TradingContext.active().other();
        dialog()
            .setTitle(Language.msg(l10n_atomix_ui_switch_title, target.coinLabel))
            .setMessage(Language.msg(l10n_atomix_ui_switch_body, target.coinLabel, target.coinLabel, TradingContext.active().coinLabel))
            .setPositiveButton(Language.msg(l10n_atomix_ui_switch_action, target.coinLabel), (d, w) -> doSwitchCurrency(target))
            .setNegativeButton(Language.msg(l10n_common_cancel), null)
            .show();
    }

    /** Persist the new currency and restart cleanly so every scanner/engine/theme re-reads it. The background
     *  settlement is currency-agnostic, so in-flight swaps in the other currency continue to completion.
     *
     *  FUND-SAFETY: the priced market state (ladder, peg config, oracle price) is NOT per-currency, so we QUIESCE
     *  the market before flipping — otherwise the previous currency's ladder/price would bleed into the new
     *  currency's book and auto-fill at a ~100–200× mispriced rate (the two price scales differ). After a switch
     *  the market is OFF: you deliberately configure + publish a fresh market in the new currency. This fails safe
     *  (a switch can only ever leave the market off, never mispriced). Clearing these prefs also stops the
     *  still-dying old service instance from republishing — it reads auto_publish + the order live each cycle. */
    private void doSwitchCurrency(final TradingContext target) {
        final TradingContext current = TradingContext.active();
        // Remember THIS currency's market so a flick back restores it (config only — never the runtime/price state).
        snapshotMarket(prefs, current.key);

        // The rest of the switch — runs exactly ONCE, after the tombstone is dispatched (or immediately if none).
        final boolean[] done = {false};
        final Runnable[] proceedRef = new Runnable[1];   // holder so proceed can cancel its own pending watchdog
        final Runnable proceed = () -> {
            if (done[0]) return; done[0] = true;
            ui.removeCallbacks(proceedRef[0]);   // callback won the race → cancel the pending 4s watchdog
            // Flip currency FIRST so the price-model guard (PriceOracle.staleForActive) rejects any in-flight
            // OLD-currency fetch that commits during the clear/reset below — airtight against the microsecond
            // commit-vs-reset race. Safe to reorder: the tombstone's sentinel was captured synchronously before
            // proceed was posted, and the clear-block/resetForSwitch operate on the single active prefs (currency-
            // agnostic keys) while restoreMarket takes an explicit target key — none depend on active being old.
            PublishGate.bumpGen(); // invalidate publications started in the leaving market
            TradingContext.setActive(target, prefs);           // flip currency (drives token/sentinels/pricing/theme)
            // Quiesce the priced state (fund-safe: the active prefs are always the ACTIVE currency's, no bleed).
            prefs.edit()
                    .remove("order_config")
                    .putBoolean("auto_publish", false)
                    .putBoolean(PriceOracle.P_ENABLE, false)
                    .putBoolean(PriceOracle.P_WITHDRAWN, true)
                    .remove("last_publish_ok")
                    // The OTC side must quiesce with the order side. Left armed, the service kept scanning the
                    // ARRIVING currency's OTC board against a stamp earned on the LEAVING one's — a guaranteed
                    // book-miss that fired a false "isn't landing on-chain" warning after every switch.
                    .putBoolean("otc_enable", false)
                    .putBoolean("otc_auto", false)
                    .remove("otc_last_publish_ok")
                    .apply();
            PriceOracle.resetForSwitch(prefs);                 // wipe cached/persisted price (no stale peg)
            try { engine.setMyOrder(new com.eurobuddha.atomix.swap.Order()); } catch (Exception ignored) {}  // disarm now
            restoreMarket(prefs, target.key);                  // bring back the target currency's own saved market (if any)
            try { stopService(new android.content.Intent(this, SwapService.class)); } catch (Exception ignored) {}
            serviceStarted = false;
            recreate();                                        // rebuild UI + scanners in the target currency's identity
        };
        proceedRef[0] = proceed;

        // Tombstone the CURRENT market FIRST (current identity + current sentinel — both read before setActive) so
        // peers drop my order next block instead of it lingering ~1h and causing failed fills. Reuses the proven
        // maybePegWithdraw withdrawal: publish my order with every pair disabled (no liquidity, freshest-per-signer).
        boolean liveMarket = false;
        try { liveMarket = loadOrder().hasLiquidity() || prefs.getBoolean("auto_publish", false); } catch (Exception ignored) {}
        if (liveMarket && node != null && ls != null && identity != null && myMinimaPk != null && wallet.ready()) {
            orderStatus = "Withdrawing your " + ccy() + " market…"; render();
            try {
                Order o = loadOrder();
                for (Order.Pair p : o.pairs.values()) p.enable = false;
                o.minimaPublicKey = myMinimaPk;
                o.ethAddress = wallet.address();
                SwapOrderBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
                    @Override public void onSent(String txpowid) { SwapLog.d("switch: tombstoned " + current.key + " market " + txpowid); ui.post(proceed); }
                    @Override public void onFailed(String message) { SwapLog.w("switch: tombstone FAIL (" + current.key + "): " + message + " — ages out"); ui.post(proceed); }
                });
                ui.postDelayed(proceed, 4000);   // watchdog: never block the switch on a hung post
            } catch (Exception e) { proceed.run(); }
        } else {
            proceed.run();   // no live market — switch immediately, no delay
        }
    }

    // Per-currency market memory: the SINGLE active prefs are always the active currency's (fund-safe — no
    // key-by-key namespacing that could bleed). On switch we PARK the leaving currency's config under its own key
    // and RESTORE the arriving currency's, so a flick doesn't wipe your ladder. Only CONFIG keys are carried; the
    // runtime/price state (P_LAST_*, withdrawn/tombstoned/wide, last_publish_ok) starts fresh so the peg re-fetches
    // the target currency's own price (resetForSwitch clears them).
    private static final String[] MKT_STR = { "order_config", PriceOracle.P_STEP, PriceOracle.P_SIZE,
            PriceOracle.P_ASK_SIZE, PriceOracle.P_BID_SIZE, PriceOracle.P_BIAS, PriceOracle.P_REPRICE, PriceOracle.P_LEVELS,
            "otc_sell_size", "otc_buy_size" };
    // otc_enable/otc_auto park WITH their sizes: the switch now disarms OTC per currency, so the flags have to be
    // remembered per currency too, and restoring a flag without its sizes would arm the other market's numbers.
    private static final String[] MKT_BOOL = { "auto_publish", PriceOracle.P_ENABLE, "otc_enable", "otc_auto" };

    /** Park the ACTIVE currency's market CONFIG under its own key (static + prefs-only so it's unit-testable). */
    static void snapshotMarket(android.content.SharedPreferences prefs, String curKey) {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            for (String k : MKT_STR) { String v = prefs.getString(k, null); if (v != null) j.put(k, v); }
            for (String k : MKT_BOOL) j.put(k, prefs.getBoolean(k, false));
            prefs.edit().putString("market_" + curKey, j.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Restore a currency's parked market CONFIG into the active keys (no-op if none saved). Runtime/price state
     *  is deliberately NOT restored — the peg re-fetches the target currency's own price. */
    static void restoreMarket(android.content.SharedPreferences prefs, String targetKey) {
        String raw = prefs.getString("market_" + targetKey, null);
        if (raw == null) return;   // nothing saved for this currency — leave the market off (already reset)
        android.content.SharedPreferences.Editor e = prefs.edit();
        try {
            org.json.JSONObject j = new org.json.JSONObject(raw);
            for (String k : MKT_STR) if (j.has(k)) e.putString(k, j.getString(k));
            for (String k : MKT_BOOL) if (j.has(k)) e.putBoolean(k, j.getBoolean(k));
            // a restored market is live-able again (the price re-fetches fresh); clear the withdrawn/tombstone flags.
            e.putBoolean(PriceOracle.P_WITHDRAWN, false).putBoolean(PriceOracle.P_TOMBSTONED, false);
        } catch (Exception ignored) {}
        e.apply();
    }

    /** AlertDialog builder whose chrome follows the in-app theme (dark/light). */
    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog dialog() {
        Context themed = new androidx.appcompat.view.ContextThemeWrapper(this, Design.dialogTheme());
        return new LanguageDialog(themed) {
            private android.content.DialogInterface.OnDismissListener dismissed;
            @Override public LanguageDialog setOnDismissListener(android.content.DialogInterface.OnDismissListener action) {
                dismissed = action; return this;
            }
            @Override public AlertDialog create() {
                setBackground(ContextCompat.getDrawable(themed, Design.isDark() ? R.drawable.pm_atomix_dialog_bg_dark : R.drawable.pm_atomix_dialog_bg_light));
                AlertDialog value = super.create(); languageDialogs.add(value);
                final android.content.DialogInterface.OnDismissListener callback = dismissed;
                value.setOnDismissListener(d -> { languageDialogs.remove(value); if (callback != null) callback.onDismiss(d); });
                return value;
            }
        };
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); Language.configurationChanged(this); AtomixMessages.ensureChannels(this);
    }
    private CharSequence glowLabel(Design.Glow value) {
        switch (value) {
            case OFF: return Language.msg(l10n_atomix_ui_glow_off);
            case CALM: return Language.msg(l10n_atomix_ui_glow_calm);
            case LIVELY: return Language.msg(l10n_atomix_ui_glow_lively);
            default: return Language.msg(l10n_atomix_ui_glow_insistent);
        }
    }

    /** The active currency's coin label ("MINIMA" / "mxUSDT") for user-facing strings. */
    private static String ccy() { return TradingContext.active().coinLabel; }

    // ---- pairing + identity ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        // Say WHICH fault this is. A wedged node and a disabled app both stop the app dead, but the
        // remedies are opposite, and telling a user to enable an already-enabled app wastes the one
        // moment they are looking at the screen.
        if (!enabled) Language.setText(pairingBanner, nodeOfflineText());
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        SwapLog.d("fg paired=" + enabled);
        if (enabled) {
            IdentityWatch.forceNext();   // a node that just (re)appeared is the likeliest moment for a reseed
            fetchMinimaBalance();
            if (wallet.ready()) fetchEthBalances(true);
            ensureDerivations();
            scanOrderBook();
            startWatcher();
        }
        render();
    }

    /** True while an async derivation is in flight — so retries never stack duplicate calls. */
    private boolean walletDeriving = false, minimaSettingUp = false, identityRequesting = false;

    /** Idempotent, retrying bootstrap (was one-shot with silent error callbacks — a transient failure
     *  left publish/identity dead for the session). Safe to call every tick; each block re-fires only
     *  while its target isn't ready and nothing is in flight. */
    private void ensureDerivations() {
        if (!wallet.ready() && !walletDeriving) {
            walletDeriving = true;
            wallet.deriveFromNode(node, ui, new EthWallet.Cb() {
                @Override public void ok(String address) { walletDeriving = false; ethAddr = address; ethErr = null; render(); fetchEthBalances(true); maybeStartEngine(); }
                @Override public void err(String msg) { walletDeriving = false; ethErr = msg; SwapLog.w("fg eth wallet derive failed"); render(); }   // key errors are fixed safe messages; never expose raw node material
            });
        }
        if (identity == null && !identityRequesting) setupIdentity();
        if (!minima.ready() && !minimaSettingUp) {
            minimaSettingUp = true;
            // Reuse a persisted identity — getaddress rotates through 64 keys, so a fresh one each
            // session would break discovery/resume (the maker's published key must stay constant).
            String savedAddr = prefs.getString("swap_addr", "");
            String savedPk = prefs.getString("swap_pk", "");
            minima.setup(savedAddr, savedPk, new MinimaHtlc.SetupCb() {
                @Override public void ok(String a, String pk) {
                    minimaSettingUp = false;
                    myMinimaPk = pk;
                    prefs.edit().putString("swap_addr", a).putString("swap_pk", pk).apply();
                    engine.setMyMinimaPk(pk);
                    minima.loadMyKeys(new MinimaHtlc.KeysCb() {
                        @Override public void ok(java.util.Set<String> keys) { engine.setMyPubkeys(keys); }
                        @Override public void err(String m) { SwapLog.w("fg loadMyKeys: " + m); /* refund still works for the publish key */ }
                    });
                    maybeStartEngine(); render();
                }
                @Override public void err(String m) { minimaSettingUp = false; SwapLog.w("fg minima setup: " + m); }
            });
        }
    }

    /** Once both the ETH wallet and Minima identity are ready, run a first poll to resume any swaps. */
    private void maybeStartEngine() {
        if (paired && wallet.ready() && minima.ready() && engine != null) {
            loadIncoming();        // re-arm any pending buy handshakes from a prior session
            engine.poll();
            startSwapService();
            scanTakeRequests();
            render();
        }
    }

    /** Start the background watcher so swaps progress with the app closed (once, when ready). */
    private void startSwapService() {
        if (serviceStarted) return;
        serviceStarted = true;
        try { androidx.core.content.ContextCompat.startForegroundService(this, new android.content.Intent(this, SwapService.class)); }
        catch (Exception ignored) {}
        try { SwapWorker.schedule(this); } catch (Exception ignored) {}
        try { HeartbeatReceiver.schedule(this); } catch (Exception ignored) {}   // Doze-proof publish heartbeat
        requestBatteryExemption();
    }

    /** Ask once to be exempt from battery optimisation so the watcher keeps running while the app is closed. */
    private void requestBatteryExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;   // already exempt — nothing to do
            // Re-prompt (at most weekly) until it's actually granted, instead of asking once and giving up —
            // an unexempt phone is a leading cause of the overnight market disappearing.
            long last = prefs.getLong("batt_asked_at", 0), now = System.currentTimeMillis();
            if (last != 0 && now - last < 7L * 24 * 60 * 60_000L) return;   // asked recently — don't nag every launch
            prefs.edit().putLong("batt_asked_at", now).apply();
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private void setupIdentity() {
        identityRequesting = true;
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (!ikm.isEmpty()) deriveIdentity(ikm);
                else { identityRequesting = false; SwapLog.w("fg identity: empty seed reply (vault locked?)"); }
            }
            @Override public void onError(String m) { identityRequesting = false; SwapLog.w("fg identity seed read: " + m); }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed, TradingContext.active().hkdfContext);
                ui.post(() -> { identity = id; crypto = new LocalEcCryptoProvider(ls, id); identityRequesting = false; SwapLog.d("fg comms identity ready"); render(); scanOrderBook(); scanOtc(); });
            } catch (Exception e) {
                ui.post(() -> { identityRequesting = false; toast(Language.msg(l10n_atomix_notice_identity_failed)); });
            }
        });
    }

    // ---- balances ----

    private void fetchMinimaBalance() {
        node.cmd("balance tokenid:" + TradingContext.active().tokenId, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONObject t = null;
                if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) t = ((JSONArray) resp).optJSONObject(0);
                else if (resp instanceof JSONObject) t = (JSONObject) resp;
                if (t != null) {
                    minimaBal = t.optString("sendable", "0");          // tradeable: simple-address coins only
                    minimaConfirmed = t.optString("confirmed", "—");   // all your confirmed coins (incl. contract-locked)
                    minimaUnconfirmed = t.optString("unconfirmed", "—");
                    minimaCoins = t.optString("coins", "—");
                }
                lastMinimaUpdate = System.currentTimeMillis();
                render();   // fires any pending balance pulse via firePendingPulses() when the Wallet tab is showing
            }
            @Override public void onError(String m) { minimaBal = "— (node didn't answer)"; lastMinimaUpdate = System.currentTimeMillis(); render(); }
        });
    }

    /** Diagnostic: list the coins the node counts as relevant to this wallet, by address — so we can see
     *  exactly what inflates the balance (e.g. shared order-book / HTLC / casino coins vs your simple coins). */
    private void minimaCoinDump() {
        toast(Language.msg(l10n_atomix_report_reading));
        final String token = TradingContext.active().tokenId;
        minima.myRelevantCoins(coins -> {
                if (!token.equals(TradingContext.active().tokenId)) return;
                Language.TextBuilder sb = new Language.TextBuilder();
                sb.append(Language.msg(l10n_atomix_report_balance, AtomixMessages.message(Util.tidyAmount(minimaBal)), Util.tidyAmount(minimaConfirmed), coins.length()));
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    if (c == null) continue;
                    String amt = MinimaHtlc.coinAmount(c);
                    String addr = c.optString("address", "");
                    try { sum = sum.add(new java.math.BigDecimal(amt)); } catch (Exception ignore) {}
                    String mx = c.optString("miniaddress", "");
                    CharSequence tag = "";
                    if (MinimaHtlc.HTLC_ADDRESS.equalsIgnoreCase(mx)) tag = Language.msg(l10n_atomix_report_htlc);
                    else if (SwapOrderBook.address().equalsIgnoreCase(addr)) tag = Language.msg(l10n_atomix_report_book);
                    sb.append(Util.tidyAmount(amt)).append("   ")
                      .append(addr).append(tag).append("\n");   // RULE 1: full address (the dump is a scrollable text view)
                }
                sb.append(Language.msg(l10n_atomix_report_sum, Util.tidyAmount(sum.toPlainString())));
                showText(Language.msg(l10n_atomix_report_breakdown), sb.build());
        }, m -> showText(Language.msg(l10n_atomix_report_breakdown), Language.msg(l10n_atomix_report_coin_failed, m)), balance -> {
            if (!token.equals(TradingContext.active().tokenId)) return;
            modalOpen = true;
            dialog().setTitle(Language.msg(l10n_atomix_report_breakdown))
                    .setMessage(Language.msg(l10n_atomix_report_oversized, balance.sendable, balance.confirmed, balance.unconfirmed, balance.coins, MinimaHtlc.maxSafeCoinRows(token)))
                    .setPositiveButton(Language.msg(l10n_atomix_operation_consolidate), (d, w) -> consolidateDialog())
                    .setNegativeButton(Language.msg(l10n_language_close), null)
                    .setOnDismissListener(d -> { modalOpen = false; render(); }).show();
        });
    }

    private void showText(CharSequence title, CharSequence body) {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView t = new TextView(this);
        Language.setText(t, body); t.setTextColor(Design.TEXT()); t.setTextSize(12.5f);
        t.setTypeface(Design.mono()); t.setTextIsSelectable(true);
        box.addView(t);
        modalOpen = true;
        dialog().setTitle(title).setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_language_close), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); }).show();
    }

    /** Full Minima balance breakdown so the displayed numbers are never a black box. */
    private CharSequence minimaBreakdown() {
        String locked = "—";
        try {
            locked = Util.fmt5(new java.math.BigDecimal(minimaConfirmed).subtract(new java.math.BigDecimal(minimaBal)).toPlainString());
        } catch (Exception ignore) {}
        CharSequence ago = lastMinimaUpdate == 0 ? Language.msg(l10n_atomix_ui_never) : Language.since(lastMinimaUpdate);
        return Language.msg(l10n_atomix_ui_balance_details, Util.fmt5(minimaConfirmed), locked, Util.fmt5(minimaUnconfirmed), minimaCoins, ago);
    }

    /** Refresh both wallet balances. showLoading=false → silent (periodic) update, no "…" flicker. */
    private void refreshBalances(boolean showLoading) {
        if (!paired) return;
        fetchMinimaBalance();
        fetchBlock();
        if (wallet.ready()) fetchEthBalances(showLoading);
    }

    /** If a swap has NEWLY reached COMPLETE since we last looked (foreground or while backgrounded), arm a
     *  balance pulse and force a visible reload. The count is persisted so a completion that finished while
     *  the app was gone still pulses on return, and pre-existing completions never false-trigger.
     *  Returns true if it kicked off a reload (so callers can skip a redundant silent refresh). */
    private boolean checkForNewCompletion() {
        if (db == null) return false;
        int complete = 0;
        for (SwapDb.Swap s : db.allSwaps()) if (SwapDb.ST_COMPLETE.equals(s.status)) complete++;
        if (lastCompleteCount < 0) {                                       // baseline on first run, then persist it
            lastCompleteCount = prefs.getInt("swap_complete_count", complete);
            prefs.edit().putInt("swap_complete_count", lastCompleteCount).apply();
        }
        if (complete > lastCompleteCount) {
            pulseMinimaPending = true;
            pulseEthPending = true;
            lastCompleteCount = complete;
            prefs.edit().putInt("swap_complete_count", complete).apply();
            refreshBalances(true);
            return true;
        }
        if (complete != lastCompleteCount) {   // terminal count can't really shrink; resync quietly if it did
            lastCompleteCount = complete;
            prefs.edit().putInt("swap_complete_count", complete).apply();
        }
        return false;
    }

    private void fetchBlock() {
        if (!paired || minima == null) return;
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) { chainBlock = block; render(); }
            @Override public void err(String m) {}
        });
    }

    private void fetchEthBalances(boolean showLoading) {
        if (!wallet.ready()) return;
        final EthRpc r = rpc;
        final EthNet n = net;
        if (showLoading) { ethBal = "…"; render(); }
        io.execute(() -> {
            String wei, err = null;
            LinkedHashMap<String, String> toks = new LinkedHashMap<>();
            BigInteger rawWei = null, rawUsdt = null;   // RAW balances — send validation never uses display strings
            try {
                BigInteger w = wallet.ethBalanceWei(r);
                rawWei = w;
                wei = EthWallet.format(w, 18, 6) + " ETH";
                for (EthNet.Token tk : n.tokens) {
                    try {
                        BigInteger raw = wallet.erc20BalanceRaw(r, tk.address);
                        if ("USDT".equals(tk.symbol)) rawUsdt = raw;
                        toks.put(tk.symbol, EthWallet.format(raw, tk.decimals, 6));
                    } catch (Exception e) { toks.put(tk.symbol, "—"); }
                }
            } catch (Exception e) { wei = "—"; err = e.getMessage(); }
            final String fwei = wei, ferr = err;
            final BigInteger fRawWei = rawWei, fRawUsdt = rawUsdt;
            ui.post(() -> {
                if (n != net) return;
                if (fRawWei != null) ethWeiRaw = fRawWei;
                if (fRawUsdt != null) usdtRawBal = fRawUsdt;
                ethBal = fwei;
                tokenBals.clear(); tokenBals.putAll(toks);
                String usdt = toks.get("USDT");
                if (usdt != null) prefs.edit().putString("usdt_avail", usdt).apply();   // for the background republish
                ethErr = ferr;   // set AND clear — a transient RPC/DNS blip must not pin the banner until restart
                render();   // fires any pending balance pulse via firePendingPulses() when the Wallet tab is showing
            });
        });
    }

    private String rpcUrl(EthNet n) {
        return prefs.getString("rpc_mainnet", n.defaultRpc);
    }

    // ---- order book ----

    private void scanOrderBook() {
        if (!paired) return;
        SwapOrderBook.scan(node, ls,
                book -> {
                    // If I've withdrawn (auto_publish off), don't let my own still-on-chain order linger in MY
                    // view until it ages out (~1h) — drop it now. My tombstone withdraws it for peers next block.
                    if (identity != null && !prefs.getBoolean("auto_publish", false))
                        book.remove("0x" + com.eurobuddha.atomix.integratedcomms.Hex.to(identity.signPk));
                    // Belt (fg parity with the Service's reconcileBookPresence): the ok-stamp claims a recent
                    // publish landed but my coin is missing/stale (vault-locked pending / orphaned txn) →
                    // zero the stamp so the next tick republishes. Only ever FORCES a publish.
                    if (identity != null && prefs.getBoolean("auto_publish", false)
                            && !prefs.getBoolean(PriceOracle.P_WITHDRAWN, false)) {
                        Order mine = book.get("0x" + com.eurobuddha.atomix.integratedcomms.Hex.to(identity.signPk));
                        long stamp = prefs.getLong("last_publish_ok", 0), now = System.currentTimeMillis();
                        if (stamp != 0 && now - stamp > 5 * 60_000 && now - stamp < REPUBLISH_INTERVAL_MS
                                && (mine == null || now - mine.ts > REPUBLISH_INTERVAL_MS + 10 * 60_000)) {
                            prefs.edit().putLong("last_publish_ok", 0).apply();
                            SwapLog.w("fg book-miss — publish claimed OK but isn't on the book; forcing republish");
                        }
                    }
                    orderBook.clear(); orderBook.putAll(book); render();
                },
                err -> { orderStatus = "Book scan: " + err; render(); });
        scanTakeRequests();
    }

    /** Scan the OTC availability board (other LPs) + drive the OTC negotiation-message receiver. Light + bounded. */
    private void scanOtc() {
        if (!paired || ls == null || identity == null) return;
        refreshOtcSelf();
        OtcBook.scan(node, ls, book -> {
            book.remove("0x" + com.eurobuddha.atomix.integratedcomms.Hex.to(identity.signPk));   // exclude my own offer from the LP list
            otcBook.clear(); otcBook.putAll(book);
            if (currentTab == TAB_OTC && !modalOpen) render();
        }, err -> {});
        if (otc != null && crypto != null) {
            if (otcScanner == null)
                otcScanner = new CommsScanner(node, crypto, new PrefsMeta(prefs), OtcMessage.address(), otc::route, (ok, n) -> {});
            otcScanner.scan(chainBlock);
            otc.expireStale(System.currentTimeMillis());
        }
    }

    private void refreshOtcSelf() {
        if (otc != null && crypto != null && identity != null && myMinimaPk != null && ethAddr != null) {
            otc.setSelf(crypto, identity, myMinimaPk, ethAddr);
            otc.setMyOffer(prefs.getBoolean("otc_enable", false),
                    parseD(prefs.getString("otc_sell_size", "0"), 0), parseD(prefs.getString("otc_buy_size", "0"), 0));
        }
    }

    // ---- buy handshake (maker side): receive buyers' hashlocks so we can getContract their USDT lock ----

    private void scanTakeRequests() {
        if (!paired || crypto == null || identity == null || engine == null) return;
        if (takeScanner == null) {
            takeScanner = new CommsScanner(node, crypto, new PrefsMeta(prefs), SwapTake.address(), this::routeTakeRequest, (ok, n) -> {});
        }
        takeScanner.scan(chainBlock);
    }

    private boolean routeTakeRequest(String coinid, Opened opened, JSONObject coin) {
        try {
            JSONObject j = new JSONObject(new String(opened.plaintext, StandardCharsets.UTF_8));
            String to = j.optString("to", ""), from = j.optString("from", ""), hash = j.optString("hash", "");
            if (hash.isEmpty() || !identity.publicId().equals(to)) return false;       // not for me
            if (opened.fromPublicId == null || !opened.fromPublicId.equals(from)) return false;  // sender mismatch
            return addIncoming(hash);
        } catch (Exception e) { return false; }
    }

    /** Record a buyer's hashlock (engine discovers their USDT lock via getContract) + persist for resume. */
    private boolean addIncoming(String hash) {
        java.util.Set<String> set = incomingHashlocks();
        boolean isNew = set.add(hash);
        if (isNew) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
        if (engine != null) engine.addIncomingHashlock(hash);
        if (isNew) {   // make the handshake visible — the buyer wants to know it landed
            // Act NOW — don't wait for the next ~90s poll. Stand down only mid-SELL-sweep (same as watchTick),
            // since that path also selects mxUSDT coins and a concurrent responder lock could double-select.
            if (engine != null && (sweepRun == null || !sweepRun.sell)) engine.checkBuyNow(hash);
            postNotification("Buy request received", "A buyer wants your " + ccy() + " — finding their USDT lock, then locking.");
            orderStatus = "↧ Buy request received — locking " + ccy() + " for a buyer…"; render();
        }
        return isNew;
    }

    private java.util.Set<String> incomingHashlocks() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String h : prefs.getString("incoming_hashlocks", "").split(",")) if (!h.isEmpty()) set.add(h);
        return set;
    }

    /** Re-arm the engine with persisted handshakes after a restart, pruning ones that have fully resolved. */
    private void loadIncoming() {
        if (engine == null || db == null) return;
        java.util.Set<String> set = incomingHashlocks();
        boolean changed = false;
        for (java.util.Iterator<String> it = set.iterator(); it.hasNext(); ) {
            String h = it.next();
            SwapDb.Swap s = db.getSwap(h);
            if (s != null && isTerminal(s)) { it.remove(); changed = true; }   // handshake done — stop persisting it
            else engine.addIncomingHashlock(h);
        }
        if (changed) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
    }

    /** Build the base order (pairs + my identities + USDT avail); returns null if no pair is enabled. */
    private Order baseOrder() {
        Order o = loadOrder();
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        o.usdtAvail = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);
        for (Order.Pair p : o.pairs.values()) if (p.enable) return o;
        return null;
    }

    private void publishOrder() {
        if (identity == null || myMinimaPk == null) { toast(Language.msg(l10n_mail_still_connecting)); return; }
        if (!wallet.ready()) { toast(Language.msg(l10n_atomix_ui_wallet_not_ready)); return; }
        if (prefs.getBoolean(PriceOracle.P_ENABLE, false) && !PriceOracle.fresh()) {
            // Pegged: grab a live price first so a user-tapped Publish never quotes a stale one.
            orderStatus = "Fetching " + PriceOracle.source() + " price…"; render();
            final TradingContext market = TradingContext.active();
            final long generation = PublishGate.gen();
            io.execute(() -> { PriceOracle.refreshSync(); ui.post(() -> {
                if (market != TradingContext.active() || generation != PublishGate.gen()) { orderStatus = SwapOrderBook.MARKET_ERROR; render(); return; }
                publishOrderNow();
            }); });
            return;
        }
        publishOrderNow();
    }

    private void publishOrderNow() {
        final Order o = baseOrder();
        if (o == null) { toast(Language.msg(l10n_atomix_market_enable_pair)); return; }
        // Pegged ladders are regenerated around the oracle mid at EVERY publish. Only when there's NO usable
        // price at all does it refuse; a stale-but-known price publishes a widened defensive ladder (stay-live).
        final int peg = PriceOracle.applyPeg(o, prefs);
        if (peg == PriceOracle.PEG_STALE) {
            orderStatus = "✗ " + PriceOracle.source() + " price unavailable — can't peg yet (no price)";
            render(); return;
        }
        final boolean wide = peg == PriceOracle.PEG_WIDE;
        final double pegMid = PriceOracle.appliedMid();
        PublishGate.bumpGen();   // manual supersedes auto: any in-flight AUTO publish result is now stale
        final long gen = PublishGate.gen();   // …and a LATER user action supersedes THIS one the same way
        prefs.edit().putBoolean("auto_publish", true).apply();   // opt in to keep it live + fresh
        orderStatus = "Publishing your order…";
        SwapLog.d("fg manual publish attempt");
        render();
        // ensureLadderCoins clamps the ask ladder to the tranches I can actually lock right now (+ splits toward
        // full depth in the background); publishFresh then re-reads the live SENDABLE balance so the advertised
        // size is never a stale snapshot.
        engine.ensureLadderCoins(o, !SWEEP_ACTIVE, () -> SwapOrderBook.publishFresh(node, ls, identity, o, gen, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                if (gen != PublishGate.gen()) { SwapLog.d("fg manual publish superseded — result dropped"); return; }
                prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).apply();
                SwapLog.d("fg manual publish OK " + txpowid);
                if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);   // baseline moves only on a CONFIRMED publish
                engine.setMyOrder(o);
                orderStatus = wide
                        ? "✓ Order live — pegged to " + PriceOracle.source() + " (feed stale — defensive spread; auto-tightens when it recovers)"
                        : peg == PriceOracle.PEG_APPLIED
                        ? "✓ Order live — pegged to " + PriceOracle.source() + " (mid " + fmtPrice(PriceOracle.mid()) + "), auto-reprices"
                        : "✓ Order published — auto-refreshes every 30 min";
                render(); ui.postDelayed(MainActivity.this::scanOrderBook, 2000);
            }
            @Override public void onFailed(String message) { orderStatus = "Publish failed: " + message; SwapLog.w("fg manual publish FAIL: " + message); render(); }
        }));
    }

    /** Keep a published order live + its advertised size current: re-publish ~every 30 min (fresh sendable),
     *  or NOW when the pegged oracle price has moved past the reprice threshold / the feed just recovered.
     *  Runs off {@code last_publish_ok} (stamped ONLY on a confirmed publish) — a failed attempt retries on
     *  the next 90s tick instead of silently suppressing the keep-alive for 30 min. */
    private void maybeAutoRepublish() {
        if (IdentityWatch.halted()) { SwapLog.skip("fgpub", "HALTED: app keys do not match the node"); return; }
        if (identity == null || myMinimaPk == null || !wallet.ready()) { SwapLog.skip("fgpub", "waiting: identity/wallet not ready"); return; }
        if (!prefs.getBoolean("auto_publish", false)) return;
        long okStamp = prefs.getLong("last_publish_ok", 0);
        boolean reprice = PriceOracle.shouldReprice(prefs, okStamp);
        if (!reprice && System.currentTimeMillis() - okStamp < REPUBLISH_INTERVAL_MS) return;
        final Order o = baseOrder();
        if (o == null) return;   // all pairs disabled → nothing to keep live
        if (!PublishGate.tryAcquire(PublishGate.ORDER)) { SwapLog.skip("fgpub", "gate busy"); return; }
        final boolean restored = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
        final int peg = PriceOracle.applyPeg(o, prefs);
        if (peg == PriceOracle.PEG_STALE) {   // NO usable price at all → don't publish (withdraw runs on the tick)
            PublishGate.release(PublishGate.ORDER);
            SwapLog.skip("fgpub", "peg stale — no usable price");
            return;
        }
        final boolean wide = peg == PriceOracle.PEG_WIDE;   // stale but priced from the last-good mid, defensive spread
        final double pegMid = PriceOracle.appliedMid();
        final long gen = PublishGate.gen();
        SwapLog.d("fg publish attempt reason=" + (reprice ? "reprice" : "interval"));
        engine.ensureLadderCoins(o, !SWEEP_ACTIVE, () -> {
            // Re-check BEFORE the send: a withdraw/edit or peg tombstone during the coin/balance reads
            // must not be out-freshened on-chain by this stale ladder (its ts is stamped at publish time).
            if (gen != PublishGate.gen()) {
                PublishGate.release(PublishGate.ORDER);
                SwapLog.d("fg publish superseded pre-send — dropped");
                return;
            }
            SwapOrderBook.publishFresh(node, ls, identity, o, gen, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                if (gen != PublishGate.gen()) {   // a user edit/withdraw or peg-tombstone superseded this attempt
                    PublishGate.release(PublishGate.ORDER);
                    SwapLog.d("fg publish superseded — result dropped");
                    return;
                }
                prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).apply();
                if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);   // baseline moves only on a CONFIRMED publish
                engine.setMyOrder(o);
                PublishGate.release(PublishGate.ORDER);
                SwapLog.d("fg publish OK " + txpowid);
                if (restored && peg == PriceOracle.PEG_APPLIED) {
                    orderStatus = "✓ " + PriceOracle.source() + " feed recovered — pegged order is live again";
                    postNotification(PriceOracle.source() + " feed recovered", "Your pegged order is live again.");
                    render();
                }
            }
            @Override public void onFailed(String message) {
                PublishGate.release(PublishGate.ORDER);
                SwapLog.w("fg publish FAIL: " + message);   // retried on the next 90s tick (ok-stamp untouched)
            }
            });
        });
    }

    /** FUND-SAFETY, last resort: only when there is NO usable price AT ALL (never fetched, none persisted) and
     *  the feed's been down past the window do we publish a TOMBSTONE + DISARM the guard. With a last-good mid
     *  the peg STAYS LIVE at a widened defensive spread instead (see applyPeg / PEG_WIDE), so a sleeping phone
     *  keeps supplying liquidity overnight. auto_publish stays ON either way. */
    private void maybePegWithdraw() {
        if (identity == null || myMinimaPk == null || !wallet.ready() || engine == null) return;
        final boolean withdrawn = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
        if (withdrawn && prefs.getBoolean(PriceOracle.P_TOMBSTONED, false)) return;   // tombstone confirmed — done
        if (PriceOracle.mid() > 0 && !PriceOracle.feedDownPastCeiling()) return;   // have a usable price & within the ceiling → stay live
        if (!withdrawn && !PriceOracle.feedDownPastLimit()) return;   // no price yet, but not down long enough
        final boolean first = !withdrawn;                // else: retrying a tombstone whose broadcast failed
        if (first) prefs.edit().putBoolean(PriceOracle.P_WITHDRAWN, true)
                .putBoolean(PriceOracle.P_TOMBSTONED, false).apply();   // withdrawn state FIRST — kill-safe
        PublishGate.bumpGen();            // any in-flight AUTO publish result is now stale — it must not re-arm the ladder
        engine.setMyOrder(new Order());   // disarm NOW — an empty order declines every take — even if the publish fails
        SwapLog.w("fg peg withdraw — tombstoning (feed unusable)");
        Order o = loadOrder();            // tombstone: the saved order with no liquidity (freshest-per-signer wins)
        for (Order.Pair p : o.pairs.values()) p.enable = false;
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        SwapOrderBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {   // confirmed on the wire — stop retrying
                prefs.edit().putBoolean(PriceOracle.P_TOMBSTONED, true).apply();
                SwapLog.d("fg tombstone confirmed " + txpowid);
            }
            @Override public void onFailed(String message) { SwapLog.w("fg tombstone publish FAIL: " + message); /* retried next tick */ }
        });
        if (first) {
            orderStatus = "⚠ " + PriceOracle.source() + " feed lost — pegged order withdrawn until it recovers";
            postNotification(PriceOracle.source() + " price feed lost",
                    "Your pegged order was withdrawn for safety. It re-publishes automatically when the feed recovers.");
            render();
        }
    }

    /** Push a just-saved order to the book NOW so an edit — especially DISABLE — takes effect in seconds, not
     *  after the ~30-min auto-republish or the ~1-h coin age-out. A still-liquid order re-publishes (update); an
     *  emptied/disabled one publishes an empty order as a TOMBSTONE (freshest-per-signer + empty effectiveBids/
     *  Asks ⇒ peers drop my liquidity on their next scan) and stops auto-publishing. Only runs for a LIVE order. */
    private void pushOrderEdit(Order o) {
        if (IdentityWatch.halted()) { toast(Language.msg(l10n_atomix_otc_halted)); return; }
        if (identity == null || myMinimaPk == null || !wallet.ready()) return;
        final boolean live = o.hasLiquidity();
        final int peg = live ? PriceOracle.applyPeg(o, prefs) : PriceOracle.PEG_OFF;
        if (peg == PriceOracle.PEG_STALE) {
            orderStatus = "Saved. " + PriceOracle.source() + " price unavailable — publishes when a price arrives.";
            render(); return;
        }
        final boolean wide = peg == PriceOracle.PEG_WIDE;
        final double pegMid = PriceOracle.appliedMid();
        PublishGate.bumpGen();   // user edit/withdraw supersedes any in-flight AUTO publish (it must not resurrect old liquidity)
        final long gen = PublishGate.gen();   // …and a LATER user action / peg tombstone supersedes THIS one
        if (!live) prefs.edit().putBoolean("auto_publish", false).apply();   // disabled/emptied → stop keeping it live
        orderStatus = live ? "Updating your order…" : "Withdrawing your order…";
        SwapLog.d(live ? "fg order edit publish attempt" : "fg order withdraw (tombstone) attempt");
        render();
        final CommsTransport.SendCb cb = new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                if (gen != PublishGate.gen()) { SwapLog.d("fg order edit/withdraw superseded — result dropped"); return; }
                prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).apply();
                SwapLog.d((live ? "fg order edit OK " : "fg order withdraw OK ") + txpowid);
                if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);   // baseline moves only on a CONFIRMED publish
                engine.setMyOrder(o);
                orderStatus = live ? "✓ Order updated" : "✓ Order withdrawn — removed from the book";
                render(); ui.postDelayed(MainActivity.this::scanOrderBook, 2000);
            }
            @Override public void onFailed(String message) { orderStatus = (live ? "Update" : "Withdraw") + " failed: " + message; render(); }
        };
        if (live) engine.ensureLadderCoins(o, !SWEEP_ACTIVE, () -> SwapOrderBook.publishFresh(node, ls, identity, o, gen, cb));
        else      SwapOrderBook.publish(node, ls, identity, o, cb);   // tombstone: no coins/balance read needed
    }

    // ---- order config (persisted; drives both publish AND the responder match guard) ----

    private Order loadOrder() {
        return Order.fromConfigJson(prefs.getString("order_config", ""));   // shared ladder-aware loader
    }

    private void saveOrder(Order o) {
        prefs.edit().putString("order_config", Order.toConfigJson(o)).apply();
        if (engine != null) engine.setMyOrder(PriceOracle.armSafe(o, prefs));   // arm the responder match guard immediately
    }

    private void editOrderDialog() {
        final Order o = loadOrder();
        final String sym = PAIR_TOKENS[0];   // single pair today
        final Order.Pair p = o.pairs.get(sym);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        Language.setText(hint, Language.msg(l10n_atomix_market_editor_help, ccy(), sym, ccy()));
        hint.setTextColor(Design.DIM()); hint.setTextSize(12f); hint.setTypeface(Design.sans()); hint.setLineSpacing(dp(2), 1f); hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);

        final SwitchCompat sw = new SwitchCompat(this);
        Language.setText(sw, Language.msg(l10n_atomix_market_enabled)); sw.setTextColor(Design.DIM()); sw.setChecked(p.enable);
        box.addView(sw);

        // ---- auto-MM: peg the ladder to the live MEXC mxUSDT/USDT market ----
        box.addView(fieldLabel(Language.msg(l10n_atomix_market_auto_market, PriceOracle.source())));
        final SwitchCompat pegSw = new SwitchCompat(this);
        Language.setText(pegSw, Language.msg(l10n_atomix_market_peg, PriceOracle.source()));
        pegSw.setTextColor(Design.DIM());
        pegSw.setChecked(prefs.getBoolean(PriceOracle.P_ENABLE, false));
        box.addView(pegSw);
        final TextView pegPx = new TextView(this);
        pegPx.setTextColor(Design.DIM()); pegPx.setTextSize(11.5f); pegPx.setTypeface(Design.mono());
        pegPx.setPadding(0, dp(2), 0, dp(2));
        Language.setText(pegPx, MarketMessages.message(PriceOracle.describe()));
        box.addView(pegPx);
        TextView pegHint = new TextView(this);
        Language.setText(pegHint, TradingContext.active().pricingParity ? Language.msg(l10n_atomix_market_parity_help) : Language.msg(l10n_atomix_market_peg_help, PriceOracle.source(), ccy()));
        pegHint.setTextColor(Design.DIM2()); pegHint.setTextSize(11f); pegHint.setTypeface(Design.sans());
        pegHint.setLineSpacing(dp(2), 1f); pegHint.setPadding(0, 0, 0, dp(4));
        box.addView(pegHint);
        LinearLayout pegRow = new LinearLayout(this);
        pegRow.setOrientation(LinearLayout.VERTICAL); pegRow.setGravity(Gravity.CENTER_VERTICAL); pegRow.setPadding(0, dp(2), 0, dp(2));
        final EditText biasE = genField(Language.msg(l10n_atomix_market_skew)), repE = genField(Language.msg(l10n_atomix_market_reprice));
        biasE.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {   // skew may be negative
            String r = dest.toString().substring(0, dstart) + source.subSequence(start, end) + dest.toString().substring(dend);
            return (r.isEmpty() || r.matches("-?[0-9]*\\.?[0-9]*")) ? null : "";
        }});
        Language.setText(biasE, prefs.getString(PriceOracle.P_BIAS, ""));
        Language.setText(repE, prefs.getString(PriceOracle.P_REPRICE, "1"));
        LinearLayout.LayoutParams pr1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); pr1.rightMargin = dp(6);
        LinearLayout.LayoutParams pr2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pegRow.addView(fieldLabel(Language.msg(l10n_atomix_market_skew))); pegRow.addView(biasE, pr1); pegRow.addView(fieldLabel(Language.msg(l10n_atomix_market_reprice))); pegRow.addView(repE, pr2);
        box.addView(pegRow);

        // ladder params — the rows regenerate automatically as these change (no Generate button). "levels" =
        // how many tranches/side to build (1 = a single tight level); the peg reuses the same count.
        box.addView(fieldLabel(Language.msg(l10n_atomix_market_auto_fill)));
        LinearLayout gen = new LinearLayout(this); gen.setOrientation(LinearLayout.VERTICAL); gen.setGravity(Gravity.CENTER_VERTICAL); gen.setPadding(0, dp(4), 0, dp(2));
        final EditText midE = genField(Language.msg(l10n_atomix_market_mid)), stepE = genField(Language.msg(l10n_atomix_market_step)), levelsE = genField(Language.msg(l10n_atomix_market_levels));
        levelsE.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        Language.setText(levelsE, prefs.getString(PriceOracle.P_LEVELS, "1"));   // default 1 tranche/side; up to MAX_LEVELS opt-in
        if (pegSw.isChecked()) Language.setText(stepE, prefs.getString(PriceOracle.P_STEP, ""));   // step doubles as the peg's spacing
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); g1.rightMargin = dp(6);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); g2.rightMargin = dp(6);
        LinearLayout.LayoutParams g4 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gen.addView(fieldLabel(Language.msg(l10n_atomix_market_mid))); gen.addView(midE, g1); gen.addView(fieldLabel(Language.msg(l10n_atomix_market_step))); gen.addView(stepE, g2); gen.addView(fieldLabel(Language.msg(l10n_atomix_market_levels))); gen.addView(levelsE, g4);
        box.addView(gen);
        // independent per-side sizes — a blank/zero side is NOT seeded or published (one-sided market)
        LinearLayout genS = new LinearLayout(this); genS.setOrientation(LinearLayout.VERTICAL); genS.setGravity(Gravity.CENTER_VERTICAL); genS.setPadding(0, dp(2), 0, dp(2));
        final EditText askSizeE = genField(Language.msg(l10n_atomix_market_ask_size)), bidSizeE = genField(Language.msg(l10n_atomix_market_bid_size));
        String legacySize = prefs.getString(PriceOracle.P_SIZE, "");
        Language.setText(askSizeE, prefs.getString(PriceOracle.P_ASK_SIZE, legacySize));
        Language.setText(bidSizeE, prefs.getString(PriceOracle.P_BID_SIZE, legacySize));
        LinearLayout.LayoutParams gs1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); gs1.rightMargin = dp(6);
        LinearLayout.LayoutParams gs2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        genS.addView(fieldLabel(Language.msg(l10n_atomix_market_ask_size))); genS.addView(askSizeE, gs1); genS.addView(fieldLabel(Language.msg(l10n_atomix_market_bid_size))); genS.addView(bidSizeE, gs2);
        box.addView(genS);

        // Live balances — used to seed a pre-ladder (0.8.x) order's synthetic size so a no-op Save keeps it live.
        final double liveMinima = parseD(minimaBal, 0);
        final double liveUsdt = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);

        // ASKS (maker sells mxUSDT, higher) — laid out ORDER-BOOK style: highest (A6) at the top, best/lowest
        // ask A1 at the BOTTOM adjacent to the bids, so the spread sits in the middle. askRows[] stays indexed
        // A1..A6 (A1 = best); only the visual insertion order is reversed.
        TextView ah = new TextView(this); Language.setText(ah, Language.msg(l10n_atomix_market_asks, ccy()));
        ah.setTextColor(Design.RED()); ah.setTextSize(12.5f); ah.setTypeface(Design.sansBold()); ah.setLetterSpacing(0.03f); ah.setPadding(0, dp(12), 0, dp(2));
        box.addView(ah);
        final EditText[][] askRows = new EditText[Order.MAX_LEVELS][];
        for (int i = Order.MAX_LEVELS - 1; i >= 0; i--) {   // A6 first (top) … A1 last (bottom, by the spread)
            boolean legacy0 = i == 0 && p.asks.isEmpty() && p.enable && p.buy > 0;   // seed row from the live single-price ask
            double px = i < p.asks.size() ? p.asks.get(i).price : (legacy0 ? p.buy : 0);
            double am = i < p.asks.size() ? p.asks.get(i).amount : (legacy0 ? liveMinima : 0);
            askRows[i] = numRow2(box, "A" + (i + 1), px, am, Design.RED());
        }

        // BIDS (maker buys mxUSDT, lower)
        TextView bh = new TextView(this); Language.setText(bh, Language.msg(l10n_atomix_market_bids, ccy()));
        bh.setTextColor(Design.IN()); bh.setTextSize(12.5f); bh.setTypeface(Design.sansBold()); bh.setLetterSpacing(0.03f); bh.setPadding(0, dp(12), 0, dp(2));
        box.addView(bh);
        final EditText[][] bidRows = new EditText[Order.MAX_LEVELS][];
        for (int i = 0; i < Order.MAX_LEVELS; i++) {
            boolean legacy0 = i == 0 && p.bids.isEmpty() && p.enable && p.sell > 0;   // seed row from the live single-price bid
            double px = i < p.bids.size() ? p.bids.get(i).price : (legacy0 ? p.sell : 0);
            double am = i < p.bids.size() ? p.bids.get(i).amount : (legacy0 && p.sell > 0 ? liveUsdt / p.sell : 0);
            bidRows[i] = numRow2(box, "B" + (i + 1), px, am, Design.IN());
        }

        final EditText minE = numRow(box, Language.msg(l10n_atomix_market_minimum, ccy()), p.min);

        final TextView pv = new TextView(this); pv.setTextSize(12f); pv.setTypeface(Design.sans()); pv.setLineSpacing(dp(2), 1f); pv.setPadding(0, dp(10), 0, dp(2));
        box.addView(pv);
        final boolean[] revealError = {false};
        // As in Future Cash tabs, reveal only after the translated view has its final layout.
        pv.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (revealError[0]) view.post(() -> {
                if (revealError[0] && view.isAttachedToWindow())
                    view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
            });
        });

        final EditText[] parameters = {biasE, repE, midE, stepE, levelsE, askSizeE, bidSizeE, minE};
        final Runnable upd = () -> {
            revealError[0] = false;
            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }
            updateLadderPreview(pv, askRows, bidRows);
        };
        TextWatcher w = onChange(upd);
        repE.addTextChangedListener(w); minE.addTextChangedListener(w);
        for (EditText[] row : askRows) { row[0].addTextChangedListener(w); row[1].addTextChangedListener(w); }
        for (EditText[] row : bidRows) { row[0].addTextChangedListener(w); row[1].addTextChangedListener(w); }
        upd.run();

        // ---- peg wiring: preview the rows from the live oracle price; poll the price line while open ----
        final boolean[] dlgOpen = { true };
        final boolean[] pegAwaitFill = { false };
        final boolean[] filling = { false };   // programmatic setText below must not re-trigger the param auto-gen watcher
        final Runnable fillFromPeg = () -> {
            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }
            double m = PriceOracle.mid();
            if (!(m > 0) || !PriceOracle.fresh()) return;
            double bias = Math.max(-20, Math.min(20, parseD(biasE.getText().toString(), 0)));
            double quoted = m * (1 + bias / 100.0);
            if (!MarketDraft.generation(quoted, parseD(stepE.getText().toString(), 0), parseD(askSizeE.getText().toString(), 0), parseD(bidSizeE.getText().toString(), 0), Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1))))) { invalidMarketDraft(pv); return; }
            filling[0] = true;
            try {
                Language.setText(midE, trimSig(quoted));
                double step = parseD(stepE.getText().toString(), 0);
                double askSize = parseD(askSizeE.getText().toString(), 0), bidSize = parseD(bidSizeE.getText().toString(), 0);
                if (step <= 0 || !(askSize > 0 || bidSize > 0)) return;   // mid shown; rows need step + at least one side sized
                int n = Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1)));
                for (int i = 0; i < Order.MAX_LEVELS; i++) {
                    boolean askOn = i < n && askSize > 0, bidOn = i < n && bidSize > 0;   // blank a side whose size is 0 → single-sided
                    askRows[i][0].setText(askOn ? trimSig(quoted * (1 + (i + 1) * step / 100.0)) : ""); askRows[i][1].setText(askOn ? trimSig(askSize) : "");
                    bidRows[i][0].setText(bidOn ? trimSig(quoted * (1 - (i + 1) * step / 100.0)) : ""); bidRows[i][1].setText(bidOn ? trimSig(bidSize) : "");
                }
                pegAwaitFill[0] = false;
            } finally { filling[0] = false; }
            upd.run();
        };
        final Runnable pegModeUi = () -> {
            boolean on = pegSw.isChecked();
            midE.setEnabled(!on); midE.setAlpha(on ? 0.5f : 1f);   // the mid comes from the oracle while pegged
        };
        pegModeUi.run();
        final Runnable[] pegTick = new Runnable[1];
        pegTick[0] = () -> {
            if (!dlgOpen[0]) return;   // dialog dismissed → stop polling
            Language.setText(pegPx, MarketMessages.message(PriceOracle.describe()));
            if (pegSw.isChecked()) {
                PriceOracle.refreshAsync();
                if (pegAwaitFill[0]) fillFromPeg.run();   // fill once the first price lands
            }
            ui.postDelayed(pegTick[0], 2000);
        };
        pegTickRef = pegTick[0];   // MI-12: expose to onPause/onResume so it stops polling while backgrounded
        ui.postDelayed(pegTick[0], 1000);
        PriceOracle.refreshAsync();
        pegSw.setOnCheckedChangeListener((btn, on) -> {
            pegModeUi.run();
            if (on) {
                PriceOracle.refreshAsync();
                if (PriceOracle.fresh()) fillFromPeg.run();
                else { pegAwaitFill[0] = true; toast(Language.msg(l10n_atomix_market_fetching, PriceOracle.source())); }
            }
        });

        // Manual generation (unpegged): silent — it fires per keystroke, so invalid/partial params just
        // pause row updates rather than toasting on every character.
        final Runnable genManual = () -> {
            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }
            double mid = parseD(midE.getText().toString(), 0);
            double step = parseD(stepE.getText().toString(), 0);
            double askSize = parseD(askSizeE.getText().toString(), 0), bidSize = parseD(bidSizeE.getText().toString(), 0);
            if (mid <= 0 || step <= 0 || !(askSize > 0 || bidSize > 0)) return;
            filling[0] = true;
            try {
                int n = Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1)));
                for (int i = 0; i < Order.MAX_LEVELS; i++) {
                    boolean askOn = i < n && askSize > 0, bidOn = i < n && bidSize > 0;   // blank a side whose size is 0 → single-sided
                    {
                        askRows[i][0].setText(askOn ? trimSig(mid * (1 + (i + 1) * step / 100.0)) : ""); askRows[i][1].setText(askOn ? trimSig(askSize) : "");
                        bidRows[i][0].setText(bidOn ? trimSig(mid * (1 - (i + 1) * step / 100.0)) : ""); bidRows[i][1].setText(bidOn ? trimSig(bidSize) : "");
                    }
                }
            } finally { filling[0] = false; }
            upd.run();
        };
        // AUTO-GENERATE: any ladder-param edit rebuilds the 6+6 rows — the old "Generate 6 + 6" button was
        // easy to miss. Watchers attach AFTER all initial seeding, so opening the dialog never stomps a
        // saved (possibly hand-tuned) ladder; only an actual edit regenerates. repE isn't watched (the
        // reprice threshold doesn't shape the rows).
        final Runnable autoGen = () -> {
            if (filling[0]) return;
            revealError[0] = false;
            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }
            upd.run();
            if (pegSw.isChecked()) {
                if (PriceOracle.fresh()) fillFromPeg.run();
                else { pegAwaitFill[0] = true; PriceOracle.refreshAsync(); }   // pegTick fills once the price lands
            } else genManual.run();
        };
        TextWatcher gw = onChange(autoGen);
        midE.addTextChangedListener(gw); stepE.addTextChangedListener(gw);
        askSizeE.addTextChangedListener(gw); bidSizeE.addTextChangedListener(gw); biasE.addTextChangedListener(gw);
        levelsE.addTextChangedListener(gw);   // changing the level count re-fills the rows (blanks the extras)

        modalOpen = true;
        final AlertDialog editor = dialog()
                .setTitle(Language.msg(l10n_atomix_market_editor))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_common_save), null)
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(d -> { dlgOpen[0] = false; pegTickRef = null; modalOpen = false; render(); })
                .show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!marketParametersValid(parameters, pegSw.isChecked()) || !marketRowsValid(askRows, bidRows)) { revealError[0] = true; invalidMarketDraft(pv); pv.post(() -> pv.requestRectangleOnScreen(new android.graphics.Rect(0, 0, pv.getWidth(), pv.getHeight()), true)); return; }

            p.enable = sw.isChecked();
            p.asks.clear(); p.bids.clear();
            for (EditText[] row : askRows) p.asks.add(new Order.Level(parseD(row[0].getText().toString(), 0), parseD(row[1].getText().toString(), 0)));
            for (EditText[] row : bidRows) p.bids.add(new Order.Level(parseD(row[0].getText().toString(), 0), parseD(row[1].getText().toString(), 0)));
            p.min = parseD(minE.getText().toString(), p.min);
            // Editor is authoritative: a side the user leaves with no valid levels is OFF. Zero its scalar
            // first so sanitize (which only DERIVES a scalar when a side HAS levels) can't leave a stale
            // legacy price live — otherwise the engine's empty-ladder fallback would keep trading it.
            p.buy = 0; p.sell = 0;
            Order.sanitize(p);   // drop blanks/invalids, cap 6, sort, derive best-level scalars for non-empty sides
            // Persist the auto-MM peg config (SwapService republishes from the same prefs). Step/size are
            // the quick-generate fields — the peg reuses them as its ladder parameters.
            boolean pegOn = pegSw.isChecked();
            double pStep = parseD(stepE.getText().toString(), 0);
            double pAskSize = parseD(askSizeE.getText().toString(), 0), pBidSize = parseD(bidSizeE.getText().toString(), 0);
            if (pegOn && (pStep <= 0 || !(pAskSize > 0 || pBidSize > 0))) { pegOn = false; toast(Language.msg(l10n_atomix_market_peg_off)); }
            double pBias = Math.max(-20, Math.min(20, parseD(biasE.getText().toString(), 0)));
            double pRep = Math.max(0.1, parseD(repE.getText().toString(), 1));
            int pLevels = Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1)));
            double pSize = Math.max(pAskSize, pBidSize);   // legacy P_SIZE fallback (older builds read one shared size)
            prefs.edit().putBoolean(PriceOracle.P_ENABLE, pegOn)
                    .putString(PriceOracle.P_STEP, pStep > 0 ? trimSig(pStep) : "")
                    .putString(PriceOracle.P_SIZE, pSize > 0 ? trimSig(pSize) : "")
                    .putString(PriceOracle.P_ASK_SIZE, pAskSize > 0 ? trimSig(pAskSize) : "")
                    .putString(PriceOracle.P_BID_SIZE, pBidSize > 0 ? trimSig(pBidSize) : "")
                    .putString(PriceOracle.P_LEVELS, String.valueOf(pLevels))
                    .putString(PriceOracle.P_BIAS, pBias != 0 ? trimSig(pBias) : "")
                    .putString(PriceOracle.P_REPRICE, trimSig(pRep))
                    .putBoolean(PriceOracle.P_WITHDRAWN, pegOn && prefs.getBoolean(PriceOracle.P_WITHDRAWN, false))
                    .putBoolean(PriceOracle.P_TOMBSTONED, pegOn && prefs.getBoolean(PriceOracle.P_TOMBSTONED, false))
                    .apply();
            saveOrder(o);
            if (p.enable && p.asks.isEmpty() && p.bids.isEmpty())
                toast(Language.msg(l10n_atomix_market_no_levels));
            else
                toast(Order.crossed(p) ? Language.msg(l10n_atomix_market_saved_crossed) : Language.msg(l10n_atomix_market_saved));
            // If an order is already live, reflect this edit in the book NOW — especially a DISABLE, which
            // publishes a tombstone so it's withdrawn in seconds, not after the ~30-min auto-republish.
            if (prefs.getBoolean("auto_publish", false)) pushOrderEdit(o);
            editor.dismiss();
        });
    }

    private boolean marketParametersValid(EditText[] fields, boolean pegged) {
        String[] raw = new String[fields.length];
        for (int i = 0; i < fields.length; i++) raw[i] = fields[i].getText().toString();
        if (!MarketDraft.parameters(raw)) return false;
        if (!pegged) return true;
        double bias = Math.max(-20, Math.min(20, MarketDraft.number(raw[0], true, 0)));
        double quoted = PriceOracle.mid() * (1 + bias / 100.0);
        int levels = Math.max(1, Math.min(Order.MAX_LEVELS, (int) MarketDraft.number(raw[4], false, 1)));
        return MarketDraft.generation(quoted, MarketDraft.number(raw[3], false, 0),
                MarketDraft.number(raw[5], false, 0), MarketDraft.number(raw[6], false, 0), levels);
    }

    private boolean marketRowsValid(EditText[][]... sides) {
        for (EditText[][] side : sides) {
            String[][] raw = new String[side.length][2];
            for (int i = 0; i < side.length; i++) {
                raw[i][0] = side[i][0].getText().toString();
                raw[i][1] = side[i][1].getText().toString();
            }
            if (!MarketDraft.rows(raw)) return false;
        }
        return true;
    }

    private void invalidMarketDraft(TextView preview) {
        Language.setText(preview, Language.msg(l10n_atomix_market_invalid_number));
        preview.setTextColor(Design.RED());
    }

    /** Live ladder summary in the editor: level counts, best prices, side totals, crossed warning. */
    private void updateLadderPreview(TextView pv, EditText[][] askRows, EditText[][] bidRows) {
        if (!marketRowsValid(askRows, bidRows)) { invalidMarketDraft(pv); return; }
        int nA = 0, nB = 0; double bestAsk = Double.MAX_VALUE, bestBid = 0, sumA = 0, sumB = 0;
        for (EditText[] row : askRows) {
            double px = parseD(row[0].getText().toString(), 0), am = parseD(row[1].getText().toString(), 0);
            if (px > 0 && am > 0) { nA++; sumA += am; if (px < bestAsk) bestAsk = px; }
        }
        for (EditText[] row : bidRows) {
            double px = parseD(row[0].getText().toString(), 0), am = parseD(row[1].getText().toString(), 0);
            if (px > 0 && am > 0) { nB++; sumB += am; if (px > bestBid) bestBid = px; }
        }
        if (nA > 0 && nB > 0 && bestBid >= bestAsk) {
            Language.setText(pv, Language.msg(l10n_atomix_market_crossed, fmtPrice(bestBid), fmtPrice(bestAsk)));
            pv.setTextColor(Design.RED());
            return;
        }
        CharSequence bidS = nB > 0 ? Language.msg(l10n_atomix_market_preview_side, nB, fmtPrice(bestBid), abbrev(sumB), ccy()) : Language.msg(l10n_atomix_market_none);
        CharSequence askS = nA > 0 ? Language.msg(l10n_atomix_market_preview_side, nA, fmtPrice(bestAsk), abbrev(sumA), ccy()) : Language.msg(l10n_atomix_market_none);
        Language.setText(pv, Language.msg(l10n_atomix_market_preview, bidS, askS));
        pv.setTextColor(Design.DIM());
    }

    private static TextWatcher onChange(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { r.run(); }
        };
    }

    /**
     * Configure an EditText for decimal entry that the Samsung keyboard can't scramble.
     *
     * The "0.0054 → 0.0045 / cursor jumps" bug is the IME's composing/predictive region: on a
     * {@code numberDecimal} field, Samsung's keyboard keeps a composing region and re-commits keystrokes
     * out of order. {@code TYPE_TEXT_VARIATION_VISIBLE_PASSWORD} forces immediate-commit with no
     * composing/autocorrect (the reliable cure), and the InputFilter keeps it to one decimal number.
     * (Suppressing the periodic refresh — what the limit app tried — does NOT fix this.)
     */
    private void decimalInput(EditText e) {
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTransformationMethod(null);   // visible-password must still show the digits, not dots
        e.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {
            String result = dest.toString().substring(0, dstart)
                    + source.subSequence(start, end)
                    + dest.toString().substring(dend);
            return (result.isEmpty() || result.matches("[0-9]*\\.?[0-9]*")) ? null : "";
        }});
    }

    private EditText numRow(LinearLayout parent, CharSequence label, double value) {
        return numRow(parent, label, value, Design.DIM());
    }

    private EditText numRow(LinearLayout parent, CharSequence label, double value, int labelColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); Language.setText(t, label); t.setTextColor(labelColor); t.setTextSize(13f); t.setTypeface(Design.sans());
        EditText e = new EditText(this);
        decimalInput(e);
        Language.setText(e, trim(value)); e.setTextColor(Design.TEXT()); e.setTextSize(15f); e.setGravity(Gravity.END); e.setTypeface(Design.mono());
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(r);
        return e;
    }

    /** A ladder tranche row: [label][price][amount mxUSDT]. Blank fields (value ≤ 0) render empty = unused. */
    private EditText[] numRow2(LinearLayout parent, String label, double price, double amount, int labelColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(2), 0, dp(2));
        TextView t = new TextView(this); Language.setText(t, label); t.setTextColor(labelColor); t.setTextSize(12.5f); t.setTypeface(Design.mono());
        EditText pe = new EditText(this); decimalInput(pe);
        Language.hint(pe, Language.msg(l10n_atomix_market_price)); pe.setHintTextColor(Design.DIM2());
        Language.setText(pe, price > 0 ? trimSig(price) : ""); pe.setTextColor(Design.TEXT()); pe.setTextSize(14f); pe.setGravity(Gravity.END); pe.setTypeface(Design.mono());
        EditText ae = new EditText(this); decimalInput(ae);
        Language.hint(ae, ccy()); ae.setHintTextColor(Design.DIM2());
        Language.setText(ae, amount > 0 ? trimSig(amount) : ""); ae.setTextColor(Design.TEXT()); ae.setTextSize(14f); ae.setGravity(Gravity.END); ae.setTypeface(Design.mono());
        TextView clr = new TextView(this); Language.setText(clr, "✕"); clr.setTextColor(Design.DIM2()); clr.setTextSize(15f);
        clr.setTypeface(Design.sans()); clr.setGravity(Gravity.CENTER); clr.setPadding(dp(8), dp(4), dp(2), dp(4));
        clr.setOnClickListener(v -> { Language.setText(pe, ""); Language.setText(ae, ""); });   // clear this level (watcher then refreshes the preview)
        Language.description(clr, Language.msg(l10n_atomix_market_clear, label));
        clr.setMinimumWidth(dp(48)); clr.setMinimumHeight(dp(48));
        Design.pressable(clr);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); plp.leftMargin = dp(6);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); alp.leftMargin = dp(8);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT); clp.leftMargin = dp(2);
        r.addView(t, tlp); r.addView(pe, plp); r.addView(ae, alp); r.addView(clr, clp);
        parent.addView(r);
        return new EditText[]{ pe, ae };
    }

    private EditText genField(CharSequence hint) {
        EditText e = new EditText(this); decimalInput(e);
        Language.hint(e, hint); e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT()); e.setTextSize(14f); e.setTypeface(Design.mono()); e.setGravity(Gravity.CENTER);
        return e;
    }

    // ---- take an order (guided swap start) ----

    /**
     * Take a maker's order. We always trade mxUSDT, priced in USDT (USDT per mxUSDT). You enter a mxUSDT
     * amount; the USDT side is derived from the maker's price.
     *   - Sell mxUSDT → you give mxUSDT, receive USDT at the maker's SELL price (the lower / bid).
     *   - Buy mxUSDT  → you give USDT, receive mxUSDT at the maker's BUY price (the higher / ask).
     */
    private void takeOrderDialog(Order maker, String symbol, boolean sellMinima, final double price, final double maxAmount) {
        if (!Double.isFinite(price) || price <= 0) return;
        Order.Pair p = maker.pairs.get(symbol);
        final double min = p != null ? p.min : 0;   // maker's global per-trade minimum

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView info = new TextView(this);
        Language.setText(info, Language.msg(l10n_atomix_market_take_info, sellMinima ? Language.msg(l10n_atomix_market_take_sell, ccy(), symbol) : Language.msg(l10n_atomix_market_take_buy, ccy(), symbol), trim(price), symbol, ccy(), abbrev(maxAmount), ccy(), min > 0 ? Language.msg(l10n_atomix_market_take_min, trim(min), ccy()) : ""));
        info.setTextColor(Design.DIM()); info.setTextSize(13f); info.setTypeface(Design.sans()); info.setLineSpacing(dp(3), 1f); info.setPadding(0, 0, 0, dp(10));
        box.addView(info);

        final EditText amt = new EditText(this);
        Language.hint(amt, sellMinima ? Language.msg(l10n_atomix_market_amount_sell, ccy()) : Language.msg(l10n_atomix_market_amount_buy, ccy()));
        decimalInput(amt);
        amt.setTextColor(Design.TEXT()); amt.setHintTextColor(Design.DIM2()); amt.setTextSize(16f); amt.setTypeface(Design.mono());
        box.addView(amt);

        final TextView out = new TextView(this);
        out.setTextColor(Design.ACCENT()); out.setTextSize(14f); out.setTypeface(Design.sans()); out.setPadding(0, dp(10), 0, 0);
        box.addView(out);

        amt.addTextChangedListener(new SimpleWatcher(() -> {
            String usdt = computeUsdt(amt.getText().toString(), price);
            Language.setText(out, usdt == null ? "" : (sellMinima ? Language.msg(l10n_atomix_market_receive, usdt, symbol) : Language.msg(l10n_atomix_market_pay, usdt, symbol)));
        }));

        // RULE 1: the title's shortened key is a glance label — put the FULL counterparty key in the body,
        // copyable, so the user can verify who they're about to lock funds with.
        if (maker.signerPk != null && !maker.signerPk.isEmpty()) {
            TextView cpv = new TextView(this);
            Language.setText(cpv, Language.msg(l10n_atomix_market_counterparty, maker.signerPk));
            cpv.setTextColor(Design.DIM()); cpv.setTextSize(11f); cpv.setTypeface(Design.mono()); cpv.setPadding(0, dp(8), 0, 0);
            cpv.setOnLongClickListener(v -> { copy(maker.signerPk, Language.msg(l10n_atomix_market_counterparty_copied)); return true; });
            box.addView(cpv);
        }

        modalOpen = true;
        final AlertDialog editor = dialog()
                .setTitle(Language.join(sellMinima ? Language.msg(l10n_atomix_otc_sell, ccy()) : Language.msg(l10n_atomix_otc_buy, ccy()), " · ", Util.shorten(maker.signerPk)))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_atomix_market_start), null)
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = amt.getText().toString();
            if (!positiveSwapAmount(raw)) { Language.setText(out, Language.msg(l10n_atomix_market_amount_invalid, ccy())); return; }
            String minima = MinimaHtlc.grain(raw);
            String usdt = computeUsdt(minima, price);
            if (usdt == null) { Language.setText(out, Language.msg(l10n_atomix_market_amount_invalid, ccy())); return; }
            // Client-side guards are UX-only — the maker's engine guard is authoritative — but catch the
            // obvious auto-declines before locking any coins on-chain.
            double m = parseD(minima, 0);
            if (m > maxAmount + 1e-9) { toast(Language.msg(l10n_atomix_market_too_large, abbrev(maxAmount), ccy())); return; }
            if (min > 0 && m < min) { toast(Language.msg(l10n_atomix_market_too_small, trim(min), ccy())); return; }
            startSwap(maker, symbol, sellMinima, minima, usdt);
            editor.dismiss();
        });
    }

    /** Match the existing six-decimal trade grain before a quote or review is offered. */
    private static boolean positiveSwapAmount(String raw) {
        double value = MarketDraft.number(raw, false, Double.NaN);
        return Double.isFinite(value) && value > 0 && new BigDecimal(MinimaHtlc.grain(raw)).signum() > 0;
    }

    /** USDT side for a mxUSDT amount at a given price (USDT per mxUSDT). */
    private String computeUsdt(String minimaAmount, double price) {
        if (!positiveSwapAmount(minimaAmount) || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal m = new BigDecimal(MinimaHtlc.grain(minimaAmount));
            if (m.signum() <= 0 || price <= 0) return null;
            BigDecimal usdt = m.multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.DOWN);
            if (usdt.signum() <= 0 || !Double.isFinite(usdt.doubleValue())) return null;
            return Util.tidyAmount(usdt.stripTrailingZeros().toPlainString());
        } catch (Exception e) { return null; }
    }

    private void startSwap(Order maker, String symbol, boolean sellMinima, String minima, String usdt) {
        orderStatus = "Starting swap…"; render();
        startLeg(maker, symbol, sellMinima, minima, usdt, new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                orderStatus = "✓ Swap started — leg 1 locked. Watching for the counterparty.";
                render(); engine.poll();
            }
            @Override public void err(String msg) { orderStatus = "Swap failed: " + msg; render(); }
        });
    }

    /**
     * Start ONE atomic swap leg (both directions), invoking {@code legCb} once the first-leg lock is placed
     * ({@code ok(hash)}) or on failure ({@code err}). Shared by the single-swap path and each sweep leg. For
     * BUYs it also seals our hashlock to the maker after the USDT locks (so they discover it via getContract).
     * The handshake's status messages are suppressed during a sweep so they don't clobber the sweep progress.
     */
    private void startLeg(Order maker, String symbol, boolean sellMinima, String minima, String usdt, SwapEngine.StartCb legCb) {
        // TAKE-TIME GUARD (fund-safety): don't lock a leg against a maker that has WITHDRAWN from the book. The
        // displayed book can lag up to one poll, so re-check the CURRENT book right before the lock — a maker that
        // switched currency / pulled its market publishes a no-liquidity tombstone (all pairs disabled), so
        // effectiveBids/Asks return empty and makerLive() is false; locking against it would strand the leg until
        // the ~2h HTLC refund. (A maker that stays live but reprices or drains is caught by its own responder
        // decline + refund, not here.) Chokepoint for BOTH the single-swap path and every sweep leg.
        if (!SwapOrderBook.makerLive(orderBook.get(maker.signerPk), symbol, sellMinima)) {
            scanOrderBook();   // pull the fresh book (async) so the UI drops the withdrawn maker
            legCb.err("that market was just withdrawn — refreshing the book, try again in a moment");
            return;
        }
        if (sellMinima) {
            // give mxUSDT (minima), receive USDT (usdt) → mxUSDT→ERC20
            engine.startMinimaToErc20(maker, minima, symbol, usdt, legCb);
        } else {
            // give USDT (usdt), receive mxUSDT (minima) → ERC20→mxUSDT, then hand the maker our sealed hashlock.
            engine.startErc20ToMinima(maker, symbol, usdt, minima, new SwapEngine.StartCb() {
                @Override public void ok(String hash) {
                    legCb.ok(hash);
                    if (crypto == null || maker.commsPublicId == null || maker.commsPublicId.isEmpty()) {
                        if (sweepRun == null) { orderStatus = "✓ Buy started, but the maker's contact key is missing — they may not see it."; render(); }
                        return;
                    }
                    SwapTake.send(node, crypto, maker.commsPublicId, identity.publicId(), hash, maker.market(), new CommsTransport.SendCb() {
                        @Override public void onSent(String txpowid) { if (sweepRun == null) { orderStatus = "✓ Buy started — maker notified, watching."; render(); } }
                        @Override public void onFailed(String message) { if (sweepRun == null) { orderStatus = "Buy locked, but maker notify failed: " + message; render(); } }
                    });
                }
                @Override public void err(String msg) { legCb.err(msg); }
            });
        }
    }

    // ---- sweep executor: BUY legs fire back-to-back on broadcast (EthTx nonce serializer keeps them ordered +
    //      collision-free); SELL legs stay gated on each mxUSDT lock confirming (no nonce for coin selection) ----

    private void startSweep(SweepPlan plan) {
        if (plan == null || plan.legs.isEmpty()) return;
        // A SELL sweep and an in-flight coin-split both pick mxUSDT coins unpinned — if they'd collide, the sweep
        // could lose leg 1 and abort. Splits finish within ~a block; ask the user to retry rather than risk it.
        if (plan.sell && (SwapEngine.isConsolidating() || (engine != null && engine.isSplitting()))) {
            toast(Language.msg(l10n_atomix_operation_preparing));
            return;
        }
        sweepRun = plan; sweepIdx = 0; sweepOk = 0;
        // Only a SELL sweep must stand the pollers down — its unpinned mxUSDT locks could double-select coins.
        // A BUY sweep is nonce-serialized (EthTx), so the poll loop may keep claiming counter-legs alongside it.
        SWEEP_ACTIVE = plan.sell;
        fireSweepLeg();
    }

    /** Tear a sweep down (done, failed, or stalled): stop the watchdog, release the wire, resume normal polling. */
    private void endSweep() {
        sweepRun = null; SWEEP_ACTIVE = false;
        if (sweepWatchdog != null) { ui.removeCallbacks(sweepWatchdog); sweepWatchdog = null; }
        render(); if (engine != null) engine.poll();
    }

    private void fireSweepLeg() {
        if (sweepRun == null) return;
        final boolean sell = sweepRun.sell;
        final int total = sweepRun.legs.size();
        if (sweepIdx >= total) {
            orderStatus = "✓ Sweep done — " + sweepOk + " of " + total + " parts "
                    + (sell ? "locked on-chain" : "sent") + ". Watching for counterparties.";
            endSweep();
            return;
        }
        final SweepLeg leg = sweepRun.legs.get(sweepIdx);
        final int shown = sweepIdx + 1;
        orderStatus = "Sweeping — locking part " + shown + " of " + total + " (best price first)…"; render();

        // Watchdog: if THIS leg's start neither ok's nor err's (a node hang drops the callback), release the wire so
        // claim/refund polling resumes — a stuck sweepRun would otherwise starve every in-flight swap indefinitely.
        if (sweepWatchdog != null) ui.removeCallbacks(sweepWatchdog);
        final int idxAtStart = sweepIdx;
        sweepWatchdog = () -> {
            if (sweepRun != null && sweepIdx == idxAtStart) {
                orderStatus = "Sweep stalled at part " + shown + " (no response) — stopped. Locked parts continue; unmatched auto-refund.";
                endSweep();
            }
        };
        ui.postDelayed(sweepWatchdog, SWEEP_LEG_TIMEOUT_MS);

        startLeg(leg.maker, "USDT", sell, leg.minima, leg.usdt, new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                if (sweepRun == null || sweepIdx != idxAtStart) return;   // already torn down — ignore late callback
                if (!sell) {
                    // BUY: the serializer already handed this lock a unique, in-order nonce, so fire the next leg
                    // immediately on broadcast — no wait to mine. Best price still mines first (it got nonce N).
                    sweepOk++; sweepIdx++;
                    orderStatus = "✓ Part " + shown + "/" + total + " lock sent"
                            + (sweepIdx < total ? " — sending part " + (sweepIdx + 1) + "…" : ". Watching for counterparties.");
                    render();
                    fireSweepLeg();
                    return;
                }
                // SELL: mxUSDT coin selection has no nonce — the next lock could re-select this leg's coins, so
                // wait until this lock's inputs are spent on-chain (confirmMyLock, coinage:1) before advancing.
                // Re-arm the watchdog for the CONFIRM phase — a hung confirmMyLock must still release SWEEP_ACTIVE.
                if (sweepWatchdog != null) ui.removeCallbacks(sweepWatchdog);
                sweepWatchdog = () -> {
                    if (sweepRun != null && sweepIdx == idxAtStart) {
                        orderStatus = "Sweep stalled confirming part " + shown + " — stopped. Locked parts continue; unmatched auto-refund.";
                        endSweep();
                    }
                };
                ui.postDelayed(sweepWatchdog, SWEEP_CONFIRM_WATCHDOG_MS);
                orderStatus = "✓ Part " + shown + "/" + total + " lock sent — confirming on-chain before the next part…";
                render();
                awaitLegConfirm(hash, sell, idxAtStart, shown, total, 0);   // don't advance until it's actually locked in
            }
            @Override public void err(String msg) {
                if (sweepRun == null || sweepIdx != idxAtStart) return;
                orderStatus = "Sweep stopped — " + sweepOk + " of " + total + " parts done (part " + shown
                        + " failed: " + msg + "). Locked parts continue; unmatched auto-refund.";
                endSweep();
            }
        });
    }

    /** Poll until leg {@code idxAtStart}'s lock is confirmed ON-CHAIN, THEN fire the next leg. If it never
     *  confirms (e.g. the lock tx was dropped), STOP — never execute a worse-priced leg behind an unconfirmed best. */
    private void awaitLegConfirm(String hash, boolean sell, int idxAtStart, int shown, int total, int attempt) {
        if (sweepRun == null || sweepIdx != idxAtStart) return;
        engine.confirmMyLock(hash, sell, onChain -> {
            if (sweepRun == null || sweepIdx != idxAtStart) return;
            if (onChain) {
                sweepOk++; sweepIdx++;
                orderStatus = "✓ Part " + shown + "/" + total + " locked in on-chain"
                        + (sweepIdx < total ? " — starting part " + (sweepIdx + 1) + "…" : ".");
                render();
                fireSweepLeg();   // NEXT leg only after THIS one is confirmed — best price locked in first
            } else if (attempt < SWEEP_CONFIRM_TRIES) {
                ui.postDelayed(() -> awaitLegConfirm(hash, sell, idxAtStart, shown, total, attempt + 1), SWEEP_CONFIRM_INTERVAL_MS);
            } else {
                orderStatus = "Sweep stopped — part " + shown + "'s lock didn't confirm on-chain in time; no further parts started. "
                        + "That leg auto-refunds at its timelock; your other funds are untouched.";
                endSweep();
            }
        });
    }

    // ---- UI ----

    // ---- tab host ----

    private LinearLayout buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Design.SURFACE());
        bar.setPadding(dp(6), dp(7), dp(6), dp(9));
        final int[] icons = {R.drawable.pm_atomix_ic_tab_swap, R.drawable.pm_atomix_ic_tab_wallet, R.drawable.pm_atomix_ic_tab_activity, R.drawable.pm_atomix_ic_tab_market, R.drawable.pm_atomix_ic_tab_otc};
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            boolean sel = idx == currentTab;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(6), 0, dp(5));
            cell.setBackground(Design.roundBg(this, sel ? Design.ACCENT_SOFT() : 0x00000000, 14));

            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(sel ? Design.TEXT() : Design.DIM());
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
            tabIcons[i] = ic;

            TextView p = new TextView(this);
            Language.setText(p, TAB_LABELS[i]);
            p.setGravity(Gravity.CENTER);
            p.setTextSize(10.5f);
            p.setTypeface(Design.sans());
            p.setPadding(0, dp(4), 0, 0);
            p.setTextColor(sel ? Design.TEXT() : Design.DIM());
            tabPills[i] = p;

            cell.addView(ic);
            cell.addView(p);
            cell.setOnClickListener(v -> setTab(idx));
            Design.pressable(cell);
            tabCells[i] = cell;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = i == 0 ? 0 : dp(4);
            bar.addView(cell, lp);
        }
        return bar;
    }

    private void updateTabBarSelection() {
        for (int i = 0; i < tabPills.length; i++) {
            boolean sel = i == currentTab;
            if (tabCells[i] != null) tabCells[i].setBackground(Design.roundBg(this, sel ? Design.ACCENT_SOFT() : 0x00000000, 14));
            if (tabIcons[i] != null) tabIcons[i].setColorFilter(sel ? Design.TEXT() : Design.DIM());
            if (tabPills[i] != null) tabPills[i].setTextColor(sel ? Design.TEXT() : Design.DIM());
        }
    }

    private void setTab(int t) {
        if (t == currentTab) return;
        otcInputFocused = false;
        swapInputFocused = false;   // leaving the Swap tab clears the typing guard
        currentTab = t;
        // Entering a book-rendering tab (Swap / Market) → refresh the book NOW. The periodic poll only scans while
        // one of these tabs is open, so this is what keeps the taker/depth view from showing a book that went stale
        // while the user was on Wallet/Activity/OTC.
        if (t == TAB_SWAP || t == TAB_MARKET) scanOrderBook();
        animateTab = true;   // cross-fade the new tab in (navigation only, not background refreshes)
        updateTabBarSelection();
        render();
    }

    // ---- render dispatcher ----

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        // Never rebuild the view tree while a dialog with text inputs is open — a background render
        // (watcher / balance callback) restarts the IME and resets the cursor mid-typing. We re-render
        // once when the dialog dismisses. Inline swap/OTC drafts have their own focus guards below.
        if (modalOpen) return;
        // While the user is typing the swap amount, don't rebuild the tree under them (would reset the IME/cursor).
        if (swapInputFocused && currentTab == TAB_SWAP) return;
        if (otcInputFocused && currentTab == TAB_OTC && !IdentityWatch.halted()) return;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(24));
        buildHeader(col);
        if (IdentityWatch.halted()) {          // keys don't belong to this node — nothing else may be reached
            renderMismatchBlocker(col);
            scroller.removeAllViews();
            scroller.addView(col);
            return;
        }
        switch (currentTab) {
            case TAB_WALLET:   renderWalletTab(col); break;
            case TAB_ACTIVITY: renderActivityTab(col); break;
            case TAB_MARKET:   renderMarketTab(col); break;
            case TAB_OTC:      renderOtcTab(col); break;
            case TAB_SWAP:
            default:           renderSwapTab(col); break;
        }
        scroller.removeAllViews();
        scroller.addView(col);
        if (animateTab) { col.setAlpha(0f); col.animate().alpha(1f).setDuration(120).start(); animateTab = false; }
        if (currentTab == TAB_WALLET) firePendingPulses();
    }

    /** The halt screen. Reached when this app's Minima key or ETH wallet provably does not belong to the node
     *  (see IdentityWatch). It replaces the whole UI on purpose: every tab below it either commits money or
     *  reports balances for a wallet the node can no longer derive, so none of it can be trusted.
     *
     *  It must also make the RESCUE possible before the user wipes anything: a reinstall clears the persisted
     *  identity AND the secrets table AND (because it is never persisted) the stale ETH key itself. So the two
     *  rescue actions come first and the uninstall last, with a count of what a wipe would destroy. */
    private void renderMismatchBlocker(LinearLayout col) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, 18));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);

        TextView title = new TextView(this);
        Language.setText(title, Language.msg(l10n_atomix_ui_mismatch_title));
        title.setTextColor(Design.RED()); title.setTextSize(18f); title.setTypeface(Design.sansBold());
        card.addView(title);

        TextView why = new TextView(this);
        Language.setText(why, Language.msg(l10n_atomix_ui_mismatch_body));
        why.setTextColor(Design.DIM()); why.setTextSize(13f);
        why.setPadding(0, dp(8), 0, dp(12));
        card.addView(why);

        // RULE 1: show the FULL keys here — this is the fund-recovery moment where the user needs to check a
        // block explorer / open a support ticket, so the identifiers must be complete and copyable, never
        // truncated. The block is selectable and long-press copies the whole thing.
        Language.TextBuilder detail = new Language.TextBuilder();
        if (IdentityWatch.minimaMismatch()) detail.append(Language.msg(l10n_atomix_ui_orphaned, IdentityWatch.orphanedPk())).append("\n\n");
        if (IdentityWatch.ethMismatch()) detail.append(Language.msg(l10n_atomix_ui_stale, IdentityWatch.staleEth(), IdentityWatch.nodeEth()));
        TextView det = new TextView(this);
        Language.setText(det, detail.build());
        det.setTextColor(Design.TEXT()); det.setTextSize(12f); det.setTypeface(android.graphics.Typeface.MONOSPACE);
        det.setTextIsSelectable(true);
        det.setOnLongClickListener(v -> { copy(det.getText().toString(), Language.msg(l10n_atomix_ui_recovery_copied)); return true; });
        card.addView(det);

        int unsettled = 0;
        try {
            for (SwapDb.Swap s : db.allSwaps())
                if (s != null && !SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status)
                        && !SwapDb.ST_ERROR.equals(s.status)) unsettled++;
        } catch (Exception ignore) {}
        TextView warn = new TextView(this);
        Language.setText(warn, Language.msg(l10n_atomix_ui_mismatch_warning, unsettled));
        warn.setTextColor(Design.RED()); warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, dp(14));
        card.addView(warn);

        if (IdentityWatch.ethMismatch()) {
            TextView exp = Design.pill(this, Language.msg(l10n_atomix_ui_export_stale), Design.SURFACE2(), Design.TEXT());
            exp.setOnClickListener(v -> exportKeyDialog());
            Design.pressable(exp);
            card.addView(exp, pillRow());

            TextView sweep = Design.pill(this, Language.msg(l10n_atomix_ui_sweep_stale), Design.SURFACE2(), Design.TEXT());
            sweep.setOnClickListener(v -> sendDialog());
            Design.pressable(sweep);
            card.addView(sweep, pillRow());
        }

        col.addView(card, clp);
    }

    private LinearLayout.LayoutParams pillRow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    // (shortKey removed — the identity-halt dialog now shows full, copyable keys per RULE 1.)

    private void buildHeader(LinearLayout col) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        boolean hosted = "com.eurobuddha.pandamonium".equals(getPackageName());
        if (hosted) {
            // render() rebuilds this whole tree every ~30s, so the HOST's one-shot
            // menu insertion dies with the first rebuild (proven on the Fold: the
            // hamburger vanished). The tag below tells the host to stand down and
            // this header draws its own hamburger on every rebuild instead.
            header.setTag("pandamonium.app.toolbar.self");
            TextView burger = new TextView(this);
            Language.setText(burger, "\u2630");
            burger.setTextColor(Design.TEXT());
            burger.setTextSize(20f);
            burger.setGravity(Gravity.CENTER);
            com.eurobuddha.minimacore.i18n.Language.description(burger, com.eurobuddha.minimacore.i18n.Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_drawer_open));
            Design.pressable(burger);
            burger.setOnClickListener(v -> {
                View shell = getWindow().getDecorView()
                        .findViewWithTag("pandamonium.navigation.shell");
                if (shell instanceof androidx.drawerlayout.widget.DrawerLayout) {
                    ((androidx.drawerlayout.widget.DrawerLayout) shell)
                            .openDrawer(Gravity.LEFT);
                }
            });
            header.addView(burger, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        TextView brand = new TextView(this);
        Language.setText(brand, "AtomiX");
        brand.setTextColor(Design.TEXT()); brand.setTextSize(21f); brand.setTypeface(Design.sansBold()); brand.setLetterSpacing(-0.01f);
        if (hosted) {
            // Hamburger left, title centred in the remaining span.
            brand.setGravity(Gravity.CENTER);
        }
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Currency selector — the active market's identity (accent-tinted). Tap to switch MINIMA ↔ mxUSDT: the
        // whole app re-themes to that currency and market-making moves to it (one currency at a time).
        //
        // actionPill, not pill: this sat 8dp from the Mainnet chip wearing the SAME fill, text colour and
        // radius as that chip, which is not tappable - so people read it as a badge and never tried it.
        // The ⇄ and the accent stroke are what distinguish it, and both are static; the glow is a bonus.
        LinearLayout marketHeader = new LinearLayout(this);
        marketHeader.setOrientation(LinearLayout.HORIZONTAL);
        marketHeader.setGravity(Gravity.CENTER_VERTICAL);
        TradingContext target = TradingContext.active().other();
        TextView ccyPill = Design.actionPill(this, "⇄", TradingContext.active().coinLabel,
                Design.ACCENT_SOFT(), Design.TEXT());
        ccyPill.setTextSize(12f);
        // The app's first accessibility label. A glyph-only affordance is no affordance to a screen
        // reader, and this control decides which market your money is in.
        Language.description(ccyPill, Language.msg(l10n_atomix_ui_trading_description, TradingContext.active().coinLabel, target.coinLabel));
        ccyPill.setMinimumHeight(dp(48));
        ccyPill.setOnClickListener(v -> switchCurrencyDialog());
        // Long-press cycles the glow strength while we tune it — long-press is free on this view and the
        // idiom already exists on the wallet card. Toasts the level so a screenshot records which is which.
        ccyPill.setOnLongClickListener(v -> {
            Design.Glow g = Design.cycleGlow(this);
            toast(Language.msg(l10n_atomix_ui_glow, glowLabel(g)));
            startCcyGlow(ccyPill);
            return true;
        });
        Design.pressable(ccyPill);
        startCcyGlow(ccyPill);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(8);
        marketHeader.addView(ccyPill, clp);

        TextView theme = Design.pill(this, Design.isDark() ? "☾" : "☀", Design.SURFACE2(), Design.DIM());
        theme.setTextSize(13f);
        Language.description(theme, Design.isDark() ? Language.msg(l10n_atomix_ui_light) : Language.msg(l10n_atomix_ui_dark));
        theme.setMinimumHeight(dp(48)); theme.setMinimumWidth(dp(48));
        theme.setOnClickListener(v -> toggleTheme());
        Design.pressable(theme);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = dp(8);
        header.addView(theme, tlp);

        col.addView(header);
        marketHeader.addView(mainnetChip());
        col.addView(marketHeader);

        TextView sub = new TextView(this);
        Language.setText(sub, Language.join("v", BuildConfig.VERSION_NAME, chainBlock > 0 ? Language.join("  ·  ", Language.msg(l10n_atomix_ui_block, chainBlock)) : "", "  ·  ", Language.msg(l10n_atomix_ui_real_funds)));
        sub.setTextColor(Design.DIM2()); sub.setTextSize(12f); sub.setTypeface(Design.sans()); sub.setPadding(0, dp(3), 0, dp(12));
        col.addView(sub);
    }

    /** Small "live" chip: a filled dot + Mainnet, tinted with the soft accent. */
    private View mainnetChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Design.roundBg(this, Design.ACCENT_SOFT(), 14));
        chip.setPadding(dp(10), dp(6), dp(11), dp(6));
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL); dg.setColor(Design.ACCENT());
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6)); dlp.rightMargin = dp(6);
        chip.addView(dot, dlp);
        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_atomix_ui_mainnet)); t.setTextColor(Design.TEXT()); t.setTextSize(11f); t.setTypeface(Design.sansBold());
        chip.addView(t);
        return chip;
    }

    // ---- Swap tab (guided, beginner-first) ----

    private TextView segTab(CharSequence label, boolean active, Runnable onTap) {
        TextView t = new TextView(this);
        Language.setText(t, label); t.setGravity(Gravity.CENTER); t.setTextSize(14f);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(Design.roundBg(this, active ? Design.ACCENT() : Design.SURFACE2(), 12));
        t.setTextColor(active ? Design.ON_ACCENT() : Design.DIM());
        t.setTypeface(active ? Design.sansBold() : Design.sans());
        t.setOnClickListener(v -> onTap.run());
        Design.pressable(t);
        return t;
    }

    private void renderSwapTab(LinearLayout col) {
        TextView h = new TextView(this);
        Language.setText(h, Language.msg(l10n_atomix_swap_title, ccy()));
        h.setTextColor(Design.TEXT()); h.setTextSize(18f); h.setTypeface(Design.sansBold());
        h.setPadding(0, dp(4), 0, dp(2));
        col.addView(h);
        TextView sh = new TextView(this);
        Language.setText(sh, Language.msg(l10n_atomix_swap_intro));
        sh.setTextColor(Design.DIM()); sh.setTextSize(12.5f); sh.setTypeface(Design.sans()); sh.setPadding(0, 0, 0, dp(12));
        col.addView(sh);

        // direction toggle (segmented)
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        TextView sellTab = segTab(Language.msg(l10n_atomix_otc_sell, ccy()), swapSell, () -> { swapInputFocused = false; if (!swapSell) { swapSell = true; } render(); });
        TextView buyTab = segTab(Language.msg(l10n_atomix_otc_buy, ccy()), !swapSell, () -> { swapInputFocused = false; if (swapSell) { swapSell = false; } render(); });
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); sp1.rightMargin = dp(5);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); sp2.leftMargin = dp(5);
        seg.addView(sellTab, sp1); seg.addView(buyTab, sp2);
        col.addView(seg);

        if (!paired) {
            col.addView(dimNote(nodeOfflineText()));
            swapStages(col);
            return;
        }

        Best best = bestMakers("USDT");
        final Order maker = swapSell ? best.bidMaker : best.askMaker;
        final double price = swapSell ? best.bestBid : best.bestAsk;
        final boolean have = maker != null && price > 0;

        if (!have) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setBackground(Design.card(this, 18));
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(14); empty.setLayoutParams(elp);
            TextView none = new TextView(this);
            Language.setText(none, swapSell ? Language.msg(l10n_atomix_swap_no_buy) : Language.msg(l10n_atomix_swap_no_sell));
            none.setTextColor(Design.TEXT()); none.setTextSize(14.5f); none.setTypeface(Design.sansBold());
            empty.addView(none);
            TextView sub2 = new TextView(this);
            Language.setText(sub2, Language.msg(l10n_atomix_swap_no_quote_help));
            sub2.setTextColor(Design.DIM()); sub2.setTextSize(12.5f); sub2.setTypeface(Design.sans()); sub2.setLineSpacing(dp(3), 1f); sub2.setPadding(0, dp(6), 0, dp(2));
            empty.addView(sub2);
            TextView go = Design.pill(this, Language.msg(l10n_atomix_swap_market), Design.SURFACE2(), Design.TEXT());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(12); go.setLayoutParams(glp); go.setOnClickListener(v -> setTab(TAB_MARKET)); Design.pressable(go);
            empty.addView(go);
            col.addView(empty);
            swapStages(col);
            return;
        }

        // ---- faithful dual-amount card: You send / ⇅ / You receive ----
        final boolean sellMinima = swapSell;   // send=mxUSDT,recv=USDT when selling; send=USDT,recv=mxUSDT when buying
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, 18));
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setClipChildren(false); card.setClipToPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14); card.setLayoutParams(clp);

        // YOU SEND (editable)
        card.addView(fieldLabel(Language.msg(l10n_atomix_swap_send)));
        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL); sendRow.setGravity(Gravity.CENTER_VERTICAL);
        sendRow.setPadding(0, dp(6), 0, dp(4));
        sendRow.addView(coinChip(sellMinima));
        final EditText amt = new EditText(this);
        decimalInput(amt); Language.description(amt, Language.msg(l10n_atomix_swap_send));
        Language.setText(amt, swapAmount);
        Language.hint(amt, "0.00"); amt.setHintTextColor(Design.DIM2());
        amt.setTextColor(Design.TEXT()); amt.setTextSize(26f); amt.setTypeface(Design.monoBold());
        amt.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amt.setBackground(null); amt.setPadding(dp(8), 0, 0, 0);
        amt.setOnFocusChangeListener((v, has) -> { swapInputFocused = has; if (!has) ui.postDelayed(this::render, 250); });
        if (swapAmount.length() > 0) amt.setSelection(swapAmount.length());
        sendRow.addView(amt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(sendRow);

        // ⇅ flip divider
        LinearLayout flipRow = new LinearLayout(this);
        flipRow.setOrientation(LinearLayout.HORIZONTAL); flipRow.setGravity(Gravity.CENTER_VERTICAL);
        flipRow.setPadding(0, dp(10), 0, dp(10));
        View l1 = new View(this); l1.setBackgroundColor(Design.BORDER());
        View l2 = new View(this); l2.setBackgroundColor(Design.BORDER());
        TextView flip = new TextView(this);
        Language.setText(flip, "⇅"); flip.setGravity(Gravity.CENTER); flip.setTextColor(Design.ACCENT()); flip.setTextSize(16f); flip.setTypeface(Design.sansBold());
        flip.setBackground(Design.stroked(this, Design.SURFACE2(), 19));
        flip.setOnClickListener(v -> { swapInputFocused = false; swapSell = !swapSell; render(); }); Design.pressable(flip);
        Language.description(flip, Language.msg(l10n_atomix_swap_flip));
        int fs = dp(48);
        LinearLayout.LayoutParams llL = new LinearLayout.LayoutParams(0, dp(1), 1f);
        LinearLayout.LayoutParams llF = new LinearLayout.LayoutParams(fs, fs); llF.leftMargin = dp(12); llF.rightMargin = dp(12);
        LinearLayout.LayoutParams llR = new LinearLayout.LayoutParams(0, dp(1), 1f);
        flipRow.addView(l1, llL); flipRow.addView(flip, llF); flipRow.addView(l2, llR);
        card.addView(flipRow);

        // YOU RECEIVE — editable too, so you can enter either side (mxUSDT or USDT)
        card.addView(fieldLabel(Language.msg(l10n_atomix_swap_receive)));
        LinearLayout recvRow = new LinearLayout(this);
        recvRow.setOrientation(LinearLayout.HORIZONTAL); recvRow.setGravity(Gravity.CENTER_VERTICAL);
        recvRow.setPadding(0, dp(6), 0, dp(2));
        recvRow.addView(coinChip(!sellMinima));
        final EditText recv = new EditText(this);
        decimalInput(recv); Language.description(recv, Language.msg(l10n_atomix_swap_receive));
        Language.hint(recv, "0.00"); recv.setHintTextColor(Design.DIM2());
        recv.setTextColor(Design.ACCENT()); recv.setTextSize(26f); recv.setTypeface(Design.monoBold());
        recv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        recv.setBackground(null); recv.setPadding(dp(8), 0, 0, 0);
        String r0 = sellMinima ? computeUsdt(swapAmount, price) : computeMinima(swapAmount, price);
        Language.setText(recv, r0 == null ? "" : r0);
        recv.setOnFocusChangeListener((v, has) -> { swapInputFocused = has; if (!has) ui.postDelayed(this::render, 250); });
        recvRow.addView(recv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(recvRow);

        // bidirectional live conversion — type EITHER field, the other computes. swapAmount always = the SEND side.
        amt.addTextChangedListener(new SimpleWatcher(() -> {
            if (swapSyncing) return;
            swapAmount = amt.getText().toString();
            String rr = sellMinima ? computeUsdt(swapAmount, price) : computeMinima(swapAmount, price);
            swapSyncing = true; Language.setText(recv, rr == null ? "" : rr); swapSyncing = false;
        }));
        recv.addTextChangedListener(new SimpleWatcher(() -> {
            if (swapSyncing) return;
            String rstr = recv.getText().toString();
            String ss = sellMinima ? computeMinima(rstr, price) : computeUsdt(rstr, price);   // inverse: receive → send
            swapSyncing = true; Language.setText(amt, ss == null ? "" : ss); swapSyncing = false;
            swapAmount = amt.getText().toString();
        }));

        // best price line — best-level cap, plus total sweepable depth across the book (larger orders sweep)
        double bestCap = sellMinima ? best.bidCap : best.askCap;
        double depth = sweepDepthMinima(sellMinima);
        TextView bp = new TextView(this);
        Language.setText(bp, Language.msg(l10n_atomix_swap_best, fmtPrice(price), ccy(), abbrev(bestCap), depth > bestCap + 1e-9 ? Language.msg(l10n_atomix_swap_depth, abbrev(depth)) : "", sellMinima ? "" : Language.msg(l10n_atomix_swap_gas_suffix)));
        bp.setTextColor(Design.DIM2()); bp.setTextSize(11.5f); bp.setTypeface(Design.sans()); bp.setPadding(0, dp(12), 0, 0);
        card.addView(bp);

        // BUY slippage tolerance — the most above best you'll pay per mxUSDT when filling across deeper levels.
        if (!sellMinima) {
            double curPct = buySlippage() * 100;
            boolean is2 = Math.abs(curPct - 2) < 0.001, is42 = Math.abs(curPct - 4.2) < 0.001;
            LinearLayout slip = new LinearLayout(this);
            slip.setOrientation(LinearLayout.VERTICAL);
            LinearLayout options = new LinearLayout(this); options.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(10); slip.setLayoutParams(slp);
            TextView lbl = new TextView(this); Language.setText(lbl, Language.msg(l10n_atomix_swap_slippage)); lbl.setTextColor(Design.DIM()); lbl.setTextSize(11.5f); lbl.setTypeface(Design.sans());
            slip.addView(lbl);
            options.addView(slipPill("2%", is2, () -> setBuySlippage(2)));
            options.addView(slipPill("4.2%", is42, () -> setBuySlippage(4.2)));
            options.addView(slipPill((!is2 && !is42) ? pctStr(curPct) + "%" : Language.msg(l10n_atomix_swap_custom), !is2 && !is42, this::customSlippageDialog));
            slip.addView(options);
            card.addView(slip);
        }

        TextView cta = button(Language.msg(l10n_atomix_swap_review));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16); cta.setLayoutParams(blp);
        cta.setOnClickListener(v -> onSwapCta(sellMinima, (sellMinima ? amt : recv).getText().toString().trim(), best));   // mxUSDT-side field
        card.addView(cta);

        col.addView(card);

        // live swap-leg stages below the price
        swapStages(col);

        if (orderStatus != null) {
            TextView stt = new TextView(this);
            Language.setText(stt, OperationMessages.message(orderStatus));
            stt.setTextColor(orderStatus.startsWith("✓") ? Design.IN() : Design.DIM());
            stt.setTextSize(12.5f); stt.setTypeface(Design.sans()); stt.setPadding(dp(2), dp(12), dp(2), 0);
            col.addView(stt);
        }
    }

    private TextView fieldLabel(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s); t.setTextColor(Design.DIM()); t.setTextSize(11f); t.setTypeface(Design.sans()); t.setLetterSpacing(0.06f);
        return t;
    }

    /** A token chip: coloured coin mark + ticker + available balance. */
    private LinearLayout coinChip(boolean minima) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL); chip.setGravity(Gravity.CENTER_VERTICAL);
        TextView circle = new TextView(this);
        Language.setText(circle, minima ? "M" : "$"); circle.setGravity(Gravity.CENTER);
        circle.setTextColor(minima ? Design.ON_ACCENT() : 0xFFFFFFFF);
        circle.setTypeface(Design.sansBold()); circle.setTextSize(15f);
        android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
        cg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        cg.setColor(minima ? Design.ACCENT() : USDT_TEAL);
        circle.setBackground(cg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(34), dp(34)); clp.rightMargin = dp(10);
        chip.addView(circle, clp);
        LinearLayout colc = new LinearLayout(this); colc.setOrientation(LinearLayout.VERTICAL);
        TextView tk = new TextView(this); Language.setText(tk, minima ? ccy() : "USDT"); tk.setTextColor(Design.TEXT()); tk.setTextSize(15f); tk.setTypeface(Design.sansBold());
        TextView sub = new TextView(this);
        String usdt = tokenBals.get("USDT");
        Language.setText(sub, Language.msg(l10n_atomix_swap_available, minima ? abbrev(parseD(minimaBal, 0)) : (usdt != null ? Util.tidyAmount(usdt) : "—")));
        sub.setTextColor(Design.DIM()); sub.setTextSize(11f); sub.setTypeface(Design.sans());
        colc.addView(tk); colc.addView(sub);
        chip.addView(colc);
        return chip;
    }

    /** mxUSDT you'd receive for a USDT amount at a given price (USDT per mxUSDT). */
    private String computeMinima(String usdtAmount, double price) {
        if (!positiveSwapAmount(usdtAmount) || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal u = new BigDecimal(MinimaHtlc.grain(usdtAmount));
            if (u.signum() <= 0 || price <= 0) return null;
            BigDecimal m = u.divide(BigDecimal.valueOf(price), 6, RoundingMode.DOWN);
            if (m.signum() <= 0 || !Double.isFinite(m.doubleValue())) return null;   // 6dp mxUSDT/USDT trade grain
            return Util.tidyAmount(m.stripTrailingZeros().toPlainString());
        } catch (Exception e) { return null; }
    }

    /** Confirm the inline swap, then start it via the existing engine path. */
    private void reviewSwap(Order maker, boolean sellMinima, String sendStr, double price, double maxAmount) {
        swapInputFocused = false;   // leaving the amount field — let the panel re-render live from here on
        if (sendStr.isEmpty()) { toast(sellMinima ? Language.msg(l10n_atomix_swap_enter_sell) : Language.msg(l10n_atomix_swap_enter_spend)); return; }
        if (!positiveSwapAmount(sendStr)) { toast(Language.msg(l10n_atomix_swap_valid)); return; }
        final String minima, usdt;
        if (sellMinima) { minima = MinimaHtlc.grain(sendStr); usdt = computeUsdt(minima, price); }   // 6dp trade grain, both legs consistent
        else { usdt = MinimaHtlc.grain(sendStr); minima = computeMinima(usdt, price); }
        if (minima == null || usdt == null) { toast(Language.msg(l10n_atomix_swap_valid)); return; }
        // UX-only cap check against the best level (the maker's guard is authoritative) — stop over-sizing before a lock.
        if (maxAmount > 0 && parseD(minima, 0) > maxAmount + 1e-9) {
            toast(Language.msg(l10n_atomix_swap_best_cap, abbrev(maxAmount), ccy())); return;
        }
        // NI-1 (RULE 1): show the FULL counterparty key on its own line at the moment funds are committed —
        // never a truncated form the user can't verify against the maker.
        CharSequence msg = sellMinima ? Language.msg(l10n_atomix_swap_sell_body, minima, ccy(), usdt, fmtPrice(price), ccy(), maker.signerPk, ccy())
                : Language.msg(l10n_atomix_swap_buy_body, minima, ccy(), usdt, fmtPrice(price), ccy(), maker.signerPk);
        modalOpen = true;
        dialog()
                .setTitle(sellMinima ? Language.msg(l10n_atomix_swap_review_sell, ccy()) : Language.msg(l10n_atomix_swap_review_buy, ccy()))
                .setMessage(msg)
                .setPositiveButton(Language.msg(l10n_atomix_swap_start), (d, w) -> { swapAmount = ""; startSwap(maker, "USDT", sellMinima, minima, usdt); })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    /** Route the Swap-tab CTA: size within the best level → today's single swap; larger → market sweep. */
    /** {@code minimaStr} is the mxUSDT amount to sell / to buy (the mxUSDT-side field). */
    private void onSwapCta(boolean sell, String minimaStr, Best best) {
        swapInputFocused = false;   // leaving the amount field — panel re-renders live from here (0.8.1 lesson)
        if (minimaStr.isEmpty()) { toast(sell ? Language.msg(l10n_atomix_swap_enter_coin_sell, ccy()) : Language.msg(l10n_atomix_swap_enter_coin_buy, ccy())); return; }
        if (!positiveSwapAmount(minimaStr)) { toast(Language.msg(l10n_atomix_swap_valid_coin, ccy())); return; }
        scanOrderBook();   // kick a fresh book scan now (async) so it's current by the time the user confirms the
                           // review dialog — the startLeg liveness guard then re-checks against that fresh book.
        final Order bestMaker = sell ? best.bidMaker : best.askMaker;
        final double bestPrice = sell ? best.bestBid : best.bestAsk;
        final double bestCap = sell ? best.bidCap : best.askCap;   // mxUSDT
        if (bestMaker == null || !Double.isFinite(bestPrice) || bestPrice <= 0) { toast(Language.msg(l10n_atomix_swap_no_quote)); return; }
        final double EPS = 1e-9;
        final double want = parseD(minimaStr, 0);
        if (want <= 0) { toast(Language.msg(l10n_atomix_swap_valid_coin, ccy())); return; }

        if (sell) {
            if (want > parseD(minimaBal, 0) + EPS) {
                toast(Language.msg(l10n_atomix_swap_only_have, abbrev(parseD(minimaBal, 0)), ccy())); return;
            }
            if (want <= bestCap + EPS) { reviewSwap(bestMaker, true, minimaStr, bestPrice, bestCap); return; }   // single (send = mxUSDT)
            SweepPlan plan = buildSweepPlan(true, minimaStr, 0);
            if (plan.legs.isEmpty()) { toast(sweepEmptyMsg(plan)); return; }
            reviewSweep(plan);
            return;
        }

        // BUY: target-driven with slippage — deliver `want` mxUSDT across asks priced ≤ best × (1+slippage).
        SweepPlan plan = buildSweepPlan(false, minimaStr, buySlippage());
        if (plan.legs.isEmpty()) { toast(sweepEmptyMsg(plan)); return; }
        double haveUsdt = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);
        if (plan.totalUsdt > haveUsdt + EPS) {
            toast(Language.msg(l10n_atomix_swap_need, trimSig(plan.totalUsdt), abbrev(plan.filledMinima), ccy(), pctStr(buySlippage() * 100), trimSig(haveUsdt))); return;
        }
        reviewSweep(plan);
    }

    private CharSequence sweepEmptyMsg(SweepPlan p) {
        if ("below-min".equals(p.stopReason)) return Language.msg(l10n_atomix_swap_below_min);
        return Language.msg(l10n_atomix_swap_no_liquidity);
    }

    /** Review a multi-leg market sweep: blended avg/worst price, per-leg breakdown, totals, partial-fill note. */
    private void reviewSweep(SweepPlan plan) {
        if (plan == null || plan.legs.isEmpty()) { toast(Language.msg(l10n_atomix_swap_nothing)); return; }
        final boolean sell = plan.sell;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView head = new TextView(this);
        Language.setText(head, sell ? Language.msg(l10n_atomix_swap_sweep_sell_head, abbrev(plan.filledMinima), ccy(), plan.legs.size()) : Language.msg(l10n_atomix_swap_sweep_buy_head, abbrev(plan.filledMinima), ccy(), trimSig(plan.totalUsdt), plan.legs.size()));
        head.setTextColor(Design.TEXT()); head.setTextSize(15f); head.setTypeface(Design.sansBold()); head.setPadding(0, 0, 0, dp(6));
        box.addView(head);

        TextView metrics = new TextView(this);
        Language.setText(metrics, Language.msg(l10n_atomix_swap_metrics, fmtPrice(plan.avgPrice), fmtPrice(plan.worstPrice), ccy(), !sell && plan.slippagePct > 0 ? Language.msg(l10n_atomix_swap_within, pctStr(plan.slippagePct)) : ""));
        metrics.setTextColor(Design.DIM()); metrics.setTextSize(12.5f); metrics.setTypeface(Design.sans()); metrics.setPadding(0, 0, 0, dp(8));
        box.addView(metrics);

        for (int i = 0; i < plan.legs.size(); i++) {
            SweepLeg leg = plan.legs.get(i);
            TextView r = new TextView(this);
            Language.setText(r, Language.msg(l10n_atomix_swap_part, i + 1, leg.minima, ccy(), fmtPrice(leg.price), leg.usdt, shortAddr(leg.maker.signerPk)));
            r.setTextColor(Design.TEXT()); r.setTextSize(12f); r.setTypeface(Design.mono()); r.setPadding(0, dp(2), 0, dp(2));
            final String legPk = leg.maker.signerPk;   // RULE 1: long-press copies the full leg maker key
            if (legPk != null && !legPk.isEmpty()) r.setOnClickListener(v -> copy(legPk, Language.msg(l10n_atomix_market_counterparty_copied)));
            box.addView(r);
        }

        TextView totals = new TextView(this);
        Language.setText(totals, sell ? Language.msg(l10n_atomix_swap_total_sell, trimSig(plan.filledMinima), ccy(), trimSig(plan.totalUsdt)) : Language.msg(l10n_atomix_swap_total_buy, trimSig(plan.totalUsdt), trimSig(plan.filledMinima), ccy()));
        totals.setTextColor(Design.ACCENT()); totals.setTextSize(12.5f); totals.setTypeface(Design.sansBold()); totals.setPadding(0, dp(8), 0, dp(2));
        box.addView(totals);

        if (plan.partial) {
            TextView pw = new TextView(this);
            CharSequence msg = Language.msg(l10n_atomix_swap_partial, abbrev(plan.filledMinima), abbrev(plan.target), ccy(), !sell && "slippage".equals(plan.stopReason) ? Language.msg(l10n_atomix_swap_partial_slip, pctStr(plan.slippagePct)) : Language.msg(l10n_atomix_swap_partial_book));
            Language.setText(pw, msg);
            pw.setTextColor(Design.RED()); pw.setTextSize(12f); pw.setTypeface(Design.sans()); pw.setLineSpacing(dp(2), 1f); pw.setPadding(0, dp(8), 0, dp(2));
            box.addView(pw);
        }

        if (!sell) {
            TextView gas = new TextView(this);
            Language.setText(gas, Language.msg(l10n_atomix_swap_sweep_gas, plan.legs.size()));
            gas.setTextColor(Design.DIM2()); gas.setTextSize(11.5f); gas.setTypeface(Design.sans()); gas.setPadding(0, dp(8), 0, dp(2));
            box.addView(gas);
        }

        TextView note = new TextView(this);
        Language.setText(note, Language.msg(l10n_atomix_swap_sweep_note));
        note.setTextColor(Design.DIM2()); note.setTextSize(11.5f); note.setTypeface(Design.sans()); note.setLineSpacing(dp(2), 1f); note.setPadding(0, dp(8), 0, dp(2));
        box.addView(note);

        modalOpen = true;
        dialog()
                .setTitle(sell ? Language.msg(l10n_atomix_swap_sweep_sell_title) : Language.msg(l10n_atomix_swap_sweep_buy_title))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_atomix_swap_start_sweep), (d, w) -> { swapAmount = ""; startSweep(plan); })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    // ---- swap-leg stage pills (readiness → lock → counterparty → claim → complete) ----

    private void swapStages(LinearLayout col) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18); box.setLayoutParams(lp);

        TextView t = new TextView(this);
        Language.setText(t, Language.msg(l10n_atomix_swap_your)); t.setTextColor(Design.DIM()); t.setTextSize(11f); t.setLetterSpacing(0.06f); t.setTypeface(Design.sans()); t.setPadding(dp(2), 0, 0, dp(8));
        box.addView(t);

        // pre-flight readiness
        box.addView(stagePill(Language.msg(l10n_atomix_swap_node), paired ? STG_DONE : STG_WARN));
        if (swapSell) {
            box.addView(stagePill(Language.msg(l10n_atomix_swap_ready_sell, ccy()), parseD(minimaBal, 0) > 0 ? STG_DONE : STG_WARN));
        } else {
            String usdt = tokenBals.get("USDT");
            box.addView(stagePill(Language.msg(l10n_atomix_swap_ready_usdt), (usdt != null && parseD(usdt, 0) > 0) ? STG_DONE : STG_WARN));
            box.addView(stagePill(Language.msg(l10n_atomix_swap_gas), parseD(ethBal.replace(" ETH", ""), 0) > 0 ? STG_DONE : STG_WARN));
        }

        // live swap legs (if a swap is in flight or just finished)
        SwapDb.Swap sw = latestRelevantSwap();
        if (sw != null) {
            // which swap this panel is tracking (disambiguates concurrent swaps)
            TextView which = new TextView(this);
            Language.setText(which, Language.join(sw.sellAmount, " ", sw.sellToken, "  →  ", sw.buyAmount, " ", sw.buyToken, "   ·   ", historyRole(sw.role)));
            which.setTextColor(Design.TEXT()); which.setTextSize(13f); which.setTypeface(Design.mono()); which.setPadding(dp(2), dp(2), 0, dp(6));
            box.addView(which);
            if (SwapDb.ST_REFUNDED.equals(sw.status)) {
                box.addView(stagePill(Language.msg(l10n_atomix_swap_stage_refund, sw.sellAmount, sw.sellToken), STG_WARN));
            } else {
                int active = stageIndex(sw.status);   // index of the first not-yet-done leg
                box.addView(stagePill(Language.msg(l10n_atomix_swap_stage_lock, sw.sellAmount, sw.sellToken), legState(1, active)));
                box.addView(stagePill(Language.msg(l10n_atomix_swap_stage_counterparty), legState(2, active)));
                box.addView(stagePill(Language.msg(l10n_atomix_swap_stage_claim, sw.buyAmount, sw.buyToken), legState(3, active)));
                box.addView(stagePill(Language.msg(l10n_atomix_swap_stage_complete), legState(4, active)));
            }
            TextView detail = new TextView(this);
            Language.setText(detail, statusDetail(sw));
            detail.setTextColor(Design.DIM()); detail.setTextSize(12f); detail.setTypeface(Design.sans()); detail.setLineSpacing(dp(2), 1f); detail.setPadding(dp(2), dp(8), 0, 0);
            box.addView(detail);
            if (!isTerminal(sw)) {
                TextView chk = Design.pill(this, Language.msg(l10n_atomix_history_check), Design.SURFACE2(), Design.TEXT());
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(8); chk.setLayoutParams(clp);
                chk.setOnClickListener(v -> checkNow(sw.hash)); Design.pressable(chk);
                box.addView(chk);
            }
            int more = activeSwapCount() - (isTerminal(sw) ? 0 : 1);
            if (more > 0) {
                TextView moreTv = new TextView(this);
                Language.setText(moreTv, Language.msg(l10n_atomix_swap_more, more));
                moreTv.setTextColor(Design.DIM2()); moreTv.setTextSize(11.5f); moreTv.setTypeface(Design.sans()); moreTv.setPadding(dp(2), dp(8), 0, 0);
                moreTv.setOnClickListener(v -> openHistory(0));
                Design.pressable(moreTv);
                box.addView(moreTv);
            }
        } else {
            box.addView(stagePill(Language.msg(l10n_atomix_swap_begin), STG_PENDING));
        }
        col.addView(box);
    }

    /** A stage row: coloured status dot + label. state ∈ {pending, active, done, warn}. */
    private LinearLayout stagePill(CharSequence label, int state) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        switch (state) {
            case STG_DONE:   dg.setColor(Design.IN()); break;
            case STG_ACTIVE: dg.setColor(Design.ACCENT()); break;
            case STG_WARN:   dg.setColor(STAGE_WARN_COLOR); break;
            default:         dg.setColor(0x00000000); dg.setStroke(Math.max(1, dp(1)), Design.DIM2()); break;
        }
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9)); dlp.leftMargin = dp(2); dlp.rightMargin = dp(12);
        row.addView(dot, dlp);
        TextView t = new TextView(this);
        Language.setText(t, label); t.setTextSize(13f);
        switch (state) {
            case STG_ACTIVE: t.setTextColor(Design.TEXT()); t.setTypeface(Design.sansBold()); break;
            case STG_DONE:   t.setTextColor(Design.TEXT()); t.setTypeface(Design.sans()); break;
            case STG_WARN:   t.setTextColor(STAGE_WARN_COLOR); t.setTypeface(Design.sans()); break;
            default:         t.setTextColor(Design.DIM()); t.setTypeface(Design.sans()); break;
        }
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int legState(int i, int active) {
        if (i < active) return STG_DONE;
        if (i == active) return STG_ACTIVE;
        return STG_PENDING;
    }

    /** Index of the first not-yet-completed leg for a status (1=lock,2=counterparty,3=claim,5=all done). */
    private int stageIndex(String status) {
        switch (status) {
            case SwapDb.ST_STARTED:  return 2;   // your leg locked; waiting on counterparty
            case SwapDb.ST_LOCKED:   return 3;   // counterparty locked; claiming next
            case SwapDb.ST_CLAIMING: return 3;   // claiming your funds
            case SwapDb.ST_COMPLETE: return 5;   // all legs done
            default:                 return 2;
        }
    }

    /** The swap to surface on the Swap tab: most-recently-updated non-terminal swap, or one that finished
     *  within the grace window (so you see it land). */
    /** The swap the front-page "YOUR SWAP" panel tracks: an IN-FLIGHT swap first (so a sweep shows its still-active
     *  leg, not a finished one); else a JUST-finished one, briefly, so it doesn't linger once funds have confirmed. */
    private SwapDb.Swap latestRelevantSwap() {
        if (db == null) return null;
        SwapDb.Swap active = null, recentDone = null;
        long now = System.currentTimeMillis();
        java.util.Set<String> dismissed = dismissedSwaps();
        for (SwapDb.Swap s : db.allSwaps()) {
            if (dismissed.contains(s.hash)) continue;   // user hid it — keep it out of the front-page panel
            if (!isTerminal(s)) {
                if (active == null || s.created > active.created) active = s;
            } else if (now - s.updated <= SWAP_PANEL_GRACE_MS) {
                if (recentDone == null || s.updated > recentDone.updated) recentDone = s;
            }
        }
        return active != null ? active : recentDone;   // in-flight first; else a just-finished one (~30s); else null → fresh prompt
    }

    /** Count of swaps still in flight (non-terminal) — used to hint at concurrent swaps. */
    private int activeSwapCount() {
        if (db == null) return 0;
        int n = 0;
        java.util.Set<String> dismissed = dismissedSwaps();
        for (SwapDb.Swap s : db.allSwaps()) if (!isTerminal(s) && !dismissed.contains(s.hash)) n++;
        return n;
    }

    private static final class Best { Order bidMaker, askMaker; Order.Level bidLevel, askLevel; double bestBid = 0, bestAsk = Double.MAX_VALUE; double bidCap = 0, askCap = 0; }

    /** Best non-own bid/ask LEVEL across the live order book (guided Swap tab; never picks my own order). Walks each
     *  maker's ladder (legacy makers contribute one synthetic level) and keeps the single best price per side; among
     *  makers at the SAME price it keeps the DEEPEST (largest balance-clamped cap) so a fillable amount takes the
     *  fewest swap legs. Price is never sacrificed for size — a strictly better price always wins. */
    private Best bestMakers(String sym) {
        Best r = new Best();
        for (Order o : orderBook.values()) {
            if (isMine(o)) continue;
            // Single source of the ranking: compareForFill(candidate, current) < 0 ⟺ candidate has a strictly
            // better price, OR an equal price with a larger cap. Keeps bestMakers and the sweep sort in lockstep.
            for (Order.Level l : o.effectiveBids(sym)) {
                double cap = SwapOrderBook.levelCap(o, l, true);
                if (cap <= 0) continue;
                if (r.bidMaker == null || SwapOrderBook.compareForFill(o, l, r.bidMaker, r.bidLevel, true) < 0) {
                    r.bestBid = l.price; r.bidMaker = o; r.bidLevel = l; r.bidCap = cap;
                }
            }
            for (Order.Level l : o.effectiveAsks(sym)) {
                double cap = SwapOrderBook.levelCap(o, l, false);
                if (cap <= 0) continue;
                if (r.askMaker == null || SwapOrderBook.compareForFill(o, l, r.askMaker, r.askLevel, false) < 0) {
                    r.bestAsk = l.price; r.askMaker = o; r.askLevel = l; r.askCap = cap;
                }
            }
        }
        if (r.askMaker == null) r.bestAsk = 0;   // normalize "none" to 0 for callers
        return r;
    }

    // ---- market sweep: split a large order across consecutive book levels, each an independent atomic swap ----

    /** One planned leg = one atomic swap against a specific maker+price. */
    private static final class SweepLeg {
        final Order maker; final double price; final String minima, usdt; final double minimaD, usdtD;
        SweepLeg(Order maker, double price, String minima, String usdt) {
            this.maker = maker; this.price = price; this.minima = minima; this.usdt = usdt;
            this.minimaD = parseD(minima, 0); this.usdtD = parseD(usdt, 0);
        }
    }
    private static final class SweepPlan {
        final boolean sell; final java.util.List<SweepLeg> legs = new java.util.ArrayList<>();
        double target, filledMinima, totalUsdt, avgPrice, worstPrice, slippagePct;
        boolean partial; String stopReason = "filled";   // filled | leg-cap | book-exhausted | below-min | slippage
        SweepPlan(boolean sell) { this.sell = sell; }
    }

    /** Round a mxUSDT quantity DOWN to 8dp (never over a level's cap) and tidy it for the wire. */
    private static String legMinima(double v) {
        // mxUSDT is 8dp but ERC20 USDT is 6dp — quantize the Minima leg to 6dp so a 1:1 (parity) swap matches
        // the counter-leg exactly with no sub-grain remainder the token send would reject.
        return Util.tidyAmount(BigDecimal.valueOf(v).setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString());
    }

    /** Round a USDT amount DOWN to 6dp (never over budget) and tidy it; null if it collapses to zero. */
    /** USDT for a target-driven BUY leg = ceil6(minima × price), computed EXACTLY in BigDecimal from the minima
     *  STRING (not a double product), so {@code lockedUsdt ≥ minima × price} holds to the last tick against the
     *  maker's own BigDecimal guard while I still receive my target mxUSDT. Non-finite/garbage input → null (no throw). */
    private static String ceilUsdt(String minima, double price) {
        if (!positiveSwapAmount(minima) || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal u = new BigDecimal(minima.trim()).multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.UP);
            if (u.signum() <= 0 || !Double.isFinite(u.doubleValue())) return null;
            String s = Util.tidyAmount(u.stripTrailingZeros().toPlainString());
            return (s.isEmpty() || "0".equals(s)) ? null : s;
        } catch (Exception e) { return null; }
    }

    /** Format a percentage to ≤2dp (kills the float noise from storing e.g. 4.2f: 4.199999809… → "4.2"). */
    private static String pctStr(double v) {
        return Util.tidyAmount(BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    }

    /**
     * Walk the book best-price-first, splitting a large order into up to {@link Order#MAX_LEVELS} per-level legs.
     * SELL is size-driven (send = mxUSDT to sell); BUY is BUDGET-driven (send = USDT to spend) so the total
     * spent never exceeds what the user typed even as deeper legs cost more. Each leg is clamped to the maker's
     * display cap AND a per-maker running balance (so two tranches of one maker can't over-allocate). Never emits
     * a sub-min leg. Excludes my own orders.
     */
    /**
     * Split a large order into up to {@link Order#MAX_LEVELS} per-level legs, best-price-first. BOTH sides are
     * mxUSDT-TARGET-driven ({@code targetStr} = mxUSDT to sell / to buy). BUY additionally caps at
     * {@code bestAsk × (1+slippage)} — legs above that are not taken (partial). Leg rounding keeps the maker's
     * guard satisfied: SELL locks mxUSDT + requests {@code floor6(minima×bid)}; BUY locks {@code ceil6(minima×ask)}
     * + requests exactly {@code minima}. Each leg clamped to the maker's live balance + a per-maker running total.
     */
    private SweepPlan buildSweepPlan(boolean sell, String targetStr, double slippage) {
        final String sym = "USDT";
        final double EPS = 1e-9, GRAIN = 1e-6;
        SweepPlan plan = new SweepPlan(sell);
        if (!positiveSwapAmount(targetStr) || !Double.isFinite(slippage) || slippage < 0 || slippage > 0.5) { plan.stopReason = "book-exhausted"; return plan; }
        plan.slippagePct = slippage * 100;
        double target = parseD(targetStr, 0);   // mxUSDT to sell (sell) or mxUSDT to buy (buy)
        plan.target = target;
        if (target <= 0) { plan.stopReason = "book-exhausted"; return plan; }
        final double dust = Math.max(EPS, target * 1e-4);   // ignore a sub-0.01% rounding remainder

        java.util.Map<Order, Double> remUsdt = new java.util.HashMap<>();     // maker's remaining USDT balance
        java.util.Map<Order, Double> remMinima = new java.util.HashMap<>();   // maker's remaining mxUSDT balance
        double remaining = target;                                           // mxUSDT left to fill
        double bestPrice = 0;                                                // first leg actually taken = the slippage anchor
        boolean skippedForMin = false;                                       // ≥1 maker skipped ONLY because the amount was below its min

        for (Object[] row : aggSide(sym, sell, true)) {
            if (plan.legs.size() >= Order.MAX_LEVELS) { plan.stopReason = "leg-cap"; plan.partial = true; break; }
            if (remaining <= dust) break;
            Order maker = (Order) row[0];
            Order.Level lvl = (Order.Level) row[1];
            double price = lvl.price;
            if (!Double.isFinite(price) || price <= 0) continue;

            // BUY slippage cap — never pay more than best × (1+slippage) per mxUSDT.
            if (!sell && bestPrice > 0 && slippage > 0 && price > bestPrice * (1 + slippage) + EPS) {
                plan.stopReason = "slippage"; plan.partial = true; break;
            }

            Order.Pair pr = maker.pairs.get(sym);
            double mn = pr != null ? pr.min : 0;

            double capMinima;
            if (sell) {   // maker BUYS my mxUSDT with USDT → cap by their remaining USDT / price
                double ru = remUsdt.containsKey(maker) ? remUsdt.get(maker) : maker.usdtAvail;
                capMinima = Math.min(lvl.amount, ru / price);
            } else {      // maker SELLS mxUSDT → cap by their remaining mxUSDT
                double rm = remMinima.containsKey(maker) ? remMinima.get(maker) : maker.minimaAvail;
                capMinima = Math.min(lvl.amount, rm);
            }
            if (!Double.isFinite(capMinima) || capMinima <= EPS) continue;

            double takeMinima = Math.min(capMinima, remaining);
            if (takeMinima <= EPS) continue;

            if (mn > 0 && takeMinima < mn - GRAIN) {   // this maker's min can't be met here → skip only IT, NEVER break the
                skippedForMin = true;                  // sweep: rows are best-price-first, so a front maker with a big min
                continue;                              // must not veto deeper, lower-min makers who'd happily fill the taker.
            }

            String minimaStr = legMinima(takeMinima);
            double m = parseD(minimaStr, 0);
            if (m <= EPS) continue;
            String usdtStr = sell ? computeUsdt(minimaStr, price) : ceilUsdt(minimaStr, price);
            if (usdtStr == null) continue;
            SweepLeg leg = new SweepLeg(maker, price, minimaStr, usdtStr);
            if (leg.minimaD <= EPS || leg.usdtD <= EPS) continue;
            if (mn > 0 && leg.minimaD < mn - EPS) { skippedForMin = true; continue; }   // grain floor slipped below min — skip (strict: never emit a leg the maker auto-declines)

            if (!Double.isFinite(plan.filledMinima + leg.minimaD) || !Double.isFinite(plan.totalUsdt + leg.usdtD)) { plan.partial = true; break; }
            if (bestPrice <= 0) bestPrice = price;   // anchor slippage on the FIRST leg actually taken (best fillable)
            plan.legs.add(leg);
            plan.filledMinima += leg.minimaD;
            plan.totalUsdt += leg.usdtD;
            plan.worstPrice = price;   // best-first → the last added is the worst price
            if (sell) remUsdt.put(maker, (remUsdt.containsKey(maker) ? remUsdt.get(maker) : maker.usdtAvail) - leg.usdtD);
            else      remMinima.put(maker, (remMinima.containsKey(maker) ? remMinima.get(maker) : maker.minimaAvail) - leg.minimaD);
            remaining -= leg.minimaD;
        }

        if (remaining > dust) plan.partial = true;
        if (plan.legs.isEmpty() && skippedForMin && "filled".equals(plan.stopReason)) plan.stopReason = "below-min";   // nothing filled ONLY because every maker's min exceeded the amount
        if (plan.partial && "filled".equals(plan.stopReason)) plan.stopReason = "book-exhausted";
        plan.avgPrice = plan.filledMinima > 0 ? plan.totalUsdt / plan.filledMinima : 0;
        return plan;
    }

    /** Total mxUSDT takeable across the book (≤6 legs) on the given side — for the "up to ~X" depth hint. */
    private double sweepDepthMinima(boolean sell) {
        return buildSweepPlan(sell, "1000000000", sell ? 0 : buySlippage()).filledMinima;   // huge target → the whole (within-slippage) depth
    }

    // ---- buy slippage tolerance (deliver the mxUSDT target across deeper legs, capped at best × (1+slippage)) ----

    private double buySlippage() { double pct = prefs.getFloat("buy_slippage_pct", 4.2f);
        return Double.isFinite(pct) && pct >= 0.1f && pct <= 50 ? pct / 100.0 : 0.042; }   // fraction, default 4.2%

    private TextView slipPill(CharSequence label, boolean active, Runnable onTap) {
        TextView p = Design.pill(this, label, active ? Design.ACCENT_SOFT() : Design.SURFACE2(), active ? Design.ACCENT() : Design.DIM());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6); p.setLayoutParams(lp);
        p.setMinimumHeight(dp(48)); p.setMinimumWidth(dp(48));
        p.setOnClickListener(v -> onTap.run()); Design.pressable(p);
        return p;
    }

    private void setBuySlippage(double pct) {
        if (!Double.isFinite(pct)) return;
        prefs.edit().putFloat("buy_slippage_pct", (float) Math.max(0.1, Math.min(50, pct))).apply();
        render();
    }

    private void customSlippageDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView h = new TextView(this);
        Language.setText(h, Language.msg(l10n_atomix_swap_slippage_help, ccy()));
        h.setTextColor(Design.DIM()); h.setTextSize(12.5f); h.setTypeface(Design.sans()); h.setLineSpacing(dp(2), 1f); h.setPadding(0, 0, 0, dp(10));
        box.addView(h);
        final EditText e = new EditText(this); decimalInput(e);
        Language.setText(e, trim(buySlippage() * 100)); Language.hint(e, "%"); e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT()); e.setTextSize(16f); e.setTypeface(Design.mono());
        box.addView(e);
        modalOpen = true;
        final AlertDialog editor = dialog()
                .setTitle(Language.msg(l10n_atomix_swap_slippage_title))
                .setView(box)
                .setPositiveButton(Language.msg(l10n_atomix_swap_set), null)
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double pct = MarketDraft.number(e.getText().toString(), false, Double.NaN);
            if (!Double.isFinite(pct)) {
                Language.setText(h, Language.msg(l10n_atomix_market_invalid_number));
                h.setTextColor(Design.RED());
                return;
            }
            setBuySlippage(pct);
            editor.dismiss();
        });
    }

    private void consolidateDialog() {
        final String token = TradingContext.active().tokenId;
        minima.tokenBalance(b -> {
            if (isFinishing() || isDestroyed() || !token.equals(TradingContext.active().tokenId)) return;
            int cap = MinimaHtlc.maxSafeCoinRows(token);
            int runs = (int) Math.max(0L, ((long) b.coins - cap + 17L) / 18L);
            String label = MinimaHtlc.MINIMA_TOKENID.equals(token) ? "MINIMA" : "MxUSD";
            modalOpen = true;
            dialog().setTitle(Language.msg(l10n_atomix_operation_title, label))
                    .setMessage(Language.msg(l10n_atomix_operation_body, b.coins, cap, runs > 0 ? Language.msg(l10n_atomix_operation_runs, runs) : Language.msg(l10n_atomix_operation_within)))
                    .setNeutralButton(Language.msg(l10n_atomix_operation_preview), (d, w) -> consolidateOnce(token, true))
                    .setPositiveButton(Language.msg(l10n_atomix_operation_run), (d, w) -> consolidateOnce(token, false))
                    .setNegativeButton(Language.msg(l10n_common_cancel), null)
                    .setOnDismissListener(d -> { modalOpen = false; render(); }).show();
        }, e -> showText(Language.msg(l10n_atomix_operation_consolidate), OperationMessages.message(e)));
    }

    private void consolidateOnce(String token, boolean preview) {
        if (!token.equals(TradingContext.active().tokenId)) { toast(Language.msg(l10n_atomix_operation_changed)); return; }
        if (IdentityWatch.halted()) { toast(Language.msg(l10n_atomix_otc_halted)); return; }
        if (!SwapEngine.beginConsolidate()) { toast(Language.msg(l10n_atomix_operation_busy)); return; }
        final MinimaHtlc target = minima;
        java.util.function.Consumer<String> fail = e -> {
            SwapEngine.endConsolidate();
            if (!isFinishing() && !isDestroyed()) showText(Language.msg(l10n_atomix_operation_consolidate), OperationMessages.message(e));
        };
        target.tokenBalance(b -> {
            if (!token.equals(TradingContext.active().tokenId)) { fail.accept("Trading currency changed; reopen Consolidate"); return; }
            if (b.coins < 3) { fail.accept("At least 3 spendable coins are needed."); return; }
            target.hasPendingMinima(pending -> {
                if (pending) { fail.accept("Coins are still confirming. Wait for 3 confirmations before another run."); return; }
                target.currentBlock(new MinimaHtlc.BlockCb() {
                    public void err(String e) { fail.accept(e); }
                    public void ok(int block) {
                        if (!token.equals(TradingContext.active().tokenId)) { fail.accept("Trading currency changed; reopen Consolidate"); return; }
                        if (block <= 0) { fail.accept("Could not read the current block"); return; }
                        int waitUntil = prefs.getInt("consolidate_until_" + token, 0);
                        if (!preview && block < waitUntil) {
                            fail.accept("Coins: " + b.coins + " · waiting for new coins to age 3 blocks. Retry at block " + waitUntil + "."); return;
                        }
                        if (preview) {
                            target.previewConsolidate(20, r -> {
                                SwapEngine.endConsolidate();
                                showText(Language.msg(l10n_atomix_operation_preview_title), r.toString());
                            }, fail);
                            return;
                        }
                        // Persist before submission: a lost reply must not offer an immediate repeat.
                        prefs.edit().putInt("consolidate_until_" + token, block + 4).apply();
                        target.consolidateCoins(20, new MinimaHtlc.PostCb() {
                            public void err(String e) { fail.accept("Submission could not be confirmed: " + e + "\nCheck your balance before retrying."); }
                            public void ok(String txpowid) {
                                SwapEngine.endConsolidate();
                                target.tokenBalance(after -> {
                                    if (!isFinishing() && !isDestroyed()) showText(Language.msg(l10n_atomix_operation_submitted_title),
                                            Language.msg(l10n_atomix_operation_submitted, b.coins, after.coins, txpowid));
                                    fetchMinimaBalance();
                                }, e -> showText(Language.msg(l10n_atomix_operation_submitted_title), Language.msg(l10n_atomix_operation_refresh, txpowid)));
                            }
                        });
                    }
                });
            }, fail);
        }, fail);
    }

    // ---- Wallet tab ----

    private void renderWalletTab(LinearLayout col) {
        if (node != null && node.hasInterruptedWrite()) {
            TextView resolve = Design.pill(this, Language.msg(l10n_atomix_recovery_resolve), Design.SURFACE2(), Design.RED());
            // Say WHICH write and WHY. The old text told everyone to "First restart MinimaCore",
            // which is right only for a timeout; for the other causes the node already answered (or
            // was never asked) and restarting it achieves nothing - observed live twice, 2026-09-23.
            final com.eurobuddha.atomix.integratedcomms.NodeApi recoveryNode = node;
            final String pausedWrite = recoveryNode.interruptedWriteId();
            String detail = recoveryNode.interruptedWriteDetail();
            resolve.setMinimumHeight(dp(48));
            resolve.setOnClickListener(v -> dialog()
                    .setTitle(Language.msg(l10n_atomix_recovery_resolve))
                    .setMessage(Language.msg(l10n_atomix_recovery_body, detail == null ? Language.msg(l10n_atomix_recovery_unknown) : RecoveryMessages.message(detail)))
                    .setNegativeButton(Language.msg(l10n_atomix_recovery_keep), null)
                    .setPositiveButton(Language.msg(l10n_atomix_recovery_checked), (d, w) -> {
                        toast(recoveryNode.acknowledgeInterruptedWrite(pausedWrite) ? Language.msg(l10n_atomix_recovery_cleared) : Language.msg(l10n_atomix_recovery_still));
                        render();
                    }).show());
            col.addView(resolve);
        }
        LinearLayout minimaCard = walletCard(Language.msg(l10n_atomix_ui_available), Language.join(AtomixMessages.message(Util.fmt5(minimaBal)), " ", ccy()), Language.join(minimaBreakdown(), "  ·  ", Language.msg(l10n_atomix_ui_coin_hold)), Design.ACCENT());
        minimaCard.setOnLongClickListener(v -> { minimaCoinDump(); return true; });
        col.addView(minimaCard);
        minimaBalView = (TextView) minimaCard.findViewWithTag(TAG_BAL);   // fresh ref each render (tree is rebuilt)

        try {
            int count = new java.math.BigDecimal(minimaCoins).intValueExact();
            if (count >= 10) {
                boolean blocked = count > MinimaHtlc.maxSafeCoinRows(TradingContext.active().tokenId);
                TextView consolidate = Design.pill(this, blocked
                        ? Language.msg(l10n_atomix_ui_consolidate_blocked, count)
                        : Language.msg(l10n_atomix_ui_consolidate_offer, count), Design.SURFACE2(), blocked ? Design.RED() : Design.ACCENT());
                consolidate.setOnClickListener(v -> consolidateDialog());
                col.addView(consolidate);
            }
        } catch (NumberFormatException | ArithmeticException ignored) { /* balance is not available yet */ }

        CharSequence addrLine = ethAddr == null ? (ethErr == null ? Language.msg(l10n_atomix_ui_deriving) : "—") : shortAddr(ethAddr);
        LinearLayout ethCard = walletCard("Ethereum · " + net.label, ethBal, addrLine, Design.TEXT());
        if (ethAddr != null) ethCard.setOnClickListener(v -> copy(ethAddr, Language.msg(l10n_atomix_ui_address_copied)));
        col.addView(ethCard);
        ethBalView = (TextView) ethCard.findViewWithTag(TAG_BAL);
        tokenBalViews.clear();
        for (Map.Entry<String, String> e : tokenBals.entrySet()) {
            // token balances get the same big card treatment as Minima/ETH (not a small row)
            LinearLayout tcard = walletCard(e.getKey() + " · Ethereum", e.getValue() + " " + e.getKey(), null, Design.TEXT());
            TextView tv = (TextView) tcard.findViewWithTag(TAG_BAL);
            if (tv != null) tokenBalViews.add(tv);
            col.addView(tcard);
        }

        if (ethAddr != null) {
            LinearLayout walletActions = new LinearLayout(this);
            walletActions.setOrientation(LinearLayout.HORIZONTAL);
            walletActions.setPadding(0, dp(8), 0, 0);
            TextView refreshBal = Design.pill(this, Language.msg(l10n_atomix_ui_refresh), Design.SURFACE2(), Design.TEXT());
            refreshBal.setOnClickListener(v -> { toast(Language.msg(l10n_atomix_ui_refreshing)); refreshBalances(true); });
            TextView fund = Design.pill(this, Language.msg(l10n_atomix_ui_fund), Design.SURFACE2(), Design.TEXT());
            fund.setOnClickListener(v -> receiveDialog());
            TextView send = Design.pill(this, Language.msg(l10n_atomix_ui_send), Design.SURFACE2(), Design.TEXT());
            send.setOnClickListener(v -> sendDialog());
            TextView exportKey = Design.pill(this, Language.msg(l10n_atomix_ui_export_key), Design.SURFACE2(), Design.DIM());
            exportKey.setOnClickListener(v -> exportKeyDialog());
            walletActions.setOrientation(LinearLayout.VERTICAL);
            for (TextView[] pair : new TextView[][]{{refreshBal, fund}, {send, exportKey}}) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (TextView action : pair) {
                    action.setMinimumHeight(dp(48));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                    row.addView(action, lp);
                }
                walletActions.addView(row);
            }
            col.addView(walletActions);
        }

        if (ethErr != null) {
            TextView err = new TextView(this);
            Language.setText(err, Language.join("⚠ ", AtomixMessages.message(ethErr)));
            err.setTextColor(Design.RED()); err.setTextSize(12f); err.setPadding(dp(2), dp(8), dp(2), 0);
            col.addView(err);
        }

        if (!paired) col.addView(dimNote(nodeOfflineText()));
    }

    /** Fire any balance pulse that was armed while the Wallet tab wasn't showing (its balance views didn't exist). */
    private void firePendingPulses() {
        if (modalOpen) return;
        if (pulseMinimaPending && minimaBalView != null) { pulseMinimaPending = false; Design.pulse(minimaBalView, 0xFFFFFFFF); }
        if (pulseEthPending && ethBalView != null) {
            pulseEthPending = false;
            Design.pulse(ethBalView, Design.ACCENT());
            for (TextView t : tokenBalViews) Design.pulse(t, Design.ACCENT());
        }
    }

    // ---- Market tab (advanced: full order book + publish your own) ----

    private void renderMarketTab(LinearLayout col) {
        TextView hint = new TextView(this);
        Language.setText(hint, Language.msg(l10n_atomix_market_hint));
        hint.setTextColor(Design.DIM()); hint.setTextSize(12.5f); hint.setTypeface(Design.sans()); hint.setPadding(0, dp(2), 0, dp(2));
        col.addView(hint);

        PriceOracle.refreshAsync();   // keep the reference price current while the tab is watched (rate-limited)
        if (PriceOracle.mid() > 0) {
            TextView mexc = new TextView(this);
            Language.setText(mexc, MarketMessages.message(PriceOracle.describe()));
            mexc.setTextColor(Design.DIM()); mexc.setTextSize(11.5f); mexc.setTypeface(Design.mono()); mexc.setPadding(0, dp(2), 0, dp(2));
            col.addView(mexc);
        }

        LinearLayout obHeader = new LinearLayout(this);
        obHeader.setOrientation(LinearLayout.HORIZONTAL);
        obHeader.setGravity(Gravity.CENTER_VERTICAL);
        obHeader.setPadding(0, dp(14), 0, dp(2));
        TextView obTitle = new TextView(this);
        Language.setText(obTitle, Language.msg(l10n_atomix_market_book, orderBook.size()));
        obTitle.setTextColor(Design.TEXT()); obTitle.setTextSize(16f); obTitle.setTypeface(Design.sansBold());
        obHeader.addView(obTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Design.pill(this, Language.msg(l10n_atomix_market_refresh), Design.SURFACE2(), Design.DIM());
        refresh.setOnClickListener(v -> scanOrderBook());
        obHeader.addView(refresh);
        col.addView(obHeader);

        if (orderBook.isEmpty()) {
            TextView empty = new TextView(this);
            Language.setText(empty, paired ? Language.msg(l10n_atomix_market_empty) : Language.msg(l10n_atomix_market_connect));
            empty.setTextColor(Design.DIM()); empty.setTextSize(12.5f); empty.setTypeface(Design.sans()); empty.setPadding(dp(2), dp(6), dp(2), 0);
            col.addView(empty);
        } else {
            orderLadder(col);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setClipChildren(false); btns.setClipToPadding(false);   // don't clip the CTA elevation shadow
        TextView editBtn = button(Language.msg(l10n_atomix_market_edit));
        editBtn.setBackground(Design.roundBg(this, Design.SURFACE2(), 14));
        editBtn.setTextColor(Design.TEXT());
        editBtn.setOnClickListener(v -> editOrderDialog());
        TextView publish = button(Language.msg(l10n_atomix_market_publish));
        publish.setOnClickListener(v -> publishOrder());
        LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        e1.rightMargin = dp(6); e1.topMargin = dp(14);
        LinearLayout.LayoutParams e2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        e2.leftMargin = dp(6); e2.topMargin = dp(14);
        btns.addView(editBtn, e1); btns.addView(publish, e2);
        col.addView(btns);

        if (orderStatus != null) {
            TextView st = new TextView(this);
            Language.setText(st, OperationMessages.message(orderStatus));
            st.setTextColor(orderStatus.startsWith("✓") ? Design.IN() : Design.DIM());
            st.setTextSize(12.5f); st.setTypeface(Design.sans()); st.setPadding(dp(2), dp(10), dp(2), 0);
            col.addView(st);
        }
    }

    private void showInfo(CharSequence title, CharSequence body) {
        dialog().setTitle(title).setMessage(body).setPositiveButton(Language.msg(l10n_atomix_ui_got_it), null).show();
    }

    private void showWelcome() {
        prefs.edit().putBoolean("seen_welcome", true).apply();
        dialog()
                .setTitle(Language.msg(l10n_atomix_ui_welcome))
                .setMessage(Language.msg(l10n_atomix_ui_welcome_body, ccy(), ccy(), ccy()))
                .setPositiveButton(Language.msg(l10n_atomix_ui_started), null)
                .show();
    }

    private void renderActiveSwaps(LinearLayout col) {
        if (db == null) return;
        java.util.Set<String> dismissed = dismissedSwaps();
        long now = System.currentTimeMillis();
        List<SwapDb.Swap> show = new java.util.ArrayList<>();
        int hidden = 0;
        for (SwapDb.Swap s : db.allSwaps()) {
            if (!visibleHere(s)) continue;   // another market's finished history — it lives on ITS Activity tab
            if (dismissed.contains(s.hash) || (isTerminal(s) && now - s.updated > TERMINAL_GRACE_MS)) { hidden++; continue; }
            show.add(s);
        }
        if (show.isEmpty() && hidden == 0) return;

        TextView title = new TextView(this);
        Language.setText(title, Language.msg(l10n_atomix_history_your));
        title.setTextColor(Design.TEXT()); title.setTextSize(16f); title.setTypeface(Design.sansBold());
        title.setPadding(0, dp(22), 0, dp(2));
        col.addView(title);
        for (SwapDb.Swap s : show) col.addView(swapCard(s));
        if (hidden > 0) {
            TextView h = new TextView(this);
            Language.setText(h, Language.msg(l10n_atomix_history_hidden, hidden));
            h.setTextColor(Design.DIM()); h.setTextSize(11.5f); h.setTypeface(Design.sans()); h.setPadding(dp(2), dp(6), 0, dp(2));
            h.setOnClickListener(v -> openHistory(0));
            col.addView(h);
        }
    }

    // ============================================================ Activity tab (live swaps + history)

    /** Jump to the Activity tab and select a sub-tab (0 = my swaps, 1 = market). Always re-renders so the
     *  sub-tab pills switch even when we're already on Activity. */
    private void openHistory(int tab) {
        boolean switching = currentTab != TAB_ACTIVITY;
        historyTab = tab;
        currentTab = TAB_ACTIVITY;
        if (switching) animateTab = true;
        updateTabBarSelection();
        render();
    }

    private void renderActivityTab(LinearLayout col) {
        renderActiveSwaps(col);   // live/resumable swap cards + "N finished hidden" link (empty → adds nothing)

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.VERTICAL); tabs.setPadding(0, dp(18), 0, dp(8));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.bottomMargin = dp(8);
        TextView t0 = Design.pill(this, Language.msg(l10n_atomix_history_mine), historyTab == 0 ? Design.ACCENT() : Design.SURFACE2(), historyTab == 0 ? Design.ON_ACCENT() : Design.DIM());
        t0.setMinimumHeight(dp(48));
        t0.setOnClickListener(v -> openHistory(0));
        TextView t1 = Design.pill(this, Language.msg(l10n_atomix_history_market), historyTab == 1 ? Design.ACCENT() : Design.SURFACE2(), historyTab == 1 ? Design.ON_ACCENT() : Design.DIM());
        t1.setMinimumHeight(dp(48));
        t1.setOnClickListener(v -> openHistory(1));
        tabs.addView(t0, gap); tabs.addView(t1);
        // Export CSV — all your swaps (maker + taker), one file. Sits with the My-swaps history.
        LinearLayout.LayoutParams gapx = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gapx.topMargin = dp(8);
        TextView tex = Design.pill(this, Language.msg(l10n_atomix_history_export), Design.SURFACE2(), Design.DIM());
        tex.setMinimumHeight(dp(48));
        tex.setOnClickListener(v -> exportSwapsCsv());
        tabs.addView(tex, gapx);
        col.addView(tabs);

        if (historyTab == 0) mySwapsList(col); else marketView(col);
    }

    /** History for the SELECTED market only (plus any still-actionable swap in the other one — visibleHere).
     *  Without the scope this listed both currencies at once, so switching to MINIMA still showed the dollar
     *  history: the rows carry their market in sellToken/buyToken, the list just never read it. */
    private void mySwapsList(LinearLayout col) {
        if (db == null) return;
        java.util.List<SwapDb.Swap> all = new java.util.ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) if (visibleHere(s)) all.add(s);
        if (all.isEmpty()) { col.addView(dimNote(Language.msg(l10n_atomix_history_empty, ccy()))); return; }
        for (SwapDb.Swap s : all) col.addView(historySwapCard(s));
    }

    private LinearLayout historySwapCard(SwapDb.Swap s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.card(this, 14));
        c.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); c.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView line = new TextView(this);
        Language.setText(line, s.sellAmount + " " + s.sellToken + "  →  " + s.buyAmount + " " + s.buyToken);
        line.setTextColor(Design.TEXT()); line.setTextSize(14f); line.setTypeface(Design.mono());
        top.addView(line, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Design.pill(this, statusLabel(s), statusBg(s), statusFg(s)));
        c.addView(top);

        TextView meta = new TextView(this);
        CharSequence when = Language.since(s.created);
        String cp = (s.counterparty == null || s.counterparty.isEmpty()) ? "" : "  ·  " + shortAddr(s.counterparty);
        Language.setText(meta, Language.join(when, "  ·  ", historyRole(s.role), cp));
        meta.setTextColor(Design.DIM2()); meta.setTextSize(11.5f); meta.setTypeface(Design.sans()); meta.setPadding(0, dp(5), 0, 0);
        // NI-1 (RULE 1): the counterparty is shown truncated — long-press copies the FULL value.
        if (s.counterparty != null && !s.counterparty.isEmpty())
            meta.setOnClickListener(v -> copy(s.counterparty, Language.msg(l10n_atomix_market_counterparty_copied)));
        c.addView(meta);

        TextView detail = new TextView(this);
        Language.setText(detail, statusDetail(s));
        detail.setTextColor(Design.DIM()); detail.setTextSize(12f); detail.setTypeface(Design.sans()); detail.setPadding(0, dp(3), 0, 0);
        c.addView(detail);

        c.setOnClickListener(v -> checkNow(s.hash));   // tap → live inspect detail
        Design.pressable(c);
        return c;
    }

    private void marketView(LinearLayout col) {
        if (db == null) return;
        // The SELECTED market's prints only. Unscoped, the chart plotted mxUSD's parity ~1.00 alongside
        // MINIMA's ~0.004 in one series, under an axis labelled with whichever currency happened to be active.
        final String tok = TradingContext.active().tokenId;
        java.util.List<SwapDb.MarketTrade> chartData = db.executedTrades(200, tok);
        java.util.List<SwapDb.MarketTrade> recent = db.recentTrades(50, tok);

        if (chartData.size() < 2) {
            TextView emptyChart = dimNote(chartData.isEmpty() ? Language.msg(l10n_atomix_history_collecting) : Language.msg(l10n_atomix_history_one_trade));
            emptyChart.setGravity(Gravity.CENTER); emptyChart.setMinimumHeight(dp(180));
            emptyChart.setBackground(Design.card(this, 14)); col.addView(emptyChart);
        } else {
            MarketChartView chart = new MarketChartView(this);
            chart.setBackground(Design.card(this, 14));
            chart.setData(chartData);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
            clp.topMargin = dp(6); chart.setLayoutParams(clp);
            col.addView(chart);
        }

        TextView stats = new TextView(this);
        String last = chartData.isEmpty() ? "—" : fmtPrice(chartData.get(chartData.size() - 1).price);
        Language.setText(stats, Language.msg(l10n_atomix_history_stats, last, ccy(), countByStatus(recent)));
        stats.setTextColor(Design.DIM2()); stats.setTextSize(11f); stats.setTypeface(Design.sans()); stats.setPadding(dp(2), dp(8), 0, dp(6));
        col.addView(stats);

        if (recent.isEmpty()) {
            col.addView(dimNote(Language.msg(l10n_atomix_history_market_empty, ccy())));
            return;
        }
        for (SwapDb.MarketTrade t : recent) col.addView(tradeRow(t));
    }

    private LinearLayout tradeRow(SwapDb.MarketTrade t) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Design.card(this, 12));
        r.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6); r.setLayoutParams(lp);

        TextView when = new TextView(this);
        Language.setText(when, Language.since(t.observedAt));
        when.setTextColor(Design.DIM2()); when.setTextSize(11f); when.setTypeface(Design.sans());
        r.addView(when, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView price = new TextView(this);
        Language.setText(price, fmtPrice(t.price)); price.setTextColor(Design.TEXT()); price.setTextSize(14f); price.setTypeface(Design.mono());
        r.addView(price);

        TextView size = new TextView(this);
        Language.setText(size, "  " + abbrev(parseD(t.sizeMinima, 0)) + " " + ccy()); size.setTextColor(Design.DIM()); size.setTextSize(12f); size.setTypeface(Design.mono());
        size.setPadding(dp(8), 0, dp(8), 0);
        r.addView(size);

        boolean exec = SwapDb.MT_EXECUTED.equals(t.status), ref = SwapDb.MT_REFUNDED.equals(t.status);
        int bg = exec ? Design.IN() : (ref ? Design.RED() : Design.SURFACE2());
        int fg = (exec || ref) ? Design.ON_ACCENT() : Design.DIM();
        r.addView(Design.pill(this, exec ? Language.msg(l10n_atomix_history_filled) : ref ? Language.msg(l10n_atomix_history_cancelled) : Language.msg(l10n_atomix_history_open), bg, fg));
        return r;
    }

    private CharSequence countByStatus(java.util.List<SwapDb.MarketTrade> ts) {
        int ex = 0, op = 0;
        for (SwapDb.MarketTrade t : ts) {
            if (SwapDb.MT_EXECUTED.equals(t.status)) ex++;
            else if (SwapDb.MT_OPEN.equals(t.status)) op++;
        }
        return Language.msg(l10n_atomix_history_counts, ex, op);
    }

    private TextView dimNote(CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s); t.setTextColor(Design.DIM()); t.setTextSize(12.5f); t.setTypeface(Design.sans());
        t.setLineSpacing(dp(3), 1f); t.setPadding(dp(2), dp(12), dp(2), 0);
        return t;
    }

    private static boolean isTerminal(SwapDb.Swap s) {
        return com.eurobuddha.atomix.swap.SwapVisibility.isTerminal(s);
    }

    /** Does this swap belong on screen in the currently-selected market? Its own currency always; the OTHER
     *  currency only while it is still actionable — see SwapVisibility for the rule and why it fails open. */
    private boolean visibleHere(SwapDb.Swap s) {
        return com.eurobuddha.atomix.swap.SwapVisibility.visibleIn(
                s, TradingContext.active(), chainBlock, System.currentTimeMillis());
    }

    private java.util.Set<String> dismissedSwaps() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String h : prefs.getString("dismissed_swaps", "").split(",")) if (!h.isEmpty()) set.add(h);
        return set;
    }

    private void dismissSwap(String hash) {
        java.util.Set<String> set = dismissedSwaps();
        set.add(hash);
        prefs.edit().putString("dismissed_swaps", android.text.TextUtils.join(",", set)).apply();
        render();
    }

    /** Hide a still-open swap card (e.g. one stuck because its lock never landed). UI-ONLY: the engine keeps
     *  driving the underlying swap, so any funds actually locked on-chain still auto-refund at their timelock. */
    private void confirmHideSwap(String hash) {
        modalOpen = true;
        dialog()
                .setTitle(Language.msg(l10n_atomix_history_hide_title))
                .setMessage(Language.msg(l10n_atomix_history_hide_help))
                .setPositiveButton(Language.msg(l10n_atomix_history_hide), (d, w) -> dismissSwap(hash))
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    private LinearLayout swapCard(SwapDb.Swap s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.card(this, 14));
        c.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); c.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView line = new TextView(this);
        Language.setText(line, s.sellAmount + " " + s.sellToken + "  →  " + s.buyAmount + " " + s.buyToken);
        line.setTextColor(Design.TEXT()); line.setTextSize(14f); line.setTypeface(Design.mono());
        top.addView(line, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Design.pill(this, statusLabel(s), statusBg(s), statusFg(s)));
        {
            TextView x = Design.pill(this, "✕", Design.SURFACE2(), Design.DIM());
            Language.description(x, Language.msg(l10n_atomix_history_hide_description)); x.setMinimumWidth(dp(48)); x.setMinimumHeight(dp(48));
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            xlp.leftMargin = dp(6); x.setLayoutParams(xlp);
            // terminal → dismiss immediately; still-open (e.g. a stuck swap whose lock never landed) → confirm first.
            x.setOnClickListener(v -> { if (isTerminal(s)) dismissSwap(s.hash); else confirmHideSwap(s.hash); });
            top.addView(x);
        }
        c.addView(top);

        TextView detail = new TextView(this);
        Language.setText(detail, statusDetail(s));
        detail.setTextColor(Design.DIM()); detail.setTextSize(12.5f); detail.setTypeface(Design.sans()); detail.setPadding(0, dp(5), 0, 0);
        c.addView(detail);

        TextView meta = new TextView(this);
        Language.setText(meta, Language.join(historyRole(s.role), "  ·  ", countdown(s)));
        meta.setTextColor(Design.DIM2()); meta.setTextSize(11.5f); meta.setTypeface(Design.sans()); meta.setPadding(0, dp(3), 0, 0);
        c.addView(meta);

        // surface a logged reject/error reason inline (so a stuck swap isn't a black box)
        String err = swapErrorNote(s.hash);
        if (err != null) {
            TextView e = new TextView(this);
            Language.setText(e, Language.join("⚠ ", AtomixMessages.message(err)));
            e.setTextColor(Design.RED()); e.setTextSize(12f); e.setPadding(0, dp(4), 0, 0);
            c.addView(e);
        }

        // a manual "Check now" that runs a live on-chain inspection AND advances the swap
        if (!SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status)) {
            TextView check = Design.pill(this, Language.msg(l10n_atomix_history_check), Design.SURFACE2(), Design.TEXT());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(8); check.setLayoutParams(clp);
            check.setOnClickListener(v -> checkNow(s.hash));
            c.addView(check);
        }
        return c;
    }

    /** First logged reject/error reason for a swap, or null. */
    private String swapErrorNote(String hash) {
        if (db == null) return null;
        for (SwapDb.Event e : db.getEvents(hash)) {
            String n = e.note == null ? "" : e.note.toLowerCase();
            if (n.contains("mismatch") || n.contains("invalid") || n.contains("incorrect")
                    || n.contains("too close") || n.contains("fail")) return e.note;
        }
        return null;
    }

    /** Force a poll (advance) + run the live inspector and show the report. */
    private void checkNow(String hash) {
        toast(Language.msg(l10n_atomix_history_checking));
        if (engine == null) return;
        engine.poll();
        refreshBalances(true);
        engine.inspect(hash, lines -> showReport(hash, lines));
    }

    private void showReport(String hash, java.util.List<String> lines) {
        Language.TextBuilder sb = new Language.TextBuilder();
        boolean first = true;
        for (String l : lines) {
            if (!first) sb.append("\n\n");
            sb.append(ReportMessages.message(l)); first = false;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView t = new TextView(this);
        Language.setText(t, sb.build());
        t.setTextColor(Design.TEXT()); t.setTextSize(13f); t.setTextIsSelectable(true);
        box.addView(t);
        modalOpen = true;
        dialog()
                .setTitle(Language.msg(l10n_atomix_history_report))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_atomix_history_again), (d, w) -> checkNow(hash))
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    /** Plain-language description of where a swap is, so "waiting" isn't ambiguous. */
    private CharSequence statusDetail(SwapDb.Swap s) {
        if (s.status == null) return "";
        boolean initiator = "INITIATOR".equals(s.role);
        switch (s.status) {
            case SwapDb.ST_STARTED:
                return Language.msg(l10n_atomix_history_started, s.sellAmount, s.sellToken);
            case SwapDb.ST_LOCKED:
                return initiator ? Language.msg(l10n_atomix_history_locked_initiator)
                        : Language.msg(l10n_atomix_history_locked_responder);
            case SwapDb.ST_CLAIMING:
                return Language.msg(l10n_atomix_history_claiming_detail, s.buyAmount, s.buyToken);
            case SwapDb.ST_COMPLETE:
                return Language.msg(l10n_atomix_history_complete_detail, s.buyAmount, s.buyToken);
            case SwapDb.ST_REFUNDED:
                return Language.msg(l10n_atomix_history_refunded_detail, s.sellAmount, s.sellToken);
            default:
                return "";
        }
    }

    private CharSequence statusLabel(SwapDb.Swap s) {
        if (s.status == null) return "";
        switch (s.status) {
            case SwapDb.ST_STARTED:  return Language.msg(l10n_atomix_history_waiting);
            case SwapDb.ST_LOCKED:   return Language.msg(l10n_atomix_history_locked);
            case SwapDb.ST_CLAIMING: return Language.msg(l10n_atomix_history_claiming);
            case SwapDb.ST_COMPLETE: return Language.msg(l10n_atomix_history_complete);
            case SwapDb.ST_REFUNDED: return Language.msg(l10n_atomix_history_refunded);
            case SwapDb.ST_ERROR: return Language.msg(l10n_atomix_history_error);
            default: return s.status == null ? "" : s.status;
        }
    }
    private int statusBg(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status)) return Design.IN();
        if (SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status)) return Design.RED();
        return Design.SURFACE2();
    }
    private int statusFg(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status))
            return Design.ON_ACCENT();
        return Design.DIM();
    }

    /** Time left on MY locked leg (the one I'd refund). Minima leg → blocks; ETH leg → wall-clock. */
    private CharSequence countdown(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status)) return Language.msg(l10n_atomix_history_done);
        if (SwapDb.ST_REFUNDED.equals(s.status)) return Language.msg(l10n_atomix_history_reclaimed);
        if (s.myTimelock <= 0) return "";
        if (s.myLegIsMinima) {
            return Language.msg(l10n_atomix_history_block, s.myTimelock);
        }
        long now = System.currentTimeMillis() / 1000L;
        long left = s.myTimelock - now;
        if (left <= 0) return Language.msg(l10n_atomix_history_window);
        long mins = left / 60;
        return Language.msg(l10n_atomix_history_minutes, mins);
    }

    /**
     * The order book as centred two-sided rows — one per maker: bidSize · bidPrice │ askPrice · askSize,
     * maker tag above. Tap the bid half to SELL mxUSDT, the ask half to BUY mxUSDT. Best bid/ask highlighted.
     */
    /** {Order maker, Order.Level} rows for one side across the live book, best-price-first (sell→bids DESC,
     *  buy→asks ASC). Legacy single-price makers contribute one synthetic level via effectiveBids/Asks.
     *  excludeMine drops my own orders (the sweep planner never takes my own liquidity). */
    private java.util.List<Object[]> aggSide(String sym, boolean sell, boolean excludeMine) {
        java.util.List<Object[]> agg = new java.util.ArrayList<>();
        for (Order o : orderBook.values()) {
            if (excludeMine && isMine(o)) continue;
            for (Order.Level l : (sell ? o.effectiveBids(sym) : o.effectiveAsks(sym))) agg.add(new Object[]{o, l});
        }
        // Best price first (sell → highest bid, buy → lowest ask); at an EQUAL price, the deepest maker first so
        // buildSweepPlan fills the largest maker before splitting into another leg — fewest swaps.
        java.util.Collections.sort(agg, (a, b) ->
                SwapOrderBook.compareForFill((Order) a[0], (Order.Level) a[1], (Order) b[0], (Order.Level) b[1], sell));
        return agg;
    }

    private void orderLadder(LinearLayout col) {
        final String sym = "USDT";
        // Display keeps my own orders (tagged "you"); the sweep planner calls aggSide with excludeMine=true.
        java.util.List<Object[]> bidsAgg = aggSide(sym, true, false);
        java.util.List<Object[]> asksAgg = aggSide(sym, false, false);
        if (bidsAgg.isEmpty() && asksAgg.isEmpty()) return;

        double bestBid = bidsAgg.isEmpty() ? 0 : ((Order.Level) bidsAgg.get(0)[1]).price;
        double bestAsk = asksAgg.isEmpty() ? 0 : ((Order.Level) asksAgg.get(0)[1]).price;
        if (bestBid > 0 && bestAsk > 0) {
            TextView sp = new TextView(this);
            Language.setText(sp, Language.msg(l10n_atomix_market_spread, fmtPrice(bestAsk - bestBid), sym, sym, ccy(), ccy()));
            sp.setTextColor(Design.DIM2()); sp.setTextSize(11f); sp.setTypeface(Design.sans()); sp.setPadding(dp(2), dp(4), 0, dp(4));
            col.addView(sp);
        }

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(dp(6), dp(4), dp(6), dp(2));
        TextView lL = new TextView(this); Language.setText(lL, Language.msg(l10n_atomix_market_sell_bid, ccy())); lL.setTextColor(Design.IN()); lL.setTextSize(11f); lL.setTypeface(Design.sansBold()); lL.setLetterSpacing(0.04f);
        TextView lR = new TextView(this); Language.setText(lR, Language.msg(l10n_atomix_market_buy_ask, ccy())); lR.setTextColor(Design.RED()); lR.setTextSize(11f); lR.setTypeface(Design.sansBold()); lR.setLetterSpacing(0.04f); lR.setGravity(Gravity.END);
        legend.addView(lL, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        legend.addView(lR, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(legend);

        int rows = Math.max(bidsAgg.size(), asksAgg.size());
        int shown = Math.min(rows, 12);
        for (int i = 0; i < shown; i++) {
            Object[] bidRow = i < bidsAgg.size() ? bidsAgg.get(i) : null;
            Object[] askRow = i < asksAgg.size() ? asksAgg.get(i) : null;
            col.addView(depthRow(sym, bidRow, askRow, i == 0));
        }
        if (rows > shown) {
            TextView more = new TextView(this);
            Language.setText(more, Language.msg(l10n_atomix_market_more, rows - shown));
            more.setTextColor(Design.DIM2()); more.setTextSize(11f); more.setTypeface(Design.sans()); more.setPadding(dp(2), dp(6), 0, 0);
            col.addView(more);
        }
    }

    /** One depth row: [bid half]│[ask half] at rank i (the two halves may be from different makers). */
    private LinearLayout depthRow(String sym, Object[] bidMr, Object[] askMr, boolean best) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(best ? Design.stroked(this, Design.SURFACE2(), 12) : Design.card(this, 12));
        box.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6); box.setLayoutParams(blp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = depthHalf(sym, bidMr, true, best);
        LinearLayout right = depthHalf(sym, askMr, false, best);
        TextView div = new TextView(this); Language.setText(div, "│"); div.setTextColor(Design.DIM2()); div.setTextSize(14f); div.setPadding(dp(8), 0, dp(8), 0);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(div);
        row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        return box;
    }

    /** One side of a depth row: the priced quote + a small maker tag; tappable to take THAT level. */
    private LinearLayout depthHalf(String sym, Object[] mr, boolean isBid, boolean best) {
        LinearLayout half = new LinearLayout(this);
        half.setOrientation(LinearLayout.VERTICAL);
        if (mr == null) {
            half.addView(quoteHalf("—", "—", isBid ? Design.IN() : Design.RED(), false, isBid, best));
            return half;
        }
        final Order maker = (Order) mr[0];
        final Order.Level lvl = (Order.Level) mr[1];
        // display clamp: never advertise more than the maker's live balance can back (guard/chain still enforce)
        final double cap = isBid ? (lvl.price > 0 ? Math.min(lvl.amount, maker.usdtAvail / lvl.price) : 0)
                                 : Math.min(lvl.amount, maker.minimaAvail);
        half.addView(isBid
                ? quoteHalf(abbrev(cap), fmtPrice(lvl.price), Design.IN(), best, true, best)
                : quoteHalf(fmtPrice(lvl.price), abbrev(cap), Design.RED(), best, false, best));
        boolean mine = isMine(maker);
        TextView tag = new TextView(this);
        Language.setText(tag, mine ? Language.msg(l10n_atomix_market_you) : shortAddr(maker.signerPk));
        tag.setTextColor(mine ? Design.ACCENT() : Design.DIM2()); tag.setTextSize(9.5f); tag.setTypeface(Design.sans());
        tag.setGravity(isBid ? Gravity.START : Gravity.END);
        // NI-1 (RULE 1): the displayed key is truncated — long-press copies the FULL value to the clipboard.
        if (!mine && maker.signerPk != null && !maker.signerPk.isEmpty())
            tag.setOnClickListener(v -> copy(maker.signerPk, Language.msg(l10n_atomix_market_counterparty_copied)));
        half.addView(tag);
        if (!mine && cap > 0) {
            half.setOnClickListener(v -> takeOrderDialog(maker, sym, isBid, lvl.price, cap));
            Design.pressable(half);
        }
        return half;
    }

    private boolean isMine(Order o) {
        return o != null && identity != null && o.commsPublicId != null && o.commsPublicId.equals(identity.publicId());
    }

    /** Pinned top-of-book: the highest bid and lowest ask across all makers (may be two different makers). */
    /** Half of a depth row. leftHalf=true → [size price] right-aligned (price by the centre); else [price size].
     *  big=true bumps the font for the pinned BEST MARKET card. */
    private LinearLayout quoteHalf(String a, String b, int priceColor, boolean best, boolean leftHalf, boolean big) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity((leftHalf ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        String sizeText = leftHalf ? a : b;
        String priceText = leftHalf ? b : a;

        TextView size = new TextView(this);
        Language.setText(size, sizeText); size.setTextColor(Design.DIM()); size.setTextSize(big ? 13f : 12f); size.setTypeface(Design.mono());
        TextView price = new TextView(this);
        Language.setText(price, priceText); price.setTextColor(priceColor); price.setTextSize(big ? 18.5f : 15f);
        price.setTypeface(best ? Design.monoBold() : Design.mono());

        if (leftHalf) { size.setPadding(0, 0, dp(8), 0); h.addView(size); h.addView(price); }
        else { price.setPadding(0, 0, dp(8), 0); h.addView(price); h.addView(size); }
        return h;
    }

    /** Compact mxUSDT size: 300 / 12k / 1.2M. */
    private static String abbrev(double v) {
        if (v <= 0) return "—";
        if (v >= 1_000_000) return trimNum(v / 1_000_000) + "M";
        if (v >= 1_000) return trimNum(v / 1_000) + "k";
        return trimNum(v);
    }
    private static String trimNum(double v) {
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(v).setScale(v < 10 ? 2 : 1, RoundingMode.DOWN).stripTrailingZeros();
        return b.toPlainString();
    }
    /** Price formatting that absorbs float subtraction noise (e.g. a computed spread). */
    private static String fmtPrice(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private LinearLayout walletCard(CharSequence title, CharSequence big, CharSequence sub, int bigColor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.card(this, 16));
        c.setPadding(dp(16), dp(15), dp(16), dp(15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); c.setLayoutParams(lp);
        TextView t = new TextView(this); Language.setText(t, title); t.setTextColor(Design.DIM()); t.setTextSize(12f); t.setTypeface(Design.sans());
        TextView v = new TextView(this); Language.setText(v, big); v.setTextColor(bigColor); v.setTextSize(22f); v.setTag(TAG_BAL);
        v.setTypeface(Design.monoBold()); v.setPadding(0, dp(4), 0, 0);   // balances in tabular mono
        c.addView(t); c.addView(v);
        if (sub != null) {
            TextView s = new TextView(this); Language.setText(s, sub); s.setTextColor(Design.DIM()); s.setTextSize(12.5f); s.setTypeface(Design.sans()); s.setPadding(0, dp(6), 0, 0);
            c.addView(s);
        }
        return c;
    }

    private LinearLayout kv(CharSequence k, CharSequence val) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(4), dp(9), dp(4), dp(3));
        TextView a = new TextView(this); Language.setText(a, k); a.setTextColor(Design.DIM()); a.setTextSize(14f); a.setTypeface(Design.sans());
        TextView v = new TextView(this); Language.setText(v, val); v.setTextColor(Design.TEXT()); v.setTextSize(14f); v.setGravity(Gravity.END); v.setTag(TAG_BAL); v.setTypeface(Design.mono());
        r.addView(a, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private TextView button(CharSequence label) {
        TextView t = new TextView(this);
        Language.setText(t, label);
        t.setTextColor(Design.ON_ACCENT());
        t.setBackground(Design.ripple(Design.gradientCta(this)));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15f); t.setTypeface(Design.sansBold());
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setElevation(dp(3));
        return t;   // ripple background provides the press feedback
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    /** The offline explanation, safe before {@link #node} exists. onCreate calls render() BEFORE it
     *  constructs NodeApi, so every view builder must tolerate a null node — 0.1.62 shipped without this
     *  guard and crashed on launch. With no NodeApi there is no verdict yet, which is exactly the
     *  "nothing has replied" case. */
    private CharSequence nodeOfflineText() {
        if (node != null && node.offline() == NodeApi.Offline.REFUSED) return Language.msg(l10n_atomix_ui_node_not_ready);
        return node != null && node.everReplied() ? Language.msg(l10n_atomix_ui_node_stopped) : Language.msg(l10n_atomix_ui_node_waiting);
    }

    private TextView buildPairingBanner() {
        TextView t = new TextView(this);
        // The node lists companions by their app LABEL — this one registers as "AtomiX", so naming the legacy
        // app here sent users hunting Minima Core → Apps for an entry that isn't there.
        // Text is set per-state in onPaired(); this is only the first paint before any verdict exists.
        Language.setText(t, nodeOfflineText());
        t.setTextColor(Design.ON_ACCENT()); t.setBackgroundColor(Design.ACCENT());
        t.setPadding(dp(16), dp(10), dp(16), dp(10)); t.setTextSize(13f); t.setTypeface(Design.sansBold());
        return t;
    }

    // ---- receive / fund + key export ----

    private void receiveDialog() {
        if (ethAddr == null) { toast(Language.msg(l10n_atomix_ui_wallet_not_ready)); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView addr = new TextView(this);
        Language.setText(addr, ethAddr);
        addr.setTextColor(Design.TEXT()); addr.setTextSize(13f);
        addr.setTypeface(Design.mono());
        addr.setTextIsSelectable(true);
        addr.setGravity(Gravity.CENTER);
        box.addView(addr);

        Bitmap qr = QrUtil.qr(ethAddr, dp(220));
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            int s = dp(220);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(s, s);
            qlp.topMargin = dp(16);
            iv.setLayoutParams(qlp);
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            iv.setBackgroundColor(0xFFFFFFFF);   // white quiet-zone so the dark theme doesn't break scanning
            box.addView(iv);
        }

        TextView note = new TextView(this);
        Language.setText(note, Language.msg(l10n_atomix_ui_fund_help, net.label));
        note.setTextColor(Design.DIM2()); note.setTextSize(12f); note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(14), 0, 0);
        box.addView(note);

        dialog()
                .setTitle(Language.msg(l10n_atomix_ui_fund_title, net.label))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_wallet_copy_address), (d, w) -> copy(ethAddr, Language.msg(l10n_atomix_ui_eth_copied)))
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .show();
    }

    // ---- manual Send (parity twin of the MDS wallet's Send — user decision 2026-07-19: both apps have it) ----
    private void sendDialog() {
        if (!wallet.ready()) { toast(Language.msg(l10n_atomix_ui_wallet_not_ready)); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        final boolean[] ethSel = { true };
        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        final TextView ethPill = Design.pill(this, "ETH", Design.ACCENT(), Design.ON_ACCENT());
        final TextView usdtPill = Design.pill(this, "USDT", Design.SURFACE2(), Design.DIM());
        Runnable restyle = () -> {
            ((android.graphics.drawable.GradientDrawable) ethPill.getBackground()).setColor(ethSel[0] ? Design.ACCENT() : Design.SURFACE2());
            ethPill.setTextColor(ethSel[0] ? Design.ON_ACCENT() : Design.DIM());
            ((android.graphics.drawable.GradientDrawable) usdtPill.getBackground()).setColor(!ethSel[0] ? Design.ACCENT() : Design.SURFACE2());
            usdtPill.setTextColor(!ethSel[0] ? Design.ON_ACCENT() : Design.DIM());
        };
        ethPill.setOnClickListener(v -> { ethSel[0] = true; restyle.run(); });
        usdtPill.setOnClickListener(v -> { ethSel[0] = false; restyle.run(); });
        LinearLayout.LayoutParams tgap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tgap.rightMargin = dp(8);
        toggles.addView(ethPill, tgap);
        toggles.addView(usdtPill);
        box.addView(toggles);

        final EditText toE = new EditText(this);
        Language.hint(toE, Language.msg(l10n_atomix_ui_recipient)); toE.setTextColor(Design.TEXT()); toE.setTextSize(13f); toE.setTypeface(Design.mono());
        toE.setSingleLine(true);
        box.addView(toE);

        LinearLayout amtRow = new LinearLayout(this);
        amtRow.setOrientation(LinearLayout.HORIZONTAL); amtRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText amtE = new EditText(this);
        decimalInput(amtE);
        Language.hint(amtE, "0.00"); amtE.setTextColor(Design.TEXT()); amtE.setTextSize(17f); amtE.setTypeface(Design.mono());
        TextView maxB = Design.pill(this, Language.msg(l10n_wallet_max), Design.SURFACE2(), Design.TEXT());
        maxB.setOnClickListener(v -> io.execute(() -> {
            try {
                if (ethSel[0]) {
                    BigInteger gp = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new org.json.JSONArray()));
                    BigInteger bf = rpc.baseFeePerGasOrZero();   // MA-6: reserve at the same effective price EthTx broadcasts at
                    final String m = EthWallet.format(EthSend.maxEthSendWei(ethWeiRaw, gp, bf), 18, 8);
                    ui.post(() -> Language.setText(amtE, m));
                } else {
                    final String m = EthWallet.format(usdtRawBal, net.tokens[0].decimals, 6);
                    ui.post(() -> Language.setText(amtE, m));
                }
            } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_atomix_ui_gas_error))); }
        }));
        amtRow.addView(amtE, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        amtRow.addView(maxB);
        box.addView(amtRow);

        TextView warn = new TextView(this);
        Language.setText(warn, Language.msg(l10n_atomix_ui_irreversible));
        warn.setTextColor(Design.DIM()); warn.setTextSize(12f); warn.setPadding(0, dp(8), 0, 0);
        box.addView(warn);

        // Review handler attached post-show so an invalid form shows its error WITHOUT dismissing the dialog.
        final AlertDialog d = dialog()
                .setTitle(Language.msg(l10n_atomix_ui_send_title))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_eth_wallet_review), null)
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final boolean eth = ethSel[0];
            final String to = toE.getText().toString().trim(), amt = amtE.getText().toString().trim();
            io.execute(() -> {
                try {
                    BigInteger gp = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new org.json.JSONArray()));
                    BigInteger bf = rpc.baseFeePerGasOrZero();   // MA-6: same effective price as the broadcast, so validation can't under-reserve
                    final String err = EthSend.checkSend(eth, to, amt, ethWeiRaw, usdtRawBal, net.tokens[0].decimals, gp, bf);
                    if (err != null) { ui.post(() -> toast(err)); return; }
                    final String fee = EthWallet.format(
                            EthSend.gasReserveWei(gp, bf, eth ? EthSend.GAS_ETH : EthSend.GAS_ERC20), 18, 8);
                    ui.post(() -> { d.dismiss(); sendReviewDialog(eth, to, amt, fee); });
                } catch (Exception e) { ui.post(() -> toast(Language.msg(l10n_atomix_ui_gas_error))); }
            });
        });
    }

    private void sendReviewDialog(boolean eth, String to, String amt, String feeEth) {
        dialog()
                .setTitle(Language.msg(l10n_atomix_ui_review_title, eth ? "ETH" : "USDT"))
                .setMessage(Language.msg(l10n_atomix_ui_review_body, amt, eth ? "ETH" : "USDT", to, feeEth))
                .setPositiveButton(Language.msg(l10n_atomix_ui_send_now), (dd, w) -> io.execute(() -> {
                    try {
                        String tx = eth
                                ? EthSend.sendEth(rpc, wallet.creds(), net.chainId, to, EthSend.parseUnits(amt, 18))
                                : EthSend.sendErc20(rpc, wallet.creds(), net.chainId, net.tokens[0].address, to,
                                        EthSend.parseUnits(amt, net.tokens[0].decimals));
                        SwapLog.d("manual send " + (eth ? "ETH" : "USDT") + " " + amt + " tx=" + tx);
                        // NI-1 (RULE 1): a tx hash in a transient toast is otherwise unrecoverable — copy the FULL
                        // hash to the clipboard so the user can look up their own send on a block explorer.
                        ui.post(() -> { copy(tx, Language.msg(l10n_atomix_ui_tx_copied)); fetchEthBalances(true); });
                    } catch (Exception e) {
                        ui.post(() -> toast(Language.msg(l10n_atomix_ui_send_failed, e.getMessage())));
                    }
                }))
                .setNegativeButton(Language.msg(l10n_explorer_back), (dd, w) -> sendDialog())
                .show();
    }

    private void exportKeyDialog() {
        if (!wallet.ready()) { toast(Language.msg(l10n_atomix_ui_wallet_not_ready)); return; }
        dialog()
                .setTitle(Language.msg(l10n_atomix_ui_export_title))
                .setMessage(Language.msg(l10n_atomix_ui_export_warning))
                .setPositiveButton(Language.msg(l10n_atomix_ui_reveal), (d, w) -> revealKeyDialog())
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void revealKeyDialog() {
        final String pk = wallet.privateKeyHex();
        if (pk == null) { toast(Language.msg(l10n_atomix_ui_wallet_not_ready)); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView warn = new TextView(this);
        Language.setText(warn, Language.msg(l10n_atomix_ui_secret));
        warn.setTextColor(Design.RED()); warn.setTextSize(12.5f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);

        TextView key = new TextView(this);
        Language.setText(key, pk);
        key.setTextColor(Design.TEXT()); key.setTextSize(13f);
        key.setTypeface(Design.mono());
        key.setTextIsSelectable(true);
        box.addView(key);

        AlertDialog secret = dialog()
                .setTitle(Language.msg(l10n_atomix_ui_private_key))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_atomix_ui_copy_key), (d, w) -> copyPrivateKey(pk))
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .create();
        if (secret.getWindow() != null) secret.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        secret.show();
    }

    private void copyPrivateKey(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("AtomiX", value);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast(Language.msg(l10n_atomix_ui_key_copied));
    }

    private String shortAddr(String a) {
        if (a == null || a.length() < 12) return a;
        return a.substring(0, 8) + "…" + a.substring(a.length() - 6);
    }

    private void copy(String text, CharSequence toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("AtomiX", text)); toast(toast); }   // label shows in the Android 13+ clipboard preview
    }

    private static String trim(double v) { return Util.tidyAmount(BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()); }
    /** Like trim() but first rounds away binary float noise (0.00597999999… → 0.00598) — for computed ladder fields. */
    private static String trimSig(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return Util.tidyAmount(new BigDecimal(v, new java.math.MathContext(10)).stripTrailingZeros().toPlainString());
    }
    private static double parseD(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    // ============================================================ OTC deals tab

    private void rememberOtcDraft(EditText field, boolean sell) {
        field.addTextChangedListener(new SimpleWatcher(() -> {
            if (sell) otcSellDraft = field.getText().toString(); else otcBuyDraft = field.getText().toString();
        }));
        field.setOnFocusChangeListener((view, focused) -> {
            otcInputFocused = focused;
            if (!focused) ui.postDelayed(this::render, 250);
        });
    }
    private void finishOtcEditing(View container) {
        container.setFocusableInTouchMode(true); container.requestFocus(); otcInputFocused = false;
    }

    private void renderOtcTab(LinearLayout col) {
        TextView intro = new TextView(this);
        Language.setText(intro, Language.msg(l10n_atomix_otc_intro));
        intro.setTextColor(Design.DIM()); intro.setTextSize(12f); intro.setTypeface(Design.sans());
        intro.setLineSpacing(dp(2), 1f); intro.setPadding(0, 0, 0, dp(10));
        col.addView(intro);
        renderOtcAvailability(col);
        renderOtcProviders(col);
        renderOtcDeals(col);
    }

    private LinearLayout otcCardBox(LinearLayout col) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Design.card(this, 18));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10); box.setLayoutParams(lp);
        col.addView(box);
        return box;
    }

    private void otcSectionHead(LinearLayout col, CharSequence s) {
        TextView t = new TextView(this);
        Language.setText(t, s); t.setTextColor(Design.DIM()); t.setTextSize(12f); t.setTypeface(Design.sansBold()); t.setLetterSpacing(0.05f);
        t.setPadding(0, dp(8), 0, dp(6)); col.addView(t);
    }

    private static double otcNumber(String value, double fallback) {
        double parsed = parseD(value, fallback);
        return Double.isFinite(parsed) ? parsed : fallback;
    }

    private void renderOtcAvailability(LinearLayout col) {
        otcSectionHead(col, Language.msg(l10n_atomix_otc_availability));
        LinearLayout box = otcCardBox(col);
        final boolean enabled = prefs.getBoolean("otc_enable", false);

        box.addView(fieldLabel(Language.msg(l10n_atomix_otc_sell_limit, ccy())));
        final EditText sellE = new EditText(this); decimalInput(sellE);
        Language.hint(sellE, Language.msg(l10n_atomix_otc_max_hint, ccy())); sellE.setHintTextColor(Design.DIM2());
        if (otcSellDraft == null) otcSellDraft = prefs.getString("otc_sell_size", "");
        Language.setText(sellE, otcSellDraft); rememberOtcDraft(sellE, true); sellE.setTextColor(Design.TEXT()); sellE.setTextSize(15f); sellE.setTypeface(Design.mono());
        box.addView(sellE);

        box.addView(fieldLabel(Language.msg(l10n_atomix_otc_buy_limit, ccy())));
        final EditText buyE = new EditText(this); decimalInput(buyE);
        Language.hint(buyE, Language.msg(l10n_atomix_otc_max_hint, ccy())); buyE.setHintTextColor(Design.DIM2());
        if (otcBuyDraft == null) otcBuyDraft = prefs.getString("otc_buy_size", "");
        Language.setText(buyE, otcBuyDraft); rememberOtcDraft(buyE, false); buyE.setTextColor(Design.TEXT()); buyE.setTextSize(15f); buyE.setTypeface(Design.mono());
        box.addView(buyE);

        TextView st = new TextView(this);
        Language.setText(st, enabled ? Language.msg(l10n_atomix_otc_live) : Language.msg(l10n_atomix_otc_off)); st.setTextColor(enabled ? Design.IN() : Design.DIM());
        st.setTextSize(12f); st.setTypeface(Design.sans()); st.setPadding(0, dp(8), 0, dp(8)); box.addView(st);

        LinearLayout btns = new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView pubBtn = Design.pill(this, enabled ? Language.msg(l10n_atomix_otc_update) : Language.msg(l10n_atomix_otc_go_live), Design.ACCENT(), Design.ON_ACCENT());
        pubBtn.setOnClickListener(v -> {
            double sell = otcNumber(sellE.length() == 0 ? "0" : sellE.getText().toString(), Double.NaN), buy = otcNumber(buyE.length() == 0 ? "0" : buyE.getText().toString(), Double.NaN);
            if (!Double.isFinite(sell) || !Double.isFinite(buy) || sell < 0 || buy < 0 || (sell <= 0 && buy <= 0)) { toast(Language.msg(l10n_atomix_otc_enter_size)); return; }
            finishOtcEditing(box);
            prefs.edit().putString("otc_sell_size", sell > 0 ? trim(sell) : "").putString("otc_buy_size", buy > 0 ? trim(buy) : "")
                    .putBoolean("otc_enable", true).putBoolean("otc_auto", true).apply();
            publishOtcOffer(sell, buy);
        });
        Design.pressable(pubBtn); pubBtn.setMinimumHeight(dp(48)); btns.addView(pubBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (enabled) {
            TextView wd = Design.pill(this, Language.msg(l10n_atomix_otc_withdraw), Design.SURFACE2(), Design.TEXT());
            wd.setOnClickListener(v -> { finishOtcEditing(box); withdrawOtcOffer(); }); Design.pressable(wd);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); wd.setMinimumHeight(dp(48)); wlp.leftMargin = dp(8);
            btns.addView(wd, wlp);
        }
        box.addView(btns);
    }

    private void renderOtcProviders(LinearLayout col) {
        otcSectionHead(col, Language.msg(l10n_atomix_otc_providers));
        boolean any = false;
        for (final OtcOffer o : otcBook.values()) {
            if (!o.hasLiquidity() || o.commsPublicId == null || o.commsPublicId.isEmpty()) continue;
            any = true;
            LinearLayout box = otcCardBox(col);
            Language.TextBuilder sides = new Language.TextBuilder();
            if (o.sells()) sides.append(Language.msg(l10n_atomix_otc_sells, trimSig(o.sellSize), ccy()));
            if (o.buys()) { if (o.sells()) sides.append("   ·   "); sides.append(Language.msg(l10n_atomix_otc_buys, trimSig(o.buySize), ccy())); }
            TextView t = new TextView(this);
            Language.setText(t, sides.build()); t.setTextColor(Design.TEXT()); t.setTextSize(14.5f); t.setTypeface(Design.sansBold()); box.addView(t);
            TextView who = new TextView(this); Language.setText(who, Language.msg(l10n_atomix_otc_provider, shortAddr(o.signerPk)));
            who.setTextColor(Design.DIM()); who.setTextSize(12f); who.setTypeface(Design.sans()); who.setPadding(0, dp(4), 0, dp(8)); box.addView(who);
            // NI-1 (RULE 1): the LP key is shown truncated — long-press copies the FULL value.
            if (o.signerPk != null && !o.signerPk.isEmpty())
                who.setOnClickListener(v -> copy(o.signerPk, Language.msg(l10n_atomix_otc_key_copied)));
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            if (o.sells()) {   // LP sells → I can BUY mxUSDT from them
                TextView b = Design.pill(this, Language.msg(l10n_atomix_otc_buy, ccy()), Design.ACCENT(), Design.ON_ACCENT());
                b.setOnClickListener(v -> otcProposeDialog(o, OtcOffer.LP_SELLS_MINIMA)); Design.pressable(b);
                LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); m.rightMargin = dp(8);
                row.addView(b, m);
            }
            if (o.buys()) {    // LP buys → I can SELL mxUSDT to them
                TextView s = Design.pill(this, Language.msg(l10n_atomix_otc_sell, ccy()), Design.SURFACE2(), Design.TEXT());
                s.setOnClickListener(v -> otcProposeDialog(o, OtcOffer.LP_BUYS_MINIMA)); Design.pressable(s);
                row.addView(s);
            }
            box.addView(row);
        }
        if (!any) { TextView none = new TextView(this); Language.setText(none, Language.msg(l10n_atomix_otc_no_providers)); none.setTextColor(Design.DIM()); none.setTextSize(13f); none.setTypeface(Design.sans()); none.setPadding(0, 0, 0, dp(10)); col.addView(none); }
    }

    private CharSequence otcCurrency(OtcDb.Deal deal) {
        return deal.market() == null ? Language.msg(l10n_atomix_otc_unknown_market) : deal.market().coinLabel;
    }

    private void renderOtcDeals(LinearLayout col) {
        otcSectionHead(col, Language.msg(l10n_atomix_otc_deals));
        java.util.List<OtcDb.Deal> deals = otcDb == null ? new java.util.ArrayList<>() : otcDb.allDeals();
        boolean any = false;
        for (final OtcDb.Deal d : deals) {
            any = true;
            LinearLayout box = otcCardBox(col);
            boolean buying = OtcOffer.LP_SELLS_MINIMA.equals(d.side)
                    ? OtcDb.ROLE_INSTIGATOR.equals(d.role) : !OtcDb.ROLE_INSTIGATOR.equals(d.role);
            TextView t = new TextView(this);
            Language.setText(t, buying ? Language.msg(l10n_atomix_otc_buy_terms, d.amount, otcCurrency(d), d.price) : Language.msg(l10n_atomix_otc_sell_terms, d.amount, otcCurrency(d), d.price));
            t.setTextColor(Design.TEXT()); t.setTextSize(15f); t.setTypeface(Design.sansBold()); box.addView(t);
            TextView st = new TextView(this);
            Language.setText(st, otcStatusText(d)); st.setTextColor(otcStatusColor(d)); st.setTextSize(12.5f); st.setTypeface(Design.sans()); st.setPadding(0, dp(4), 0, dp(2)); box.addView(st);
            if (!d.inActiveMarket()) {
                TextView marketHint = fieldLabel(d.market() == null ? Language.msg(l10n_atomix_otc_unknown_market_help)
                        : Language.msg(l10n_atomix_otc_switch_market, d.market().coinLabel));
                box.addView(marketHint);
            }
            if (d.inActiveMarket() && otc != null && OtcDb.TURN_ME.equals(d.whoseTurn) && (OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status))) {
                LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(8), 0, 0);
                TextView acc = Design.pill(this, Language.msg(l10n_atomix_otc_accept), Design.IN(), Design.ON_ACCENT());
                acc.setOnClickListener(v -> {
                    CharSequence fundErr = otcCheckFunds(d.role, d.side, d.amount, d.price);
                    if (fundErr != null) { toast(fundErr); return; }
                    otc.accept(d, otcRes(Language.msg(l10n_atomix_otc_accepted)));
                });
                TextView cnt = Design.pill(this, Language.msg(l10n_atomix_otc_counter), Design.SURFACE2(), Design.TEXT());
                cnt.setOnClickListener(v -> otcCounterDialog(d));
                TextView rej = Design.pill(this, Language.msg(l10n_atomix_otc_reject), Design.SURFACE2(), Design.RED());
                rej.setOnClickListener(v -> otc.reject(d, otcRes(Language.msg(l10n_atomix_otc_rejected))));
                Design.pressable(acc); Design.pressable(cnt); Design.pressable(rej);
                LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); m.rightMargin = dp(8);
                for (TextView action : new TextView[]{acc, cnt, rej}) {
                    action.setMinimumHeight(dp(48));
                    row.addView(action, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                box.addView(row);
            }
        }
        if (!any) { TextView none = new TextView(this); Language.setText(none, Language.msg(l10n_atomix_otc_no_deals)); none.setTextColor(Design.DIM()); none.setTextSize(13f); none.setTypeface(Design.sans()); col.addView(none); }
    }

    private OtcController.SendResult otcRes(CharSequence okMsg) {
        return new OtcController.SendResult() {
            @Override public void ok() { ui.post(() -> { toast(okMsg); if (currentTab == TAB_OTC && !modalOpen) render(); }); }
            @Override public void err(String m) { ui.post(() -> toast(Language.join("OTC: ", AtomixMessages.message(m)))); }
        };
    }

    private CharSequence otcStatusText(OtcDb.Deal d) {
        if (OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status))
            return OtcDb.TURN_ME.equals(d.whoseTurn) ? Language.msg(l10n_atomix_otc_your_move) : Language.msg(l10n_atomix_otc_waiting);
        if (OtcDb.ST_AGREED.equals(d.status)) return Language.msg(l10n_atomix_otc_agreed);
        if (OtcDb.ST_EXECUTING.equals(d.status)) return Language.msg(l10n_atomix_otc_executing);
        if (OtcDb.ST_COMPLETE.equals(d.status)) return Language.msg(l10n_atomix_otc_complete);
        if (OtcDb.ST_REJECTED.equals(d.status)) return Language.msg(l10n_atomix_otc_rejected);
        if (OtcDb.ST_EXPIRED.equals(d.status)) return Language.msg(l10n_atomix_otc_expired);
        return d.status;
    }
    private int otcStatusColor(OtcDb.Deal d) {
        if (OtcDb.ST_COMPLETE.equals(d.status)) return Design.IN();
        if (OtcDb.ST_REJECTED.equals(d.status) || OtcDb.ST_EXPIRED.equals(d.status)) return Design.RED();
        return Design.DIM();
    }

    /** Pre-check that I can fund the leg I'll lock for an OTC deal (my role + the deal's side), from cached balances.
     *  Returns null if OK (or the balance is unknown), else a user-facing shortfall message. The on-chain lock is the
     *  authoritative backstop — this is a fast UX guard so I never offer/accept terms I can't fund. */
    private CharSequence otcCheckFunds(String role, String side, String amount, String price) {
        if (!OtcController.validTerms(amount, price)) return Language.msg(l10n_atomix_otc_enter_terms);
        double amt = otcNumber(amount, 0), pr = otcNumber(price, 0);
        boolean iLockMinima = (OtcDb.ROLE_INSTIGATOR.equals(role) && OtcOffer.LP_BUYS_MINIMA.equals(side))
                || (OtcDb.ROLE_LP.equals(role) && OtcOffer.LP_SELLS_MINIMA.equals(side));
        if (iLockMinima) {                                               // I'd lock mxUSDT = amount
            double have = otcNumber(minimaBal, -1);                         // "sendable" (tradeable) mxUSDT
            if (have < 0) return null;                                   // unknown → don't block; execute is the gate
            if (amt > have + 1e-9) return Language.msg(l10n_atomix_otc_funds, ccy(), trim(amt), trim(have));
        } else {                                                        // I'd lock USDT = amount × price
            String u = tokenBals.get("USDT");
            if (u == null) return null;                                 // not loaded → don't block
            double have = otcNumber(Util.tidyAmount(u), -1), need = amt * pr;
            if (have < 0) return null;
            if (need > have + 1e-9) return Language.msg(l10n_atomix_otc_funds, "USDT", trim(need), trim(have));
        }
        return null;
    }

    private void otcProposeDialog(final OtcOffer lp, final String dealSide) {
        final boolean iBuy = OtcOffer.LP_SELLS_MINIMA.equals(dealSide);
        final double cap = lp.capFor(dealSide);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(fieldLabel(Language.msg(l10n_atomix_otc_capped_amount, ccy(), trimSig(cap))));
        final EditText amtE = new EditText(this); decimalInput(amtE); amtE.setTextColor(Design.TEXT()); amtE.setTextSize(16f); amtE.setTypeface(Design.mono()); box.addView(amtE);
        box.addView(fieldLabel(Language.msg(l10n_atomix_otc_price, ccy())));
        final EditText prE = new EditText(this); decimalInput(prE); prE.setTextColor(Design.TEXT()); prE.setTextSize(16f); prE.setTypeface(Design.mono()); box.addView(prE);
        modalOpen = true;
        dialog().setTitle(iBuy ? Language.msg(l10n_atomix_otc_buy, ccy()) : Language.msg(l10n_atomix_otc_sell, ccy()))
                .setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_atomix_otc_send_offer), (dg, w) -> {
                    double a = otcNumber(amtE.getText().toString(), 0), p = otcNumber(prE.getText().toString(), 0);
                    if (a <= 0 || p <= 0 || !Double.isFinite(a) || !Double.isFinite(p) || !OtcController.validTerms(trim(a), trimSig(p))) { toast(Language.msg(l10n_atomix_otc_enter_terms)); return; }
                    if (a > cap + 1e-9) { toast(Language.msg(l10n_atomix_otc_above_limit)); return; }
                    CharSequence fundErr = otcCheckFunds(OtcDb.ROLE_INSTIGATOR, dealSide, trim(a), trimSig(p));
                    if (fundErr != null) { toast(fundErr); return; }
                    if (otc != null) otc.propose(lp, dealSide, trim(a), trimSig(p), otcRes(Language.msg(l10n_atomix_otc_offer_sent)));
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(dg -> { modalOpen = false; render(); })
                .show();
    }

    private void otcCounterDialog(final OtcDb.Deal d) {
        if (!d.inActiveMarket()) { toast(Language.msg(l10n_atomix_otc_market_unavailable)); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(fieldLabel(Language.msg(l10n_atomix_otc_amount, ccy())));
        final EditText amtE = new EditText(this); decimalInput(amtE); Language.setText(amtE, d.amount); amtE.setTextColor(Design.TEXT()); amtE.setTextSize(16f); amtE.setTypeface(Design.mono()); box.addView(amtE);
        box.addView(fieldLabel(Language.msg(l10n_atomix_otc_price, ccy())));
        final EditText prE = new EditText(this); decimalInput(prE); Language.setText(prE, d.price); prE.setTextColor(Design.TEXT()); prE.setTextSize(16f); prE.setTypeface(Design.mono()); box.addView(prE);
        modalOpen = true;
        dialog().setTitle(Language.msg(l10n_atomix_otc_counter_offer)).setView(wrapScroll(box))
                .setPositiveButton(Language.msg(l10n_atomix_otc_send_counter), (dg, w) -> {
                    double a = otcNumber(amtE.getText().toString(), 0), p = otcNumber(prE.getText().toString(), 0);
                    if (a <= 0 || p <= 0 || !Double.isFinite(a) || !Double.isFinite(p) || !OtcController.validTerms(trim(a), trimSig(p))) { toast(Language.msg(l10n_atomix_otc_enter_terms)); return; }
                    CharSequence fundErr = otcCheckFunds(d.role, d.side, trim(a), trimSig(p));
                    if (fundErr != null) { toast(fundErr); return; }
                    if (otc != null) otc.counter(d, trim(a), trimSig(p), otcRes(Language.msg(l10n_atomix_otc_counter_sent)));
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .setOnDismissListener(dg -> { modalOpen = false; render(); })
                .show();
    }

    private void publishOtcOffer(double sellSize, double buySize) {
        if (!Double.isFinite(sellSize) || !Double.isFinite(buySize) || sellSize < 0 || buySize < 0) { toast(Language.msg(l10n_atomix_otc_enter_size)); return; }
        if (IdentityWatch.halted()) { toast(Language.msg(l10n_atomix_otc_halted)); return; }
        if (identity == null || myMinimaPk == null || !wallet.ready()) { toast(Language.msg(l10n_atomix_otc_connecting)); return; }
        if (!PublishGate.tryAcquire(PublishGate.OTC)) {
            // A keep-alive publish (old sizes) is in flight. Zero the ok-stamp AND bump the gen: the
            // in-flight publish's onSent is gen-checked, so it can no longer re-stamp over our zero —
            // the next 90s tick then republishes with the user's NEW sizes.
            PublishGate.bumpGen();
            prefs.edit().putLong("otc_last_publish_ok", 0).apply();
            SwapLog.d("fg OTC publish deferred — gate busy, stamp zeroed + gen bumped");
            toast(Language.msg(l10n_atomix_otc_busy));
            return;
        }
        final long gen = PublishGate.gen();
        OtcOffer o = new OtcOffer();
        o.sellSize = Math.max(0, sellSize); o.buySize = Math.max(0, buySize); o.enable = true;
        o.minimaPublicKey = myMinimaPk; o.ethAddress = ethAddr == null ? "" : ethAddr; o.commsPublicId = identity.publicId();
        OtcBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                PublishGate.release(PublishGate.OTC);
                if (gen != PublishGate.gen()) { SwapLog.d("fg OTC publish superseded — stamp skipped"); return; }
                prefs.edit().putLong("otc_last_publish_ok", System.currentTimeMillis()).apply();
                SwapLog.d("fg OTC publish OK " + txpowid);
                ui.post(() -> { toast(Language.msg(l10n_atomix_otc_published)); render(); });
            }
            @Override public void onFailed(String message) {
                PublishGate.release(PublishGate.OTC);
                SwapLog.w("fg OTC publish FAIL: " + message);
                ui.post(() -> toast(Language.msg(l10n_atomix_otc_publish_failed, message)));
            }
        });
    }

    private void withdrawOtcOffer() {
        prefs.edit().putBoolean("otc_enable", false).putBoolean("otc_auto", false).apply();
        if (identity == null || myMinimaPk == null) { render(); return; }
        OtcOffer o = new OtcOffer();
        o.sellSize = 0; o.buySize = 0; o.enable = false;
        o.minimaPublicKey = myMinimaPk; o.ethAddress = ethAddr == null ? "" : ethAddr; o.commsPublicId = identity.publicId();
        OtcBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { ui.post(() -> { toast(Language.msg(l10n_atomix_otc_withdrawn)); render(); }); }
            @Override public void onFailed(String message) { ui.post(() -> render()); }
        });
    }

    /** Keep my OTC availability live (it ages out of the board after ~1h) — republish on the same cadence as
     *  the order. Runs off {@code otc_last_publish_ok} (stamped only on a confirmed publish inside
     *  publishOtcOffer) so a failed attempt retries on the next 90s tick instead of waiting 30 min. */
    private void maybeRepublishOtc() {
        if (IdentityWatch.halted()) return;
        if (identity == null || myMinimaPk == null || !wallet.ready()) return;
        if (!prefs.getBoolean("otc_auto", false)) return;
        if (System.currentTimeMillis() - prefs.getLong("otc_last_publish_ok", 0) < REPUBLISH_INTERVAL_MS) return;
        publishOtcOffer(otcNumber(prefs.getString("otc_sell_size", "0"), 0), otcNumber(prefs.getString("otc_buy_size", "0"), 0));
    }

    private int dp(int v) { return Design.dp(this, v); }
    // ===================== Export trading history to CSV (maker + taker) =====================
    private void exportSwapsCsv() {
        if (db == null || db.allSwaps().isEmpty()) { toast(Language.msg(l10n_atomix_history_no_export)); return; }
        csvLauncher.launch("atomix-trades.csv");
    }

    /** One CSV of ALL my swaps, each row tagged Maker (RESPONDER) / Taker (INITIATOR), with the on-chain leg tx ids
     *  joined from the events log. Same columns/format as the desktop + MDS peers. Runs off the UI thread. */
    private void doExportCsv(Uri uri) {
        new Thread(() -> {
            try {
                java.util.List<SwapDb.Swap> all = db.allSwaps();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                // Currency is APPENDED, never inserted, and no existing header is renamed: the reconciliation
                // ledger (tools/dexHistory/atomix_ledger.py) reads these columns BY NAME, "Price (USDT/MINIMA)"
                // included. Renaming that header to something currency-neutral would break a working ledger for
                // a cosmetic gain — the new Currency column already says which coin the price is denominated in.
                StringBuilder sb = new StringBuilder(
                    "Date,Role,Direction,Sold Amount,Sold Token,Bought Amount,Bought Token,Price (USDT/MINIMA),Counterparty,Status,Contract Id,Minima Tx,Eth Tx,Currency\r\n");
                for (SwapDb.Swap s : all) {
                    java.util.List<SwapDb.Event> ev = db.getEvents(s.hash);
                    String date = s.created > 0 ? fmt.format(new Date(s.created)) : "";
                    String[] cells = {
                        date, roleLabel(s.role), dirLabel(s),
                        s.sellAmount, tokLabel(s.sellToken), s.buyAmount, tokLabel(s.buyToken), priceUsdtPerMinima(s),
                        s.counterparty, s.status == null ? "" : s.status.toLowerCase(Locale.ENGLISH),
                        s.contractId, pickTx(ev, true), pickTx(ev, false), ccyLabel(s)
                    };
                    for (int i = 0; i < cells.length; i++) { if (i > 0) sb.append(','); sb.append(csvCell(cells[i])); }
                    sb.append("\r\n");
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                final int n = all.size();
                ui.post(() -> toast(Language.msg(l10n_atomix_history_exported, n)));
            } catch (Exception e) {
                ui.post(() -> toast(Language.msg(l10n_atomix_history_export_failed, e.getMessage() == null ? Language.msg(l10n_atomix_history_error) : e.getMessage())));
            }
        }).start();
    }

    private CharSequence historyRole(String role) {
        if ("RESPONDER".equals(role)) return Language.msg(l10n_atomix_history_maker);
        if ("INITIATOR".equals(role)) return Language.msg(l10n_atomix_history_taker);
        return role == null ? "" : role;
    }

    private static String roleLabel(String r) { return "RESPONDER".equals(r) ? "Maker" : ("INITIATOR".equals(r) ? "Taker" : (r == null ? "" : r)); }
    /** The market a recorded swap traded in, for the CSV's Currency column. "" when unattributable. */
    private static String ccyLabel(SwapDb.Swap s) {
        TradingContext c = TradingContext.forSwap(s.sellToken, s.buyToken);
        return c == null ? "" : c.coinLabel;
    }

    /** "Sell <coin>" / "Buy <coin>" where <coin> is the row's OWN Minima-leg coin. The direction constants are
     *  shared by both markets (MINIMA_TO_ERC20 is used for mxUSDT swaps too), so a hard-coded "MINIMA" here
     *  labelled every dollar swap as a Minima one. */
    private static String dirLabel(SwapDb.Swap s) {
        String d = s.direction;
        TradingContext c = TradingContext.forSwap(s.sellToken, s.buyToken);
        String coin = c == null ? "MINIMA" : c.coinLabel;
        if ("MINIMA_TO_ERC20".equals(d)) return "Sell " + coin;
        if ("ERC20_TO_MINIMA".equals(d)) return "Buy " + coin;
        return d == null ? "" : d;
    }
    /** Normalise a stored leg label for the CSV: a raw tokenid becomes its coin label, an already-labelled
     *  leg (including the legacy mxUSDT spelling) passes through its canonical TradingContext label, and an
     *  Ethereum symbol ("USDT", "WETH") is left exactly as written. */
    private static String tokLabel(String t) {
        if (t == null) return "";
        String l = t.toLowerCase(Locale.ENGLISH);
        if (l.equals("0x00")) return "MINIMA";
        if (l.contains("7d39745")) return TradingContext.MXUSDT.coinLabel;
        TradingContext c = TradingContext.forCoinLabel(t);
        return c == null ? t : c.coinLabel;
    }
    /** USDT per MINIMA, comparable across both directions; blank if a leg amount is missing/zero. */
    private static String priceUsdtPerMinima(SwapDb.Swap s) {
        double sell, buy;
        try { sell = Double.parseDouble(s.sellAmount); buy = Double.parseDouble(s.buyAmount); } catch (Exception e) { return ""; }
        if (!(sell > 0) || !(buy > 0)) return "";
        double p = "MINIMA_TO_ERC20".equals(s.direction) ? buy / sell : ("ERC20_TO_MINIMA".equals(s.direction) ? sell / buy : 0);
        if (!(p > 0)) return "";
        return new java.math.BigDecimal(p).round(new java.math.MathContext(8)).stripTrailingZeros().toPlainString();
    }
    private static boolean isTxId(String v) { return v != null && v.matches("0x[0-9a-fA-F]{16,}"); }
    /** Join the real on-chain tx ids for one leg: minima leg logs token 'minima'; ETH leg logs 'ETH:<addr>'.
     *  settle logs the ETH collect/expire with note "confirmed on-chain" (not a hash) — filtered out by isTxId. */
    private static String pickTx(java.util.List<SwapDb.Event> ev, boolean minimaLeg) {
        java.util.List<String> seen = new ArrayList<>();
        for (SwapDb.Event e : ev) {
            boolean match = minimaLeg ? "minima".equals(e.token) : (e.token != null && e.token.startsWith("ETH"));
            if (match && isTxId(e.note) && !seen.contains(e.note)) seen.add(e.note);
        }
        return android.text.TextUtils.join(";", seen);
    }
    /** CSV cell: always double-quoted; neutralize spreadsheet formula-injection (leading = + - @ TAB CR → prefix '). */
    private static String csvCell(String s) {
        if (s == null) s = "";
        if (s.length() > 0 && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---- notifications ----

    private final SwapEngine.Notifier notifier = new SwapEngine.Notifier() {
        @Override public void notify(String title, String body) { ui.post(() -> postNotification(title, body)); }
        @Override public void onSwapsChanged() { ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            // Drop the sticky action line ("Buy started — maker notified" / "Sweep done…") once everything has settled.
            if (sweepRun == null && activeSwapCount() == 0) orderStatus = null;
            render();
            if (!checkForNewCompletion()) refreshBalances(false);   // completion → pulse+reload; other moves → silent refresh
        }); }
    };

    private void postNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toast(Language.join(AtomixMessages.message(title), " — ", AtomixMessages.message(body)));
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationCompat.Builder n = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(AtomixMessages.message(title))
                .setContentText(AtomixMessages.message(body))
                // BigTextStyle so a full identifier (e.g. the ETH address in "Add ETH for gas") survives the
                // shade instead of being ellipsised by the system — RULE 1. SwapService.alert already does this.
                .setStyle(new NotificationCompat.BigTextStyle().bigText(AtomixMessages.message(body)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify("pandamonium.atomix.ui", notifId.incrementAndGet(), n.build());
    }

    private void ensureChannel() { AtomixMessages.ensureChannels(this); }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
    }

    /** A no-frills TextWatcher that just runs a callback on every change. */
    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(android.text.Editable s) { r.run(); }
    }
}
