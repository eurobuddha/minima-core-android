package com.eurobuddha.utxo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared helpers for the transaction tools (Send / Split / Distribute). */
public final class TxnUtil {

    private TxnUtil() {}

    /** Callback for the async getaddress loop in {@link #fetchAddresses}. */
    public interface AddrList {
        void onAddresses(List<String> addresses);
        void onError(String message);
    }

    /** Insert a pending history row and return its internal id. */
    public static String recordPosting(MainActivity act, String recipient, String amount,
                                       String tokenid, String tokenName) {
        return recordPosting(act, recipient, amount, tokenid, tokenName, null, null, null, null);
    }

    /** Insert a pending history row with full FROM/TO/CHANGE/BURN detail for the History view. */
    public static String recordPosting(MainActivity act, String recipient, String amount,
                                       String tokenid, String tokenName, List<Coin> inputs,
                                       List<TxnBuilder.Out> outputs, String changeaddr, String burn) {
        long ts = System.currentTimeMillis();
        // Local-only key (no on-chain id yet); txnid starts as the same placeholder and is overwritten
        // with the real txpowid once the resolver matches this row's input coinids on-chain.
        String internalid = "uw_" + ts + "_" + Integer.toHexString(new Random().nextInt(0x1000000));
        HistoryRow r = new HistoryRow();
        r.internalid = internalid;
        r.txnid = internalid;
        r.status = HistoryDb.STATUS_POSTING;
        r.recipient = recipient;
        r.amount = amount;
        r.tokenid = tokenid;
        r.tokenName = tokenName;
        r.ts = ts;
        r.note = "";
        r.inputs = inputsJson(inputs);
        r.outputs = outputsJson(outputs);
        r.changeaddr = changeaddr == null ? "" : changeaddr;
        r.burn = burn == null ? "" : burn;
        act.history().insertPosting(r);
        return internalid;
    }

    // Inputs are stored as JSON [{coinid,address,amount}] — the coinids are what the resolver later
    // matches against on-chain inputs to find the real txpowid, and what Re-post replays on failure.
    /** Serialize the spent coins to the inputs JSON stored on the history row. */
    private static String inputsJson(List<Coin> inputs) {
        if (inputs == null) return "";
        JSONArray arr = new JSONArray();
        for (Coin c : inputs) {
            try {
                JSONObject o = new JSONObject();
                o.put("coinid", c.coinid);
                o.put("address", c.miniaddress != null && !c.miniaddress.isEmpty() ? c.miniaddress : c.address);
                o.put("amount", c.amount);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /** Serialize the recipient outputs to the outputs JSON [{address,amount}] stored on the row. */
    private static String outputsJson(List<TxnBuilder.Out> outputs) {
        if (outputs == null) return "";
        JSONArray arr = new JSONArray();
        for (TxnBuilder.Out o : outputs) {
            try {
                JSONObject j = new JSONObject();
                j.put("address", o.address);
                j.put("amount", o.amount);
                arr.put(j);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /** The inputs of a posted txpow as the history-row JSON [{coinid,address,amount}] — for node-driven
     *  commands (consolidate) whose coins we didn't pick, so the resolver can still match the row.
     *  Accepts {response:{txpow:{…}}} or {response:{body:…}}; "" when there is no txpow in the reply. */
    public static String inputsJsonFromTxpow(JSONObject json) {
        try {
            JSONObject resp = json.optJSONObject("response");
            if (resp == null) return "";
            JSONObject txpow = resp.optJSONObject("txpow");
            if (txpow == null) txpow = resp;
            JSONObject body = txpow.optJSONObject("body");
            JSONObject txn = body == null ? null : body.optJSONObject("txn");
            JSONArray ins = txn == null ? null : txn.optJSONArray("inputs");
            if (ins == null || ins.length() == 0) return "";
            JSONArray arr = new JSONArray();
            for (int i = 0; i < ins.length(); i++) {
                JSONObject c = ins.optJSONObject(i);
                if (c == null) continue;
                JSONObject o = new JSONObject();
                o.put("coinid", c.optString("coinid", ""));
                o.put("address", c.optString("miniaddress", c.optString("address", "")));
                o.put("amount", c.optString("tokenamount", c.optString("amount", "")));
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) { return ""; }
    }

    /** Fetch n addresses by calling getaddress sequentially (the node cycles its defaults). */
    public static void fetchAddresses(MainActivity act, int n, AddrList cb) {
        fetchNext(act, n, new ArrayList<>(), cb);
    }

    /** Recursive step: getaddress once, append, recurse until n collected (or error). */
    private static void fetchNext(MainActivity act, int n, List<String> out, AddrList cb) {
        if (out.size() >= n) { cb.onAddresses(out); return; }
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String a = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (a.isEmpty()) { cb.onError("Could not fetch an address."); return; }
                out.add(a);
                fetchNext(act, n, out, cb);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }
}
