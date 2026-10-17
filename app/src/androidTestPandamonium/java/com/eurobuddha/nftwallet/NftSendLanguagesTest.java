package com.eurobuddha.nftwallet;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.*;
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
/** Public fixtures and recorded commands only: no node, seed, signing, posting or history writes. */
@RunWith(AndroidJUnit4.class)
public class NftSendLanguagesTest {
    private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    private static final String ADDRESS="0x"+"12".repeat(32), CHANGE="0x"+"34".repeat(32), COIN="0x"+"56".repeat(32);
    private static final String TOKEN="0x"+"ab".repeat(32), OTHER="0x"+"cd".repeat(32);
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    static class PendingNode extends FixtureNode {
        final List<Cb> checks=new ArrayList<>(), sends=new ArrayList<>(), signs=new ArrayList<>();
        boolean holdSigning;
        PendingNode(Context c){super(c);}
        @Override public void cmd(String command,Cb cb){
            requests.add(command);
            if(command.startsWith("checkaddress "))checks.add(cb);
            else if(command.startsWith("sendnosign "))cb.onResult(object("status",true));
            else if(command.startsWith("sendview "))cb.onResult(unsigned());
            else if(command.startsWith("scripts address:"))cb.onResult(object("status",true,"response",object("address",CHANGE,"default",true,"simple",true,"publickey",COIN)));
            else if(command.startsWith("sendsign ")){if(holdSigning)signs.add(cb);else cb.onResult(object("status",true,"response",object("txpow","/public/fixture/signedtransaction-123.txn")));}
            else if(command.startsWith("sendpost "))sends.add(cb);
            else assertFalse("Unexpected transaction command: "+command,command.startsWith("txn"));
        }
        void checked(int index){checks.get(index).onResult(object("status",true,"response",object("address",ADDRESS,"relevant",false,"simple",true)));}
    }
    /** In-memory acknowledgement fixture. It never opens or changes the SQLite database. */
    static class FixtureHistory extends HistoryDb {
        FixtureHistory(Context c){super(c);}
        @Override void abandonPreparations(NodeApi owner){}
        @Override boolean hasUnresolvedAttempt(String token){return false;}
        @Override public boolean insertPosting(HistoryRow row){return true;}
        @Override boolean prepareAttempt(String id,String handle,org.json.JSONObject intent){TransactionEvidence.storedIntent(intent.toString());return true;}
        @Override boolean markAttempt(String id,String phase,org.json.JSONObject reply){return true;}
        @Override public boolean update(String id,String status,String txid,String note){return true;}
        @Override public java.util.List<NodeTx> loadNodeTx(int limit){return Collections.emptyList();}
    }
    static org.json.JSONObject unsigned(){
        org.json.JSONArray outputs=new org.json.JSONArray();
        for(int i=0;i<3;i++)outputs.put(object("coinid","0x"+String.valueOf(i+1).repeat(64),"address",ADDRESS,"tokenid",TOKEN,"amount","4.12","tokenamount","4.12"));
        outputs.put(object("coinid","0x"+"4".repeat(64),"address",CHANGE,"tokenid",TOKEN,"amount","37.64","tokenamount","37.64"));
        org.json.JSONObject main=object("transactionid",ADDRESS,"inputs",new org.json.JSONArray().put(object("coinid",COIN,"address",CHANGE,"tokenid",TOKEN,"amount","50","tokenamount","50")),"outputs",outputs);
        org.json.JSONObject burn=object("transactionid",CHANGE,"linkhash",ADDRESS,"inputs",new org.json.JSONArray().put(object("coinid","0x"+"7".repeat(64),"address",CHANGE,"tokenid","0x00","amount","1")),"outputs",new org.json.JSONArray().put(object("coinid","0x"+"8".repeat(64),"address",CHANGE,"tokenid","0x00","amount","0.999")));
        return object("status",true,"response",object("txpow",object("txpowid","0x"+"9".repeat(64),"body",object("txn",main,"burntxn",burn))));
    }
    static TokenBalance balance(String id,String name){return TokenBalance.from(object("tokenid",id,"token",name==null?object("decimals",8):object("name",name,"decimals",8),"sendable","50","confirmed","50","total","100","coins",2));}
    static PendingNode fixture(MainActivity a,boolean quick){
        freeze(a);seedDisplay(a);a.node().onDestroy();PendingNode n=new PendingNode(a);set(a,"node",n);set(a,"historyDb",new FixtureHistory(a));
        a.balances().clear();a.balances().add(balance(TOKEN,"Send"));a.balances().add(balance(OTHER,null));
        if(quick)send(a).setFieldValues(new String[]{ADDRESS,"12.340000",CHANGE,"0.001","3","q",TOKEN});
        n.requests.clear();return n;
    }
    static void preview(MainActivity a){call(send(a),"onPreview",new Class<?>[]{});}
    static TextView status(MainActivity a){return a.findViewById(R.id.pm_nftwallet_sendStatus);}
    static void noWrites(PendingNode n){for(String request:n.requests)assertFalse(request,request.startsWith("send")||request.startsWith("txn"));}
    static void visibleAction(TextView t){assertTrue(t.isShown());assertNotNull(t.getLayout());for(int i=0;i<t.getLineCount();i++)assertEquals(0,t.getLayout().getEllipsisCount(i));android.graphics.Rect bounds=new android.graphics.Rect();assertTrue(t.getGlobalVisibleRect(bounds));assertTrue("Only pixel rounding may differ: "+bounds,Math.abs(t.getHeight()-bounds.height())<=1);int[] xy=new int[2];t.getLocationOnScreen(xy);android.graphics.Rect screen=((android.app.Activity)t.getContext()).getWindowManager().getCurrentWindowMetrics().getBounds();assertTrue(xy[1]>=screen.top);assertTrue(xy[1]+t.getHeight()<=screen.bottom);assertTrue(t.getHeight()>=48*t.getResources().getDisplayMetrics().density-1);}
    private void confirmation(boolean quick,Design.Mode mode)throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(c,mode));
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();AtomicReference<Dialog> retained=new AtomicReference<>();AtomicReference<String[]> draft=new AtomicReference<>();
            s.onActivity(a->{PendingNode n=fixture(a,quick);node.set(n);draft.set(send(a).fieldValues());input(a,R.id.pm_nftwallet_sendAddress).setSelection(5);preview(a);assertEquals(1,n.checks.size());assertFalse(a.findViewById(R.id.pm_nftwallet_previewBtn).isEnabled());Language.select(a,"uk");});settle();
            s.onActivity(a->{node.get().checked(0);retained.set(dialog(a));});settle();android.os.SystemClock.sleep(12000);
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(retained.get(),dialog(a));View form=a.getWindow().getDecorView();for(int label:new int[]{l10n_nft_send_quick_mode,l10n_nft_send_coin_mode,l10n_utxo_recipient,l10n_utxo_amount,l10n_utxo_burn_label,l10n_utxo_preview,l10n_eth_wallet_scan})exact(form,Language.msg(label));View v=retained.get().getWindow().getDecorView();exact(v,Language.msg(l10n_utxo_confirm));exact(v,ADDRESS);exact(v,"12.34 Send");
                visibleAction(exact(v,Language.msg(quick?l10n_utxo_send_action:l10n_utxo_sign_post)));visibleAction(exact(v,Language.msg(l10n_utxo_back)));
                if(quick){exact(v,Language.msg(l10n_nft_send_equal_coins,3));exact(v,Language.msg(l10n_nft_send_node_chosen));exact(v,Language.msg(l10n_nft_send_warning_quick));}
                else {exact(v,COIN);exact(v,CHANGE);exact(v,"37.659 Send");TextView verify=exact(v,Language.join(Language.msg(l10n_utxo_change),"  ",Language.msg(l10n_utxo_verify)));assertEquals(Design.red(),verify.getCurrentTextColor());}
                assertArrayEquals(draft.get(),send(a).fieldValues());assertEquals(5,input(a,R.id.pm_nftwallet_sendAddress).getSelectionStart());assertEquals(1,node.get().checks.size());noWrites(node.get());
            });guards.screenshot("nft-send-"+(quick?"quick":"coins")+"-"+mode.name()+"-"+code);}
            s.onActivity(a->{View v=retained.get().getWindow().getDecorView();exact(v,ADDRESS).performClick();android.content.ClipboardManager cm=(android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(ADDRESS,cm.getPrimaryClip().getItemAt(0).getText().toString());exact(v,Language.msg(l10n_utxo_back)).performClick();});settle();
            s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertArrayEquals(draft.get(),send(a).fieldValues());noWrites(node.get());});
        }
    }
    @Test public void quickReviewKeepsDraftAndFullRecipient()throws Exception{confirmation(true,Design.Mode.CURRENT);}
    @Test public void coinReviewKeepsExactValuesInDarkTheme()throws Exception{confirmation(false,Design.Mode.CURRENT);}
    @Test public void coinReviewKeepsExactValuesInLightTheme()throws Exception{confirmation(false,Design.Mode.CLEAN_LIGHT);}
    @Test public void tokenPickerRetainsLiteralNamesAndMissingNameBinding(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<Dialog>d=new AtomicReference<>();s.onActivity(a->{n.set(fixture(a,true));call(send(a),"showTokenPicker",new Class<?>[]{});d.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));View v=d.get().getWindow().getDecorView();exact(v,"Send");exact(v,Language.msg(l10n_wallet_token));exact(v,Language.msg(l10n_nft_send_which_token));exact(v,Language.msg(l10n_utxo_cancel));assertTrue(n.get().requests.isEmpty());});}
            s.onActivity(a->{((View)exact(d.get().getWindow().getDecorView(),Language.msg(l10n_wallet_token)).getParent()).performClick();assertEquals(OTHER,send(a).fieldValues()[6]);});settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(Language.msg(l10n_wallet_token).toString(),((TextView)a.findViewById(R.id.pm_nftwallet_sendTokenLabel)).getText().toString());noWrites(n.get());});
        }
    }
    @Test public void delayedChecksRejectEditedDraftsAndSupersededReplies(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();
            s.onActivity(a->{PendingNode n=fixture(a,true);node.set(n);preview(a);send(a).preselectToken(OTHER);n.checked(0);assertTrue(dialogs(a).isEmpty());assertEquals(Language.msg(l10n_nft_send_review_again).toString(),status(a).getText().toString());
                preview(a);preview(a);n.checks.get(1).onError("late error");assertFalse(a.findViewById(R.id.pm_nftwallet_previewBtn).isEnabled());n.checked(2);Dialog current=dialog(a);n.checked(1);assertSame(current,dialog(a));
            });settle();s.onActivity(a->dialog(a).dismiss());settle();
            for(int id:new int[]{R.id.pm_nftwallet_sendAddress,R.id.pm_nftwallet_sendAmount,R.id.pm_nftwallet_sendBurn,R.id.pm_nftwallet_sendSplit}){
                s.onActivity(a->{PendingNode n=node.get();assertTrue(dialogs(a).isEmpty());send(a).setFieldValues(new String[]{ADDRESS,"12.340000",CHANGE,"0.001","3","q",TOKEN});preview(a);input(a,id).setText(id==R.id.pm_nftwallet_sendAddress?CHANGE:"2");n.checked(n.checks.size()-1);assertTrue(dialogs(a).isEmpty());assertEquals(Language.msg(l10n_nft_send_review_again).toString(),status(a).getText().toString());noWrites(n);});settle();
            }
        }
    }
    @Test public void staleConfirmationsCannotSendAnotherTokenOrChangedCoins(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();
            s.onActivity(a->{PendingNode n=fixture(a,true);node.set(n);preview(a);n.checked(0);});settle();
            s.onActivity(a->{Dialog d=dialog(a);send(a).preselectToken(OTHER);exact(d.getWindow().getDecorView(),Language.msg(l10n_utxo_send_action)).performClick();assertTrue(node.get().sends.isEmpty());});settle();
            s.onActivity(a->{assertTrue(dialogs(a).isEmpty());send(a).setFieldValues(new String[]{ADDRESS,"12.340000",CHANGE,"0.001","3","c","0x00"});preview(a);node.get().checked(1);});settle();
            s.onActivity(a->{Dialog d=dialog(a);a.clearSelection();exact(d.getWindow().getDecorView(),Language.msg(l10n_utxo_sign_post)).performClick();noWrites(node.get());assertEquals(Language.msg(l10n_nft_send_review_again).toString(),status(a).getText().toString());});settle();
            s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
        }
    }
    @Test public void quickCommandUsesReviewedTokenAndLateSuccessKeepsNewDraft(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();AtomicReference<String[]> changed=new AtomicReference<>();s.onActivity(a->{PendingNode n=fixture(a,true);node.set(n);input(a,R.id.pm_nftwallet_sendAmount).setText("12.360000");preview(a);n.checked(0);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_send_action)).performClick();assertEquals(1,n.sends.size());assertTrue(n.requests.stream().anyMatch(command->command.startsWith("sendnosign address:"+ADDRESS+" amount:12.36 tokenid:"+TOKEN+" split:3 burn:0.001 file:nft_send_")));assertEquals("sendpost file:signedtransaction-123.txn",n.requests.get(n.requests.size()-1));assertEquals(true,get(send(a),"sending"));
                send(a).preselectToken(OTHER);input(a,R.id.pm_nftwallet_sendAmount).setText("7.5000");input(a,R.id.pm_nftwallet_sendAmount).setSelection(3);changed.set(send(a).fieldValues());Language.select(a,"ru");});settle();s.onActivity(a->{node.get().sends.get(0).onResult(unsigned());assertArrayEquals(changed.get(),send(a).fieldValues());assertEquals(3,input(a,R.id.pm_nftwallet_sendAmount).getSelectionStart());assertEquals(Language.msg(l10n_nft_send_sent,"12.36","Send",ADDRESS).toString(),status(a).getText().toString());assertEquals(false,get(send(a),"sending"));assertEquals(1,node.get().sends.size());});
        }
    }
    @Test public void uncertainQuickSendKeepsItsDraftAndDoesNotRepeatSigningOnLanguageChange(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();AtomicReference<String[]> draft=new AtomicReference<>();
            s.onActivity(a->{PendingNode n=fixture(a,true);node.set(n);input(a,R.id.pm_nftwallet_sendAmount).setText("12.360000");preview(a);n.checked(0);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_send_action)).performClick();draft.set(send(a).fieldValues());assertEquals(1,n.sends.size());n.sends.get(0).onUnknown("fixture timeout");});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertArrayEquals(draft.get(),send(a).fieldValues());assertEquals(NftMessages.message(TransactionEvidence.UNKNOWN).toString(),status(a).getText().toString());assertEquals(false,get(send(a),"sending"));assertEquals(1,node.get().requests.stream().filter(command->command.startsWith("sendsign ")).count());assertEquals(1,node.get().sends.size());});}
        }
    }
    @Test public void interruptedSigningKeepsDraftAndNeverPostsAcrossLanguages()throws Exception {
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();AtomicReference<String[]> draft=new AtomicReference<>();
            s.onActivity(a->{PendingNode n=fixture(a,true);n.holdSigning=true;node.set(n);a.goToTab(MainActivity.TAB_SEND);input(a,R.id.pm_nftwallet_sendAmount).setText("12.360000");preview(a);n.checked(0);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_utxo_send_action)).performClick();draft.set(send(a).fieldValues());assertEquals(1,n.signs.size());n.signs.get(0).onUnknown("fixture signing timeout");n.signs.get(0).onResult(object("status",true,"response",object("txpow","/public/fixture/signedtransaction-123.txn")));});
            android.os.SystemClock.sleep(30000); // Reuse the outcome capture's stopped-node toast wait.
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertArrayEquals(draft.get(),send(a).fieldValues());assertEquals(Language.msg(l10n_utxo_failed,NftMessages.message(TransactionEvidence.NOT_SENT)).toString(),status(a).getText().toString());assertEquals(false,get(send(a),"sending"));
                assertEquals(1,node.get().requests.stream().filter(command->command.startsWith("sendsign ")).count());assertTrue(node.get().sends.isEmpty());
                TextView label=status(a);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(label.getGlobalVisibleRect(visible));assertEquals(label.getHeight(),visible.height());for(int line=0;line<label.getLineCount();line++)assertEquals(0,label.getLayout().getEllipsisCount(line));
            });guards.screenshot("nft-preparation-interrupted-"+code);}
        }
    }
    @Test public void validationAndProgressBindingsRetainLiteralDiagnostics(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> node=new AtomicReference<>();s.onActivity(a->{node.set(fixture(a,true));input(a,R.id.pm_nftwallet_sendAmount).setText("0");preview(a);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{exact(a.getWindow().getDecorView(),Language.msg(l10n_utxo_positive_amount));assertEquals("0",input(a,R.id.pm_nftwallet_sendAmount).getText().toString());assertTrue(node.get().requests.isEmpty());});}
            s.onActivity(a->{call(send(a),"status",new Class<?>[]{CharSequence.class,boolean.class},NftMessages.message("Adding inputs (2/7)…"),true);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals(Language.msg(l10n_utxo_adding_inputs,"2","7").toString(),status(a).getText().toString());assertEquals("Unrecognised Send 12.3400",NftMessages.message("Unrecognised Send 12.3400").toString());assertEquals(Language.msg(l10n_explorer_node_retry).toString(),NftMessages.message(NodeApi.ERR_NOT_ENABLED).toString());noWrites(node.get());});}
        }
    }
}
