package com.eurobuddha.wallet;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.minima.objects.Address;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.wallet.WalletAuthenticatedWorkTest.*;

/** Real startup dispatch, controlled queues and public fixtures; no key derivation or signing. */
public class WalletStartupWorkTest {
    private void initialize(Fixture f) throws Exception {
        set(f.activity,MainActivity.class,"mDialogs",new WalletDialogs());
        set(f.activity,MainActivity.class,"mScreenHost",mock(android.widget.FrameLayout.class));
        set(f.activity,MainActivity.class,"mBalances",new ArrayList<TokenBalance>());
        set(f.activity,MainActivity.class,"mMeta",new LinkedHashMap<String,TokenMeta>());
        when(f.activity.getApplicationContext()).thenReturn(mock(Context.class));
        when(f.vault.keyUses()).thenReturn(mock(KeyUses.class));
        when(f.vault.phrase()).thenReturn("disposable startup fixture");
    }
    private void start(Fixture f) throws Exception {call(f.activity,MainActivity.class,"prepareMain",new Class<?>[0]);}
    @Test public void walletConstructionAndFirstKeyWaitForWorkerThenUiDelivery() throws Exception {
        try(Fixture f=new Fixture();MockedConstruction<WalletCore> wallets=mockConstruction(WalletCore.class,
                (w,c)->when(w.getReceiveAddress()).thenReturn(new Address("RETURN TRUE")));
            MockedConstruction<AddressBook> books=mockConstruction(AddressBook.class);
            MockedConstruction<TxnFactory> factories=mockConstruction(TxnFactory.class)) {
            initialize(f);start(f);
            verify(f.vault,never()).phrase();assertEquals(0,wallets.constructed().size());assertTrue(f.work.busy());
            f.workers.remove(0).run();
            verify(f.vault).phrase();assertEquals(1,wallets.constructed().size());
            assertEquals(1,books.constructed().size());assertEquals(1,factories.constructed().size());
            verify(wallets.constructed().get(0)).getReceiveAddress();
            assertTrue(f.work.busy());assertNull(get(f.activity,"mWallet"));assertNull(get(f.activity,"mNode"));
            // A destroyed screen receives no prepared wallet, even after its worker succeeds.
            when(f.activity.isDestroyed()).thenReturn(true);f.deliveries.remove(0).run();
            assertFalse(f.work.busy());assertNull(get(f.activity,"mWallet"));assertNull(get(f.activity,"mNode"));
        }
    }
    @Test public void revokedGenerationStopsBeforeAnyKeyPreparation() throws Exception {
        try(Fixture f=new Fixture();MockedConstruction<WalletCore> wallets=mockConstruction(WalletCore.class)) {
            initialize(f);start(f);f.authorized.set(false);f.workers.remove(0).run();
            when(f.activity.isDestroyed()).thenReturn(true);f.deliveries.remove(0).run();
            assertTrue(f.work.failure() instanceof SeedVault.SigningNotAllowedException);
            assertEquals(0,wallets.constructed().size());verify(f.vault,never()).phrase();assertNull(get(f.activity,"mWallet"));
        }
    }
    @Test public void generationChangedDuringPreparationCannotBecomeCurrent() throws Exception {
        try(Fixture f=new Fixture();MockedConstruction<WalletCore> wallets=mockConstruction(WalletCore.class,
                (w,c)->when(w.getReceiveAddress()).thenAnswer(i->{f.authorized.set(false);return new Address("RETURN TRUE");}));
            MockedConstruction<AddressBook> books=mockConstruction(AddressBook.class);
            MockedConstruction<TxnFactory> factories=mockConstruction(TxnFactory.class)) {
            initialize(f);start(f);f.workers.remove(0).run();
            when(f.activity.isDestroyed()).thenReturn(true);f.deliveries.remove(0).run();
            assertTrue(f.work.failure() instanceof SeedVault.SigningNotAllowedException);
            assertNull(get(f.activity,"mWallet"));assertNull(get(f.activity,"mNode"));
        }
    }
    @Test public void counterFailureEndsWorkWithoutConstructingAKey() throws Exception {
        try(Fixture f=new Fixture();MockedConstruction<WalletCore> wallets=mockConstruction(WalletCore.class)) {
            initialize(f);IllegalStateException failure=new IllegalStateException("synthetic stopped node");
            when(f.vault.keyUses()).thenThrow(failure);start(f);f.workers.remove(0).run();
            when(f.activity.isDestroyed()).thenReturn(true);f.deliveries.remove(0).run();
            assertSame(failure,f.work.failure());assertFalse(f.work.busy());assertEquals(0,wallets.constructed().size());
        }
    }
}
