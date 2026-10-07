package com.eurobuddha.entropy;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The LAST WORD tab — port of the MiniDapp checksum calculator. Pick 12/15/18/21/24,
 * type N-1 words into boxes with 4-char-unique autocomplete, and every valid final word
 * (128/64/32/16/8 of them — free bits + checksum) is computed and checksum-validated.
 */
public class LastWordView extends BaseView {

    private static final int[] LENGTHS = {12, 15, 18, 21, 24};

    private int wordsTarget = 12;
    private boolean hidden = false;
    private List<String> candidates = null;

    private final Button[] lenBtns = new Button[LENGTHS.length];
    private Button btnHide, btnClear, btnCalc, btnRandom;
    private LinearLayout boxGrid, candGrid, resultsCard;
    private LinearLayout suggRow;
    private HorizontalScrollView suggScroll;
    private TextView status;
    private final List<EditText> boxes = new ArrayList<>();
    private EditText lastBox;
    private boolean muteWatcher = false;
    private final List<Integer> prevLens = new ArrayList<>(); // per-box previous text length

    public LastWordView(MainActivity a) {
        super(a);
        build();
    }

    private void build() {
        var c = act;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.label(c, "Last-word (checksum) calculator"));

        LinearLayout topRow = Ui.row(c);
        btnHide = Ui.button(c, "🙈 Hide", Theme.panel2(), Theme.dim());
        btnClear = Ui.button(c, "↺ Clear all", Theme.panel2(), Theme.dim());
        btnHide.setLayoutParams(Ui.lpRow(c, 1));
        btnClear.setLayoutParams(Ui.lpRow(c, 1));
        btnHide.setOnClickListener(v -> setHidden(!hidden));
        btnClear.setOnClickListener(v -> clearAll());
        Ui.marginTop(topRow, Ui.dp(c, 6));
        topRow.addView(btnHide);
        topRow.addView(btnClear);
        card.addView(topRow);

        LinearLayout lenRow = Ui.row(c);
        for (int i = 0; i < LENGTHS.length; i++) {
            final int n = LENGTHS[i];
            Button b = Ui.button(c, String.valueOf(n), Theme.panel2(), Theme.dim());
            b.setLayoutParams(Ui.lpRow(c, 1));
            b.setOnClickListener(v -> { wordsTarget = n; refreshChips(); buildBoxes(); });
            lenBtns[i] = b;
            lenRow.addView(b);
        }
        Ui.marginTop(lenRow, Ui.dp(c, 8));
        card.addView(lenRow);

        TextView hint = Ui.text(c,
                "Every BIP39 word is unique after 4 letters, so boxes auto-complete as soon as the " +
                "word is unambiguous. The final word packs entropy bits plus the SHA-256 checksum, " +
                "so it is calculated, never typed.", Theme.dim(), 11, false);
        Ui.marginTop(hint, Ui.dp(c, 8));
        card.addView(hint);

        boxGrid = Ui.col(c);
        Ui.marginTop(boxGrid, Ui.dp(c, 8));
        card.addView(boxGrid);

        suggScroll = new HorizontalScrollView(c);
        suggScroll.setHorizontalScrollBarEnabled(false);
        suggRow = Ui.row(c);
        suggScroll.addView(suggRow);
        Ui.marginTop(suggScroll, Ui.dp(c, 4));
        card.addView(suggScroll);

        btnCalc = Ui.button(c, "Calculate valid last words", Theme.gold(), Theme.onAccent());
        btnCalc.setOnClickListener(v -> calc());
        btnCalc.setEnabled(false);
        btnCalc.setAlpha(0.4f);
        Ui.marginTop(btnCalc, Ui.dp(c, 8));
        card.addView(btnCalc);

        status = Ui.text(c, "", Theme.dim(), 11, false);
        status.setTypeface(Theme.mono());
        Ui.marginTop(status, Ui.dp(c, 6));
        card.addView(status);
        container.addView(card);

        resultsCard = Ui.card(c);
        resultsCard.addView(Ui.label(c, "Valid last words"));
        TextView rHint = Ui.text(c, "tap a word to use it as your final word, or:", Theme.dim(), 10, false);
        resultsCard.addView(rHint);
        btnRandom = Ui.button(c, "🎲 Pick one at random (node + device entropy)", Theme.panel2(), Theme.cyan());
        btnRandom.setOnClickListener(v -> pickRandom());
        Ui.marginTop(btnRandom, Ui.dp(c, 6));
        resultsCard.addView(btnRandom);
        candGrid = Ui.col(c);
        Ui.marginTop(candGrid, Ui.dp(c, 8));
        resultsCard.addView(candGrid);
        resultsCard.setVisibility(View.GONE);
        container.addView(resultsCard);

        refreshChips();
        buildBoxes();
    }

    private void refreshChips() {
        for (int i = 0; i < LENGTHS.length; i++) {
            boolean on = LENGTHS[i] == wordsTarget;
            lenBtns[i].setTextColor(on ? Theme.onAccent() : Theme.dim());
            lenBtns[i].setBackground(Ui.rounded(on ? Theme.gold() : Theme.panel2(), 0, 6, act));
        }
    }

    private void buildBoxes() {
        boxes.clear();
        prevLens.clear();
        boxGrid.removeAllViews();
        candidates = null;
        resultsCard.setVisibility(View.GONE);
        candGrid.removeAllViews();
        status.setText("");
        LinearLayout row = null;
        for (int i = 0; i < wordsTarget; i++) {
            if (i % 3 == 0) {
                row = Ui.row(act);
                Ui.marginTop(row, Ui.dp(act, 6));
                boxGrid.addView(row);
            }
            EditText e = new EditText(act);
            boolean cs = (i == wordsTarget - 1);
            e.setHint(cs ? "?" : (i + 1) + "…");
            e.setHintTextColor(Theme.dim());
            e.setTextColor(cs ? Theme.cyan() : Theme.text());
            e.setTextSize(12);
            e.setSingleLine(true);
            // VISIBLE_PASSWORD: keyboards treat this as a password field — no personal-dictionary
            // learning, no cloud sync of typed seed words. NO_SUGGESTIONS alone does not stop that.
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            e.setTypeface(Theme.mono()); // set AFTER inputType: VISIBLE_PASSWORD resets the typeface
            e.setBackground(Ui.rounded(0x66000000, cs ? Theme.cyan() & 0x80FFFFFF : Theme.border(), 6, act));
            int p = Ui.dp(act, 8);
            e.setPadding(p, Ui.dp(act, 6), p, Ui.dp(act, 6));
            LinearLayout.LayoutParams lp = Ui.lpRow(act, 1);
            lp.leftMargin = i % 3 == 0 ? 0 : Ui.dp(act, 6);
            e.setLayoutParams(lp);
            if (cs) {
                e.setEnabled(false);
                lastBox = e;
            } else {
                final int idx = i;
                e.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int cc) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int cc) {}
                    @Override public void afterTextChanged(Editable s) { if (!muteWatcher) onBoxEdit(idx); }
                });
                e.setOnFocusChangeListener((v, has) -> { if (has) showSuggestions(idx); });
                boxes.add(e);
                prevLens.add(0);
            }
            row.addView(e);
        }
        setHiddenTransforms();
    }

    private List<String> suggestionsFor(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix.isEmpty()) return out;
        for (String w : Bip39.words()) {
            if (w.startsWith(prefix)) { out.add(w); if (out.size() > 8) break; }
        }
        return out;
    }

    private void onBoxEdit(int idx) {
        EditText e = boxes.get(idx);
        String v = e.getText().toString().toLowerCase().replaceAll("[^a-z]", "");
        if (!v.equals(e.getText().toString())) {
            muteWatcher = true;
            e.setText(v);
            e.setSelection(v.length());
            muteWatcher = false;
        }
        invalidateResults();
        boolean grew = v.length() > prevLens.get(idx);
        prevLens.set(idx, v.length());
        List<String> m = suggestionsFor(v);
        // BIP39 words are unique after 4 chars: commit the moment the prefix is unambiguous —
        // but only while TYPING (grew). Auto-committing on deletion would make committed words
        // uneditable: backspacing "abandon" to "abando" would instantly re-complete it.
        if (grew && m.size() == 1 && !v.isEmpty()) { commit(idx, m.get(0)); return; }
        styleBox(idx);
        showSuggestions(idx);
        refreshCalc();
    }

    private void styleBox(int idx) {
        EditText e = boxes.get(idx);
        String v = e.getText().toString();
        int border = Theme.border();
        if (!v.isEmpty()) {
            if (Bip39.wordIndex(v) >= 0) border = Theme.green();
            else if (suggestionsFor(v).isEmpty()) border = Theme.red();
        }
        e.setBackground(Ui.rounded(0x66000000, border, 6, act));
    }

    private void commit(int idx, String w) {
        muteWatcher = true;
        EditText e = boxes.get(idx);
        e.setText(w);
        e.setSelection(w.length());
        muteWatcher = false;
        prevLens.set(idx, w.length());
        styleBox(idx);
        invalidateResults();
        refreshCalc();
        for (int j = 0; j < boxes.size(); j++) {
            int k = (idx + 1 + j) % boxes.size();
            if (Bip39.wordIndex(boxes.get(k).getText().toString()) < 0) {
                boxes.get(k).requestFocus();
                showSuggestions(k);
                return;
            }
        }
        e.clearFocus();
        suggRow.removeAllViews();
    }

    private void showSuggestions(int idx) {
        suggRow.removeAllViews();
        List<String> m = suggestionsFor(boxes.get(idx).getText().toString());
        for (String w : m.subList(0, Math.min(m.size(), 8))) {
            Button b = Ui.button(act, w, Theme.panel2(), Theme.cyan());
            b.setAllCaps(false);
            b.setOnClickListener(v -> commit(idx, w));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 6);
            b.setLayoutParams(lp);
            suggRow.addView(b);
        }
    }

    private List<String> typedWords() {
        List<String> out = new ArrayList<>(boxes.size());
        for (EditText e : boxes) {
            String v = e.getText().toString();
            if (Bip39.wordIndex(v) < 0) return null;
            out.add(v);
        }
        return out;
    }

    private void refreshCalc() {
        boolean ok = typedWords() != null;
        btnCalc.setEnabled(ok);
        btnCalc.setAlpha(ok ? 1f : 0.4f);
    }

    private void invalidateResults() {
        if (candidates != null) {
            candidates = null;
            resultsCard.setVisibility(View.GONE);
            candGrid.removeAllViews();
        }
        if (lastBox != null && lastBox.getText().length() > 0) {
            muteWatcher = true;
            lastBox.setText("");
            muteWatcher = false;
        }
    }

    private void calc() {
        if (act.selfTestError() != null) {
            setStatus("Blocked — startup self-test failed: " + act.selfTestError(), true);
            return;
        }
        List<String> words = typedWords();
        if (words == null) return;
        Bip39.LastWords res = Bip39.lastWordCandidates(words);
        if (res.error != null) { setStatus(res.error, true); return; }
        // gate: every candidate must pass the checksum validator
        for (String cand : res.candidates) {
            List<String> full = new ArrayList<>(words);
            full.add(cand);
            if (!Bip39.check(full)) {
                setStatus("INTERNAL MISMATCH — results suppressed (candidate failed validation).", true);
                return;
            }
        }
        candidates = res.candidates;
        setStatus(res.candidates.size() + " valid last words (" + res.free + " free bits + " + res.cs +
                "-bit checksum) — all checksum-validated", false);
        candGrid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < candidates.size(); i++) {
            if (i % 4 == 0) {
                row = Ui.row(act);
                Ui.marginTop(row, Ui.dp(act, 6));
                candGrid.addView(row);
            }
            final String w = candidates.get(i);
            TextView t = Ui.text(act, w, Theme.text(), 11, false);
            t.setTypeface(Theme.mono());
            t.setBackground(Ui.rounded(0x66000000, Theme.border(), 6, act));
            int p = Ui.dp(act, 6);
            t.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = Ui.lpRow(act, 1);
            lp.leftMargin = i % 4 == 0 ? 0 : Ui.dp(act, 4);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> pick(w, "chosen manually"));
            row.addView(t);
        }
        // pad the final row so columns stay aligned
        if (row != null) {
            int rem = candidates.size() % 4;
            if (rem != 0) for (int i = rem; i < 4; i++) {
                View v = new View(act);
                LinearLayout.LayoutParams lp = Ui.lpRow(act, 1);
                lp.leftMargin = Ui.dp(act, 4);
                v.setLayoutParams(lp);
                row.addView(v);
            }
        }
        resultsCard.setVisibility(View.VISIBLE);
        setHiddenTransforms();
    }

    private void pick(String w, String how) {
        muteWatcher = true;
        lastBox.setText(w);
        muteWatcher = false;
        setStatus("Phrase complete — word " + wordsTarget + " = \"" + w + "\" (" + how + "). Valid BIP39 ✓", false);
        if (Theme.sound()) Sfx.chime();
    }

    private void pickRandom() {
        if (candidates == null || candidates.isEmpty()) return;
        // candidate count is a power of two, so mod of a uniform value is exactly uniform
        act.engine().randomN((n, src) -> pick(candidates.get((int) (n % candidates.size())),
                RollEngine.SRC_NODE_DEVICE.equals(src) ? "picked by SHA3(node ++ device) entropy" : "picked by device entropy"));
    }

    private void setHidden(boolean on) {
        hidden = on;
        btnHide.setText(on ? "👁 Show" : "🙈 Hide");
        setHiddenTransforms();
    }

    private void setHiddenTransforms() {
        for (EditText e : boxes) e.setTransformationMethod(hidden ? PasswordTransformationMethod.getInstance() : null);
        if (lastBox != null) lastBox.setTransformationMethod(hidden ? PasswordTransformationMethod.getInstance() : null);
        suggScroll.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        candGrid.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        status.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    private void setStatus(String s, boolean err) {
        status.setText(s);
        status.setTextColor(err ? Theme.red() : Theme.green());
    }

    public void clearAll() {
        setHidden(false);
        buildBoxes();
        refreshCalc();
    }

    /** Called on tab switch: blur everything without destroying the user's typed words. */
    public void hideAll() {
        if (!hidden) setHidden(true);
    }

    @Override public void refresh() {}
}
