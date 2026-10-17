package com.eurobuddha.utxo;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
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
import static com.eurobuddha.utxo.UtxoLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public fixtures, retained forms and actual menu actions. Every financial dialog stops at Cancel. */
@RunWith(AndroidJUnit4.class)
public class UtxoWalletLanguagesTest {
    private final UtxoLanguagesTest guards=new UtxoLanguagesTest();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String ADDRESS="0x"+"12".repeat(32), TOKEN="0x"+"78".repeat(32);
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    private static WalletView wallet(MainActivity a){return (WalletView)((BaseView[])get(a,"views"))[MainActivity.TAB_WALLET];}
    private static BalancesView balances(MainActivity a){return (BalancesView)((BaseView[])get(a,"views"))[MainActivity.TAB_BALANCES];}
    private static FixtureNode fixture(MainActivity a){
        FixtureNode n=freeze(a);seedDisplay(a);
        Coin other=Coin.from(object("coinid","0x"+"9a".repeat(32),"address",ADDRESS,"tokenid","0x00","amount","25.5000"));
        other.tokenName="Send";other.sendable=true;a.coins().add(other);a.toggleCoin(other);
        a.myAddresses().add(new String[]{ADDRESS,ADDRESS});a.goToTab(MainActivity.TAB_WALLET);wallet(a).refresh();n.requests.clear();return n;
    }
    private static Map<PopupMenu,?> menus(MainActivity a){return (Map<PopupMenu,?>)get(a,"languageMenus");}
    private static PopupMenu menu(MainActivity a){assertEquals(1,menus(a).size());return menus(a).keySet().iterator().next();}
    private static void tool(MainActivity a,int id){a.findViewById(R.id.pm_utxo_toolsBtn).performClick();PopupMenu m=menu(a);assertTrue(m.getMenu().performIdentifierAction(id,0));m.dismiss();}
    private static List<EditText> fields(AlertDialog d){List<EditText> out=new ArrayList<>();for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)out.add((EditText)t);return out;}
    private static void cancel(MainActivity a){dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();}
    @Test public void walletSelectionCollapsedCardAndOpenMenuSurviveLanguages()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<PopupMenu> original=new AtomicReference<>();AtomicReference<View> card=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));LinearLayout container=a.findViewById(R.id.pm_utxo_walletContainer);((LinearLayout)container.getChildAt(0)).getChildAt(0).performClick();card.set(container.getChildAt(0));a.findViewById(R.id.pm_utxo_toolsBtn).performClick();original.set(menu(a));});
            int[] labels={l10n_utxo_split_selected,l10n_utxo_consolidate,l10n_utxo_distribute_selected,l10n_utxo_untrack_selected};
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();guards.screenshot("utxo-wallet-layout-"+code);s.onActivity(a->{
                for(int id:new int[]{R.id.pm_utxo_toolsBtn,R.id.pm_utxo_clearBtn,R.id.pm_utxo_sendBtn}){TextView button=a.findViewById(id);assertEquals("Action wraps: "+button.getText()+" width="+button.getWidth()+" textSize="+button.getTextSize()+" padding="+button.getPaddingLeft()+"/"+button.getPaddingRight(),1,button.getLineCount());}
                assertSame(original.get(),menu(a));for(int i=0;i<4;i++)assertEquals(Language.msg(labels[i]).toString(),menu(a).getMenu().getItem(i).getTitle().toString());
                assertEquals(2,a.selectedCoins().size());exact(wallet(a).getRoot(),Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_selected_count,2));exact(wallet(a).getRoot(),"75.5 Send");
                assertSame(card.get(),((LinearLayout)a.findViewById(R.id.pm_utxo_walletContainer)).getChildAt(0));assertEquals(1,((LinearLayout)card.get()).getChildCount());
                assertTrue(((Set<?>)get(wallet(a),"collapsed")).contains(ADDRESS));assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("utxo-wallet-menu-"+code);}
            s.onActivity(a->{menu(a).dismiss();((LinearLayout)card.get()).getChildAt(0).performClick();exact(wallet(a).getRoot(),"SEND");assertEquals(2,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void splitAndDistributeKeepInputValuesAndCursor()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();s.onActivity(a->node.set(fixture(a)));
            for(int toolId:new int[]{1,3}){
                AtomicReference<AlertDialog> original=new AtomicReference<>();AtomicReference<List<EditText>> inputs=new AtomicReference<>();
                s.onActivity(a->{tool(a,toolId);original.set(dialog(a));inputs.set(fields(dialog(a)));inputs.get().get(0).setText("12");inputs.get().get(0).setSelection(1);if(toolId==3){inputs.get().get(1).setText("0.125000");inputs.get().get(1).setSelection(4);}});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                    assertSame(original.get(),dialog(a));assertSame(inputs.get().get(0),fields(dialog(a)).get(0));
                    assertEquals("12",inputs.get().get(0).getText().toString());assertEquals(1,inputs.get().get(0).getSelectionStart());
                    assertEquals(Language.msg(toolId==1?l10n_utxo_coin_number:l10n_utxo_address_number).toString(),inputs.get().get(0).getHint().toString());
                    if(toolId==3){assertEquals("0.125000",inputs.get().get(1).getText().toString());assertEquals(4,inputs.get().get(1).getSelectionStart());assertEquals(Language.msg(l10n_utxo_amount_each).toString(),inputs.get().get(1).getHint().toString());}
                    exact(dialog(a).getWindow().getDecorView(),Language.msg(toolId==1?l10n_utxo_split_title:l10n_utxo_distribute_title));
                    assertEquals(Language.msg(l10n_utxo_cancel).toString(),dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());assertTrue(node.get().requests.isEmpty());
                });guards.screenshot("utxo-tool-"+toolId+"-"+code);}
                s.onActivity(a->{cancel(a);assertEquals(2,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());});settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
            }
        }
    }
    @Test public void consolidateAndUntrackRetainSelectionAndNeverRunCommands()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();s.onActivity(a->node.set(fixture(a)));
            for(int toolId:new int[]{2,4}){
                AtomicReference<AlertDialog> original=new AtomicReference<>();s.onActivity(a->{tool(a,toolId);original.set(dialog(a));});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(original.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();
                    if(toolId==2)exact(root,Language.msg(l10n_utxo_merge_prompt,2,"Send"));else exact(root,Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_untrack_title,2));
                    assertEquals(2,a.selectedCoins().size());assertTrue(node.get().requests.isEmpty());});}
                s.onActivity(UtxoWalletLanguagesTest::cancel);settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
            }
        }
    }
    @Test public void balanceMetadataAndMissingNameStayDistinctInLightDialog()throws Exception{
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(InstrumentationRegistry.getInstrumentation().getTargetContext(),Design.Mode.ORIGINAL_LIGHT));
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> original=new AtomicReference<>();
            s.onActivity(a->{node.set(freeze(a));a.balances().clear();
                a.balances().add(TokenBalance.from(object("tokenid",TOKEN,"token",object("name",object("name","Send","ticker","TOKEN","description","Token 中文 Русский Українська {amount} $1"),"decimals","8"),"confirmed","50.750000","sendable","25.500000","unconfirmed","0.125000","total","1000","coins",2)));
                a.balances().add(TokenBalance.from(object("tokenid","0x"+"bc".repeat(32),"token",object("ticker","MISSING"),"confirmed","1","sendable","1","total","100","coins",1)));
                a.goToTab(MainActivity.TAB_BALANCES);balances(a).refresh();node.get().requests.clear();
            });
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{exact(balances(a).getRoot(),Language.msg(l10n_wallet_token));exact(balances(a).getRoot(),"Send");exact(balances(a).getRoot(),"TOKEN");exact(balances(a).getRoot(),"25.5");assertTrue(node.get().requests.isEmpty());});guards.screenshot("utxo-balances-"+code);}
            s.onActivity(a->{((LinearLayout)a.findViewById(R.id.pm_utxo_balancesContainer)).getChildAt(0).performClick();original.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(original.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();exact(root,"Send");exact(root,TOKEN);exact(root,"Token 中文 Русский Українська {amount} $1");exact(root,"50.75");exact(root,"25.5");exact(root,"0.125");exact(root,Language.msg(l10n_utxo_sendable));exact(root,Language.msg(l10n_utxo_token_id));assertTrue(node.get().requests.isEmpty());});guards.screenshot("utxo-token-detail-"+code);}
            s.onActivity(a->{cancel(a);assertTrue(node.get().requests.isEmpty());});settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
        }
    }
}
