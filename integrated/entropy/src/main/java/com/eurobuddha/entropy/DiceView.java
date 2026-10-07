package com.eurobuddha.entropy;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The DICE ROLL tab — port of the MiniDapp flow: pick a length, throw 5 animated dice
 * (node + device entropy, or tap in physical rolls), watch the entropy bar fill, then
 * reveal the phrase. Nothing is ever persisted; wipe() clears everything.
 */
public class DiceView extends BaseView {

    private static final int[] LENGTHS = {12, 15, 18, 21, 24};
    private static final int DICE = 5;

    private final Handler main = new Handler(Looper.getMainLooper());

    // state
    private int wordsTarget = 12;
    private boolean physical = false;
    private final List<Integer> faces = new ArrayList<>();
    private final List<RollEngine.Audit> audits = new ArrayList<>();
    private boolean busy = false, auto = false, done = false;
    private boolean revealed = false;
    private List<String> phrase = null;

    // ui
    private final Button[] lenBtns = new Button[LENGTHS.length];
    private Button btnDigital, btnPhysical, btnThrow, btnAuto, btnStartOver, btnReveal, btnAudit;
    private final DiceRollView[] dice = new DiceRollView[DICE];
    private ProgressBar progress;
    private TextView progressLabel, ticker, status, sourceLine;
    private LinearLayout rollCard, padWrap, doneCard, wordGrid, auditWrap;
    private TextView doneSummary, verifyRolls, auditLog, physAdvisory;

    public DiceView(MainActivity a) {
        super(a);
        build();
    }

    private void build() {
        var c = act;

        // ---- setup card ----
        LinearLayout setup = Ui.card(c);
        setup.addView(Ui.label(c, "Phrase length"));
        LinearLayout lenRow = Ui.row(c);
        for (int i = 0; i < LENGTHS.length; i++) {
            final int n = LENGTHS[i];
            Button b = Ui.button(c, String.valueOf(n), Theme.panel2(), Theme.dim());
            b.setLayoutParams(Ui.lpRow(c, 1));
            b.setOnClickListener(v -> { if (!busy && !done) { wordsTarget = n; refreshChips(); refreshProgress(); } });
            lenBtns[i] = b;
            lenRow.addView(b);
        }
        Ui.marginTop(lenRow, Ui.dp(c, 6));
        setup.addView(lenRow);

        setup.addView(spacer(8));
        setup.addView(Ui.label(c, "Dice mode"));
        LinearLayout modeRow = Ui.row(c);
        btnDigital = Ui.button(c, "Digital (node + device)", Theme.panel2(), Theme.dim());
        btnPhysical = Ui.button(c, "Physical dice", Theme.panel2(), Theme.dim());
        btnDigital.setLayoutParams(Ui.lpRow(c, 1));
        btnPhysical.setLayoutParams(Ui.lpRow(c, 1));
        btnDigital.setOnClickListener(v -> { if (!busy) { physical = false; refreshChips(); refreshMode(); } });
        btnPhysical.setOnClickListener(v -> { if (!busy) { physical = true; refreshChips(); refreshMode(); } });
        Ui.marginTop(modeRow, Ui.dp(c, 6));
        modeRow.addView(btnDigital);
        modeRow.addView(btnPhysical);
        setup.addView(modeRow);

        sourceLine = Ui.text(c, "checking entropy sources…", Theme.dim(), 11, false);
        sourceLine.setTypeface(Theme.mono());
        Ui.marginTop(sourceLine, Ui.dp(c, 8));
        setup.addView(sourceLine);

        TextView advisory = Ui.text(c,
                "⚠ SECURITY — you are generating a real wallet seed. Go offline / airplane mode, " +
                "make sure nobody can see the screen, and write the words on paper only. " +
                "Nothing is stored: closing this app destroys the phrase forever.",
                Theme.gold(), 11, false);
        advisory.setBackground(Ui.rounded(0x22FFD700, 0x44FFD700, 6, c));
        int p = Ui.dp(c, 10);
        advisory.setPadding(p, p, p, p);
        Ui.marginTop(advisory, Ui.dp(c, 10));
        setup.addView(advisory);
        container.addView(setup);

        // ---- roll card ----
        rollCard = Ui.card(c);
        rollCard.addView(Ui.label(c, "Collect entropy"));

        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.getProgressDrawable().setTint(Theme.gold());
        Ui.marginTop(progress, Ui.dp(c, 8));
        rollCard.addView(progress);
        progressLabel = Ui.text(c, "", Theme.dim(), 11, false);
        progressLabel.setTypeface(Theme.mono());
        rollCard.addView(progressLabel);

        LinearLayout diceRow = Ui.row(c);
        diceRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < DICE; i++) {
            DiceRollView d = new DiceRollView(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 70), 1);
            d.setLayoutParams(lp);
            d.setSilent(true); // one shared roll sound per throw, not five stacked tails
            d.setIdleFace(i % 6);
            dice[i] = d;
            diceRow.addView(d);
        }
        Ui.marginTop(diceRow, Ui.dp(c, 8));
        rollCard.addView(diceRow);

        btnThrow = Ui.button(c, "Throw 5 dice", Theme.gold(), Theme.onAccent());
        btnThrow.setOnClickListener(v -> throwDice());
        Ui.marginTop(btnThrow, Ui.dp(c, 8));
        rollCard.addView(btnThrow);

        btnAuto = Ui.button(c, "Auto-roll: off", Theme.panel2(), Theme.dim());
        btnAuto.setOnClickListener(v -> {
            auto = !auto;
            btnAuto.setText(auto ? "Auto-roll: on" : "Auto-roll: off");
            btnAuto.setTextColor(auto ? Theme.gold() : Theme.dim());
            if (auto && !busy && !done) throwDice();
        });
        Ui.marginTop(btnAuto, Ui.dp(c, 6));
        rollCard.addView(btnAuto);

        // physical pad
        padWrap = Ui.col(c);
        physAdvisory = Ui.text(c,
                "⚠ Use a REAL, fair die and enter every roll exactly as it lands. Typing numbers " +
                "\"randomly\" from your head produces a weak, guessable seed.",
                Theme.gold(), 11, false);
        physAdvisory.setBackground(Ui.rounded(0x22FFD700, 0x44FFD700, 6, c));
        physAdvisory.setPadding(p, p, p, p);
        padWrap.addView(physAdvisory);
        LinearLayout padRow1 = Ui.row(c), padRow2 = Ui.row(c);
        for (int f = 1; f <= 6; f++) {
            final int face = f;
            Button b = Ui.button(c, "⚀⚁⚂⚃⚄⚅".substring(f - 1, f) + " " + f, Theme.panel2(), Theme.text());
            b.setLayoutParams(Ui.lpRow(c, 1));
            b.setOnClickListener(v -> physicalTap(face));
            (f <= 3 ? padRow1 : padRow2).addView(b);
        }
        Ui.marginTop(padRow1, Ui.dp(c, 8));
        padWrap.addView(padRow1);
        padWrap.addView(padRow2);
        Button undo = Ui.button(c, "↺ Undo last", Theme.panel2(), Theme.dim());
        undo.setOnClickListener(v -> {
            if (done || faces.isEmpty()) return;
            faces.remove(faces.size() - 1);
            audits.remove(audits.size() - 1);
            refreshProgress();
        });
        Ui.marginTop(undo, Ui.dp(c, 6));
        padWrap.addView(undo);
        rollCard.addView(padWrap);

        ticker = Ui.text(c, "", Theme.cyan(), 12, false);
        ticker.setTypeface(Theme.mono());
        ticker.setLetterSpacing(0.25f);
        ticker.setBackground(Ui.rounded(0x66000000, Theme.border(), 6, c));
        ticker.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 8));
        ticker.setMinHeight(Ui.dp(c, 36));
        Ui.marginTop(ticker, Ui.dp(c, 10));
        rollCard.addView(ticker);

        status = Ui.text(c, "", Theme.dim(), 11, false);
        status.setTypeface(Theme.mono());
        Ui.marginTop(status, Ui.dp(c, 6));
        rollCard.addView(status);
        container.addView(rollCard);

        // ---- done card ----
        doneCard = Ui.card(c);
        doneCard.addView(Ui.label(c, "Your seed phrase"));
        wordGrid = Ui.col(c);
        Ui.marginTop(wordGrid, Ui.dp(c, 8));
        doneCard.addView(wordGrid);
        btnReveal = Ui.button(c, "👁 Tap to reveal", Theme.panel2(), Theme.dim());
        btnReveal.setOnClickListener(v -> { revealed = !revealed; renderWords(); });
        Ui.marginTop(btnReveal, Ui.dp(c, 6));
        doneCard.addView(btnReveal);

        doneSummary = Ui.text(c, "", Theme.dim(), 11, false);
        doneSummary.setTypeface(Theme.mono());
        Ui.marginTop(doneSummary, Ui.dp(c, 10));
        doneCard.addView(doneSummary);

        doneCard.addView(spacer(8));
        doneCard.addView(Ui.label(c, "Verify fairness"));
        TextView vHint = Ui.text(c,
                "Maths is bit-identical to iancoleman/bip39 v0.4.3 (locked by fixed vectors produced " +
                "from the genuine code — see the Entropy repo tests). Roll history below: paste into " +
                "bip39-standalone.html (Raw entropy mode) to reproduce the phrase.",
                Theme.dim(), 11, false);
        Ui.marginTop(vHint, Ui.dp(c, 4));
        doneCard.addView(vHint);
        verifyRolls = Ui.text(c, "", Theme.cyan(), 12, false);
        verifyRolls.setTypeface(Theme.mono());
        verifyRolls.setTextIsSelectable(true);
        verifyRolls.setBackground(Ui.rounded(0x66000000, Theme.border(), 6, c));
        verifyRolls.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 8));
        Ui.marginTop(verifyRolls, Ui.dp(c, 6));
        doneCard.addView(verifyRolls);

        btnAudit = Ui.button(c, "Show per-roll audit log", Theme.panel2(), Theme.dim());
        auditWrap = Ui.col(c);
        auditWrap.setVisibility(View.GONE);
        auditLog = Ui.text(c, "", Theme.dim(), 9, false);
        auditLog.setTypeface(Theme.mono());
        auditLog.setTextIsSelectable(true);
        TextView auditWarn = Ui.text(c,
                "⚠ This log reveals every roll's secrets (A + B) — anyone who sees it can " +
                "recompute the entire seed phrase. Treat it exactly like the seed words.",
                Theme.gold(), 10, false);
        auditWrap.addView(auditWarn);
        TextView auditHint = Ui.text(c,
                "Each digital face = SHA3(node_secret ++ device_secret) → first 4 bytes mod 6 " +
                "(values ≥ 4294967292 rerolled — zero modulo bias). Verify any roll in a Minima " +
                "terminal: runscript script:\"LET h=SHA3(CONCAT(<A> <B>)) LET n=NUMBER(SUBSET(0 4 h)) " +
                "LET r=n%6 RETURN TRUE\"", Theme.dim(), 10, false);
        auditWrap.addView(auditHint);
        auditWrap.addView(auditLog);
        btnAudit.setOnClickListener(v -> {
            boolean show = auditWrap.getVisibility() != View.VISIBLE;
            auditWrap.setVisibility(show ? View.VISIBLE : View.GONE);
            btnAudit.setText(show ? "Hide per-roll audit log" : "Show per-roll audit log");
        });
        Ui.marginTop(btnAudit, Ui.dp(c, 8));
        doneCard.addView(btnAudit);
        doneCard.addView(auditWrap);

        btnStartOver = Ui.button(c, "Wipe & start over", Theme.pink(), Theme.onAccent());
        btnStartOver.setOnClickListener(v -> wipe());
        Ui.marginTop(btnStartOver, Ui.dp(c, 10));
        doneCard.addView(btnStartOver);
        container.addView(doneCard);

        refreshChips();
        refreshMode();
        refreshProgress();
        doneCard.setVisibility(View.GONE);
    }

    private View spacer(int dp) {
        View v = new View(act);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, Ui.dp(act, dp)));
        return v;
    }

    private void refreshChips() {
        for (int i = 0; i < LENGTHS.length; i++) {
            boolean on = LENGTHS[i] == wordsTarget;
            lenBtns[i].setTextColor(on ? Theme.onAccent() : Theme.dim());
            lenBtns[i].setBackground(Ui.rounded(on ? Theme.gold() : Theme.panel2(), 0, 6, act));
        }
        btnDigital.setTextColor(!physical ? Theme.onAccent() : Theme.dim());
        btnDigital.setBackground(Ui.rounded(!physical ? Theme.gold() : Theme.panel2(), 0, 6, act));
        btnPhysical.setTextColor(physical ? Theme.onAccent() : Theme.dim());
        btnPhysical.setBackground(Ui.rounded(physical ? Theme.gold() : Theme.panel2(), 0, 6, act));
    }

    private void refreshMode() {
        btnThrow.setVisibility(physical ? View.GONE : View.VISIBLE);
        btnAuto.setVisibility(physical ? View.GONE : View.VISIBLE);
        padWrap.setVisibility(physical ? View.VISIBLE : View.GONE);
    }

    private String bits() {
        int[] f = new int[faces.size()];
        for (int i = 0; i < f.length; i++) f[i] = faces.get(i);
        return Bip39.facesToBits(f);
    }

    private void refreshProgress() {
        int target = Bip39.entTarget(wordsTarget);
        int b = bits().length();
        progress.setProgress(Math.min(1000, b * 1000 / target));
        progressLabel.setText(Math.min(b, target) + " / " + target + " bits — " + faces.size() + " rolls");
        StringBuilder sb = new StringBuilder();
        for (int f : faces) sb.append(f);
        ticker.setText(sb.toString());
    }

    public void setSourceLine(String s, boolean ok) {
        sourceLine.setText(s);
        sourceLine.setTextColor(ok ? Theme.green() : Theme.amber());
    }

    // ---- digital throw ----
    private void throwDice() {
        if (busy || done) return;
        if (act.selfTestError() != null) { setStatus("Blocked — self-test failed: " + act.selfTestError(), true); return; }
        if (blockedByDemo()) return;
        if (!act.engineReady()) { setStatus("Verifying entropy sources — one moment…", true); return; }
        busy = true;
        setStatus("", false);
        for (DiceRollView d : dice) d.startTumbling();
        final int[] results = new int[DICE];
        final RollEngine.Audit[] auds = new RollEngine.Audit[DICE];
        final int[] pending = {DICE};
        final boolean[] failed = {false}, settled = {false};
        Runnable watchdog = () -> {
            if (settled[0]) return;
            settled[0] = true;
            finishThrow(results, auds, true);
        };
        main.postDelayed(watchdog, 20000);
        for (int i = 0; i < DICE; i++) {
            final int idx = i;
            act.engine().rollOne(new RollEngine.RollCb() {
                @Override public void onFace(int face, RollEngine.Audit audit) {
                    if (settled[0]) return;
                    results[idx] = face; auds[idx] = audit;
                    if (--pending[0] == 0) { settled[0] = true; main.removeCallbacks(watchdog); finishThrow(results, auds, false); }
                }
                @Override public void onError(String m) {
                    if (settled[0]) return;
                    failed[0] = true;
                    if (--pending[0] == 0) { settled[0] = true; main.removeCallbacks(watchdog); finishThrow(results, auds, true); }
                }
            });
        }
    }

    private void finishThrow(int[] results, RollEngine.Audit[] auds, boolean failed) {
        if (failed) {
            for (DiceRollView d : dice) { d.cancel(); d.setIdleFace(0); }
            busy = false; auto = false;
            btnAuto.setText("Auto-roll: off"); btnAuto.setTextColor(Theme.dim());
            setStatus("Entropy source error — nothing recorded. Try again.", true);
            return;
        }
        if (Theme.sound()) Sfx.diceRoll(); // ONE shared sound for the whole 5-die throw
        final int[] landed = {0};
        for (int i = 0; i < DICE; i++) {
            final int idx = i;
            main.postDelayed(() -> dice[idx].roll(results[idx] - 1, () -> {
                if (++landed[0] == DICE) recordThrow(results, auds);
            }), 120L * i);
        }
    }

    private void recordThrow(int[] results, RollEngine.Audit[] auds) {
        int target = Bip39.entTarget(wordsTarget);
        for (int i = 0; i < DICE; i++) {
            if (bits().length() >= target) break;   // stop exactly per raw-mode rule
            faces.add(results[i]);
            audits.add(auds[i]);
        }
        refreshProgress();
        busy = false;
        if (bits().length() >= target) { finish(); return; }
        if (auto && !done) main.postDelayed(this::throwDice, 500);
    }

    // ---- physical ----
    private void physicalTap(int face) {
        if (done || busy || !physical) return;
        if (act.selfTestError() != null) { setStatus("Blocked — self-test failed: " + act.selfTestError(), true); return; }
        if (blockedByDemo()) return;
        faces.add(face);
        audits.add(new RollEngine.Audit(RollEngine.SRC_PHYSICAL, "", "", face - 1, face));
        dice[(faces.size() - 1) % DICE].setIdleFace(face - 1);
        if (Theme.sound()) Sfx.click();
        refreshProgress();
        if (bits().length() >= Bip39.entTarget(wordsTarget)) finish();
    }

    // ---- finish ----
    private void finish() {
        done = true;
        String all = bits();
        String ent = Bip39.rawTruncate(all);
        phrase = Bip39.entropyBitsToWords(ent);
        // the checksum validator must accept our own output or nothing is shown
        if (!Bip39.check(phrase)) {
            phrase = null; done = false;
            setStatus("INTERNAL MISMATCH — phrase suppressed. Wipe and retry.", true);
            return;
        }
        if (Theme.sound()) Sfx.chime();
        revealed = false;
        renderWords();

        Map<String, Integer> src = new HashMap<>();
        for (RollEngine.Audit a : audits) src.merge(a.src, 1, Integer::sum);
        StringBuilder parts = new StringBuilder();
        for (Map.Entry<String, Integer> e : src.entrySet()) {
            if (parts.length() > 0) parts.append(", ");
            parts.append(e.getValue()).append(" × ").append(e.getKey());
        }
        StringBuilder sum = new StringBuilder();
        sum.append(faces.size()).append(" rolls → ").append(all.length()).append(" unbiased bits → ")
           .append(ent.length()).append(" entropy bits + ").append(ent.length() / 32)
           .append("-bit SHA-256 checksum = ").append(phrase.size()).append(" words\nsources: ").append(parts);
        if (src.containsKey(RollEngine.SRC_DEVICE_ONLY))
            sum.append("\n⚠ some rolls used a single entropy source (node unavailable)");
        if (src.containsKey(RollEngine.SRC_PHYSICAL)) {
            Map<Integer, Integer> fc = new HashMap<>();
            int maxc = 0;
            for (int f : faces) { int v = fc.merge(f, 1, Integer::sum); if (v > maxc) maxc = v; }
            if (fc.size() < 4 || maxc * 2 > faces.size())
                sum.append("\n⚠ roll distribution looks non-random — use a real, fair die");
        }
        doneSummary.setText(sum.toString());

        StringBuilder rollStr = new StringBuilder();
        for (int f : faces) rollStr.append(f);
        verifyRolls.setText(rollStr.toString());

        StringBuilder log = new StringBuilder();
        for (int i = 0; i < audits.size(); i++) {
            RollEngine.Audit a = audits.get(i);
            if (RollEngine.SRC_PHYSICAL.equals(a.src)) log.append("#").append(i + 1).append(" face:").append(a.face).append(" (physical)\n");
            else if (RollEngine.SRC_DEVICE_ONLY.equals(a.src)) log.append("#").append(i + 1).append(" face:").append(a.face).append(" (device CSPRNG only)\n");
            else log.append("#").append(i + 1).append(" A:").append(a.a).append(" B:").append(a.b).append(" n:").append(a.n).append(" face:").append(a.face).append("\n");
        }
        auditLog.setText(log.toString());

        rollCard.setVisibility(View.GONE);
        doneCard.setVisibility(View.VISIBLE);
    }

    private void renderWords() {
        wordGrid.removeAllViews();
        if (phrase == null) return;
        LinearLayout row = null;
        for (int i = 0; i < phrase.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.row(act);
                Ui.marginTop(row, Ui.dp(act, 6));
                wordGrid.addView(row);
            }
            boolean cs = (i == phrase.size() - 1);
            TextView t = Ui.text(act, (i + 1) + ". " + (revealed ? phrase.get(i) : "••••••"),
                    cs ? Theme.cyan() : Theme.text(), 12, false);
            t.setTypeface(Theme.mono(), revealed ? Typeface.NORMAL : Typeface.BOLD);
            t.setBackground(Ui.rounded(0x66000000, cs ? Theme.cyan() & 0x80FFFFFF : Theme.border(), 6, act));
            int p = Ui.dp(act, 8);
            t.setPadding(p, Ui.dp(act, 6), p, Ui.dp(act, 6));
            LinearLayout.LayoutParams lp = Ui.lpRow(act, 1);
            lp.leftMargin = i % 3 == 0 ? 0 : Ui.dp(act, 6);
            t.setLayoutParams(lp);
            row.addView(t);
        }
        btnReveal.setText(revealed ? "🙈 Tap to hide" : "👁 Tap to reveal");
    }

    /** Rolls captured on a mirrored/recorded screen are seed material — never collect any. */
    private boolean blockedByDemo() {
        if (!act.demoMode()) return false;
        auto = false;
        btnAuto.setText("Auto-roll: off"); btnAuto.setTextColor(Theme.dim());
        setStatus("Blocked — demo mode is ON (screen capture enabled). Turn it off to roll.", true);
        return true;
    }

    private void setStatus(String s, boolean err) {
        status.setText(s);
        status.setTextColor(err ? Theme.red() : Theme.dim());
    }

    /** Called on tab switch: re-blur a revealed phrase without destroying the run. */
    public void hideWords() {
        if (done && revealed) { revealed = false; renderWords(); }
    }

    public void wipe() {
        main.removeCallbacksAndMessages(null);   // stale watchdog / landing callbacks
        faces.clear(); audits.clear();
        phrase = null;
        busy = false; auto = false; done = false; revealed = false;
        btnAuto.setText("Auto-roll: off"); btnAuto.setTextColor(Theme.dim());
        wordGrid.removeAllViews();
        doneSummary.setText(""); verifyRolls.setText(""); auditLog.setText("");
        auditWrap.setVisibility(View.GONE);
        btnAudit.setText("Show per-roll audit log");
        setStatus("", false);
        for (int i = 0; i < DICE; i++) { dice[i].cancel(); dice[i].setIdleFace(i % 6); }
        doneCard.setVisibility(View.GONE);
        rollCard.setVisibility(View.VISIBLE);
        refreshProgress();
    }

    @Override public void refresh() { refreshProgress(); }
}
