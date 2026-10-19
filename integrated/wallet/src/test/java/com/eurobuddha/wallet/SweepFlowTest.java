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
        public void close(){
            try{Field field=NftView.class.getDeclaredField("PENDING");field.setAccessible(true);((Map<?,?>)field.get(null)).clear();}
            catch(Exception error){throw new AssertionError(error);}finally{language.close();}
        }
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
    static class Pooled {
        final TxnFactoryTest.InMemoryKeyUses uses=new TxnFactoryTest.InMemoryKeyUses();
        final WalletCore wallet=new WalletCore("public sweep split fixture",uses);
        final TxnFactory real=new TxnFactory(wallet);
        final List<TxnFactory.InputCoin> inputs=new ArrayList<>();
        final SweepPlanner.Plan plan=new SweepPlanner.Plan();
        final List<NodeApi.Cb> replies=new ArrayList<>();final List<TxnFactory.BuiltTxn> built=new ArrayList<>();
        final List<String> destinations=new ArrayList<>();
        Pooled(Fixture f)throws Exception{
            List<org.minima.utils.json.JSONObject> coins=new ArrayList<>();
            for(String octet:new String[]{"12","34"}){
                String id="0x"+octet.repeat(32);org.minima.utils.json.JSONObject coin=new org.minima.utils.json.JSONObject();coin.put("coinid",id);coins.add(coin);
                inputs.add(new TxnFactory.InputCoin(new org.minima.objects.base.MiniData(id),wallet.getAddress(0).getAddressData(),org.minima.objects.base.MiniNumber.ONE,
                    TxnFactory.TOKEN_MINIMA,new org.minima.objects.mmr.MMREntryNumber(inputs.size()+1),new org.minima.objects.base.MiniNumber(100),true,new ArrayList<>(),null,0));
            }
            plan.jobs.add(new SweepPlanner.Job(false,"0x00",coins,"pool",1));
            plan.jobs.add(new SweepPlanner.Job(true,"0x00",Collections.singletonList(new org.minima.utils.json.JSONObject()),"later unrelated job",1));
            doAnswer(i->{destinations.add(i.getArgument(1));return real.buildSend(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5));}).when(f.factory).buildSend(anyList(),anyString(),any(),any(),any(),anyString());
            doAnswer(i->{TxnFactory.BuiltTxn result=((Callable<TxnFactory.BuiltTxn>)i.getArgument(0)).call();((Consumer<TxnFactory.BuiltTxn>)i.getArgument(1)).accept(result);return null;}).when(f.act).runSigningWork(any(),any(),any());
            doAnswer(i->{built.add(i.getArgument(0));replies.add(i.getArgument(1));return null;}).when(f.node).publish(any(),any());
            f.call("next",plan,"0x"+"78".repeat(32),0,new ArrayList<String>(),new ArrayList<String>(),new ArrayList<String>(),mock(TextView.class),mock(AlertDialog.class),new boolean[]{false});
            f.resolution.get().onResolved(inputs);
        }
    }
    @Test public void definiteSizeRejectionSplitsThenContinuesToTheSameDestination()throws Exception{
        try(Fixture f=new Fixture()){
            Pooled p=new Pooled(f);NodeLink.SizeAwareCb reply=(NodeLink.SizeAwareCb)p.replies.get(0);
            reply.onSizeRefusal(SweepPendingTest.sizeRefusal(p.built.get(0)));
            assertEquals(3,p.plan.jobs.size());assertEquals(1,p.plan.jobs.get(0).coins.size());assertEquals(1,p.plan.jobs.get(1).coins.size());
            assertTrue(f.pending.coins().isEmpty());
            f.resolution.get().onResolved(Collections.singletonList(p.inputs.get(0)));
            assertEquals(2,p.built.size());assertEquals(p.destinations.get(0),p.destinations.get(1));
            assertEquals("The refused signature is consumed, never reused by the smaller batch",2,p.uses.currentUses(0));
            assertEquals(1,p.built.get(1).getTransaction().getAllInputs().size());
            reply.onSizeRefusal(SweepPendingTest.sizeRefusal(p.built.get(0)));
            assertEquals(3,p.plan.jobs.size());assertEquals(2,p.built.size());
        }
    }
    @Test public void failureToSaveSizeRejectionCannotSplitOrReleaseInputs()throws Exception{
        try(Fixture f=new Fixture()){
            Pooled p=new Pooled(f);f.store.fail=true;
            ((NodeLink.SizeAwareCb)p.replies.get(0)).onSizeRefusal(SweepPendingTest.sizeRefusal(p.built.get(0)));
            assertEquals(2,p.plan.jobs.size());assertEquals(2,f.pending.coins().size());assertEquals(1,p.built.size());
        }
    }
    @Test public void timeoutContainingSizeTextRemainsUnknownAndDoesNotSplit()throws Exception{
        try(Fixture f=new Fixture()){
            Pooled p=new Pooled(f);p.replies.get(0).onError("TxPoW size too large.. 71234/65536");
            assertEquals(2,p.plan.jobs.size());assertEquals(2,f.pending.coins().size());assertEquals(1,p.built.size());
        }
    }
    @Test public void lockBeforeSizeCallbackCannotContinueSigning()throws Exception{
        try(Fixture f=new Fixture()){
            Pooled p=new Pooled(f);f.current.set(false);
            ((NodeLink.SizeAwareCb)p.replies.get(0)).onSizeRefusal(SweepPendingTest.sizeRefusal(p.built.get(0)));
            assertEquals(2,p.plan.jobs.size());assertEquals(1,p.built.size());verify(f.act,times(1)).runSigningWork(any(),any(),any());
        }
    }
    @Test public void aRejectedSingleStopsSplittingAndContinuesWithTheNextCoin()throws Exception{
        try(Fixture f=new Fixture()){
            Pooled p=new Pooled(f);
            ((NodeLink.SizeAwareCb)p.replies.get(0)).onSizeRefusal(SweepPendingTest.sizeRefusal(p.built.get(0)));
            f.resolution.get().onResolved(Collections.singletonList(p.inputs.get(0)));
            ((NodeLink.SizeAwareCb)p.replies.get(1)).onSizeRefusal(SweepPendingTest.sizeRefusal(p.built.get(1)));
            assertEquals(3,p.plan.jobs.size());assertTrue(f.pending.coins().isEmpty());assertEquals(2,p.built.size());
            f.resolution.get().onResolved(Collections.singletonList(p.inputs.get(1)));
            assertEquals(3,p.built.size());assertEquals(p.inputs.get(1).getCoinID(),p.built.get(2).getTransaction().getAllInputs().get(0).getCoinID());
            assertEquals(3,p.uses.currentUses(0));
        }
    }
    @Test public void clearingRejectedAttemptDoesNotEraseANewerNftReservation(){
        String coin="sweep_fixture_reservation";long first=NftView.markPending(coin),second=NftView.markPending(coin);
        try{assertTrue(second>first);NftView.clearSweepPending(coin,first);assertTrue(NftView.pendingCoinIds().contains(coin));}
        finally{NftView.clearSweepPending(coin,second);}
    }
}
