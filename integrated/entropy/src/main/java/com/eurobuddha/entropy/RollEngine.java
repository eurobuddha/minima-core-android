package com.eurobuddha.entropy;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The die-roll engine, ported from the Entropy MiniDapp and the Zero Edge Casino resolve
 * maths: face = SHA3(node_secret ++ device_secret) -> first 4 bytes -> mod 6, with
 * rejection sampling above floor(2^32/6)*6 so the result is EXACTLY uniform.
 *
 * Two independent CSPRNGs: the Minima node's `random` command and Android's SecureRandom.
 * The SHA3 is asked of the node's `hash` command — bit-identical to the KISS VM script
 *   LET h=SHA3(CONCAT(a b)) LET n=NUMBER(SUBSET(0 4 h)) LET r=n%6
 * (casino contract, proven on-chain), and every roll's inputs are kept for the audit log
 * so it can be re-verified with `runscript` in any Minima terminal.
 *
 * THE NODE DOES NOT DECIDE A DIE FACE (0.1.15). Whoever answers the node API could answer
 * `hash` with a value of its choosing and so choose the face. The same SHA3-256 is worked out
 * here first ({@link Sha3}; Minima's `hash data:0x…` defaults to SHA3-256 of the raw bytes,
 * Crypto.hashData), and the node's answer is used only when it IS that digest (any letter case).
 * The node's `random` must be 0x + 64 hex (the command's default 32 bytes) and never one it gave
 * before, so the bytes hashed are always exactly 64 (node 32 ++ device 32). Anything else throws
 * the whole roll away with {@link #NODE_BAD}; nothing falls back to one source in silence.
 *
 * When the node is unavailable the engine falls back to device-only rolls (single source,
 * byte rejection sampling) and labels them as such — the UI must surface that honestly.
 */
public final class RollEngine {

    /** floor(2^32/6)*6 — 4-byte values at or above this are rerolled (zero modulo bias). */
    public static final long REJECT_THRESHOLD = 4294967292L;
    private static final int REJECT_LIMIT = 25;

    public static final String SRC_NODE_DEVICE = "node-sha3+device";
    public static final String SRC_DEVICE_ONLY = "device-only";
    public static final String SRC_PHYSICAL = "physical";

    /** The node's answer was refused: the roll (or pick) it was for is not used. */
    public static final String NODE_BAD = "The node's answer did not match; this roll was not used.";

    public static final class Audit {
        public final String src, a, b; public final long n; public final int face;
        public Audit(String src, String a, String b, long n, int face) {
            this.src = src; this.a = a; this.b = b; this.n = n; this.face = face;
        }
    }

    public interface RollCb {
        void onFace(int face, Audit audit);
        void onError(String message);
    }

    public interface NCb {
        void onN(long n, String src);
        void onError(String message);
    }

    public interface VerifyCb {
        void onVerified(boolean ok, String why);
    }

    /** How the engine reaches the node (NodeApi::cmd). Callbacks on the main thread. */
    public interface Node {
        void cmd(String command, NodeApi.Cb cb);
    }

    private interface HashCb {
        void onHash(String a, String b, String hash);
        void onError(String message);
    }

    private final Node node;
    private final SecureRandom rng = new SecureRandom();
    private final Set<String> seenRandoms = new HashSet<>();   // every node random taken, lower case
    private volatile boolean nodeOk = false;

    public RollEngine(Node node) { this.node = node; }

    public void setNodeOk(boolean ok) { nodeOk = ok; }

    public boolean nodeOk() { return nodeOk; }

    public String deviceSecretHex() {
        byte[] b = new byte[32];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder("0x");
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Single-source fallback: one uniform die face from SecureRandom via byte rejection. */
    public int deviceOnlyFace() {
        byte[] u = new byte[1];
        int v;
        do { rng.nextBytes(u); v = u[0] & 0xFF; } while (v >= 252);
        return (v % 6) + 1;
    }

    /** Roll one die. Callbacks arrive on the main thread (NodeApi guarantees it). */
    public void rollOne(RollCb cb) { rollOne(cb, 0); }

    private void rollOne(RollCb cb, int attempt) {
        if (attempt > REJECT_LIMIT) { cb.onError("Entropy source misbehaving (rejection limit)"); return; }
        if (!nodeOk || node == null) {
            int face = deviceOnlyFace();
            cb.onFace(face, new Audit(SRC_DEVICE_ONLY, "", "", face - 1, face));
            return;
        }
        nodeHash(new HashCb() {
            @Override public void onHash(String a, String b, String hash) {
                long n = Long.parseLong(hash.substring(2, 10), 16);   // checked: 0x + 64 hex
                if (n >= REJECT_THRESHOLD) { rollOne(cb, attempt + 1); return; }
                int face = (int) (n % 6) + 1;
                cb.onFace(face, new Audit(SRC_NODE_DEVICE, a, b, n, face));
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    /**
     * One uniform 32-bit value for pickers (e.g. last-word random choice — candidate
     * counts are powers of two so mod is exactly uniform). Node+device when the node is
     * verified, device-only when it is not; src reported so the UI can label it honestly.
     * A node that answers wrongly gives onError: nothing is picked.
     */
    public void randomN(NCb cb) {
        if (!nodeOk || node == null) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); return; }
        nodeHash(new HashCb() {
            @Override public void onHash(String a, String b, String hash) { cb.onN(Long.parseLong(hash.substring(2, 10), 16), SRC_NODE_DEVICE); }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    /** Prove the node by the very check every roll passes; "verified" means one node + device hash matched here. */
    public void verify(VerifyCb cb) {
        if (node == null) { nodeOk = false; cb.onVerified(false, "no node"); return; }
        nodeHash(new HashCb() {
            @Override public void onHash(String a, String b, String hash) { nodeOk = true; cb.onVerified(true, null); }
            @Override public void onError(String m) { nodeOk = false; cb.onVerified(false, m); }
        });
    }

    /** One node random with one device random, hashed by the node and believed only when checked here. */
    private void nodeHash(HashCb cb) {
        final String b = deviceSecretHex();
        node.cmd("random", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                final String a = resp(r, "random");
                if (!freshRandom(a)) { cb.onError(NODE_BAD); return; }
                final String input = a + b.substring(2);
                node.cmd("hash data:" + input, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String hash = checkedHash(input, resp(h, "hash"));
                        if (hash == null) cb.onError(NODE_BAD); else cb.onHash(a, b, hash);
                    }
                    @Override public void onError(String m) { cb.onError(m); }
                });
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    /** The node's random: 0x + exactly 64 hex, and not one it gave before (recorded when taken). */
    boolean freshRandom(String a) {
        if (a == null || !a.matches("0x[0-9a-fA-F]{64}")) return false;
        synchronized (seenRandoms) { return seenRandoms.add(a.toLowerCase(Locale.ROOT)); }
    }

    /** SHA3-256 of the bytes a 0x hex string spells, as 0x lower-case hex: what a truthful node answers to `hash data:`. */
    static String localHash(String hex) {
        byte[] in = new byte[(hex.length() - 2) / 2];
        for (int i = 0; i < in.length; i++) in[i] = (byte) Integer.parseInt(hex.substring(2 + 2 * i, 4 + 2 * i), 16);
        return "0x" + Sha3.hex(Sha3.hash(in));
    }

    /** The digest of a roll's 64 bytes (0x + 128 hex) when the node's answer equals it (any case), else null. */
    static String checkedHash(String input, String nodeHash) {
        if (input == null || !input.matches("0x[0-9a-fA-F]{128}") || nodeHash == null) return null;
        String mine = localHash(input);
        return mine.equalsIgnoreCase(nodeHash) ? mine : null;
    }

    private static String resp(JSONObject r, String key) {
        if (r == null || !r.optBoolean("status", false)) return "";
        JSONObject resp = r.optJSONObject("response");
        if (resp == null) return "";
        return resp.optString(key, "");
    }
}
