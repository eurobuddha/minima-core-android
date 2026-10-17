package com.eurobuddha.nftwallet;
import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.*;

public class BurialLifecycleTest {
    static class Prefs {
        final Context ctx=mock(Context.class);final SharedPreferences prefs=mock(SharedPreferences.class);final SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class);
        final Map<String,String> values=new HashMap<>(),changes=new HashMap<>(); boolean writable=true;
        Prefs(){
            when(ctx.getSharedPreferences(eq("nftwallet_statenft"),anyInt())).thenReturn(prefs);
            when(prefs.getString(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
            when(prefs.edit()).thenReturn(editor);
            when(editor.putString(anyString(),anyString())).thenAnswer(i->{changes.put(i.getArgument(0),i.getArgument(1));return editor;});
            when(editor.remove(anyString())).thenAnswer(i->{changes.put(i.getArgument(0),null);return editor;});
            when(editor.commit()).thenAnswer(i->{if(!writable){changes.clear();return false;}flush();return true;});
            doAnswer(i->{flush();return null;}).when(editor).apply();
        }
        void flush(){changes.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});changes.clear();}
        void rows(JSONObject...rows){JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);values.put("collections",a.toString());}
    }
    static JSONObject row(long id,String token,String phase){return object("id",id,"tokenid",token,"phase",phase,"name","Literal Collection");}
    @Before @After public void resetDriver()throws Exception {for(Object[] v:new Object[][]{{"running",false},{"activeNode",null},{"generation",0L}}){Field f=MintDriver.class.getDeclaredField((String)v[0]);f.setAccessible(true);f.set(null,v[1]);}}
    @Test public void liveMintCannotOverlapBurialAndOldCompletionCannotReleaseNewTick(){
        Prefs p=new Prefs();p.rows(row(1,TOKEN,"MOVE"));NodeApi first=mock(NodeApi.class),second=mock(NodeApi.class);List<MintEngine.Done> callbacks=new ArrayList<>();AtomicInteger firstDone=new AtomicInteger(),secondDone=new AtomicInteger();
        try(MockedStatic<MintEngine> engine=mockStatic(MintEngine.class)){
            engine.when(()->MintEngine.tick(eq(p.ctx),any(NodeApi.class),any(MintEngine.Done.class))).thenAnswer(i->{callbacks.add(i.getArgument(2));return null;});
            assertEquals(MintDriver.Result.STARTED,MintDriver.tick(p.ctx,first,m->firstDone.incrementAndGet()));
            assertFalse(MintDriver.pauseForBurial(p.ctx,TOKEN));assertEquals("MOVE",LocalStore.findById(p.ctx,1).optString("phase"));
            assertEquals(MintDriver.Result.BUSY,MintDriver.tick(p.ctx,second,m->secondDone.incrementAndGet()));
            when(first.isClosed()).thenReturn(true);
            assertEquals(MintDriver.Result.STARTED,MintDriver.tick(p.ctx,second,m->secondDone.incrementAndGet()));
            callbacks.get(0).done("old reply");assertEquals(0,firstDone.get());assertTrue(MintDriver.isRunning());assertFalse(MintDriver.pauseForBurial(p.ctx,TOKEN));
            callbacks.get(1).done("current reply");assertEquals(1,secondDone.get());assertFalse(MintDriver.isRunning());
            assertTrue(MintDriver.pauseForBurial(p.ctx,TOKEN));assertEquals("BURY",LocalStore.findById(p.ctx,1).optString("phase"));assertFalse(MintDriver.hasWork(p.ctx));assertTrue(MintDriver.hasPausedBurial(p.ctx));
        }
    }
    @Test public void lateMintRowsCannotUndoBurialPauseOrCompletion()throws Exception{
        Prefs p=new Prefs();JSONObject original=row(1,TOKEN,"STAMP");p.rows(original,row(2,"0xbb","MOVE"));assertTrue(MintDriver.pauseForBurial(p.ctx,TOKEN));
        LocalStore.upsert(p.ctx,copy(original).put("error","late error"));assertEquals("BURY",LocalStore.findById(p.ctx,1).optString("phase"));assertEquals("MOVE",LocalStore.findById(p.ctx,2).optString("phase"));
        JSONArray journal=new JSONArray().put(BurialEvidence.receipt(posted(coin(1)),BurialEvidence.intent(coin(1),TOKEN)));assertTrue(LocalStore.saveBurialJournal(p.ctx,TOKEN,journal));
        assertFalse(LocalStore.completeBurial(p.ctx,TOKEN,new JSONArray()));assertEquals("BURY",LocalStore.findById(p.ctx,1).optString("phase"));
        assertTrue(LocalStore.completeBurial(p.ctx,TOKEN,journal));assertEquals("BURIED",LocalStore.findById(p.ctx,1).optString("phase"));assertEquals("MOVE",LocalStore.findById(p.ctx,2).optString("phase"));assertEquals(0,LocalStore.burialJournal(p.ctx,TOKEN).length());
        LocalStore.upsert(p.ctx,original);assertEquals("BURIED",LocalStore.findById(p.ctx,1).optString("phase"));
    }
    @Test public void publicJournalSurvivesFreshReadsAndFailedWritesDoNotAuthorizeCompletion(){
        Prefs p=new Prefs();p.rows(row(1,TOKEN,"DONE"));JSONArray journal=new JSONArray().put(BurialEvidence.intent(coin(1),TOKEN));assertTrue(LocalStore.saveBurialJournal(p.ctx,TOKEN,journal));
        assertEquals(journal.toString(),LocalStore.burialJournal(p.ctx,TOKEN).toString());assertNotSame(journal,LocalStore.burialJournal(p.ctx,TOKEN));
        p.writable=false;assertFalse(MintDriver.pauseForBurial(p.ctx,TOKEN));assertEquals("DONE",LocalStore.findById(p.ctx,1).optString("phase"));
        assertFalse(LocalStore.saveBurialJournal(p.ctx,TOKEN,new JSONArray()));assertEquals(1,LocalStore.burialJournal(p.ctx,TOKEN).length());
    }
    @Test public void corruptStoredRowsOrJournalCannotBeSilentlyReplacedWithEmpty(){
        for(String raw:new String[]{"not json","{}","[null]"}){Prefs p=new Prefs();p.values.put("collections",raw);rejects(()->MintDriver.pauseForBurial(p.ctx,TOKEN));assertEquals(raw,p.values.get("collections"));}
        for(String raw:new String[]{"not json","{}","[null]","[{}]"}){Prefs p=new Prefs();p.values.put("burial:"+TOKEN,raw);rejects(()->LocalStore.burialJournal(p.ctx,TOKEN));assertEquals(raw,p.values.get("burial:"+TOKEN));}
    }
    @Test public void pausedCollectionDoesNotStarveAnotherMintsReadOnlyStep(){
        Prefs p=new Prefs();p.rows(row(1,TOKEN,"BURY"),row(2,"0xbb","MOVE"));NodeApi node=mock(NodeApi.class);List<String> commands=new ArrayList<>();AtomicReference<String> done=new AtomicReference<>();
        doAnswer(i->{String command=i.getArgument(0);commands.add(command);NodeApi.Cb cb=i.getArgument(1);if(command.equals("block"))cb.onResult(object("status",true,"response",object("block",100)));else{assertEquals("coins relevant:true own:true tokenid:0xbb",command);cb.onResult(list(new JSONArray()));}return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        // prunePending needs only its empty default JSON, supplied by the same public preferences fixture.
        MintEngine.tick(p.ctx,node,done::set);assertEquals("Waiting for mint coin",done.get());assertEquals(2,commands.size());assertEquals("BURY",LocalStore.findById(p.ctx,1).optString("phase"));
    }
    @Test public void emptyAndPendingJournalsCannotRetireAMint()throws Exception{
        Prefs p=new Prefs();p.rows(row(1,TOKEN,"BURY").put("stuck",1));
        assertFalse(LocalStore.completeBurial(p.ctx,TOKEN,new JSONArray()));
        JSONArray pending=new JSONArray().put(BurialEvidence.intent(coin(1),TOKEN));assertTrue(LocalStore.saveBurialJournal(p.ctx,TOKEN,pending));
        assertFalse(LocalStore.completeBurial(p.ctx,TOKEN,pending));assertEquals("BURY",LocalStore.findById(p.ctx,1).optString("phase"));
        assertTrue(MintDriver.hasPausedBurial(p.ctx));assertFalse(MintDriver.hasStuck(p.ctx));
    }
    @Test public void closedNodeIgnoresAlreadyQueuedRepliesAndCannotDispatchNewWrites()throws Exception{
        List<Runnable> posted=new ArrayList<>();AtomicReference<com.eurobuddha.minimaapi.MinimaAPIListener> response=new AtomicReference<>();
        try(org.mockito.MockedConstruction<android.os.Handler> handlers=mockConstruction(android.os.Handler.class,(h,c)->{
            when(h.post(any(Runnable.class))).thenAnswer(i->{posted.add(i.getArgument(0));return true;});
        });org.mockito.MockedConstruction<com.eurobuddha.minimaapi.direct.DirectNodeApi> clients=mockConstruction(com.eurobuddha.minimaapi.direct.DirectNodeApi.class,(n,c)->{
            doAnswer(i->{response.set(i.getArgument(1));return null;}).when(n).Command(anyString(),any());
        })){
            NodeApi api=new NodeApi(mock(Context.class),null);NodeApi.Cb cb=mock(NodeApi.Cb.class);
            assertFalse(api.isClosed());api.cmd("coins",cb);posted.remove(0).run();
            response.get().response(object("status",true,"response",new JSONArray()));api.onDestroy();assertTrue(api.isClosed());posted.remove(0).run();verifyNoInteractions(cb);
            api.cmd("txnpost id:public-fixture",cb);posted.remove(0).run();posted.remove(0).run();verify(cb).onError("Node API released");
            verify(clients.constructed().get(0),never()).Command(eq("txnpost id:public-fixture"),any());
        }
    }
}
