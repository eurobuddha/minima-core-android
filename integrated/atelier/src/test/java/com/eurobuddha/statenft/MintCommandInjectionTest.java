package com.eurobuddha.statenft;

import static org.junit.Assert.*;

import org.junit.Test;

/** A typed mint field rides `tokencreate name:{...}` and the node splits command text at every ";": Atelier never
 *  sends one. The JSON metadata escapes control characters, so a real mint command carries none. */
public class MintCommandInjectionTest {
    @Test public void aSemicolonInATypedFieldNeverReachesTheNode() {
        String evil = StateNft.tokenCreateCommand("Gold;send amount:1000 address:0x" + "66".repeat(32), "line one\nline two",
                "GLD", "https://example.com/i.png", 1000, 0);
        assertTrue(NodeApi.unsafe(evil));
        String ok = StateNft.tokenCreateCommand("Gold", "line one\nline two", "GLD", "https://example.com/i.png", 1000, 0);
        assertFalse(ok, NodeApi.unsafe(ok));
        for (String bad : new String[]{null, "coins\r", "coins\u0000", "coins\u007f", "txnbasics id:a;txnpost id:a"})
            assertTrue(String.valueOf(bad), NodeApi.unsafe(bad));
    }
}
