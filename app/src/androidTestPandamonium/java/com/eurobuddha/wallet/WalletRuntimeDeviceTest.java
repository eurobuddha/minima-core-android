package com.eurobuddha.wallet;

import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Empty disposable wallet; simulated counter/node failures and literal display fixtures only. */
@RunWith(AndroidJUnit4.class)
public class WalletRuntimeDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String[] languages={"zh-Hans","ru","uk","en"};
    @Before public void guard(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertFalse(WalletSession.get(context).vault().exists());assertFalse(WalletSession.get(context).work().busy());
            AppLock.disable(context);Language.select(context,"en");
        });
    }
    private static Object get(Object object,Class<?> cls,String name){try{Field f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(MainActivity act,String name,Object value){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(act,value);}catch(Exception e){throw new AssertionError(e);}}
    private static Object call(Object object,Class<?> cls,String name,Class<?>[] types,Object...args){try{Method m=cls.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}catch(Exception e){throw new AssertionError(e);}}
    private static void call(MainActivity a,String method){call(a,MainActivity.class,method,new Class<?>[0]);}
    private static List<TextView> textViews(View view){List<TextView> out=new ArrayList<>();if(view instanceof TextView)out.add((TextView)view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)out.addAll(textViews(g.getChildAt(i)));}return out;}
    private static boolean contains(View view,String text){for(TextView t:textViews(view))if(text.contentEquals(t.getText()))return true;return false;}
    @SuppressWarnings("unchecked") private static List<Dialog> dialogs(MainActivity a){Object tracker=get(a,MainActivity.class,"mDialogs");return new ArrayList<>(((Map<Dialog,Boolean>)get(tracker,WalletDialogs.class,"dialogs")).keySet());}
    private static KeyUses counters(AtomicBoolean unavailable){return new KeyUses(){
        public int currentUses(int i){if(unavailable.get())throw new IllegalStateException("synthetic unavailable node");return 7;}
        public int reserveNextUse(int i){throw new AssertionError("Signing forbidden");}
        public void recordExternalUses(int i,int n){throw new AssertionError("Counter writes forbidden");}
        public Map<Integer,Integer> snapshotAllUses(){return Collections.singletonMap(0,currentUses(0));}
    };}
    private static void fixture(MainActivity a,AtomicBoolean unavailable){set(a,"mKeyUses",counters(unavailable));set(a,"mUiSession",(java.util.function.BooleanSupplier)()->true);}

    @Test public void unavailableCounterDoesNotCrashMainUiSettingsOrReview() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicBoolean unavailable=new AtomicBoolean(true);
            scenario.onActivity(a->{fixture(a,unavailable);call(a,"buildUi");a.goToTab(MainActivity.TAB_SETTINGS);
                SettingsView settings=(SettingsView)get(a,MainActivity.class,"mSettingsView");
                for(String code:languages){Language.select(a,code);assertTrue(contains(settings.getRoot(),Language.text(a,l10n_wallet_counter_unavailable)));}
                call(settings,SettingsView.class,"setUsesManually",new Class<?>[0]);assertTrue(dialogs(a).isEmpty());
                a.confirmSignAndPublish("fixture review",0,()->{throw new AssertionError("No transaction build");});assertTrue(dialogs(a).isEmpty());
                unavailable.set(false);settings.refresh();assertTrue(contains(settings.getRoot(),"7 / 262144"));
                assertFalse(a.vault().isOpen());assertNull(a.node());assertNull(MinimaService.minima);
            });
        }
    }
    @Test public void sensitiveSeedDialogIsProtectedAndClearedWhenPaused() {
        AtomicReference<Dialog> dialog=new AtomicReference<>();AtomicReference<TextView> secret=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{fixture(a,new AtomicBoolean(false));SettingsView settings=new SettingsView(a);
                call(settings,SettingsView.class,"showSeedDialog",new Class<?>[]{String.class},"literal display fixture only");
                Dialog d=dialogs(a).get(0);dialog.set(d);
                assertTrue((d.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                for(TextView t:textViews(d.getWindow().getDecorView()))if("literal display fixture only".contentEquals(t.getText()))secret.set(t);
                assertNotNull(secret.get());assertFalse(secret.get().isTextSelectable());assertFalse(a.vault().isOpen());
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            assertFalse(dialog.get().isShowing());assertEquals("",secret.get().getText().toString());
        }
    }
    @Test public void languageKeepsDraftThenLockedResumeClosesEveryDialog() {
        AtomicReference<androidx.appcompat.app.AlertDialog> dialog=new AtomicReference<>();AtomicReference<EditText> field=new AtomicReference<>();
        AtomicBoolean confirmed=new AtomicBoolean(false);
        AtomicReference<java.util.function.BooleanSupplier> previous=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{fixture(a,new AtomicBoolean(false));previous.set(a.sessionAuthorization());assertTrue(previous.get().getAsBoolean());EditText input=new EditText(a);input.setText("literal draft 12.340000");input.setSelection(5);field.set(input);
                dialog.set(new WalletDialog(a).setTitle(Language.msg(l10n_wallet_confirm_transaction)).setView(input)
                        .setPositiveButton(Language.msg(l10n_wallet_continue),(d,w)->confirmed.set(true)).show());
                for(String code:languages){Language.select(a,code);assertTrue(dialog.get().isShowing());assertEquals("literal draft 12.340000",input.getText().toString());assertEquals(5,input.getSelectionStart());}
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->WalletSession.get(context).lock());
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a->{assertFalse(dialog.get().isShowing());assertTrue(dialogs(a).isEmpty());assertNull(a.keyUses());assertFalse(previous.get().getAsBoolean());
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_unlock_title)));assertFalse(confirmed.get());});
        }
    }
    @Test public void seedEntryAndUnlockWindowsAreProtectedWithoutLosingLanguageDrafts() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                call(a,MainActivity.class,"onboardImport",new Class<?>[]{boolean.class},false);
                assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                EditText draft=null;for(TextView text:textViews(a.getWindow().getDecorView()))if(text instanceof EditText){draft=(EditText)text;break;}
                assertNotNull(draft);draft.setText("literal import draft only");draft.setSelection(6);
                for(String code:languages){Language.select(a,code);assertEquals("literal import draft only",draft.getText().toString());assertEquals(6,draft.getSelectionStart());}
                call(a,"showUnlock");assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                call(a,"showOnboarding");assertEquals(0,a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);
                assertFalse(a.vault().exists());assertFalse(a.vault().isOpen());assertNull(MinimaService.minima);
            });
        }
    }
    @Test public void failedPreparationReturnsToUnlockWithConnectionCleared() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{set(a,"mVault",new SeedVault(a){@Override public synchronized java.util.function.BooleanSupplier authorization(){throw new IllegalStateException("synthetic stopped node");}});
                call(a,"startMain");assertNull(a.keyUses());assertNull(a.node());assertNull(a.factory());assertNull(a.addressBook());
                assertTrue(contains(a.getWindow().getDecorView(),Language.text(a,l10n_wallet_unlock_title)));
                assertFalse(WalletSession.get(a).vault().isOpen());assertFalse(WalletSession.get(a).vault().exists());assertNull(MinimaService.minima);
            });
        }
    }
}
