package com.eurobuddha.wallet;

import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real publish chain, mocked command transport. No sockets, keys or real node. */
public class SweepSizeTransportTest {
    @Test public void requiresExactCorrelatedStructuredSizeRefusal()throws Exception{
        TxnFactory.BuiltTxn built=SweepPendingTest.built();JSONObject good=SweepPendingTest.sizeRefusal(built);
        assertTrue(NodeLink.isSizeRefusal(good,built.getID()));
        for(String field:new String[]{"command","params","status","pending","error"}){
            JSONObject bad=new JSONObject(good.toString());bad.remove(field);assertFalse(field,NodeLink.isSizeRefusal(bad,built.getID()));
        }
        for(Object status:new Object[]{true,"false",0})assertFalse(NodeLink.isSizeRefusal(new JSONObject(good.toString()).put("status",status),built.getID()));
        for(String error:new String[]{"Txn too big","timeout: TxPoW size too large.. 71234/65536","TxPoW size too large.. 65536/65536","TxPoW size too large.. 60000/65536","TxPoW size too large.. 999/1","TxPoW size too large.. 99999999999999999999999/65536"})
            assertFalse(error,NodeLink.isSizeRefusal(new JSONObject(good.toString()).put("error",error),built.getID()));
        assertFalse(NodeLink.isSizeRefusal(new JSONObject(good.toString()).put("response",new JSONObject()),built.getID()));
        assertFalse(NodeLink.isSizeRefusal(good,"different"));
    }
    static class Fixture {
        final NodeLink link=mock(NodeLink.class,CALLS_REAL_METHODS);final NodeApi api=mock(NodeApi.class);
        final List<String> commands=new ArrayList<>();final List<NodeApi.Cb> callbacks=new ArrayList<>();
        Fixture()throws Exception{
            java.lang.reflect.Field field=NodeLink.class.getDeclaredField("mApi");field.setAccessible(true);field.set(link,api);
            doAnswer(i->{commands.add(i.getArgument(0));callbacks.add(i.getArgument(1));return null;}).when(api).cmd(anyString(),any());
        }
        void ready(TxnFactory.BuiltTxn built,NodeApi.Cb cb)throws Exception{
            link.publish(built,cb);callbacks.get(0).onResult(new JSONObject().put("status",true));callbacks.get(1).onResult(new JSONObject().put("status",true));
        }
    }
    @Test public void onlyPostRejectionUsesTypedCallbackAndEachStageCompletesOnce()throws Exception{
        Fixture f=new Fixture();TxnFactory.BuiltTxn built=SweepPendingTest.built();NodeLink.SizeAwareCb cb=mock(NodeLink.SizeAwareCb.class);
        f.ready(built,cb);JSONObject refusal=SweepPendingTest.sizeRefusal(built);
        f.callbacks.get(0).onResult(new JSONObject().put("status",true));f.callbacks.get(0).onError("late");
        f.callbacks.get(1).onResult(new JSONObject().put("status",true));f.callbacks.get(1).onError("late");
        f.callbacks.get(2).onResult(refusal);f.callbacks.get(2).onResult(refusal);f.callbacks.get(2).onError("late");
        assertEquals(3,f.commands.size());assertEquals(built.getTxnPostCommand(),f.commands.get(2));
        verify(cb,times(1)).onSizeRefusal(refusal);verifyNoMoreInteractions(cb);
    }
    @Test public void transportTimeoutOrEarlierStageErrorNeverAuthorizesSplitting()throws Exception{
        TxnFactory.BuiltTxn built=SweepPendingTest.built();
        for(int stage=0;stage<3;stage++){
            Fixture f=new Fixture();NodeLink.SizeAwareCb cb=mock(NodeLink.SizeAwareCb.class);f.link.publish(built,cb);
            for(int i=0;i<stage;i++)f.callbacks.get(i).onResult(new JSONObject().put("status",true));
            f.callbacks.get(stage).onError("TxPoW size too large.. 71234/65536");
            verify(cb).onError(anyString());verify(cb,never()).onSizeRefusal(any());assertEquals(stage+1,f.commands.size());
        }
        Fixture f=new Fixture();NodeLink.SizeAwareCb cb=mock(NodeLink.SizeAwareCb.class);f.link.publish(built,cb);
        f.callbacks.get(0).onResult(SweepPendingTest.sizeRefusal(built));verify(cb).onError(anyString());verify(cb,never()).onSizeRefusal(any());
    }
    @Test public void ordinaryReplayCallbackCannotReleasePreviouslyUnknownAttempt()throws Exception{
        Fixture f=new Fixture();TxnFactory.BuiltTxn built=SweepPendingTest.built();NodeApi.Cb cb=mock(NodeApi.Cb.class);
        f.ready(built,cb);f.callbacks.get(2).onResult(SweepPendingTest.sizeRefusal(built));verify(cb).onError(anyString());verifyNoMoreInteractions(cb);
    }
}
