package com.eurobuddha.utxo;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Exercises real durable state and asynchronous node replies with public fixtures only. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class TransactionRecoveryTest {
    Context context; HistoryDb db; NodeApi node; TransactionRecovery recovery;
    List<String> commands; List<NodeApi.Cb> callbacks; int changes;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_utxo_history.db");db=new HistoryDb(context);
        commands=new ArrayList<>();callbacks=new ArrayList<>();node=mock(NodeApi.class);
        doAnswer(i->{commands.add(i.getArgument(0));callbacks.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        recovery=new TransactionRecovery(node,db,()->changes++);
    }
    @After public void close(){recovery.close();db.close();context.deleteDatabase("pandamonium_utxo_history.db");}
    void prepare(String phase) {
        HistoryRow row=new HistoryRow();row.internalid=row.txnid="attempt";row.status=HistoryDb.STATUS_UNKNOWN;row.tokenid="0x00";assertTrue(db.insertPosting(row));
        assertTrue(db.prepareAttempt("attempt","handle",intent("0x00")));
        if(HistoryDb.PREPARED.equals(phase))return;
        assertTrue(db.markAttempt("attempt",HistoryDb.POSTING,null));
        if(!HistoryDb.POSTING.equals(phase))assertTrue(db.markAttempt("attempt",phase,HistoryDb.POSTED.equals(phase)?receipt(intent("0x00")):null));
    }
    static JSONObject inclusion(String depth) {return object("status",true,"response",object("found",true,"block","100","tip",new java.math.BigInteger(depth).add(java.math.BigInteger.valueOf(100)).toString(),"confirmations",depth,"blockid",id('d')));}
    static JSONObject raw()throws Exception{return receipt(intent("0x00")).getJSONObject("response").getJSONObject("txpow").put("istransaction",true);}
    static JSONObject page(JSONObject txpow){return object("status",true,"response",object("txpows",new JSONArray().put(txpow)));}
    void answer(JSONObject value){callbacks.remove(0).onResult(value);}
    void candidate()throws Exception {recovery.poll(103);assertEquals("history relevant:true max:8 offset:0",commands.get(0));answer(page(raw()));assertEquals("txpow onchain:"+id('f'),commands.get(1));}
    void reserved(){assertTrue(db.hasUnresolvedAttempt("0x00"));assertNotEquals(HistoryDb.STATUS_CONFIRMED,db.list(5).get(0).status);}

    @Test public void restartFindsExactTransactionThenCommitsInclusionBeforeReleasingInputs()throws Exception {
        prepare(HistoryDb.UNKNOWN);db.close();db=new HistoryDb(context);recovery.close();recovery=new TransactionRecovery(node,db,()->changes++);
        candidate();reserved();answer(inclusion("3"));assertEquals(1,changes);assertFalse(db.hasUnresolvedAttempt("0x00"));
        HistoryDb.Attempt saved=db.transactionAttempts().get(0);assertEquals(HistoryDb.CONFIRMED,saved.phase);assertEquals("3",saved.receipt.getJSONObject("inclusion").getString("confirmations"));assertEquals(id('f'),db.list(5).get(0).txnid);
        assertFalse(db.update("attempt",HistoryDb.STATUS_UNKNOWN,null,"late callback"));assertEquals(HistoryDb.STATUS_CONFIRMED,db.list(5).get(0).status);
        assertFalse(db.markAttempt("attempt",HistoryDb.NOT_SUBMITTED,null));assertFalse(db.markAttempt("attempt",HistoryDb.POSTED,receipt(intent("0x00"))));
        db.close();db=new HistoryDb(context);assertEquals(HistoryDb.CONFIRMED,db.transactionAttempts().get(0).phase);assertFalse(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void acceptedPremineIdDoesNotReplaceTheMatchingMinedId()throws Exception {
        prepare(HistoryDb.POSTED);recovery.poll(103);JSONObject mined=raw().put("txpowid",id('e'));answer(page(mined));assertEquals("txpow onchain:"+id('e'),commands.get(1));answer(inclusion("4"));assertEquals(id('e'),db.list(5).get(0).txnid);
    }
    @Test public void absentShallowMalformedAndInconsistentInclusionNeverReleaseAnAttempt()throws Exception {
        prepare(HistoryDb.UNKNOWN);
        List<JSONObject> invalid=new ArrayList<>(Arrays.asList(null,object(),object("status",false),object("status",true,"response",object("found",false)),inclusion("0"),inclusion("2")));
        for(String key:new String[]{"block","tip","confirmations","blockid","found"}) {JSONObject j=inclusion("3");j.getJSONObject("response").remove(key);invalid.add(j);}
        for(Object v:new Object[]{3,"3.0","-3","3;send",true}){JSONObject j=inclusion("3");j.getJSONObject("response").put("confirmations",v);invalid.add(j);}
        JSONObject inconsistent=inclusion("3");inconsistent.getJSONObject("response").put("tip","104");invalid.add(inconsistent);
        JSONObject zero=inclusion("3");zero.getJSONObject("response").put("blockid","0x00");invalid.add(zero);
        for(JSONObject proof:invalid){assertThrows(RuntimeException.class,()->db.confirmAttempt("attempt",receipt(intent("0x00")),proof));reserved();}
    }
    @Test public void partialWrongAmountAndNonTransactionHistoryNeverTriggerConfirmationQueries()throws Exception {
        prepare(HistoryDb.UNKNOWN);JSONArray entries=new JSONArray();JSONObject missing=raw();missing.getJSONObject("body").getJSONObject("txn").getJSONArray("outputs").remove(1);entries.put(missing);
        JSONObject different=raw();different.getJSONObject("body").getJSONObject("txn").getJSONArray("outputs").getJSONObject(0).put("amount","4");entries.put(different);entries.put(raw().put("istransaction",false));
        recovery.poll(103);answer(object("status",true,"response",object("txpows",entries)));assertEquals(1,commands.size());reserved();assertEquals(0,changes);
    }
    @Test public void atomicConfirmationRollsBackIfEitherRecordCannotBeSaved()throws Exception {
        prepare(HistoryDb.UNKNOWN);
        for(String table:new String[]{"transaction_attempts","history"}){
            db.getWritableDatabase().execSQL("CREATE TRIGGER deny_confirm BEFORE UPDATE ON "+table+" BEGIN SELECT RAISE(ABORT,'fixture'); END");
            assertThrows(RuntimeException.class,()->db.confirmAttempt("attempt",receipt(intent("0x00")),inclusion("3")));reserved();assertEquals(HistoryDb.UNKNOWN,db.transactionAttempts().get(0).phase);
            db.getWritableDatabase().execSQL("DROP TRIGGER deny_confirm");
        }
        db.delete("attempt");assertEquals(1,db.list(5).size());db.getWritableDatabase().delete("history","internalid=?",new String[]{"attempt"});assertFalse(db.confirmAttempt("attempt",receipt(intent("0x00")),inclusion("3")));assertEquals(HistoryDb.UNKNOWN,db.transactionAttempts().get(0).phase);assertTrue(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void wrongReceiptCannotConfirmEvenWithAValidChainReply()throws Exception {
        prepare(HistoryDb.UNKNOWN);JSONObject wrong=receipt(intent("0x00"));txn(wrong).getJSONArray("inputs").getJSONObject(0).put("coinid",id('9'));
        assertThrows(RuntimeException.class,()->db.confirmAttempt("attempt",wrong,inclusion("3")));reserved();assertFalse(db.confirmAttempt("missing",receipt(intent("0x00")),inclusion("3")));
    }
    @Test public void timeoutsRetainEvidenceAndAllowALaterBlockToRetry()throws Exception {
        prepare(HistoryDb.UNKNOWN);candidate();callbacks.remove(0).onUnknown("timeout");reserved();assertEquals(0,changes);recovery.poll(103);assertEquals(2,commands.size());
        recovery.poll(104);answer(page(raw()));answer(inclusion("3"));assertEquals(1,changes);assertFalse(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void scansAreBoundedAndContinueAtTheNextOffsetOnALaterBlock() {
        prepare(HistoryDb.UNKNOWN);recovery.poll(103);recovery.poll(104);assertEquals(1,commands.size());
        JSONArray irrelevant=new JSONArray();for(int i=0;i<8;i++)irrelevant.put(object("istransaction",false));JSONObject page=object("status",true,"response",object("txpows",irrelevant));
        for(int i=0;i<4;i++)answer(page);assertEquals(4,commands.size());assertTrue(callbacks.isEmpty());
        recovery.poll(104);assertEquals("history relevant:true max:8 offset:32",commands.get(4));answer(object("status",true,"response",object("txpows",new JSONArray())));
        recovery.poll(105);assertEquals("history relevant:true max:8 offset:0",commands.get(5));reserved();
    }
    @Test public void oversizedQueriesShrinkAndStoppedActivityIgnoresLateCallbacks()throws Exception {
        prepare(HistoryDb.UNKNOWN);recovery.poll(103);
        for(int size:new int[]{8,4,2,1}){assertEquals("history relevant:true max:"+size+" offset:0",commands.get(commands.size()-1));callbacks.remove(0).onUnknown(NodeApi.ERR_TOO_LONG);}
        assertEquals("history relevant:true max:1 offset:1",commands.get(4));reserved();answer(page(raw()));recovery.close();answer(inclusion("3"));reserved();assertEquals(0,changes);recovery.poll(105);assertTrue(callbacks.isEmpty());
    }
    @Test public void emptyPreparedAndRejectedJournalsDoNotStartRecoveryQueries() {
        recovery.poll(100);assertTrue(commands.isEmpty());prepare(HistoryDb.PREPARED);recovery.poll(101);assertTrue(commands.isEmpty());
        assertTrue(db.markAttempt("attempt",HistoryDb.NOT_SUBMITTED,null));recovery.poll(102);assertTrue(commands.isEmpty());
    }
    @Test public void anOversizedSingleEntryDoesNotStarveOlderMatchingTransactions()throws Exception {
        prepare(HistoryDb.UNKNOWN);recovery.poll(103);
        for(int i=0;i<4;i++)callbacks.remove(0).onUnknown(NodeApi.ERR_TOO_LONG);
        assertEquals(5,commands.size());assertEquals("history relevant:true max:1 offset:1",commands.get(4));
        reserved();answer(page(raw()));answer(inclusion("3"));assertEquals(1,changes);assertFalse(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void damagedStoredConfirmationCannotUnlockFunds()throws Exception {
        prepare(HistoryDb.UNKNOWN);assertTrue(db.confirmAttempt("attempt",receipt(intent("0x00")),inclusion("3")));
        JSONObject bad=db.transactionAttempts().get(0).receipt;bad.getJSONObject("inclusion").put("confirmations","0");db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET receipt=?",new Object[]{bad.toString()});
        assertThrows(RuntimeException.class,()->db.hasUnresolvedAttempt("0x00"));
    }
}
