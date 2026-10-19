package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntFunction;
import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.objects.base.MiniData;
import org.minima.utils.BaseConverter;

/** Seed-authenticated PUBLIC keys only. No counters or private TreeKeys are cached.
 * Reuses VaultCrypto's HKDF and authenticated container; the vault format is unchanged. */
final class CachedPublicKeys implements IntFunction<String> {
    interface Store { String read(); boolean write(String value); }
    private static final int MAX_BYTES=256*1024;
    private static final byte[] DOMAIN="minima-wallet/public-key-cache/v1".getBytes(StandardCharsets.UTF_8);
    private final Store store;
    private final IntFunction<String> derive;
    private final String cacheKey;
    private final Map<Integer,String> keys=new TreeMap<>();
    private boolean dirty;

    static CachedPublicKeys open(Context context,WalletCore core) {
        String namespace=org.minima.utils.Crypto.getInstance().hashObject(core.getBaseSeed()).to0xString();
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(
                "pandamonium_wallet_public_keys_v1_"+namespace,Context.MODE_PRIVATE);
        return new CachedPublicKeys(new Store() {
            public String read(){return prefs.getString("authenticated_keys",null);}
            public boolean write(String value){return prefs.edit().putString("authenticated_keys",value).commit();}
        },core.getBaseSeed(),index->core.getPublicKey(index).to0xString());
    }

    CachedPublicKeys(Store store,MiniData baseSeed,IntFunction<String> derive) {
        this.store=store;this.derive=derive;
        try {
            byte[] key=VaultCrypto.hkdfExpandSha256(baseSeed.getBytes(),DOMAIN,32);
            try {cacheKey=BaseConverter.encode16(key);}finally{Arrays.fill(key,(byte)0);}
        } catch(java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("Public key cache protection unavailable",unavailable);
        }
        load();
    }
    private static boolean valid(int index,String key) {
        return index>=0 && key!=null && key.matches("0x[0-9a-fA-F]{64}");
    }
    private void load() {
        try {
            String hex=store.read();
            if(hex==null||hex.length()>MAX_BYTES*2+2||!hex.matches("0x[0-9a-fA-F]+"))return;
            byte[] encrypted=new MiniData(hex).getBytes();
            // This cache uses a seed-derived 256-bit key, not a human password. Avoid
            // attacker-selected KDF work; ordinary vaults keep their 210,000 iterations.
            if(encrypted.length<56||encrypted[6]!=0||encrypted[7]!=0||encrypted[8]!=0||encrypted[9]!=1)return;
            byte[] plain=VaultCrypto.decrypt(cacheKey,encrypted);
            try {
                JSONObject root=new JSONObject(new String(plain,StandardCharsets.UTF_8));
                if(root.length()!=2||!Integer.valueOf(1).equals(root.get("version")))return;
                JSONArray rows=root.getJSONArray("keys");Map<Integer,String> checked=new TreeMap<>();
                Set<String> unique=new HashSet<>();
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.getJSONObject(i);
                    Object index=row.get("index");String key=row.getString("publickey");
                    if(row.length()!=2||!(index instanceof Integer)||!valid((Integer)index,key)
                            ||checked.containsKey((Integer)index)||!unique.add(key.toUpperCase(Locale.ROOT)))return;
                    checked.put((Integer)index,key);
                }
                keys.putAll(checked);
            } finally {Arrays.fill(plain,(byte)0);}
        } catch(Exception invalid) {
            // Missing/corrupt/wrong-seed cache is a miss, never authority to sign or reset counters.
            keys.clear();
        }
    }
    @Override public synchronized String apply(int index) {
        if(index<0)throw new IllegalArgumentException("Negative key index");
        String key=keys.get(index);
        if(key==null) {
            key=derive.apply(index);
            if(!valid(index,key))throw new IllegalStateException("Invalid wallet public key");
            keys.put(index,key);dirty=true;
        }
        return key;
    }
    synchronized void save() {
        if(!dirty)return;
        try {
            JSONArray rows=new JSONArray();
            for(Map.Entry<Integer,String> key:keys.entrySet())
                rows.put(new JSONObject().put("index",key.getKey()).put("publickey",key.getValue()));
            byte[] plain=new JSONObject().put("version",1).put("keys",rows).toString().getBytes(StandardCharsets.UTF_8);
            if(plain.length>MAX_BYTES-56)return;
            byte[] encrypted;
            try {encrypted=VaultCrypto.encrypt(cacheKey,plain,1);}finally{Arrays.fill(plain,(byte)0);}
            if(store.write(BaseConverter.encode16(encrypted)))dirty=false;
        } catch(Exception unavailable) {
            // Optional acceleration only. Real keys/counters/vault persistence retain their own gates.
        }
    }
}
