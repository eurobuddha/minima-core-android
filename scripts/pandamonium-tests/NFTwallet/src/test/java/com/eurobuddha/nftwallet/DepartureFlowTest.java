package com.eurobuddha.nftwallet;
import android.os.Handler;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.*;
import static com.eurobuddha.nftwallet.DepartureEvidenceTest.*;

public class DepartureFlowTest {
    static class Flow implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class);final NodeApi node=mock(NodeApi.class);final Sheet.Progress progress=mock(Sheet.Progress.class);
        final List<String> commands=new ArrayList<>();final List<Runnable> polls=new ArrayList<>();
        boolean allowed=true;JSONObject postedReply=posted(coin(1));
        final MockedStatic<LocalStore> store=mockStatic(LocalStore.class);final MockedStatic<Sheet> sheets=mockStatic(Sheet.class);
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{polls.add(i.getArgument(0));return true;}));
        Flow(){
            when(act.node()).thenReturn(node);store.when(()->LocalStore.canDepart(act,TOKEN)).thenAnswer(i->allowed);
            sheets.when(()->Sheet.progress(eq(act),any(CharSequence.class),any(CharSequence.class))).thenReturn(progress);
            doAnswer(i->{String cmd=i.getArgument(0);commands.add(cmd);NodeApi.Cb cb=i.getArgument(1);
                if(cmd.startsWith("txncheck "))cb.onResult(object("status",true,"response",object("coins",new JSONArray().put(object("difference","0")))));
                else if(cmd.startsWith("txnpost "))cb.onResult(postedReply);
                else if(cmd.startsWith("txn"))cb.onResult(object("status",true));
                else assertTrue(cmd.startsWith("coins coinid:"));return null;
            }).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        }
        void run(boolean bury,JSONObject coin){try{
            Method m=StateNftActions.class.getDeclaredMethod(bury?"runBury":"runTransfer",bury?new Class<?>[]{MainActivity.class,String.class,CharSequence.class,JSONObject.class}:new Class<?>[]{MainActivity.class,String.class,CharSequence.class,JSONObject.class,String.class});m.setAccessible(true);
            if(bury)m.invoke(null,act,TOKEN,"Public item",coin);else m.invoke(null,act,TOKEN,"Public item",coin,MX);
        }catch(Exception e){throw new AssertionError(e);}}
        public void close(){handlers.close();sheets.close();store.close();}
    }
    @Test public void unfinishedMintBlocksBothSingleActionsAndCollectionTransferBeforeNodeCommands()throws Exception{
        for(boolean bury:new boolean[]{true,false})try(Flow f=new Flow()){f.allowed=false;f.run(bury,coin(1));assertTrue(f.commands.isEmpty());assertTrue(f.polls.isEmpty());}
        try(Flow f=new Flow()){f.allowed=false;Method m=StateNftActions.class.getDeclaredMethod("gatherAndTransfer",MainActivity.class,String.class,String.class,String.class,Runnable.class);m.setAccessible(true);m.invoke(null,f.act,TOKEN,"Public collection",DEST,null);assertTrue(f.commands.isEmpty());}
    }
    @Test public void invalidChosenCoinCannotReachAnyTransactionCommand()throws Exception{
        for(JSONObject c:new JSONObject[]{copy(coin(1)).put("state",object("0","0")),copy(coin(1)).put("state",object("0","7 burn:9")),copy(coin(1)).put("tokenamount","1 burn:9"),copy(coin(1)).put("tokenid","0xff"),copy(coin(1)).put("coinid","0x12;send")})
            for(boolean bury:new boolean[]{true,false})try(Flow f=new Flow()){f.run(bury,c);assertTrue(f.commands.isEmpty());assertTrue(f.polls.isEmpty());}
    }
    @Test public void bothActionsUseExistingBuildersAndOnlyPollAfterAnExactPostReceipt()throws Exception{
        for(boolean bury:new boolean[]{true,false})try(Flow f=new Flow()){
            f.postedReply=bury?posted(coin(1)):transferPost(coin(1),DEST);f.run(bury,coin(1));
            assertEquals(1,f.commands.stream().filter(c->c.startsWith("txnpost ")).count());assertEquals(1,f.polls.size());
            int check=-1,sign=-1;for(int i=0;i<f.commands.size();i++){if(f.commands.get(i).startsWith("txncheck "))check=i;if(f.commands.get(i).startsWith("txnsign "))sign=i;}assertTrue(check>=0&&sign>check);
            assertTrue(f.commands.stream().anyMatch(c->c.contains("storestate:true")));f.polls.remove(0).run();assertEquals("coins coinid:"+output(coin(1)).getString("coinid"),f.commands.get(f.commands.size()-1));
        }
    }
    @Test public void bareAcknowledgementOrWrongRecipientCannotStartASuccessWatcher()throws Exception{
        for(JSONObject reply:new JSONObject[]{object("status",true),transferPost(coin(1),ADDRESS)})try(Flow f=new Flow()){
            // A different Mx value is a different recipient even when the output otherwise matches.
            if(reply.optJSONObject("response")!=null)txn(reply).getJSONArray("outputs").getJSONObject(0).put("miniaddress","Mx"+"c".repeat(48));
            f.postedReply=reply;f.run(false,coin(1));assertTrue(f.polls.isEmpty());verify(f.act,never()).reload();
        }
    }
    @Test public void realStoreAllowsFinishedOrReceivedCollectionsAndRejectsUnfinishedOrCorruptState()throws Exception{
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();assertTrue(LocalStore.canDepart(p.ctx,TOKEN));
        for(String phase:new String[]{"CREATE","MOVE","SPLIT","STAMP","NEEDIMAGES","BURY","UNKNOWN",""}){p.rows(BurialLifecycleTest.row(1,TOKEN,phase));String before=p.values.get("collections");assertFalse(LocalStore.canDepart(p.ctx,TOKEN));assertEquals(before,p.values.get("collections"));}
        for(String phase:new String[]{"DONE","BURIED"}){p.rows(BurialLifecycleTest.row(1,TOKEN,phase),BurialLifecycleTest.row(2,"0xbb","STAMP"));assertTrue(LocalStore.canDepart(p.ctx,TOKEN));}
        for(String raw:new String[]{"broken","{}","[null]"}){p.values.put("collections",raw);assertFalse(LocalStore.canDepart(p.ctx,TOKEN));assertEquals(raw,p.values.get("collections"));}
    }
}
