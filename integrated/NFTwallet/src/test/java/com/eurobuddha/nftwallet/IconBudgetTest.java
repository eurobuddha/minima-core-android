package com.eurobuddha.nftwallet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The token record travels with EVERY transaction that touches the token, and it is immutable.
 * A collection whose icon is a full-size plate is bricked on creation — its first MOVE was
 * 80438 bytes against the 64KB TxPoW cap. So an oversized icon must never reach the metadata.
 */
public class IconBudgetTest {

    private static String chars(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('A');
        return sb.toString();
    }

    /**
     * org.json on the JVM escapes "&lt;/" as "&lt;\\/" (an HTML-safety habit); Android's does not.
     * Both decode to the same string, so normalise before asserting on the shape.
     */
    private static String json(StateNft.Meta m) {
        return StateNft.tokenMetadata(m).toString().replace("\\/", "/");
    }

    private static StateNft.Meta meta(String icon) {
        StateNft.Meta m = new StateNft.Meta();
        m.name = "WNP";
        m.mode = "embed";
        m.size = 3;
        m.icon = icon;
        return m;
    }

    /** The exact failure: item #1's 14540-char plate used verbatim as the icon. */
    @Test public void oversizedEmbeddedIconIsDropped() {
        String json = json(meta(chars(14540)));
        assertFalse(json, json.contains("artimage"));
        assertTrue(json, json.contains("\"name\":\"WNP\""));   // the rest of the record survives
    }

    @Test public void iconAtTheBudgetIsKept() {
        String json = json(meta(chars(ImageTools.ICON_BUDGET)));
        assertTrue(json, json.contains("<artimage>"));
        assertTrue(json, json.contains("</artimage>"));       // closing tag, or it never renders
    }

    /** A URL icon costs a handful of bytes however long it looks — never dropped. */
    @Test public void urlIconsAreNeverDropped() {
        String url = "https://example.com/" + chars(200) + ".png";
        String json = json(meta(url));
        assertTrue(json, json.contains(url));
        assertFalse(json, json.contains("artimage"));
    }

    @Test public void noIconWritesNoUrlField() {
        assertFalse(json(meta("")).contains("\"url\""));
    }
}
