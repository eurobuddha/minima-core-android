package com.eurobuddha.wallet;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.objects.Coin;
import org.minima.objects.CoinProof;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Internal Future Cash rescue bridge. Public discovery does not touch this authenticated signer. */
public final class FutureCashAccess {
    private FutureCashAccess() {}

    static int index(JSONArray keys, String address) {
        MiniData wanted=TxnFactory.parseAddress(address);
        for (int i=0;i<keys.length();i++) {
            JSONObject row=keys.optJSONObject(i);
            if (row==null) throw new IllegalStateException("Incomplete wallet public keys");
            String pk=row.optString("publickey", "");
            if (!pk.matches("0x[0-9a-fA-F]{64}")) throw new IllegalStateException("Invalid wallet public key");
            MiniData derived=new org.minima.objects.Address("RETURN SIGNEDBY(0x"
                    +pk.substring(2).toUpperCase(java.util.Locale.ROOT)+")").getAddressData();
            if (derived.isEqual(wanted)) {
                Object raw=row.opt("index");
                if (!(raw instanceof Number)) throw new IllegalStateException("Missing wallet key index");
                int index=new java.math.BigDecimal(raw.toString()).intValueExact();
                if (index<0) throw new IllegalStateException("Invalid wallet key index");
                return index;
            }
        }
        return -1;
    }

    public static boolean canSweep(Context context, String address) {
        WalletSession session=WalletSession.current();
        if (session==null || session.work().busy() || session.shouldLockOnForeground(System.currentTimeMillis())) return false;
        try {
            session.vault().assertSigningAllowed();
            return index(PublicAuditKeys.snapshot(context),address)>=0;
        } catch (RuntimeException unavailable) { return false; }
    }

    /** Worker-thread only. Reuses the session's operation gate and durable reserve-before-sign path. */
    public static <T> T sweep(Context context, String coinId, String address, String amount,
                                      String token, String destination, String exported, java.util.function.Function<String,T> publish) {
        if (android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            throw new IllegalStateException("Wallet rescue must run off the UI thread");
        if (!canSweep(context,address)) throw new IllegalStateException("Unlock minimaWallet to rescue its reused-key payouts.");
        WalletSession session=WalletSession.current();
        SeedVault vault=session.vault();
        java.util.function.BooleanSupplier authorized=vault.authorization();
        final int key=index(PublicAuditKeys.snapshot(context),address);
        final CountDownLatch done=new CountDownLatch(1);
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicReference<T> result=new AtomicReference<>();
        final AtomicReference<RuntimeException> failure=new AtomicReference<>();
        boolean accepted=session.work().start(() -> {
            try {
                if (cancelled.get() || !authorized.getAsBoolean()) throw new IllegalStateException("Wallet session changed");
                vault.assertSigningAllowed();
                WalletCore wallet=new WalletCore(vault.phrase(),vault.keyUses());
                // Verify the cached public descriptor against actual derivation before any signature.
                if (key<0 || !wallet.getAddress(key).getAddressData().isEqual(TxnFactory.parseAddress(address)))
                    throw new IllegalStateException("Wallet ownership changed");
                if (cancelled.get() || !authorized.getAsBoolean()) throw new IllegalStateException("Wallet session changed");
                String data=buildSweep(wallet,key,coinId,address,amount,token,destination,exported);
                vault.syncKeyUses(key);
                if (cancelled.get() || !authorized.getAsBoolean()) throw new IllegalStateException("Wallet session changed");
                // Keep the wallet operation gate until publishing ends. Background auto-lock is deferred
                // by WalletSession until this operation completes, just as for the wallet's own sends.
                result.set(publish.apply(data));
            } catch (RuntimeException invalid) { failure.set(invalid); }
            finally { done.countDown(); }
        });
        if (!accepted) throw new IllegalStateException("Wallet is busy");
        try {
            if (!done.await(180,TimeUnit.SECONDS)) { cancelled.set(true); throw new IllegalStateException("Wallet rescue timed out"); }
        } catch (InterruptedException interrupted) {
            cancelled.set(true); Thread.currentThread().interrupt(); throw new IllegalStateException("Wallet rescue interrupted",interrupted);
        }
        if (failure.get()!=null) throw failure.get();
        if (result.get()==null) throw new IllegalStateException("Wallet session changed");
        return result.get();
    }

    /** Exact coinexport path already used by minimaWallet; validates the requested whole coin before signing. */
    static String buildSweep(WalletCore wallet,int index,String coinId,String address,String amount,
                             String token,String destination,String exported) {
        if (exported==null || exported.length()>4*1024*1024 || ((exported.length()&1)!=0 || !exported.matches("0x[0-9a-fA-F]+")))
            throw new IllegalArgumentException("Invalid coin proof");
        CoinProof proof=CoinProof.convertMiniDataVersion(new MiniData(exported));
        Coin coin=proof==null?null:proof.getCoin();
        if (coin==null || coin.getSpent() || !coin.getCoinID().isEqual(new MiniData(coinId))
                || !coin.getAddress().isEqual(TxnFactory.parseAddress(address))
                || !coin.getTokenID().isEqual(new MiniData(token)))
            throw new IllegalArgumentException("Payout coin changed");
        MiniNumber human=coin.getTokenID().isEqual(TxnFactory.TOKEN_MINIMA) ? coin.getAmount()
                : coin.getToken().getScaledTokenAmount(coin.getAmount());
        if (!human.isEqual(new MiniNumber(amount)) || human.isLessEqual(MiniNumber.ZERO)
                || coin.getAddress().isEqual(TxnFactory.parseAddress(destination)))
            throw new IllegalArgumentException("Payout amount or destination changed");
        return new TxnFactory(wallet).buildSweep(TxnFactory.fromExportedCoin(coin,index),
                destination,"FCWalletRescue").getImportHex();
    }
}
