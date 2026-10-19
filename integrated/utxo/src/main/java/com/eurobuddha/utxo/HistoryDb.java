package com.eurobuddha.utxo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local transaction history. The web utxoWallet kept this in MDS.sql; the native IPC API only runs
 * node commands, so we keep our own SQLite table. Stores the full FROM/TO/CHANGE/BURN detail so the
 * History tab can show an expandable breakdown like the dapp.
 */
public class HistoryDb extends SQLiteOpenHelper {

    public static final String STATUS_POSTING   = "posting";
    public static final String STATUS_POSTED    = "posted";    // broadcast, awaiting confirmation
    public static final String STATUS_CONFIRMED = "confirmed"; // real on-chain txpowid resolved
    public static final String STATUS_ERROR     = "error";
    public static final String STATUS_UNKNOWN   = "unknown";   // txnsign timed out: may or may not have posted

    private static final String DB_NAME = "pandamonium_utxo_history.db";
    private static final int    DB_VERSION = 6;     // v6: nodetx outputs carry both address forms (resolver output match)
    private static final String TABLE = "history";

    public HistoryDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    /** Fresh install: create the full v2 schema. internalid is the app-side key; txnid holds the
     *  on-chain txpowid once resolved. inputs/outputs are JSON arrays so the breakdown can be rebuilt. */
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "internalid TEXT UNIQUE," +
                "txnid TEXT," +
                "status TEXT," +
                "recipient TEXT," +
                "amount TEXT," +
                "tokenid TEXT," +
                "tokenname TEXT," +
                "ts INTEGER," +
                "note TEXT," +
                "inputs TEXT," +        // JSON array [{coinid,address,amount}]
                "outputs TEXT," +       // JSON array [{address,amount}]
                "changeaddr TEXT," +
                "burn TEXT)");
        // Persisted on-chain history (from the node's `history`), keyed by txpowid — accumulates + survives
        // restarts, like the standalone Minima History app.
        db.execSQL("CREATE TABLE IF NOT EXISTS nodetx (" +
                "txpowid TEXT PRIMARY KEY, block INTEGER, timemilli INTEGER," +
                "direction TEXT, incoming INTEGER, tokenid TEXT, tokenname TEXT, amount TEXT," +
                "deltas TEXT, counterparty TEXT, inputs TEXT, outputs TEXT)");
    }

    /** Non-destructive migration: ensure the table exists, then ALTER in the v2 columns. Each ALTER
     *  is wrapped so a "column already exists" error on a partially-migrated db is ignored. */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // nodetx is a re-fetchable cache of on-chain history — recreate it if its schema changed.
        if (oldV < 6) db.execSQL("DROP TABLE IF EXISTS nodetx");
        // Preserve the local send-records (history) table; (re)create tables; add the v2 send columns.
        onCreate(db);
        for (String col : new String[]{"inputs TEXT", "outputs TEXT", "changeaddr TEXT", "burn TEXT"}) {
            try { db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + col); } catch (Exception ignored) {}
        }
    }

    /** Insert (or replace on internalid conflict) a history row — used for new pending postings. */
    public void insertPosting(HistoryRow r) {
        ContentValues v = new ContentValues();
        v.put("internalid", r.internalid);
        v.put("txnid", r.txnid);
        v.put("status", r.status);
        v.put("recipient", r.recipient);
        v.put("amount", r.amount);
        v.put("tokenid", r.tokenid);
        v.put("tokenname", r.tokenName);
        v.put("ts", r.ts);
        v.put("note", r.note);
        v.put("inputs", r.inputs);
        v.put("outputs", r.outputs);
        v.put("changeaddr", r.changeaddr);
        v.put("burn", r.burn);
        getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Patch status (and optionally txnid/note) of an existing row keyed by internalid. */
    public void update(String internalid, String status, String txnid, String note) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        if (txnid != null) v.put("txnid", txnid);
        if (note != null) v.put("note", note);
        getWritableDatabase().update(TABLE, v, "internalid=?", new String[]{internalid});
    }

    /** Record the inputs of a node-driven posting after the fact (consolidate), so the resolver can match it. */
    public void setInputs(String internalid, String inputsJson) {
        ContentValues v = new ContentValues();
        v.put("inputs", inputsJson);
        getWritableDatabase().update(TABLE, v, "internalid=?", new String[]{internalid});
    }

    /** Remove a row (e.g. user dismisses a failed send). */
    public void delete(String internalid) {
        getWritableDatabase().delete(TABLE, "internalid=?", new String[]{internalid});
    }

    /** Rows this wallet posted that are NOT yet matched to an on-chain transaction (posting / posted /
     *  unknown / error) — shown at the top of History. Confirmed rows appear as their nodetx entry instead. */
    public List<HistoryRow> listOpen(int limit) {
        return query("SELECT id,internalid,txnid,status,recipient,amount,tokenid,tokenname,ts,note," +
                "inputs,outputs,changeaddr,burn FROM " + TABLE + " WHERE status<>'" + STATUS_CONFIRMED +
                "' ORDER BY id DESC LIMIT " + limit);
    }

    private List<HistoryRow> query(String sql) {
        List<HistoryRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                HistoryRow r = new HistoryRow();
                r.id         = c.getLong(0);
                r.internalid = c.getString(1);
                r.txnid      = c.getString(2);
                r.status     = c.getString(3);
                r.recipient  = c.getString(4);
                r.amount     = c.getString(5);
                r.tokenid    = c.getString(6);
                r.tokenName  = c.getString(7);
                r.ts         = c.getLong(8);
                r.note       = c.getString(9);
                r.inputs     = c.getString(10);
                r.outputs    = c.getString(11);
                r.changeaddr = c.getString(12);
                r.burn       = c.getString(13);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ----- persisted on-chain history (nodetx) -----

    /** Insert a node history row; returns true if NEW (false if this txpowid was already stored). */
    public boolean upsertNodeTx(NodeTx n) {
        ContentValues v = new ContentValues();
        v.put("txpowid", n.txpowid); v.put("block", n.block); v.put("timemilli", n.timemilli);
        v.put("direction", n.direction); v.put("incoming", n.incoming ? 1 : 0);
        v.put("tokenid", n.tokenid); v.put("tokenname", n.tokenName); v.put("amount", n.amount);
        v.put("deltas", n.deltas); v.put("counterparty", n.counterparty);
        v.put("inputs", n.inputs); v.put("outputs", n.outputs);
        long rid = getWritableDatabase().insertWithOnConflict("nodetx", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (rid != -1) resolve(n);
        return rid != -1;
    }

    /**
     * The resolver. A freshly seen on-chain transaction that spent a local row's recorded input coinids
     * is either THAT row's transaction (its recorded output addresses appear in the txpow → CONFIRMED,
     * real txpowid stored) or a different transaction that re-spent the same coins (no output overlap →
     * the local one can never post → ERROR). Inputs alone are not enough: a timed-out send that never
     * posted, followed by a re-send of the same coins, used to get confirmed against the second txpow.
     * Rows with no recorded outputs (node-driven consolidate) confirm on inputs alone.
     * Returns true if any row changed.
     */
    public boolean resolve(NodeTx n) {
        java.util.Set<String> spent = coinIds(n.inputs, "coinid");
        if (spent.isEmpty()) return false;
        java.util.Set<String> outAddrs = coinIds(n.outputs, "addr");
        outAddrs.addAll(coinIds(n.outputs, "address"));
        boolean any = false;
        for (HistoryRow r : listOpen(500)) {
            if (STATUS_ERROR.equals(r.status)) continue;          // an errored row never posted
            java.util.Set<String> mine = coinIds(r.inputs, "coinid");
            mine.retainAll(spent);
            if (mine.isEmpty()) continue;
            java.util.Set<String> want = coinIds(r.outputs, "address");
            if (want.isEmpty()) {
                update(r.internalid, STATUS_CONFIRMED, n.txpowid, "");
                any = true;
                continue;
            }
            boolean outMatch = false;
            for (String a : want) if (containsIgnoreCase(outAddrs, a)) { outMatch = true; break; }
            if (outMatch) update(r.internalid, STATUS_CONFIRMED, n.txpowid, "");
            else update(r.internalid, STATUS_ERROR, null,
                    "Not posted: these coins were spent by a different transaction (" + n.txpowid + ").");
            any = true;
        }
        return any;
    }

    /** Re-run the resolver for every open row against the txpows ALREADY stored, so a send whose txpow
     *  was fetched on an earlier page (or before the row existed) settles without a fresh upsert. */
    public boolean resolveOpenAgainstStored() {
        boolean any = false;
        for (HistoryRow r : listOpen(200)) {
            if (STATUS_ERROR.equals(r.status)) continue;
            for (String id : coinIds(r.inputs, "coinid")) {
                Cursor c = getReadableDatabase().rawQuery(
                        "SELECT txpowid,inputs,outputs FROM nodetx WHERE inputs LIKE ? LIMIT 1", new String[]{"%" + id + "%"});
                try {
                    if (c.moveToFirst()) {
                        NodeTx n = new NodeTx();
                        n.txpowid = c.getString(0); n.inputs = c.getString(1); n.outputs = c.getString(2);
                        any |= resolve(n);
                        break;
                    }
                } finally { c.close(); }
            }
        }
        return any;
    }

    /** True if any open row is still waiting on the chain (posted / posted?) — drives History's auto-paging. */
    public boolean hasOpenWaiting() {
        for (HistoryRow r : listOpen(50)) {
            if (STATUS_POSTED.equals(r.status) || STATUS_UNKNOWN.equals(r.status)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(java.util.Set<String> set, String v) {
        for (String s : set) if (s.equalsIgnoreCase(v)) return true;
        return false;
    }

    /**
     * Compatibility entrypoint for coin refreshes. A coin list (including a partial one)
     * cannot prove whether a timed-out transaction was submitted. Preserve its recorded
     * outcome across elapsed time and restarts; settlement requires transaction evidence.
     */
    public boolean reconcileUnknown(java.util.Set<String> liveCoinIds, long staleMs) {
        return false;
    }

    /** The values of {@code key} across a JSON array of coin objects (empty on null/garbage). */
    private static java.util.Set<String> coinIds(String json, String key) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (json == null || json.isEmpty()) return out;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString(key, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Newest-first persisted on-chain history, capped at limit. */
    public List<NodeTx> loadNodeTx(int limit) {
        List<NodeTx> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT txpowid,block,timemilli,direction,incoming,tokenid,tokenname,amount,deltas,counterparty,inputs,outputs FROM nodetx " +
                        "ORDER BY block DESC, timemilli DESC LIMIT " + limit, null);
        try {
            while (c.moveToNext()) {
                NodeTx n = new NodeTx();
                n.txpowid = c.getString(0); n.block = c.getLong(1); n.timemilli = c.getLong(2);
                n.direction = c.getString(3); n.incoming = c.getInt(4) == 1;
                n.tokenid = c.getString(5); n.tokenName = c.getString(6); n.amount = c.getString(7);
                n.deltas = c.getString(8); n.counterparty = c.getString(9);
                n.inputs = c.getString(10); n.outputs = c.getString(11);
                out.add(n);
            }
        } finally { c.close(); }
        return out;
    }

    public int nodeTxCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM nodetx", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }
}
