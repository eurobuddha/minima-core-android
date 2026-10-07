package com.eurobuddha.entropy;

import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * The die-roll engine, ported from the Entropy MiniDapp and the Zero Edge Casino resolve
 * maths: face = SHA3(node_secret ++ device_secret) -> first 4 bytes -> mod 6, with
 * rejection sampling above floor(2^32/6)*6 so the result is EXACTLY uniform.
 *
 * Two independent CSPRNGs: the Minima node's `random` command and Android's SecureRandom.
 * The SHA3 is computed by the node's `hash` command — bit-identical to the KISS VM script
 *   LET h=SHA3(CONCAT(a b)) LET n=NUMBER(SUBSET(0 4 h)) LET r=n%6
 * (casino contract, proven on-chain), and every roll's inputs are kept for the audit log
 * so it can be re-verified with `runscript` in any Minima terminal.
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
    }

    private final NodeApi node;
    private final SecureRandom rng = new SecureRandom();
    private volatile boolean nodeOk = false;

    public RollEngine(NodeApi node) { this.node = node; }

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
        final String b = deviceSecretHex();
        node.cmd("random", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                String a = resp(r, "random");
                if (a.isEmpty()) { cb.onError("Node random failed"); return; }
                combinedN(a, b, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String hash = resp(h, "hash");
                        // strict shape check — a malformed hash must fail loudly, never mis-derive
                        if (hash.isEmpty() || !hash.startsWith("0x") || hash.length() < 10) { cb.onError("Node hash failed"); return; }
                        long n;
                        try { n = Long.parseLong(hash.substring(2, 10), 16); }
                        catch (NumberFormatException e) { cb.onError("Node hash unparseable"); return; }
                        if (n >= REJECT_THRESHOLD) { rollOne(cb, attempt + 1); return; }
                        int face = (int) (n % 6) + 1;
                        cb.onFace(face, new Audit(SRC_NODE_DEVICE, a, b, n, face));
                    }
                    @Override public void onError(String m) { cb.onError(m); }
                });
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    private void combinedN(String a, String b, NodeApi.Cb cb) {
        node.cmd("hash data:" + a + b.substring(2), cb);
    }

    /**
     * One uniform 32-bit value for pickers (e.g. last-word random choice — candidate
     * counts are powers of two so mod is exactly uniform). Node+device when available,
     * device-only otherwise; src reported so the UI can label it honestly.
     */
    public void randomN(NCb cb) {
        if (!nodeOk || node == null) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); return; }
        final String b = deviceSecretHex();
        node.cmd("random", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                String a = resp(r, "random");
                if (a.isEmpty()) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); return; }
                combinedN(a, b, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject h) {
                        String hash = resp(h, "hash");
                        if (hash.isEmpty() || !hash.startsWith("0x") || hash.length() < 10) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); return; }
                        try { cb.onN(Long.parseLong(hash.substring(2, 10), 16), SRC_NODE_DEVICE); }
                        catch (NumberFormatException e) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); }
                    }
                    @Override public void onError(String m) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); }
                });
            }
            @Override public void onError(String m) { cb.onN(rng.nextInt() & 0xFFFFFFFFL, SRC_DEVICE_ONLY); }
        });
    }

    private static String resp(JSONObject r, String key) {
        if (r == null || !r.optBoolean("status", false)) return "";
        JSONObject resp = r.optJSONObject("response");
        if (resp == null) return "";
        return resp.optString(key, "");
    }
}
