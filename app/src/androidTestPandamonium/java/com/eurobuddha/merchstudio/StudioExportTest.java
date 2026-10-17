package com.eurobuddha.merchstudio;
import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.merchstudio.MallStudioLanguagesTest.*;
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
public class StudioExportTest {
 private final MallStudioLanguagesTest guards=new MallStudioLanguagesTest();
 @Before public void prepareDisposable(){guards.prepare();}
 @After public void resetDisposable(){guards.reset();}
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private final List<Uri> exported=new ArrayList<>();
 private final AtomicReference<Intent> chooser=new AtomicReference<>();
 private Instrumentation.ActivityMonitor monitor;
 @Before public void monitorShare(){monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){chooser.set(intent);return new Instrumentation.ActivityResult(0,null);}return null;}};InstrumentationRegistry.getInstrumentation().addMonitor(monitor);}
 @After public void cleanExports()throws Exception{InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);for(Uri u:exported)deleteExport(context,u);}
 private Intent sent(){Intent c=chooser.getAndSet(null);assertNotNull(c);Intent send=c.getParcelableExtra(Intent.EXTRA_INTENT);exported.add(send.getParcelableExtra(Intent.EXTRA_STREAM));return send;}
 private static void drain(ActivityScenario<MainActivity> s)throws Exception{AtomicReference<ExecutorService>io=new AtomicReference<>();s.onActivity(a->io.set((ExecutorService)get(a,"io")));io.get().submit(()->{}).get(15,TimeUnit.SECONDS);settle();}
 @Test public void editsAndNavigationDuringExportCannotChangeSnapshotOrReplaceEditor()throws Exception{
  CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<Object>p=new AtomicReference<>(),n=new AtomicReference<>();AtomicReference<View>editor=new AtomicReference<>();AtomicReference<EditText>draft=new AtomicReference<>();
   s.onActivity(a->{freeze(a);p.set(product(a));n.set(nft(a));set(a,"shopName","Public Studio Fixture");set(a,"about","Original about");((ExecutorService)get(a,"io")).execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});});
   assertTrue(entered.await(5,TimeUnit.SECONDS));
   s.onActivity(a->{call(a,"exportShop");assertEquals(true,get(a,"exporting"));set(p.get(),"price","99");set(p.get(),"imageB64","changed photo");set(n.get(),"cachedB64","changed NFT art");set(n.get(),"price","999");set(a,"shopName","Changed shop");set(a,"about","Changed about");set(a,"currency","USDT");((List<Catalog.Shipping>)get(a,"shipping")).get(0).fee="300";listings(a).clear();show(a,"buildProductEditor",p.get());editor.set(stack(a).peek());draft.set(inputs(editor.get()).get(1));draft.get().setSelection(3);Language.select(a,"uk");});
   release.countDown();drain(s);Intent first=sent();Uri original=first.getParcelableExtra(Intent.EXTRA_STREAM);assertEquals("Public Studio Fixture — miniMall shop",first.getStringExtra(Intent.EXTRA_SUBJECT));byte[] bytes=exportBytes(context,original);JSONObject shop=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
   assertEquals("Public Studio Fixture",shop.getString("shopName"));assertEquals("Original about",shop.getString("about"));assertEquals("Minima",shop.getString("currency"));JSONArray products=shop.getJSONArray("products");assertEquals(2,products.length());assertEquals("0012.34000",products.getJSONObject(0).getString("price"));assertEquals("",products.getJSONObject(0).getString("image"));assertEquals("0002.5000",products.getJSONObject(1).getString("price"));assertNotEquals("changed NFT art",products.getJSONObject(1).getString("image"));assertEquals(2,products.getJSONObject(1).getInt("maxUnits"));
   s.onActivity(a->{assertFalse((Boolean)get(a,"exporting"));assertSame(editor.get(),stack(a).peek());assertSame(draft.get(),inputs(editor.get()).get(1));assertEquals(3,draft.get().getSelectionStart());set(a,"shopName","Public Studio Fixture");call(a,"exportShop");});drain(s);Intent second=sent();Uri other=second.getParcelableExtra(Intent.EXTRA_STREAM);assertNotEquals(original,other);assertArrayEquals(bytes,exportBytes(context,original));
  }finally{release.countDown();}
 }
 @Test public void unverifiedRefreshAndUnknownTokenCannotExportButTypedProductsCan()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);nft(a);set(a,"shopName","Eligibility Fixture");set(a,"walletVerified",false);call(a,"exportShop");assertFalse((Boolean)get(a,"exporting"));assertNull(chooser.get());set(a,"walletVerified",true);((Map<?,?>)get(a,"tokenRecords")).clear();call(a,"exportShop");assertFalse((Boolean)get(a,"exporting"));assertNull(chooser.get());listings(a).clear();product(a);set(a,"walletVerified",false);call(a,"exportShop");});drain(s);sent();
  }
 }
 @Test public void unsupportedSupplyNeverProducesWrappedCatalog()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);Object n=nft(a);set(a,"shopName","Overflow Fixture");TokenBalance balance=(TokenBalance)get(n,"bal");balance.total="4294967297";call(a,"exportShop");assertFalse((Boolean)get(a,"exporting"));assertNull(chooser.get());});
  }
 }
 @Test public void collectionExportRequiresVerifiedCoinsAndFreezesDirectoryMetadata()throws Exception{
  CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<Object>col=new AtomicReference<>();
   s.onActivity(a->{freeze(a);Object l=nft(a);listings(a).clear();col.set(collection(a));StateNft.Meta meta=(StateNft.Meta)get(col.get(),"meta");set(l,"bundle",true);set(l,"snMeta",meta);listings(a).put((String)get(l,"tokenid")+"#ALL",l);set(a,"shopName","Public Collection Fixture");set(col.get(),"coinsVerified",false);call(a,"exportShop");assertFalse((Boolean)get(a,"exporting"));assertNull(chooser.get());set(col.get(),"coinsVerified",true);meta.size=0;call(a,"exportShop");assertFalse((Boolean)get(a,"exporting"));assertNull(chooser.get());meta.size=8;
    ((ExecutorService)get(a,"io")).execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   });assertTrue(entered.await(5,TimeUnit.SECONDS));
   s.onActivity(a->{call(a,"exportShop");assertTrue((Boolean)get(a,"exporting"));StateNft.Meta meta=(StateNft.Meta)get(col.get(),"meta");meta.name="Changed collection";meta.size=300;((List<?>)get(col.get(),"ownedCoins")).clear();});release.countDown();drain(s);
   Intent send=sent();JSONObject shop=new JSONObject(new String(exportBytes(context,send.getParcelableExtra(Intent.EXTRA_STREAM)),java.nio.charset.StandardCharsets.UTF_8));JSONObject product=shop.getJSONArray("products").getJSONObject(0),collection=shop.getJSONArray("nftCollections").getJSONObject(0);assertEquals(1,product.getInt("nftBundle"));assertEquals(1,product.getInt("nftPieces"));assertEquals(8,product.getInt("nftSupply"));assertEquals("Pay",product.getString("nftCollection"));assertEquals("Pay",collection.getString("name"));assertEquals(8,collection.getInt("size"));assertFalse(collection.getString("icon").isEmpty());
  }finally{release.countDown();}
 }
}
