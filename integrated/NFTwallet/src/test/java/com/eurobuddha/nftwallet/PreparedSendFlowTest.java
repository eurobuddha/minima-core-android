package com.eurobuddha.nftwallet;

import android.os.Handler;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;

/** Reuses TxnBuilderFlowTest's real NodeApi / mocked transport fixture. No node or signature runs. */
public class PreparedSendFlowTest {
    static class Flow implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class);final HistoryDb history=mock(HistoryDb.class);final TxnBuilder.Done done=mock(TxnBuilder.Done.class);
        final List<String> commands=new ArrayList<>();final List<Runnable> timeouts=new ArrayList<>();
        JSONObject quote,postReply,signedReply;String hold="",reject="",signedPath="/public/node/signedtransaction-123.txn";
        JSONObject rejection=object("status",false,"error","Public fixture rejected");MinimaAPIListener late;
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->{
            when(h.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{timeouts.add(i.getArgument(0));return true;});
            doAnswer(i->{timeouts.remove(i.getArgument(0));return null;}).when(h).removeCallbacks(any(Runnable.class));
        });
        final MockedConstruction<DirectNodeApi> clients=mockConstruction(DirectNodeApi.class,(api,c)->{
            doAnswer(i->{String command=i.getArgument(0);commands.add(command);
                if(!hold.isEmpty()&&command.startsWith(hold)){late=i.getArgument(1);return null;}
                JSONObject reply=object("status",true);
                if(!reject.isEmpty()&&command.startsWith(reject))reply=rejection;
                else if(command.startsWith("sendview file:nft_send_"))reply=quote;
                else if(command.startsWith("sendview file:signedtransaction-"))reply=signedReply==null?quote:signedReply;
                else if(command.startsWith("sendsign "))reply=object("status",true,"response",object("txpow",signedPath));
                else if(command.startsWith("sendpost "))reply=postReply==null?quote:postReply;
                else if(command.startsWith("scripts address:"))reply=object("status",true,"response",object("address",command.substring("scripts address:".length()),"default",true,"simple",true,"publickey",id('e')));
                ((MinimaAPIListener)i.getArgument(1)).response(reply);return null;
            }).when(api).Command(anyString(),any());
        });
        final NodeApi node=new NodeApi(act,null);
        Flow(){when(act.node()).thenReturn(node);when(act.history()).thenReturn(history);when(history.insertPosting(any())).thenReturn(true);when(history.prepareAttempt(anyString(),anyString(),any())).thenReturn(true);when(history.markAttempt(anyString(),anyString(),any())).thenReturn(true);}
        PreparedSend run(String token,String burn)throws Exception {if(quote==null)quote=PreparedSendEvidenceTest.quote(token,PreparedSendEvidence.fee(burn),false);PreparedSend send=new PreparedSend(act,DEST,"3",burn,1,token,"Token",done);send.run();return send;}
        boolean sent(String prefix){return commands.stream().anyMatch(c->c.startsWith(prefix));}
        public void close(){node.onDestroy();clients.close();handlers.close();}
    }
    @Test public void exactNativeSelectionIsSavedBeforeSigningAndSuccessRequiresAPersistedReceipt()throws Exception {
        for(String token:new String[]{"0x00",TOKEN})for(String burn:new String[]{null,"0.25"})try(Flow f=new Flow()) {
            when(f.history.prepareAttempt(anyString(),anyString(),any())).thenAnswer(i->{assertFalse(f.sent("sendsign "));JSONObject intent=i.getArgument(2);assertEquals(!Util.isMinima(token)&&burn!=null,intent.has("companion"));return true;});
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenAnswer(i->{assertTrue(f.sent("sendsign "));assertFalse(f.sent("sendpost "));return true;});
            PreparedSend send=f.run(token,burn);send.run();verify(f.done).onPosted(eq(id('f')),anyList());verify(f.history).markAttempt(anyString(),eq(HistoryDb.POSTED),any());
            assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendsign ")).count());assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendpost ")).count());
            assertFalse(f.commands.stream().anyMatch(c->c.startsWith("send ")||c.contains("dryrun")));assertTrue(f.commands.get(0).startsWith("sendnosign address:"+DEST+" amount:3 tokenid:"+token));
        }
    }
    @Test public void failedDisplayOrReservationWritesCannotReachSigning()throws Exception {
        try(Flow f=new Flow()){when(f.history.insertPosting(any())).thenReturn(false);f.run("0x00",null);assertTrue(f.commands.isEmpty());verify(f.done).onFailed(TransactionEvidence.SAVE_FAILED);}
        try(Flow f=new Flow()){when(f.history.prepareAttempt(anyString(),anyString(),any())).thenReturn(false);f.run("0x00",null);assertFalse(f.sent("sendsign "));verify(f.done).onFailed(TransactionEvidence.RECORD_FAILED);}
    }
    @Test public void postingWriteFailureStopsBeforeBroadcastAndReceiptWriteFailureStaysUnknown()throws Exception {
        try(Flow f=new Flow()){
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenReturn(false);f.run(TOKEN,"0.25");
            verify(f.done).onFailed(TransactionEvidence.SAVE_FAILED);verify(f.done,never()).onPosted(anyString(),any());
            verify(f.history).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());assertTrue(f.sent("sendsign "));assertFalse(f.sent("sendpost "));
        }
        try(Flow f=new Flow()){
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTED),any())).thenReturn(false);f.run(TOKEN,"0.25");
            verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onFailed(anyString());assertTrue(f.sent("sendpost "));
        }
    }
    @Test public void signingTimeoutCannotPostButPostingTimeoutRetainsItsReservation()throws Exception {
        for(String stage:new String[]{"sendsign ","sendpost "})try(Flow f=new Flow()){
            f.hold=stage;f.run(TOKEN,"0.25");assertEquals(1,f.timeouts.size());f.timeouts.get(0).run();
            f.late.response(stage.startsWith("sendsign")?object("status",true,"response",object("txpow",f.signedPath)):f.quote);
            verify(f.done,never()).onPosted(anyString(),any());
            if(stage.startsWith("sendsign")) {
                verify(f.done).onFailed(TransactionEvidence.NOT_SENT);verify(f.done,never()).onUnknown(anyString());
                verify(f.history).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());
                verify(f.history,never()).markAttempt(anyString(),eq(HistoryDb.POSTING),any());
            } else {
                verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onFailed(anyString());
                verify(f.history,never()).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());
            }
            assertEquals(stage.startsWith("sendsign")?0:1,f.commands.stream().filter(c->c.startsWith("sendpost ")).count());
        }
    }
    @Test public void signedFileReadTimeoutDoesNotReachBroadcastEvenWithALateValidReply()throws Exception {
        try(Flow f=new Flow()){
            f.hold="sendview file:signedtransaction-";PreparedSend send=f.run(TOKEN,"0.25");f.timeouts.get(0).run();f.late.response(f.quote);send.run();
            verify(f.done).onFailed(TransactionEvidence.NOT_SENT);assertFalse(f.sent("sendpost "));
            assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendsign ")).count());
        }
    }
    @Test public void failedPrePostReleasePreservesReservationAndReportsUnknownWithoutBroadcast()throws Exception {
        try(Flow f=new Flow()){
            f.hold="sendsign ";when(f.history.markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any())).thenReturn(false);
            f.run(TOKEN,"0.25");f.timeouts.get(0).run();verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onFailed(anyString());assertFalse(f.sent("sendpost "));
        }
    }
    @Test public void malformedSignedPathOrChangedSignedTransactionCannotPost()throws Exception {
        for(boolean path:new boolean[]{true,false})try(Flow f=new Flow()){
            if(path)f.signedPath="signedtransaction-1.txn;send";
            else{f.signedReply=PreparedSendEvidenceTest.quote("0x00","0",false);txn(f.signedReply).put("transactionid",id('9'));}
            f.run("0x00",null);verify(f.done).onFailed(TransactionEvidence.NOT_SENT);assertFalse(f.sent("sendpost "));
        }
    }
    @Test public void partialOrLostPostedReceiptCannotReportSuccess()throws Exception {
        for(JSONObject reply:new JSONObject[]{object("status",true),object("transporterror","Node result unavailable. Outcome unknown.")})try(Flow f=new Flow()){
            f.postReply=reply;f.run("0x00",null);verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onPosted(anyString(),any());verify(f.history,never()).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());
        }
    }
    @Test public void unsignedPreparationErrorsCannotConsumeASigningCommand()throws Exception {
        for(String stage:new String[]{"sendnosign ","sendview file:nft_send_","scripts address:"})try(Flow f=new Flow()){
            f.reject=stage;f.run("0x00",null);verify(f.done).onFailed("Public fixture rejected");assertFalse(f.sent("sendsign "));assertFalse(f.sent("sendpost "));
        }
    }
    @Test public void aTrackedButNondefaultChangeAddressIsRejectedBeforeSigning()throws Exception {
        try(Flow f=new Flow()){f.reject="scripts address:";f.rejection=object("status",true,"response",object("address",CHANGE,"default",false,"simple",true,"publickey",id('e')));f.run("0x00",null);verify(f.done).onFailed(TransactionEvidence.INVALID);assertFalse(f.sent("sendsign "));}
    }
}
