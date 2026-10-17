package com.eurobuddha.wallet;

import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Synthetic in-memory vault state and held jobs; no stored seed, node, signing or backup I/O. */
@RunWith(AndroidJUnit4.class)
public class WalletWorkDeviceTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    static void main(Runnable job){InstrumentationRegistry.getInstrumentation().runOnMainSync(job);}
    static Object get(Object target,Class<?> type,String field){try{Field f=type.getDeclaredField(field);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    static void set(Object target,Class<?> type,String field,Object value){try{Field f=type.getDeclaredField(field);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new AssertionError(e);}}
    static boolean contains(View view,String text){
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),text))return true;
        return false;
    }
    @Before public void guard(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        main(()->{assertFalse(WalletSession.get(context).vault().exists());assertFalse(WalletSession.get(context).work().busy());AppLock.disable(context);Language.select(context,"en");});
    }
    static class FakeVault extends SeedVault {
        volatile boolean opened=true;
        final AtomicInteger signingChecks=new AtomicInteger(), snapshots=new AtomicInteger();
        FakeVault(){super(new BlobStore(){public boolean contains(){return false;}public String read(){return null;}public void write(String ignored){throw new AssertionError("No vault writes");}},(KeyUses)null);}
        @Override public synchronized void assertSigningAllowed(){
            if(!opened)throw new SeedVault.SigningNotAllowedException("Fixture locked");signingChecks.incrementAndGet();
        }
        @Override public synchronized void syncKeyUses(int... indices){snapshots.incrementAndGet();}
        @Override public synchronized boolean exists(){return true;}
        @Override public synchronized boolean isOpen(){return opened;}
        @Override public synchronized void lock(){opened=false;}
        @Override public synchronized java.util.function.BooleanSupplier authorization(){return ()->opened;}
        @Override public synchronized String phrase(){throw new AssertionError("No seed");}
    }
    class Fixture implements AutoCloseable {
        final WalletSession session=WalletSession.get(context);
        final FakeVault vault=new FakeVault();
        final SeedVault original=session.vault();
        final Object last=get(session,WalletSession.class,"mLastActivity"),background=get(session,WalletSession.class,"mBackgroundedAt");
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger operations=new AtomicInteger(),result=new AtomicInteger();
        final AtomicReference<Exception> error=new AtomicReference<>();
        void install(MainActivity a){
            set(session,WalletSession.class,"mVault",vault);set(session,WalletSession.class,"mBackgroundedAt",0L);
            set(a,MainActivity.class,"mVault",vault);set(a,MainActivity.class,"mUiSession",vault.authorization());
            TextView text=new TextView(a);text.setText("Disposable wallet worker fixture");a.setContentView(text);
        }
        void start(MainActivity a){
            a.runAuthenticatedWalletWork(()->{
                assertNotSame(Looper.getMainLooper(),Looper.myLooper());operations.incrementAndGet();started.countDown();
                if(!release.await(45,TimeUnit.SECONDS))throw new IllegalStateException("Fixture release timeout");return 42;
            },result::set,error::set);
        }
        void awaitStarted()throws Exception{assertTrue(started.await(15,TimeUnit.SECONDS));}
        void finish()throws Exception{
            release.countDown();long end=System.currentTimeMillis()+15000;
            while(session.work().busy()&&System.currentTimeMillis()<end)android.os.SystemClock.sleep(30);
            assertFalse(session.work().busy());InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        @Override public void close()throws Exception{
            finish();main(()->{set(session,WalletSession.class,"mVault",original);set(session,WalletSession.class,"mLastActivity",last);
                set(session,WalletSession.class,"mBackgroundedAt",background);set(session,WalletSession.class,"mLockAfterWork",false);
                assertFalse(original.exists());assertNull(MinimaService.minima);});
        }
    }
    @Test public void busyDialogRemainsResponsiveAndTranslated()throws Exception{
        try(Fixture f=new Fixture();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<Dialog> dialog=new AtomicReference<>();
            scenario.onActivity(a->{f.install(a);f.start(a);dialog.set((Dialog)get(a,MainActivity.class,"mWorkDialog"));});f.awaitStarted();
            for(String code:new String[]{"zh-Hans","ru","uk","en"})scenario.onActivity(a->{
                Language.select(a,code);assertSame(dialog.get(),get(a,MainActivity.class,"mWorkDialog"));assertTrue(dialog.get().isShowing());
                assertTrue(contains(dialog.get().getWindow().getDecorView(),Language.text(a,l10n_wallet_working)));
                assertTrue(contains(dialog.get().getWindow().getDecorView(),Language.text(a,l10n_wallet_working_body)));
                a.runAuthenticatedWalletWork(()->{throw new AssertionError("Duplicate job");},v->fail(),e->fail());
            });
            assertEquals(0,f.result.get());f.finish();assertEquals(42,f.result.get());assertEquals(1,f.operations.get());assertNull(f.error.get());
            scenario.onActivity(a->{assertNull(get(a,MainActivity.class,"mWorkDialog"));assertNull(a.factory());assertNull(a.node());});
        }
    }
    @Test public void pausedCompletionWaitsForResume()throws Exception{
        try(Fixture f=new Fixture();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{f.install(a);f.start(a);});f.awaitStarted();scenario.moveToState(Lifecycle.State.STARTED);
            f.finish();assertEquals(0,f.result.get());assertNull(f.error.get());
            scenario.moveToState(Lifecycle.State.RESUMED);scenario.onActivity(a->{assertEquals(42,f.result.get());assertEquals(1,f.operations.get());});
        }
    }
    @Test public void lockDuringWorkSuppressesResultOnReturn()throws Exception{
        try(Fixture f=new Fixture();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{f.install(a);f.start(a);});f.awaitStarted();scenario.moveToState(Lifecycle.State.CREATED);
            main(f.session::lock);f.finish();assertFalse(f.vault.isOpen());assertEquals(0,f.result.get());
            scenario.moveToState(Lifecycle.State.RESUMED);scenario.onActivity(a->{assertEquals(0,f.result.get());
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_unlock_title)));assertNull(a.factory());assertNull(a.node());});
        }
    }
    @Test public void expiredSessionRejectsDeferredFileWork()throws Exception{
        try(Fixture f=new Fixture();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{f.install(a);set(f.session,WalletSession.class,"mLastActivity",1L);set(f.session,WalletSession.class,"mBackgroundedAt",1L);
                a.runAuthenticatedWalletWork(()->{f.operations.incrementAndGet();return 1;},v->fail(),e->fail());
                assertEquals(0,f.operations.get());assertFalse(f.session.work().busy());assertFalse(f.vault.isOpen());
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_unlock_title)));
            });
        }
    }
    @Test public void approvedSigningUsesWorkerAndRetainsTranslatedProgress()throws Exception{
        try(Fixture f=new Fixture();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<Dialog> dialog=new AtomicReference<>();
            scenario.onActivity(a->{f.install(a);a.runSigningWork(()->{
                assertNotSame(Looper.getMainLooper(),Looper.myLooper());f.operations.incrementAndGet();f.started.countDown();
                if(!f.release.await(45,TimeUnit.SECONDS))throw new IllegalStateException("Fixture release timeout");
                return null; // No transaction or signature is created.
            },built->{assertSame(Looper.getMainLooper(),Looper.myLooper());f.result.incrementAndGet();},f.error::set);
                dialog.set((Dialog)get(a,MainActivity.class,"mWorkDialog"));});
            f.awaitStarted();assertEquals(1,f.vault.signingChecks.get());assertEquals(0,f.vault.snapshots.get());
            for(String code:new String[]{"zh-Hans","ru","uk","en"})scenario.onActivity(a->{
                Language.select(a,code);assertSame(dialog.get(),get(a,MainActivity.class,"mWorkDialog"));
                assertTrue(contains(dialog.get().getWindow().getDecorView(),Language.text(a,l10n_wallet_working)));
            });
            f.finish();assertEquals(1,f.result.get());assertEquals(1,f.operations.get());assertEquals(1,f.vault.snapshots.get());assertNull(f.error.get());
        }
    }
}
