package com.eurobuddha.wallet;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.minima.objects.base.MiniData;
import org.minima.utils.BaseConverter;
import static org.junit.Assert.*;

/** Disposable data only. Check derivation counts, authentication and live counter reconciliation. */
public class CachedPublicKeysTest {
    private static final MiniData SEED=new MiniData("0x1234567890ABCDEF");
    static final class Store implements CachedPublicKeys.Store {
        String value;boolean fail;
        public String read(){return value;}
        public boolean write(String data){if(fail)return false;value=data;return true;}
    }
    private static String key(int i){return String.format(java.util.Locale.ROOT,"0x%064x",i+1);}
    private static CachedPublicKeys warm(Store store) {
        CachedPublicKeys cache=new CachedPublicKeys(store,SEED,CachedPublicKeysTest::key);
        for(int i=0;i<WalletCore.NUM_BASE_KEYS;i++)cache.apply(i);
        cache.apply(70);cache.save();return cache;
    }
    @Test public void repeatedOpenReusesAllBaseAndExtraKeysWithoutDerivation() {
        Store store=new Store();warm(store);assertNotNull(store.value);
        AtomicInteger derived=new AtomicInteger();
        for(int reopen=0;reopen<3;reopen++) {
            CachedPublicKeys cache=new CachedPublicKeys(store,SEED,i->{derived.incrementAndGet();return key(i);});
            for(int i=0;i<WalletCore.NUM_BASE_KEYS;i++)assertEquals(key(i),cache.apply(i));
            assertEquals(key(70),cache.apply(70));
        }
        assertEquals(0,derived.get());
    }
    @Test public void wrongSeedAndTamperedCacheCannotSupplyKeys() {
        Store store=new Store();warm(store);String original=store.value;
        AtomicInteger derived=new AtomicInteger();
        new CachedPublicKeys(store,new MiniData("0xABCDEF"),i->{derived.incrementAndGet();return key(i);}).apply(0);
        byte[] corrupt=new MiniData(original).getBytes();corrupt[corrupt.length-1]^=1;
        store.value=BaseConverter.encode16(corrupt);
        new CachedPublicKeys(store,SEED,i->{derived.incrementAndGet();return key(i);}).apply(0);
        assertEquals(2,derived.get());
    }
    @Test public void untrustedKdfAndMalformedCacheAreMisses() {
        Store store=new Store();warm(store);String original=store.value;
        byte[] expensive=new MiniData(original).getBytes();expensive[6]=0x7F;
        for(String bad:new String[]{"not a container","0x01",BaseConverter.encode16(expensive)}) {
            store.value=bad;AtomicInteger derived=new AtomicInteger();
            CachedPublicKeys cache=new CachedPublicKeys(store,SEED,i->{derived.incrementAndGet();return key(i);});
            assertEquals(key(0),cache.apply(0));assertEquals(1,derived.get());
        }
    }
    @Test public void failedCacheWriteIsOptionalAndCannotInventAKey() {
        Store store=new Store();store.fail=true;warm(store);assertNull(store.value);
        AtomicInteger derived=new AtomicInteger();
        CachedPublicKeys cache=new CachedPublicKeys(store,SEED,i->{derived.incrementAndGet();return key(i);});
        assertEquals(key(0),cache.apply(0));assertEquals(1,derived.get());
        assertThrows(IllegalArgumentException.class,()->cache.apply(-1));
        assertThrows(IllegalStateException.class,()->new CachedPublicKeys(store,SEED,i->"0x00").apply(0));
    }
    @Test public void cachedPublicKeyStillReadsNewNodeCountersAndReservesDurably()throws Exception {
        try(NodeKeyUsesTest.Fixture f=new NodeKeyUsesTest.Fixture()) {
            Store store=new Store();
            CachedPublicKeys first=new CachedPublicKeys(store,f.core.getBaseSeed(),i->f.core.getPublicKey(i).to0xString());
            assertEquals(f.key,first.apply(0));first.save();String oldCache=store.value;
            f.local.recordExternalUses(0,4);f.sql("UPDATE keys SET uses=9");
            CachedPublicKeys reopened=new CachedPublicKeys(store,f.core.getBaseSeed(),i->{throw new AssertionError("Cached key rederived");});
            NodeKeyUses uses=new NodeKeyUses(f.local,reopened,f.source());
            assertEquals(9,uses.currentUses(0));assertEquals(9,uses.reserveNextUse(0));
            assertEquals(10,f.nodeCount());assertEquals(10,f.local.currentUses(0));
            store.value=oldCache;f.sql("UPDATE keys SET uses=15");
            NodeKeyUses again=new NodeKeyUses(f.local,new CachedPublicKeys(store,f.core.getBaseSeed(),i->{throw new AssertionError();}),f.source());
            assertEquals(15,again.currentUses(0));
            f.node.failPrefix="SELECT";
            assertThrows(IllegalStateException.class,()->again.reserveNextUse(0));
        }
    }
}
