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
    private static final int    DB_VERSION = 8;     // v8: durable attempts; preserve legacy history and cache
    private static final String TABLE = "history";

    public HistoryDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    /** Fresh install: create the full v2 schema. internalid is the app-side key; txnid holds the
     *  on-chain txpowid once resolved. inputs/outputs are JSON arrays so the breakdown can be rebuilt. */
    @Override
    public void onCreate(SQLiteDatabase db) {
        createAttempts(db);
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
        if (oldV < 7) {
            // Earlier versions confirmed on partial overlap and could also infer a failed send.
            // Preserve the evidence/display rows, but require fresh inclusion before claiming success.
            db.execSQL("UPDATE history SET status='unknown', txnid=internalid WHERE status='confirmed' "
                    + "OR (status='error' AND note LIKE 'Not posted: these coins were spent by a different transaction (%')");
        }
        for (String col : new String[]{"inputs TEXT", "outputs TEXT", "changeaddr TEXT", "burn TEXT"}) {
            try { db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + col); } catch (Exception ignored) {}
        }
    }

    /** Insert a new history row without replacing an existing internalid — used for new pending postings. */
    public boolean insertPosting(HistoryRow r) {
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
        try { return getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_ABORT) != -1; }
        catch (android.database.sqlite.SQLiteException failure) { return false; }
    }

    /** Patch status (and optionally txnid/note) of an existing row keyed by internalid. */
    public boolean update(String internalid, String status, String txnid, String note) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        if (txnid != null) v.put("txnid", txnid);
        if (note != null) v.put("note", note);
        SQLiteDatabase db;
        try { db=getWritableDatabase();db.beginTransaction(); }
        catch (android.database.sqlite.SQLiteException failure) { return false; }
        try {
            // Recovery may finish before a late send callback; never downgrade confirmed evidence.
            for (Attempt attempt:attempts(db)) if(attempt.id.equals(internalid)
                    &&(CONFIRMED.equals(attempt.phase)||NOT_SUBMITTED.equals(attempt.phase)&&!STATUS_ERROR.equals(status)))return false;
            if(db.update(TABLE,v,"internalid=? AND status<>?",new String[]{internalid,STATUS_CONFIRMED})!=1)return false;
            db.setTransactionSuccessful();return true;
        } catch (RuntimeException failure) { return false; }
        finally { db.endTransaction(); }
    }

    /** Record the inputs of a node-driven posting after the fact (consolidate), so the resolver can match it. */
    public void setInputs(String internalid, String inputsJson) {
        ContentValues v = new ContentValues();
        v.put("inputs", inputsJson);
        getWritableDatabase().update(TABLE, v, "internalid=? AND status<>?", new String[]{internalid,STATUS_CONFIRMED});
    }

    /** Remove a row (e.g. user dismisses a failed send). */
    public void delete(String internalid) {
        getWritableDatabase().delete(TABLE, "internalid=? AND NOT EXISTS (SELECT 1 FROM transaction_attempts a WHERE a.internalid=history.internalid AND a.phase NOT IN ('CONFIRMED','NOT_SUBMITTED'))", new String[]{internalid});
    }

    /** Rows this wallet posted that are NOT yet matched to an on-chain transaction (posting / posted /
     *  unknown / error) — shown at the top of History. Confirmed rows appear as their nodetx entry instead. */
    public List<HistoryRow> listOpen(int limit) {
        return query("SELECT id,internalid,txnid,status,recipient,amount,tokenid,tokenname,ts,note," +
                "inputs,outputs,changeaddr,burn FROM " + TABLE + " WHERE status<>'" + STATUS_CONFIRMED +
                "' OR NOT EXISTS (SELECT 1 FROM nodetx n WHERE lower(n.txpowid)=lower(history.txnid)) ORDER BY id DESC LIMIT " + limit);
    }

    public List<HistoryRow> list(int limit) {
        return query("SELECT id,internalid,txnid,status,recipient,amount,tokenid,tokenname,ts,note,inputs,outputs,changeaddr,burn FROM history ORDER BY id DESC LIMIT " + limit);
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
    public boolean upsertNodeTx(NodeTx n) { return saveNodeTx(n, false); }

    private boolean saveNodeTx(NodeTx n, boolean verified) {
        ContentValues v = new ContentValues();
        v.put("txpowid", n.txpowid); v.put("block", n.block); v.put("timemilli", n.timemilli);
        v.put("direction", n.direction); v.put("incoming", n.incoming ? 1 : 0);
        v.put("tokenid", n.tokenid); v.put("tokenname", n.tokenName); v.put("amount", n.amount);
        v.put("deltas", n.deltas); v.put("counterparty", n.counterparty);
        v.put("inputs", n.inputs); v.put("outputs", n.outputs);
        long rid = getWritableDatabase().insertWithOnConflict("nodetx", null, v, verified ? SQLiteDatabase.CONFLICT_REPLACE : SQLiteDatabase.CONFLICT_IGNORE);
        return rid != -1;
    }

    /** Legacy cache/presence hooks cannot prove chain inclusion. Recovery queries fresh evidence. */
    public boolean resolve(NodeTx n) { return false; }
    public boolean resolveOpenAgainstStored() { return false; }

    /** Pending attempts only; display pagination must not hide evidence correlations. */
    List<HistoryRow> listRecoverable() {
        return query("SELECT id,internalid,txnid,status,recipient,amount,tokenid,tokenname,ts,note,"
                + "inputs,outputs,changeaddr,burn FROM history WHERE status IN ('posting','posted','unknown') AND NOT EXISTS (SELECT 1 FROM transaction_attempts a WHERE a.internalid=history.internalid) ORDER BY id DESC");
    }

    /** Recheck the current saved row and commit its matching history entry atomically. */
    boolean confirmHistory(String internalid,org.json.JSONObject txpow,org.json.JSONObject detail,
                           org.json.JSONObject inclusion) {
        org.json.JSONObject proof=HistoryEvidence.inclusion(inclusion);
        String txpowid=HistoryEvidence.id(txpow,"txpowid");
        if(detail==null || detail.optJSONObject("difference")==null)return false;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            HistoryRow row=null;
            int matching=0;
            // Ambiguous duplicate attempts must stay unresolved, including across restarts.
            for(HistoryRow candidate:listRecoverable()) {
                if(HistoryRecovery.recoverable(candidate.status)&&HistoryEvidence.matches(candidate,txpow)) {
                    matching++;
                    if(internalid.equals(candidate.internalid))row=candidate;
                }
            }
            if(row==null||matching!=1)return false;
            try(Cursor claimed=db.rawQuery("SELECT 1 FROM history WHERE status=? AND lower(txnid)=? LIMIT 1",
                    new String[]{STATUS_CONFIRMED,txpowid})) {
                if(claimed.moveToFirst())return false;
            }
            NodeTx found=NodeTx.from(txpow,detail);
            found.block=Long.parseLong(proof.optString("block"));
            if(!saveNodeTx(found,true))throw new IllegalStateException("Could not save verified history");
            ContentValues values=new ContentValues();values.put("status",STATUS_CONFIRMED);
            values.put("txnid",found.txpowid);values.put("note","");
            if(db.update(TABLE,values,"internalid=? AND status=?",new String[]{internalid,row.status})!=1)return false;
            db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
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
    /** Durable public transaction intents. No retention cap: unresolved evidence must survive restart. */
    static final String ATTEMPTS = "transaction_attempts";
    // A process nonce distinguishes a killed sender from another still-live Activity/helper.
    private static final String PREPARATION_PROCESS = java.util.UUID.randomUUID().toString().replace("-","");
    private final String preparationOwner = PREPARATION_PROCESS+":"+java.util.UUID.randomUUID().toString().replace("-","");
    private volatile boolean preparationClosed;
    static final String PREPARED = "PREPARED", POSTING = "POSTING", UNKNOWN = "UNKNOWN",
            POSTED = "POSTED", CONFIRMED = "CONFIRMED", NOT_SUBMITTED = "NOT_SUBMITTED";
    static final class Attempt {
        final String id, handle, phase, owner;
        final org.json.JSONObject intent, receipt;
        Attempt(String id,String handle,String phase,String intent,String receipt,String owner) {
            if(owner==null||!("legacy".equals(owner)||owner.matches("[0-9a-f]{32}:[0-9a-f]{32}")))throw TransactionEvidence.invalid();
            this.owner=owner;
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||handle==null||!handle.matches("[A-Za-z0-9_-]{1,100}")
                    ||!java.util.Arrays.asList(PREPARED,POSTING,UNKNOWN,POSTED,CONFIRMED,NOT_SUBMITTED).contains(phase))throw TransactionEvidence.invalid();
            this.id=id;this.handle=handle;this.phase=phase;this.intent=TransactionEvidence.storedIntent(intent);
            if(receipt==null)throw TransactionEvidence.invalid();
            this.receipt=receipt.isEmpty()?null:TransactionEvidence.storedObject(receipt);
            if((POSTED.equals(phase)||CONFIRMED.equals(phase))!=(this.receipt!=null))throw TransactionEvidence.invalid();
            if(CONFIRMED.equals(phase)) {
                try { TransactionEvidence.inclusion(new org.json.JSONObject().put("status",true).put("response",this.receipt.optJSONObject("inclusion"))); }
                catch(org.json.JSONException e) { throw TransactionEvidence.invalid(); }
            }
            if(this.receipt!=null)TransactionEvidence.storedReceipt(this.receipt,this.intent);
        }
    }
    private static void createAttempts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS "+ATTEMPTS+" (internalid TEXT PRIMARY KEY NOT NULL, handle TEXT NOT NULL UNIQUE, phase TEXT NOT NULL, intent TEXT NOT NULL, receipt TEXT NOT NULL, owner TEXT NOT NULL DEFAULT 'legacy')");
    }
    private java.util.List<Attempt> attempts(SQLiteDatabase db) {
        java.util.List<Attempt> result=new java.util.ArrayList<>();
        try(Cursor cursor=db.rawQuery("SELECT internalid,handle,phase,intent,receipt,owner FROM "+ATTEMPTS,null)) {
            while(cursor.moveToNext())result.add(new Attempt(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5)));
        }
        return result;
    }
    java.util.List<Attempt> transactionAttempts() { return attempts(getReadableDatabase()); }
    /** Node-selected inputs must not bypass custom transaction reservations. */
    boolean hasUnresolvedAttempt(String tokenid) {
        if(!Util.isValidHexId(tokenid))throw TransactionEvidence.invalid();
        for(Attempt attempt:transactionAttempts())if(!released(attempt.phase)
                &&(TransactionFields.same(tokenid,attempt.intent.optString("tokenid"))
                ||attempt.intent.has("companion")&&TransactionFields.same(tokenid,attempt.intent.optJSONObject("companion").optString("tokenid"))))return true;
        return false;
    }
    /** Atomic read/check/write: two builders, even through different helpers, cannot reserve the same input. */
    boolean prepareAttempt(String id,String handle,org.json.JSONObject intent) {
        if(preparationClosed)return false;
        Attempt proposed=new Attempt(id,handle,PREPARED,intent.toString(),"",preparationOwner);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(preparationClosed)return false;
            java.util.Set<String> mine=TransactionEvidence.inputIds(proposed.intent);
            for(Attempt current:attempts(db)) {
                if(current.id.equals(id)||current.handle.equals(handle))return false;
                if(!released(current.phase)&&!java.util.Collections.disjoint(mine,TransactionEvidence.inputIds(current.intent)))return false;
            }
            // The display row must have been saved too. Missing rows never permit signing.
            try(Cursor c=db.rawQuery("SELECT internalid FROM "+TABLE+" WHERE internalid=?",new String[]{id})) {if(!c.moveToFirst())return false;}
            ContentValues v=new ContentValues();v.put("internalid",id);v.put("handle",handle);v.put("phase",PREPARED);
            v.put("intent",proposed.intent.toString());v.put("receipt","");v.put("owner",preparationOwner);db.insertOrThrow(ATTEMPTS,null,v);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }
    /** Same acknowledged pre-post boundary as PandaDEX Pending; no callback can recreate a missing row. */
    boolean markAttempt(String id,String phase,org.json.JSONObject reply) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Attempt found=null;for(Attempt a:attempts(db))if(a.id.equals(id)){found=a;break;}
            if(found==null)return false;
            if(PREPARED.equals(found.phase)&&POSTING.equals(phase)
                    &&(preparationClosed||!preparationOwner.equals(found.owner)))return false;
            boolean valid=PREPARED.equals(found.phase)&&(POSTING.equals(phase)||NOT_SUBMITTED.equals(phase))
                    ||POSTING.equals(found.phase)&&(UNKNOWN.equals(phase)||POSTED.equals(phase)||NOT_SUBMITTED.equals(phase))
                    ||UNKNOWN.equals(found.phase)&&UNKNOWN.equals(phase);
            if(!valid)return false;
            String receipt=POSTED.equals(phase)?TransactionEvidence.publicReceipt(reply,found.intent).toString():"";
            new Attempt(found.id,found.handle,phase,found.intent.toString(),receipt,found.owner);
            ContentValues values=new ContentValues();values.put("phase",phase);values.put("receipt",receipt);
            if(db.update(ATTEMPTS,values,"internalid=? AND phase=?",new String[]{id,found.phase})!=1)return false;
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }
    private static boolean released(String phase) { return NOT_SUBMITTED.equals(phase)||CONFIRMED.equals(phase); }
    /** Commit the exact public receipt, inclusion evidence and display status together. */
    boolean confirmAttempt(String id,org.json.JSONObject reply,org.json.JSONObject chain) {
        org.json.JSONObject inclusion=TransactionEvidence.inclusion(chain);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Attempt found=null;for(Attempt a:attempts(db))if(a.id.equals(id)){found=a;break;}
            if(found==null||!TransactionRecovery.recoverable(found.phase))return false;
            org.json.JSONObject receipt=TransactionEvidence.publicReceipt(reply,found.intent);
            try { receipt.put("inclusion",inclusion); }
            catch(org.json.JSONException e){throw TransactionEvidence.invalid();}
            new Attempt(found.id,found.handle,CONFIRMED,found.intent.toString(),receipt.toString(),found.owner);
            ContentValues values=new ContentValues();values.put("phase",CONFIRMED);values.put("receipt",receipt.toString());
            if(db.update(ATTEMPTS,values,"internalid=? AND phase=?",new String[]{id,found.phase})!=1)return false;
            ContentValues display=new ContentValues();display.put("status",STATUS_CONFIRMED);display.put("txnid",receipt.optString("txpowid"));display.put("note","");
            if(db.update(TABLE,display,"internalid=?",new String[]{id})!=1)return false;
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }

    /** The owning client must stop callbacks before its never-posted work can be abandoned. */
    void abandonPreparations(NodeApi owner) {
        if(owner==null||!owner.isClosed())throw new IllegalStateException("Transaction client is still active");
        preparationClosed=true;
        releasePreparations(getWritableDatabase(),true);
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Old process callbacks cannot survive Android process death. POSTING/UNKNOWN remain held.
        try { releasePreparations(db,false); }
        catch (RuntimeException unavailable) { /* Preserve evidence; financial methods still fail closed, while history remains readable. */ }
    }
    private void releasePreparations(SQLiteDatabase db,boolean ownClosedClient) {
        db.beginTransaction();
        try {
            for(Attempt attempt:attempts(db)) {
                boolean abandoned=ownClosedClient?preparationOwner.equals(attempt.owner)
                        :!attempt.owner.startsWith(PREPARATION_PROCESS+":");
                if(!abandoned||!PREPARED.equals(attempt.phase))continue;
                ContentValues phase=new ContentValues();phase.put("phase",NOT_SUBMITTED);
                if(db.update(ATTEMPTS,phase,"internalid=? AND phase=?",new String[]{attempt.id,PREPARED})!=1)
                    throw new IllegalStateException("Preparation changed during recovery");
                ContentValues display=new ContentValues();display.put("status",STATUS_ERROR);display.put("note",TransactionEvidence.NOT_SENT);
                if(db.update(TABLE,display,"internalid=?",new String[]{attempt.id})!=1)
                    throw new IllegalStateException("Preparation history is missing");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
