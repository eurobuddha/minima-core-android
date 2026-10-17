package com.eurobuddha.minimacore.integrated;

import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.wallet.MainActivity;
import com.eurobuddha.wallet.WalletSession;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** No seed import, signing, node startup or navigation around the stopped-node gate. */
@RunWith(AndroidJUnit4.class)
public class WalletNavigationTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void prepare() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        assertFalse(context.getSharedPreferences("main_prefs",0).getBoolean("SEED_SET",false));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(WalletSession.get(context).vault().exists());
            assertFalse(WalletSession.get(context).work().busy());
            Language.select(context,"en");
        });
    }
    @After public void reset() {
        if(!"ranchu".equals(Build.HARDWARE) || !"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> Language.select(context,"en"));
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        assertFalse(WalletSession.get(context).vault().exists());
    }
    private static void form(MainActivity a,String method,Class<?>[] types,Object... args) {
        try {Method m=MainActivity.class.getDeclaredMethod(method,types);m.setAccessible(true);m.invoke(a,args);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void form(MainActivity a,String method){form(a,method,new Class<?>[0]);}
    private static <T extends View> List<T> views(View root,Class<T> type) {
        List<T> result=new ArrayList<>();if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return result;
    }
    private static DrawerLayout drawer(android.app.Activity a) {
        DrawerLayout d=a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell");
        assertNotNull(d);return d;
    }
    private static void settle(){android.os.SystemClock.sleep(500);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    @Test public void setupUnlockAndLanguageSelectorKeepOneDrawerAndLiteralDrafts() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<DrawerLayout> shell=new AtomicReference<>();AtomicReference<EditText> field=new AtomicReference<>();
            scenario.onActivity(a -> {
                shell.set(drawer(a));
                form(a,"onboardImport",new Class<?>[]{boolean.class},false);
                assertSame(shell.get(),drawer(a));
                List<EditText> fields=views(a.findViewById(android.R.id.content),EditText.class);assertEquals(4,fields.size());
                field.set(fields.get(0));field.get().setText("literal draft 中文 Русский Українська");field.get().setSelection(3,9);
                assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            });
            String[] codes={"zh-Hans","ru","uk","en"};String[] names={"简体中文","Русский","Українська","English"};
            for(int i=0;i<codes.length;i++) {
                scenario.onActivity(a -> {
                    drawer(a).openDrawer(Gravity.LEFT,false);
                    assertTrue(drawer(a).findViewWithTag("pandamonium.navigation.language").performClick());
                });
                onView(withText(names[i])).inRoot(isDialog()).perform(click());settle();
                assertEquals(codes[i],Language.code());
                scenario.onActivity(a -> {
                    assertSame(shell.get(),drawer(a));assertTrue(field.get().isAttachedToWindow());
                    assertEquals("literal draft 中文 Русский Українська",field.get().getText().toString());
                    assertEquals(3,field.get().getSelectionStart());assertEquals(9,field.get().getSelectionEnd());
                    assertEquals(Language.text(a,l10n_wallet_seed_hint),field.get().getHint().toString());
                    assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                    drawer(a).closeDrawer(Gravity.LEFT,false);
                });
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            scenario.onActivity(a -> {
                assertSame(shell.get(),drawer(a));
                assertTrue("Returning to seed entry must keep screenshot protection",
                        (a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                assertEquals("literal draft 中文 Русский Українська",field.get().getText().toString());
                android.content.SharedPreferences prefs=a.getSharedPreferences("main_prefs",0);
                boolean existed=prefs.contains("ALLOW_SCREENSHOTS"), prior=prefs.getBoolean("ALLOW_SCREENSHOTS",false);
                try {
                    ((com.eurobuddha.minimacore.MinimaApplication)a.getApplication()).setScreenshotsAllowed(true);
                    assertTrue("The general setting cannot expose seed entry",
                            (a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                } finally {
                    if(existed)prefs.edit().putBoolean("ALLOW_SCREENSHOTS",prior).commit();else prefs.edit().remove("ALLOW_SCREENSHOTS").commit();
                }
            });
            scenario.onActivity(a -> {
                form(a,"showUnlock");assertSame(shell.get(),drawer(a));assertFalse(field.get().isAttachedToWindow());
                assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                form(a,"showOnboarding");assertSame(shell.get(),drawer(a));
                assertEquals(0,a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);
                assertEquals(1,views(a.findViewById(android.R.id.content),DrawerLayout.class).size());
                TextView menu=null;
                for(TextView t:views(a.findViewById(android.R.id.content),TextView.class))
                    if(Language.text(a,l10n_drawer_open).contentEquals(t.getContentDescription()==null?"":t.getContentDescription()))menu=t;
                assertNotNull(menu);assertTrue(menu.performClick());
            });settle();
            scenario.onActivity(a -> assertTrue(drawer(a).isDrawerOpen(Gravity.LEFT)));
            java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());
            android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"wallet-navigation.png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
        }
    }
    @Test public void walletEntryUsesExistingVisibilityOrderAndStoppedNodeGate() throws Exception {
        int wallet=PandamoniumDestinations.indexFor(MainActivity.class.getName());assertTrue(wallet>0);
        android.content.SharedPreferences nav=context.getSharedPreferences("pandamonium_navigation",0);
        String oldOrder=nav.getString("app_order",null),oldDisabled=nav.getString("app_disabled",null);
        try {
            assertTrue(nav.edit().putString("app_order",MainActivity.class.getName()).putString("app_disabled","").commit());
            try(ActivityScenario<com.eurobuddha.blockexplorer.MainActivity> scenario=ActivityScenario.launch(com.eurobuddha.blockexplorer.MainActivity.class)) {
                AtomicReference<android.app.Activity> original=new AtomicReference<>();
                scenario.onActivity(a -> {
                    original.set(a);drawer(a).openDrawer(Gravity.LEFT,false);
                    RecyclerView list=drawer(a).findViewWithTag("pandamonium.navigation.entries");
                    assertEquals(wallet,list.getAdapter().getItemId(1));
                    assertTrue(PandamoniumHooks.integratedApps(a).get(wallet).enabled);
                });settle();
                scenario.onActivity(a -> {
                    RecyclerView list=drawer(a).findViewWithTag("pandamonium.navigation.entries");
                    RecyclerView.ViewHolder row=list.findViewHolderForAdapterPosition(1);assertNotNull(row);
                    assertEquals("minimaWallet",((TextView)row.itemView).getText().toString());assertTrue(row.itemView.performClick());
                });settle();
                scenario.onActivity(a -> {
                    assertSame(original.get(),a);
                    assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED,a.getLifecycle().getCurrentState());
                    assertFalse(drawer(a).isDrawerOpen(Gravity.LEFT));
                    PandamoniumHooks.setIntegratedEnabled(a,wallet,false);
                    assertFalse(PandamoniumHooks.integratedApps(a).get(wallet).enabled);
                    drawer(a).openDrawer(Gravity.LEFT,false);
                });settle();
                scenario.onActivity(a -> {
                    RecyclerView list=drawer(a).findViewWithTag("pandamonium.navigation.entries");
                    for(int i=0;i<list.getAdapter().getItemCount();i++)assertNotEquals(wallet,list.getAdapter().getItemId(i));
                    drawer(a).closeDrawer(Gravity.LEFT,false);
                    PandamoniumHooks.setIntegratedEnabled(a,wallet,true);drawer(a).openDrawer(Gravity.LEFT,false);
                });settle();
                scenario.onActivity(a -> {
                    RecyclerView list=drawer(a).findViewWithTag("pandamonium.navigation.entries");assertEquals(wallet,list.getAdapter().getItemId(1));
                    android.content.pm.ActivityInfo info;
                    try{info=a.getPackageManager().getActivityInfo(new android.content.ComponentName(a,MainActivity.class),0);}catch(Exception e){throw new AssertionError(e);}
                    assertFalse(info.exported);
                });
            }
        }finally {
            android.content.SharedPreferences.Editor e=nav.edit();
            if(oldOrder==null)e.remove("app_order");else e.putString("app_order",oldOrder);
            if(oldDisabled==null)e.remove("app_disabled");else e.putString("app_disabled",oldDisabled);
            assertTrue(e.commit());
        }
    }
}
