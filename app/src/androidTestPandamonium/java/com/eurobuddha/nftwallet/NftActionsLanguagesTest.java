package com.eurobuddha.nftwallet;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Display and refusal paths only; fake node refuses every transaction command. */
@RunWith(AndroidJUnit4.class)
public class NftActionsLanguagesTest {
    final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    static final String TOKEN="0x"+"ab".repeat(32), COIN="0x"+"cd".repeat(32), ADDRESS="0x"+"12".repeat(32);
    static final String NAME="Collection", ITEM="Collection #7";
    static final String[] CODES={"en","zh-Hans","ru","uk"};
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    static JSONObject coin(String stamp){return object("coinid",COIN,"tokenid",TOKEN,"address",ADDRESS,"tokenamount","1.0000","state",object("0",stamp,"1","[QUJDRA==]"));}
    static class PendingNode extends FixtureNode {
        final List<Cb> replies=new ArrayList<>();PendingNode(Context c){super(c);}
        @Override public void cmd(String command,Cb cb){assertFalse("No transaction allowed: "+command,command.startsWith("txn")||command.startsWith("send "));requests.add(command);if(command.equals("coins relevant:true own:true tokenid:"+TOKEN))replies.add(cb);}
    }
    static PendingNode fixture(MainActivity a){freeze(a);a.node().onDestroy();PendingNode n=new PendingNode(a);set(a,"node",n);return n;}
    static Object invoke(String name,Class<?>[] types,Object...args){try{Method m=StateNftActions.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}catch(Exception e){throw new AssertionError(e);}}
    static EditText field(Dialog d){List<EditText> fields=new ArrayList<>();for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)fields.add((EditText)t);assertEquals(1,fields.size());return fields.get(0);}
    static void close(MainActivity a,int label){exact(dialog(a).getWindow().getDecorView(),Language.msg(label)).performClick();}
    static void bounds(TextView t){NftSendLanguagesTest.visibleAction(t);}
    @Test public void singleTransferKeepsRecipientAndLiveFallbackName()throws Exception{
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<Dialog>d=new AtomicReference<>();JSONObject coin=coin("7");String before=coin.toString();
            s.onActivity(a->{n.set(fixture(a));StateNftActions.transferDialog(a,TOKEN,Language.join(Language.msg(l10n_nft_collection)," #7"),coin);d.set(dialog(a));field(d.get()).setText(ADDRESS);field(d.get()).setSelection(9);});settle();android.os.SystemClock.sleep(12000);
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));View v=d.get().getWindow().getDecorView();exact(v,Language.msg(l10n_nft_actions_transfer_name,Language.join(Language.msg(l10n_nft_collection)," #7")));exact(v,Language.msg(l10n_nft_actions_identity_replay));assertEquals(Language.msg(l10n_nft_actions_recipient_hint).toString(),field(d.get()).getHint().toString());assertEquals(ADDRESS,field(d.get()).getText().toString());assertEquals(9,field(d.get()).getSelectionStart());bounds(exact(v,Language.msg(l10n_nft_actions_transfer)));boolean scan=false;for(TextView t:texts(v))if(Language.msg(l10n_wallet_scan_qr).toString().equals(String.valueOf(t.getContentDescription()))){scan=true;assertTrue(t.getMinHeight()>=48*t.getResources().getDisplayMetrics().density-1);}assertTrue(scan);assertEquals(before,coin.toString());assertTrue(n.get().requests.isEmpty());});guards.screenshot("nft-actions-transfer-"+code);}
            s.onActivity(a->close(a,l10n_utxo_back));settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertTrue(n.get().requests.isEmpty());});
        }
    }
    void bury(Design.Mode mode)throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,mode));
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<Dialog>d=new AtomicReference<>();JSONObject c=coin("7");String raw=c.toString();
            s.onActivity(a->{n.set(fixture(a));StateNftActions.buryDialog(a,TOKEN,NAME,ITEM,c);d.set(dialog(a));field(d.get()).setText(NAME);field(d.get()).setSelection(4);});settle();android.os.SystemClock.sleep(12000);
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));View v=d.get().getWindow().getDecorView();exact(v,Language.msg(l10n_nft_actions_bury_name,ITEM));TextView warning=exact(v,Language.msg(l10n_nft_actions_bury_help,ITEM,NAME));assertTrue(warning.getText().toString().contains("RETURN FALSE"));assertEquals(Design.red(),warning.getCurrentTextColor());assertEquals(NAME,field(d.get()).getHint().toString());assertEquals(NAME,field(d.get()).getText().toString());assertEquals(4,field(d.get()).getSelectionStart());bounds(exact(v,Language.msg(l10n_nft_actions_bury_forever)));assertEquals(raw,c.toString());assertTrue(n.get().requests.isEmpty());});guards.screenshot("nft-actions-bury-"+mode.name()+"-"+code);}
            s.onActivity(a->{field(d.get()).setText("Collections");close(a,l10n_nft_actions_bury_forever);});settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertTrue(n.get().requests.isEmpty());assertEquals(raw,c.toString());});
        }
    }
    @Test public void buryConfirmationKeepsLiteralNameInDarkTheme()throws Exception{bury(Design.Mode.CURRENT);}
    @Test public void buryConfirmationKeepsLiteralNameInLightTheme()throws Exception{bury(Design.Mode.CLEAN_LIGHT);}
    @Test public void wholeCollectionFormsKeepTheirDraftsAndRawConfirmation(){
        for(boolean bury:new boolean[]{false,true})try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<Dialog>d=new AtomicReference<>();AtomicInteger completed=new AtomicInteger();
            s.onActivity(a->{n.set(fixture(a));if(bury)StateNftActions.buryCollectionDialog(a,TOKEN,NAME,"0x1234",completed::incrementAndGet);else StateNftActions.transferCollectionDialog(a,TOKEN,NAME,completed::incrementAndGet);d.set(dialog(a));field(d.get()).setText(bury?NAME:ADDRESS);field(d.get()).setSelection(4);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));View v=d.get().getWindow().getDecorView();exact(v,Language.msg(bury?l10n_nft_actions_bury_collection:l10n_nft_gallery_send_collection));exact(v,bury?Language.msg(l10n_nft_actions_collection_bury_help,NAME,NAME):Language.msg(l10n_nft_actions_collection_transfer_help,NAME));assertEquals(bury?NAME:ADDRESS,field(d.get()).getText().toString());assertEquals(4,field(d.get()).getSelectionStart());if(bury)assertEquals(NAME,field(d.get()).getHint().toString());bounds(exact(v,Language.msg(bury?l10n_nft_actions_bury_all:l10n_nft_actions_send_all)));assertEquals(0,completed.get());assertTrue(n.get().requests.isEmpty());});}
            s.onActivity(a->close(a,l10n_utxo_back));settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(0,completed.get());assertTrue(n.get().requests.isEmpty());});
        }
    }
    @Test public void refusalDialogsKeepAllExistingSafetyGates(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();s.onActivity(a->n.set(fixture(a)));
            for(int mode=0;mode<6;mode++){
                final int m=mode;AtomicReference<Dialog>d=new AtomicReference<>();
                s.onActivity(a->{if(m==0)StateNftActions.transferDialog(a,TOKEN,ITEM,coin("0"));else if(m==1)StateNftActions.transferDialog(a,TOKEN,ITEM,coin("7 burn:10"));else if(m==2)StateNftActions.buryDialog(a,TOKEN,NAME,ITEM,coin("0"));else if(m==3)StateNftActions.buryDialog(a,TOKEN,NAME,ITEM,coin("7 burn:10"));else if(m==4)StateNftActions.transferDialog(a,"0xab;send",ITEM,coin("7"));else StateNftActions.buryCollectionDialog(a,"0xab;send",NAME,"0x1234",null);d.set(dialog(a));});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));View v=d.get().getWindow().getDecorView();int[] title={l10n_nft_actions_cannot_transfer,l10n_nft_actions_cannot_transfer,l10n_nft_actions_cannot_bury,l10n_nft_actions_cannot_bury,l10n_nft_actions_refuse_coin,l10n_nft_actions_refuse_collection};int[] body={l10n_nft_actions_unstamped_transfer,l10n_nft_actions_unsafe_transfer,l10n_nft_actions_unstamped_bury,l10n_nft_actions_unsafe_bury,l10n_nft_actions_bad_coin_id,l10n_nft_actions_bad_collection_id};exact(v,Language.msg(title[m]));exact(v,Language.msg(body[m]));exact(v,Language.msg(l10n_language_close));assertTrue(n.get().requests.isEmpty());});}
                s.onActivity(a->close(a,l10n_language_close));settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
            }
        }
    }
    @Test public void delayedCollectionRepliesUseCurrentLanguageWithoutPosting(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicInteger done=new AtomicInteger();s.onActivity(a->n.set(fixture(a)));
            for(int mode=0;mode<5;mode++){
                final int m=mode;AtomicReference<Dialog>d=new AtomicReference<>();s.onActivity(a->{if(m==4)invoke("gatherAndBury",new Class<?>[]{MainActivity.class,String.class,String.class,String.class,Runnable.class},a,TOKEN,NAME,"0x1234",(Runnable)done::incrementAndGet);else invoke("gatherAndTransfer",new Class<?>[]{MainActivity.class,String.class,String.class,String.class,Runnable.class},a,TOKEN,NAME,ADDRESS,(Runnable)done::incrementAndGet);d.set(dialog(a));Language.select(a,"uk");});settle();
                s.onActivity(a->{NodeApi.Cb cb=n.get().replies.get(m);if(m==2)cb.onError(NodeApi.ERR_TOO_LONG);else if(m==3)cb.onError("Raw failure 设置 12.3400");else cb.onResult(object("status",true,"response",m==1?new JSONArray().put(coin("0")):new JSONArray()));});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));View v=d.get().getWindow().getDecorView();CharSequence report=m==0?Language.msg(l10n_nft_actions_no_items):m==1?Language.msg(l10n_nft_actions_skipped_unsafe,1):m==2?Language.msg(l10n_nft_actions_transfer_list_large):m==3?Language.msg(l10n_nft_actions_list_items_failed,"Raw failure 设置 12.3400"):Language.msg(l10n_nft_actions_no_coins);exact(v,report);exact(v,Language.msg(l10n_language_close));assertEquals(m+1,n.get().requests.size());assertEquals(0,done.get());});}
                s.onActivity(a->close(a,l10n_language_close));settle();
            }
        }
    }
    @Test public void finalTransferReportKeepsCountsAndLiteralErrors(){
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<Dialog>d=new AtomicReference<>();AtomicInteger completed=new AtomicInteger();AtomicInteger requests=new AtomicInteger();
            s.onActivity(a->{n.set(fixture(a));Sheet.Progress p=Sheet.progress(a,Language.msg(l10n_nft_actions_sending_name,NAME),"");invoke("transferNext",new Class<?>[]{MainActivity.class,String.class,String.class,List.class,int.class,int[].class,int.class,String[].class,Sheet.Progress.class,Runnable.class},a,TOKEN,ADDRESS,Arrays.asList(coin("7"),coin("8")),2,new int[]{1},3,new String[]{"Raw error Блок 0x1234"},p,(Runnable)completed::incrementAndGet);d.set(dialog(a));requests.set(n.get().requests.size());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));exact(d.get().getWindow().getDecorView(),Language.join(Language.msg(l10n_nft_actions_posted_transfers,1,2),Language.msg(l10n_nft_actions_skipped_count,3),"\n\n",Language.msg(l10n_nft_actions_failed_first,1,"Raw error Блок 0x1234"),"\n\n",Language.msg(l10n_nft_actions_chain_decides)));assertEquals(1,completed.get());assertEquals(requests.get(),n.get().requests.size());});}
            s.onActivity(a->close(a,l10n_language_close));settle();
        }
    }
}
