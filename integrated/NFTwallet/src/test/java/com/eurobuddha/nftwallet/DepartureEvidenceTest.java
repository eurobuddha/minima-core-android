package com.eurobuddha.nftwallet;
import android.os.Handler;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.*;

public class DepartureEvidenceTest {
    static final String DEST="0x"+"34".repeat(32), MX="Mx"+"Ab12".repeat(12);
    static JSONObject transferPost(JSONObject input,String destination)throws Exception{
        JSONObject reply=posted(input);txn(reply).getJSONArray("outputs").getJSONObject(0).put("address",destination).put("miniaddress",MX);return reply;
    }
    static class Watch implements AutoCloseable {
        final List<Runnable> polls=new ArrayList<>();final MainActivity activity=mock(MainActivity.class);final NodeApi node=mock(NodeApi.class);final Sheet.Progress progress=mock(Sheet.Progress.class);
        NodeApi.Cb callback;String command;
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{polls.add(i.getArgument(0));return true;}));
        Watch(JSONObject receipt)throws Exception{
            when(activity.node()).thenReturn(node);doAnswer(i->{command=i.getArgument(0);callback=i.getArgument(1);return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
            Method m=StateNftActions.class.getDeclaredMethod("watchDeparture",MainActivity.class,String.class,JSONObject.class,Sheet.Progress.class,CharSequence.class);m.setAccessible(true);m.invoke(null,activity,TOKEN,receipt,progress,"Confirmed fixture");
        }
        void poll(){assertFalse(polls.isEmpty());polls.remove(0).run();}
        public void close(){handlers.close();}
    }
    @Test public void receiptBindsExactMxOrHexDestinationAndCanonicalChainAddress()throws Exception{
        JSONObject c=coin(1),intent=BurialEvidence.intent(c,TOKEN),reply=transferPost(c,DEST);
        JSONObject receipt=BurialEvidence.receipt(reply,intent,MX);assertEquals(DEST,receipt.getString("address"));
        assertEquals(receipt.toString(),BurialEvidence.receipt(reply,intent,DEST).toString());
        rejects(()->BurialEvidence.receipt(reply,intent,MX.toLowerCase()));
        rejects(()->BurialEvidence.receipt(reply,intent,ADDRESS));
        rejects(()->BurialEvidence.receipt(reply,intent,"Mx"+"a".repeat(48)));
        JSONObject chain=copy(output(c)).put("address",DEST);
        assertTrue(BurialEvidence.confirmed(receipt,chain,receipt.getString("address")));
        assertFalse(BurialEvidence.confirmed(receipt,chain,StateNft.GRAVEYARD));
    }
    @Test public void absentInputOrMalformedOutputCannotConfirmButExactOutputCan()throws Exception{
        JSONObject c=coin(1),receipt=BurialEvidence.receipt(transferPost(c,DEST),BurialEvidence.intent(c,TOKEN),DEST);
        try(Watch w=new Watch(receipt)){
            for(JSONObject bad:new JSONObject[]{object("status",true),list(new JSONArray()),list(new JSONArray().put(JSONObject.NULL)),list(new JSONArray().put(object())),list(new JSONArray().put(output(c))),list(new JSONArray().put(copy(output(c)).put("address",DEST).put("state",object("0","9")))),object("status",false,"response",new JSONArray())}){
                w.poll();assertEquals("coins coinid:"+receipt.getString("output"),w.command);w.callback.onResult(bad);verify(w.progress,never()).finish();verify(w.activity,never()).reload();assertEquals(1,w.polls.size());
            }
            w.poll();w.callback.onResult(list(new JSONArray().put(copy(output(c)).put("address",DEST))));verify(w.progress).text("Confirmed fixture");verify(w.progress).finish();verify(w.activity).reload();assertTrue(w.polls.isEmpty());
        }
    }
    @Test public void errorsAndSpentOutputRemainUnconfirmedAndPollingIsBounded()throws Exception{
        JSONObject c=coin(1),receipt=BurialEvidence.receipt(posted(c),BurialEvidence.intent(c,TOKEN));
        try(Watch w=new Watch(receipt)){
            for(int i=0;i<20;i++){w.poll();if(i%2==0)w.callback.onError("Unavailable");else w.callback.onResult(list(new JSONArray().put(copy(output(c)).put("spent",true))));}
            assertTrue(w.polls.isEmpty());verify(w.progress,never()).text("Confirmed fixture");verify(w.progress).finish();verify(w.activity,never()).reload();
        }
        try(Watch w=new Watch(receipt)){w.poll();w.callback.onError(NodeApi.ERR_TOO_LONG);assertTrue(w.polls.isEmpty());verify(w.progress,never()).text("Confirmed fixture");}
    }
    @Test public void destroyedHostAndUnsafeReceiptCannotDispatchOrFinish()throws Exception{
        JSONObject c=coin(1),receipt=BurialEvidence.receipt(posted(c),BurialEvidence.intent(c,TOKEN));
        try(Watch w=new Watch(receipt)){when(w.activity.isDestroyed()).thenReturn(true);w.poll();verifyNoInteractions(w.node);verify(w.progress,never()).finish();}
        try(Watch w=new Watch(receipt)){w.poll();when(w.activity.isDestroyed()).thenReturn(true);w.callback.onResult(list(new JSONArray().put(output(c))));verify(w.progress,never()).finish();assertTrue(w.polls.isEmpty());}
        try(Watch w=new Watch(copy(receipt).put("output","0x12;send"))){assertTrue(w.polls.isEmpty());verifyNoInteractions(w.node);verify(w.progress,never()).text("Confirmed fixture");}
    }
}
