package com.eurobuddha.statenft;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** A typed mint field rides `tokencreate name:{...}`. The node splits command text at every ";", counts { } [ ] with
 *  one depth counter even inside a JSON string, ends a quoted value at the first '"' and has no backslash escape:
 *  a typed field holding any of them is refused, never escaped, and no command is built. */
public class MintCommandInjectionTest {
    static final String SEND = "send amount:1000 address:0x" + "66".repeat(32);
    /** One per forbidden character: ; { } [ ] " \ and control characters. */
    static final String[] EVIL = {"Gold;" + SEND, "Nice art] amount:1 decimals:0 burn:1000 webvalidate:", "a[b", "a{b", "a}b",
            "say \"hi\" burn:1000", "back\\slash", "line one\nline two", "tab\there", "nul\u0000x", "del\u007f"};   // inside the text: the builders trim the ends

    static boolean refused(Supplier<String> build) {
        try { build.get(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static JSONArray traits(String type, String value) {
        return StateNft.traitsToAttributes(Collections.singletonList(new String[]{type, value}));
    }

    @Test public void theReviewersNftNameNeverBecomesACommand() {
        assertTrue(refused(() -> StateNft.nftCreateCommand("Nice art] amount:1 decimals:0 burn:1000 webvalidate:", "study",
                "<artimage>AAAA</artimage>", "", "", "", null, 1, "0x" + "ab".repeat(32))));
    }

    @Test public void everyTypedNftFieldIsRefusedWithEveryForbiddenCharacter() {
        for (String bad : EVIL) {
            assertTrue("name " + bad, refused(() -> StateNft.nftCreateCommand(bad, "d", "https://x/y.png", "", "", "", null, 1, "")));
            assertTrue("description " + bad, refused(() -> StateNft.nftCreateCommand("n", bad, "https://x/y.png", "", "", "", null, 1, "")));
            assertTrue("url " + bad, refused(() -> StateNft.nftCreateCommand("n", "d", "https://x/" + bad, "", "", "", null, 1, "")));
            assertTrue("owner " + bad, refused(() -> StateNft.nftCreateCommand("n", "d", "https://x/y.png", bad, "", "", null, 1, "")));
            assertTrue("external " + bad, refused(() -> StateNft.nftCreateCommand("n", "d", "https://x/y.png", "", "https://x/" + bad, "", null, 1, "")));
            assertTrue("webvalidate " + bad, refused(() -> StateNft.nftCreateCommand("n", "d", "https://x/y.png", "", "", "https://x/" + bad, null, 1, "")));
            assertTrue("trait type " + bad, refused(() -> StateNft.nftCreateCommand("n", "d", "https://x/y.png", "", "", "", traits(bad, "v"), 1, "")));
            assertTrue("trait value " + bad, refused(() -> StateNft.nftCreateCommand("n", "d", "https://x/y.png", "", "", "", traits("t", bad), 1, "")));
        }
    }

    @Test public void everyTypedTokenFieldIsRefusedWithEveryForbiddenCharacter() {
        for (String bad : EVIL) {
            assertTrue("name " + bad, refused(() -> StateNft.tokenCreateCommand(bad, "d", "GLD", "https://x/i.png", 1000, 0)));
            assertTrue("description " + bad, refused(() -> StateNft.tokenCreateCommand("n", bad, "GLD", "https://x/i.png", 1000, 0)));
            assertTrue("ticker " + bad, refused(() -> StateNft.tokenCreateCommand("n", "d", bad, "https://x/i.png", 1000, 0)));
            assertTrue("icon " + bad, refused(() -> StateNft.tokenCreateCommand("n", "d", "GLD", "https://x/" + bad, 1000, 0)));
            List<String[]> key = Collections.singletonList(new String[]{bad, "v"}), value = Collections.singletonList(new String[]{"k", bad});
            assertTrue("pair key " + bad, refused(() -> StateNft.tokenCreateCommand("n", "d", "GLD", "", 1000, 0, key)));
            assertTrue("pair value " + bad, refused(() -> StateNft.tokenCreateCommand("n", "d", "GLD", "", 1000, 0, value)));
        }
    }

    @Test public void ordinaryTextStillMintsAndTheCommandKeepsItsAppBuiltJson() {
        String nft = StateNft.nftCreateCommand("Nocturne 04 — Kyiv, oil & ink (2026)", "Україна 中文: a study, 50% off!", "<artimage>AAAA+/=</artimage>",
                "Alice", "https://alice.example/n?a=1&b=2", "", traits("Mood", "calm"), 3, "0x" + "ab".repeat(32));
        assertTrue(nft, nft.contains("\"attributes\":[{") && nft.endsWith(" amount:3 decimals:0 signtoken:0x" + "ab".repeat(32)));
        assertFalse(nft, NodeApi.unsafe(nft));
        String token = StateNft.tokenCreateCommand("Gold", "a coin", "GLD", "https://example.com/i.png", 1000, 0,
                Arrays.asList(new String[]{"website", "https://example.com"}, new String[]{"team", "A & B"}));
        assertTrue(token, token.endsWith(" amount:1000 decimals:0"));
        assertFalse(token, NodeApi.unsafe(token));
        for (String bad : new String[]{null, "coins\r", "coins\u0000", "coins\u007f", "txnbasics id:a;txnpost id:a"})
            assertTrue(String.valueOf(bad), NodeApi.unsafe(bad));
    }
}
