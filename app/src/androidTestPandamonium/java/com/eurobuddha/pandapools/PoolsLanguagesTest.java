package com.eurobuddha.pandapools;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.google.android.material.tabs.TabLayout;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Reuses Casino/AtomiX stopped-worker fixtures; no owned pools, node, seed or transactions. */
public class PoolsLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private DirectNodeApi.Backend backend;private boolean prepared;
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));assertTrue(OwnPoolStore.all(context).isEmpty());
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");Design.set(context,Design.Mode.CURRENT);});prepared=true;
 }
 @After public void reset(){if(!prepared)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip();Language.select(context,"en");Design.set(context,Design.Mode.CURRENT);DirectNodeApi.install(backend);});assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertTrue(OwnPoolStore.all(context).isEmpty());}
 static Object read(Class<?>c,Object o,String n){try{java.lang.reflect.Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n,Class<?>[]types,Object...args){try{java.lang.reflect.Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n){return call(o,n,new Class<?>[0]);}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(250);}
 static List<TextView>texts(View v){List<TextView>r=new ArrayList<>();if(v instanceof TextView)r.add((TextView)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)r.addAll(texts(((ViewGroup)v).getChildAt(i)));return r;}
 static TextView exact(View v,CharSequence s){for(TextView t:texts(v))if(s.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label: "+s);}
 static void wrapped(TextView t){assertNotNull(t.getLayout());int width=t.getWidth()-t.getCompoundPaddingLeft()-t.getCompoundPaddingRight();for(int i=0;i<t.getLayout().getLineCount();i++)assertTrue(t.getText().toString(),t.getLayout().getLineMax(i)<=width+2);}
 static class CountingPools extends PoolRepository {int scans;CountingPools(NodeApi n){super(n);}@Override public void refresh(){scans++;}@Override public void requestNow(PoolBook.Listener l){scans++;}}
 static CountingPools freeze(MainActivity a){call(a,"stopPolling");for(BaseView v:(BaseView[])get(a,"views")){v.onStop();v.onDestroy();}a.node().onDestroy();a.ui().removeCallbacksAndMessages(null);CountingPools p=new CountingPools(a.node());set(a,"poolRepo",p);return p;}
 static BaseView page(MainActivity a,int i){return ((BaseView[])get(a,"views"))[i];}
 void screenshot(String name)throws Exception{settle();android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);java.io.File d=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(d.isDirectory()||d.mkdirs());try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(d,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o));}finally{b.recycle();}}
 @Test public void navigationRetainsAllPagesSwapDraftCursorAndCurrentTab()throws Exception{
  for(Design.Mode mode:Design.Mode.values()){
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,mode));
   try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
    settle();AtomicReference<BaseView[]>pages=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<EditText>draft=new AtomicReference<>();AtomicReference<Map<String,?>>prefs=new AtomicReference<>();
    run.onActivity(a->{repo.set(freeze(a));pages.set(((BaseView[])get(a,"views")).clone());draft.set(a.findViewById(R.id.pm_pandapools_swapAmount));draft.get().setText("00012.34000");draft.get().setSelection(4);a.goToTab(MainActivity.TAB_POOLS);repo.get().scans=0;prefs.set(new HashMap<>(a.getSharedPreferences("pandapools",0).getAll()));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertArrayEquals(pages.get(),(BaseView[])get(a,"views"));assertSame(draft.get(),a.findViewById(R.id.pm_pandapools_swapAmount));assertEquals("00012.34000",draft.get().getText().toString());assertEquals(4,draft.get().getSelectionStart());assertEquals(MainActivity.TAB_POOLS,a.currentTab());assertEquals(0,repo.get().scans);assertEquals(prefs.get(),a.getSharedPreferences("pandapools",0).getAll());TabLayout tabs=a.findViewById(R.id.pm_pandapools_tabs);int[]labels={l10n_pools_swap,l10n_pools_pools,l10n_pools_my_lp,l10n_pools_wallet,l10n_pools_activity};for(int i=0;i<labels.length;i++)assertEquals(Language.msg(labels[i]).toString(),tabs.getTabAt(i).getText().toString());assertEquals(Language.msg(l10n_pools_style_description).toString(),a.findViewById(R.id.pm_pandapools_designToggle).getContentDescription().toString());wrapped(exact(page(a,MainActivity.TAB_POOLS).getRoot(),Language.msg(l10n_pools_scan)));});}
    screenshot("pools-shell-"+mode.name().toLowerCase(Locale.ROOT));
   }
  }
 }
 static Pool pool(int n){Pool p=new Pool();p.address="0x"+(n==0?"ab":"cd").repeat(64);p.tok="0x"+"12".repeat(32);p.tokName="pool address";p.reserveM=new BigDecimal(n==0?"100.0000":"150.0000");p.reserveT=new BigDecimal(n==0?"200.0000":"300.0000");p.kmin=n==0?"20000":"45000";return p;}
 @Test public void individualAndCombinedCardsKeepAmountsFullIdentifiersAndCopyValues()throws Exception{
  for(Design.Mode mode:new Design.Mode[]{Design.Mode.CURRENT,Design.Mode.ORIGINAL_LIGHT}){
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,mode));
   try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
    settle();List<Pool>data=Arrays.asList(pool(0),pool(1));AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<PoolsView>view=new AtomicReference<>();
    run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_POOLS);view.set((PoolsView)page(a,MainActivity.TAB_POOLS));call(view.get(),"render",new Class<?>[]{List.class},data);repo.get().scans=0;});
    for(boolean combined:new boolean[]{false,true}){
     run.onActivity(a->call(view.get(),"setMode",new Class<?>[]{boolean.class},combined));View root=view.get().getRoot();
     for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(view.get(),page(a,MainActivity.TAB_POOLS));assertSame(data,get(view.get(),"lastPools"));assertEquals(combined,get(view.get(),"combined"));exact(root,Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_summary,2,2,"250"));assertEquals(new BigDecimal("100.0000"),data.get(0).reserveM);assertEquals(new BigDecimal("300.0000"),data.get(1).reserveT);assertEquals("pool address",data.get(0).tokName);assertEquals(0,repo.get().scans);
      if(combined){exact(root,Language.msg(l10n_pools_combined_title,"pool address"));exact(root,"250 MINIMA  ·  500 pool address");exact(root,"2 pool address / MINIMA");exact(root,"250 MINIMA");}
      else{exact(root,"MINIMA / pool address");TextView addr=exact(root,Language.msg(l10n_pools_copy_value,data.get(0).address));wrapped(addr);((View)addr.getParent()).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(data.get(0).address,cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(Language.msg(l10n_pools_pool_address).toString(),cm.getPrimaryClip().getDescription().getLabel().toString());cm.clearPrimaryClip();exact(root,"100 MINIMA  ·  200 pool address");}
     });}
     screenshot("pools-cards-"+(combined?"combined":"individual")+"-"+mode.name().toLowerCase(Locale.ROOT));
    }
   }
  }
 }
 @Test public void emptyAndFailedScanMessagesRebindWithoutLosingRawDiagnostics()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<PoolsView>view=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();String raw="Node 中文 Русский Українська $1 {count} 00012.34000";
   run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_POOLS);view.set((PoolsView)page(a,MainActivity.TAB_POOLS));call(view.get(),"render",new Class<?>[]{List.class},Collections.emptyList());repo.get().scans=0;});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{exact(view.get().getRoot(),Language.msg(l10n_pools_no_active));exact(view.get().getRoot(),Language.msg(l10n_pools_no_live));assertEquals(0,repo.get().scans);});}
   run.onActivity(a->((PoolBook.Listener)get(view.get(),"poolListener")).onError(raw));
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{exact(view.get().getRoot(),Language.msg(l10n_pools_scan_error,raw));assertEquals(0,repo.get().scans);});}
   screenshot("pools-scan-error");
  }
 }
 @Test public void identifierRowPreservesLiteralValueAndTranslatesCopyLabel()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<TextView>row=new AtomicReference<>();String full="0x"+"ef".repeat(64);AtomicReference<CountingPools>repo=new AtomicReference<>();
   run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_POOLS);LinearLayout list=a.findViewById(R.id.pm_pandapools_poolList);row.set(Ui.identifierRow(a,"Txpow id",full));list.addView(row.get());repo.get().scans=0;});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertEquals(Language.msg(l10n_pools_identifier,Language.msg(l10n_pools_txpow_id),full).toString(),row.get().getText().toString());wrapped(row.get());row.get().performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(full,cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(Language.msg(l10n_pools_txpow_id).toString(),cm.getPrimaryClip().getDescription().getLabel().toString());cm.clearPrimaryClip();assertEquals(0,repo.get().scans);});}
   screenshot("pools-identifier");
  }
 }

}
