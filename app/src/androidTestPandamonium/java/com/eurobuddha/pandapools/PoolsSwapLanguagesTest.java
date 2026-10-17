package com.eurobuddha.pandapools;
import android.widget.*;
import android.view.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.minimacore.i18n.Language;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Seedless public fixtures and cancelled reviews. No swap approval, signing or broadcast. */
public class PoolsSwapLanguagesTest extends PoolsLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static String full(BigDecimal n){return n.stripTrailingZeros().toPlainString();}
 private static String shortValue(BigDecimal n){return full(n.setScale(8,RoundingMode.DOWN));}
 private static SwapView swap(MainActivity a){return (SwapView)page(a,MainActivity.TAB_SWAP);}
 @SuppressWarnings("unchecked") private static AlertDialog dialog(MainActivity a){Set<AlertDialog>d=(Set<AlertDialog>)get(a,"languageDialogs");assertEquals(1,d.size());return d.iterator().next();}
 private static List<Pool>fixture(int count){List<Pool>p=new ArrayList<>();for(int i=0;i<count;i++){Pool v=pool(i%2);v.address="0x"+String.format(Locale.ROOT,"%0128x",i+1);v.tokName="token";v.tokDecimals=8;p.add(v);}return p;}
 private static EditText amount(SwapView v){return (EditText)get(v,"amount");}
 @Test public void quoteAndOpenReviewKeepDraftDirectionValuesAndRouteAcrossLanguages()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<SwapView>view=new AtomicReference<>();List<Pool>pools=fixture(8);
   run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_SWAP);view.set(swap(a));call(view.get(),"renderPools",new Class<?>[]{List.class},pools);repo.get().scans=0;});
   for(boolean direction:new boolean[]{true,false}){
    AtomicReference<PoolRouter.Route>route=new AtomicReference<>();AtomicReference<AlertDialog>review=new AtomicReference<>();AtomicReference<View>quoteRow=new AtomicReference<>();
    run.onActivity(a->{SwapView v=view.get();set(v,"minimaToToken",direction);call(v,"syncLabels");amount(v).setText("00012.34000");amount(v).setSelection(4);route.set(PoolRouter.route(pools,direction,new BigDecimal("12.34")));assertTrue(route.get().ok);assertTrue(route.get().capped);quoteRow.set(((LinearLayout)get(v,"quotePanel")).getChildAt(0));call(v,"confirmAndSwap");review.set(dialog(a));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{SwapView v=view.get();PoolRouter.Route r=route.get();assertSame(v,swap(a));assertEquals(direction,get(v,"minimaToToken"));assertEquals("00012.34000",amount(v).getText().toString());assertEquals(4,amount(v).getSelectionStart());assertSame(quoteRow.get(),((LinearLayout)get(v,"quotePanel")).getChildAt(0));assertEquals(shortValue(r.totalOut),((TextView)get(v,"out")).getText().toString());assertEquals(direction?"MINIMA":"token",((TextView)get(v,"fromTok")).getText().toString());assertEquals(direction?"token":"MINIMA",((TextView)get(v,"toTok")).getText().toString());
     exact(v.getRoot(),Language.msg(l10n_pools_rate));exact(v.getRoot(),Language.msg(l10n_pools_pool_fee));wrapped(exact(v.getRoot(),Language.msg(l10n_pools_lp_kept)));exact(v.getRoot(),Language.join(Language.msg(l10n_pools_route_count,r.poolsUsed,8),Language.msg(l10n_pools_route_cap,6)));assertEquals(Language.msg(l10n_pools_flip_description).toString(),((TextView)get(v,"flip")).getContentDescription().toString());
     assertSame(review.get(),dialog(a));assertTrue(review.get().isShowing());exact(review.get().getWindow().getDecorView(),Language.msg(l10n_pools_confirm_swap));String pay=full(r.totalIn)+" "+(direction?"MINIMA":"token"),receive=full(r.totalOut)+" "+(direction?"token":"MINIMA");CharSequence routed=r.poolsUsed>1?Language.msg(l10n_pools_route_review,r.poolsUsed):"";String impact=(String)call(v,"impact",new Class<?>[]{PoolRouter.Route.class},r);TextView body=review.get().findViewById(android.R.id.message);assertEquals(Language.msg(l10n_pools_swap_review,pay,receive,impact,routed).toString(),body.getText().toString());wrapped(body);assertEquals(Language.msg(l10n_pools_cancel).toString(),review.get().getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());assertEquals(0,repo.get().scans);assertFalse(v.isBusy());});}
    screenshot("pools-swap-review-"+(direction?"buy":"sell"));run.onActivity(a->review.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{assertTrue(((Set<?>)get(a,"languageDialogs")).isEmpty());assertEquals("00012.34000",amount(view.get()).getText().toString());});
   }
   screenshot("pools-swap-quote");
  }
 }
 private static void marketField(String name,Object value){try{java.lang.reflect.Field f=MarketPrice.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}catch(Exception e){throw new AssertionError(e);}}
 @Test public void marketComparisonRebindsCapturedValuesWithoutFetching()throws Exception{
  Object oldMid=read(MarketPrice.class,null,"mid"),oldTime=read(MarketPrice.class,null,"fetchedAt"),oldTry=read(MarketPrice.class,null,"lastTryMs");
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<SwapView>view=new AtomicReference<>();
   run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_SWAP);view.set(swap(a));call(view.get(),"renderPools",new Class<?>[]{List.class},fixture(2));set(view.get(),"pairLabel","USDT");repo.get().scans=0;});
   for(double mid:new double[]{1.0,2.0,4.0,0.0}){
    run.onActivity(a->{marketField("mid",mid);marketField("fetchedAt",System.currentTimeMillis());call(view.get(),"renderMarket");});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{TextView line=(TextView)get(view.get(),"marketLine");CharSequence expected=mid==0?Language.msg(l10n_pools_market_unavailable):Language.msg(l10n_pools_market_compare,full(BigDecimal.valueOf(mid)),"2",mid==2?Language.msg(l10n_pools_at_market):Language.msg(mid==1?l10n_pools_above_market:l10n_pools_below_market,mid==1?"100.0":"50.0"));assertEquals(expected.toString(),line.getText().toString());wrapped(line);assertEquals(oldTry,read(MarketPrice.class,null,"lastTryMs"));assertEquals(0,repo.get().scans);});}
   }
   screenshot("pools-swap-market");
  }finally{marketField("mid",oldMid);marketField("fetchedAt",oldTime);marketField("lastTryMs",oldTry);}
 }
 @Test public void activityStatusesKeepFullLiteralSummariesAndEvidence()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<SwapView>view=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();List<ActivityLog.Entry>entries=new ArrayList<>();List<View>rows=new ArrayList<>();String id="0x"+"ab".repeat(64),summary="−00012.34000 token 中文 Русский Українська $1 {count} "+id;
   for(int depth:new int[]{0,1,2,5}){ActivityLog.Entry e=new ActivityLog.Entry(ActivityLog.SWAP,summary,id,1,1,false,null);e.verifiedDepth=depth;entries.add(e);}
   entries.add(new ActivityLog.Entry(ActivityLog.SWAP,summary,id,1,1,true,"raw diagnostic"));entries.add(new ActivityLog.Entry(ActivityLog.SWAP,summary,id,1,1,true,NodeApi.ERR_WRITE_UNCERTAIN));entries.add(new ActivityLog.Entry(ActivityLog.SWAP,summary,"",1,1,false,null));entries.add(new ActivityLog.Entry(ActivityLog.SWAP,summary,id,1,1,false,null));
   ActivityLog.Entry missing=new ActivityLog.Entry(ActivityLog.SWAP,summary,id,1,1,false,null);missing.verifiedAt=1;entries.add(missing);ActivityLog.Entry waiting=new ActivityLog.Entry(ActivityLog.SWAP,summary,id,1,1,false,null);waiting.verifiedAt=1;waiting.transactionId=id;entries.add(waiting);
   run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_SWAP);view.set(swap(a));LinearLayout activity=(LinearLayout)get(view.get(),"swapActivity");activity.removeAllViews();for(ActivityLog.Entry e:entries){View row=(View)call(view.get(),"activityRow",new Class<?>[]{ActivityLog.Entry.class,int.class},e,10000);rows.add(row);activity.addView(row);}repo.get().scans=0;});
   String[]raw=entries.stream().map(e->e.statusText(10000)).toArray(String[]::new);int[]fixed={l10n_pools_failed,l10n_pools_outcome_unknown,l10n_pools_no_transaction,l10n_pools_checking_chain,l10n_pools_not_found_node,l10n_pools_awaiting_inclusion};
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{for(int i=0;i<entries.size();i++){ActivityLog.Entry e=entries.get(i);assertEquals(raw[i],e.statusText(10000));TextView desc=exact(rows.get(i),summary);assertEquals(Integer.MAX_VALUE,desc.getMaxLines());CharSequence status=i<4?Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_confirmations,e.verifiedDepth,e.verifiedDepth):Language.msg(fixed[i-4]);wrapped(exact(rows.get(i),status));assertEquals(i==3,e.confirmed(10000));}assertEquals(0,repo.get().scans);});}
   screenshot("pools-swap-activity");
  }
 }
 @Test public void emptyErrorsAndBusyStatusRetainRawValuesAcrossLanguages()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<SwapView>view=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();String raw="node 中文 Русский Українська $1 {id} 00012.34000",id="0x"+"ef".repeat(64);
   run.onActivity(a->{repo.set(freeze(a));a.goToTab(MainActivity.TAB_SWAP);view.set(swap(a));call(view.get(),"renderPools",new Class<?>[]{List.class},Collections.emptyList());repo.get().scans=0;});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{exact(view.get().getRoot(),Language.msg(l10n_pools_swap_empty));assertFalse(((TextView)get(view.get(),"swapBtn")).isEnabled());});}
   run.onActivity(a->((PoolBook.Listener)get(view.get(),"poolListener")).onError(raw));
   CharSequence[]states={Language.msg(l10n_pools_swap_failure,raw),Language.msg(l10n_pools_swap_failure,PoolsMessages.message(NodeApi.ERR_WRITE_UNCERTAIN)),Language.msg(l10n_pools_swap_submitted,id),Language.msg(l10n_pools_swap_posting)};
   for(CharSequence state:states){run.onActivity(a->call(view.get(),"setStatus",new Class<?>[]{CharSequence.class,int.class},state,Design.amber()));for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{exact(view.get().getRoot(),Language.msg(l10n_pools_scan_error,raw));TextView st=exact(view.get().getRoot(),state);wrapped(st);assertEquals(0,repo.get().scans);});}}
   screenshot("pools-swap-status");
  }
 }
}
