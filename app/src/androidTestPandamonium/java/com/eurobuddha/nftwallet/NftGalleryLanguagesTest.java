package com.eurobuddha.nftwallet;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Matrix;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public, disposable display fixtures. Node callbacks are controlled; financial actions are never invoked. */
@RunWith(AndroidJUnit4.class)
public class NftGalleryLanguagesTest {
 private final NftShellLanguagesTest guards=new NftShellLanguagesTest();
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] CODES={"en","zh-Hans","ru","uk"};
 private static final String A="0x"+"11".repeat(32),B="0x"+"22".repeat(32),C="0x"+"33".repeat(32),D="0x"+"44".repeat(32),ADDRESS="0x"+"55".repeat(32),PK="0x"+"66".repeat(32);
 private static final String DESC="Collection 中文 Русский Українська $1 {name}",ART="["+"A".repeat(240)+"]",URL="https://example.invalid/art?name=Collection&x=uk%20zh";
 private boolean authorized;
 @Before public void prepare(){guards.prepare();assertTrue(HiddenTokens.all(context).isEmpty());assertTrue(favs().isEmpty());authorized=true;}
 @After public void reset(){if(authorized)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Set<String> f=favs();f.remove(A+"#2");context.getSharedPreferences("nftwallet_gallery",0).edit().putStringSet("favs",f).commit();});guards.reset();}
 private Set<String> favs(){return new HashSet<>(context.getSharedPreferences("nftwallet_gallery",0).getStringSet("favs",Collections.emptySet()));}
 private static GalleryView gallery(MainActivity a){return (GalleryView)((BaseView[])get(a,"views"))[MainActivity.TAB_GALLERY];}
 private static List<GalleryView.GItem> items(GalleryView g){return (List<GalleryView.GItem>)get(g,"items");}
 private static View root(Dialog d){return d.getWindow().getDecorView();}
 private static GalleryView.GItem item(GalleryView g,String token){for(GalleryView.GItem it:items(g))if(token.equals(it.tokenid))return it;throw new AssertionError("missing fixture");}
 private static TextView action(View root,CharSequence label){for(TextView t:texts(root))if(t.isClickable()&&label.toString().equals(t.getText().toString()))return t;throw new AssertionError("missing action "+label);}
 private static TextView described(View root,CharSequence label){for(TextView t:texts(root))if(label.toString().contentEquals(t.getContentDescription()==null?"":t.getContentDescription()))return t;throw new AssertionError("missing description "+label);}
 private static <T> T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){T x=find(g.getChildAt(i),type);if(x!=null)return x;}}return null;}
 private static void visible(TextView t){android.graphics.Rect b=new android.graphics.Rect();assertTrue(t.getText()+" visibility="+t.getVisibility()+" height="+t.getHeight()+" top="+t.getTop(),t.getGlobalVisibleRect(b));assertEquals(t.getText().toString(),t.getHeight(),b.height());assertEquals(0,t.getLayout().getEllipsisCount(t.getLineCount()-1));assertTrue(t.getWidth()>0);int[] screen=new int[2];t.getLocationOnScreen(screen);android.graphics.Point size=new android.graphics.Point();t.getDisplay().getRealSize(size);androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(t);int bottom=insets==null?0:insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;assertTrue("Under system bar: "+t.getText()+" y="+screen[1]+" height="+t.getHeight()+" display="+size.y+" inset="+bottom,screen[1]>=0 && screen[1]+t.getHeight()<=size.y-bottom);}
 private static void clipboard(MainActivity a,String expected){assertEquals(expected,((android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());}
 static class GalleryNode extends FixtureNode {
  final Map<String,Cb> pending=new HashMap<>();GalleryNode(Context c){super(c);}
  @Override public void cmd(String cmd,Cb cb){requests.add(cmd);assertTrue("Unexpected command: "+cmd,cmd.startsWith("tokens tokenid:"));pending.put(cmd.substring("tokens tokenid:".length()),cb);}
 }
 private static JSONObject metadata(String name){JSONObject m=object("size",7,"mode","embed","description",DESC,"external_url",URL);if(name!=null)try{m.put("name",name);}catch(Exception e){throw new AssertionError(e);}return m;}
 private static JSONObject record(String name){return object("script",StateNft.script(PK,"embed"),"token",object("name",metadata(name),"totalamount","7"));}
 private static Coin coin(String token,int idx,int id){Coin c=Coin.from(object("coinid","0x"+String.format(java.util.Locale.ROOT,"%02x",id).repeat(32),"address",ADDRESS,"tokenid",token,"amount","0.00000001","tokenamount","1.000000","token",object("name",object("name","Collection")),"created","90","state",new JSONArray().put(object("port","0","data",String.valueOf(idx))).put(object("port","1","data",ART)).put(object("port","2","data",DESC))));c.sendable=true;return c;}
 private static GalleryNode fixture(MainActivity a,boolean delayed){
  freeze(a).onDestroy();GalleryNode node=new GalleryNode(a);set(a,"node",node);a.balances().clear();a.coins().clear();
  for(String token:new String[]{A,B,C,D}){String name=token.equals(A)?"Collection":token.equals(C)?"Send":null;JSONObject meta=object("description",DESC,"owner","Public creator");if(name!=null)try{meta.put("name",name);}catch(Exception e){throw new AssertionError(e);}
   a.balances().add(TokenBalance.from(object("tokenid",token,"token",object("name",meta,"decimals","0"),"sendable","1.000000","confirmed","1.000000","total",token.equals(C)?"1":"7","coins",1)));
  }
  for(int i=0;i<3;i++)a.coins().add(coin(A,i,20+i));a.coins().add(coin(B,1,25));
  GalleryView g=gallery(a);Map<String,JSONObject> records=(Map<String,JSONObject>)get(g,"tokenRecords");records.clear();((Set<?>)get(g,"fetching")).clear();
  if(!delayed){records.put(A,record("Collection"));records.put(B,record(null));records.put(C,object());records.put(D,object());}
  a.goToTab(MainActivity.TAB_GALLERY);g.refresh();node.requests.clear();return node;
 }
 @Test public void galleryNamesSearchAndCollectionStayInPlace()throws Exception {
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<GalleryNode> node=new AtomicReference<>();AtomicReference<View> first=new AtomicReference<>();
   s.onActivity(a->node.set(fixture(a,false)));settle();s.onActivity(a->first.set(((RecyclerView)get(gallery(a),"grid")).getChildAt(0)));
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{GalleryView g=gallery(a);View r=g.getRoot();assertEquals(4,items(g).size());exact(r,"Collection");exact(r,Language.msg(l10n_nft_collection));exact(r,"Send");exact(r,Language.msg(l10n_wallet_token));assertSame(first.get(),((RecyclerView)get(g,"grid")).getChildAt(0));assertEquals(Language.msg(l10n_nft_gallery_search).toString(),((EditText)get(g,"search")).getHint().toString());assertTrue(node.get().requests.isEmpty());assertEquals("Collection",item(g,A).name);assertEquals("Send",item(g,C).name);});guards.screenshot("nft-gallery-"+code);}
   s.onActivity(a->{GalleryView g=gallery(a);EditText q=(EditText)get(g,"search");q.setText("#2");q.setSelection(1);assertEquals(1,items(g).size());call(g,"openCollection",new Class<?>[]{GalleryView.GItem.class},item(g,A));});settle();
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{GalleryView g=gallery(a);assertEquals(A,get(g,"openCollection"));assertEquals("#2",((EditText)get(g,"search")).getText().toString());assertEquals(1,((EditText)get(g,"search")).getSelectionStart());assertEquals(1,items(g).size());exact(g.getRoot(),"Collection #2");exact(g.getRoot(),Language.upper(Language.msg(l10n_nft_gallery_held,3,"7")));assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-collection-"+code);}
   s.onActivity(a->{GalleryView g=gallery(a);call(g,"showCollectionActions",new Class<?>[]{});});
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{View r=root(dialog(a));exact(r,"Collection");exact(r,Language.msg(l10n_nft_gallery_collection_actions));visible(action(r,Language.msg(l10n_nft_gallery_send_collection)));visible(action(r,Language.msg(l10n_nft_gallery_bury_collection)));assertEquals(A,get(gallery(a),"openCollection"));assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-collection-actions-"+code);}
  }
 }
 @Test public void favouritesAndEmptyStateKeepTheSameStoredKey()throws Exception {
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<GalleryNode> node=new AtomicReference<>();s.onActivity(a->{node.set(fixture(a,false));((TextView)get(gallery(a),"tabFavs")).performClick();});
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertTrue(items(gallery(a)).isEmpty());exact(gallery(a).getRoot(),Language.msg(l10n_nft_gallery_no_favourites));assertTrue(favs().isEmpty());assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->call(gallery(a),"toggleFav",new Class<?>[]{String.class},A+"#2"));settle();
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals(Collections.singleton(A+"#2"),favs());assertEquals(1,items(gallery(a)).size());exact(gallery(a).getRoot(),"Collection #2");described(gallery(a).getRoot(),Language.msg(l10n_nft_gallery_unfavourite));assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->described(gallery(a).getRoot(),Language.msg(l10n_nft_gallery_unfavourite)).performClick());settle();s.onActivity(a->{assertTrue(favs().isEmpty());assertTrue(items(gallery(a)).isEmpty());});
  }
 }
 @Test public void viewerKeepsPositionZoomChromeAndUnstampedGuard()throws Exception {
  AtomicReference<Dialog> viewer=new AtomicReference<>();
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<GalleryNode> node=new AtomicReference<>();AtomicReference<ZoomImageView> image=new AtomicReference<>();AtomicReference<android.graphics.drawable.Drawable> drawable=new AtomicReference<>();AtomicReference<Matrix> matrix=new AtomicReference<>();
   s.onActivity(a->{node.set(fixture(a,false));GalleryView g=gallery(a);call(g,"openCollection",new Class<?>[]{GalleryView.GItem.class},item(g,A));assertEquals(3,items(g).size());NftViewer.open(a,new ArrayList<>(items(g)),0,(NftViewer.Host)get(g,"viewerHost"));viewer.set(dialog(a));image.set(find(root(viewer.get()),ZoomImageView.class));assertNotNull(image.get());});settle();
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(viewer.get(),dialog(a));TextView blocked=exact(root(viewer.get()),Language.upper(Language.msg(l10n_nft_gallery_unstamped)));assertFalse(blocked.isEnabled());assertFalse(blocked.hasOnClickListeners());exact(root(viewer.get()),Language.join("Collection", " (",Language.msg(l10n_nft_gallery_unstamped),")"));assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->{ZoomImageView.SwipeListener swipe=(ZoomImageView.SwipeListener)get(image.get(),"mSwipeListener");swipe.onSwipe(true);swipe.onSwipe(true);});settle();
   s.onActivity(a->{call(image.get(),"scaleBy",new Class<?>[]{float.class,float.class,float.class},2.5f,150f,300f);drawable.set(image.get().getDrawable());matrix.set(new Matrix(image.get().getImageMatrix()));});
   android.os.SystemClock.sleep(12000);
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{View r=root(viewer.get());assertSame(viewer.get(),dialog(a));assertSame(drawable.get(),image.get().getDrawable());assertEquals(matrix.get(),image.get().getImageMatrix());exact(r,"Collection #2");visible(exact(r,Language.join("3 / 3","  ·  ",Language.upper(Language.msg(l10n_nft_gallery_edition,2,7)),"  ·  ",Language.upper(Language.msg(l10n_nft_gallery_locked_edition)))));visible(action(r,Language.upper(Language.msg(l10n_nft_details))));visible(action(r,Language.upper(Language.msg(l10n_nft_wallet_transfer))));described(r,Language.msg(l10n_nft_gallery_close_viewer));assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-viewer-"+code);}
   s.onActivity(a->((Runnable)get(image.get(),"mTapListener")).run());settle();android.os.SystemClock.sleep(250);
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals(View.GONE,((View)described(root(viewer.get()),Language.msg(l10n_nft_gallery_close_viewer)).getParent()).getVisibility());assertEquals(matrix.get(),image.get().getImageMatrix());exact(root(viewer.get()),"Collection #2");assertTrue(node.get().requests.isEmpty());});}
   s.onActivity(a->((Runnable)get(image.get(),"mTapListener")).run());settle();
   s.onActivity(a->{described(root(viewer.get()),Language.msg(l10n_nft_gallery_favourite)).performClick();assertEquals(Collections.singleton(A+"#2"),favs());assertTrue(node.get().requests.isEmpty());});
  }
  settle();assertFalse(viewer.get().isShowing());
 }
 @Test public void detailKeepsMetadataFullClipboardAndSelectedItem()throws Exception {
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<GalleryNode> node=new AtomicReference<>();AtomicReference<Dialog> detail=new AtomicReference<>();AtomicReference<GalleryView.GItem> selected=new AtomicReference<>();AtomicReference<String> raw=new AtomicReference<>();
   s.onActivity(a->{node.set(fixture(a,false));GalleryView g=gallery(a);call(g,"openCollection",new Class<?>[]{GalleryView.GItem.class},item(g,A));selected.set(items(g).get(2));raw.set(selected.get().coin.raw.toString());call(g,"showDetail",new Class<?>[]{GalleryView.GItem.class},selected.get());detail.set(dialog(a));});
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(detail.get(),dialog(a));View r=root(detail.get());exact(r,"Collection #2");exact(r,DESC);exact(r,A);exact(r,ADDRESS);exact(r,"1.000000");exact(r,URL);exact(r,Language.msg(l10n_nft_gallery_embedded_artwork,ART.length()));exact(r,Language.msg(l10n_nft_gallery_identity));exact(r,Language.msg(l10n_nft_gallery_sealed,2));exact(r,Language.msg(l10n_nft_gallery_bury_item));exact(r,Language.msg(l10n_nft_gallery_send_collection));assertEquals(raw.get(),selected.get().coin.raw.toString());assertEquals("Collection #2",selected.get().name);assertTrue(node.get().requests.isEmpty());});guards.screenshot("nft-gallery-detail-"+code);}
   s.onActivity(a->{View r=root(detail.get());((View)exact(r,Language.msg(l10n_nft_gallery_embedded_artwork,ART.length())).getParent()).performClick();clipboard(a,ART);((View)exact(r,selected.get().coin.coinid).getParent()).performClick();clipboard(a,selected.get().coin.coinid);((View)exact(r,Language.msg(l10n_nft_gallery_sealed,2)).getParent()).performClick();clipboard(a,Language.msg(l10n_nft_gallery_sealed,2).toString());assertTrue(node.get().requests.isEmpty());});
  }
 }
 @Test public void delayedRecordUsesCurrentLanguageAndPreservesRawCollectionName()throws Exception {
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<GalleryNode> node=new AtomicReference<>();s.onActivity(a->{node.set(fixture(a,true));assertEquals(4,node.get().pending.size());Language.select(a,"uk");});settle();
   s.onActivity(a->{GalleryNode n=node.get();n.pending.get(A).onResult(object("response",record("Collection")));n.pending.get(B).onResult(object("response",record(null)));n.pending.get(C).onResult(object("response",object()));n.pending.get(D).onResult(object("response",object()));assertTrue(n.requests.isEmpty());});settle();
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{GalleryView g=gallery(a);assertEquals(4,items(g).size());exact(g.getRoot(),"Collection");exact(g.getRoot(),Language.msg(l10n_nft_collection));assertEquals("Collection",item(g,A).meta.name);assertTrue(node.get().requests.isEmpty());});}
  }
 }
 @Test public void singleImageCaptionStaysLiveWithoutResettingImage()throws Exception {
  AtomicReference<Dialog> viewer=new AtomicReference<>();
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<ZoomImageView> image=new AtomicReference<>();AtomicReference<Matrix> matrix=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));NftViewer.openSingle(a,"",Language.msg(l10n_nft_wallet_port,"1"));viewer.set(dialog(a));image.set(find(root(viewer.get()),ZoomImageView.class));node.get().requests.clear();});settle();
   s.onActivity(a->{call(image.get(),"scaleBy",new Class<?>[]{float.class,float.class,float.class},2.5f,150f,300f);matrix.set(new Matrix(image.get().getImageMatrix()));});
   for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(viewer.get(),dialog(a));exact(root(viewer.get()),Language.msg(l10n_nft_wallet_port,"1"));assertEquals(matrix.get(),image.get().getImageMatrix());assertTrue(node.get().requests.isEmpty());});}
  }
  settle();assertFalse(viewer.get().isShowing());
 }
 @Test public void placeholderReusesUtxoMalformedIdGuard(){
  GalleryView.GItem unnamed=new GalleryView.GItem();unnamed.name="";unnamed.displayName="";assertEquals("NFT",NftText.galleryName(unnamed));
  for(String id:new String[]{null,"","#-1","not hex / 中文",A,A+"#2"}){
   android.graphics.Bitmap actual=Identicon.forToken(id,48),expected=com.eurobuddha.utxo.Identicon.forToken(id,48);
   try{assertTrue("Placeholder differs for "+id,actual.sameAs(expected));}finally{actual.recycle();expected.recycle();}
  }
 }

}
