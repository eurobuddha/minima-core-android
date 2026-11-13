package com.eurobuddha.minimacore.main;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.eurobuddha.account.WordEntry;
import com.eurobuddha.minimacore.i18n.LanguageDialog;

import org.minima.utils.BIP39;

/**
 * The Bears' seed-phrase boxes: the guided entry Dexicon, AtomiX and PandaPools use (WordEntry, vendored by
 * scripts/vendor-wordentry.sh), owner 13 Nov 2026 ("new seed entry was a delight. please add it to the bears"). One box
 * per word replaces the multi-line field, where a line break or number glued two words together and the first-run
 * screens hashed whatever was typed unless the user tapped Check. Plus "My phrase is not 24 list words" (any-phrase,
 * restore only, with a warning).
 *
 * <p>Any-phrase here is NODE context (review 13 Nov): a phrase made only of list words is allowed, because the node's old
 * first-run screens hashed typed words AS TYPED (H1) and the Vault took 12-21 words (M5) - the user is then asked
 * whether to use Minima's own form or exactly what they typed ({@link #resolve}). Every any-phrase is checked as a node
 * command would be (no " ; : [ ] { }, no leading "-"), so a phrase that starts a wallet can also rebuild it (L7).
 */
public final class SeedEntry {
    private SeedEntry() {}

    /** The node's guided entry: boxes, plus any-phrase in node context. */
    public static WordEntry create(Activity a) { return new WordEntry(a, 0, null, null).enableAnyPhrase(true, true); }

    /** Puts the boxes where {@code oldBox} was (in a vertical LinearLayout); hides it and its Check button. */
    public static WordEntry replace(Activity a, EditText oldBox, View checkButton) {
        WordEntry w = create(a);
        ViewGroup parent = (ViewGroup) oldBox.getParent();
        ViewGroup.LayoutParams lp = oldBox.getLayoutParams();
        parent.addView(w.view, parent.indexOfChild(oldBox),
                lp instanceof LinearLayout.LayoutParams ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp) : lp);
        oldBox.setVisibility(View.GONE);
        if (checkButton != null) checkButton.setVisibility(View.GONE);
        return w;
    }

    /** What a choice means for the node: {@code asTyped} = hash exactly the text (anyphrase), else Minima's own form. */
    public interface Choice { void use(String phrase, boolean asTyped); }

    /**
     * Resolves what the user entered. Boxes: Minima's own form (UPPERCASE, single spaces - what a node generated, so a
     * first-run restore opens the original wallet even though the node starts with -anyseed). Any-phrase of list words
     * only: ASK - Minima's form, or exactly as typed (only right for a wallet an older app made from the typed text).
     * Any other any-phrase: as typed. Shows {@link WordEntry#problem()} and returns without calling back when not ready.
     */
    public static void resolve(Activity a, WordEntry w, Choice choice) {
        String typed = w.phrase();
        if (typed == null) { android.widget.Toast.makeText(a, w.problem(), android.widget.Toast.LENGTH_LONG).show(); return; }
        if (!w.isAnyPhrase()) { choice.use(minimaForm(typed), false); return; }
        if (!WordEntry.allListWords(typed)) { choice.use(typed.trim(), true); return; }
        new LanguageDialog(a)
                .setTitle("These are list words")
                .setMessage("A wallet made by Minima uses its own form of the words (capitals, single spaces). Older "
                        + "versions of this app could make a wallet from the words exactly as they were typed, unless you "
                        + "tapped Check. Choose \"Exactly as typed\" only for such a wallet - otherwise you open a "
                        + "different, empty wallet.")
                .setPositiveButton("Minima's form", (d, x) -> choice.use(minimaForm(typed), false))
                .setNeutralButton("Exactly as typed", (d, x) -> choice.use(typed.trim(), true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    static String minimaForm(String words) { return BIP39.cleanSeedPhrase(words); }
}
