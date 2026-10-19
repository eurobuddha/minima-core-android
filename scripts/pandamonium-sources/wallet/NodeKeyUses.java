package com.eurobuddha.wallet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.minima.database.wallet.Wallet;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/** Shares classic counters with Wallet.signData using the same Wallet monitor. */
public final class NodeKeyUses implements KeyUses {
    private final KeyUses local;
    private final IntFunction<String> publicKey;
    private final EmbeddedWallet.Source source;

    public NodeKeyUses(android.content.Context context,String phrase,KeyUses local) {
        this(local,CachedPublicKeys.open(context,new WalletCore(phrase,local)),EmbeddedWallet.NODE);
    }

    public NodeKeyUses(String phrase, KeyUses local) {
        this(local, new WalletCore(phrase, local), EmbeddedWallet.NODE);
    }
    private NodeKeyUses(KeyUses local, WalletCore core, EmbeddedWallet.Source source) {
        this(local, index -> core.getPublicKey(index).to0xString(), source);
    }
    NodeKeyUses(KeyUses local, IntFunction<String> publicKey, EmbeddedWallet.Source source) {
        this.local = local;
        this.publicKey = publicKey;
        this.source = source;
    }
    private String key(int index) {
        if (index < 0) throw new IllegalArgumentException("Negative key index");
        String key = publicKey.apply(index);
        if (key == null || !key.matches("0x[0-9a-fA-F]{64}"))
            throw new IllegalStateException("Invalid wallet public key");
        return key;
    }
    @Override public int currentUses(int index) {
        String key = key(index);
        return source.withWallet(wallet -> {
            synchronized (wallet) {
                Row row = read(wallet, key);
                int count = local.currentUses(index);
                if (row != null && row.uses > count) {
                    local.recordExternalUses(index, row.uses);
                    count = row.uses;
                }
                return count;
            }
        });
    }
    @Override public int reserveNextUse(int index) {
        String key = key(index);
        return source.withWallet(wallet -> {
            synchronized (wallet) {
                Row row = read(wallet, key);
                if (row != null) local.recordExternalUses(index, row.uses);
                int before = local.currentUses(index);
                if (before < 0 || before >= 262144) throw new IllegalStateException("Classic key exhausted");
                int leaf = local.reserveNextUse(index);
                if (leaf < before || leaf >= 262144) throw new IllegalStateException("Invalid signing reservation");
                if (row != null) raiseNode(wallet, key, row, leaf + 1);
                // The local mirrors and (when owned) node DB are durable before the caller signs.
                return leaf;
            }
        });
    }
    @Override public void recordExternalUses(int index, int uses) {
        if (uses < 0) throw new IllegalArgumentException("Negative key uses");
        String key = key(index);
        source.withWallet(wallet -> {
            synchronized (wallet) {
                Row row = read(wallet, key);
                local.recordExternalUses(index, Math.max(uses, row == null ? 0 : row.uses));
                if (row != null) raiseNode(wallet, key, row, local.currentUses(index));
                return null;
            }
        });
    }
    /** Export the already-derived classic public keys; never exports the private derivation. */
    Map<Integer,String> auditPublicKeys() {
        java.util.TreeSet<Integer> indices = new java.util.TreeSet<>(local.snapshotAllUses().keySet());
        for (int i=0; i<WalletCore.NUM_BASE_KEYS; i++) indices.add(i);
        Map<Integer,String> keys = new LinkedHashMap<>();
        for (int index : indices) keys.put(index, key(index));
        if(publicKey instanceof CachedPublicKeys)((CachedPublicKeys)publicKey).save();
        return keys;
    }
    @Override public Map<Integer,Integer> snapshotAllUses() {
        Map<Integer,Integer> snapshot = new LinkedHashMap<>(local.snapshotAllUses());
        // Include the entire base set: the node can have spent an index never used by this UI.
        for (int index=0; index<WalletCore.NUM_BASE_KEYS; index++)
            snapshot.put(index, currentUses(index));
        for (int index : new java.util.ArrayList<>(snapshot.keySet()))
            if (index >= WalletCore.NUM_BASE_KEYS) snapshot.put(index, currentUses(index));
        return snapshot;
    }
    private static final class Row {
        final int uses;
        Row(int uses) { this.uses=uses; }
    }
    private static JSONObject sql(Wallet wallet, String command) {
        JSONObject reply = wallet.executeGenericSQL(command);
        if (reply == null || !Boolean.TRUE.equals(reply.get("status")))
            throw new IllegalStateException("Could not verify or persist the node key counter");
        return reply;
    }
    private static Row read(Wallet wallet, String key) {
        JSONObject reply = sql(wallet, "SELECT size,depth,uses,maxuses FROM keys WHERE publickey='" + key + "'");
        Object raw = reply.get("rows");
        if (!Boolean.TRUE.equals(reply.get("results")) || !(raw instanceof JSONArray))
            throw new IllegalStateException("Node key query was incomplete");
        JSONArray rows = (JSONArray)raw;
        if (exact(reply.get("count")) != rows.size() || rows.size() > 1)
            throw new IllegalStateException("Node key query was ambiguous");
        if (rows.isEmpty()) return null; // explicit successful absence, never a query failure
        if (!(rows.get(0) instanceof JSONObject)) throw new IllegalStateException("Invalid node key row");
        JSONObject row = (JSONObject)rows.get(0);
        int size = exact(row.get("SIZE")), depth = exact(row.get("DEPTH"));
        int capacity = exact(row.get("MAXUSES")), uses = exact(row.get("USES"));
        if (size != 64 || depth != 3 || capacity != 262144 || uses < 0)
            throw new IllegalStateException("Node key is not a valid classic key");
        return new Row(uses);
    }
    private static int exact(Object value) {
        if (value == null) throw new IllegalStateException("Missing node key counter");
        try { return new java.math.BigDecimal(value.toString()).intValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid node key counter",invalid); }
    }
    private static void raiseNode(Wallet wallet, String key, Row row, int floor) {
        if (floor < row.uses) throw new IllegalStateException("Counter would decrease");
        if (floor > row.uses) {
            sql(wallet, "UPDATE keys SET uses=" + floor + " WHERE publickey='" + key
                    + "' AND uses=" + row.uses + " AND size=64 AND depth=3 AND maxuses=262144");
        }
        Row updated = read(wallet,key);
        if (updated == null || updated.uses < floor)
            throw new IllegalStateException("Node counter update did not persist");
        // The node uses H2 autocommit; CHECKPOINT SYNC forces the committed MVStore to disk.
        sql(wallet, "CHECKPOINT SYNC");
    }
}
