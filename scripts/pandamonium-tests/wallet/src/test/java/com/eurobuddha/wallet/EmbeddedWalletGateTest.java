package com.eurobuddha.wallet;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.minima.database.MinimaDB;
import org.minima.database.wallet.Wallet;
import org.minima.system.Main;
import org.minima.system.params.GeneralParams;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Uses mocked node lifecycle only; never constructs or starts a real Main. */
public class EmbeddedWalletGateTest {
    static final class Fixture implements AutoCloseable {
        final Main main=mock(Main.class);
        final MinimaDB db=mock(MinimaDB.class);
        final Wallet wallet=mock(Wallet.class);
        final AtomicBoolean ready=new AtomicBoolean(true);
        final AtomicReference<MinimaDB> active=new AtomicReference<>(db);
        final MockedStatic<Main> mains=mockStatic(Main.class);
        final MockedStatic<MinimaDB> databases=mockStatic(MinimaDB.class);
        final boolean ignoreBefore=GeneralParams.DB_IGNORE_LOCKS;
        Fixture() throws Exception {
            GeneralParams.DB_IGNORE_LOCKS=false;
            mains.when(Main::getInstance).thenReturn(main);
            databases.when(MinimaDB::getDB).thenAnswer(a -> active.get());
            when(main.isStartUpComplete()).thenReturn(true);
            when(main.getAllKeysCreated()).thenReturn(true);
            when(db.getWallet()).thenReturn(wallet);
            when(wallet.isOpen()).thenReturn(true);
            EmbeddedWallet.install(ready::get);
        }
        public void close() {
            EmbeddedWallet.install(() -> false);
            GeneralParams.DB_IGNORE_LOCKS=ignoreBefore;
            databases.close(); mains.close();
        }
    }
    @Test public void holdsNodeWalletMonitorAndReleasesDatabaseReadLock() throws Exception {
        try(Fixture f=new Fixture()) {
            assertEquals(Integer.valueOf(7),EmbeddedWallet.NODE.withWallet(wallet -> {
                assertSame(f.wallet,wallet);
                assertTrue(Thread.holdsLock(wallet));
                verify(f.db).readLock(true);
                return 7;
            }));
            verify(f.db).readLock(false);
        }
    }
    @Test public void hostLockAndIncompleteKeysRefuseBeforeWork() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready.set(false);
            assertThrows(IllegalStateException.class, () -> EmbeddedWallet.NODE.withWallet(w -> { fail(); return null; }));
            verify(f.db,never()).readLock(true);
            f.ready.set(true);
            when(f.main.getAllKeysCreated()).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> EmbeddedWallet.NODE.withWallet(w -> { fail(); return null; }));
            verify(f.db,never()).readLock(true);
        }
    }
    @Test public void shutdownRestoreClosedDatabaseAndDisabledLocksRefuse() throws Exception {
        try(Fixture f=new Fixture()) {
            when(f.main.isShuttongDownOrRestoring()).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> EmbeddedWallet.NODE.withWallet(w -> { fail(); return null; }));
            when(f.main.isShuttongDownOrRestoring()).thenReturn(false);
            when(f.wallet.isOpen()).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> EmbeddedWallet.NODE.withWallet(w -> { fail(); return null; }));
            verify(f.db).readLock(false);
            when(f.wallet.isOpen()).thenReturn(true);
            GeneralParams.DB_IGNORE_LOCKS=true;
            assertThrows(IllegalStateException.class, () -> EmbeddedWallet.NODE.withWallet(w -> { fail(); return null; }));
        }
    }
    @Test public void changedNodeOrFailureDuringWorkAlwaysReleasesReadLock() throws Exception {
        try(Fixture f=new Fixture()) {
            assertThrows(IllegalStateException.class, () -> EmbeddedWallet.NODE.withWallet(w -> {
                f.active.set(mock(MinimaDB.class)); return 1;
            }));
            verify(f.db).readLock(false);
            f.active.set(f.db);
            assertThrows(IllegalArgumentException.class, () -> EmbeddedWallet.NODE.withWallet(w -> {
                throw new IllegalArgumentException("fixture failure");
            }));
            verify(f.db,times(2)).readLock(false);
        }
    }
}
