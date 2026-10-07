package com.eurobuddha.futurecashnext;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

/**
 * Client for the hosted archive-audit service.
 *
 * <p>The reuse check needs a full scan of the chain archive, which cannot run on a phone, so the
 * spend index lives on a server. Two endpoints (see {@code mds/keyuses/backend/KeyUsesServer.java}):
 * <ul>
 *   <li>{@code GET /keyaudit?keys=…} — per-key spend stats from the archive</li>
 *   <li>{@code GET /reuse?addrs=…} — the witness-confirmed known-reuse database</li>
 * </ul>
 *
 * <p>Because the app derives its own addresses ({@link MinimaAddress}), both calls fire in parallel
 * rather than chaining the second behind the first as the MiniDapp has to.
 *
 * <p>Everything is bounded: https only, no redirects, hard timeouts, a capped read, and requests
 * chunked so a node with many keys can't produce an over-long URL. Callbacks are delivered on the
 * main thread and suppressed once the Activity is gone.
 */
public final class AuditApi {

    private static final String BASE = "https://eurobuddha.com";
    private static final String KEYAUDIT = BASE + "/keyaudit";
    private static final String REUSE = BASE + "/reuse";

    /**
     * Entries per request. The backend's own cap is MAX_KEYS=256 (KeyUsesServer.java:46) but the
     * binding constraint is URL length: 64 entries x 67 chars is already ~4.4 KB, close to Apache's
     * 8 KB LimitRequestLine. The shipped MiniDapp does not chunk at all and breaks outright on a
     * node with more than 256 keys.
     */
    private static final int CHUNK = 64;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** Hard cap on a single response body so a hostile or broken server can't exhaust memory. */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    public interface Cb {
        /** @param archiveTip the block the archive index reaches, or -1 if the server didn't say */
        void onResult(List<KeyAudit.Usage> usage, List<KeyAudit.Reuse> reuse, long archiveTip);
        void onError(String message);
    }

    // NULLABLE. In KeyUses this was always an Activity and post() could ask it whether it was still
    // alive. Here the same audit also runs from GuardianService, which has no Activity and must NOT
    // have its callbacks silently dropped — a null act means "no view lifecycle to respect", and
    // `released` alone gates delivery.
    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Two endpoints x a handful of chunks; a small pool keeps them concurrent without a thread each.
    private final ExecutorService exec = Executors.newFixedThreadPool(4);
    private volatile boolean released = false;

    public AuditApi(Activity activity) {
        this.act = activity;
    }

    /** Headless audit, for the background guardian. */
    public AuditApi() {
        this.act = null;
    }

    public void onDestroy() {
        released = true;
        exec.shutdownNow();
    }

    /**
     * Run the full audit lookup.
     *
     * @param publicKeys 0x public keys from {@code keys action:list}
     * @param addresses  0x addresses derived locally from those keys (same order; may hold nulls)
     */
    public void audit(final List<String> publicKeys, final List<String> addresses, final Cb cb) {
        final List<String> keysClean = nonNull(publicKeys);
        final List<String> addrClean = nonNull(addresses);

        final List<KeyAudit.Usage> usage = new ArrayList<>();
        final List<KeyAudit.Reuse> reuse = new ArrayList<>();
        final long[] archiveTip = {-1};

        final List<List<String>> keyChunks = chunk(keysClean);
        final List<List<String>> addrChunks = chunk(addrClean);
        final int total = keyChunks.size() + addrChunks.size();
        if (total == 0) {
            post(() -> cb.onResult(usage, reuse, -1));
            return;
        }

        final AtomicInteger outstanding = new AtomicInteger(total);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final String[] firstError = {null};

        final Runnable settle = () -> {
            if (outstanding.decrementAndGet() != 0) return;
            post(() -> {
                if (failed.get()) cb.onError(firstError[0]);
                else cb.onResult(usage, reuse, archiveTip[0]);
            });
        };

        for (final List<String> c : keyChunks) {
            exec.execute(() -> {
                try {
                    final String url = KEYAUDIT + "?keys=" + enc(join(c));
                    final JSONObject j = getJson(url);
                    synchronized (usage) {
                        final long tip = j.optLong("archive_tip", -1);
                        if (tip > archiveTip[0]) archiveTip[0] = tip;
                        parseUsage(j.optJSONArray("keys"), usage);
                    }
                } catch (Throwable t) {
                    if (failed.compareAndSet(false, true)) firstError[0] = friendly(t);
                }
                settle.run();
            });
        }

        for (final List<String> c : addrChunks) {
            exec.execute(() -> {
                try {
                    final String url = REUSE + "?addrs=" + enc(join(c));
                    final JSONObject j = getJson(url);
                    synchronized (reuse) {
                        parseReuse(j.optJSONArray("results"), reuse);
                    }
                } catch (Throwable t) {
                    if (failed.compareAndSet(false, true)) firstError[0] = friendly(t);
                }
                settle.run();
            });
        }
    }

    /* ---------- parsing ---------- */

    private static void parseUsage(JSONArray arr, List<KeyAudit.Usage> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String pk = o.optString("publickey", "");
            if (pk.isEmpty()) continue;   // the backend inlines {publickey, error} for bad shapes
            // rowStats(): the hosted backend emits spend_blocks/spent_coins; the CoinScanner JSON
            // form uses onchain_signatures/spent_coins_upperbound (index.html:219-226).
            final long sigs = o.has("spend_blocks")
                    ? o.optLong("spend_blocks", 0) : o.optLong("onchain_signatures", 0);
            final long coins = o.has("spent_coins")
                    ? o.optLong("spent_coins", 0) : o.optLong("spent_coins_upperbound", 0);
            out.add(new KeyAudit.Usage(pk, o.optString("address", ""), sigs, coins));
        }
    }

    private static void parseReuse(JSONArray arr, List<KeyAudit.Reuse> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String addr = o.optString("address", "");
            if (addr.isEmpty()) continue;
            out.add(new KeyAudit.Reuse(addr, o.optString("miniaddress", ""),
                    o.optBoolean("reused", false), o.optLong("reuse_count", 0)));
        }
    }

    /* ---------- transport ---------- */

    private static JSONObject getJson(String url) throws Exception {
        final URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IllegalArgumentException("refusing a non-https audit url");
        }

        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // A redirect could downgrade to http or point somewhere else entirely; refuse instead.
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");

            final int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("audit service returned HTTP " + code);
            }

            final String body = readBounded(conn.getInputStream());
            final JSONObject j = new JSONObject(body);
            if (!j.optBoolean("status", false)) {
                throw new IllegalStateException(j.optString("error", "audit service refused the request"));
            }
            return j;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBounded(InputStream in) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IllegalStateException("audit response too large");
            }
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    /* ---------- helpers ---------- */

    private void post(Runnable r) {
        main.post(() -> {
            if (released) return;
            if (act != null && (act.isFinishing() || act.isDestroyed())) return;
            r.run();
        });
    }

    private static List<String> nonNull(List<String> in) {
        final List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) if (s != null && !s.isEmpty()) out.add(s);
        return out;
    }

    private static List<List<String>> chunk(List<String> in) {
        final List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i += CHUNK) {
            out.add(new ArrayList<>(in.subList(i, Math.min(in.size(), i + CHUNK))));
        }
        return out;
    }

    private static String join(List<String> in) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(in.get(i));
        }
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** One user-facing message for every transport failure — matches the dapp's copy. */
    private static String friendly(Throwable t) {
        return "Could not reach the audit service. Check your connection and try again.";
    }
}
