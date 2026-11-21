package com.eurobuddha.entropy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.Charset;

/**
 * FIPS-202 known-answer tests. These are the published SHA3-256 vectors, not values produced by
 * this implementation — the point is to catch a wrong permutation, wrong padding, or a
 * Keccak-vs-SHA3 domain mix-up.
 */
public class Sha3Test {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static String h(String s) {
        return Sha3.hex(Sha3.hash(s.getBytes(UTF8)));
    }

    @Test
    public void emptyString() {
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
                Sha3.hex(Sha3.hash(new byte[0])));
    }

    @Test
    public void abc() {
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", h("abc"));
    }

    @Test
    public void fourFiftyOneBitMessage() {
        assertEquals("41c0dba2a9d6240849100376a8235e2c82e1b9998a999e21db32dd97496d3376",
                h("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"));
    }

    @Test
    public void multiBlockMessage() {
        // 112 bytes: crosses the 136-byte rate only after padding, exercising two absorb blocks
        assertEquals("916f6061fe879741ca6469b43971dfdb28b1a32dc36cb3254e812be27aad1d18",
                h("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno"
                        + "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"));
    }

    @Test
    public void exactlyOneRateBlock() {
        // 136 bytes of 'a' — a message exactly one rate block long, so the pad10*1 lands in a
        // block of its own. A classic off-by-one site.
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 136; i++) sb.append('a');
        final String at136 = h(sb.toString());
        assertEquals("3fc5559f14db8e453a0a3091edbd2bc25e11528d81c66fa570a4efdcc2695ee1", at136);
        assertTrue(!at136.equals(h(sb.substring(0, 135))));
        assertTrue(!at136.equals(h(sb + "a")));
    }

    @Test
    public void matchesTheJdkSha3OnRollSizedInputs() throws Exception {
        // a roll hashes exactly 64 bytes (node 32 ++ device 32); also cover the rate edges around it
        final java.util.Random r = new java.util.Random(7);
        for (int len : new int[]{0, 1, 32, 63, 64, 65, 135, 136, 137, 272, 1000}) {
            final byte[] in = new byte[len];
            r.nextBytes(in);
            assertEquals("length " + len,
                    Sha3.hex(java.security.MessageDigest.getInstance("SHA3-256").digest(in)),
                    Sha3.hex(Sha3.hash(in)));
        }
    }

    @Test
    public void selfTestPasses() {
        assertTrue(Sha3.selfTest());
    }

    /** Point release item 13: a failed SHA3 self-test blocks generation at startup. */
    @Test public void aFailedSha3SelfTestBlocksGeneration() {
        assertTrue(Sha3.selfTest());
        assertNull(MainActivity.startupError(null, true));
        assertEquals("SHA3-256 known-answer test failed", MainActivity.startupError(null, false));
        assertEquals("bip39", MainActivity.startupError("bip39", true));
    }
}
