package com.eurobuddha.merchstudio;
import android.app.Instrumentation;
import android.content.*;
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
import com.eurobuddha.merchstudio.integratedcomms.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
@RunWith(AndroidJUnit4.class)
public class MallStudioLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String KEY="0x"+"12".repeat(64),ADDRESS="0x"+"34".repeat(32),TOKEN="0x"+"56".repeat(32);
 private static final String RAW="Send 中文 Русский Українська $1 {count}",NAME="Pay",SHOP="Public Studio Fixture";
 private DirectNodeApi.Backend backend;private boolean prepared;
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");});prepared=true;
 }
 @After public void reset(){if(!prepared)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");DirectNodeApi.install(backend);});assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());}
 static Object read(Class<?>c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n,Class<?>[] types,Object...args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n){return call(o,n,new Class<?>[]{});}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(200);}
 static JSONObject obj(Object...kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
 static class FixtureNode extends NodeApi{final List<String> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}@Override public void cmd(String c,Cb cb){requests.add(c);}}
 static FixtureNode freeze(MainActivity a){((NodeApi)get(a,"node")).onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode n=new FixtureNode(a);set(a,"node",n);set(a,"vendorPublicId",KEY);set(a,"vendorAddress",ADDRESS);set(a,"walletVerified",true);set(a,"paired",true);return n;}
 static Object model(String name){try{Constructor<?> c=Class.forName(MainActivity.class.getName()+"$"+name).getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}catch(Exception e){throw new AssertionError(e);}}
 static void show(MainActivity a,String method,Object value){push(a,(View)call(a,method,new Class<?>[]{value.getClass()},value));}
 static List<Object> products(MainActivity a){return (List<Object>)get(a,"typedProducts");}
 static Map<String,Object> listings(MainActivity a){return (Map<String,Object>)get(a,"listings");}
 static Object product(MainActivity a){Object p=model("TypedProduct");set(p,"name",NAME);set(p,"description",RAW);set(p,"price","0012.34000");set(p,"maxUnits",7);products(a).add(p);return p;}
 static Object collection(MainActivity a){Object col=model("StateCollection");StateNft.Meta m=new StateNft.Meta();m.name=NAME;m.size=8;set(col,"meta",m);set(col,"tokenid",TOKEN);set(col,"coinsLoaded",true);set(col,"coinsVerified",true);((List<JSONObject>)get(col,"ownedCoins")).add(obj("coinid","0x1234"));((Map<String,Object>)get(a,"collections")).put(TOKEN,col);return col;}
 static Object nft(MainActivity a){Object l=model("NftListing");TokenBalance b=TokenBalance.from(obj("tokenid",TOKEN,"token",obj("name",NAME,"decimals","0"),"sendable","9","total","15"));set(l,"bal",b);set(l,"tokenid",TOKEN);set(l,"name",NAME);set(l,"description",RAW);set(l,"price","0002.5000");set(l,"units","2");set(l,"listed",true);listings(a).put(TOKEN,l);((Map<String,JSONObject>)get(a,"tokenRecords")).put(TOKEN,obj("tokenid",TOKEN,"script","RETURN SIGNEDBY(0x00)"));return l;}
 static ArrayDeque<View> stack(MainActivity a){return (ArrayDeque<View>)get(a,"stack");}
 static void push(MainActivity a,View v){call(a,"push",new Class<?>[]{View.class},v);}
 static List<TextView> texts(View v){List<TextView> result=new ArrayList<>();if(v instanceof TextView)result.add((TextView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)result.addAll(texts(g.getChildAt(i)));}return result;}
 static TextView exact(View v,CharSequence label){for(TextView t:texts(v))if(label.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label: "+label);}
 static List<EditText> inputs(View v){List<EditText> result=new ArrayList<>();for(TextView t:texts(v))if(t instanceof EditText)result.add((EditText)t);return result;}
 static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
 static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}

 void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
 @Test public void productAndShopFieldsKeepDraftsAndCursorAcrossLanguages()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<Object>p=new AtomicReference<>();AtomicReference<View>page=new AtomicReference<>();AtomicReference<EditText>draft=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));p.set(product(a));show(a,"buildProductEditor",p.get());page.set(stack(a).peek());draft.set(inputs(page.get()).get(1));draft.get().setText(RAW);draft.get().setSelection(3);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());assertSame(draft.get(),inputs(page.get()).get(1));assertEquals(RAW,draft.get().getText().toString());assertEquals(3,draft.get().getSelectionStart());assertEquals(M("description").toString(),draft.get().getHint().toString());exact(page.get(),M("yourProduct"));exact(page.get(),Language.upper(M("name")));exact(page.get(),Language.upper(M("about")));exact(page.get(),Language.upper(M("stock")));assertEquals(NAME,get(p.get(),"name"));assertEquals("0012.34000",get(p.get(),"price"));assertEquals(7,get(p.get(),"maxUnits"));assertTrue(node.get().requests.isEmpty());});screenshot("mall-studio-product-"+lang);}
   s.onActivity(a->{set(a,"shopName",SHOP);set(a,"about",RAW);push(a,(View)call(a,"buildShopDetails"));page.set(stack(a).peek());List<EditText>ins=inputs(page.get());ins.get(2).setText(RAW);ins.get(3).setText("0001.2340");draft.set(ins.get(2));draft.get().setSelection(4);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());assertSame(draft.get(),inputs(page.get()).get(2));assertEquals(4,draft.get().getSelectionStart());assertEquals(RAW,draft.get().getText().toString());exact(page.get(),M("yourShop"));exact(page.get(),Language.upper(M("shipping")));Catalog.Shipping sh=((List<Catalog.Shipping>)get(a,"shipping")).get(0);assertEquals("uk",sh.id);assertEquals(RAW,sh.label);assertEquals("0001.2340",sh.fee);assertEquals("Minima",get(a,"currency"));assertEquals("0x00",get(a,"tokenid"));assertTrue(node.get().requests.isEmpty());});}
  }
 }
 @Test public void nftEditorKeepsLiteralMetadataExactPriceAndStepper()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<Object>l=new AtomicReference<>();AtomicReference<View>page=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));l.set(nft(a));show(a,"buildListingEditor",l.get());page.set(stack(a).peek());inputs(page.get()).get(1).setSelection(2);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());exact(page.get(),M("priceListing"));exact(page.get(),M("editions","15"));assertEquals(NAME,inputs(page.get()).get(0).getText().toString());assertEquals(RAW,inputs(page.get()).get(1).getText().toString());assertEquals(2,inputs(page.get()).get(1).getSelectionStart());assertEquals("0002.5000",inputs(page.get()).get(2).getText().toString());exact(page.get(),"+").performClick();assertEquals("3",get(l.get(),"units"));exact(page.get(),"–").performClick();assertEquals("2",get(l.get(),"units"));assertTrue(node.get().requests.isEmpty());});}
  }
 }
 @Test public void collectionDialogsKeepPriceAndCallbacksInAllLanguages()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();AtomicReference<Object>col=new AtomicReference<>();AtomicReference<AlertDialog>kept=new AtomicReference<>();AtomicReference<EditText>price=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));col.set(collection(a));show(a,"buildCollectionGrid",col.get());call(a,"bundleDialog",new Class<?>[]{col.get().getClass()},col.get());kept.set(dialog(a));price.set(inputs(dialog(a).getWindow().getDecorView()).get(0));price.get().setText("0012.34000");price.get().setSelection(4);});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));exact(dialog(a).getWindow().getDecorView(),M("sellComplete"));exact(dialog(a).getWindow().getDecorView(),M("bundleHelp",1));assertSame(price.get(),inputs(dialog(a).getWindow().getDecorView()).get(0));assertEquals(4,price.get().getSelectionStart());assertEquals("0012.34000",price.get().getText().toString());assertEquals(M("bundlePriceHint",1,"Minima").toString(),price.get().getHint().toString());assertTrue(listings(a).isEmpty());assertTrue(node.get().requests.isEmpty());});screenshot("mall-studio-bundle-"+lang);}
   s.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();
   s.onActivity(a->{Object b=listings(a).get(TOKEN+"#ALL");assertNotNull(b);assertEquals("0012.34000",get(b,"price"));assertEquals(NAME+" — complete collection",get(b,"name"));assertEquals(true,get(b,"listed"));call(a,"priceAllDialog",new Class<?>[]{col.get().getClass()},col.get());kept.set(dialog(a));price.set(inputs(dialog(a).getWindow().getDecorView()).get(0));price.get().setText("0003.2100");});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));exact(dialog(a).getWindow().getDecorView(),M("priceOwned"));assertEquals("0003.2100",price.get().getText().toString());assertEquals(M("pricePerPiece","Minima").toString(),price.get().getHint().toString());assertEquals(true,get(listings(a).get(TOKEN+"#ALL"),"listed"));assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->dialog(a).cancel());
  }
 }
 @Test public void exportedShopBytesStayIdenticalAcrossLanguages()throws Exception{
  Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();AtomicReference<Intent>chooser=new AtomicReference<>();
  Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){chooser.set(intent);return new Instrumentation.ActivityResult(0,null);}return null;}};instrumentation.addMonitor(monitor);
  List<android.net.Uri> exports=new ArrayList<>();
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode>node=new AtomicReference<>();s.onActivity(a->{node.set(freeze(a));product(a);nft(a);set(a,"shopName",SHOP);set(a,"about",RAW);Catalog.Shipping sh=((List<Catalog.Shipping>)get(a,"shipping")).get(0);sh.label=RAW;sh.fee="0001.2340";});byte[] first=null;
   for(String lang:LANGS){s.onActivity(a->{Language.select(a,lang);call(a,"exportShop");});AtomicReference<java.util.concurrent.ExecutorService>io=new AtomicReference<>();s.onActivity(a->io.set((java.util.concurrent.ExecutorService)get(a,"io")));io.get().submit(()->{}).get(15,java.util.concurrent.TimeUnit.SECONDS);settle();
    Intent shown=chooser.getAndSet(null);assertNotNull(shown);assertEquals(M("share").toString(),shown.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());Intent send=shown.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_SEND,send.getAction());android.net.Uri uri=send.getParcelableExtra(Intent.EXTRA_STREAM);assertEquals("com.eurobuddha.pandamonium.merchstudio.fileprovider",uri.getAuthority());assertEquals("application/octet-stream",send.getType());assertEquals(SHOP+" — miniMall shop",send.getStringExtra(Intent.EXTRA_SUBJECT));exports.add(uri);byte[] bytes=exportBytes(context,uri);if(first==null)first=bytes;else assertArrayEquals(first,bytes);
    JSONObject shop=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));assertEquals(3,shop.getInt("schema"));assertEquals(SHOP,shop.getString("shopName"));assertEquals(RAW,shop.getString("about"));assertEquals(KEY,shop.getString("vendorPublicId"));assertEquals(ADDRESS,shop.getString("vendorAddress"));assertEquals("Minima",shop.getString("currency"));assertEquals("0x00",shop.getString("tokenid"));JSONObject sh=shop.getJSONArray("shipping").getJSONObject(0);assertEquals("uk",sh.getString("id"));assertEquals(RAW,sh.getString("label"));assertEquals("0001.2340",sh.getString("fee"));JSONArray ps=shop.getJSONArray("products");assertEquals(2,ps.length());assertEquals(NAME,ps.getJSONObject(0).getString("name"));assertEquals(RAW,ps.getJSONObject(0).getString("description"));assertEquals("0012.34000",ps.getJSONObject(0).getString("price"));assertEquals(7,ps.getJSONObject(0).getInt("maxUnits"));assertEquals(TOKEN,ps.getJSONObject(1).getString("nftTokenId"));assertEquals("0002.5000",ps.getJSONObject(1).getString("price"));assertEquals(2,ps.getJSONObject(1).getInt("maxUnits"));s.onActivity(a->{assertFalse((Boolean)get(a,"exporting"));assertTrue(node.get().requests.isEmpty());});
   }
  }finally{instrumentation.removeMonitor(monitor);for(android.net.Uri uri:exports)deleteExport(context,uri);}
 }
 static byte[] exportBytes(Context context,android.net.Uri uri)throws Exception{try(java.io.InputStream in=context.getContentResolver().openInputStream(uri)){assertNotNull(in);return in.readAllBytes();}}
 static void deleteExport(Context context,android.net.Uri uri)throws Exception{
  List<String> parts=uri.getPathSegments();assertEquals(3,parts.size());assertEquals("shops",parts.get(0));assertTrue(parts.get(1).startsWith("export-"));
  java.io.File root=new java.io.File(context.getCacheDir(),"shops"),dir=new java.io.File(root,parts.get(1)),file=new java.io.File(dir,parts.get(2));assertEquals(root.getCanonicalFile(),dir.getCanonicalFile().getParentFile());assertEquals(dir.getCanonicalFile(),file.getCanonicalFile().getParentFile());assertTrue(file.delete());assertTrue(dir.delete());
 }
 static CharSequence M(String key,Object...args){switch(key){
case "ready":return Language.msg(l10n_mall_studio_ui_ready,args);
case "products":return Language.msg(l10n_mall_studio_ui_products,args);
case "continueShop":return Language.msg(l10n_mall_studio_ui_continue_shop,args);
case "addToContinue":return Language.msg(l10n_mall_studio_ui_add_to_continue,args);
case "productsHelp":return Language.msg(l10n_mall_studio_ui_products_help,args);
case "unnamed":return Language.msg(l10n_mall_studio_ui_unnamed,args);
case "noPrice":return Language.msg(l10n_mall_studio_ui_no_price,args);
case "addProduct":return Language.msg(l10n_mall_studio_ui_add_product,args);
case "cap":return Language.msg(l10n_mall_studio_ui_cap,args);
case "walletCount":return Language.msg(l10n_mall_studio_ui_wallet_count,args);
case "reading":return Language.msg(l10n_mall_studio_ui_reading,args);
case "noNfts":return Language.msg(l10n_mall_studio_ui_no_nfts,args);
case "nftHelp":return Language.msg(l10n_mall_studio_ui_nft_help,args);
case "yourProduct":return Language.msg(l10n_mall_studio_ui_your_product,args);
case "addPhoto":return Language.msg(l10n_mall_studio_ui_add_photo,args);
case "changePhoto":return Language.msg(l10n_mall_studio_ui_change_photo,args);
case "name":return Language.msg(l10n_mall_studio_ui_name,args);
case "productName":return Language.msg(l10n_mall_studio_ui_product_name,args);
case "about":return Language.msg(l10n_mall_studio_ui_about,args);
case "description":return Language.msg(l10n_mall_studio_ui_description,args);
case "price":return Language.msg(l10n_mall_studio_ui_price,args);
case "stock":return Language.msg(l10n_mall_studio_ui_stock,args);
case "done":return Language.msg(l10n_chat_done,args);
case "needName":return Language.msg(l10n_mall_studio_ui_need_name,args);
case "needPrice":return Language.msg(l10n_mall_studio_ui_need_price,args);
case "removeProduct":return Language.msg(l10n_mall_studio_ui_remove_product,args);
case "collectionHeld":return Language.msg(l10n_mall_studio_ui_collection_held,args);
case "readingPieces":return Language.msg(l10n_mall_studio_ui_reading_pieces,args);
case "bundleListed":return Language.msg(l10n_mall_studio_ui_bundle_listed,args);
case "listed":return Language.msg(l10n_mall_studio_ui_listed,args);
case "viewPieces":return Language.msg(l10n_mall_studio_ui_view_pieces,args);
case "listedNeedsPrice":return Language.msg(l10n_mall_studio_ui_listed_needs_price,args);
case "unlisted":return Language.msg(l10n_mall_studio_ui_unlisted,args);
case "product":return Language.msg(l10n_mall_studio_ui_product,args);
case "available":return Language.msg(l10n_mall_studio_ui_available,args);
case "priceListing":return Language.msg(l10n_mall_studio_ui_price_listing,args);
case "edition":return Language.msg(l10n_mall_studio_ui_edition,args);
case "listingName":return Language.msg(l10n_mall_studio_ui_listing_name,args);
case "pieceDescription":return Language.msg(l10n_mall_studio_ui_piece_description,args);
case "editionsSale":return Language.msg(l10n_mall_studio_ui_editions_sale,args);
case "held":return Language.msg(l10n_mall_studio_ui_held,args);
case "doneListed":return Language.msg(l10n_mall_studio_ui_done_listed,args);
case "removeListing":return Language.msg(l10n_mall_studio_ui_remove_listing,args);
case "piecesHeld":return Language.msg(l10n_mall_studio_ui_pieces_held,args);
case "bundlePrice":return Language.msg(l10n_mall_studio_ui_bundle_price,args);
case "slots":return Language.msg(l10n_mall_studio_ui_slots,args);
case "unstamped":return Language.msg(l10n_mall_studio_ui_unstamped,args);
case "legacy":return Language.msg(l10n_mall_studio_ui_legacy,args);
case "coinListLarge":return Language.msg(l10n_mall_studio_ui_coin_list_large,args);
case "priceEach":return Language.msg(l10n_mall_studio_ui_price_each,args);
case "editBundle":return Language.msg(l10n_mall_studio_ui_edit_bundle,args);
case "sellBundle":return Language.msg(l10n_mall_studio_ui_sell_bundle,args);
case "nothingListed":return Language.msg(l10n_mall_studio_ui_nothing_listed,args);
case "doneSummary":return Language.msg(l10n_mall_studio_ui_done_summary,args);
case "pricePerPiece":return Language.msg(l10n_mall_studio_ui_price_per_piece,args);
case "priceOwned":return Language.msg(l10n_mall_studio_ui_price_owned,args);
case "listThem":return Language.msg(l10n_mall_studio_ui_list_them,args);
case "positivePrice":return Language.msg(l10n_mall_studio_ui_positive_price,args);
case "listedCapped":return Language.msg(l10n_mall_studio_ui_listed_capped,args);
case "listedPrice":return Language.msg(l10n_mall_studio_ui_listed_price,args);
case "bundleRemoved":return Language.msg(l10n_mall_studio_ui_bundle_removed,args);
case "piecesRemoved":return Language.msg(l10n_mall_studio_ui_pieces_removed,args);
case "noPieces":return Language.msg(l10n_mall_studio_ui_no_pieces,args);
case "bundlePriceHint":return Language.msg(l10n_mall_studio_ui_bundle_price_hint,args);
case "sellComplete":return Language.msg(l10n_mall_studio_ui_sell_complete,args);
case "bundleHelp":return Language.msg(l10n_mall_studio_ui_bundle_help,args);
case "updatePrice":return Language.msg(l10n_mall_studio_ui_update_price,args);
case "listIt":return Language.msg(l10n_mall_studio_ui_list_it,args);
case "full":return Language.msg(l10n_mall_studio_ui_full,args);
case "unlist":return Language.msg(l10n_mall_studio_ui_unlist,args);
case "yourShop":return Language.msg(l10n_mall_studio_ui_your_shop,args);
case "shopName":return Language.msg(l10n_mall_studio_ui_shop_name,args);
case "shopHint":return Language.msg(l10n_mall_studio_ui_shop_hint,args);
case "aboutHint":return Language.msg(l10n_mall_studio_ui_about_hint,args);
case "pays":return Language.msg(l10n_mall_studio_ui_pays,args);
case "shipping":return Language.msg(l10n_mall_studio_ui_shipping,args);
case "label":return Language.msg(l10n_mall_studio_ui_label,args);
case "productCount":return Language.msg(l10n_mall_studio_ui_product_count,args);
case "nftCount":return Language.msg(l10n_mall_studio_ui_nft_count,args);
case "preview":return Language.msg(l10n_mall_studio_ui_preview,args);
case "needShopName":return Language.msg(l10n_mall_studio_ui_need_shop_name,args);
case "buyerPreview":return Language.msg(l10n_mall_studio_ui_buyer_preview,args);
case "preparing":return Language.msg(l10n_mall_studio_ui_preparing,args);
case "export":return Language.msg(l10n_mall_studio_ui_export,args);
case "needKey":return Language.msg(l10n_mall_studio_ui_need_key,args);
case "needListing":return Language.msg(l10n_mall_studio_ui_need_listing,args);
case "unnamedError":return Language.msg(l10n_mall_studio_ui_unnamed_error,args);
case "priceFor":return Language.msg(l10n_mall_studio_ui_price_for,args);
case "notSellable":return Language.msg(l10n_mall_studio_ui_not_sellable,args);
case "noPiecesLeft":return Language.msg(l10n_mall_studio_ui_no_pieces_left,args);
case "share":return Language.msg(l10n_mall_studio_ui_share,args);
case "allPieces":return Language.msg(l10n_mall_studio_ui_all_pieces,args);
case "allPiecesCount":return Language.msg(l10n_mall_studio_ui_all_pieces_count,args);
case "cancel":return Language.msg(l10n_common_cancel,args);
case "close":return Language.msg(l10n_language_close,args);
case "back":return Language.msg(l10n_explorer_back,args);
case "nodeUnavailable":return Language.msg(l10n_mall_shop_node_unavailable,args);
case "connecting":return Language.msg(l10n_mail_connecting,args);
case "connectFirst":return Language.msg(l10n_mall_shop_connect_first,args);
case "identityError":return Language.msg(l10n_mall_shop_identity_error,args);
case "exportError":return Language.msg(l10n_mall_inbox_export_error,args);
case "oneOfOne":return Language.msg(l10n_mall_shop_one_of_one,args);
case "editions":return Language.msg(l10n_mall_shop_editions,args);
case "nftPays":return Language.msg(l10n_mall_shop_nft_pays,args);
case "paysCurrency":return Language.msg(l10n_mall_shop_pays,args);
case "minus":return Language.msg(l10n_mall_shop_minus,args);
case "plus":return Language.msg(l10n_mall_shop_plus,args);
case "walletError":return Language.msg(l10n_mall_studio_ui_wallet_error,args);
case "imageUnreadable":return Language.msg(l10n_mail_image_unreadable,args);
case "refresh":return Language.msg(l10n_filez_refresh,args);
case "website":return Language.msg(l10n_wallet_website,args);
default:throw new AssertionError(key);}}
}
