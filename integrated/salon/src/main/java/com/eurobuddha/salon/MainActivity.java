package com.eurobuddha.salon;
import com.eurobuddha.minimacore.i18n.Language;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Salon — a decentralised, media-rich social page. Your identity is a 1/1
 * signed token; your page is a profile.json (with images, video and music, all
 * hosted URLs) on your own server, edited freely. A shared on-chain address is
 * the town square: post a pointer, browse everyone, follow, and pull a feed.
 */
public class MainActivity extends AppCompatActivity {

    private NodeApi node;
    private final ExecutorService io = Executors.newFixedThreadPool(3);

    // Persistent audio: the player and its docked mini-bar outlive screen changes.
    private MediaPlayer audio;
    private final Handler playback = new Handler(Looper.getMainLooper());
    private LinearLayout miniPlayer;
    private TextView miniTitle, miniPlay, miniTime;
    private SeekBar miniBar;
    private boolean miniSeeking = false;

    private enum Screen { FEED, DISCOVER, HOME, VIEW, EDIT, ONBOARD, SETTINGS, HOSTING, HOSTING_EDIT, MESSAGES, THREAD }
    private Screen screen = Screen.HOME;
    private String threadPeer = "";   // peer msgpk for the open THREAD screen
    // Reply state, TAGGED to the thread it belongs to (replyPeer) so a reply
    // started in one chat can never leak into another's composer/DM. Empty = not replying.
    private String replyBody = "", replyFrom = "", replyPeer = "";
    // In-progress compose text, preserved across the frequent thread re-renders
    // (inbound DMs / acks) so a half-typed message isn't wiped. Cleared on peer change / send.
    private String threadDraft = "";
    private int threadRenderedCount = -1;   // last rendered TOTAL count; auto-scroll only when it grows
    private static final int THREAD_WINDOW_STEP = 200;
    private int threadWindow = THREAD_WINDOW_STEP;   // how many recent messages to render; "Load earlier" grows it

    private LinearLayout appbar, navRow, body;
    private int mLastKb = 0;   // last real keyboard height, latched through spurious 0-insets
    private static final String XPORT_PREF = "xport_";   // per-contact transport override
    /** One tip in flight at a time. Tips move REAL value and have no node-side
     *  idempotency, so guard the whole check-balance→send round-trip. */
    private volatile boolean mTipInFlight = false;
    private ScrollView scroll;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private FrameLayout rootFrame;
    private TextView nodeChip;
    private boolean nodeUp = false;
    private boolean wasNodeUp = false;      // to re-render node-gated screens on connect
    /** Single-driver invariant for the beacon keep-alive: while the Activity is resumed its own
     *  tick drives keepAlive and SalonKeepAliveWorker stays idle (openly's FOREGROUND pattern). */
    public static volatile boolean FOREGROUND = false;
    private String expandedNft = null;      // tokenid of the showcase holding currently expanded
    private final ProofRequests proofRequests = new ProofRequests(android.os.SystemClock::elapsedRealtime);
    private final java.util.Map<String, String> mLiveIcon = new java.util.concurrent.ConcurrentHashMap<>();
    private String pubkey = "";
    private boolean adoptChecked = false;
    private boolean reannouncedThisSession = false;

    // Minima's provably-unspendable "RETURN FALSE" graveyard — burning a coin
    // here destroys it forever (same address Atelier's StateNft.buryCommands uses).
    private static final String GRAVEYARD = "0xABA005476D2B3CD7F251B9783E64C124C9670BB358695F04D91B2057BB64CB49";

    private Hosting.Profile hostEdit;
    private TextView claimBtn; private boolean claiming = false;
    private JSONObject viewProfile;        // profile being viewed (someone else)
    private SalonRegistry.Entry viewEntry; // registry entry for the viewed profile

    /* ---------------- lifecycle ---------------- */

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Refresh the embedded host transport; its service owns start and stop.
        MaximaLink.connect(this);

        NostrKeys.init(getApplicationContext());   // Blossom uploads sign off the io executor, no Context there
        // Warm the derived-pubkey cache off the main thread — first derivation does a
        // Keystore decrypt + EC scalar-mult, and the Blossom editor renders it inline.
        io.execute(() -> { try { NostrKeys.pubkeyHex(); } catch (Throwable ignored) { } });

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Design.PAPER());
        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);

        appbar = new LinearLayout(this); appbar.setOrientation(LinearLayout.VERTICAL);
        appbar.setTag("pandamonium.app.toolbar");
        appbar.setBackgroundColor(Design.PAPER());
        chrome.addView(appbar, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(28));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        swipe = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this);
        swipe.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        swipe.setColorSchemeColors(Design.ACCENT());
        swipe.setOnRefreshListener(() -> { mProfileCache.clear(); mRegistryCache = null; render(); swipe.setRefreshing(false); });   // pull-to-refresh = fetch latest
        chrome.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));

        buildMiniPlayer();
        chrome.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        navRow = new LinearLayout(this); navRow.setOrientation(LinearLayout.VERTICAL);
        navRow.setBackgroundColor(Design.PAPER());
        chrome.addView(navRow, new LinearLayout.LayoutParams(-1, -2));

        rootFrame.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), rootFrame);
        wic.setAppearanceLightStatusBars(true); wic.setAppearanceLightNavigationBars(true);
        final LinearLayout chromeRef = chrome;
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVis = insets.isVisible(WindowInsetsCompat.Type.ime());
            // The keyboard inset arrives correctly (e.g. 972) but is sometimes
            // immediately followed by a spurious ime.bottom=0 dispatch while the
            // keyboard is STILL up. Latch on the visibility flag: hold the last
            // real height through the spurious 0 so the UI doesn't drop back down.
            int kb;
            if (imeVis) {
                int now = Math.max(0, ime.bottom - sys.bottom);
                if (now > 0) mLastKb = now;
                kb = mLastKb;
            } else {
                kb = 0; mLastKb = 0;
            }
            appbar.setPadding(0, sys.top, 0, 0);
            navRow.setPadding(0, 0, 0, sys.bottom);
            // LIFT THE WHOLE UI above the keyboard. Padding the scroll's INTERIOR
            // didn't work: with edge-to-edge (setDecorFitsSystemWindows=false) the
            // window doesn't resize for the IME on Android 15, so the ScrollView's
            // on-screen bottom edge still sits UNDER the keyboard and the field
            // scrolls to a spot the keyboard covers. Padding chrome's bottom by the
            // keyboard height physically raises the scroll (and nav/mini-player)
            // above the keyboard; then the focused field is scrolled into view.
            chromeRef.setPadding(0, 0, 0, kb);
            scroll.setPadding(0, 0, 0, 0);
            if (kb > 0) {
                final View f = getCurrentFocus();
                if (f != null) {
                    f.post(() -> f.requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, f.getWidth(), f.getHeight()), false));
                }
            }
            return insets;
        });

        node = new NodeApi(this, enabled -> runOnUiThread(() -> { nodeUp = enabled; onNode(); }));
        startPairingRetry();   // re-broadcast REGISTER while unpaired (recovers if Salon starts before Minima Core, e.g. after reboot)
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        if (SalonStore.hasIdentity(this)) screen = Screen.HOME;
        render();
        refreshUnread();   // seed the nav badge without a UI-thread DB query

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                switch (screen) {   // walk UP the logical stack instead of exiting the app
                    case THREAD:       go(Screen.MESSAGES); break;
                    case VIEW:         go(Screen.DISCOVER); break;
                    case EDIT:         go(Screen.HOME); break;
                    case HOSTING:      go(Screen.SETTINGS); break;
                    case HOSTING_EDIT: go(Screen.HOSTING); break;
                    default:           setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true);
                }
            }
        });
    }

    private void onNode() {
        if (nodeChip != null) Language.setText(nodeChip, nodeUp ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_connected) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_node_not_ready));
        if (!nodeUp) startPairingRetry();   // keep re-registering after a disconnect
        if (nodeUp && pubkey.isEmpty()) fetchPubkey();
        // One-time-per-session setup (relay TTL, mail key, first inbox scan).
        if (nodeUp && !reannouncedThisSession && SalonStore.hasIdentity(this)) {
            reannouncedThisSession = true;
            touchOwnRelay();   // refresh the 7-day TTL on any relay-hosted content
            ensureMailKey();
            ensureInboxAndScan();
        }
        // Gossip mesh: re-post any FADED pointer (own + a few others) so the square stays
        // alive. Must run REPEATEDLY (on every connect + on a foreground timer), not once —
        // a beacon prunes ~a day, so an app open once and left running has to keep renewing
        // it. keepAlive no-ops unless something has actually faded (on-chain check).
        if (nodeUp && SalonStore.hasIdentity(this)) reannounceNow();
        // The first render() runs at onCreate before the node connects; node-gated
        // content (the holdings showcase, the tip/message bar) was drawn in its
        // "no node" state. Re-render once, on the false->true transition, so it refreshes.
        if (nodeUp && !wasNodeUp && (screen == Screen.HOME || screen == Screen.VIEW || screen == Screen.DISCOVER || screen == Screen.MESSAGES)) render();
        if (nodeUp && !adoptChecked && !claiming && !SalonStore.hasIdentity(this)) { adoptChecked=true; checkIdentityAdoption(); }
        wasNodeUp = nodeUp;
    }

    /** Scan the inbox (tips + DMs) — but only once the tip address is captured. On a fresh
     *  identity getaddress is async, so we scan INSIDE its callback (fixes the race where a
     *  new device never scanned because tipaddr was still empty). */
    private void ensureInboxAndScan() {
        String addr = SalonStore.get(this, "tipaddr");
        if (!addr.isEmpty()) { scanTips(); scanMail(); return; }
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                JSONObject resp = r.optJSONObject("response");
                String a = resp == null ? "" : resp.optString("address", "");
                if (!a.isEmpty()) runOnUiThread(() -> { SalonStore.put(MainActivity.this, "tipaddr", a); scanTips(); scanMail(); });
            }
            @Override public void onError(String m) {}
        });
    }

    /** Derive our app-side messaging identity once (libsodium, no node/Maxima) and publish
     *  its public id as msgpk so others can seal DMs to us. */
    private void ensureMailKey() {
        io.execute(() -> {
            try { SalonComms.publicId(MainActivity.this); }
            catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); }
        });
    }

    /** Scan on-chain mail at our address; store new DMs + refresh the badge/thread. */
    private void scanMail() {
        final String addr = SalonStore.get(this, "tipaddr");
        if (addr.isEmpty()) return;
        final LocalEcCryptoProvider identity;
        try { identity = SalonComms.crypto(this); }
        catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }
        MinimaMail.scan(node, identity, addr, msgs -> io.execute(() -> {   // DB writes off the UI thread
            MailDb db = MailDb.get(MainActivity.this);
            int newCount = 0; String lastFrom = "", lastBody = "";
            for (MinimaMail.Msg m : msgs) {
                if (m.coinid.isEmpty()) continue;
                if (!m.valid) continue;   // anonymous seal — drop messages whose sender signature doesn't verify (anti-impersonation)
                String preview = !m.body.isEmpty() ? m.body : (!m.mediaRef.isEmpty() ? "📎 media" : "");
                // Dedup on the sender's stable app id when present (agrees with the
                // Maxima path + the NOTIFY intake), so one logical message is one
                // bubble regardless of which transport(s) carried it.
                String dedupId = m.stableId == null || m.stableId.isEmpty()
                        ? m.coinid : "id-" + m.fromPublicId + "-" + m.stableId;
                boolean isNew = db.insert(dedupId, m.fromPublicId, false, m.body, m.mediaRef, m.mediaMime, m.ts, m.valid);
                if (m.replyBody != null && !m.replyBody.isEmpty()) db.setReply(dedupId, m.replyBody, m.replyFrom);
                db.setMxAddr(m.fromPublicId, m.peerMx);   // learn their Maxima address from an on-chain DM too (sticky, no-op if absent)
                if (isNew) {
                    db.upsertContact(m.fromPublicId, m.fromHandle, "", m.fromAddr, preview, m.ts, !m.fromPublicId.equals(threadPeer));
                    newCount++; lastFrom = m.fromHandle; lastBody = preview;
                }
            }
            final int fc = newCount; final String lf = lastFrom, lb = lastBody; final int unread = db.totalUnread();
            runOnUiThread(() -> {
                unreadCache = unread;
                if (fc > 0) {
                    if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
                    else { buildNav(); toast(Language.join("💬 ", fc == 1 ? "@" + lf.replaceFirst("^@", "") + ": " + lb : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_chat_new_messages, fc))); }
                } else buildNav();
            });
        }));
    }

    private int unreadCache = 0;
    /** Refresh the cached unread count off the UI thread and repaint the nav badge. */
    private void refreshUnread() {
        io.execute(() -> { int u = MailDb.get(MainActivity.this).totalUnread(); runOnUiThread(() -> { unreadCache = u; if (navRow != null) buildNav(); }); });
    }

    /** Detect inbound tips at our address and announce new ones. Baselines silently
     *  on first run so old coins don't spam; thereafter new coinids → "X tipped you". */
    private void scanTips() {
        final String addr = SalonStore.get(this, "tipaddr");
        if (addr.isEmpty()) return;
        node.cmd("coinnotify action:add address:" + addr, new NodeApi.Cb() { public void onResult(JSONObject j) {} public void onError(String m) {} });
        node.cmd("coins address:" + addr + " order:desc depth:200", new NodeApi.Cb() {   // bounded: the tip address is public and could be dust-flooded
            @Override public void onResult(JSONObject j) {
                final JSONArray arr = j.optJSONArray("response"); if (arr == null) return;
                runOnUiThread(() -> {
                    JSONArray seen = SalonStore.arr(MainActivity.this, "tipseen");
                    java.util.HashSet<String> seenSet = new java.util.HashSet<>();
                    for (int i = 0; i < seen.length(); i++) seenSet.add(seen.optString(i));
                    boolean baselined = "1".equals(SalonStore.get(MainActivity.this, "tipbaselined"));
                    java.util.List<CharSequence> fresh = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i); if (c == null) continue;
                        String cid = c.optString("coinid", ""); if (cid.isEmpty() || seenSet.contains(cid)) continue;
                        seenSet.add(cid); seen.put(cid);
                        if (baselined) fresh.add(describeTip(c));
                    }
                    SalonStore.setArr(MainActivity.this, "tipseen", capArr(seen, 500));
                    if (!baselined) SalonStore.put(MainActivity.this, "tipbaselined", "1");
                    for (CharSequence d : fresh) toast(d);
                });
            }
            @Override public void onError(String m) {}
        });
    }

    private CharSequence describeTip(JSONObject coin) {
        String amount = coin.optString("tokenamount", "");
        if (amount.isEmpty()) amount = coin.optString("amount", "?");
        String tokenid = coin.optString("tokenid", TipTransport.MINIMA);
        String from = "", note = "";
        JSONArray st = coin.optJSONArray("state");
        if (st != null) for (int k = 0; k < st.length(); k++) {
            JSONObject s = st.optJSONObject(k); if (s == null) continue;
            int p = s.optInt("port", -1); String v = SalonRegistry.unhex(s.optString("data", ""));
            if (p == 1 && !v.isEmpty()) from = v; else if (p == 2) note = v;
        }
        CharSequence sender = from.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_sender) : "@" + from.replaceFirst("^@", "");
        return Language.join("💰 ", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_tip_from, sender, amount, TipTransport.label(tokenid)), note.isEmpty() ? "" : " — " + note);
    }

    private static boolean arrEmpty(JSONArray a) { return a == null || a.length() == 0; }
    private static boolean emptyProfile(JSONObject p) {
        return p.optString("about", "").isEmpty() && arrEmpty(p.optJSONArray("links"))
                && arrEmpty(p.optJSONArray("gallery")) && arrEmpty(p.optJSONArray("posts")) && arrEmpty(p.optJSONArray("nfts"));
    }

    /** Keep only the most-recent {@code max} entries of a persisted seen-id list (unbounded growth guard). */
    private static JSONArray capArr(JSONArray a, int max) {
        if (a == null || a.length() <= max) return a;
        JSONArray out = new JSONArray();
        for (int i = a.length() - max; i < a.length(); i++) out.put(a.opt(i));
        return out;
    }

    /* ---------------- tipping UI ---------------- */

    private void tipDialog(String handle, String toAddr) {
        if (!nodeUp) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_connect)); return; }
        final String[] tokenid = { TipTransport.MXUSD };   // default dollar-pegged
        LinearLayout box = dialogBox();
        final TextView curBtn = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_currency, "mxUSD"), false);
        box.addView(curBtn, lph(46, 0, 2, 0, 8));
        final EditText amount = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_amount), "", false, "1");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final LinearLayout presets = row();
        for (String amt : new String[]{"1", "5", "20"}) {
            TextView pb = Design.button(this, "$" + amt, false);
            pb.setOnClickListener(v -> amount.setText(amt));
            presets.addView(pb, weight(40, 0, amt.equals("20") ? 0 : 6));
        }
        box.addView(presets, lp(0, 6, 0, 4));
        final TextView bal = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_checking));
        box.addView(bal, lp(0, 0, 0, 4));
        final int[] balanceRequest = {0};
        final Runnable refreshBal = () -> {
            final int request = ++balanceRequest[0];
            final String requestedToken = tokenid[0];
            Language.setText(bal, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_checking));
            node.cmd("balance tokenid:" + requestedToken, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    String amount = null;
                    if (j != null && Boolean.TRUE.equals(j.opt("status"))) {
                        JSONArray rows = j.optJSONArray("response");
                        if (rows != null && rows.length() == 0) amount = "0";
                        else if (rows != null && rows.length() == 1) {
                            JSONObject row = rows.optJSONObject(0);
                            if (row != null && Args.isDecimal(row.optString("sendable", ""))) amount = row.optString("sendable");
                        }
                    }
                    final String have = amount;
                    runOnUiThread(() -> {
                        if (request != balanceRequest[0] || !requestedToken.equals(tokenid[0]) || isFinishing() || isDestroyed()) return;
                        Language.setText(bal, have == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_unavailable) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_available, have, TipTransport.label(requestedToken)));
                    });
                }
                @Override public void onError(String ignored) {
                    runOnUiThread(() -> {
                        if (request == balanceRequest[0] && requestedToken.equals(tokenid[0]) && !isFinishing() && !isDestroyed()) Language.setText(bal, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_unavailable));
                    });
                }
            });
        };
        refreshBal.run();
        final EditText note = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_note), "", false, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_note_hint));
        curBtn.setOnClickListener(v -> {
            tokenid[0] = tokenid[0].equals(TipTransport.MXUSD) ? TipTransport.MINIMA : TipTransport.MXUSD;
            Language.setText(curBtn, Language.upper(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_currency, TipTransport.label(tokenid[0]))));
            presets.setVisibility(tokenid[0].equals(TipTransport.MXUSD) ? View.VISIBLE : View.GONE);
            refreshBal.run();
        });
        new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_title, handle)).setView(box)
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_send), (d, w) -> sendTip(handle, toAddr, tokenid[0], text(amount).trim(), text(note).trim()))
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel), null).show();
    }

    private void sendTip(String handle, String toAddr, String tokenid, String amount, String note) {
        if (!Args.isDecimal(amount)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_valid_amount)); return; }
        final java.math.BigDecimal amt;
        try { amt = new java.math.BigDecimal(amount); } catch (Exception e) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_valid_amount)); return; }
        if (amt.signum() <= 0) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_enter_amount)); return; }
        if (mTipInFlight) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_busy)); return; }
        mTipInFlight = true;
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_checking));
        node.cmd("balance tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                String sendable = "0";
                try { JSONArray a = j.optJSONArray("response"); if (a != null && a.length() > 0) sendable = a.optJSONObject(0).optString("sendable", "0"); } catch (Exception ignored) {}
                java.math.BigDecimal haveBd; try { haveBd = new java.math.BigDecimal(sendable); } catch (Exception e) { haveBd = java.math.BigDecimal.ZERO; }
                final java.math.BigDecimal have = haveBd;
                runOnUiThread(() -> {
                    if (amt.compareTo(have) > 0) { mTipInFlight = false; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_insufficient, TipTransport.label(tokenid), have.toPlainString())); return; }
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_sending));
                    TipTransport.tip(node, toAddr, amount, tokenid, "@" + SalonStore.get(MainActivity.this, "handle"), note, new TipTransport.Cb() {
                        @Override public void onSent(String txpowid) { runOnUiThread(() -> { mTipInFlight = false; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_submitted, handle)); }); }
                        @Override public void onFailed(String msg) { runOnUiThread(() -> { mTipInFlight = false; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_failed, HoldingMessages.message(msg))); }); }
                    });
                });
            }
            @Override public void onError(String m) { runOnUiThread(() -> { mTipInFlight = false; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_balance_failed, HoldingMessages.message(m))); }); }
        });
    }

    /* ================= Messages (DMs over Minima Mail) ================= */

    private CharSequence replyAuthor(String value) {
        if (value == null || value.isEmpty()) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_reply);
        return "You".equals(value) ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_you) : value;
    }

    private void openThread(String peerpk, String handle, String avatar, String addr) {
        if (peerpk == null || peerpk.isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_cannot_receive)); return; }
        // Switching to a different peer: drop any in-progress reply and draft so
        // one conversation's private text can never carry into another's.
        if (!peerpk.equals(threadPeer)) { cancelMediaRequests(); replyBody = ""; replyFrom = ""; replyPeer = ""; threadDraft = ""; threadRenderedCount = -1; threadWindow = THREAD_WINDOW_STEP; }
        MailDb db = MailDb.get(this);
        MailDb.Contact ex = db.contact(peerpk);
        db.upsertContact(peerpk, handle, avatar, addr, ex != null ? ex.lastbody : "", ex != null ? ex.lastts : 0, false);
        threadPeer = peerpk;
        db.clearUnread(peerpk);
        refreshUnread();
        go(Screen.THREAD);
    }

    private void renderMessages() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_messages));
        if (!SalonStore.hasIdentity(this)) { body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_claim_first)), lp(0, 0, 0, 12)); return; }
        List<MailDb.Contact> cs = MailDb.get(this).contacts();
        if (cs.isEmpty()) {
            LinearLayout c = card();
            c.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_empty)));
            body.addView(c, lp(0, 0, 0, 12));
            return;
        }
        for (MailDb.Contact ct : cs) {
            LinearLayout c = card(); c.setClickable(true); Design.pressable(c);
            final MailDb.Contact fct = ct;
            c.setOnClickListener(v -> openThread(fct.peerpk, fct.handle, fct.avatar, fct.addr));
            LinearLayout r = row();
            r.addView(avatarView(ct.avatar, ct.handle == null ? "?" : ct.handle, 44));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(Design.text(this, ct.handle == null || ct.handle.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_unknown_contact) : "@" + ct.handle.replaceFirst("^@", ""), 15, Design.INK(), Design.sansBold()));
            TextView prev = Design.text(this, ct.lastbody == null ? "" : ct.lastbody, 12.5f, Design.DIM(), Design.sans()); prev.setMaxLines(1); prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(prev);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (ct.unread > 0) r.addView(Design.pill(this, String.valueOf(ct.unread), Design.PILL_MINE));
            c.addView(r);
            body.addView(c, lp(0, 0, 0, 10));
        }
    }

    private void renderThread() {
        if (threadPeer.isEmpty()) { go(Screen.MESSAGES); return; }
        MailDb db = MailDb.get(this);
        MailDb.Contact ct = db.contact(threadPeer);
        String handle = ct != null && ct.handle != null ? ct.handle.replaceFirst("^@", "") : "";
        masthead(handle.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_unknown_contact) : "@" + handle);
        db.clearUnread(threadPeer); refreshUnread();
        LinearLayout bar = row();
        bar.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_back), false, () -> go(Screen.MESSAGES)), weight(-2, 0, 0));
        body.addView(bar, lp(0, 0, 0, 8));
        // Which transport carries messages to THIS contact — tap to override.
        String xport = SalonStore.get(this, XPORT_PREF + threadPeer);
        boolean hasMxAddr = ct != null && ct.mxaddr != null && !ct.mxaddr.isEmpty();
        boolean usingMaxima = "maxima".equals(xport) || (!"chain".equals(xport) && hasMxAddr);
        CharSequence forced = "maxima".equals(xport) || "chain".equals(xport) ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_forced) : "";
        TextView chip = Design.pill(this,
                Language.join(usingMaxima ? (!hasMxAddr ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_maxima_no_address) : MaximaLink.isReady(this) ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_maxima) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_maxima_waiting)) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_chain), forced, "  ▾"),
                usingMaxima ? Design.PILL_MINE : Design.PILL_DIM);
        chip.setOnClickListener(v -> transportDialog());
        LinearLayout tr = row();
        tr.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        body.addView(tr, lp(0, 0, 0, 8));
        final String peerHandle = handle;
        // Windowed: render only the most-recent threadWindow messages so a long thread
        // doesn't rebuild hundreds of View bubbles (and hold them all in heap) on every
        // ack/inbound re-render. "Load earlier" grows the window on demand.
        int total = db.messageCount(threadPeer);
        List<MailDb.Message> msgs = db.messages(threadPeer, threadWindow);
        if (msgs.isEmpty()) body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_thread_empty)), lp(0, 0, 0, 10));
        if (total > msgs.size()) {
            body.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_earlier, total - msgs.size()), false,
                    () -> { threadWindow += THREAD_WINDOW_STEP; render(); }), lp(0, 0, 0, 6));
        }
        for (MailDb.Message m : msgs) {
            View bv = bubble(m);
            final MailDb.Message fm = m;
            bv.setOnLongClickListener(v -> { startReply(fm, peerHandle); return true; });
            body.addView(bv);
        }
        LinearLayout comp = card();
        // Reply banner — the quoted parent, with a cancel, shown above the field.
        // Only for a reply that belongs to THIS thread (never another peer's).
        if (!replyBody.isEmpty() && threadPeer.equals(replyPeer)) {
            LinearLayout rb = row(); rb.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 1)); rb.setPadding(dp(10), dp(7), dp(8), dp(7));
            LinearLayout rcol = new LinearLayout(this); rcol.setOrientation(LinearLayout.VERTICAL);
            rcol.addView(Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_replying, replyAuthor(replyFrom)), 10f, Design.ACCENT(), Design.sansBold()));
            TextView rsnip = Design.text(this, replyBody, 12f, Design.DIM(), Design.sans()); rsnip.setMaxLines(1); rsnip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            rcol.addView(rsnip);
            rb.addView(rcol, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = Design.text(this, "✕", 16, Design.DIM(), Design.sansBold()); x.setPadding(dp(10), 0, dp(4), 0); x.setClickable(true);
            x.setOnClickListener(v -> { replyBody = ""; replyFrom = ""; render(); });
            rb.addView(x);
            comp.addView(rb, lp(0, 0, 0, 8));
        }
        final EditText input = fieldMulti(comp, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_chat_message), threadDraft);   // restore any preserved draft
        input.addTextChangedListener(watch(s -> threadDraft = s));          // keep the draft current across re-renders
        LinearLayout crow = row();
        crow.addView(btn("📎", false, this::attachDmMedia), new LinearLayout.LayoutParams(dp(54), -2));
        crow.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_send), true, () -> sendDm(text(input), "", "")), weight(-2, 6, 0));
        comp.addView(crow, lp(0, 6, 0, 0));
        body.addView(comp, lp(0, 8, 0, 0));
        // Auto-scroll to the newest only when the message set actually grew (fresh
        // open or a new message) — not on an ack re-render, so we don't yank a user
        // who's reading back through history.
        boolean grew = total != threadRenderedCount;   // a NEW message (total up), not "load earlier" (window up)
        threadRenderedCount = total;
        if (grew) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Begin a quoted reply to {@code m}; the banner + send stamp the snapshot. */
    private void startReply(MailDb.Message m, String peerHandle) {
        String b = m.body != null && !m.body.isEmpty() ? m.body : (m.media != null && !m.media.isEmpty() ? "📎 media" : "");
        if (b.isEmpty()) return;
        replyBody = b;
        replyFrom = m.mine ? "You" : "@" + (peerHandle == null ? "" : peerHandle.replaceFirst("^@", ""));
        replyPeer = threadPeer;   // this reply belongs to the current thread only
        render();
    }

    private View bubble(MailDb.Message m) {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Design.ruled(this, m.mine ? Design.ACCENT() : Design.CARD(), Design.INK(), 1));
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        // Quoted parent (denormalised snapshot carried with the reply).
        if (m.replyBody != null && !m.replyBody.isEmpty()) {
            LinearLayout q = new LinearLayout(this); q.setOrientation(LinearLayout.VERTICAL);
            int accent = m.mine ? Design.PAPER() : Design.ACCENT();
            q.setBackground(Design.ruled(this, m.mine ? Design.ACCENT() : Design.PAPER(), accent, 1));
            q.setPadding(dp(8), dp(5), dp(8), dp(6));
            q.addView(Design.text(this, Language.join("↩ ", replyAuthor(m.replyFrom)), 9.5f, accent, Design.sansBold()));
            TextView qs = Design.text(this, m.replyBody, 12f, m.mine ? Design.PAPER() : Design.DIM(), Design.sans()); qs.setMaxLines(2); qs.setEllipsize(android.text.TextUtils.TruncateAt.END);
            q.addView(qs);
            b.addView(q, lp(0, 0, 0, 6));
        }
        if (m.body != null && !m.body.isEmpty())
            b.addView(Design.text(this, m.body, 14.5f, m.mine ? Design.PAPER() : Design.INK(), Design.sans()));
        if (m.media != null && !m.media.isEmpty()) {
            JSONObject mm = new JSONObject();
            try { mm.put("type", m.mime != null && m.mime.contains("video") ? "video" : m.mime != null && m.mime.contains("audio") ? "audio" : "image"); mm.put("url", m.media); mm.put("caption", ""); } catch (Exception ignored) {}
            b.addView(mediaCard(mm), lp(0, m.body != null && !m.body.isEmpty() ? 6 : 0, 0, 0));
        }
        // Show delivery state on my own bubbles: an unconfirmed send reads
        // "sending…" (it's PENDING in the outbox) so nothing looks delivered
        // when it isn't; a confirmed one shows the timestamp.
        CharSequence meta = outgoingStatus(m);
        if(m.mine && (m.status==MailDb.UNCERTAIN || (m.status==MailDb.PENDING && !outgoingOwner(m)))) b.setOnClickListener(v->reviewOutgoing(m));
        if (meta.length() != 0) b.addView(Design.text(this, meta, 9.5f, m.mine ? Design.PAPER() : Design.DIM(), Design.mono()), lp(0, 3, 0, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(m.mine ? dp(52) : 0, 0, m.mine ? 0 : dp(52), dp(6));
        b.setLayoutParams(blp);
        return b;
    }

    private String outgoingDelivery(MailDb.Contact contact,String preference) {
        if("chain".equals(preference)) return "chain";
        if("maxima".equals(preference)) return "maxima";
        return contact.mxaddr!=null && !contact.mxaddr.isEmpty()?"maxima":"chain";
    }

    private void sendDm(String text,String mediaRef,String mediaMime) {
        String quoted=threadPeer.equals(replyPeer)?replyBody:"";
        queueDm(text,mediaRef,mediaMime,quoted,replyFrom,"","",true);
    }

    /** Capture the authenticated sender and complete wire payload before accepting the draft. */
    private void queueDm(String text,String mediaRef,String mediaMime,String quoted,String quoteAuthor,
            String requiredDelivery,String requiredAddress,boolean consumeComposer) {
        if(threadPeer.isEmpty()) return;
        String body=text==null?"":text.trim();
        if(body.isEmpty() && (mediaRef==null || mediaRef.isEmpty())) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_type_message)); return; }
        MailDb db=MailDb.get(this);MailDb.Contact contact=db.contact(threadPeer);
        if(contact==null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_no_contact)); return; }
        final MailDb.Message outgoing=new MailDb.Message();
        try {
            synchronized(SalonComms.class) { synchronized(SalonStore.class) {
                String delivery=requiredDelivery.isEmpty()?outgoingDelivery(contact,SalonStore.get(this,XPORT_PREF+threadPeer)):requiredDelivery;
                if("maxima".equals(delivery) && (contact.mxaddr==null || contact.mxaddr.isEmpty())) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_no_maxima)); return; }
                String destination=requiredAddress.isEmpty()?contact.addr:requiredAddress;
                if("chain".equals(delivery) && !Args.isAddr(destination)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_no_delivery)); return; }
                outgoing.sender=SalonComms.publicId(this);
                String handle="@"+SalonStore.get(this,"handle"),address=SalonStore.get(this,"tipaddr");
                outgoing.coinid="me-"+java.util.UUID.randomUUID();outgoing.peerpk=threadPeer;
                outgoing.body=body;outgoing.media=mediaRef==null?"":mediaRef;outgoing.mime=mediaMime==null?"":mediaMime;
                outgoing.ts=System.currentTimeMillis()/1000;outgoing.mine=true;outgoing.valid=true;outgoing.status=MailDb.PENDING;
                outgoing.replyBody=quoted==null?"":quoted;outgoing.replyFrom=quoteAuthor==null?"":quoteAuthor;
                outgoing.delivery=delivery;outgoing.destination=destination==null?"":destination;outgoing.phase="queued";
                JSONObject wire=MinimaMail.compose(handle,address,body,outgoing.media,outgoing.mime,outgoing.ts);
                wire.put("id",outgoing.coinid);
                String mx=MaximaLink.myAddresses(this);if(mx!=null && !mx.isEmpty())wire.put("mxaddr",mx);
                if(!outgoing.replyBody.isEmpty())wire.put("replybody",outgoing.replyBody)
                        .put("replyfrom","You".equals(outgoing.replyFrom)?handle:"You");
                outgoing.wire=wire.toString();
                if(!db.enqueue(outgoing,contact)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_storage)); return; }
            } }
        } catch(SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }
        catch(Exception unavailable) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_storage)); return; }
        if(consumeComposer) {
            replyBody="";replyFrom="";replyPeer="";
            if(outgoing.media.isEmpty())threadDraft="";
        }
        render();
        if("maxima".equals(outgoing.delivery) && !MaximaLink.isReady(this))toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_queued));
        try { io.execute(()->dispatchOutgoing(outgoing)); }
        catch(java.util.concurrent.RejectedExecutionException unavailable) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_queued)); }
    }

    private boolean outgoingOwner(MailDb.Message m) {
        return m.sender!=null && !m.sender.isEmpty() && m.sender.equals(SalonStore.get(this,"msgpk"));
    }
    private void outgoingNotice(MailDb.Message m,CharSequence message) {
        runOnUiThread(()->{
            if(isFinishing() || isDestroyed() || !outgoingOwner(m))return;
            if(screen==Screen.THREAD && m.peerpk.equals(threadPeer))render();
            toast(message);
        });
    }

    private void completeOutgoing(MailDb db,MailDb.Message m,String attempt,boolean accepted,CharSequence notice) {
        try { if(db.finishAttempt(m,attempt,accepted))outgoingNotice(m,notice); }
        catch(RuntimeException unavailable) { outgoingNotice(m,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_storage)); }
    }

    /** Frozen delivery never follows a later preference change or falls back from Maxima to chain. */
    private void dispatchOutgoing(MailDb.Message m) {
        if(isFinishing() || isDestroyed())return;
        final MailDb db=MailDb.get(this);
        try {
            synchronized(SalonComms.class) {
                if(!outgoingOwner(m))return;
                JSONObject wire=new JSONObject(m.wire);
                if(!m.coinid.equals(wire.optString("id")))return;
                if("maxima".equals(m.delivery)) {
                    MailDb.Contact contact=db.contact(m.peerpk);
                    if(!MaximaLink.isReady(this) || contact==null || contact.mxaddr==null || contact.mxaddr.isEmpty())return;
                    final LocalEcCryptoProvider identity=SalonComms.crypto(this);
                    if(!m.sender.equals(identity.identity()))throw new SalonComms.IdentityUnavailable();
                    String sealed=identity.seal(m.peerpk,m.wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if(sealed==null || sealed.isEmpty()) { outgoingNotice(m,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_seal_failed)); return; }
                    if(!db.beginAttempt(m))return;
                    final String attempt=m.attempt;
                    MaximaLink.SendCb result=new MaximaLink.SendCb() {
                        public void onSent(String status,boolean held) {
                            completeOutgoing(db,m,attempt,true,held?Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_sent_held):Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_sent));
                        }
                        public void onFailed(String ignored) {
                            completeOutgoing(db,m,attempt,false,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_retry));
                        }
                    };
                    try { MaximaLink.sendDm(this,contact.mxaddr,sealed,result); }
                    catch(RuntimeException unavailable) { result.onFailed(""); }
                } else if("chain".equals(m.delivery) && nodeUp && Args.isAddr(m.destination)) {
                    final LocalEcCryptoProvider identity=SalonComms.crypto(this);
                    if(!m.sender.equals(identity.identity()))throw new SalonComms.IdentityUnavailable();
                    // The checked database claim becomes UNCERTAIN before any non-idempotent send.
                    if(!db.beginAttempt(m))return;
                    final String attempt=m.attempt;
                    MinimaMail.Cb result=new MinimaMail.Cb() {
                        public void onSent(String txpowid) {
                            completeOutgoing(db,m,attempt,true,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_mining));
                        }
                        public void onFailed(String ignored) {
                            completeOutgoing(db,m,attempt,false,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_unknown));
                        }
                    };
                    try { MinimaMail.send(node,identity,m.destination,m.peerpk,wire,result); }
                    catch(RuntimeException unavailable) { result.onFailed(""); }
                }
            }
        } catch(SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); }
        catch(Exception unavailable) { outgoingNotice(m,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_storage)); }
    }

    private void retryOutbox() {
        if(isFinishing() || isDestroyed())return;
        try { io.execute(()->{
            try { for(MailDb.Message m:MailDb.get(this).pendingOutbox())dispatchOutgoing(m); }
            catch(Exception unavailable) { runOnUiThread(()->{if(!isFinishing() && !isDestroyed())toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_storage));}); }
        }); } catch(java.util.concurrent.RejectedExecutionException unavailable) { /* Durable queue remains for next session. */ }
    }

    private androidx.appcompat.app.AlertDialog outboxRetryDialog;
    private void cancelOutboxDialog() {
        if(outboxRetryDialog!=null){androidx.appcompat.app.AlertDialog dialog=outboxRetryDialog;outboxRetryDialog=null;dialog.dismiss();}
    }
    private CharSequence outgoingStatus(MailDb.Message m) {
        if(!m.mine || m.status==MailDb.SENT)return socialTime(m.ts);
        if(m.status==MailDb.UNCERTAIN)return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_unknown);
        return outgoingOwner(m)?Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_sending):Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_owner);
    }
    private void reviewOutgoing(MailDb.Message m) {
        if(outboxRetryDialog!=null || !m.mine || screen!=Screen.THREAD || !m.peerpk.equals(threadPeer))return;
        MailDb.Message stored=MailDb.get(this).message(m.coinid);
        if(stored==null || stored.status==MailDb.SENT)return;
        final String owner;
        try { owner=backupOwner(); } catch(Exception unavailable) { identityUnavailable();return; }
        MailDb.Contact contact=MailDb.get(this).contact(m.peerpk);if(contact==null)return;
        final String delivery="maxima".equals(m.delivery)||"chain".equals(m.delivery)?m.delivery:outgoingDelivery(contact,SalonStore.get(this,XPORT_PREF+m.peerpk));
        final String destination="chain".equals(m.delivery)?m.destination:contact.addr;
        final int epoch=renderEpoch;
        outboxRetryDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_review)).setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_warning, "chain".equals(delivery)?Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_chain):"Maxima"))
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel),null)
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_outbox_copy),(dialog,which)->{
                    if(outboxRetryDialog!=dialog)return;outboxRetryDialog=null;
                    if(isFinishing() || isDestroyed() || screen!=Screen.THREAD || epoch!=renderEpoch || !m.peerpk.equals(threadPeer))return;
                    try { if(!owner.equals(backupOwner())) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_selection_changed));return; } }
                    catch(Exception unavailable) { identityUnavailable();return; }
                    MailDb.Message latest=MailDb.get(this).message(m.coinid);
                    if(latest==null || latest.status==MailDb.SENT) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_sent));return; }
                    queueDm(m.body,m.media,m.mime,m.replyBody,m.replyFrom,delivery,destination==null?"":destination,false);
                }).create();
        outboxRetryDialog.setOnDismissListener(dialog->{if(outboxRetryDialog==dialog)outboxRetryDialog=null;});outboxRetryDialog.show();
    }

    /** Let the user force the transport for THIS contact, or leave it automatic. */
    private void transportDialog() {
        final String peer = threadPeer, owner = mediaIdentity();
        if (peer.isEmpty()) return;
        final MailDb.Contact ct = MailDb.get(this).contact(peer);
        boolean hasMx = ct != null && ct.mxaddr != null && !ct.mxaddr.isEmpty();
        final CharSequence[] labels = {
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_auto_choice),
                Language.join(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_maxima_choice), hasMx ? "" : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_no_address_suffix)),
                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_chain_choice)
        };
        final String[] vals = {"auto", "maxima", "chain"};
        String cur = SalonStore.get(this, XPORT_PREF + threadPeer);
        int sel = "maxima".equals(cur) ? 1 : "chain".equals(cur) ? 2 : 0;
        new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_transport))
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    if (isFinishing() || isDestroyed() || screen != Screen.THREAD
                            || !peer.equals(threadPeer) || !owner.equals(mediaIdentity())) {
                        d.dismiss(); if (!isFinishing() && !isDestroyed()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_selection_changed)); return;
                    }
                    SalonStore.put(this, XPORT_PREF + peer, vals[which]);
                    d.dismiss();
                    if ("maxima".equals(vals[which]) && !hasMx)
                        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_no_maxima));
                    render();
                })
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_language_close), null)
                .show();
    }

    private void attachDmMedia() {
        pickMedia(PICK_DM_IMG, "image/*");
    }

    /* ---------------- messaging key backup / restore ---------------- */

    private void identityUnavailable() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) toast(Language.text(this,
                    com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_unavailable));
        });
    }

    /** One owned backup operation; no plaintext secret is put in saved-instance state. */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_BACKUP_REQUEST = new java.util.concurrent.atomic.AtomicInteger(1000);
    private static final class BackupOperation {
        final int code, requestCode;
        final String owner;
        volatile boolean cancelled;
        boolean picker;
        String content;
        BackupOperation(int code, String owner) {
            this.code = code; this.owner = owner;
            requestCode = code == 0 ? 0 : NEXT_BACKUP_REQUEST.getAndIncrement();
        }
    }
    private BackupOperation backupOperation;
    private volatile boolean backupClosed;

    private String backupOwner() { return backupOwner(SalonStore.identityRecord(this)); }

    private String backupOwner(JSONObject record) {
        return new JSONArray().put(record.optString("tokenid")).put(record.optString("handle"))
                .put(record.optString("msgpk")).put(record.optString("msgseed")).toString();
    }

    private BackupOperation beginBackup(int code) {
        if (backupClosed || isFinishing() || isDestroyed()) return null;
        if (backupOperation != null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_busy)); return null; }
        try {
            BackupOperation operation = new BackupOperation(code, backupOwner());
            if (operation.requestCode > 65535) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable)); return null; }
            return backupOperation = operation;
        } catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return null; }
    }

    private boolean backupCurrent(BackupOperation operation) {
        if (backupClosed || operation.cancelled) return false;
        try { return operation.owner.equals(backupOwner()); }
        catch (SalonComms.IdentityUnavailable unavailable) { return false; }
    }

    private void backupNotice(CharSequence message) {
        if (!backupClosed && !isFinishing() && !isDestroyed()) toast(message);
    }

    private void finishBackup(BackupOperation operation, CharSequence notice) {
        if (backupOperation != operation) return;
        backupOperation = null; operation.content = null;
        if (!backupClosed && !isFinishing() && !isDestroyed() && notice != null) toast(notice);
    }

    private void cancelBackup() {
        backupClosed = true;
        if (backupOperation != null) {
            backupOperation.cancelled = true; backupOperation.content = null; backupOperation = null;
        }
    }

    private void backupAccount() {
        final BackupOperation operation = beginBackup(SAVE_TEXT);
        if (operation == null) return;
        try {
            io.execute(() -> {
                try {
                    final String content;
                    // Serialize key changes; recheck the captured owner before launching the picker.
                    // Hosting decryption must not hold the profile-edit lock.
                    synchronized (SalonComms.class) {
                        if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed))); return; }
                        content = buildBackupJson();
                    }
                    if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 20 * 1024 * 1024) {
                        runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_too_large))); return;
                    }
                    runOnUiThread(() -> launchBackupSave(operation, "salon-backup.json", content));
                } catch (SalonStore.BackupStorageUnavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_storage_failed))); }
                catch (Crypt.Unavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable))); }
                catch (SalonComms.IdentityUnavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_unavailable))); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable)); }
    }

    private void backupMsgKey() {
        final BackupOperation operation = beginBackup(0);
        if (operation == null) return;
        try {
            io.execute(() -> {
                try {
                    final String seed;
                    synchronized (SalonComms.class) {
                        if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed))); return; }
                        seed = SalonComms.exportSeed(this);
                    }
                    runOnUiThread(() -> {
                        if (backupOperation != operation) return;
                        if (!backupCurrent(operation) || isFinishing() || isDestroyed()) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed)); return; }
                        finishBackup(operation, null);
                        if (seed.isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_no_key)); return; }
                        LinearLayout box = dialogBox();
                        box.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_key_help)), lp(0, 2, 0, 8));
                        TextView t = Design.text(this, seed, 12, Design.INK(), Design.mono());
                        t.setOnClickListener(v -> { if (backupDialogCurrent(operation)) copyMessagingKey(seed); }); box.addView(t);
                        new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_backup_key)).setView(box)
                                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_save_file), (d, w) -> { if (backupDialogCurrent(operation)) saveTextFile("salon-messaging-key.txt", seed); })
                                .setNeutralButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_copy), (d, w) -> { if (backupDialogCurrent(operation)) copyMessagingKey(seed); })
                                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_language_close), null).show();
                    });
                } catch (SalonComms.IdentityUnavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_unavailable))); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable)); }
    }

    private boolean backupDialogCurrent(BackupOperation operation) {
        if (isFinishing() || isDestroyed() || backupClosed) return false;
        if (!backupCurrent(operation)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed)); return false; }
        return true;
    }

    private void saveTextFile(String suggestedName, String content) {
        final BackupOperation operation = beginBackup(SAVE_TEXT);
        if (operation != null) launchBackupSave(operation, suggestedName, content);
    }

    private void launchBackupSave(BackupOperation operation, String suggestedName, String content) {
        if (backupOperation != operation) return;
        if (!backupDialogCurrent(operation)) { finishBackup(operation, null); return; }
        operation.content = content; operation.picker = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, suggestedName);
        try { startActivityForResult(intent, operation.requestCode); }
        catch (RuntimeException unavailable) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_no_save_app)); }
    }

    private void restoreFromFile() {
        final BackupOperation operation = beginBackup(OPEN_BACKUP);
        if (operation == null) return;
        operation.picker = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, operation.requestCode); }
        catch (RuntimeException unavailable) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_no_open_app)); }
    }

    private void receiveBackupFile(int requestCode, int result, Intent data) {
        final BackupOperation operation = backupOperation;
        if (operation == null || operation.requestCode != requestCode || !operation.picker) {
            if (result == RESULT_OK && !backupClosed && !isFinishing() && !isDestroyed()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_reselect));
            return;
        }
        final int code = operation.code;
        operation.picker = false; // Consume once, including cancellation and malformed results.
        if (result != RESULT_OK || data == null || data.getData() == null) { finishBackup(operation, null); return; }
        if (!backupCurrent(operation)) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed)); return; }
        final Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_document)); return; }
        final String content = operation.content; operation.content = null;
        try {
            io.execute(() -> {
                try {
                    if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed))); return; }
                    if (code == SAVE_TEXT) {
                        // Match TradeExportWriter.writeTo: truncate, close, then report success.
                        if (content == null) throw new java.io.IOException("missing backup");
                        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new java.io.IOException("cannot open document");
                            if (!backupCurrent(operation)) throw new java.io.InterruptedIOException();
                            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush();
                        }
                        runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_saved)));
                    } else {
                        final String json = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(readCapped(uri, 20))).toString();
                        restoreFromBackup(json, operation);
                        runOnUiThread(() -> finishBackup(operation, null));
                    }
                } catch (Exception error) {
                    runOnUiThread(() -> finishBackup(operation, code == SAVE_TEXT ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_save_incomplete)
                            : "file too large (max 20 MB)".equals(error.getMessage()) ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_too_large) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_read_failed)));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable)); }
    }

    private void restoreMsgKey() {
        final BackupOperation dialog;
        try { dialog = new BackupOperation(0, backupOwner()); }
        catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }
        LinearLayout box = dialogBox();
        final EditText input = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_paste_key), "", false, "");
        showDialog(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_restore_key), box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_restore), () -> {
            if (!backupDialogCurrent(dialog)) return;
            final BackupOperation operation = beginBackup(0);
            if (operation == null) return;
            final String seed = text(input);
            try {
                io.execute(() -> {
                    final boolean ok;
                    synchronized (SalonComms.class) { synchronized (SalonStore.class) {
                        if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed))); return; }
                        ok = SalonComms.importSeed(this, seed);
                    } }
                    runOnUiThread(() -> {
                        if (backupOperation != operation) return;
                        finishBackup(operation, ok ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_restored_key_help) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_restore_failed));
                        if (ok && !backupClosed && !isFinishing() && !isDestroyed()) render();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable)); }
        });
    }

    private void copyMessagingKey(String seed) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_core_wallet_clipboard)); return; }
        ClipData clip = ClipData.newPlainText("salon-msgkey", seed);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_copied));
    }

    /** A full account backup file: messaging key + hosting logins (+ identity/content
     *  draft). Secrets are stored DECRYPTED here (Crypt is per-install and useless after
     *  a reinstall) and re-wrapped with this device's key on restore. Guard the file. */
    private String buildBackupJson() {
        JSONObject b = new JSONObject();
        try {
            b.put("salonBackup", 1);
            b.put("created", System.currentTimeMillis() / 1000);
            JSONObject me = new JSONObject(SalonStore.identityRecord(this).toString());
            me.remove("msgseed");   // device-bound; exported separately as plaintext
            b.put("me", me);
            String seed = SalonComms.exportSeed(this);
            if (!seed.isEmpty()) b.put("msgseed", seed);
            JSONArray hosts = new JSONArray();
            for (Hosting.Profile p : HostingStore.listForBackup(this)) {
                JSONObject copy = HostingSecrets.copySecrets(p.j, false);
                hosts.put(copy);
            }
            b.put("hosting", hosts);
            try { BackupValidation.check(b); }
            catch (IllegalArgumentException invalid) { throw new SalonStore.BackupStorageUnavailable(); }
        } catch (SalonStore.BackupStorageUnavailable unavailable) { throw unavailable; }
        catch (Crypt.Unavailable unavailable) { throw unavailable; }
        catch (Exception unavailable) { throw new SalonComms.IdentityUnavailable(); }
        return b.toString();
    }

    /** Restore an account backup: re-wrap hosting secrets with this device's Crypt key,
     *  import the messaging seed, and (only if empty) seed the identity/content draft. */
    private void restoreFromBackup(String json) { restoreFromBackup(json, null); }

    private void restoreFromBackup(String json, BackupOperation operation) {
        try {
            JSONObject b = new JSONObject(json);
            try { BackupValidation.check(b); }
            catch (IllegalArgumentException invalid) {
                runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_invalid_backup)));
                return;
            }
            JSONArray preparedHosts = HostingSecrets.prepareRestore(b.optJSONArray("hosting"));
            // Stage credential encryption before taking the profile-edit lock.
            synchronized (SalonComms.class) { synchronized (SalonStore.class) {
                if (operation != null && !backupCurrent(operation)) {
                    runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed))); return;
                }
            final SalonComms.BackupResult restored;
            try { restored = SalonComms.restoreBackup(this, b, preparedHosts, () -> operation == null || backupCurrent(operation)); }
            catch (SalonComms.BackupCancelled cancelled) {
                runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed)));
                return;
            } catch (SalonComms.IdentityUnavailable unavailable) {
                runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_restore_failed)));
                return;
            } catch (SalonStore.BackupStorageUnavailable unavailable) {
                runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_storage_failed)));
                return;
            }
            final int hn = restored.destinations;
            final boolean mo = restored.keyRestored, sk = restored.keyKept, restoredDraft = restored.draftRestored;
            final String restoredOwner = backupOwner();
            runOnUiThread(() -> {
                if (backupClosed || isFinishing() || isDestroyed()) return;
                try { if (!restoredOwner.equals(backupOwner())) return; }
                catch (SalonComms.IdentityUnavailable unavailable) { return; }
                // Match hosted restore: old Edit widgets must not reapply the previous draft.
                if (restoredDraft) edName = edBio = edAbout = edAvatar = edBanner = edWebvalidate = null;
                toast(Language.join(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_restored_destinations, hn), mo ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_key_restored_suffix) : "", sk ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_key_kept_suffix) : "")); render(); });
            } }
        } catch (Exception e) { runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_restore_failed))); }
    }

    /** GET-touch the owner's own relay blobs so their 7-day TTL resets each session. */
    private void touchOwnRelay() {
        final String pu = SalonStore.get(this, "profileUrl");
        if (!RelayResolver.isRelayRef(pu)) return;
        io.execute(() -> {
            try {
                JSONObject p = RelayResolver.resolveJson(pu);   // GET refreshes profile.json chunks
                touchRef(p.optString("avatar", "")); touchRef(p.optString("banner", ""));
                touchArr(p.optJSONArray("gallery"), "url");
                touchArr(p.optJSONArray("posts"), "media");
            } catch (Exception ignored) {}
        });
    }
    private void touchRef(String u) { if (RelayResolver.isRelayRef(u)) RelayResolver.touch(u); }
    private void touchArr(JSONArray a, String key) { if (a == null) return; for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) touchRef(o.optString(key, "")); } }

    private final android.os.Handler pairHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pairTick = new Runnable() {
        @Override public void run() {
            if (node != null && !node.isEnabled()) { node.reRegister(); pairHandler.postDelayed(this, 5000); }
        }
    };
    private void startPairingRetry() { pairHandler.removeCallbacks(pairTick); pairHandler.postDelayed(pairTick, 3000); }

    // Foreground gossip-mesh renewal: re-run keepAlive on every connect/resume AND on a
    // timer while the app is open, so a beacon that prunes (~a day) is renewed before it
    // goes dark. keepAlive itself only POSTS when a beacon has actually faded (on-chain
    // check), so this is cheap. Throttled so a burst of connects doesn't re-scan repeatedly.
    private final android.os.Handler reannHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastReannounceMs = 0;
    private static final long REANN_THROTTLE_MS = 4 * 60 * 1000L;    // don't re-scan more than ~every 4 min
    private static final long REANN_INTERVAL_MS = 20 * 60 * 1000L;   // check every ~20 min while foreground
    private final Runnable reannTick = new Runnable() {
        @Override public void run() { reannounceNow(); retryOutbox(); reannHandler.postDelayed(this, REANN_INTERVAL_MS); }
    };
    private void reannounceNow() {
        if (!nodeUp || !SalonStore.hasIdentity(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastReannounceMs < REANN_THROTTLE_MS) return;
        lastReannounceMs = now;
        SalonKeepAliveWorker.markPass(this);   // foreground passes reset the shared background clock
        SalonRegistry.keepAlive(node, SalonStore.get(this, "tokenid"), SalonStore.get(this, "profileUrl"),
                SalonStore.get(this, "handle"), SalonStore.follows(this), n -> {});
    }

    /** Periodic safety net behind the NEWBLOCK-triggered one-shot (SalonNotifyReceiver): if the
     *  node's block broadcasts ever go quiet, this still runs a due-gated pass every ~6h. KEEP so
     *  re-opening the app never resets the schedule; WorkManager persists it across reboots. */
    private void scheduleKeepAliveFallback() {
        if (!SalonKeepAliveWorker.bgEnabled(this) || !SalonStore.hasIdentity(this)) return;
        try {
            androidx.work.PeriodicWorkRequest req = new androidx.work.PeriodicWorkRequest.Builder(
                    SalonKeepAliveWorker.class, 6, java.util.concurrent.TimeUnit.HOURS).build();
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    SalonKeepAliveWorker.WORK_PERIODIC, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Throwable t) { android.util.Log.w(SalonKeepAliveWorker.TAG, "fallback schedule failed", t); }
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        scheduleKeepAliveFallback();   // no-op without identity; covers create/adopt since last open
        reannHandler.removeCallbacks(reannTick);
        reannHandler.post(reannTick);   // renew now (if faded) + reschedule while foreground
        if (node != null && !node.isEnabled()) startPairingRetry();   // resume pairing retries only if still unpaired
        // While we're foreground, let the shared inbox path (Maxima deliveries and
        // background coin NOTIFY, both static in SalonNotifyReceiver.intakeDm) poke
        // the open tab. Only the on-chain scanMail rendered itself, so a Maxima DM
        // toasted/notified but never appeared in the Messages tab until you left
        // and came back. Now any transport repaints it live.
        sInbox = () -> runOnUiThread(() -> {
            refreshUnread();
            retryOutbox();   // an inbound message proves a transport is alive — flush
            if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
        });
        // Also flush when Maxima finishes (re)connecting, so a message queued
        // while it was down sends the moment it comes up.
        sMaximaReady = () -> runOnUiThread(this::retryOutbox);
        refreshUnread();   // catch anything delivered while we were away
        retryOutbox();     // flush anything queued while we were backgrounded / offline
        if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;
        reannHandler.removeCallbacks(reannTick);   // the Activity's tick stops; SalonKeepAliveWorker takes over while closed
        pairHandler.removeCallbacks(pairTick);     // stop the 5s SDK-rebuild loop while backgrounded (restarted on resume if still unpaired)
        sInbox = null; sMaximaReady = null;   // don't hold the activity while backgrounded
    }

    /** Set while resumed; SalonNotifyReceiver.intakeDm pokes it after a new DM
     *  arrives over ANY transport, so the open Messages/Thread tab repaints. */
    private static volatile Runnable sInbox;
    /** Set while resumed; MaximaLink pokes it when the transport (re)connects, so
     *  the outbox flushes messages queued while Maxima was down. */
    private static volatile Runnable sMaximaReady;

    /** Called by the shared DM intake when a NEW message lands. No-op if no
     *  activity is foreground (the notification already covers that case). */
    static void onInboxChanged() {
        Runnable r = sInbox;
        if (r != null) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    /** Called by MaximaLink when the transport becomes ready. Flushes the outbox. */
    static void onMaximaReady() {
        Runnable r = sMaximaReady;
        if (r != null) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pairHandler.removeCallbacks(pairTick);
        reannHandler.removeCallbacks(reannTick);
        cancelMediaRequests();
        cancelBackup();
        cancelPublication();
        cancelHostedRestore();
        cancelIdentityClaim();
        cancelSpareBurn();
        cancelOutboxDialog();
        cancelHostingCheck();
        io.shutdownNow();
        stopAudio();
        videoRequest++;
        dismissVideo();
        proofRequests.close();
        if (node != null) node.onDestroy();   // release the DirectNodeApi receiver + cancel pending IPC timeouts
    }

    /* ---------------- chrome + nav ---------------- */

    private void buildNav() {
        navRow.removeAllViews();
        navRow.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(navTab(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_feed), screen == Screen.FEED, () -> go(Screen.FEED)), weight1());
        tabs.addView(navTab(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_discover), screen == Screen.DISCOVER || screen == Screen.VIEW, () -> go(Screen.DISCOVER)), weight1());
        tabs.addView(navTab(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_salon), screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.EDIT, () -> go(Screen.HOME)), weight1());
        tabs.addView(navTabBadge(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_messages), screen == Screen.MESSAGES || screen == Screen.THREAD, unreadCache, () -> go(Screen.MESSAGES)), weight1());   // cached; refreshed off the UI thread
        tabs.addView(navTab(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_settings), screen == Screen.SETTINGS || screen == Screen.HOSTING || screen == Screen.HOSTING_EDIT, () -> go(Screen.SETTINGS)), weight1());
        navRow.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
    }

    private View navTab(CharSequence label, boolean active, Runnable click) {
        TextView t = Design.text(this, Language.upper(label), 10f, active ? Design.INK() : Design.DIM(), Design.sansBold());
        t.setLetterSpacing(0.04f); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(12), 0, dp(12));
        if (active) t.setBackgroundColor(0x14000000);
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private View navTabBadge(CharSequence label, boolean active, int unread, Runnable click) {
        View base = navTab(label, active, click);
        if (unread <= 0) return base;
        FrameLayout f = new FrameLayout(this);
        f.addView(base, new FrameLayout.LayoutParams(-1, -2));
        TextView badge = Design.pill(this, unread > 9 ? "9+" : String.valueOf(unread), Design.PILL_MINE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = dp(5); blp.leftMargin = dp(34);
        f.addView(badge, blp);
        return f;
    }

    private void masthead(CharSequence title) {
        appbar.removeAllViews();
        LinearLayout pad = new LinearLayout(this); pad.setOrientation(LinearLayout.HORIZONTAL); pad.setGravity(Gravity.CENTER_VERTICAL);
        pad.setPadding(dp(16), dp(10), dp(16), dp(8));
        pad.addView(Design.display(this, title, 22), new LinearLayout.LayoutParams(0, -2, 1));
        TextView ver = Design.text(this, "№ " + BuildConfig.VERSION_NAME, 10.5f, Design.DIM(), Design.mono());
        ver.setPadding(0, 0, dp(8), 0); pad.addView(ver);
        nodeChip = Design.pill(this, nodeUp ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_connected) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_node_not_ready), Design.PILL_DIM); pad.addView(nodeChip);
        appbar.addView(pad);
        appbar.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
    }

    private void go(Screen s) {
        if (screen != s) { cancelMediaRequests(); cancelPublication(); cancelHostedRestore(); cancelSpareBurn(); }
        if (screen == Screen.EDIT && s != Screen.EDIT) commitEditFields();   // preserve the draft when leaving Edit
        screen = s; render();
    }

    private int renderEpoch = 0;   // bumped every render(); async painters bail if it moved (stale screen)
    private void render() {
        if (screen == Screen.EDIT) commitEditFields();   // an in-Edit action rebuilding the screen must not lose typed text
        cancelIdentityClaim();
        cancelOutboxDialog();
        cancelHostingCheck();
        renderEpoch++;
        if (swipe != null) swipe.setEnabled(screen == Screen.FEED || screen == Screen.DISCOVER);   // pull-to-refresh where it makes sense
        buildNav(); body.removeAllViews();
        switch (screen) {
            case FEED:         renderFeed(); break;
            case DISCOVER:     renderDiscover(); break;
            case HOME:         renderHome(); break;
            case VIEW:         renderView(); break;
            case EDIT:         renderEdit(); break;
            case ONBOARD:      renderOnboard(); break;
            case SETTINGS:     renderSettings(); break;
            case HOSTING:      renderHosting(); break;
            case HOSTING_EDIT: renderHostingEdit(); break;
            case MESSAGES:     renderMessages(); break;
            case THREAD:       renderThread(); break;
        }
    }

    /* ================= windowed list (bounded scrolling section) ================= */

    /** A RecyclerView that grows with its content up to a maximum height, then
     *  scrolls internally — so a section (e.g. the profile's Posts) can hold an
     *  unbounded number of items without stretching the whole page. Only the
     *  visible window is ever bound, so memory stays bounded however long it grows. */
    private class BoundedRecycler extends RecyclerView {
        private final int maxHpx;
        BoundedRecycler(Context c, int maxHpx) {
            super(c);
            this.maxHpx = maxHpx;
            setLayoutManager(new LinearLayoutManager(c));
            setVerticalScrollBarEnabled(true);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // Wrap content up to maxHpx; beyond that, scroll inside this view.
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHpx, MeasureSpec.AT_MOST));
        }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            // The outer ScrollView is not a nested-scrolling parent, so it would
            // steal every vertical drag. While this list actually has something to
            // scroll, claim the gesture so it scrolls internally; release on lift so
            // dragging over a non-scrolling window still pages the whole screen.
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                boolean scrollable = canScrollVertically(1) || canScrollVertically(-1);
                if (scrollable && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** A ScrollView that grows with its content up to a maximum height, then scrolls
     *  internally — for heterogeneous sections (Gallery grid+carousel+list, Holdings
     *  rows) that don't map cleanly onto an adapter. Same gesture-claim as
     *  {@link BoundedRecycler} so the outer ScrollView doesn't steal the drag. */
    private class BoundedScroll extends ScrollView {
        private final int maxHpx;
        BoundedScroll(Context c, int maxHpx) {
            super(c);
            this.maxHpx = maxHpx;
            setVerticalScrollBarEnabled(true);
            setClipToPadding(false);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHpx, MeasureSpec.AT_MOST));
        }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                boolean scrollable = canScrollVertically(1) || canScrollVertically(-1);
                if (scrollable && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** Wrap {@code content} in a fixed-height internal scroll if it would run long. */
    private View boundedBox(View content, int maxHdp) {
        BoundedScroll bs = new BoundedScroll(this, dp(maxHdp));
        bs.addView(content, new FrameLayout.LayoutParams(-1, -2));
        return bs;
    }

    /** A RecyclerView adapter that builds each row imperatively (the app has no XML
     *  layouts). onBind rebuilds the row into a reused FrameLayout — heterogeneous
     *  row heights, but only the visible window is ever bound. */
    private abstract class ImpAdapter<T> extends RecyclerView.Adapter<ImpVH> {
        final List<T> items = new ArrayList<>();
        void set(List<T> data) { items.clear(); if (data != null) items.addAll(data); notifyDataSetChanged(); }
        @Override public ImpVH onCreateViewHolder(ViewGroup p, int vt) {
            FrameLayout f = new FrameLayout(MainActivity.this);
            f.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new ImpVH(f);
        }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onBindViewHolder(ImpVH h, int pos) {
            FrameLayout f = (FrameLayout) h.itemView;
            f.removeAllViews();
            f.addView(bind(items.get(pos), pos));
        }
        abstract View bind(T item, int pos);
    }
    private static class ImpVH extends RecyclerView.ViewHolder { ImpVH(View v) { super(v); } }

    /* ================= profile model ================= */

    /** The public profile.json = identity + rich content, from the local draft. */
    private JSONObject buildProfileJson() {
        JSONObject me = SalonStore.me(this), p = new JSONObject();
        putJson(p, "v", "1");
        for (String k : new String[]{"handle","name","bio","about","avatar","banner","tokenid","webvalidate","tipaddr","msgpk"})
            putJson(p, k, me.optString(k, ""));
        // Our Maxima addresses (CSV): how peers reach us for instant off-chain
        // DMs. Absent = this Salon has no Maxima yet; peers fall back on-chain.
        String mx = MaximaLink.myAddresses(this);
        if (!mx.isEmpty()) putJson(p, "mxaddr", mx);
        putJson(p, "updated", Long.toString(System.currentTimeMillis() / 1000));
        try {
            p.put("links", me.optJSONArray("links") == null ? new JSONArray() : me.optJSONArray("links"));
            p.put("gallery", me.optJSONArray("gallery") == null ? new JSONArray() : me.optJSONArray("gallery"));
            p.put("posts", me.optJSONArray("posts") == null ? new JSONArray() : me.optJSONArray("posts"));
            p.put("nfts", me.optJSONArray("nfts") == null ? new JSONArray() : me.optJSONArray("nfts"));
        } catch (Exception ignored) {}
        return p;
    }

    private static final class ProfilePublish {
        final String draft, typed, html;
        final Hosting.Profile host;
        final JSONObject profile;
        final int epoch;
        final TextView status;
        final Runnable done;
        volatile boolean cancelled;
        boolean refreshing = true;
        ProfilePublish(String draft, String typed, String html, Hosting.Profile host,
                JSONObject profile, int epoch, TextView status, Runnable done) {
            this.draft = draft; this.typed = typed; this.html = html;
            this.host = host; this.profile = profile; this.epoch = epoch; this.status = status; this.done = done;
        }
    }
    private ProfilePublish profilePublish;
    private Object announcement;

    private String profileTyped() {
        return new JSONArray().put(edName == null ? "" : text(edName)).put(edBio == null ? "" : text(edBio))
                .put(edAbout == null ? "" : text(edAbout)).put(edAvatar == null ? "" : text(edAvatar))
                .put(edBanner == null ? "" : text(edBanner)).put(edWebvalidate == null ? "" : text(edWebvalidate)).toString();
    }

    private boolean publicationStoredCurrent(ProfilePublish op) {
        if (op.cancelled) return false;
        try {
            Hosting.Profile host = HostingStore.getDefault(this);
            return op.draft.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)))
                    && host != null && op.host.j.toString().equals(host.j.toString());
        } catch (Exception unavailable) { return false; }
    }

    private boolean publicationCurrent(ProfilePublish op) {
        return !isFinishing() && !isDestroyed() && op.epoch == renderEpoch
                && op.typed.equals(profileTyped()) && publicationStoredCurrent(op);
    }

    private void publicationStatus(TextView status, CharSequence message) {
        if (isFinishing() || isDestroyed()) return;
        if (status != null) Language.setText(status, message); else toast(message);
    }

    private void cancelPublication() {
        if (profilePublish != null) profilePublish.cancelled = true;
    }

    private void hostProfile(TextView status, Runnable done) {
        if (profilePublish != null || announcement != null) { publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_busy)); return; }
        Hosting.Profile host = HostingStore.getDefault(this);
        if (host == null) { publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_host)); return; }
        final ProfilePublish op;
        try {
            op = new ProfilePublish(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)),
                    profileTyped(), salonHtml(), host, buildProfileJson(), renderEpoch, status, done);
        } catch (Exception unavailable) { publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
        profilePublish = op;
        publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_preparing));
        refreshPublicationEditions(op);
    }

    /** All edition mutation and completion runs on the UI thread; late replies cannot
     *  mutate the uploaded JSON or overwrite a replacement account's holdings. */
    private void refreshPublicationEditions(ProfilePublish op) {
        JSONArray nfts = op.profile.optJSONArray("nfts");
        if (!nodeUp || nfts == null || nfts.length() == 0) { queuePublication(op); return; }
        final int[] pending = {nfts.length()};
        final boolean[] received = new boolean[nfts.length()];
        final android.os.Handler timer = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable finish = () -> { if (op.refreshing) queuePublication(op); };
        timer.postDelayed(finish, 12_000);
        for (int i = 0; i < nfts.length(); i++) {
            final int index = i;
            final JSONObject item = nfts.optJSONObject(i);
            final JsonArrayCb result = editions -> runOnUiThread(() -> {
                if (!op.refreshing || received[index]) return;
                received[index] = true;
                if (publicationCurrent(op) && item != null && editions.length() > 0)
                    try { item.put("editions", editions); } catch (Exception ignored) { }
                if (--pending[0] == 0) { timer.removeCallbacks(finish); finish.run(); }
            });
            if (item == null || item.optString("tokenid").isEmpty()) result.ready(new JSONArray());
            else try { captureEditions(item.optString("tokenid"), result); }
            catch (RuntimeException unavailable) { result.ready(new JSONArray()); }
        }
    }

    private void queuePublication(ProfilePublish op) {
        op.refreshing = false;
        if (profilePublish != op) return;
        if (!publicationCurrent(op)) { finishPublication(op, null, null); return; }
        final String content = op.profile.toString();
        publicationStatus(op.status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_uploading));
        try {
            io.execute(() -> {
                try {
                    if (!publicationStoredCurrent(op)) { runOnUiThread(() -> finishPublication(op, null, null)); return; }
                    String url = ProfileHosting.upload(op.host, op.profile.optString("handle"), content, op.html);
                    runOnUiThread(() -> finishPublication(op, url, null));
                } catch (Exception error) { runOnUiThread(() -> finishPublication(op, null, error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishPublication(op, null, unavailable); }
    }

    private void finishPublication(ProfilePublish op, String url, Exception error) {
        if (profilePublish != op) return;
        profilePublish = null;
        if (!publicationCurrent(op)) {
            if (!isFinishing() && !isDestroyed()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_changed));
            return;
        }
        if (error != null || url == null) {
            publicationStatus(op.status, error == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_failed, mediaError(error)));
            return;
        }
        if (!SalonStore.savePublication(this, op.draft, url, op.profile.optJSONArray("nfts"))) {
            publicationStatus(op.status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return;
        }
        mProfileCache.remove(url);
        publicationStatus(op.status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_uploaded));
        if (op.done != null) op.done.run();
    }

    // ---- Maxima mesh budget: a Maxima-hosted profile shares a small, LRU-evicted relay
    //      shelf with everyone else, so cap its TOTAL footprint. Server hosts are unlimited. ----
    static final long MAX_PROFILE_MESH_BYTES = 24L * 1024 * 1024;

    /** Plaintext bytes a mesh ({@code mx1:}) ref occupies, read from its embedded manifest
     *  {@code size}; 0 for a non-mesh ref (http/relay1) or anything unparseable. */
    private static long meshRefSize(String ref) {
        if (!MaximaLink.isMediaRef(ref)) return 0;
        try {
            byte[] raw = android.util.Base64.decode(ref.substring(MaximaLink.MEDIA_PREFIX.length()),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            JSONObject m = new JSONObject(new String(raw, "UTF-8"));
            return Math.max(0, Long.parseLong(m.optString("size", "0")));
        } catch (Exception e) { return 0; }
    }

    /** Total plaintext bytes THIS profile currently hosts on the Maxima mesh (avatar, banner,
     *  every gallery item, every post's media). Derived on demand — no separate bookkeeping. */
    private long meshUsageBytes() {
        JSONObject p = buildProfileJson();
        long t = meshRefSize(p.optString("avatar")) + meshRefSize(p.optString("banner"));
        JSONArray g = p.optJSONArray("gallery");
        if (g != null) for (int i = 0; i < g.length(); i++) { JSONObject o = g.optJSONObject(i); if (o != null) t += meshRefSize(o.optString("url")); }
        JSONArray po = p.optJSONArray("posts");
        if (po != null) for (int i = 0; i < po.length(); i++) { JSONObject o = po.optJSONObject(i); if (o != null) t += meshRefSize(o.optString("media")); }
        return t;
    }

    /** Human "12.3 MB" / "8 MB". */
    private static String mibOf(long b) {
        double m = b / (1024.0 * 1024.0);
        return (m >= 10 ? Math.round(m) + "" : (Math.round(m * 10) / 10.0) + "") + " MB";
    }

    /* ================= FEED ================= */

    private CharSequence socialTime(long timestamp) {
        if (timestamp <= 0) return "";
        return Language.since(timestamp < 1_000_000_000_000L ? timestamp * 1000L : timestamp);
    }

    private void renderFeed() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_feed));
        if (!SalonStore.hasIdentity(this)) { renderOnboard(); return; }
        JSONArray follows = SalonStore.follows(this);
        if (follows.length() == 0) {
            LinearLayout c = card();
            c.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_feed_empty)));
            c.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_discover_action), true, () -> go(Screen.DISCOVER)), lp(0, 10, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
            return;
        }
        TextView status = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_pulling, follows.length()));
        body.addView(status, lp(0, 0, 0, 8));
        final List<JSONObject> posts = Collections.synchronizedList(new ArrayList<>());
        final int[] pending = { follows.length() }, fails = { 0 };
        final int ep = renderEpoch;
        for (int i = 0; i < follows.length(); i++) {
            JSONObject f = follows.optJSONObject(i);
            if (f == null) { pending[0]--; continue; }
            final String author = f.optString("handle"), url = f.optString("url"), tid = f.optString("tokenid");
            io.execute(() -> {
                JSONObject prof = httpGetJson(url);
                if (prof != null) {
                    String avatar = prof.optString("avatar", "");
                    JSONArray ps = prof.optJSONArray("posts");
                    if (ps != null) for (int k = 0; k < ps.length(); k++) {
                        JSONObject sourcePost = ps.optJSONObject(k);
                        if (sourcePost == null) continue;
                        try {
                            JSONObject post = new JSONObject(sourcePost.toString());
                            post.put("_author", author.isEmpty() ? prof.optString("handle") : author);
                            post.put("_avatar", avatar); post.put("_tid", tid);
                            posts.add(post);
                        } catch (org.json.JSONException malformed) { /* Skip an unreadable post. */ }
                    }
                }
                final boolean failed = prof == null;
                runOnUiThread(() -> {
                    if (failed) fails[0]++;
                    if (--pending[0] == 0 && ep == renderEpoch && !isFinishing() && !isDestroyed()) paintFeed(posts, status, fails[0]);
                });
            });
        }
        if (pending[0] == 0) paintFeed(posts, status, fails[0]);
    }

    private void paintFeed(List<JSONObject> posts, TextView status, int fails) {
        Collections.sort(posts, (a, b) -> Long.compare(b.optLong("ts", 0), a.optLong("ts", 0)));
        body.removeView(status);
        if (posts.isEmpty()) {
            if (fails > 0) {
                LinearLayout c = card();
                c.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_unreachable, fails)));
                c.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_pools_lp_try_again), true, this::render), lp(0, 10, 0, 0));
                body.addView(c, lp(0, 0, 0, 12));
            } else body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_no_posts)), lp(0, 0, 0, 12));
            return;
        }
        for (JSONObject post : posts) {
            LinearLayout c = card();
            LinearLayout head = row();
            head.addView(avatarView(post.optString("_avatar"), post.optString("_author"), 34));
            TextView who = Design.text(this, "@" + post.optString("_author"), 13, Design.ACCENT(), Design.sansBold());
            who.setPadding(dp(9), 0, 0, 0);
            who.setOnClickListener(v -> openTokenProfile(post.optString("_tid")));
            head.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
            CharSequence when = socialTime(post.optLong("ts", 0));
            if (when.length() != 0) head.addView(Design.text(this, when, 10.5f, Design.DIM(), Design.mono()));
            c.addView(head, lp(0, 0, 0, 8));
            if (!post.optString("text").isEmpty()) c.addView(Design.body(this, post.optString("text")), lp(0, 0, 0, 6));
            addPostMedia(c, post);
            body.addView(c, lp(0, 0, 0, 12));
        }
    }

    /* ================= DISCOVER ================= */

    private void renderDiscover() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_discover));
        LinearLayout head = card();
        head.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_discover_help, SalonRegistry.SALON_ADDRESS)));
        body.addView(head, lp(0, 0, 0, 12));
        // Explicit refresh (pull-to-refresh also works) — re-reads the square.
        body.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_refresh), false, () -> { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_refreshing)); mProfileCache.clear(); mRegistryCache = null; render(); }), lp(0, 0, 0, 10));
        TextView status = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_reading));
        body.addView(status, lp(0, 0, 0, 8));
        if (!nodeUp) { Language.setText(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_waiting_node)); return; }
        final int ep = renderEpoch;
        registryList(rawEntries -> runOnUiThread(() -> {
            if (ep != renderEpoch) return;   // user navigated away before the square loaded
            body.removeView(status);
            java.util.List<SalonRegistry.Entry> entries = new java.util.ArrayList<>(rawEntries);
            // Always show yourself first — your beacon may still be confirming after a re-announce,
            // so the square never looks empty to you while your identity exists locally.
            String myTid = SalonStore.get(this, "tokenid");
            if (!myTid.isEmpty()) {
                boolean present = false;
                for (SalonRegistry.Entry e : entries) if (myTid.equals(e.tokenid)) { present = true; break; }
                if (!present) entries.add(0, new SalonRegistry.Entry(myTid, SalonStore.get(this, "profileUrl"), SalonStore.get(this, "handle")));
            }
            if (entries.isEmpty()) { body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_empty_directory)), lp(0, 0, 0, 12)); return; }
            for (SalonRegistry.Entry e : entries) {
                LinearLayout c = card(); c.setClickable(true); Design.pressable(c);
                c.setOnClickListener(v -> openProfile(e));
                LinearLayout r = row();
                final FrameLayout avSlot = new FrameLayout(this);
                avSlot.addView(avatarView("", e.handle, 48));
                r.addView(avSlot, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, 0, 0);
                final TextView nameTv = Design.text(this, e.handle.isEmpty() ? "…" : e.handle, 16, Design.INK(), Design.sansBold());
                final TextView handleTv = Design.text(this, "@" + e.handle, 12, Design.ACCENT(), Design.mono());
                final TextView bioTv = Design.text(this, "", 12.5f, Design.DIM(), Design.sans()); bioTv.setMaxLines(2); bioTv.setVisibility(View.GONE);
                col.addView(nameTv); col.addView(handleTv); col.addView(bioTv);
                r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                if (SalonStore.isFollowing(this, e.tokenid)) r.addView(Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_following), Design.PILL_DONE));
                c.addView(r);
                body.addView(c, lp(0, 0, 0, 10));
                // enrich the card from the hosted profile: real avatar, display name, bio
                io.execute(() -> { JSONObject prof = httpGetJson(e.url); if (prof == null) return; runOnUiThread(() -> {
                    if (ep != renderEpoch || isFinishing() || isDestroyed()) return;
                    String nm = prof.optString("name", ""), av = prof.optString("avatar", ""), bio = prof.optString("bio", ""), wv = prof.optString("webvalidate", "");
                    if (!nm.isEmpty()) nameTv.setText(nm);
                    avSlot.removeAllViews(); avSlot.addView(badgedAvatar(av, e.handle, e.tokenid, wv, 48));
                    if (!bio.isEmpty()) { bioTv.setText(bio); bioTv.setVisibility(View.VISIBLE); }
                }); });
            }
        }));
    }

    private boolean viewFailed = false;
    private int profileRequest = 0;
    private void openProfile(SalonRegistry.Entry e) {
        // A holding expanded on one profile must not carry its open state to the next.
        if (viewEntry == null || !viewEntry.tokenid.equals(e.tokenid)) expandedNft = null;
        viewEntry = e; viewProfile = null; viewFailed = false;
        final int request = ++profileRequest;
        go(Screen.VIEW);
        io.execute(() -> { JSONObject p = httpGetJson(e.url); runOnUiThread(() -> {
            if (request != profileRequest || viewEntry != e || isFinishing() || isDestroyed()) return;
            viewProfile = p; viewFailed = (p == null); if (screen == Screen.VIEW) render();
        }); });
    }

    private void openTokenProfile(String tokenid) {
        final int request = ++profileRequest, ep = renderEpoch;
        registryList(entries -> runOnUiThread(() -> {
            if (request != profileRequest || ep != renderEpoch || isFinishing() || isDestroyed()) return;
            for (SalonRegistry.Entry e : entries) if (e.tokenid.equals(tokenid)) { openProfile(e); return; }
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_not_listed));
        }));
    }

    /* ================= VIEW someone else ================= */

    private void renderView() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_salon));
        if (viewEntry == null) { go(Screen.DISCOVER); return; }
        LinearLayout bar = row();
        bar.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_back_discover), false, () -> go(Screen.DISCOVER)), weight(-2, 0, 4));
        boolean following = SalonStore.isFollowing(this, viewEntry.tokenid);
        bar.addView(btn(following ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_unfollow) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_follow), !following, () -> {
            if (following) SalonStore.unfollow(this, viewEntry.tokenid);
            else SalonStore.follow(this, viewEntry.tokenid, viewEntry.handle, viewEntry.url);
            render();
        }), weight(-2, 4, 0));
        body.addView(bar, lp(0, 0, 0, 8));
        if (viewProfile == null) {
            if (viewFailed) {
                LinearLayout c = card();
                c.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_page_unreachable, viewEntry.handle)));
                LinearLayout er = row();
                er.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_pools_lp_try_again), true, () -> openProfile(viewEntry)), weight(-2, 0, 4));
                er.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_open_browser), false, () -> openUrl(viewEntry.url)), weight(-2, 4, 0));
                c.addView(er, lp(0, 10, 0, 0));
                body.addView(c, lp(0, 0, 0, 12));
            } else body.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_page_loading, viewEntry.handle)), lp(0, 0, 0, 12));
            return;
        }
        String tipAddr = viewProfile.optString("tipaddr", "");
        String peerMsgpk = viewProfile.optString("msgpk", "");
        String peerMx = viewProfile.optString("mxaddr", "");
        boolean canMsg = !tipAddr.isEmpty() && !peerMsgpk.isEmpty();
        if (!tipAddr.isEmpty()) {
            LinearLayout arow = row();
            if (canMsg) arow.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_message), true, () -> {
                // Learn/refresh their Maxima address from their page. Sticky: an empty
                // value (stale fetch, or they're not advertising one right now) is a
                // no-op, so we never lose an address we already knew.
                MailDb.get(this).setMxAddr(peerMsgpk, peerMx);
                openThread(peerMsgpk, viewEntry.handle, viewProfile.optString("avatar", ""), tipAddr);
            }), weight(-2, 0, 4));
            arow.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_tip), false, () -> tipDialog(viewEntry.handle, tipAddr)), weight(-2, canMsg ? 4 : 0, 0));
            body.addView(arow, lp(0, 0, 0, 10));
        }
        renderProfilePage(viewProfile, false);
    }

    /* ================= HOME / My Salon ================= */

    private void renderHome() {
        if (!SalonStore.hasIdentity(this)) { masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_salon)); renderOnboard(); return; }
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_my_salon));
        renderProfilePage(buildProfileJson(), true);
    }

    /** The one renderer for any Salon — yours (mine=true) or someone else's. */
    private void renderProfilePage(JSONObject p, boolean mine) {
        final String boundIdentity = (!mine && viewEntry != null) ? viewEntry.tokenid : p.optString("tokenid");
        LinearLayout headCard = card();
        String banner = p.optString("banner", "");
        if (!banner.isEmpty()) {
            ImageView bn = new ImageView(this); bn.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.loadFull(this, banner, bn);
            headCard.addView(bn, new LinearLayout.LayoutParams(-1, dp(130)));
        }
        LinearLayout idrow = row();
        idrow.addView(badgedAvatar(p.optString("avatar"), p.optString("handle"), boundIdentity, p.optString("webvalidate"), 66));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, 0, 0);
        col.addView(Design.text(this, p.optString("name"), 19, Design.INK(), Design.sansBold()));
        TextView h = Design.text(this, "@" + p.optString("handle"), 13, Design.ACCENT(), Design.mono());
        copyOnTap(h, "@" + p.optString("handle")); col.addView(h);
        idrow.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        headCard.addView(idrow, lp(0, banner.isEmpty() ? 0 : 10, 0, 6));
        if (!p.optString("bio").isEmpty()) headCard.addView(Design.body(this, p.optString("bio")), lp(0, 4, 0, 2));
        // Stats — the at-a-glance social summary. Counts come straight off the
        // profile; "following" is only meaningful (and known) on your own page.
        LinearLayout stats = row(); boolean anyStat = false;
        int nPosts = arrLen(p, "posts"), nHold = arrLen(p, "nfts"), nGal = arrLen(p, "gallery");
        if (nPosts > 0) { stats.addView(statPill(nPosts, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_salon_social_post_count, nPosts))); anyStat = true; }
        if (nHold  > 0) { stats.addView(statPill(nHold, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_salon_social_holding_count, nHold))); anyStat = true; }
        if (nGal   > 0) { stats.addView(statPill(nGal, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_salon_social_media_count, nGal))); anyStat = true; }
        if (mine) { int nFol = SalonStore.follows(this).length(); if (nFol > 0) { stats.addView(statPill(nFol, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_salon_social_following_count, nFol))); anyStat = true; } }
        if (anyStat) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false); hs.addView(stats);   // never clip on narrow screens
            headCard.addView(hs, lp(0, 8, 0, 2));
        }
        // Freshness: when the page was last published, so you can tell if a Salon
        // has changed since you last looked (shown for your own page too).
        long upd = p.optLong("updated", 0);
        if (upd > 0) {
            CharSequence freshness = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_updated, socialTime(upd));
            headCard.addView(Design.text(this, freshness, 10.5f, Design.DIM(), Design.mono()), lp(0, 6, 0, 0));
        }
        // web-validation shield (optional webvalidate URL in profile)
        final LinearLayout shieldSlot = new LinearLayout(this);
        headCard.addView(shieldSlot);
        String wv = p.optString("webvalidate", "");
        if (!wv.isEmpty()) {
            final String host = urlHost(wv);
            Boolean st = WebValidate.status(boundIdentity, wv);
            if (Boolean.TRUE.equals(st)) shieldSlot.addView(verifiedBadge(host));
            WebValidate.ensure(this, shieldSlot, boundIdentity, wv, () -> { shieldSlot.removeAllViews(); if (Boolean.TRUE.equals(WebValidate.status(boundIdentity, wv))) shieldSlot.addView(verifiedBadge(host)); });
        }
        body.addView(headCard, lp(0, 0, 0, 12));

        if (mine) {
            LinearLayout actions = row();
            actions.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_edit_page), true, () -> go(Screen.EDIT)), weight(-2, 0, 4));
            actions.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atomix_market_publish), false, () -> publishSalon(null)), weight(-2, 4, 0));
            body.addView(actions, lp(0, 0, 0, 8));
            String myUrl = SalonStore.get(this, "profileUrl");
            if (!myUrl.isEmpty()) {
                LinearLayout share = row();
                share.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_share_page), false, () -> sharePage(myUrl)), weight(-2, 0, 4));
                if (myUrl.startsWith("http")) share.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_open_browser), false, () -> openUrl(myUrl)), weight(-2, 4, 0));
                body.addView(share, lp(0, 0, 0, 12));
            }
        }

        if (!p.optString("about").isEmpty()) {
            LinearLayout c = card(); c.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_about)));
            c.addView(Design.body(this, p.optString("about")), lp(0, 6, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray links = p.optJSONArray("links");
        if (links != null && links.length() > 0) {
            LinearLayout c = card(); c.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_links)));
            for (int i = 0; i < links.length(); i++) {
                JSONObject l = links.optJSONObject(i); if (l == null) continue;
                c.addView(linkRow(l.optString("label", l.optString("url")), l.optString("url")));
            }
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray gal = p.optJSONArray("gallery");
        if (gal != null && gal.length() > 0) {
            LinearLayout c = card(); c.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_wallet_gallery)));
            // Bound the gallery to a scroll window so a large collection doesn't
            // stretch the page. Short galleries measure under the cap and just show.
            LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL);
            renderGallery(inner, gal);
            c.addView(boundedBox(inner, 560), lp(0, 2, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray posts = p.optJSONArray("posts");
        if (posts != null && posts.length() > 0) {
            LinearLayout c = card();
            LinearLayout ph = row();
            ph.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_posts)), new LinearLayout.LayoutParams(0, -2, 1));
            ph.addView(Design.text(this, String.valueOf(posts.length()), 11f, Design.DIM(), Design.mono()));
            c.addView(ph);
            // Newest first.
            final List<JSONObject> plist = new ArrayList<>();
            for (int i = posts.length() - 1; i >= 0; i--) { JSONObject po = posts.optJSONObject(i); if (po != null) plist.add(po); }
            // A short page renders inline; once it would run long, the Posts section
            // becomes a fixed-height window with its own scrollbar so the page as a
            // whole never gets unruly, no matter how many posts accumulate.
            final int INLINE_MAX = 4;
            if (plist.size() <= INLINE_MAX) {
                for (int i = 0; i < plist.size(); i++) c.addView(buildProfilePost(plist.get(i), i > 0));
            } else {
                BoundedRecycler rv = new BoundedRecycler(this, dp(480));
                ImpAdapter<JSONObject> ad = new ImpAdapter<JSONObject>() {
                    @Override View bind(JSONObject post, int pos) { return buildProfilePost(post, pos > 0); }
                };
                rv.setAdapter(ad); ad.set(plist);
                c.addView(rv, lp(0, 6, 0, 0));
                c.addView(Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_scroll_posts, plist.size()), 9.5f, Design.DIM(), Design.mono()), lp(0, 6, 0, 0));
            }
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray nfts = p.optJSONArray("nfts");
        // Bind proofs to the ON-CHAIN discovered tokenid when viewing others, not the
        // profile's self-declared one — else a profile could paste someone else's valid proofs.
        String bindTid = boundIdentity;
        if (nfts != null && nfts.length() > 0) renderNftShowcase(nfts, bindTid);

        if (mine && emptyProfile(p)) {
            LinearLayout nud = card(); nud.setBackground(Design.dashed(this, Design.CARD(), Design.DIM()));
            nud.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_profile_empty)));
            nud.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_edit_page), true, () -> go(Screen.EDIT)), lp(0, 10, 0, 0));
            body.addView(nud, lp(0, 0, 0, 12));
        }

        if (mine) {
            LinearLayout meta = card(); meta.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_your_page)));
            copyRow(meta, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_identity_token), p.optString("tokenid"));
            meta.addView(linkRow(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_profile_url), SalonStore.get(this, "profileUrl")));
            body.addView(meta, lp(0, 0, 0, 12));
        }
    }

    private int arrLen(JSONObject p, String key) { JSONArray a = p.optJSONArray(key); return a == null ? 0 : a.length(); }

    /** A number + label chip for the profile stats row (e.g. "12 POSTS"). */
    private View statPill(int n, CharSequence label) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.HORIZONTAL); p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
        p.setPadding(dp(9), dp(5), dp(9), dp(6));
        p.addView(Design.text(this, String.valueOf(n), 13, Design.INK(), Design.sansBold()));
        TextView l = Design.text(this, Language.upper(label), 9f, Design.DIM(), Design.sansBold()); l.setLetterSpacing(0.08f); l.setPadding(dp(4), 0, 0, 0);
        p.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8);
        p.setLayoutParams(lp);
        return p;
    }

    /** One post block for the profile Posts section (inline or windowed row binder). */
    private View buildProfilePost(JSONObject post, boolean divider) {
        LinearLayout pc = new LinearLayout(this); pc.setOrientation(LinearLayout.VERTICAL);
        pc.setPadding(0, dp(8), 0, dp(8));
        if (divider) pc.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
        CharSequence when = socialTime(post.optLong("ts", 0));
        if (when.length() != 0) pc.addView(Design.text(this, when, 10f, Design.DIM(), Design.mono()), lp(0, 6, 0, 0));
        if (!post.optString("text").isEmpty()) pc.addView(Design.body(this, post.optString("text")), lp(0, when.length() == 0 ? 6 : 2, 0, 4));
        addPostMedia(pc, post);
        return pc;
    }

    /** A renderable icon for a showcased holding, or null. Resolves the stored
     *  metadata icon via {@link IconResolver} (unwraps &lt;artimage&gt;/base64/data:/http/
     *  ipfs), else the first PUBLISHED edition plate ({@code editions[0].url} — already
     *  a data: URI for embedded StateNFTs, so this backfills existing listings for the
     *  owner AND remote viewers). null means only a live coin-state read (owner) can
     *  produce one — see {@link #liveFillNftImage}. */
    private String nftIcon(JSONObject n) {
        String icon = IconResolver.resolve(n.optString("image"));
        if (icon != null) return icon;
        JSONArray eds = n.optJSONArray("editions");
        if (eds != null && eds.length() > 0) {
            JSONObject e0 = eds.optJSONObject(0);
            String u = e0 == null ? "" : e0.optString("url");
            if (!u.isEmpty()) return u;
        }
        return null;
    }

    /** Owner-only fallback: pull the embedded plate from the first held coin's
     *  state[1] and fill {@code iv}. For legacy holdings stored with no icon and no
     *  captured editions; reuses the working {@link #enumerateEditions} pipeline. */
    private void liveFillNftImage(final ImageView iv, final String tid, final boolean full) {
        if (!nodeUp || tid == null || tid.isEmpty()) return;
        final String cachedUrl = mLiveIcon.get(tid);
        if (cachedUrl != null) {   // resolved this session already — no repeat edition scan on re-render
            if (full) ImageLoader.loadFull(this, cachedUrl, iv); else ImageLoader.loadOver(this, cachedUrl, iv);
            return;
        }
        enumerateEditions(tid, editions -> {
            if (editions == null || editions.isEmpty()) return;
            final String u = editions.get(0).optString("url");
            if (u.isEmpty()) return;
            mLiveIcon.put(tid, u);
            runOnUiThread(() -> { if (full) ImageLoader.loadFull(this, u, iv); else ImageLoader.loadOver(this, u, iv); });
        });
    }

    /** Render showcased holdings; the VIEWER's node verifies each live. Tap a row to
     *  expand its details + a manual re-verify. */
    private void renderNftShowcase(JSONArray nfts, String profileTokenid) {
        LinearLayout c = card();
        LinearLayout hh = row();
        hh.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_holdings)), new LinearLayout.LayoutParams(0, -2, 1));
        hh.addView(Design.text(this, String.valueOf(nfts.length()), 11f, Design.DIM(), Design.mono()));
        c.addView(hh);
        // Rows go into their own container so a long collection can be a bounded
        // scroll window — but only while nothing is expanded, so opening a holding
        // still shows its full artwork and editions at full size.
        final LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        final String bindHex = NftProof.hexOf(profileTokenid);
        for (int i = 0; i < nfts.length(); i++) {
            final JSONObject n = nfts.optJSONObject(i); if (n == null) continue;
            final String tid = n.optString("tokenid");
            final boolean open = tid != null && tid.equals(expandedNft);
            LinearLayout r = row(); r.setPadding(0, dp(8), 0, dp(8)); r.setClickable(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF141310);
            String iconSrc = nftIcon(n);
            if (iconSrc != null) ImageLoader.loadOver(this, iconSrc, iv);
            else liveFillNftImage(iv, tid, false);   // embedded plate lives in coin state[1] — pull it live (owner)
            r.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(Design.text(this, n.optString("name", Util.shorten(tid)), 15, Design.INK(), Design.sansBold()));
            final TextView badge = Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verifying), 11f, Design.DIM(), Design.mono());
            col.addView(badge);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Design.text(this, open ? "▾" : "▸", 15, Design.DIM(), Design.mono()));
            r.setOnClickListener(v -> { expandedNft = open ? null : tid; render(); });
            rows.addView(r);
            verifyInto(n, bindHex, badge);

            if (open) {   // expanded: full main icon (tap→fullscreen), ids, verify, tappable editions gallery
                LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
                d.setPadding(dp(4), dp(2), dp(4), dp(10));
                final String iconUrl = nftIcon(n);
                ImageView hero = new ImageView(this); hero.setScaleType(ImageView.ScaleType.FIT_CENTER); hero.setBackgroundColor(0xFF141310);
                d.addView(hero, new LinearLayout.LayoutParams(-1, dp(300)));
                if (iconUrl != null) {
                    ImageLoader.loadFull(this, iconUrl, hero); hero.setClickable(true); hero.setOnClickListener(v -> openImage(iconUrl));
                    d.addView(Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_full_artwork), 10.5f, Design.DIM(), Design.mono()), lp(0, 4, 0, 2));
                } else {
                    liveFillNftImage(hero, tid, true);   // embedded plate from coin state[1] (owner)
                }
                copyRow(d, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_token_id), tid);
                if (!n.optString("coinid").isEmpty()) copyRow(d, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_coin_id), n.optString("coinid"));
                final TextView vline = Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verifying), 12f, Design.DIM(), Design.mono());
                d.addView(vline, lp(0, 8, 0, 6));
                d.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verify_again), false, () -> verifyInto(n, bindHex, vline, true)), lp(0, 4, 0, 0));
                rows.addView(d);
                verifyInto(n, bindHex, vline);
                // Editions: prefer the PUBLISHED set the owner captured at prove
                // time (so a remote VIEWER sees the full suite, not just the top
                // icon). Fall back to a live scan of THIS node for the owner's own
                // page (or legacy items published before editions were captured).
                JSONArray pubEds = n.optJSONArray("editions");
                if (pubEds != null && pubEds.length() > 0) {
                    java.util.List<JSONObject> eds = new java.util.ArrayList<>();
                    for (int j = 0; j < pubEds.length(); j++) { JSONObject e = pubEds.optJSONObject(j); if (e != null) eds.add(e); }
                    renderEditionTiles(d, eds, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_collection_images, eds.size()));
                } else {
                    renderEditionArt(d, tid);   // live gallery of what this node holds
                }
            }
        }
        // Browsing many holdings → bounded scroll window; expanded → full height.
        if (nfts.length() > 3 && expandedNft == null) c.addView(boundedBox(rows, 520), lp(0, 2, 0, 0));
        else c.addView(rows);
        body.addView(c, lp(0, 0, 0, 12));
    }

    /** Verify a showcase holding on THIS device's node; write the outcome (with the
     *  failing-stage reason) into {@code tv}. Green on success, vermilion on failure. */
    private final java.util.Set<String> proofRefreshed = new java.util.HashSet<>();

    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv) {
        verifyInto(n, bindHex, tv, false);
    }

    /** Explicit retry replaces an older request; duplicate badges share the check. */
    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv, boolean force) {
        tv.setTag(null); // Rebinding also invalidates an old callback on this view.
        if (!nodeUp) { Language.setText(tv, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_connect)); tv.setTextColor(Design.DIM()); return; }
        final String owner = SalonStore.get(this, "tokenid");
        final int epoch = renderEpoch;
        final JSONObject snapshot = ProofRequests.snapshot(n);
        final String key = ProofRequests.key(snapshot, bindHex, owner);
        final boolean ownPage = !owner.isEmpty() && bindHex != null && bindHex.equalsIgnoreCase(NftProof.hexOf(owner));
        Language.setText(tv, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verifying));
        tv.setTextColor(Design.DIM());
        java.util.function.Consumer<ProofRequests.Result> listener = new java.util.function.Consumer<ProofRequests.Result>() {
            @Override public void accept(ProofRequests.Result result) {
                if (tv.getTag() != this) return;
                tv.setTag(null);
                if (isFinishing() || isDestroyed() || epoch != renderEpoch || !nodeUp
                        || !owner.equals(SalonStore.get(MainActivity.this, "tokenid"))
                        || !key.equals(ProofRequests.key(n, bindHex, owner))) return;
                Language.setText(tv, result.verified
                        ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verified)
                        : Language.join("✕ ", HoldingMessages.message(result.reason)));
                tv.setTextColor(result.verified ? 0xFF1F7A3F : 0xFFB4462A);
                if (result.live && ownPage) maybeRefreshProof(n, bindHex);
            }
        };
        tv.setTag(listener); // Requests retain subscribers weakly, as in WebValidate.
        proofRequests.request(key, force, done -> {
            Runnable bound = () -> NftProof.verify(node, snapshot, bindHex,
                    (ok, reason, image) -> done.accept(new ProofRequests.Result(ok, reason, false)));
            if (ownPage) NftProof.holds(node, snapshot.optString("tokenid"), (held, coin) -> {
                if (held) done.accept(new ProofRequests.Result(true, "verified holding", true));
                else bound.run();
            });
            else bound.run();
        }, listener);
    }

    private String storedHolding(String tokenid) {
        JSONArray items = SalonStore.arr(this, "nfts");
        for (int i=0; i<items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && tokenid.equalsIgnoreCase(item.optString("tokenid"))) return item.toString();
        }
        return "";
    }

    /** Refresh once per identity/token, only while the saved holding remains unchanged. */
    private void maybeRefreshProof(final JSONObject n, final String bindHex) {
        final String tid = n.optString("tokenid"), owner = SalonStore.get(this, "tokenid"), draft = n.toString();
        if (tid.isEmpty() || owner.isEmpty() || bindHex == null || !bindHex.equalsIgnoreCase(NftProof.hexOf(owner))) return;
        final java.util.function.BooleanSupplier current = () -> !isFinishing() && !isDestroyed()
                && owner.equals(SalonStore.get(this, "tokenid")) && draft.equals(storedHolding(tid));
        final String request = owner + "|" + tid;
        if (!current.getAsBoolean() || !proofRefreshed.add(request)) return;
        NftProof.generate(node, tid, bindHex, current, new NftProof.Gen() {
            @Override public void ok(JSONObject item) {
                if (!current.getAsBoolean()) return;
                try { item.put("name", n.optString("name")); item.put("image", n.optString("image")); } catch (Exception ignored) {}
                captureEditions(tid, editions -> runOnUiThread(() -> {
                    if (!current.getAsBoolean()) return;
                    try { item.put("editions", editions); } catch (Exception ignored) {}
                    JSONArray arr = SalonStore.arr(MainActivity.this, "nfts");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && draft.equals(o.toString())) {
                            try { arr.put(i, item); } catch (Exception ignored) { return; }
                            SalonStore.setArr(MainActivity.this, "nfts", arr); break;
                        }
                    }
                }));
            }
            @Override public void fail(String message) {}
        });
    }

    private interface EditionsCb { void ready(java.util.List<JSONObject> editions); }

    /** Enumerate the StateNFT editions THIS node holds for a token, as {url,caption}
     *  tiles. Handles BOTH modes: embedded state art, and url-mode
     *  ({@code base + index + ext}) collections like "gallery bibeau". base/ext from
     *  the token metadata (balance); index from each coin's state port 0. */
    private void enumerateEditions(final String tokenid, final EditionsCb cb) {
        if (!nodeUp || !Args.isTokenId(tokenid)) { cb.ready(java.util.Collections.emptyList()); return; }
        node.cmd("balance tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject bal) {
                String base = "", ext = ".png";
                JSONArray br = bal.optJSONArray("response");
                JSONObject e0 = br == null || br.length() == 0 ? null : br.optJSONObject(0);
                JSONObject tk = e0 == null ? null : Util.tokenObject(e0.opt("token"));
                JSONObject src = tk == null ? null : Util.asObject(tk.opt("name"));
                if (src == null) src = tk;
                if (src != null && tk != null) {
                    base = firstNonEmpty(src.optString("base", ""), tk.optString("base", ""));
                    ext = firstNonEmpty(src.optString("ext", ""), tk.optString("ext", ""), ".png");
                }
                final String fbase = base, fext = ext;
                node.cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject r) {
                        JSONArray arr = r.optJSONArray("response");
                        final java.util.List<JSONObject> editions = new java.util.ArrayList<>();
                        final java.util.HashSet<String> seen = new java.util.HashSet<>();
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject coin = arr.optJSONObject(i); if (coin == null) continue;
                            String u = NftProof.editionImageUrl(coin, fbase, fext);
                            if (u.isEmpty() || !seen.add(u)) continue;
                            String idx = NftProof.state(coin, 0);
                            try { JSONObject it = new JSONObject(); it.put("url", u);
                                it.put("caption", idx != null && idx.matches("[0-9]+") ? "Edition #" + idx : "Edition"); editions.add(it); }
                            catch (Exception ignored) {}
                        }
                        cb.ready(editions);
                    }
                    @Override public void onError(String m) { cb.ready(java.util.Collections.emptyList()); }
                });
            }
            @Override public void onError(String m) { cb.ready(java.util.Collections.emptyList()); }
        });
    }

    /** A 2-up grid of edition tiles; each taps into a fullscreen swipeable carousel. */
    private void renderEditionTiles(final LinearLayout parent, final java.util.List<JSONObject> editions, final CharSequence label) {
        if (editions.isEmpty()) return;
        parent.addView(Design.text(this, label, 9f, Design.DIM(), Design.sansBold()), lp(0, 12, 0, 6));
        LinearLayout g = null;
        for (int i = 0; i < editions.size(); i++) {
            final int idx = i;
            if (i % 2 == 0) { g = row(); parent.addView(g, lp(0, 0, 0, 6)); }
            ImageView t = new ImageView(this); t.setScaleType(ImageView.ScaleType.CENTER_CROP); t.setBackgroundColor(0xFF141310);
            t.setClickable(true); t.setOnClickListener(v -> openCarousel(editions, idx));
            ImageLoader.loadOver(this, editions.get(i).optString("url"), t);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(168), 1); tp.setMargins(0, 0, i % 2 == 0 ? dp(6) : 0, 0);
            g.addView(t, tp);
        }
        if (editions.size() % 2 == 1) g.addView(new View(this), new LinearLayout.LayoutParams(0, dp(168), 1));
    }

    /** Live editions THIS node holds — the owner viewing their own page. Silent for a
     *  viewer who holds none; a stranger's view uses the PUBLISHED editions instead. */
    private void renderEditionArt(final LinearLayout parent, final String tokenid) {
        enumerateEditions(tokenid, editions -> {
            if (editions.isEmpty()) return;
            runOnUiThread(() -> renderEditionTiles(parent, editions,
                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_held_images, editions.size())));
        });
    }

    /** An edition image url small enough to publish in profile.json (skip embedded
     *  data: URIs that would bloat the page; keep remote refs). */
    private boolean isPublishableEditionUrl(String u) {
        if (u == null || u.length() >= 1024) return false;
        String s = u.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ipfs:")
                || s.startsWith("ar://") || s.startsWith("relay1:") || s.startsWith("mx1:");
    }

    private interface JsonArrayCb { void ready(JSONArray editions); }

    /** Enumerate this node's editions of a token and hand back a publishable JSONArray
     *  (remote-url editions only, capped) to store in a showcase item. */
    private void captureEditions(final String tokenid, final JsonArrayCb done) {
        enumerateEditions(tokenid, editions -> {
            JSONArray ed = new JSONArray();
            for (JSONObject e : editions) {
                if (ed.length() >= 120) break;
                if (isPublishableEditionUrl(e.optString("url"))) ed.put(e);
            }
            done.ready(ed);
        });
    }

    /* ---------------- prove & showcase a holding ---------------- */

    private void pickNftDialog() {
        if (!nodeUp) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_connect_node)); return; }
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_reading));
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = j.optJSONArray("response");
                java.util.List<JSONObject> assets = new java.util.ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.optJSONObject(i); if (row == null) continue;
                    String tid = row.optString("tokenid", ""); if (tid.isEmpty() || tid.equals("0x00")) continue;
                    Object tok = row.opt("token"); String name = "", image = "";
                    JSONObject tm = Util.tokenObject(tok);
                    if (tm != null) {
                        Object nm = tm.opt("name");
                        JSONObject parsedName = Util.asObject(nm);
                        if (parsedName != null) nm = parsedName;
                        // Atelier/StateNFT metadata is an envelope: the minted JSON (name, url,
                        // icon, mode, …) lives under token.name. Read the icon from there, then
                        // resolve <artimage>/base64/data:/http/ipfs into a renderable string.
                        JSONObject md = nm instanceof JSONObject ? (JSONObject) nm : tm;
                        name = nm instanceof JSONObject ? md.optString("name", "") : tm.optString("name", "");
                        String rawIcon = firstNonEmpty(md.optString("url"), md.optString("icon"), md.optString("image"),
                                tm.optString("url"), tm.optString("icon"), tm.optString("image"));
                        String resolved = IconResolver.resolve(rawIcon);
                        image = resolved != null ? resolved : "";
                    } else if (tok instanceof String) name = (String) tok;
                    if (name.isEmpty()) name = Util.shorten(tid);
                    try { JSONObject a = new JSONObject(); a.put("tokenid", tid); a.put("name", name); a.put("image", image); assets.add(a); } catch (Exception ignored) {}
                }
                runOnUiThread(() -> showAssetPicker(assets));
            }
            @Override public void onError(String m) { runOnUiThread(() -> toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_balance_failed, HoldingMessages.message(m)))); }
        });
    }

    private void showAssetPicker(java.util.List<JSONObject> assets) {
        if (assets.isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_empty)); return; }
        CharSequence[] items = new CharSequence[assets.size()];
        for (int i = 0; i < assets.size(); i++) items[i] = assets.get(i).optString("name");
        new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_prove)).setItems(items, (d, which) -> proveNft(assets.get(which))).show();
    }

    private boolean provingHolding;

    private void proveNft(JSONObject asset) {
        if (provingHolding) { toast(HoldingMessages.message("A holding proof is already being created.")); return; }
        final String myTid = SalonStore.get(this, "tokenid");
        final String assetDraft = asset.toString(), holdingsDraft = SalonStore.arr(this, "nfts").toString();
        final java.util.function.BooleanSupplier current = () -> !isFinishing() && !isDestroyed()
                && myTid.equals(SalonStore.get(this, "tokenid")) && assetDraft.equals(asset.toString())
                && holdingsDraft.equals(SalonStore.arr(this, "nfts").toString());
        if (myTid.isEmpty()) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_claim_first)); return; }
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_generating));
        provingHolding = true;
        NftProof.generate(node, asset.optString("tokenid"), NftProof.hexOf(myTid), current, new NftProof.Gen() {
            @Override public void ok(JSONObject item) {
                if (!current.getAsBoolean()) { provingHolding = false; return; }
                try { item.put("name", asset.optString("name")); item.put("image", asset.optString("image")); } catch (Exception ignored) {}
                // Capture the editions this node holds so a remote viewer sees the
                // whole collection, not just the top icon. Then store + publish.
                captureEditions(asset.optString("tokenid"), editions -> {
                    if (!current.getAsBoolean()) { provingHolding = false; return; }
                    try {
                        item.put("editions", editions);
                        // Embedded StateNFT with no small metadata icon: seed the showcase
                        // icon from the first edition's plate (a data: URI) so it renders and
                        // gets baked into profile.json for remote viewers on publish.
                        if (item.optString("image").isEmpty() && editions != null && editions.length() > 0) {
                            JSONObject e0 = editions.optJSONObject(0);
                            if (e0 != null && !e0.optString("url").isEmpty()) item.put("image", e0.optString("url"));
                        }
                    } catch (Exception ignored) {}
                    runOnUiThread(() -> { provingHolding = false; if (!current.getAsBoolean()) return; JSONArray a = SalonStore.arr(MainActivity.this, "nfts"); a.put(item); SalonStore.setArr(MainActivity.this, "nfts", a); toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_added)); render(); });
                });
            }
            @Override public void fail(String msg) { runOnUiThread(() -> { provingHolding = false; if (current.getAsBoolean()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_prove_failed, HoldingMessages.message(msg))); }); }
        });
    }

    private String firstNonEmpty(String... xs) { for (String x : xs) if (x != null && !x.isEmpty()) return x; return ""; }

    /* ================= EDIT hub ================= */

    private EditText edName, edBio, edAbout, edAvatar, edBanner, edWebvalidate; private TextView profStatus;

    /** Persist the Edit text fields to the local draft so an in-Edit render() (add link,
     *  upload, delete a row) or navigating away never discards what the user typed. */
    private void commitEditFields() {
        if (edName == null) return;
        SalonStore.put(this, "name", text(edName));
        SalonStore.put(this, "bio", text(edBio));
        SalonStore.put(this, "about", text(edAbout));
        SalonStore.put(this, "avatar", text(edAvatar));
        SalonStore.put(this, "banner", text(edBanner));
        SalonStore.put(this, "webvalidate", text(edWebvalidate));
    }

    private void renderEdit() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_title));
        body.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_back), false, () -> go(Screen.HOME)), lp(0, 0, 0, 10));
        JSONObject me = SalonStore.me(this);
        LinearLayout who = card(); who.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_you)));
        edName = field(who, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_display_name), me.optString("name"), false, "");
        edBio = field(who, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_bio), me.optString("bio"), false, "");
        edAbout = fieldMulti(who, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_about), me.optString("about"));
        EditText name = edName, bio = edBio, about = edAbout;   // aliases for the existing Save handler below
        edAvatar = field(who, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_avatar), me.optString("avatar"), false, "https://…");
        who.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_upload_avatar), false, () -> pickMedia(PICK_AVATAR, "image/*")), lp(0, 4, 0, 6));
        edBanner = field(who, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_banner), me.optString("banner"), false, "https://…");
        who.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_upload_banner), false, () -> pickMedia(PICK_BANNER, "image/*")), lp(0, 4, 0, 2));
        body.addView(who, lp(0, 0, 0, 12));

        // Verification — the anti-impersonation defence (handles aren't unique).
        LinearLayout ver = card(); ver.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_verify)));
        ver.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_verify_help, me.optString("handle"))), lp(0, 6, 0, 4));
        copyRow(ver, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_token), me.optString("tokenid"));
        edWebvalidate = field(ver, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_web_url), me.optString("webvalidate"), false, "https://eurobuddha.com/salon.txt");
        body.addView(ver, lp(0, 0, 0, 12));

        // Links
        LinearLayout linksCard = card(); linksCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_links)));
        JSONArray links = SalonStore.arr(this, "links");
        for (int i = 0; i < links.length(); i++) {
            JSONObject l = links.optJSONObject(i); if (l == null) continue; final int idx = i;
            LinearLayout r = row();
            r.addView(Design.text(this, l.optString("label"), 13, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(btn("✕", false, () -> { JSONArray a = SalonStore.arr(this, "links"); a = removeAt(a, idx); SalonStore.setArr(this, "links", a); render(); }), new LinearLayout.LayoutParams(dp(44), -2));
            linksCard.addView(r, lp(0, 4, 0, 4));
        }
        linksCard.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_add_link_action), false, this::addLinkDialog), lp(0, 6, 0, 2));
        body.addView(linksCard, lp(0, 0, 0, 12));

        // Gallery
        LinearLayout galCard = card(); galCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_gallery)));
        JSONArray gal = SalonStore.arr(this, "gallery");
        for (int i = 0; i < gal.length(); i++) {
            JSONObject m = gal.optJSONObject(i); if (m == null) continue; final int idx = i;
            String type = m.optString("type", "image"), cap = m.optString("caption", "");
            LinearLayout r = row(); r.setPadding(0, dp(6), 0, dp(6));
            TextView kind = Design.text(this, type.equals("audio") ? "♪" : type.equals("video") ? "▶" : "▣", 18, Design.ACCENT(), Design.sansBold());
            r.addView(kind, new LinearLayout.LayoutParams(dp(28), -2));
            LinearLayout cc = new LinearLayout(this); cc.setOrientation(LinearLayout.VERTICAL); cc.setPadding(dp(8), 0, 0, 0);
            cc.addView(Design.text(this, cap.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_untitled) : cap, 13.5f, Design.INK(), Design.sansBold()));
            cc.addView(Design.text(this, Language.upper(type.equals("audio") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_music) : type.equals("video") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_video) : type.equals("image") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_chat_photo) : type), 9f, Design.DIM(), Design.sansBold()));
            r.addView(cc, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_chat_rename), false, () -> renameGalleryItem(idx)), new LinearLayout.LayoutParams(dp(88), -2));
            r.addView(btn("✕", false, () -> { JSONArray a = removeAt(SalonStore.arr(this, "gallery"), idx); SalonStore.setArr(this, "gallery", a); render(); }), new LinearLayout.LayoutParams(dp(44), -2));
            galCard.addView(r);
            galCard.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
        }
        LinearLayout addRow = row();
        addRow.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_add_photo), false, () -> pickMedia(PICK_GALLERY_IMG, "image/*")), weight(-2, 0, 4));
        addRow.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_add_video), false, () -> pickMedia(PICK_GALLERY_VID, "video/*")), weight(-2, 4, 4));
        addRow.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_add_music), false, () -> pickMedia(PICK_GALLERY_AUD, "audio/*")), weight(-2, 4, 0));
        galCard.addView(addRow, lp(0, 6, 0, 0));
        body.addView(galCard, lp(0, 0, 0, 12));

        // Posts
        LinearLayout postCard = card(); postCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_posts)));
        postCard.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_new_post_action), false, this::newPostDialog), lp(0, 6, 0, 2));
        body.addView(postCard, lp(0, 0, 0, 12));

        // Showcase NFTs — proven holdings (no faking)
        LinearLayout nftCard = card(); nftCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_showcase)));
        nftCard.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_showcase_help)), lp(0, 4, 0, 6));
        JSONArray nfts = SalonStore.arr(this, "nfts");
        for (int i = 0; i < nfts.length(); i++) {
            JSONObject n = nfts.optJSONObject(i); if (n == null) continue; final int idx = i;
            LinearLayout r = row(); r.setPadding(0, dp(6), 0, dp(6));
            r.addView(Design.text(this, n.optString("name", Util.shorten(n.optString("tokenid"))), 13.5f, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(btn("✕", false, () -> { SalonStore.setArr(this, "nfts", removeAt(SalonStore.arr(this, "nfts"), idx)); render(); }), new LinearLayout.LayoutParams(dp(44), -2));
            nftCard.addView(r);
        }
        nftCard.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_add_holding), false, this::pickNftDialog), lp(0, 6, 0, 2));
        body.addView(nftCard, lp(0, 0, 0, 12));

        profStatus = Design.note(this, ""); body.addView(profStatus, lp(0, 4, 0, 0));
        body.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_publish), true, () -> {
            SalonStore.put(this, "name", text(name)); SalonStore.put(this, "bio", text(bio));
            SalonStore.put(this, "about", text(about));
            SalonStore.put(this, "avatar", text(edAvatar)); SalonStore.put(this, "banner", text(edBanner));
            SalonStore.put(this, "webvalidate", text(edWebvalidate));
            hostProfile(profStatus, () -> publishSalon(profStatus));
        }), lp(0, 8, 0, 0));
    }

    /** A modal edit belongs to the identity and collection draft visible when opened. */
    private java.util.function.BooleanSupplier editorDraftCurrent(String collection) {
        final String owner = SalonStore.get(this, "tokenid");
        final String draft = SalonStore.arr(this, collection).toString();
        return () -> {
            boolean current = !isFinishing() && !isDestroyed()
                    && owner.equals(SalonStore.get(this, "tokenid"))
                    && draft.equals(SalonStore.arr(this, collection).toString());
            if (!current && !isFinishing() && !isDestroyed()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_changed));
            return current;
        };
    }

    private void addLinkDialog() {
        final java.util.function.BooleanSupplier current = editorDraftCurrent("links");
        LinearLayout box = dialogBox();
        EditText label = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mall_studio_ui_label), "", false, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_blog_hint));
        EditText url = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_url), "", false, "https://…");
        showDialog(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_add_link), box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_add), () -> {
            if (!current.getAsBoolean()) return;
            if (text(url).isEmpty()) return;
            JSONArray a = SalonStore.arr(this, "links");
            try { JSONObject o = new JSONObject(); o.put("label", text(label).isEmpty() ? text(url) : text(label)); o.put("url", text(url)); a.put(o); } catch (Exception ignored) {}
            SalonStore.setArr(this, "links", a); render();
        });
    }

    private void newPostDialog() {
        final java.util.function.BooleanSupplier current = editorDraftCurrent("posts");
        LinearLayout box = dialogBox();
        EditText textF = fieldMulti(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_say_something), "");
        showDialog(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_new_post), box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_post), () -> {
            if (!current.getAsBoolean()) return;
            JSONArray a = SalonStore.arr(this, "posts");
            try { JSONObject o = new JSONObject(); o.put("ts", System.currentTimeMillis() / 1000); o.put("text", text(textF)); a.put(o); } catch (Exception ignored) {}
            SalonStore.setArr(this, "posts", a);
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_posted)); render();
        });
    }

    /* ================= media: pick / upload / play ================= */

    private static final int PICK_AVATAR = 41, PICK_BANNER = 42, PICK_GALLERY_IMG = 43, PICK_GALLERY_VID = 44, PICK_GALLERY_AUD = 45, PICK_DM_IMG = 46, SAVE_TEXT = 51, OPEN_BACKUP = 52;

    /** One selection/upload at a time; snapshots contain data, never live editor views. */
    private static final class MediaPick {
        final int code;
        final String identity, handle, target, hostJson;
        final Hosting.Profile host;
        final boolean meshDm;
        volatile boolean cancelled;
        MediaPick(int code, String identity, String handle, String target, Hosting.Profile host, boolean meshDm) {
            this.code = code; this.identity = identity; this.handle = handle; this.target = target;
            this.host = host; this.hostJson = host.j.toString(); this.meshDm = meshDm;
        }
    }
    private MediaPick pendingMediaPick, activeMediaUpload;

    private String mediaIdentity() {
        return new JSONArray().put(SalonStore.get(this, "tokenid")).put(SalonStore.get(this, "handle"))
                .put(SalonStore.get(this, "msgpk")).toString();
    }

    /** Read on the UI thread, so typed values and quoted replies belong to this selection. */
    private String mediaTarget(int code) {
        if (code == PICK_DM_IMG) return new JSONArray().put(threadPeer).put(threadDraft)
                .put(replyPeer).put(replyBody).put(replyFrom).put(SalonStore.get(this, XPORT_PREF + threadPeer)).toString();
        if (code == PICK_AVATAR) return edAvatar == null ? SalonStore.get(this, "avatar") : text(edAvatar);
        if (code == PICK_BANNER) return edBanner == null ? SalonStore.get(this, "banner") : text(edBanner);
        return SalonStore.arr(this, "gallery").toString();
    }

    private boolean mediaHostCurrent(MediaPick pick) {
        if (pick.meshDm) return true; // The chosen mesh transport never falls back to a different host.
        Hosting.Profile host = HostingStore.getDefault(this);
        return host != null && pick.hostJson.equals(host.j.toString());
    }

    private boolean mediaCurrent(MediaPick pick) {
        boolean current = !pick.cancelled && !isFinishing() && !isDestroyed()
                && pick.identity.equals(mediaIdentity()) && mediaHostCurrent(pick)
                && screen == (pick.code == PICK_DM_IMG ? Screen.THREAD : Screen.EDIT)
                && pick.target.equals(mediaTarget(pick.code));
        if (!current) pick.cancelled = true;
        return current;
    }

    private void cancelMediaRequests() {
        if (pendingMediaPick != null) pendingMediaPick.cancelled = true;
        if (activeMediaUpload != null) activeMediaUpload.cancelled = true;
    }

    private void mediaStatus(CharSequence message) {
        if (isFinishing() || isDestroyed()) return;
        if (screen == Screen.EDIT && profStatus != null) Language.setText(profStatus, message);
        toast(message);
    }

    private void pickMedia(int code, String mime) {
        if (code < PICK_AVATAR || code > PICK_DM_IMG) return;
        if (pendingMediaPick != null || activeMediaUpload != null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_busy)); return; }
        if (screen != (code == PICK_DM_IMG ? Screen.THREAD : Screen.EDIT)) return;
        if (code == PICK_DM_IMG && threadPeer.isEmpty()) return;
        boolean meshDm = code == PICK_DM_IMG && MaximaLink.isReady(this);
        Hosting.Profile host = meshDm ? Hosting.Profile.fresh(Hosting.TYPE_MAXIMA) : HostingStore.getDefault(this);
        if (host == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_host)); return; }
        final MediaPick pick = new MediaPick(code, mediaIdentity(), SalonStore.get(this, "handle"), mediaTarget(code), host, meshDm);
        pendingMediaPick = pick;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType(mime);
            startActivityForResult(Intent.createChooser(i, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_choose)), code);
        } catch (RuntimeException unavailable) {
            pendingMediaPick = null; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_picker_failed));
        }
    }

    /** Ignore unowned callbacks after recreation rather than attaching to the current account. */
    private void receiveMedia(int code, int result, Intent data) {
        MediaPick pick = pendingMediaPick;
        if (pick == null || pick.code != code) {
            if (result == RESULT_OK) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_reselect));
            return;
        }
        pendingMediaPick = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (!mediaCurrent(pick)) { mediaStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_changed)); return; }
        activeMediaUpload = pick;
        mediaStatus(pick.code == PICK_DM_IMG ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_sending) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_uploading));
        final Uri uri = data.getData();
        try {
            io.execute(() -> {
                try {
                    if (pick.cancelled || !pick.identity.equals(mediaIdentity()) || !mediaHostCurrent(pick)) {
                        runOnUiThread(() -> finishMedia(pick, null, null)); return;
                    }
                    final byte[] bytes;
                    final String kind, mime, extension;
                    if (pick.code == PICK_GALLERY_VID) { bytes = readCapped(uri, 60); kind = "video"; mime = "video/mp4"; extension = ".mp4"; }
                    else if (pick.code == PICK_GALLERY_AUD) { bytes = readCapped(uri, 25); kind = "audio"; mime = "audio/mpeg"; extension = ".mp3"; }
                    else { bytes = readScaledJpeg(uri, pick.code == PICK_AVATAR ? 640 : 1400); kind = "image"; mime = "image/jpeg"; extension = ".jpg"; }
                    // Recheck typed fields and recipient on the UI before dispatch.
                    runOnUiThread(() -> dispatchMedia(pick, bytes, kind, mime, extension));
                } catch (Exception error) { runOnUiThread(() -> finishMedia(pick, null, error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishMedia(pick, null, unavailable); }
    }

    private void dispatchMedia(MediaPick pick, byte[] bytes, String kind, String mime, String extension) {
        if (activeMediaUpload != pick) return;
        if (!mediaCurrent(pick)) { finishMedia(pick, null, null); return; }
        if (bytes.length == 0) { finishMedia(pick, null, new Exception("cannot read file")); return; }
        // Random suffix prevents two successful selections in the same millisecond colliding.
        final String path = Hosting.slug(pick.handle) + "/media/" + kind + "-" + java.util.UUID.randomUUID() + extension;
        try {
            io.execute(() -> {
                try {
                    if (pick.cancelled || !pick.identity.equals(mediaIdentity()) || !mediaHostCurrent(pick)) {
                        runOnUiThread(() -> finishMedia(pick, null, null)); return;
                    }
                    if (pick.code != PICK_DM_IMG && Hosting.TYPE_MAXIMA.equals(pick.host.type())) {
                        final long usage = meshUsageBytes();
                        if (usage > MAX_PROFILE_MESH_BYTES - bytes.length) {
                            runOnUiThread(() -> {
                                if (activeMediaUpload != pick) return;
                                if (!mediaCurrent(pick)) { finishMedia(pick, null, null); return; }
                                activeMediaUpload = null; mediaStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_mesh_full, mibOf(usage), MAX_PROFILE_MESH_BYTES >> 20));
                            });
                            return;
                        }
                    }
                    final String ref;
                    try (Hosting.Uploader uploader = Hosting.forProfile(pick.host)) { ref = uploader.putFile(bytes, path, mime); }
                    if (ref == null || ref.isEmpty()) throw new Exception("empty upload reference");
                    runOnUiThread(() -> finishMedia(pick, new String[]{kind, ref, mime}, null));
                } catch (Exception error) { runOnUiThread(() -> finishMedia(pick, null, error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishMedia(pick, null, unavailable); }
    }

    private void finishMedia(MediaPick pick, String[] uploaded, Exception error) {
        if (activeMediaUpload != pick) return;
        activeMediaUpload = null;
        if (isFinishing() || isDestroyed()) return;
        if (!mediaCurrent(pick)) {
            if (pick.identity.equals(mediaIdentity())) mediaStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_changed)); else toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_changed));
            return;
        }
        if (error != null) { mediaStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_failed, mediaError(error))); return; }
        if (uploaded == null) { mediaStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_changed)); return; }
        if (pick.code == PICK_DM_IMG) { sendDm("", uploaded[1], uploaded[2]); return; }
        if (pick.code == PICK_AVATAR || pick.code == PICK_BANNER) {
            String key = pick.code == PICK_AVATAR ? "avatar" : "banner";
            EditText field = pick.code == PICK_AVATAR ? edAvatar : edBanner;
            if (field != null) field.setText(uploaded[1]);
            SalonStore.put(this, key, uploaded[1]); render();
        } else promptMediaTitle(uploaded[0], uploaded[1]);
        mediaStatus(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_uploaded));
    }

    private CharSequence mediaError(Exception error) {
        if (error instanceof GithubUploader.ProfileFailure) {
            GithubUploader.ProfileFailure failure = (GithubUploader.ProfileFailure) error;
            switch (failure.reason) {
                case CONFIGURATION: return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_configuration);
                case READ: return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_read, failure.status);
                case INVALID: return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_invalid);
                case CONFLICT: return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_conflict, failure.status);
                case WRITE: return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_write, failure.status);
                case UNAVAILABLE: return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_unavailable);
            }
        }
        String detail = error.getMessage();
        if ("cannot read file".equals(detail) || "could not read image".equals(detail)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_read_failed);
        if ("file too large (max 60 MB)".equals(detail)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_size, 60);
        if ("file too large (max 25 MB)".equals(detail)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_size, 25);
        if ("empty upload reference".equals(detail)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_no_reference);
        return detail == null || detail.isEmpty() ? error.getClass().getSimpleName() : HoldingMessages.message(detail);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req >= PICK_AVATAR && req <= PICK_DM_IMG) { receiveMedia(req, res, data); return; }
        if (req == SAVE_TEXT || req == OPEN_BACKUP || (req >= 1000 && req <= 65535)) receiveBackupFile(req, res, data);
    }

    private void promptMediaTitle(String kind, String url) {
        final java.util.function.BooleanSupplier current = editorDraftCurrent("gallery");
        LinearLayout box = dialogBox();
        CharSequence label = kind.equals("audio") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_track_title) : kind.equals("video") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_video_title) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_photo_caption);
        EditText t = field(box, label, "", false, kind.equals("audio") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_song_hint) : "");
        new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_add_gallery)).setView(box).setCancelable(false)
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_add), (d, w) -> { if (current.getAsBoolean()) addGalleryItem(kind, url, text(t)); })
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_skip_title), (d, w) -> { if (current.getAsBoolean()) addGalleryItem(kind, url, ""); }).show();
    }

    private void addGalleryItem(String kind, String url, String caption) {
        JSONArray a = SalonStore.arr(this, "gallery");
        try { JSONObject o = new JSONObject(); o.put("type", kind); o.put("url", url); o.put("caption", caption == null ? "" : caption.trim()); a.put(o); } catch (Exception ignored) {}
        SalonStore.setArr(this, "gallery", a); render();
    }

    private void renameGalleryItem(int idx) {
        final java.util.function.BooleanSupplier current = editorDraftCurrent("gallery");
        JSONArray a = SalonStore.arr(this, "gallery");
        JSONObject m = a.optJSONObject(idx); if (m == null) return;
        LinearLayout box = dialogBox();
        EditText t = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_caption), m.optString("caption", ""), false, "");
        showDialog(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_chat_rename), box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_save), () -> { if (!current.getAsBoolean()) return; try { m.put("caption", text(t).trim()); } catch (Exception ignored) {} SalonStore.setArr(this, "gallery", a); render(); });
    }

    /** A full-width media card: a labelled header (KIND + title) over the media
     *  itself — image (tap to zoom), video (tap to play fullscreen), or an audio
     *  row with its own player modal. */
    private View mediaCard(JSONObject m) {
        String type = m.optString("type", "image"), url = m.optString("url"), cap = m.optString("caption", "");
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));

        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL); head.setPadding(dp(11), dp(9), dp(11), dp(8));
        CharSequence kindLabel = type.equals("audio") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_music) : type.equals("video") ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_video) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_chat_photo);
        TextView kt = Design.text(this, kindLabel, 8.5f, Design.ACCENT(), Design.sansBold()); kt.setLetterSpacing(0.14f);
        head.addView(kt);
        if (!cap.isEmpty()) head.addView(Design.text(this, cap, 15, Design.INK(), Design.sansBold()));
        col.addView(head);

        if ("image".equals(type)) {
            ImageView iv = new ImageView(this); iv.setAdjustViewBounds(true); iv.setMaxHeight(dp(360)); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF141310);
            if (!url.isEmpty()) ImageLoader.loadOver(this, url, iv);   // list preview — tap opens full-res
            iv.setClickable(true); iv.setOnClickListener(v -> openImage(url));
            col.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        } else if ("video".equals(type)) {
            FrameLayout f = new FrameLayout(this); f.setBackgroundColor(0xFF141310);
            TextView play = Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_tap_play), 13, 0xFFF2F1EC, Design.sansBold()); play.setGravity(Gravity.CENTER);
            f.addView(play, new FrameLayout.LayoutParams(-1, -1));
            f.setClickable(true); f.setOnClickListener(v -> openVideo(url));
            col.addView(f, new LinearLayout.LayoutParams(-1, dp(190)));
        } else { // audio
            LinearLayout ar = row(); ar.setPadding(dp(11), dp(2), dp(11), dp(12));
            TextView note = Design.text(this, "♪", 26, Design.INK(), Design.sansBold());
            ar.addView(note, new LinearLayout.LayoutParams(dp(38), -2));
            ar.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_play_action), true, () -> playTrack(url, cap.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_untitled) : cap)), new LinearLayout.LayoutParams(0, -2, 1));
            col.addView(ar);
        }
        return col;
    }

    private void openImage(String url) {
        if (url == null || url.isEmpty()) return;
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this); iv.setBackgroundColor(0xFF000000); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageLoader.loadFull(this, url, iv); iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv); d.show();
    }

    private long videoRequest;
    private Dialog videoDialog;

    private void openVideo(String url) {
        if (url == null || url.isEmpty() || isFinishing() || isDestroyed()) return;
        dismissVideo();
        final long request = ++videoRequest;
        if (RelayResolver.isMediaRef(url)) {                 // decrypt to a cache file first
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_decrypt_video));
            io.execute(() -> { try { java.io.File f = RelayResolver.resolveToTempFile(this, url);
                runOnUiThread(() -> { if (request == videoRequest && !isFinishing() && !isDestroyed()) playVideoUri(Uri.fromFile(f)); }); }
                catch (Exception e) { runOnUiThread(() -> { if (request == videoRequest && !isFinishing() && !isDestroyed()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_video_failed, e.getMessage())); }); } });
            return;
        }
        playVideoUri(Uri.parse(url));
    }

    private void dismissVideo() {
        // onDismiss is queued: detach the old owner before choosing the next request.
        Dialog previous = videoDialog; videoDialog = null;
        if (previous != null) previous.dismiss();
    }

    private void playVideoUri(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        dismissVideo();
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        VideoView vv = new VideoView(this);
        MediaController mc = new MediaController(this); mc.setAnchorView(vv);
        vv.setMediaController(mc); vv.setVideoURI(uri);
        FrameLayout fl = new FrameLayout(this); fl.setBackgroundColor(0xFF000000);
        fl.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoDialog = d; d.setContentView(fl); d.show();
        vv.setOnPreparedListener(mp -> { if (videoDialog != d || !d.isShowing() || isFinishing() || isDestroyed()) return; mp.setLooping(false); vv.start(); mc.show(0); });
        d.setOnDismissListener(x -> { vv.stopPlayback(); if (videoDialog == d) { videoDialog = null; videoRequest++; } });   // release the player so audio doesn't keep running after Back
    }

    /* ---------- gallery as a collection: photo grid, video carousel, playlist ---------- */

    /** Renders the whole gallery as Instagram-style collections — photos in a
     *  captioned grid (tap → swipeable carousel), videos in a horizontal
     *  carousel, songs as a playlist that plays in the persistent mini-player. */
    private void renderGallery(LinearLayout c, JSONArray gal) {
        List<JSONObject> photos = new ArrayList<>(), videos = new ArrayList<>(), tracks = new ArrayList<>();
        for (int i = 0; i < gal.length(); i++) {
            JSONObject m = gal.optJSONObject(i); if (m == null) continue;
            String t = m.optString("type", "image");
            if ("audio".equals(t)) tracks.add(m); else if ("video".equals(t)) videos.add(m); else photos.add(m);
        }
        if (!photos.isEmpty()) { c.addView(subLabel(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_photos, photos.size())), lp(0, 10, 0, 6)); c.addView(photoGrid(photos)); }
        if (!videos.isEmpty()) { c.addView(subLabel(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_videos, videos.size())), lp(0, 16, 0, 6)); c.addView(videoCarousel(videos)); }
        if (!tracks.isEmpty()) { c.addView(subLabel(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_tracks, tracks.size())), lp(0, 16, 0, 6)); c.addView(trackList(tracks)); }
    }

    private TextView subLabel(CharSequence s) { TextView t = Design.text(this, Language.upper(s), 9f, Design.DIM(), Design.sansBold()); t.setLetterSpacing(0.14f); return t; }

    private LinearLayout.LayoutParams cellLp(int col) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); if (col == 0) p.rightMargin = dp(4); else p.leftMargin = dp(4); return p; }

    private View photoGrid(final List<JSONObject> photos) {
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = null;
        for (int i = 0; i < photos.size(); i++) {
            final int idx = i; JSONObject m = photos.get(i);
            if (i % 2 == 0) { r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); grid.addView(r, lp(0, 0, 0, 8)); }
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF141310);
            if (!m.optString("url").isEmpty()) ImageLoader.loadOver(this, m.optString("url"), iv);
            iv.setClickable(true); iv.setOnClickListener(v -> openCarousel(photos, idx));
            cell.addView(iv, new LinearLayout.LayoutParams(-1, dp(150)));
            String cap = m.optString("caption", "");
            if (!cap.isEmpty()) { TextView ct = Design.text(this, cap, 11.5f, Design.DIM(), Design.sans()); ct.setPadding(dp(1), dp(4), 0, 0); ct.setMaxLines(2); cell.addView(ct); }
            r.addView(cell, cellLp(i % 2));
        }
        if (photos.size() % 2 == 1 && r != null) r.addView(new View(this), cellLp(1));
        return grid;
    }

    private View videoCarousel(final List<JSONObject> videos) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout rowv = new LinearLayout(this); rowv.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < videos.size(); i++) {
            final JSONObject m = videos.get(i);
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            FrameLayout f = new FrameLayout(this); f.setBackground(Design.ruled(this, 0xFF141310, Design.INK(), 1));
            TextView play = Design.text(this, "▶", 30, 0xFFF2F1EC, Design.sansBold()); play.setGravity(Gravity.CENTER);
            f.addView(play, new FrameLayout.LayoutParams(-1, -1));
            f.setClickable(true); f.setOnClickListener(v -> openVideo(m.optString("url")));
            cell.addView(f, new LinearLayout.LayoutParams(dp(220), dp(140)));
            String cap = m.optString("caption", "");
            if (!cap.isEmpty()) { TextView ct = Design.text(this, cap, 11.5f, Design.DIM(), Design.sans()); ct.setPadding(dp(2), dp(4), 0, 0); ct.setMaxLines(2); cell.addView(ct); }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.rightMargin = dp(8); rowv.addView(cell, clp);
        }
        hs.addView(rowv); return hs;
    }

    private View trackList(final List<JSONObject> tracks) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
        for (int i = 0; i < tracks.size(); i++) {
            JSONObject m = tracks.get(i); final String url = m.optString("url");
            final CharSequence title = m.optString("caption", "").isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_untitled) : m.optString("caption");
            LinearLayout r = row(); r.setPadding(dp(11), dp(11), dp(10), dp(11)); r.setClickable(true); Design.pressable(r);
            r.setOnClickListener(v -> playTrack(url, title));
            r.addView(Design.text(this, String.valueOf(i + 1), 12, Design.DIM(), Design.mono()), new LinearLayout.LayoutParams(dp(24), -2));
            TextView tt = Design.text(this, title, 14, Design.INK(), Design.sansBold()); tt.setMaxLines(1); tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Design.text(this, "▶", 15, Design.ACCENT(), Design.sansBold()));
            col.addView(r);
            if (i < tracks.size() - 1) col.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
        }
        return col;
    }

    /** Fullscreen swipeable carousel over a list of image items (‹ › + fling), caption underneath. */
    private void openCarousel(final List<JSONObject> items, int start) {
        if (items.isEmpty()) return;
        final int[] idx = { Math.max(0, Math.min(start, items.size() - 1)) };
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        final ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(16), dp(26), dp(16), dp(10));
        final TextView counter = Design.text(this, "", 13, 0xFFFFFFFF, Design.mono()); top.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = Design.text(this, "✕", 20, 0xFFFFFFFF, Design.sansBold()); close.setPadding(dp(10), dp(4), dp(10), dp(4)); close.setOnClickListener(v -> d.dismiss()); top.addView(close);
        Language.description(close, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_language_close));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        final TextView caption = Design.text(this, "", 14, 0xFFFFFFFF, Design.sans()); caption.setPadding(dp(16), dp(12), dp(16), dp(30)); caption.setBackgroundColor(0x99000000);
        root.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        final TextView prev = navArrow("‹"), next = navArrow("›");
        Language.description(prev, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_previous));
        Language.description(next, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_next));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL); plp.leftMargin = dp(4); root.addView(prev, plp);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL); nlp.rightMargin = dp(4); root.addView(next, nlp);

        final Runnable[] bind = new Runnable[1];
        bind[0] = () -> {
            JSONObject m = items.get(idx[0]); Language.setText(counter, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_position, idx[0] + 1, items.size()));
            String cap = m.optString("caption", ""); caption.setText(cap); caption.setVisibility(cap.isEmpty() ? View.GONE : View.VISIBLE);
            ImageLoader.loadFull(this, m.optString("url"), img);
            prev.setVisibility(idx[0] > 0 ? View.VISIBLE : View.INVISIBLE); next.setVisibility(idx[0] < items.size() - 1 ? View.VISIBLE : View.INVISIBLE);
        };
        prev.setOnClickListener(v -> { if (idx[0] > 0) { idx[0]--; bind[0].run(); } });
        next.setOnClickListener(v -> { if (idx[0] < items.size() - 1) { idx[0]++; bind[0].run(); } });

        final android.view.GestureDetector gd = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false; float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > dp(56) && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                    if (dx < 0) { if (idx[0] < items.size() - 1) { idx[0]++; bind[0].run(); } } else { if (idx[0] > 0) { idx[0]--; bind[0].run(); } }
                    return true;
                }
                return false;
            }
        });
        img.setClickable(true); img.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        bind[0].run();
        d.setContentView(root); d.show();
    }

    private TextView navArrow(String s) { TextView t = Design.text(this, s, 34, 0xFFFFFFFF, Design.sansBold()); t.setPadding(dp(14), dp(10), dp(14), dp(10)); t.setClickable(true); return t; }

    /* ---------- persistent audio: a mini-player docked above the nav ---------- */

    private void buildMiniPlayer() {
        miniPlayer = new LinearLayout(this); miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setBackgroundColor(Design.CARD()); miniPlayer.setVisibility(View.GONE);
        miniPlayer.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));

        miniBar = new SeekBar(this); miniBar.setMax(1000);
        miniBar.getProgressDrawable().setColorFilter(Design.ACCENT(), android.graphics.PorterDuff.Mode.SRC_IN);
        miniBar.getThumb().setColorFilter(Design.ACCENT(), android.graphics.PorterDuff.Mode.SRC_IN);
        miniBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser && audioDur() > 0) { try { audio.seekTo((int) (audioDur() * (p / 1000f))); } catch (Exception ignored) {} } }
            public void onStartTrackingTouch(SeekBar s) { miniSeeking = true; }
            public void onStopTrackingTouch(SeekBar s) { miniSeeking = false; }
        });
        Language.description(miniBar, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_seek));
        miniPlayer.addView(miniBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(12), dp(2), dp(6), dp(6));
        r.addView(Design.text(this, "♪", 18, Design.ACCENT(), Design.sansBold()), new LinearLayout.LayoutParams(dp(22), -2));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(7), 0, dp(6), 0);
        miniTitle = Design.text(this, "", 13, Design.INK(), Design.sansBold()); miniTitle.setMaxLines(1); miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniTime = Design.text(this, "0:00", 10, Design.DIM(), Design.mono());
        col.addView(miniTitle); col.addView(miniTime);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(miniIcon("⏪", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_back_ten), () -> seekRel(-10000)));
        miniPlay = miniIcon("❚❚", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_logs_pause), this::togglePlay); r.addView(miniPlay);
        r.addView(miniIcon("⏩", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_forward_ten), () -> seekRel(10000)));
        r.addView(miniIcon("✕", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_stop), this::stopAudio));
        miniPlayer.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView miniIcon(String s, CharSequence description, Runnable click) {
        TextView t = Design.text(this, s, 16, Design.INK(), Design.sansBold()); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6)); t.setClickable(true); Design.pressable(t);
        Language.description(t, description); t.setMinWidth(dp(48)); t.setMinHeight(dp(48));
        t.setOnClickListener(v -> click.run()); return t;
    }

    private int audioDur() { try { return audio == null ? 0 : audio.getDuration(); } catch (Exception e) { return 0; } }
    private void seekRel(int ms) { if (audio == null) return; try { int d = audioDur(); int np = audio.getCurrentPosition() + ms; if (np < 0) np = 0; if (d > 0 && np > d) np = d; audio.seekTo(np); } catch (Exception ignored) {} }
    private void togglePlay() { if (audio == null) return; try { if (audio.isPlaying()) audio.pause(); else audio.start(); updateMiniPlay(); } catch (Exception ignored) {} }
    private void updateMiniPlay() { if (miniPlay == null) return; boolean playing = false; try { playing = audio != null && audio.isPlaying(); } catch (Exception ignored) {} miniPlay.setText(playing ? "❚❚" : "▶"); Language.description(miniPlay, playing ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_logs_pause) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_play)); }

    /** Start (or switch to) a track; it plays on regardless of screen changes,
     *  controlled from the docked mini-player until you hit ✕. */
    private long audioRequest;

    private void playTrack(String url, CharSequence title) {
        if (url == null || url.isEmpty()) return;
        if (isFinishing() || isDestroyed()) return;
        stopAudio();
        final long request = audioRequest;
        if (RelayResolver.isMediaRef(url)) {                 // decrypt to a cache file first
            Language.setText(miniTitle, title); Language.setText(miniTime, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_decrypting)); miniBar.setProgress(0);
            miniPlayer.setVisibility(View.VISIBLE);
            io.execute(() -> { try { java.io.File f = RelayResolver.resolveToTempFile(this, url);
                runOnUiThread(() -> { if (request == audioRequest && !isFinishing() && !isDestroyed()) playTrackSource(f.getAbsolutePath(), title); }); }
                catch (Exception e) { runOnUiThread(() -> { if (request != audioRequest || isFinishing() || isDestroyed()) return; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_track_failed, e.getMessage())); stopAudio(); }); } });
            return;
        }
        playTrackSource(url, title);
    }

    private void playTrackSource(String source, CharSequence title) {
        if (isFinishing() || isDestroyed()) return;
        stopAudio();
        final MediaPlayer mp = new MediaPlayer(); audio = mp;
        Language.setText(miniTitle, title); miniTime.setText("0:00"); miniBar.setProgress(0);
        miniPlayer.setVisibility(View.VISIBLE); updateMiniPlay();
        try {
            mp.setDataSource(source);
            mp.setOnPreparedListener(x -> { if (audio != mp || isFinishing() || isDestroyed()) return; mp.start(); updateMiniPlay(); startTicker(); });
            mp.setOnCompletionListener(x -> { if (audio != mp || isFinishing() || isDestroyed()) return; miniBar.setProgress(1000); updateMiniPlay(); });
            mp.setOnErrorListener((m, w, e) -> { if (audio != mp || isFinishing() || isDestroyed()) return true; toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_playback_error)); stopAudio(); return true; });
            mp.prepareAsync();
        } catch (Exception e) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_media_play_failed, e.getMessage())); stopAudio(); }
    }

    private void startTicker() {
        playback.removeCallbacksAndMessages(null);
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try { int d = audioDur(); if (audio != null && d > 0) { if (!miniSeeking) miniBar.setProgress((int) (1000L * audio.getCurrentPosition() / d)); miniTime.setText(fmtTime(audio.getCurrentPosition()) + " / " + fmtTime(d)); } } catch (Exception ignored) {}
            playback.postDelayed(tick[0], 500);
        };
        playback.post(tick[0]);
    }

    private String fmtTime(int ms) { int s = ms / 1000; return s / 60 + ":" + String.format("%02d", s % 60); }

    private void stopAudio() {
        audioRequest++;
        miniSeeking = false;
        playback.removeCallbacksAndMessages(null);
        if (audio != null) { try { audio.release(); } catch (Exception ignored) {} audio = null; }
        if (miniPlayer != null) miniPlayer.setVisibility(View.GONE);
    }

    /** A real avatar (round-cornered photo) or, when none is set, a clean
     *  letter monogram — no auto-generated identicon. */
    private View avatarView(String url, String label, int sizeDp) {
        if (url != null && !url.isEmpty()) {
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
            ImageLoader.loadOver(this, url, iv);   // thumbnail size — avatars are small, don't decode 1800px
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
            return iv;
        }
        FrameLayout f = new FrameLayout(this); f.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 2));
        String ltr = (label == null || label.trim().isEmpty()) ? "·" : label.trim().substring(0, 1).toUpperCase();
        TextView t = Design.text(this, ltr, sizeDp * 0.44f, Design.ACCENT(), Design.sansBold()); t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(-1, -1));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    private void addPostMedia(LinearLayout parent, JSONObject post) {
        String type = post.optString("type", post.optString("mtype", "")), media = post.optString("media", "");
        if (media.isEmpty()) return;
        if (type.isEmpty()) type = "image";
        JSONObject m = new JSONObject(); try { m.put("type", type); m.put("url", media); m.put("caption", ""); } catch (Exception ignored) {}
        parent.addView(mediaCard(m), lp(0, 6, 0, 2));
    }

    /* ================= ONBOARD / claim (unchanged core) ================= */

    private void renderOnboard() {
        LinearLayout intro = card();
        intro.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_open)));
        intro.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_intro)), lp(0, 6, 0, 0));
        body.addView(intro, lp(0, 0, 0, 12));

        Hosting.Profile def = HostingStore.getDefault(this);
        LinearLayout hostCard = card(); hostCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_hosting)));
        addKvPlain(hostCard, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_destination), def == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_no_destination) : def.name() + " · " + def.type());
        hostCard.addView(btn(def == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_setup_hosting) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_manage_hosting), def == null, () -> go(Screen.HOSTING)), lph(46, 0, 8, 0, 0));
        if (def == null) {
            // RETIRED (MaxLite sunset) — the one-tap encrypted-relay shortcut is gone. To restore:
            // hostCard.addView(Design.note(this, "No server? Publish to the encrypted relay — zero setup, your page is stored end-to-end encrypted."), lp(0, 8, 0, 4));
            // hostCard.addView(btn("Publish with no server (relay)", false, this::quickStartRelay), lph(44, 0, 4, 0, 0));
            hostCard.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_blossom_help)), lp(0, 8, 0, 4));
            hostCard.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_blossom), false, this::quickStartBlossom), lph(44, 0, 4, 0, 0));
        }
        body.addView(hostCard, lp(0, 0, 0, 12));

        LinearLayout form = card(); form.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_claim_handle)));
        EditText handle = field(form, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_handle), SalonStore.get(this, "handle"), false, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_handle_example));
        EditText name = field(form, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_display_name), SalonStore.get(this, "name"), false, "Euro Buddha");
        EditText bio = field(form, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_bio), SalonStore.get(this, "bio"), false, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_bio_hint));
        final TextView status = Design.note(this, ""); form.addView(status, lp(0, 8, 0, 0));
        claimBtn = btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_claim), true, () -> claimIdentity(text(handle), text(name), text(bio), status));
        claimHandleField=handle; claimNameField=name; claimBioField=bio; setClaimEnabled(!claiming);
        form.addView(claimBtn, lph(52, 0, 10, 0, 0));
        if (SalonStore.me(this).has("claimPending")) {
            claimRetryButton=btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_retry_title),false,this::confirmClaimRetry);
            form.addView(claimRetryButton,lph(48,0,8,0,0));
        }
        body.addView(form, lp(0, 0, 0, 12));
    }

    /** Setup follows the same captured-owner lifetime as profile publishing. */
    private static final class IdentityClaim {
        String snapshot, url;
        final String handle, name, bio, signer, html;
        final Hosting.Profile host;
        final JSONObject profile;
        final int epoch;
        final NodeApi node;
        final TextView status;
        volatile boolean cancelled;
        int request;
        Runnable poll;
        IdentityClaim(String snapshot, String handle, String name, String bio, String signer,
                Hosting.Profile host, JSONObject profile, String html, int epoch, NodeApi node, TextView status) {
            this.snapshot=snapshot; this.handle=handle; this.name=name; this.bio=bio; this.signer=signer;
            this.host=host; this.profile=profile; this.html=html; this.epoch=epoch; this.node=node; this.status=status;
        }
    }
    private EditText claimHandleField, claimNameField, claimBioField;
    private TextView claimRetryButton;
    private androidx.appcompat.app.AlertDialog claimRetryDialog;
    private IdentityClaim identityClaim;
    private Object identityAdoption;
    private final android.os.Handler claimTimer = new android.os.Handler(android.os.Looper.getMainLooper());

    private void cancelIdentityClaim() {
        if(claimRetryDialog!=null){androidx.appcompat.app.AlertDialog dialog=claimRetryDialog;claimRetryDialog=null;dialog.dismiss();}
        IdentityClaim op=identityClaim;
        identityClaim=null; identityAdoption=null; claiming=false;
        if(op!=null) { op.cancelled=true; if(op.poll!=null) claimTimer.removeCallbacks(op.poll); }
        setClaimEnabled(true);
    }
    private boolean claimStoredCurrent(IdentityClaim op) {
        if(op.cancelled) return false;
        try {
            Hosting.Profile host=HostingStore.getDefault(this);
            return op.snapshot.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)))
                    && host!=null && op.host.j.toString().equals(host.j.toString());
        } catch(Exception unavailable) { return false; }
    }
    private boolean claimCurrent(IdentityClaim op) {
        return identityClaim==op && !isFinishing() && !isDestroyed() && op.epoch==renderEpoch
                && nodeUp && node==op.node && pubkey.equals(op.signer) && claimStoredCurrent(op);
    }
    private boolean requireClaim(IdentityClaim op) {
        if(claimCurrent(op)) return true;
        if(identityClaim==op) endClaim(op, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_changed));
        return false;
    }
    private void endClaim(IdentityClaim op, CharSequence message) {
        if(identityClaim!=op) return;
        cancelIdentityClaim();
        if(!isFinishing() && !isDestroyed() && op.epoch==renderEpoch) publicationStatus(op.status,message);
    }
    private boolean saveClaim(IdentityClaim op, JSONObject changes) {
        synchronized(SalonStore.class) {
            if(!requireClaim(op)) return false;
            if(!SalonStore.saveClaim(this,op.snapshot,changes)) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return false; }
            op.snapshot=SalonStore.publicationSnapshot(SalonStore.identityRecord(this));
            return true;
        }
    }
    private void claimIdentity(String handle,String name,String bio,TextView status) {
        if(claiming) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_claiming)); return; }
        handle=Hosting.slug(handle);
        if(handle.isEmpty() || name.trim().isEmpty()) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_required)); return; }
        if(!Args.isSafeMeta(name) || !Args.isSafeMeta(bio)) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_safe_metadata)); return; }
        Hosting.Profile host=HostingStore.getDefault(this);
        if(host==null) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_hosting_first)); return; }
        if(!nodeUp || node==null) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_waiting_node)); return; }
        if(!Args.isTokenId(pubkey)) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_fetching_key)); fetchPubkey(); return; }
        final IdentityClaim op;
        final JSONObject pending;
        try {
            JSONObject current=SalonStore.identityRecord(this);
            if(!current.optString("tokenid").isEmpty()) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_changed)); return; }
            if(current.has("claimPending") && !(current.opt("claimPending") instanceof JSONObject)) {
                publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return;
            }
            pending=current.optJSONObject("claimPending");
            // A prior submission can still confirm after a timeout/restart. Resume reads only.
            if(pending!=null && (!handle.equals(pending.optString("handle"))
                    || !pubkey.equals(pending.optString("signer")) || pending.optString("url").isEmpty())) {
                publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_pending)); return;
            }
            JSONObject profile=pending==null?buildProfileJson():new JSONObject();
            profile.put("handle",handle).put("name",name.trim()).put("bio",bio.trim());
            op=new IdentityClaim(SalonStore.publicationSnapshot(current),handle,name.trim(),bio.trim(),pubkey,
                    new Hosting.Profile(new JSONObject(host.j.toString())),profile,pending==null?salonHtml():"",renderEpoch,node,status);
        } catch(Exception unavailable) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
        identityAdoption=null; identityClaim=op; claiming=true; setClaimEnabled(false);
        if(pending!=null) {
            op.url=pending.optString("url"); publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_confirming)); pollForIdentity(op,0); return;
        }
        JSONObject draft=new JSONObject();putJson(draft,"handle",op.handle);putJson(draft,"name",op.name);putJson(draft,"bio",op.bio);
        if(!saveClaim(op,draft)) return;
        publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_publishing));
        try {
            io.execute(()->{
                if(!claimStoredCurrent(op)) { runOnUiThread(()->endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_changed))); return; }
                try {
                    String url=ProfileHosting.upload(op.host,op.handle,op.profile.toString(),op.html);
                    runOnUiThread(()->{
                        if(!requireClaim(op)) return;
                        if(url==null || url.isEmpty() || !Args.isSafeMeta(url)) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
                        JSONObject change=new JSONObject();putJson(change,"profileUrl",url);
                        if(!saveClaim(op,change)) return;
                        op.url=url;publicationStatus(op.status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_checking));adoptOrMint(op);
                    });
                } catch(SftpUploader.HostKeyUnverified untrusted) {
                    runOnUiThread(()->{synchronized(SalonStore.class){if(requireClaim(op)){endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage));promptTrustHostKey(op.host,untrusted.fingerprint,op.status);}}});
                } catch(Exception unavailable) { runOnUiThread(()->endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_failed, mediaError(unavailable)))); }
            });
        } catch(java.util.concurrent.RejectedExecutionException unavailable) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); }
    }
    private void adoptOrMint(IdentityClaim op) {
        if(!requireClaim(op)) return;
        final int request=++op.request;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return; op.request++;
                try {
                    JSONObject existing=IdentityBalance.find(reply,op.handle);
                    if(existing!=null) finishClaim(op,existing.optString("tokenid"));
                    else mintIdentity(op);
                } catch(IllegalArgumentException unavailable) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_identity_lookup_failed)); }
            }); }
            public void onError(String ignored) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_identity_lookup_failed));
            }); }
        };
        try { op.node.cmd("balance",result); } catch(RuntimeException unavailable) { result.onError(""); }
    }
    private void mintIdentity(IdentityClaim op) {
        if(!requireClaim(op)) return;
        JSONObject pending=new JSONObject(),changes=new JSONObject(),meta=new JSONObject();
        try {
            pending.put("handle",op.handle).put("url",op.url).put("signer",op.signer);
            changes.put("claimPending",pending);
            meta.put("salon","1").put("handle",op.handle).put("name",op.name).put("url",op.url).put("bio",op.bio);
        } catch(Exception unavailable) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
        // Reuse Atelier's save-before-post rule. An unconfirmed attempt is never auto-reposted.
        if(!saveClaim(op,changes)) return;
        publicationStatus(op.status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_identity_minting));
        final int request=++op.request;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;
                if(reply!=null && Boolean.FALSE.equals(reply.opt("status"))) {
                    JSONObject clear=new JSONObject();try{clear.put("claimPending",JSONObject.NULL);}catch(Exception ignored){}
                    if(saveClaim(op,clear)) endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_rejected));
                } else if(reply!=null && Boolean.TRUE.equals(reply.opt("status"))) {
                    publicationStatus(op.status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_confirming));pollForIdentity(op,0);
                } else endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_pending));
            }); }
            public void onError(String ignored) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_pending));
            }); }
        };
        try {
            synchronized(SalonStore.class) {
                if(!requireClaim(op)) return;
                op.node.cmd("tokencreate name:"+meta+" amount:1 decimals:0 signtoken:"+op.signer,result);
            }
        }
        catch(RuntimeException unavailable) { result.onError(""); }
    }
    private void pollForIdentity(IdentityClaim op,int tries) {
        if(!requireClaim(op)) return;
        final int request=++op.request;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;
                try {
                    JSONObject row=IdentityBalance.find(reply,op.handle);
                    if(row!=null) { finishClaim(op,row.optString("tokenid")); return; }
                } catch(IllegalArgumentException unavailable) { /* Unknown balance is never permission to mint. */ }
                nextClaimPoll(op,tries);
            }); }
            public void onError(String ignored) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;nextClaimPoll(op,tries);
            }); }
        };
        try { op.node.cmd("balance",result); } catch(RuntimeException unavailable) { result.onError(""); }
    }
    private void nextClaimPoll(IdentityClaim op,int tries) {
        if(tries>=30) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_pending)); return; }
        op.poll=()->pollForIdentity(op,tries+1);claimTimer.postDelayed(op.poll,4000);
    }
    private void finishClaim(IdentityClaim op,String tokenid) {
        if(!Args.isTokenId(tokenid) || new java.math.BigInteger(tokenid.substring(2),16).signum()==0) { endClaim(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_identity_lookup_failed)); return; }
        JSONObject change=new JSONObject();putJson(change,"tokenid",tokenid);
        try{change.put("claimPending",JSONObject.NULL);}catch(Exception ignored){}
        if(!saveClaim(op,change)) return;
        cancelIdentityClaim();publishSalon(null);toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_opened));go(Screen.HOME);
    }
    private void setClaimEnabled(boolean on) {
        if(claimBtn!=null){claimBtn.setEnabled(on);claimBtn.setAlpha(on?1f:0.4f);}
        for(EditText field:new EditText[]{claimHandleField,claimNameField,claimBioField}) if(field!=null) field.setEnabled(on);
    }

    /** Explicit permission for another attempt; this dialog never sends a command. */
    private void confirmClaimRetry() {
        if(claiming || claimRetryDialog!=null || !nodeUp || isFinishing() || isDestroyed()) return;
        final String snapshot;
        try {
            JSONObject current=SalonStore.identityRecord(this);
            if(!(current.opt("claimPending") instanceof JSONObject)) return;
            snapshot=SalonStore.publicationSnapshot(current);
        } catch(Exception unavailable) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
        final int epoch=renderEpoch;final NodeApi requestedNode=node;
        claimRetryDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_retry_title)).setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_retry_warning))
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel),null)
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_retry_title),(dialog,which)->{
                    if(claimRetryDialog!=dialog) return;claimRetryDialog=null;
                    if(claiming || isFinishing() || isDestroyed() || epoch!=renderEpoch || node!=requestedNode || !nodeUp) return;
                    try {
                        if(!snapshot.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)))) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_changed)); return; }
                        JSONObject clear=new JSONObject().put("claimPending",JSONObject.NULL);
                        if(!SalonStore.saveClaim(this,snapshot,clear)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
                        if(claimRetryButton!=null) claimRetryButton.setVisibility(View.GONE);
                        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_retry_ready));
                    } catch(Exception unavailable) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); }
                }).create();
        claimRetryDialog.setOnDismissListener(dialog->{if(claimRetryDialog==dialog)claimRetryDialog=null;});
        claimRetryDialog.show();
    }

    /** Automatic adoption may finish only for the empty account that requested the balance. */
    private void checkIdentityAdoption() {
        if(identityAdoption!=null || claiming || SalonStore.hasIdentity(this)) return;
        final String snapshot;
        try { snapshot=SalonStore.publicationSnapshot(SalonStore.identityRecord(this)); }
        catch(Exception unavailable) { return; }
        final Object request=new Object();identityAdoption=request;
        final NodeApi requestedNode=node;final int epoch=renderEpoch;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(identityAdoption!=request) return; identityAdoption=null;
                if(isFinishing() || isDestroyed() || epoch!=renderEpoch || node!=requestedNode || !nodeUp || claiming) return;
                try {
                    JSONObject current=SalonStore.identityRecord(MainActivity.this);
                    if(!snapshot.equals(SalonStore.publicationSnapshot(current))) return;
                    JSONObject pending=current.optJSONObject("claimPending");
                    JSONObject row=IdentityBalance.find(reply,pending==null?null:pending.optString("handle"));
                    if(row==null) return;
                    String tokenid=row.optString("tokenid");
                    if(new java.math.BigInteger(tokenid.substring(2),16).signum()==0) return;
                    JSONObject meta=row.getJSONObject("token"),change=new JSONObject();
                    change.put("tokenid",tokenid).put("handle",meta.getString("handle")).put("profileUrl",meta.optString("url"));
                    for(String key:new String[]{"name","bio"}) if(current.optString(key).isEmpty()) change.put(key,meta.optString(key));
                    change.put("claimPending",JSONObject.NULL);
                    if(!SalonStore.saveClaim(MainActivity.this,snapshot,change)) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); return; }
                    if(screen==Screen.HOME || screen==Screen.ONBOARD || screen==Screen.FEED) go(Screen.HOME);
                    hydrateFromHosted(meta.optString("url"),false);
                } catch(Exception unavailable) { /* A failed lookup cannot adopt or mint. */ }
            }); }
            public void onError(String ignored) { runOnUiThread(()->{if(identityAdoption==request) identityAdoption=null;}); }
        };
        try { requestedNode.cmd("balance",result); } catch(RuntimeException unavailable) { result.onError(""); }
    }

    private static final class HostedRestore {
        final String snapshot, typed, url;
        final int epoch;
        final boolean loud;
        HostedRestore(String snapshot, String typed, String url, int epoch, boolean loud) {
            this.snapshot = snapshot; this.typed = typed; this.url = url; this.epoch = epoch; this.loud = loud;
        }
    }
    private HostedRestore hostedRestore;

    private void cancelHostedRestore() { hostedRestore = null; }

    private boolean hostedRestoreCurrent(HostedRestore op) {
        if (isFinishing() || isDestroyed() || op.epoch != renderEpoch || !op.typed.equals(profileTyped())) return false;
        try { return op.snapshot.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this))); }
        catch (Exception unavailable) { return false; }
    }

    /** Hosted content never replaces identity/key fields or a newer local draft. */
    private void hydrateFromHosted(final String url, final boolean loud) {
        if (url == null || url.trim().isEmpty()) { if (loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_no_hosted_url)); return; }
        if (isFinishing() || isDestroyed()) return;
        if (hostedRestore != null) { if (loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_busy)); return; }
        final HostedRestore op;
        try {
            JSONObject record = SalonStore.identityRecord(this);
            if (!url.equals(record.optString("profileUrl"))) { if (loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_changed)); return; }
            op = new HostedRestore(SalonStore.publicationSnapshot(record), profileTyped(), url, renderEpoch, loud);
        } catch (Exception unavailable) { if (loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_storage)); return; }
        hostedRestore = op;
        if (loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_restoring));
        try {
            io.execute(() -> {
                // Force a fresh read through the existing checked HTTP/mesh transport.
                final JSONObject profile = httpGetJson(op.url, true);
                runOnUiThread(() -> finishHostedRestore(op, profile));
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            hostedRestore = null;
            if (loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_unavailable));
        }
    }

    private void finishHostedRestore(HostedRestore op, JSONObject profile) {
        if (hostedRestore != op) return;
        hostedRestore = null;
        if (isFinishing() || isDestroyed()) return;
        if (!hostedRestoreCurrent(op)) { if (op.loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_changed)); return; }
        if (profile == null) { if (op.loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_unreachable)); return; }
        SalonStore.RestoreResult result = SalonStore.restoreHosted(this, op.snapshot, profile);
        switch (result) {
            case APPLIED:
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_restored));
                // render() commits retained editor widgets even when returning from Settings.
                // Drop the old references so opening Edit cannot undo the durable restore.
                edName = edBio = edAbout = edAvatar = edBanner = edWebvalidate = null;
                render();
                break;
            case EMPTY: if (op.loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_empty_hosted)); break;
            case CHANGED: if (op.loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_changed)); break;
            case INVALID: if (op.loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_invalid)); break;
            case FAILED: if (op.loud) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_restore_storage)); break;
        }
    }

    private void publishSalon(TextView status) {
        if (announcement != null) { publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_busy)); return; }
        if (!nodeUp || !SalonStore.hasIdentity(this) || SalonStore.get(this, "profileUrl").isEmpty()) {
            publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_need_node)); return;
        }
        final String owner;
        try { owner = backupOwner(); } catch (Exception unavailable) { identityUnavailable(); return; }
        final String url = SalonStore.get(this, "profileUrl");
        final int epoch = renderEpoch;
        final Object request = new Object();
        announcement = request;
        publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_announcing));
        final SalonRegistry.Announced result = (ok, detail) -> runOnUiThread(() -> {
            if (announcement != request) return;
            announcement = null;
            if (isFinishing() || isDestroyed()) return;
            try { if (!owner.equals(backupOwner()) || !url.equals(SalonStore.get(this, "profileUrl"))) return; }
            catch (Exception unavailable) { return; }
            publicationStatus(epoch == renderEpoch ? status : null, Language.msg(ok
                    ? com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_submitted
                    : com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_unknown));
        });
        try { SalonRegistry.announce(node, SalonStore.get(this, "tokenid"), url, SalonStore.get(this, "handle"), result); }
        catch (RuntimeException unavailable) { result.done(false, ""); }
    }

    private static final class SpareBurn {
        final String owner, keep;
        final int epoch;
        final NodeApi node;
        boolean lookedUp, submitted;
        int pending;
        androidx.appcompat.app.AlertDialog dialog;
        SpareBurn(String owner, String keep, int epoch, NodeApi node) {
            this.owner = owner; this.keep = keep; this.epoch = epoch; this.node = node;
        }
    }
    private SpareBurn spareBurn;

    private boolean spareBurnCurrent(SpareBurn op) {
        if (spareBurn != op || isFinishing() || isDestroyed() || op.epoch != renderEpoch
                || node != op.node || !nodeUp) return false;
        try { return op.owner.equals(backupOwner()); }
        catch (Exception unavailable) { return false; }
    }

    private void cancelSpareBurn() {
        SpareBurn op = spareBurn;
        spareBurn = null;
        if (op != null && op.dialog != null) op.dialog.dismiss();
    }

    private void spareBurnNotice(SpareBurn op, CharSequence message) {
        if (spareBurnCurrent(op)) toast(message);
    }

    /** Confirm only validated spare identities, retaining the original amount/destination. */
    private void burnSpares() {
        if (isFinishing() || isDestroyed()) return;
        if (!nodeUp || node == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_node)); return; }
        if (spareBurn != null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_busy)); return; }
        final SpareBurn op;
        try {
            JSONObject me = SalonStore.identityRecord(this);
            String keep = me.optString("tokenid");
            if (!Args.isTokenId(keep) || new java.math.BigInteger(keep.substring(2), 16).signum() == 0) {
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_identity)); return;
            }
            op = new SpareBurn(backupOwner(me), keep, renderEpoch, node);
        } catch (Exception unavailable) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_identity)); return; }
        spareBurn = op;
        NodeApi.Cb result = new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) { runOnUiThread(() -> showSpareBurn(op, reply)); }
            @Override public void onError(String ignored) { runOnUiThread(() -> showSpareBurn(op, null)); }
        };
        try { op.node.cmd("balance", result); } catch (RuntimeException unavailable) { result.onError(""); }
    }

    private void showSpareBurn(SpareBurn op, JSONObject reply) {
        if (spareBurn != op || op.lookedUp) return;
        op.lookedUp = true;
        if (!spareBurnCurrent(op)) { cancelSpareBurn(); return; }
        final List<String> spares;
        try { spares = IdentityBalance.spares(reply, op.keep); }
        catch (RuntimeException unavailable) { spareBurnNotice(op, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_lookup)); cancelSpareBurn(); return; }
        if (spares.isEmpty()) { spareBurnNotice(op, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_empty)); cancelSpareBurn(); return; }
        StringBuilder ids = new StringBuilder();
        for (String id : spares) ids.append("• ").append(id).append("\n");
        op.dialog = new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_title, spares.size()))
                .setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_confirm, op.keep, ids.toString()))
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_burn), (d, which) -> submitSpareBurn(op, spares))
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel), null).create();
        op.dialog.setOnDismissListener(d -> { if (spareBurn == op && !op.submitted) spareBurn = null; });
        op.dialog.show();
    }

    private void submitSpareBurn(SpareBurn op, List<String> spares) {
        if (spareBurn != op || op.submitted) return;
        if (!spareBurnCurrent(op)) {
            if (!isFinishing() && !isDestroyed()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_changed));
            cancelSpareBurn(); return;
        }
        op.submitted = true;
        op.pending = spares.size();
        for (String tid : spares) {
            // Recheck immediately before dispatch. Already dispatched sends cannot be cancelled.
            if (!spareBurnCurrent(op) || tid.equalsIgnoreCase(SalonStore.get(this, "tokenid"))) {
                cancelSpareBurn(); return;
            }
            final boolean[] received = {false};
            NodeApi.Cb result = new NodeApi.Cb() {
                private void finish(JSONObject reply) {
                    runOnUiThread(() -> {
                        if (received[0]) return;
                        received[0] = true;
                        if (reply != null && Boolean.TRUE.equals(reply.opt("status"))) spareBurnNotice(op, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_submitted, tid));
                        else spareBurnNotice(op, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_unknown, tid));
                        if (--op.pending == 0 && spareBurn == op) spareBurn = null;
                    });
                }
                @Override public void onResult(JSONObject reply) { finish(reply); }
                @Override public void onError(String ignored) { finish(null); }
            };
            try { op.node.cmd("send amount:1 tokenid:" + tid + " address:" + GRAVEYARD, result); }
            catch (RuntimeException unavailable) { result.onError(""); }
        }
    }

    /* ================= SETTINGS + HOSTING (unchanged) ================= */

    private void renderSettings() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_settings));
        LinearLayout nodeCard = card(); nodeCard.addView(Design.lot(this, "Minima Core"));
        addKvPlain(nodeCard, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_tab_node), nodeUp ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_connected) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_not_connected));
        body.addView(nodeCard, lp(0, 0, 0, 12));
        Hosting.Profile def = HostingStore.getDefault(this);
        LinearLayout hostCard = card(); hostCard.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_hosting)));
        hostCard.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_hosting_help)), lp(0, 4, 0, 6));
        addKvPlain(hostCard, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_eth_wallet_default), def == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_none) : def.name() + " · " + def.type());
        hostCard.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_manage), true, () -> go(Screen.HOSTING)), lp(0, 8, 0, 0));
        body.addView(hostCard, lp(0, 0, 0, 12));

        LinearLayout bkp = card(); bkp.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_backup)));
        bkp.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_backup_help)), lp(0, 4, 0, 6));
        bkp.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_backup_file), true, this::backupAccount), lp(0, 6, 0, 0));
        bkp.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_restore_file), false, this::restoreFromFile), lp(0, 8, 0, 0));
        body.addView(bkp, lp(0, 0, 0, 12));
        if (SalonStore.hasIdentity(this)) {
            LinearLayout idc = card(); idc.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_home_identity)));
            copyRow(idc, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_handle), "@" + SalonStore.get(this, "handle"));
            copyRow(idc, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_token), SalonStore.get(this, "tokenid"));
            String tipaddr = SalonStore.get(this, "tipaddr");
            if (!tipaddr.isEmpty()) { copyRow(idc, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_tip_address), tipaddr);
                idc.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_tip_qr), false, () -> showQr(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_tip_for, SalonStore.get(this, "handle")), tipaddr)), lp(0, 4, 0, 0)); }
            idc.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_republish), false, () -> publishSalon(null)), lp(0, 8, 0, 0));
            boolean bg = SalonKeepAliveWorker.bgEnabled(this);
            idc.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_background_help)), lp(0, 8, 0, 4));
            idc.addView(btn(bg ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_background_on) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_background_off), false, () -> {
                if (SalonKeepAliveWorker.bgEnabled(this)) {
                    SalonStore.put(this, "keepalivebg", "off");
                    try {
                        androidx.work.WorkManager.getInstance(this).cancelUniqueWork(SalonKeepAliveWorker.WORK_ONESHOT);
                        androidx.work.WorkManager.getInstance(this).cancelUniqueWork(SalonKeepAliveWorker.WORK_PERIODIC);
                    } catch (Throwable ignored) {}
                } else {
                    SalonStore.put(this, "keepalivebg", "");
                    scheduleKeepAliveFallback();
                }
                render();
            }), lp(0, 8, 0, 0));
            idc.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_restore_page), false, () -> hydrateFromHosted(SalonStore.get(this, "profileUrl"), true)), lp(0, 8, 0, 0));
            idc.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_backup_key), false, this::backupMsgKey), lp(0, 8, 0, 0));
            idc.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_restore_key), false, this::restoreMsgKey), lp(0, 8, 0, 0));
            idc.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_burn), false, this::burnSpares), lp(0, 8, 0, 0));
            body.addView(idc, lp(0, 0, 0, 12));
        }
        LinearLayout about = card(); about.addView(Design.lot(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_about)));
        addKvPlain(about, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_app_heading), "The Salon"); addKvPlain(about, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        body.addView(about, lp(0, 0, 0, 12));
    }

    private void renderHosting() {
        masthead(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_hosting));
        List<Hosting.Profile> profiles = HostingStore.list(this);
        if (profiles.isEmpty()) { LinearLayout e = card(); e.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_empty))); body.addView(e, lp(0, 0, 0, 12)); }
        for (Hosting.Profile p : profiles) {
            LinearLayout c = card(); LinearLayout head = row();
            head.addView(Design.text(this, p.name().isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_unnamed) : p.name(), 15, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Design.pill(this, p.type(), Design.PILL_DIM)); if (p.isDefault()) head.addView(Design.pill(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_default), Design.PILL_DONE));
            c.addView(head, lp(0, 0, 0, 8));
            LinearLayout r = row();
            r.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_test), false, () -> testProfile(p)), weight(48, 0, 4));
            r.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_edit), false, () -> { hostEdit = p; go(Screen.HOSTING_EDIT); }), weight(48, 4, 4));
            r.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_filez_delete), false, () -> { HostingStore.remove(this, p.id()); render(); }), weight(48, 4, 0));
            c.addView(r, lp(0, 0, 0, 6));
            if (!p.isDefault()) c.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_make_default), false, () -> { HostingStore.setDefault(this, p.id()); render(); }), lph(42, 0, 0, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
        }
        body.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_add), true, () -> { hostEdit = null; go(Screen.HOSTING_EDIT); }), lph(52, 0, 4, 0, 8));
    }

    // RETIRED (MaxLite sunset) — encrypted relay dropped from the picker. To restore, re-add
    // Hosting.TYPE_RELAY to HTYPES and "Encrypted relay" to HLABELS at the same index.
    private static final String[] HTYPES = { Hosting.TYPE_BLOSSOM, Hosting.TYPE_SFTP, Hosting.TYPE_WEBDAV, Hosting.TYPE_KUBO, Hosting.TYPE_PINATA, Hosting.TYPE_GITHUB, Hosting.TYPE_MAXIMA };
    private static final CharSequence[] HLABELS = { "Blossom", "SFTP", "WebDAV", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_ipfs_node), "Pinata", "GitHub", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_maxima_mesh) };

    private void renderHostingEdit() {
        masthead(hostEdit == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_add) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_edit_destination));
        if (hostEdit == null) hostEdit = Hosting.Profile.fresh(Hosting.TYPE_BLOSSOM);
        final Hosting.Profile p = hostEdit;
        LinearLayout card = card();
        EditText nameF = field(card, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_name), p.name(), false, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_server_hint));
        nameF.addTextChangedListener(watch(s -> Hosting.put(p.j, "name", s)));
        card.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_type)), lp(0, 8, 0, 4));
        LinearLayout chips = row();
        for (int i = 0; i < HTYPES.length; i++) { final String t = HTYPES[i]; TextView chip = Design.chip(this, HLABELS[i], t.equals(p.type())); chip.setPadding(dp(11), dp(6), dp(11), dp(6));
            chip.setOnClickListener(v -> { if (!t.equals(p.type())) { Hosting.put(p.j, "type", t); if (p.j.optJSONObject(t) == null) Hosting.put(p.j, t, new JSONObject()); render(); } });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.rightMargin = dp(6); chips.addView(chip, clp); }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(chips);
        card.addView(hs, lp(0, 0, 0, 6));
        JSONObject cfg = p.cfg();
        switch (p.type()) {
            case Hosting.TYPE_SFTP:
                cfgField(card, cfg, "host", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_host), "31.125.188.214", false); cfgField(card, cfg, "port", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_terminal_txn_port), "22", false); cfgField(card, cfg, "user", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_user), "root", false);
                boolean keyAuth = "key".equals(cfg.optString("auth", "password"));
                card.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_authentication)), lp(0, 8, 0, 4));
                LinearLayout ar = row(); TextView pw = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_password), !keyAuth), ky = Design.chip(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_eth_wallet_private_key), keyAuth);
                pw.setPadding(dp(11), dp(6), dp(11), dp(6)); ky.setPadding(dp(11), dp(6), dp(11), dp(6));
                pw.setOnClickListener(v -> { Hosting.put(cfg, "auth", "password"); render(); }); ky.setOnClickListener(v -> { Hosting.put(cfg, "auth", "key"); render(); });
                LinearLayout.LayoutParams m8 = new LinearLayout.LayoutParams(-2, -2); m8.rightMargin = dp(8); ar.addView(pw, m8); ar.addView(ky); card.addView(ar, lp(0, 0, 0, 6));
                if (keyAuth) cfgSecretMulti(card, cfg, "privateKey", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_private_key_pem)); else cfgSecret(card, cfg, "password", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_password));
                cfgField(card, cfg, "remoteRoot", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_remote_root), "/var/www/html/salon", false);
                cfgField(card, cfg, "urlPrefix", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_url_prefix), "http://31.125.188.214/salon/", false); break;
            case Hosting.TYPE_WEBDAV:
                cfgField(card, cfg, "endpoint", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_write_endpoint), "https://dav.example.com/files/", false); cfgField(card, cfg, "user", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_user), "", false); cfgSecret(card, cfg, "password", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_password)); cfgField(card, cfg, "urlPrefix", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_url_prefix), "https://example.com/files/", false); break;
            case Hosting.TYPE_KUBO:
                cfgField(card, cfg, "apiUrl", "kubo RPC API", "https://api-ipfs.eurobuddha.com", false);
                cfgField(card, cfg, "user", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_api_user), "ipfs", false);
                cfgSecret(card, cfg, "password", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_api_password));
                cfgField(card, cfg, "gateway", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_public_gateway), "https://ipfs.eurobuddha.com", false);
                card.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_kubo_help)), lp(0, 6, 0, 2));
                break;
            case Hosting.TYPE_PINATA:
                cfgSecret(card, cfg, "jwt", "Pinata JWT"); cfgField(card, cfg, "gateway", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_gateway), "https://gateway.pinata.cloud", false); break;
            case Hosting.TYPE_GITHUB:
                card.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_github_help)), lp(0, 6, 0, 4));
                cfgField(card, cfg, "owner", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_owner), "", false); cfgField(card, cfg, "repo", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_repo), "salon", false); cfgField(card, cfg, "branch", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_branch), "main", false); cfgSecret(card, cfg, "token", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_token)); cfgField(card, cfg, "serve", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_serve), "raw", false); cfgField(card, cfg, "pagesPrefix", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_pages_prefix), "https://you.github.io/salon/", false); break;
            // RETIRED (MaxLite sunset) — encrypted-relay config UI removed from the picker. To
            // restore, re-add TYPE_RELAY to HTYPES/HLABELS and uncomment this case:
            // case Hosting.TYPE_RELAY:
            //     cfgField(card, cfg, "relayUrl", "Relay URL", RelayUploader.DEFAULT_RELAY, false);
            //     card.addView(Design.note(this, "Publish with NO server of your own: your phone encrypts your page + media (libsodium) and uploads only the CIPHERTEXT to this relay's blob store — the relay can never read it. The decryption key travels in your public pointer, so any Salon viewer can see your page.\n\nTrade-offs: viewable in the Salon app only (a web browser can't decrypt it — SFTP/IPFS keep the browser view); and content expires ~7 days after it's last opened/viewed, so open the app now and then to keep it alive."), lp(0, 6, 0, 2));
            //     break;
            case Hosting.TYPE_BLOSSOM:
                card.addView(Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_blossom_help)), lp(0, 6, 0, 2));
                // Pre-fill the actual value, not just the hint — the field must show
                // where the page will land without the user typing anything.
                if (cfg.optString("endpoint", "").isEmpty()) Hosting.put(cfg, "endpoint", BlossomUploader.DEFAULT_SERVER);
                cfgField(card, cfg, "endpoint", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_blossom_server), BlossomUploader.DEFAULT_SERVER, false);
                card.addView(Design.kicker(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_nostr_identity)), lp(0, 8, 0, 4));
                try {
                    copyRow(card, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_public_key_hex), NostrKeys.pubkeyHex());
                    copyRow(card, "npub", NostrKeys.npub());
                } catch (SalonComms.IdentityUnavailable unavailable) {
                    TextView error = Design.note(this, "");
                    Language.setText(error, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_unavailable));
                    card.addView(error);
                }
                break;
            case Hosting.TYPE_MAXIMA:
                TextView meshNote=Design.note(this, "");
                Language.setText(meshNote, Language.join(
                        Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_mesh_help), "\n\n",
                        Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_mesh_usage, mibOf(meshUsageBytes()), MAX_PROFILE_MESH_BYTES >> 20), "\n\n",
                        Language.msg(MaximaLink.isReady(this) ? com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_connected
                                : com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_connecting)));
                card.addView(meshNote, lp(0, 6, 0, 2));
                break;
        }
        final TextView status = Design.note(this, ""); card.addView(status, lp(0, 8, 0, 0));
        LinearLayout btns = row();
        btns.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_save), true, () -> { if (saveHost(p)) go(Screen.HOSTING); }), weight(48, 0, 4));
        btns.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_save_test), false, () -> { if (saveHost(p)) testProfile(p, status); }), weight(48, 4, 0));
        card.addView(btns, lp(0, 8, 0, 0));
        body.addView(card, lp(0, 0, 0, 12));
    }

    private boolean saveHost(Hosting.Profile p) {
        if (!hostingSecretsReady(p)) return false;
        if (p.name().isEmpty()) Hosting.put(p.j, "name", p.type());
        Hosting.put(p.j, "pathTemplate", "salon/{collection}/{name}-{ts}{ext}"); Hosting.put(p.j, "dirTemplate", "salon/{collection}-{ts}");
        HostingStore.upsert(this, p);
        if (HostingStore.getDefault(this) == null || HostingStore.list(this).size() == 1) HostingStore.setDefault(this, p.id());
        return true;
    }

    /** A test owns a frozen destination and the account/screen that requested it. */
    private static final class HostingCheck {
        final String owner;
        final Hosting.Profile profile, editor;
        final int epoch;
        final TextView status;
        volatile boolean cancelled;
        HostingCheck(String owner, Hosting.Profile profile, Hosting.Profile editor, int epoch, TextView status) {
            this.owner=owner; this.profile=profile; this.editor=editor; this.epoch=epoch; this.status=status;
        }
    }
    private volatile HostingCheck hostingCheck;
    private androidx.appcompat.app.AlertDialog hostingTrustDialog;

    private void cancelHostingCheck() {
        HostingCheck op=hostingCheck; hostingCheck=null;
        if(op!=null) op.cancelled=true;
        if(hostingTrustDialog!=null) {
            androidx.appcompat.app.AlertDialog dialog=hostingTrustDialog;hostingTrustDialog=null;dialog.dismiss();
        }
    }
    private boolean hostingStoredCurrent(HostingCheck op) {
        if(op.cancelled || hostingCheck!=op) return false;
        synchronized(SalonStore.class) {
            try { return op.owner.equals(backupOwner()) && HostingStore.matches(this,op.profile); }
            catch(Exception unavailable) { return false; }
        }
    }
    private boolean hostingCurrent(HostingCheck op) {
        if(isFinishing() || isDestroyed() || op.epoch!=renderEpoch) return false;
        try {
            return HostingStore.profileSnapshot(op.editor).equals(HostingStore.profileSnapshot(op.profile))
                    && hostingStoredCurrent(op);
        } catch(Exception unavailable) { return false; }
    }
    private HostingCheck beginHostingCheck(Hosting.Profile p,TextView status) {
        cancelHostingCheck();
        if(isFinishing() || isDestroyed()) return null;
        try {
            synchronized(SalonStore.class) {
                HostingCheck op=new HostingCheck(backupOwner(),new Hosting.Profile(new JSONObject(p.j.toString())),p,renderEpoch,status);
                if(!HostingStore.matches(this,op.profile)) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_changed));return null; }
                hostingCheck=op;return op;
            }
        } catch(Exception unavailable) { publicationStatus(status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage));return null; }
    }
    private void finishHostingCheck(HostingCheck op,CharSequence message) {
        if(hostingCheck!=op) return;
        boolean current=hostingCurrent(op);cancelHostingCheck();
        if(current) publicationStatus(op.status,message);
    }
    private void testProfile(Hosting.Profile p) { testProfile(p,null); }
    private void testProfile(Hosting.Profile p,TextView status) {
        HostingCheck op=beginHostingCheck(p,status);
        if(op==null) return;
        publicationStatus(status,status!=null?Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_testing):Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_testing_name, op.profile.name()));
        try {
            io.execute(()->{
                if(!hostingStoredCurrent(op)) return;
                try {
                    String rel="_test/probe-"+java.util.UUID.randomUUID()+".txt";
                    String url;
                    try(Hosting.Uploader up=Hosting.forProfile(op.profile)) {
                        if(!hostingStoredCurrent(op)) return;
                        url=up.putFile("salon-test".getBytes(java.nio.charset.StandardCharsets.UTF_8),rel,"text/plain");
                    }
                    if(!hostingStoredCurrent(op)) return;
                    Hosting.verifyUrl(url,op.profile);
                    runOnUiThread(()->finishHostingCheck(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_tested, url)));
                } catch(SftpUploader.HostKeyUnverified untrusted) {
                    runOnUiThread(()->showHostingTrust(op,untrusted.fingerprint));
                } catch(Exception error) {
                    runOnUiThread(()->finishHostingCheck(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_failed, error.getMessage())));
                }
            });
        } catch(java.util.concurrent.RejectedExecutionException unavailable) { finishHostingCheck(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage)); }
    }
    /** Setup already checks its operation before handing its captured host here. */
    private void promptTrustHostKey(Hosting.Profile p,String fp,TextView status) {
        HostingCheck op=beginHostingCheck(p,status);
        if(op!=null) showHostingTrust(op,fp);
    }
    private void showHostingTrust(HostingCheck op,String fp) {
        if(!hostingCurrent(op) || !Hosting.TYPE_SFTP.equals(op.profile.type()) || fp==null || fp.isEmpty()
                || !op.profile.cfgStr("hostKeyFp").isEmpty() || hostingTrustDialog!=null) return;
        hostingTrustDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_trust_title)).setMessage(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_trust_body, op.profile.cfgStr("host"), fp))
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_trust_pin),(d,w)->{
                    synchronized(SalonStore.class) {
                        if(!hostingCurrent(op)) { if(hostingCheck==op)cancelHostingCheck();return; }
                        // Consume consent before touching persistent state or scheduling a retry.
                        cancelHostingCheck();
                        boolean saved;
                        try { saved=HostingStore.pinHostKey(this,op.profile,fp); }
                        catch(Exception unavailable) { saved=false; }
                        if(!saved) { publicationStatus(op.status,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage));return; }
                        Hosting.put(op.editor.cfg(),"hostKeyFp",fp);
                        testProfile(op.editor,op.status);
                    }
                })
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel),(d,w)->finishHostingCheck(op,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_not_trusted))).show();
        hostingTrustDialog.setOnDismissListener(d->{if(hostingCheck==op)cancelHostingCheck();});
    }

    /* ================= node key ================= */

    private void fetchPubkey() {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { String pk = firstPublicKey(json); if (!pk.isEmpty()) runOnUiThread(() -> pubkey = pk); else node.cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j2) { JSONObject r = j2.optJSONObject("response"); if (r != null && !r.optString("publickey").isEmpty()) runOnUiThread(() -> pubkey = r.optString("publickey")); }
                @Override public void onError(String m) {} }); }
            @Override public void onError(String m) {} });
    }
    private String firstPublicKey(JSONObject json) {
        Object resp = json.opt("response");
        try { if (resp instanceof JSONArray) { JSONArray a = (JSONArray) resp; if (a.length() > 0 && a.optJSONObject(0) != null) return a.optJSONObject(0).optString("publickey", ""); }
            else if (resp instanceof JSONObject) { JSONArray keys = ((JSONObject) resp).optJSONArray("keys"); if (keys != null && keys.length() > 0) return keys.optJSONObject(0).optString("publickey", ""); return ((JSONObject) resp).optString("publickey", ""); }
        } catch (Exception ignored) {}
        return "";
    }

    /* ================= HTTP ================= */

    // Short-TTL memo of fetched profile JSON, keyed by url/ref. Feed + Discover re-pull
    // every followed/listed profile (each up to 1 MB) on EVERY render/swipe/reconnect;
    // this collapses that to at most one fetch per url per TTL. Pull-to-refresh clears it.
    private final java.util.Map<String, Object[]> mProfileCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PROFILE_CACHE_TTL_MS = 90_000L;

    // Memo of the last town-square scan (a depth-1500 chain read). Discover and
    // openTokenProfile both trigger it; without this, tapping a feed author or
    // re-rendering Discover re-scans the whole square each time. Cleared on pull-to-refresh.
    private java.util.List<SalonRegistry.Entry> mRegistryCache;
    private long mRegistryCacheTs;
    private static final long REGISTRY_TTL_MS = 60_000L;

    private void registryList(SalonRegistry.Listed cb) {
        if (mRegistryCache != null && System.currentTimeMillis() - mRegistryCacheTs < REGISTRY_TTL_MS) { cb.done(mRegistryCache); return; }
        SalonRegistry.list(node, entries -> { mRegistryCache = entries; mRegistryCacheTs = System.currentTimeMillis(); cb.done(entries); });
    }

    private JSONObject httpGetJson(String url) { return httpGetJson(url, false); }

    /** @param force bypass the memo cache and re-fetch (pull-to-refresh). */
    private JSONObject httpGetJson(String url, boolean force) {
        if (url == null) return null;
        if (!force) {
            Object[] e = mProfileCache.get(url);
            if (e != null && System.currentTimeMillis() - (Long) e[1] < PROFILE_CACHE_TTL_MS) return (JSONObject) e[0];
        }
        JSONObject j = httpGetJsonRaw(url);
        if (j != null) mProfileCache.put(url, new Object[]{ j, System.currentTimeMillis() });
        return j;
    }

    private JSONObject httpGetJsonRaw(String url) {
        // A profile pointer can be relay1: (encrypted relay) OR mx1: (Maxima mesh)
        // OR a plain http(s) URL. isMediaRef covers BOTH ref schemes; without the
        // mx1: case a mesh-hosted profile fell through to the http branch, failed
        // the startsWith("http") test, returned null and showed "host offline".
        if (RelayResolver.isMediaRef(url)) {
            try { return RelayResolver.resolveJson(url); } catch (Exception e) { return null; }
        }
        HttpURLConnection c = null;
        try {
            if (url == null || !url.startsWith("http")) return null;
            // SSRF-safe: validates the host AND re-checks every redirect hop, so an
            // attacker-published profile URL can't 302 us to the local Minima node.
            c = ImageLoader.openChecked(url, 3);
            if (c == null || c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n, tot = 0; while ((n = in.read(buf)) > 0 && tot < 1_000_000) { bos.write(buf, 0, n); tot += n; } in.close();
            return new JSONObject(bos.toString("UTF-8"));
        } catch (Exception e) { return null; } finally { if (c != null) c.disconnect(); }
    }

    private byte[] readCapped(Uri uri, int maxMB) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot read file");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[16384];
            int n; long total = 0, cap = (long) maxMB * 1024 * 1024;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                if (n == 0) { int one = in.read(); if (one == -1) break; buf[0] = (byte) one; n = 1; }
                total += n;
                if (total > cap) throw new Exception("file too large (max " + maxMB + " MB)");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private byte[] readScaledJpeg(Uri uri, int maxDim) throws Exception {
        // ImageTools.boundedBitmap decodes via ImageDecoder, which applies EXIF
        // orientation exactly once — the Atelier fix for sideways Samsung photos.
        Bitmap bmp = ImageTools.boundedBitmap(this, uri, maxDim);
        if (bmp == null) throw new Exception("could not read image");
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos); return bos.toByteArray();
    }

    /* ================= UI helpers ================= */

    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(Design.blockShadow(this, Design.CARD())); c.setPadding(dp(14), dp(12), dp(14), dp(12)); return c; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }

    private void addKvPlain(LinearLayout parent, CharSequence k, CharSequence v) {
        LinearLayout r = row(); r.setPadding(0, dp(4), 0, dp(4));
        TextView key = Design.text(this, Language.upper(k), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.12f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, v == null ? "—" : v, 11.5f, Design.INK(), Design.mono()); val.setGravity(Gravity.END);
        r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.6f)); parent.addView(r);
    }

    /** kv row: shortened value, TAP copies the FULL value. */
    private void copyRow(LinearLayout parent, CharSequence k, String full) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(0, dp(6), 0, dp(6));
        LinearLayout head = row();
        TextView key = Design.text(this, Language.upper(k), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.12f);
        head.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Design.text(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_tap_copy), 8.5f, Design.ACCENT(), Design.sansBold()));
        col.addView(head);
        // Show the FULL value (no truncation) in a tappable box — wraps rather than clipping.
        TextView val = Design.text(this, full, 11f, Design.INK(), Design.mono());
        val.setBackground(Design.dashed(this, Design.CARD(), Design.DIM())); val.setPadding(dp(8), dp(6), dp(8), dp(6));
        copyOnTap(val, full);
        col.addView(val, lp(0, 3, 0, 0));
        parent.addView(col);
    }

    private void copyOnTap(TextView t, String full) {
        t.setClickable(true);
        t.setOnClickListener(v -> { ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("salon", full)); toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_history_copied)); });
    }

    /** label + tappable vermilion underlined link (opens browser), long-press copies. */
    private LinearLayout linkRow(CharSequence label, String url) {
        LinearLayout r = row(); r.setPadding(0, dp(5), 0, dp(5));
        TextView key = Design.text(this, Language.upper(label), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.1f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, url, 11f, Design.ACCENT(), Design.mono()); val.setGravity(Gravity.END);
        val.setPaintFlags(val.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); val.setMaxLines(2);
        val.setClickable(true); val.setOnClickListener(v -> openUrl(url));
        val.setOnLongClickListener(v -> { ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("salon", url)); toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_history_copied)); return true; });
        r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.8f)); return r;
    }

    private TextView verifiedBadge(String host) {
        int gold = 0xFF9A7B1F;
        CharSequence label = host == null || host.isEmpty() ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_web_verified) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_verified_host, host);
        TextView t = Design.text(this, label, 9.5f, gold, Design.sansBold());
        t.setLetterSpacing(0.1f); t.setPadding(dp(8), dp(5), dp(8), dp(6)); t.setBackground(Design.ruled(this, Design.CARD(), gold, 2)); return t;
    }

    private String urlHost(String url) { try { return new java.net.URL(url.trim()).getHost(); } catch (Exception e) { return ""; } }

    /** Atelier/NFTwallet parity: wrap the avatar so a web-validation plaque
     *  (Identicon.checkBadge) sits bottom-right when the token's webvalidate doc
     *  proves the tokenid — the over-image mark that flags the real, verified
     *  account (handles aren't unique, so this is what distinguishes fakes). */
    private FrameLayout badgedAvatar(String avatarUrl, String label, final String tokenid, String webvalidate, int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        View av = avatarView(avatarUrl, label, sizeDp);
        f.addView(av, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        final int bs = dp(Math.max(15, sizeDp / 3));
        final Runnable paint = () -> {
            if (f.getChildCount() > 1) f.removeViewAt(1);
            if (Boolean.TRUE.equals(WebValidate.status(tokenid, webvalidate))) {
                ImageView badge = new ImageView(this);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
                blp.setMargins(0, 0, dp(2), dp(2));
                badge.setLayoutParams(blp); badge.setImageBitmap(Identicon.checkBadge(bs));
                f.addView(badge);
            }
        };
        paint.run();
        if (tokenid != null && !tokenid.isEmpty() && webvalidate != null && !webvalidate.isEmpty())
            WebValidate.ensure(this, f, tokenid, webvalidate, paint);
        return f;
    }

    private EditText field(LinearLayout parent, CharSequence label, String value, boolean secret, CharSequence hint) {
        parent.addView(Design.text(this, Language.upper(label), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this); e.setText(value == null ? "" : value); Language.hint(e, hint); e.setTextColor(Design.INK()); e.setTextSize(14);
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1)); e.setPadding(dp(10), dp(9), dp(10), dp(9));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private EditText fieldMulti(LinearLayout parent, CharSequence label, String value) {
        parent.addView(Design.text(this, Language.upper(label), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this); e.setText(value == null ? "" : value); e.setTextColor(Design.INK()); e.setTextSize(14);
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1)); e.setPadding(dp(10), dp(9), dp(10), dp(9));
        e.setMinLines(3); e.setGravity(Gravity.TOP); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private void cfgField(LinearLayout parent, JSONObject cfg, String key, CharSequence label, CharSequence hint, boolean secret) { EditText e = field(parent, label, cfg.optString(key, ""), secret, hint); e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, s))); }
    private final HostingSecrets hostSecrets = new HostingSecrets();

    private void hostingSecretUnavailable() {
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable).toString());
    }
    private boolean hostingSecretsReady(Hosting.Profile profile) {
        if (hostSecrets.retry(profile)) return true;
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved).toString());
        return false;
    }
    private void bindHostingSecret(EditText input, JSONObject cfg, String key,
            boolean revealStored, boolean trim, boolean keepBlank) {
        hostSecrets.bind(input, cfg, key);
        try { input.setText(hostSecrets.value(cfg, key, revealStored)); }
        catch (Crypt.Unavailable unavailable) {
            Language.error(input, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable));
        }
        if (hostSecrets.pending(cfg, key))
            Language.error(input, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved));
        input.addTextChangedListener(watch(value -> {
            String next = trim ? value.trim() : value;
            if (keepBlank && next.isEmpty()) {
                hostSecrets.discard(cfg, key); Language.error(input, null); return;
            }
            boolean saved = hostSecrets.write(cfg, key, next);
            Language.error(input, saved ? null : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved));
        }));
    }

    private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, CharSequence label) { EditText e = field(parent, label, "", true, "••••••"); bindHostingSecret(e, cfg, key, true, false, false); }
    private void cfgSecretMulti(LinearLayout parent, JSONObject cfg, String key, CharSequence label) {
        parent.addView(Design.text(this, Language.upper(label), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this); e.setTextColor(Design.INK()); e.setTextSize(11); e.setTypeface(Design.mono());
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1)); e.setPadding(dp(10), dp(9), dp(10), dp(9)); e.setMinLines(4); e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); bindHostingSecret(e, cfg, key, true, false, false);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView btn(CharSequence label, boolean filled, Runnable click) { TextView b = Design.button(this, label, filled); b.setMinHeight(dp(48)); b.setOnClickListener(v -> click.run()); return b; }

    private LinearLayout dialogBox() { LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(18), dp(6), dp(18), dp(6)); return b; }
    private void showDialog(CharSequence title, LinearLayout content, CharSequence ok, Runnable onOk) {
        new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(title).setView(content)
                .setPositiveButton(ok, (d, w) -> onOk.run()).setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel), null).show();
    }

    private JSONArray removeAt(JSONArray a, int idx) { JSONArray out = new JSONArray(); for (int i = 0; i < a.length(); i++) if (i != idx) out.put(a.opt(i)); return out; }

    private void openUrl(String url) {
        try {
            String u = url.trim();
            if (u.matches("(?i)^https?://.*")) {                       // http/https: open
            } else if (u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {     // any other scheme (file://, market://, app deep-links): refuse
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_only_web)); return;
            } else {
                u = "http://" + u;                                    // bare domain → http
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_open_failed)); }
    }

    /** One-tap: create + default a Blossom hosting profile (no server needed). */
    private void quickStartBlossom() {
        Hosting.Profile p = Hosting.Profile.fresh(Hosting.TYPE_BLOSSOM);
        Hosting.put(p.j, "name", "Blossom");
        Hosting.put(p.cfg(), "endpoint", BlossomUploader.DEFAULT_SERVER);
        saveHost(p);
        HostingStore.setDefault(this, p.id());
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_blossom_ready));
        render();
    }

    // RETIRED (MaxLite sunset) — one-tap encrypted-relay onboarding. Uncomment to restore:
    // /** One-tap: create + default an encrypted-relay hosting profile (no server needed). */
    // private void quickStartRelay() {
    //     Hosting.Profile p = Hosting.Profile.fresh(Hosting.TYPE_RELAY);
    //     Hosting.put(p.j, "name", "Encrypted relay");
    //     saveHost(p);
    //     HostingStore.setDefault(this, p.id());
    //     toast("Relay hosting ready — now claim your handle.");
    //     render();
    // }

    private void sharePage(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_share_text, SalonStore.get(this, "handle"), url).toString());
            startActivity(Intent.createChooser(i, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_share_chooser)));
        } catch (Exception e) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_share_failed)); }
    }

    private void showQr(CharSequence title, String data) {
        Bitmap bmp = Qr.bitmap(data, 640);
        if (bmp == null) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_qr_failed)); return; }
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFFFFFFFF); col.setPadding(dp(24), dp(24), dp(24), dp(24));
        ImageView iv = new ImageView(this); iv.setImageBitmap(bmp);
        col.addView(iv, new LinearLayout.LayoutParams(dp(280), dp(280)));
        TextView cap = Design.text(this, title, 13, 0xFF111111, Design.mono()); cap.setGravity(Gravity.CENTER); cap.setPadding(0, dp(16), 0, 0);
        col.addView(cap);
        col.setOnClickListener(v -> d.dismiss());
        d.setContentView(col); d.show();
    }
    private void toast(CharSequence m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private String text(EditText e) { return e.getText().toString(); }
    private static void putJson(JSONObject o, String k, String v) { try { o.put(k, v == null ? "" : v); } catch (Exception ignored) {} }
    private TextWatcher watch(final java.util.function.Consumer<String> on) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int b, int c) {} public void onTextChanged(CharSequence s, int a, int b, int c) {} public void afterTextChanged(android.text.Editable s) { on.accept(s.toString()); } }; }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams lph(int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams weight(int hDp, int lm, int rm) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, hDp < 0 ? hDp : dp(hDp), 1); p.setMargins(dp(lm), 0, dp(rm), 0); return p; }
    private LinearLayout.LayoutParams weight1() { return new LinearLayout.LayoutParams(0, -2, 1); }

    /* Public web renderer uploaded beside profile.json — fetches ./profile.json and draws a real page. */
    private static String salonHtml() {
        return SALON_HTML.replace("__SALON_LANGUAGE__", Language.code());
    }

    private static final String SALON_HTML = "<!doctype html><html lang=\"en\" data-salon-language=\"__SALON_LANGUAGE__\"><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>The Salon</title><style>body{margin:0;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#F2F1EC;color:#141310}.w{max-width:680px;margin:0 auto;padding:0 16px 64px}.ban{width:100%;height:180px;object-fit:cover;display:block}.av{width:88px;height:88px;border-radius:8px;object-fit:cover;border:2px solid #141310;margin-top:-30px;background:#ddd}h1{font-size:26px;margin:10px 0 2px}.h{color:#E63312;font-family:monospace}.bio{font-size:16px}.k{font:700 11px/1 monospace;letter-spacing:.15em;text-transform:uppercase;color:#6B6A64;margin:26px 0 8px;border-bottom:2px solid #141310;padding-bottom:6px}a.l{display:block;padding:11px 13px;border:1.5px solid #141310;background:#FCFBF7;margin:8px 0;color:#141310;text-decoration:none;box-shadow:3px 3px 0 #141310}.g{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.g>*{aspect-ratio:1;width:100%;object-fit:cover;border:1.5px solid #141310;background:#000}.post{border:1.5px solid #141310;background:#FCFBF7;padding:12px 14px;margin:10px 0;box-shadow:3px 3px 0 #141310}.language{display:flex;align-items:center;justify-content:flex-end;gap:10px;padding:16px 0;font-size:13px}\n.language select{font:inherit;max-width:100%;padding:8px 10px;border:1.5px solid #141310;border-radius:4px;background:#FCFBF7;color:#141310}\n.language select:focus-visible{outline:3px solid #E63312;outline-offset:3px}\n.bio,.post,a.l,h1{overflow-wrap:anywhere}.bio,.post{white-space:pre-wrap}\nvideo,audio{width:100%}img.pm{width:100%;border:1.5px solid #141310;margin-top:8px}</style></head><body><div class=w><div class=\"language\"><label for=\"salon-language\" data-i18n=\"language.title\">Language</label>\n<select id=\"salon-language\"><option value=\"en\" lang=\"en\">English</option><option value=\"zh-Hans\" lang=\"zh-Hans\">简体中文</option><option value=\"ru\" lang=\"ru\">Русский</option><option value=\"uk\" lang=\"uk\">Українська</option></select></div>\n<div id=b><p role=\"status\" data-i18n=\"salonPage.loading\">Loading profile…</p></div></div><script>window.SalonMessages={\"en\":{\"language.title\":\"Language\",\"salonSocial.about\":\"About\",\"creative.links\":\"Links\",\"nftWallet.gallery\":\"Gallery\",\"salonSocial.posts\":\"Posts\",\"salonPage.loading\":\"Loading profile…\",\"salonPage.unavailable\":\"This profile could not be loaded.\"},\"zh-Hans\":{\"language.title\":\"语言\",\"salonSocial.about\":\"关于\",\"creative.links\":\"链接\",\"nftWallet.gallery\":\"画廊\",\"salonSocial.posts\":\"帖子\",\"salonPage.loading\":\"正在加载个人主页…\",\"salonPage.unavailable\":\"无法加载此个人主页。\"},\"ru\":{\"language.title\":\"Язык\",\"salonSocial.about\":\"О себе\",\"creative.links\":\"Ссылки\",\"nftWallet.gallery\":\"Галерея\",\"salonSocial.posts\":\"Публикации\",\"salonPage.loading\":\"Загрузка профиля…\",\"salonPage.unavailable\":\"Не удалось загрузить этот профиль.\"},\"uk\":{\"language.title\":\"Мова\",\"salonSocial.about\":\"Про себе\",\"creative.links\":\"Посилання\",\"nftWallet.gallery\":\"Галерея\",\"salonSocial.posts\":\"Дописи\",\"salonPage.loading\":\"Завантаження профілю…\",\"salonPage.unavailable\":\"Не вдалося завантажити цей профіль.\"}};\n/* Reuse FILTR/Parlons retained label bindings. Publisher and visitor choose explicitly. */\n(function(root) {\n  'use strict';\n  const messages = root.SalonMessages;\n  const valid = code => ['en','zh-Hans','ru','uk'].includes(code);\n  let language = 'en';\n  const bindings = new WeakMap(), renderers = new WeakMap();\n  function t(key, vars = {}) {\n    let text = messages[language][key] || messages.en[key];\n    if (text == null) throw new Error('Unknown translation key: ' + key);\n    if (typeof text !== 'string') text = text[new Intl.PluralRules(language).select(Number(vars.count))] || text.other;\n    return text.replace(/\\{(\\w+)\\}/g, (match, name) => Object.hasOwn(vars, name) ? String(vars[name]) : match);\n  }\n  function apply(node = document) {\n    const elements = [...node.querySelectorAll('[data-i18n], [data-i18n-render], [data-i18n-title], [data-i18n-placeholder], [data-i18n-aria-label], [data-i18n-alt]')];\n    if (node.nodeType === 1) elements.unshift(node);\n    for (const el of elements) {\n      if (renderers.has(el)) el.textContent = renderers.get(el)();\n      const vars = bindings.get(el) || JSON.parse(el.getAttribute('data-i18n-vars') || '{}');\n      for (const attribute of ['text', 'title', 'placeholder', 'aria-label', 'alt']) {\n        const key = el.getAttribute(attribute === 'text' ? 'data-i18n' : 'data-i18n-' + attribute);\n        if (!key) continue;\n        if (attribute === 'text') el.textContent = t(key, vars);\n        else el.setAttribute(attribute, t(key, vars));\n      }\n    }\n  }\n  function bind(el, key, vars = {}) {\n    el.removeAttribute('data-i18n-render'); renderers.delete(el);\n    el.setAttribute('data-i18n', key); bindings.set(el, vars); el.textContent = t(key, vars);\n  }\n  function render(el, formatter) {\n    el.removeAttribute('data-i18n'); bindings.delete(el);\n    el.setAttribute('data-i18n-render', ''); renderers.set(el, formatter); el.textContent = formatter();\n  }\n  function text(el, value) {\n    el.removeAttribute('data-i18n'); el.removeAttribute('data-i18n-render');\n    bindings.delete(el); renderers.delete(el); el.textContent = value;\n  }\n  function attribute(el, name, key, vars = {}) {\n    if (!['title', 'placeholder', 'aria-label', 'alt'].includes(name)) throw new Error('Unsupported translated attribute');\n    el.setAttribute('data-i18n-' + name, key); bindings.set(el, vars); el.setAttribute(name, t(key, vars));\n  }\n\n  function setLanguage(code) {\n    if (!valid(code)) throw new Error('Unsupported language');\n    language = code;\n    document.documentElement.lang = code;\n    apply();\n    document.getElementById('salon-language').value = code;\n  }\n  root.SalonI18n = Object.freeze({t,apply,bind,render,text,attribute,setLanguage,getLanguage:()=>language});\n  const storageKey = 'salon.language';\n  let code = document.documentElement.getAttribute('data-salon-language');\n  try { const saved = root.localStorage.getItem(storageKey); if (valid(saved)) code = saved; } catch (_) {}\n  setLanguage(valid(code) ? code : 'en');\n  document.getElementById('salon-language').addEventListener('change', event => {\n    const code = event.target.value;\n    if (!valid(code)) return;\n    setLanguage(code);\n    try { root.localStorage.setItem(storageKey, code); } catch (_) { /* choice still works without storage */ }\n  });\n})(window);\n\nvar x=function(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/'/g,'&#39;').replace(/\"/g,'&quot;')};var u=function(s){s=String(s==null?'':s);try{var parsed=new URL(s,location.href);return ['javascript:','data:','vbscript:'].includes(parsed.protocol)?'':x(s)}catch(_){return ''}};fetch('./profile.json').then(r=>{if(!r.ok)throw Error('Profile unavailable');return r.json()}).then(p=>{if(!p||typeof p!=='object'||Array.isArray(p))throw Error('Invalid profile');var b=document.getElementById('b');var h='';if(p.banner)h+=\"<img class=ban src='\"+u(p.banner)+\"'>\";h+=\"<img class=av src='\"+u(p.avatar||'')+\"'>\";h+='<h1>'+x(p.name||'')+'</h1>';h+=\"<div class=h>@\"+x(p.handle||'')+'</div>';if(p.bio)h+='<div class=bio>'+x(p.bio)+'</div>';if(p.about){h+='<div class=k data-i18n=salonSocial.about>About</div><div>'+x(p.about)+'</div>'}if(p.links&&p.links.length){h+='<div class=k data-i18n=creative.links>Links</div>';p.links.forEach(function(l){h+=\"<a class=l href='\"+u(l.url)+\"'>\"+x(l.label||l.url)+'</a>'})}if(p.gallery&&p.gallery.length){h+='<div class=k data-i18n=nftWallet.gallery>Gallery</div><div class=g>';p.gallery.forEach(function(m){if(m.type=='video')h+=\"<video src='\"+u(m.url)+\"' controls></video>\";else if(m.type=='audio')h+=\"<div style='display:flex;align-items:center;justify-content:center'>&#9835;</div>\";else h+=\"<img src='\"+u(m.url)+\"'>\"});h+='</div>';p.gallery.forEach(function(m){if(m.type=='audio')h+=\"<audio src='\"+u(m.url)+\"' controls></audio>\"})}if(p.posts&&p.posts.length){h+='<div class=k data-i18n=salonSocial.posts>Posts</div>';p.posts.slice().reverse().forEach(function(t){h+='<div class=post>'+x(t.text||'');if(t.media){if(t.type=='video')h+=\"<video src='\"+u(t.media)+\"' controls></video>\";else if(t.type=='audio')h+=\"<audio src='\"+u(t.media)+\"' controls></audio>\";else h+=\"<img class=pm src='\"+u(t.media)+\"'>\"}h+='</div>'})}b.innerHTML=h;SalonI18n.apply(b)}).catch(function(){document.getElementById('b').innerHTML='<p role=status data-i18n=salonPage.unavailable></p>';SalonI18n.apply(document.getElementById('b'))})</script></body></html>\n";
}
