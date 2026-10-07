package com.eurobuddha.futurecashnext;

import android.app.AlertDialog;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The four tabs: Guardian (health + rescue setup + Harden), Stakes (lock and collect),
 * Activity (what the guardian did), Help.
 *
 * <p>All node and network work happens on a single worker thread — {@link Node} blocks, and the IPC
 * reply arrives on the main thread, so calling it from the UI thread would deadlock until timeout.
 */
public class MainActivity extends AppCompatActivity {

    /** The service stands down while this is visible so both don't post for the same coin. */
    public static volatile boolean FOREGROUND = false;

    private LinearLayout content, tabsBar;
    private TextView pairBanner;

    private NodeApi nodeApi;
    private Node node;
    private Cfg cfg;
    private Store store;
    private Guardian guardian;
    private AuditRunner auditRunner;
    private ExecutorService worker;
    private DirectNodeEvents.Listener receiver;

    private String tab = "guardian";
    /**
     * TRI-STATE, deliberately: null = we haven't heard from Minima Core yet.
     *
     * <p>Defaulting to true hid the "not connected" banner until the node denied us, which reassures
     * before there is anything to be reassured by. Defaulting to false would flash a false alarm at
     * every launch instead. Neither is honest — "don't know yet" is a real state and gets rendered
     * as itself.
     */
    private Boolean paired = null;
    private KeyAudit.Result lastAudit;
    private long tip = 0;
    private List<Guardian.Stake> stakes = new ArrayList<>();
    private boolean auditRunning = false;
    /** Set when the rescue chooser was opened BY "Start guardian", so we finish that job afterwards.
     *  volatile: written on the main thread, read and cleared on the worker. */
    private volatile boolean startGuardianAfterSetup = false;

    // Snapshots computed on the worker and rendered from cache. NOTHING in render() may touch the
    // node or the database.
    //
    // Audit.usable() blocks on `keys action:list` whenever the stored verdict is past its freshness
    // window but still re-confirmable (30 min .. 24 h). NodeApi delivers that reply ON THE MAIN
    // THREAD — so calling it from render() blocked the main thread waiting for something only the
    // main thread could deliver. The app hung on the splash screen for the full 200s IPC timeout and
    // then rendered a wrongly-STALE verdict. It looked fine in testing only because a node with no
    // stored audit short-circuits before the node call.
    private Audit.Verdict lastVerdict;
    private Guardian.Status lastStatus;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Design.load(this);   // fonts + persisted light/dark, before any view exists
        setContentView(R.layout.pm_futurecash_next_activity_main);
        content = findViewById(R.id.pm_futurecash_next_content);
        tabsBar = findViewById(R.id.pm_futurecash_next_tabs);
        pairBanner = findViewById(R.id.pm_futurecash_next_pairBanner);

        cfg = new Cfg(this);
        store = new Store(this);
        worker = Executors.newSingleThreadExecutor();
        nodeApi = new NodeApi(this, enabled -> { paired = enabled; runOnUiThread(this::renderPairBanner); });
        node = new Node(nodeApi);
        guardian = new Guardian(node, cfg, store,
                (t, b) -> { if (cfg.is(Cfg.NOTIFY, true)) Notifier.alert(this, t, b); });
        auditRunner = new AuditRunner(node, cfg, this);

        Notifier.ensureChannels(this);
        askNotificationPermission();
        styleChrome();
        buildTabs();
        render();

        receiver = new DirectNodeEvents.Listener() {
            @Override public void onEvent(JSONObject nodeEvent) {
                try {
                    final String ev = nodeEvent.optString("event", "");
                    if ("NEWBLOCK".equals(ev) || "NEWBALANCE".equals(ev)) refresh();
                } catch (Exception ignored) {}
            }
        };
        // Subscribed only while this screen is visible.

        // Assert the covenant at every launch, so simply opening the app repairs a node whose script
        // table lost it — the failure that only shows at spend time.
        submit(() -> {
            guardian.wallet().registerContract();
            guardian.reloadAtRisk();
            refresh();
            maybeAudit(false);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        GuardianService.UI_FOREGROUND = true;
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;
        GuardianService.UI_FOREGROUND = false;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (receiver != null) { try { DirectNodeEvents.unsubscribe(receiver); } catch (Exception ignored) {} }
        if (auditRunner != null) auditRunner.onDestroy();
        if (worker != null) worker.shutdownNow();
        if (nodeApi != null) nodeApi.onDestroy();
        if (store != null) store.close();
    }

    private void submit(Runnable r) {
        final ExecutorService w = worker;
        if (w != null && !w.isShutdown()) { try { w.execute(r); } catch (Exception ignored) {} }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /* ================= chrome ================= */

    /** Apply the live palette to the chrome that lives in XML. Called on every theme change. */
    private void styleChrome() {
        final View root = findViewById(R.id.pm_futurecash_next_rootView);
        root.setBackgroundColor(Design.BG());

        final TextView title = findViewById(R.id.pm_futurecash_next_title);
        title.setTypeface(Design.sansBold());
        title.setTextColor(Design.TEXT());

        final TextView sub = findViewById(R.id.pm_futurecash_next_subtitle);
        sub.setTypeface(Design.sans());
        sub.setTextColor(Design.DIM2());

        // A quiet affordance, not a control that competes with the guardian's own state.
        final TextView toggle = findViewById(R.id.pm_futurecash_next_themeToggle);
        toggle.setText(Design.isDark() ? "☀" : "☾");
        toggle.setTextColor(Design.DIM());
        toggle.setBackground(Design.stroked(this, Design.SURFACE2(), 12));
        toggle.setOnClickListener(v -> {
            Design.toggle(this);
            styleChrome();
            buildTabs();
            render();
        });

        pairBanner.setTypeface(Design.sans());
        pairBanner.setTextColor(Design.WARN());
        pairBanner.setBackground(Design.stroked(this, Design.SURFACE2(), 14));
    }

    /** A segmented control: the active tab is a filled pill, the rest are quiet text. */
    private void buildTabs() {
        tabsBar.removeAllViews();
        final String[][] defs = {{"guardian", "Guardian"}, {"lock", "Stakes"},
                {"activity", "Activity"}, {"help", "Help"}};
        for (String[] d : defs) {
            final boolean active = tab.equals(d[0]);
            final TextView t = new TextView(this);
            t.setText(d[1]);
            t.setTextSize(13);
            t.setTypeface(active ? Design.sansBold() : Design.sans());
            t.setTextColor(active ? Design.ACCENT() : Design.DIM());
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            t.setBackground(active
                    ? Design.roundBg(this, Design.ACCENT_SOFT(), 12)
                    : Design.roundBg(this, Color.TRANSPARENT, 12));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            t.setLayoutParams(lp);
            Design.pressable(t);
            t.setOnClickListener(v -> { tab = d[0]; buildTabs(); render(); });
            tabsBar.addView(t);
        }
    }

    private void renderPairBanner() {
        // Only when the node has actually told us we're not enabled — never on the unknown state,
        // which would be a false alarm at every launch.
        if (!Boolean.FALSE.equals(paired)) { pairBanner.setVisibility(View.GONE); return; }
        pairBanner.setVisibility(View.VISIBLE);
        pairBanner.setText("⚠ The embedded node is unavailable. Open "
                + "Minima Core and check its status. Until then nothing can be collected "
                + "or rescued.");
    }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    /**
     * Persist a guardian flag off the main thread, then redraw.
     *
     * <p>{@link Cfg#set} is a synchronous {@code commit()} on purpose — these flags decide whether
     * protection runs, and an unflushed one means the guardian doesn't come back after a reboot, so
     * trading durability for smoothness would be the wrong way round. The fix is to keep the durable
     * write and stop doing it on the UI thread.
     *
     * @param andThen extra work to run on the main thread once the flag is stored (starting or
     *                stopping the service, toasting) — may be null
     */
    private void setFlag(String key, boolean on, Runnable andThen) {
        submit(() -> {
            cfg.setBool(key, on);
            runOnUiThread(() -> {
                if (andThen != null) andThen.run();
                render();
            });
        });
    }

    /**
     * Finish the job the user actually asked for. They pressed "Start guardian" and were diverted
     * into choosing a destination; now that one exists, start it — rather than leaving them on a
     * screen that still says Off after they did what it asked.
     */
    private void finishPendingGuardianStart() {
        if (!startGuardianAfterSetup) return;
        startGuardianAfterSetup = false;
        if (!cfg.rescueReady()) return;   // setup didn't actually complete
        cfg.setBool(Cfg.GUARDIAN_ON, true);
        runOnUiThread(() -> {
            GuardianService.start(this);
            toast("Guardian started — watching your stakes.");
            render();
        });
    }

    /** Pull fresh state on the worker, then redraw. Every blocking call belongs in here. */
    private void refresh() {
        submit(() -> {
            final long t = guardian.tip();
            final List<Guardian.Stake> s = guardian.listStakes();
            final Audit.Verdict v = new Audit(cfg, node).usable();   // may block on the node — worker only
            final Guardian.Status st = guardian.status(t);           // SQLite — worker only
            runOnUiThread(() -> {
                tip = t; stakes = s; lastVerdict = v; lastStatus = st;
                render();
            });
        });
    }

    private void render() {
        renderPairBanner();
        content.removeAllViews();
        switch (tab) {
            case "lock": renderStakes(); break;
            case "activity": renderActivity(); break;
            case "help": renderHelp(); break;
            default: renderGuardian();
        }
    }

    /* ================= Guardian tab ================= */

    private void renderGuardian() {
        content.addView(heroCard());
        content.addView(auditCard());
        content.addView(daemonCard());
        content.addView(safeCard());
        content.addView(riskCard());
        recoverCard();
        hardenCard();
        content.addView(keyTableCard());
    }

    /**
     * One line answering the only question that matters on opening the app: is my money being
     * watched, and if not, why not.
     *
     * <p>Everything below this card is detail. The states are ranked by what needs the user MOST, not
     * by what is most alarming — "not enabled in Minima Core" outranks key reuse, because until the
     * IPC is granted nothing can happen at all.
     */
    private View heroCard() {
        final boolean on = cfg.is(Cfg.GUARDIAN_ON, false);
        final boolean ready = cfg.rescueReady();
        final String[] badDest = new Audit(cfg, node).safeReused();
        final int atRisk = lastStatus != null ? lastStatus.atRiskKnown : 0;

        final String headline;
        final String detail;
        final int colour;
        // ORDER MATTERS, and the rule is: never resolve "I don't know yet" to good news.
        //
        // The states below fall into two groups. Everything down to "guardian is off" is derived
        // from stored config, is known the instant the app opens, and is never reassuring — so it is
        // safe to draw immediately. Only the last two need a completed snapshot from the worker, and
        // that snapshot takes seconds: a chain-tip round trip, a `coins relevant:true` that has
        // measured 248KB, possibly a key re-confirm, then SQLite. Rendering "Protected" during that
        // window told the user their money was watched before anything had been read — and someone
        // who glances and locks the phone may see nothing else.
        if (Boolean.FALSE.equals(paired)) {
            headline = "Not connected";
            detail = "Start the embedded node in Minima Core. Nothing can be collected or "
                   + "rescued until you do.";
            colour = Design.RED();
        } else if (badDest != null) {
            headline = "Your safe address is reused";
            detail = "Rescued money would land on another exposed key. Set a fresh destination.";
            colour = Design.RED();
        } else if (!on) {
            headline = ready ? "Guardian is off" : "Guardian needs a destination";
            detail = ready
                    ? "Your stakes are only watched while this app is open."
                    : "Pick where rescued funds should go, then it can run.";
            colour = Design.WARN();
        } else if (lastStatus == null || paired == null) {
            // Guardian is on and configured, but nothing has been read back yet. Say so.
            headline = "Checking…";
            detail = "Reading your stakes and keys from Minima Core.";
            colour = Design.DIM();
        } else if (atRisk > 0) {
            headline = "Watching — " + atRisk + " at-risk address" + (atRisk == 1 ? "" : "es");
            detail = "Anything maturing onto a reused key is collected and swept to safety.";
            colour = Design.WARN();
        } else if (!cfg.is(Cfg.AUTO_COLLECT_SAFE, false) && lastStatus.readySafeN > 0) {
            // "Protected" while collectible money sits untouched is the lie this card must not
            // tell: with auto-collect off, the guardian will NEVER take these — only rescue.
            final int n = (int) lastStatus.readySafeN;
            headline = "Auto-collect is OFF — " + n + " stake" + (n == 1 ? "" : "s") + " waiting";
            detail = "Matured stakes on clean addresses are NOT collected while auto-collect is "
                   + "off. Turn it on below, or tap Collect all now. At-risk rescue still runs.";
            colour = Design.WARN();
        } else {
            headline = "Protected";
            detail = "The guardian is watching your stakes, even with the app closed."
                   + (cfg.is(Cfg.AUTO_COLLECT_SAFE, false) ? ""
                      : " Auto-collect is off, so clean matured stakes will wait for you; "
                        + "at-risk ones are still rescued.");
            colour = Design.SAFE();
        }

        final LinearLayout c = Ui.card(this, colour);
        final LinearLayout top = Ui.row(this);
        final View dot = new View(this);
        dot.setBackground(Design.roundBg(this, colour, 5));
        final LinearLayout.LayoutParams dotLp =
                new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dotLp.rightMargin = Ui.dp(this, 10);   // a margin, not two spaces — survives font and locale
        dot.setLayoutParams(dotLp);
        top.addView(dot);
        top.addView(Ui.text(this, headline, colour, 19, true));
        c.addView(top);
        c.addView(Ui.body(this, detail));

        // The chain tip doubles as a liveness indicator: a number that stops moving means the node
        // stopped talking to us, which no amount of reassuring copy would reveal.
        if (tip > 0) {
            final LinearLayout foot = Ui.row(this);
            foot.addView(Ui.text(this, "chain ", Design.DIM2(), 11, false));
            foot.addView(Ui.mono(this, String.valueOf(tip), Design.DIM(), 11, false));
            c.addView(Ui.divider(this));
            c.addView(foot);
        }
        return c;
    }

    private View auditCard() {
        final LinearLayout c = Ui.card(this, 0);
        final long at = cfg.getLong(Cfg.AUDIT_AT);
        final String err = cfg.get(Cfg.AUDIT_LAST_ERR, "");
        final Audit.Verdict v = lastVerdict;                        // cached — see the field comment
        final String[] bad = new Audit(cfg, node).safeReused();     // prefs only, never touches the node

        String msg;
        int colour;
        if (auditRunning) {
            msg = "Auditing your keys…"; colour = Design.DIM();
        } else if (bad != null) {
            // Loudest state in the app: the place we sweep RESCUED money to is itself reused. Ranked
            // above key-reuse risk because it breaks the escape route, not just the front door.
            // Full address on its own line — the user needs to recognise WHICH address is burnt,
            // and an abbreviated one cannot be compared against a wallet or an explorer.
            msg = "⚠ Your safe destination is a REUSED address (signed ×"
                    + bad[1] + "). Rescuing there would move your money onto another exposed key — "
                    + "set a fresh destination below.\n\n" + bad[0];
            colour = Design.RED();
        } else if (lastAudit != null && lastAudit.worstReuse > 0) {
            msg = "⚠ Key-reuse risk detected (reused ×" + lastAudit.worstReuse + "). At-risk stakes "
                    + "are collected and swept to your safe address.";
            colour = lastAudit.worstReuse > 3 ? Design.RED() : Design.WARN();
        } else if (!err.isEmpty()) {
            // Report the failure we actually had. Wrapping every error in "couldn't reach the audit
            // service" told the user to check their network when the real problem was a local one
            // (not enabled in Minima Core), which is the opposite of the fix they needed.
            msg = err + " "
                    + (v != null && v.ok ? "Using your last good audit (" + Ui.timeAgo(v.at) + ") — collecting continues."
                            : "Auto-collect of safe stakes is paused until an audit succeeds; your funds "
                              + "are untouched and stay locked in the contract.");
            colour = (v != null && v.ok) ? Design.WARN() : Design.RED();
        } else if (at == 0) {
            msg = "No audit yet. Tap Re-audit to check your keys."; colour = Design.DIM();
        } else {
            msg = "✓ No key-reuse risk. Your matured stakes can be collected safely.";
            colour = Design.SAFE();
        }

        c.addView(Ui.text(this, "Key audit", Design.TEXT(), 16, true));
        c.addView(Ui.text(this, msg, colour, 13, false));
        if (at > 0) {
            // "stale" / "reconfirmed" / "partial" are this code's vocabulary, not the user's. Say
            // what the state MEANS for their money, and say nothing at all when it means nothing.
            final String meta;
            if (v == null) meta = "";
            else if (v.mode == Audit.Mode.RECONFIRMED) meta = " · re-checked against your node";
            else if (!v.ok) meta = " · needs a fresh audit before safe stakes auto-collect";
            else meta = "";
            c.addView(Ui.text(this, "audited " + Ui.timeAgo(at) + meta, Design.DIM(), 11, false));
        }
        final LinearLayout row = Ui.row(this);
        row.addView(Ui.ghost(this, "Re-audit", v2 -> maybeAudit(true)));
        c.addView(row);
        return c;
    }

    private View daemonCard() {
        final boolean on = cfg.is(Cfg.GUARDIAN_ON, false);
        final boolean ready = cfg.rescueReady();
        final String safe = cfg.get(Cfg.SAFE_ADDRESS, null);
        final LinearLayout c = Ui.card(this, 0);
        final LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "Guardian daemon"));
        head.addView(Ui.spacer(this));
        head.addView(Ui.pill(this, on ? "running" : (ready ? "off" : "needs setup"),
                on ? Design.SAFE() : Design.WARN()));
        c.addView(head);

        // The old copy said only that stakes "are checked while this app is open", which never
        // explained WHY an unwatched stake is exposed — and the exposure is the whole point. The
        // mechanism has to be in the text: collecting needs no signature, so the clock is not yours
        // to start.
        final String body;
        if (on) {
            body = "Watching your stakes even with the app closed. Anything maturing onto a reused "
                 + "key is collected and swept to safety within about a minute.";
        } else if (!ready) {
            body = "It needs somewhere to move rescued funds before it can run. Start guardian will "
                 + "ask — a fresh address on this node takes one tap.";
        } else {
            body = "Off. After a stake matures, anyone can collect it — that needs no signature — "
                 + "which lands the money on its payout address. If that address's key was reused, "
                 + "it sits there exposed until you next open this app. With the guardian on, it is "
                 + "moved to safety within about a minute of landing.";
        }
        c.addView(Ui.body(this, body));
        // Name the destination when running. "Swept to safety" is a promise; showing WHERE lets the
        // user check it rather than take it on faith.
        if (on && safe != null) {
            c.addView(Ui.divider(this));
            final LinearLayout dest = Ui.row(this);
            dest.addView(Ui.text(this, "sweeps to", Design.DIM2(), 11, false));
            dest.addView(Ui.text(this, guardian.wallet().sameNodeMode()
                    ? "  · this node · tap to copy" : "  · external · tap to copy",
                    Design.DIM2(), 11, false));
            c.addView(dest);
            // The FULL address, never abbreviated — "swept to safety" is a promise, and only the
            // whole value lets the user check it against their wallet rather than take it on faith.
            c.addView(Ui.copyable(this, safe, Design.DIM(), 11));
        }

        final LinearLayout row = Ui.row(this);
        if (on) {
            row.addView(Ui.ghost(this, "Stop guardian", v ->
                    setFlag(Cfg.GUARDIAN_ON, false, () -> GuardianService.stop(this))));
        } else {
            row.addView(Ui.cta(this, "Start guardian", v -> {
                // HARD GATE. Never let the daemon run when it cannot rescue — that is the state that
                // tells the user they are protected while nothing would happen. Not an error dialog:
                // send them straight to the chooser so the fix is one tap away.
                if (!cfg.rescueReady()) {
                    toast("Pick where rescued funds should go — the guardian needs a destination.");
                    startGuardianAfterSetup = true;
                    rescueDialog();
                    return;
                }
                setFlag(Cfg.GUARDIAN_ON, true, () -> GuardianService.start(this));
            }));
        }
        c.addView(row);
        return c;
    }

    private View safeCard() {
        final Guardian.Status st = lastStatus != null ? lastStatus : new Guardian.Status();
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, "Ready to collect — SAFE", Design.TEXT(), 16, true));

        final String detail = st.readySafeN > 0
                ? Ui.amount(String.valueOf(st.readySafeA)) + " MINIMA in " + (int) st.readySafeN
                  + " matured stake" + (st.readySafeN == 1 ? "" : "s") + " on clean addresses."
                : "No safe matured stakes right now.";
        c.addView(Ui.text(this, detail, Design.DIM(), 13, false));

        final boolean auto = cfg.is(Cfg.AUTO_COLLECT_SAFE, false);
        final LinearLayout row = Ui.row(this);
        if (auto) {
            c.addView(Ui.text(this, "Auto-collect is ON — matured stakes are collected to your own "
                    + "address. There's no rush: until then they stay safely locked away.", Design.SAFE(), 12, false));
            row.addView(Ui.ghost(this, "Turn off", v ->
                    setFlag(Cfg.AUTO_COLLECT_SAFE, false, () -> GuardianService.refreshNotification(this))));
        } else {
            c.addView(Ui.text(this, "Auto-collect is OFF — the guardian will not collect these. "
                    + "They stay safely locked in the contract until you act.", Design.WARN(), 12, false));
            row.addView(Ui.tinted(this, "Turn on auto-collect", Design.SAFE(), v ->
                    setFlag(Cfg.AUTO_COLLECT_SAFE, true, () -> GuardianService.refreshNotification(this))));
        }
        if (st.readySafeN > 0) row.addView(Ui.ghost(this, "Collect all now", v -> collectAllSafe()));
        c.addView(row);
        return c;
    }

    private View riskCard() {
        final Guardian.Status st = lastStatus != null ? lastStatus : new Guardian.Status();
        final boolean anyRisk = st.atRiskKnown > 0 || st.readyRiskN > 0 || st.pendRiskN > 0;
        final LinearLayout c = Ui.card(this, st.readyRiskN > 0 ? Design.WARN() : 0);
        c.addView(Ui.text(this, "At-risk stakes", Design.TEXT(), 16, true));

        String detail;
        if (st.readyRiskN > 0) {
            detail = Ui.amount(String.valueOf(st.readyRiskA)) + " MINIMA in " + (int) st.readyRiskN
                    + " matured stake(s) paying out to REUSED addresses — rescuing to safety now.";
        } else if (st.pendRiskN > 0) {
            detail = Ui.amount(String.valueOf(st.pendRiskA)) + " MINIMA in " + (int) st.pendRiskN
                    + " at-risk stake(s) maturing (soonest "
                    + Ui.maturesIn((long) st.pendRiskSoonest, tip) + "). The guardian collects and "
                    + "sweeps each the moment it matures.";
        } else {
            detail = anyRisk ? "No at-risk stakes right now — the guardian is watching and acts the "
                    + "moment one matures." : "No at-risk stakes detected.";
        }
        c.addView(Ui.text(this, detail, Design.DIM(), 13, false));

        final String safe = cfg.get(Cfg.SAFE_ADDRESS, null);
        final boolean rescueOn = cfg.is(Cfg.ENABLED, false);
        if (safe != null) {
            c.addView(Ui.text(this, (rescueOn ? "rescue ON → " : "destination: ")
                    + (guardian.wallet().sameNodeMode() ? "this node" : "external") + " · tap to copy",
                    rescueOn ? Design.SAFE() : Design.DIM(), 12, false));
            c.addView(Ui.copyable(this, safe, rescueOn ? Design.SAFE() : Design.DIM(), 11));
        }
        if (st.vaultLocked) {
            c.addView(Ui.text(this, "🔒 vault locked — unlock your node to sweep", Design.WARN(), 12, false));
        }

        final LinearLayout row = Ui.row(this);
        row.addView(Ui.tinted(this, safe == null ? "Set up rescue…" : "Change destination", Design.ACCENT(),
                v -> rescueDialog()));
        if (rescueOn) row.addView(Ui.ghost(this, "Stop rescue", v -> submit(() -> {
            cfg.setBool(Cfg.ENABLED, false);
            cfg.log(Cfg.LVL_INFO, "At-risk rescue DISABLED");
            runOnUiThread(this::render);
        })));
        c.addView(row);
        return c;
    }

    /** Rescue setup: a fresh clean address on this node, or an external wallet. */
    private void rescueDialog() {
        final LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        box.addView(Ui.text(this, "Where should rescued funds land?", Design.TEXT(), 15, true));
        box.addView(Ui.text(this, "Best if you only have one device: funds stay on this node at a "
                + "brand-new unused address.", Design.DIM(), 12, false));
        final EditText ext = Ui.field(this, "…or an Mx address from a DIFFERENT wallet", false);
        box.addView(ext);

        final AlertDialog dlg = new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Rescue at-risk stakes")
                .setView(box)
                // Backing out means they did NOT agree to set a destination, so drop the pending
                // "start the guardian afterwards" intent — never start it behind a cancel.
                .setNegativeButton("Cancel", (d, w) -> startGuardianAfterSetup = false)
                .setOnCancelListener(d -> startGuardianAfterSetup = false)
                .create();

        final LinearLayout actions = Ui.row(this);
        actions.addView(Ui.tinted(this, "Fresh address on THIS node", Design.SAFE(), v -> {
            dlg.dismiss();
            submit(() -> {
                final Wallet.Res r = guardian.wallet().setSafeSameNode();
                if (r.ok) { cfg.setBool(Cfg.ENABLED, true); cfg.log(Cfg.LVL_INFO, "At-risk rescue ENABLED"); finishPendingGuardianStart(); }
                toast(r.ok ? "Rescue ON → " + r.value : r.error);
                runOnUiThread(this::render);
            });
        }));
        actions.addView(Ui.ghost(this, "Use external", v -> {
            final String addr = ext.getText().toString().trim();
            dlg.dismiss();
            toast("Checking that address is clean…");
            submit(() -> auditRunner.checkOne(addr, count -> submit(() -> {
                // count: >0 reused (refuse), 0 clean, -1 unverifiable (accept, re-checked on every audit)
                final Wallet.Res r = guardian.wallet().setSafeExternal(addr, count);
                if (r.ok) { cfg.setBool(Cfg.ENABLED, true); cfg.log(Cfg.LVL_INFO, "At-risk rescue ENABLED"); finishPendingGuardianStart(); }
                toast(r.ok
                        ? "Rescue ON → external " + addr
                          + (count == 0 ? " (verified clean)" : " (reuse check unverified — will re-check)")
                        : r.error);
                runOnUiThread(this::render);
            })));
        }));
        box.addView(actions);
        dlg.show();
    }

    /** Coins stranded on retired addresses — shown only when there are any. */
    private void recoverCard() {
        submit(() -> {
            final List<String[]> stuck = guardian.wallet().retiredWithCoins();
            if (stuck.isEmpty()) return;
            int total = 0;
            for (String[] s : stuck) total += Integer.parseInt(s[1]);
            final int n = total;
            runOnUiThread(() -> {
                final LinearLayout c = Ui.card(this, Design.WARN());
                c.addView(Ui.text(this, "⚠ " + n + " coin(s) stuck on retired addresses", Design.WARN(), 15, true));
                c.addView(Ui.text(this, "Retiring nulled those addresses' keys, so ordinary sends can't "
                        + "spend them. Recover moves them to your clean address using each address's "
                        + "real key.", Design.DIM(), 12, false));
                c.addView(Ui.tinted(this, "Recover " + n + " coin(s)", Design.WARN(), v -> submit(() -> {
                    final Wallet.SweepReport r = guardian.wallet().recoverRetiredCoins();
                    toast(r.swept > 0 ? "Recovered " + r.swept + " coin(s) to " + r.dest
                            : (r.error != null ? r.error : "Nothing to recover."));
                    refresh();
                })));
                content.addView(c, Math.min(2, content.getChildCount()));
            });
        });
    }

    /** Harden: retire reused addresses so the node stops routing change to them. */
    private void hardenCard() {
        submit(() -> {
            final JSONArray flagged = cfg.atRisk();
            if (flagged.length() == 0) return;
            final List<Wallet.ReusedAddr> list = guardian.wallet().reusedDefaults(flagged);
            if (list.isEmpty()) return;
            runOnUiThread(() -> {
                final LinearLayout c = Ui.card(this, Design.WARN());
                c.addView(Ui.text(this, "Harden — retire reused addresses", Design.TEXT(), 16, true));
                c.addView(Ui.text(this, "Your node routes change to a random pick of its default "
                        + "addresses, so a reused one keeps receiving fresh money. Retiring drops it "
                        + "from that rotation for good — kept watch-only, moves no funds.", Design.DIM(), 12, false));
                int active = 0;
                for (Wallet.ReusedAddr ra : list) {
                    if (!ra.isDefault) continue;
                    active++;
                    final LinearLayout r = Ui.row(this);
                    final LinearLayout info = Ui.column(this);
                    info.addView(Ui.copyable(this, ra.mx.isEmpty() ? ra.hex : ra.mx, Design.TEXT(), 11));
                    info.addView(Ui.text(this, ra.coins == 0 ? "empty · ready to retire"
                            : "holds " + ra.coins + " coin(s) — sweep them off first",
                            ra.coins == 0 ? Design.DIM() : Design.WARN(), 11, false));
                    info.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    r.addView(info);
                    if (ra.coins == 0) {
                        r.addView(Ui.ghost(this, "Retire", v -> submit(() -> {
                            final Wallet.Res res = guardian.wallet().retireAddress(ra.hex);
                            toast(res.ok ? "Retired — change will never land there again." : res.error);
                            refresh();
                        })));
                    } else {
                        r.addView(Ui.tinted(this, "Sweep", Design.SAFE(), v -> submit(() -> {
                            final Wallet.SweepReport res = guardian.wallet().sweepAddressToClean(ra.hex);
                            toast(res.swept > 0 ? "Swept " + res.swept + " coin(s) → " + res.dest
                                    : (res.error != null ? res.error : "Nothing swept."));
                            refresh();
                        })));
                    }
                    c.addView(r);
                }
                if (active == 0) {
                    c.addView(Ui.text(this, "✓ All your reused addresses are already out of the change "
                            + "rotation.", Design.SAFE(), 12, false));
                }
                c.addView(Ui.text(this, "Retiring survives restarts but NOT a reinstall-from-seed — "
                        + "re-run it after any reseed. Restart your node afterwards to top the change "
                        + "pool back up.", Design.DIM(), 11, false));
                content.addView(c);
            });
        });
    }

    private View keyTableCard() {
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, "Key detail", Design.TEXT(), 16, true));
        if (lastAudit == null) {
            c.addView(Ui.text(this, "Run an audit to see every key's on-chain usage.", Design.DIM(), 12, false));
            return c;
        }
        for (KeyAudit.Row r : lastAudit.rows) {
            if (!r.risk && !r.reused) continue;   // only the interesting ones; the rest are noise
            final LinearLayout row = Ui.row(this);
            row.addView(Ui.text(this, "#" + r.index, Design.TEXT(), 12, true));
            row.addView(Ui.text(this, r.reused ? "  RE-USED ×" + r.reuseCount : "  AT RISK",
                    r.reused ? Design.RED() : Design.WARN(), 12, true));
            c.addView(row);
            // The full address under its verdict — this is the value the user takes to an explorer
            // or a support ticket, and a shortened one cannot be pasted anywhere.
            c.addView(Ui.copyable(this, r.address, Design.DIM(), 10));
        }
        c.addView(Ui.text(this, "Only your PUBLIC keys are ever sent to the audit service — they can't "
                + "be used to take your money.", Design.DIM(), 11, false));
        return c;
    }

    private void maybeAudit(boolean force) {
        if (auditRunning) return;
        final long at = cfg.getLong(Cfg.AUDIT_AT);
        // Refresh once the verdict is nearing expiry, not on every open: the gate needs a verdict
        // under 30 minutes old, so auditing on every launch was load the service never needed.
        if (!force && at > 0 && System.currentTimeMillis() - at < 20 * 60_000L) return;
        auditRunning = true;
        runOnUiThread(this::render);
        submit(() -> auditRunner.run(new AuditRunner.Cb() {
            @Override public void onDone(KeyAudit.Result result, long archiveTip) {
                auditRunning = false;
                lastAudit = result;
                guardian.reloadAtRisk();
                // refresh(), not render(): the cached verdict was computed BEFORE this audit ran, so
                // rendering it now showed "audited just now · stale" — two lines of the same card
                // disagreeing about the same fact.
                refresh();
            }
            @Override public void onError(String message) {
                auditRunning = false;
                refresh();
            }
        }));
    }

    /* ================= Stakes tab ================= */

    private void renderStakes() {
        final LinearLayout listCard = Ui.card(this, 0);
        final LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "Your stakes"));
        head.addView(Ui.spacer(this));
        if (!stakes.isEmpty()) {
            int ready = 0;
            for (Guardian.Stake s : stakes) if (s.ready) ready++;
            head.addView(Ui.pill(this, ready > 0 ? ready + " ready" : stakes.size() + " pending",
                    ready > 0 ? Design.SAFE() : Design.DIM()));
        }
        listCard.addView(head);

        if (stakes.isEmpty()) {
            listCard.addView(Ui.body(this, "No stakes yet. Lock one below and it appears here, "
                    + "soonest to unlock first."));
        }
        for (Guardian.Stake s : stakes) {
            final LinearLayout rowV = Ui.inset(this);
            final LinearLayout info = Ui.column(this);

            // The amount is the thing being scanned for — mono, large, and the only bright ink here.
            final LinearLayout amtRow = Ui.row(this);
            amtRow.addView(Ui.mono(this, Ui.amount(s.amount), Design.TEXT(), 17, true));
            final TextView unit = Ui.text(this, "0x00".equals(s.tokenid) ? " MINIMA" : " token",
                    Design.DIM2(), 11, false);
            unit.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
            amtRow.addView(unit);
            if (s.atRisk) {
                final View sp = new View(this);
                sp.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
                amtRow.addView(sp);
                amtRow.addView(Ui.pill(this, "reused payout", Design.WARN()));
            }
            info.addView(amtRow);

            info.addView(Ui.text(this, s.ready ? "ready to collect"
                            : "unlocks " + Ui.maturesIn(s.matureBlock, tip),
                    s.ready ? Design.SAFE() : Design.DIM(), 12, false));
            info.addView(Ui.mono(this, "block " + s.matureBlock + " → " + s.payout,
                    Design.DIM2(), 10, false));
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rowV.addView(info);

            // No bare-collect button for an at-risk payout: those belong to the rescue path.
            if (s.ready && !s.atRisk) {
                rowV.addView(Ui.ghost(this, "Collect", v -> submit(() -> {
                    final Wallet.Res r = guardian.collectNow(s.coinid);
                    toast(r.ok ? "Collect posted — confirms in about a block." : r.error);
                    refresh();
                })));
            }
            listCard.addView(rowV);
        }
        content.addView(listCard);
        content.addView(lockCard());
    }

    private View lockCard() {
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, "Lock a new payment", Design.TEXT(), 16, true));
        final EditText amount = Ui.field(this, "Amount (MINIMA)", true);
        c.addView(amount);

        final long[] blocks = {1728};   // ~1 day
        final TextView preview = Ui.text(this, "Unlocks in ~1 day", Design.DIM(), 12, false);
        final LinearLayout presets = Ui.row(this);
        final int[][] opts = {{60, 72}, {1440, 1728}, {10080, 12096}, {43200, 51840}};
        final String[] labels = {"+1 hour", "+1 day", "+1 week", "+1 month"};
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            presets.addView(Ui.ghost(this, labels[i], v -> {
                blocks[0] = opts[idx][1];
                preview.setText("Unlocks in ~" + labels[idx].substring(1) + " · " + blocks[0] + " blocks");
            }));
        }
        c.addView(presets);
        c.addView(preview);

        final EditText recipient = Ui.field(this, "Recipient Mx… (leave blank to pay yourself)", false);
        c.addView(recipient);

        c.addView(Ui.cta(this, "Lock it", v -> {
            final String amt = amount.getText().toString().trim();
            final String rcp = recipient.getText().toString().trim();
            submit(() -> {
                String payoutHex;
                if (rcp.isEmpty()) {
                    // Pay ourselves at a FRESH clean address, never a reused default.
                    final Wallet.Res clean = guardian.wallet().cleanAddress();
                    if (!clean.ok) { toast(clean.error); return; }
                    payoutHex = guardian.wallet().cleanAddressHex();
                } else if (Node.validHexAddr(rcp)) {
                    payoutHex = rcp;
                } else {
                    final JSONObject r = node.cmd("checkaddress address:" + rcp);
                    final JSONObject resp = Node.obj(r);
                    payoutHex = resp == null ? null : resp.optString("0x", resp.optString("address", ""));
                    if (payoutHex == null || !Node.validHexAddr(payoutHex)) {
                        toast("Couldn't resolve that recipient address."); return;
                    }
                }
                final Wallet.Res res = guardian.createFutureCash(amt, "0x00", blocks[0], payoutHex);
                toast(res.ok ? "✓ Locked " + amt + " MINIMA · matures block " + res.value : res.error);
                refresh();
            });
        }));
        c.addView(Ui.text(this, "Once locked, nobody — not even you — can unlock it early. Collected "
                + "coins can ONLY go to the payout address; that is enforced on-chain.", Design.DIM(), 11, false));
        return c;
    }

    private void collectAllSafe() {
        submit(() -> {
            int done = 0;
            String lastErr = null;
            for (Guardian.Stake s : guardian.listStakes()) {
                if (!s.ready || s.atRisk) continue;
                final Wallet.Res r = guardian.collectNow(s.coinid);
                if (r.ok) done++;
                else {
                    lastErr = r.error;
                    // A blocking condition fails every remaining stake identically — stop.
                    if (r.error != null && (r.error.contains("audit") || r.error.contains("embedded node"))) break;
                }
            }
            toast(done > 0 ? "Posted " + done + " collect(s) — they confirm in about a block."
                    : (lastErr != null ? lastErr : "Nothing to collect."));
            refresh();
        });
    }

    /* ================= Activity tab ================= */

    private void renderActivity() {
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, "Activity", Design.TEXT(), 16, true));
        final JSONArray log = cfg.logLines();
        if (log.length() == 0) {
            c.addView(Ui.text(this, "Nothing yet. Every collect, sweep and recovery shows here with "
                    + "full coin ids and addresses.", Design.DIM(), 12, false));
        }
        for (int i = 0; i < log.length(); i++) {
            final JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            final String lvl = e.optString("level", "info");
            final int col = "error".equals(lvl) ? Design.RED() : "warn".equals(lvl) ? Design.WARN() : Design.TEXT();
            final String msg = e.optString("msg", "");
            final String[] parts = msg.split(" \\| ", 2);
            c.addView(Ui.text(this, parts[0], col, 12, false));
            if (parts.length > 1) c.addView(Ui.text(this, parts[1], Design.DIM(), 10, false));
        }
        c.addView(Ui.ghost(this, "Reset history", v -> {
            new AlertDialog.Builder(this, Design.dialogTheme())
                    .setTitle("Reset history?")
                    .setMessage("Clears the log and completed records. Keeps in-flight rescues and your "
                            + "safe address. Moves no funds — your coins and locks live on the chain.")
                    .setPositiveButton("Reset", (d, w) -> submit(() -> {
                        store.resetHistory();
                        cfg.clearLog();
                        refresh();
                    }))
                    .setNegativeButton("Cancel", null)
                    .show();
        }));
        content.addView(c);
    }

    /* ================= Help tab ================= */

    private void renderHelp() {
        content.addView(helpCard("The 30-second version",
                "1. Start the embedded node in Minima Core.\n"
              + "2. Start the guardian on the Guardian tab.\n"
              + "3. Stakes tab: enter an amount, pick when it unlocks, press Lock it.\n\n"
              + "When the date arrives the guardian collects it for you, safely."));
        content.addView(helpCard("Why there's a guardian — it's a race",
                "Minima signs with one-time keys. Signing twice with the same one leaks it, and that "
              + "address is then exposed forever.\n\n"
              + "Your money can never be stolen AT collection: the chain only lets a collected coin go "
              + "to the payout address you chose when locking. The risk is what happens after it "
              + "lands.\n\n"
              + "And you don't control when it lands. Collecting a matured stake needs NO signature, "
              + "so anyone can do it — including someone holding a leaked key, who has every reason "
              + "to collect your stake onto the address they can forge for, then take it.\n\n"
              + "So the guardian's job is to be the one who collects, with the sweep already moving:\n\n"
              + "• Clean payout → collected normally, straight to your node.\n"
              + "• Reused payout → collected AND swept onward in the same pass, no waiting for a "
              + "confirmation, then checked that it arrived.\n\n"
              + "That is why it needs a destination before it will run at all: a guardian with nowhere "
              + "to sweep to can't win the race, it can only watch."));
        content.addView(helpCard("If the audit service is down",
                "Collecting carries on. Your last good result stays in force, and the app re-checks it "
              + "against your node's own key counters — no internet needed. It only pauses if a key "
              + "has actually signed again since, or after 24 hours without a successful audit."));
        content.addView(helpCard("My stake matured but nothing happened",
                "In order of likelihood:\n"
              + "① The embedded node is not running.\n"
              + "② The guardian isn't started.\n"
              + "③ At-risk stake: rescue isn't set up, or your vault is locked.\n"
              + "④ Your safe destination was flagged REUSED — set a fresh one.\n"
              + "⑤ Auto-collect is off — use Collect on the stake itself.\n\n"
              + "Nothing is lost by waiting: matured coins stay locked to your payout address until "
              + "collected."));
        content.addView(helpCard("The Harden panel",
                "Your node routes change to a random pick of its default addresses. If a reused one is "
              + "still in that rotation, fresh money keeps landing on it. Sweep the coins off, then "
              + "Retire the address, then restart your node. Two minutes."));
    }

    private View helpCard(String title, String body) {
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, title, Design.TEXT(), 15, true));
        c.addView(Ui.text(this, body, Design.DIM(), 12, false));
        return c;
    }

    @Override protected void onStart() {
        super.onStart();
        DirectNodeEvents.subscribe(receiver, "NEWBLOCK", "NEWBALANCE");
    }
    @Override protected void onStop() {
        DirectNodeEvents.unsubscribe(receiver);
        super.onStop();
    }
}
