package com.eurobuddha.wallet;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.mmr.MMREntryNumber;
import static org.junit.Assert.*;

/** Public fixture seed and in-memory persistence; never uses a real wallet or node. */
public class SweepPendingTest {
    static class Store implements SeedVault.BlobStore {
        String value;boolean fail;
        public boolean contains(){return value!=null;}public String read(){return value;}
        public void write(String next){if(fail)throw new IllegalStateException("disk fixture failed");value=next;}
    }
    static TxnFactory.BuiltTxn built(){
        WalletCore wallet=new WalletCore("public sweep pending fixture",new TxnFactoryTest.InMemoryKeyUses());
        TxnFactory.InputCoin input=new TxnFactory.InputCoin(new MiniData("0x"+"12".repeat(32)),wallet.getAddress(0).getAddressData(),MiniNumber.ONE,
            TxnFactory.TOKEN_MINIMA,new MMREntryNumber(1),new MiniNumber(100),true,new ArrayList<>(),null,0);
        return new TxnFactory(wallet).buildWholeTransfer(input,wallet.getAddress(1).getAddressData().to0xString(),"sweep_fixture");
    }
    static String proof="0x"+"34".repeat(32);
    static JSONObject receipt(TxnFactory.BuiltTxn built)throws Exception{
        return new JSONObject().put("status",true).put("response",new JSONObject().put("txpowid",proof)
            .put("body",new JSONObject().put("txn",new JSONObject().put("transactionid",built.getTransactionID().to0xString()))));
    }
    static JSONObject inclusion()throws Exception{return new JSONObject().put("status",true).put("response",new JSONObject()
        .put("found",true).put("blockid","0x"+"56".repeat(32)).put("block","100").put("tip","103").put("confirmations","3"));}
    @Test public void restartPreservesInputsAndByteIdenticalSignedTransaction(){
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();pending.reserve(built);
        SweepPending restarted=new SweepPending(store);
        assertEquals(Collections.singleton("0x"+"12".repeat(32)),restarted.coins());
        assertEquals("",restarted.transactions().get(built.getTransactionID().to0xString()));
        assertEquals(built.getImportHex(),restarted.savedTransactions().get(0).getImportHex());
        assertThrows(IllegalStateException.class,()->restarted.reserve(built));
    }
    @Test public void failedCommitCannotReportReservation(){Store store=new Store();store.fail=true;assertThrows(IllegalStateException.class,()->new SweepPending(store).reserve(built()));assertNull(store.value);}
    @Test public void malformedStorageFailsClosed(){Store store=new Store();for(String raw:new String[]{"", "null", "{} garbage","[]","{\"bad\":{}}"}){store.value=raw;assertThrows(IllegalStateException.class,()->new SweepPending(store).coins());}}
    @Test public void receiptMustMatchExactSignedTransaction()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();pending.reserve(built);
        JSONObject wrong=receipt(built);wrong.getJSONObject("response").getJSONObject("body").getJSONObject("txn").put("transactionid",proof);
        assertThrows(IllegalStateException.class,()->pending.receipt(built.getTransactionID().to0xString(),wrong));
        assertEquals("",pending.transactions().get(built.getTransactionID().to0xString()));
        pending.receipt(built.getTransactionID().to0xString(),receipt(built));assertEquals(proof,pending.transactions().get(built.getTransactionID().to0xString()));
    }
    @Test public void onlyStrictOnchainEvidenceReleasesReservation()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();String id=built.getTransactionID().to0xString();pending.reserve(built);pending.receipt(id,receipt(built));
        JSONObject weak=inclusion();weak.getJSONObject("response").put("confirmations","2").put("tip","102");
        assertThrows(IllegalStateException.class,()->pending.confirmed(id,proof,weak));assertFalse(pending.coins().isEmpty());
        pending.confirmed(id,proof,inclusion());assertTrue(new SweepPending(store).coins().isEmpty());
    }
    @Test public void malformedInclusionNeverConfirms()throws Exception{
        for(String field:new String[]{"block","tip","confirmations","blockid","found"}){
            JSONObject reply=inclusion();reply.getJSONObject("response").remove(field);assertFalse(SweepPending.included(reply));
        }
        JSONObject stringStatus=inclusion().put("status","true");assertFalse(SweepPending.included(stringStatus));
        JSONObject inconsistent=inclusion();inconsistent.getJSONObject("response").put("tip","999");assertFalse(SweepPending.included(inconsistent));
        assertFalse(SweepPending.included(new JSONObject().put("status",true).put("response",new org.json.JSONArray())));
    }
    @Test public void failedConfirmationCommitRetainsPending()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();String id=built.getTransactionID().to0xString();pending.reserve(built);pending.receipt(id,receipt(built));
        store.fail=true;assertThrows(IllegalStateException.class,()->pending.confirmed(id,proof,inclusion()));assertFalse(new SweepPending(store).coins().isEmpty());
    }
    @Test public void savedPayloadTamperingCannotBeReplayed()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();pending.reserve(built);
        JSONObject root=new JSONObject(store.value);root.getJSONObject(built.getTransactionID().to0xString()).put("signed",built.getImportHex()+"00");store.value=root.toString();
        assertThrows(IllegalStateException.class,pending::savedTransactions);
    }
    static JSONObject sizeRefusal(TxnFactory.BuiltTxn built)throws Exception {
        return new JSONObject().put("command","txnpost").put("params",new JSONObject().put("id",built.getID()))
            .put("status",false).put("pending",false).put("error","TxPoW size too large.. 71234/65536");
    }
    @Test public void exactPreMiningSizeRejectionReleasesOnlyItsSavedAttempt()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();pending.reserve(built);
        pending.rejectedSize(built,sizeRefusal(built));assertTrue(new SweepPending(store).coins().isEmpty());
        assertTrue(new SweepPending(store).savedTransactions().isEmpty());
    }
    @Test public void failedSizeRejectionCommitKeepsOriginalBytesReserved()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();pending.reserve(built);store.fail=true;
        assertThrows(IllegalStateException.class,()->pending.rejectedSize(built,sizeRefusal(built)));
        assertEquals(built.getImportHex(),new SweepPending(store).savedTransactions().get(0).getImportHex());
    }
    @Test public void sizeRejectionCannotReleaseAcceptedOrMismatchedAttempt()throws Exception{
        Store store=new Store();SweepPending pending=new SweepPending(store);TxnFactory.BuiltTxn built=built();pending.reserve(built);
        JSONObject wrong=sizeRefusal(built);wrong.getJSONObject("params").put("id","another_attempt");
        assertThrows(IllegalStateException.class,()->pending.rejectedSize(built,wrong));
        pending.receipt(built.getTransactionID().to0xString(),receipt(built));
        assertThrows(IllegalStateException.class,()->pending.rejectedSize(built,sizeRefusal(built)));
        assertFalse(pending.coins().isEmpty());
    }
}
