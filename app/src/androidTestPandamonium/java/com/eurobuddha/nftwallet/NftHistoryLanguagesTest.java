package com.eurobuddha.nftwallet;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.*;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** In-memory public history only. Choosers are intercepted before any external activity opens. */
@RunWith(AndroidJUnit4.class)
public class NftHistoryLanguagesTest {
    private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String ADDRESS="0x"+"12".repeat(32), TX="0x"+"58".repeat(32), TOKEN="0x"+"9a".repeat(32);
    private static final String NAME="Send, \"Україна\" 中文";
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    static class History extends NftSendLanguagesTest.FixtureHistory {
        final List<NodeTx> rows=new ArrayList<>();
        History(Context c){super(c);rows.add(row());}
        @Override public List<NodeTx> loadNodeTx(int limit){return new ArrayList<>(rows);}
        @Override public int nodeTxCount(){return rows.size();}
    }
    private static NodeTx row(){NodeTx n=new NodeTx();n.txpowid=TX;n.block=2345678;n.timemilli=1792100000000L;n.direction="received";n.incoming=true;n.tokenid=TOKEN;n.tokenName=NAME;n.amount="12.340000";n.deltas=object(TOKEN,"12.34").toString();n.counterparty=ADDRESS;n.inputs=new JSONArray().put(object("addr",ADDRESS,"tokenid",TOKEN,"amount","12.34")).toString();n.outputs=n.inputs;return n;}
    private static HistoryView history(MainActivity a){return (HistoryView)((BaseView[])get(a,"views"))[MainActivity.TAB_HISTORY];}
    private static LinearLayout rows(MainActivity a){return a.findViewById(R.id.pm_nftwallet_historyContainer);}
    private static void render(MainActivity a){set(history(a),"fetching",false);call(history(a),"render",new Class<?>[]{});}
    private static FixtureNode fixture(MainActivity a){FixtureNode n=freeze(a);set(a,"historyDb",new History(a));a.myAddresses().clear();a.myAddresses().add(new String[]{ADDRESS,ADDRESS});a.goToTab(MainActivity.TAB_HISTORY);render(a);n.requests.clear();return n;}
    private static void kv(View root,int key,CharSequence value){exact(root,Language.join(Language.msg(key),":  ",value));}
    @Test public void retainedDetailsTranslateAndCopyFullIdsWithoutRequests()throws Exception {
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Dialog> kept=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));rows(a).getChildAt(1).performClick();kept.set(dialog(a));});settle();
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(kept.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_history_transaction));kv(root,l10n_history_direction,Language.msg(l10n_history_received));kv(root,l10n_history_amount,"+12.34 "+NAME);kv(root,l10n_history_date_time,Language.stamp(row().timemilli,"yMMMdHms"));exact(root,Language.msg(l10n_history_txpow));exact(root,TX);exact(root,ADDRESS);exact(root,Language.msg(l10n_history_yours));exact(root,Language.msg(l10n_history_inputs));exact(root,Language.msg(l10n_history_outputs));
                NodeTx n=a.history().loadNodeTx(1).get(0);assertEquals("received",n.direction);assertEquals(NAME,n.tokenName);assertEquals("12.340000",n.amount);assertEquals(row().inputs,n.inputs);assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("nft-history-detail-"+code);}
            s.onActivity(a->{exact(dialog(a).getWindow().getDecorView(),TX).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(TX,cm.getPrimaryClip().getItemAt(0).getText().toString());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_close)).performClick();});settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(1,a.history().loadNodeTx(100).size());assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void filtersAndExportDialogRebindAndRemainReachable()throws Exception {
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Dialog> kept=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));exact(rows(a),Language.upper(Language.msg(l10n_history_sent))).performClick();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertEquals("sent",get(history(a),"filter"));exact(rows(a),Language.msg(l10n_nft_history_empty_filter));
                HorizontalScrollView scroll=(HorizontalScrollView)rows(a).getChildAt(0);TextView export=exact(scroll,Language.msg(l10n_nft_history_export));scroll.smoothScrollTo(export.getRight(),0);
                for(TextView t:texts(scroll)){assertNotNull(t.getLayout());for(int i=0;i<t.getLineCount();i++)assertEquals(0,t.getLayout().getEllipsisCount(i));assertTrue(t.getHeight()>=48*t.getResources().getDisplayMetrics().density-1);}
                assertTrue(node.get().requests.isEmpty());
            });settle();guards.screenshot("nft-history-filter-"+code);}
            s.onActivity(a->{exact(rows(a),Language.msg(l10n_nft_history_export)).performClick();kept.set(dialog(a));});settle();
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_wallet_export_history));exact(root,Language.msg(l10n_nft_history_export_help));exact(root,"CSV");exact(root,"JSON");assertEquals("sent",get(history(a),"filter"));assertTrue(node.get().requests.isEmpty());});}
            s.onActivity(a->{dialog(a).dismiss();exact(rows(a),Language.upper(Language.msg(l10n_wallet_all))).performClick();assertNull(get(history(a),"filter"));assertEquals(2,rows(a).getChildCount());});
        }
    }
    @Test public void exportPayloadsKeepCanonicalFieldsAndLiteralValuesInEveryLanguage()throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();AtomicReference<Intent> chooser=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){chooser.set(intent);return new Instrumentation.ActivityResult(0,null);}return null;}};
        instrumentation.addMonitor(monitor);
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();s.onActivity(a->node.set(fixture(a)));Map<Boolean,String> payloads=new HashMap<>();
            for(String code:CODES)for(boolean csv:new boolean[]{true,false}){s.onActivity(a->{Language.select(a,code);call(history(a),"shareExport",new Class<?>[]{boolean.class},csv);});settle();Intent shown=chooser.getAndSet(null);assertNotNull(shown);assertEquals(Language.msg(l10n_wallet_export_history).toString(),shown.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());Intent send=shown.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_SEND,send.getAction());assertEquals("text/plain",send.getType());assertEquals(Language.msg(l10n_nft_history_export_subject,csv?"CSV":"JSON").toString(),send.getStringExtra(Intent.EXTRA_SUBJECT));String body=send.getStringExtra(Intent.EXTRA_TEXT);if(payloads.containsKey(csv))assertEquals(payloads.get(csv),body);else payloads.put(csv,body);
                if(csv){assertTrue(body.startsWith("time,direction,amount,token,counterparty,block,txpowid\n"));assertTrue(body.contains(",received,12.34,\"Send, \"\"Україна\"\" 中文\","));assertTrue(body.endsWith(TX+"\n"));}else{JSONArray data=new JSONArray(body);assertEquals(1,data.length());JSONObject record=data.getJSONObject(0);assertEquals(7,record.length());assertEquals("received",record.getString("direction"));assertEquals(NAME,record.getString("token"));assertEquals("12.34",record.getString("amount"));assertEquals(TX,record.getString("txpowid"));assertEquals(ADDRESS,record.getString("counterparty"));}
            }
            s.onActivity(a->{exact(rows(a),Language.upper(Language.msg(l10n_history_sent))).performClick();call(history(a),"shareExport",new Class<?>[]{boolean.class},false);android.util.Log.i("BearHistoryExport", "Fixture requests after intercepted chooser returns: "+node.get().requests);for(String command:node.get().requests)assertTrue("Unexpected export request: "+command, command.equals("block")||command.equals("balance")||command.equals("status")||command.equals("scripts")||command.equals("coins relevant:true own:true")||command.startsWith("history relevant:true max:"));});settle();Intent send=chooser.get().getParcelableExtra(Intent.EXTRA_INTENT);assertEquals("[]",send.getStringExtra(Intent.EXTRA_TEXT));
        }finally{instrumentation.removeMonitor(monitor);}
    }
}
