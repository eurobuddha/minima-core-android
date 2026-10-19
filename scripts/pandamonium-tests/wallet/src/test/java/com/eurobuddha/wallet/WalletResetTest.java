package com.eurobuddha.wallet;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.wallet.WalletAuthenticatedWorkTest.set;

/** Disposable vaults only; no device seed, transactions or node state. */
public class WalletResetTest {
    static final class Store implements SeedVault.BlobStore {
        String value; boolean fail;
        public boolean contains(){return value!=null;}
        public String read(){return value;}
        public void write(String hex){value=hex;}
        public void delete(){if(fail)throw new IllegalStateException("fixture deletion failed");value=null;}
    }
    @Test public void resetErasesVaultRevokesHandlesAndRetainsBothSeedsCounters() {
        Store store=new Store(); SeedCounterLifecycleTest.Counters counters=new SeedCounterLifecycleTest.Counters();
        SeedVault vault=new SeedVault(store,counters);
        vault.importSeed("disposable reset fixture alpha","disposable passphrase",17);
        KeyUses old=vault.keyUses(); java.util.function.BooleanSupplier authorization=vault.authorization();
        counters.forPhrase("disposable reset fixture beta").recordExternalUses(0,23);
        vault.wipe();assertFalse(vault.exists());assertFalse(vault.isOpen());assertFalse(authorization.getAsBoolean());
        assertThrows(IllegalStateException.class,()->old.reserveNextUse(0));
        assertFalse(vault.open("disposable passphrase"));
        vault.createNew("disposable reset fixture beta","different passphrase");
        assertEquals(23,vault.keyUses().currentUses(0));
        vault.importSeed("disposable reset fixture alpha","different passphrase",0);
        assertEquals(17,vault.keyUses().currentUses(0));
    }
    @Test public void failedDeletionLocksButPreservesRecoverableVaultAndCounters() {
        Store store=new Store();SeedVault vault=new SeedVault(store,new WalletCoreTest.InMemoryKeyUses());
        vault.importSeed("disposable reset fixture","disposable passphrase",19);String original=store.value;
        KeyUses old=vault.keyUses();store.fail=true;
        assertThrows(IllegalStateException.class,vault::wipe);assertFalse(vault.isOpen());
        assertEquals(original,store.value);assertThrows(IllegalStateException.class,()->old.reserveNextUse(0));
        assertTrue(vault.open("disposable passphrase"));assertEquals(19,vault.keyUses().currentUses(0));
        vault.lock();assertThrows(IllegalStateException.class,vault::wipe);assertEquals(original,store.value);
    }
    @Test public void resetUsesWorkerAndReturnsToSetupOnlyAfterSuccessfulDeletion()throws Exception {
        try(WalletAuthenticatedWorkTest.Fixture f=new WalletAuthenticatedWorkTest.Fixture();
            MockedStatic<BiometricUnlock> bio=mockStatic(BiometricUnlock.class);
            MockedConstruction<SweepPending> pending=mockConstruction(SweepPending.class)) {
            android.content.Context app=mock(android.content.Context.class);when(f.activity.getApplicationContext()).thenReturn(app);
            set(f.activity,MainActivity.class,"mDialogs",new WalletDialogs());
            set(f.activity,MainActivity.class,"mBalances",new java.util.ArrayList<>());
            set(f.activity,MainActivity.class,"mMeta",new java.util.LinkedHashMap<>());
            doAnswer(i->{when(f.vault.exists()).thenReturn(false);when(f.vault.isOpen()).thenReturn(false);f.authorized.set(false);return null;}).when(f.session).resetWallet();
            f.activity.resetWallet();verify(f.session,never()).resetWallet();bio.verifyNoInteractions();
            f.workers.remove(0).run();verify(f.session).resetWallet();bio.verify(()->BiometricUnlock.disable(app));
            verify(f.activity,never()).recreate();f.deliveries.remove(0).run();verify(f.activity).recreate();
        }
    }
    @Test public void queuedResetRejectsRevokedSessionBeforeTouchingAnything()throws Exception {
        try(WalletAuthenticatedWorkTest.Fixture f=new WalletAuthenticatedWorkTest.Fixture();
            MockedStatic<BiometricUnlock> bio=mockStatic(BiometricUnlock.class)) {
            f.activity.resetWallet();f.authorized.set(false);f.execute();
            verify(f.session,never()).resetWallet();bio.verifyNoInteractions();verify(f.activity,never()).recreate();
        }
    }
    @Test public void pendingOrUnreadableTransactionsBlockReset()throws Exception {
        for(boolean corrupt:new boolean[]{false,true}) {
            try(WalletAuthenticatedWorkTest.Fixture f=new WalletAuthenticatedWorkTest.Fixture();
                MockedStatic<BiometricUnlock> bio=mockStatic(BiometricUnlock.class);
                MockedConstruction<SweepPending> pending=mockConstruction(SweepPending.class,(p,c)->{
                    if(corrupt)when(p.transactions()).thenThrow(new IllegalStateException("fixture corrupt journal"));
                    else when(p.transactions()).thenReturn(java.util.Collections.singletonMap("fixture","pending"));
                })) {
                f.activity.resetWallet();f.execute();verify(f.session,never()).resetWallet();
                bio.verifyNoInteractions();verify(f.activity,never()).recreate();
            }
        }
    }
    @Test public void corruptVaultAuthenticationCannotReachResetConfirmation()throws Exception {
        try(WalletAuthenticatedWorkTest.Fixture f=new WalletAuthenticatedWorkTest.Fixture()) {
            when(f.vault.verifyPassphrase("fixture")).thenThrow(new SeedVault.VaultCorruptException("fixture",null));
            WalletAuthenticatedWorkTest.call(f.settings(),SettingsView.class,"authenticate",new Class<?>[]{String.class,Runnable.class},
                    "fixture",(Runnable)()->fail("Corrupt vault must not authorize deletion"));
            f.execute();verify(f.session,never()).resetWallet();
        }
    }

}
