package com.eurobuddha.wallet;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.Test;
import org.minima.database.wallet.Wallet;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.Signature;
import org.minima.system.params.GeneralParams;
import org.minima.utils.BIP39;
import org.minima.utils.json.JSONObject;
import static org.junit.Assert.*;

/** Real node Wallet and H2 in temporary files; never starts a node or uses owner data. */
public class NodeKeyUsesTest {
    private static final String PHRASE = "disposable coordinated wallet fixture only";
    private static final MiniData TXID = new MiniData("0x0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20");
    static final class TestWallet extends Wallet {
        String failPrefix;
        @Override public synchronized JSONObject executeGenericSQL(String command) {
            if (failPrefix != null && command.startsWith(failPrefix)) {
                JSONObject failure = new JSONObject(); failure.put("status",false); return failure;
            }
            return super.executeGenericSQL(command);
        }
    }
    static final class Fixture implements AutoCloseable {
        final boolean blockBefore = GeneralParams.USE_BLOCK_AS_KEYUSES;
        final File path;
        final TestWallet node = new TestWallet();
        final PrefsKeyUsesTest.Store prefs = new PrefsKeyUsesTest.Store();
        final PrefsKeyUses local = prefs.open("classic_fixture");
        final WalletCore core = new WalletCore(PHRASE,local);
        final String key;
        boolean ready=true;
        Fixture() throws Exception {
            GeneralParams.USE_BLOCK_AS_KEYUSES=false;
            path = Files.createTempDirectory("wallet-counter-node-").resolve("wallet").toFile();
            node.loadDB(path);
            node.updateSeedRow(PHRASE,BIP39.convertStringToSeed(PHRASE).to0xString());
            key=node.createNewKey().getPublicKey();
            assertEquals(core.getPublicKey(0).to0xString(),key);
        }
        EmbeddedWallet.Source source() {
            return new EmbeddedWallet.Source() {
                public <T> T withWallet(Function<Wallet,T> work) {
                    if (!ready) throw new IllegalStateException("fixture stopped");
                    synchronized(node) { return work.apply(node); }
                }
            };
        }
        NodeKeyUses uses() { return new NodeKeyUses(local,i -> core.getPublicKey(i).to0xString(),source()); }
        void sql(String command) { assertEquals(Boolean.TRUE,node.executeGenericSQL(command).get("status")); }
        int nodeCount() { return node.getKeyFromPublic(key).getUses(); }
        @Override public void close() {
            node.hardCloseDB(); GeneralParams.USE_BLOCK_AS_KEYUSES=blockBefore;
        }
    }
    @Test public void maximumIsDurableInBothStoresBeforeReturningLeaf() throws Exception {
        try (Fixture f=new Fixture()) {
            f.local.recordExternalUses(0,12);
            f.sql("UPDATE keys SET uses=5");
            assertEquals(12,f.uses().reserveNextUse(0));
            assertEquals(13,f.local.currentUses(0));
            assertEquals(13,f.nodeCount());
            f.node.hardCloseDB(); f.node.loadDB(f.path);
            assertEquals(13,f.nodeCount());
            f.sql("UPDATE keys SET uses=20");
            assertEquals(20,f.uses().reserveNextUse(0));
            assertEquals(21,f.local.currentUses(0));
            assertEquals(21,f.nodeCount());
        }
    }
    @Test public void nativeAndLocalSignersNeverEmitTheSameLeafUnderConcurrency() throws Exception {
        try (Fixture f=new Fixture()) {
            Set<String> leafKeys=ConcurrentHashMap.newKeySet();
            ExecutorService exec=Executors.newFixedThreadPool(3);
            try {
                List<Callable<Void>> jobs=new ArrayList<>();
                for(int worker=0;worker<3;worker++) {
                    final boolean nativeSigner=worker==0;
                    jobs.add(() -> {
                        WalletCore app=new WalletCore(PHRASE,f.uses());
                        for(int j=0;j<4;j++) {
                            Signature sig=nativeSigner ? f.node.signData(f.key,TXID) : app.signTransactionID(TXID,0);
                            assertNotNull(sig);
                            assertTrue(f.core.deriveTreeKey(0).verify(TXID,sig));
                            List<org.minima.objects.keys.SignatureProof> proofs=sig.getAllSignatureProofs();
                            assertTrue("one-time leaf used by exactly one signer",leafKeys.add(proofs.get(proofs.size()-1).getPublicKey().to0xString()));
                        }
                        return null;
                    });
                }
                for(Future<Void> future:exec.invokeAll(jobs)) future.get(30,TimeUnit.SECONDS);
                assertEquals(12,leafKeys.size());
                assertEquals(12,f.nodeCount());
                assertEquals(12,f.uses().currentUses(0));
            } finally { exec.shutdownNow(); }
        }
    }
    @Test public void failedNodeWriteOrFlushNeverReturnsALeafAndRetrySkipsIt() throws Exception {
        for(String failure:new String[]{"UPDATE keys", "CHECKPOINT SYNC"}) {
            try (Fixture f=new Fixture()) {
                f.node.failPrefix=failure;
                assertThrows(IllegalStateException.class, () -> f.uses().reserveNextUse(0));
                assertEquals(1,f.local.currentUses(0));
                f.node.failPrefix=null;
                assertEquals(1,f.uses().reserveNextUse(0));
                assertEquals(2,f.nodeCount());
            }
        }
    }
    @Test public void failedQueryCannotBeTreatedAsAnUnownedKey() throws Exception {
        try(Fixture f=new Fixture()) {
            f.node.failPrefix="SELECT";
            assertThrows(IllegalStateException.class, () -> f.uses().reserveNextUse(0));
            assertEquals(0,f.local.currentUses(0));
        }
    }
    @Test public void explicitAbsenceUsesLocalCounterWithoutCreatingANodeKey() throws Exception {
        try(Fixture f=new Fixture()) {
            assertEquals(0,f.uses().reserveNextUse(1));
            assertEquals(1,f.local.currentUses(1));
            assertEquals(1,f.node.getAllKeys().size());
            assertEquals(0,f.nodeCount());
        }
    }
    @Test public void externalFloorIsRaiseOnlyInBothStores() throws Exception {
        try(Fixture f=new Fixture()) {
            f.uses().recordExternalUses(0,137);
            f.uses().recordExternalUses(0,3);
            assertEquals(137,f.nodeCount());
            assertEquals(137,f.local.currentUses(0));
            assertEquals(137,f.uses().reserveNextUse(0));
        }
    }
    @Test public void exhaustedMalformedAndAmbiguousRowsCannotReserve() throws Exception {
        for(String mutation:new String[]{"UPDATE keys SET uses=262144", "UPDATE keys SET uses=-1",
                "UPDATE keys SET uses=2147483648", "UPDATE keys SET size=128,depth=4,maxuses=268435456",
                "INSERT INTO keys(size,depth,uses,maxuses,modifier,privatekey,publickey) SELECT size,depth,uses,maxuses,modifier,privatekey,publickey FROM keys"}) {
            try(Fixture f=new Fixture()) {
                f.sql(mutation);
                assertThrows(IllegalStateException.class, () -> f.uses().reserveNextUse(0));
            }
        }
    }
    @Test public void stoppedNodeAndFailedLocalMirrorCannotAdvanceNode() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready=false;
            assertThrows(IllegalStateException.class, () -> f.uses().reserveNextUse(0));
            f.ready=true; f.prefs.b.fail=true;
            assertThrows(IllegalStateException.class, () -> f.uses().reserveNextUse(0));
            assertEquals(0,f.nodeCount());
        }
    }
    @Test public void invalidPublicKeyCannotReachSql() throws Exception {
        try(Fixture f=new Fixture()) {
            NodeKeyUses uses=new NodeKeyUses(f.local,i -> "0x00' OR 1=1 --",f.source());
            assertThrows(IllegalStateException.class, () -> uses.reserveNextUse(0));
            assertEquals(0,f.nodeCount());
        }
    }
}
