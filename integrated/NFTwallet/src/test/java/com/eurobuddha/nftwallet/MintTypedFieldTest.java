package com.eurobuddha.nftwallet;

import static org.junit.Assert.*;

import org.junit.Test;

/** Typed mint text rides `tokencreate name:{...}`. The node splits it at every ";", counts { } [ ] with one depth
 *  counter even inside a JSON string, ends a quoted value at the first '"', has no backslash escape and starts a
 *  new parameter at a control character: such text is refused at the field, never escaped. */
public class MintTypedFieldTest {
    static final String[] EVIL = {"Gold;send amount:1000", "Nice art] amount:1 decimals:0 burn:1000 webvalidate:", "a[b", "a{b", "a}b",
            "say \"hi\" burn:1000", "back\\slash", "line one\nline two", "tab\there", "cr\rhere", "nul\u0000", "del\u007f"};

    @Test public void everyCharacterTheTokenizerActsOnIsRefused() {
        for (String bad : EVIL) assertTrue(bad, StateNft.unsafeText(bad));
        for (String ok : new String[]{null, "", "Nocturne 04 — Kyiv, oil & ink (2026)", "Україна 中文", "50% <b> 'q' a:b #1 @x",
                "https://x.example/a?b=1&c=2", "AAAA+/="})
            assertFalse(String.valueOf(ok), StateNft.unsafeText(ok));
    }

    /** A collection row saved by an older version re-enters the engine without the screen's check. */
    @Test public void everyTypedPieceOfACollectionIsChecked() {
        assertFalse(StateNft.unsafeMeta(meta()));
        for (String bad : EVIL) {
            StateNft.Meta m = meta(); m.name += bad; assertTrue("name " + bad, StateNft.unsafeMeta(m));
            m = meta(); m.description += bad; assertTrue("description " + bad, StateNft.unsafeMeta(m));
            m = meta(); m.base += bad; assertTrue("base " + bad, StateNft.unsafeMeta(m));
            m = meta(); m.ext += bad; assertTrue("ext " + bad, StateNft.unsafeMeta(m));
            m = meta(); m.icon += bad; assertTrue("icon " + bad, StateNft.unsafeMeta(m));
            m = meta(); m.externalUrl += bad; assertTrue("external " + bad, StateNft.unsafeMeta(m));
        }
    }

    static StateNft.Meta meta() {
        StateNft.Meta m = new StateNft.Meta();
        m.name = "Lots"; m.description = "ten lots"; m.mode = "url"; m.size = 10; m.base = "https://x.example/a/"; m.ext = ".png";
        m.icon = "https://x.example/i.png"; m.externalUrl = "https://x.example/";
        return m;
    }
}
