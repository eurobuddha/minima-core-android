package com.eurobuddha.statenft;

import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.statenft.BurialEvidenceTest.*;

public class AtelierPostReceiptTest {
    static class Flow {
        final NodeApi node=mock(NodeApi.class);
        final List<String> commands=new ArrayList<>();
        JSONObject check=object("status",true,"response",object("coins",new JSONArray().put(object("difference","0"))));
        JSONObject post=posted(coin(1)), received;
        String failure;
        boolean cleanupFails, postFails;
        Flow(){doAnswer(i->{String command=i.getArgument(0);commands.add(command);NodeApi.Cb cb=i.getArgument(1);
            if(command.startsWith("txncheck"))cb.onResult(check);
            else if(command.startsWith("txnpost")){if(postFails)cb.onError("unknown outcome");else cb.onResult(post);}
            else if(command.startsWith("txndelete")&&cleanupFails)cb.onError("nothing to delete");
            else cb.onResult(object("status",true));return null;
        }).when(node).cmd(anyString(),any(NodeApi.Cb.class));}
        void run(){MintEngine.postTxnWithReceipt(node,"fixture",StateNft.transferCommands("fixture",TOKEN,coin(1),ADDRESS),r->received=r,e->failure=e);}
    }
    @Test public void returnsActualPostReceiptAfterCleanupAndChecksBeforeSigning(){Flow f=new Flow();f.run();assertSame(f.post,f.received);assertNull(f.failure);assertTrue(f.commands.indexOf("txncheck id:fixture")<f.commands.indexOf("txnsign id:fixture publickey:auto"));assertEquals("txndelete id:fixture",f.commands.get(f.commands.size()-1));assertEquals(1,f.commands.stream().filter(s->s.startsWith("txnpost")).count());}
    @Test public void cleanupFailureDoesNotDiscardAcceptedReceipt(){Flow f=new Flow();f.cleanupFails=true;f.run();assertSame(f.post,f.received);assertNull(f.failure);}
    @Test public void malformedOrUnbalancedCheckCannotSignOrPost(){for(JSONArray coins:new JSONArray[]{new JSONArray(),new JSONArray().put(JSONObject.NULL),new JSONArray().put(object()),new JSONArray().put(object("difference","1")),new JSONArray().put(object("difference","0")).put(JSONObject.NULL)}){Flow f=new Flow();f.check=object("status",true,"response",object("coins",coins));f.run();assertNull(f.received);assertNotNull(f.failure);assertFalse(f.commands.stream().anyMatch(s->s.startsWith("txnsign")||s.startsWith("txnpost")));}}
    @Test public void unknownPostOutcomeNeverInvokesSuccess(){Flow f=new Flow();f.postFails=true;f.run();assertNull(f.received);assertEquals("unknown outcome",f.failure);assertEquals("txndelete id:fixture",f.commands.get(f.commands.size()-1));}
    @Test public void existingRunnableCallersStillReceiveOneSuccess(){Flow f=new Flow();int[] calls={0};MintEngine.postTxn(f.node,"fixture",StateNft.transferCommands("fixture",TOKEN,coin(1),ADDRESS),()->calls[0]++,e->fail(e));assertEquals(1,calls[0]);}
    @Test public void sharedReplayGateRejectsUnsafeObjectStateAndPreservesValidBurialPorts()throws Exception{
        JSONObject c=coin(1);assertTrue(StateNft.replayableState(c));
        for(Object state:new Object[]{object("0","7 burn:9"),object("0","7","1;send","[AA==]"),object("0","7","256","1"),new JSONArray().put(object("port",0,"data","7")).put(object("port","00","data","7")),JSONObject.NULL})assertFalse(StateNft.replayableState(copy(c).put("state",state)));
        List<String> cmds=StateNft.buryCommands("fixture",TOKEN,"",c,true);
        assertTrue(cmds.contains("txnstate id:fixture port:0 value:7"));assertTrue(cmds.contains("txnstate id:fixture port:1 value:[QUJDRA==]"));
        assertTrue(cmds.stream().anyMatch(x->x.contains("amount:1.0000 address:"+StateNft.GRAVEYARD)));
    }

}
