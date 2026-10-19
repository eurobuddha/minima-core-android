"""Reuse NFT Wallet complete receipt and chain inclusion checks for legacy UTXO history."""
from pathlib import Path
import shutil

def adapt_app(folder):
    source=Path(__file__).resolve().parent/'pandamonium-sources/utxo'
    for name in ['HistoryEvidence','HistoryRecovery']:
        shutil.copy2(source/(name+'.java'),folder/(name+'.java'))
    p=folder/'HistoryDb.java';s=p.read_text()
    s=s.replace('DB_VERSION = 6;','DB_VERSION = 7;')
    s=s.replace('// v6: nodetx outputs carry both address forms (resolver output match)','// v7: recheck legacy confirmations; preserve both history tables')
    s=s.replace('        if (rid != -1) resolve(n);\n','')
    s=s.replace('        onCreate(db);', '        onCreate(db);\n        if (oldV < 7) {\n            // Earlier versions confirmed on partial overlap and could also infer a failed send.\n            // Preserve the evidence/display rows, but require fresh inclusion before claiming success.\n            db.execSQL("UPDATE history SET status=\'unknown\', txnid=internalid WHERE status=\'confirmed\' "\n                    + "OR (status=\'error\' AND note LIKE \'Not posted: these coins were spent by a different transaction (%\')");\n        }')
    s=s.replace('    public boolean upsertNodeTx(NodeTx n) {',
        '    public boolean upsertNodeTx(NodeTx n) { return saveNodeTx(n, false); }\n\n    private boolean saveNodeTx(NodeTx n, boolean verified) {')
    s=s.replace('null, v, SQLiteDatabase.CONFLICT_IGNORE);',
        'null, v, verified ? SQLiteDatabase.CONFLICT_REPLACE : SQLiteDatabase.CONFLICT_IGNORE);')
    a=s.index('    /**\n     * The resolver.');b=s.index('    /** True if any open row',a)
    s=s[:a]+"""    /** Legacy cache/presence hooks cannot prove chain inclusion. Recovery queries fresh evidence. */
    public boolean resolve(NodeTx n) { return false; }
    public boolean resolveOpenAgainstStored() { return false; }

    /** Pending attempts only; display pagination must not hide evidence correlations. */
    List<HistoryRow> listRecoverable() {
        return query("SELECT id,internalid,txnid,status,recipient,amount,tokenid,tokenname,ts,note,"
                + "inputs,outputs,changeaddr,burn FROM history WHERE status IN ('posting','posted','unknown') ORDER BY id DESC");
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

"""+s[b:]
    # Late completion callbacks cannot downgrade already verified history.
    s=s.replace('getWritableDatabase().update(TABLE, v, "internalid=?", new String[]{internalid});',
        'getWritableDatabase().update(TABLE, v, "internalid=? AND status<>?", new String[]{internalid,STATUS_CONFIRMED});')
    p.write_text(s)
    p=folder/'NodeTx.java';s=p.read_text();needle='o.put("amount", c.optString("amount", c.optString("tokenamount", "")));';assert needle in s
    s=s.replace(needle,'o.put("amount", Util.isMinima(c.optString("tokenid", "")) ? c.optString("amount", "") : c.optString("tokenamount", ""));')
    p.write_text(s)
    p=folder/'HistoryView.java';s=p.read_text()
    s=s.replace('public void refresh() { if (visible() && !fetching) render(); }',
        'public void refresh() { rowsDirty = true; if (visible() && !fetching) render(); }')
    p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    for old,new in [
        ('    private HistoryDb historyDb;', '    private HistoryDb historyDb;\n    private HistoryRecovery historyRecovery;'),
        ('        distribute = new DistributeManager(this);','        distribute = new DistributeManager(this);\n        historyRecovery = new HistoryRecovery(node::cmd,historyDb,this::refreshHistory);'),
        ('        if (node != null) node.onDestroy();','        if (historyRecovery != null) historyRecovery.close();\n        if (node != null) node.onDestroy();'),
        ('                    views[TAB_HISTORY].onNewBlock();','                    views[TAB_HISTORY].onNewBlock();\n                    if (historyRecovery != null) historyRecovery.poll(chainBlock);')]:
        assert s.count(old)==1,(p,old);s=s.replace(old,new)
    p.write_text(s)

def adapt(root):
    adapt_app(root/'utxo/src/main/java/com/eurobuddha/utxo')
