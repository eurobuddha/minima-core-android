package com.eurobuddha.wallet;

import android.content.DialogInterface;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeLink;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.wallet.WalletAuthenticatedWorkTest.*;

/** Real dispatcher/review path with mocked builders; no signatures, keys, node or broadcasts. */
public class WalletSigningWorkTest {
    @Test public void signingAndSnapshotRunOnWorkerBeforeMainDelivery()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            List<String> order=new ArrayList<>();TxnFactory.BuiltTxn built=mock(TxnFactory.BuiltTxn.class);
            doAnswer(i->{order.add("gate");return null;}).when(f.vault).assertSigningAllowed();
            doAnswer(i->{order.add("snapshot");return null;}).when(f.vault).syncKeyUses();
            f.activity.runSigningWork(()->{order.add("build");return built;},v->{assertSame(built,v);order.add("deliver");},e->fail());
            assertTrue(order.isEmpty());f.workers.remove(0).run();
            assertEquals(Arrays.asList("gate","build","snapshot"),order);
            f.deliveries.remove(0).run();assertEquals(Arrays.asList("gate","build","snapshot","deliver"),order);
        }
    }
    @Test public void untrustedGateCannotBuildOrSnapshot()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            SeedVault.SigningNotAllowedException gate=new SeedVault.SigningNotAllowedException("fixture refusal");
            doThrow(gate).when(f.vault).assertSigningAllowed();AtomicReference<Exception> error=new AtomicReference<>();
            f.activity.runSigningWork(()->{fail("must not build");return null;},v->fail(),error::set);f.execute();
            assertSame(gate,error.get());verify(f.vault,never()).syncKeyUses();
        }
    }
    @Test public void buildFailureStillSnapshotsAndKeepsOriginalError()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            Exception build=new Exception("fixture partial signing");AtomicReference<Exception> error=new AtomicReference<>();
            doThrow(new IllegalStateException("fixture snapshot failure")).when(f.vault).syncKeyUses();
            f.activity.runSigningWork(()->{throw build;},v->fail(),error::set);f.execute();
            assertSame(build,error.get());verify(f.vault).syncKeyUses();
        }
    }
    @Test public void snapshotFailureDoesNotDiscardCompletedSignature()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            TxnFactory.BuiltTxn built=mock(TxnFactory.BuiltTxn.class);AtomicReference<TxnFactory.BuiltTxn> result=new AtomicReference<>();
            doThrow(new IllegalStateException("fixture snapshot failure")).when(f.vault).syncKeyUses();
            f.activity.runSigningWork(()->built,result::set,e->fail());f.execute();assertSame(built,result.get());
        }
    }
    @Test public void replacedConnectionRejectsQueuedBuilder()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            f.activity.runSigningWork(()->{fail("revoked builder");return null;},v->fail(),e->fail());
            set(f.activity,MainActivity.class,"mUiSession",(BooleanSupplier)()->true);f.execute();
            verify(f.vault,never()).assertSigningAllowed();verify(f.vault,never()).syncKeyUses();
        }
    }
    @Test public void reviewDoesNotBuildUntilApprovalAndNeverPublishesBeforeDelivery()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            KeyUses counters=mock(KeyUses.class);NodeLink node=mock(NodeLink.class);
            set(f.activity,MainActivity.class,"mKeyUses",counters);set(f.activity,MainActivity.class,"mNode",node);
            AtomicInteger builds=new AtomicInteger();TxnFactory.BuiltTxn built=mock(TxnFactory.BuiltTxn.class);
            f.activity.confirmSignAndPublish("review",0,()->{builds.incrementAndGet();return built;});
            assertTrue(f.workers.isEmpty());assertEquals(0,builds.get());verifyNoInteractions(node);
            ArgumentCaptor<DialogInterface.OnClickListener> click=ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
            verify(f.dialogs.constructed().get(0)).setPositiveButton((CharSequence)isNull(),click.capture());
            click.getValue().onClick(null,0);assertEquals(0,builds.get());
            f.workers.remove(0).run();assertEquals(1,builds.get());verifyNoInteractions(node);
            f.deliveries.remove(0).run();verify(node).publish(eq(built),any());
        }
    }
    @Test public void revokedResultCannotPublishAfterSigning()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<Language> language=mockStatic(Language.class)) {
            f.activity.runSigningWork(()->mock(TxnFactory.BuiltTxn.class),v->fail("stale publish"),e->fail());
            f.workers.remove(0).run();f.authorized.set(false);f.deliveries.remove(0).run();
            verify(f.vault).syncKeyUses();assertFalse(f.work.busy());
        }
    }
}
