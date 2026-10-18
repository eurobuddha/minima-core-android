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
        node=mock(NodeApi.class);db=mock(MerchDb.class);store=mock(ShopStore.class);crypto=mock(CryptoProvider.class);
        set("node",node);set("db",db);set("store",store);set("crypto",crypto);set("myId","public-buyer");
        when(crypto.seal(anyString(),any())).thenReturn("abcd");
        doAnswer(i->{commands.add(i.getArgument(0));replies.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any());
    }
    @After @SuppressWarnings("unchecked") public void close()throws Exception {
        for(AlertDialog dialog:new ArrayList<>((Set<AlertDialog>)get("languageDialogs")))dialog.dismiss();
        ((Handler)get("ui")).removeCallbacksAndMessages(null);Language.select(activity,"en");activity.finish();
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
        verify(db).setMeta("vendoraddr:"+message.ref,ADDRESS);
        Catalog replacement=new Catalog();replacement.vendorAddress=OTHER;replacement.tokenid="0xaa";set("catalog",replacement);
        Language.select(activity,"uk");replies.remove(0).onResult(accepted());assertEquals(2,commands.size());
        assertEquals("send amount:24.790000 address:"+ADDRESS+" tokenid:0x00 state:{\"1\":\"0x"+Hex.to(message.ref.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\"}",commands.get(1));
    }
    MerchDb.Order order(){MerchDb.Order o=new MerchDb.Order();o.ref="ORD-original";o.amount="0012.340000";o.tokenid="0x00";o.currency="Minima";o.shopId="original";return o;}
    void retry(MerchDb.Order o)throws Exception{call("retryPayment",new Class<?>[]{MerchDb.Order.class},o);}
    @Test public void retryUsesSavedAddressAndCallbackKeepsOriginalReference()throws Exception {
        Catalog c=prepare();c.vendorAddress=OTHER;MerchDb.Order o=order();when(db.getMeta("vendoraddr:"+o.ref,"")).thenReturn(ADDRESS);
        retry(o);assertTrue(commands.get(0).startsWith("send amount:0012.340000 address:"+ADDRESS+" tokenid:0x00 "));
        o.ref="ORD-mutated";o.amount="999";o.tokenid="0xff";replies.get(0).onResult(accepted());work.remove(0).run();
        verify(db).setMeta("unpaid:ORD-original","");verify(db,never()).setMeta(eq("unpaid:ORD-mutated"),any());verifyNoInteractions(store);
    }
    @Test public void missingAddressNeverFallsBackToAnOpenOrReimportedShopInAnyLanguage()throws Exception {
        Catalog c=prepare();c.vendorAddress=OTHER;MerchDb.Order o=order();when(store.get(o.shopId)).thenReturn(c);when(db.getMeta("vendoraddr:"+o.ref,"")).thenReturn("");
        for(String language:new String[]{"en","zh-Hans","ru","uk"}){Language.select(activity,language);retry(o);assertEquals(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_mall_shop_retry_unavailable),org.robolectric.shadows.ShadowToast.getTextOfLatestToast());}
        verifyNoInteractions(node);verify(store,never()).get(anyString());assertTrue(work.isEmpty());
    }
    @Test public void nullAddressAlsoCannotDispatchAPayment()throws Exception {
        prepare();retry(order());verifyNoInteractions(node,store);assertTrue(work.isEmpty());
    }
}
