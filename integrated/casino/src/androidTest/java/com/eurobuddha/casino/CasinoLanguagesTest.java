package com.eurobuddha.casino;
import android.app.Dialog;
import android.content.Context;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Adapted from NftShellLanguagesTest. Empty disposable emulator, public fixture bets, no betting commands. */
@RunWith(AndroidJUnit4.class)
public class CasinoLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] CODES={"en","zh-Hans","ru","uk"};
 private DirectNodeApi.Backend backend;private boolean prepared;
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  assertNull(new SecretStore(context).history());assertTrue(context.getSharedPreferences("casino_secrets_plain",0).getAll().isEmpty());
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");Theme.setDollar(context,false);Theme.setSound(context,false);});prepared=true;
 }
 @After public void reset(){if(!prepared)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");Theme.setDollar(context,false);Theme.setSound(context,true);DirectNodeApi.install(backend);});assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertNull(new SecretStore(context).history());}
 static Object read(Class<?> c,Object o,String n){try{java.lang.reflect.Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n){try{java.lang.reflect.Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}catch(Exception e){throw new AssertionError(e);}}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(250);}
 static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
 static TextView exact(View root,CharSequence label){for(TextView t:texts(root))if(label.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label: "+label);}
 static class FixtureNode extends NodeApi {final List<String> commands=new ArrayList<>();FixtureNode(Context c){super(c,null);}@Override public void cmd(String command,Cb cb){commands.add(command);}}
 static FixtureNode freeze(MainActivity a){a.node().onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode n=new FixtureNode(a);set(a,"node",n);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return n;}
 static BaseView page(MainActivity a,int tab){return ((BaseView[])get(a,"views"))[tab];}
 static Dialog dialog(MainActivity a){Set<Dialog> d=(Set<Dialog>)get(a,"languageDialogs");assertEquals(1,d.size());return d.iterator().next();}
 void screenshot(String name)throws Exception{settle();android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o));}finally{b.recycle();}}
 @Test public void houseDraftSelectionAndBothCurrenciesSurviveLanguageChanges()throws Exception {
  for(boolean dollar:new boolean[]{false,true}){
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Theme.setDollar(context,dollar);Theme.setSound(context,false);});
   try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
    settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<BaseView> house=new AtomicReference<>();AtomicReference<EditText> amount=new AtomicReference<>();
    s.onActivity(a->{node.set(freeze(a));a.switchTab(MainActivity.TAB_HOUSE);house.set(page(a,MainActivity.TAB_HOUSE));amount.set((EditText)get(house.get(),"amount"));amount.get().setText("0012.340000");amount.get().setSelection(5);((LinearLayout)get(house.get(),"presetRow")).getChildAt(1).performClick();exact(house.get().getRoot(),CasinoMessages.message("Create Bet")).performClick();node.get().commands.clear();});settle();
    for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
     assertSame(house.get(),page(a,MainActivity.TAB_HOUSE));assertSame(amount.get(),get(house.get(),"amount"));assertEquals("0012.340000",amount.get().getText().toString());assertEquals(5,amount.get().getSelectionStart());assertEquals(CasinoContract.Game.DICE,get(house.get(),"selected"));assertEquals(dollar,Theme.dollar());assertEquals(dollar?Currency.USD_TOKENID:"0x00",Currency.tokenId());
     View root=house.get().getRoot();exact(root,Language.msg(l10n_casino_be_house));exact(root,Language.upper(Language.msg(l10n_casino_bet_amount,Currency.label())));exact(root,Language.msg(l10n_casino_identity_missing));exact(root,Language.msg(l10n_casino_house_summary,Currency.show("61.7"),Currency.show("12.34"),Currency.show("74.04"),Currency.show("74.04"),5,6));
     com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_casino_tabs);String[] raw={"PLAY","HOUSE","MY BETS","HISTORY"};for(int i=0;i<raw.length;i++)assertEquals(Language.upper(CasinoMessages.message(raw[i])).toString(),tabs.getTabAt(i).getText().toString());assertEquals(MainActivity.TAB_HOUSE,tabs.getSelectedTabPosition());assertTrue(node.get().commands.toString(),node.get().commands.isEmpty());
    });if(!dollar)screenshot("casino-house-"+code);}
   }
  }
 }
 private static Bet bet(){Coin c=new Coin();c.coinid="0x"+"45".repeat(32);c.tokenid=Currency.USD_TOKENID;c.amount="24.680000";c.created=100;c.state.put(CasinoContract.P_HOUSE_PK,"0x"+"12".repeat(32));c.state.put(CasinoContract.P_HOUSE_COMMIT,"0x"+"34".repeat(32));c.state.put(CasinoContract.P_RANGE,"2");c.state.put(CasinoContract.P_PAYOUT,"2");c.state.put(CasinoContract.P_BET,"12.340000");c.state.put(CasinoContract.P_PHASE,"0");return new Bet(c);}
 @Test public void playPickAndInFlightGuardRemainStableWithoutTakingABet()throws Exception {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Theme.setDollar(context,true));
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<BaseView> play=new AtomicReference<>();Bet bet=bet();Map<Integer,String> state=new HashMap<>(bet.coin.state);
   s.onActivity(a->{node.set(freeze(a));a.bets().add(bet);play.set(page(a,MainActivity.TAB_PLAY));play.get().refresh();exact(play.get().getRoot(),CasinoMessages.message("Tails")).performClick();exact(play.get().getRoot(),CasinoMessages.message("Take Bet")).performClick();node.get().commands.clear();});
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(play.get(),page(a,MainActivity.TAB_PLAY));assertEquals(1,((Map<String,Integer>)get(play.get(),"picks")).get(bet.coinid()).intValue());View root=play.get().getRoot();exact(root,Language.join("✦","  ",Language.msg(l10n_casino_coin_flip)));exact(root,Language.msg(l10n_casino_heads));exact(root,Language.msg(l10n_casino_tails));exact(root,Language.msg(l10n_casino_stakes,"12.34 USD","24.68 USD","24.68 USD"));exact(root,Language.msg(l10n_casino_identity_missing));assertEquals(state,bet.coin.state);assertEquals("Coin Flip",bet.gameName());assertEquals("Tails",bet.game().pickLabel(1));assertTrue(node.get().commands.isEmpty());});screenshot("casino-play-"+code);}
   s.onActivity(a->{((Set<String>)get(play.get(),"taking")).add(bet.coinid());play.get().refresh();});
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertTrue(((Set<String>)get(play.get(),"taking")).contains(bet.coinid()));assertFalse(exact(play.get().getRoot(),Language.msg(l10n_casino_taking)).isEnabled());exact(play.get().getRoot(),Language.msg(l10n_casino_taking_confirm));assertTrue(node.get().commands.isEmpty());});}
  }
 }
 @Test public void shellStatusAndEmptyLogDialogRebindInPlace(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Dialog> kept=new AtomicReference<>();s.onActivity(a->{node.set(freeze(a));a.log("Create failed: Identity not loaded yet",MainActivity.LOG_ERR);((java.util.ArrayDeque<String>)get(a,"logLines")).clear();call(a,"showLogDialog");kept.set(dialog(a));node.get().commands.clear();});settle();
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_casino_activity_log));exact(root,Language.msg(l10n_casino_no_activity));exact(root,Language.msg(l10n_language_close));exact(a.getWindow().getDecorView(),Language.join("✕ ",Language.msg(l10n_casino_create_failed,Language.msg(l10n_casino_identity_missing))));assertTrue(node.get().commands.isEmpty());});}
   s.onActivity(a->exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_language_close)).performClick());settle();s.onActivity(a->assertTrue(((Set<?>)get(a,"languageDialogs")).isEmpty()));
  }
 }
}
