package com.eurobuddha.utxo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds and posts a custom Minima transaction over the IPC, the canonical way:
 * txncreate -> txninput (per input coin) -> txnoutput (per output) ->
 * txnsign publickey:auto txnpostauto:true txndelete:true.
 *
 * Shared by Send, Split and Distribute. On success it surfaces the posted transaction's output
 * coins (coinid/address/amount) so a caller can identify e.g. the change coin to chain on.
 */
public class TxnBuilder {

    public interface Done {
        void onPosted(String txpowid, List<OutCoin> outputs);
        void onFailed(String message);
        /** The builder issued no node command; preparation/admission itself failed. */
        default void onNotStarted(String message) { onFailed(message); }
        /** Required: callers must preserve an unknown outcome instead of treating it as failed. */
        void onUnknown(String message);
    }

    /** Live build progress (so the UI shows staged logs, not one static line). */
    public interface Progress { void stage(String label); }

    private Progress progress;
    public TxnBuilder onProgress(Progress p) { this.progress = p; return this; }
    private void stage(String s) { if (progress != null) progress.stage(s); }

    /** An output to create. */
    public static class Out {
        public final String address;
        public final String amount;
        public final Boolean storestate;
        public Out(String address, String amount) { this(address,amount,null); }
        public Out(String address,String amount,Boolean storestate) { this.address=address;this.amount=amount;this.storestate=storestate; }
    }

    /** An output coin as it came back in the posted transaction (both address forms kept). */
    public static class OutCoin {
        public final String coinid;
        public final String address;       // 0x… hex form
        public final String miniaddress;   // Mx… form
        public final String amount;
        public final Boolean storestate;
        public OutCoin(String coinid,String address,String miniaddress,String amount) { this(coinid,address,miniaddress,amount,null); }
        public OutCoin(String coinid, String address, String miniaddress, String amount,Boolean storestate) {
            this.storestate=storestate;
            this.coinid = coinid; this.address = address; this.miniaddress = miniaddress; this.amount = amount;
        }
        /** True if either address form equals the given address. */
        public boolean addressIs(String a) {
            return a != null && ((a.startsWith("0x") && a.equalsIgnoreCase(address)) || a.equals(miniaddress));
        }
    }

    private final MainActivity act;
    private final List<Coin> inputs;
    private final List<Out> outputs;
    private final String tokenid;
    private final boolean minima;
    private final String burn;          // Minima-only burn, already reserved by the caller in the input/output difference
    private final Done done;
    private final String txid;
    private JSONObject intent;
    private boolean started, finished, prepared;
    private String historyId;
    /** Every custom builder must be linked to its saved display row before it can issue commands. */
    public TxnBuilder record(String id) {
        if (started) throw new IllegalStateException("Transaction already started");
        historyId=id;return this;
    }
    private boolean mark(String phase, JSONObject reply) {
        try { return prepared && act.history().markAttempt(historyId,phase,reply); }
        catch (RuntimeException failure) { return false; }
    }

    public TxnBuilder(MainActivity act, List<Coin> inputs, List<Out> outputs, String tokenid, Done done) {
        this(act, inputs, outputs, tokenid, null, done);
    }

    public TxnBuilder(MainActivity act, List<Coin> inputs, List<Out> outputs, String tokenid,
                      String burn, Done done) {
        this.act = act;
        this.inputs = inputs;
        this.outputs = outputs;
        this.tokenid = tokenid;
        this.minima = Util.isMinima(tokenid);
        this.burn = burn;
        this.done = done;
        this.txid = "uw_" + System.currentTimeMillis() + "_"
                + Integer.toHexString(new Random().nextInt(0x1000000));
    }

    public void run() {
        if (started) return;
        started = true;
        try { intent = TransactionEvidence.intent(inputs, outputs, tokenid, burn); }
        catch (RuntimeException invalid) { finished = true; done.onNotStarted(TransactionEvidence.INVALID); return; }
        try {
            if (historyId == null || !act.history().prepareAttempt(historyId,txid,intent)) throw new IllegalStateException();
            prepared=true;
        } catch (RuntimeException failure) { finished=true;done.onNotStarted(TransactionEvidence.RECORD_FAILED);return; }
        stage("Building transaction…");
        act.node().cmd("txncreate id:" + txid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { addInput(0); }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void addInput(int i) {
        if (finished) return;
        JSONArray frozen = intent.optJSONArray("inputs");
        if (i >= frozen.length()) { addOutput(0); return; }
        stage("Adding inputs (" + (i + 1) + "/" + frozen.length() + ")…");
        act.node().cmd("txninput id:" + txid + " coinid:" + frozen.optJSONObject(i).optString("coinid"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { addInput(i + 1); }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void addOutput(int i) {
        if (finished) return;
        JSONArray frozen = intent.optJSONArray("outputs");
        if (i >= frozen.length()) { sign(); return; }
        stage("Adding outputs (" + (i + 1) + "/" + frozen.length() + ")…");
        JSONObject o = frozen.optJSONObject(i);
        String cmd = "txnoutput id:" + txid + " amount:" + o.optString("amount") + " address:" + o.optString("address")
                + (minima ? "" : " tokenid:" + tokenid)
                + (o.has("storestate")?" storestate:"+o.optBoolean("storestate"):"");
        act.node().cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { addOutput(i + 1); }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void sign() {
        if (!mark(HistoryDb.POSTING,null)) { unknown(TransactionEvidence.UNKNOWN); return; }
        stage("Signing & posting (proof-of-work — can take a moment)…");
        // BURN: the caller already reserves the burn by leaving a gap between inputs and outputs
        // (change = inputs − amount − burn); the node burns that surplus (Transaction.getBurn).
        // We deliberately do NOT also pass txnpostburn — that makes the node build a SECOND burn
        // transaction from another wallet coin, so the user burnt 2× and spent a coin they never
        // picked. (The utxoWallet dapp had the same double burn; fixed there in 1.0.54.)
        act.node().cmd("txnsign id:" + txid + " publickey:auto txnpostauto:true txndelete:true",
                new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        if (finished) return;
                        final List<OutCoin> receipt;
                        try { receipt = TransactionEvidence.receipt(json, intent); }
                        catch (RuntimeException incomplete) { unknown(TransactionEvidence.UNKNOWN); return; }
                        if (!mark(HistoryDb.POSTED,json)) { unknown(TransactionEvidence.UNKNOWN); return; }
                        finished = true;
                        done.onPosted(TransactionEvidence.txpow(json).optString("txpowid"), receipt);
                    }
                    @Override public void onError(String message) { fail(message); }
                    @Override public void onUnknown(String message) { unknown(message); }
                });
    }

    private void unknown(String message) {
        if (finished) return;
        finished = true;
        mark(HistoryDb.UNKNOWN,null); // failed updates leave the earlier intent and reservation intact
        // An executing post may still finish. Keep its record; never schedule cleanup or a retry.
        done.onUnknown(TransactionEvidence.UNKNOWN);
    }

    private void fail(String message) {
        if (finished) return;
        if (!mark(HistoryDb.NOT_SUBMITTED,null)) { unknown(TransactionEvidence.UNKNOWN); return; }
        finished = true;
        // Best-effort cleanup of the half-built custom txn on the node.
        act.node().cmd("txndelete id:" + txid, null);
        done.onFailed(message);
    }

}
