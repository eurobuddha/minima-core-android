package com.eurobuddha.nftwallet;

import com.eurobuddha.minimaapi.MinimaAPIListener;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;

/** Reuses the real NodeApi transport fixture; no owner files or node are touched. */
public class PreparedFileCleanupTest {
    static final String UNSIGNED="nft_send_"+"a".repeat(32)+".txn";
    static class Flow extends PreparedSendFlowTest.Flow {
        final List<String> files=new ArrayList<>();
        JSONObject statOverride;String throwAction="";boolean holdStat;MinimaAPIListener delayedStat;String delayedName;
        Flow() {
            doAnswer(i->{
                String action=i.getArgument(0),name=i.getArgument(1);files.add(action+":"+name);
                if(action.equals(throwAction))throw new IllegalStateException("Public cleanup failure");
                MinimaAPIListener cb=i.getArgument(4);
                if(action.equals("stat")&&holdStat){delayedStat=cb;delayedName=name;return null;}
                cb.response(action.equals("stat")?(statOverride==null?stat(name):statOverride):object("status",false,"error","Public delete failure"));
                return null;
            }).when(clients.constructed().get(0)).FileCommand(anyString(),anyString(),isNull(),isNull(),any());
        }
        JSONObject stat(String name){return object("status",true,"action","stat","query",name,"exists",true,"isdir",false,"size",1234L,"base","/public/node","canonical","/public/node/"+name);}
        long deletes(){return files.stream().filter(s->s.startsWith("delete:")).count();}
    }
    @Test public void postedReceiptIsSavedBeforeOnlyItsTwoFilesAreRemoved()throws Exception {
        try(Flow f=new Flow()) {
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTED),any())).thenAnswer(i->{assertTrue(f.files.isEmpty());return true;});
            PreparedSend send=f.run(TOKEN,"0.25");send.run();
            verify(f.done).onPosted(eq(id('f')),anyList());verify(f.done,never()).onFailed(anyString());
            assertEquals(2,f.deletes());assertEquals(4,f.files.size());
            assertTrue(f.files.get(0).matches("stat:nft_send_[0-9a-f]{32}\\.txn"));
            assertEquals(f.files.get(0).replace("stat:","delete:"),f.files.get(1));
            assertEquals(Arrays.asList("stat:signedtransaction-123.txn","delete:signedtransaction-123.txn"),f.files.subList(2,4));
        }
    }
    @Test public void knownPrePostFailureRemovesOnlyAcknowledgedAndVerifiedFiles()throws Exception {
        try(Flow f=new Flow()) {
            when(f.history.prepareAttempt(anyString(),anyString(),any())).thenReturn(false);f.run("0x00",null);
            verify(f.done).onFailed(TransactionEvidence.RECORD_FAILED);assertEquals(1,f.deletes());assertFalse(f.sent("sendsign "));
        }
        try(Flow f=new Flow()) {
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenReturn(false);f.run("0x00",null);
            verify(f.history).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());assertEquals(2,f.deletes());assertFalse(f.sent("sendpost "));
        }
        try(Flow f=new Flow()) {
            f.signedReply=PreparedSendEvidenceTest.quote("0x00","0",false);txn(f.signedReply).put("transactionid",id('9'));f.run("0x00",null);
            verify(f.done).onFailed(TransactionEvidence.NOT_SENT);assertEquals(1,f.deletes());assertTrue(f.files.stream().noneMatch(s->s.contains("signedtransaction")));
        }
    }
    @Test public void unknownPreparationSigningReadsOrPostRetainFilesAndIgnoreLateReplies()throws Exception {
        for(String stage:new String[]{"sendnosign ","sendview file:nft_send_","scripts address:","sendsign ","sendview file:signedtransaction-","sendpost "})try(Flow f=new Flow()) {
            f.hold=stage;PreparedSend send=f.run("0x00",null);assertEquals(1,f.timeouts.size());f.timeouts.get(0).run();
            f.late.response(stage.startsWith("sendsign")?object("status",true,"response",object("txpow",f.signedPath)):f.quote);send.run();
            assertTrue(stage,f.files.isEmpty());verify(f.done,never()).onPosted(anyString(),any());
            if(stage.startsWith("sendpost"))verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);
            else verify(f.done).onFailed(anyString());
        }
    }
    @Test public void lostAcknowledgementsKeepFilesAndNeverChangeTheFinancialOutcome()throws Exception {
        for(boolean posted:new boolean[]{true,false})try(Flow f=new Flow()) {
            if(posted)when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTED),any())).thenReturn(false);
            else {when(f.history.markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any())).thenReturn(false);f.reject="sendsign ";}
            f.run("0x00",null);verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);assertTrue(f.files.isEmpty());
        }
        try(Flow f=new Flow()){f.reject="sendnosign ";f.run("0x00",null);assertTrue(f.files.isEmpty());verify(f.done).onFailed("Public fixture rejected");}
    }
    @Test public void cleanupErrorsDoNotTurnASentTransferIntoFailureOrRepeatIt()throws Exception {
        for(String action:new String[]{"stat","delete",""})try(Flow f=new Flow()) {
            f.throwAction=action;PreparedSend send=f.run("0x00",null);send.run();
            verify(f.done).onPosted(eq(id('f')),anyList());verify(f.done,never()).onFailed(anyString());verify(f.done,never()).onUnknown(anyString());
            assertEquals(1,f.commands.stream().filter(c->c.startsWith("sendpost ")).count());
        }
    }
    @Test public void directoriesAliasesIncompleteAndFailedStatsCannotAuthorizeDeletion()throws Exception {
        for(String field:new String[]{"status","action","query","exists","isdir","size","base","canonical"})try(Flow f=new Flow()) {
            JSONObject bad=f.stat(UNSIGNED);bad.remove(field);f.statOverride=bad;f.node.discardPreparedFile(UNSIGNED);assertEquals(field,0,f.deletes());
        }
        for(int mode=0;mode<7;mode++)try(Flow f=new Flow()) {
            JSONObject bad=f.stat(UNSIGNED);
            if(mode==0)bad.put("isdir",true);if(mode==1)bad.put("exists",false);if(mode==2)bad.put("status","true");
            if(mode==3)bad.put("canonical","/public/node/backup.bak");if(mode==4)bad.put("canonical","/public/node-sibling/"+UNSIGNED);
            if(mode==5)bad.put("size","1234");if(mode==6)bad.put("size",0);
            f.statOverride=bad;f.node.discardPreparedFile(UNSIGNED);assertEquals(0,f.deletes());
        }
    }
    @Test public void onlyTheExactScratchNameShapesCanReachTheFileApi() {
        try(Flow f=new Flow()) {
            for(String name:new String[]{null,"","/", "backup.bak","../"+UNSIGNED,"/public/node/"+UNSIGNED,"mi_send_"+"a".repeat(32)+".txn","signedtransaction-1.txn;send","nft_send_x.txn"})f.node.discardPreparedFile(name);
            assertTrue(f.files.isEmpty());
        }
    }
    @Test public void closedHostOrDuplicateStatReplyCannotDeleteMoreFiles() {
        try(Flow f=new Flow()) {
            f.holdStat=true;f.node.discardPreparedFile(UNSIGNED);when(f.act.isDestroyed()).thenReturn(true);f.delayedStat.response(f.stat(UNSIGNED));assertEquals(0,f.deletes());
        }
        try(Flow f=new Flow()) {
            f.holdStat=true;f.node.discardPreparedFile(UNSIGNED);f.delayedStat.response(f.stat(UNSIGNED));f.delayedStat.response(f.stat(UNSIGNED));assertEquals(1,f.deletes());
        }
        try(Flow f=new Flow()){f.node.onDestroy();f.node.discardPreparedFile(UNSIGNED);assertTrue(f.files.isEmpty());}
    }
}
