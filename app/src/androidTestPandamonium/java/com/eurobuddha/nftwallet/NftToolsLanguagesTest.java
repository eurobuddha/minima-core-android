package com.eurobuddha.nftwallet;
import android.view.View;
import android.widget.*;
import android.app.Dialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public fixtures, retained forms and actual menu actions. Transaction commands use a fake node. */
@RunWith(AndroidJUnit4.class)
public class NftToolsLanguagesTest {
    private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private final android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String TX="0x"+"58".repeat(32);
    private static final String ADDRESS="0x"+"12".repeat(32), TOKEN="0x"+"78".repeat(32);
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    private static WalletView wallet(MainActivity a){return (WalletView)((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET];}
    private static FixtureNode fixture(MainActivity a){
        FixtureNode n=freeze(a);seedDisplay(a);
        Coin other=Coin.from(object("coinid","0x"+"9a".repeat(32),"address",ADDRESS,"tokenid","0x00","amount","25.5000"));
        other.tokenName="Send";other.sendable=true;a.coins().add(other);a.toggleCoin(other);
        a.myAddresses().add(new String[]{ADDRESS,ADDRESS});a.goToTab(MainActivity.TAB_WALLET);wallet(a).refresh();n.requests.clear();return n;
    }
    private static Map<PopupMenu,?> menus(MainActivity a){return (Map<PopupMenu,?>)get(a,"languageMenus");}
    private static PopupMenu menu(MainActivity a){assertEquals(1,menus(a).size());return menus(a).keySet().iterator().next();}
    private static void tool(MainActivity a,int id){a.findViewById(R.id.pm_nftwallet_toolsBtn).performClick();PopupMenu m=menu(a);assertTrue(m.getMenu().performIdentifierAction(id,0));m.dismiss();}
    private static List<EditText> fields(Dialog d){List<EditText> out=new ArrayList<>();for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)out.add((EditText)t);return out;}
    private static void cancel(MainActivity a){exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_cancel)).performClick();}
    private void forms(Design.Mode mode)throws Exception{
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(InstrumentationRegistry.getInstrumentation().getTargetContext(),mode));
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();s.onActivity(a->node.set(fixture(a)));
            for(int toolId:new int[]{1,3}){
                AtomicReference<Dialog> original=new AtomicReference<>();AtomicReference<List<EditText>> inputs=new AtomicReference<>();
                s.onActivity(a->{tool(a,toolId);original.set(dialog(a));inputs.set(fields(dialog(a)));inputs.get().get(0).setText("12");inputs.get().get(0).setSelection(1);if(toolId==3){inputs.get().get(1).setText("0.125000");inputs.get().get(1).setSelection(4);}});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                    assertSame(original.get(),dialog(a));assertSame(inputs.get().get(0),fields(dialog(a)).get(0));
                    assertEquals("12",inputs.get().get(0).getText().toString());assertEquals(1,inputs.get().get(0).getSelectionStart());
                    assertEquals(Language.msg(toolId==1?l10n_utxo_coin_number:l10n_utxo_address_number).toString(),inputs.get().get(0).getHint().toString());
                    if(toolId==3){assertEquals("0.125000",inputs.get().get(1).getText().toString());assertEquals(4,inputs.get().get(1).getSelectionStart());assertEquals(Language.msg(l10n_utxo_amount_each).toString(),inputs.get().get(1).getHint().toString());}
                    exact(dialog(a).getWindow().getDecorView(),Language.msg(toolId==1?l10n_utxo_split_title:l10n_utxo_distribute_title));
                    exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_cancel));assertEquals(Design.text(),inputs.get().get(0).getCurrentTextColor());assertEquals(Design.dim(),inputs.get().get(0).getCurrentHintTextColor());assertTrue(node.get().requests.isEmpty());
                });guards.screenshot("nft-tool-"+mode.name()+"-"+toolId+"-"+code);}
                s.onActivity(a->{cancel(a);assertEquals(2,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());});settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
            }
        }
    }
    @Test public void darkSplitAndDistributeKeepInputs()throws Exception{forms(Design.Mode.CURRENT);}
    @Test public void lightSplitAndDistributeKeepInputs()throws Exception{forms(Design.Mode.CLEAN_LIGHT);}
    @Test public void consolidateAndUntrackRetainSelectionAndNeverRunCommands()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();s.onActivity(a->node.set(fixture(a)));
            for(int toolId:new int[]{2,4,0}){
                AtomicReference<Dialog> original=new AtomicReference<>();s.onActivity(a->{if(toolId==0)a.clearSelection();tool(a,toolId==0?2:toolId);original.set(dialog(a));});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(original.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();
                    if(toolId==0)exact(root,Language.msg(l10n_utxo_consolidate_help));else if(toolId==2)exact(root,Language.msg(l10n_utxo_merge_prompt,2,"Send"));else exact(root,Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_untrack_title,2));
                    assertEquals(toolId==0?0:2,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-tool-confirm-"+toolId+"-"+code);}
                s.onActivity(NftToolsLanguagesTest::cancel);settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
            }
        }
    }
    @Test public void distributionProgressKeepsJobAndToolStatus()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<String> saved=new AtomicReference<>();AtomicReference<DistributeJob> job=new AtomicReference<>();
            s.onActivity(a->{node.set(freeze(a));assertNull(DistributeJob.load(a));DistributeJob j=new DistributeJob();j.tokenid=TOKEN;j.tokenName="Send";j.per="0.125000";j.batchesDone=2;j.remaining.addAll(Arrays.asList(ADDRESS,ADDRESS));j.waiting=true;j.retryCount=1;j.currentInternalId="public-fixture-only";job.set(j);saved.set(j.toJson().toString());set(a.distribute(),"job",j);a.goToTab(MainActivity.TAB_WALLET);((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET].refresh();node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                CharSequence progress=Language.join(Language.msg(l10n_utxo_distribution_progress,2,2)," · ",Language.msg(l10n_utxo_distribution_waiting)," · ",Language.msg(l10n_utxo_distribution_retry,1,3));exact(a.getWindow().getDecorView(),progress);
                assertEquals(saved.get(),job.get().toJson().toString());assertNull(DistributeJob.load(a));assertTrue(node.get().requests.isEmpty());

            });guards.screenshot("nft-distribute-progress-"+code);}
            s.onActivity(a->{job.get().waiting=false;((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET].refresh();exact(a.getWindow().getDecorView(),Language.join(Language.msg(l10n_utxo_distribution_progress,2,2)," · ",Language.msg(l10n_utxo_status_posting)," · ",Language.msg(l10n_utxo_distribution_retry,1,3)));set(a.distribute(),"job",null);((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET].refresh();assertEquals(View.GONE,a.findViewById(R.id.pm_nftwallet_walletStatus).getVisibility());WalletView wallet=(WalletView)((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET];call(wallet,"setStatus",new Class<?>[]{CharSequence.class,boolean.class},Language.msg(l10n_utxo_consolidate_submitted),true);wallet.refresh();assertEquals(View.VISIBLE,a.findViewById(R.id.pm_nftwallet_walletStatus).getVisibility());exact(wallet.getRoot(),Language.msg(l10n_utxo_consolidate_submitted));assertTrue(node.get().requests.isEmpty());});
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
            for(String value:messages){CharSequence mapped=NftMessages.message(value);assertTrue(value,mapped instanceof Language.Message);assertFalse(mapped.toString().isEmpty());}
            assertTrue(NftMessages.message(messages[7]).toString().endsWith("unexpected 中文 {name} $1"));
            assertTrue(NftMessages.message(messages[8]).toString().contains(TX));
            assertTrue(NftMessages.message(messages[10]).toString().contains("Send 中文 {amount} $1"));
        });
    }

    private static class ConsolidationNode extends FixtureNode {
        Cb pending;org.json.JSONObject quote;
        ConsolidationNode(MainActivity a){super(a);
            org.json.JSONArray in=new org.json.JSONArray(),out=new org.json.JSONArray();
            for(int i=1;i<=3;i++)in.put(object("coinid","0x"+String.valueOf(i).repeat(64),"address",ADDRESS,"tokenid","0x00","amount","1"));
            out.put(object("coinid","0x"+"4".repeat(64),"address",ADDRESS,"tokenid","0x00","amount","1.5","storestate",true));
            out.put(object("coinid","0x"+"5".repeat(64),"address",ADDRESS,"tokenid","0x00","amount","1.5","storestate",true));
            quote=object("status",true,"response",object("txpowid",TX,"body",object("txn",object("transactionid","0x"+"6".repeat(64),"linkhash","0x00","state",new org.json.JSONArray(),"inputs",in,"outputs",out))));
        }
        @Override public void cmd(String command,Cb cb){requests.add(command);
            if(command.startsWith("consolidate ")){assertTrue(command.endsWith("dryrun:true"));cb.onResult(quote);}
            else if(command.startsWith("scripts address:"))cb.onResult(object("status",true,"response",object("address",ADDRESS,"default",true,"simple",true,"publickey",TX)));
            else if(command.startsWith("txnsign "))pending=cb;
            else if(command.startsWith("txn"))cb.onResult(object("status",true));
        }
    }
    private void consolidationOutcome(boolean unknown)throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<ConsolidationNode> node=new AtomicReference<>();AtomicReference<org.json.JSONObject> reserved=new AtomicReference<>();
            s.onActivity(a->{fixture(a);a.clearSelection();a.history().close();
                set(a,"historyDb",new NftSendLanguagesTest.FixtureHistory(a){
                    @Override boolean prepareAttempt(String id,String handle,org.json.JSONObject intent){assertFalse(node.get().requests.stream().anyMatch(c->c.startsWith("txncreate ")));reserved.set(TransactionEvidence.storedIntent(intent.toString()));return true;}
                    @Override boolean markAttempt(String id,String phase,org.json.JSONObject reply){if(HistoryDb.POSTING.equals(phase))assertNull(node.get().pending);return true;}
                });
                a.node().onDestroy();ConsolidationNode n=new ConsolidationNode(a);node.set(n);set(a,"node",n);
                tool(a,2);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_consolidate)).performClick();
                assertNotNull(n.pending);assertEquals(3,TransactionEvidence.inputIds(reserved.get()).size());
                a.toggleCoin(a.coins().get(0));assertEquals(1,a.selectedCoins().size());
                if(unknown)n.pending.onUnknown("Public fixture timeout");else n.pending.onResult(n.quote);
                // Even a late duplicate callback cannot rewrite the outcome or clear the new selection.
                n.pending.onResult(n.quote);
            });
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                exact(wallet(a).getRoot(),unknown?NftMessages.message(TransactionEvidence.UNKNOWN):Language.msg(l10n_utxo_consolidate_submitted));
                assertEquals(1,a.selectedCoins().size());assertEquals(1,node.get().requests.stream().filter(c->c.startsWith("txnsign ")).count());
                assertEquals(1,node.get().requests.stream().filter(c->c.startsWith("consolidate ")).count());
            });}
        }
    }
    @Test public void nativeConsolidationPreservesSelectionAndOutcomeAcrossLanguages()throws Exception{consolidationOutcome(false);}
    @Test public void uncertainNativeConsolidationCannotRepeatAcrossLanguages()throws Exception{consolidationOutcome(true);}

    private static class ToolNode extends FixtureNode {
        final List<Cb> addresses=new ArrayList<>();Cb posting;org.json.JSONObject intent;
        ToolNode(MainActivity a){super(a);}
        @Override public void cmd(String command,Cb cb){requests.add(command);
            if(command.equals("getaddress"))addresses.add(cb);
            else if(command.startsWith("txnsign "))posting=cb;
            else if(command.startsWith("txn"))cb.onResult(object("status",true));
        }
        void address(){addresses.remove(0).onResult(object("status",true,"response",object("address",ADDRESS)));}
        org.json.JSONObject receipt(){try{
            org.json.JSONArray out=new org.json.JSONArray(),expected=intent.getJSONArray("outputs");
            for(int i=0;i<expected.length();i++){org.json.JSONObject want=expected.getJSONObject(i);out.put(object("coinid","0x"+String.valueOf(i+3).repeat(64),"address",want.getString("address"),"amount",want.getString("amount"),"tokenid","0x00","storestate",true));}
            return object("status",true,"response",object("txpowid",TX,"body",object("txn",object("inputs",intent.getJSONArray("inputs"),"outputs",out))));
        }catch(Exception e){throw new AssertionError(e);}}
    }
    private static ToolNode toolFixture(MainActivity a){
        a.clearSelection();fixture(a);for(Coin c:a.coins())c.rawAmount=c.amount;
        a.node().onDestroy();ToolNode node=new ToolNode(a);set(a,"node",node);a.history().close();
        set(a,"historyDb",new NftSendLanguagesTest.FixtureHistory(a){
            @Override boolean prepareAttempt(String id,String handle,org.json.JSONObject intent){node.intent=TransactionEvidence.storedIntent(intent.toString());return true;}
        });return node;
    }
    private static void confirmTool(MainActivity a,int id){
        tool(a,id);if(id==1||id==3)fields(dialog(a)).get(0).setText("2");if(id==3)fields(dialog(a)).get(1).setText("1.0000");
    }
    private static int action(int id){return id==1?l10n_utxo_split:id==2?l10n_utxo_merge:id==3?l10n_utxo_distribute:l10n_utxo_untrack;}
    @Test public void changedToolConfirmationsStopBeforeCommandsInEveryLanguage()throws Exception {
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<ToolNode> node=new AtomicReference<>();
            for(int toolId:new int[]{1,2,3,4}){
                s.onActivity(a->{assertTrue(dialogs(a).isEmpty());node.set(toolFixture(a));confirmTool(a,toolId);});settle();
                s.onActivity(a->{Dialog review=dialog(a);assertTrue(review.isShowing());a.toggleCoin(a.selectedCoins().get(0));assertTrue(exact(review.getWindow().getDecorView(),Language.msg(action(toolId))).performClick());assertEquals(1,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());});settle();
                s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
                if(toolId==1)android.os.SystemClock.sleep(30000); // Drain stopped-node startup toasts before captures.
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                    TextView warning=exact(wallet(a).getRoot(),Language.msg(l10n_nft_tools_review_again));assertEquals(1,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());assertNull(node.get().intent);
                    android.graphics.Rect visible=new android.graphics.Rect();assertTrue(warning.getGlobalVisibleRect(visible));assertEquals(warning.getHeight(),visible.height());for(int line=0;line<warning.getLineCount();line++)assertEquals(0,warning.getLayout().getEllipsisCount(line));
                });if(toolId==1)guards.screenshot("nft-tools-review-"+code);}
            }
        }
    }
    @Test public void delayedToolAddressesCannotSpendChangedCoinsAcrossLanguages(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<ToolNode> node=new AtomicReference<>();
            s.onActivity(a->{ToolNode n=toolFixture(a);node.set(n);confirmTool(a,1);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_split)).performClick();assertEquals(1,n.addresses.size());a.selectedCoins().get(0).sendable=false;n.address();n.address();assertNull(n.posting);assertNull(n.intent);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{exact(wallet(a).getRoot(),Language.msg(l10n_nft_tools_review_again));assertEquals(2,a.selectedCoins().size());assertFalse(a.selectedCoins().get(0).sendable);assertEquals(Arrays.asList("getaddress","getaddress"),node.get().requests);});}
        }
    }
    @Test public void completedSplitKeepsANewSelectionAcrossLanguages(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<ToolNode> node=new AtomicReference<>();
            s.onActivity(a->{ToolNode n=toolFixture(a);node.set(n);confirmTool(a,1);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_split)).performClick();n.address();n.address();assertNotNull(n.posting);a.toggleCoin(a.selectedCoins().get(0));n.posting.onResult(n.receipt());n.posting.onResult(n.receipt());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                exact(wallet(a).getRoot(),Language.msg(l10n_utxo_tool_sent,Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_split_done,2)));assertEquals(1,a.selectedCoins().size());assertEquals("25.5000",a.selectedCoins().get(0).amount);
                assertEquals(1,node.get().requests.stream().filter(c->c.startsWith("txnsign ")).count());
            });}
        }
    }

}
