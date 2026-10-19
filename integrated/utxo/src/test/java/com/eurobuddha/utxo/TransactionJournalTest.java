package com.eurobuddha.utxo;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
/** Real SQLite in Robolectric, using only public fixtures; no node or device. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class TransactionJournalTest {
    Context context;HistoryDb db;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_utxo_history.db");db=new HistoryDb(context);}
    @After public void close(){db.close();context.deleteDatabase("pandamonium_utxo_history.db");}
    void row(String id){HistoryRow row=new HistoryRow();row.internalid=row.txnid=id;row.status=HistoryDb.STATUS_POSTING;row.tokenid="0x00";assertTrue(db.insertPosting(row));}
    void prepare(String id){row(id);assertTrue(db.prepareAttempt(id,"handle_"+id,intent("0x00")));}
    @Test public void exactIntentAndUnknownReservationSurviveAClosedDatabase() {
        prepare("first");assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));assertTrue(db.markAttempt("first",HistoryDb.UNKNOWN,null));db.close();db=new HistoryDb(context);
        HistoryDb.Attempt stored=db.transactionAttempts().get(0);assertEquals(HistoryDb.UNKNOWN,stored.phase);assertEquals("0",stored.intent.optString("burn"));assertEquals("10",stored.intent.optJSONArray("inputs").optJSONObject(0).optString("amount"));
        row("retry");assertFalse(db.prepareAttempt("retry","handle_retry",intent("0x00")));assertEquals(1,db.transactionAttempts().size());assertTrue(db.hasUnresolvedAttempt("0x00"));assertFalse(db.hasUnresolvedAttempt(TOKEN));
    }
    @Test public void helpersCannotReserveTheSameInputOrReplaceAnExistingIdentity()throws Exception {
        prepare("first");row("second");try(HistoryDb other=new HistoryDb(context)){assertFalse(other.prepareAttempt("second","handle_other",intent("0x00")));assertFalse(other.prepareAttempt("first","different_handle",intent("0x00")));}
        assertEquals("handle_first",db.transactionAttempts().get(0).handle);
        JSONObject separate=intent("0x00");separate.getJSONArray("inputs").getJSONObject(0).put("coinid",id('2'));row("third");assertFalse(db.prepareAttempt("third","handle_first",separate));assertTrue(db.prepareAttempt("third","handle_third",separate));
    }
    @Test public void provenPrePostFailureReleasesInputsButNeverErasesEvidence() {
        prepare("first");assertTrue(db.markAttempt("first",HistoryDb.NOT_SUBMITTED,null));assertFalse(db.hasUnresolvedAttempt("0x00"));row("retry");assertTrue(db.prepareAttempt("retry","handle_retry",intent("0x00")));assertEquals(2,db.transactionAttempts().size());
        assertFalse(db.markAttempt("first",HistoryDb.POSTING,null));assertFalse(db.markAttempt("missing",HistoryDb.UNKNOWN,null));
    }
    @Test public void unknownCannotBeReleasedByALateFailureAndPostedReceiptsKeepTheirInputsReserved()throws Exception {
        prepare("first");assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));assertTrue(db.markAttempt("first",HistoryDb.UNKNOWN,null));assertFalse(db.markAttempt("first",HistoryDb.NOT_SUBMITTED,null));
        db.close();context.deleteDatabase("pandamonium_utxo_history.db");db=new HistoryDb(context);prepare("posted");assertTrue(db.markAttempt("posted",HistoryDb.POSTING,null));
        JSONObject reply=receipt(intent("0x00"));reply.getJSONObject("response").getJSONObject("txpow").put("witness","PUBLIC-SIGNATURE-SENTINEL");assertTrue(db.markAttempt("posted",HistoryDb.POSTED,reply));
        HistoryDb.Attempt saved=db.transactionAttempts().get(0);assertFalse(saved.receipt.toString().contains("SIGNATURE"));assertEquals(id('f'),saved.receipt.optString("txpowid"));assertEquals(2,saved.receipt.optJSONArray("outputs").length());
        row("retry");assertFalse(db.prepareAttempt("retry","new_handle",intent("0x00")));
    }
    @Test public void insertionAndStatusWriteFailuresRollBackWithoutLosingTheReservation() {
        row("first");SQLiteDatabase sql=db.getWritableDatabase();sql.execSQL("CREATE TRIGGER deny_insert BEFORE INSERT ON transaction_attempts BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(RuntimeException.class,()->db.prepareAttempt("first","handle_first",intent("0x00")));assertTrue(db.transactionAttempts().isEmpty());sql.execSQL("DROP TRIGGER deny_insert");assertTrue(db.prepareAttempt("first","handle_first",intent("0x00")));
        sql.execSQL("CREATE TRIGGER deny_update BEFORE UPDATE ON transaction_attempts BEGIN SELECT RAISE(ABORT,'fixture'); END");assertThrows(RuntimeException.class,()->db.markAttempt("first",HistoryDb.POSTING,null));assertEquals(HistoryDb.PREPARED,db.transactionAttempts().get(0).phase);
        row("retry");assertFalse(db.prepareAttempt("retry","handle_retry",intent("0x00")));
    }
    @Test public void malformedSavedEvidenceCannotBeSilentlyIgnoredOrRewritten() {
        prepare("first");String broken=intent("0x00").toString()+" hidden";db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET intent=?",new Object[]{broken});row("next");
        assertThrows(RuntimeException.class,()->db.prepareAttempt("next","handle_next",intent("0x00")));assertThrows(RuntimeException.class,()->db.markAttempt("first",HistoryDb.NOT_SUBMITTED,null));
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT intent FROM transaction_attempts",null)){assertTrue(c.moveToFirst());assertEquals(broken,c.getString(0));}
    }
    @Test public void duplicateDisplayRowsAndMissingRowsCannotStartAnAttempt() {
        row("first");HistoryRow duplicate=new HistoryRow();duplicate.internalid="first";duplicate.status=HistoryDb.STATUS_ERROR;assertFalse(db.insertPosting(duplicate));assertEquals(HistoryDb.STATUS_POSTING,db.list(5).get(0).status);
        assertFalse(db.prepareAttempt("missing","handle_missing",intent("0x00")));assertTrue(db.transactionAttempts().isEmpty());
    }
    @Test public void upgradePreservesExistingHistoryAndCreatesTheJournal() {
        row("legacy");db.close();SQLiteDatabase old=SQLiteDatabase.openDatabase(context.getDatabasePath("pandamonium_utxo_history.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);old.execSQL("DROP TABLE transaction_attempts");old.setVersion(7);old.close();db=new HistoryDb(context);
        assertEquals("legacy",db.list(5).get(0).internalid);assertTrue(db.transactionAttempts().isEmpty());assertTrue(db.prepareAttempt("legacy","handle_legacy",intent("0x00")));
    }
    @Test public void storedIntentRejectsTrailingBytesAndMissingOrChangedEconomics()throws Exception {
        for(String suffix:new String[]{" hidden"," []"," //hidden","\u0000hidden","\f"})assertThrows(RuntimeException.class,()->TransactionEvidence.storedIntent(intent("0x00").toString()+suffix));
        JSONObject missing=intent("0x00");missing.remove("burn");assertThrows(RuntimeException.class,()->TransactionEvidence.storedIntent(missing.toString()));
        JSONObject changed=intent("0x00");changed.getJSONArray("inputs").getJSONObject(0).put("tokenamount","9");assertThrows(RuntimeException.class,()->TransactionEvidence.storedIntent(changed.toString()));
        assertEquals("0",TransactionEvidence.storedIntent(" \n"+intent(TOKEN)+" \n").optString("burn"));
    }
    @Test public void concurrentHelpersAllowOnlyOneBuilderToReserveTheInputs()throws Exception {
        row("first");row("second");java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results=new java.util.ArrayList<>();
            for(String id:new String[]{"first","second"})results.add(pool.submit(()->{start.await();try(HistoryDb other=new HistoryDb(context)){return other.prepareAttempt(id,"handle_"+id,intent("0x00"));}catch(RuntimeException conflict){return false;}}));
            start.countDown();int accepted=0;for(java.util.concurrent.Future<Boolean> result:results)if(result.get(10,java.util.concurrent.TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,db.transactionAttempts().size());
        }finally{pool.shutdownNow();}
    }
    @Test public void tokenAndBurnInputsReserveAndConfirmTogetherAcrossRestart()throws Exception {
        JSONObject quote=PreparedSendEvidenceTest.quote(TOKEN,"0.25",true),nativeIntent=PreparedSendEvidenceTest.planned(quote,TOKEN,"0.25");
        row("native");assertTrue(db.prepareAttempt("native","native_handle",nativeIntent));
        assertTrue(db.hasUnresolvedAttempt(TOKEN));assertTrue(db.hasUnresolvedAttempt("0x00"));
        JSONObject other=intent("0x00");other.getJSONArray("inputs").getJSONObject(0).put("coinid",id('2'));row("custom");assertFalse(db.prepareAttempt("custom","custom_handle",other));
        assertTrue(db.markAttempt("native",HistoryDb.POSTING,null));assertTrue(db.markAttempt("native",HistoryDb.POSTED,quote));
        db.close();db=new HistoryDb(context);assertTrue(db.hasUnresolvedAttempt("0x00"));assertEquals(0,db.transactionAttempts().get(0).receipt.getJSONObject("companion").getJSONArray("outputs").length());
        assertTrue(db.confirmAttempt("native",quote,TransactionRecoveryTest.inclusion("3")));assertFalse(db.hasUnresolvedAttempt(TOKEN));assertFalse(db.hasUnresolvedAttempt("0x00"));
        assertTrue(db.prepareAttempt("custom","custom_handle",other));row("again");assertFalse(db.prepareAttempt("again","again_handle",nativeIntent));
    }

    private NodeApi closedClient() { NodeApi n=org.mockito.Mockito.mock(NodeApi.class);org.mockito.Mockito.when(n.isClosed()).thenReturn(true);return n; }
    private void previousProcess() { db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET owner=?",new Object[]{"00000000000000000000000000000000:11111111111111111111111111111111"}); }
    @Test public void processRestartReleasesOnlyPreparedAndKeepsPossibleBroadcasts()throws Exception {
        for(String phase:new String[]{HistoryDb.PREPARED,HistoryDb.POSTING,HistoryDb.UNKNOWN,HistoryDb.POSTED}) {
            db.close();context.deleteDatabase("pandamonium_utxo_history.db");db=new HistoryDb(context);prepare("first");
            if(!HistoryDb.PREPARED.equals(phase))assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));
            if(HistoryDb.UNKNOWN.equals(phase))assertTrue(db.markAttempt("first",phase,null));
            if(HistoryDb.POSTED.equals(phase))assertTrue(db.markAttempt("first",phase,receipt(intent("0x00"))));
            previousProcess();db.close();db=new HistoryDb(context);
            boolean abandoned=HistoryDb.PREPARED.equals(phase);assertEquals(abandoned?HistoryDb.NOT_SUBMITTED:phase,db.transactionAttempts().get(0).phase);
            assertEquals(!abandoned,db.hasUnresolvedAttempt("0x00"));
            if(abandoned){assertEquals(HistoryDb.STATUS_ERROR,db.list(5).get(0).status);assertEquals(TransactionEvidence.NOT_SENT,db.list(5).get(0).note);}
        }
    }
    @Test public void anotherLiveHelperIsPreservedAndClosedOwnerCannotResume()throws Exception {
        prepare("first");try(HistoryDb other=new HistoryDb(context)){
            assertEquals(HistoryDb.PREPARED,other.transactionAttempts().get(0).phase);
            JSONObject separate=intent("0x00");separate.getJSONArray("inputs").getJSONObject(0).put("coinid",id('2'));row("other");assertTrue(other.prepareAttempt("other","other_handle",separate));
            assertFalse(other.markAttempt("first",HistoryDb.POSTING,null));
            db.abandonPreparations(closedClient());assertFalse(db.markAttempt("first",HistoryDb.POSTING,null));
            row("late");assertFalse(db.prepareAttempt("late","late_handle",intent("0x00")));
            assertTrue(other.markAttempt("other",HistoryDb.POSTING,null));
        }
        assertFalse(db.update("first",HistoryDb.STATUS_UNKNOWN,null,"late"));assertEquals(HistoryDb.STATUS_ERROR,db.list(10).stream().filter(x->x.internalid.equals("first")).findFirst().get().status);
    }
    @Test public void aLiveClientCannotReleasePreparations() {
        prepare("first");NodeApi live=org.mockito.Mockito.mock(NodeApi.class);
        assertThrows(IllegalStateException.class,()->db.abandonPreparations(live));assertEquals(HistoryDb.PREPARED,db.transactionAttempts().get(0).phase);assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));
    }
    @Test public void abandonedPhaseAndDisplayWriteAreAtomicAndRetryable() {
        prepare("first");SQLiteDatabase sql=db.getWritableDatabase();sql.execSQL("CREATE TRIGGER deny_display BEFORE UPDATE ON history BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(RuntimeException.class,()->db.abandonPreparations(closedClient()));assertEquals(HistoryDb.PREPARED,db.transactionAttempts().get(0).phase);assertTrue(db.hasUnresolvedAttempt("0x00"));
        assertEquals(HistoryDb.STATUS_POSTING,db.list(5).get(0).status);assertFalse(db.markAttempt("first",HistoryDb.POSTING,null));
        sql.execSQL("DROP TRIGGER deny_display");db.abandonPreparations(closedClient());assertEquals(HistoryDb.NOT_SUBMITTED,db.transactionAttempts().get(0).phase);assertFalse(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void missingDisplayRowKeepsAbandonedReservation() {
        prepare("first");db.getWritableDatabase().delete("history","internalid=?",new String[]{"first"});
        assertThrows(RuntimeException.class,()->db.abandonPreparations(closedClient()));assertEquals(HistoryDb.PREPARED,db.transactionAttempts().get(0).phase);
    }
    @Test public void malformedOwnerCannotBeTreatedAsAnAbandonedProcess() {
        prepare("first");db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET owner='damaged'");
        assertThrows(RuntimeException.class,()->db.abandonPreparations(closedClient()));
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT phase,owner FROM transaction_attempts",null)){assertTrue(c.moveToFirst());assertEquals(HistoryDb.PREPARED,c.getString(0));assertEquals("damaged",c.getString(1));}
    }

    @Test public void closureDuringPreparationCannotInsertAnOrphanReservation()throws Exception {
        row("late");java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1);
        JSONObject delayed=new JSONObject(intent("0x00").toString()){
            @Override public String toString(){entered.countDown();try{assertTrue(resume.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return super.toString();}
        };
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<Boolean> result=worker.submit(()->db.prepareAttempt("late","late_handle",delayed));assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            db.abandonPreparations(closedClient());resume.countDown();assertFalse(result.get(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(db.transactionAttempts().isEmpty());
        }finally{resume.countDown();worker.shutdownNow();}
    }

    @Test public void damagedRestartEvidenceKeepsHistoryReadableButNeverReleasesInputs() {
        prepare("first");previousProcess();String broken=intent("0x00").toString()+" hidden";db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET intent=?",new Object[]{broken});db.close();db=new HistoryDb(context);
        assertEquals("first",db.list(5).get(0).internalid);assertThrows(RuntimeException.class,()->db.transactionAttempts());
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT phase,intent FROM transaction_attempts",null)){assertTrue(c.moveToFirst());assertEquals(HistoryDb.PREPARED,c.getString(0));assertEquals(broken,c.getString(1));}
        row("retry");assertThrows(RuntimeException.class,()->db.prepareAttempt("retry","retry_handle",intent("0x00")));
    }

    @Test public void consolidationReservationAndOutputFlagsSurviveRestartAndConfirmTogether()throws Exception {
        JSONObject quote=PreparedConsolidationTest.quote(true),plan=PreparedSendEvidence.consolidation(quote,"0x00",new java.util.HashSet<>(java.util.Arrays.asList(DEST,CHANGE)));
        row("merge");assertTrue(db.prepareAttempt("merge","merge_handle",plan));row("custom");assertFalse(db.prepareAttempt("custom","custom_handle",intent("0x00")));
        assertTrue(db.markAttempt("merge",HistoryDb.POSTING,null));assertTrue(db.markAttempt("merge",HistoryDb.POSTED,quote));db.close();db=new HistoryDb(context);
        assertFalse(db.transactionAttempts().get(0).intent.getJSONArray("outputs").getJSONObject(3).getBoolean("storestate"));
        JSONObject changed=TransactionEvidenceTest.copy(quote);PreparedConsolidationTest.txn(changed).getJSONArray("outputs").getJSONObject(3).put("storestate",true);
        assertThrows(RuntimeException.class,()->db.confirmAttempt("merge",changed,TransactionRecoveryTest.inclusion("3")));assertTrue(db.hasUnresolvedAttempt("0x00"));
        assertTrue(db.confirmAttempt("merge",quote,TransactionRecoveryTest.inclusion("3")));assertFalse(db.hasUnresolvedAttempt("0x00"));assertTrue(db.prepareAttempt("custom","custom_handle",intent("0x00")));
    }

}
