package com.eurobuddha.pandapools;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public fixture metadata, stopped workers and cancelled dialogs; no node, seed or financial action. */
public class PoolsWalletActivityLanguagesTest extends PoolsLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String ID="0x"+"ab".repeat(64),TOKEN="0x"+"cd".repeat(32),ADDR="0x"+"ef".repeat(32);
 private static final long TIME=1720000000000L;
 @SuppressWarnings("unchecked") private static AlertDialog dialog(MainActivity a){Set<AlertDialog>d=(Set<AlertDialog>)get(a,"languageDialogs");assertEquals(1,d.size());return d.iterator().next();}
 private static CountingPools stopAt(MainActivity a,int index){CountingPools p=freeze(a);a.goToTab(index);page(a,index).onStop();a.ui().removeCallbacksAndMessages(null);p.scans=0;return p;}
 private static TokenBalance balance()throws Exception{
  TokenBalance b=TokenBalance.from(new JSONObject().put("tokenid",TOKEN).put("token",new JSONObject().put("name","Sendable").put("description","Owner 中文 Русский Українська $1 {count}")).put("confirmed","175901.49982642299").put("sendable","0").put("unconfirmed","0").put("coins",3).put("total","250000"));return b;
 }
 @Test public void balancesAndTokenDetailKeepLiteralMetadataAmountsAndReceiveCopy()throws Exception{
  TokenBalance b=balance();String raw=b.breakdown(0,0);
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<WalletView>view=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<View>card=new AtomicReference<>();
   run.onActivity(a->{repo.set(stopAt(a,MainActivity.TAB_WALLET));view.set((WalletView)page(a,MainActivity.TAB_WALLET));((List<TokenBalance>)get(view.get(),"balances")).add(b);set(view.get(),"balanceError","");set(view.get(),"receiveAddr",ADDR);set(view.get(),"lastBalanceUpdate",0L);call(view.get(),"renderCards");card.set(((LinearLayout)get(view.get(),"container")).getChildAt(1));});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{View root=view.get().getRoot();assertSame(card.get(),((LinearLayout)get(view.get(),"container")).getChildAt(1));exact(root,"SENDABLE");exact(root,Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_coin_count,3,3)));TextView desc=exact(root,PoolsMessages.message(raw));wrapped(desc);assertTrue(desc.getText().toString().contains("175901.49982642299"));TextView addr=exact(root,Language.msg(l10n_pools_receive_address,ADDR));wrapped(addr);addr.performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(ADDR,cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(raw,b.breakdown(0,0));assertEquals(0,repo.get().scans);});}
   screenshot("pools-wallet-balances");run.onActivity(a->call(view.get(),"showTokenDetail",new Class<?>[]{TokenBalance.class},b));settle();AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->d.set(dialog(a)));
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));View root=d.get().getWindow().getDecorView();exact(root,"Sendable");exact(root,b.meta.description);exact(root,TOKEN);exact(root,"175901.49982642299");exact(root,Language.msg(l10n_pools_sendable));exact(root,Language.msg(l10n_pools_locked));assertEquals(Language.msg(l10n_language_close).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(0,repo.get().scans);});}
   screenshot("pools-wallet-token");run.onActivity(a->d.get().dismiss());
  }
 }
 private static JSONObject coin(String id,String amount,String address)throws Exception{return new JSONObject().put("coinid",id).put("tokenid","0x00").put("amount",amount).put("address",address);}
 @Test public void coinReportAndRepeatedCopyFollowCurrentLanguageAndCurrentResult()throws Exception{
  TokenBalance b=balance();JSONObject j=new JSONObject().put("status",true).put("response",new JSONArray().put(coin(ID,"00012.3400",ADDR)).put(coin("0x"+"12".repeat(64),"0.00000001",PoolCovenant.SENTINEL)));String raw=j.toString();
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<WalletView>view=new AtomicReference<>();AtomicReference<TextView>report=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();
   run.onActivity(a->{repo.set(stopAt(a,MainActivity.TAB_WALLET));view.set((WalletView)page(a,MainActivity.TAB_WALLET));call(view.get(),"showTokenDetail",new Class<?>[]{TokenBalance.class},b);});settle();
   run.onActivity(a->{d.set(dialog(a));PandaAddrBook book=new PandaAddrBook(a);book.add(ADDR);set(view.get(),"addrBook",book);for(TextView t:texts(d.get().getWindow().getDecorView()))if(t.isTextSelectable()&&!TOKEN.equals(t.getText().toString())){report.set(t);break;}assertNotNull(report.get());CharSequence text=(CharSequence)call(view.get(),"renderCoins",new Class<?>[]{JSONObject.class,Set.class,boolean.class},j,Collections.singleton(ID),true);Language.setText(report.get(),text);});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{String text=report.get().getText().toString();assertTrue(text,text.contains("00012.34"));assertTrue(text.contains(ID));assertTrue(text.contains(Language.msg(l10n_pools_spendable_tag).toString()));assertTrue(text.contains(Language.msg(l10n_pools_locked_tag).toString()));assertTrue(text.contains(Language.msg(l10n_pools_pool_tag).toString()));assertTrue(text.contains(Language.msg(l10n_pools_beacon_tag).toString()));exact(d.get().getWindow().getDecorView(),Language.msg(l10n_pools_copy_coins)).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(text,cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(raw,j.toString());assertEquals(0,repo.get().scans);});}
   screenshot("pools-wallet-coins");
   run.onActivity(a->{Language.setText(report.get(),"new result "+ID);exact(d.get().getWindow().getDecorView(),Language.msg(l10n_pools_copy_coins)).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals("new result "+ID,cm.getPrimaryClip().getItemAt(0).getText().toString());d.get().dismiss();});
  }
 }
 private static HistoryEntry history()throws Exception{HistoryEntry n=new HistoryEntry();n.txpowid=ID;n.transactionId="0x"+"11".repeat(32);n.tokenid=TOKEN;n.tokenName="Direction";n.amount="00012.3400";n.direction="received";n.incoming=true;n.counterparty=ADDR;n.block=12345;n.timemilli=TIME;n.verifiedAt=TIME+1000;n.verifiedDepth=4;n.deltas="{}";n.inputs=new JSONArray().put(new JSONObject().put("addr",ADDR).put("amount","00012.3400").put("tokenid",TOKEN)).toString();n.outputs=n.inputs;return n;}
 @Test public void transactionDetailAndOlderReceiptKeepIdentifiersEvidenceAndDates()throws Exception{
  HistoryEntry n=history();ActivityLog.Entry e=new ActivityLog.Entry(ActivityLog.SWAP,"Swap",ID,12345,TIME,false,null);e.originalTxpowid="0x"+"22".repeat(64);e.verifiedAt=TIME+1000;
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<ActivityView>view=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<View>receipt=new AtomicReference<>();
   run.onActivity(a->{repo.set(stopAt(a,MainActivity.TAB_ACTIVITY));view.set((ActivityView)page(a,MainActivity.TAB_ACTIVITY));LinearLayout box=(LinearLayout)get(view.get(),"container");box.removeAllViews();receipt.set((View)call(view.get(),"pendingCard",new Class<?>[]{ActivityLog.Entry.class,int.class,HistoryEntry.class,long.class},e,999999,null,TIME));box.addView(receipt.get());box.addView((View)call(view.get(),"historyRow",new Class<?>[]{HistoryEntry.class},n));receipt.get().performClick();});
   AtomicReference<AlertDialog>d=new AtomicReference<>();run.onActivity(a->d.set(dialog(a)));
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));exact(receipt.get(),"Swap");String text=((TextView)d.get().findViewById(android.R.id.message)).getText().toString();assertTrue(text.contains(ID));assertTrue(text.contains(e.originalTxpowid));assertTrue(text.contains(Language.msg(l10n_pools_receipt_match_note).toString()));assertTrue(text.contains(Language.stamp(TIME,"yMMMdHmsz").toString()));assertEquals(-1,e.verifiedDepth);assertFalse(e.confirmed(999999));assertEquals("",e.transactionId);assertEquals(0,repo.get().scans);});}
   screenshot("pools-activity-receipt");run.onActivity(a->d.get().dismiss());settle();run.onActivity(a->{call(view.get(),"showDetail",new Class<?>[]{HistoryEntry.class},n);d.set(dialog(a));});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));View root=d.get().getWindow().getDecorView();exact(root,Language.join(Language.msg(l10n_history_direction),":  ",Language.msg(l10n_history_received)));exact(root,Language.join(Language.msg(l10n_history_date_time),":  ",Language.stamp(TIME,"yMMMdHms")));assertTrue(texts(root).stream().anyMatch(t->t.getText().toString().contains("00012.34 Direction")));TextView id=exact(root,Language.msg(l10n_pools_identifier,Language.msg(l10n_pools_txpow_id),ID));id.performClick();assertEquals(ID,((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());assertEquals("received",n.direction);assertEquals(4,n.verifiedDepth);assertEquals(0,repo.get().scans);});}
   screenshot("pools-activity-detail");run.onActivity(a->d.get().dismiss());
  }
 }
 @Test public void publicObservationsRemainUnverifiedAndLiteralAcrossLanguages()throws Exception{
  List<GlobalFeed.Event>events=new ArrayList<>();for(String kind:new String[]{GlobalFeed.CREATE,GlobalFeed.ADD,GlobalFeed.WITHDRAW,GlobalFeed.SWAP})for(boolean in:new boolean[]{true,false})events.add(new GlobalFeed.Event(kind,ADDR,"Pool creation",in,new BigDecimal("12.3400"),new BigDecimal("0.567800"),new BigDecimal("0.046"),TIME));
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<CountingPools>repo=new AtomicReference<>();AtomicReference<ActivityView>view=new AtomicReference<>();List<View>rows=new ArrayList<>();
   run.onActivity(a->{repo.set(stopAt(a,MainActivity.TAB_ACTIVITY));view.set((ActivityView)page(a,MainActivity.TAB_ACTIVITY));set(view.get(),"showGlobal",true);set(view.get(),"shown",120);call(view.get(),"styleTabs");LinearLayout box=(LinearLayout)get(view.get(),"container");box.removeAllViews();for(GlobalFeed.Event event:events){View row=(View)call(view.get(),"globalRow",new Class<?>[]{GlobalFeed.Event.class},event);rows.add(row);box.addView(row);}});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertTrue((boolean)get(view.get(),"showGlobal"));assertEquals(120,get(view.get(),"shown"));for(int i=0;i<events.size();i++){GlobalFeed.Event event=events.get(i);int key=event.kind.equals(GlobalFeed.CREATE)?l10n_pools_observed_create:event.kind.equals(GlobalFeed.ADD)?l10n_pools_observed_add:event.kind.equals(GlobalFeed.WITHDRAW)?l10n_pools_observed_withdraw:event.minimaIn?l10n_pools_observed_sold:l10n_pools_observed_bought;assertTrue(texts(rows.get(i)).stream().anyMatch(t->t.getText().toString().contains(Language.msg(key).toString())&&t.getText().toString().contains("Pool creation")));assertTrue(texts(rows.get(i)).stream().anyMatch(t->t.getText().toString().contains(Language.msg(l10n_pools_observation_time,ADDR,Language.stamp(TIME,"yMMMdHmsz")).toString())));assertEquals(TIME,event.ts);}wrapped(exact(view.get().getRoot(),Language.msg(l10n_pools_my_activity)));wrapped(exact(view.get().getRoot(),Language.msg(l10n_pools_all_pools)));assertEquals(0,repo.get().scans);});}
   screenshot("pools-activity-observations");
  }
 }
 @Test public void failedBalanceReadKeepsCachedAmountsAndFreshness()throws Exception{
  TokenBalance b=balance();String error="server 中文 Русский Українська $1 {amount}";
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<WalletView>view=new AtomicReference<>();AtomicReference<CountingPools>repo=new AtomicReference<>();
   run.onActivity(a->{repo.set(stopAt(a,MainActivity.TAB_WALLET));view.set((WalletView)page(a,MainActivity.TAB_WALLET));((List<TokenBalance>)get(view.get(),"balances")).add(b);set(view.get(),"balanceError",error);set(view.get(),"lastBalanceUpdate",TIME);call(view.get(),"renderCards");});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{exact(view.get().getRoot(),Language.msg(l10n_pools_balance_error,error,Language.msg(l10n_pools_last_read)));assertEquals(TIME,get(view.get(),"lastBalanceUpdate"));assertEquals("175901.49982642299",b.confirmed);assertEquals(error,get(view.get(),"balanceError"));assertEquals(0,repo.get().scans);});}
   screenshot("pools-wallet-stale");
  }
 }
}
