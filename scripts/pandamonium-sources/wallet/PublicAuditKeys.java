package com.eurobuddha.wallet;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Public-only audit view. Reading it never unlocks the vault, derives keys or writes counters. */
public final class PublicAuditKeys {
    static final String VAULT_FILE = "wallet_vault_v1";
    static final String VAULT_BLOB = "vault_blob_hex";
    static final String RECORD = "public_audit_keys_v1";
    private PublicAuditKeys() {}

    // Same seed/family namespace used by the mirrored signing counters.
    static String namespace(String phrase) {
        return "classic_" + org.minima.utils.Crypto.getInstance().hashObject(
                org.minima.utils.BIP39.convertStringToSeed(phrase)).to0xString().substring(2);
    }

    /** Called inside the wallet's existing authenticated setup/unlock/persistence paths. */
    static String describe(VaultBlob blob, KeyUses counters) {
        if (!(counters instanceof NodeKeyUses))
            throw new IllegalStateException("Wallet public audit keys are unavailable");
        try {
            JSONArray keys = new JSONArray();
            for (Map.Entry<Integer,String> key : ((NodeKeyUses)counters).auditPublicKeys().entrySet())
                keys.put(new JSONObject().put("index", key.getKey()).put("publickey", key.getValue()));
            return new JSONObject().put("version", 1).put("namespace", namespace(blob.getPhrase()))
                    .put("trusted", blob.isKeyUsesTrusted()).put("keys", keys).toString();
        } catch (org.json.JSONException invalid) {
            throw new IllegalStateException("Wallet public audit keys are unavailable", invalid);
        }
    }

    /** Complete current classic key list, or an explicit failure; never substitutes an empty list. */
    public static JSONArray snapshot(Context context) {
        Context app = context.getApplicationContext();
        // One preference snapshot sees the blob and its public descriptor from the same commit.
        Map<String,?> stored = app.getSharedPreferences(VAULT_FILE, Context.MODE_PRIVATE).getAll();
        if (!stored.containsKey(VAULT_BLOB)) return new JSONArray(); // wallet has never been configured
        Object raw = stored.get(RECORD);
        if (!(raw instanceof String) || ((String)raw).length() > VaultCrypto.MAX_CONTAINER_BYTES)
            throw new IllegalStateException("Unlock minimaWallet to prepare its public audit keys");
        try {
            JSONObject record = new JSONObject((String)raw);
            if (integer(record.get("version")) != 1 || !(record.get("trusted") instanceof Boolean))
                throw new IllegalArgumentException("Invalid public key record");
            String ns = record.getString("namespace");
            if (!ns.matches("classic_[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid key namespace");
            boolean trusted = record.getBoolean("trusted");
            JSONArray keys = record.getJSONArray("keys");
            Set<Integer> indices = new HashSet<>();
            Set<String> publicKeys = new HashSet<>();
            JSONArray rows = new JSONArray();
            PrefsKeyUses counters = new PrefsKeyUses(app, ns);
            for (int i=0; i<keys.length(); i++) {
                JSONObject key = keys.getJSONObject(i);
                int index = integer(key.get("index"));
                String pk = key.getString("publickey");
                if (index < 0 || !indices.add(index) || !pk.matches("0x[0-9a-fA-F]{64}")
                        || !publicKeys.add(pk.toUpperCase(java.util.Locale.ROOT)))
                    throw new IllegalArgumentException("Invalid or duplicate public key");
                int uses = counters.currentUses(index); // MAX across existing durable mirrors
                rows.put(new JSONObject().put("publickey",pk).put("uses",trusted ? uses : -1)
                        .put("size",64).put("depth",3).put("maxuses",262144));
            }
            for (int i=0; i<WalletCore.NUM_BASE_KEYS; i++)
                if (!indices.contains(i)) throw new IllegalArgumentException("Incomplete base key list");
            // A counter for an additional index must not silently escape the audit.
            for (int index : counters.snapshotAllUses().keySet())
                if (!indices.contains(index)) throw new IllegalArgumentException("Public key metadata needs refreshing");
            return rows;
        } catch (Exception invalid) {
            throw new IllegalStateException("minimaWallet public audit data is incomplete. Unlock it and retry", invalid);
        }
    }

    private static int integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid integer");
        return new java.math.BigDecimal(value.toString()).intValueExact();
    }
}
