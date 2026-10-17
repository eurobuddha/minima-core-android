package com.eurobuddha.atomix;
import android.content.*;
import android.net.Uri;
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
/** Reuses the seedless, stopped-engine fixture. No node calls, signing or publishing. */
public class AtomixHistoryLanguagesTest extends AtomixMarketLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String CP="0x"+"ac".repeat(64);
 private static final String[] STATES={SwapDb.ST_STARTED,SwapDb.ST_LOCKED,SwapDb.ST_CLAIMING,SwapDb.ST_COMPLETE,SwapDb.ST_REFUNDED,SwapDb.ST_ERROR,"CUSTOM_状态"};
 static SwapDb.Swap fixture(String hash,String coin,String state,String role){SwapDb.Swap s=new SwapDb.Swap();s.hash=hash;s.role=role;s.direction="MINIMA_TO_ERC20";s.sellToken=coin;s.sellAmount="00012.34000";s.buyToken="USDT";s.buyAmount="1.234000";s.counterparty=CP;s.status=state;s.created=System.currentTimeMillis()-120000;s.updated=s.created;s.myLegIsMinima=true;s.myTimelock=2345678;return s;}
 static LinearLayout blank(MainActivity a){ScrollView sc=(ScrollView)get(a,"scroller");sc.removeAllViews();LinearLayout col=new LinearLayout(a);col.setOrientation(LinearLayout.VERTICAL);col.setPadding(16,16,16,16);sc.addView(col);return col;}
 static void showCard(MainActivity a,SwapDb.Swap value){LinearLayout col=blank(a);col.addView((View)call(a,"historySwapCard",new Class<?>[]{SwapDb.Swap.class},value));col.addView((View)call(a,"swapCard",new Class<?>[]{SwapDb.Swap.class},value));stopTicks(a);}
 @Test public void historyCardsKeepRecordsAndCounterpartyAcrossLanguages()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(Design.Mode mode:Design.Mode.values())for(String state:STATES){
    SwapDb.Swap value=fixture("0x"+"31".repeat(32),"mxUSDT",state,"RESPONDER");AtomicReference<View>kept=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    run.onActivity(a->{Design.set(a,mode);call(a,"applyChrome");showCard(a,value);kept.set(page(a));saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(kept.get(),page(a));exact(page(a),"00012.34000 mxUSDT  →  1.234000 USDT");exact(page(a),(CharSequence)call(a,"statusLabel",new Class<?>[]{SwapDb.Swap.class},value));boolean role=false;for(TextView t:texts(page(a)))if(t.getText().toString().contains(Language.msg(l10n_atomix_history_maker).toString()))role=true;assertTrue(role);assertEquals(state,value.status);assertEquals(CP,value.counterparty);assertEquals("00012.34000",value.sellAmount);assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());});}
    if(SwapDb.ST_STARTED.equals(state))screenshot("atomix-history-cards-"+mode.name().toLowerCase(Locale.ROOT));
   }
   run.onActivity(a->{SwapDb.Swap value=fixture("0x"+"31".repeat(32),"MINIMA",SwapDb.ST_COMPLETE,"INITIATOR");showCard(a,value);TextView meta=null;for(TextView t:texts(page(a)))if(t.getText().toString().contains(" · ")&&t.getText().toString().contains(Language.msg(l10n_atomix_history_taker).toString())){meta=t;break;}assertNotNull(meta);assertTrue(meta.performClick());});settle();run.onActivity(a->{ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(CP,cm.getPrimaryClip().getItemAt(0).getText().toString());cm.clearPrimaryClip();SwapDb.Swap v=fixture("x","MINIMA",null,null);assertEquals("",call(a,"statusLabel",new Class<?>[]{SwapDb.Swap.class},v).toString());assertEquals("",call(a,"statusDetail",new Class<?>[]{SwapDb.Swap.class},v).toString());});
  }
 }
 @Test public void hideAndReportDialogsRetainCallbacksAndLiteralEvidence()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();String hash="0x"+"45".repeat(32);
   run.onActivity(a->{saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));call(a,"confirmHideSwap",new Class<?>[]{String.class},hash);d.set(dialog(a));stopTicks(a);});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_history_hide_title));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_history_hide_help));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());});}
   screenshot("atomix-history-hide");run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());call(a,"confirmHideSwap",new Class<?>[]{String.class},hash);dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();run.onActivity(a->{stopTicks(a);assertTrue(((SharedPreferences)get(a,"prefs")).getString("dismissed_swaps","").contains(hash));});
   String raw="fixture diagnostic 中文 Русский Українська\n"+CP;
   run.onActivity(a->{call(a,"showReport",new Class<?>[]{String.class,List.class},hash,Arrays.asList(raw));d.set(dialog(a));stopTicks(a);});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_history_report));assertTrue(exact(d.get().getWindow().getDecorView(),raw).isTextSelectable());assertEquals(Language.msg(l10n_atomix_history_again).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());});}
   run.onActivity(a->d.get().dismiss());settle();
  }
 }
 @Test public void marketHistoryAndRefundUnitsUpdateWithoutChangingMarket()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(TradingContext market:TradingContext.values())for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<View>kept=new AtomicReference<>();SwapDb.Swap refund=fixture("unit",market.coinLabel,SwapDb.ST_STARTED,"INITIATOR");
    run.onActivity(a->{TradingContext.setActive(market,null);Design.set(a,mode);call(a,"applyChrome");set(a,"historyTab",1);set(a,"currentTab",2);call(a,"render");stopTicks(a);kept.set(page(a));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(kept.get(),page(a));assertSame(market,TradingContext.active());for(int id:new int[]{l10n_atomix_history_mine,l10n_atomix_history_market,l10n_atomix_history_export,l10n_atomix_history_collecting})wrapped(exact(page(a),Language.msg(id)));exact(page(a),Language.msg(l10n_atomix_history_market_empty,market.coinLabel));assertEquals(Language.msg(l10n_atomix_history_block,2345678L).toString(),call(a,"countdown",new Class<?>[]{SwapDb.Swap.class},refund).toString());});}
    screenshot("atomix-history-market-"+market.key+"-"+mode.name().toLowerCase(Locale.ROOT));
    run.onActivity(a->{LinearLayout col=blank(a);for(String state:new String[]{SwapDb.MT_OPEN,SwapDb.MT_EXECUTED,SwapDb.MT_REFUNDED}){SwapDb.MarketTrade t=new SwapDb.MarketTrade();t.status=state;t.price=1.25;t.sizeMinima="12.34000";t.observedAt=System.currentTimeMillis()-60000;col.addView((View)call(a,"tradeRow",new Class<?>[]{SwapDb.MarketTrade.class},t));}stopTicks(a);});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{exact(page(a),"  12.3 "+market.coinLabel);for(int id:new int[]{l10n_atomix_history_filled,l10n_atomix_history_cancelled,l10n_atomix_history_open})exact(page(a),Language.msg(id));});}
   }
   run.onActivity(a->{SwapDb.Swap v=fixture("unit","MINIMA",SwapDb.ST_STARTED,"INITIATOR");v.myLegIsMinima=false;v.myTimelock=1;assertEquals(Language.msg(l10n_atomix_history_window).toString(),call(a,"countdown",new Class<?>[]{SwapDb.Swap.class},v).toString());v.myTimelock=System.currentTimeMillis()/1000+121;assertEquals(Language.msg(l10n_atomix_history_minutes,2L).toString(),call(a,"countdown",new Class<?>[]{SwapDb.Swap.class},v).toString());});
  }
 }
 @Test public void csvSchemaAndLiteralRowsStayStableInEveryLanguage()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<SwapDb>db=new AtomicReference<>();AtomicReference<java.io.File>file=new AtomicReference<>();String h1="0x"+"65".repeat(32),h2="0x"+"76".repeat(32);
   run.onActivity(a->{freeze(a);publicWallet(a);db.set((SwapDb)get(a,"db"));SwapDb.Swap one=fixture(h1,"MINIMA",SwapDb.ST_COMPLETE,"INITIATOR"),two=fixture(h2,"mxUSDT",SwapDb.ST_REFUNDED,"RESPONDER");one.created=two.created=1700000000000L;two.counterparty="=literal,\"中文\"";db.get().upsertSwap(one);db.get().upsertSwap(two);file.set(new java.io.File(a.getCacheDir(),"language-history-fixture.csv"));});
   try{String before=null;for(String lang:LANGS){run.onActivity(a->{Language.select(a,lang);assertTrue(!file.get().exists()||file.get().delete());call(a,"doExportCsv",new Class<?>[]{Uri.class},Uri.fromFile(file.get()));});String csv="";for(int n=0;n<100;n++){Thread.sleep(50);if(file.get().exists()){csv=new String(java.nio.file.Files.readAllBytes(file.get().toPath()),java.nio.charset.StandardCharsets.UTF_8);if(csv.endsWith("\r\n")&&csv.contains(h2))break;}}assertTrue(csv.startsWith("Date,Role,Direction,Sold Amount,Sold Token,Bought Amount,Bought Token,Price (USDT/MINIMA),Counterparty,Status,Contract Id,Minima Tx,Eth Tx,Currency\r\n"));assertTrue(csv.contains("\"Taker\",\"Sell MINIMA\""));assertTrue(csv.contains("\"Maker\",\"Sell mxUSDT\""));assertTrue(csv.contains("\"00012.34000\""));assertTrue(csv.contains("\"'=literal,\"\"中文\"\"\""));if(before==null)before=csv;else assertEquals(before,csv);}}finally{run.onActivity(a->{db.get().deleteSwap(h1);db.get().deleteSwap(h2);assertTrue(db.get().allSwaps().isEmpty());file.get().delete();stopTicks(a);});}
  }
 }
}
