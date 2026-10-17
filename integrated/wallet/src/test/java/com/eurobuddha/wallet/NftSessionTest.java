package com.eurobuddha.wallet;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.minima.utils.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real NFT continuation methods, synthetic public coin metadata and a revoked connection. */
public class NftSessionTest {
    static class Fixture {
        final MainActivity act=mock(MainActivity.class);
        final NftView view=mock(NftView.class,CALLS_REAL_METHODS);
        final AtomicBoolean current=new AtomicBoolean(true);
        Fixture()throws Exception {
            Field a=BaseView.class.getDeclaredField("act");a.setAccessible(true);a.set(view,act);
            Field s=NftView.class.getDeclaredField("session");s.setAccessible(true);s.set(view,(java.util.function.BooleanSupplier)current::get);
        }
        void call(String name,Object...args)throws Exception {
            for(Method m:NftView.class.getDeclaredMethods())if(m.getName().equals(name)){m.setAccessible(true);m.invoke(view,args);return;}
            throw new AssertionError(name);
        }
    }
    @Test public void revokedBatchCannotStartResolveOrPublish()throws Exception {
        Fixture f=new Fixture();f.current.set(false);
        f.call("runCollectionSend",null,Collections.emptyList(),"recipient");
        f.call("sendNext",null,Collections.emptyList(),"recipient",0,new ArrayList<>(),new ArrayList<>(),mock(TextView.class),mock(AlertDialog.class),new boolean[]{false});
        verifyNoInteractions(f.act);
    }
    @Test public void inputResolutionAfterConnectionChangeCannotBuildOrSign()throws Exception {
        Fixture f=new Fixture();AtomicReference<MainActivity.InputsCb> reply=new AtomicReference<>();
        doAnswer(i->{reply.set(i.getArgument(1));return null;}).when(f.act).resolveInputs(anyList(),any());
        JSONObject coin=new JSONObject();coin.put("coinid","0xfeed");
        try(MockedStatic<Language> ignored=mockStatic(Language.class)){
            f.call("sendNext",null,Collections.singletonList(coin),"recipient",0,new ArrayList<>(),new ArrayList<>(),mock(TextView.class),mock(AlertDialog.class),new boolean[]{false});
            assertNotNull(reply.get());f.current.set(false);reply.get().onResolved(Collections.emptyList());
            verify(f.act,never()).factory();verify(f.act,never()).vault();verify(f.act,never()).node();
        }
    }
    @Test public void broadcastReplyAfterConnectionChangeCannotContinueBatch()throws Exception {
        Fixture f=new Fixture();NodeLink node=mock(NodeLink.class);when(f.act.node()).thenReturn(node);
        AtomicReference<NodeApi.Cb> reply=new AtomicReference<>();
        doAnswer(i->{reply.set(i.getArgument(1));return null;}).when(node).publish(any(),any());
        f.call("publishOne",null,Collections.emptyList(),"recipient",0,new ArrayList<>(),new ArrayList<>(),mock(TextView.class),mock(AlertDialog.class),new boolean[]{false},"0xfeed",1,mock(TxnFactory.BuiltTxn.class));
        assertNotNull(reply.get());f.current.set(false);reply.get().onResult(new org.json.JSONObject());
        verify(f.act,never()).resolveInputs(anyList(),any());verify(f.act,never()).factory();
        Field p=NftView.class.getDeclaredField("PENDING");p.setAccessible(true);assertFalse(((Map<?,?>)p.get(null)).containsKey("0xfeed"));
    }
}
