package com.eurobuddha.nftwallet;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.*;
import androidx.appcompat.widget.PopupMenu;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Retained public coin fixtures; no wallet tool or transaction is submitted. */
@RunWith(AndroidJUnit4.class)
public class NftWalletLanguagesTest {
    private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String ADDRESS="0x"+"12".repeat(32), EMPTY="0x"+"34".repeat(32), CONTRACT="0x"+"78".repeat(32);
    @Before public void prepare(){guards.prepare();}
    @After public void reset(){guards.reset();}
    private static WalletView wallet(MainActivity a){return (WalletView)((BaseView[])get(a,"views"))[MainActivity.TAB_COINS];}
    private static Coin coin(String id,String address,String amount,String name){
        Coin c=Coin.from(object("coinid","0x"+id.repeat(32),"address",address,"miniaddress",address,"tokenid","0x00","amount",amount));
        c.tokenName=name;c.sendable=true;return c;
    }
    private static FixtureNode fixture(MainActivity a){
        FixtureNode n=freeze(a);a.coins().clear();a.clearSelection();a.myAddresses().clear();
        Coin first=coin("9a",ADDRESS,"50.0000","Send"),second=coin("bc",ADDRESS,"25.5000","Send");
        a.coins().add(first);a.coins().add(second);a.toggleCoin(first);a.toggleCoin(second);
        a.myAddresses().add(new String[]{ADDRESS,ADDRESS});a.myAddresses().add(new String[]{EMPTY,EMPTY});
        a.goToTab(MainActivity.TAB_COINS);wallet(a).refresh();n.requests.clear();return n;
    }
    private static LinearLayout container(MainActivity a){return a.findViewById(R.id.pm_nftwallet_walletContainer);}
    private static Map<PopupMenu,?> menus(MainActivity a){return (Map<PopupMenu,?>)get(a,"languageMenus");}
    private static PopupMenu menu(MainActivity a){assertEquals(1,menus(a).size());return menus(a).keySet().iterator().next();}
    private static void assertSelectedTabVisible(MainActivity a){
        com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_nftwallet_tabs);
        View selected=((android.view.ViewGroup)tabs.getChildAt(0)).getChildAt(tabs.getSelectedTabPosition());
        android.graphics.Rect visible=new android.graphics.Rect();assertTrue(selected.getGlobalVisibleRect(visible));
        assertEquals("Selected tab must fit after translation",selected.getWidth(),visible.width());
    }
    @Test public void selectionCollapsedCardAndMenuSurviveAllLanguages()throws Exception{
        AtomicReference<MainActivity> host=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<PopupMenu> original=new AtomicReference<>();AtomicReference<View> card=new AtomicReference<>();
            s.onActivity(a->{host.set(a);node.set(fixture(a));((LinearLayout)container(a).getChildAt(0)).getChildAt(0).performClick();card.set(container(a).getChildAt(0));a.findViewById(R.id.pm_nftwallet_toolsBtn).performClick();original.set(menu(a));});
            int[] labels={l10n_utxo_split_selected,l10n_utxo_consolidate,l10n_utxo_distribute_selected,l10n_utxo_untrack_selected};
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                for(int id:new int[]{R.id.pm_nftwallet_toolsBtn,R.id.pm_nftwallet_clearBtn,R.id.pm_nftwallet_sendBtn}){
                    TextView b=a.findViewById(id);assertEquals("Action wraps: "+b.getText(),1,b.getLineCount());assertTrue(b.getHeight()>=48*a.getResources().getDisplayMetrics().density);
                }
                assertSelectedTabVisible(a);assertSame(original.get(),menu(a));for(int i=0;i<4;i++)assertEquals(Language.msg(labels[i]).toString(),menu(a).getMenu().getItem(i).getTitle().toString());
                assertEquals(2,a.selectedCoins().size());exact(wallet(a).getRoot(),Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_selected_count,2));exact(wallet(a).getRoot(),"75.5 Send");
                assertSame(card.get(),container(a).getChildAt(0));assertEquals(1,((LinearLayout)card.get()).getChildCount());assertTrue(((Set<?>)get(wallet(a),"collapsed")).contains(ADDRESS));assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("nft-wallet-menu-"+code);}
            s.onActivity(a->{menu(a).dismiss();((LinearLayout)card.get()).getChildAt(0).performClick();exact(wallet(a).getRoot(),"SEND");
                LinearLayout row=(LinearLayout)((LinearLayout)container(a).getChildAt(0)).getChildAt(0);row.getChildAt(2).performClick();
                ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(ADDRESS,cm.getPrimaryClip().getItemAt(0).getText().toString());
                a.findViewById(R.id.pm_nftwallet_clearBtn).performClick();assertTrue(a.selectedCoins().isEmpty());assertFalse(a.findViewById(R.id.pm_nftwallet_sendBtn).isEnabled());exact(wallet(a).getRoot(),Language.msg(l10n_utxo_pick_coins));assertTrue(node.get().requests.isEmpty());
                a.findViewById(R.id.pm_nftwallet_toolsBtn).performClick();assertEquals(1,menus(a).size());
            });
        }
        settle();assertTrue(menus(host.get()).isEmpty());
    }
    @Test public void pendingWatchNamesAndCoinDetailsKeepTheirMeaning()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<LinearLayout> firstCard=new AtomicReference<>();
            s.onActivity(a->{node.set(fixture(a));a.clearSelection();
                Coin pending=coin("de",ADDRESS,"0.125000","Token"),watch=coin("ef",CONTRACT,"3.5000","Token");pending.confirmed=false;watch.sendable=false;watch.tokenNameProvided=false;
                a.coins().add(pending);a.coins().add(watch);wallet(a).refresh();firstCard.set((LinearLayout)container(a).getChildAt(0));node.get().requests.clear();});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSelectedTabVisible(a);View root=wallet(a).getRoot();assertSame(firstCard.get(),container(a).getChildAt(0));exact(root,Language.msg(l10n_utxo_pending_caps));exact(root,Language.msg(l10n_utxo_watch));exact(root,Language.msg(l10n_utxo_contract));exact(root,Language.msg(l10n_utxo_empty));
                exact(root,"TOKEN");exact(root,Language.upper(Language.msg(l10n_wallet_token)));exact(root,"0.125");exact(root,"3.5");
                firstCard.get().getChildAt(4).performClick();((LinearLayout)container(a).getChildAt(1)).getChildAt(2).performClick();assertTrue(a.selectedCoins().isEmpty());assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("nft-wallet-coins-"+code);}
            AtomicReference<Dialog> details=new AtomicReference<>();
            s.onActivity(a->{assertTrue(firstCard.get().getChildAt(2).performLongClick());details.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(details.get(),dialog(a));exact(details.get().getWindow().getDecorView(),"0x"+"9a".repeat(32));assertTrue(a.selectedCoins().isEmpty());assertTrue(node.get().requests.isEmpty());});}
            s.onActivity(a->exact(details.get().getWindow().getDecorView(),Language.msg(l10n_language_close)).performClick());settle();s.onActivity(a->assertTrue(dialogs(a).isEmpty()));
        }
    }

    @Test public void manualTabScrollSurvivesArrowChangesAndLayout() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle();
            scenario.onActivity(a->{
                freeze(a);a.goToTab(MainActivity.TAB_BALANCES);
                com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_nftwallet_tabs);
                LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)tabs.getLayoutParams();
                lp.width=(int)(180*a.getResources().getDisplayMetrics().density);lp.weight=0;tabs.setLayoutParams(lp);
            });
            settle();android.os.SystemClock.sleep(1300);
            scenario.onActivity(a->{
                com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_nftwallet_tabs);
                assertTrue(tabs.getChildAt(0).getWidth()>tabs.getWidth());
                tabs.smoothScrollTo(tabs.getChildAt(0).getWidth()-tabs.getWidth(),0);
            });
            settle();android.os.SystemClock.sleep(500);
            scenario.onActivity(a->{a.findViewById(R.id.pm_nftwallet_tabs).requestLayout();});
            settle();
            scenario.onActivity(a->{
                com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_nftwallet_tabs);
                assertEquals(MainActivity.TAB_BALANCES,a.currentTab());
                assertTrue("Manual scroll must not snap back to the selected tab",tabs.getScrollX()>0);
                View history=((android.view.ViewGroup)tabs.getChildAt(0)).getChildAt(MainActivity.TAB_HISTORY);
                android.graphics.Rect visible=new android.graphics.Rect();
                assertTrue("Last tab must remain reachable",history.getGlobalVisibleRect(visible));
                assertEquals(history.getWidth(),visible.width());history.performClick();
                assertEquals(MainActivity.TAB_HISTORY,a.currentTab());
            });
        }
    }
}
