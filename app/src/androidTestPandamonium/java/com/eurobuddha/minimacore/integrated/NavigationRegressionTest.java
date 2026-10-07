package com.eurobuddha.minimacore.integrated;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Reuses the existing disposable emulator wallet. Never clears or restores node data. */
public class NavigationRegressionTest {
    private void ui(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }
    private Activity await(String name) {
        long deadline=SystemClock.elapsedRealtime()+600_000;
        while(SystemClock.elapsedRealtime()<deadline) {
            AtomicReference<Activity> result=new AtomicReference<>();
            ui(() -> { for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(a.getClass().getName().equals(name)) result.set(a); });
            if(result.get()!=null) return result.get();
            SystemClock.sleep(200);
        }
        throw new AssertionError("Missing activity "+name);
    }
    private View find(View v,String description) {
        if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
            View result=find(((ViewGroup)v).getChildAt(i),description);if(result!=null)return result;
        }
        return null;
    }
    @Test public void compactHeaderAndLongPressOrderPersist() throws Exception {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue("Emulator only",android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Reuse an initialized disposable wallet",ctx.getSharedPreferences("main_prefs",0).getBoolean("SEED_SET",false));
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("pandamonium_navigation",0);
        String prior=prefs.getString("app_order",null);
        try {
            assertTrue(prefs.edit().putString("app_order","").commit());
            // This is a layout/gesture test, independent of offline chain startup readiness.
            ctx.startActivity(new Intent(ctx,com.eurobuddha.minimacore.main.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity core=await(PandamoniumDestinations.CLASSES[0]);
            SystemClock.sleep(700);
            ui(() -> {
                androidx.appcompat.widget.Toolbar toolbar=core.findViewById(com.eurobuddha.minimacore.R.id.toolbar);
                View brand=core.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.brand");
                assertSame(toolbar,brand.getParent());
                int[] title=new int[2],bar=new int[2];brand.getLocationOnScreen(title);toolbar.getLocationOnScreen(bar);
                assertTrue("Centred brand",Math.abs(title[0]+brand.getWidth()/2-(bar[0]+toolbar.getWidth()/2))<=2);
                find(core.findViewById(android.R.id.content),"Open Minima Core menu").performClick();
            });
            SystemClock.sleep(500);
            int[] point=new int[3];
            ui(() -> {
                View eth=find(core.findViewById(android.R.id.content),"ETH Wallet");
                assertNotNull(eth);int[] xy=new int[2];eth.getLocationOnScreen(xy);
                point[0]=xy[0]+eth.getWidth()/2;point[1]=xy[1]+eth.getHeight()/2;
                point[2]=eth.getHeight()+Math.round(4*ctx.getResources().getDisplayMetrics().density);
            });
            long down=SystemClock.uptimeMillis();
            gesture(down,android.view.MotionEvent.ACTION_DOWN,point[0],point[1]);
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+250);
            ui(() -> assertEquals("Long hold enters drag mode",.8f,find(core.findViewById(android.R.id.content),"ETH Wallet").getAlpha(),.01f));
            for(int i=1;i<=12;i++) { gesture(down,android.view.MotionEvent.ACTION_MOVE,point[0],point[1]+point[2]*1.4f*i/12f);SystemClock.sleep(35); }
            gesture(down,android.view.MotionEvent.ACTION_UP,point[0],point[1]+point[2]*1.4f);
            SystemClock.sleep(600);
            assertEquals(Integer.valueOf(2),PandamoniumOrder.decode(prefs.getString("app_order","")).get(1));
            ui(() -> { ((androidx.drawerlayout.widget.DrawerLayout)core.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell")).closeDrawer(android.view.Gravity.LEFT,false); core.recreate(); });
            SystemClock.sleep(800);
            Activity recreated=await(PandamoniumDestinations.CLASSES[0]);
            ui(() -> {
                find(recreated.findViewById(android.R.id.content),"Open Minima Core menu").performClick();
                androidx.recyclerview.widget.RecyclerView rows=recreated.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.entries");
                assertEquals(2L,rows.getAdapter().getItemId(1));
            });
        } finally {
            android.content.SharedPreferences.Editor edit=prefs.edit();
            if(prior==null) edit.remove("app_order");else edit.putString("app_order",prior);
            assertTrue(edit.commit());
        }
    }
    private void gesture(long down,int action,float x,float y) {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event,true)); }
        finally { event.recycle(); }
    }
    @Test public void vestrReadsEmbeddedNodeAndOpensExistingForms() throws Exception {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue("Emulator only",android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.startActivity(new Intent(ctx,com.eurobuddha.minimacore.StartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity core=await(PandamoniumDestinations.CLASSES[0]);
        ui(() -> core.startActivity(new Intent(core,com.eurobuddha.vestr.MainActivity.class)));
        com.eurobuddha.vestr.MainActivity vestr=(com.eurobuddha.vestr.MainActivity)await("com.eurobuddha.vestr.MainActivity");
        java.util.concurrent.CountDownLatch reply=new java.util.concurrent.CountDownLatch(1);
        AtomicReference<org.json.JSONObject> result=new AtomicReference<>();
        AtomicReference<String> error=new AtomicReference<>();
        ui(() -> vestr.node().cmd("status",new com.eurobuddha.vestr.NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) { result.set(json); reply.countDown(); }
            @Override public void onError(String message) { error.set(message); reply.countDown(); }
        }));
        assertTrue("Vestr direct response",reply.await(35,java.util.concurrent.TimeUnit.SECONDS));
        assertNull(error.get()); assertNotNull(result.get()); assertTrue(result.get().toString(),result.get().optBoolean("status"));
        assertNotNull(result.get().optJSONObject("response"));
        AtomicReference<String> script=new AtomicReference<>();
        long deadline=SystemClock.elapsedRealtime()+30_000;
        do { ui(() -> script.set(vestr.scriptAddress())); if(!script.get().isEmpty())break; SystemClock.sleep(100); }
        while(SystemClock.elapsedRealtime()<deadline);
        assertFalse("Vestr resolves its existing contract",script.get().isEmpty());
        ui(() -> vestr.startActivity(new Intent(vestr,com.eurobuddha.vestr.CreateContractActivity.class).putExtra("script",script.get())));
        Activity create=await("com.eurobuddha.vestr.CreateContractActivity");
        ui(() -> { assertTrue(create.findViewById(com.eurobuddha.vestr.R.id.pm_vestr_formContainer).isShown()); create.finish(); });
        await("com.eurobuddha.vestr.MainActivity");
        ui(() -> vestr.startActivity(new Intent(vestr,com.eurobuddha.vestr.CalculatorActivity.class)));
        Activity calculator=await("com.eurobuddha.vestr.CalculatorActivity");
        ui(() -> { assertTrue(calculator.findViewById(com.eurobuddha.vestr.R.id.pm_vestr_formContainer).isShown()); calculator.finish(); });
    }
    @Test public void toolbarAndHistoryAndNativeMenus() throws Exception {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue("Emulator only",android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        for (String label : PandamoniumDestinations.NAMES) assertNotEquals("Minima History", label);
        assertEquals("Minima Vestr", PandamoniumDestinations.NAMES[9]);
        assertEquals("com.eurobuddha.vestr.MainActivity", PandamoniumDestinations.CLASSES[9]);
        android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("main_prefs", android.content.Context.MODE_PRIVATE);
        if (!prefs.getBoolean("SEED_SET",false)) {
            assertFalse("Never replace an existing wallet",new java.io.File(ctx.getFilesDir(),"databases/wallet.mv.db").exists());
            assertTrue(prefs.edit().putBoolean("SEED_SET",true).putString("SEED",
                    org.minima.utils.BIP39.convertWordListToString(org.minima.utils.BIP39.getNewWordList())).commit());
        }
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        ctx.startActivity(new Intent(ctx,com.eurobuddha.minimacore.StartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity core=await(PandamoniumDestinations.CLASSES[0]);
        ui(() -> {
            androidx.appcompat.widget.Toolbar toolbar=core.findViewById(com.eurobuddha.minimacore.R.id.toolbar);
            View menu=find(core.findViewById(android.R.id.content),"Open Minima Core menu");
            assertNotNull(menu); assertSame("Menu is on overflow toolbar row",toolbar,menu.getParent());
            View brand=core.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.brand");
            assertNotNull(brand); assertSame("Brand shares the menu row",toolbar,brand.getParent());
            assertEquals("No duplicate core title","",toolbar.getTitle());
            com.google.android.material.tabs.TabLayout tabs=core.findViewById(com.eurobuddha.minimacore.R.id.tabs);
            assertEquals("History",tabs.getTabAt(2).getText()); tabs.getTabAt(2).select();
        });
        SystemClock.sleep(1500);
        ui(() -> {
            assertTrue(core.findViewById(com.eurobuddha.history.R.id.pm_history_recycler).isShown());
            core.findViewById(com.eurobuddha.history.R.id.pm_history_search).requestFocus();
            core.findViewById(com.eurobuddha.history.R.id.pm_history_menuBtn).performClick();
        });
        SystemClock.sleep(400);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        for(String name:PandamoniumDestinations.CLASSES) {
            ui(() -> core.startActivity(new Intent().setClassName(ctx.getPackageName(),name).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            Activity app=await(name);
            SystemClock.sleep(300);
            ui(() -> {
                View menu=find(app.findViewById(android.R.id.content),"Open Minima Core menu");
                assertNotNull(name,menu); assertTrue(name,menu.isShown());
                int[] xy=new int[2];menu.getLocationOnScreen(xy);
                assertTrue("Left edge: "+name,xy[0]<app.getResources().getDisplayMetrics().widthPixels/3);
                menu.performClick();
                androidx.drawerlayout.widget.DrawerLayout drawer=app.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell");
                drawer.closeDrawer(android.view.Gravity.LEFT,false);
            });
        }
        ui(() -> core.startActivity(new Intent().setClassName(ctx.getPackageName(),PandamoniumDestinations.CLASSES[0]).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        await(PandamoniumDestinations.CLASSES[0]);
        ui(core::recreate);
        SystemClock.sleep(800);
        Activity recreated=await(PandamoniumDestinations.CLASSES[0]);
        SystemClock.sleep(500);
        ui(() -> ((com.google.android.material.tabs.TabLayout)recreated.findViewById(com.eurobuddha.minimacore.R.id.tabs)).getTabAt(2).select());
        SystemClock.sleep(800);
        ui(() -> assertTrue(recreated.findViewById(com.eurobuddha.history.R.id.pm_history_recycler).isShown()));
    }
}
