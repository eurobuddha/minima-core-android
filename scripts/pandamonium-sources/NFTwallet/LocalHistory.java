package com.eurobuddha.nftwallet;

import java.util.*;

/** Read-only projection of local audit rows. Only validated journal evidence supplies an outcome. */
final class LocalHistory {
    static final class Entry {
        final HistoryRow row;
        final HistoryDb.Attempt attempt;
        Entry(HistoryRow row,HistoryDb.Attempt attempt){this.row=row;this.attempt=attempt;}
        String phase(){return attempt==null?HistoryDb.UNKNOWN:attempt.phase;}
        String txpowid(){return attempt==null||attempt.receipt==null?"":attempt.receipt.optString("txpowid");}
        boolean confirmed(){return HistoryDb.CONFIRMED.equals(phase());}
        String note(){
            if(HistoryDb.NOT_SUBMITTED.equals(phase()))return TransactionEvidence.NOT_SENT;
            if(attempt==null||HistoryDb.UNKNOWN.equals(phase()))return TransactionEvidence.UNKNOWN;
            return "";
        }
    }
    static List<Entry> read(HistoryDb db,int limit) {
        Map<String,HistoryDb.Attempt> attempts=new HashMap<>();
        try {for(HistoryDb.Attempt a:db.transactionAttempts())attempts.put(a.id,a);}
        catch(RuntimeException unavailable){attempts.clear();}
        List<Entry> entries=new ArrayList<>();
        for(HistoryRow row:db.list(limit))entries.add(new Entry(row,attempts.get(row.internalid)));
        return entries;
    }
    static List<Entry> visible(List<Entry> entries,List<NodeTx> chain) {
        Set<String> downloaded=new HashSet<>();
        for(NodeTx tx:chain)if(tx.txpowid!=null)downloaded.add(tx.txpowid.toLowerCase(Locale.ROOT));
        List<Entry> shown=new ArrayList<>();
        for(Entry entry:entries)if(!entry.confirmed()||!downloaded.contains(entry.txpowid().toLowerCase(Locale.ROOT)))shown.add(entry);
        return shown;
    }
}
