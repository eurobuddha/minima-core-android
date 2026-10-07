package com.eurobuddha.entropy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BIP39 maths, ported line-for-line from the Entropy MiniDapp (mds/index.html), which is
 * itself bit-identical to iancoleman/bip39 v0.4.3 (proven by tests/verify.js against the
 * genuine extracted code). Parity here is enforced by the same fixed vectors in selfTest()
 * and Bip39Test — including the dice vector whose expected phrase was produced by the
 * embedded trusted code.
 *
 * Dice encoding ("base 6 (dice)", unbiased): face 6 -> 0; digits 0..3 -> two bits, 4,5 -> one bit.
 */
public final class Bip39 {

    private static List<String> WORDS = null;
    private static Map<String, Integer> INDEX = null;

    private Bip39() {}

    public static synchronized void init(List<String> words) {
        if (words == null || words.size() != 2048)
            throw new IllegalStateException("BIP39 wordlist must have 2048 words");
        WORDS = new ArrayList<>(words);
        INDEX = new HashMap<>(4096);
        for (int i = 0; i < WORDS.size(); i++) INDEX.put(WORDS.get(i), i);
    }

    public static void init(Context ctx) {
        List<String> words = new ArrayList<>(2048);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                ctx.getResources().openRawResource(R.raw.pm_entropy_bip39_english), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) { if (!line.isEmpty()) words.add(line.trim()); }
        } catch (Exception e) {
            throw new IllegalStateException("BIP39 wordlist unreadable", e);
        }
        init(words);
    }

    public static boolean ready() { return WORDS != null; }

    public static List<String> words() { return WORDS; }

    public static int wordIndex(String w) {
        Integer i = INDEX.get(w);
        return i == null ? -1 : i;
    }

    /** ENT bits for a phrase length; -1 when the length is not a BIP39 length. */
    public static int entTarget(int wordCount) {
        switch (wordCount) {
            case 12: return 128;
            case 15: return 160;
            case 18: return 192;
            case 21: return 224;
            case 24: return 256;
            default: return -1;
        }
    }

    // face (1-6) -> base6 digit: 6 counts as 0   [bip39-standalone.html:28928]
    private static final String[] EVENT_BITS = {"00", "01", "10", "11", "0", "1"};

    /** Unbiased variable-length bit mapping     [bip39-standalone.html:28783] */
    public static String facesToBits(int[] faces) {
        StringBuilder sb = new StringBuilder(faces.length * 2);
        for (int f : faces) sb.append(EVENT_BITS[f == 6 ? 0 : f]);
        return sb.toString();
    }

    /** iancoleman raw-mode truncation: keep the LAST floor(len/32)*32 bits [bip39-standalone.html:30982] */
    public static String rawTruncate(String bits) {
        int use = (bits.length() / 32) * 32;
        return bits.substring(bits.length() - use);
    }

    private static byte[] bitsToBytes(String bits) {
        byte[] out = new byte[bits.length() / 8];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        return out;
    }

    private static String bytesToBits(byte[] bytes, int bitCount) {
        StringBuilder sb = new StringBuilder(bitCount);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                sb.append((b >> i) & 1);
                if (sb.length() == bitCount) return sb.toString();
            }
        }
        return sb.toString();
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** entropy bits (multiple of 32) -> full phrase, SHA-256 checksum appended (jsbip39 toMnemonic). */
    public static List<String> entropyBitsToWords(String entBits) {
        byte[] entropy = bitsToBytes(entBits);
        String csBits = bytesToBits(sha256(entropy), entBits.length() / 32);
        String all = entBits + csBits;
        List<String> out = new ArrayList<>(all.length() / 11);
        for (int i = 0; i < all.length() / 11; i++)
            out.add(WORDS.get(Integer.parseInt(all.substring(i * 11, i * 11 + 11), 2)));
        return out;
    }

    /** Validate a complete phrase's checksum (jsbip39 self.check). */
    public static boolean check(List<String> phrase) {
        int n = phrase.size();
        if (entTarget(n) < 0) return false;
        StringBuilder bits = new StringBuilder(n * 11);
        for (String w : phrase) {
            int ix = wordIndex(w);
            if (ix < 0) return false;
            String b = Integer.toBinaryString(ix);
            for (int i = b.length(); i < 11; i++) bits.append('0');
            bits.append(b);
        }
        int ent = n * 11 * 32 / 33;
        String entBits = bits.substring(0, ent);
        String claimed = bits.substring(ent);
        String actual = bytesToBits(sha256(bitsToBytes(entBits)), n * 11 - ent);
        return claimed.equals(actual);
    }

    /** Result of the last-word calculator. */
    public static final class LastWords {
        public final List<String> candidates; public final int free, cs, n;
        public final String error; public final int badIndex;
        LastWords(List<String> c, int free, int cs, int n) { candidates = c; this.free = free; this.cs = cs; this.n = n; error = null; badIndex = -1; }
        LastWords(String error, int badIndex) { candidates = null; free = cs = n = 0; this.error = error; this.badIndex = badIndex; }
    }

    /**
     * Given the first N-1 words of an N-word phrase, return EVERY valid final word.
     * The last word packs (11 - ENT/32) free entropy bits plus the checksum:
     * 12w:128, 15w:64, 18w:32, 21w:16, 24w:8 candidates.
     */
    public static LastWords lastWordCandidates(List<String> words) {
        int n = words.size() + 1;
        int ent = entTarget(n);
        if (ent < 0) return new LastWords("enter 11, 14, 17, 20 or 23 words (got " + words.size() + ")", -1);
        StringBuilder prior = new StringBuilder(words.size() * 11);
        for (int i = 0; i < words.size(); i++) {
            int ix = wordIndex(words.get(i));
            if (ix < 0) return new LastWords("word " + (i + 1) + " (\"" + words.get(i) + "\") is not in the BIP39 list", i);
            String b = Integer.toBinaryString(ix);
            for (int j = b.length(); j < 11; j++) prior.append('0');
            prior.append(b);
        }
        int cs = ent / 32;
        int free = 11 - cs;
        List<String> out = new ArrayList<>(1 << free);
        for (int c = 0; c < (1 << free); c++) {
            String fb = Integer.toBinaryString(c);
            StringBuilder entBits = new StringBuilder(prior);
            for (int j = fb.length(); j < free; j++) entBits.append('0');
            entBits.append(fb);
            List<String> full = entropyBitsToWords(entBits.toString());
            out.add(full.get(full.size() - 1));
        }
        return new LastWords(out, free, cs, n);
    }

    private static String hexToBits(String hex) {
        StringBuilder sb = new StringBuilder(hex.length() * 4);
        for (int i = 0; i < hex.length(); i++) {
            String b = Integer.toBinaryString(Integer.parseInt(hex.substring(i, i + 1), 16));
            for (int j = b.length(); j < 4; j++) sb.append('0');
            sb.append(b);
        }
        return sb.toString();
    }

    /**
     * Fixed-vector self-test; returns null when everything passes, else a description.
     * Vectors: Trezor BIP39 + the MiniDapp dice vector whose expected phrase was produced
     * by the genuine iancoleman code (tests/verify.js). Generation must be blocked on failure.
     */
    public static String selfTest() {
        try {
            if (!ready()) return "wordlist not loaded";
            if (WORDS.size() != 2048) return "wordlist has " + WORDS.size() + " words";
            if (!"abandon".equals(WORDS.get(0)) || !"zoo".equals(WORDS.get(2047))) return "wordlist bounds wrong";

            String[][] v = {
                {"00000000000000000000000000000000", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"},
                {"7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "legal winner thank year wave sausage worth useful legal winner thank yellow"},
                {"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote"}
            };
            for (int i = 0; i < v.length; i++) {
                List<String> got = entropyBitsToWords(hexToBits(v[i][0]));
                if (!String.join(" ", got).equals(v[i][1])) return "BIP39 vector " + (i + 1) + " failed";
                if (!check(got)) return "check() rejects valid vector " + (i + 1);
            }

            // dice vector — expected phrase produced by the genuine iancoleman code
            String facesStr = "25262423135241341562253664545432446414112156541463663153452544512632242356432255434125363";
            int[] faces = new int[facesStr.length()];
            for (int i = 0; i < faces.length; i++) faces[i] = facesStr.charAt(i) - '0';
            String bits = facesToBits(faces);
            if (bits.length() != 143) return "dice encoding length check failed";
            String ent = rawTruncate(bits);
            if (ent.length() != 128) return "raw truncation check failed";
            String expect = "wedding crawl scout then coast emotion bridge spot demise together holiday tray";
            if (!String.join(" ", entropyBitsToWords(ent)).equals(expect)) return "dice-to-phrase vector failed";

            // last-word tool vector
            List<String> eleven = new ArrayList<>();
            for (int i = 0; i < 11; i++) eleven.add("abandon");
            LastWords lw = lastWordCandidates(eleven);
            if (lw.error != null || lw.candidates.size() != 128) return "last-word tool: candidate count failed";
            if (!lw.candidates.contains("about")) return "last-word tool: known candidate missing";
            List<String> good = new ArrayList<>(eleven); good.add("about");
            List<String> bad = new ArrayList<>(eleven); bad.add("abandon");
            if (!check(good)) return "last-word tool: validator rejects known phrase";
            if (check(bad)) return "last-word tool: validator accepts invalid phrase";
            for (String c : lw.candidates) {
                List<String> p = new ArrayList<>(eleven); p.add(c);
                if (!check(p)) return "last-word tool: candidate fails checksum";
            }
            return null;
        } catch (Exception e) {
            return "self-test exception: " + e.getMessage();
        }
    }
}
