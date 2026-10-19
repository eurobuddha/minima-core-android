"""Reuse NFT transaction evidence, durable attempts, distribution and prepared consolidation."""
from pathlib import Path
import shutil

VALIDATORS = '    public static boolean isValidHexId(String id) {\n        return id != null && (MINIMA_TOKENID.equals(id) || id.matches("^0x[0-9a-fA-F]{2,64}$"));\n    }\n\n    /**\n     * A plain decimal amount as the node emits them. Amounts are interpolated into commands too,\n     * so a chain-supplied "1 burn:9999" must never reach one.\n     */\n    public static boolean isValidAmount(String amt) {\n        return amt != null && amt.matches("^[0-9]+(\\\\.[0-9]+)?$");\n    }\n\n'
COIN_VALIDATOR = '    public boolean isWellFormed() {\n        return Util.isValidHexId(coinid)\n                && Util.isValidHexId(tokenid)\n                && Util.isValidAmount(amount)\n                && Util.isValidAmount(rawAmount)\n                && (address == null || address.isEmpty() || Util.isValidHexId(address));\n    }\n\n'
UPDATE_BODY = '        SQLiteDatabase db;\n        try { db=getWritableDatabase();db.beginTransaction(); }\n        catch (android.database.sqlite.SQLiteException failure) { return false; }\n        try {\n            // Recovery may finish before a late send callback; never downgrade confirmed evidence.\n            for (Attempt attempt:attempts(db)) if(attempt.id.equals(internalid)\n                    &&(CONFIRMED.equals(attempt.phase)||NOT_SUBMITTED.equals(attempt.phase)&&!STATUS_ERROR.equals(status)))return false;\n            if(db.update(TABLE,v,"internalid=? AND status<>?",new String[]{internalid,STATUS_CONFIRMED})!=1)return false;\n            db.setTransactionSuccessful();return true;\n        } catch (RuntimeException failure) { return false; }\n        finally { db.endTransaction(); }\n    }\n\n'
CONSOLIDATE = '    private void autoConsolidate(String tokenid) {\n        if (!TxnUtil.canUseNativeFunds(act,tokenid,null)) { status.show(UtxoMessages.message(TransactionEvidence.RECORD_FAILED),false);return; }\n        final String internalid = TxnUtil.recordPosting(act, "Consolidate " + tokenNameFor(tokenid),\n                "—", tokenid, tokenNameFor(tokenid));\n        if (internalid == null) { status.show(UtxoMessages.message(TransactionEvidence.SAVE_FAILED),false);return; }\n        status.show(Language.msg(l10n_utxo_consolidating), true);\n        new PreparedConsolidation(act,tokenid,internalid,new TxnBuilder.Done() {\n            @Override public void onPosted(String txpowid,List<TxnBuilder.OutCoin> outputs) {\n                act.history().update(internalid,HistoryDb.STATUS_POSTED,null,"");\n                status.show(Language.msg(l10n_utxo_consolidate_submitted),true);act.reload();\n            }\n            @Override public void onFailed(String message) {\n                if(NodeApi.ERR_NOT_ENABLED.equals(message))message="The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";\n                act.history().update(internalid,HistoryDb.STATUS_ERROR,null,message);\n                status.show(Language.msg(l10n_utxo_failed,UtxoMessages.message(message)),false);\n            }\n            @Override public void onUnknown(String message) {\n                act.history().update(internalid,HistoryDb.STATUS_UNKNOWN,null,message);\n                status.show(UtxoMessages.message(message),false);act.reload();\n            }\n        }).run();\n    }\n\n'

def change(s,old,new):
    assert s.count(old)==1,('UTXO journal drift',old)
    return s.replace(old,new)

def adapt_app(folder,integrated=True):
    source=Path(__file__).resolve().parent/'pandamonium-sources/utxo'
    for name in ['CoinLoader','TransactionFields','TransactionEvidence','TxnBuilder','TransactionRecovery','PreparedSendEvidence','PreparedConsolidation','DistributeJob','DistributeManager']:
        s=(source/(name+'.java')).read_text()
        if not integrated and name=='DistributeManager':
            s=s.replace('import com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n','')
            a=s.index('        CharSequence s = Language.join(');b=s.index('        return s;',a)
            s=s[:a]+"""        String s = "Distribution: " + job.batchesDone + " batches done, " + job.remaining.size() + " addresses remaining · "
                + (job.waiting && !inFlight ? "waiting for confirmation" : "posting");
        if (job.retryCount > 0) s += " · retry " + job.retryCount + "/" + MAX_RETRIES;
"""+s[b:]
            s=s.replace('c.tokenNameProvided=original.tokenNameProvided;','')
            s=s.replace('UtxoMessages.message(TransactionEvidence.DISTRIBUTION_UNKNOWN)','TransactionEvidence.DISTRIBUTION_UNKNOWN').replace('UtxoMessages.message(msg)','msg')
        (folder/(name+'.java')).write_text(s)
    p=folder/'Util.java';s=p.read_text();s=change(s,'    private Util() {}','    private Util() {}\n\n'+VALIDATORS);p.write_text(s)
    p=folder/'Coin.java';s=p.read_text();s=change(s,'    public String tokenName;','    public String rawAmount;\n    public boolean confirmed = true;\n    public boolean spent;\n    public String tokenName;')
    s=change(s,'        boolean minima = Util.isMinima(x.tokenid);','        boolean minima = Util.isMinima(x.tokenid);\n        x.rawAmount = c.optString("amount", "");\n        x.spent = c.optBoolean("spent", false);')
    s=s.replace('c.optString("tokenid", Util.MINIMA_TOKENID)', 'c.optString("tokenid", "")')
    s=s.replace('c.optBoolean("spent", false)', 'c.has("spent") && !Boolean.FALSE.equals(c.opt("spent"))')
    s=s.replace('c.optString("tokenamount", c.optString("amount", "0"))', 'c.optString("tokenamount", "")')
    s=s.rstrip()[:-1]+COIN_VALIDATOR+'}\n';p.write_text(s)
    p=folder/'HistoryDb.java';s=p.read_text()
    s=change(s,'DB_VERSION = 7;','DB_VERSION = 8;').replace('// v7: recheck legacy confirmations; preserve both history tables','// v8: durable attempts; preserve legacy history and cache')
    s=change(s,'    public void onCreate(SQLiteDatabase db) {','    public void onCreate(SQLiteDatabase db) {\n        createAttempts(db);')
    s=change(s,'    public void insertPosting(HistoryRow r) {','    public boolean insertPosting(HistoryRow r) {')
    s=change(s,'        getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);','        try { return getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_ABORT) != -1; }\n        catch (android.database.sqlite.SQLiteException failure) { return false; }')
    s=s.replace('Insert (or replace on internalid conflict) a history row','Insert a new history row without replacing an existing internalid')
    s=change(s,'    public void update(String internalid, String status, String txnid, String note) {','    public boolean update(String internalid, String status, String txnid, String note) {')
    a=s.index('        getWritableDatabase().update(TABLE, v,',s.index('    public boolean update('));b=s.index('    /** Record the inputs',a)
    s=s[:a]+UPDATE_BODY+s[b:]
    # Legacy matching cannot release or confirm a modern journalled attempt.
    s=change(s,"FROM history WHERE status IN ('posting','posted','unknown') ORDER BY id DESC", "FROM history WHERE status IN ('posting','posted','unknown') AND NOT EXISTS (SELECT 1 FROM transaction_attempts a WHERE a.internalid=history.internalid) ORDER BY id DESC")
    # Keep a confirmed audit row visible until its node-history entry has been downloaded.
    s=change(s,'"\' ORDER BY id DESC LIMIT " + limit);','"\' OR NOT EXISTS (SELECT 1 FROM nodetx n WHERE lower(n.txpowid)=lower(history.txnid)) ORDER BY id DESC LIMIT " + limit);')
    # The imported NFT tests and audit inspection use an unfiltered bounded list.
    marker='    private List<HistoryRow> query(String sql) {'
    s=change(s,marker,'    public List<HistoryRow> list(int limit) {\n        return query("SELECT id,internalid,txnid,status,recipient,amount,tokenid,tokenname,ts,note,inputs,outputs,changeaddr,burn FROM history ORDER BY id DESC LIMIT " + limit);\n    }\n\n'+marker)
    s=change(s,'getWritableDatabase().delete(TABLE, "internalid=?", new String[]{internalid});',
        'getWritableDatabase().delete(TABLE, "internalid=? AND NOT EXISTS (SELECT 1 FROM transaction_attempts a WHERE a.internalid=history.internalid AND a.phase NOT IN (\'CONFIRMED\',\'NOT_SUBMITTED\'))", new String[]{internalid});')
    s=s.rstrip()[:-1]+(source/'transaction-journal.inc').read_text()+'}\n';p.write_text(s)
    p=folder/'TxnUtil.java';s=p.read_text();s=change(s,'        act.history().insertPosting(r);\n        return internalid;','        return act.history().insertPosting(r) ? internalid : null;')
    s=change(s,'    private TxnUtil() {}',"""    private TxnUtil() {}

    /** Native selection cannot bypass reservations held by a custom transaction. */
    static boolean canUseNativeFunds(MainActivity act,String tokenid,String burn) {
        try {
            if (act.history().hasUnresolvedAttempt(tokenid)) return false;
            if (burn == null || burn.isEmpty()) return true;
            if (!Util.isValidAmount(burn)) return false;
            return new java.math.BigDecimal(burn).signum()==0 || !act.history().hasUnresolvedAttempt(Util.MINIMA_TOKENID);
        } catch (RuntimeException unreadable) { return false; }
    }""");p.write_text(s)
    p=folder/'WalletTools.java';s=p.read_text();s=change(s,'        }).run();\n    }\n\n    // ---- Split ----','        }).record(internalid).run();\n    }\n\n    // ---- Split ----')
    a=s.index('    private void autoConsolidate(');b=s.index('    /** Merge exactly',a);section=CONSOLIDATE
    if not integrated:
        section=section.replace('UtxoMessages.message(TransactionEvidence.RECORD_FAILED)','TransactionEvidence.RECORD_FAILED').replace('UtxoMessages.message(TransactionEvidence.SAVE_FAILED)','TransactionEvidence.SAVE_FAILED')
        section=section.replace('Language.msg(l10n_utxo_consolidating)','"Consolidating…"').replace('Language.msg(l10n_utxo_consolidate_submitted)','"Consolidate submitted."').replace('Language.msg(l10n_utxo_failed,UtxoMessages.message(message))','"Failed: "+message').replace('UtxoMessages.message(message)','message')
        section=section.replace('The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.','Enable this wallet in Minima Core → Apps first.')
    s=s[:a]+section+s[b:];p.write_text(s)
    p=folder/'SendView.java';s=p.read_text();old='        }).onProgress(label -> status(UtxoMessages.message(label), true)).run();' if integrated else '        }).onProgress(label -> status(label, true)).run();'
    s=change(s,old,old.replace('}).onProgress','}).record(internalid).onProgress'));p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    s=change(s,'    private HistoryRecovery historyRecovery;','    private HistoryRecovery historyRecovery;\n    private TransactionRecovery transactionRecovery;')
    s=change(s,'        historyRecovery = new HistoryRecovery(node::cmd,historyDb,this::refreshHistory);','        historyRecovery = new HistoryRecovery(node::cmd,historyDb,this::refreshHistory);\n        transactionRecovery = new TransactionRecovery(node,historyDb,this::refreshHistory);')
    s=change(s,'        if (node != null) node.onDestroy();','        if (transactionRecovery != null) transactionRecovery.close();\n        if (distribute != null) distribute.close();\n        if (node != null) node.onDestroy();\n        if (historyDb != null && node != null) {\n            try { historyDb.abandonPreparations(node); } catch (RuntimeException unavailable) { /* Keep reservations for restart recovery. */ }\n        }')
    s=change(s,'                    if (historyRecovery != null) historyRecovery.poll(chainBlock);','                    if (historyRecovery != null) historyRecovery.poll(chainBlock);\n                    if (transactionRecovery != null) transactionRecovery.poll(chainBlock);');p.write_text(s)
    p=folder/'HistoryView.java';s=p.read_text()
    needle='            case HistoryDb.STATUS_POSTING:'
    s=s.replace(needle,('            case HistoryDb.STATUS_CONFIRMED: return Language.msg(l10n_nft_history_confirmed);\n' if integrated else '            case HistoryDb.STATUS_CONFIRMED: return "Confirmed";\n')+needle)
    s=s.replace('int color = failed ? Design.red() : Design.amber();','int color = failed ? Design.red() : HistoryDb.STATUS_CONFIRMED.equals(r.status) ? 0xFF1EA85A : Design.amber();')
    s=s.replace('failed ? "✗" : "⏳"','failed ? "✗" : HistoryDb.STATUS_CONFIRMED.equals(r.status) ? "✓" : "⏳"')
    s=s.replace('isFailed(r) ? Language.msg(l10n_utxo_failed_transaction) : Language.msg(l10n_utxo_pending_transaction)', 'Language.msg(l10n_history_transaction)')
    s=s.replace('isFailed(r) ? "Failed transaction" : "Pending transaction"', '"Transaction"')
    s=s.replace('if (isFailed(r) || stale)', 'if (isFailed(r))')
    s=s.replace('        // Dismiss: failed rows, and any open row older than a day (something the resolver could never match).\n        boolean stale = System.currentTimeMillis() - r.ts > 24L * 60 * 60 * 1000;','        // Pending evidence must survive elapsed time; only failed audit rows offer dismissal.')
    at='        kv(box, Language.msg(l10n_utxo_status), statusWord(r));' if integrated else '        kv(box, "Status", statusWord(r));'
    if at in s:
        s=s.replace(at,at+'\n        if (Util.isValidHexId(r.txnid)) copyRow(box, '+('Language.msg(l10n_history_txpow)' if integrated else '"TxPoW"')+', r.txnid);')
    p.write_text(s)
    if integrated:
        p=folder/'UtxoMessages.java';s=p.read_text();cases=''
        for name,key in [('INVALID','invalid'),('UNKNOWN','unknown'),('SAVE_FAILED','save_failed'),('RECORD_FAILED','record_failed'),('NOT_SENT','not_sent')]:
            cases+='            case TransactionEvidence.'+name+': return Language.msg(l10n_nft_transaction_'+key+');\n'
        cases+='            case TransactionEvidence.DISTRIBUTION_UNKNOWN: return Language.msg(l10n_nft_transaction_distribution_unknown);\n'
        s=change(s,'        switch(value) {','        switch(value) {\n'+cases);p.write_text(s)

def adapt(root):
    adapt_app(root/'utxo/src/main/java/com/eurobuddha/utxo')
