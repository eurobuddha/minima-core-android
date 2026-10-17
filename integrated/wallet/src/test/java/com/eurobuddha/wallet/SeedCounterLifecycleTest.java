package com.eurobuddha.wallet;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises the real encrypted vault transitions with disposable phrases and counters. */
public class SeedCounterLifecycleTest {
    private static final String A = "disposable seed counter fixture alpha";
    private static final String B = "disposable seed counter fixture beta";
    private static final String PASS = "disposable vault test passphrase";
    static final class Store implements SeedVault.BlobStore {
        String value;
        boolean fail;
        boolean visibleBeforeFailure;
        public boolean contains() { return value != null; }
        public String read() { return value; }
        public void write(String hex) {
            if (visibleBeforeFailure) value=hex;
            if (fail) throw new IllegalStateException("fixture write failure");
            value=hex;
        }
    }
    static final class Counters implements SeedVault.CounterFactory {
        final Map<String,KeyUses> seeds = new HashMap<>();
        public KeyUses forPhrase(String phrase) {
            return seeds.computeIfAbsent(phrase, p -> new WalletCoreTest.InMemoryKeyUses());
        }
    }
    @Test public void switchingSeedsPreservesEveryClassicCounterAndRevokesOldHandles() {
        Store store = new Store(); Counters counters = new Counters();
        SeedVault vault = new SeedVault(store,counters);
        vault.importSeed(A,PASS,137);
        KeyUses first = vault.keyUses();
        for (int i=0;i<64;i++) assertEquals(137, first.currentUses(i));
        assertEquals(137, first.reserveNextUse(63));
        vault.importSeed(B,PASS,3);
        assertThrows(IllegalStateException.class, () -> first.reserveNextUse(0));
        KeyUses second = vault.keyUses();
        assertEquals(3, second.reserveNextUse(0));
        vault.importSeed(A,PASS,0);
        assertThrows(IllegalStateException.class, () -> second.reserveNextUse(0));
        assertEquals(138,vault.keyUses().currentUses(63));
        assertEquals(137,vault.keyUses().currentUses(0));
        assertEquals(4,counters.forPhrase(B).currentUses(0));
    }
    @Test public void lockedAndReopenedVaultNeverRevivesAnOldSigningHandle() {
        SeedVault vault = new SeedVault(new Store(),new Counters());
        vault.createNew(A,PASS);
        KeyUses old = vault.keyUses();
        vault.lock();
        assertThrows(IllegalStateException.class, () -> old.reserveNextUse(0));
        assertTrue(vault.open(PASS));
        assertThrows(IllegalStateException.class, () -> old.reserveNextUse(0));
        assertEquals(0,vault.keyUses().reserveNextUse(0));
    }
    @Test public void staleRestoreRaisesOnlyItsOwnSeedAndRemainsUntrusted() throws Exception {
        Store store = new Store(); Counters counters = new Counters();
        SeedVault vault = new SeedVault(store,counters);
        vault.importSeed(A,PASS,10);
        byte[] backup = vault.exportBytes(PASS);
        vault.keyUses().reserveNextUse(63);
        vault.importSeed(B,PASS,2);
        vault.importVault(backup,PASS,PASS);
        assertEquals(A,vault.phrase());
        assertEquals(11,vault.keyUses().currentUses(63));
        assertEquals(2,counters.forPhrase(B).currentUses(63));
        assertFalse(vault.isKeyUsesTrusted());
        assertThrows(SeedVault.SigningNotAllowedException.class, () -> vault.keyUses().reserveNextUse(63));
        vault.attestKeyUsesUpToDate();
        assertEquals(11,vault.keyUses().reserveNextUse(63));
    }
    @Test public void failedSeedReplacementRevokesSessionAndReopensOldDurableVault() {
        Store store = new Store(); Counters counters = new Counters();
        SeedVault vault = new SeedVault(store,counters);
        vault.importSeed(A,PASS,9);
        KeyUses old = vault.keyUses();
        String blob = store.value;
        store.fail=true;
        assertThrows(IllegalStateException.class, () -> vault.importSeed(B,PASS,4));
        assertEquals(blob,store.value);
        assertFalse(vault.isOpen());
        assertThrows(IllegalStateException.class, () -> old.reserveNextUse(0));
        store.fail=false;
        SeedVault reopen = new SeedVault(store,counters);
        assertTrue(reopen.open(PASS));
        assertEquals(A,reopen.phrase());
        assertEquals(9,reopen.keyUses().currentUses(0));
    }
    @Test public void failedTrustPersistenceCannotEnableSigningInMemory() {
        for (int operation=0;operation<3;operation++) {
            Store store = new Store();
            SeedVault vault = new SeedVault(store,new Counters());
            vault.importSeed(A,PASS,false);
            store.fail=true;
            final int action=operation;
            assertThrows(IllegalStateException.class, () -> {
                if (action==0) vault.confirmBrandNewSeed();
                else if (action==1) vault.attestKeyUsesUpToDate();
                else vault.setStartingKeyUses(25);
            });
            assertFalse(vault.isKeyUsesTrusted());
            assertThrows(IllegalStateException.class, () -> vault.keyUses().reserveNextUse(0));
        }
    }
    @Test public void uncertainCommitCannotLeaveOldSessionSigningAgainstDifferentStoredSeed() {
        Store store = new Store(); Counters counters = new Counters();
        SeedVault vault = new SeedVault(store,counters);
        vault.importSeed(A,PASS,9);
        KeyUses old = vault.keyUses();
        store.fail=true; store.visibleBeforeFailure=true;
        assertThrows(IllegalStateException.class, () -> vault.importSeed(B,PASS,4));
        assertFalse(vault.isOpen());
        assertThrows(IllegalStateException.class, () -> old.reserveNextUse(0));
        store.fail=false;
        assertTrue(vault.open(PASS));
        assertEquals(B,vault.phrase());
        assertEquals(4,vault.keyUses().currentUses(0));
        assertEquals(9,counters.forPhrase(A).currentUses(0));
    }
    @Test public void wrongPassphraseCannotSwitchSeedOrRevokeCurrentSession() {
        SeedVault vault = new SeedVault(new Store(),new Counters());
        vault.importSeed(A,PASS,7);
        KeyUses current = vault.keyUses();
        assertFalse(vault.open("wrong fixture passphrase"));
        assertEquals(A,vault.phrase());
        assertEquals(7,current.reserveNextUse(0));
    }
}
