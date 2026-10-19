"""Durable custom transaction intents and acknowledged pre-post writes."""
from pathlib import Path
import shutil

def adapt(root):
    folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    project=Path(__file__).resolve().parents[1]
    shutil.copy2(project/'scripts/pandamonium-sources/NFTwallet/TransactionRecovery.java',folder/'TransactionRecovery.java')
    shutil.copy2(project/'scripts/pandamonium-sources/NFTwallet/PreparedSendEvidence.java',folder/'PreparedSendEvidence.java')
    def change(name,old,new,count=1):
        p=folder/(name+'.java');s=p.read_text();assert s.count(old)==count,('NFT journal drift',name,old);p.write_text(s.replace(old,new))
    change('HistoryDb','DB_VERSION = 4;     // v4: nodetx full schema (mirror of the History app)', 'DB_VERSION = 6;     // v6: preparation ownership and interrupted-send recovery')
    change('HistoryDb','Insert (or replace on internalid conflict) a history row', 'Insert a new history row without replacing an existing internalid')
    change('HistoryDb','    public void onCreate(SQLiteDatabase db) {','    public void onCreate(SQLiteDatabase db) {\n        createAttempts(db);')
    change('HistoryDb','        onCreate(db);','        onCreate(db);\n        if (oldV >= 5 && oldV < 6) db.execSQL("ALTER TABLE "+ATTEMPTS+" ADD COLUMN owner TEXT NOT NULL DEFAULT \'legacy\'");')
    change('HistoryDb','    public void insertPosting(HistoryRow r) {','    public boolean insertPosting(HistoryRow r) {')
    change('HistoryDb','        getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);',"""        try { return getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_ABORT) != -1; }
        catch (android.database.sqlite.SQLiteException failure) { return false; }""")
    change('HistoryDb','    public void update(String internalid, String status, String txnid, String note) {','    public boolean update(String internalid, String status, String txnid, String note) {')
    change('HistoryDb','        getWritableDatabase().update(TABLE, v, "internalid=?", new String[]{internalid});',"""        SQLiteDatabase db;
        try { db=getWritableDatabase();db.beginTransaction(); }
        catch (android.database.sqlite.SQLiteException failure) { return false; }
        try {
            // Recovery may finish before a late send callback; never downgrade confirmed evidence.
            for (Attempt attempt:attempts(db)) if(attempt.id.equals(internalid)
                    &&(CONFIRMED.equals(attempt.phase)||NOT_SUBMITTED.equals(attempt.phase)&&!STATUS_ERROR.equals(status)))return false;
            if(db.update(TABLE,v,"internalid=?",new String[]{internalid})!=1)return false;
            db.setTransactionSuccessful();return true;
        } catch (RuntimeException failure) { return false; }
        finally { db.endTransaction(); }""")
    p=folder/'HistoryDb.java';s=p.read_text();s=s.rstrip()[:-1]+(project/'scripts/pandamonium-sources/NFTwallet/transaction-journal.inc').read_text()+'}\n';p.write_text(s)
    change('TxnUtil','        act.history().insertPosting(r);\n        return internalid;','        return act.history().insertPosting(r) ? internalid : null;')
    change('TxnBuilder','        void onFailed(String message);', '        void onFailed(String message);\n        /** The builder issued no node command; preparation/admission itself failed. */\n        default void onNotStarted(String message) { onFailed(message); }')
    change('TxnBuilder','done.onFailed(TransactionEvidence.INVALID)', 'done.onNotStarted(TransactionEvidence.INVALID)')
    change('TxnBuilder','    private boolean started, finished;','    private boolean started, finished, prepared;\n    private String historyId;\n    /** Every custom builder must be linked to its saved display row before it can issue commands. */\n    public TxnBuilder record(String id) {\n        if (started) throw new IllegalStateException("Transaction already started");\n        historyId=id;return this;\n    }\n    private boolean mark(String phase, JSONObject reply) {\n        try { return prepared && act.history().markAttempt(historyId,phase,reply); }\n        catch (RuntimeException failure) { return false; }\n    }')
    change('TxnBuilder','        stage("Building transaction…");', '        try {\n            if (historyId == null || !act.history().prepareAttempt(historyId,txid,intent)) throw new IllegalStateException();\n            prepared=true;\n        } catch (RuntimeException failure) { finished=true;done.onNotStarted(TransactionEvidence.RECORD_FAILED);return; }\n        stage("Building transaction…");')
    change('TxnBuilder','    private void sign() {','    private void sign() {\n        if (!mark(HistoryDb.POSTING,null)) { unknown(TransactionEvidence.UNKNOWN); return; }')
    change('TxnBuilder','                        finished = true;\n                        done.onPosted','                        if (!mark(HistoryDb.POSTED,json)) { unknown(TransactionEvidence.UNKNOWN); return; }\n                        finished = true;\n                        done.onPosted')
    change('TxnBuilder','        // An executing post may still finish.','        mark(HistoryDb.UNKNOWN,null); // failed updates leave the earlier intent and reservation intact\n        // An executing post may still finish.')
    change('TxnBuilder','    private void fail(String message) {\n        if (finished) return;\n        finished = true;','    private void fail(String message) {\n        if (finished) return;\n        if (!mark(HistoryDb.NOT_SUBMITTED,null)) { unknown(TransactionEvidence.UNKNOWN); return; }\n        finished = true;')
    change('WalletTools','        }).run();\n    }\n\n    // ---- Split ----','        }).record(internalid).run();\n    }\n\n    // ---- Split ----')
    change('WalletTools','        status.show(Language.msg(l10n_utxo_consolidating), true);','        if (internalid == null) { status.show(NftMessages.message(TransactionEvidence.SAVE_FAILED),false);return; }\n        status.show(Language.msg(l10n_utxo_consolidating), true);')
    change('SendView','        }).onProgress(label -> status(NftMessages.message(label), true)).run();','        }).record(internalid).onProgress(label -> status(NftMessages.message(label), true)).run();')
    change('DistributeManager','        job.currentInternalId = internalid;','        if (internalid == null) { failBatch(TransactionEvidence.SAVE_FAILED); return; }\n        job.currentInternalId = internalid;')
    change('DistributeManager','        }).run();','        }).record(internalid).run();')
    change('NftMessages','        switch(value) {','        switch(value) {\n            case TransactionEvidence.RECORD_FAILED: return Language.msg(l10n_nft_transaction_record_failed);\n            case TransactionEvidence.NOT_SENT: return Language.msg(l10n_nft_transaction_not_sent);')

    change('TxnUtil','    private TxnUtil() {}', '    private TxnUtil() {}\n\n    /** Native send/consolidate choose inputs inside the node; block the affected token while a custom attempt is unresolved. */\n    static boolean canUseNativeFunds(MainActivity act,String tokenid,String burn) {\n        try {\n            if (act.history().hasUnresolvedAttempt(tokenid)) return false;\n            if (burn == null || burn.isEmpty()) return true;\n            if (!Util.isValidAmount(burn)) return false;\n            return new java.math.BigDecimal(burn).signum()==0 || !act.history().hasUnresolvedAttempt(Util.MINIMA_TOKENID);\n        } catch (RuntimeException unreadable) { return false; }\n    }')
    change('SendView','final String burnStr, final int split, final CharSequence tokenName, final String tokenid) {\n        reviewGeneration++;','final String burnStr, final int split, final CharSequence tokenName, final String tokenid) {\n        if (Util.isValidHexId(tokenid) && !TxnUtil.canUseNativeFunds(act,tokenid,burnStr)) { status(NftMessages.message(TransactionEvidence.RECORD_FAILED),false);return; }\n        reviewGeneration++;')
    change('WalletTools','    private void autoConsolidate(String tokenid) {','    private void autoConsolidate(String tokenid) {\n        if (!TxnUtil.canUseNativeFunds(act,tokenid,null)) { status.show(NftMessages.message(TransactionEvidence.RECORD_FAILED),false);return; }')

    change('MainActivity','    private HistoryDb historyDb;','    private HistoryDb historyDb;\n    private TransactionRecovery transactionRecovery;')
    change('MainActivity','        distribute = new DistributeManager(this);','        distribute = new DistributeManager(this);\n        transactionRecovery = new TransactionRecovery(node,historyDb,this::refreshHistory);')
    change('MainActivity','        if (node != null) node.onDestroy();','        if (transactionRecovery != null) transactionRecovery.close();\n        if (node != null) node.onDestroy();\n        if (historyDb != null && node != null) {\n            try { historyDb.abandonPreparations(node); } catch (RuntimeException unavailable) { /* Keep reservations for next process recovery. */ }\n        }')
    change('MainActivity','                    views[TAB_HISTORY].onNewBlock();','                    views[TAB_HISTORY].onNewBlock();\n                    if (transactionRecovery != null) transactionRecovery.poll(chainBlock);')
