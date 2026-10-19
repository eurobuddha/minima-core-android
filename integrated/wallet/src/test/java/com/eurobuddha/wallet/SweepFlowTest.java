package com.eurobuddha.wallet;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercise real port continuations with mocked Android/UI and transport; never send funds. */
public class SweepFlowTest {
    static class Fixture implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class);
        final SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        final NodeLink node=mock(NodeLink.class);final TxnFactory factory=mock(TxnFactory.class);
        final AtomicBoolean current=new AtomicBoolean(true);
        final SweepPendingTest.Store store=new SweepPendingTest.Store();final SweepPending pending=new SweepPending(store);
        final AtomicReference<MainActivity.InputsCb> resolution=new AtomicReference<>();
        final MockedStatic<Language> language=mockStatic(Language.class,i->i.getMethod().getReturnType()==String.class ? "translated fixture" : null);
        Fixture()throws Exception{
            put("act",act);put("node",node);put("factory",factory);put("pending",pending);put("session",(java.util.function.BooleanSupplier)current::get);
            when(act.isPaired()).thenReturn(true);
            doAnswer(i->{resolution.set(i.getArgument(1));return null;}).when(act).resolveInputs(anyList(),any());
        }
        void put(String name,Object value)throws Exception{Field f=SweepFlow.class.getDeclaredField(name);f.setAccessible(true);f.set(flow,value);}
        void next()throws Exception{
            SweepPlanner.Plan plan=new SweepPlanner.Plan();org.minima.utils.json.JSONObject coin=new org.minima.utils.json.JSONObject();coin.put("coinid","0x"+"12".repeat(32));
            plan.jobs.add(new SweepPlanner.Job(true,"0x00",Collections.singletonList(coin),"fixture",1));
            call("next",plan,"0x"+"78".repeat(32),0,new ArrayList<String>(),new ArrayList<String>(),new ArrayList<String>(),mock(TextView.class),mock(AlertDialog.class),new boolean[]{false});
        }
        Object call(String name,Object...args)throws Exception{for(Method m:SweepFlow.class.getDeclaredMethods())if(m.getName().equals(name)){m.setAccessible(true);return m.invoke(flow,args);}throw new AssertionError(name);}
        public void close(){language.close();}
    }
    @Test public void revokedSessionCannotStartSweepOrContinueResolution()throws Exception{
        try(Fixture f=new Fixture()){f.current.set(false);f.flow.start();f.next();verify(f.act,never()).resolveInputs(anyList(),any());verifyNoInteractions(f.node);verifyNoInteractions(f.factory);}
    }
    @Test public void resolutionAfterLockCannotSign()throws Exception{
        try(Fixture f=new Fixture()){f.next();assertNotNull(f.resolution.get());f.current.set(false);f.resolution.get().onResolved(Collections.emptyList());verify(f.act,never()).runSigningWork(any(),any(),any());verifyNoInteractions(f.factory);verifyNoInteractions(f.node);}
    }
    @Test public void duplicateInputCallbackSchedulesOnlyOneSigningJob()throws Exception{
        try(Fixture f=new Fixture()){f.next();f.resolution.get().onResolved(Collections.emptyList());f.resolution.get().onResolved(Collections.emptyList());verify(f.act,times(1)).runSigningWork(any(),any(),any());verifyNoInteractions(f.factory);}
    }
    @Test public void pendingWriteFailurePreventsPublish()throws Exception{
        try(Fixture f=new Fixture()){
            TxnFactory.BuiltTxn built=SweepPendingTest.built();f.store.fail=true;
            when(f.factory.buildWholeTransfer(any(),anyString(),anyString())).thenReturn(built);
            AtomicReference<Exception> failed=new AtomicReference<>();
            doAnswer(i->{try{TxnFactory.BuiltTxn result=((Callable<TxnFactory.BuiltTxn>)i.getArgument(0)).call();((Consumer<TxnFactory.BuiltTxn>)i.getArgument(1)).accept(result);}catch(Exception error){failed.set(error);}return null;}).when(f.act).runSigningWork(any(),any(),any());
            TxnFactory.InputCoin input=mock(TxnFactory.InputCoin.class);when(input.getCoinID()).thenReturn(new org.minima.objects.base.MiniData("0x"+"12".repeat(32)));
            f.next();f.resolution.get().onResolved(Collections.singletonList(input));assertNotNull(failed.get());verify(f.node,never()).publish(any(),any());
        }
    }
    @Test public void explicitRetryPublishesIdenticalBytesWithoutSigning()throws Exception{
        try(Fixture f=new Fixture()){
            TxnFactory.BuiltTxn built=SweepPendingTest.built();f.pending.reserve(built);
            AtomicReference<TxnFactory.BuiltTxn> sent=new AtomicReference<>();doAnswer(i->{sent.set(i.getArgument(0));return null;}).when(f.node).publish(any(),any());
            f.call("replay",f.pending.savedTransactions(),0);
            assertEquals(built.getImportHex(),sent.get().getImportHex());verify(f.act,never()).runSigningWork(any(),any(),any());verifyNoInteractions(f.factory);
        }
    }
}
