package com.eurobuddha.wallet;

import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeLink;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONObject;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.wallet.WalletAuthenticatedWorkTest.*;

/** Real Activity worker/session guard with mocked building/storage/transport. No signing or node. */
public class SweepWorkerTest {
    static final String DEST="MxG081PTR905VTUGBKY7KHKV76QVHE99BM691QNC09GP2WPCG1P0QZTHD8SWWVQ";
    static class Sweep {
        final SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        final TxnFactory factory=mock(TxnFactory.class);
        final SweepPending pending=mock(SweepPending.class);
        final NodeLink node=mock(NodeLink.class);
        final TxnFactory.BuiltTxn built=mock(TxnFactory.BuiltTxn.class);
        final List<String> failures=new ArrayList<>();
        Sweep(Fixture f)throws Exception {
            set(flow,SweepFlow.class,"act",f.activity);set(flow,SweepFlow.class,"session",f.activity.sessionAuthorization());
            set(flow,SweepFlow.class,"factory",factory);set(flow,SweepFlow.class,"pending",pending);set(flow,SweepFlow.class,"node",node);
            when(pending.coins()).thenAnswer(i->new HashSet<String>());
            android.content.res.Resources resources=mock(android.content.res.Resources.class);
            android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();metrics.density=1;
            when(resources.getDisplayMetrics()).thenReturn(metrics);when(f.activity.getResources()).thenReturn(resources);
            when(factory.buildSend(anyList(),eq(DEST),any(),any(),any(),anyString())).thenReturn(built);
            TxnFactory.InputCoin input=mock(TxnFactory.InputCoin.class);
            when(input.getCoinID()).thenReturn(new MiniData("0x"+"37".repeat(32)));when(input.getAmount()).thenReturn(MiniNumber.ONE);
            doAnswer(i->{((MainActivity.InputsCb)i.getArgument(1)).onResolved(Collections.singletonList(input));return null;})
                .when(f.activity).resolveInputs(anyList(),any());
        }
        void start()throws Exception {
            JSONObject coin=new JSONObject();coin.put("coinid","0x"+"37".repeat(32));
            SweepPlanner.Plan plan=new SweepPlanner.Plan();
            plan.jobs.add(new SweepPlanner.Job(false,"0x00",Collections.singletonList(coin),"Minima",1));
            call(flow,SweepFlow.class,"next",new Class<?>[]{SweepPlanner.Plan.class,String.class,int.class,List.class,List.class,List.class,TextView.class,AlertDialog.class,boolean[].class},
                plan,DEST,0,new ArrayList<String>(),new ArrayList<String>(),failures,mock(TextView.class),mock(AlertDialog.class),new boolean[]{false});
        }
    }
    @Test public void ownBusyWorkerCanBuildThenReserveBeforePublishing()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class,i->i.getMethod().getReturnType()==String.class ? "fixture" : null)) {
            Sweep s=new Sweep(f);s.start();
            assertTrue(f.work.busy());assertFalse(f.activity.sessionAuthorization().getAsBoolean());
            verifyNoInteractions(s.factory,s.node);
            f.workers.remove(0).run();
            verify(s.factory).buildSend(anyList(),eq(DEST),any(),any(),any(),anyString());
            verify(s.pending).reserve(s.built);verifyNoInteractions(s.node);
            f.deliveries.remove(0).run();
            org.mockito.InOrder order=inOrder(s.factory,s.pending,s.node);
            order.verify(s.factory).buildSend(anyList(),eq(DEST),any(),any(),any(),anyString());
            order.verify(s.pending).reserve(s.built);order.verify(s.node).publish(eq(s.built),any());
            assertTrue(s.failures.isEmpty());
        } finally {java.lang.reflect.Field p=NftView.class.getDeclaredField("PENDING");p.setAccessible(true);((Map<?,?>)p.get(null)).clear();}
    }
    @Test public void replacedConnectionBeforeWorkerCannotBuildOrPublish()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class,i->i.getMethod().getReturnType()==String.class ? "fixture" : null)) {
            Sweep s=new Sweep(f);s.start();
            set(f.activity,MainActivity.class,"mUiSession",(BooleanSupplier)()->true);f.execute();
            verifyNoInteractions(s.factory,s.node);verify(s.pending,never()).reserve(any());
        }
    }
    @Test public void revokedVaultBeforeWorkerCannotBuildOrPublish()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class,i->i.getMethod().getReturnType()==String.class ? "fixture" : null)) {
            Sweep s=new Sweep(f);s.start();f.authorized.set(false);f.execute();
            verifyNoInteractions(s.factory,s.node);verify(s.pending,never()).reserve(any());
        }
    }
    private static MockedStatic<Language> diagnosticLanguage() {
        return mockStatic(Language.class,i->{
            if(i.getMethod().getReturnType()!=String.class)return null;
            if(i.getMethod().getName().equals("text"))
                return i.getArgument(1)+":"+Arrays.toString((Object[])i.getRawArguments()[2]);
            return "fixture";
        });
    }
    @Test public void localBuildFailureKeepsItsReasonAndNeverPublishes()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=diagnosticLanguage()) {
            Sweep s=new Sweep(f);
            when(s.factory.buildSend(anyList(),eq(DEST),any(),any(),any(),anyString()))
                .thenThrow(new IllegalStateException("fixture local build refusal"));
            s.start();f.execute();
            assertEquals(1,s.failures.size());
            assertTrue(s.failures.get(0).contains("fixture local build refusal"));
            assertTrue(s.failures.get(0).contains(String.valueOf(com.eurobuddha.minimacore.i18n.R.string.l10n_sweep_prepare_error)));
            verify(f.vault).syncKeyUses();verify(s.pending,never()).reserve(any());verifyNoInteractions(s.node);
        }
    }
    @Test public void inputFailureKeepsItsReasonWithoutStartingSigning()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=diagnosticLanguage()) {
            Sweep s=new Sweep(f);
            doAnswer(i->{((MainActivity.InputsCb)i.getArgument(1)).onError("fixture coin resolution refusal");return null;})
                .when(f.activity).resolveInputs(anyList(),any());
            s.start();assertTrue(f.workers.isEmpty());assertEquals(1,s.failures.size());
            assertTrue(s.failures.get(0).contains("fixture coin resolution refusal"));
            assertTrue(s.failures.get(0).contains(String.valueOf(com.eurobuddha.minimacore.i18n.R.string.l10n_sweep_inputs_error)));
            verifyNoInteractions(s.factory,s.node);verify(s.pending,never()).reserve(any());
        }
    }

}
