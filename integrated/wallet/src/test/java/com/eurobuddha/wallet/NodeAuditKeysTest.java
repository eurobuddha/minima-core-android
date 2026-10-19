package com.eurobuddha.wallet;

import org.junit.Test;
import org.json.*;
import org.minima.database.wallet.Wallet;
import org.minima.database.wallet.SeedRow;
import org.minima.objects.base.MiniData;
import org.minima.utils.BIP39;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class NodeAuditKeysTest {
    static class Fixture implements EmbeddedWallet.Source {
        final Wallet wallet=mock(Wallet.class);
        String phrase="read only key audit fixture";
        int uses=100;
        boolean broken;
        int entered;
        Fixture() {
            when(wallet.isBaseSeedAvailable()).thenReturn(true);
            when(wallet.getBaseSeed()).thenAnswer(a -> {
                SeedRow row=mock(SeedRow.class); when(row.getPhrase()).thenReturn(phrase);
                when(row.getSeed()).thenReturn(BIP39.convertStringToSeed(phrase).to0xString());return row;
            });
            when(wallet.executeGenericSQL(anyString())).thenAnswer(a -> {
                assertEquals("SELECT publickey,size,depth,maxuses,uses FROM keys ORDER BY publickey",a.getArgument(0));
                org.minima.utils.json.JSONObject out=new org.minima.utils.json.JSONObject();
                out.put("status",!broken);out.put("results",true);out.put("count",1);
                org.minima.utils.json.JSONArray rows=new org.minima.utils.json.JSONArray();
                org.minima.utils.json.JSONObject r=new org.minima.utils.json.JSONObject();
                r.put("PUBLICKEY",pk(128,0));r.put("SIZE",128);r.put("DEPTH",4);r.put("MAXUSES",268435456);r.put("USES",uses);
                rows.add(r);out.put("rows",rows);return out;
            });
        }
        public <T>T withWallet(Function<Wallet,T> job) {entered++;try{return job.apply(wallet);}finally{entered--;}}
    }
    static String pk(int size,int index) {return String.format(Locale.ROOT,"0x%064X",size*1000+index);}
    @Test public void derivesAll96OutsideWalletLockAndCachesOnlyPublicKeys() throws Exception {
        Fixture f=new Fixture();AtomicInteger calls=new AtomicInteger(),progress=new AtomicInteger();
        NodeAuditKeys reader=new NodeAuditKeys(f,(seed,index,size)->{
            assertEquals(0,f.entered);calls.incrementAndGet();return pk(size,index);
        });
        NodeAuditKeys.Snapshot s=reader.prepare((done,total)->{assertEquals(96,total.intValue());progress.set(done);});
        JSONArray keys=new JSONArray(s.derivedKeys);assertEquals(96,keys.length());assertEquals(96,progress.get());
        for(int i=0;i<96;i++) {
            JSONObject k=keys.getJSONObject(i);assertEquals(i<64?64:128,k.getInt("size"));
            assertEquals(i<64?i:i-64,k.getInt("index"));assertEquals(-1,k.getInt("uses"));
        }
        assertFalse(s.derivedKeys.contains(f.phrase));assertFalse(s.nodeKeys.contains(f.phrase));
        assertTrue(reader.current(s));f.uses++;assertFalse(reader.current(s));
        NodeAuditKeys.Snapshot next=reader.prepare((d,t)->{});assertEquals(96,calls.get());assertTrue(reader.current(next));
        f.phrase="different read only fixture";reader.prepare((d,t)->{});assertEquals(192,calls.get());
    }
    @Test public void changedSeedAndFailedQueryNeverPublishSnapshot() {
        Fixture f=new Fixture();NodeAuditKeys reader=new NodeAuditKeys(f,(seed,index,size)->{
            if(size==128&&index==31)f.phrase="changed fixture";return pk(size,index);
        });
        assertThrows(IllegalStateException.class,()->reader.prepare((d,t)->{}));
        f.broken=true;assertThrows(IllegalStateException.class,()->reader.prepare((d,t)->{}));
    }
    @Test public void malformedOrDuplicateDerivationFailsClosed() {
        for(String bad:new String[]{"0xGG","0x"+String.join("",Collections.nCopies(64,"G")),pk(64,0)}) {
            Fixture f=new Fixture();NodeAuditKeys reader=new NodeAuditKeys(f,(s,i,n)->bad);
            assertThrows(IllegalStateException.class,()->reader.prepare((d,t)->{}));
        }
    }
    @Test public void bothShapesMatchAllExistingPhraseGoldenVectors() throws Exception {
        String json=new String(getClass().getResourceAsStream("/keyuses-derive-vectors.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        JSONArray vectors=new JSONObject(json).getJSONArray("vectors");
        for(int i=0;i<vectors.length();i++) {
            JSONObject v=vectors.getJSONObject(i);
            String actual=NodeAuditKeys.derivePublicKey(new MiniData(v.getString("baseseed")),v.getInt("index"),v.getInt("keysPerLevel"));
            assertEquals(v.getString("publickey"),actual);
            org.minima.objects.Address address=new org.minima.objects.Address("RETURN SIGNEDBY("+actual+")");
            assertEquals(v.getString("address"),address.getAddressData().to0xString());
            assertEquals(v.getString("mx"),address.getMinimaAddress());
        }
    }
}
