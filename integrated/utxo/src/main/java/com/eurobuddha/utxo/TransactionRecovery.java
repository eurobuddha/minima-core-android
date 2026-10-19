package com.eurobuddha.utxo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Read-only node queries. Only complete matching transactions plus chain inclusion settle an attempt. */
final class TransactionRecovery {
    private final NodeApi node;
    private final HistoryDb history;
    private final Runnable changed;
    private boolean running, closed;
    private int offset, pageSize = 8, pages, lastBlock = -1;
    private List<HistoryDb.Attempt> waiting;

    TransactionRecovery(NodeApi node, HistoryDb history, Runnable changed) {
        this.node = node; this.history = history; this.changed = changed;
    }
    void close() { closed = true; running = false; }
    void poll(int block) {
        if (closed || running || block < 0 || block == lastBlock) return;
        try {
            waiting = new ArrayList<>();
            for (HistoryDb.Attempt attempt : history.transactionAttempts())
                if (recoverable(attempt.phase)) waiting.add(attempt);
        } catch (RuntimeException unreadable) { return; }
        if (waiting.isEmpty()) { offset = 0; return; }
        running = true; pages = 0; lastBlock = block;
        page();
    }
    static boolean recoverable(String phase) {
        return HistoryDb.POSTING.equals(phase) || HistoryDb.UNKNOWN.equals(phase) || HistoryDb.POSTED.equals(phase);
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
                        || Boolean.TRUE.equals(reply.opt("pending")) || txpows == null || txpows.length() > requested) { running = false; return; }
                pages++;
                inspect(txpows, 0, () -> {
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
                // One opaque entry must not hide older evidence. Keep all reservations; the next
                // complete scan retries this entry. Charge it against this tick's four-page budget.
                pages++;
                if (start == Integer.MAX_VALUE) { offset = 0; running = false; return; }
                offset = start + 1;
                if (pages >= 4) running = false; else page();
            }
        });
    }
    private void inspect(JSONArray txpows, int index, Runnable done) {
        if (closed || !running) return;
        if (index >= txpows.length() || waiting.isEmpty()) { done.run(); return; }
        JSONObject txpow = txpows.optJSONObject(index);
        JSONObject receipt;
        try {
            if (txpow == null || !Boolean.TRUE.equals(txpow.opt("istransaction"))) throw TransactionEvidence.invalid();
            receipt = new JSONObject().put("status", true).put("response", txpow);
            TransactionEvidence.txpow(receipt);
        } catch (Exception invalid) { inspect(txpows, index + 1, done); return; }
        List<HistoryDb.Attempt> matches = new ArrayList<>();
        for (HistoryDb.Attempt attempt : waiting) {
            try { TransactionEvidence.receipt(receipt, attempt.intent); matches.add(attempt); }
            catch (RuntimeException mismatch) { /* A different transaction proves nothing about this intent. */ }
        }
        if (matches.isEmpty()) { inspect(txpows, index + 1, done); return; }
        final JSONObject exactReceipt = receipt;
        node.cmd("txpow onchain:" + txpow.optString("txpowid"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject inclusion) {
                if (closed || !running) return;
                boolean any = false;
                for (HistoryDb.Attempt attempt : matches) {
                    try {
                        if (history.confirmAttempt(attempt.id, exactReceipt, inclusion)) {
                            waiting.remove(attempt); any = true;
                        }
                    } catch (RuntimeException unavailable) { /* Keep the reservation and retry a later scan. */ }
                }
                if (any) changed.run();
                inspect(txpows, index + 1, done);
            }
            @Override public void onError(String message) { inspect(txpows, index + 1, done); }
        });
    }
}
