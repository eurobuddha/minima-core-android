package com.eurobuddha.utxo;

import android.widget.Toast;
import org.json.*;
import org.junit.Test;
import org.mockito.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Actual controller, saved preferences and validated journal attempts; builders stop before signing. */
public class DistributeResumeTest {
    static final String ADDRESS="0x"+"22".repeat(32),RECIPIENT="0x"+"33".repeat(32),COIN="0x"+"11".repeat(32);
    static String id(int n){return "0x"+String.format(java.util.Locale.ROOT,"%064x",n);}
    static class Flow implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class);final NodeApi node=mock(NodeApi.class);final HistoryDb history=mock(HistoryDb.class);
        final DistributionTestPrefs.Prefs prefs=new DistributionTestPrefs.Prefs();
        final List<DistributeManager> managers=new ArrayList<>();final List<HistoryDb.Attempt> attempts=new ArrayList<>();final List<Coin> coins=new ArrayList<>();
        String initialAmount="30";
        final List<List<?>> builds=new ArrayList<>();final List<String> records=new ArrayList<>(),commands=new ArrayList<>();
        final MockedStatic<Toast> toasts=mockStatic(Toast.class);
        final MockedConstruction<TxnBuilder> builders=mockConstruction(TxnBuilder.class,(builder,c)->{
            builds.add(c.arguments());when(builder.record(anyString())).thenAnswer(call->{records.add(call.getArgument(0));return builder;});
            doAnswer(call->{phase(HistoryDb.PREPARED);return null;}).when(builder).run();
        });
        Flow(){
            when(act.getSharedPreferences(eq("utxo_distribute"),anyInt())).thenReturn(prefs.prefs);when(act.node()).thenReturn(node);when(act.history()).thenReturn(history);when(history.insertPosting(any())).thenReturn(true);
            when(act.chainBlock()).thenReturn(100);when(act.coins()).thenReturn(coins);when(history.transactionAttempts()).thenAnswer(c->new ArrayList<>(attempts));
            toasts.when(()->Toast.makeText(eq(act),any(CharSequence.class),anyInt())).thenReturn(mock(Toast.class));
            doAnswer(c->{String command=c.getArgument(0);commands.add(command);assertEquals("getaddress",command);((NodeApi.Cb)c.getArgument(1)).onResult(object("status",true,"response",object("address",ADDRESS)));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        }
        DistributeManager manager(){DistributeManager m=new DistributeManager(act);managers.add(m);return m;}
        Coin input(){Coin c=new Coin();c.coinid=COIN;c.address=ADDRESS;c.miniaddress="";c.tokenid="0x00";c.amount=c.rawAmount=initialAmount;c.confirmed=c.sendable=true;return c;}
        DistributeManager start(int recipients){DistributeManager m=manager();m.start(Collections.singletonList(input()),new ArrayList<>(Collections.nCopies(recipients,RECIPIENT)),"1","0x00","Minima");return m;}
        @SuppressWarnings("unchecked") JSONObject intent(){List<?> b=builds.get(builds.size()-1);return TransactionEvidence.intent((List<Coin>)b.get(1),(List<TxnBuilder.Out>)b.get(2),(String)b.get(3),null);}
        JSONObject reply(){JSONObject intent=intent();JSONArray outs=new JSONArray();for(int i=0;i<intent.optJSONArray("outputs").length();i++){JSONObject want=intent.optJSONArray("outputs").optJSONObject(i);outs.put(object("coinid",id(100*builds.size()+i),"tokenid","0x00","address",want.optString("address"),"amount",want.optString("amount")));}
            return object("status",true,"response",object("txpowid",id(10000+builds.size()),"body",object("txn",object("inputs",intent.optJSONArray("inputs"),"outputs",outs))));}
        JSONObject inclusion(){return object("status",true,"response",object("found",true,"blockid",id(9000),"block","100","tip","103","confirmations","3"));}
        void phase(String phase){String id=records.get(records.size()-1);JSONObject intent=intent(),receipt=null;
            if(HistoryDb.POSTED.equals(phase)||HistoryDb.CONFIRMED.equals(phase)){receipt=TransactionEvidence.publicReceipt(reply(),intent);if(HistoryDb.CONFIRMED.equals(phase))try{receipt.put("inclusion",TransactionEvidence.inclusion(inclusion()));}catch(JSONException e){throw new AssertionError(e);}}
            HistoryDb.Attempt a=new HistoryDb.Attempt(id,"public_builder_"+builds.size(),phase,intent.toString(),receipt==null?"":receipt.toString(),"a".repeat(32)+":"+"b".repeat(32));attempts.removeIf(old->old.id.equals(id));attempts.add(a);
        }
        TxnBuilder.Done done(){return (TxnBuilder.Done)builds.get(builds.size()-1).get(4);}
        void posted(){phase(HistoryDb.POSTED);done().onPosted(id(10000+builds.size()),TransactionEvidence.receipt(reply(),intent()));}
        void supplyChange(){JSONArray outs=attempts.get(attempts.size()-1).receipt.optJSONArray("outputs");JSONObject out=outs.optJSONObject(outs.length()-1);Coin c=input();c.coinid=out.optString("coinid");c.address=out.optString("address");c.amount=c.rawAmount=out.optString("amount");coins.clear();coins.add(c);}
        public void close(){for(DistributeManager m:managers)m.close();builders.close();toasts.close();}
    }
    @Test public void postingDoesNotAdvanceAndConfirmationPersistsBeforeTheNextBatch() {
        try(Flow f=new Flow()){DistributeManager m=f.start(15);assertEquals(1,f.builds.size());f.posted();m.onCoinsUpdated();DistributeJob held=DistributeJob.load(f.act);assertTrue(held.uncertain);assertEquals(15,held.remaining.size());assertEquals(0,held.batchesDone);
            f.phase(HistoryDb.CONFIRMED);f.supplyChange();m.onCoinsUpdated();held=DistributeJob.load(f.act);assertEquals(1,held.remaining.size());assertEquals(1,held.batchesDone);assertEquals(1,f.builds.size());m.onCoinsUpdated();assertEquals(2,f.builds.size());
            assertEquals(f.coins.get(0).coinid,((List<Coin>)f.builds.get(1).get(1)).get(0).coinid);
        }
    }
    @Test public void restartRecoversAnUnknownConfirmedBatchWithoutRepeatingItsRecipients() {
        try(Flow f=new Flow()){DistributeManager old=f.start(15);f.phase(HistoryDb.UNKNOWN);f.done().onUnknown(TransactionEvidence.UNKNOWN);old.close();f.phase(HistoryDb.CONFIRMED);f.supplyChange();DistributeManager resumed=f.manager();resumed.onCoinsUpdated();assertEquals(1,f.builds.size());assertEquals(1,DistributeJob.load(f.act).remaining.size());resumed.onCoinsUpdated();assertEquals(2,f.builds.size());}
    }
    @Test public void finalBatchIsNotCompleteUntilItsExactJournalAttemptConfirms() {
        try(Flow f=new Flow()){DistributeManager m=f.start(2);f.posted();m.onCoinsUpdated();assertTrue(m.isActive());assertNotNull(DistributeJob.load(f.act));f.phase(HistoryDb.CONFIRMED);m.onCoinsUpdated();assertFalse(m.isActive());assertNull(DistributeJob.load(f.act));}
    }
    @Test public void failedProgressWriteKeepsRecipientsPendingAndCannotBuildAnotherBatch() {
        try(Flow f=new Flow()){DistributeManager m=f.start(15);f.posted();f.phase(HistoryDb.CONFIRMED);f.supplyChange();f.prefs.writable=false;m.onCoinsUpdated();m.onCoinsUpdated();assertEquals(1,f.builds.size());assertEquals(15,DistributeJob.load(f.act).remaining.size());f.prefs.writable=true;m.onCoinsUpdated();assertEquals(1,DistributeJob.load(f.act).remaining.size());}
    }
    @Test public void failedPreSignJobSaveCannotReachTheBuilder() {
        try(Flow f=new Flow()){f.prefs.writable=false;f.start(15);assertTrue(f.builds.isEmpty());}
    }
    @Test public void missingOrDifferentAttemptCannotReleaseAnUncertainBatch()throws Exception {
        try(Flow f=new Flow()){DistributeManager m=f.start(15);f.phase(HistoryDb.UNKNOWN);f.done().onUnknown(TransactionEvidence.UNKNOWN);f.attempts.clear();m.onCoinsUpdated();assertEquals(1,f.builds.size());assertEquals(15,DistributeJob.load(f.act).remaining.size());
            f.phase(HistoryDb.CONFIRMED);HistoryDb.Attempt exact=f.attempts.remove(0);JSONObject wrong=TransactionEvidence.storedIntent(exact.intent.toString());wrong.getJSONArray("inputs").getJSONObject(0).put("coinid",id(99));f.attempts.add(new HistoryDb.Attempt(exact.id,exact.handle,exact.phase,wrong.toString(),exact.receipt.toString(),exact.owner));m.onCoinsUpdated();assertEquals(15,DistributeJob.load(f.act).remaining.size());
        }
    }
    @Test public void secondLiveControllerCannotAdvanceOrClearTheOwnersJob() {
        try(Flow f=new Flow()){DistributeManager first=f.start(15),second=f.manager();assertFalse(second.reserve());f.posted();f.phase(HistoryDb.CONFIRMED);second.onCoinsUpdated();assertEquals(15,DistributeJob.load(f.act).remaining.size());first.onCoinsUpdated();assertEquals(1,DistributeJob.load(f.act).remaining.size());}
    }
    @Test public void callbackAfterCloseCannotMutateTheResumedJobsProgress() {
        try(Flow f=new Flow()){DistributeManager first=f.start(15);TxnBuilder.Done stale=f.done();f.phase(HistoryDb.CONFIRMED);first.close();DistributeManager second=f.manager();second.onCoinsUpdated();String saved=f.prefs.values.get("job");stale.onFailed("late");stale.onUnknown("late");assertEquals(saved,f.prefs.values.get("job"));}
    }
    @Test public void onlyProvenNotSubmittedFailureCanUseTheRetryPolicy() {
        try(Flow f=new Flow()){DistributeManager m=f.start(15);f.posted();f.phase(HistoryDb.CONFIRMED);f.supplyChange();m.onCoinsUpdated();m.onCoinsUpdated();f.phase(HistoryDb.UNKNOWN);f.done().onFailed("ambiguous");assertTrue(DistributeJob.load(f.act).uncertain);m.onCoinsUpdated();assertEquals(2,f.builds.size());f.phase(HistoryDb.NOT_SUBMITTED);m.onCoinsUpdated();assertFalse(DistributeJob.load(f.act).uncertain);assertEquals(1,DistributeJob.load(f.act).retryCount);m.onCoinsUpdated();assertEquals(3,f.builds.size());}
    }
    @Test public void unrelatedOrUnspendableChangeCannotStartTheNextBatch() {
        for(int mode=0;mode<3;mode++)try(Flow f=new Flow()){DistributeManager m=f.start(15);f.posted();f.phase(HistoryDb.CONFIRMED);f.supplyChange();m.onCoinsUpdated();if(mode==0)f.coins.get(0).coinid=id(999);if(mode==1)f.coins.get(0).sendable=false;if(mode==2)f.coins.get(0).amount="999";m.onCoinsUpdated();assertEquals(1,f.builds.size());}
    }
    @Test public void anAlreadyConfirmedChangeCanResumeAfterTheOldExpiryDeadline() {
        try(Flow f=new Flow()){DistributeManager m=f.start(15);f.posted();f.phase(HistoryDb.CONFIRMED);f.supplyChange();m.onCoinsUpdated();m.close();when(f.act.chainBlock()).thenReturn(200);f.manager().onCoinsUpdated();assertEquals(2,f.builds.size());}
    }
    @Test public void failedCompletionClearKeepsTheCompletedJobForIdempotentRetry() {
        try(Flow f=new Flow()){DistributeManager m=f.start(2);f.posted();f.phase(HistoryDb.CONFIRMED);when(f.prefs.editor.commit()).thenAnswer(c->{if(f.prefs.changes.containsValue(null)){f.prefs.changes.clear();return false;}f.prefs.flush();return true;});m.onCoinsUpdated();assertTrue(m.isActive());assertTrue(DistributeJob.load(f.act).remaining.isEmpty());m.onCoinsUpdated();assertEquals(1,f.builds.size());}
    }
    @Test public void malformedAndLegacyJobsRemainVisibleWithoutNodeCommands() {
        for(String raw:new String[]{"not json","{}","{\"remaining\":[\"public\"]} extra","{\"schema\":2}"})try(Flow f=new Flow()){f.prefs.values.put("job",raw);DistributeManager m=f.manager();m.onCoinsUpdated();assertTrue(m.isBusy());assertFalse(m.reserve());assertEquals(raw,f.prefs.values.get("job"));assertTrue(f.commands.isEmpty());assertTrue(f.builds.isEmpty());}
    }
    @Test public void invalidInitialAmountsAddressesAndCountsCannotStartAJob() {
        try(Flow f=new Flow()){DistributeManager m=f.manager();for(String per:new String[]{"0","-1","NaN","1;send"})m.start(Collections.singletonList(f.input()),Collections.singletonList(RECIPIENT),per,"0x00","Minima");m.start(Collections.singletonList(f.input()),Collections.nCopies(57,RECIPIENT),"1","0x00","Minima");m.start(Collections.singletonList(f.input()),Collections.singletonList(RECIPIENT+";send"),"1","0x00","Minima");assertTrue(f.commands.isEmpty());assertTrue(f.builds.isEmpty());}
    }
    @Test public void exhaustedRetriesSurviveFailedClearAndRestartWithoutAnotherBuilder() {
        try(Flow f=new Flow()) {
            DistributeManager m=f.start(15);f.posted();f.phase(HistoryDb.CONFIRMED);f.supplyChange();m.onCoinsUpdated();m.onCoinsUpdated();
            for(int retry=0;retry<3;retry++) {f.phase(HistoryDb.NOT_SUBMITTED);f.done().onFailed("not sent");m.onCoinsUpdated();}
            assertEquals(5,f.builds.size());
            when(f.prefs.editor.commit()).thenAnswer(c->{if(f.prefs.changes.containsValue(null)){f.prefs.changes.clear();return false;}f.prefs.flush();return true;});
            f.phase(HistoryDb.NOT_SUBMITTED);f.done().onFailed("not sent");assertEquals(4,DistributeJob.load(f.act).retryCount);
            m.close();DistributeManager resumed=f.manager();resumed.onCoinsUpdated();resumed.onCoinsUpdated();assertEquals(5,f.builds.size());assertTrue(resumed.isActive());
            when(f.prefs.editor.commit()).thenAnswer(c->{f.prefs.flush();return true;});resumed.onCoinsUpdated();assertNull(DistributeJob.load(f.act));assertEquals(5,f.builds.size());
        }
    }


    @Test public void admissionFailureBeforeAnyNodeCommandCannotStrandANewJob() {
        try(Flow f=new Flow()) {
            DistributeManager m=f.start(15);f.attempts.clear();f.done().onNotStarted(TransactionEvidence.RECORD_FAILED);
            assertFalse(m.isActive());assertNull(DistributeJob.load(f.act));assertEquals(1,f.builds.size());
        }
    }
    @Test public void allFourBatchesAdvanceExactlyOnceAfterTheirOwnConfirmations() {
        try(Flow f=new Flow()) {
            f.initialAmount="100";DistributeManager m=f.start(56);
            for(int i=1;i<=4;i++) {
                assertEquals(i,f.builds.size());f.posted();m.onCoinsUpdated();assertEquals(i,f.builds.size());
                f.phase(HistoryDb.CONFIRMED);f.supplyChange();m.onCoinsUpdated();
                if(i<4){assertEquals(56-14*i,DistributeJob.load(f.act).remaining.size());m.onCoinsUpdated();}
            }
            assertFalse(m.isActive());assertNull(DistributeJob.load(f.act));m.onCoinsUpdated();assertEquals(4,f.builds.size());
        }
    }
    @Test public void finalBatchWithNoChangeStaysPendingUntilItsReceiptConfirms() {
        try(Flow f=new Flow()) {
            f.initialAmount="2";DistributeManager m=f.start(2);f.phase(HistoryDb.UNKNOWN);f.done().onUnknown(TransactionEvidence.UNKNOWN);
            m.onCoinsUpdated();assertTrue(m.isActive());assertEquals(2,DistributeJob.load(f.act).remaining.size());
            f.phase(HistoryDb.CONFIRMED);m.onCoinsUpdated();assertFalse(m.isActive());assertEquals(1,f.builds.size());
        }
    }
}
