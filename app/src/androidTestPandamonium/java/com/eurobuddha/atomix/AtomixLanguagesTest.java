package com.eurobuddha.atomix;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.atomix.integratedcomms.NodeApi;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Disposable public fixtures only. Network workers are stopped before fixtures are installed. */
@RunWith(AndroidJUnit4.class)
public class AtomixLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String ADDRESS="0x0123456789abcdef0123456789abcdef01234567",RAW="Pay 中文 Русский Українська {amount}",PUBLIC_KEY="0x"+"12".repeat(64);
 private DirectNodeApi.Backend backend;private boolean prepared;
 static Object read(Class<?> c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){set(o.getClass(),o,n,v);}
 static void set(Class<?> c,Object o,String n,Object v){try{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n,Class<?>[] types,Object...args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n){return call(o,n,new Class<?>[]{});}
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");IdentityWatch.resetForTest();((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip();});
  for(String name:new String[]{"atomix","atomix_design","atomix_write_safety"})assertTrue(context.getSharedPreferences(name,0).edit().clear().commit());
  assertTrue(context.getSharedPreferences("atomix",0).edit().putBoolean("seen_welcome",true).commit());prepared=true;
 }
 @After public void reset(){if(!prepared)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{IdentityWatch.resetForTest();((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip();Language.select(context,"en");DirectNodeApi.install(backend);});assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());}
 static void freeze(MainActivity a){call(a,"stopWatcher");call(a,"stopCcyGlow");((Handler)get(a,"ui")).removeCallbacksAndMessages(null);((SwapEngine)get(a,"engine")).shutdown();((NodeApi)get(a,"node")).onDestroy();((ExecutorService)get(a,"io")).shutdownNow();set(a,"paired",false);assertTrue(((com.eurobuddha.atomix.swap.SwapDb)get(a,"db")).allSwaps().isEmpty());assertFalse((Boolean)get(a,"serviceStarted"));}
 static Object publicCredentials(){try{return Class.forName("org.web3j.crypto.Credentials").getMethod("create",String.class).invoke(null,"0x"+"0".repeat(63)+"1");}catch(Exception e){throw new AssertionError(e);}}
 static void publicWallet(MainActivity a){EthWallet wallet=(EthWallet)get(a,"wallet");set(wallet,"creds",publicCredentials());set(a,"ethAddr",ADDRESS);set(a,"ethBal","0.00012345 ETH");set(a,"minimaBal","00012.34000");set(a,"minimaConfirmed","20.34000");set(a,"minimaUnconfirmed","0.01000");set(a,"minimaCoins","2");((Map<String,String>)get(a,"tokenBals")).put("USDT","0012.34000");}
 static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
 static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
 static List<TextView> texts(View v){List<TextView> out=new ArrayList<>();if(v instanceof TextView)out.add((TextView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
 static TextView exact(View v,CharSequence label){for(TextView t:texts(v))if(label.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing "+label);}
 static List<EditText> inputs(AlertDialog d){List<EditText> out=new ArrayList<>();for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)out.add((EditText)t);return out;}
 static View page(MainActivity a){return ((ScrollView)get(a,"scroller")).getChildAt(0);}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(250);}
 void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
 static void wrapped(TextView t){assertNotNull(t.getLayout());for(int i=0;i<t.getLayout().getLineCount();i++){assertEquals(0,t.getLayout().getEllipsisCount(i));assertTrue(t.getLayout().getLineWidth(i)<=t.getWidth()-t.getPaddingLeft()-t.getPaddingRight()+2);}}
 @Test public void walletAndNavigationUpdateInPlaceInBothThemes()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);set(a,"currentTab",1);call(a,"render");});
   for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<View>page=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();s.onActivity(a->{Design.set(a,mode);call(a,"applyChrome");call(a,"render");page.set(page(a));saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),page(a));exact(page(a),Language.msg(l10n_atomix_ui_available));wrapped(exact(page(a),Language.msg(l10n_atomix_ui_export_key)));wrapped(exact(page(a),Language.msg(l10n_atomix_ui_fund)));exact((View)get(a,"tabBar"),Language.msg(l10n_tab_wallet));exact((View)get(a,"tabBar"),Language.msg(l10n_atomix_ui_activity));assertEquals("00012.34000",get(a,"minimaBal"));assertEquals(ADDRESS,get(a,"ethAddr"));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());assertTrue(((ExecutorService)get(a,"io")).isShutdown());});screenshot("atomix-wallet-"+mode.name().toLowerCase(Locale.ROOT)+"-"+lang);}
   }
  }
 }
 @Test public void sendDraftAssetAndCursorSurviveEveryLanguage()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<EditText>to=new AtomicReference<>(),amt=new AtomicReference<>();
   s.onActivity(a->{freeze(a);publicWallet(a);Design.set(a,Design.Mode.DAYLIGHT);call(a,"sendDialog");d.set(dialog(a));List<EditText>fields=inputs(d.get());assertEquals(2,fields.size());to.set(fields.get(0));amt.set(fields.get(1));to.get().setText(ADDRESS);to.get().setSelection(12);amt.get().setText("00012.34000");amt.get().setSelection(5);exact(d.get().getWindow().getDecorView(),"USDT").performClick();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals(ADDRESS,to.get().getText().toString());assertEquals(12,to.get().getSelectionStart());assertEquals("00012.34000",amt.get().getText().toString());assertEquals(5,amt.get().getSelectionStart());assertEquals(Language.msg(l10n_atomix_ui_recipient).toString(),to.get().getHint().toString());assertEquals(Language.msg(l10n_eth_wallet_review).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(Design.ON_ACCENT(),exact(d.get().getWindow().getDecorView(),"USDT").getCurrentTextColor());assertTrue(((ExecutorService)get(a,"io")).isShutdown());});screenshot("atomix-send-"+lang);}
   s.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
  }
 }
 @Test public void reviewAndFundDialogsKeepFullPublicValuesAndCallbacks()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<AlertDialog>d=new AtomicReference<>();s.onActivity(a->{freeze(a);publicWallet(a);call(a,"sendReviewDialog",new Class<?>[]{boolean.class,String.class,String.class,String.class},false,ADDRESS,"00012.34000","0.0000000042");d.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_ui_review_body,"00012.34000","USDT",ADDRESS,"0.0000000042"));assertEquals(Language.msg(l10n_atomix_ui_send_now).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());});screenshot("atomix-review-"+lang);}
   s.onActivity(a->dialog(a).dismiss());settle();s.onActivity(a->{call(a,"receiveDialog");d.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),ADDRESS);exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_ui_fund_help,"Ethereum"));});}
   s.onActivity(a->dialog(a).dismiss());settle();s.onActivity(a->{call(a,"showText",new Class<?>[]{CharSequence.class,CharSequence.class},Language.msg(l10n_atomix_ui_activity),RAW);assertTrue((Boolean)get(a,"modalOpen"));dialog(a).dismiss();});settle();s.onActivity(a->{assertFalse((Boolean)get(a,"modalOpen"));assertTrue(dialogs(a).isEmpty());});
  }
 }
 @Test public void welcomeAndCurrencyChoiceNeverSwitchMarketsOnLanguageChange()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);call(a,"showWelcome");});
   for(String method:new String[]{"welcome","switchCurrencyDialog"}){
    AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();AtomicReference<TradingContext>market=new AtomicReference<>();s.onActivity(a->{if(!"welcome".equals(method))call(a,method);d.set(dialog(a));market.set(TradingContext.active());saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertSame(market.get(),TradingContext.active());assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());exact(d.get().getWindow().getDecorView(),"welcome".equals(method)?Language.msg(l10n_atomix_ui_welcome):Language.msg(l10n_atomix_ui_switch_title,TradingContext.active().other().coinLabel));});}
    s.onActivity(a->dialog(a).dismiss());settle();
   }
  }
 }
 @Test public void mismatchKeepsFullRecoveryIdentifiersAndRemainsHalted()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<View>kept=new AtomicReference<>();s.onActivity(a->{freeze(a);IdentityWatch.setMismatchForTest(true,true);set(IdentityWatch.class,null,"orphanedPk",PUBLIC_KEY);set(IdentityWatch.class,null,"staleEth",ADDRESS);set(IdentityWatch.class,null,"nodeEth","0x"+"ab".repeat(20));call(a,"render");kept.set(page(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),page(a));exact(page(a),Language.msg(l10n_atomix_ui_mismatch_title));boolean full=false;for(TextView t:texts(page(a)))if(t.getText().toString().contains(PUBLIC_KEY)){full=true;assertTrue(t.getText().toString().contains(ADDRESS));assertTrue(t.isTextSelectable());}assertTrue(full);assertTrue(IdentityWatch.halted());assertEquals(PUBLIC_KEY,IdentityWatch.orphanedPk());});}
  }
 }
 @Test public void privateKeyDialogStaysSecureAndCopiesLiteralFixtureAsSensitive()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<String>literal=new AtomicReference<>();s.onActivity(a->{freeze(a);publicWallet(a);literal.set(((EthWallet)get(a,"wallet")).privateKeyHex());call(a,"revealKeyDialog");d.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertTrue((d.get().getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);exact(d.get().getWindow().getDecorView(),literal.get());exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_ui_secret));});}
   s.onActivity(a->{dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();s.onActivity(a->{ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertNotNull(cm.getPrimaryClip());assertEquals(literal.get(),cm.getPrimaryClip().getItemAt(0).getText().toString());assertTrue(cm.getPrimaryClip().getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));cm.clearPrimaryClip();});
  }
 }
}
