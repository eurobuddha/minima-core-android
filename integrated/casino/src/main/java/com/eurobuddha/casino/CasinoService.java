package com.eurobuddha.casino;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Foreground service that keeps the casino running when the app is closed: on each new block it
 * auto-reveals bets where the user is house and auto-resolves bets where the user is player, then
 * notifies on reveals and wins/losses. Port of the dapp's service.js.
 */
public class CasinoService extends Service {

    private final Runnable languageChanged = this::refreshLanguageNotification;

    private static final String CH_FG = "casino_fg";
    private static final String CH_ALERT = "casino_alert";
    private static final int FG_ID = 5101;
    private int alertId = 2000;
    private boolean foregroundActive = false;

    private NodeApi node;
    private SecretStore secrets;
    private CasinoTxn txn;
    private AutoProcessor auto;
    private DirectNodeEvents.Listener receiver;

    private String myPubkey = "", myHexAddr = "";
    private final Set<String> myKeys = new HashSet<>();
    private boolean ready = false;
    // Hygiene sweep state — mirrors MainActivity.sweepForeignTracking (see the doc there). The
    // service sweeps too so a polluted node heals even when only the background brain is running;
    // tick() stands down while the Activity is foreground, so the two never sweep concurrently.
    private Set<String> hygieneMine = null;
    private boolean sweepBusy = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        if (!startForegroundCompat()) { stopSelf(); return; }
        // No bet of mine on chain and none just posted: stop at once (after startForeground, which a
        // startForegroundService caller requires) and cancel the fallback job.
        if (!hasWork(this)) { stopIdle(); return; }
        Language.addListener(languageChanged);

        secrets = new SecretStore(this);
        node = new NodeApi(this, enabled -> {});
        CasinoContract.register(node);

        receiver = new DirectNodeEvents.Listener() {
            @Override public void onEvent(JSONObject nodeEvent) {
                try {
                    String event = nodeEvent.optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) tick();
                } catch (Exception ignored) {}
            }
        };
        DirectNodeEvents.subscribe(receiver, "NEWBLOCK", "NEWBALANCE");

        loadIdentity();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguageNotification();
    }

    private void refreshLanguageNotification() {
        createChannels();
        if (foregroundActive) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(FG_ID, foregroundNotification());
        }
    }

    @Override public void onDestroy() {
        Language.removeListener(languageChanged);
        foregroundActive = false;
        super.onDestroy();
        if (node != null) node.onDestroy();
        if (receiver != null) { try { DirectNodeEvents.unsubscribe(receiver); } catch (Exception ignored) {} }
    }

    // ----- identity -----
    private void loadIdentity() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) { myPubkey = r.optString("publickey", ""); myHexAddr = r.optString("address", ""); }
                node.cmd("keys", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) {
                        Object resp = j2.opt("response");
                        JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                                : (resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("keys") : null);
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject k = arr.optJSONObject(i);
                            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) myKeys.add(pk); }
                        }
                        if (!myPubkey.isEmpty()) myKeys.add(myPubkey);
                        ready = !myPubkey.isEmpty() && !myHexAddr.isEmpty();
                        txn = new CasinoTxn(node, secrets, myPubkey, myHexAddr);
                        auto = new AutoProcessor(txn);
                        tick();
                    }
                    @Override public void onError(String m) {}
                });
            }
            @Override public void onError(String m) {}
        });
    }

    // ----- per-block processing -----
    private int lastBlock = 0;

    private void tick() {
        if (!ready || auto == null) return;
        // The foreground Activity owns auto-processing while it's visible — stand down so we don't
        // both post competing reveal/resolve transactions for the same coin.
        if (MainActivity.FOREGROUND) return;
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    if (b.isEmpty()) { JSONObject h = r.optJSONObject("header"); if (h != null) b = h.optString("block", ""); }
                    try { lastBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                }
                fetchAndProcess();
            }
            @Override public void onError(String m) { fetchAndProcess(); }
        });
    }

    private void fetchAndProcess() {
        // depth:4096 = pathological-growth cap above every tree length (stock cascade 2048) — see MainActivity note.
        node.cmd("coins address:" + CasinoContract.SCRIPT_ADDR + " depth:4096", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                List<Bet> bets = new ArrayList<>();
                JSONArray arr = json.optJSONArray("response");
                if (!json.optBoolean("status", false) || arr == null) return;
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Bet b = new Bet(Coin.from(c));
                    if (b.isValid()) bets.add(b);
                }
                boolean mine = false;
                for (Bet b : bets) if (b.isMine(myKeys)) { mine = true; break; }
                recordMine(CasinoService.this, mine);
                auto.process(bets, new HashSet<>(myKeys), lastBlock, listener);
                TimeoutAlerts.update(CasinoService.this, new TimeoutClaims(bets, myKeys, lastBlock));
                sweepForeignTracking();   // shed any foreign coins the node adopted since last block
                if (!mine && !hasWork(CasinoService.this)) stopIdle();
            }
            @Override public void onError(String m) {}
        });
    }

    // ----- hygiene sweep (same contract as MainActivity.sweepForeignTracking: ownership set from
    // `scripts` resolved once, per-block untrack of casino coins we are NOT a party to, abort on
    // partial ownership knowledge — the ports 0/1/8/9 spare-filter is the only own-bet protection) -----
    private void sweepForeignTracking() {
        if (node == null || myKeys.isEmpty()) return;
        if (hygieneMine != null) { untrackForeignBets(hygieneMine); return; }
        final Set<String> mine = new HashSet<>();
        for (String k : myKeys) mine.add(k.toLowerCase());
        if (!myHexAddr.isEmpty()) mine.add(myHexAddr.toLowerCase());
        node.cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!CasinoHygiene.truthy(j, "status") || j.optJSONArray("response") == null) return;
                CasinoHygiene.collectWalletAddressesLower(j.optJSONArray("response"), mine);
                hygieneMine = mine;
                untrackForeignBets(mine);
            }
            @Override public void onError(String m) {}
        });
    }

    private void untrackForeignBets(final Set<String> mineLower) {
        if (sweepBusy) return;
        sweepBusy = true;
        node.cmd("coins relevant:true address:" + CasinoContract.SCRIPT_ADDR, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                untrackNextCoin(CasinoHygiene.coinsToUntrack(j.optJSONArray("response"), mineLower), 0);
            }
            @Override public void onError(String m) { sweepBusy = false; }
        });
    }

    private void untrackNextCoin(final java.util.List<String> coinids, final int i) {
        if (i >= coinids.size()) { sweepBusy = false; return; }
        node.cmd("cointrack enable:false coinid:" + coinids.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { untrackNextCoin(coinids, i + 1); }
            @Override public void onError(String m) { untrackNextCoin(coinids, i + 1); }
        });
    }

    private final AutoProcessor.Listener listener = new AutoProcessor.Listener() {
        @Override public void onOfferMaintained(Bet bet, boolean cancelled) {
            android.util.Log.i("CasinoService", (cancelled ? "Offer cancellation posted: " : "Open offer renewed: ") + bet.coinid());
        }
        @Override public void onRevealed(Bet bet) {
            notifyAlert("Secret revealed", bet.gameName() + " — waiting for player to resolve");
        }
        @Override public void onResolved(Bet bet, boolean iWon, BigDecimal profit, int result) {
            recordHistory(bet, iWon, profit, result);
            String ccy = Currency.nameFor(bet.tokenid());
            notifyAlert(iWon ? "You won +" + Util.miniNum(profit) + " " + ccy + "!"
                            : "You lost -" + Util.miniNum(profit) + " " + ccy,
                    bet.gameName() + " — rolled " + bet.game().pickLabel(result));
        }
        @Override public void onError(String message) {
            if (message.startsWith("Offer keepalive")) android.util.Log.w("CasinoService", message);
        }
    };

    /**
     * Append to the same casino_history store the activity reads, with celebrated=false so the
     * activity shows the modal/anim/sound when it next opens/reloads. De-dupes by coinid.
     */
    private void recordHistory(Bet bet, boolean iWon, BigDecimal profit, int result) {
        boolean isHouse = bet.iAmHouse(myKeys);
        ResolvedBet rb = new ResolvedBet();
        rb.role = isHouse ? "House" : "Player";
        rb.game = bet.gameName();
        rb.range = bet.range;
        rb.pickIdx = bet.pick;
        rb.resultIdx = result;
        rb.pickLabel = bet.game().pickLabel(bet.pick);
        rb.resultLabel = result >= 0 ? bet.game().pickLabel(result) : "—";
        rb.won = iWon;
        rb.profit = Util.miniNum(profit);
        rb.tokenid = bet.tokenid();
        rb.coinid = bet.coinid();
        rb.time = System.currentTimeMillis();
        rb.celebrated = false;
        try {
            JSONArray arr = secrets.history() == null ? new JSONArray() : new JSONArray(secrets.history());
            for (int i = 0; i < arr.length(); i++) {               // de-dupe: skip if already recorded
                JSONObject e = arr.optJSONObject(i);
                if (e != null && bet.coinid().equals(e.optString("coinid"))) return;
            }
            JSONArray out = new JSONArray();
            out.put(rb.toJson());
            for (int i = 0; i < arr.length() && out.length() < SecretStore.HISTORY_CAP; i++) out.put(arr.get(i));
            secrets.putHistory(out.toString());
        } catch (Exception ignored) {}
    }

    // ----- notifications -----
    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel foregroundChannel = nm.getNotificationChannel(CH_FG);
            if (foregroundChannel == null) foregroundChannel = new NotificationChannel(CH_FG, Language.text(this, l10n_casino_background_channel), NotificationManager.IMPORTANCE_LOW);
            foregroundChannel.setName(Language.text(this, l10n_casino_background_channel));
            nm.createNotificationChannel(foregroundChannel);
            NotificationChannel alertChannel = nm.getNotificationChannel(CH_ALERT);
            if (alertChannel == null) alertChannel = new NotificationChannel(CH_ALERT, Language.text(this, l10n_casino_results_channel), NotificationManager.IMPORTANCE_DEFAULT);
            alertChannel.setName(Language.text(this, l10n_casino_results_channel));
            nm.createNotificationChannel(alertChannel);
        }
    }

    private Notification foregroundNotification() {
        return new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("Zero Edge Casino")
                .setContentText(Language.text(this, l10n_casino_background_watching))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private boolean startForegroundCompat() {
        Notification n = foregroundNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            foregroundActive = true;
            return true;
        } catch (Exception unavailable) { return false; }
    }

    @Override public void onTimeout(int startId) { stopGracefully(); }
    @Override public void onTimeout(int startId, int type) { stopGracefully(); }
    private void stopGracefully() {
        foregroundActive = false;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private static final String BG_PREFS = "casino_bg";
    private static final String KEY_MINE = "live_mine";
    private static final String KEY_PENDING_UNTIL = "pending_until";
    /** A posted create/take is not on chain yet: keep watching this long after leaving the screen. */
    static final long PENDING_GRACE_MS = 15 * 60_000;

    /** What the last successful scan of the casino contract's coins found: any bet of mine (house or player). */
    static void recordMine(android.content.Context ctx, boolean mine) {
        try { ctx.getSharedPreferences(BG_PREFS, MODE_PRIVATE).edit().putString(KEY_MINE, mine ? "1" : "0").apply(); }
        catch (Exception ignored) {}
    }

    /** A create or take was just posted from the screen. */
    static void markPending(android.content.Context ctx) {
        try { ctx.getSharedPreferences(BG_PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_PENDING_UNTIL, System.currentTimeMillis() + PENDING_GRACE_MS).apply(); }
        catch (Exception ignored) {}
    }

    /**
     * Work the watcher protects: a bet of mine as house (offer renewal, secret reveal before the
     * deadline) or as player (auto-resolve), or a create/take posted in the last 15 minutes. An
     * unknown state (no scan yet, e.g. right after this update) and unreadable state count as work.
     */
    public static boolean hasWork(android.content.Context ctx) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences(BG_PREFS, MODE_PRIVATE);
            if (System.currentTimeMillis() < p.getLong(KEY_PENDING_UNTIL, 0)) return true;
            return !"0".equals(p.getString(KEY_MINE, ""));
        } catch (Throwable t) {
            return true;
        }
    }

    /** No work: cancel the fallback job and stop. */
    private void stopIdle() {
        AutoProcessWorker.cancel(getApplicationContext());
        stopGracefully();
    }

    private void notifyAlert(String title, String body) {
        Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                .setContentTitle(CasinoMessages.message(title))
                .setContentText(CasinoMessages.message(body))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify("pandamonium.casino", alertId++, n);
    }
}
