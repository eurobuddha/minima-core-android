package com.eurobuddha.wallet;

import java.util.*;
import java.util.function.BiConsumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.TreeKey;
import org.minima.utils.Crypto;

/** Read-only counterpart of KeyUses Phrase's 64 classic + 32 block derivation jobs.
 * Seeds stay inside this bridge. Only public descriptors leave it; no counters are written.
 */
public final class NodeAuditKeys {
    public static final NodeAuditKeys NODE = new NodeAuditKeys(EmbeddedWallet.NODE, NodeAuditKeys::derivePublicKey);
    static String derivePublicKey(MiniData seed, int index, int size) {
        // The exact scanner/DeriveVectors.java recipe, using WalletCore's modifier hashing.
        WalletCore core = new WalletCore(seed, null);
        return new TreeKey(core.derivePrivateSeed(index), size, size == 64 ? 3 : 4)
                .getPublicKey().to0xString();
    }
    interface Deriver { String publicKey(MiniData seed, int index, int size); }
    private final EmbeddedWallet.Source source;
    private final Deriver derive;
    private String cachedIdentity;
    private String cachedPublic;
    NodeAuditKeys(EmbeddedWallet.Source source, Deriver derive) {
        this.source = source; this.derive = derive;
    }
    public static final class Snapshot {
        /** Public node counters and all 96 deterministic public keys; no phrase or base seed. */
        public final String nodeKeys, derivedKeys;
        private final String identity;
        private Snapshot(String node, String derived, String identity) {
            nodeKeys=node; derivedKeys=derived; this.identity=identity;
        }
    }
    private static final class State {
        final MiniData seed;
        final String identity, keys;
        State(MiniData seed, String keys) {
            this.seed=seed; this.keys=keys;
            identity=Crypto.getInstance().hashObject(seed).to0xString();
        }
    }
    private State read() {
        return source.withWallet(wallet -> {
            // Reuse the authenticated node-seed validation and DB/host gates.
            String phrase=EmbeddedWallet.phrase(wallet);
            MiniData seed=org.minima.utils.BIP39.convertStringToSeed(phrase);
            org.minima.utils.json.JSONObject reply=wallet.executeGenericSQL(
                    "SELECT publickey,size,depth,maxuses,uses FROM keys ORDER BY publickey");
            try {
                if (reply==null || !Boolean.TRUE.equals(reply.get("status"))
                        || !Boolean.TRUE.equals(reply.get("results"))) throw new IllegalStateException();
                JSONObject json=new JSONObject(reply.toString());
                JSONArray rows=json.getJSONArray("rows"), keys=new JSONArray();
                if (integer(json,"count")!=rows.length() || rows.length()==0) throw new IllegalStateException();
                Set<String> seen=new HashSet<>();
                for(int i=0;i<rows.length();i++) {
                    JSONObject r=rows.getJSONObject(i);
                    String pk=r.getString("PUBLICKEY");
                    int size=integer(r,"SIZE"),depth=integer(r,"DEPTH"),max=integer(r,"MAXUSES"),uses=integer(r,"USES");
                    if (!pk.matches("0x[0-9a-fA-F]{64}") || !seen.add(pk.toUpperCase(Locale.ROOT)) || uses<0
                            || !((size==64 && depth==3 && max==262144)
                                || (size==128 && depth==4 && max==268435456))) throw new IllegalStateException();
                    keys.put(row(pk,size,depth,max,uses));
                }
                return new State(seed,keys.toString());
            } catch(Exception invalid) { throw new IllegalStateException("Node public keys unavailable",invalid); }
        });
    }
    private static int integer(JSONObject row,String key) throws Exception {
        return new java.math.BigDecimal(row.get(key).toString()).intValueExact();
    }
    private static JSONObject row(String pk,int size,int depth,int max,int uses) throws Exception {
        return new JSONObject().put("publickey",pk).put("size",size).put("depth",depth)
                .put("maxuses",max).put("uses",uses);
    }
    /** Call on a worker. Never holds the node's wallet monitor while deriving a tree. */
    public synchronized Snapshot prepare(BiConsumer<Integer,Integer> progress) {
        State before=read();
        String result=cachedIdentity!=null && cachedIdentity.equals(before.identity) ? cachedPublic : null;
        if(result==null) {
            try {
                JSONArray keys=new JSONArray(); Set<String> seen=new HashSet<>();
                for(int size:new int[]{64,128}) {
                    int count=size==64 ? 64 : 32;
                    for(int index=0;index<count;index++) {
                        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                        String pk=derive.publicKey(before.seed,index,size);
                        if(pk==null || !pk.matches("0x[0-9a-fA-F]{64}") || !seen.add(pk.toUpperCase(Locale.ROOT)))
                            throw new IllegalStateException("Invalid derived public key");
                        keys.put(row(pk,size,size==64?3:4,size==64?262144:268435456,-1).put("index",index));
                        progress.accept(keys.length(),96);
                    }
                }
                result=keys.toString();
            } catch(RuntimeException error) { throw error; }
            catch(Exception error) { throw new IllegalStateException("Could not derive audit keys",error); }
        }
        State after=read();
        if(!before.identity.equals(after.identity)) throw new IllegalStateException("Node changed during derivation");
        cachedIdentity=after.identity; cachedPublic=result; // Public data only, process-memory cache.
        return new Snapshot(after.keys,result,after.identity);
    }
    public boolean current(Snapshot snapshot) {
        State now=read();
        return now.identity.equals(snapshot.identity) && now.keys.equals(snapshot.nodeKeys);
    }
}
