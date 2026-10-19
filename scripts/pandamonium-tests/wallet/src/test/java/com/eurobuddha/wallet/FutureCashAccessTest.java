package com.eurobuddha.wallet;

import org.junit.Test;
import org.json.*;
import org.minima.objects.*;
import org.minima.objects.base.*;
import org.minima.objects.mmr.MMRProof;
import org.minima.database.userprefs.txndb.TxnRow;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Synthetic key/coin fixtures. Builds signatures locally; never sends anything or changes real counters. */
public class FutureCashAccessTest {
    static final String ID="0x"+"ab".repeat(32), DEST="0x"+"cd".repeat(32);
    static class Fixture {
        final TxnFactoryTest.InMemoryKeyUses uses=new TxnFactoryTest.InMemoryKeyUses();
        final WalletCore wallet=new WalletCore("future cash wallet rescue test fixture",uses);
        final String address=wallet.getAddress(1).getAddressData().to0xString();
        final Coin coin=new Coin(new MiniData(ID),new MiniData(address),new MiniNumber("5000"),new MiniData("0x00"),false);
        String proof(){return MiniData.getMiniDataVersion(new CoinProof(coin,new MMRProof())).to0xString();}
        String build(){return FutureCashAccess.buildSweep(wallet,1,ID,address,"5000","0x00",DEST,proof());}
    }
    @Test public void rescueSignsExactWholePayoutWithOneReservedClassicLeaf() {
        Fixture f=new Fixture();String hex=f.build();TxnRow row=TxnRow.convertMiniDataVersion(new MiniData(hex));
        assertEquals(1,f.uses.currentUses(1));assertEquals(0,f.uses.currentUses(0));
        assertEquals(1,row.getTransaction().getAllInputs().size());assertEquals(1,row.getTransaction().getAllOutputs().size());
        Coin out=row.getTransaction().getAllOutputs().get(0);
        assertFalse(out.storeState());
        assertTrue(out.getAddress().isEqual(new MiniData(DEST)));assertTrue(out.getAmount().isEqual(new MiniNumber("5000")));
        assertEquals(1,row.getWitness().getAllSignatures().size());assertTrue(row.getWitness().getAllCoinProofs().isEmpty());
        assertTrue(row.getWitness().getAllScripts().isEmpty());
        assertTrue(f.wallet.deriveTreeKey(1).verify(row.getTransaction().getTransactionID(),row.getWitness().getAllSignatures().get(0)));
        f.build();assertEquals(2,f.uses.currentUses(1));
    }
    @Test public void changedCoinIdentityAmountTokenOrDestinationIsRejectedBeforeSigning() {
        Fixture f=new Fixture();
        for(String[] fields:new String[][]{{DEST,f.address,"5000","0x00",DEST},{ID,DEST,"5000","0x00",DEST},
                {ID,f.address,"4999","0x00",DEST},{ID,f.address,"5000",DEST,DEST},{ID,f.address,"5000","0x00",f.address}})
            assertThrows(RuntimeException.class,()->FutureCashAccess.buildSweep(f.wallet,1,fields[0],fields[1],fields[2],fields[3],fields[4],f.proof()));
        assertEquals(0,f.uses.currentUses(1));
    }
    @Test public void wrongKeyIndexAndSpentPayoutCannotConsumeASignature() {
        Fixture f=new Fixture();
        assertThrows(RuntimeException.class,()->FutureCashAccess.buildSweep(f.wallet,0,ID,f.address,"5000","0x00",DEST,f.proof()));
        f.coin.setSpent(true);assertThrows(RuntimeException.class,f::build);
        assertEquals(0,f.uses.currentUses(0));assertEquals(0,f.uses.currentUses(1));
    }
    @Test public void malformedProofNeverSigns() {
        Fixture f=new Fixture();for(String bad:new String[]{"","0x0","0xzz","0x12;send amount:1","0x"+"aa".repeat(2*1024*1024)})
            assertThrows(RuntimeException.class,()->FutureCashAccess.buildSweep(f.wallet,1,ID,f.address,"5000","0x00",DEST,bad));
        assertEquals(0,f.uses.currentUses(1));
    }
    @Test public void publicAddressLookupHandlesMxAndHigherIndicesWithoutDerivingAnySeed() throws Exception {
        String pk="0x"+"11".repeat(32);Address address=new Address("RETURN SIGNEDBY("+pk+")");
        JSONArray keys=new JSONArray().put(new JSONObject().put("index",70).put("publickey",pk));
        assertEquals(70,FutureCashAccess.index(keys,address.getMinimaAddress()));
        assertEquals(70,FutureCashAccess.index(keys,address.getAddressData().to0xString()));
        assertEquals(-1,FutureCashAccess.index(keys,DEST));
        keys.getJSONObject(0).put("index",1.5);assertThrows(RuntimeException.class,()->FutureCashAccess.index(keys,address.getMinimaAddress()));
    }
    @Test public void noSessionLockedBusyOrExpiredWalletCannotAuthorizeRescue() {
        android.content.Context context=mock(android.content.Context.class);
        try(MockedStatic<WalletSession> current=mockStatic(WalletSession.class)) {
            assertFalse(FutureCashAccess.canSweep(context,DEST));
            WalletSession session=mock(WalletSession.class);WalletWork work=mock(WalletWork.class);SeedVault vault=mock(SeedVault.class);
            current.when(WalletSession::current).thenReturn(session);when(session.work()).thenReturn(work);when(session.vault()).thenReturn(vault);
            when(work.busy()).thenReturn(true);assertFalse(FutureCashAccess.canSweep(context,DEST));verifyNoInteractions(vault);
            when(work.busy()).thenReturn(false);when(session.shouldLockOnForeground(anyLong())).thenReturn(true);
            assertFalse(FutureCashAccess.canSweep(context,DEST));verifyNoInteractions(vault);
            when(session.shouldLockOnForeground(anyLong())).thenReturn(false);doThrow(new IllegalStateException("locked")).when(vault).assertSigningAllowed();
            assertFalse(FutureCashAccess.canSweep(context,DEST));verifyNoInteractions(context);
        }
    }
    @Test public void sessionGateStaysHeldThroughPublicationAndCompletionMayThenLock() throws Exception {
        bridge(false);
    }
    @Test public void revokedSessionNeverReachesPublication() throws Exception {
        bridge(true);
    }
    private void bridge(boolean revoke) throws Exception {
        Fixture f=new Fixture();android.content.Context context=mock(android.content.Context.class);
        JSONArray keys=new JSONArray().put(new JSONObject().put("index",1).put("publickey",f.wallet.getPublicKey(1).to0xString()));
        WalletSession session=mock(WalletSession.class);SeedVault vault=mock(SeedVault.class);
        java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger published=new java.util.concurrent.atomic.AtomicInteger();
        WalletWork work=new WalletWork(operation->{if(revoke)current.set(false);operation.run();},finish->{current.set(false);finish.run();});
        when(session.work()).thenReturn(work);when(session.vault()).thenReturn(vault);
        when(vault.authorization()).thenReturn(current::get);when(vault.phrase()).thenReturn("future cash wallet rescue test fixture");when(vault.keyUses()).thenReturn(f.uses);
        try(MockedStatic<WalletSession> sessions=mockStatic(WalletSession.class);
                MockedStatic<PublicAuditKeys> publicKeys=mockStatic(PublicAuditKeys.class);
                MockedStatic<android.os.Looper> loopers=mockStatic(android.os.Looper.class)) {
            sessions.when(WalletSession::current).thenReturn(session);
            publicKeys.when(()->PublicAuditKeys.snapshot(context)).thenReturn(keys);
            loopers.when(android.os.Looper::getMainLooper).thenReturn(mock(android.os.Looper.class));
            java.util.function.Supplier<String> call=()->FutureCashAccess.sweep(context,ID,f.address,"5000","0x00",DEST,f.proof(),data->{
                assertTrue(work.busy());assertTrue(current.get());assertNotNull(TxnRow.convertMiniDataVersion(new MiniData(data)));
                published.incrementAndGet();return "posted fixture";
            });
            if(revoke) {assertThrows(IllegalStateException.class,call::get);assertEquals(0,published.get());assertEquals(0,f.uses.currentUses(1));}
            else {assertEquals("posted fixture",call.get());assertEquals(1,published.get());assertEquals(1,f.uses.currentUses(1));verify(vault).syncKeyUses(1);}
            assertFalse(work.busy());assertFalse(current.get());
        }
    }

    @Test public void customTokenRescueUsesExactExportedDescriptorAndHumanAmount() {
        Fixture f=new Fixture();
        Token token=new Token(new MiniData("0x"+"ef".repeat(32)),new MiniNumber("2"),new MiniNumber("1000"),
                new MiniString("{\"name\":\"Future token\",\"ticker\":\"FT\"}"),new MiniString("RETURN TRUE"),MiniNumber.ONE);
        Coin coin=new Coin(new MiniData(ID),new MiniData(f.address),new MiniNumber("1.25"),token.getTokenID(),false);coin.setToken(token);
        String exported=MiniData.getMiniDataVersion(new CoinProof(coin,new MMRProof())).to0xString();
        String amount=token.getScaledTokenAmount(coin.getAmount()).toString();
        String data=FutureCashAccess.buildSweep(f.wallet,1,ID,f.address,amount,token.getTokenID().to0xString(),DEST,exported);
        Coin out=TxnRow.convertMiniDataVersion(new MiniData(data)).getTransaction().getAllOutputs().get(0);
        assertTrue(out.getAmount().isEqual(coin.getAmount()));assertTrue(out.getTokenID().isEqual(token.getTokenID()));assertFalse(out.storeState());
        assertEquals(token.getName().toString(),out.getToken().getName().toString());assertEquals(1,f.uses.currentUses(1));
    }

}
