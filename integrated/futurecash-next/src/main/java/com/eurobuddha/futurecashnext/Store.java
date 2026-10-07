package com.eurobuddha.futurecashnext;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The guardian's work tables — the native replacement for the MiniDapp's {@code MDS.sql}
 * (`fc_collect` / `fc_sweep`), same columns, same status vocabulary.
 *
 * <p>Two deliberate differences from the MiniDapp, both because SQLite gives us what H2-over-MDS
 * didn't:
 *
 * <ul>
 *   <li><b>Parameter binding everywhere.</b> The MiniDapp had to concatenate SQL strings and escape
 *       quotes by hand. Values here reach the DB as bound arguments, so no amount of odd data from
 *       the node can change the shape of a statement.</li>
 *   <li><b>Claiming a row is atomic.</b> {@code claimForPost} is an UPDATE with the expected status
 *       in its WHERE clause, and SQLite tells us how many rows actually changed. Only the caller
 *       that changed the row posts the transaction. In the MiniDapp two overlapping reconcile passes
 *       could each read the same DETECTED row and both post it (fixed there with a generation
 *       counter); here the database settles it outright.</li>
 * </ul>
 */
public final class Store extends SQLiteOpenHelper {

    private static final String DB = "futurecash_guardian.db";
    private static final int VERSION = 1;

    /* ---- fc_collect.status ---- */
    public static final String DETECTED             = "DETECTED";
    public static final String COLLECT_POSTED       = "COLLECT_POSTED";        // at-risk: sweep must follow
    public static final String COLLECT_POSTED_SAFE  = "COLLECT_POSTED_SAFE";   // bare collect
    public static final String COLLECTED            = "COLLECTED";
    public static final String COLLECTED_SAFE       = "COLLECTED_SAFE";
    public static final String SWEEP_QUEUED         = "SWEEP_QUEUED";

    /* ---- fc_sweep.status ---- */
    public static final String PENDING      = "PENDING";
    public static final String SWEEP_POSTED = "SWEEP_POSTED";
    public static final String SWEPT        = "SWEPT";
    public static final String TAKEN        = "TAKEN";

    public Store(Context ctx) {
        super(ctx.getApplicationContext(), DB, null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fc_collect ("
                + " fc_coinid TEXT PRIMARY KEY, payout_address TEXT, tokenid TEXT,"
                + " out_amount TEXT, mature_block INTEGER DEFAULT 0, at_risk INTEGER DEFAULT 0,"
                + " status TEXT, collect_block INTEGER DEFAULT 0, attempts INTEGER DEFAULT 0,"
                + " updated INTEGER, note TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS fc_sweep ("
                + " coinid TEXT PRIMARY KEY, address TEXT, tokenid TEXT,"
                + " amount TEXT, status TEXT, sweep_block INTEGER DEFAULT 0, attempts INTEGER DEFAULT 0,"
                + " updated INTEGER, note TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_collect_status ON fc_collect(status)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sweep_status ON fc_sweep(status)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { /* v1 */ }

    /* ================= rows ================= */

    public static final class CollectRow {
        public String coinid, payout, tokenid, amount, status;
        public long matureBlock, collectBlock, attempts;
        public boolean atRisk;
    }

    public static final class SweepRow {
        public String coinid, address, tokenid, amount, status, note;
        public long sweepBlock, attempts;
    }

    private static CollectRow readCollect(Cursor c) {
        final CollectRow r = new CollectRow();
        r.coinid       = c.getString(c.getColumnIndexOrThrow("fc_coinid"));
        r.payout       = c.getString(c.getColumnIndexOrThrow("payout_address"));
        r.tokenid      = c.getString(c.getColumnIndexOrThrow("tokenid"));
        r.amount       = c.getString(c.getColumnIndexOrThrow("out_amount"));
        r.status       = c.getString(c.getColumnIndexOrThrow("status"));
        r.matureBlock  = c.getLong(c.getColumnIndexOrThrow("mature_block"));
        r.collectBlock = c.getLong(c.getColumnIndexOrThrow("collect_block"));
        r.attempts     = c.getLong(c.getColumnIndexOrThrow("attempts"));
        r.atRisk       = c.getInt(c.getColumnIndexOrThrow("at_risk")) == 1;
        return r;
    }

    private static SweepRow readSweep(Cursor c) {
        final SweepRow r = new SweepRow();
        r.coinid     = c.getString(c.getColumnIndexOrThrow("coinid"));
        r.address    = c.getString(c.getColumnIndexOrThrow("address"));
        r.tokenid    = c.getString(c.getColumnIndexOrThrow("tokenid"));
        r.amount     = c.getString(c.getColumnIndexOrThrow("amount"));
        r.status     = c.getString(c.getColumnIndexOrThrow("status"));
        r.note       = c.getString(c.getColumnIndexOrThrow("note"));
        r.sweepBlock = c.getLong(c.getColumnIndexOrThrow("sweep_block"));
        r.attempts   = c.getLong(c.getColumnIndexOrThrow("attempts"));
        return r;
    }

    /* ================= fc_collect ================= */

    public boolean collectExists(String coinid) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM fc_collect WHERE fc_coinid=? LIMIT 1", new String[]{coinid})) {
            return c.moveToFirst();
        }
    }

    public void insertCollect(String coinid, String payout, String tokenid, String amount,
                              long matureBlock, boolean atRisk) {
        final ContentValues v = new ContentValues();
        v.put("fc_coinid", coinid);
        v.put("payout_address", payout);
        v.put("tokenid", tokenid);
        v.put("out_amount", amount);
        v.put("mature_block", matureBlock);
        v.put("at_risk", atRisk ? 1 : 0);
        v.put("status", DETECTED);
        v.put("collect_block", 0);
        v.put("attempts", 0);
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("fc_collect", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Keep maturity + risk current; the audit can flip at_risk between passes. */
    public void refreshCollect(String coinid, long matureBlock, boolean atRisk) {
        getWritableDatabase().execSQL(
                "UPDATE fc_collect SET mature_block=?, at_risk=?, updated=? WHERE fc_coinid=?",
                new Object[]{matureBlock, atRisk ? 1 : 0, System.currentTimeMillis(), coinid});
    }

    /**
     * REORG re-arm. The original stake coin is unspent again, so a collect we had recorded was
     * rolled back — put the row back to DETECTED so it gets collected (and swept) again.
     *
     * <p>SWEEP_QUEUED is in this list on purpose. Sweeps post at zero confirmations, so acting
     * inside the reorg window is the designed case; leaving that status out stranded the stake in
     * a status nothing acts on, silently, forever.
     */
    public void reArmAfterReorg(String coinid) {
        getWritableDatabase().execSQL(
                "UPDATE fc_collect SET status=?, updated=? WHERE fc_coinid=? AND status IN (?,?,?)",
                new Object[]{DETECTED, System.currentTimeMillis(), coinid,
                        COLLECTED, COLLECTED_SAFE, SWEEP_QUEUED});
    }

    public List<CollectRow> collectsWithStatus(String... statuses) {
        final StringBuilder q = new StringBuilder("SELECT * FROM fc_collect WHERE status IN (");
        for (int i = 0; i < statuses.length; i++) q.append(i == 0 ? "?" : ",?");
        q.append(")");
        final List<CollectRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(q.toString(), statuses)) {
            while (c.moveToNext()) out.add(readCollect(c));
        }
        return out;
    }

    /**
     * Atomically claim a DETECTED row for posting. Returns true for exactly one caller; a second
     * concurrent pass sees 0 rows changed and must not post. This is the database doing what the
     * MiniDapp needed a generation counter for.
     */
    public boolean claimForPost(String coinid, String postedStatus, long tip) {
        final SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE fc_collect SET status=?, collect_block=?, attempts=attempts+1, updated=?"
                        + " WHERE fc_coinid=? AND status=?",
                new Object[]{postedStatus, tip, System.currentTimeMillis(), coinid, DETECTED});
        return changed(db);
    }

    public void setCollectStatus(String coinid, String status) {
        getWritableDatabase().execSQL("UPDATE fc_collect SET status=?, updated=? WHERE fc_coinid=?",
                new Object[]{status, System.currentTimeMillis(), coinid});
    }

    public void deleteCollect(String coinid) {
        getWritableDatabase().execSQL("DELETE FROM fc_collect WHERE fc_coinid=?", new Object[]{coinid});
    }

    public List<String> allCollectPayouts() {
        final List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT fc_coinid, payout_address FROM fc_collect", null)) {
            while (c.moveToNext()) out.add(c.getString(0) + " " + c.getString(1));
        }
        return out;
    }

    /* ================= fc_sweep ================= */

    public boolean sweepExists(String coinid) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM fc_sweep WHERE coinid=? LIMIT 1", new String[]{coinid})) {
            return c.moveToFirst();
        }
    }

    public void insertSweep(String coinid, String address, String tokenid, String amount) {
        final ContentValues v = new ContentValues();
        v.put("coinid", coinid);
        v.put("address", address);
        v.put("tokenid", tokenid);
        v.put("amount", amount);
        v.put("status", PENDING);
        v.put("sweep_block", 0);
        v.put("attempts", 0);
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("fc_sweep", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<SweepRow> sweepsWithStatus(String... statuses) {
        final StringBuilder q = new StringBuilder("SELECT * FROM fc_sweep WHERE status IN (");
        for (int i = 0; i < statuses.length; i++) q.append(i == 0 ? "?" : ",?");
        q.append(")");
        final List<SweepRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(q.toString(), statuses)) {
            while (c.moveToNext()) out.add(readSweep(c));
        }
        return out;
    }

    /** Atomic PENDING -> SWEEP_POSTED claim; same reasoning as claimForPost. */
    public boolean claimSweepForPost(String coinid, long tip) {
        final SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE fc_sweep SET status=?, sweep_block=?, attempts=attempts+1, updated=?"
                        + " WHERE coinid=? AND status=?",
                new Object[]{SWEEP_POSTED, tip, System.currentTimeMillis(), coinid, PENDING});
        return changed(db);
    }

    public void setSweepStatus(String coinid, String status, String note) {
        if (note == null) {
            getWritableDatabase().execSQL("UPDATE fc_sweep SET status=?, updated=? WHERE coinid=?",
                    new Object[]{status, System.currentTimeMillis(), coinid});
        } else {
            getWritableDatabase().execSQL("UPDATE fc_sweep SET status=?, note=?, updated=? WHERE coinid=?",
                    new Object[]{status, note, System.currentTimeMillis(), coinid});
        }
    }

    public void deleteSweep(String coinid) {
        getWritableDatabase().execSQL("DELETE FROM fc_sweep WHERE coinid=?", new Object[]{coinid});
    }

    /** Every note ever recorded — the set of output coins already credited to a sweep. */
    public List<String> sweepNotes() {
        final List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT note FROM fc_sweep WHERE note IS NOT NULL AND note<>''", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public List<String> allSweepCoinIds() {
        final List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT coinid FROM fc_sweep", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /* ================= status counts (for the dashboard) ================= */

    public int countCollect(String status) { return count("fc_collect", status); }
    public int countSweep(String status)   { return count("fc_sweep", status); }

    private int count(String table, String status) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE status=?", new String[]{status})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Matured, not yet posted, MINIMA only — split safe vs at-risk. Returns {count, total}. */
    public double[] readyTotals(long tip, boolean atRisk) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CAST(out_amount AS REAL)),0) FROM fc_collect"
                        + " WHERE status=? AND mature_block>0 AND mature_block<=? AND tokenid='0x00' AND at_risk=?",
                new String[]{DETECTED, String.valueOf(tip), atRisk ? "1" : "0"})) {
            if (c.moveToFirst()) return new double[]{c.getInt(0), c.getDouble(1)};
        }
        return new double[]{0, 0};
    }

    /** At-risk stakes still pending. Returns {count, soonestBlock, total}. */
    public double[] pendingAtRisk(long tip) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(MIN(mature_block),0), COALESCE(SUM(CAST(out_amount AS REAL)),0)"
                        + " FROM fc_collect WHERE status=? AND at_risk=1 AND mature_block>? AND tokenid='0x00'",
                new String[]{DETECTED, String.valueOf(tip)})) {
            if (c.moveToFirst()) return new double[]{c.getInt(0), c.getLong(1), c.getDouble(2)};
        }
        return new double[]{0, 0, 0};
    }

    /** Clear completed history, keeping anything still in flight. */
    public void resetHistory() {
        final SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM fc_sweep WHERE status IN (?,?)", new Object[]{SWEPT, TAKEN});
        db.execSQL("DELETE FROM fc_collect WHERE status IN (?,?,?)",
                new Object[]{DETECTED, SWEEP_QUEUED, COLLECTED_SAFE});
    }

    private static boolean changed(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT changes()", null)) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }
}
