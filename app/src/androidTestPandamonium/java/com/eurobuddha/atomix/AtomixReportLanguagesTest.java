package com.eurobuddha.atomix;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Read-only public fixture replies. Workers are stopped; no signing or report recheck is clicked. */
public class AtomixReportLanguagesTest extends AtomixMarketLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String ADDRESS="0x"+"13".repeat(64),HASH="0x"+"24".repeat(32),RAW="Node 中文 Русский Українська $1 {amount}";
 static class FixtureNode extends AtomixOperationLanguagesTest.FixtureNode {
  JSONArray rows;String error;
  FixtureNode(Context c){super(c);coins=2;rows=new JSONArray().put(AtomixOperationLanguagesTest.object("amount","0.0000001","tokenamount","00012.3400","address",ADDRESS,"miniaddress",MinimaHtlc.HTLC_ADDRESS)).put(AtomixOperationLanguagesTest.object("amount","1.6600","address",SwapOrderBook.address(),"miniaddress",""));}
  @Override public void cmd(String command,Cb cb){
   if(error!=null){commands.add(command);new Handler(Looper.getMainLooper()).post(()->cb.onError(error));}
   else if(command.startsWith("coins relevant:true")){commands.add(command);new Handler(Looper.getMainLooper()).post(()->cb.onResult(AtomixOperationLanguagesTest.object("status",true,"response",rows)));}
   else super.cmd(command,cb);
  }
 }
 static FixtureNode fixture(MainActivity a,TradingContext market){TradingContext.setActive(market,null);FixtureNode n=new FixtureNode(a);MinimaHtlc m=new MinimaHtlc(n);m.setActiveToken(market.tokenId);set(a,"minima",m);return n;}
 @Test public void reportRetainsNestedEvidenceLiteralIdsAndUnknownLines()throws Exception{
  List<String>lines=Arrays.asList("Sell 00012.34000 MINIMA → 0001.2000 USDT  ·  started","Hashlock: "+HASH,"Minima lookup: completed, depth 1024, minimum coin age 2","• Your 00012.34000 MINIMA: LOCKED — refundable at block 001234 (~56 min)","• Counterparty USDT leg: FOUND 0001.2000 USDT — locked; waiting for the secret","Recorded STARTED transaction: "+ADDRESS,"Recorded transactions: NONE. No transaction id is recorded locally. A broadcast may have completed despite a lost reply. Check the on-chain state before retrying or deciding whether funds need recovery.","⚠ "+RAW);
  List<String>before=new ArrayList<>(lines);
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->{Design.set(a,mode);call(a,"showReport",new Class<?>[]{String.class,List.class},HASH,lines);d.set(dialog(a));stopTicks(a);});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));StringBuilder expected=new StringBuilder();for(String l:lines){if(expected.length()>0)expected.append("\n\n");expected.append(ReportMessages.message(l));}TextView body=exact(d.get().getWindow().getDecorView(),expected);assertTrue(body.isTextSelectable());assertTrue(body.getText().toString().contains(ADDRESS));assertTrue(body.getText().toString().contains(HASH));assertTrue(body.getText().toString().contains("00012.34000"));assertTrue(body.getText().toString().contains(RAW));assertEquals(before,lines);assertEquals(Language.msg(l10n_atomix_history_again).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());});}
    screenshot("atomix-report-"+mode.name().toLowerCase(Locale.ROOT));run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{assertFalse((Boolean)get(a,"modalOpen"));stopTicks(a);});
   }
  }
 }
 @Test public void coinBreakdownPreservesFullAddressesTokenAmountsAndTotals()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(TradingContext market:TradingContext.values()){
    AtomicReference<FixtureNode>n=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->{n.set(fixture(a,market));call(a,"minimaCoinDump");});settle();run.onActivity(a->d.set(dialog(a)));
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_report_breakdown));CharSequence expected=Language.join(Language.msg(l10n_atomix_report_balance,"00012.34","20.34",2),"00012.34   ",ADDRESS,Language.msg(l10n_atomix_report_htlc),"\n1.66   ",SwapOrderBook.address(),Language.msg(l10n_atomix_report_book),"\n",Language.msg(l10n_atomix_report_sum,"14"));assertTrue(exact(d.get().getWindow().getDecorView(),expected).isTextSelectable());assertEquals(Arrays.asList("balance tokenid:"+market.tokenId,"coins relevant:true tokenid:"+market.tokenId+" simplestate:true"),n.get().commands);assertEquals(2,n.get().rows.length());});}
    screenshot("atomix-coins-"+market.key);run.onActivity(a->{dialog(a).dismiss();n.get().onDestroy();stopTicks(a);});settle();
   }
  }
 }
 @Test public void oversizedCoinReplyKeepsReadLimitAndOffersReviewWithoutSigning()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(TradingContext market:TradingContext.values()){
    AtomicReference<FixtureNode>n=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->{FixtureNode node=fixture(a,market);node.coins=401;n.set(node);call(a,"minimaCoinDump");});settle();run.onActivity(a->d.set(dialog(a)));
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_report_oversized,"12.34000","12.34000","0",401,MinimaHtlc.maxSafeCoinRows(market.tokenId)));assertEquals(Collections.singletonList("balance tokenid:"+market.tokenId),n.get().commands);assertEquals(Language.msg(l10n_atomix_operation_consolidate).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());});}
    screenshot("atomix-coins-large-"+market.key);run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();run.onActivity(a->{assertEquals(Arrays.asList("balance tokenid:"+market.tokenId,"balance tokenid:"+market.tokenId),n.get().commands);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_atomix_operation_title,market==TradingContext.MINIMA?"MINIMA":"MxUSD"));dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();n.get().onDestroy();stopTicks(a);});settle();
   }
  }
 }
 @Test public void failedCoinReadKeepsRawDiagnosticAndDoesNotReadCoins()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>n=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->{freeze(a);publicWallet(a);FixtureNode node=fixture(a,TradingContext.MINIMA);node.error=RAW;n.set(node);call(a,"minimaCoinDump");});settle();run.onActivity(a->d.set(dialog(a)));
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_report_coin_failed,RAW));assertEquals(Collections.singletonList("balance tokenid:"+TradingContext.MINIMA.tokenId),n.get().commands);});}
   screenshot("atomix-coins-failed");run.onActivity(a->{dialog(a).dismiss();n.get().onDestroy();stopTicks(a);});
  }
 }
}
