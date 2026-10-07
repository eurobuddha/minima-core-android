package com.eurobuddha.atomix.eth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Ethereum JSON-RPC client over HttpURLConnection — no okhttp, no web3j HTTP service.
 * All methods are SYNCHRONOUS and BLOCKING: call them off the main thread (the UI uses an executor).
 */
public final class EthRpc {

    /** Keyless mainnet endpoints to fall back to when the primary is down/rate-limited (all verified live
     *  2026-07-18). A single public node returning a 5xx/HTML body, a rate-limit error, or a dead connection
     *  must not block reads or broadcasts — the more independent providers here, the less likely the ETH leg
     *  ever stalls (the exhaustion that stranded a swap: publicnode+drpc down and 1rpc rate-limited at once).
     *  General-purpose full nodes first; the historically rate-limited 1rpc is LAST. */
    private static final String[] FALLBACKS = {
            "https://ethereum-rpc.publicnode.com",
            "https://eth.drpc.org",
            "https://eth-mainnet.public.blastapi.io",
            "https://eth-pokt.nodies.app",
            "https://eth.api.onfinality.io/public",
            "https://api.zan.top/eth-mainnet",
            "https://eth.rpc.blxrbdn.com",
            "https://gateway.tenderly.co/public/mainnet",
            "https://1rpc.io/eth",
    };

    private volatile String url;             // the endpoint that last worked (sticky)
    // MA-1: an immutable snapshot published to a volatile field, so call() iterates without locking and can
    // never see a half-built list (ConcurrentModificationException) if setUrl runs concurrently.
    private volatile java.util.List<String> endpoints = java.util.Collections.emptyList();

    public EthRpc(String url) { setUrl(url); }

    public synchronized void setUrl(String url) {
        // MA-2/MA-3: force a parseable https URL. A non-http scheme would make callOnce's (HttpURLConnection)
        // cast throw ClassCastException — NOT an IOException, so it would escape the fallback loop and crash the
        // caller; plain http would expose balances / timelock reads to MITM. On a bad value fall back to the
        // built-in mainnet default rather than throw: this runs in the constructor on the UI thread
        // (MainActivity.onCreate), where a throw would crash app start.
        String primary = isHttps(url) ? url.trim() : EthNet.MAINNET.defaultRpc;
        this.url = primary;
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(primary);                              // configured primary first
        for (String f : FALLBACKS) set.add(f);         // then the known-good keyless nodes
        this.endpoints = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(set));
    }

    private static boolean isHttps(String u) {
        if (u == null) return false;
        try { return "https".equalsIgnoreCase(new URL(u.trim()).getProtocol()); }
        catch (Exception e) { return false; }
    }

    public String url() { return url; }

    /**
     * Raw JSON-RPC call with endpoint fallback. Tries the active endpoint, then each fallback, on any
     * transport/parse failure (e.g. a 521/HTML body); the first that answers becomes the active one.
     */
    public Object call(String method, JSONArray params) throws IOException {
        StringBuilder errs = new StringBuilder();
        for (String ep : endpoints) {
            try {
                Object r = callOnce(ep, method, params);
                if (!ep.equals(url)) url = ep;         // stick to whichever responded
                return r;
            } catch (IOException e) {                   // try the next endpoint; keep each reason
                if (errs.length() > 0) errs.append("  |  ");
                errs.append(e.getMessage());
            }
        }
        throw new IOException(method + " failed on all RPCs: " + errs);
    }

    private Object callOnce(String url, String method, JSONArray params) throws IOException {
        HttpURLConnection c = null;
        try {
            JSONObject payload = new JSONObject()
                    .put("jsonrpc", "2.0").put("id", 1).put("method", method)
                    .put("params", params == null ? new JSONArray() : params);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);    // a live CDN endpoint connects fast; a slow connect = dead node → fail over quickly
            c.setReadTimeout(30000);      // reads (eth_getLogs can be heavy) get longer
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            String trimmed = body == null ? "" : body.trim();
            // A healthy node answers with a JSON object; anything else (HTML error page, 5xx text,
            // captive portal, empty) gets reported with host+status+snippet so failures are diagnosable.
            if (!trimmed.startsWith("{")) {
                throw new IOException(host(url) + " HTTP " + code + " non-JSON: " + snippet(trimmed));
            }
            JSONObject resp = new JSONObject(trimmed);
            if (resp.has("error")) {
                JSONObject err = resp.optJSONObject("error");
                throw new IOException(host(url) + ": " + (err == null ? snippet(trimmed) : err.optString("message", snippet(trimmed))));
            }
            return resp.opt("result");
        } catch (JSONException e) {
            throw new IOException(host(url) + " parse error: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }
    private static String snippet(String s) {
        if (s == null || s.isEmpty()) return "(empty)";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    public String callStr(String method, JSONArray params) throws IOException {
        Object r = call(method, params);
        return r == null ? null : r.toString();
    }

    public JSONArray getLogs(JSONObject filter) throws IOException {
        Object r = call("eth_getLogs", new JSONArray().put(filter));
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    public BigInteger getBalance(String address) throws IOException {
        return hexToBig(callStr("eth_getBalance", new JSONArray().put(address).put("latest")));
    }

    public BigInteger getTransactionCount(String address) throws IOException {
        return hexToBig(callStr("eth_getTransactionCount", new JSONArray().put(address).put("pending")));
    }

    public BigInteger blockNumber() throws IOException {
        return hexToBig(callStr("eth_blockNumber", new JSONArray()));
    }

    /**
     * The latest block's timestamp in unix seconds — the clock the HTLC vault actually enforces timelocks
     * against (Solidity {@code block.timestamp}). Basing a lock's timelock on THIS instead of the device
     * clock immunizes it from phone-clock skew that could otherwise invert the two legs' expiry ordering.
     */
    public long latestBlockTimestamp() throws IOException {
        Object r = call("eth_getBlockByNumber", new JSONArray().put("latest").put(false));
        if (!(r instanceof JSONObject)) throw new IOException("eth_getBlockByNumber: no block header");
        String ts = ((JSONObject) r).optString("timestamp", "");
        if (ts.isEmpty()) throw new IOException("eth_getBlockByNumber: no timestamp");
        return hexToBig(ts).longValue();
    }

    /** The latest block's EIP-1559 base fee (wei), or ZERO if unavailable. A LEGACY tx's gasPrice must be ≥ the
     *  base fee to be minable, so this is used to FLOOR the gas price (F5) — a stale eth_gasPrice or the hardcoded
     *  fallback could sit below a risen base fee and hang the tx forever. Tolerant: returns ZERO on any failure so
     *  it can only ever RAISE the gas price, never block a send. */
    public BigInteger baseFeePerGasOrZero() {
        try {
            Object r = call("eth_getBlockByNumber", new JSONArray().put("latest").put(false));
            if (!(r instanceof JSONObject)) return BigInteger.ZERO;
            String bf = ((JSONObject) r).optString("baseFeePerGas", "");
            return bf.isEmpty() ? BigInteger.ZERO : hexToBig(bf);
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    /** eth_call to a contract; returns the raw hex return data ("0x...."). */
    public String ethCall(String to, String data) throws IOException {
        try {
            JSONObject tx = new JSONObject().put("to", to).put("data", data);
            return callStr("eth_call", new JSONArray().put(tx).put("latest"));
        } catch (JSONException e) {
            throw new IOException("ETH eth_call build error: " + e.getMessage());
        }
    }

    public String sendRawTransaction(String signedHex) throws IOException {
        return callStr("eth_sendRawTransaction", new JSONArray().put(signedHex));
    }

    public static BigInteger hexToBig(String hex) throws IOException {
        if (hex == null) return BigInteger.ZERO;
        hex = hex.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.isEmpty()) return BigInteger.ZERO;
        // MA-4: a malformed hex body from a broken/hostile RPC (e.g. "0x1g", "pending") threw an UNCHECKED
        // NumberFormatException that crashed the background thread — callers declare only IOException. Wrap it so
        // it surfaces as a normal IOException (the tolerant callers — baseFeePerGasOrZero, EthHtlc.parseNew —
        // already catch it; do NOT return ZERO, which would corrupt nonce/blockNumber/timelock math).
        try { return new BigInteger(hex, 16); }
        catch (NumberFormatException e) { throw new IOException("invalid hex from RPC: " + snippet(hex)); }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);   // NI-2: type-safe charset (minSdk 28 < API 33 for toString(Charset))
        }
    }
}
