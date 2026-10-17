package com.eurobuddha.wallet;

import java.util.function.Function;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.minima.database.wallet.Wallet;
import org.minima.database.wallet.SeedRow;
import org.minima.utils.BIP39;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Synthetic phrases with a mocked embedded node; no Main, network or owner data. */
public class SharedNodeSeedTest {
    private static final String A = "disposable node seed setup fixture alpha";
    private static final String B = "disposable node seed setup fixture beta";
    private static final String PASS = "disposable setup passphrase";
    private static SeedRow row(String phrase) throws Exception {
        return new SeedRow(phrase, BIP39.convertStringToSeed(phrase).to0xString());
    }
    private static EmbeddedWallet.Source source(Wallet wallet) {
        return new EmbeddedWallet.Source() {
            public <T> T withWallet(Function<Wallet,T> work) {
                synchronized(wallet) { return work.apply(wallet); }
            }
        };
    }
    @Test public void nodeSeedSetupPreservesHigherCountsAcrossEveryClassicKey() throws Exception {
        Wallet node = mock(Wallet.class);
        when(node.isBaseSeedAvailable()).thenReturn(true);
        when(node.getBaseSeed()).thenReturn(row(A));
        SeedCounterLifecycleTest.Counters counters = new SeedCounterLifecycleTest.Counters();
        counters.forPhrase(A).recordExternalUses(63, 180);
        SeedVault vault = new SeedVault(new SeedCounterLifecycleTest.Store(), counters);
        vault.importNodeSeed(PASS, 137, source(node));
        assertEquals(A, vault.phrase());
        assertTrue(vault.isKeyUsesTrusted());
        for (int i=0;i<63;i++) assertEquals(137, vault.keyUses().currentUses(i));
        assertEquals(180, vault.keyUses().currentUses(63));
        verify(node,never()).createNewKey();
    }
    @Test public void unavailableOrInconsistentNodeSeedCannotReplaceCurrentVault() throws Exception {
        SeedCounterLifecycleTest.Store store = new SeedCounterLifecycleTest.Store();
        SeedVault vault = new SeedVault(store, new SeedCounterLifecycleTest.Counters());
        vault.importSeed(A, PASS, 9);
        String original = store.value;
        Wallet node = mock(Wallet.class);
        assertThrows(IllegalStateException.class, () -> vault.importNodeSeed(PASS, 0, source(node)));
        when(node.isBaseSeedAvailable()).thenReturn(true);
        when(node.getBaseSeed()).thenReturn(new SeedRow(B, BIP39.convertStringToSeed(A).to0xString()));
        assertThrows(IllegalStateException.class, () -> vault.importNodeSeed(PASS, 0, source(node)));
        assertEquals(original, store.value);
        assertEquals(A, vault.phrase());
        assertEquals(9, vault.keyUses().currentUses(0));
    }
    @Test public void nodeSeedChangingDuringSetupCannotCommitTheWrongSeed() throws Exception {
        Wallet node = mock(Wallet.class);
        when(node.isBaseSeedAvailable()).thenReturn(true);
        when(node.getBaseSeed()).thenReturn(row(A), row(B));
        SeedCounterLifecycleTest.Store store = new SeedCounterLifecycleTest.Store();
        SeedVault vault = new SeedVault(store, new SeedCounterLifecycleTest.Counters());
        vault.importSeed(B, PASS, 9);
        String original = store.value;
        assertThrows(IllegalStateException.class, () -> vault.importNodeSeed(PASS, 137, source(node)));
        assertEquals(original, store.value);
        assertEquals(B, vault.phrase());
        assertEquals(9, vault.keyUses().currentUses(0));
    }
    @Test public void invalidFloorIsRejectedBeforeReadingNodeSeed() {
        Wallet node = mock(Wallet.class);
        SeedVault vault = new SeedVault(new SeedCounterLifecycleTest.Store(),new SeedCounterLifecycleTest.Counters());
        for (int floor : new int[]{-1,262144,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> vault.importNodeSeed(PASS, floor, source(node)));
        verifyNoInteractions(node);
    }
    @Test public void authorizationDoesNotReviveWhenTheSameSeedIsReopened() {
        SeedVault vault = new SeedVault(new SeedCounterLifecycleTest.Store(),new SeedCounterLifecycleTest.Counters());
        vault.importSeed(A,PASS,9);
        BooleanSupplier first = vault.authorization();
        assertTrue(first.getAsBoolean());
        assertTrue(vault.verifyPassphrase(PASS));
        assertTrue(first.getAsBoolean());
        vault.lock();
        assertFalse(first.getAsBoolean());
        assertTrue(vault.open(PASS));
        assertFalse(first.getAsBoolean());
        BooleanSupplier reopened = vault.authorization();
        assertTrue(reopened.getAsBoolean());
        vault.importSeed(B,PASS,2);
        assertFalse(reopened.getAsBoolean());
        assertTrue(vault.authorization().getAsBoolean());
    }
    @Test public void seedReplacementCancelsQueuedDirectCommandBeforeExecution() {
        SeedVault vault = new SeedVault(new SeedCounterLifecycleTest.Store(),new SeedCounterLifecycleTest.Counters());
        vault.importSeed(A,PASS,9);
        int[] delivered = {0};
        DirectNodeApi.Request request = new DirectNodeApi.Request(reply -> delivered[0]++, Runnable::run, vault.authorization());
        vault.importSeed(B,PASS,2);
        assertFalse(request.begin());
        assertEquals(1, delivered[0]);
        assertFalse(request.active());
    }
}
