package com.eurobuddha.utxo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
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
public class UtxoLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String ADDRESS="0x"+"12".repeat(32), CHANGE="0x"+"34".repeat(32), COIN="0x"+"56".repeat(32);
    private DirectNodeApi.Backend backend;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
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
        @Override public void cmd(String command,Cb cb){requests.add(command);if(command.equals("checkaddress address:"+ADDRESS))cb.onResult(object("status",true,"response",object("valid",true)));}
    }
    static FixtureNode freeze(MainActivity a){a.node().onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode node=new FixtureNode(a);set(a,"node",node);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return node;}
    static SendView send(MainActivity a){return (SendView)((BaseView[])get(a,"views"))[MainActivity.TAB_SEND];}
    static EditText input(MainActivity a,int id){return a.findViewById(id);}
    static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
    static TextView exact(View root,CharSequence expected){for(TextView t:texts(root))if(expected.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label "+expected);}
    static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
    static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    static void seedDisplay(MainActivity a){
        Coin coin=new Coin();coin.coinid=COIN;coin.address=ADDRESS;coin.miniaddress=ADDRESS;coin.tokenid="0x00";coin.amount="50";coin.tokenName="Send";coin.sendable=true;
        a.coins().clear();a.coins().add(coin);a.toggleCoin(coin);a.goToTab(MainActivity.TAB_SEND);
        send(a).setFieldValues(new String[]{ADDRESS,"12.340000",CHANGE,"0.001"});input(a,R.id.pm_utxo_sendAddress).setSelection(5);
    }
    static void assertDraft(MainActivity a){assertArrayEquals(new String[]{ADDRESS,"12.340000",CHANGE,"0.001"},send(a).fieldValues());assertEquals(5,input(a,R.id.pm_utxo_sendAddress).getSelectionStart());assertEquals(COIN,a.selectedCoins().get(0).coinid);assertEquals(MainActivity.TAB_SEND,a.currentTab());}
    void screenshot(String name)throws Exception{settle();android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o));}finally{b.recycle();}}
    @Test public void sendFormAndTabsRetainDraftSelectionAndCursor()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<SendView> original=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));seedDisplay(a);original.set(send(a));node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),send(a));assertDraft(a);exact(send(a).getRoot(),Language.msg(l10n_utxo_from_selected));exact(send(a).getRoot(),Language.msg(l10n_utxo_preview));assertEquals(Language.msg(l10n_utxo_address_hint).toString(),input(a,R.id.pm_utxo_sendAddress).getHint().toString());assertTrue(node.get().requests.isEmpty());TabLayout tabs=a.findViewById(R.id.pm_utxo_tabs);assertEquals(Language.msg(l10n_utxo_send).toString(),tabs.getTabAt(3).getText().toString());assertEquals(3,tabs.getSelectedTabPosition());});screenshot("utxo-send-"+code);}
        }
    }
    private void confirmation(Design.Mode mode)throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Design.set(context,mode));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> original=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));seedDisplay(a);node.get().requests.clear();a.findViewById(R.id.pm_utxo_previewBtn).performClick();original.set(dialog(a));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),dialog(a));assertDraft(a);View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_utxo_confirm));exact(root,ADDRESS);exact(root,CHANGE);exact(root,COIN);exact(root,"12.34 Send");exact(root,"37.659 Send");exact(root,"0.001 MINIMA");TextView warning=exact(root,Language.join(Language.msg(l10n_utxo_change),"  ",Language.msg(l10n_utxo_verify)));assertEquals(Design.red(),warning.getCurrentTextColor());assertEquals(Language.msg(l10n_utxo_sign_post).toString(),dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(Collections.singletonList("checkaddress address:"+ADDRESS),node.get().requests);});screenshot("utxo-confirm-"+mode.name()+"-"+code);}
            scenario.onActivity(a->{dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertDraft(a);assertEquals(Collections.singletonList("checkaddress address:"+ADDRESS),node.get().requests);});
        }
    }
    @Test public void darkConfirmationKeepsValuesAndNeverPosts()throws Exception{confirmation(Design.Mode.CURRENT);}
    @Test public void originalDarkConfirmationKeepsValuesAndNeverPosts()throws Exception{confirmation(Design.Mode.ORIGINAL_DARK);}
    @Test public void lightConfirmationKeepsValuesAndNeverPosts()throws Exception{confirmation(Design.Mode.ORIGINAL_LIGHT);}
    @Test public void validationAndProgressRebindWithoutRequests()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();scenario.onActivity(a->{node.set(freeze(a));seedDisplay(a);input(a,R.id.pm_utxo_sendAmount).setText("80");a.findViewById(R.id.pm_utxo_previewBtn).performClick();node.get().requests.clear();call(send(a),"status",new Class<?>[]{CharSequence.class,boolean.class},UtxoMessages.message("Adding inputs (2/15)…"),true);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{exact(send(a).getRoot(),Language.msg(l10n_utxo_max_amount,"49.999"));exact(send(a).getRoot(),Language.msg(l10n_utxo_adding_inputs,"2","15"));assertEquals("80",input(a,R.id.pm_utxo_sendAmount).getText().toString());assertTrue(node.get().requests.isEmpty());assertTrue(dialogs(a).isEmpty());});}
        }
    }
    @Test public void receiveKeepsAddressAndQrAcrossLanguages()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<android.graphics.drawable.Drawable> qr=new AtomicReference<>();scenario.onActivity(a->{node.set(freeze(a));a.setDefaultAddress(ADDRESS);a.goToTab(MainActivity.TAB_RECEIVE);});settle();
            scenario.onActivity(a->{ImageView image=a.findViewById(R.id.pm_utxo_rcvQr);assertNotNull(image.getDrawable());qr.set(image.getDrawable());node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{exact(a.getWindow().getDecorView(),Language.msg(l10n_utxo_copy_address));assertEquals(ADDRESS,((TextView)a.findViewById(R.id.pm_utxo_rcvAddress)).getText().toString());assertSame(qr.get(),((ImageView)a.findViewById(R.id.pm_utxo_rcvQr)).getDrawable());assertTrue(node.get().requests.isEmpty());});screenshot("utxo-receive-"+code);}
        }
    }
}
