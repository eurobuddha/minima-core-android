package com.eurobuddha.nftwallet;
import android.os.Handler;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.*;

/** Runs the real command chain against a fake node; no device, node, key or transaction is used. */
public class BurialFlowTest {
    static class Harness implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class); final NodeApi node=mock(NodeApi.class);
        final Sheet.Progress progress=mock(Sheet.Progress.class); final AtomicInteger complete=new AtomicInteger(), callback=new AtomicInteger();
        final List<String> commands=new ArrayList<>(); final List<Runnable> polls=new ArrayList<>();
        final Map<String,ArrayDeque<NodeApi.Cb>> reads=new HashMap<>();
        final Map<String,JSONObject> fixtures=new HashMap<>(); final Set<String> failed=new HashSet<>();
        JSONArray journal=new JSONArray(); String input=""; boolean pause=true,write=true,bareAck=false;
        final MockedStatic<LocalStore> store=mockStatic(LocalStore.class);
        final MockedStatic<MintDriver> driver=mockStatic(MintDriver.class);
        final MockedStatic<Sheet> sheets=mockStatic(Sheet.class);
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{polls.add(i.getArgument(0));return true;}));
        Harness(){
            when(act.node()).thenReturn(node);
            sheets.when(()->Sheet.progress(eq(act),any(CharSequence.class),any(CharSequence.class))).thenReturn(progress);
            driver.when(()->MintDriver.pauseForBurial(act,TOKEN)).thenAnswer(i->pause);
            store.when(()->LocalStore.burialJournal(act,TOKEN)).thenAnswer(i->new JSONArray(journal.toString()));
            store.when(()->LocalStore.saveBurialJournal(eq(act),eq(TOKEN),any(JSONArray.class))).thenAnswer(i->{if(!write)return false;journal=new JSONArray(((JSONArray)i.getArgument(2)).toString());return true;});
            store.when(()->LocalStore.completeBurial(eq(act),eq(TOKEN),any(JSONArray.class))).thenAnswer(i->{assertTrue(BurialEvidence.sameJournal(journal,i.getArgument(2),TOKEN));complete.incrementAndGet();journal=new JSONArray();return true;});
            doAnswer(call->{String command=call.getArgument(0);NodeApi.Cb cb=call.getArgument(1);commands.add(command);
                if(command.startsWith("coins ")){reads.computeIfAbsent(command,k->new ArrayDeque<>()).add(cb);return null;}
                assertTrue("Unexpected command "+command,command.startsWith("txn"));
                if(command.startsWith("txninput ")){input=command.substring(command.indexOf("coinid:")+7);assertNotNull(fixtures.get(input));}
                if(command.startsWith("txnpost ")){assertTrue("Intent must be durable before posting",journal.toString().contains(input));if(failed.contains(input))cb.onError("Rejected fixture");else cb.onResult(bareAck?object("status",true):posted(fixtures.get(input)));}
                else if(command.startsWith("txncheck "))cb.onResult(object("status",true,"response",object("coins",new JSONArray().put(object("difference","0")))));
                else cb.onResult(object("status",true));return null;
            }).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        }
        String ownedCommand(){return "coins relevant:true own:true tokenid:"+TOKEN;}
        void gather(){try{Method m=StateNftActions.class.getDeclaredMethod("gatherAndBury",MainActivity.class,String.class,String.class,String.class,Runnable.class);m.setAccessible(true);m.invoke(null,act,TOKEN,"Literal Collection","0x1234",(Runnable)callback::incrementAndGet);}catch(Exception e){throw new AssertionError(e);}}
        void answer(String command,JSONObject reply){ArrayDeque<NodeApi.Cb> q=reads.get(command);assertNotNull("No queued query "+command,q);assertFalse(q.isEmpty());q.remove().onResult(reply);}
        void owned(JSONObject...coins){JSONArray a=new JSONArray();for(JSONObject c:coins){fixtures.put(c.optString("coinid"),c);a.put(c);}answer(ownedCommand(),list(a));}
        void poll(){assertFalse("No scheduled poll",polls.isEmpty());polls.remove(0).run();}
        void prove(JSONObject...inputs){for(JSONObject c:inputs){JSONObject out=output(c);answer("coins coinid:"+out.optString("coinid"),list(new JSONArray().put(out)));}}
        long posts(){return commands.stream().filter(c->c.startsWith("txnpost ")).count();}
        public void close(){handlers.close();sheets.close();driver.close();store.close();}
    }
    @Test public void missingMalformedAndUnsafeListsNeverPostOrComplete()throws Exception{
        for(JSONObject bad:new JSONObject[]{object("status",true),list(new JSONArray().put(JSONObject.NULL)),list(new JSONArray().put(object())),list(new JSONArray().put(copy(coin(1)).put("state",object("0","7 burn:9"))))})try(Harness h=new Harness()){
            h.gather();h.answer(h.ownedCommand(),bad);assertEquals(0,h.posts());assertEquals(0,h.complete.get());assertEquals(0,h.callback.get());assertTrue(h.polls.isEmpty());assertEquals(0,h.journal.length());
        }
    }
    @Test public void noOwnedCoinsAndNoReceiptsCannotMarkAMintBuried(){try(Harness h=new Harness()){h.gather();h.owned();assertTrue(h.reads.get(h.ownedCommand()).isEmpty());assertEquals(0,h.posts());assertEquals(0,h.callback.get());assertEquals(0,h.complete.get());assertTrue(h.polls.isEmpty());}}
    @Test public void busyMintAndFailedPersistenceStopBeforeAnySignature(){
        try(Harness h=new Harness()){h.pause=false;h.gather();assertTrue(h.commands.isEmpty());assertEquals(0,h.complete.get());}
        try(Harness h=new Harness()){h.write=false;h.gather();h.owned(coin(1));assertEquals(0,h.posts());assertFalse(h.commands.stream().anyMatch(c->c.startsWith("txnsign")));assertEquals(0,h.complete.get());}
    }
    @Test public void postAckAndInputAbsenceAreInsufficientUntilEveryOutputIsVerified(){
        try(Harness h=new Harness()){
            JSONObject c=coin(1);h.gather();h.owned(c);assertEquals(1,h.posts());assertEquals(0,h.complete.get());assertEquals(0,h.callback.get());
            h.poll();h.answer("coins coinid:"+output(c).optString("coinid"),list(new JSONArray()));assertEquals(0,h.complete.get());
            h.poll();h.prove(c);h.owned(c);assertEquals(0,h.complete.get());
            h.poll();h.prove(c);h.owned();assertEquals("completion",1,h.complete.get());assertEquals("callback",1,h.callback.get());assertTrue(h.polls.isEmpty());
        }
    }
    @Test public void partialFailurePersistsPendingInputAndCannotFinalizeOnRetryWithoutEvidence(){
        try(Harness h=new Harness()){
            JSONObject a=coin(1),b=coin(2);h.failed.add(b.optString("coinid"));h.gather();h.owned(a,b);assertEquals(2,h.posts());assertEquals(2,h.journal.length());assertEquals("",h.journal.optJSONObject(1).optString("output"));assertTrue(h.polls.isEmpty());assertEquals(0,h.callback.get());
            h.gather();h.owned();assertTrue(h.polls.isEmpty());assertEquals(0,h.complete.get());
            h.failed.clear();h.gather();h.owned(b);assertEquals(3,h.posts());h.poll();h.prove(a,b);h.owned();assertEquals("completion",1,h.complete.get());assertEquals("callback",1,h.callback.get());
        }
    }
    @Test public void savedReceiptsResumeChecksWithoutSigningTheSameInputAgain(){try(Harness h=new Harness()){
        JSONObject c=coin(1);h.gather();h.owned(c);assertEquals(1,h.posts());h.polls.clear();
        h.gather();h.owned(c);assertEquals(1,h.posts());h.poll();h.prove(c);h.owned();assertEquals("completion",1,h.complete.get());
    }}
    @Test public void missingPostReceiptRemainsUnknownEvenIfTheInputDisappears(){try(Harness h=new Harness()){
        h.bareAck=true;h.gather();h.owned(coin(1));assertEquals(1,h.posts());assertEquals(1,h.journal.length());assertEquals("",h.journal.optJSONObject(0).optString("output"));assertTrue(h.polls.isEmpty());h.gather();h.owned();assertEquals(0,h.complete.get());assertEquals(0,h.callback.get());
    }}
    @Test public void malformedProofsTimeoutWithoutCompletingOrDiscardingJournal(){try(Harness h=new Harness()){
        JSONObject c=coin(1);h.gather();h.owned(c);
        for(int i=0;i<20;i++){h.poll();h.answer("coins coinid:"+output(c).optString("coinid"),object("status",true));}
        assertTrue(h.polls.isEmpty());assertEquals(1,h.journal.length());assertEquals(0,h.complete.get());assertEquals(0,h.callback.get());
    }}
    @Test public void changedJournalAndDestroyedActivityCannotFinalizeOldWork(){
        try(Harness h=new Harness()){JSONObject c=coin(1);h.gather();h.owned(c);h.poll();h.prove(c);h.journal.put(BurialEvidence.intent(coin(2),TOKEN));h.owned();assertEquals(0,h.complete.get());assertEquals(0,h.callback.get());}
        try(Harness h=new Harness()){h.gather();when(h.act.isDestroyed()).thenReturn(true);h.owned(coin(1));assertEquals(0,h.posts());assertEquals(0,h.complete.get());}
    }
}
