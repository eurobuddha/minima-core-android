package com.eurobuddha.merchstudio;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.eurobuddha.merchstudio.integratedcomms.NodeApi;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.merchstudio.MallStudioLanguagesTest.*;
@RunWith(AndroidJUnit4.class)
public class StudioRefreshTest {
 private final MallStudioLanguagesTest guards=new MallStudioLanguagesTest();
 private static final String TOKEN="0x"+"56".repeat(32),ADDR="0x"+"34".repeat(32);
 @Before public void prepare(){guards.prepare();}
 @After public void reset(){guards.reset();}
 static final class ReplayNode extends NodeApi {
  final Map<String,Deque<Cb>> pending=new LinkedHashMap<>();final List<String> requests=new ArrayList<>();
  ReplayNode(Context c){super(c,null);}
  @Override public void cmd(String command,Cb cb){requests.add(command);pending.computeIfAbsent(command,k->new ArrayDeque<>()).add(cb);}
  Cb take(String command){Deque<Cb> q=pending.get(command);assertNotNull("Missing request: "+command,q);assertFalse(q.isEmpty());return q.remove();}
 }
 static ReplayNode fixture(MainActivity a){freeze(a).onDestroy();ReplayNode n=new ReplayNode(a);set(a,"node",n);return n;}
 static JSONObject balance(){return obj("tokenid",TOKEN,"token",obj("name","Pay","decimals","0"),"confirmed","2","unconfirmed","0","sendable","2","total","15");}
 static JSONObject coin(int id,String index){return obj("coinid","0x"+String.format("%02x",id).repeat(32),"tokenid",TOKEN,"address",ADDR,"tokenamount","1","state",obj("0",index),"spent",false,"storestate",true);}
 static JSONObject malformedCoin(){JSONObject c=coin(2,"1");try{return c.put("state",new JSONArray().put("broken"));}catch(Exception e){throw new AssertionError(e);}}
 static JSONObject reply(Object value){return obj("status",true,"response",value);}
 static Map<String,Object> collections(MainActivity a){return (Map<String,Object>)get(a,"collections");}
 static Map<String,JSONObject> records(MainActivity a){return (Map<String,JSONObject>)get(a,"tokenRecords");}
 static void load(MainActivity a,Object col){call(a,"loadCollectionCoins",new Class<?>[]{col.getClass()},col);}
 @Test public void failedAndMalformedBalanceKeepsEditsButRealEmptyRemovesListings(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){settle();s.onActivity(a->{ReplayNode n=fixture(a);Object item=nft(a),typed=product(a);records(a).put(TOKEN,obj("tokenid",TOKEN,"script","RETURN TRUE"));set(a,"nftsLoaded",true);
   for(JSONObject bad:new JSONObject[]{null,obj("status",false,"response",new JSONArray()),obj("status",true),reply(new JSONArray().put(balance()).put("broken"))}){call(a,"loadNfts");n.take("balance").onResult(bad);assertSame(item,listings(a).get(TOKEN));assertEquals("0002.5000",get(item,"price"));assertEquals(false,get(a,"nftsLoading"));assertEquals(false,get(a,"walletVerified"));assertEquals(true,get(a,"nftsLoaded"));assertSame(typed,products(a).get(0));}
   call(a,"loadNfts");n.take("balance").onResult(reply(new JSONArray().put(balance())));assertSame(item,listings(a).get(TOKEN));assertEquals("0002.5000",get(item,"price"));assertEquals("2",((TokenBalance)get(item,"bal")).sendable);assertEquals(true,get(a,"walletVerified"));
   call(a,"loadNfts");n.take("balance").onResult(reply(new JSONArray()));assertTrue(listings(a).isEmpty());assertEquals(1,products(a).size());assertEquals(Collections.singletonList("balance"),new ArrayList<>(new LinkedHashSet<>(n.requests)));
  });}
 }
 @Test public void collectionRefreshKeepsBundleAndPieceEditsAndRejectsPartialRows(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){settle();s.onActivity(a->{ReplayNode n=fixture(a);Object col=collection(a);((List<JSONObject>)get(col,"ownedCoins")).clear();load(a,col);n.take("coins relevant:true tokenid:"+TOKEN).onResult(reply(new JSONArray().put(coin(1,"1"))));Object piece=listings(a).get(TOKEN+"#1");assertNotNull(piece);set(piece,"listed",true);set(piece,"price","0004.2500");set(piece,"name","User edited 中文");Object bundle=model("NftListing");set(bundle,"tokenid",TOKEN);set(bundle,"bundle",true);set(bundle,"listed",true);set(bundle,"price","0010.00");set(bundle,"snMeta",get(col,"meta"));listings(a).put(TOKEN+"#ALL",bundle);
   for(JSONObject bad:new JSONObject[]{reply(new JSONArray().put(malformedCoin())),reply(new JSONArray().put(coin(2,"1")).put("bad")),obj("status",false,"response",new JSONArray()),obj("status",true),reply(new JSONArray().put(coin(2,"1")).put(coin(3,"01")))}){load(a,col);n.take("coins relevant:true tokenid:"+TOKEN).onResult(bad);assertSame(piece,listings(a).get(TOKEN+"#1"));assertSame(bundle,listings(a).get(TOKEN+"#ALL"));assertEquals(1,((List<?>)get(col,"ownedCoins")).size());assertEquals(false,get(col,"coinsLoading"));assertEquals(false,get(col,"coinsVerified"));}
   load(a,col);n.take("coins relevant:true tokenid:"+TOKEN).onResult(reply(new JSONArray().put(coin(2,"1")).put(coin(3,"999999999999999999999999"))));assertSame(piece,listings(a).get(TOKEN+"#1"));assertSame(bundle,listings(a).get(TOKEN+"#ALL"));assertEquals("0004.2500",get(piece,"price"));assertEquals("User edited 中文",get(piece,"name"));assertEquals(true,get(col,"coinsVerified"));assertEquals(coin(2,"1").optString("coinid"),((JSONObject)get(piece,"coinRaw")).optString("coinid"));assertEquals(1,((List<?>)get(col,"ownedCoins")).size());
   load(a,col);n.take("coins relevant:true tokenid:"+TOKEN).onResult(reply(new JSONArray()));assertFalse(listings(a).containsKey(TOKEN+"#ALL"));assertFalse(listings(a).containsKey(TOKEN+"#1"));
  });}
 }
 @Test public void delayedCollectionAndTokenRepliesCannotResurrectRemovedHoldings(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){settle();s.onActivity(a->{ReplayNode n=fixture(a);Object col=collection(a);load(a,col);NodeApi.Cb old=n.take("coins relevant:true tokenid:"+TOKEN);call(a,"loadNfts");n.take("balance").onResult(reply(new JSONArray()));assertTrue(collections(a).isEmpty());old.onResult(reply(new JSONArray().put(coin(1,"1"))));assertTrue(listings(a).isEmpty());
   call(a,"loadNfts");n.take("balance").onResult(reply(new JSONArray().put(balance())));NodeApi.Cb token=n.take("tokens tokenid:"+TOKEN);call(a,"loadNfts");n.take("balance").onResult(reply(new JSONArray()));JSONObject record=obj("tokenid",TOKEN,"script",StateNft.script(ADDR,"url"),"name",obj("name","Pay","size",8,"mode","url"));token.onResult(reply(record));assertTrue(collections(a).isEmpty());assertFalse(records(a).containsKey(TOKEN));
   records(a).put(TOKEN,record);call(a,"loadNfts");n.take("balance").onResult(reply(new JSONArray().put(balance())));assertTrue(collections(a).containsKey(TOKEN));Object restored=collections(a).get(TOKEN);n.take("coins relevant:true tokenid:"+TOKEN).onResult(reply(new JSONArray().put(coin(2,"1"))));assertSame(restored,collections(a).get(TOKEN));assertTrue(listings(a).containsKey(TOKEN+"#1"));assertFalse(listings(a).containsKey(TOKEN));
  });}
 }
 static File photo(MainActivity a){try{File f=new File(a.getCacheDir(),"public-studio-photo.png");assertFalse(f.exists());Bitmap b=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);b.eraseColor(0xff33aa55);try(FileOutputStream o=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,o));}b.recycle();return f;}catch(Exception e){throw new AssertionError(e);}}
 static ExecutorService io(MainActivity a){return (ExecutorService)get(a,"io");}
 static void attach(MainActivity a,Object product,File photo){call(a,"attachPhoto",new Class<?>[]{product.getClass(),Uri.class},product,Uri.fromFile(photo));}
 @Test public void photoCompletionDoesNotReplaceAnotherProductEditor()throws Exception{
  AtomicReference<File>file=new AtomicReference<>();CountDownLatch release=new CountDownLatch(1);
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){settle();AtomicReference<Object>first=new AtomicReference<>(),second=new AtomicReference<>();AtomicReference<View>page=new AtomicReference<>();AtomicReference<EditText>draft=new AtomicReference<>();AtomicReference<ExecutorService>worker=new AtomicReference<>();
   s.onActivity(a->{fixture(a);first.set(product(a));second.set(product(a));file.set(photo(a));worker.set(io(a));worker.get().execute(()->{try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});attach(a,first.get(),file.get());show(a,"buildProductEditor",second.get());page.set(stack(a).peek());draft.set(inputs(page.get()).get(1));draft.get().setText("Keep this draft");draft.get().setSelection(4);});release.countDown();worker.get().submit(()->{}).get(15,TimeUnit.SECONDS);settle();s.onActivity(a->{assertFalse(((String)get(first.get(),"imageB64")).isEmpty());assertEquals("",get(second.get(),"imageB64"));assertSame(page.get(),stack(a).peek());assertSame(draft.get(),inputs(page.get()).get(1));assertEquals("Keep this draft",draft.get().getText().toString());assertEquals(4,draft.get().getSelectionStart());});
  }finally{release.countDown();if(file.get()!=null)assertTrue(file.get().delete());}
 }
 @Test public void photoCompletionPreservesSameEditorAndIgnoresDeletedOrSupersededRequest()throws Exception{
  AtomicReference<File>file=new AtomicReference<>();
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){settle();AtomicReference<Object>p=new AtomicReference<>();AtomicReference<View>page=new AtomicReference<>();AtomicReference<ExecutorService>worker=new AtomicReference<>();
   s.onActivity(a->{fixture(a);p.set(product(a));file.set(photo(a));show(a,"buildProductEditor",p.get());page.set(stack(a).peek());inputs(page.get()).get(1).setSelection(3);worker.set(io(a));attach(a,p.get(),file.get());});worker.get().submit(()->{}).get(15,TimeUnit.SECONDS);settle();s.onActivity(a->{assertSame(page.get(),stack(a).peek());assertEquals(3,inputs(page.get()).get(1).getSelectionStart());String accepted=(String)get(p.get(),"imageB64");assertFalse(accepted.isEmpty());assertNotNull(((ImageView)page.get().findViewWithTag(p.get())).getDrawable());long request=(Long)get(p.get(),"photoRequest");call(a,"applyPhoto",new Class<?>[]{p.get().getClass(),long.class,String.class},p.get(),request-1,"stale");assertEquals(accepted,get(p.get(),"imageB64"));products(a).remove(p.get());call(a,"applyPhoto",new Class<?>[]{p.get().getClass(),long.class,String.class},p.get(),request,"removed");assertEquals(accepted,get(p.get(),"imageB64"));assertSame(page.get(),stack(a).peek());});
  }finally{if(file.get()!=null)assertTrue(file.get().delete());}
 }

 @Test public void unstampedCoinsDoNotBlockCollectionOrDiscardEditedListings(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){settle();s.onActivity(a->{
   ReplayNode n=fixture(a);Object col=collection(a);load(a,col);
   n.take("coins relevant:true tokenid:"+TOKEN).onResult(reply(new JSONArray().put(coin(1,"1"))));
   Object piece=listings(a).get(TOKEN+"#1");assertNotNull(piece);set(piece,"listed",true);set(piece,"price","0004.2500");set(piece,"name","Edited name");
   JSONObject unstamped=coin(2,"0"),unsupported=coin(3,"3");
   try {unstamped.put("state",new JSONArray());unsupported.put("state",obj("0","3","2","creator note"));}catch(Exception e){throw new AssertionError(e);}
   load(a,col);n.take("coins relevant:true tokenid:"+TOKEN).onResult(reply(new JSONArray().put(coin(1,"1")).put(unstamped).put(unsupported)));
   assertEquals(true,get(col,"coinsVerified"));assertEquals(false,get(col,"coinsLoading"));
   assertEquals(1,get(col,"unstampedCount"));assertEquals(1,((List<?>)get(col,"ownedCoins")).size());
   assertSame(piece,listings(a).get(TOKEN+"#1"));assertEquals("0004.2500",get(piece,"price"));assertEquals("Edited name",get(piece,"name"));
   assertFalse(listings(a).containsKey(TOKEN+"#3"));
  });}
 }
}
