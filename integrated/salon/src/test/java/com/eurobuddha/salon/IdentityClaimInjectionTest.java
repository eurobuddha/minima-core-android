package com.eurobuddha.salon;

import static org.junit.Assert.*;

import org.junit.Test;

/** The identity claim's name, bio and hosted URL go into `tokencreate name:{...}`, which the node splits at every ";". */
public class IdentityClaimInjectionTest {
    static final String SEND = "send amount:1000 address:0x" + "66".repeat(32) + " tokenid:0x00";

    @Test public void aSemicolonOrControlCharacterInTheClaimIsRefused() {
        for (String bad : new String[]{"Alice;" + SEND, "bio\u007f", "a\nb", "{x}"}) assertFalse(bad, Args.isSafeMeta(bad));
        for (String ok : new String[]{"Alice", "Painter, Kyiv — oil & ink [2026]", "Україна 中文"}) assertTrue(ok, Args.isSafeMeta(ok));
        for (String bad : new String[]{null, "", "https://host.example/alice;" + SEND, "https://host.example/a b",
                "ftp://host.example/alice", "https://host.example/\"x", "javascript:alert(1)"})
            assertFalse(String.valueOf(bad), Args.isSafeUrl(bad));
        assertTrue(Args.isSafeUrl("https://alice.github.io/salon/alice.html"));
        assertTrue(Args.isSafeUrl("http://192.168.1.4:8080/salon/alice.html?v=2"));
    }
}
