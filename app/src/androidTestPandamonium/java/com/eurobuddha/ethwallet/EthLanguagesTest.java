package com.eurobuddha.ethwallet;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.ethwallet.eth.EthWallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Disposable public fixtures. No node, RPC request, real key, signing or broadcasting. */
@RunWith(AndroidJUnit4.class)
public class EthLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String RAW="Literal 中文 Русский Українська {value}";
    private static final String ADDRESS="0x0123456789abcdef0123456789abcdef01234567";
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        context.getSharedPreferences("ethwallet",0).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AppLock.disable(context);Language.select(context,"en");});
    }
    @After public void reset(){
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        context.getSharedPreferences("ethwallet",0).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    private static Object get(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Object o,String name,Object value){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new AssertionError(e);}}
    private static Object call(Object o,String name,Class<?>[] types,Object...args){try{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") private static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
    private static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    private static void freeze(MainActivity a){assertNull(get(a,"node"));assertNull(get(a,"vault"));((ExecutorService)get(a,"io")).shutdownNow();}
    private static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(texts(((ViewGroup)root).getChildAt(i)));return out;}
    private static TextView exact(View root,CharSequence value){for(TextView t:texts(root))if(value.toString().contentEquals(t.getText()))return t;throw new AssertionError("Missing "+value);}
    private static List<EditText> inputs(AlertDialog d){List<EditText> out=new ArrayList<>();for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)out.add((EditText)t);return out;}
    private void settle(){android.os.SystemClock.sleep(350);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
    @Test public void sourcePickerStaysOpenAndDoesNotChooseOrDeriveOnLanguageChange()throws Exception{
        AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);retained.set(dialog(a));});
            for(String code:CODES){
                scenario.onActivity(a->{Language.select(a,code);assertSame(retained.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_eth_wallet_setup));exact(root,Language.msg(l10n_eth_wallet_source_body));assertEquals(Language.msg(l10n_eth_wallet_embedded).toString(),dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertFalse(((EthWallet)get(a,"wallet")).ready());assertNull(get(a,"node"));assertNull(get(a,"vault"));assertFalse(context.getSharedPreferences("ethwallet",0).contains("source"));});
                screenshot("eth-source-"+code);
            }
        }
        assertFalse(retained.get().isShowing());
    }
    @Test public void editableDialogsRetainTextCursorAndDismissalCallbacks()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);dialog(a).dismiss();});settle();
            for(String method:new String[]{"rpcDialog","addTokenDialog","importDialog"}){
                AtomicReference<AlertDialog> retained=new AtomicReference<>();AtomicReference<EditText> input=new AtomicReference<>();
                scenario.onActivity(a->{call(a,method,new Class<?>[]{});retained.set(dialog(a));input.set(inputs(dialog(a)).get(0));input.get().setText(RAW);input.get().setSelection(8);});
                for(String code:CODES){scenario.onActivity(a->{Language.select(a,code);assertSame(retained.get(),dialog(a));assertEquals(RAW,input.get().getText().toString());assertEquals(8,input.get().getSelectionStart());assertNull(get(a,"node"));assertNull(get(a,"vault"));});settle();}
                scenario.onActivity(a->dialog(a).dismiss());settle();scenario.onActivity(a->{assertTrue(dialogs(a).isEmpty());if(!method.equals("importDialog"))assertEquals(false,get(a,"modalOpen"));});
            }
        }
    }
    @Test public void sendDraftAndTokenChoiceRemainLiteralAcrossLanguages()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<AlertDialog> retained=new AtomicReference<>();AtomicReference<EditText> to=new AtomicReference<>(),amount=new AtomicReference<>();
            scenario.onActivity(a->{freeze(a);dialog(a).dismiss();});settle();
            scenario.onActivity(a->{EthWallet wallet=(EthWallet)get(a,"wallet");wallet.importKey("0000000000000000000000000000000000000000000000000000000000000001");call(a,"sendDialog",new Class<?>[]{String.class},"ETH");retained.set(dialog(a));List<EditText> fields=inputs(dialog(a));assertEquals(2,fields.size());to.set(fields.get(0));amount.set(fields.get(1));to.get().setText(ADDRESS);to.get().setSelection(12);amount.get().setText("00012.34000");amount.get().setSelection(5);});
            for(String code:CODES){
                scenario.onActivity(a->{Language.select(a,code);assertSame(retained.get(),dialog(a));assertEquals(ADDRESS,to.get().getText().toString());assertEquals(12,to.get().getSelectionStart());assertEquals("00012.34000",amount.get().getText().toString());assertEquals(5,amount.get().getSelectionStart());assertEquals(Language.msg(l10n_eth_wallet_recipient).toString(),to.get().getHint().toString());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_eth_wallet_token_balance,"ETH","—"));assertNull(get(a,"node"));assertNull(get(a,"vault"));assertFalse(context.getSharedPreferences("ethwallet",0).contains("lastTx"));});
                screenshot("eth-send-"+code);
            }
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();scenario.onActivity(a->{assertEquals(false,get(a,"modalOpen"));assertTrue(dialogs(a).isEmpty());});
        }
    }
    @Test public void confirmationKeepsFullRecipientFeeSelectionAndWarning()throws Exception{
        AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);dialog(a).dismiss();});settle();
            scenario.onActivity(a->{call(a,"showConfirm",new Class<?>[]{String.class,String.class,String.class,boolean.class,String.class,String.class,BigInteger.class,BigInteger.class,BigInteger.class},"ETH",ADDRESS,"0.00001",true,ADDRESS,null,new BigInteger("10000000000000"),BigInteger.valueOf(21000),BigInteger.valueOf(1000000000));retained.set(dialog(a));});
            for(String code:CODES){
                scenario.onActivity(a->{Language.select(a,code);assertSame(retained.get(),dialog(a));View root=dialog(a).getWindow().getDecorView();TextView address=exact(root,ADDRESS);assertTrue(address.getText() instanceof android.text.Spanned);assertEquals(2,((android.text.Spanned)address.getText()).getSpans(0,ADDRESS.length(),android.text.style.ForegroundColorSpan.class).length);exact(root,Language.msg(l10n_eth_wallet_medium));exact(root,Language.msg(l10n_eth_wallet_fee_percent,273));exact(root,"~0.0000273 ETH  ·  1.3 gwei");assertNull(get(a,"node"));assertFalse(((EthWallet)get(a,"wallet")).ready());assertFalse(context.getSharedPreferences("ethwallet",0).contains("lastTx"));});
                screenshot("eth-confirm-"+code);
            }
            scenario.onActivity(a->{View root=dialog(a).getWindow().getDecorView();exact(root,Language.msg(l10n_eth_wallet_high)).performClick();exact(root,"~0.0000357 ETH  ·  1.7 gwei");exact(root,Language.msg(l10n_eth_wallet_fee_percent,357));Language.select(a,"en");exact(root,"~0.0000357 ETH  ·  1.7 gwei");exact(root,Language.msg(l10n_eth_wallet_fee_percent,357));});
        }
        assertFalse(retained.get().isShowing());
    }
    @SuppressWarnings("unchecked") private static void populatedFixture(MainActivity a) {
        EthWallet wallet=(EthWallet)get(a,"wallet");
        wallet.importKey("0000000000000000000000000000000000000000000000000000000000000001");
        set(a,"ethAddr",ADDRESS);set(a,"ethBal","00012.34000");set(a,"modalOpen",false);
        // Cache public placeholder icons before render, so this fixture makes no CDN request.
        try {
            Field f=com.eurobuddha.ethwallet.eth.IconLoader.class.getDeclaredField("MEM");f.setAccessible(true);
            android.util.LruCache<String,android.graphics.Bitmap> cache=(android.util.LruCache<String,android.graphics.Bitmap>)f.get(null);
            android.graphics.Bitmap icon=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888);
            icon.eraseColor(Design.ACCENT);cache.put((String)get(a,"TW_ETH"),icon);
            com.eurobuddha.ethwallet.eth.TokenStore store=(com.eurobuddha.ethwallet.eth.TokenStore)get(a,"tokens");
            for(com.eurobuddha.ethwallet.eth.EthNet.Token token:store.tokens()) {
                cache.put((String)call(a,"tokenIconUrl",new Class<?>[]{String.class},token.address),icon);
                ((Map<String,String>)get(a,"tokenBals")).put(token.symbol,"10.000");
            }
        } catch(Exception e) { throw new AssertionError(e); }
        call(a,"render",new Class<?>[]{});
    }
    private static void assertWrapped(View root,CharSequence label) {
        TextView view=exact(root,label);assertNotNull(view.getLayout());
        for(int line=0;line<view.getLayout().getLineCount();line++)assertEquals(label.toString(),0,view.getLayout().getEllipsisCount(line));
        assertTrue(label.toString(),view.getLayout().getHeight()<=view.getHeight()-view.getCompoundPaddingTop()-view.getCompoundPaddingBottom());
        assertTrue(label.toString(),view.getWidth()<=root.getWidth());
    }
    @Test public void populatedHomeAndMenusRetainBalancesAndTranslate()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{freeze(a);dialog(a).dismiss();});settle();
            scenario.onActivity(EthLanguagesTest::populatedFixture);settle();
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{View root=a.getWindow().getDecorView();
                    exact(root,"00012.34000 ETH");exact(root,"10.000 USDT");
                    for(int id:new int[]{l10n_eth_wallet_send_action,l10n_eth_wallet_receive_action,l10n_eth_wallet_refresh_action,l10n_eth_wallet_add_token_action,l10n_eth_wallet_settings_action}) {
                        TextView action=exact(root,Language.msg(id));assertWrapped(root,Language.msg(id));
                        assertTrue(action.getHeight()>=Design.dp(a,48));
                    }
                    assertWrapped(root,"ETH Wallet");assertWrapped(root,Language.msg(l10n_eth_wallet_mainnet));
                    android.util.TypedValue surface=new android.util.TypedValue();a.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface,surface,true);
                    assertEquals(Design.SURFACE,surface.data);
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(Design.DIM,Design.SURFACE)>=4.5);
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(Design.DIM2,Design.BG)>=4.5);
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(Design.RED,0xFF2C221E)>=4.5);
                    ((android.widget.ScrollView)get(a,"scroller")).fullScroll(View.FOCUS_DOWN);
                });screenshot("eth-home-"+code);
                scenario.onActivity(a->call(a,"settingsDialog",new Class<?>[]{}));settle();
                scenario.onActivity(a->{for(int id:new int[]{l10n_eth_wallet_export_key,l10n_eth_wallet_rpc_endpoint,l10n_eth_wallet_add_token,l10n_eth_wallet_view_address,l10n_eth_wallet_last_tx,l10n_eth_wallet_switch_source})exact(dialog(a).getWindow().getDecorView(),Language.msg(id));});
                screenshot("eth-settings-"+code);
                scenario.onActivity(a->dialog(a).dismiss());settle();
            }
            scenario.onActivity(a->{assertNull(get(a,"node"));assertNull(get(a,"vault"));assertFalse(context.getSharedPreferences("ethwallet",0).contains("source"));});
        }
    }
    @Test public void receiveAndExportWarningRetainDialogAndPublicDataAcrossLanguages()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{freeze(a);dialog(a).dismiss();});settle();
            scenario.onActivity(EthLanguagesTest::populatedFixture);
            for(String method:new String[]{"receiveDialog","exportKeyFlow"}) {
                AtomicReference<AlertDialog> retained=new AtomicReference<>();
                scenario.onActivity(a->{call(a,method,new Class<?>[]{});retained.set(dialog(a));});
                for(String code:CODES) {
                    scenario.onActivity(a->{Language.select(a,code);assertSame(retained.get(),dialog(a));
                        View root=dialog(a).getWindow().getDecorView();
                        if(method.equals("receiveDialog")) { assertTrue(exact(root,ADDRESS).isTextSelectable());exact(root,Language.msg(l10n_eth_wallet_receive_body)); }
                        else {exact(root,Language.msg(l10n_eth_wallet_export_warning));assertNull(get(a,"pendingAuthAction"));}
                        assertNull(get(a,"node"));assertNull(get(a,"vault"));
                    });screenshot("eth-"+(method.equals("receiveDialog")?"receive":"export-warning")+"-"+code);
                }
                scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();
                scenario.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(false,get(a,"modalOpen"));});
            }
        }
    }
    @Test public void protectedFixtureDialogKeepsSecureFlagAndLanguageAcrossBackground()throws Exception {
        AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{freeze(a);dialog(a).dismiss();});settle();
            scenario.onActivity(a->{populatedFixture(a);call(a,"revealKeyDialog",new Class<?>[]{});retained.set(dialog(a));});
            for(String code:CODES) {
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);settle();
                scenario.onActivity(a->{Language.select(a,code);assertSame(retained.get(),dialog(a));
                    assertTrue((dialog(a).getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);
                    exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_eth_wallet_private_warning));
                    assertNull(get(a,"node"));assertNull(get(a,"vault"));assertNull(get(a,"pendingAuthAction"));
                });
            }
            // Deliberately do not capture the secure window or copy even the disposable fixture key.
        }
        assertFalse(retained.get().isShowing());
    }

}
