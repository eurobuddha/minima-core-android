package com.eurobuddha.merchinbox;
import android.os.Handler;
import com.eurobuddha.merchinbox.integratedcomms.NodeApi;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.merchinbox.DeliveryEvidenceTest.*;

/** Actual delivery controller, real journal, synthetic node replies; no device or signing. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk=33,manifest=org.robolectric.annotation.Config.NONE)
public class InboxDeliveryFlowTest extends InboxDeliveryFixture {
    NodeApi node;Handler handler;ArrayDeque<Runnable> io,ui,delayed;List<String> commands;
    DeliveryFlow.Listener listener;boolean active;JSONObject coinReply,postReply,historyReply,chainReply;boolean postError;MockedStatic<CmdChain> chains;
    @Before public void flowSetup()throws Exception {
        node=mock(NodeApi.class);handler=mock(Handler.class);listener=mock(DeliveryFlow.Listener.class);active=true;io=new ArrayDeque<>();ui=new ArrayDeque<>();delayed=new ArrayDeque<>();commands=new ArrayList<>();
        coinReply=list(new JSONArray().put(coin(1)));postReply=reply(1);historyReply=object("status",true,"response",object("txpows",new JSONArray()));chainReply=included();
        when(handler.post(any(Runnable.class))).thenAnswer(c->{ui.add(c.getArgument(0));return true;});
        when(handler.postDelayed(any(Runnable.class),anyLong())).thenAnswer(c->{delayed.add(c.getArgument(0));return true;});
        doAnswer(c->{String cmd=c.getArgument(0);NodeApi.Cb cb=c.getArgument(1);commands.add(cmd);
            if(cmd.startsWith("coins "))cb.onResult(coinReply);
            else if(cmd.startsWith("txnpost ")){assertEquals(DeliveryJournal.POSTING,phase());if(postError)cb.onError("timeout");else cb.onResult(postReply);}
            else if(cmd.startsWith("txpow onchain:"))cb.onResult(chainReply);
            else if(cmd.startsWith("history "))cb.onResult(historyReply);
            else if(cmd.startsWith("txndelete "))cb.onResult(object("status",true));
            else fail("Unexpected node command: "+cmd);return null;
        }).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        chains=mockStatic(CmdChain.class);
        chains.when(()->CmdChain.run(eq(node),anyList(),anyString(),any(CmdChain.Done.class))).thenAnswer(c->{
            List<String> cmds=c.getArgument(1);assertTrue(cmds.stream().anyMatch(x->x.startsWith("txnsign ")));assertFalse(cmds.stream().anyMatch(x->x.startsWith("txnpost ")));
            assertEquals(DeliveryJournal.PREPARED,phase());CmdChain.Done done=c.getArgument(3);done.ok(object("status",true));return null;
        });
    }
    @After public void flowClose(){chains.close();}
    void drain(){int n=0;while(!io.isEmpty()||!ui.isEmpty()){assertTrue("callback loop",n++<2000);if(!io.isEmpty())io.remove().run();if(!ui.isEmpty())ui.remove().run();}}
    DeliveryFlow start(){DeliveryFlow f=new DeliveryFlow(node,journal,(Executor)io::add,handler,()->active,listener);f.start(order,KEY,line);drain();return f;}
    long posts(){return commands.stream().filter(x->x.startsWith("txnpost ")).count();}
    @Test public void sendsOnceThenConfirmsExactSavedReceiptAtRequiredDepth()throws Exception {
        start();assertEquals(1,posts());assertTrue(DeliveryJournal.complete(journal.load(KEY,order,line)));verify(listener).posted();verify(listener).confirmed();verify(listener,never()).unconfirmed();
        assertFalse(commands.stream().anyMatch(x->x.startsWith("history ")));
    }
    @Test public void absentInputAndLegacyMarkerNeverConfirmOrResend() {
        db.setMeta("nftposted:"+KEY,"old-marker");coinReply=list(new JSONArray());start();assertEquals(0,posts());verify(listener).unconfirmed();verify(listener,never()).confirmed();chains.verifyNoInteractions();
    }
    @Test public void malformedPostIsRecoveredAfterRestartWithoutSigningOrSendingAgain()throws Exception {
        postReply=object("status",true,"pending",true);start();assertEquals(1,posts());assertEquals(DeliveryJournal.UNKNOWN,phase());verify(listener,never()).confirmed();
        active=false;journal.close();journal=new DeliveryJournal(db);active=true;listener=mock(DeliveryFlow.Listener.class);commands.clear();chains.clearInvocations();
        coinReply=list(new JSONArray());historyReply=object("status",true,"response",object("txpows",new JSONArray().put(reply(1).getJSONObject("response"))));start();
        assertEquals(0,posts());chains.verifyNoInteractions();verify(listener).confirmed();assertEquals(DeliveryJournal.CONFIRMED,phase());
    }
    @Test public void wrongRecipientHistoryAndShallowInclusionRemainUnconfirmed()throws Exception {
        postError=true;JSONObject wrong=reply(1);txn(wrong).getJSONArray("outputs").getJSONObject(0).put("address",ADDRESS);
        historyReply=object("status",true,"response",object("txpows",new JSONArray().put(wrong.getJSONObject("response"))));start();assertEquals(DeliveryJournal.UNKNOWN,phase());verify(listener,never()).confirmed();
        historyReply=object("status",true,"response",object("txpows",new JSONArray().put(reply(1).getJSONObject("response"))));chainReply=object("status",true,"response",object("found",true,"blockid","0x11","block","100","tip","101","confirmations","1"));
        delayed.remove().run();drain();assertEquals(DeliveryJournal.UNKNOWN,phase());assertEquals(1,posts());verify(listener,never()).confirmed();
    }
    @Test public void persistenceFailureBeforePostingPreventsThePost() {
        db.getWritableDatabase().execSQL("CREATE TRIGGER deny_update BEFORE UPDATE ON nft_delivery BEGIN SELECT RAISE(ABORT,'fixture'); END");start();assertEquals(0,posts());assertEquals(DeliveryJournal.PREPARED,phase());verify(listener).unconfirmed();
    }
    @Test public void closedScreenCannotPostFromQueuedPreparationCallback() {
        DeliveryFlow f=new DeliveryFlow(node,journal,(Executor)io::add,handler,()->active,listener);f.start(order,KEY,line);
        io.remove().run();active=false;journal.close();drain();assertTrue(commands.isEmpty());verifyNoInteractions(listener);
    }
    @Test public void unknownHistoryScanIsBoundedAndStopsAfterTwentyPolls() {
        postError=true;historyReply=object("status",true,"response",object("txpows",new JSONArray().put(object()).put(object()).put(object()).put(object())));start();
        assertEquals(4,commands.stream().filter(x->x.startsWith("history ")).count());
        for(int i=0;i<20;i++){assertEquals(1,delayed.size());delayed.remove().run();drain();}
        assertEquals(1,posts());assertEquals(80,commands.stream().filter(x->x.startsWith("history ")).count());assertTrue(delayed.isEmpty());verify(listener).unconfirmed();verify(listener,never()).confirmed();
    }
    @Test public void malformedCoinSnapshotsCannotReserveInputsOrStartTheCommandChain()throws Exception {
        for(JSONObject bad:InboxCoinEvidenceTest.invalidReplies()) {
            coinReply=bad;listener=mock(DeliveryFlow.Listener.class);commands.clear();start();
            assertNull(journal.load(KEY,order,line));assertEquals(0,posts());verify(listener).unconfirmed();verify(listener,never()).confirmed();
        }
        chains.verifyNoInteractions();
    }
    @Test public void lostPostCallbackRemainsReservedAndRecoversAfterTheScreenCloses()throws Exception {
        final NodeApi.Cb[] pending={null};
        doAnswer(c->{commands.add(c.getArgument(0));pending[0]=c.getArgument(1);return null;})
            .when(node).cmd(argThat(command->command.startsWith("txnpost ")),any(NodeApi.Cb.class));
        start();assertEquals(DeliveryJournal.POSTING,phase());assertNotNull(pending[0]);
        active=false;journal.close();pending[0].onResult(reply(1));drain();assertEquals(DeliveryJournal.POSTING,phase());verify(listener,never()).confirmed();
        journal=new DeliveryJournal(db);active=true;listener=mock(DeliveryFlow.Listener.class);commands.clear();chains.clearInvocations();
        coinReply=list(new JSONArray());historyReply=object("status",true,"response",object("txpows",new JSONArray().put(reply(1).getJSONObject("response"))));
        start();assertEquals(0,posts());chains.verifyNoInteractions();verify(listener).confirmed();
    }

    @Test public void postedPreMiningIdRecoversFromTheMinedHistoryHeader()throws Exception {
        String returned=postReply.getJSONObject("response").getString("txpowid");
        JSONObject mined=reply(1).getJSONObject("response");mined.put("txpowid","0x"+"aa".repeat(32));
        historyReply=object("status",true,"response",object("txpows",new JSONArray().put(mined)));
        doAnswer(c->{commands.add(c.getArgument(0));((NodeApi.Cb)c.getArgument(1)).onResult(object("status",true,"response",object("found",false)));return null;})
            .when(node).cmd(eq("txpow onchain:"+returned),any(NodeApi.Cb.class));
        start();assertEquals(1,posts());verify(listener).confirmed();assertEquals(DeliveryJournal.CONFIRMED,phase());
        assertEquals(mined.getString("txpowid"),DeliveryJournal.entry(journal.load(KEY,order,line),coin(1).optString("coinid")).getString("txpowid"));
    }
}
