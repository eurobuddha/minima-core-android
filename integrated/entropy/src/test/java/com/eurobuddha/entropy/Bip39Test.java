package com.eurobuddha.entropy;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JVM parity tests. The fixed vectors (Trezor + the dice vector) were produced by the
 * genuine iancoleman/bip39 code via the MiniDapp harness (tests/verify.js), locking this
 * Java port to the same bits as the web app and the trusted tool.
 */
public class Bip39Test {

    @BeforeClass
    public static void loadWordlist() throws Exception {
        List<String> words = new ArrayList<>(2048);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream("src/main/res/raw/pm_entropy_bip39_english.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) if (!line.isEmpty()) words.add(line.trim());
        }
        Bip39.init(words);
    }

    @Test public void selfTestPasses() {
        assertNull(Bip39.selfTest());
    }

    @Test public void diceVectorMatchesIancoleman() {
        String facesStr = "25262423135241341562253664545432446414112156541463663153452544512632242356432255434125363";
        int[] faces = new int[facesStr.length()];
        for (int i = 0; i < faces.length; i++) faces[i] = facesStr.charAt(i) - '0';
        String bits = Bip39.facesToBits(faces);
        assertEquals(143, bits.length());
        String ent = Bip39.rawTruncate(bits);
        assertEquals(128, ent.length());
        assertEquals("wedding crawl scout then coast emotion bridge spot demise together holiday tray",
                String.join(" ", Bip39.entropyBitsToWords(ent)));
    }

    @Test public void lastWordCountsAllLengths() {
        int[][] cases = {{12, 128}, {15, 64}, {18, 32}, {21, 16}, {24, 8}};
        SecureRandom rng = new SecureRandom();
        for (int[] cs : cases) {
            for (int r = 0; r < 5; r++) {
                List<String> prior = new ArrayList<>();
                for (int i = 0; i < cs[0] - 1; i++) prior.add(Bip39.words().get(rng.nextInt(2048)));
                Bip39.LastWords lw = Bip39.lastWordCandidates(prior);
                assertNull(lw.error);
                assertEquals(cs[1], lw.candidates.size());
                assertEquals(cs[1], new HashSet<>(lw.candidates).size()); // distinct
                for (String cand : lw.candidates) {
                    List<String> full = new ArrayList<>(prior);
                    full.add(cand);
                    assertTrue("candidate must pass checksum", Bip39.check(full));
                }
                // non-candidates must fail
                Set<String> candSet = new HashSet<>(lw.candidates);
                int tried = 0;
                while (tried < 3) {
                    String w = Bip39.words().get(rng.nextInt(2048));
                    if (candSet.contains(w)) continue;
                    tried++;
                    List<String> full = new ArrayList<>(prior);
                    full.add(w);
                    assertFalse("non-candidate must fail checksum", Bip39.check(full));
                }
            }
        }
    }

    @Test public void rejectionThresholdIsExact() {
        assertEquals((4294967296L / 6) * 6, RollEngine.REJECT_THRESHOLD);
    }

    @Test public void stoppingRuleAlwaysYieldsExactEnt() {
        SecureRandom rng = new SecureRandom();
        int[] lengths = {12, 15, 18, 21, 24};
        for (int wc : lengths) {
            int target = Bip39.entTarget(wc);
            for (int r = 0; r < 50; r++) {
                List<Integer> faces = new ArrayList<>();
                while (true) {
                    int[] f = new int[faces.size()];
                    for (int i = 0; i < f.length; i++) f[i] = faces.get(i);
                    if (Bip39.facesToBits(f).length() >= target) break;
                    faces.add(rng.nextInt(6) + 1);
                }
                int[] f = new int[faces.size()];
                for (int i = 0; i < f.length; i++) f[i] = faces.get(i);
                String ent = Bip39.rawTruncate(Bip39.facesToBits(f));
                assertEquals(target, ent.length());
                List<String> phrase = Bip39.entropyBitsToWords(ent);
                assertEquals(wc, phrase.size());
                assertTrue(Bip39.check(phrase));
            }
        }
    }
}
