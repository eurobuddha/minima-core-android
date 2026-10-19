package com.eurobuddha.utxo;

import android.os.Handler;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses NFT TxnBuilderFlowTest's real wrapper / fake transport. No node or signing occurs. */
public class UncertainWriteTest {
    static JSONObject object(Object... pairs) {
        JSONObject result=new JSONObject();
        try { for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]); }
        catch(JSONException failure){throw new AssertionError(failure);}return result;
    }
    static class Flow implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class);
        final HistoryDb history=mock(HistoryDb.class);
        final TxnBuilder.Done done=mock(TxnBuilder.Done.class);
        final List<String> commands=new ArrayList<>();
        final List<Runnable> timeouts=new ArrayList<>();
        MinimaAPIListener pending;
        JSONObject reply;
        boolean hold, throwsTransport;
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->{
            when(h.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{timeouts.add(i.getArgument(0));return true;});
            doAnswer(i->{timeouts.remove(i.getArgument(0));return null;}).when(h).removeCallbacks(any());
        });
        final MockedConstruction<DirectNodeApi> clients=mockConstruction(DirectNodeApi.class,(api,c)->{
            doAnswer(i->{String command=i.getArgument(0);commands.add(command);
                boolean write=command.startsWith("txnsign ")||command.startsWith("consolidate ");
                if(write){pending=i.getArgument(1);if(throwsTransport)throw new IllegalStateException("public fixture");if(hold)return null;}
                ((MinimaAPIListener)i.getArgument(1)).response(write?reply:object("status",true));return null;
            }).when(api).Command(anyString(),any());
        });
        final NodeApi node=new NodeApi(act,null);
        Flow(){when(act.node()).thenReturn(node);when(act.history()).thenReturn(history);when(history.insertPosting(any())).thenReturn(true);when(history.prepareAttempt(anyString(),anyString(),any())).thenReturn(true);when(history.markAttempt(anyString(),anyString(),any())).thenReturn(true);}
        void sign(){Coin c=new Coin();c.coinid="0x"+"11".repeat(32);c.tokenid="0x00";c.amount=c.rawAmount="10";c.confirmed=c.sendable=true;
            new TxnBuilder(act,Collections.singletonList(c),Collections.singletonList(new TxnBuilder.Out("0x"+"22".repeat(32),"10")),"0x00",done).record("public-attempt").run();}
        void consolidate()throws Exception {WalletTools tools=new WalletTools(act,mock(WalletTools.Status.class));
            java.lang.reflect.Method method=WalletTools.class.getDeclaredMethod("autoConsolidate",String.class);method.setAccessible(true);method.invoke(tools,"0x00");}
        public void close(){node.onDestroy();clients.close();handlers.close();}
    }
    @Test public void missingMalformedAndOversizedSignRepliesNeverDeleteOrRetryAnExecutingPost(){
        for(JSONObject bad:new JSONObject[]{null,object(),object("status","true"),object("transporterror","Invalid node result. Outcome remains unknown."),object("status",false,"response","Result too long!"),object("status",false,"response","MAX(256000)")})try(Flow f=new Flow()){
            f.reply=bad;f.sign();verify(f.done).onUnknown(anyString());verify(f.done,never()).onFailed(anyString());verify(f.done,never()).onPosted(any(),any());
            assertEquals(1,f.commands.stream().filter(c->c.startsWith("txnsign ")).count());assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txndelete ")));
        }
    }
    @Test public void timeoutSuppressesTheLateReplyAndDoesNotScheduleDeletion(){try(Flow f=new Flow()){
        f.hold=true;f.sign();assertEquals(1,f.timeouts.size());f.timeouts.get(0).run();f.pending.response(object("status",true));
        verify(f.done,times(1)).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onPosted(any(),any());verify(f.done,never()).onFailed(anyString());
        assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txndelete ")));
    }}
    @Test public void explicitRejectionsRemainFailures(){for(JSONObject rejected:new JSONObject[]{object("status",false,"error","Rejected"),object("enabled",false),object("transporterror","The embedded node is not running. No command was sent.")})try(Flow f=new Flow()){
        f.reply=rejected;f.sign();verify(f.done).onFailed(anyString());verify(f.done,never()).onUnknown(anyString());verify(f.done,never()).onPosted(any(),any());
    }}
    @Test public void thrownTransportIsUnknownAndCancelsItsTimeout(){try(Flow f=new Flow()){
        f.throwsTransport=true;f.sign();verify(f.done).onUnknown(anyString());assertTrue(f.timeouts.isEmpty());verify(f.done,never()).onFailed(anyString());assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txndelete ")));
    }}
    @Test public void uncertainUnsignedConsolidationCannotSignOrPost()throws Exception {
        for(int mode=0;mode<4;mode++)try(Flow f=new Flow()){
            if(mode==0)f.reply=object("transporterror","Invalid node result. Outcome remains unknown.");
            if(mode==1)f.reply=object("status",false,"response","Result too long!");
            if(mode==2)f.hold=true;
            if(mode==3)f.throwsTransport=true;
            f.consolidate();if(f.hold)f.timeouts.get(0).run();
            verify(f.history).update(anyString(),eq(HistoryDb.STATUS_ERROR),isNull(),eq(TransactionEvidence.NOT_SENT));
            verify(f.history,never()).update(anyString(),eq(HistoryDb.STATUS_UNKNOWN),any(),any());
            verify(f.history,never()).update(anyString(),eq(HistoryDb.STATUS_POSTED),any(),any());
            assertEquals(1,f.commands.size());assertTrue(f.commands.get(0).endsWith("dryrun:true"));
        }
    }
    @Test public void consolidationDefiniteRejectionRemainsAnError()throws Exception {try(Flow f=new Flow()){
        f.reply=object("status",false,"error","Nothing to consolidate");f.consolidate();
        verify(f.history).update(anyString(),eq(HistoryDb.STATUS_ERROR),isNull(),eq("Nothing to consolidate"));verify(f.history,never()).update(anyString(),eq(HistoryDb.STATUS_UNKNOWN),any(),any());
    }}
    @Test public void readOnlyOverflowStillReachesTheExistingShrinkAndRetryCallback(){try(Flow f=new Flow()){
        // The default unknown callback preserves the read-only caller's established error path.
        NodeApi.Cb callback=new NodeApi.Cb(){public void onResult(JSONObject result){fail("overflow is not success");}public void onError(String message){assertEquals(NodeApi.ERR_TOO_LONG,message);}};
        f.reply=object("status",false,"response","Result too long!");f.node.cmd("consolidate fixture:read-callback",callback);
    }}
    @Test public void destructionDropsAnAlreadyDispatchedWritesLateReply(){try(Flow f=new Flow()){
        f.hold=true;f.sign();f.node.onDestroy();f.pending.response(object("status",true));verifyNoInteractions(f.done);assertTrue(f.timeouts.isEmpty());
    }}
}
