package com.eurobuddha.wallet;

import android.os.Handler;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual Activity dispatcher with controlled worker/main queues and synthetic vault callbacks. */
public class WalletAuthenticatedWorkTest {
    static void set(Object target,Class<?> type,String name,Object value)throws Exception{
        Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static Object get(Object target,String name)throws Exception{
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static void call(Object target,Class<?> type,String name,Class<?>[] types,Object...args)throws Exception{
        Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);
        try{m.invoke(target,args);}catch(InvocationTargetException e){throw new AssertionError(e.getCause());}
    }
    static class Fixture implements AutoCloseable {
        final MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        final SeedVault vault=mock(SeedVault.class);
        final WalletSession session=mock(WalletSession.class);
        final AtomicBoolean authorized=new AtomicBoolean(true);
        final List<Runnable> workers=new ArrayList<>(), deliveries=new ArrayList<>();
        final WalletWork work;
        final MockedStatic<Toast> toast=mockStatic(Toast.class, i->mock(Toast.class));
        final MockedConstruction<WalletDialog> dialogs=mockConstruction(WalletDialog.class,
                withSettings().defaultAnswer(RETURNS_SELF),(b,c)->when(b.create()).thenReturn(mock(AlertDialog.class)));
        Fixture()throws Exception{this(false);}
        Fixture(boolean reject)throws Exception{
            work=new WalletWork(reject ? task->{throw new RejectedExecutionException("fixture");}:workers::add,deliveries::add);
            when(session.work()).thenReturn(work);when(vault.exists()).thenReturn(true);when(vault.isOpen()).thenReturn(true);
            when(vault.authorization()).thenReturn(authorized::get);
            set(activity,MainActivity.class,"mSession",session);set(activity,MainActivity.class,"mVault",vault);
            set(activity,MainActivity.class,"mUiSession",(BooleanSupplier)authorized::get);
            set(activity,MainActivity.class,"mForeground",true);set(activity,MainActivity.class,"mTickHandler",mock(Handler.class));
            work.observe(()->{try{finish();}catch(Exception e){throw new AssertionError(e);}});
        }
        void finish()throws Exception{call(activity,MainActivity.class,"walletWorkFinished",new Class<?>[0]);}
        void execute(){workers.remove(0).run();deliveries.remove(0).run();}
        SettingsView settings()throws Exception{
            SettingsView settings=mock(SettingsView.class,CALLS_REAL_METHODS);
            set(settings,BaseView.class,"act",activity);set(settings,SettingsView.class,"session",(BooleanSupplier)authorized::get);
            return settings;
        }
        @Override public void close(){dialogs.close();toast.close();}
    }
    @Test public void workAndResultRunOnTheirRespectiveQueues()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger operations=new AtomicInteger(),result=new AtomicInteger();
            f.activity.runAuthenticatedWalletWork(()->{operations.incrementAndGet();return 42;},result::set,e->fail(e.toString()));
            assertEquals(0,operations.get());assertEquals(0,result.get());assertTrue(f.work.busy());
            f.workers.remove(0).run();assertEquals(1,operations.get());assertEquals(0,result.get());
            f.deliveries.remove(0).run();assertEquals(42,result.get());assertFalse(f.work.busy());
            f.finish();assertEquals(1,operations.get());
        }
    }
    @Test public void pausedScreenDefersUiResultUntilForeground()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger delivered=new AtomicInteger();
            f.activity.runAuthenticatedWalletWork(()->1,v->delivered.incrementAndGet(),e->fail());
            set(f.activity,MainActivity.class,"mForeground",false);f.execute();
            assertEquals(0,delivered.get());assertEquals(true,get(f.activity,"mWaitingForWork"));
            set(f.activity,MainActivity.class,"mForeground",true);f.finish();f.finish();
            assertEquals(1,delivered.get());assertEquals(false,get(f.activity,"mWaitingForWork"));
        }
    }
    @Test public void checkedFailureIsDeliveredWithoutSuccessAndAllowsRetry()throws Exception{
        try(Fixture f=new Fixture()){
            java.io.IOException original=new java.io.IOException("fixture stream failure");AtomicReference<Exception> failure=new AtomicReference<>();
            f.activity.runAuthenticatedWalletWork(()->{throw original;},v->fail(),failure::set);f.execute();
            assertSame(original,failure.get());assertFalse(f.work.busy());
            AtomicInteger result=new AtomicInteger();f.activity.runAuthenticatedWalletWork(()->7,result::set,e->fail());f.execute();assertEquals(7,result.get());
        }
    }
    @Test public void replacedScreenCannotReceiveOldCompletion()throws Exception{
        try(Fixture f=new Fixture()){
            f.activity.runAuthenticatedWalletWork(()->42,v->fail("old screen"),e->fail("old failure"));
            set(f.activity,MainActivity.class,"mUiSession",(BooleanSupplier)()->true);f.execute();
            assertNull(get(f.activity,"mAfterWork"));assertNull(get(f.activity,"mWorkFailure"));
        }
    }
    @Test public void resultCannotAuthorizeAnotherVaultGeneration()throws Exception{
        try(Fixture f=new Fixture()){
            f.activity.runAuthenticatedWalletWork(()->true,v->fail("stale authentication"),e->fail());
            f.workers.remove(0).run();f.authorized.set(false);f.deliveries.remove(0).run();
            assertFalse(f.work.busy());assertNull(get(f.activity,"mAfterWork"));
        }
    }
    @Test public void anotherScreensWorkDoesNotOverwriteDeferredFailure()throws Exception{
        try(Fixture f=new Fixture()){
            java.io.IOException original=new java.io.IOException("original failure");AtomicReference<Exception> error=new AtomicReference<>();
            f.activity.runAuthenticatedWalletWork(()->{throw original;},v->fail(),error::set);
            set(f.activity,MainActivity.class,"mForeground",false);f.execute();assertNull(error.get());
            // A replacement Activity may start another session job before this screen resumes.
            assertTrue(f.work.start(()->{}));f.execute();assertNull(f.work.failure());
            set(f.activity,MainActivity.class,"mForeground",true);f.finish();assertSame(original,error.get());
        }
    }
    @Test public void queuedJobChecksVaultGenerationBeforeExecuting()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicReference<Exception> failure=new AtomicReference<>();
            f.activity.runAuthenticatedWalletWork(()->{fail("revoked operation");return null;},v->fail(),failure::set);
            f.authorized.set(false);f.execute();assertTrue(failure.get() instanceof SeedVault.SigningNotAllowedException);
        }
    }
    @Test public void duplicateAndRejectedJobsDoNotLoseTheCurrentOperation()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger result=new AtomicInteger();f.activity.runAuthenticatedWalletWork(()->1,result::set,e->fail());
            f.activity.runAuthenticatedWalletWork(()->{fail("duplicate");return 2;},v->fail(),e->fail());
            assertEquals(1,f.workers.size());f.execute();assertEquals(1,result.get());
        }
        try(Fixture f=new Fixture(true)){
            AtomicReference<Exception> failure=new AtomicReference<>();f.activity.runAuthenticatedWalletWork(()->1,v->fail(),failure::set);
            assertTrue(failure.get() instanceof RejectedExecutionException);assertFalse(f.work.busy());
            assertEquals(false,get(f.activity,"mWaitingForWork"));assertTrue(f.dialogs.constructed().isEmpty());
        }
    }
    @Test public void settingsAuthenticationWaitsForWorkerAndRejectsWrongPassword()throws Exception{
        try(Fixture f=new Fixture()){
            SettingsView settings=f.settings();AtomicInteger approved=new AtomicInteger();
            when(f.vault.verifyPassphrase("fixture")).thenReturn(false,true);
            call(settings,SettingsView.class,"authenticate",new Class<?>[]{String.class,Runnable.class},"fixture",(Runnable)approved::incrementAndGet);
            verify(f.vault,never()).verifyPassphrase(anyString());f.execute();assertEquals(0,approved.get());
            call(settings,SettingsView.class,"authenticate",new Class<?>[]{String.class,Runnable.class},"fixture",(Runnable)approved::incrementAndGet);
            f.execute();assertEquals(1,approved.get());verify(f.vault,times(2)).verifyPassphrase("fixture");
        }
    }
    @Test public void restoreMutatesOnlyOnWorkerAndInvalidatesOldBiometricCredential()throws Exception{
        try(Fixture f=new Fixture();MockedStatic<SeedVault> validation=mockStatic(SeedVault.class);MockedStatic<BiometricUnlock> biometric=mockStatic(BiometricUnlock.class)){
            android.content.Context app=mock(android.content.Context.class);when(f.activity.getApplicationContext()).thenReturn(app);
            byte[] encrypted={1,2,3};SettingsView settings=f.settings();
            call(settings,SettingsView.class,"doRestore",new Class<?>[]{byte[].class,String.class},encrypted,"fixture");
            verify(f.vault,never()).importVault(any(),anyString(),anyString());biometric.verifyNoInteractions();
            f.workers.remove(0).run();verify(f.vault).importVault(encrypted,"fixture","fixture");biometric.verify(()->BiometricUnlock.disable(app));
            verify(f.activity,never()).recreate();f.deliveries.remove(0).run();verify(f.activity).recreate();
        }
    }
}
