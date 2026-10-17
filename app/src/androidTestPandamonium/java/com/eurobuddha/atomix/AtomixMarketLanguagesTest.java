package com.eurobuddha.atomix;
import android.content.*;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Seedless base guard; oracle fetches disabled before Activity launch. No publish or swap is submitted. */
public class AtomixMarketLanguagesTest extends AtomixLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String KEY="0x"+"ab".repeat(64),RAW="00012.34000";
 private final Map<String,Object>oracle=new LinkedHashMap<>();private TradingContext original;
 @Before public void pauseOracle(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{original=TradingContext.active();for(String n:new String[]{"price","bid","ask","goodAtMs","lastTryMs","firstTryMs","fetching","lastError","sPrefs","appliedMid"})oracle.put(n,read(PriceOracle.class,null,n));set(PriceOracle.class,null,"fetching",true);set(PriceOracle.class,null,"price",0d);});}
 @After public void restoreOracle(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Map.Entry<String,Object>e:oracle.entrySet())set(PriceOracle.class,null,e.getKey(),e.getValue());TradingContext.setActive(original,null);});}
 static void stopTicks(MainActivity a){((Handler)get(a,"ui")).removeCallbacksAndMessages(null);}
 static EditText hint(AlertDialog d,int id){String value=Language.msg(id).toString();for(EditText e:inputs(d))if(e.getHint()!=null&&value.equals(e.getHint().toString()))return e;throw new AssertionError("Missing hint "+value);}
 static Order maker(){Order o=new Order();o.signerPk=KEY;o.commsPublicId="public market fixture";o.minimaAvail=100;o.usdtAvail=100;Order.Pair p=new Order.Pair(true,2,1,0.1);p.asks.add(new Order.Level(2,12.34));p.bids.add(new Order.Level(1,15));o.pairs.put("USDT",p);return o;}
 @Test public void editorKeepsEveryDraftAndCursorWithoutSavingOnLanguageChange()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);});
   for(TradingContext market:TradingContext.values())for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<List<EditText>>fields=new AtomicReference<>();AtomicReference<List<String>>values=new AtomicReference<>();AtomicReference<int[]>cursors=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    s.onActivity(a->{TradingContext.setActive(market,null);Design.set(a,mode);call(a,"applyChrome");set(a,"currentTab",3);call(a,"render");call(a,"editOrderDialog");stopTicks(a);d.set(dialog(a));fields.set(inputs(d.get()));assertEquals(32,fields.get().size());hint(d.get(),l10n_atomix_market_skew).setText("-0.2500");hint(d.get(),l10n_atomix_market_reprice).setText("1.5000");hint(d.get(),l10n_atomix_market_mid).setText("001.0000");hint(d.get(),l10n_atomix_market_step).setText("0.2500");hint(d.get(),l10n_atomix_market_ask_size).setText(RAW);hint(d.get(),l10n_atomix_market_bid_size).setText("0004.5000");fields.get().get(7).setText("2.0000");fields.get().get(8).setText("3.5000");List<String>raw=new ArrayList<>();int[]pos=new int[fields.get().size()];for(int i=0;i<pos.length;i++){EditText e=fields.get().get(i);raw.add(e.getText().toString());pos[i]=Math.min(3,e.length());e.setSelection(pos[i]);}values.set(raw);cursors.set(pos);saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertTrue((Boolean)get(a,"modalOpen"));assertSame(market,TradingContext.active());for(int i=0;i<fields.get().size();i++){assertEquals(values.get().get(i),fields.get().get(i).getText().toString());assertEquals(cursors.get()[i],fields.get().get(i).getSelectionStart());}exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_editor));for(int id:new int[]{l10n_atomix_market_skew,l10n_atomix_market_reprice,l10n_atomix_market_mid,l10n_atomix_market_step,l10n_atomix_market_levels,l10n_atomix_market_ask_size,l10n_atomix_market_bid_size})exact(d.get().getWindow().getDecorView(),Language.msg(id));exact(d.get().getWindow().getDecorView(),market.pricingParity?Language.msg(l10n_atomix_market_parity_help):Language.msg(l10n_atomix_market_peg_help,"MEXC",market.coinLabel));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());assertEquals(Language.msg(l10n_common_save).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());});}
    screenshot("atomix-market-editor-"+market.key+"-"+mode.name().toLowerCase(Locale.ROOT));s.onActivity(a->{for(View v=fields.get().get(31);v.getParent() instanceof View;v=(View)v.getParent())if(v instanceof ScrollView){((ScrollView)v).fullScroll(View.FOCUS_DOWN);break;}});settle();screenshot("atomix-market-levels-"+market.key+"-"+mode.name().toLowerCase(Locale.ROOT));s.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();s.onActivity(a->{stopTicks(a);assertTrue(dialogs(a).isEmpty());assertFalse((Boolean)get(a,"modalOpen"));assertNull(get(a,"pegTickRef"));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());});
   }
  }
 }
 @Test public void tradeEntryKeepsLiteralAmountQuotesAndFullCounterparty()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);});
   for(boolean sell:new boolean[]{true,false}){
    AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<EditText>amount=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    s.onActivity(a->{Design.set(a,Design.Mode.DAYLIGHT);call(a,"applyChrome");call(a,"takeOrderDialog",new Class<?>[]{Order.class,String.class,boolean.class,double.class,double.class},maker(),"USDT",sell,2d,50d);d.set(dialog(a));amount.set(inputs(d.get()).get(0));amount.get().setText(RAW);amount.get().setSelection(5);saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals(RAW,amount.get().getText().toString());assertEquals(5,amount.get().getSelectionStart());assertEquals(Language.msg(sell?l10n_atomix_market_amount_sell:l10n_atomix_market_amount_buy,TradingContext.active().coinLabel).toString(),amount.get().getHint().toString());exact(d.get().getWindow().getDecorView(),Language.msg(sell?l10n_atomix_market_receive:l10n_atomix_market_pay,"24.68","USDT"));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_counterparty,KEY));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());});}
    screenshot("atomix-market-take-"+(sell?"sell":"buy"));s.onActivity(a->{exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_counterparty,KEY)).performLongClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(KEY,cm.getPrimaryClip().getItemAt(0).getText().toString());cm.clearPrimaryClip();d.get().cancel();});settle();s.onActivity(a->{stopTicks(a);assertFalse((Boolean)get(a,"modalOpen"));assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());});
   }
  }
 }
 @Test public void bookAndStatusTranslateInPlaceAndKeepLiteralQuotes()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);((Map<String,Order>)get(a,"orderBook")).put(KEY,maker());set(a,"currentTab",3);set(a,"orderStatus","Publish failed: Node 中文 $1 {0}");});
   for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<View>page=new AtomicReference<>();s.onActivity(a->{Design.set(a,mode);call(a,"applyChrome");call(a,"render");stopTicks(a);page.set(page(a));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),page(a));exact(page(a),Language.msg(l10n_atomix_market_book,1));wrapped(exact(page(a),Language.msg(l10n_atomix_market_edit)));wrapped(exact(page(a),Language.msg(l10n_atomix_market_publish)));exact(page(a),Language.msg(l10n_atomix_market_sell_bid,TradingContext.active().coinLabel));exact(page(a),Language.msg(l10n_atomix_market_buy_ask,TradingContext.active().coinLabel));exact(page(a),Language.msg(l10n_atomix_market_publish_failed,"Node 中文 $1 {0}"));assertEquals("Publish failed: Node 中文 $1 {0}",get(a,"orderStatus"));assertEquals(12.34,((Map<String,Order>)get(a,"orderBook")).get(KEY).pairs.get("USDT").asks.get(0).amount,0);});}
    screenshot("atomix-market-book-"+mode.name().toLowerCase(Locale.ROOT));
   }
   s.onActivity(a->{((Map<String,Order>)get(a,"orderBook")).clear();call(a,"render");stopTicks(a);exact(page(a),Language.msg(l10n_atomix_market_connect));});
  }
 }
}
