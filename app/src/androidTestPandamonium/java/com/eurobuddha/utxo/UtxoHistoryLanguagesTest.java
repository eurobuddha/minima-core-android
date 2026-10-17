package com.eurobuddha.utxo;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.UtxoLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public fixture rows only. No node, keys, requests, signing or transaction submission. */
@RunWith(AndroidJUnit4.class)
public class UtxoHistoryLanguagesTest {
    private final UtxoLanguagesTest guards=new UtxoLanguagesTest();
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String LOCAL="language_fixture_utxo_history", TX="0x"+"58".repeat(32),ADDRESS="0x"+"12".repeat(32),COIN="0x"+"76".repeat(32),TOKEN="0x"+"9a".repeat(32);
    private boolean ready;
    @Before public void prepare(){guards.prepare();ready=true;}
    @After public void reset(){if(ready){try(HistoryDb db=new HistoryDb(context)){db.delete(LOCAL);db.getWritableDatabase().delete("nodetx","txpowid=?",new String[]{TX});}guards.reset();}}
    private static HistoryView history(MainActivity a){return (HistoryView)((BaseView[])get(a,"views"))[MainActivity.TAB_HISTORY];}
    private static LinearLayout rows(MainActivity a){return a.findViewById(R.id.pm_utxo_historyContainer);}
    private static void render(MainActivity a){set(history(a),"rowsDirty",true);call(history(a),"render",new Class<?>[]{});}
    private static void kv(View root,int key,CharSequence value){exact(root,Language.join(Language.msg(key),":  ",value));}
    private static CharSequence copy(int key,String value){return Language.msg(l10n_history_copy_row,Language.msg(key),value);}
    private static HistoryRow local(){HistoryRow r=new HistoryRow();r.internalid=LOCAL;r.txnid="";r.status=HistoryDb.STATUS_ERROR;r.recipient="Split → 3 coins";r.amount="12.340000";r.tokenid=TOKEN;r.tokenName="Send";r.ts=1792100000000L;r.note="batch attempt failed: could not get a change address";r.inputs=new JSONArray().put(object("coinid",COIN,"address",ADDRESS,"amount","12.34")).toString();r.outputs=new JSONArray().put(object("address",ADDRESS,"amount","12.34")).toString();r.changeaddr=ADDRESS;r.burn="0.001000";return r;}
    private static NodeTx onchain(){NodeTx n=new NodeTx();n.txpowid=TX;n.block=2345678;n.timemilli=1792100000000L;n.direction="received";n.incoming=true;n.tokenid=TOKEN;n.tokenName="Token";n.amount="12.340000";n.deltas=object(TOKEN,"12.34").toString();n.counterparty=ADDRESS;n.inputs=new JSONArray().put(object("coinid",COIN,"addr",ADDRESS,"address",ADDRESS,"tokenid",TOKEN,"amount","12.34")).toString();n.outputs=n.inputs;return n;}
    @Test public void localHistoryRetainsStoredRecordAndCopyableIdentifiers()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> original=new AtomicReference<>();
            s.onActivity(a->{node.set(freeze(a));assertTrue(a.history().listOpen(200).isEmpty());a.history().insertPosting(local());a.goToTab(MainActivity.TAB_HISTORY);set(history(a),"fetching",false);render(a);rows(a).getChildAt(0).performClick();original.set(dialog(a));node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(original.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_utxo_failed_transaction));
                kv(root,l10n_utxo_status,Language.msg(l10n_utxo_status_failed));kv(root,l10n_utxo_action,UtxoMessages.message("Split → 3 coins"));kv(root,l10n_utxo_error,UtxoMessages.message(local().note));kv(root,l10n_history_amount,"−12.34 Send");
                kv(root,l10n_history_date_time,Language.stamp(local().ts,"yMMMdHms"));exact(root,copy(l10n_utxo_coin_id,COIN));exact(root,copy(l10n_utxo_history_change,ADDRESS));
                List<HistoryRow> stored=a.history().listOpen(200);assertEquals(1,stored.size());HistoryRow r=stored.get(0);assertEquals(local().note,r.note);assertEquals(local().recipient,r.recipient);assertEquals("12.340000",r.amount);assertEquals("Send",r.tokenName);assertEquals(local().inputs,r.inputs);assertEquals(HistoryDb.STATUS_ERROR,r.status);assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("utxo-local-history-"+code);}
            s.onActivity(a->{exact(dialog(a).getWindow().getDecorView(),copy(l10n_utxo_coin_id,COIN)).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(COIN,cm.getPrimaryClip().getItemAt(0).getText().toString());dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(1,a.history().listOpen(200).size());assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void confirmedHistoryDirectionDateAndFullTransactionIdRebind()throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,Design.Mode.ORIGINAL_LIGHT));
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> original=new AtomicReference<>();
            s.onActivity(a->{node.set(freeze(a));assertEquals(0,a.history().nodeTxCount());a.history().upsertNodeTx(onchain());a.goToTab(MainActivity.TAB_HISTORY);set(history(a),"fetching",false);render(a);rows(a).getChildAt(0).performClick();original.set(dialog(a));node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(original.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();kv(root,l10n_history_direction,Language.msg(l10n_history_received));kv(root,l10n_history_amount,"+12.34 Token");kv(root,l10n_history_date_time,Language.stamp(onchain().timemilli,"yMMMdHms"));exact(root,copy(l10n_history_txpow,TX));exact(root,copy(l10n_history_from,ADDRESS));exact(root,Language.msg(l10n_history_per_token));
                NodeTx n=a.history().loadNodeTx(1).get(0);assertEquals("received",n.direction);assertEquals("Token",n.tokenName);assertEquals(onchain().inputs,n.inputs);assertEquals(onchain().deltas,n.deltas);assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("utxo-confirmed-history-"+code);}
            s.onActivity(a->{exact(dialog(a).getWindow().getDecorView(),copy(l10n_history_txpow,TX)).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(TX,cm.getPrimaryClip().getItemAt(0).getText().toString());dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(1,a.history().nodeTxCount());assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void distributionProgressKeepsJobStateAndHistoryStatusesStayDistinct()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<String> saved=new AtomicReference<>();AtomicReference<DistributeJob> job=new AtomicReference<>();
            s.onActivity(a->{node.set(freeze(a));assertNull(DistributeJob.load(a));DistributeJob j=new DistributeJob();j.tokenid=TOKEN;j.tokenName="Send";j.per="0.125000";j.batchesDone=2;j.remaining.addAll(Arrays.asList(ADDRESS,ADDRESS));j.waiting=true;j.retryCount=1;j.currentInternalId="public-fixture-only";job.set(j);saved.set(j.toJson().toString());set(a.distribute(),"job",j);a.goToTab(MainActivity.TAB_WALLET);((BaseView[])get(a,"views"))[0].refresh();node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                CharSequence progress=Language.join(Language.msg(l10n_utxo_distribution_progress,2,2)," · ",Language.msg(l10n_utxo_distribution_waiting)," · ",Language.msg(l10n_utxo_distribution_retry,1,3));exact(a.getWindow().getDecorView(),progress);
                assertEquals(saved.get(),job.get().toJson().toString());assertNull(DistributeJob.load(a));assertTrue(node.get().requests.isEmpty());
                Set<String> states=new HashSet<>();for(String status:new String[]{HistoryDb.STATUS_POSTING,HistoryDb.STATUS_POSTED,HistoryDb.STATUS_UNKNOWN,HistoryDb.STATUS_ERROR}){HistoryRow r=local();r.status=status;states.add(call(history(a),"statusWord",new Class<?>[]{HistoryRow.class},r).toString());}assertEquals(4,states.size());
            });guards.screenshot("utxo-distribute-progress-"+code);}
            s.onActivity(a->{job.get().waiting=false;((BaseView[])get(a,"views"))[0].refresh();exact(a.getWindow().getDecorView(),Language.join(Language.msg(l10n_utxo_distribution_progress,2,2)," · ",Language.msg(l10n_utxo_status_posting)," · ",Language.msg(l10n_utxo_distribution_retry,1,3)));set(a.distribute(),"job",null);((BaseView[])get(a,"views"))[0].refresh();assertEquals(View.GONE,a.findViewById(R.id.pm_utxo_walletStatus).getVisibility());WalletView wallet=(WalletView)((BaseView[])get(a,"views"))[0];call(wallet,"setStatus",new Class<?>[]{CharSequence.class,boolean.class},Language.msg(l10n_utxo_consolidate_submitted),true);wallet.refresh();assertEquals(View.VISIBLE,a.findViewById(R.id.pm_utxo_walletStatus).getVisibility());exact(wallet.getRoot(),Language.msg(l10n_utxo_consolidate_submitted));assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void everyKnownDiagnosticPatternRendersInAllFourLanguages(){
        String[] messages={"Distribute: posting batch 2 (14 addresses)…", "Distribute: no reply from the node in time — waiting to see if batch 2 posted…",
            "Distribute aborted (14 address(es) unfunded): batch 2 failed after 3 retries: could not get a change address",
            "Distribute couldn't start: wallet not enabled in Minima Core", "Distribute batch failed; retrying next block (1/3)",
            "Distribute aborted: timed out waiting for the change coin to confirm", "batch attempt failed: could not get a change address",
            "batch 2 failed after 3 retries: unexpected 中文 {name} $1", "Not posted: these coins were spent by a different transaction ("+TX+").",
            "Split → 3 coins", "Consolidate Send 中文 {amount} $1", "Distribute batch 2 → 14 addrs",
            "node reply timed out — awaiting settlement", "The node didn't answer in time and the coins are still unspent — not posted.",
            "Distribute: final batch got no reply from the node in time — see 'posted?' in History.", "Distribute complete."};
        for(String code:CODES)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Language.select(context,code);
            for(String value:messages){CharSequence mapped=UtxoMessages.message(value);assertTrue(value,mapped instanceof Language.Message);assertFalse(mapped.toString().isEmpty());}
            assertTrue(UtxoMessages.message(messages[7]).toString().endsWith("unexpected 中文 {name} $1"));
            assertTrue(UtxoMessages.message(messages[8]).toString().contains(TX));
            assertTrue(UtxoMessages.message(messages[10]).toString().contains("Send 中文 {amount} $1"));
        });
    }

}
