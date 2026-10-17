package com.eurobuddha.merchinbox;
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
import com.eurobuddha.merchinbox.integratedcomms.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Reuses Mail's guarded fixture. Public order data; no node, seed, signing or payment. */
@RunWith(AndroidJUnit4.class)
public class MallInboxLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String KEY="0x"+"12".repeat(64),ADDRESS="0x"+"34".repeat(32),TOKEN="0x"+"56".repeat(32);
 private static final String RAW="Send 中文 Русский Українська $1 {count}",SHOP="Checkout",NAME="Pay",REF="ORD-PUBLIC";
 private DirectNodeApi.Backend backend;private boolean prepared;
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  try(MerchDb db=new MerchDb(context)){assertTrue(db.orders().isEmpty());}
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");});
  InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);prepared=true;
 }
 @After public void reset(){if(!prepared)return;
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");DirectNodeApi.install(backend);});
  try(MerchDb db=new MerchDb(context)){db.getWritableDatabase().delete("order_chat","ref=?",new String[]{REF});db.getWritableDatabase().delete("orders","ref=?",new String[]{REF});db.getWritableDatabase().delete("meta","k=?",new String[]{"autodeliver"});assertTrue(db.orders().isEmpty());}
  context.getSystemService(NotificationManager.class).cancel("pandamonium.mall.inbox",2);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
 }
 static Object read(Class<?>c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n,Class<?>[] types,Object...args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n){return call(o,n,new Class<?>[]{});}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(200);}
 static JSONObject obj(Object...kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
 static class FixtureNode extends NodeApi{final List<String> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}@Override public void cmd(String c,Cb cb){requests.add(c);}}
 static FixtureNode freeze(MainActivity a){((NodeApi)get(a,"node")).onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode n=new FixtureNode(a);set(a,"node",n);set(a,"myId",KEY);set(a,"vendorAddr",ADDRESS);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return n;}
 static void crypto(MainActivity a){set(a,"crypto",new CryptoProvider(){public String identity(){return KEY;}public String seal(String to,byte[]data){throw new AssertionError("No encryption/payment allowed");}public Opened open(String data){throw new AssertionError("No decryption allowed");}});}
 static ArrayDeque<View> stack(MainActivity a){return (ArrayDeque<View>)get(a,"stack");}
 static void push(MainActivity a,View v){call(a,"push",new Class<?>[]{View.class},v);}
 static List<TextView> texts(View v){List<TextView> result=new ArrayList<>();if(v instanceof TextView)result.add((TextView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)result.addAll(texts(g.getChildAt(i)));}return result;}
 static TextView exact(View v,CharSequence label){for(TextView t:texts(v))if(label.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label: "+label);}
 static List<EditText> inputs(View v){List<EditText> result=new ArrayList<>();for(TextView t:texts(v))if(t instanceof EditText)result.add((EditText)t);return result;}
 static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
 static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}

 void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
 void insert(boolean nft){MerchMessage m=new MerchMessage();m.ref=REF;m.from=KEY;m.to=KEY;m.product=NAME;m.shopName=SHOP;m.amount="0012.34000";m.currency="USD";m.tokenid="0x00";m.buyerPayaddr=ADDRESS;m.shipping="digital";m.delivery=RAW;m.message=RAW;m.date=1577880123000L;JSONObject item=obj("product","Send","size","PAID","quantity",2,"lineTotal","0012.34000");if(nft)try{item.put("nftTokenId",TOKEN).put("nftStateIdx",2).put("nftBundle",0);}catch(Exception e){throw new AssertionError(e);}m.items=new JSONArray().put(item).toString();try(MerchDb db=new MerchDb(context)){assertTrue(db.upsertOrder(m,"seller",""));assertEquals(MerchDb.PAID,db.recordPayment(REF,"0012.34000","0x00"));assertTrue(db.insertChat(REF,true,RAW,"public-inbox-chat",m.date));}}
 static Object[] settings(NotificationChannel c){return new Object[]{c.getImportance(),c.getSound(),c.getAudioAttributes(),c.shouldVibrate(),Arrays.toString(c.getVibrationPattern()),c.canShowBadge(),c.getLockscreenVisibility()};}
 static Notification notice(NotificationManager nm){long until=SystemClock.elapsedRealtime()+4000;do{for(android.service.notification.StatusBarNotification n:nm.getActiveNotifications())if("pandamonium.mall.inbox".equals(n.getTag())&&n.getId()==2)return n.getNotification();SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError("Missing Inbox notice");}
 @Test public void orderReplyAndNftConfirmationKeepLiteralDataAndDoNotSend()throws Exception{
  insert(true);String original;try(MerchDb db=new MerchDb(context)){original=db.order(REF).items;}
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<View>page=new AtomicReference<>();AtomicReference<EditText>draft=new AtomicReference<>();AtomicReference<AlertDialog>kept=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));crypto(a);page.set((View)call(a,"buildOrderDetail",new Class<?>[]{String.class},REF));push(a,page.get());draft.set(inputs(page.get()).get(0));draft.get().setText(RAW);draft.get().setSelection(4);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());assertEquals(RAW,draft.get().getText().toString());assertEquals(4,draft.get().getSelectionStart());assertEquals(M("reply").toString(),draft.get().getHint().toString());exact(page.get(),NAME);exact(page.get(),SHOP);exact(page.get(),M("buyerSays",RAW));exact(page.get(),M("paidAmount","0012.34000","USD"));exact(page.get(),M("digital"));exact(page.get(),M("sendBuyer","\"Send\""));MerchDb db=(MerchDb)get(a,"db");assertEquals(original,db.order(REF).items);assertEquals("PAID",db.order(REF).status);assertEquals("digital",db.order(REF).shipping);assertEquals(RAW,db.chat(REF).get(0)[1]);assertTrue(node.get().requests.isEmpty());});screenshot("mall-inbox-order-"+lang);}
   s.onActivity(a->{exact(page.get(),M("sendBuyer","\"Send\"" )).performClick();kept.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));exact(dialog(a).getWindow().getDecorView(),M("sendNft"));exact(dialog(a).getWindow().getDecorView(),M("sendHelp","\"Send\"",call(a,"shortId",new Class<?>[]{String.class},TOKEN),call(a,"shortId",new Class<?>[]{String.class},ADDRESS)));assertTrue(node.get().requests.isEmpty());assertTrue(((Set<?>)get(a,"nftSending")).isEmpty());});}
   s.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(RAW,draft.get().getText().toString());});
  }
 }
 @Test public void publicVendorCardAndSeedDialogRetainIdentityAndSecureWindow()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<AlertDialog>kept=new AtomicReference<>();s.onActivity(a->{node.set(freeze(a));assertEquals("",get(a,"shopName"));exact(stack(a).peek(),M("orders"));call(a,"showIdentityCard");kept.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));exact(dialog(a).getWindow().getDecorView(),M("vendorCard"));exact(dialog(a).getWindow().getDecorView(),M("vendorHelp"));exact(dialog(a).getWindow().getDecorView(),KEY+"|"+ADDRESS);assertEquals(KEY+"|"+ADDRESS,call(a,"vendorCard"));assertTrue(node.get().requests.isEmpty());});screenshot("mall-inbox-card-"+lang);}
   s.onActivity(a->{dialog(a).dismiss();call(a,"askForSeed");kept.set(dialog(a));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));assertTrue((dialog(a).getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);exact(dialog(a).getWindow().getDecorView(),M("setup"));EditText in=inputs(dialog(a).getWindow().getDecorView()).get(0);assertEquals(M("seed").toString(),in.getHint().toString());assertEquals("",in.getText().toString());assertNull(get(a,"identity"));assertNull(get(a,"crypto"));assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->dialog(a).cancel());
  }
 }
 @Test public void ordersFiltersAndCsvRetainRawCodesAndAmounts()throws Exception{
  insert(false);java.io.File export=new java.io.File(context.getCacheDir(),"public-inbox-orders.csv");
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<View>page=new AtomicReference<>();s.onActivity(a->{node.set(freeze(a));crypto(a);set(a,"shopName",SHOP);set(a,"filterStatus","PAID");set(a,"filterShop",SHOP);call(a,"showOrders");page.set(stack(a).peek());});settle();
   byte[][] first={null};
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());assertEquals("PAID",get(a,"filterStatus"));assertEquals(SHOP,get(a,"filterShop"));exact(page.get(),SHOP);exact(page.get(),M("all"));exact(page.get(),M("autoOn"));exact(page.get(),"●  "+NAME);assertTrue(node.get().requests.isEmpty());call(a,"writeCsv",new Class<?>[]{android.net.Uri.class},android.net.Uri.fromFile(export));});AtomicReference<java.util.concurrent.ExecutorService>io=new AtomicReference<>();s.onActivity(a->io.set((java.util.concurrent.ExecutorService)get(a,"io")));io.get().submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);settle();byte[] bytes=Files.readAllBytes(export.toPath());if(first[0]==null)first[0]=bytes;else assertArrayEquals(first[0],bytes);String csv=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);assertTrue(csv.startsWith("ref,status,product,total,currency,paid,paidAmount,shipping,delivery,buyerKey,date\n"));assertTrue(csv.contains(REF+",PAID,Pay,0012.34000,USD,yes,0012.34000,digital,"+RAW));}
   s.onActivity(a->{exact(page.get(),M("statusShipped")).performClick();assertEquals("SHIPPED",get(a,"filterStatus"));assertEquals(SHOP,get(a,"filterShop"));assertTrue(node.get().requests.isEmpty());});
  }finally{assertTrue(!export.exists()||export.delete());}
 }
 @Test public void notificationsAndLocalDiagnosticsTranslateWithoutChangingChannelSettings(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();s.onActivity(a->node.set(freeze(a)));NotificationManager nm=context.getSystemService(NotificationManager.class);Object[] before=settings(nm.getNotificationChannel("merchinbox"));
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{call(a,"notifyNew",new Class<?>[]{int.class},3);assertTrue(node.get().requests.isEmpty());});Notification n=notice(nm);assertEquals(M("newActivity").toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(M("newUpdates",3).toString(),n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());assertEquals(M("orders").toString(),nm.getNotificationChannel("merchinbox").getName().toString());assertArrayEquals(before,settings(nm.getNotificationChannel("merchinbox")));nm.cancel("pandamonium.mall.inbox",2);SystemClock.sleep(100);
    for(boolean ok:new boolean[]{true,false}){s.onActivity(a->call(a,"notifyDelivery",new Class<?>[]{String.class,boolean.class,CharSequence.class},REF,ok,RAW));n=notice(nm);assertEquals(M(ok?"paymentReceived":"deliveryFailed").toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(M(ok?"deliveredNotice":"retryNotice",REF).toString(),n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());nm.cancel("pandamonium.mall.inbox",2);SystemClock.sleep(100);}
    for(String error:new String[]{"transaction does not balance — refusing to sign","TOO_LONG","txncheck failed","txnsign failed : sign failed"}) assertTrue(InboxMessages.message(error) instanceof Language.Message);assertEquals(RAW,InboxMessages.message(RAW));assertEquals(RAW,InboxMessages.status(RAW));assertEquals(M("badAddress").toString(),InboxMessages.message("buyer pay address is malformed").toString());assertEquals(M("missingPiece","2",RAW).toString(),InboxMessages.message("piece #2 of "+RAW+" is not in this wallet — sold or moved?").toString());assertEquals(M("missingCollection",RAW).toString(),InboxMessages.message("no pieces of "+RAW+" are in this wallet — sold or moved?").toString());
   }
  }
 }
 static Language.Message M(String key,Object...args){switch(key){
case "all":return Language.msg(l10n_mall_inbox_all,args);
case "autoOn":return Language.msg(l10n_mall_inbox_auto_on,args);
case "badAddress":return Language.msg(l10n_mall_inbox_bad_address,args);
case "buyerSays":return Language.msg(l10n_mall_inbox_buyer_says,args);
case "deliveredNotice":return Language.msg(l10n_mall_inbox_delivered_notice,args);
case "deliveryFailed":return Language.msg(l10n_mall_inbox_delivery_failed,args);
case "digital":return Language.msg(l10n_mall_inbox_digital,args);
case "missingCollection":return Language.msg(l10n_mall_inbox_missing_collection,args);
case "missingPiece":return Language.msg(l10n_mall_inbox_missing_piece,args);
case "newActivity":return Language.msg(l10n_mall_inbox_new_activity,args);
case "newUpdates":return Language.msg(l10n_mall_inbox_new_updates,args);
case "orders":return Language.msg(l10n_mall_inbox_orders,args);
case "paidAmount":return Language.msg(l10n_mall_inbox_paid_amount,args);
case "paymentReceived":return Language.msg(l10n_mall_inbox_payment_received,args);
case "reply":return Language.msg(l10n_mall_inbox_reply,args);
case "retryNotice":return Language.msg(l10n_mall_inbox_retry_notice,args);
case "seed":return Language.msg(l10n_mall_shop_seed,args);
case "sendBuyer":return Language.msg(l10n_mall_inbox_send_buyer,args);
case "sendHelp":return Language.msg(l10n_mall_inbox_send_help,args);
case "sendNft":return Language.msg(l10n_mall_inbox_send_nft,args);
case "setup":return Language.msg(l10n_mall_inbox_setup,args);
case "statusShipped":return Language.msg(l10n_mall_shop_status_shipped,args);
case "vendorCard":return Language.msg(l10n_mall_inbox_vendor_card,args);
case "vendorHelp":return Language.msg(l10n_mall_inbox_vendor_help,args);
default:throw new AssertionError(key);}}
}
