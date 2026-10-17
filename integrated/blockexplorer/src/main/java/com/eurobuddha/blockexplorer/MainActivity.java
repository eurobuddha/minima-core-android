package com.eurobuddha.blockexplorer;

import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Minima Explorer — a full block explorer served entirely by the local Minima Core node over the
 * broadcast-Intent IPC. No internet, no external explorer: `block` for the tip, `txpow block:N`
 * to walk the chain, `txpow txpowid:` / `address:` / `onchain:` for search and detail.
 *
 * IPC discipline (the node is a phone): one bounded command at a time, blocks fetched
 * sequentially with a small gap, NEWBLOCK refreshes debounced.
 */
public class MainActivity extends AppCompatActivity {

    public static final String NODE_PKG = "com.eurobuddha.pandamonium";

    private static final int PAGE = 16;            // blocks per page, same as the minimask explorer
    private static final int FETCH_GAP_MS = 60;    // pause between sequential block fetches
    private static final int MAX_LIVE_GAP = 5;     // NEWBLOCK gap bigger than this -> full reload

    private NodeApi node;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // header / chrome
    private TextView tipChip, goBtn;
    private EditText search;
    private View pairingBanner;
    private FrameLayout content;

    // home page
    private View homeView;
    private TextView tileTip, tileSpeed, tileDb, listStatus;
    private RecyclerView recycler;
    private BlockAdapter adapter;

    // pushed detail pages (back pops)
    private final List<View> pageStack = new ArrayList<>();
    private OnBackPressedCallback backCb;

    // chain state
    private long tipHeight = 0;                    // latest known tip
    private long lowestLoaded = 0;                 // lowest block currently in the list
    private boolean loading = false;               // one sequential loader at a time
    private boolean endOfChain = false;            // hit "not found" below -> retained floor
    private boolean paired = false;

    private final List<JSONObject> blocks = new ArrayList<>();          // newest first
    private final LruCache<Long, JSONObject> blockCache = new LruCache<>(200);
    private final LruCache<String, JSONObject> txpowCache = new LruCache<>(100);

    private DirectNodeEvents.Listener notifyReceiver;
    private final Runnable refreshTask = this::refreshTip;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        Design.apply(Design.loadPref(this));   // palette BEFORE any view is built
        super.onCreate(b);
        setContentView(R.layout.pm_blockexplorer_activity_main);

        Language.apply(findViewById(R.id.pm_blockexplorer_main));
        tipChip = findViewById(R.id.pm_blockexplorer_tipChip);
        search = findViewById(R.id.pm_blockexplorer_search);
        goBtn = findViewById(R.id.pm_blockexplorer_goBtn);
        pairingBanner = findViewById(R.id.pm_blockexplorer_pairingBanner);
        content = findViewById(R.id.pm_blockexplorer_content);

        // chrome styling that XML can't do (rounded shapes + the active palette)
        applyChrome();

        applyInsets();
        buildHome();

        goBtn.setOnClickListener(v -> doSearch());
        search.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); return true; }
            return false;
        });
        Button openNode = findViewById(R.id.pm_blockexplorer_openNodeBtn);
        openNode.setOnClickListener(v -> openMinimaCore());
        openNode.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.ACCENT));
        openNode.setTextColor(Design.ON_ACCENT);

        backCb = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { popPage(); }
        };
        getOnBackPressedDispatcher().addCallback(this, backCb);

        node = new NodeApi(this, this::onPaired);

        notifyReceiver = new DirectNodeEvents.Listener() {
            @Override public void onEvent(JSONObject nodeEvent) {
                try {
                    String event = nodeEvent.optString("event", "");
                    if ("NEWBLOCK".equals(event)) requestRefresh();
                } catch (Exception ignored) {}
            }
        };
        // Subscribed only while this screen is visible.

        requestRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (notifyReceiver != null) try { DirectNodeEvents.unsubscribe(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
    }

    /** Paint the XML chrome with the active palette (XML ships the dark defaults). */
    private void applyChrome() {
        findViewById(R.id.pm_blockexplorer_main).setBackgroundColor(Design.BG);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Design.BG));
        getWindow().setStatusBarColor(Design.CARD);
        getWindow().setNavigationBarColor(Design.BG);

        View header = findViewById(R.id.pm_blockexplorer_header);
        header.setBackgroundColor(Design.CARD);
        ((TextView) findViewById(R.id.pm_blockexplorer_title)).setTextColor(Design.ACCENT);
        tipChip.setTextColor(Design.GREEN);

        // theme toggle lives in the header, after the tip chip
        TextView theme = new TextView(this);
        Language.setText(theme, Design.DARK ? "☀" : "☾");
        Language.description(theme, Design.DARK ? Language.msg(l10n_future_light_theme) : Language.msg(l10n_future_dark_theme));
        theme.setTextColor(Design.DIM);
        theme.setTextSize(17f);
        theme.setPadding(dp(10), dp(2), dp(4), dp(2));
        theme.setOnClickListener(v -> {
            Design.savePref(this, !Design.DARK);
            recreate();
        });
        ((LinearLayout) header).addView(theme);

        View searchRow = findViewById(R.id.pm_blockexplorer_searchRow);
        searchRow.setBackgroundColor(Design.CARD);
        searchRow.setPadding(dp(14), 0, dp(14), dp(12));
        search.setBackground(Design.card(this, Design.CARD_2, 12));
        search.setTextColor(Design.TEXT);
        search.setHintTextColor(Design.DIM_2);
        goBtn.setBackground(Design.card(this, Design.ACCENT, 12));
        goBtn.setTextColor(Design.ON_ACCENT);
        LinearLayout.LayoutParams gl = (LinearLayout.LayoutParams) goBtn.getLayoutParams();
        gl.setMarginStart(dp(8));
        goBtn.setLayoutParams(gl);

        pairingBanner.setBackgroundColor(Design.CARD);
        ((TextView) findViewById(R.id.pm_blockexplorer_pairTitle)).setTextColor(Design.ACCENT);
        ((TextView) findViewById(R.id.pm_blockexplorer_pairSub)).setTextColor(Design.DIM);
    }

    private void applyInsets() {
        final View root = findViewById(R.id.pm_blockexplorer_main);
        final View header = findViewById(R.id.pm_blockexplorer_header);
        final int headerTop = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            content.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), root);
        wic.setAppearanceLightStatusBars(!Design.DARK);
        wic.setAppearanceLightNavigationBars(!Design.DARK);
    }

    // ================================================================== pairing

    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) requestRefresh();
    }

    private void openMinimaCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, Language.msg(l10n_history_core_missing), Toast.LENGTH_LONG).show();
    }

    // ================================================================== home page

    private void buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        // --- stat tiles row
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(dp(12), dp(12), dp(12), dp(4));
        tileTip = statTile(stats, Language.msg(l10n_explorer_tip_height), true);
        tileSpeed = statTile(stats, Language.msg(l10n_explorer_block_time), false);
        tileDb = statTile(stats, Language.msg(l10n_explorer_txpow_db), false);
        home.addView(stats);

        // --- section label + status
        LinearLayout secRow = new LinearLayout(this);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        secRow.setGravity(Gravity.CENTER_VERTICAL);
        secRow.setPadding(dp(18), dp(10), dp(18), dp(4));
        TextView sec = new TextView(this);
        Language.setText(sec, Language.msg(l10n_explorer_latest));
        sec.setTextColor(Design.DIM);
        sec.setTextSize(11f);
        sec.setLetterSpacing(0.12f);
        sec.setTypeface(Typeface.DEFAULT_BOLD);
        sec.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        secRow.addView(sec);
        listStatus = new TextView(this);
        Language.setText(listStatus, "");
        listStatus.setTextColor(Design.DIM_2);
        listStatus.setTextSize(11f);
        secRow.addView(listStatus);
        home.addView(secRow);

        // --- block list
        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BlockAdapter();
        recycler.setAdapter(adapter);
        recycler.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        home.addView(recycler);

        homeView = home;
        content.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView statTile(LinearLayout parent, CharSequence label, boolean hero) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(hero
                ? Design.outlinedCard(this, Design.CARD, Design.ACCENT, 14)
                : Design.card(this, Design.CARD, 14));
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(8));
        tile.setLayoutParams(lp);

        TextView lab = new TextView(this);
        Language.setText(lab, label);
        lab.setTextColor(Design.DIM_2);
        lab.setTextSize(10f);
        lab.setLetterSpacing(0.1f);
        tile.addView(lab);

        TextView val = new TextView(this);
        Language.setText(val, "—");
        val.setTextColor(hero ? Design.ACCENT : Design.TEXT);
        val.setTextSize(17f);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tile.addView(val);

        parent.addView(tile);
        return val;
    }

    // ================================================================== chain loading

    /** Coalesce NEWBLOCK / resume / pairing bursts into one refresh. */
    private void requestRefresh() {
        ui.removeCallbacks(refreshTask);
        ui.postDelayed(refreshTask, 400);
    }

    private void refreshTip() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                onPaired(true);
                JSONObject r = j.optJSONObject("response");
                if (r == null) return;
                long newTip;
                try { newTip = Long.parseLong(r.optString("block", "0")); } catch (Exception e) { return; }
                if (newTip <= 0) return;

                boolean first = tipHeight == 0;
                long gap = newTip - tipHeight;
                tipHeight = newTip;
                Language.setText(tipChip, "● " + Util.groupNum(tipHeight));
                Language.setText(tileTip, Util.groupNum(tipHeight));

                if (first || blocks.isEmpty()) {
                    reloadFromTip();
                } else if (gap > 0) {
                    if (gap <= MAX_LIVE_GAP) prependNewBlocks(gap);
                    else reloadFromTip();       // fell far behind (app slept) — start fresh
                }
                fetchStats();
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) return;   // banner already up
                if (blocks.isEmpty()) Language.setText(listStatus, Language.msg(l10n_explorer_unreachable));
            }
        });
    }

    private void fetchStats() {
        node.cmd("status", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject chain = j.optJSONObject("response") != null
                        ? j.optJSONObject("response").optJSONObject("chain") : null;
                if (chain == null) return;
                try {
                    double speed = Double.parseDouble(chain.optString("speed", "0"));
                    if (speed > 0) Language.setText(tileSpeed, Language.msg(l10n_explorer_seconds, String.format(java.util.Locale.ENGLISH, "%.1f", 1.0 / speed)));
                } catch (Exception ignored) {}
            }
            @Override public void onError(String m) {}
        });
        node.cmd("txpow action:info", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r == null) return;
                JSONObject db = r.optJSONObject("txpowdb");
                if (db != null) Language.setText(tileDb, Language.msg(l10n_explorer_txn_total, Util.groupNum(db.optLong("size", 0))));
            }
            @Override public void onError(String m) {}
        });
    }

    private void reloadFromTip() {
        blocks.clear();
        blockCache.evictAll();
        endOfChain = false;
        lowestLoaded = tipHeight + 1;
        adapter.notifyDataSetChanged();
        loadOlder();
    }

    /** Fetch the next PAGE blocks below the lowest one we have, one at a time, gently. */
    private void loadOlder() {
        if (loading || endOfChain || tipHeight == 0) return;
        loading = true;
        Language.setText(listStatus, Language.msg(l10n_explorer_loading));
        loadChain(lowestLoaded - 1, PAGE, false);
    }

    /** NEWBLOCK arrived: fetch the small gap above the current top and prepend. */
    private void prependNewBlocks(long gap) {
        if (loading) return;
        loading = true;
        loadChain(tipHeight, (int) gap, true);
    }

    /** Sequential walker. Descends from `height` for `count` blocks; prepend=true inserts at the top. */
    private void loadChain(final long height, final int count, final boolean prepend) {
        if (count <= 0 || height <= 0) { finishLoad(); return; }

        JSONObject cached = blockCache.get(height);
        if (cached != null) { acceptBlock(cached, height, count, prepend); return; }

        node.cmd("txpow block:" + height, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject txpow = j.optJSONObject("response");
                if (txpow == null) {
                    // node replied but without a block — treat like the floor
                    if (!prepend) endOfChain = true;
                    finishLoad();
                    return;
                }
                acceptBlock(txpow, height, count, prepend);
            }
            @Override public void onError(String m) {
                // "TxPoW not found @ height N" -> below the retained/archive floor
                if (!prepend) endOfChain = true;
                finishLoad();
            }
        });
    }

    private void acceptBlock(JSONObject txpow, long height, int count, boolean prepend) {
        blockCache.put(height, txpow);
        if (prepend) {
            blocks.add(0, txpow);
            adapter.notifyItemInserted(0);
            recycler.scrollToPosition(0);
        } else {
            blocks.add(txpow);
            lowestLoaded = height;
            adapter.notifyItemInserted(blocks.size() - 1);
        }
        if (count - 1 <= 0 || height - 1 <= 0) { finishLoad(); return; }
        // small gap between fetches — never hammer the node
        final long next = prepend ? nextPrependHeight(height) : height - 1;
        if (next <= 0) { finishLoad(); return; }
        ui.postDelayed(() -> loadChain(next, count - 1, prepend), FETCH_GAP_MS);
    }

    /** When prepending we descend from the tip toward the previous top block. */
    private long nextPrependHeight(long current) {
        long prevTop = blocks.size() > 1 ? blockHeight(blocks.get(1)) : 0;
        long next = current - 1;
        return next > prevTop ? next : 0;
    }

    private void finishLoad() {
        loading = false;
        Language.setText(listStatus, endOfChain ? Language.msg(l10n_explorer_chain_end) : "");
        adapter.notifyDataSetChanged();
    }

    private static long blockHeight(JSONObject txpow) {
        JSONObject h = txpow.optJSONObject("header");
        if (h == null) return 0;
        try { return Long.parseLong(h.optString("block", "0")); } catch (Exception e) { return 0; }
    }

    // ================================================================== search

    private void doSearch() {
        final String q = search.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();

        if (Util.isAllDigits(q)) {                       // block number
            fetchAndShowBlock(Long.parseLong(q));
        } else if (Util.isHex(q) || q.startsWith("Mx")) {
            // A 0x 64-hex string can be a TxPoW id OR an address — try txpowid first, fall back.
            if (q.startsWith("Mx")) searchAddress(q);
            else searchTxpowThenAddress(q);
        } else {
            Toast.makeText(this, Language.msg(l10n_explorer_search_help), Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchAndShowBlock(long height) {
        JSONObject cached = blockCache.get(height);
        if (cached != null) { showBlockDetail(cached); return; }
        toastProgress(Language.msg(l10n_explorer_lookup_block, Util.groupNum(height)));
        node.cmd("txpow block:" + height, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject txpow = j.optJSONObject("response");
                if (txpow == null) { notFound(Language.msg(l10n_explorer_block, height)); return; }
                blockCache.put(height, txpow);
                showBlockDetail(txpow);
            }
            @Override public void onError(String m) { notFound(Language.msg(l10n_explorer_block, height)); }
        });
    }

    private void searchTxpowThenAddress(final String q) {
        toastProgress(Language.msg(l10n_explorer_searching));
        JSONObject cached = txpowCache.get(q);
        if (cached != null) { showTxpowSmart(cached); return; }
        node.cmd("txpow txpowid:" + q, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject txpow = j.optJSONObject("response");
                if (txpow == null) { searchAddress(q); return; }
                txpowCache.put(q, txpow);
                showTxpowSmart(txpow);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) return;
                searchAddress(q);   // not a txpow — maybe it's an address
            }
        });
    }

    private void searchAddress(final String addr) {
        node.cmd("txpow address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = j.optJSONArray("response");
                if (arr == null || arr.length() == 0) { notFound(Util.shorten(addr)); return; }
                showAddressResults(addr, arr);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) return;
                notFound(Util.shorten(addr));
            }
        });
    }

    /** A found TxPoW routes to the right page: blocks get the block page, txns the txn page. */
    private void showTxpowSmart(JSONObject txpow) {
        if (txpow.optBoolean("isblock", false)) showBlockDetail(txpow);
        else showTxnDetail(txpow);
    }

    private void notFound(CharSequence what) {
        Toast.makeText(this, Language.msg(l10n_explorer_not_found, what), Toast.LENGTH_LONG).show();
    }

    private void toastProgress(CharSequence msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    // ================================================================== page stack

    private void pushPage(View page) {
        homeView.setVisibility(View.GONE);
        for (View v : pageStack) v.setVisibility(View.GONE);
        pageStack.add(page);
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backCb.setEnabled(true);
    }

    private void popPage() {
        if (pageStack.isEmpty()) return;
        View top = pageStack.remove(pageStack.size() - 1);
        content.removeView(top);
        if (pageStack.isEmpty()) {
            homeView.setVisibility(View.VISIBLE);
            backCb.setEnabled(false);
        } else {
            pageStack.get(pageStack.size() - 1).setVisibility(View.VISIBLE);
        }
    }

    /** Scrollable page skeleton with a back row + title. Returns the body container to fill. */
    private LinearLayout newPage(CharSequence backLabel, CharSequence title) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Design.BG);

        LinearLayout backRow = new LinearLayout(this);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        backRow.setPadding(dp(14), dp(12), dp(14), dp(6));
        TextView back = new TextView(this);
        Language.setText(back, Language.join("‹ ", backLabel));
        back.setTextColor(Design.ACCENT);
        back.setTextSize(14f);
        back.setPadding(dp(4), dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> popPage());
        backRow.addView(back);
        TextView t = new TextView(this);
        Language.setText(t, title);
        t.setTextColor(Design.DIM);
        t.setTextSize(13f);
        backRow.addView(t);
        page.addView(backRow);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), 0, dp(12), dp(20));
        sv.addView(body);
        page.addView(sv);

        pushPage(page);
        return body;
    }

    // ================================================================== block detail page

    private void showBlockDetail(JSONObject txpow) {
        JSONObject header = txpow.optJSONObject("header");
        long height = blockHeight(txpow);
        LinearLayout body = newPage(Language.msg(l10n_explorer_back), Language.msg(l10n_explorer_block_detail));

        // hero card
        LinearLayout hero = sectionCard(body, true);
        TextView big = new TextView(this);
        Language.setText(big, "#" + Util.groupNum(height));
        big.setTextColor(Design.ACCENT);
        big.setTextSize(28f);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hero.addView(big);
        long ms = Util.timeMilli(header);
        TextView when = new TextView(this);
        Language.setText(when, Language.join(Util.dateTime(ms), ms > 0 ? Language.join("   ·   ", Util.relative(ms)) : ""));
        when.setTextColor(Design.DIM);
        when.setTextSize(12f);
        hero.addView(when);

        // facts card
        LinearLayout facts = sectionCard(body, false);
        copyRow(facts, Language.msg(l10n_history_txpow), txpow.optString("txpowid", ""));
        kv(facts, Language.msg(l10n_explorer_superblock), txpow.optString("superblock", "—"));
        kv(facts, Language.msg(l10n_explorer_size), Language.msg(l10n_explorer_bytes, txpow.optString("size", "—")));
        kv(facts, Language.msg(l10n_explorer_burn), Util.tidyAmount(txpow.optString("burn", "0")) + " MINIMA");
        if (header != null) {
            kv(facts, Language.msg(l10n_explorer_difficulty), Util.shorten(header.optString("blkdiff", "—")));
            kv(facts, Language.msg(l10n_explorer_cascade_levels), header.optString("cascadelevels", "—"));
            JSONArray sp = header.optJSONArray("superparents");
            if (sp != null && sp.length() > 0) {
                JSONObject p0 = sp.optJSONObject(0);
                if (p0 != null) {
                    final String parent = p0.optString("parent", "");
                    if (!parent.isEmpty()) linkRow(facts, Language.msg(l10n_explorer_parent_block), parent,
                            v -> searchTxpowThenAddress(parent));
                }
            }
        }

        // transactions card
        LinearLayout txs = sectionCard(body, false);
        sectionHeader(txs, Language.msg(l10n_explorer_transactions));
        JSONObject bodyJson = txpow.optJSONObject("body");
        JSONArray txnlist = bodyJson != null ? bodyJson.optJSONArray("txnlist") : null;
        int n = txnlist != null ? txnlist.length() : 0;
        boolean carriesOwn = txpow.optBoolean("istransaction", false);
        if (n == 0 && !carriesOwn) {
            TextView none = new TextView(this);
            Language.setText(none, Language.msg(l10n_explorer_no_transactions));
            none.setTextColor(Design.DIM_2);
            none.setTextSize(13f);
            none.setPadding(0, dp(4), 0, dp(4));
            txs.addView(none);
        }
        if (carriesOwn) {
            linkRow(txs, Language.msg(l10n_explorer_carried), txpow.optString("txpowid", ""),
                    v -> showTxnDetail(txpow));
        }
        for (int i = 0; i < n; i++) {
            final String txid = txnlist.optString(i, "");
            if (txid.isEmpty()) continue;
            linkRow(txs, null, txid, v -> fetchAndShowTxn(txid));
        }

        rawJsonSection(body, txpow);
    }

    private void fetchAndShowTxn(final String txid) {
        JSONObject cached = txpowCache.get(txid);
        if (cached != null) { showTxnDetail(cached); return; }
        toastProgress(Language.msg(l10n_explorer_loading_transaction));
        node.cmd("txpow txpowid:" + txid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject txpow = j.optJSONObject("response");
                if (txpow == null) { notFound(Util.shorten(txid)); return; }
                txpowCache.put(txid, txpow);
                showTxnDetail(txpow);
            }
            @Override public void onError(String m) { notFound(Util.shorten(txid)); }
        });
    }

    // ================================================================== transaction detail page

    private void showTxnDetail(JSONObject txpow) {
        JSONObject header = txpow.optJSONObject("header");
        LinearLayout body = newPage(Language.msg(l10n_explorer_back), Language.msg(l10n_history_transaction));

        // hero card
        LinearLayout hero = sectionCard(body, true);
        TextView big = new TextView(this);
        Language.setText(big, Language.msg(l10n_history_transaction));
        big.setTextColor(Design.TEXT);
        big.setTextSize(20f);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hero.addView(big);
        long ms = Util.timeMilli(header);
        TextView when = new TextView(this);
        Language.setText(when, Language.join(Util.dateTime(ms), ms > 0 ? Language.join("   ·   ", Util.relative(ms)) : ""));
        when.setTextColor(Design.DIM);
        when.setTextSize(12f);
        hero.addView(when);
        final TextView conf = new TextView(this);
        Language.setText(conf, Language.msg(l10n_explorer_confirm_pending));
        conf.setTextColor(Design.GREEN);
        conf.setTextSize(12f);
        conf.setTypeface(Typeface.MONOSPACE);
        conf.setPadding(0, dp(4), 0, 0);
        hero.addView(conf);

        LinearLayout facts = sectionCard(body, false);
        copyRow(facts, Language.msg(l10n_history_txpow), txpow.optString("txpowid", ""));
        kv(facts, Language.msg(l10n_explorer_size), Language.msg(l10n_explorer_bytes, txpow.optString("size", "—")));
        kv(facts, Language.msg(l10n_explorer_burn), Util.tidyAmount(txpow.optString("burn", "0")) + " MINIMA");
        kv(facts, Language.msg(l10n_explorer_is_block), txpow.optBoolean("isblock", false) ? Language.msg(l10n_explorer_also_block) : Language.msg(l10n_explorer_no));

        JSONObject bodyJson = txpow.optJSONObject("body");
        JSONObject txn = bodyJson != null ? bodyJson.optJSONObject("txn") : null;

        if (txn != null) {
            coinSection(body, Language.msg(l10n_explorer_inputs), txn.optJSONArray("inputs"), Design.RED, "−");
            coinSection(body, Language.msg(l10n_explorer_outputs), txn.optJSONArray("outputs"), Design.GREEN, "+");

            JSONArray state = txn.optJSONArray("state");
            if (state != null && state.length() > 0) {
                LinearLayout st = sectionCard(body, false);
                sectionHeader(st, Language.msg(l10n_explorer_state));
                for (int i = 0; i < state.length(); i++) {
                    JSONObject s = state.optJSONObject(i);
                    if (s == null) continue;
                    copyRow(st, Language.msg(l10n_explorer_port, s.optString("port", "?")), s.optString("data", ""));
                }
            }
        }

        rawJsonSection(body, txpow);

        // confirmations, async — fill into the hero card when the node answers
        node.cmd("txpow onchain:" + txpow.optString("txpowid", ""), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null && r.optBoolean("found", false)) {
                    Language.setText(conf, Language.msg(l10n_explorer_on_chain, r.optString("block", "?"), r.optString("confirmations", "?")));
                    conf.setTextColor(Design.GREEN);
                } else {
                    Language.setText(conf, Language.msg(l10n_explorer_off_chain));
                    conf.setTextColor(Design.DIM_2);
                }
            }
            @Override public void onError(String m) { Language.setText(conf, ""); }
        });
    }

    private void coinSection(LinearLayout parent, CharSequence title, JSONArray coins, int color, String sign) {
        LinearLayout card = sectionCard(parent, false);
        sectionHeader(card, Language.join(title, coins != null ? "  ·  " + coins.length() : ""));
        if (coins == null || coins.length() == 0) {
            TextView none = new TextView(this);
            Language.setText(none, Language.msg(l10n_explorer_none));
            none.setTextColor(Design.DIM_2);
            none.setTextSize(13f);
            card.addView(none);
            return;
        }
        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null) continue;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView amt = new TextView(this);
            Language.setText(amt, sign + Util.coinAmount(c) + "  " + Util.tokenLabel(c));
            amt.setTextColor(color);
            amt.setTextSize(15f);
            amt.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(amt);

            final String addr = c.optString("miniaddress", c.optString("address", ""));
            TextView ad = new TextView(this);
            Language.setText(ad, Language.msg(l10n_explorer_tap_copy, Util.shorten(addr)));
            ad.setTextColor(Design.DIM);
            ad.setTextSize(12f);
            ad.setTypeface(Typeface.MONOSPACE);
            ad.setOnClickListener(v -> copyToClip(Language.msg(l10n_history_address), addr));
            row.addView(ad);

            card.addView(row);
            if (i < coins.length() - 1) divider(card);
        }
    }

    // ================================================================== address results page

    private void showAddressResults(String addr, JSONArray arr) {
        LinearLayout body = newPage(Language.msg(l10n_explorer_back), Language.msg(l10n_history_address));

        LinearLayout hero = sectionCard(body, true);
        TextView t = new TextView(this);
        Language.setText(t, Util.shorten(addr));
        t.setTextColor(Design.TEXT);
        t.setTextSize(16f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setOnClickListener(v -> copyToClip(Language.msg(l10n_history_address), addr));
        hero.addView(t);
        TextView cnt = new TextView(this);
        Language.setText(cnt, Language.msg(l10n_explorer_address_count, arr.length()));
        cnt.setTextColor(Design.DIM);
        cnt.setTextSize(12f);
        hero.addView(cnt);

        LinearLayout list = sectionCard(body, false);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject txpow = arr.optJSONObject(i);
            if (txpow == null) continue;
            JSONObject header = txpow.optJSONObject("header");
            boolean isBlock = txpow.optBoolean("isblock", false);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView kind = new TextView(this);
            Language.setText(kind, isBlock ? Language.msg(l10n_explorer_block_badge) : Language.msg(l10n_explorer_txn_badge));
            kind.setTextColor(isBlock ? Design.ACCENT : Design.BLUE);
            kind.setTextSize(10f);
            kind.setTypeface(Typeface.DEFAULT_BOLD);
            kind.setBackground(Design.pill(this, Design.CARD_2));
            kind.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.addView(kind);

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(10), 0, 0, 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView id = new TextView(this);
            Language.setText(id, Util.shorten(txpow.optString("txpowid", "")));
            id.setTextColor(Design.TEXT);
            id.setTextSize(13f);
            id.setTypeface(Typeface.MONOSPACE);
            mid.addView(id);
            TextView sub = new TextView(this);
            long ms = Util.timeMilli(header);
            String blockNo = header != null ? header.optString("block", "?") : "?";
            Language.setText(sub, Language.join(Language.msg(l10n_explorer_block, blockNo), ms > 0 ? Language.join("  ·  ", Util.relative(ms)) : ""));
            sub.setTextColor(Design.DIM_2);
            sub.setTextSize(11f);
            mid.addView(sub);
            row.addView(mid);

            TextView chev = new TextView(this);
            Language.setText(chev, "›");
            chev.setTextColor(Design.DIM_2);
            chev.setTextSize(18f);
            row.addView(chev);

            row.setOnClickListener(v -> showTxpowSmart(txpow));
            list.addView(row);
            if (i < arr.length() - 1) divider(list);
        }
    }

    // ================================================================== shared page widgets

    private LinearLayout sectionCard(LinearLayout parent, boolean hero) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(hero
                ? Design.outlinedCard(this, Design.CARD, Design.DIVIDER, 16)
                : Design.card(this, Design.CARD, 16));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        parent.addView(card);
        return card;
    }

    private void sectionHeader(LinearLayout parent, CharSequence text) {
        TextView h = new TextView(this);
        Language.setText(h, text);
        h.setTextColor(Design.DIM);
        h.setTextSize(10f);
        h.setLetterSpacing(0.12f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(6));
        parent.addView(h);
    }

    private void kv(LinearLayout parent, CharSequence key, CharSequence value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView k = new TextView(this);
        Language.setText(k, key);
        k.setTextColor(Design.DIM);
        k.setTextSize(13f);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(k);
        TextView v = new TextView(this);
        Language.setText(v, value);
        v.setTextColor(Design.TEXT);
        v.setTextSize(13f);
        v.setGravity(Gravity.END);
        row.addView(v);
        parent.addView(row);
    }

    private void copyRow(LinearLayout parent, CharSequence key, final String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView k = new TextView(this);
        Language.setText(k, Language.msg(l10n_explorer_tap_copy, key));
        k.setTextColor(Design.DIM);
        k.setTextSize(11f);
        row.addView(k);
        TextView v = new TextView(this);
        Language.setText(v, value);
        v.setTextColor(Design.TEXT);
        v.setTextSize(12f);
        v.setTypeface(Typeface.MONOSPACE);
        row.addView(v);
        row.setOnClickListener(view -> copyToClip(key, value));
        parent.addView(row);
    }

    private void linkRow(LinearLayout parent, CharSequence label, String id, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        if (label != null) {
            TextView l = new TextView(this);
            Language.setText(l, label);
            l.setTextColor(Design.DIM);
            l.setTextSize(11f);
            row.addView(l);
        }
        TextView v = new TextView(this);
        Language.setText(v, Util.shorten(id) + "  ›");
        v.setTextColor(Design.ACCENT);
        v.setTextSize(13f);
        v.setTypeface(Typeface.MONOSPACE);
        row.addView(v);
        row.setOnClickListener(click);
        parent.addView(row);
    }

    private void divider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(Design.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        d.setLayoutParams(lp);
        parent.addView(d);
    }

    /** Collapsible raw-JSON viewer at the bottom of every detail page. */
    private void rawJsonSection(LinearLayout parent, final JSONObject json) {
        final LinearLayout card = sectionCard(parent, false);
        final TextView toggle = new TextView(this);
        Language.setText(toggle, Language.msg(l10n_explorer_raw_show));
        toggle.setTextColor(Design.DIM);
        toggle.setTextSize(13f);
        card.addView(toggle);

        final HorizontalScrollView[] holder = new HorizontalScrollView[1];
        toggle.setOnClickListener(v -> {
            if (holder[0] == null) {
                String pretty;
                try { pretty = json.toString(2); } catch (Exception e) { pretty = json.toString(); }
                TextView raw = new TextView(this);
                Language.setText(raw, pretty);
                raw.setTextColor(Design.DIM);
                raw.setTextSize(10f);
                raw.setTypeface(Typeface.MONOSPACE);
                raw.setOnLongClickListener(view -> { copyToClip("TxPoW JSON", raw.getText().toString()); return true; });
                HorizontalScrollView hsv = new HorizontalScrollView(this);
                hsv.addView(raw);
                hsv.setPadding(0, dp(8), 0, 0);
                holder[0] = hsv;
                card.addView(hsv);
                Language.setText(toggle, Language.msg(l10n_explorer_raw_hide));
            } else {
                card.removeView(holder[0]);
                holder[0] = null;
                Language.setText(toggle, Language.msg(l10n_explorer_raw_show));
            }
        });
    }

    private void copyToClip(CharSequence label, String value) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, Language.msg(l10n_history_copied), Toast.LENGTH_SHORT).show();
    }

    // ================================================================== block list adapter

    private static final int VT_BLOCK = 0, VT_FOOTER = 1;

    private class BlockAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override public int getItemCount() { return blocks.size() + 1; }   // +1 footer

        @Override public int getItemViewType(int pos) { return pos < blocks.size() ? VT_BLOCK : VT_FOOTER; }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VT_FOOTER) {
                TextView more = new TextView(MainActivity.this);
                more.setGravity(Gravity.CENTER);
                more.setTextSize(13f);
                more.setPadding(dp(16), dp(14), dp(16), dp(14));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(12), dp(6), dp(12), dp(10));
                more.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(more) {};
            }

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Design.card(MainActivity.this, Design.CARD, 14));
            row.setPadding(dp(14), dp(12), dp(12), dp(12));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), dp(4), dp(12), dp(4));
            row.setLayoutParams(lp);

            LinearLayout left = new LinearLayout(MainActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView num = new TextView(MainActivity.this);
            num.setTextSize(16f);
            num.setTextColor(Design.TEXT);
            num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            TextView sub = new TextView(MainActivity.this);
            sub.setTextSize(11f);
            sub.setTextColor(Design.DIM_2);
            sub.setTypeface(Typeface.MONOSPACE);
            left.addView(num);
            left.addView(sub);
            row.addView(left);

            TextView pillTx = new TextView(MainActivity.this);
            pillTx.setTextSize(10f);
            pillTx.setTypeface(Typeface.DEFAULT_BOLD);
            pillTx.setPadding(dp(10), dp(4), dp(10), dp(4));
            row.addView(pillTx);

            TextView chev = new TextView(MainActivity.this);
            Language.setText(chev, "  ›");
            chev.setTextColor(Design.DIM_2);
            chev.setTextSize(18f);
            row.addView(chev);

            BlockVH vh = new BlockVH(row);
            vh.num = num; vh.sub = sub; vh.pill = pillTx;
            return vh;
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (getItemViewType(pos) == VT_FOOTER) {
                TextView more = (TextView) holder.itemView;
                if (endOfChain) {
                    Language.setText(more, Language.join("· ", Language.msg(l10n_explorer_chain_end), " ·"));
                    more.setTextColor(Design.DIM_2);
                    more.setBackground(null);
                    more.setOnClickListener(null);
                } else if (loading) {
                    Language.setText(more, Language.msg(l10n_explorer_loading));
                    more.setTextColor(Design.DIM);
                    more.setBackground(null);
                    more.setOnClickListener(null);
                } else {
                    Language.setText(more, Language.msg(l10n_explorer_older));
                    more.setTextColor(Design.ACCENT);
                    more.setBackground(Design.card(MainActivity.this, Design.CARD, 14));
                    more.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); loadOlder(); });
                }
                return;
            }

            BlockVH vh = (BlockVH) holder;
            final JSONObject txpow = blocks.get(pos);
            JSONObject header = txpow.optJSONObject("header");
            long height = blockHeight(txpow);
            long ms = Util.timeMilli(header);

            JSONObject bodyJson = txpow.optJSONObject("body");
            JSONArray txnlist = bodyJson != null ? bodyJson.optJSONArray("txnlist") : null;
            int n = (txnlist != null ? txnlist.length() : 0) + (txpow.optBoolean("istransaction", false) ? 1 : 0);

            Language.setText(vh.num, "#" + Util.groupNum(height));
            Language.setText(vh.sub, Language.join(Util.shorten(txpow.optString("txpowid", "")), "  ·  ", Util.relative(ms)));
            if (n > 0) {
                Language.setText(vh.pill, Language.msg(l10n_explorer_transactions_badge, n));
                vh.pill.setTextColor(Design.ACCENT);
                vh.pill.setBackground(Design.pill(MainActivity.this, Design.ACCENT_SOFT));
            } else {
                Language.setText(vh.pill, Language.msg(l10n_explorer_empty));
                vh.pill.setTextColor(Design.DIM_2);
                vh.pill.setBackground(Design.pill(MainActivity.this, Design.CARD_2));
            }
            vh.itemView.setOnClickListener(v -> showBlockDetail(txpow));
        }

        class BlockVH extends RecyclerView.ViewHolder {
            TextView num, sub, pill;
            BlockVH(View v) { super(v); }
        }
    }

    private int dp(int v) { return Design.dp(this, v); }

    @Override protected void onStart() {
        super.onStart();
        DirectNodeEvents.subscribe(notifyReceiver, "NEWBLOCK", "NEWBALANCE");
    }
    @Override protected void onStop() {
        DirectNodeEvents.unsubscribe(notifyReceiver);
        super.onStop();
    }
}
