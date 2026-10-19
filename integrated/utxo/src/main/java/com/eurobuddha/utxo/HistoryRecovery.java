package com.eurobuddha.utxo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Read-only node queries. Only complete matching transactions plus chain inclusion settle an attempt. */
final class HistoryRecovery {
    interface Commands { void cmd(String command, NodeApi.Cb callback); }
    private final Commands node;
    private final HistoryDb history;
    private final Runnable changed;
    private boolean running, closed;
    private int offset, pageSize = 8, pages, lastBlock = -1;
    private List<HistoryRow> waiting;

    HistoryRecovery(Commands node, HistoryDb history, Runnable changed) {
        this.node = node; this.history = history; this.changed = changed;
    }
    void close() { closed = true; running = false; }
    void poll(int block) {
        if (closed || running || block < 0 || block == lastBlock) return;
        try {
            waiting = new ArrayList<>();
            for (HistoryRow attempt : history.listRecoverable())
                if (recoverable(attempt.status)) waiting.add(attempt);
        } catch (RuntimeException unreadable) { return; }
        if (waiting.isEmpty()) { offset = 0; return; }
        running = true; pages = 0; lastBlock = block;
        page();
    }
    static boolean recoverable(String phase) {
        return HistoryDb.STATUS_POSTING.equals(phase) || HistoryDb.STATUS_UNKNOWN.equals(phase) || HistoryDb.STATUS_POSTED.equals(phase);
    }
    private void page() {
        if (closed) return;
        final int start = offset, requested = pageSize;
        node.cmd("history relevant:true max:" + requested + " offset:" + start, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if (closed || !running) return;
                JSONObject response = reply == null ? null : reply.optJSONObject("response");
                JSONArray txpows = response == null ? null : response.optJSONArray("txpows");
                if (!Boolean.TRUE.equals(reply == null ? null : reply.opt("status"))
                        || txpows == null || txpows.length() > requested) { running = false; return; }
                JSONArray details=response.optJSONArray("details");
                if (Boolean.TRUE.equals(reply.opt("pending")) || details==null || details.length()!=txpows.length()) {
                    running=false; return;
                }
                pages++;
                inspect(txpows, details, 0, () -> {
                    if (txpows.length() < requested) { offset = 0; running = false; return; }
                    if (start > Integer.MAX_VALUE - txpows.length()) { offset = 0; running = false; return; }
                    offset = start + txpows.length();
                    if (pages >= 4 || waiting.isEmpty()) { running = false; return; }
                    page();
                });
            }
            @Override public void onError(String message) {
                if (closed || !running) return;
                if (!NodeApi.ERR_TOO_LONG.equals(message)) { running = false; return; }
                if (pageSize > 1) { pageSize = Math.max(1, pageSize / 2); page(); return; }
                // One opaque entry must not hide older evidence. Keep every unresolved attempt; the next
                // complete scan retries this entry. Charge it against this tick's four-page budget.
                pages++;
                if (start == Integer.MAX_VALUE) { offset = 0; running = false; return; }
                offset = start + 1;
                if (pages >= 4) running = false; else page();
            }
        });
    }
    private void inspect(JSONArray txpows, JSONArray details, int index, Runnable done) {
        if (closed || !running) return;
        if (index >= txpows.length() || waiting.isEmpty()) { done.run(); return; }
        JSONObject txpow = txpows.optJSONObject(index);
        try {
            if (txpow == null || !Boolean.TRUE.equals(txpow.opt("istransaction"))) throw HistoryEvidence.invalid();
            HistoryEvidence.id(txpow,"txpowid");
        } catch (Exception invalid) { inspect(txpows, details, index + 1, done); return; }
        List<HistoryRow> matches = new ArrayList<>();
        for (HistoryRow attempt : waiting) {
            try { if(HistoryEvidence.matches(attempt,txpow)) matches.add(attempt); }
            catch (RuntimeException mismatch) { /* A different transaction proves nothing about this intent. */ }
        }
        if (matches.size()!=1) { inspect(txpows, details, index + 1, done); return; }
        node.cmd("txpow onchain:" + txpow.optString("txpowid"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject inclusion) {
                if (closed || !running) return;
                boolean any = false;
                for (HistoryRow attempt : matches) {
                    try {
                        if (history.confirmHistory(attempt.internalid, txpow, details.optJSONObject(index), inclusion)) {
                            waiting.remove(attempt); any = true;
                        }
                    } catch (RuntimeException unavailable) { /* Keep the attempt unresolved and retry a later scan. */ }
                }
                if (any) changed.run();
                inspect(txpows, details, index + 1, done);
            }
            @Override public void onError(String message) { inspect(txpows, details, index + 1, done); }
        });
    }
}
