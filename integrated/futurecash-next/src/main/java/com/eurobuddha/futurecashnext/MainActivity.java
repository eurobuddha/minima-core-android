package com.eurobuddha.futurecashnext;

import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
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

    private final java.util.Set<AlertDialog> dialogs = new java.util.HashSet<>();
    private LanguageDialog dialog() {
        final Context themed = new androidx.appcompat.view.ContextThemeWrapper(this, Design.isDark()
                ? R.style.pm_futurecash_next_Base_Theme_FutureCash_DialogDark
                : R.style.pm_futurecash_next_Base_Theme_FutureCash_Light);
        return new LanguageDialog(themed) {
            @Override public AlertDialog create() {
                AlertDialog value = super.create();
                dialogs.add(value);
                value.setOnDismissListener(d -> dialogs.remove(value));
                return value;
            }
        };
    }

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
    // Status updates rebuild cards; keep the user's payment draft and selected delay.
    private EditText lockAmount, lockRecipient;
    private int lockPreset = 1;
    private long lockBlocks = 1728;

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
        for (AlertDialog value : new java.util.ArrayList<>(dialogs)) value.dismiss();
        dialogs.clear();
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
        Language.setText(sub, Language.msg(l10n_future_subtitle));

        // A quiet affordance, not a control that competes with the guardian's own state.
        final TextView toggle = findViewById(R.id.pm_futurecash_next_themeToggle);
        toggle.setText(Design.isDark() ? "☀" : "☾");
        Language.description(toggle, Design.isDark() ? Language.msg(l10n_future_light_theme)
                : Language.msg(l10n_future_dark_theme));
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
        final String[] ids = {"guardian", "lock", "activity", "help"};
        final CharSequence[] labels = {Language.msg(l10n_future_guardian), Language.msg(l10n_future_stakes),
                Language.msg(l10n_future_activity), Language.msg(l10n_future_help)};
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            final boolean active = tab.equals(id);
            final TextView t = new TextView(this);
            Language.setText(t, labels[i]);
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
            t.setOnClickListener(v -> { tab = id; buildTabs(); render(); });
            // Keep the selected label visible after a tab change or a longer translation.
            // requestRectangleOnScreen reuses the existing PandaDEX scrolling approach; no focus move.
            if (active) t.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    view.post(() -> {
                        if (view.isAttachedToWindow() && tab.equals(id))
                            view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
                    }));
            tabsBar.addView(t);
        }
    }

    private void renderPairBanner() {
        // Only when the node has actually told us we're not enabled — never on the unknown state,
        // which would be a false alarm at every launch.
        if (!Boolean.FALSE.equals(paired)) { pairBanner.setVisibility(View.GONE); return; }
        pairBanner.setVisibility(View.VISIBLE);
        Language.setText(pairBanner, Language.msg(l10n_future_node_unavailable));
    }

    private void toast(CharSequence s) { runOnUiThread(() -> Toast.makeText(this, s instanceof String ? FutureMessages.message((String) s) : s, Toast.LENGTH_LONG).show()); }

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
            toast(Language.msg(l10n_future_guardian_started));
            render();
        });
    }

    /** Pull fresh state on the worker, then redraw. Every blocking call belongs in here. */
    private String stakeError;
    private void refresh() {
        submit(() -> {
            try {
            final long t = guardian.tip();
            if (t>0 && cfg.is(Cfg.GUARDIAN_ON,false) && cfg.rescueReady()) guardian.reconcile(t);
            final List<Guardian.Stake> s = guardian.listStakes();
            final Audit.Verdict v = new Audit(cfg, node).usable();   // may block on the node — worker only
            final Guardian.Status st = guardian.status(t);           // SQLite — worker only
            runOnUiThread(() -> {
                stakeError=null;
                tip = t; stakes = s; lastVerdict = v; lastStatus = st;
                render();
            });
            } catch (RuntimeException unavailable) {
                runOnUiThread(() -> {
                    stakeError=unavailable.getMessage();
                    if (stakeError==null) stakeError="Could not read the complete stake list. Refresh and retry.";
                    stakes=java.util.Collections.emptyList();
                    render();
                });
            }
        });
    }

    private void render() {
        renderPairBanner();
        content.removeAllViews();
        if (stakeError!=null) {
            LinearLayout error=Ui.card(this,0);
            error.addView(Ui.body(this,FutureMessages.message(stakeError)));
            error.addView(Ui.ghost(this,Language.msg(l10n_future_scan_retry),v -> refresh()));
            content.addView(error);
            return;
        }
        switch (tab) {
            case "lock": renderStakes(); break;
            case "activity": renderActivity(); break;
            case "help": renderHelp(); break;
            default: renderGuardian();
        }
    }

    /* ================= Guardian tab ================= */

    private void renderGuardian() {
        if (lastStatus!=null && lastStatus.walletLocked) {
            LinearLayout warning=Ui.card(this,0);
            warning.addView(Ui.body(this,Language.msg(l10n_future_wallet_rescue_unlock)));
            content.addView(warning);
        }
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

        final CharSequence headline;
        final CharSequence detail;
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
            headline = Language.msg(l10n_future_not_connected);
            detail = Language.msg(l10n_future_start_node);
            colour = Design.RED();
        } else if (badDest != null) {
            headline = Language.msg(l10n_future_safe_reused);
            detail = Language.msg(l10n_future_fresh_destination);
            colour = Design.RED();
        } else if (!on) {
            headline = ready ? Language.msg(l10n_future_guardian_off) : Language.msg(l10n_future_needs_destination);
            detail = ready
                    ? Language.msg(l10n_future_only_open)
                    : Language.msg(l10n_future_pick_destination);
            colour = Design.WARN();
        } else if (lastStatus == null || paired == null) {
            // Guardian is on and configured, but nothing has been read back yet. Say so.
            headline = Language.msg(l10n_future_checking);
            detail = Language.msg(l10n_future_reading_stakes);
            colour = Design.DIM();
        } else if (atRisk > 0) {
            headline = Language.msg(l10n_future_watching_risk, atRisk);
            detail = Language.msg(l10n_future_maturing_rescue);
            colour = Design.WARN();
        } else if (!cfg.is(Cfg.AUTO_COLLECT_SAFE, false) && lastStatus.readySafeN > 0) {
            // "Protected" while collectible money sits untouched is the lie this card must not
            // tell: with auto-collect off, the guardian will NEVER take these — only rescue.
            final int n = (int) lastStatus.readySafeN;
            headline = Language.msg(l10n_future_auto_waiting, n);
            detail = Language.msg(l10n_future_auto_waiting_body);
            colour = Design.WARN();
        } else {
            headline = Language.msg(l10n_future_protected);
            detail = Language.join(Language.msg(l10n_future_watch_closed), cfg.is(Cfg.AUTO_COLLECT_SAFE, false) ? "" : Language.msg(l10n_future_clean_wait));
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
            foot.addView(Ui.text(this, Language.msg(l10n_future_chain), Design.DIM2(), 11, false));
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

        CharSequence msg;
        int colour;
        if (auditRunning) {
            msg = Language.msg(l10n_future_auditing); colour = Design.DIM();
        } else if (bad != null) {
            // Loudest state in the app: the place we sweep RESCUED money to is itself reused. Ranked
            // above key-reuse risk because it breaks the escape route, not just the front door.
            // Full address on its own line — the user needs to recognise WHICH address is burnt,
            // and an abbreviated one cannot be compared against a wallet or an explorer.
            msg = Language.msg(l10n_future_reused_destination, bad[1], bad[0]);
            colour = Design.RED();
        } else if (lastAudit != null && lastAudit.worstReuse > 0) {
            msg = Language.msg(l10n_future_reuse_detected, lastAudit.worstReuse);
            colour = lastAudit.worstReuse > 3 ? Design.RED() : Design.WARN();
        } else if ((lastAudit != null && lastAudit.verdict == KeyAudit.Verdict.RISK_HEURISTIC)
                || cfg.atRisk().length() > 0) {
            msg = Language.msg(l10n_future_risk_recorded);
            colour = Design.WARN();
        } else if (!err.isEmpty()) {
            // Report the failure we actually had. Wrapping every error in "couldn't reach the audit
            // service" told the user to check their network when the real problem was a local one
            // (not enabled in Minima Core), which is the opposite of the fix they needed.
            msg = Language.join(FutureMessages.message(err), " ", v != null && v.ok ? Language.msg(l10n_future_last_good_audit, Language.since(v.at)) : Language.msg(l10n_future_audit_paused));
            colour = (v != null && v.ok) ? Design.WARN() : Design.RED();
        } else if (at == 0) {
            msg = Language.msg(l10n_future_no_audit); colour = Design.DIM();
        } else if (v == null || !v.ok || (lastAudit != null && !lastAudit.complete)) {
            msg = Language.msg(l10n_future_incomplete_audit);
            colour = Design.WARN();
        } else {
            msg = Language.msg(l10n_future_clear_audit);
            colour = Design.SAFE();
        }

        c.addView(Ui.text(this, Language.msg(l10n_future_key_audit), Design.TEXT(), 16, true));
        c.addView(Ui.text(this, msg, colour, 13, false));
        if (at > 0) {
            // "stale" / "reconfirmed" / "partial" are this code's vocabulary, not the user's. Say
            // what the state MEANS for their money, and say nothing at all when it means nothing.
            final CharSequence meta;
            if (v == null) meta = "";
            else if (v.mode == Audit.Mode.RECONFIRMED) meta = Language.msg(l10n_future_reconfirmed);
            else if (!v.ok) meta = Language.msg(l10n_future_fresh_audit_needed);
            else meta = "";
            c.addView(Ui.text(this, Language.msg(l10n_future_audited, Language.since(at), meta), Design.DIM(), 11, false));
        }
        final LinearLayout row = Ui.row(this);
        row.addView(Ui.ghost(this, Language.msg(l10n_future_reaudit), v2 -> maybeAudit(true)));
        c.addView(row);
        return c;
    }

    private View daemonCard() {
        final boolean on = cfg.is(Cfg.GUARDIAN_ON, false);
        final boolean ready = cfg.rescueReady();
        final String safe = cfg.get(Cfg.SAFE_ADDRESS, null);
        final LinearLayout c = Ui.card(this, 0);
        final LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, Language.msg(l10n_future_daemon)));
        head.addView(Ui.spacer(this));
        head.addView(Ui.pill(this, on ? Language.msg(l10n_future_running) : (ready ? Language.msg(l10n_future_off) : Language.msg(l10n_future_needs_setup)),
                on ? Design.SAFE() : Design.WARN()));
        c.addView(head);

        // The old copy said only that stakes "are checked while this app is open", which never
        // explained WHY an unwatched stake is exposed — and the exposure is the whole point. The
        // mechanism has to be in the text: collecting needs no signature, so the clock is not yours
        // to start.
        final CharSequence body;
        if (on) {
            body = Language.msg(l10n_future_daemon_on);
        } else if (!ready) {
            body = Language.msg(l10n_future_daemon_setup);
        } else {
            body = Language.msg(l10n_future_daemon_off);
        }
        c.addView(Ui.body(this, body));
        // Name the destination when running. "Swept to safety" is a promise; showing WHERE lets the
        // user check it rather than take it on faith.
        if (on && safe != null) {
            c.addView(Ui.divider(this));
            final LinearLayout dest = Ui.row(this);
            dest.addView(Ui.text(this, Language.msg(l10n_future_sweeps_to), Design.DIM2(), 11, false));
            dest.addView(Ui.text(this, guardian.wallet().sameNodeMode()
                    ? Language.msg(l10n_future_this_node_copy) : Language.msg(l10n_future_external_copy),
                    Design.DIM2(), 11, false));
            c.addView(dest);
            // The FULL address, never abbreviated — "swept to safety" is a promise, and only the
            // whole value lets the user check it against their wallet rather than take it on faith.
            c.addView(Ui.copyable(this, safe, Design.DIM(), 11));
        }

        final LinearLayout row = Ui.row(this);
        if (on) {
            row.addView(Ui.ghost(this, Language.msg(l10n_future_stop_guardian), v ->
                    setFlag(Cfg.GUARDIAN_ON, false, () -> GuardianService.stop(this))));
        } else {
            row.addView(Ui.cta(this, Language.msg(l10n_future_start_guardian), v -> {
                // HARD GATE. Never let the daemon run when it cannot rescue — that is the state that
                // tells the user they are protected while nothing would happen. Not an error dialog:
                // send them straight to the chooser so the fix is one tap away.
                if (!cfg.rescueReady()) {
                    toast(Language.msg(l10n_future_pick_rescue));
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
        c.addView(Ui.text(this, Language.msg(l10n_future_ready_safe), Design.TEXT(), 16, true));

        final CharSequence detail = st.readySafeN > 0
                ? Language.msg(l10n_future_safe_matured, Ui.amount(String.valueOf(st.readySafeA)), (int) st.readySafeN)
                : Language.msg(l10n_future_no_safe);
        c.addView(Ui.text(this, detail, Design.DIM(), 13, false));

        final boolean auto = cfg.is(Cfg.AUTO_COLLECT_SAFE, false);
        final LinearLayout row = Ui.column(this);
        if (auto) {
            c.addView(Ui.text(this, Language.msg(l10n_future_auto_on), Design.SAFE(), 12, false));
            row.addView(Ui.ghost(this, Language.msg(l10n_future_turn_off), v ->
                    setFlag(Cfg.AUTO_COLLECT_SAFE, false, () -> GuardianService.refreshNotification(this))));
        } else {
            c.addView(Ui.text(this, Language.msg(l10n_future_auto_off), Design.WARN(), 12, false));
            row.addView(Ui.tinted(this, Language.msg(l10n_future_turn_on_auto), Design.SAFE(), v ->
                    setFlag(Cfg.AUTO_COLLECT_SAFE, true, () -> GuardianService.refreshNotification(this))));
        }
        if (st.readySafeN > 0) row.addView(Ui.ghost(this, Language.msg(l10n_future_collect_all), v -> collectAllSafe()));
        c.addView(row);
        return c;
    }

    private View riskCard() {
        final Guardian.Status st = lastStatus != null ? lastStatus : new Guardian.Status();
        final boolean anyRisk = st.atRiskKnown > 0 || st.readyRiskN > 0 || st.pendRiskN > 0;
        final LinearLayout c = Ui.card(this, st.readyRiskN > 0 ? Design.WARN() : 0);
        c.addView(Ui.text(this, Language.msg(l10n_future_risk_stakes), Design.TEXT(), 16, true));

        CharSequence detail;
        if (st.readyRiskN > 0) {
            detail = Language.msg(l10n_future_risk_matured, Ui.amount(String.valueOf(st.readyRiskA)), (int) st.readyRiskN);
        } else if (st.pendRiskN > 0) {
            detail = Language.msg(l10n_future_risk_pending, Ui.amount(String.valueOf(st.pendRiskA)), (int) st.pendRiskN, Ui.maturesIn((long) st.pendRiskSoonest, tip));
        } else {
            detail = anyRisk ? Language.msg(l10n_future_no_risk_watching) : Language.msg(l10n_future_no_risk);
        }
        c.addView(Ui.text(this, detail, Design.DIM(), 13, false));

        final String safe = cfg.get(Cfg.SAFE_ADDRESS, null);
        final boolean rescueOn = cfg.is(Cfg.ENABLED, false);
        if (safe != null) {
            c.addView(Ui.text(this, Language.msg(rescueOn ? l10n_future_rescue_on_copy : l10n_future_destination_copy, guardian.wallet().sameNodeMode() ? Language.msg(l10n_future_this_node) : Language.msg(l10n_future_external)),
                    rescueOn ? Design.SAFE() : Design.DIM(), 12, false));
            c.addView(Ui.copyable(this, safe, rescueOn ? Design.SAFE() : Design.DIM(), 11));
        }
        if (st.vaultLocked) {
            c.addView(Ui.text(this, Language.msg(l10n_future_vault_locked), Design.WARN(), 12, false));
        }

        final LinearLayout row = Ui.column(this);
        row.addView(Ui.tinted(this, safe == null ? Language.msg(l10n_future_setup_rescue) : Language.msg(l10n_future_change_destination), Design.ACCENT(),
                v -> rescueDialog()));
        if (rescueOn) row.addView(Ui.ghost(this, Language.msg(l10n_future_stop_rescue), v -> submit(() -> {
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
        box.addView(Ui.text(this, Language.msg(l10n_future_rescue_where), Design.TEXT(), 15, true));
        box.addView(Ui.text(this, Language.msg(l10n_future_rescue_local_body), Design.DIM(), 12, false));
        final EditText ext = Ui.field(this, Language.msg(l10n_future_external_hint), false);
        box.addView(ext);

        final AlertDialog dlg = dialog()
                .setTitle(Language.msg(l10n_future_rescue_title))
                .setView(box)
                // Backing out means they did NOT agree to set a destination, so drop the pending
                // "start the guardian afterwards" intent — never start it behind a cancel.
                .setNegativeButton(Language.msg(l10n_future_cancel), (d, w) -> startGuardianAfterSetup = false)
                .setOnCancelListener(d -> startGuardianAfterSetup = false)
                .create();

        final LinearLayout actions = Ui.column(this);
        actions.addView(Ui.tinted(this, Language.msg(l10n_future_fresh_local), Design.SAFE(), v -> {
            dlg.dismiss();
            submit(() -> {
                final Wallet.Res r = guardian.wallet().setSafeSameNode();
                if (r.ok) { cfg.setBool(Cfg.ENABLED, true); cfg.log(Cfg.LVL_INFO, "At-risk rescue ENABLED"); finishPendingGuardianStart(); }
                toast(r.ok ? Language.msg(l10n_future_rescue_address, r.value) : r.error);
                runOnUiThread(this::render);
            });
        }));
        actions.addView(Ui.ghost(this, Language.msg(l10n_future_use_external), v -> {
            final String addr = ext.getText().toString().trim();
            dlg.dismiss();
            toast(Language.msg(l10n_future_checking_address));
            submit(() -> auditRunner.checkOne(addr, count -> submit(() -> {
                // count: >0 reused (refuse), 0 clean, -1 unverifiable (accept, re-checked on every audit)
                final Wallet.Res r = guardian.wallet().setSafeExternal(addr, count);
                if (r.ok) { cfg.setBool(Cfg.ENABLED, true); cfg.log(Cfg.LVL_INFO, "At-risk rescue ENABLED"); finishPendingGuardianStart(); }
                toast(r.ok
                        ? Language.msg(l10n_future_external_rescue, addr, count == 0 ? Language.msg(l10n_future_verified_clean) : Language.msg(l10n_future_reuse_unverified))
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
                c.addView(Ui.text(this, Language.msg(l10n_future_retired_coins, n), Design.WARN(), 15, true));
                c.addView(Ui.text(this, Language.msg(l10n_future_recover_body), Design.DIM(), 12, false));
                c.addView(Ui.tinted(this, Language.msg(l10n_future_recover_coins, n), Design.WARN(), v -> submit(() -> {
                    final Wallet.SweepReport r = guardian.wallet().recoverRetiredCoins();
                    toast(r.swept > 0 ? Language.msg(l10n_future_recovered, r.swept, r.dest)
                            : (r.error != null ? r.error : Language.msg(l10n_future_nothing_recover)));
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
                c.addView(Ui.text(this, Language.msg(l10n_future_harden), Design.TEXT(), 16, true));
                c.addView(Ui.text(this, Language.msg(l10n_future_harden_body), Design.DIM(), 12, false));
                int active = 0;
                for (Wallet.ReusedAddr ra : list) {
                    if (!ra.isDefault) continue;
                    active++;
                    final LinearLayout r = Ui.row(this);
                    final LinearLayout info = Ui.column(this);
                    info.addView(Ui.copyable(this, ra.mx.isEmpty() ? ra.hex : ra.mx, Design.TEXT(), 11));
                    info.addView(Ui.text(this, ra.coins == 0 ? Language.msg(l10n_future_empty_retire)
                            : Language.msg(l10n_future_holds_coins, ra.coins),
                            ra.coins == 0 ? Design.DIM() : Design.WARN(), 11, false));
                    info.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    r.addView(info);
                    if (ra.coins == 0) {
                        r.addView(Ui.ghost(this, Language.msg(l10n_future_retire), v -> submit(() -> {
                            final Wallet.Res res = guardian.wallet().retireAddress(ra.hex);
                            toast(res.ok ? Language.msg(l10n_future_retired) : res.error);
                            refresh();
                        })));
                    } else {
                        r.addView(Ui.tinted(this, Language.msg(l10n_future_sweep), Design.SAFE(), v -> submit(() -> {
                            final Wallet.SweepReport res = guardian.wallet().sweepAddressToClean(ra.hex);
                            toast(res.swept > 0 ? Language.msg(l10n_future_swept, res.swept, res.dest)
                                    : (res.error != null ? res.error : Language.msg(l10n_future_nothing_swept)));
                            refresh();
                        })));
                    }
                    c.addView(r);
                }
                if (active == 0) {
                    c.addView(Ui.text(this, Language.msg(l10n_future_all_retired), Design.SAFE(), 12, false));
                }
                c.addView(Ui.text(this, Language.msg(l10n_future_retire_warning), Design.DIM(), 11, false));
                content.addView(c);
            });
        });
    }

    private View keyTableCard() {
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, Language.msg(l10n_future_key_detail), Design.TEXT(), 16, true));
        if (lastAudit == null) {
            c.addView(Ui.text(this, Language.msg(l10n_future_run_audit), Design.DIM(), 12, false));
            return c;
        }
        c.addView(Ui.text(this, Language.join(Language.msg(l10n_future_key_families, lastAudit.classicKeys, lastAudit.blockKeys), lastAudit.unknownKeys == 0 ? "" : Language.msg(l10n_future_unknown_keys, lastAudit.unknownKeys)),
                Design.DIM(), 12, false));
        c.addView(Ui.text(this, Language.msg(l10n_future_both_families),
                Design.DIM(), 12, false));
        for (KeyAudit.Row r : lastAudit.rows) {
            final LinearLayout row = Ui.row(this);
            row.addView(Ui.text(this, "#" + r.index, Design.TEXT(), 12, true));
            CharSequence verdict = r.reused ? Language.msg(l10n_future_reused, r.reuseCount)
                    : r.risk ? Language.msg(l10n_future_at_risk) : r.checked ? Language.msg(l10n_future_no_reuse) : Language.msg(l10n_future_not_checked);
            row.addView(Ui.text(this, verdict,
                    r.reused ? Design.RED() : r.risk || !r.checked ? Design.WARN() : Design.TEXT(), 12, true));
            c.addView(Ui.text(this, (r.family == KeyAudit.Family.CLASSIC ? Language.msg(l10n_future_classic_counter, r.local) : r.family == KeyAudit.Family.BLOCK ? Language.msg(l10n_future_block_position, r.local) : Language.msg(l10n_future_unknown_type, r.local)), Design.DIM(), 11, false));
            c.addView(row);
            // The full address under its verdict — this is the value the user takes to an explorer
            // or a support ticket, and a shortened one cannot be pasted anywhere.
            if ("(address unavailable)".equals(r.address))
                c.addView(Ui.text(this, Language.msg(l10n_future_address_unavailable), Design.DIM(), 10, false));
            else c.addView(Ui.copyable(this, r.address, Design.DIM(), 10));
        }
        c.addView(Ui.text(this, Language.msg(l10n_future_public_only), Design.DIM(), 11, false));
        return c;
    }

    private void maybeAudit(boolean force) {
        if (auditRunning) return;
        final long at = cfg.getLong(Cfg.AUDIT_AT);
        // Refresh once the verdict is nearing expiry, not on every open: the gate needs a verdict
        // under 30 minutes old, so auditing on every launch was load the service never needed.
        final long age = System.currentTimeMillis() - at;
        if (!force && at > 0 && age >= 0 && cfg.get(Cfg.AUDIT_USES, "").startsWith(Audit.FINGERPRINT_VERSION)
                && age < 20 * 60_000L) return;
        auditRunning = true;
        runOnUiThread(this::render);
        submit(() -> auditRunner.run(new AuditRunner.Cb() {
            @Override public void onDone(KeyAudit.Result result, long archiveTip) {
                auditRunning = false;
                lastAudit = result;
                // Audit callbacks may run on the UI thread. refresh() queues listStakes(),
                // which reloads risk (including node reads) on the existing worker.
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
        head.addView(Ui.label(this, Language.msg(l10n_future_your_stakes)));
        head.addView(Ui.spacer(this));
        if (!stakes.isEmpty()) {
            int ready = 0;
            for (Guardian.Stake s : stakes) if (s.ready) ready++;
            head.addView(Ui.pill(this, ready > 0 ? Language.msg(l10n_future_ready_count, ready) : Language.msg(l10n_future_pending_count, stakes.size()),
                    ready > 0 ? Design.SAFE() : Design.DIM()));
        }
        listCard.addView(head);
        listCard.addView(Ui.body(this,Language.msg(l10n_future_scan_sources)));

        if (stakes.isEmpty()) {
            listCard.addView(Ui.body(this, Language.msg(l10n_future_no_stakes)));
        }
        for (Guardian.Stake s : stakes) {
            final LinearLayout rowV = Ui.inset(this);
            final LinearLayout info = Ui.column(this);

            // The amount is the thing being scanned for — mono, large, and the only bright ink here.
            final LinearLayout amtRow = Ui.row(this);
            amtRow.addView(Ui.mono(this, Ui.amount(s.amount), Design.TEXT(), 17, true));
            final TextView unit = Ui.text(this, "0x00".equals(s.tokenid) ? " MINIMA" : Language.msg(l10n_future_token),
                    Design.DIM2(), 11, false);
            unit.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
            amtRow.addView(unit);
            if (s.atRisk) {
                final View sp = new View(this);
                sp.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
                amtRow.addView(sp);
                amtRow.addView(Ui.pill(this, Language.msg(l10n_future_reused_payout), Design.WARN()));
            }
            info.addView(amtRow);
            info.addView(Ui.text(this, Language.msg(s.wallet ? (s.node ? l10n_future_source_both : l10n_future_source_wallet)
                    : l10n_future_source_node), Design.DIM2(),11,false));
            if (s.wallet && s.atRisk && !s.rescueAvailable)
                info.addView(Ui.body(this,Language.msg(l10n_future_wallet_rescue_unlock)));

            info.addView(Ui.text(this, s.ready ? Language.msg(l10n_future_ready_to_collect)
                            : Language.msg(l10n_future_unlocks, Ui.maturesIn(s.matureBlock, tip)),
                    s.ready ? Design.SAFE() : Design.DIM(), 12, false));
            info.addView(Ui.mono(this, Language.msg(l10n_future_payout_block, s.matureBlock, s.payout),
                    Design.DIM2(), 10, false));
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rowV.addView(info);

            // No bare-collect button for an at-risk payout: those belong to the rescue path.
            if (s.ready && !s.atRisk) {
                rowV.addView(Ui.ghost(this, Language.msg(l10n_future_collect), v -> submit(() -> {
                    final Wallet.Res r = guardian.collectNow(s.coinid);
                    toast(r.ok ? Language.msg(l10n_future_collect_posted) : r.error);
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
        c.addView(Ui.text(this, Language.msg(l10n_future_lock_payment), Design.TEXT(), 16, true));
        if (lockAmount == null) lockAmount = Ui.field(this, Language.msg(l10n_future_amount), true);
        final EditText amount = lockAmount;
        attachField(c, amount);

        final CharSequence[] durations = {Language.msg(l10n_future_hour), Language.msg(l10n_future_day),
                Language.msg(l10n_future_week), Language.msg(l10n_future_month)};
        final TextView preview = Ui.text(this, Language.msg(l10n_future_unlock_preview,
                durations[lockPreset], lockBlocks), Design.DIM(), 12, false);
        final LinearLayout presets = Ui.row(this);
        final int[][] opts = {{60, 72}, {1440, 1728}, {10080, 12096}, {43200, 51840}};
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            presets.addView(Ui.ghost(this, Language.join("+", durations[i]), v -> {
                lockPreset = idx;
                lockBlocks = opts[idx][1];
                Language.setText(preview, Language.msg(l10n_future_unlock_preview, durations[idx], lockBlocks));
            }));
        }
        final android.widget.HorizontalScrollView presetScroll = new android.widget.HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.addView(presets);
        c.addView(presetScroll);
        c.addView(preview);

        if (lockRecipient == null) lockRecipient = Ui.field(this, Language.msg(l10n_future_recipient), false);
        final EditText recipient = lockRecipient;
        attachField(c, recipient);

        c.addView(Ui.cta(this, Language.msg(l10n_future_lock), v -> {
            final String amt = amount.getText().toString().trim();
            final String rcp = recipient.getText().toString().trim();
            // Capture the delay alongside amount/recipient; queued work must not read a later edit.
            final long requestedBlocks = lockBlocks;
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
                        toast(Language.msg(l10n_future_resolve_failed)); return;
                    }
                }
                final Wallet.Res res = guardian.createFutureCash(amt, "0x00", requestedBlocks, payoutHex);
                toast(res.ok ? Language.msg(l10n_future_locked, amt, res.value) : res.error);
                if (res.ok) runOnUiThread(() -> clearSubmittedDraft(amt, rcp, requestedBlocks));
                refresh();
            });
        }));
        c.addView(Ui.text(this, Language.msg(l10n_future_lock_warning), Design.DIM(), 11, false));
        return c;
    }

    /** Clear only the submitted draft; keep any edits made while that payment was queued. */
    private void clearSubmittedDraft(String amount, String recipient, long blocks) {
        if (lockAmount == null || lockRecipient == null || lockBlocks != blocks) return;
        if (!amount.equals(lockAmount.getText().toString().trim())
                || !recipient.equals(lockRecipient.getText().toString().trim())) return;
        lockAmount.setText("");
        lockRecipient.setText("");
    }

    private static void attachField(LinearLayout parent, EditText field) {
        final boolean focused = field.hasFocus();
        if (field.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) field.getParent()).removeView(field);
        Ui.styleField(field);
        parent.addView(field);
        if (focused) field.requestFocus();
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
            toast(done > 0 ? Language.msg(l10n_future_collects_posted, done)
                    : (lastErr != null ? lastErr : Language.msg(l10n_future_nothing_collect)));
            refresh();
        });
    }

    /* ================= Activity tab ================= */

    private void renderActivity() {
        final LinearLayout c = Ui.card(this, 0);
        c.addView(Ui.text(this, Language.msg(l10n_future_activity), Design.TEXT(), 16, true));
        final JSONArray log = cfg.logLines();
        if (log.length() == 0) {
            c.addView(Ui.text(this, Language.msg(l10n_future_activity_empty), Design.DIM(), 12, false));
        }
        for (int i = 0; i < log.length(); i++) {
            final JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            final String lvl = e.optString("level", "info");
            final int col = "error".equals(lvl) ? Design.RED() : "warn".equals(lvl) ? Design.WARN() : Design.TEXT();
            final String msg = e.optString("msg", "");
            final String[] parts = msg.split(" \\| ", 2);
            c.addView(Ui.text(this, FutureMessages.message(parts[0]), col, 12, false));
            if (parts.length > 1) c.addView(Ui.text(this, FutureMessages.message(parts[1]), Design.DIM(), 10, false));
        }
        c.addView(Ui.ghost(this, Language.msg(l10n_future_reset_history), v -> {
            dialog()
                    .setTitle(Language.msg(l10n_future_reset_title))
                    .setMessage(Language.msg(l10n_future_reset_body))
                    .setPositiveButton(Language.msg(l10n_future_reset), (d, w) -> submit(() -> {
                        store.resetHistory();
                        cfg.clearLog();
                        refresh();
                    }))
                    .setNegativeButton(Language.msg(l10n_future_cancel), null)
                    .show();
        }));
        content.addView(c);
    }

    /* ================= Help tab ================= */

    private void renderHelp() {
        content.addView(helpCard(Language.msg(l10n_future_help_quick),
                Language.msg(l10n_future_help_quick_body)));
        content.addView(helpCard(Language.msg(l10n_future_help_race),
                Language.msg(l10n_future_help_race_body)));
        content.addView(helpCard(Language.msg(l10n_future_help_outage),
                Language.msg(l10n_future_help_outage_body)));
        content.addView(helpCard(Language.msg(l10n_future_help_matured),
                Language.msg(l10n_future_help_matured_body)));
        content.addView(helpCard(Language.msg(l10n_future_help_harden),
                Language.msg(l10n_future_help_harden_body)));
    }

    private View helpCard(CharSequence title, CharSequence body) {
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
