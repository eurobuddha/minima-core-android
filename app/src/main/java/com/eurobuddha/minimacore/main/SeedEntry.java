package com.eurobuddha.minimacore.main;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.eurobuddha.account.WordEntry;

import org.minima.utils.BIP39;

/**
 * The Bears' seed-phrase boxes: the guided entry Dexicon, AtomiX and PandaPools use (WordEntry, vendored by
 * scripts/vendor-wordentry.sh), owner 13 Nov 2026 ("new seed entry was a delight. please add it to the bears"). One box
 * per word replaces the multi-line field, where a line break or number glued two words together, and the first-run
 * screens hashed whatever was typed unless the user tapped Check. Plus "My phrase is not 24 list words" (any-phrase,
 * restore only, with a warning) where the node can restore one.
 */
public final class SeedEntry {
    private SeedEntry() {}

    /** Puts the boxes where {@code oldBox} was (in a vertical LinearLayout); hides it and its Check button. */
    public static WordEntry replace(Activity a, EditText oldBox, View checkButton, boolean anyPhrase, boolean forCommand) {
        WordEntry w = new WordEntry(a, 0, null, null);
        if (anyPhrase) w.enableAnyPhrase(forCommand);
        ViewGroup parent = (ViewGroup) oldBox.getParent();
        parent.addView(w.view, parent.indexOfChild(oldBox), oldBox.getLayoutParams());
        oldBox.setVisibility(View.GONE);
        if (checkButton != null) checkButton.setVisibility(View.GONE);
        return w;
    }

    /** What the node is given: boxes, the words cleaned to Minima's own form (UPPERCASE, single spaces - exactly what a
     *  node generated, so it opens the original wallet even though the node starts with -anyseed); any-phrase, the
     *  phrase as made (trimmed). Null while the entry is not ready. */
    public static String nodePhrase(WordEntry w) {
        String typed = w.phrase();
        return typed == null ? null : nodePhrase(w.isAnyPhrase(), typed);
    }

    static String nodePhrase(boolean anyPhrase, String typed) {
        return anyPhrase ? typed.trim() : BIP39.cleanSeedPhrase(typed);
    }
}
