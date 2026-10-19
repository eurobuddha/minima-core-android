"""Reuse minimaWallet's per-wallet history accounting for Core's saved history."""
from pathlib import Path

def adapt(root):
    base=root/'history/src/main/java/com/eurobuddha/history'
    p=base/'HistoryEntry.java';s=p.read_text()
    marker='    // ----- split / consolidation display (a self-only coin reshuffle) -----'
    assert s.count(marker)==1
    s=s.replace(marker,(Path(__file__).with_name('pandamonium-sources')/'history-wallet-scope.inc').read_text()+'\n'+marker)
    marker='        e.syncedAt = System.currentTimeMillis();'
    assert s.count(marker)==1;s=s.replace(marker,'        e.reconcile(own);\n'+marker)
    s=s.replace('return "self".equals(direction) && count(', 'return verifiedSelfTransfer && count(')
    s=s.replace('\n            if (c == null) continue;', '\n            if (c == null) return "[]";')
    marker='                o.put("amount", c.optString("amount", c.optString("tokenamount", "")));'
    assert s.count(marker)==1
    s=s.replace(marker,'''                o.put("amount", c.optString("tokenamount", c.optString("amount", "")));
                o.put("displayUnits", Util.isMinima(c.optString("tokenid", "0x00")) || c.has("tokenamount"));
                o.put("name", Util.tokenName(c.opt("token"), c.optString("tokenid", "0x00")));''');p.write_text(s)
    p=base/'HistoryDb.java';s=p.read_text()
    marker='    /** Insert a row; returns true if it was NEW, false if this txpowid was already stored. Idempotent. */'
    assert s.count(marker)==1
    s=s.replace(marker,'''    private Ownership reconciledOwnership;

    /** Correct existing rows before search/export; retain IDs, timestamps and coin evidence. */
    public synchronized void reconcile(Ownership own) {
        if (own == null || !own.isLoaded() || reconciledOwnership == own) return;
        SQLiteDatabase database=getWritableDatabase();
        database.beginTransaction();
        try {
            for (HistoryEntry entry:all()) {
                if (!entry.reconcile(own)) continue;
                ContentValues values=new ContentValues();
                values.put("direction",entry.direction);values.put("incoming",entry.incoming ? 1 : 0);
                values.put("tokenid",entry.tokenid);values.put("tokenname",entry.tokenName);
                values.put("amount",entry.amount);values.put("deltas",entry.deltas);values.put("counterparty",entry.counterparty);
                database.update(TX,values,"txpowid=?",new String[]{entry.txpowid});
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        reconciledOwnership=own;
    }

'''+marker)
    marker='    public boolean insert(HistoryEntry e) {'
    assert s.count(marker)==1
    s=s.replace(marker,'''    public boolean insert(HistoryEntry e) { return put(e,false); }
    public boolean insertFromNode(HistoryEntry e) { return put(e,true); }
    private synchronized boolean put(HistoryEntry e, boolean refresh) {''')
    marker='        return rid != -1;'
    assert s.count(marker)==1
    s=s.replace(marker,'''        if (rid != -1) reconciledOwnership=null;
        else if (refresh && e.hasCoinData()) {
            getWritableDatabase().update(TX,v,"txpowid=?",new String[]{e.txpowid});
            reconciledOwnership=null;
        }
'''+marker)
    # Restore verified split classification for deserialized rows; values were reconciled above.
    marker='                out.add(e);'
    s=s.replace(marker,'                e.reconcile(reconciledOwnership);\n'+marker)
    p.write_text(s)
    p=base/'HistorySync.java';s=p.read_text();assert 'db.insert(e)' in s;s=s.replace('db.insert(e)','db.insertFromNode(e)');p.write_text(s)
    p=base/'HistoryScreen.java';s=p.read_text()
    marker='            List<HistoryEntry> rows = db.list(MAX_ROWS, 0, currentSearch);'
    assert s.count(marker)==1;s=s.replace(marker,'            db.reconcile(o);\n'+marker)
    marker='                List<HistoryEntry> all = db.all();'
    assert s.count(marker)==2;s=s.replace(marker,'                db.reconcile(own);\n'+marker)
    p.write_text(s)
