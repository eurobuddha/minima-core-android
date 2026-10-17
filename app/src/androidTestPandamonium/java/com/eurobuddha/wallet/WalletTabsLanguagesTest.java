package com.eurobuddha.wallet;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.minima.objects.Address;
import org.minima.objects.Coin;
import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

/** Synthetic public fixtures only. Counters refuse writes, and no transaction is signed or sent. */
@RunWith(AndroidJUnit4.class)
public class WalletTabsLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String[] codes={"zh-Hans","ru","uk","en"};
    private static final Address ADDRESS=new Address("RETURN TRUE");
    private static final Address SECOND=new Address("RETURN FALSE");
    private static final KeyUses READ_ONLY=new KeyUses() {
        public int currentUses(int index) { return 7; }
        public int reserveNextUse(int index) { throw new AssertionError("UI test must never sign"); }
        public void recordExternalUses(int index,int uses) { throw new AssertionError("UI test must not change key uses"); }
        public java.util.Map<Integer,Integer> snapshotAllUses() { return Collections.singletonMap(0,7); }
    };
    @Before public void prepare() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(WalletSession.get(context).vault().exists());
            AppLock.disable(context);Language.select(context,"en");
        });
    }
    private static void set(Object target,String name,Object value) {
        try {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Object get(Object target,String name) {
        try {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... values) {
        try {Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,values);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void invoke(Object target,String name) {invoke(target,name,new Class<?>[0]);}
    private void fixture(MainActivity a) {
        // Override only UI trust reporting. The real session stays closed and cannot authorize signing.
        set(a,"mVault",new SeedVault(context) {
            @Override public synchronized boolean isKeyUsesTrusted(){return true;}
            @Override public synchronized void assertSigningAllowed(){throw new AssertionError("No signing in UI tests");}
        });
        // Public review fixture only: the vault gate and counter writes above still refuse signing.
        set(a,"mUiSession",(java.util.function.BooleanSupplier)()->true);
        set(a,"mKeyUses",READ_ONLY);
        set(a,"mBook",new AddressBook(context,new WalletCore(new MiniData("0x00"),READ_ONLY)) {
            @Override public synchronized Integer keyIndexFor(String address){return ADDRESS.getAddressData().to0xString().equals(address)?0:null;}
            @Override public int derivedCount(){return 2;}
            @Override public synchronized String addrMx(int index){return (index==0?ADDRESS:SECOND).getMinimaAddress();}
            @Override public synchronized String addr0x(int index){return (index==0?ADDRESS:SECOND).getAddressData().to0xString();}
        });
        set(a,"mAddrMx",ADDRESS.getMinimaAddress());
        org.minima.utils.json.JSONArray coins=new org.minima.utils.json.JSONArray();
        for(int i=1;i<=3;i++)coins.add(new Coin(new MiniData("0x0"+i),ADDRESS.getAddressData(),new MiniNumber(i==3?"50":i==2?"10":"5"),Token.TOKENID_MINIMA,true).toJSON());
        set(a,"mLastCoins",coins);
        try {
            TokenBalance balance=TokenBalance.from(new org.json.JSONObject().put("tokenid","0x00").put("confirmed","65").put("sendable","65").put("coins",3));
            a.balances().add(balance);
        }catch(Exception e){throw new AssertionError(e);}
    }
    private static <T extends View> List<T> views(View root,Class<T> type) {
        List<T> out=new ArrayList<>();if(type.isInstance(root))out.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return out;
    }
    private void settle(){android.os.SystemClock.sleep(500);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private void screenshot(String name) throws Exception {
        java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    @Test public void sendSplitAndConsolidationKeepDraftsAndReviewAcrossLanguages() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> original=new AtomicReference<>();AtomicReference<SendView> view=new AtomicReference<>();
            scenario.onActivity(a -> {original.set(a);fixture(a);view.set(new SendView(a));a.setContentView(view.get().getRoot());});
            for(String mode:new String[]{"SEND","SPLIT","CONSOLIDATE"}) {
                List<EditText> inputs=new ArrayList<>();List<String> values=new ArrayList<>();
                scenario.onActivity(a -> {
                    try {
                        Field field=SendView.class.getDeclaredField("mMode");field.setAccessible(true);
                        field.set(view.get(),Enum.valueOf((Class)field.getType(),mode));view.get().refresh();
                        if(mode.equals("SEND"))((EditText)get(view.get(),"mRecipient")).setText(SECOND.getMinimaAddress());
                        if(!mode.equals("CONSOLIDATE"))((EditText)get(view.get(),"mAmount")).setText("12.340000");
                        if(mode.equals("SPLIT"))((EditText)get(view.get(),"mSplitN")).setText("3");
                        ((EditText)get(view.get(),"mBurn")).setText("0.001");
                        inputs.addAll(views(view.get().getRoot(),EditText.class));
                        for(EditText input:inputs){values.add(input.getText().toString());input.setSelection(1);}
                    }catch(Exception e){throw new AssertionError(e);}
                });
                for(String code:codes){scenario.onActivity(a -> Language.select(a,code));settle();scenario.onActivity(a -> {
                    assertSame(original.get(),a);assertEquals(mode,String.valueOf(get(view.get(),"mMode")));assertEquals("0x00",get(view.get(),"mTokenId"));
                    List<EditText> current=views(view.get().getRoot(),EditText.class);assertEquals(inputs.size(),current.size());
                    for(int i=0;i<inputs.size();i++){assertSame(inputs.get(i),current.get(i));assertEquals(values.get(i),current.get(i).getText().toString());assertEquals(1,current.get(i).getSelectionStart());}
                });}
                scenario.onActivity(a -> invoke(view.get(),mode.equals("SEND")?"reviewSend":mode.equals("SPLIT")?"reviewSplit":"reviewConsolidate"));settle();
                for(String code:codes){scenario.onActivity(a -> Language.select(a,code));settle();
                    onView(withText(Language.text(context,l10n_wallet_confirm_transaction))).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withId(android.R.id.message)).inRoot(isDialog()).check((v,error) -> {
                        if(error!=null)throw error;String text=((TextView)v).getText().toString();
                        assertTrue(text,text.contains(Language.text(context,l10n_wallet_irreversible)));
                        assertTrue(text,text.contains(Language.text(context,l10n_wallet_key_usage,0,7,262144)));
                        assertTrue(text,text.contains(Language.text(context,l10n_wallet_review_burn,"0.001")));
                        if(mode.equals("SEND")){assertTrue(text,text.contains(SECOND.getMinimaAddress()));assertTrue(text,text.contains("12.340000"));}
                        if(mode.equals("SPLIT"))assertTrue(text,text.contains(Language.quantity(context,com.eurobuddha.minimacore.i18n.R.plurals.l10n_wallet_review_split_line,3,"12.340000",3)));
                    });screenshot("wallet-review-"+mode.toLowerCase(java.util.Locale.ROOT)+"-"+code);
                }
                pressBack();settle();assertEquals(7,READ_ONLY.currentUses(0));
            }
            scenario.onActivity(a -> {assertFalse(WalletSession.get(a).vault().exists());assertNull(a.factory());assertNull(a.node());});assertNull(MinimaService.minima);
        }
    }
    @Test public void historyDetailsAndFilterChangeLanguageWithoutChangingExports() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            HistoryTx tx=new HistoryTx();tx.txpowid="0xDISPOSABLE";tx.block="1234";tx.timemilli=1577880123000L;tx.counterparty=SECOND.getMinimaAddress();tx.diff.put("0x1234",new BigDecimal("12.340000"));tx.tokenNames.put("0x1234","Literal 中文 Русский Українська");
            AtomicReference<HistoryView> view=new AtomicReference<>();AtomicReference<String> csv=new AtomicReference<>();AtomicReference<String> json=new AtomicReference<>();
            scenario.onActivity(a -> {
                fixture(a);view.set(new HistoryView(a));((List<HistoryTx>)get(view.get(),"mRows")).add(tx);invoke(view.get(),"render");a.setContentView(view.get().getRoot());assertTrue(((LinearLayout)get(view.get(),"chips")).getChildAt(2).performClick());
                csv.set((String)invoke(view.get(),"exportCsv",new Class<?>[]{List.class},Collections.singletonList(tx)));json.set((String)invoke(view.get(),"exportJson",new Class<?>[]{List.class},Collections.singletonList(tx)));
                invoke(view.get(),"showDetail",new Class<?>[]{HistoryTx.class},tx);
            });settle();
            for(String code:codes){scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_history_transaction))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(org.hamcrest.Matchers.containsString(tx.txpowid))).inRoot(isDialog()).check((v,error) -> {
                    if(error!=null)throw error;String text=((TextView)v).getText().toString();assertTrue(text,text.contains(Language.text(context,l10n_history_received)));assertTrue(text,text.contains(tx.tokenNames.get("0x1234")));assertTrue(text,text.contains(tx.counterparty));
                });
                scenario.onActivity(a -> {assertEquals("RECEIVED",get(view.get(),"mFilter"));assertEquals(csv.get(),invoke(view.get(),"exportCsv",new Class<?>[]{List.class},Collections.singletonList(tx)));assertEquals(json.get(),invoke(view.get(),"exportJson",new Class<?>[]{List.class},Collections.singletonList(tx)));});
                screenshot("wallet-history-"+code);
            }
            pressBack();assertTrue(csv.get(),csv.get().contains(",RECEIVED,12.340000,"));assertTrue(json.get(),json.get().contains("12.340000"));
        }
    }
    @Test public void receiveAddressAndQrStayLiteralWhileLabelsSwitch() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<ReceiveView> view=new AtomicReference<>();AtomicReference<android.graphics.drawable.Drawable> qr=new AtomicReference<>();
            scenario.onActivity(a -> {fixture(a);view.set(new ReceiveView(a));a.setContentView(view.get().getRoot());invoke(view.get(),"nextAddress");});settle();
            // Let the existing asynchronous QR renderer complete before testing language-only changes.
            long until=android.os.SystemClock.uptimeMillis()+5000;
            while(qr.get()==null && android.os.SystemClock.uptimeMillis()<until){scenario.onActivity(a -> qr.set(((ImageView)get(view.get(),"qr")).getDrawable()));settle();}
            assertNotNull(qr.get());
            for(String code:codes){scenario.onActivity(a -> Language.select(a,code));settle();scenario.onActivity(a -> {
                assertEquals(1,get(view.get(),"mIndex"));assertEquals(SECOND.getMinimaAddress(),((TextView)get(view.get(),"address")).getText().toString());
                assertSame(qr.get(),((ImageView)get(view.get(),"qr")).getDrawable());
                assertEquals(Language.text(a,l10n_wallet_address_index,2,64),((TextView)get(view.get(),"label")).getText().toString());
            });onView(withText(Language.text(context,l10n_wallet_copy_address))).check(matches(isDisplayed()));}
        }
    }
    @Test public void tokenDetailsKeepAmountsAndMetadataWhileLabelsSwitch() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<BalancesView> view=new AtomicReference<>();
            TokenBalance token=TokenBalance.from(new org.json.JSONObject().put("tokenid","0x1234").put("confirmed","12.340000").put("sendable","12.340000").put("coins",2).put("token",new org.json.JSONObject().put("name","Literal 中文 Русский Українська").put("description","Literal metadata")));
            scenario.onActivity(a -> {fixture(a);a.balances().clear();a.balances().add(token);view.set(new BalancesView(a));a.setContentView(view.get().getRoot());invoke(view.get(),"showTokenDetail",new Class<?>[]{TokenBalance.class},token);});settle();
            for(String code:codes){scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_wallet_confirmed))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("Literal 中文 Русский Українська")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("0x1234")).inRoot(isDialog()).check(matches(isDisplayed()));
                scenario.onActivity(a -> {assertEquals("12.340000",token.confirmed);assertEquals("12.340000",token.sendable);assertSame(token,a.balances().get(0));});
            }pressBack();
        }
    }
}
