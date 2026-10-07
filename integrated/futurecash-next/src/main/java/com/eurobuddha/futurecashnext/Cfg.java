package com.eurobuddha.futurecashnext;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Config, verdict and activity log — the native replacement for the MiniDapp's {@code MDS.keypair}.
 *
 * In the MiniDapp this store had a second job: it was the ONLY channel between the page (which ran
 * all HTTP) and the service worker (which ran all chain writes), because the two are separate JS
 * contexts that cannot call each other. Here the guardian and the UI are the same process, so the
 * cross-context dance is gone — but the KEYS and their meanings are kept identical, because the
 * safety rules written against them (audit freshness, the at-risk set, the reuse verdict on our own
 * destination) are the part that was hard to get right and is worth porting verbatim.
 *
 * Everything is stored as String, as it was in keypair storage, so the semantics of "missing",
 * "empty" and "0" stay exactly what the ported logic expects.
 */
public final class Cfg {

    private static final String PREFS = "futurecash_guardian";

    /* ---- keys: same names as the MiniDapp, so the ported rules read the same ---- */
    public static final String ENABLED           = "enabled";              // at-risk rescue on/off
    public static final String AUTO_COLLECT_SAFE = "auto_collect_safe";
    public static final String SAFE_ADDRESS      = "safe_address";
    public static final String SAFE_MODE         = "safe_mode";            // "external" | "samenode"
    public static final String CLEAN_ADDR        = "clean_addr";           // {"mx":..,"hex":..}
    public static final String ATRISK_ADDRS      = "atrisk_addrs";         // JSON array
    public static final String AUDIT_OK          = "audit_ok";             // "1" only on a COMPLETE audit
    public static final String AUDIT_AT          = "audit_at";             // epoch ms, written LAST
    public static final String AUDIT_USES        = "audit_uses";           // key-set fingerprint
    public static final String AUDIT_LAST_ERR    = "audit_last_err";
    public static final String SAFE_REUSED       = "safe_reused";          // {"addr":..,"count":..}
    public static final String NOTIFY            = "notify";
    public static final String SCRIPT_ADDRESS    = "script_address";
    public static final String LAST_TIP          = "last_tip";
    public static final String LAST_ACTION       = "last_action";
    public static final String LOG               = "fc_log";
    public static final String AUDIT_LOGGED_N    = "audit_logged_n";
    public static final String GUARDIAN_ON       = "guardian_on";          // the daemon itself

    private static final int LOG_MAX_LINES = 80;
    /** Activity-log line cap. Clips the TAIL, so ids must come FIRST in a message — see Guardian. */
    private static final int LOG_MSG_MAX = 300;

    private final SharedPreferences sp;

    public Cfg(Context ctx) {
        // Guardian and UI both touch this from different threads; SharedPreferences is thread-safe
        // and the guardian only ever writes from its single worker thread.
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Can the guardian actually rescue? This is the precondition for running it AT ALL.
     *
     * <p>The whole point of the daemon is winning a race: collecting no longer needs a signature
     * once a stake matures, so an attacker holding a leaked key can collect it themselves, land the
     * money on the address they can forge for, and take it. A guardian with nowhere to sweep to
     * cannot compete in that race — it just watches. Worse, it says it is protecting you.
     *
     * <p>Required even on a node with no reuse today, because "today" is not the guarantee that
     * matters: the reuse database refreshes every 30 minutes and a key can sign again, so a clean
     * node can be flagged at 3am — precisely when nobody is looking. Satisfying this costs one tap
     * (a fresh same-node address), and on a clean node it simply never gets used.
     */
    public boolean rescueReady() {
        return Node.validMx(get(SAFE_ADDRESS, null)) && is(ENABLED, false);
    }

    public String get(String key, String dflt) {
        final String v = sp.getString(key, null);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    public void set(String key, String value) {
        // commit(), not apply(): the guardian runs on a worker thread and the process can be killed
        // the moment it goes idle. An unflushed "we already posted this collect" is how a coin gets
        // collected twice.
        sp.edit().putString(key, value == null ? "" : value).commit();
    }

    public boolean is(String key, boolean dflt) {
        final String v = get(key, null);
        if (v == null) return dflt;
        return "1".equals(v);
    }

    public void setBool(String key, boolean on) { set(key, on ? "1" : "0"); }

    public long getLong(String key) {
        try { return Long.parseLong(get(key, "0")); } catch (Exception e) { return 0L; }
    }

    public void remove(String key) { sp.edit().remove(key).commit(); }

    /* ---------- the at-risk set ---------- */

    public JSONArray atRisk() {
        try { return new JSONArray(get(ATRISK_ADDRS, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /* ---------- clean (minted) destination, stored as ONE atomic blob ---------- */

    /** @return {mx, hex} or null. Stored together so a concurrent mint can't pair A's hex with B's mx. */
    public String[] cleanAddr() {
        final String raw = get(CLEAN_ADDR, null);
        if (raw == null) return null;
        try {
            final JSONObject o = new JSONObject(raw);
            final String mx = o.optString("mx", ""), hex = o.optString("hex", "");
            if (mx.isEmpty() || hex.isEmpty()) return null;
            return new String[]{mx, hex};
        } catch (Exception e) { return null; }
    }

    public void setCleanAddr(String mx, String hex) {
        try {
            final JSONObject o = new JSONObject();
            o.put("mx", mx);
            o.put("hex", hex);
            set(CLEAN_ADDR, o.toString());
        } catch (Exception ignored) {}
    }

    /* ---------- activity log ---------- */

    public static final String LVL_INFO = "info", LVL_WARN = "warn", LVL_ERROR = "error";

    /**
     * Newest first, capped. Messages carry full ids in " | key:value" segments AFTER the summary,
     * and the cap clips the tail — so a long node error can push an id out of the line. Callers put
     * ids before any unbounded error text, exactly as the MiniDapp does.
     */
    public synchronized void log(String level, String message) {
        String msg = message == null ? "" : message;
        if (msg.length() > LOG_MSG_MAX) msg = msg.substring(0, LOG_MSG_MAX);
        try {
            final JSONArray arr = new JSONArray(get(LOG, "[]"));
            final JSONObject row = new JSONObject();
            row.put("ts", System.currentTimeMillis());
            row.put("level", level);
            row.put("msg", msg);
            final JSONArray out = new JSONArray();
            out.put(row);
            for (int i = 0; i < arr.length() && out.length() < LOG_MAX_LINES; i++) out.put(arr.get(i));
            set(LOG, out.toString());
        } catch (Exception ignored) {}
    }

    public JSONArray logLines() {
        try { return new JSONArray(get(LOG, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public void clearLog() { set(LOG, "[]"); }
}
