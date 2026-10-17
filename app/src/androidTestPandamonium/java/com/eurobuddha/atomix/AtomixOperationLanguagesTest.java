package com.eurobuddha.atomix;
import android.content.*;
import android.os.*;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.integratedcomms.NodeApi;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Read-only replies and stopped workers; no real node or signed command. */
public class AtomixOperationLanguagesTest extends AtomixMarketLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 static JSONObject object(Object... kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
 static class FixtureNode extends NodeApi {
  final List<String>commands=new ArrayList<>();int coins=240,block=100;String pending="0";final JSONObject preview=object("status",true,"response",object("literal","中文 Русский Українська {0}","coinid","0x"+"12".repeat(32)));
  FixtureNode(Context c){super(c,null);}
  @Override public void cmd(String command,Cb cb){commands.add(command);JSONObject reply;
   if(command.startsWith("balance tokenid:"))reply=object("status",true,"response",object("tokenid",command.substring(16),"coins",coins,"sendable","12.34000","confirmed","12.34000","unconfirmed",pending));
   else if(command.equals("block"))reply=object("status",true,"response",object("block",block));
   else if(command.startsWith("consolidate tokenid:")&&command.endsWith(" dryrun:true"))reply=preview;
   else throw new AssertionError("Unexpected or signing command: "+command);
   new Handler(Looper.getMainLooper()).post(()->cb.onResult(reply));
  }
 }
 static FixtureNode fixture(MainActivity a,TradingContext market){TradingContext.setActive(market,null);FixtureNode n=new FixtureNode(a);MinimaHtlc m=new MinimaHtlc(n);m.setActiveToken(market.tokenId);set(a,"minima",m);return n;}
 static void operationWrapped(android.widget.TextView t){assertNotNull(t.getLayout());for(int i=0;i<t.getLayout().getLineCount();i++){assertEquals(0,t.getLayout().getEllipsisCount(i));// Android line width includes trailing spaces; line max measures visible text.
float width=t.getLayout().getLineMax(i);int available=t.getWidth()-t.getPaddingLeft()-t.getPaddingRight();assertTrue("line="+i+" width="+width+" available="+available+" text="+t.getText(),width<=available+2);}}
 @After public void clearConsolidationGate(){SwapEngine.endConsolidate();}
 @Test public void progressPanelsRetainRawStateAcrossLanguages()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);AtomixSwapLanguagesTest.book(a);});
   for(int tab:new int[]{0,3})for(String raw:new String[]{"Starting swap…","✓ Part 2/3 lock sent — confirming on-chain before the next part…","Sweep stopped — 2 of 3 parts done (part 3 failed: Node 中文 {0}). Locked parts continue; unmatched auto-refund.","Publish failed: Node 中文 {0}"}){
    AtomicReference<View>kept=new AtomicReference<>();run.onActivity(a->{set(a,"currentTab",tab);set(a,"orderStatus",raw);call(a,"render");stopTicks(a);kept.set(page(a));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(kept.get(),page(a));assertEquals(raw,get(a,"orderStatus"));wrapped(exact(page(a),OperationMessages.message(raw)));assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());});}
   }
   screenshot("atomix-operation-progress");run.onActivity(a->{set(a,"paired",false);stopTicks(a);});
  }
 }
 @Test public void consolidationReviewKeepsTokenCountsAndCancelWithoutSigning()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(TradingContext market:TradingContext.values())for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    run.onActivity(a->{Design.set(a,mode);node.set(fixture(a,market));saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));call(a,"consolidateDialog");});settle();run.onActivity(a->d.set(dialog(a)));
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();screenshot("atomix-operation-review-debug-"+market.key+"-"+mode.name().toLowerCase(Locale.ROOT)+"-"+lang);run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_operation_title,market==TradingContext.MINIMA?"MINIMA":"MxUSD"));int cap=MinimaHtlc.maxSafeCoinRows(market.tokenId),runs=(240-cap+17)/18;operationWrapped(exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_operation_body,240,cap,Language.msg(l10n_atomix_operation_runs,runs))));assertEquals(Language.msg(l10n_atomix_operation_preview).toString(),d.get().getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());assertEquals(Language.msg(l10n_atomix_operation_run).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());assertEquals(Collections.singletonList("balance tokenid:"+market.tokenId),node.get().commands);});}
    screenshot("atomix-operation-review-"+market.key+"-"+mode.name().toLowerCase(Locale.ROOT));run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{node.get().onDestroy();stopTicks(a);assertFalse((Boolean)get(a,"modalOpen"));});
   }
  }
 }
 @Test public void consolidationPreviewKeepsRawJsonAndNeverSigns()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->{freeze(a);publicWallet(a);node.set(fixture(a,TradingContext.MINIMA));call(a,"consolidateOnce",new Class<?>[]{String.class,boolean.class},TradingContext.MINIMA.tokenId,true);});settle();run.onActivity(a->d.set(dialog(a)));
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_operation_preview_title));assertTrue(exact(d.get().getWindow().getDecorView(),node.get().preview.toString()).isTextSelectable());assertFalse(((SharedPreferences)get(a,"prefs")).contains("consolidate_until_"+TradingContext.MINIMA.tokenId));assertEquals(4,node.get().commands.size());assertTrue(node.get().commands.get(3).endsWith(" dryrun:true"));assertTrue(SwapEngine.beginConsolidate());SwapEngine.endConsolidate();});}
   screenshot("atomix-operation-preview");run.onActivity(a->{dialog(a).dismiss();node.get().onDestroy();stopTicks(a);});
  }
 }
 @Test public void consolidationPreflightFailuresRemainReadOnlyAndReleaseGate()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(int kind=0;kind<4;kind++){
    final int k=kind;AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    run.onActivity(a->{FixtureNode n=fixture(a,TradingContext.MINIMA);node.set(n);if(k==0)n.coins=2;if(k==1)n.pending="1";if(k==2)n.block=0;((SharedPreferences)get(a,"prefs")).edit().putInt("consolidate_until_"+TradingContext.MINIMA.tokenId,k==3?104:0).commit();saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));call(a,"consolidateOnce",new Class<?>[]{String.class,boolean.class},TradingContext.MINIMA.tokenId,false);});settle();run.onActivity(a->d.set(dialog(a)));
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),k==3?Language.msg(l10n_atomix_operation_wait_block,"240","104"):Language.msg(k==0?l10n_atomix_operation_three:k==1?l10n_atomix_operation_confirm:l10n_atomix_operation_no_block));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());for(String c:node.get().commands)assertTrue(c.startsWith("balance ")||c.equals("block"));assertTrue(SwapEngine.beginConsolidate());SwapEngine.endConsolidate();});}
    if(k==3)screenshot("atomix-operation-wait-block");run.onActivity(a->{dialog(a).dismiss();node.get().onDestroy();stopTicks(a);});settle();
   }
  }
 }
}
