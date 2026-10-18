package com.eurobuddha.statenft;
import android.content.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.statenft.BurialEvidenceTest.*;

public class AtelierBurialStoreTest {
    static class Prefs {
        final Context ctx=mock(Context.class);final SharedPreferences prefs=mock(SharedPreferences.class);final SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class);
        final Map<String,String> values=new HashMap<>(),changes=new HashMap<>();boolean writable=true;
        Prefs(){when(ctx.getSharedPreferences(eq("statenft_suite"),anyInt())).thenReturn(prefs);when(prefs.getString(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));when(prefs.edit()).thenReturn(editor);when(editor.putString(anyString(),anyString())).thenAnswer(i->{changes.put(i.getArgument(0),i.getArgument(1));return editor;});when(editor.remove(anyString())).thenAnswer(i->{changes.put(i.getArgument(0),null);return editor;});when(editor.commit()).thenAnswer(i->{if(!writable){changes.clear();return false;}flush();return true;});doAnswer(i->{flush();return null;}).when(editor).apply();}
        void flush(){changes.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});changes.clear();}
        void rows(JSONObject...rows){JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);values.put("collections",a.toString());}
    }
    static JSONObject row(String phase){return object("id",41,"tokenid",TOKEN,"phase",phase,"name","Literal collection");}
    static void clearCache()throws Exception{Field f=LocalStore.class.getDeclaredField("sCollections");f.setAccessible(true);f.set(null,null);}
    @Before @After public void reset()throws Exception{clearCache();Field f=MintEngine.class.getDeclaredField("mintNode");f.setAccessible(true);f.set(null,null);f=MintEngine.class.getDeclaredField("posting");f.setAccessible(true);((Map<?,?>)f.get(null)).clear();}
    @Test public void cachedLateRowCannotUndoDurablePauseOrCompletion()throws Exception{
        Prefs p=new Prefs();p.rows(row("STAMP"));JSONObject stale=LocalStore.findById(p.ctx,41);assertTrue(LocalStore.pauseForBurial(p.ctx,TOKEN));stale.put("phase","DONE");LocalStore.upsert(p.ctx,stale);assertEquals("BURY",LocalStore.findById(p.ctx,41).getString("phase"));
        JSONArray journal=new JSONArray().put(BurialEvidence.receipt(posted(coin(1)),BurialEvidence.intent(coin(1),TOKEN)));assertTrue(LocalStore.saveBurialJournal(p.ctx,TOKEN,journal));assertFalse(LocalStore.completeBurial(p.ctx,TOKEN,journal,0));assertTrue(LocalStore.completeBurial(p.ctx,TOKEN,journal,100));assertEquals(100,LocalStore.findById(p.ctx,41).getInt("buriedat"));assertEquals("BURIED",LocalStore.findById(p.ctx,41).getString("phase"));assertEquals(0,LocalStore.burialJournal(p.ctx,TOKEN).length());LocalStore.upsert(p.ctx,stale);assertEquals("BURIED",LocalStore.findById(p.ctx,41).getString("phase"));
    }
    @Test public void failedPersistenceOrUnknownOutputCannotComplete()throws Exception{
        Prefs p=new Prefs();p.rows(row("DONE"));LocalStore.load(p.ctx);p.writable=false;assertFalse(LocalStore.pauseForBurial(p.ctx,TOKEN));assertEquals("DONE",LocalStore.findById(p.ctx,41).getString("phase"));
        JSONArray journal=new JSONArray().put(BurialEvidence.intent(coin(1),TOKEN));assertFalse(LocalStore.saveBurialJournal(p.ctx,TOKEN,journal));assertEquals(0,LocalStore.burialJournal(p.ctx,TOKEN).length());p.writable=true;assertTrue(LocalStore.saveBurialJournal(p.ctx,TOKEN,journal));assertFalse(LocalStore.completeBurial(p.ctx,TOKEN,journal,100));assertFalse(LocalStore.canDepart(p.ctx,TOKEN));clearCache();assertEquals(journal.toString(),LocalStore.burialJournal(p.ctx,TOKEN).toString());
    }
    @Test public void malformedRowsAndJournalsStayUntouched()throws Exception{
        for(String raw:new String[]{"bad","{}","[null]"}){Prefs p=new Prefs();p.values.put("collections",raw);rejects(()->LocalStore.pauseForBurial(p.ctx,TOKEN));assertEquals(raw,p.values.get("collections"));clearCache();}
        for(String raw:new String[]{"bad","{}","[null]","[{}]"}){Prefs p=new Prefs();p.values.put("burial:"+TOKEN,raw);rejects(()->LocalStore.burialJournal(p.ctx,TOKEN));assertEquals(raw,p.values.get("burial:"+TOKEN));}
    }
    @Test public void liveTickBlocksBurialUntilItsWholeReadChainEnds()throws Exception{
        Prefs p=new Prefs();p.rows(row("DONE"));NodeApi node=mock(NodeApi.class);List<NodeApi.Cb> replies=new ArrayList<>();doAnswer(i->{replies.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        MintEngine.tick(p.ctx,node,m->{});assertFalse(MintEngine.pauseForBurial(p.ctx,TOKEN));MintEngine.tick(p.ctx,node,m->{});assertEquals(1,replies.size());replies.remove(0).onResult(object("status",true,"response",object("block",100)));assertTrue(MintEngine.pauseForBurial(p.ctx,TOKEN));assertEquals("BURY",LocalStore.findById(p.ctx,41).getString("phase"));
    }
    @Test public void livePostBlocksBurialAndClosedOwnerCannotHoldTheLock()throws Exception{
        Prefs p=new Prefs();p.rows(row("DONE"));NodeApi node=mock(NodeApi.class);MintEngine.postTxnWithReceipt(node,"fixture",new ArrayList<>(),r->{},e->{});assertFalse(MintEngine.pauseForBurial(p.ctx,TOKEN));when(node.isClosed()).thenReturn(true);assertTrue(MintEngine.pauseForBurial(p.ctx,TOKEN));
    }
    @Test public void oldClosedTickCannotUnlockANewerOwnersRead()throws Exception{
        Prefs p=new Prefs();p.rows(row("DONE"));NodeApi old=mock(NodeApi.class),fresh=mock(NodeApi.class);List<NodeApi.Cb> first=new ArrayList<>(),second=new ArrayList<>();
        doAnswer(i->{first.add(i.getArgument(1));return null;}).when(old).cmd(anyString(),any(NodeApi.Cb.class));
        doAnswer(i->{second.add(i.getArgument(1));return null;}).when(fresh).cmd(anyString(),any(NodeApi.Cb.class));
        MintEngine.tick(p.ctx,old,m->{});when(old.isClosed()).thenReturn(true);MintEngine.tick(p.ctx,fresh,m->{});
        first.get(0).onError("late timeout");assertFalse(MintEngine.pauseForBurial(p.ctx,TOKEN));
        second.get(0).onResult(object("status",true,"response",object("block",100)));assertTrue(MintEngine.pauseForBurial(p.ctx,TOKEN));
    }

}
