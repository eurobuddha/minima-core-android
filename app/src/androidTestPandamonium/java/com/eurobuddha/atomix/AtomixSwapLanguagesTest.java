package com.eurobuddha.atomix;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Seedless, stopped workers and public makers. No confirmation that starts a swap is clicked. */
public class AtomixSwapLanguagesTest extends AtomixMarketLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String RAW="00012.34000";
 static List<EditText> fields(View v){List<EditText>r=new ArrayList<>();for(TextView t:texts(v))if(t instanceof EditText)r.add((EditText)t);return r;}
 static void book(MainActivity a){Order o=maker();((Map<String,Order>)get(a,"orderBook")).put(o.signerPk,o);set(a,"paired",true);set(a,"currentTab",0);}
 @Test public void twoWayAmountsDirectionAndCursorSurviveAllLanguages()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);book(a);});
   for(TradingContext market:TradingContext.values())for(Design.Mode mode:Design.Mode.values())for(boolean sell:new boolean[]{true,false}){
    AtomicReference<View>kept=new AtomicReference<>();AtomicReference<List<EditText>>input=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();AtomicReference<String>send=new AtomicReference<>(),receive=new AtomicReference<>();
    run.onActivity(a->{set(a,"swapInputFocused",false);TradingContext.setActive(market,null);Design.set(a,mode);call(a,"applyChrome");set(a,"swapSell",sell);set(a,"swapAmount",RAW);call(a,"render");stopTicks(a);kept.set(page(a));input.set(fields(page(a)));assertEquals(2,input.get().size());input.get().get(0).setText(RAW);input.get().get(0).setSelection(4);input.get().get(1).setSelection(2);set(a,"swapInputFocused",true);send.set(input.get().get(0).getText().toString());receive.set(input.get().get(1).getText().toString());saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(kept.get(),page(a));assertEquals(sell,get(a,"swapSell"));assertSame(market,TradingContext.active());assertEquals(send.get(),input.get().get(0).getText().toString());assertEquals(receive.get(),input.get().get(1).getText().toString());assertEquals(4,input.get().get(0).getSelectionStart());assertEquals(2,input.get().get(1).getSelectionStart());assertEquals(send.get(),get(a,"swapAmount"));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());for(int id:new int[]{l10n_atomix_swap_send,l10n_atomix_swap_receive,l10n_atomix_swap_review})wrapped(exact(page(a),Language.msg(id)));assertEquals(Language.msg(l10n_atomix_swap_send).toString(),input.get().get(0).getContentDescription().toString());if(!sell)wrapped(exact(page(a),Language.msg(l10n_atomix_swap_slippage)));});}
    if(market==TradingContext.MINIMA)screenshot("atomix-swap-"+(sell?"sell":"buy")+"-"+mode.name().toLowerCase(Locale.ROOT));
    run.onActivity(a->{input.get().get(1).setText("0003.5000");input.get().get(1).setSelection(3);stopTicks(a);send.set(input.get().get(0).getText().toString());});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(kept.get(),page(a));assertEquals("0003.5000",input.get().get(1).getText().toString());assertEquals(3,input.get().get(1).getSelectionStart());assertEquals(send.get(),get(a,"swapAmount"));});}
   }
   run.onActivity(a->{set(a,"swapInputFocused",false);set(a,"paired",false);call(a,"render");stopTicks(a);});
  }
 }
 @Test public void singleReviewsKeepFullMakerAndAmountsWithoutSubmitting()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});Order maker=maker();
   for(TradingContext market:TradingContext.values())for(boolean sell:new boolean[]{true,false}){
    AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    run.onActivity(a->{TradingContext.setActive(market,null);set(a,"swapAmount",RAW);saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));call(a,"reviewSwap",new Class<?>[]{Order.class,boolean.class,String.class,double.class,double.class},maker,sell,RAW,2d,100d);d.set(dialog(a));stopTicks(a);});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(sell?l10n_atomix_swap_review_sell:l10n_atomix_swap_review_buy,market.coinLabel));boolean full=false;for(TextView t:texts(d.get().getWindow().getDecorView()))if(t.getText().toString().contains(maker.signerPk)){full=true;assertTrue(t.getText().toString().contains(sell?"24.68":"6.17"));}assertTrue(full);assertEquals(RAW,get(a,"swapAmount"));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());assertEquals(Language.msg(l10n_atomix_swap_start).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());});}
    if(market==TradingContext.MINIMA)screenshot("atomix-swap-review-"+(sell?"sell":"buy"));run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{assertEquals(RAW,get(a,"swapAmount"));assertFalse((Boolean)get(a,"modalOpen"));assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());stopTicks(a);});
   }
  }
 }
 @Test public void sweepReviewsRetainPlanPartialFillAndCopyKeys()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);Order o=maker();o.pairs.get("USDT").asks.clear();o.pairs.get("USDT").asks.add(new Order.Level(2,4));o.pairs.get("USDT").asks.add(new Order.Level(3,10));o.pairs.get("USDT").bids.clear();o.pairs.get("USDT").bids.add(new Order.Level(1,5));o.pairs.get("USDT").bids.add(new Order.Level(.9,10));((Map<String,Order>)get(a,"orderBook")).put(o.signerPk,o);});
   for(boolean sell:new boolean[]{true,false}){
    AtomicReference<Object>plan=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<String>before=new AtomicReference<>();
    run.onActivity(a->{set(a,"swapAmount",RAW);Object p=call(a,"buildSweepPlan",new Class<?>[]{boolean.class,String.class,double.class},sell,"12",.1d);plan.set(p);assertEquals(sell?2:1,((List<?>)get(p,"legs")).size());assertEquals(!sell,get(p,"partial"));before.set(snapshot(p));call(a,"reviewSweep",new Class<?>[]{p.getClass()},p);d.set(dialog(a));stopTicks(a);});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals(before.get(),snapshot(plan.get()));assertEquals(RAW,get(a,"swapAmount"));exact(d.get().getWindow().getDecorView(),Language.msg(sell?l10n_atomix_swap_sweep_sell_title:l10n_atomix_swap_sweep_buy_title));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_swap_sweep_note));if(!sell){exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_swap_sweep_gas,1));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_swap_partial,"4","12",TradingContext.active().coinLabel,Language.msg(l10n_atomix_swap_partial_slip,"10")));}assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());});}
    screenshot("atomix-swap-sweep-"+(sell?"sell":"buy"));run.onActivity(a->{for(TextView t:texts(d.get().getWindow().getDecorView()))if(t.getText().toString().startsWith(Language.msg(l10n_atomix_swap_part,1,"","","","","").toString().split(" · ")[0])){t.performClick();break;}});settle();run.onActivity(a->{ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertNotNull(cm.getPrimaryClip());assertEquals(maker().signerPk,cm.getPrimaryClip().getItemAt(0).getText().toString());cm.clearPrimaryClip();d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});settle();run.onActivity(a->stopTicks(a));
   }
  }
 }
 static String snapshot(Object p){StringBuilder b=new StringBuilder();for(String n:new String[]{"sell","target","filledMinima","totalUsdt","avgPrice","worstPrice","slippagePct","partial","stopReason"})b.append(n).append('=').append(get(p,n)).append(';');for(Object l:(List<?>)get(p,"legs"))for(String n:new String[]{"price","minima","usdt"})b.append(n).append('=').append(get(l,n)).append(';');return b.toString();}
 @Test public void slippageDraftCursorAndCancelAreRetained()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<EditText>field=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();run.onActivity(a->{freeze(a);publicWallet(a);saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));call(a,"customSlippageDialog");d.set(dialog(a));field.set(inputs(d.get()).get(0));field.get().setText("0004.200");field.get().setSelection(4);stopTicks(a);});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals("0004.200",field.get().getText().toString());assertEquals(4,field.get().getSelectionStart());assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_swap_slippage_title));});}
   screenshot("atomix-swap-slippage");run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());call(a,"customSlippageDialog");inputs(dialog(a)).get(0).setText("3.5");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();run.onActivity(a->{assertEquals(3.5f,((SharedPreferences)get(a,"prefs")).getFloat("buy_slippage_pct",0),0);assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());stopTicks(a);});
  }
 }
}
