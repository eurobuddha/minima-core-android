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
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public display fixtures only. No signing, minting, transfer, bury or external link action is invoked. */
@RunWith(AndroidJUnit4.class)
public class NftDetailsLanguagesTest {
    private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String ADDRESS="0x"+"12".repeat(32),TOKEN="0x"+"78".repeat(32),COIN="0x"+"9a".repeat(32),MISSING="0x"+"bc".repeat(32),PK="0x"+"de".repeat(32);
    private static final String DESC="Token 中文 Русский Українська {amount} $1",URL="https://example.invalid/NFT?title=Send&lang=uk%20zh",STATE="literal-state-"+"中文 Русский Українська $1 ".repeat(12);
    private boolean fixtureAuthorized;
    @Before public void prepare(){guards.prepare();assertTrue(HiddenTokens.all(context).isEmpty());fixtureAuthorized=true;}
    @After public void reset(){if(fixtureAuthorized)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->HiddenTokens.unhide(context,TOKEN));guards.reset();}
    private static BalancesView balances(MainActivity a){return (BalancesView)((BaseView[])get(a,"views"))[MainActivity.TAB_BALANCES];}
    private static Dialog newest(MainActivity a,Dialog old){for(Dialog d:dialogs(a))if(d!=old)return d;throw new AssertionError("No new owned dialog");}
    private static View root(Dialog d){return d.getWindow().getDecorView();}
    private static TextView action(View root,CharSequence label){for(TextView t:texts(root))if(t.isClickable() && label.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing action "+label);}
    private static void clipboard(MainActivity a,String expected){android.content.ClipboardManager cm=(android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(expected,cm.getPrimaryClip().getItemAt(0).getText().toString());}
    static class DetailsNode extends FixtureNode {
        Cb tokenReply,coinReply;DetailsNode(Context c){super(c);}
        @Override public void cmd(String cmd,Cb cb){requests.add(cmd);if(cmd.equals("tokens tokenid:"+TOKEN))tokenReply=cb;else if(cmd.equals("coins relevant:true own:true tokenid:"+TOKEN))coinReply=cb;}
        void token(){assertNotNull(tokenReply);tokenReply.onResult(object("status",true,"response",object("script",StateNft.script(PK,"embed"),"name",object("name","Send","size",5,"mode","embed"),"total","5")));}
        void coins(JSONObject c){assertNotNull(coinReply);coinReply.onResult(object("status",true,"response",new JSONArray().put(c)));}
    }
    private static JSONObject coinJson(){return object("coinid",COIN,"address",ADDRESS,"miniaddress",ADDRESS,"tokenid",TOKEN,"amount","0.0000000001234000","tokenamount","12.340000","token",object("name",object("name","Send")),"created","90","mmrentry","12345678901234567890","storestate",true,"state",new JSONArray().put(object("port","0","data","3")).put(object("port","2","data",STATE)));}
    private static DetailsNode fixture(MainActivity a){
        freeze(a).onDestroy();DetailsNode node=new DetailsNode(a);set(a,"node",node);set(a,"chainBlock",100);
        a.balances().clear();a.balances().add(TokenBalance.from(object("tokenid",TOKEN,"token",object("name",object("name","Send","ticker","TOKEN","description",DESC,"owner","public owner"),"decimals","8"),"confirmed","50.750000","sendable","25.500000","unconfirmed","0.125000","total","5","coins",2)));
        a.balances().add(TokenBalance.from(object("tokenid",MISSING,"token",object("ticker","MISSING"),"confirmed","1","sendable","1","total","100","coins",1)));
        a.myAddresses().add(new String[]{ADDRESS,ADDRESS});Coin c=Coin.from(coinJson());c.sendable=true;a.coins().clear();a.coins().add(c);
        a.goToTab(MainActivity.TAB_BALANCES);balances(a).refresh();node.requests.clear();return node;
    }
    private static Dialog tokenDetail(MainActivity a){((LinearLayout)a.findViewById(R.id.pm_nftwallet_balancesContainer)).getChildAt(0).performClick();return dialog(a);}
    @Test public void balanceCardsNamesAndSearchCursorStayInPlace()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<DetailsNode> node=new AtomicReference<>();AtomicReference<View> card=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));card.set(((LinearLayout)a.findViewById(R.id.pm_nftwallet_balancesContainer)).getChildAt(0));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{View r=balances(a).getRoot();exact(r,"Send");exact(r,"TOKEN");exact(r,"MISSING");exact(r,Language.msg(l10n_wallet_token));exact(r,"25.5");exact(r,Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_coin_count,2)));exact(r,Language.upper(Language.msg(l10n_nft_wallet_unconfirmed)));assertSame(card.get(),((LinearLayout)a.findViewById(R.id.pm_nftwallet_balancesContainer)).getChildAt(0));assertEquals(Language.msg(l10n_nft_wallet_balance_search).toString(),((EditText)a.findViewById(R.id.pm_nftwallet_balSearch)).getHint().toString());assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-balances-"+code);}
            s.onActivity(a->{EditText search=a.findViewById(R.id.pm_nftwallet_balSearch);search.setText("missing 中文 $1");search.setSelection(4);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{EditText search=a.findViewById(R.id.pm_nftwallet_balSearch);assertEquals("missing 中文 $1",search.getText().toString());assertEquals(4,search.getSelectionStart());exact(balances(a).getRoot(),Language.msg(l10n_nft_wallet_nothing_matches,"missing 中文 $1"));assertTrue(node.get().requests.isEmpty());});}
        }
    }
    @Test public void delayedContractAndCoinRowsKeepRawValuesAndOpenState()throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,Design.Mode.CLEAN_LIGHT));
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<DetailsNode> node=new AtomicReference<>();AtomicReference<Dialog> detail=new AtomicReference<>();AtomicReference<TextView> script=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));detail.set(tokenDetail(a));assertEquals(Arrays.asList("tokens tokenid:"+TOKEN,"coins relevant:true own:true tokenid:"+TOKEN),node.get().requests);Language.select(a,"uk");});settle();
            s.onActivity(a->{node.get().token();node.get().coins(a.coins().get(0).raw);View r=root(detail.get());exact(r,Language.msg(l10n_nft_wallet_view_contract)).performClick();script.set(exact(r,StateNft.script(PK,"embed")));assertEquals(View.VISIBLE,script.get().getVisibility());node.get().requests.clear();});
            // Let Android's earlier clipboard/toast overlays expire before inspecting the action row.
            android.os.SystemClock.sleep(12000);
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(detail.get(),dialog(a));View r=root(detail.get());for(int label:new int[]{l10n_utxo_close,l10n_utxo_receive,l10n_utxo_send}){TextView action=action(r,Language.msg(label));assertEquals("Wrapped action: "+action.getText(),1,action.getLineCount());android.graphics.Rect bounds=new android.graphics.Rect();assertTrue(action.getGlobalVisibleRect(bounds));assertTrue(bounds.height()>=action.getHeight());if(label==l10n_utxo_send){assertEquals(Design.onAccent(),action.getCurrentTextColor());assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(action.getCurrentTextColor(),Design.accent())>=4.5);}}exact(r,"Send");exact(r,TOKEN);exact(r,DESC);exact(r,"50.75");exact(r,"25.5");exact(r,"0.125");exact(r,Language.msg(l10n_nft_wallet_locked_contracts));exact(r,Language.msg(l10n_nft_wallet_state_contract));exact(r,Language.msg(l10n_nft_wallet_hide_contract));exact(r,Language.msg(l10n_nft_wallet_send_collection));exact(r,Language.msg(l10n_nft_wallet_bury_collection));exact(r,Language.msg(l10n_nft_wallet_coin_details,1));exact(r,Language.join(Util.shorten(COIN),"  ·  ",Language.msg(l10n_nft_wallet_age_blocks,10L)));assertSame(script.get(),exact(r,StateNft.script(PK,"embed")));assertEquals(View.VISIBLE,script.get().getVisibility());assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-token-detail-"+code);}
            s.onActivity(a->{exact(root(detail.get()),TOKEN).performClick();clipboard(a,TOKEN);script.get().performClick();clipboard(a,StateNft.script(PK,"embed"));assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void coinSheetPreservesFullStateIdentifiersAndAmounts()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<DetailsNode> node=new AtomicReference<>();AtomicReference<Dialog> sheet=new AtomicReference<>();AtomicReference<String> raw=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));Coin c=a.coins().get(0);raw.set(c.raw.toString());CoinDetailDialog.show(a,c);sheet.set(dialog(a));assertEquals(Collections.singletonList("tokens tokenid:"+TOKEN),node.get().requests);node.get().token();node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(sheet.get(),dialog(a));View r=root(sheet.get());exact(r,"12.34  Send");exact(r,COIN);exact(r,TOKEN);exact(r,ADDRESS);exact(r,"0.0000000001234000");exact(r,"12345678901234567890");exact(r,Language.msg(l10n_nft_wallet_state_variables,2));exact(r,Language.msg(l10n_nft_wallet_port,"2"));exact(r,Language.join("90","   (",Language.msg(l10n_nft_wallet_age_blocks,10L),")"));exact(r,Language.msg(l10n_nft_wallet_transfer));exact(r,Language.msg(l10n_nft_wallet_bury));assertEquals(raw.get(),a.coins().get(0).raw.toString());assertEquals("12.340000",a.coins().get(0).amount);assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-coin-detail-"+code);}
            s.onActivity(a->{View r=root(sheet.get());exact(r,COIN).performClick();clipboard(a,COIN);exact(r,STATE.substring(0,117)+"…").performClick();clipboard(a,STATE);exact(r,"0.0000000001234000").performClick();clipboard(a,"0.0000000001234000");assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void hidePromptAndSettingsKeepLiteralNameAndUserChoice()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<DetailsNode> node=new AtomicReference<>();AtomicReference<Dialog> detail=new AtomicReference<>(),prompt=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));detail.set(tokenDetail(a));exact(root(detail.get()),Language.msg(l10n_nft_wallet_hide_token)).performClick();prompt.set(newest(a,detail.get()));node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals(2,dialogs(a).size());assertTrue(dialogs(a).contains(prompt.get()));exact(root(prompt.get()),Language.msg(l10n_nft_wallet_hide_question));exact(root(prompt.get()),Language.msg(l10n_nft_wallet_hide_help,"Send"));exact(root(prompt.get()),Language.msg(l10n_utxo_cancel));assertFalse(HiddenTokens.isHidden(a,TOKEN));assertTrue(node.get().requests.isEmpty());});}
            s.onActivity(a->exact(root(prompt.get()),Language.msg(l10n_nft_wallet_hide_action)).performClick());settle();
            s.onActivity(a->{assertTrue(HiddenTokens.isHidden(a,TOKEN));detail.get().dismiss();});settle();
            s.onActivity(a->{exact(balances(a).getRoot(),Language.msg(l10n_nft_wallet_hidden_count,1));SettingsDialog.show(a);prompt.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{exact(root(prompt.get()),"Send  ·  "+Util.shorten(TOKEN));exact(root(prompt.get()),Language.msg(l10n_nft_wallet_unhide));assertTrue(HiddenTokens.isHidden(a,TOKEN));assertTrue(node.get().requests.isEmpty());});}
            s.onActivity(a->{exact(root(prompt.get()),Language.msg(l10n_nft_wallet_unhide)).performClick();assertFalse(HiddenTokens.isHidden(a,TOKEN));exact(root(prompt.get()),Language.msg(l10n_nft_wallet_unhidden));for(String command:node.get().requests)assertTrue(command,command.startsWith("tokens tokenid:") || command.startsWith("coins ") || command.equals("getaddress"));});
        }
    }
    @Test public void linkConfirmationRetainsExactDestinationAndCancelOnly()throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<DetailsNode> node=new AtomicReference<>();AtomicReference<Dialog> prompt=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));Util.openWebUrl(a,URL);prompt.set(dialog(a));node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(prompt.get(),dialog(a));exact(root(prompt.get()),Language.msg(l10n_nft_wallet_open_question));exact(root(prompt.get()),Language.msg(l10n_nft_wallet_metadata_link,URL));exact(root(prompt.get()),Language.msg(l10n_terminal_editor_open));assertTrue(node.get().requests.isEmpty());});}
            s.onActivity(a->exact(root(prompt.get()),Language.msg(l10n_utxo_cancel)).performClick());settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertTrue(node.get().requests.isEmpty());});
        }
    }
}
