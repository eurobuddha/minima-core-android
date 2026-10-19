package com.eurobuddha.keyreuses;

import org.json.*;
import java.util.*;
import com.eurobuddha.futurecashnext.KeyAudit;
import com.eurobuddha.futurecashnext.MinimaAddress;

/** Public snapshots only. MAX reconciliation mirrors NodeKeyUses without writing any counter. */
final class AuditSelection {
    static final int NODE=1, CLASSIC=2, BLOCK=4, WALLET=8;
    final List<KeyAudit.LocalKey> keys=new ArrayList<>();
    final List<MinimaAddress.Result> addresses=new ArrayList<>();
    final List<Integer> origins=new ArrayList<>();
    final List<String> publicKeys=new ArrayList<>(), hexAddresses=new ArrayList<>();
    private static final class Entry {
        String pk; int size,depth,max,uses,origin;
        Entry(JSONObject row,int origin) throws Exception {
            pk=row.getString("publickey");size=number(row,"size");depth=number(row,"depth");
            max=number(row,"maxuses");uses=number(row,"uses");this.origin=origin;
            if(!pk.matches("0x[0-9a-fA-F]{64}") || uses < -1
                    || !((size==64 && depth==3 && max==262144) || (size==128 && depth==4 && max==268435456)))
                throw new IllegalArgumentException("Invalid public key metadata");
        }
    }
    AuditSelection(String node,String derived,String wallet,boolean includeWallet) {
        try {
            Map<String,Entry> merged=new LinkedHashMap<>();
            // Derivation carries no historical counter; an absent value stays unknown.
            JSONArray defaults=new JSONArray(derived);
            int classic=0,block=0;
            Set<String> indices=new HashSet<>();
            for(int i=0;i<defaults.length();i++) {
                JSONObject row=defaults.getJSONObject(i);int size=number(row,"size"), index=number(row,"index");
                if(index<0 || index >= (size==64?64:32) || !indices.add(size+":"+index))
                    throw new IllegalArgumentException("Incomplete derived key set");
                add(merged,new Entry(row,size==64?CLASSIC:BLOCK),false);
                if(size==64)classic++;else block++;
            }
            if(classic!=64 || block!=32) throw new IllegalArgumentException("Incomplete derived key set");
            JSONArray nodes=new JSONArray(node); if(nodes.length()==0) throw new IllegalArgumentException("No node keys");
            append(merged,nodes,NODE);
            if(includeWallet) {
                JSONArray extra=new JSONArray(wallet);
                if(extra.length()<64) throw new IllegalArgumentException("Unlock minimaWallet to prepare its public keys");
                append(merged,extra,WALLET);
            }
            for(Entry e:merged.values()) {
                KeyAudit.LocalKey key=new KeyAudit.LocalKey(e.pk,e.uses,e.max,e.size,e.depth);
                MinimaAddress.Result address=MinimaAddress.fromPublicKey(e.pk);
                if(address==null) throw new IllegalArgumentException("Invalid public address");
                keys.add(key);addresses.add(address);origins.add(e.origin);
                publicKeys.add(e.pk);hexAddresses.add(address.hex);
            }
        } catch(Exception invalid) {throw new IllegalStateException("Public audit metadata unavailable",invalid);}
    }
    private static int number(JSONObject row,String field) throws Exception {
        return new java.math.BigDecimal(row.get(field).toString()).intValueExact();
    }
    private static void append(Map<String,Entry> all,JSONArray rows,int origin) throws Exception {
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            Entry next=new Entry(rows.getJSONObject(i),origin);
            if(!seen.add(next.pk.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate source key");
            if(origin==WALLET && next.size!=64) throw new IllegalArgumentException("Invalid minimaWallet family");
            add(all,next,true);
        }
    }
    private static void add(Map<String,Entry> all,Entry next,boolean merge) {
        String id=next.pk.toUpperCase(Locale.ROOT);Entry prior=all.get(id);
        if(prior==null) {all.put(id,next);return;}
        if(!merge || prior.size!=next.size || prior.depth!=next.depth || prior.max!=next.max)
            throw new IllegalArgumentException("Conflicting derived keys");
        // Missing derivation counters can be filled. Untrusted configured-wallet counters stay unknown.
        if(next.origin==WALLET && next.uses<0) prior.uses=-1;
        else prior.uses=Math.max(prior.uses,next.uses);
        prior.origin|=next.origin;
    }
}
