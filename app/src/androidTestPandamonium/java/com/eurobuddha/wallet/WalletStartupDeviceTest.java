package com.eurobuddha.wallet;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.MinimaApplication;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Slow synthetic preparation fails before deriving a key. No vault mutation or node startup. */
@RunWith(AndroidJUnit4.class)
public class WalletStartupDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void guard() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertFalse(WalletSession.get(context).vault().exists());assertFalse(WalletSession.get(context).work().busy());Language.select(context,"en");
        });
    }
    @After public void reset() {
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(WalletSession.get(context).vault().exists());
    }
    private static Object field(MainActivity a,String name) {
        try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}
    }
    private static void field(MainActivity a,String name,Object value) {
        try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new AssertionError(e);}
    }
    private static void call(MainActivity a,String name) {
        try{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new AssertionError(e);}
    }
    private static boolean contains(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),text))return true;
        return false;
    }
    private SeedVault slowVault(CountDownLatch entered,CountDownLatch release,boolean existing) {
        java.util.concurrent.atomic.AtomicBoolean open=new java.util.concurrent.atomic.AtomicBoolean(true);
        return new SeedVault(context) {
            @Override public synchronized boolean exists(){return existing;}
            @Override public synchronized boolean isOpen(){return existing && open.get();}
            @Override public synchronized void lock(){open.set(false);}
            @Override public synchronized java.util.function.BooleanSupplier authorization(){return ()->true;}
            @Override public synchronized KeyUses keyUses(){return new KeyUses(){
                public int currentUses(int i){throw new AssertionError("No counter reads in failed startup");}
                public int reserveNextUse(int i){throw new AssertionError("No signing");}
                public void recordExternalUses(int i,int n){throw new AssertionError("No counter writes");}
                public java.util.Map<Integer,Integer> snapshotAllUses(){throw new AssertionError("No snapshot");}
            };}
            @Override public synchronized String phrase(){
                if(Looper.myLooper()==Looper.getMainLooper())throw new AssertionError("Key preparation must be off the UI thread");
                entered.countDown();
                try{if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Preparation fixture timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
                throw new IllegalStateException("synthetic preparation failure before key derivation");
            }
        };
    }
    private void slowPreparation(MainActivity a,CountDownLatch entered,CountDownLatch release) {
        field(a,"mVault",slowVault(entered,release,false));
        call(a,"startMain");
    }
    private void awaitIdle() throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+10000;
        while(WalletSession.get(context).work().busy()&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(20);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertFalse(WalletSession.get(context).work().busy());
    }
    @Test public void slowPreparationKeepsUiAndLanguageResponsiveThenShowsProtectedUnlock() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->slowPreparation(a,entered,release));assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(String code:new String[]{"zh-Hans","ru","uk","en"})scenario.onActivity(a->{
                Language.select(a,code);assertTrue(WalletSession.get(a).work().busy());
                androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field(a,"mWorkDialog");
                assertNotNull(dialog);assertTrue(dialog.isShowing());
                assertTrue(contains(dialog.getWindow().getDecorView(),Language.text(a,l10n_wallet_working)));
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell"));
            });
            release.countDown();awaitIdle();
            scenario.onActivity(a->{assertNull(a.node());assertNull(a.wallet());
                assertTrue(a.isSensitiveScreen());assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_unlock_title)));
            });
        }finally{release.countDown();awaitIdle();}
    }
    @Test public void recreatedActivityReceivesPendingFailureWithoutUsingOldViews() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<MainActivity> old=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{old.set(a);slowPreparation(a,entered,release);});assertTrue(entered.await(5,TimeUnit.SECONDS));
            scenario.recreate();scenario.onActivity(a->{assertNotSame(old.get(),a);assertTrue(old.get().isDestroyed());assertTrue(WalletSession.get(a).work().busy());});
            release.countDown();awaitIdle();
            scenario.onActivity(a->{assertFalse(a.vault().exists());assertNull(a.node());
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_setup_intro)));
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell"));
            });
        }finally{release.countDown();awaitIdle();}
    }
    @Test public void alreadyOpenSessionGetsDrawerBeforePreparationFinishes() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        WalletSession session=WalletSession.get(context);
        Field vault=WalletSession.class.getDeclaredField("mVault");vault.setAccessible(true);
        SeedVault previous=session.vault(),fixture=slowVault(entered,release,true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try{vault.set(session,fixture);session.onHandledForeground(true);}catch(Exception e){throw new AssertionError(e);}
        });
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            scenario.onActivity(a->{
                assertSame(fixture,a.vault());assertTrue(session.work().busy());
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell"));
                Language.select(a,"uk");assertNull(a.node());assertNull(a.wallet());
            });
            release.countDown();awaitIdle();
            scenario.onActivity(a->{assertFalse(fixture.isOpen());assertTrue(a.isSensitiveScreen());
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_unlock_title)));
            });
        }finally {
            release.countDown();awaitIdle();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                session.lock();try{vault.set(session,previous);}catch(Exception e){throw new AssertionError(e);}
            });
        }
    }
    @Test public void publicAndSecretScreenTransitionsHonorGlobalScreenshotChoice() {
        android.content.SharedPreferences prefs=context.getSharedPreferences("main_prefs",0);
        boolean existed=prefs.contains(MinimaApplication.PREF_ALLOW_SS),prior=prefs.getBoolean(MinimaApplication.PREF_ALLOW_SS,false);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            for(boolean allowed:new boolean[]{false,true,false})scenario.onActivity(a->{
                ((MinimaApplication)a.getApplication()).setScreenshotsAllowed(allowed);
                call(a,"showUnlock");assertTrue(a.isSensitiveScreen());
                assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                call(a,"showOnboarding");assertFalse(a.isSensitiveScreen());
                assertEquals(!allowed,(a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                assertEquals(allowed,MinimaApplication.screenshotsAllowed(a));
            });
        }finally {
            if(existed)prefs.edit().putBoolean(MinimaApplication.PREF_ALLOW_SS,prior).commit();else prefs.edit().remove(MinimaApplication.PREF_ALLOW_SS).commit();
        }
    }
}
