package com.eurobuddha.wallet;

import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeLink;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.minima.utils.json.JSONObject;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.wallet.WalletAuthenticatedWorkTest.*;

public class WalletSigningCallerTest {
    @Test public void collectionQueuesCapturedFactoryAndPublishesOnlyAfterDelivery()throws Exception {
        NftSessionTest.Fixture f=new NftSessionTest.Fixture();
        AtomicReference<MainActivity.InputsCb> reply=new AtomicReference<>();
        AtomicReference<Callable<TxnFactory.BuiltTxn>> job=new AtomicReference<>();
        AtomicReference<Consumer<TxnFactory.BuiltTxn>> delivered=new AtomicReference<>();
        TxnFactory factory=mock(TxnFactory.class);NodeLink node=mock(NodeLink.class);
        when(f.act.factory()).thenReturn(factory);when(f.act.node()).thenReturn(node);
        doAnswer(i->{reply.set(i.getArgument(1));return null;}).when(f.act).resolveInputs(anyList(),any());
        doAnswer(i->{job.set(i.getArgument(0));delivered.set(i.getArgument(1));return null;}).when(f.act).runSigningWork(any(),any(),any());
        JSONObject coin=new JSONObject();coin.put("coinid","0xabc123");
        TxnFactory.InputCoin input=mock(TxnFactory.InputCoin.class);TxnFactory.BuiltTxn built=mock(TxnFactory.BuiltTxn.class);
        when(factory.buildNftTransfer(eq(input),eq("recipient"),anyString())).thenReturn(built);
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            f.call("sendNext",null,Collections.singletonList(coin),"recipient",0,new ArrayList<>(),new ArrayList<>(),mock(TextView.class),mock(AlertDialog.class),new boolean[]{false});
            reply.get().onResolved(Collections.singletonList(input));assertNotNull(job.get());verifyNoInteractions(factory,node);
            when(f.act.factory()).thenReturn(mock(TxnFactory.class));assertSame(built,job.get().call());
            verify(factory).buildNftTransfer(eq(input),eq("recipient"),anyString());verifyNoInteractions(node);
            delivered.get().accept(built);verify(node).publish(eq(built),any());
        }
    }
    @Test public void staleSendResolutionCannotOpenAnotherReview()throws Exception {
        MainActivity act=mock(MainActivity.class);SendView view=mock(SendView.class,CALLS_REAL_METHODS);
        AtomicBoolean current=new AtomicBoolean(true);AtomicReference<MainActivity.InputsCb> reply=new AtomicReference<>();
        set(view,BaseView.class,"act",act);set(view,SendView.class,"session",(BooleanSupplier)current::get);
        set(view,SendView.class,"mTokenId","0x1234");
        doAnswer(i->{reply.set(i.getArgument(1));return null;}).when(act).resolveInputs(anyList(),any());
        AtomicInteger reviews=new AtomicInteger();
        call(view,SendView.class,"withResolvedInputs",new Class<?>[]{List.class,Consumer.class},Collections.emptyList(),(Consumer<List<TxnFactory.InputCoin>>)v->reviews.incrementAndGet());
        assertNotNull(reply.get());current.set(false);reply.get().onResolved(Collections.emptyList());
        assertEquals(0,reviews.get());
    }
}
