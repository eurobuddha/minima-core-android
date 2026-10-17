package com.eurobuddha.merchshop;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.merchshop.integratedcomms.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Reuses Mail's guarded fixture. Public catalog/order data; no node, seed, signing or payment. */
@RunWith(AndroidJUnit4.class)
public class MallShopLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String KEY="0x"+"12".repeat(64),ADDRESS="0x"+"34".repeat(32),TOKEN="0x"+"56".repeat(32);
 private static final String RAW="Send 中文 Русский Українська $1 {count}",SHOP="Checkout",NAME="Pay",REF="ORD-PUBLIC";
 private DirectNodeApi.Backend backend;private boolean prepared;
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  assertTrue(new ShopStore(context).all().isEmpty());try(MerchDb db=new MerchDb(context)){assertTrue(db.orders().isEmpty());}
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");});
  InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);prepared=true;
 }
 @After public void reset(){if(!prepared)return;
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");DirectNodeApi.install(backend);});
  new ShopStore(context).delete("public-shop");try(MerchDb db=new MerchDb(context)){db.getWritableDatabase().delete("order_chat","ref=?",new String[]{REF});db.getWritableDatabase().delete("orders","ref=?",new String[]{REF});assertTrue(db.orders().isEmpty());}
  context.getSystemService(NotificationManager.class).cancel("pandamonium.mall.shop",3);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
 }
 static Object read(Class<?>c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n,Class<?>[] types,Object...args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n){return call(o,n,new Class<?>[]{});}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(200);}
 static JSONObject obj(Object...kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
 static class FixtureNode extends NodeApi{final List<String> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}@Override public void cmd(String c,Cb cb){requests.add(c);}}
 static FixtureNode freeze(MainActivity a){((NodeApi)get(a,"node")).onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode n=new FixtureNode(a);set(a,"node",n);set(a,"myId",KEY);set(a,"buyerPayaddr",ADDRESS);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return n;}
 static void crypto(MainActivity a){set(a,"crypto",new CryptoProvider(){public String identity(){return KEY;}public String seal(String to,byte[]data){throw new AssertionError("No encryption/payment allowed");}public Opened open(String data){throw new AssertionError("No decryption allowed");}});}
 static ArrayDeque<View> stack(MainActivity a){return (ArrayDeque<View>)get(a,"stack");}
 static void push(MainActivity a,View v){call(a,"push",new Class<?>[]{View.class},v);}
 static List<TextView> texts(View v){List<TextView> result=new ArrayList<>();if(v instanceof TextView)result.add((TextView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)result.addAll(texts(g.getChildAt(i)));}return result;}
 static TextView exact(View v,CharSequence label){for(TextView t:texts(v))if(label.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label: "+label);}
 static List<EditText> inputs(View v){List<EditText> result=new ArrayList<>();for(TextView t:texts(v))if(t instanceof EditText)result.add((EditText)t);return result;}
 static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
 static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
 static String bundle(boolean nft){JSONObject product=obj("id","public-item","name",NAME,"description",RAW,"price","0012.34000","maxUnits",4);if(nft)try{product.put("nftTokenId",TOKEN).put("nftStateIdx",2).put("nftSupply",4).put("nftCollection","Collection");}catch(Exception e){throw new AssertionError(e);}return obj("shopId","public-shop","shopName",SHOP,"vendorPublicId",KEY,"vendorAddress",ADDRESS,"currency","USD","tokenid","0x00","products",new JSONArray().put(product),"shipping",new JSONArray().put(obj("id","digital","label","free","fee","0")).put(obj("id","postal","label","Pay","fee","1.20000"))).toString();}
 static Catalog load(MainActivity a,boolean nft){Catalog c=((ShopStore)get(a,"store")).importBundle(bundle(nft));assertNotNull(c);call(a,"openShop",new Class<?>[]{String.class},"public-shop");return (Catalog)get(a,"catalog");}
 static Map<String,Integer> cart(MainActivity a){return (Map<String,Integer>)get(a,"cart");}
 void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
 @Test public void storefrontRetainsCartNavigationAndLiteralCatalogContent()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<View>storefront=new AtomicReference<>();AtomicReference<Catalog>catalog=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));crypto(a);catalog.set(load(a,false));storefront.set(stack(a).peek());});settle();
   s.onActivity(a->{exact(storefront.get(),"+").performClick();exact(storefront.get(),"+").performClick();});
   byte[] stored=Files.readAllBytes(new java.io.File(context.getFilesDir(),"pandamonium_mall_shops/public-shop.json").toPath());
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(storefront.get(),stack(a).peek());assertSame(catalog.get(),get(a,"catalog"));assertEquals(Integer.valueOf(2),cart(a).get("public-item"));assertEquals("24.68000",call(a,"cartTotal").toString());exact(storefront.get(),SHOP);exact(storefront.get(),NAME);exact(storefront.get(),RAW);exact(storefront.get(),M("cartSummary",2,"24.68000","USD"));exact(storefront.get(),M("checkout"));assertEquals(2,stack(a).size());assertTrue(node.get().requests.isEmpty());});assertArrayEquals(stored,Files.readAllBytes(new java.io.File(context.getFilesDir(),"pandamonium_mall_shops/public-shop.json").toPath()));}
   s.onActivity(a->{View detail=(View)call(a,"buildProductDetail",new Class<?>[]{Catalog.Product.class},catalog.get().products.get(0));push(a,detail);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{exact(stack(a).peek(),NAME);exact(stack(a).peek(),RAW);exact(stack(a).peek(),Language.upper(M("quantity")));exact(stack(a).peek(),M("goCheckout"));assertEquals(3,stack(a).size());assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->{call(a,"pop");assertSame(storefront.get(),stack(a).peek());exact(storefront.get(),M("checkout"));assertEquals(2,cart(a).get("public-item").intValue());});
  }
 }
 @Test public void checkoutAndPaymentConfirmationKeepDraftShippingAndExactTotals()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<View>checkout=new AtomicReference<>();AtomicReference<List<EditText>>fields=new AtomicReference<>();AtomicReference<AlertDialog>kept=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));crypto(a);load(a,false);cart(a).put("public-item",2);call(a,"openCheckout");checkout.set(stack(a).peek());fields.set(inputs(checkout.get()));assertEquals(2,fields.get().size());fields.get().get(0).setText(RAW);fields.get().get(1).setText("Pay");fields.get().get(0).setSelection(5);exact(checkout.get(),"Pay  +1.20000").performClick();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(checkout.get(),stack(a).peek());assertEquals("postal",get(a,"chosenShipping"));assertEquals(RAW,fields.get().get(0).getText().toString());assertEquals("Pay",fields.get().get(1).getText().toString());assertEquals(5,fields.get().get(0).getSelectionStart());assertEquals(M("deliveryHint").toString(),fields.get().get(0).getHint().toString());exact(checkout.get(),M("payAmount","25.88000","USD"));exact(checkout.get(),Language.join(M("subtotal","24.68000","USD"),"\n",M("shippingFee","1.20000","USD"),"\n",M("grandTotal","25.88000","USD")));assertTrue(node.get().requests.isEmpty());});screenshot("mall-shop-checkout-"+lang);}
   s.onActivity(a->{exact(checkout.get(),M("payAmount","25.88000","USD")).performClick();kept.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));exact(dialog(a).getWindow().getDecorView(),M("payTitle","25.88000","USD"));exact(dialog(a).getWindow().getDecorView(),M("payHelp",SHOP,"25.88000","USD"));assertEquals(2,cart(a).get("public-item").intValue());assertTrue(((MerchDb)get(a,"db")).orders().isEmpty());assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertSame(checkout.get(),stack(a).peek());assertEquals(RAW,fields.get().get(0).getText().toString());});
  }
 }
 @Test public void nftDetailsAndCheckoutKeepEditionTokenAndReceivingAddress()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<Catalog.Product>product=new AtomicReference<>();AtomicReference<View>detail=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));crypto(a);Catalog c=load(a,true);product.set(c.products.get(0));detail.set((View)call(a,"buildProductDetail",new Class<?>[]{Catalog.Product.class},product.get()));push(a,detail.get());});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(detail.get(),stack(a).peek());exact(detail.get(),NAME);exact(detail.get(),RAW);exact(detail.get(),"Collection");exact(detail.get(),M("editionOf",2,4));exact(detail.get(),M("buy","0012.34000","USD"));assertEquals(TOKEN,product.get().nftTokenId);assertEquals("0012.34000",product.get().price);assertTrue(node.get().requests.isEmpty());});screenshot("mall-shop-nft-"+lang);}
   s.onActivity(a->exact(detail.get(),M("buy","0012.34000","USD")).performClick());settle();AtomicReference<View>checkout=new AtomicReference<>();s.onActivity(a->{checkout.set(stack(a).peek());assertEquals(1,inputs(checkout.get()).size());inputs(checkout.get()).get(0).setText(RAW);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(checkout.get(),stack(a).peek());exact(checkout.get(),Language.upper(M("walletDelivery")));exact(checkout.get(),M("walletDeliveryHelp"));assertEquals(ADDRESS,get(a,"buyerPayaddr"));assertEquals("digital",get(a,"chosenShipping"));assertEquals(RAW,inputs(checkout.get()).get(0).getText().toString());assertTrue(node.get().requests.isEmpty());});}
  }
 }
 @Test public void orderConversationAndNotificationsPreserveRawStatusMessagesAndSettings()throws Exception{
  MerchMessage m=new MerchMessage();m.ref=REF;m.from=KEY;m.to=KEY;m.product=NAME;m.amount="0012.34000";m.currency="USD";m.tokenid="0x00";m.delivery=RAW;m.shipping="postal";m.date=1577880123000L;
  try(MerchDb db=new MerchDb(context)){assertTrue(db.upsertOrder(m,"buyer",""));db.setStatus(REF,MerchDb.PAID);assertTrue(db.insertChat(REF,true,RAW,"public-chat",m.date));}
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<View>detail=new AtomicReference<>();AtomicReference<EditText>draft=new AtomicReference<>();NotificationManager nm=context.getSystemService(NotificationManager.class);
   s.onActivity(a->{node.set(freeze(a));detail.set((View)call(a,"buildOrderDetail",new Class<?>[]{String.class},REF));push(a,detail.get());draft.set(inputs(detail.get()).get(0));draft.get().setText(RAW);draft.get().setSelection(3);});NotificationChannel initial=nm.getNotificationChannel("merchshop");Object[] settings=settings(initial);
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(detail.get(),stack(a).peek());exact(detail.get(),NAME);exact(detail.get(),M("statusPaid"));exact(detail.get(),"0012.34000 USD");exact(detail.get(),M("vendorSays",RAW));exact(detail.get(),REF);assertEquals(RAW,draft.get().getText().toString());assertEquals(3,draft.get().getSelectionStart());assertEquals(M("messageHint").toString(),draft.get().getHint().toString());MerchDb db=(MerchDb)get(a,"db");assertEquals(MerchDb.PAID,db.order(REF).status);assertEquals("0012.34000",db.order(REF).amount);assertEquals(RAW,db.chat(REF).get(0)[1]);assertEquals(1,db.chat(REF).size());assertTrue(node.get().requests.isEmpty());set(a,"nftDeliveredNotify",false);call(a,"notifyNew",new Class<?>[]{int.class},1);});
    Notification n=notice(nm);assertEquals(M("orderNotice").toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(M("orderNoticeBody").toString(),n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());assertEquals(M("channel").toString(),nm.getNotificationChannel("merchshop").getName().toString());assertArrayEquals(settings,settings(nm.getNotificationChannel("merchshop")));nm.cancel("pandamonium.mall.shop",3);SystemClock.sleep(100);
   }
   s.onActivity(a->{set(a,"nftDeliveredNotify",true);call(a,"notifyNew",new Class<?>[]{int.class},1);assertEquals(false,get(a,"nftDeliveredNotify"));});assertEquals(M("nftNotice").toString(),notice(nm).extras.getCharSequence(Notification.EXTRA_TITLE).toString());
   assertEquals(RAW,ShopMessages.status(RAW));for(String value:new String[]{"PENDING","PAID","CONFIRMED","SHIPPED","DELIVERED","UNDERPAID","WRONG_TOKEN","INQUIRY"})assertTrue(ShopMessages.status(value) instanceof Language.Message);
  }
 }
 static Object[] settings(NotificationChannel c){return new Object[]{c.getImportance(),c.getSound(),c.getAudioAttributes(),c.shouldVibrate(),Arrays.toString(c.getVibrationPattern()),c.canShowBadge(),c.getLockscreenVisibility()};}
 static Notification notice(NotificationManager nm){long until=SystemClock.elapsedRealtime()+4000;do{for(android.service.notification.StatusBarNotification n:nm.getActiveNotifications())if("pandamonium.mall.shop".equals(n.getTag())&&n.getId()==3)return n.getNotification();SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError("Missing Shop notice");}
 @Test public void emptyHomeAndSeedDialogTranslateWithoutReadingOrRetainingASeed(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<View>home=new AtomicReference<>();s.onActivity(a->{node.set(freeze(a));home.set(stack(a).peek());});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(home.get(),stack(a).peek());exact(home.get(),M("empty"));exact(home.get(),M("add"));assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->call(a,"askForSeed"));AtomicReference<AlertDialog>kept=new AtomicReference<>();s.onActivity(a->kept.set(dialog(a)));
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));assertTrue((dialog(a).getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);assertTrue(inputs(dialog(a).getWindow().getDecorView()).get(0).getText().toString().isEmpty());exact(dialog(a).getWindow().getDecorView(),M("setup"));assertEquals(M("seed").toString(),inputs(dialog(a).getWindow().getDecorView()).get(0).getHint().toString());assertNull(get(a,"crypto"));assertTrue(node.get().requests.isEmpty());assertEquals(RAW,ShopMessages.message(RAW));for(String error:new String[]{"Node connection closed.","box seed keypair failed","sign seed keypair failed","HKDF failed","encrypt failed: seal error: sign failed"})assertTrue(ShopMessages.message(error) instanceof Language.Message);assertEquals(M("invalidPayment").toString(),ShopMessages.message("Invalid payment address, token or amount.").toString());});}
   s.onActivity(a->dialog(a).cancel());
  }
 }
 @Test public void generatedInquiryTitlesTranslateWhileStoredAndOrdinaryProductTextStayLiteral()throws Exception{
  MerchMessage m=new MerchMessage();m.type=MerchMessage.INQUIRY;m.ref=REF;m.from=KEY;m.to=KEY;m.shopName=SHOP;m.product="Messages with "+SHOP;
  try(MerchDb db=new MerchDb(context)){assertTrue(db.upsertOrder(m,"buyer",""));}
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();s.onActivity(a->node.set(freeze(a)));
   for(String raw:new String[]{"Messages with "+SHOP,"General enquiry",RAW}){
    AtomicReference<View>page=new AtomicReference<>();s.onActivity(a->{MerchDb db=(MerchDb)get(a,"db");android.content.ContentValues values=new android.content.ContentValues();values.put("product",raw);db.getWritableDatabase().update("orders",values,"ref=?",new String[]{REF});page.set((View)call(a,"buildOrderDetail",new Class<?>[]{String.class},REF));push(a,page.get());inputs(page.get()).get(0).setText(RAW);});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());exact(page.get(),raw.equals("Messages with "+SHOP)?Language.msg(l10n_mall_shop_messages_with_shop,SHOP):raw.equals("General enquiry")?Language.msg(l10n_mall_shop_general_enquiry):RAW);assertEquals(raw,((MerchDb)get(a,"db")).order(REF).product);assertEquals(RAW,inputs(page.get()).get(0).getText().toString());assertTrue(node.get().requests.isEmpty());});}
    s.onActivity(a->call(a,"pop"));
   }
   s.onActivity(a->{MerchDb db=(MerchDb)get(a,"db");db.setStatus(REF,MerchDb.PAID);android.content.ContentValues values=new android.content.ContentValues();values.put("product","Messages with "+SHOP);db.getWritableDatabase().update("orders",values,"ref=?",new String[]{REF});View page=(View)call(a,"buildOrderDetail",new Class<?>[]{String.class},REF);push(a,page);exact(page,"Messages with "+SHOP);assertTrue(node.get().requests.isEmpty());});
  }
 }
 // Resource aliases are generated from the catalog; assertions above cover retained state and literal data.
 static Language.Message M(String key,Object...args){switch(key){
 case "add":return Language.msg(l10n_mall_shop_add,args);
 case "buy":return Language.msg(l10n_mall_shop_buy,args);
 case "cartSummary":return Language.msg(l10n_mall_shop_cart_summary,args);
 case "channel":return Language.msg(l10n_mall_shop_channel,args);
 case "checkout":return Language.msg(l10n_mall_shop_checkout,args);
 case "deliveryHint":return Language.msg(l10n_mall_shop_delivery_hint,args);
 case "editionOf":return Language.msg(l10n_mall_shop_edition_of,args);
 case "empty":return Language.msg(l10n_mall_shop_empty,args);
 case "goCheckout":return Language.msg(l10n_mall_shop_go_checkout,args);
 case "grandTotal":return Language.msg(l10n_mall_shop_grand_total,args);
 case "invalidPayment":return Language.msg(l10n_mall_shop_invalid_payment,args);
 case "messageHint":return Language.msg(l10n_mall_shop_message_hint,args);
 case "nftNotice":return Language.msg(l10n_mall_shop_nft_notice,args);
 case "orderNotice":return Language.msg(l10n_mall_shop_order_notice,args);
 case "orderNoticeBody":return Language.msg(l10n_mall_shop_order_notice_body,args);
 case "payAmount":return Language.msg(l10n_mall_shop_pay_amount,args);
 case "payHelp":return Language.msg(l10n_mall_shop_pay_help,args);
 case "payTitle":return Language.msg(l10n_mall_shop_pay_title,args);
 case "quantity":return Language.msg(l10n_mall_shop_quantity,args);
 case "seed":return Language.msg(l10n_mall_shop_seed,args);
 case "setup":return Language.msg(l10n_mall_shop_setup,args);
 case "shippingFee":return Language.msg(l10n_mall_shop_shipping_fee,args);
 case "statusPaid":return Language.msg(l10n_mall_shop_status_paid,args);
 case "subtotal":return Language.msg(l10n_mall_shop_subtotal,args);
 case "vendorSays":return Language.msg(l10n_mall_shop_vendor_says,args);
 case "walletDelivery":return Language.msg(l10n_mall_shop_wallet_delivery,args);
 case "walletDeliveryHelp":return Language.msg(l10n_mall_shop_wallet_delivery_help,args);
 default:throw new AssertionError(key);}}
}
