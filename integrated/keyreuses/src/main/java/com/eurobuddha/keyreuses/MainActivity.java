package com.eurobuddha.keyreuses;
import com.eurobuddha.futurecashnext.KeyAudit;
import com.eurobuddha.futurecashnext.AuditApi;
import com.eurobuddha.futurecashnext.MinimaAddress;
import com.eurobuddha.minimacore.i18n.Language;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.text.HtmlCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KeyUses — audits this node's keys (classic 64/3 and block-as-keyuses 128/4) for
 * one-time-signature (Winternitz) re-use.
 *
 * <p>Native port of the KeyUses MiniDapp ({@code mds/keyuses/minidapp/index.html} v0.1.55). One
 * screen, one node command ({@code keys action:list}), addresses derived on-device, and two
 * parallel HTTPS lookups against the hosted archive index.
 *
 * <p>The dapp is a single vertically-scrolling column, so the whole UI is built programmatically
 * into the layout's container — the fleet idiom.
 */
public class MainActivity extends AppCompatActivity {

    private static final String NODE_PACKAGE = "com.eurobuddha.pandamonium";
    private static final String VERSION = "0.1.4";


    private AuditApi api;

    private LinearLayout container;
    private LinearLayout pairingBanner;
    private LinearLayout runButton;
    private TextView runLabel;
    private ProgressBar runSpinner;
    private TextView statusBox;
    private LinearLayout resultsBox;

    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Deliberately NOT FLAG_SECURE. This screen shows public keys and addresses only — no seed,
        // no private key, nothing that can move funds, and the audit already sends exactly this
        // data to the backend. Sharing a screenshot of the verdict (to an exchange, to support, in
        // a disclosure) is a first-class use of this app, so blocking capture would cost more than
        // it protects. FLAG_SECURE belongs in the node app, which renders the seed phrase.
        setContentView(R.layout.pm_keyreuses_activity_main);
        container = findViewById(R.id.pm_keyreuses_container);

        final View scroll = findViewById(R.id.pm_keyreuses_scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        includeWallet=getSharedPreferences("keyuses_settings",MODE_PRIVATE).getBoolean("include_wallet",false);
        api = new AuditApi(this);

        buildUi();
        Language.addListener(languageChanged);

        // A miscompiled SHA3 would derive wrong addresses, and a wrong address looks like "never
        // spent" — i.e. it would report SAFE. Refuse to run rather than reassure wrongly.
        if (!derivationValid) {
            runButton.setEnabled(false);
            runButton.setAlpha(0.5f);
            errorText=Copy::selfTest; showError(errorText.get());
        }
    }

    @Override
    protected void onDestroy() {
        generation++;
        auditWorker.shutdownNow();
        Language.removeListener(languageChanged);
        if (api != null) api.onDestroy();
        super.onDestroy();
    }

    /* ====================== the audit ====================== */

    private final java.util.concurrent.ExecutorService auditWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> preparation;
    private int generation;
    private boolean includeWallet;
    private android.widget.CheckBox walletToggle;
    private AuditSelection selection;
    private KeyAudit.Result lastResult;
    private long lastTip;
    private int prepared;
    private java.util.function.Supplier<String> errorText;
    private final boolean derivationValid=Sha3.selfTest();
    com.eurobuddha.wallet.NodeAuditKeys auditKeys() { return com.eurobuddha.wallet.NodeAuditKeys.NODE; }
    private final Runnable languageChanged = () -> {
        if (container == null) return;
        container.removeAllViews(); buildUi(); setBusy(busy);
        if(errorText!=null) showError(errorText.get());
        if(lastResult!=null) renderResults(lastResult,lastTip);
    };
    String walletKeys() { return com.eurobuddha.wallet.PublicAuditKeys.snapshot(this).toString(); }
    private boolean live(int request) {
        return request==generation && !isFinishing() && !isDestroyed();
    }
    @Override protected void onStop() {
        generation++;
        if(preparation!=null) preparation.cancel(true);
        lastResult=null;selection=null;
        if(resultsBox!=null) {resultsBox.removeAllViews();resultsBox.setVisibility(View.GONE);setBusy(false);}
        super.onStop();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }
    private void runAudit() {
        if (busy || !derivationValid) return;
        clearError(); lastResult=null; selection=null; prepared=0;
        setBusy(true);
        resultsBox.removeAllViews(); resultsBox.setVisibility(View.GONE);
        final int request=++generation;
        final boolean walletSelected=includeWallet;
        preparation=auditWorker.submit(() -> {
            try {
                com.eurobuddha.wallet.NodeAuditKeys.Snapshot nodeSnapshot =
                        auditKeys().prepare((done,total) -> runOnUiThread(() -> {
                            if(live(request)) { prepared=done; updateProgress(); }
                        }));
                final String walletSnapshot=walletSelected
                        ? walletKeys() : "[]";
                AuditSelection chosen=new AuditSelection(nodeSnapshot.nodeKeys,nodeSnapshot.derivedKeys,walletSnapshot,walletSelected);
                runOnUiThread(() -> {
                    if(!live(request)) return;
                    selection=chosen;
                    api.audit(chosen.publicKeys,chosen.hexAddresses,new AuditApi.Cb() {
                        @Override public void onResult(List<KeyAudit.Usage> usage,List<KeyAudit.Reuse> reuse,long tip) {
                            if(!live(request)) return;
                            preparation=auditWorker.submit(() -> {
                                try {
                                    if(!auditKeys().current(nodeSnapshot)
                                            || (walletSelected && !walletSnapshot.equals(walletKeys()))) {
                                        auditError(request,Copy::changed); return;
                                    }
                                    KeyAudit.Result result=KeyAudit.join(chosen.keys,usage,reuse,chosen.addresses);
                                    runOnUiThread(() -> {if(live(request)){setBusy(false);renderResults(result,tip);}});
                                } catch(Exception unavailable) {auditError(request,Copy::unavailable);}
                            });
                        }
                        @Override public void onError(String message) {auditError(request,Copy::network);}
                    });
                });
            } catch(Exception unavailable) {auditError(request,Copy::unavailable);}
        });
    }
    private void auditError(int request,java.util.function.Supplier<String> message) {
        runOnUiThread(() -> {if(live(request)){setBusy(false);errorText=message;showError(message.get());}});
    }
    private void updateProgress() {
        if(runLabel!=null) runLabel.setText(busy
                ? (prepared>0 && prepared<96 ? Copy.progress(prepared) : Copy.auditing()) : Copy.run());
    }
    private String sourceLabel(int index) {
        if(selection==null || index<1 || index>selection.origins.size())return "";
        int flags=selection.origins.get(index-1);
        java.util.List<String> names=new ArrayList<>();
        if((flags&AuditSelection.NODE)!=0)names.add(Copy.node());
        if((flags&AuditSelection.CLASSIC)!=0)names.add(Copy.classic());
        if((flags&AuditSelection.BLOCK)!=0)names.add(Copy.block());
        if((flags&AuditSelection.WALLET)!=0)names.add("minimaWallet");
        return String.join(" · ",names);
    }

    /** Group display rows without changing their audit identity or source mapping. */
    private void renderKeySections(LinearLayout table, KeyAudit.Result result) {
        for(int group=0;group<3;group++) {
            java.util.List<KeyAudit.Row> rows=new ArrayList<>();
            for(KeyAudit.Row auditRow:result.rows) {
                int flags=selection.origins.get(auditRow.index-1);
                int rowGroup=flags==AuditSelection.WALLET ? 2
                        : auditRow.family==KeyAudit.Family.BLOCK ? 1 : 0;
                if(rowGroup==group) rows.add(auditRow);
            }
            if(rows.isEmpty())continue;
            LinearLayout section=new LinearLayout(this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setTag("keyuses.section."+group);
            section.setLayoutParams(marginParams(0,group==0?0:24,0,0));
            TextView heading=text(Copy.section(group,rows.size()),15f,Design.ACCENT,Typeface.BOLD);
            heading.setBackground(Design.card(this,Design.ACCENT_SOFT,Design.BORDER,8));
            heading.setPadding(dp(12),dp(14),dp(12),dp(14));
            heading.setLayoutParams(marginParams(0,0,0,12));
            androidx.core.view.ViewCompat.setAccessibilityHeading(heading,true);
            section.addView(heading);
            section.addView(headerRow());
            int displayIndex=0;
            for(KeyAudit.Row auditRow:rows) section.addView(row(auditRow,++displayIndex));
            table.addView(section);
        }
    }

    /* ====================== rendering ====================== */

    private void renderResults(KeyAudit.Result r, long archiveTip) {
        lastResult=r; lastTip=archiveTip;
        resultsBox.removeAllViews();

        if (!r.publicComplete) resultsBox.addView(noticeCard(Copy.incomplete(), Design.WARN));
        resultsBox.addView(text(Copy.scope(r.classicKeys,r.blockKeys),12f,Design.DIM,Typeface.NORMAL));
        if (r.derivationMismatch) {
            resultsBox.addView(noticeCard(Copy.MISMATCH(), Design.RISK));
        }
        if (r.exhaustion) {
            resultsBox.addView(noticeCard(Copy.EXHAUSTION(), Design.WARN));
        }

        resultsBox.addView(verdictCard(r));
        resultsBox.addView(tableCard(r, archiveTip));
        resultsBox.setVisibility(View.VISIBLE);
    }

    private View verdictCard(KeyAudit.Result r) {
        final int color;
        final String title, body;
        switch (r.verdict) {
            case REUSE_RISK:
                color = Design.RISK;
                title = Copy.REUSE_RISK_TITLE();
                body = Copy.reuseRiskBody(num(r.worstReuse));
                break;
            case REUSE_WARN:
                color = Design.WARN;
                title = Copy.REUSE_WARN_TITLE();
                body = Copy.reuseWarnBody(num(r.worstReuse));
                break;
            case RISK_HEURISTIC:
                color = Design.RISK;
                title = Copy.RISK_TITLE();
                body = Copy.RISK_BODY();
                break;
            default:
                color = r.publicComplete ? Design.OK : Design.WARN;
                title = r.publicComplete ? Copy.OK_TITLE() : Copy.partialTitle();
                body = r.publicComplete ? Copy.OK_BODY() : Copy.incomplete();
                break;
        }

        final int soft = color == Design.OK ? Design.OK_SOFT
                : color == Design.WARN ? Design.WARN_SOFT : Design.RISK_SOFT;

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, soft, Design.alpha(color, 0x59), 14));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setLayoutParams(marginParams(0, 0, 0, 14));

        final TextView h = text(title, 19f, color, Typeface.BOLD);
        h.setGravity(Gravity.CENTER);
        card.addView(h);

        final TextView p = html(body, 13.5f, Design.TEXT);
        p.setGravity(Gravity.CENTER);
        p.setPadding(0, dp(8), 0, 0);
        p.setLineSpacing(dp(3), 1f);
        card.addView(p);

        // Confirmed re-use gets the "why this happened" list; the advice there is migrate, not
        // resync better, so it deliberately does NOT show the keyuses recommendation
        // (index.html:288, :297).
        if (r.verdict == KeyAudit.Verdict.REUSE_RISK || r.verdict == KeyAudit.Verdict.REUSE_WARN) {
            card.addView(insetBlock(Copy.WHY_LABEL(), Copy.WHY_LIST(), false));
        } else if (r.verdict == KeyAudit.Verdict.RISK_HEURISTIC) {
            card.addView(insetBlock(Copy.HOW_LABEL(), Copy.HOW_LIST(), false));
            card.addView(insetBlock(Copy.DO_LABEL(), Copy.DO_LIST(), false));
            addRecoBlocks(card, r);
        } else {
            addRecoBlocks(card, r);
        }

        return card;
    }

    /**
     * The keyuses recommendation applies to classic keys only; 128/4 keys get the block-height
     * note instead (index.html:299-314).
     */
    private void addRecoBlocks(LinearLayout card, KeyAudit.Result r) {
        if (r.complete && r.classicKeys > 0 && r.recommendedKeyUses < 262144) {
            card.addView(recoBlock(r.recommendedKeyUses, r.blockKeys > 0));
        }
        if (r.blockKeys > 0) {
            card.addView(insetBlock(Copy.BLK_NOTE_LABEL(), Copy.BLK_NOTE(), false));
        }
    }

    /** The .reco box: the exact command to run on the next seed re-sync, tap to copy. */
    private View recoBlock(long keyuses, boolean mixedSchemes) {
        final String cmd = Copy.recoCommand(keyuses);
        final LinearLayout box = (LinearLayout) insetBlock(
                Copy.RECO_LABEL() + (mixedSchemes ? Copy.RECO_LABEL_CLASSIC_SUFFIX() : ""), null, true);
        final TextView code = text(cmd, 12f, Design.ACCENT, Typeface.NORMAL);
        code.setTypeface(Typeface.MONOSPACE);
        code.setLineSpacing(dp(3), 1f);
        box.addView(code);
        box.setOnClickListener(v -> copy(cmd, Copy.copied()));
        return box;
    }

    private View insetBlock(String label, String bodyHtml, boolean empty) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Design.card(this, Design.INSET, Design.BORDER, 10));
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setLayoutParams(marginParams(0, 14, 0, 0));

        final TextView l = text(label, 10.5f, Design.DIM, Typeface.BOLD);
        l.setLetterSpacing(0.08f);
        box.addView(l);

        if (!empty && bodyHtml != null) {
            final TextView b = html(bodyHtml, 12.5f, Design.TEXT);
            b.setPadding(0, dp(8), 0, 0);
            b.setLineSpacing(dp(4), 1f);
            box.addView(b);
        }
        return box;
    }

    private View noticeCard(String body, int color) {
        final int soft = color == Design.WARN ? Design.WARN_SOFT : Design.RISK_SOFT;
        final TextView t = html(body, 12.5f, color);
        t.setBackground(Design.card(this, soft, Design.alpha(color, 0x59), 10));
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setLineSpacing(dp(3), 1f);
        t.setLayoutParams(marginParams(0, 0, 0, 12));
        return t;
    }

    /* ---------- results table ---------- */

    // Fixed column widths so the header and rows line up. Total exceeds a phone's width, so the
    // table scrolls horizontally — the same behaviour as the dapp's `.tablewrap { overflow-x: auto }`.
    private static final int COL_ADDR = 190, COL_USES = 66, COL_SIGS = 82, COL_COINS = 66, COL_STATUS = 90;

    private View tableCard(KeyAudit.Result r, long archiveTip) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, Design.CARD, Design.BORDER, 16));
        card.setPadding(dp(12), dp(16), dp(12), dp(16));
        card.setLayoutParams(marginParams(0, 0, 0, 14));

        final LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);

        renderKeySections(table,r);

        card.addView(table);

        final TextView cov = text(Copy.coverage(archiveTip), 10.5f, Design.DIM, Typeface.NORMAL);
        cov.setPadding(dp(2), dp(14), dp(2), 0);
        cov.setLineSpacing(dp(3), 1f);
        card.addView(cov);

        return card;
    }

    private View headerRow() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(8));

        row.addView(headerCell(Copy.address(), COL_ADDR, Gravity.START));
        row.addView(headerCell(Copy.localUses(), COL_USES, Gravity.END));
        row.addView(headerCell(Copy.onchain(), COL_SIGS, Gravity.END));
        row.addView(headerCell(Copy.coins(), COL_COINS, Gravity.END));


        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(divider());
        return wrap;
    }

    private TextView headerCell(String s, int widthDp, int gravity) {
        final TextView t = text(s, 9.5f, Design.DIM, Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setAllCaps(true);
        t.setGravity(gravity);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,widthDp));
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private View row(final KeyAudit.Row r, int displayIndex) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(9), 0, dp(9));

        // address cell: "#N" then the Mx address, wrapping inside its column
        final LinearLayout addrCell = new LinearLayout(this);
        addrCell.setOrientation(LinearLayout.VERTICAL);
        addrCell.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,COL_ADDR));
        addrCell.setPadding(dp(4), 0, dp(4), 0);

        final TextView n = text("#" + displayIndex + (r.family == KeyAudit.Family.BLOCK ? " · 128/4" : ""),
                9.5f, Design.DIM_2, Typeface.NORMAL);
        n.setTag("keyuses.number."+r.index);
        addrCell.addView(n);
        addrCell.addView(text(sourceLabel(r.index),9.5f,Design.DIM_2,Typeface.NORMAL));

        final TextView a = text(r.address, 9.5f, Design.DIM, Typeface.NORMAL);
        a.setTypeface(Typeface.MONOSPACE);
        a.setLineSpacing(dp(1), 1f);
        addrCell.addView(a);

        addrCell.setOnClickListener(v -> {
            copy(r.address, Copy.copied());
            a.setTextColor(Design.ACCENT);
            a.postDelayed(() -> a.setTextColor(Design.DIM), 1300);
        });
        row.addView(addrCell);

        row.addView(numCell(r.local < 0 ? "—" : num(r.local), COL_USES));
        row.addView(numCell(num(r.sigs), COL_SIGS));
        row.addView(numCell(num(r.coins), COL_COINS));

        // status chip
        final String label;
        final int fg, bg;
        switch (r.reused ? KeyAudit.Status.REUSED : r.risk ? KeyAudit.Status.AT_RISK : r.publicChecked ? KeyAudit.Status.OK : KeyAudit.Status.UNKNOWN) {
            case REUSED:
                final boolean red = r.reuseCount > KeyAudit.REUSE_RED_ABOVE;
                fg = red ? Design.RISK : Design.WARN;
                bg = red ? Design.RISK_SOFT : Design.WARN_SOFT;
                label = Copy.reused() + (r.reuseCount > 0 ? " ×" + r.reuseCount : "");
                break;
            case AT_RISK:
                fg = Design.RISK;
                bg = Design.RISK_SOFT;
                label = Copy.atRisk();
                break;
            case UNKNOWN:
                fg=Design.WARN; bg=Design.WARN_SOFT; label=Copy.unknown(); break;
            default:
                fg = Design.OK;
                bg = Design.OK_SOFT;
                label = r.sigs>0 ? Copy.used(num(r.sigs)) : Copy.unused();
                break;
        }

        final TextView chip = text(label, 9.5f, fg, Typeface.BOLD);
        chip.setBackground(Design.chip(this, bg));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.setGravity(Gravity.CENTER);

        chip.setTag("keyuses.status."+r.index);
        n.setTextColor(fg);
        addrCell.addView(chip,1);

        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(divider());
        return wrap;
    }

    private TextView numCell(String s, int widthDp) {
        final TextView t = text(s, 12f, Design.TEXT, Typeface.NORMAL);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(8,12,1,TypedValue.COMPLEX_UNIT_SP);
        t.setGravity(Gravity.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,widthDp));
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private View divider() {
        final View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        v.setBackgroundColor(Design.BORDER);
        return v;
    }

    /* ====================== static chrome ====================== */

    private void buildUi() {
        container.addView(badge());
        container.addView(title());
        container.addView(subtitle());
        container.addView(pairingBanner = pairingBanner());
        container.addView(controlsCard());

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setVisibility(View.GONE);
        container.addView(resultsBox);

        container.addView(explainerCard());
        container.addView(footer());
    }

    private View badge() {
        final LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Design.card(this, Design.ACCENT_SOFT, Design.ACCENT_SOFT, 4));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));

        final View dot = new View(this);
        final LinearLayout.LayoutParams dp6 = new LinearLayout.LayoutParams(dp(6), dp(6));
        dp6.rightMargin = dp(8);
        dot.setLayoutParams(dp6);
        dot.setBackground(Design.dot(Design.ACCENT));
        b.addView(dot);

        final TextView t = text(Copy.BADGE(), 10f, Design.ACCENT, Typeface.BOLD);
        t.setLetterSpacing(0.1f);
        b.addView(t);

        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setTag("pandamonium.app.toolbar.self");
        TextView menu=text("☰",20f,Design.TEXT,Typeface.NORMAL);
        menu.setTag("keyuses.menu"); menu.setGravity(Gravity.CENTER);
        menu.setContentDescription(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_drawer_open));
        menu.setOnClickListener(v -> {
            View shell=getWindow().getDecorView().findViewWithTag("pandamonium.navigation.shell");
            if(shell instanceof androidx.drawerlayout.widget.DrawerLayout)
                ((androidx.drawerlayout.widget.DrawerLayout)shell).openDrawer(Gravity.LEFT);
        });
        wrap.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(b);
        wrap.setLayoutParams(marginParams(0, 0, 0, 16));
        return wrap;
    }

    private View title() {
        final TextView t = text(Copy.TITLE(), 26f, Design.TEXT, Typeface.BOLD);
        t.setLetterSpacing(-0.02f);
        t.setLayoutParams(marginParams(0, 0, 0, 8));
        return t;
    }

    private View subtitle() {
        final TextView t = html(Copy.SUBTITLE(), 13.5f, Design.DIM);
        t.setLineSpacing(dp(4), 1f);
        t.setLayoutParams(marginParams(0, 0, 0, 20));
        return t;
    }

    private LinearLayout pairingBanner() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, Design.ACCENT_SOFT, Design.alpha(Design.ACCENT, 0x66), 14));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setLayoutParams(marginParams(0, 0, 0, 14));
        card.setVisibility(View.GONE);

        card.addView(text(Copy.ERR_PAIRING_TITLE(), 14f, Design.ACCENT, Typeface.BOLD));

        final TextView body = text(Copy.ERR_PAIRING_BODY(), 12.5f, Design.TEXT, Typeface.NORMAL);
        body.setPadding(0, dp(6), 0, dp(12));
        body.setLineSpacing(dp(3), 1f);
        card.addView(body);

        final TextView btn = text(Copy.openNode(), 13f, 0xFFFFFFFF, Typeface.BOLD);
        btn.setBackground(Design.accentButton(this, 10));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(11), dp(14), dp(11));
        btn.setOnClickListener(v -> openNode());
        card.addView(btn);

        return card;
    }

    private View controlsCard() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, Design.CARD, Design.BORDER, 16));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setLayoutParams(marginParams(0, 0, 0, 14));

        runButton = new LinearLayout(this);
        runButton.setOrientation(LinearLayout.HORIZONTAL);
        runButton.setGravity(Gravity.CENTER);
        runButton.setBackground(Design.accentButton(this, 10));
        runButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        runButton.setOnClickListener(v -> runAudit());

        runSpinner = new ProgressBar(this);
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(16), dp(16));
        sp.rightMargin = dp(10);
        runSpinner.setLayoutParams(sp);
        runSpinner.setIndeterminate(true);
        runSpinner.setVisibility(View.GONE);
        runButton.addView(runSpinner);

        runLabel = text(Copy.run(), 14.5f, 0xFFFFFFFF, Typeface.BOLD);
        runButton.addView(runLabel);
        card.addView(runButton);
        walletToggle=new android.widget.CheckBox(this);
        walletToggle.setText(Copy.includeWallet()); walletToggle.setTextColor(Design.TEXT);
        walletToggle.setMinHeight(dp(48)); walletToggle.setChecked(includeWallet);
        walletToggle.setOnCheckedChangeListener((button,checked) -> {
            includeWallet=checked;
            getSharedPreferences("keyuses_settings",MODE_PRIVATE).edit().putBoolean("include_wallet",checked).apply();
            lastResult=null;selection=null;resultsBox.removeAllViews();resultsBox.setVisibility(View.GONE);
        });
        card.addView(walletToggle);

        final TextView privacy = html(Copy.PRIVACY(), 11f, Design.DIM);
        privacy.setPadding(0, dp(14), 0, 0);
        privacy.setLineSpacing(dp(3), 1f);
        card.addView(privacy);

        statusBox = html("", 12f, Design.RISK);
        statusBox.setBackground(Design.card(this, Design.RISK_SOFT, Design.alpha(Design.RISK, 0x40), 10));
        statusBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusBox.setLineSpacing(dp(3), 1f);
        statusBox.setVisibility(View.GONE);
        statusBox.setLayoutParams(marginParams(0, 14, 0, 0));
        card.addView(statusBox);

        return card;
    }

    private View explainerCard() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, Design.CARD, Design.BORDER, 16));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setLayoutParams(marginParams(0, 0, 0, 14));

        final TextView label = text(Copy.EXPLAIN_LABEL(), 10.5f, Design.DIM, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        card.addView(label);

        final TextView body = html(Copy.EXPLAIN_BODY(), 12.5f, Design.TEXT);
        body.setPadding(0, dp(10), 0, 0);
        body.setLineSpacing(dp(4), 1f);
        card.addView(body);

        return card;
    }

    private View footer() {
        final TextView t = text("eurobuddha · Minima Ecosystem · KeyUses v" + VERSION,
                10f, Design.DIM_2, Typeface.NORMAL);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.05f);
        t.setPadding(0, dp(6), 0, dp(10));
        return t;
    }

    /* ====================== state ====================== */

    private void setBusy(boolean b) {
        busy = b;
        runButton.setEnabled(!b && derivationValid);
        runButton.setAlpha(b ? 0.5f : 1f);
        runSpinner.setVisibility(b ? View.VISIBLE : View.GONE);
        if(walletToggle!=null)walletToggle.setEnabled(!b);
        updateProgress();
    }

    private void setPaired(boolean enabled) {
        if (pairingBanner != null) {
            pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
    }

    private void showError(String msg) {
        statusBox.setText(msg);
        statusBox.setVisibility(View.VISIBLE);
    }

    private void clearError() {
        errorText=null;
        statusBox.setVisibility(View.GONE);
    }

    private void openNode() {
        Intent i = getPackageManager().getLaunchIntentForPackage(NODE_PACKAGE);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
        if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
        if (i != null) {
            startActivity(i);
        } else {
            Toast.makeText(this, Copy.unavailable(), Toast.LENGTH_LONG).show();
        }
    }

    private void copy(String s, String toast) {
        final ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("keyuses", s));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    /* ====================== view helpers ====================== */

    private TextView text(String s, float sizeSp, int color, int style) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (style != Typeface.NORMAL) t.setTypeface(Typeface.DEFAULT, style);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView html(String s, float sizeSp, int color) {
        final TextView t = new TextView(this);
        t.setText(HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_COMPACT));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams marginParams(int l, int t, int r, int b) {
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float v) {
        return Design.dpi(this, v);
    }

    private static String num(long v) {
        return NumberFormat.getInstance(Locale.getDefault()).format(v);
    }
}
