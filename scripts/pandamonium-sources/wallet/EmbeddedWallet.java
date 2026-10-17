package com.eurobuddha.wallet;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.minima.database.MinimaDB;
import org.minima.database.wallet.Wallet;
import org.minima.system.Main;
import org.minima.system.params.GeneralParams;

/** Host readiness plus the node's own database and signing monitors. No exported IPC. */
public final class EmbeddedWallet {
    private static volatile BooleanSupplier hostReady = () -> false;
    private EmbeddedWallet() {}
    public static void install(BooleanSupplier ready) {
        hostReady = java.util.Objects.requireNonNull(ready);
    }
    /** Internal setup only: never export or log the node seed. */
    static String phrase(Wallet wallet) {
        org.minima.database.wallet.SeedRow row = wallet.getBaseSeed();
        if (row == null || !wallet.isBaseSeedAvailable() || row.getPhrase() == null
                || row.getPhrase().trim().isEmpty())
            throw new IllegalStateException("The embedded node seed is unavailable");
        String phrase = row.getPhrase();
        if (!org.minima.utils.BIP39.convertStringToSeed(phrase).to0xString().equalsIgnoreCase(row.getSeed()))
            throw new IllegalStateException("The embedded node seed could not be verified");
        return phrase;
    }

    public interface Source {
        <T> T withWallet(Function<Wallet,T> work);
    }
    public static final Source NODE = new Source() {
        @Override public <T> T withWallet(Function<Wallet,T> work) {
            Main node = Main.getInstance();
            MinimaDB db = MinimaDB.getDB();
            check(node, db);
            db.readLock(true);
            try {
                check(node, db);
                Wallet wallet = db.getWallet();
                if (wallet == null) throw new IllegalStateException("Node wallet unavailable");
                synchronized (wallet) {
                    check(node, db);
                    if (wallet != db.getWallet()) throw new IllegalStateException("Node wallet changed");
                    try {
                        if (!wallet.isOpen()) throw new IllegalStateException("Node wallet database is closed");
                    } catch (java.sql.SQLException unavailable) {
                        throw new IllegalStateException("Node wallet database is unavailable", unavailable);
                    }
                    T result = work.apply(wallet);
                    check(node, db);
                    return result;
                }
            } finally { db.readLock(false); }
        }
    };
    private static void check(Main node, MinimaDB db) {
        if (!hostReady.getAsBoolean() || node == null || node != Main.getInstance()
                || db == null || db != MinimaDB.getDB() || !node.isStartUpComplete()
                || node.isStartupError() || node.isShuttongDownOrRestoring()
                || !node.getAllKeysCreated() || GeneralParams.DB_IGNORE_LOCKS)
            throw new IllegalStateException("Start the embedded node and wait for its keys before using this wallet");
    }
}
