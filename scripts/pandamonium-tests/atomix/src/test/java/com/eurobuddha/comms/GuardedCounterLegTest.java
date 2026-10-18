package com.eurobuddha.atomix.integratedcomms;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** The existing publication fixture drives the real SignGate, NodeApi and shared worker boundary. */
public class GuardedCounterLegTest {
    @Before public void before(){SignGate.resetForTest();WriteSafety.resetForTest();}
    @After public void after(){SignGate.resetForTest();WriteSafety.resetForTest();}
    static JSONObject reply(String command) throws Exception {
        JSONObject r=new JSONObject().put("txpowid","0x"+"45".repeat(32));
        if(command.startsWith("txncheck ")) r.put("valid",new JSONObject().put("basic",true).put("mmrproofs",true).put("scripts",1))
                .put("validamounts",true).put("allsignaturesvalid",true).put("validtransaction",true);
        return new JSONObject().put("status",true).put("response",r);
    }
    static MinimaHtlc htlc(PublishQueueTest.Host host,TradingContext market) throws Exception {
        MinimaHtlc h=new MinimaHtlc(host.node);h.setActiveToken(market.tokenId);
        // Synthetic public identity only; never initialize a node, seed or wallet.
        Field f=MinimaHtlc.class.getDeclaredField("myAddress");f.setAccessible(true);f.set(h,"MxPUBLICFIXTURE");
        f=MinimaHtlc.class.getDeclaredField("myPubkey");f.setAccessible(true);f.set(h,"0xABCD");return h;
    }
    static void lock(MinimaHtlc h,AtomicBoolean allowed,MinimaHtlc.PostCb cb) {
        h.lockFromCoins(Arrays.asList("0xA1","0xA2"),"3","2","2","minima","0xBCDE","0x1234","0x5678",136,"FALSE",allowed::get,cb);
    }
    static int advanceTo(PublishQueueTest.Host host,String verb) throws Exception {
        for(int i=0;i<50;i++) {
            assertTrue("Missing next command",host.commands.size()>i);
            if(host.commands.get(i).startsWith(verb+" "))return i;
            assertTrue(host.requests.get(i).begin());host.requests.get(i).accept(reply(host.commands.get(i)));
        }
        throw new AssertionError("Missing "+verb);
    }
    @Test public void invalidationWhileWaitingForSignGateNeverBuildsOrSigns() throws Exception {
        try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
            List<SignGate.Release> hold=new ArrayList<>();SignGate.submit(hold::add);
            AtomicBoolean allowed=new AtomicBoolean(true);MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);
            lock(htlc(host,TradingContext.MINIMA),allowed,cb);assertTrue(host.commands.isEmpty());
            allowed.set(false);hold.get(0).free();
            assertTrue(host.commands.stream().allMatch(c->c.startsWith("txndelete ")));
            verify(cb).err(NodeApi.ERR_COMMAND_CHANGED);verify(cb,never()).ok(anyString());
            List<Boolean> next=new ArrayList<>();SignGate.submit(r->{next.add(true);r.free();});assertEquals(1,next.size());
        }
    }
    @Test public void sharedWorkerRechecksBeforeBuildingSigningAndPosting() throws Exception {
        for(String verb:new String[]{"txncreate","txnsign","txnpost"}) {
            before();
            try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
                AtomicBoolean allowed=new AtomicBoolean(true);MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);
                lock(htlc(host,TradingContext.MXUSDT),allowed,cb);int i=advanceTo(host,verb);
                allowed.set(false);assertFalse(host.requests.get(i).begin());verify(cb,never()).ok(anyString());
                verify(cb).err(verb.equals("txnpost")?startsWith("POSTED:"):argThat(e->!e.startsWith("POSTED:")));
                assertTrue(host.commands.subList(i+1,host.commands.size()).stream().allMatch(c->c.startsWith("txndelete ")));
            }
        }
    }
    @Test public void tokenChangeWhileQueuedCannotSignOriginalOutputs() throws Exception {
        try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
            AtomicBoolean allowed=new AtomicBoolean(true);MinimaHtlc h=htlc(host,TradingContext.MINIMA);
            MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);lock(h,allowed,cb);int i=advanceTo(host,"txnsign");
            h.setActiveToken(TradingContext.MXUSDT.tokenId);assertFalse(host.requests.get(i).begin());verify(cb,never()).ok(anyString());
            assertFalse(host.commands.stream().anyMatch(c->c.startsWith("txnpost ")));
        }
    }
    @Test public void knownCancellationBeforePostDispatchIsNotAnUncertainBroadcast() throws Exception {
        try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
            AtomicBoolean allowed=new AtomicBoolean(true);MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);
            lock(htlc(host,TradingContext.MINIMA),allowed,cb);int i=advanceTo(host,"txncheck");
            assertTrue(host.requests.get(i).begin());allowed.set(false);host.requests.get(i).accept(reply(host.commands.get(i)));
            verify(cb).err(NodeApi.ERR_COMMAND_CHANGED);assertFalse(host.commands.stream().anyMatch(c->c.startsWith("txnpost ")));
        }
    }
    @Test public void startedPostKeepsItsReceiptAndOriginalTokenAfterInvalidation() throws Exception {
        for(TradingContext market:TradingContext.values()) {
            before();
            try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
                AtomicBoolean allowed=new AtomicBoolean(true);MinimaHtlc h=htlc(host,market);MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);
                lock(h,allowed,cb);int i=advanceTo(host,"txnpost");DirectNodeApi.Request post=host.requests.get(i);assertTrue(post.begin());
                allowed.set(false);h.setActiveToken(market.other().tokenId);post.accept(reply(host.commands.get(i)));
                verify(cb).ok("0x"+"45".repeat(32));verify(cb,never()).err(anyString());
                assertEquals(2,host.commands.stream().filter(c->c.startsWith("txnoutput ")&&c.contains("tokenid:"+market.tokenId+" ")).count());
                assertFalse(host.node.hasInterruptedWrite());
            }
        }
    }
    @Test public void malformedPostReceiptsCannotClearInterruptedWriteEvidence() throws Exception {
        List<JSONObject> replies=new ArrayList<>();
        for(Object id:new Object[]{JSONObject.NULL,"",false,12,"0x12","0x"+"00".repeat(32),"0x"+"gg".repeat(32)})
            replies.add(new JSONObject().put("status",true).put("response",new JSONObject().put("txpowid",id)));
        for(String flag:new String[]{"pending","dryrun"})for(Object value:new Object[]{true,"false",JSONObject.NULL,0})
            replies.add(reply("txnpost id:fixture").put(flag,value));
        for(JSONObject malformed:replies) {
            before();try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
                MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);lock(htlc(host,TradingContext.MINIMA),new AtomicBoolean(true),cb);
                int i=advanceTo(host,"txnpost");assertTrue(host.requests.get(i).begin());host.requests.get(i).accept(malformed);
                verify(cb).err(startsWith("POSTED:"));verify(cb,never()).ok(anyString());assertTrue(host.node.hasInterruptedWrite());
            }
        }
        assertFalse(WriteSafety.completeReply("txnpost id:fixture dryrun:true",reply("txnpost").put("dryrun",true)));
        assertTrue(WriteSafety.completeReply("txnpost id:fixture",new JSONObject().put("status",false).put("error","rejected")));
    }
    @Test public void unknownPostReplyRemainsTaggedAndKeepsDurablePause() throws Exception {
        try(PublishQueueTest.Host host=new PublishQueueTest.Host()) {
            MinimaHtlc.PostCb cb=mock(MinimaHtlc.PostCb.class);lock(htlc(host,TradingContext.MINIMA),new AtomicBoolean(true),cb);
            int i=advanceTo(host,"txnpost");assertTrue(host.requests.get(i).begin());host.requests.get(i).accept(new JSONObject().put("status",true));
            verify(cb).err(startsWith("POSTED:"));verify(cb,never()).ok(anyString());assertTrue(host.node.hasInterruptedWrite());
        }
    }
}
