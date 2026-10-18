package com.eurobuddha.merchshop;

import android.os.Handler;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.merchshop.integratedcomms.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual Shop order/payment callbacks, with public fixtures and no Activity.onCreate or live node.
 * Reuses CheckoutRefreshTest's Robolectric activity fixture and PaymentArgumentsTest's command capture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class OrderContextTest {
    MainActivity activity; NodeApi node; MerchDb db; ShopStore store; CryptoProvider crypto;
    final List<Runnable> work=new ArrayList<>();
    final List<String> commands=new ArrayList<>();
    final List<NodeApi.Cb> replies=new ArrayList<>();
    static final String ADDRESS="0x"+"12".repeat(32),OTHER="0x"+"34".repeat(32);
    @Before public void setup()throws Exception {
        Language.initialize(org.robolectric.RuntimeEnvironment.getApplication(),()->false);
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);Language.select(activity,"en");
        ((ExecutorService)get("io")).shutdownNow();ExecutorService io=mock(ExecutorService.class);set("io",io);
        doAnswer(i->{work.add(i.getArgument(0));return null;}).when(io).execute(any());
        org.robolectric.RuntimeEnvironment.getApplication().deleteDatabase("pandamonium_merchshop.db");
        node=mock(NodeApi.class);db=spy(new MerchDb(org.robolectric.RuntimeEnvironment.getApplication()));store=mock(ShopStore.class);crypto=mock(CryptoProvider.class);
        set("node",node);set("db",db);set("store",store);set("crypto",crypto);set("myId","public-buyer");
        when(crypto.seal(anyString(),any())).thenReturn("abcd");
        doAnswer(i->{commands.add(i.getArgument(0));replies.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any());
    }
    @After @SuppressWarnings("unchecked") public void close()throws Exception {
        for(AlertDialog dialog:new ArrayList<>((Set<AlertDialog>)get("languageDialogs")))dialog.dismiss();
        ((Handler)get("ui")).removeCallbacksAndMessages(null);if(get("paymentJournal")!=null)((PaymentJournal)get("paymentJournal")).close();db.close();org.robolectric.RuntimeEnvironment.getApplication().deleteDatabase("pandamonium_merchshop.db");Language.select(activity,"en");activity.finish();
    }
    Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    @SuppressWarnings("unchecked") Catalog prepare()throws Exception {
        Catalog c=new Catalog();c.vendorPublicId="original-seller";c.vendorAddress=ADDRESS;c.tokenid="0x00";c.currency="Minima";c.shopId="original";c.shopName="Original shop";
        Catalog.Product p=new Catalog.Product();p.id="one";p.name="Parcel";p.price="12.340000";c.products.add(p);
        Catalog.Shipping shipping=new Catalog.Shipping();shipping.id="postal";shipping.fee="0.110000";c.shipping.add(shipping);
        ((Map<String,Integer>)get("cart")).put(p.id,2);set("catalog",c);return c;
    }
    static JSONObject accepted()throws Exception{return new JSONObject().put("status",true).put("response",new JSONObject().put("txpowid","0x"+"56".repeat(32)));}
    @Test public void delayedOrderAndPaymentStayWithOriginalRecipientAndTerms()throws Exception {
        Catalog original=prepare();call("placeOrder",new Class<?>[]{String.class,String.class,String.class},"postal","  delivery 中文  ","note Українська");
        original.vendorAddress=OTHER;original.vendorPublicId="changed-seller";original.tokenid="0xff";original.products.get(0).price="999";
        set("catalog",null);work.remove(0).run();assertEquals(1,commands.size());
        org.mockito.ArgumentCaptor<byte[]> wire=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(crypto).seal(eq("original-seller"),wire.capture());MerchMessage message=MerchMessage.fromWire(wire.getValue());
        assertEquals("24.790000",message.amount);assertEquals("0x00",message.tokenid);assertEquals("  delivery 中文  ",message.delivery);assertEquals("note Українська",message.message);
        assertEquals(ADDRESS,db.getMeta("vendoraddr:"+message.ref,""));
        Catalog replacement=new Catalog();replacement.vendorAddress=OTHER;replacement.tokenid="0xaa";set("catalog",replacement);
        Language.select(activity,"uk");replies.remove(0).onResult(accepted());pump();assertEquals(2,commands.size());
        assertEquals("sendnosign address:"+ADDRESS+" amount:24.790000 tokenid:0x00 split:1 burn:0 state:{\"1\":\"0x"+Hex.to(message.ref.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\"} file:"+commands.get(1).split(" file:")[1],commands.get(1));
    }
    MerchDb.Order order(){MerchDb.Order o=new MerchDb.Order();o.ref="ORD-original";o.amount="0012.340000";o.tokenid="0x00";o.currency="Minima";o.shopId="original";return o;}
    void retry(MerchDb.Order o)throws Exception{call("retryPayment",new Class<?>[]{MerchDb.Order.class},o);}
    void pump(){for(int i=0;i<8;i++){while(!work.isEmpty())work.remove(0).run();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}}
    @Test public void retryUsesSavedAddressAndOriginalReferenceAcrossDelayedWork()throws Exception {
        Catalog c=prepare();c.vendorAddress=OTHER;MerchMessage m=new MerchMessage();m.ref="ORD-original";m.amount="0012.340000";m.tokenid="0x00";
        PaymentJournal journal=new PaymentJournal(db);set("paymentJournal",journal);assertTrue(journal.saveOrder(m,ADDRESS));journal.orderSent(m.ref);
        MerchDb.Order o=db.order(m.ref);retry(o);o.ref="ORD-mutated";o.amount="999";o.tokenid="0xff";pump();
        assertEquals(1,commands.size());assertTrue(commands.get(0).startsWith("sendnosign address:"+ADDRESS+" amount:0012.340000 tokenid:0x00 "));
        assertTrue(commands.get(0).contains(Hex.to("ORD-original".getBytes(java.nio.charset.StandardCharsets.UTF_8))));verifyNoInteractions(store);
    }
    @Test public void missingAddressNeverFallsBackToAnOpenOrReimportedShopInAnyLanguage()throws Exception {
        Catalog c=prepare();c.vendorAddress=OTHER;MerchDb.Order o=order();when(store.get(o.shopId)).thenReturn(c);
        for(String language:new String[]{"en","zh-Hans","ru","uk"}){Language.select(activity,language);retry(o);assertEquals(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_mall_shop_payment_unavailable),org.robolectric.shadows.ShadowToast.getTextOfLatestToast());}
        verifyNoInteractions(node);verify(store,never()).get(anyString());assertTrue(work.isEmpty());
    }
    @Test public void nullAddressAlsoCannotDispatchAPayment()throws Exception {
        prepare();retry(order());verifyNoInteractions(node,store);assertTrue(work.isEmpty());
    }

    @Test @SuppressWarnings("unchecked") public void paymentRefreshPreservesReplyEditableThroughAllLanguages()throws Exception {
        MerchMessage m=new MerchMessage();m.ref="ORD-draft";m.amount="1";m.tokenid="0x00";
        PaymentJournal journal=new PaymentJournal(db);set("paymentJournal",journal);assertTrue(journal.saveOrder(m,ADDRESS));journal.orderSent(m.ref);
        android.view.View page=(android.view.View)call("buildOrderDetail",new Class<?>[]{String.class},m.ref);
        java.util.ArrayDeque<android.view.View> stack=(java.util.ArrayDeque<android.view.View>)get("stack");stack.push(page);
        android.widget.FrameLayout container=new android.widget.FrameLayout(activity);set("container",container);activity.getWindow().setContentView(container);container.addView(page);
        android.widget.EditText box=CheckoutRefreshTest.fields(page).get(0);String raw="  中文 Русский Українська  ";box.setText(raw);box.setSelection(2,6);box.requestFocus();android.text.Editable text=box.getText();Object composing=new Object();text.setSpan(composing,2,4,android.text.Spanned.SPAN_COMPOSING);
        for(String language:new String[]{"en","zh-Hans","ru","uk"}) {
            Language.select(activity,language);call("refreshPaymentOrder",new Class<?>[]{String.class},m.ref);
            assertSame(page,stack.peek());assertSame(box,CheckoutRefreshTest.fields(page).get(0));assertSame(text,box.getText());assertEquals(raw,box.getText().toString());assertEquals(2,box.getSelectionStart());assertEquals(6,box.getSelectionEnd());assertEquals(2,text.getSpanStart(composing));assertTrue(box.hasFocus());
        }
        android.view.View other=new android.view.View(activity);stack.push(other);call("refreshPaymentOrder",new Class<?>[]{String.class},m.ref);assertSame(other,stack.peek());assertEquals(raw,box.getText().toString());verifyNoInteractions(node);
    }
}
