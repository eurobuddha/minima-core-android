package com.eurobuddha.nftwallet;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.app.Dialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.google.android.material.tabs.TabLayout;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public fixtures only. A fake node records requests and never signs, posts or derives keys. */
@RunWith(AndroidJUnit4.class)
public class NftShellLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String ADDRESS="0x"+"12".repeat(32), CHANGE="0x"+"34".repeat(32), COIN="0x"+"56".repeat(32);
    private DirectNodeApi.Backend backend;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
        assertEquals(0,LocalStore.load(context).length());assertNull(DistributeJob.load(context));assertFalse(MintDriver.hasWork(context));assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");Design.set(context,Design.Mode.CURRENT);});
    }
    @After public void reset(){
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");Design.set(context,Design.Mode.CURRENT);DirectNodeApi.install(backend);});
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    static Object read(Class<?> c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static Object get(Object o,String n){return read(o.getClass(),o,n);}
    static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
    static Object call(Object o,String n,Class<?>[] types,Object... args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
    static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(250);}
    static JSONObject object(Object... kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
    static class FixtureNode extends NodeApi {
        final List<String> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}
        @Override public void cmd(String command,Cb cb){requests.add(command);if(command.equals("checkaddress address:"+ADDRESS))cb.onResult(object("status",true,"response",object("valid",true,"relevant",true,"simple",true)));}
    }
    static FixtureNode freeze(MainActivity a){a.node().onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode node=new FixtureNode(a);set(a,"node",node);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return node;}
    static SendView send(MainActivity a){return (SendView)((BaseView[])get(a,"views"))[MainActivity.TAB_SEND];}
    static EditText input(MainActivity a,int id){return a.findViewById(id);}
    static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
    static TextView exact(View root,CharSequence expected){for(TextView t:texts(root))if(expected.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label "+expected);}
    static Set<Dialog> dialogs(MainActivity a){return (Set<Dialog>)get(a,"languageDialogs");}
    static Dialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    static void seedDisplay(MainActivity a){
        Coin coin=new Coin();coin.coinid=COIN;coin.address=ADDRESS;coin.miniaddress=ADDRESS;coin.tokenid="0x00";coin.amount="50";coin.tokenName="Send";coin.sendable=true;
        a.coins().clear();a.coins().add(coin);a.toggleCoin(coin);a.goToTab(MainActivity.TAB_SEND);
        send(a).setFieldValues(new String[]{ADDRESS,"12.340000",CHANGE,"0.001","3","c","0x00"});input(a,R.id.pm_nftwallet_sendAddress).setSelection(5);
    }
    static void assertDraft(MainActivity a){assertArrayEquals(new String[]{ADDRESS,"12.340000",CHANGE,"0.001","3","c","0x00"},send(a).fieldValues());assertEquals(5,input(a,R.id.pm_nftwallet_sendAddress).getSelectionStart());assertEquals(COIN,a.selectedCoins().get(0).coinid);assertEquals(MainActivity.TAB_SEND,a.currentTab());}
    void screenshot(String name)throws Exception{settle();android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o));}finally{b.recycle();}}
    @Test public void sendDraftAndAllTabsSurviveLanguageChanges()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<SendView> original=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));seedDisplay(a);original.set(send(a));node.get().requests.clear();});
            int[] titles={l10n_utxo_balances,l10n_nft_wallet_gallery,l10n_nft_wallet_mint,l10n_utxo_send,l10n_utxo_receive,l10n_utxo_coins_label,l10n_utxo_history};
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),send(a));assertDraft(a);assertTrue(node.get().requests.isEmpty());TabLayout tabs=a.findViewById(R.id.pm_nftwallet_tabs);for(int i=0;i<titles.length;i++)assertEquals(Language.msg(titles[i]).toString(),tabs.getTabAt(i).getText().toString());assertEquals(MainActivity.TAB_SEND,tabs.getSelectedTabPosition());assertEquals(Language.msg(l10n_wallet_settings).toString(),a.findViewById(R.id.pm_nftwallet_settingsBtn).getContentDescription().toString());});}
        }
    }
    @Test public void mintDraftMetadataAndCursorSurviveWithoutMinting()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<MintView> mint=new AtomicReference<>();AtomicReference<EditText> name=new AtomicReference<>();AtomicReference<View> form=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));a.goToTab(MainActivity.TAB_MINT);MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];mint.set(m);Object screen=get(m,"screen");set(m,"screen",Enum.valueOf((Class)screen.getClass(),"TOKEN"));m.refresh();name.set((EditText)get(m,"tName"));name.get().setText("Gallery 设置 Україна");name.get().setSelection(3);((EditText)get(m,"tAmount")).setText("123400000000");((EditText)get(m,"tDesc")).setText("Literal mint draft • не переводить");call(m,"addMetaRow",new Class<?>[]{});LinearLayout row=(LinearLayout)((LinearLayout)get(m,"tMetaRows")).getChildAt(0);((EditText)row.getChildAt(0)).setText("Mint");((EditText)row.getChildAt(1)).setText("Settings");form.set((View)get(m,"tokenForm"));node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];assertSame(mint.get(),m);assertSame(form.get(),get(m,"tokenForm"));assertSame(name.get(),get(m,"tName"));assertEquals("Gallery 设置 Україна",name.get().getText().toString());assertEquals(3,name.get().getSelectionStart());assertEquals("123400000000",((EditText)get(m,"tAmount")).getText().toString());assertEquals("Literal mint draft • не переводить",((EditText)get(m,"tDesc")).getText().toString());LinearLayout row=(LinearLayout)((LinearLayout)get(m,"tMetaRows")).getChildAt(0);assertEquals("Mint",((EditText)row.getChildAt(0)).getText().toString());assertEquals("Settings",((EditText)row.getChildAt(1)).getText().toString());assertEquals(MainActivity.TAB_MINT,a.currentTab());assertTrue(node.get().requests.isEmpty());assertEquals(0,LocalStore.load(a).length());});}
        }
    }
    @Test public void receiveKeepsQrSafetyAddressAndExactClipboard()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<android.graphics.drawable.Drawable> qr=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));a.myAddresses().add(new String[]{ADDRESS,ADDRESS});a.setDefaultAddress(ADDRESS);a.goToTab(MainActivity.TAB_RECEIVE);});settle();
            scenario.onActivity(a->{qr.set(((ImageView)a.findViewById(R.id.pm_nftwallet_rcvQr)).getDrawable());assertNotNull(qr.get());node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{View root=a.getWindow().getDecorView();exact(root,Language.msg(l10n_utxo_copy_address));exact(root,Language.msg(l10n_nft_wallet_next_address));exact(root,Language.msg(l10n_nft_wallet_showing));exact(root,Language.join(Language.msg(l10n_nft_wallet_belongs),"   ·   ",Language.msg(l10n_nft_wallet_safe)));assertEquals(ADDRESS,((TextView)a.findViewById(R.id.pm_nftwallet_rcvAddress)).getText().toString());assertSame(qr.get(),((ImageView)a.findViewById(R.id.pm_nftwallet_rcvQr)).getDrawable());assertTrue(node.get().requests.isEmpty());});screenshot("nft-receive-"+code);}
            scenario.onActivity(a->{a.findViewById(R.id.pm_nftwallet_rcvCopy).performClick();android.content.ClipboardManager cm=(android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(ADDRESS,cm.getPrimaryClip().getItemAt(0).getText().toString());assertTrue(node.get().requests.isEmpty());});
        }
    }
    private void settings(Design.Mode mode)throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,mode));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Dialog> original=new AtomicReference<>();AtomicReference<List<RadioButton>> radios=new AtomicReference<>();AtomicReference<List<Boolean>> checked=new AtomicReference<>();int decimals=Format.decimals();
            scenario.onActivity(a->{node.set(freeze(a));SettingsDialog.show(a);original.set(dialog(a));List<RadioButton> rs=new ArrayList<>();List<Boolean> cs=new ArrayList<>();for(TextView t:texts(original.get().getWindow().getDecorView()))if(t instanceof RadioButton){rs.add((RadioButton)t);cs.add(((RadioButton)t).isChecked());}assertEquals(8,rs.size());radios.set(rs);checked.set(cs);node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),dialog(a));View root=original.get().getWindow().getDecorView();exact(root,Language.msg(l10n_wallet_settings));exact(root,Language.msg(l10n_nft_wallet_decimal_help));exact(root,Language.msg(l10n_nft_wallet_full_precision));for(int d:new int[]{2,4,8})exact(root,Language.msg(l10n_nft_wallet_precision_places,d));exact(root,Language.msg(l10n_nft_wallet_none_hidden));exact(root,Language.join(Language.msg(l10n_explorer_node_retry),"\n",Language.msg(l10n_nft_wallet_last_reply,Language.msg(l10n_nft_wallet_never))));exact(root,Language.msg(l10n_language_close));for(int i=0;i<radios.get().size();i++)assertEquals(checked.get().get(i),radios.get().get(i).isChecked());assertEquals(mode,Design.mode());assertEquals(decimals,Format.decimals());assertTrue(node.get().requests.isEmpty());});screenshot("nft-settings-"+mode.name()+"-"+code);}
            scenario.onActivity(a->exact(original.get().getWindow().getDecorView(),Language.msg(l10n_language_close)).performClick());settle();scenario.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertTrue(node.get().requests.isEmpty());});
        }
    }
    @Test public void darkSettingsKeepControlsAndSelections()throws Exception{settings(Design.Mode.CURRENT);}
    @Test public void lightSettingsKeepControlsAndSelections()throws Exception{settings(Design.Mode.CLEAN_LIGHT);}
    @Test public void sheetAndProgressKeepValuesCallbacksAndLifecycle()throws Exception {
        AtomicReference<Dialog> owned=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<EditText> draft=new AtomicReference<>();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            scenario.onActivity(a->{node.set(freeze(a));Sheet sheet=Sheet.create(a,Language.msg(l10n_wallet_settings)).subtitle(Language.msg(l10n_nft_wallet_decimal_help));EditText e=new EditText(a);e.setText("Untranslated Галерея 12.340000");e.setSelection(4);draft.set(e);sheet.body(e);sheet.action(Language.msg(l10n_language_close),Sheet.Style.SECONDARY,calls::incrementAndGet);owned.set(sheet.show());node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(owned.get(),dialog(a));exact(owned.get().getWindow().getDecorView(),Language.msg(l10n_wallet_settings));exact(owned.get().getWindow().getDecorView(),Language.msg(l10n_nft_wallet_decimal_help));exact(owned.get().getWindow().getDecorView(),Language.msg(l10n_language_close));assertEquals("Untranslated Галерея 12.340000",draft.get().getText().toString());assertEquals(4,draft.get().getSelectionStart());assertEquals(0,calls.get());assertTrue(node.get().requests.isEmpty());});}
            scenario.onActivity(a->exact(owned.get().getWindow().getDecorView(),Language.msg(l10n_language_close)).performClick());settle();scenario.onActivity(a->{assertEquals(1,calls.get());assertTrue(dialogs(a).isEmpty());Sheet.Progress p=Sheet.progress(a,Language.msg(l10n_nft_wallet_mint),Language.msg(l10n_nft_wallet_last_reply,"PUBLIC-FIXTURE"));p.finish();owned.set(dialog(a));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(owned.get(),dialog(a));exact(owned.get().getWindow().getDecorView(),Language.msg(l10n_nft_wallet_last_reply,"PUBLIC-FIXTURE"));exact(owned.get().getWindow().getDecorView(),Language.msg(l10n_language_close));assertEquals(1,calls.get());assertTrue(node.get().requests.isEmpty());});}
        }
        settle();assertFalse(owned.get().isShowing());
    }
}
