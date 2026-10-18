package com.eurobuddha.salon;

import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Retained proof/holding displays and picker. Mock proof generation; no signing or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonHoldingsLanguageTest {
    private MainActivity activity;private LinearLayout body,appbar;private NodeApi node;
    private ExecutorService worker;private final List<Runnable> jobs=new ArrayList<>();
    private MockedStatic<ImageLoader> images;private MockedStatic<WebValidate> verification;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    @SuppressWarnings({"unchecked","rawtypes"})private void screen(String name)throws Exception{set("screen",Enum.valueOf((Class)field("screen").getType(),name));}
    private void render()throws Exception{call("render",new Class<?>[]{});}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @SuppressWarnings("unchecked")private Map<String,Object[]> cache()throws Exception{return(Map<String,Object[]>)field("mProfileCache").get(activity);}
    private void cache(String url,JSONObject profile)throws Exception{cache().put(url,new Object[]{profile,System.currentTimeMillis()});}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbar",appbar);set("navRow",new LinearLayout(activity));
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));node=mock(NodeApi.class);set("node",node);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"handle","owner");SalonStore.put(activity,"tokenid","0xowner");SalonStore.put(activity,"profileUrl","https://example.invalid/owner/profile.json");
        images=mockStatic(ImageLoader.class);verification=mockStatic(WebValidate.class);
    }
    @After public void cleanup(){images.close();verification.close();worker.shutdownNow();Language.select(activity,"en");}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root){if(root instanceof TextView&&!(root instanceof EditText)){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText()+" w="+t.getWidth()+" h="+t.getHeight(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private void layout(){for(int w:new int[]{320,480,900})for(View root:new View[]{body,appbar}){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,w,root.getMeasuredHeight());fits(root);}}
    private JSONObject holding()throws Exception{return new JSONObject().put("tokenid","0xabc123").put("coinid","0xcoin123").put("name","Asset 中文 Русский Українська").put("image","https://example.invalid/art.png").put("coinproof","proof bytes stay literal").put("editions",new JSONArray().put(new JSONObject().put("url","https://example.invalid/1.png").put("caption","Original caption")));}
    @Test public void expandedHoldingsKeepArtworkAndProofsWhileLabelsSwitch()throws Exception{
        JSONObject item=holding();JSONArray items=new JSONArray().put(item);String before=items.toString();set("expandedNft",item.getString("tokenid"));
        call("renderNftShowcase",new Class<?>[]{JSONArray.class,String.class},items,"0xremote");View first=body.getChildAt(0);
        for(String code:CODES){Language.select(activity,code);layout();assertSame(first,body.getChildAt(0));assertEquals(before,items.toString());assertNotNull(find(body,item.getString("name")));assertNotNull(find(body,item.getString("tokenid")));assertNotNull(find(body,item.getString("coinid")));assertNotNull(find(body,upper(l10n_salon_holdings_holdings)));assertNotNull(find(body,upper(l10n_salon_holdings_verify_again)));assertNotNull(find(body,Language.msg(l10n_salon_holdings_collection_images,1).toString()));assertNotNull(find(body,Language.msg(l10n_salon_holdings_connect).toString()));assertNotNull(find(body,Language.msg(l10n_salon_holdings_full_artwork).toString()));}
        images.verify(()->ImageLoader.loadFull(eq(activity),eq("https://example.invalid/art.png"),any(ImageView.class)));verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void cachedVerdictsRetainTheirLanguageBindingAndManualRetryStillRuns()throws Exception{
        set("nodeUp",true);JSONObject item=holding();String before=item.toString();TextView first=new TextView(activity);body.addView(first);List<NftProof.Verify> callbacks=new ArrayList<>();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.verify(eq(node),any(JSONObject.class),eq("remote-binding"),any(NftProof.Verify.class))).thenAnswer(c->{callbacks.add(c.getArgument(3));return null;});
            call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote-binding",first);assertEquals(1,callbacks.size());callbacks.get(0).result(false,"proof out of date — re-publish to refresh it","");idle();
            TextView cached=new TextView(activity);body.addView(cached);call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote-binding",cached);assertEquals(1,callbacks.size());
            for(String code:CODES){Language.select(activity,code);String expected="✕ "+Language.msg(l10n_salon_holdings_stale);assertEquals(expected,first.getText().toString());assertEquals(expected,cached.getText().toString());assertEquals(before,item.toString());assertEquals(1,callbacks.size());}
            call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class,boolean.class},item,"remote-binding",cached,true);assertEquals(2,callbacks.size());callbacks.get(1).result(true,"verified holding","");idle();for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_holdings_verified).toString(),cached.getText().toString());}
        }
    }
    @Test public void diagnosticLookupTranslatesKnownReasonsAndPreservesUnknownText(){
        CharSequence known=HoldingMessages.message("incomplete proof"),unknown=HoldingMessages.message("node says: 中文 Русский Українська 0xAbC");
        CharSequence transport=HoldingMessages.message("Invalid node result. Outcome remains unknown.");
        for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_holdings_incomplete).toString(),known.toString());assertEquals("node says: 中文 Русский Українська 0xAbC",unknown.toString());assertEquals(Language.msg(l10n_vestr_transport_invalid).toString(),transport.toString());}
        assertEquals("",HoldingMessages.message(null));
    }
    @Test public void assetPickerKeepsNamesAndSelectionAcrossLanguageChanges()throws Exception{
        JSONObject item=holding();List<JSONObject> assets=Arrays.asList(item);String before=item.toString();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            call("showAssetPicker",new Class<?>[]{List.class},assets);idle();androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(dialog);
            for(String code:CODES){Language.select(activity,code);idle();assertSame(dialog,ShadowDialog.getLatestDialog());assertNotNull(find(dialog.getWindow().getDecorView(),Language.msg(l10n_salon_holdings_prove).toString()));assertEquals(item.getString("name"),dialog.getListView().getAdapter().getItem(0).toString());assertEquals(before,item.toString());}
            ListView list=dialog.getListView();list.performItemClick(list.getAdapter().getView(0,null,list),0,0);idle();
            ArgumentCaptor<NftProof.Gen> gen=ArgumentCaptor.forClass(NftProof.Gen.class);proofs.verify(()->NftProof.generate(eq(node),eq("0xabc123"),eq("0x30786F776E6572"),any(java.util.function.BooleanSupplier.class),gen.capture()));
            gen.getValue().fail("You don't hold a spendable coin of this asset.");idle();assertEquals(Language.msg(l10n_salon_holdings_prove_failed,Language.msg(l10n_salon_holdings_no_spendable)).toString(),ShadowToast.getTextOfLatestToast());assertEquals(before,item.toString());
        }
    }
    @Test public void emptyPickerAndDisconnectedNodeTranslateWithoutIssuingCommands()throws Exception{
        for(String code:CODES){Language.select(activity,code);call("showAssetPicker",new Class<?>[]{List.class},Collections.emptyList());assertEquals(Language.msg(l10n_salon_holdings_empty).toString(),ShadowToast.getTextOfLatestToast());call("pickNftDialog",new Class<?>[]{});assertEquals(Language.msg(l10n_salon_holdings_connect_node).toString(),ShadowToast.getTextOfLatestToast());}
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void balanceFailureUsesLocalizedDiagnosticAndDoesNotOpenPicker()throws Exception{
        set("nodeUp",true);List<NodeApi.Cb> replies=new ArrayList<>();doAnswer(c->{replies.add(c.getArgument(1));return null;}).when(node).cmd(eq("balance"),any(NodeApi.Cb.class));
        for(String code:CODES){Language.select(activity,code);call("pickNftDialog",new Class<?>[]{});assertEquals(Language.msg(l10n_salon_holdings_reading).toString(),ShadowToast.getTextOfLatestToast());replies.get(replies.size()-1).onError("Invalid node result. Outcome remains unknown.");idle();assertEquals(Language.msg(l10n_salon_holdings_balance_failed,Language.msg(l10n_vestr_transport_invalid)).toString(),ShadowToast.getTextOfLatestToast());}
    }
    @Test public void simultaneousBadgesShareRetryAndIgnoreTheSupersededReply()throws Exception{
        set("nodeUp",true);JSONObject item=holding();TextView a=new TextView(activity),b=new TextView(activity);body.addView(a);body.addView(b);List<NftProof.Verify> replies=new ArrayList<>();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.verify(eq(node),any(JSONObject.class),eq("remote"),any(NftProof.Verify.class))).thenAnswer(c->{replies.add(c.getArgument(3));return null;});
            call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote",a);call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote",b);assertEquals(1,replies.size());
            call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class,boolean.class},item,"remote",a,true);assertEquals(2,replies.size());replies.get(1).result(false,"incomplete proof","");replies.get(0).result(true,"verified holding","");idle();
            for(String code:CODES){Language.select(activity,code);assertEquals("✕ "+Language.msg(l10n_salon_holdings_incomplete),a.getText().toString());assertEquals(a.getText().toString(),b.getText().toString());}
            assertNull(a.getTag());assertNull(b.getTag());TextView cached=new TextView(activity);call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote",cached);assertEquals(a.getText().toString(),cached.getText().toString());assertEquals(2,replies.size());
        }
    }
    @Test public void changedProofIdentityOrScreenCannotReceiveAnOldBadge()throws Exception{
        set("nodeUp",true);List<NftProof.Verify> replies=new ArrayList<>();List<JSONObject> snapshots=new ArrayList<>();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.verify(eq(node),any(JSONObject.class),eq("remote"),any(NftProof.Verify.class))).thenAnswer(c->{snapshots.add(c.getArgument(1));replies.add(c.getArgument(3));return null;});
            JSONObject item=holding();TextView view=new TextView(activity);call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote",view);item.put("sig","new-signature");assertFalse(snapshots.get(0).optString("sig").equals("new-signature"));replies.get(0).result(true,"verified holding","");assertEquals(Language.msg(l10n_salon_holdings_verifying).toString(),view.getText().toString());
            call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote",view);assertEquals(2,replies.size());SalonStore.put(activity,"tokenid","0xchanged");replies.get(1).result(true,"verified holding","");assertEquals(Language.msg(l10n_salon_holdings_verifying).toString(),view.getText().toString());
            call("verifyInto",new Class<?>[]{JSONObject.class,String.class,TextView.class},item,"remote",view);assertEquals(3,replies.size());set("renderEpoch",1);replies.get(2).result(true,"verified holding","");assertEquals(Language.msg(l10n_salon_holdings_verifying).toString(),view.getText().toString());assertNull(view.getTag());
        }
    }
    @Test public void automaticRefreshCannotWriteIntoAChangedIdentityAfterEditionLookup()throws Exception{
        set("nodeUp",true);JSONObject item=holding();SalonStore.setArr(activity,"nfts",new JSONArray().put(item));String before=SalonStore.arr(activity,"nfts").toString();List<NftProof.Gen> generated=new ArrayList<>();List<java.util.function.BooleanSupplier> guards=new ArrayList<>();List<NodeApi.Cb> replies=new ArrayList<>();
        doAnswer(c->{replies.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.generate(eq(node),anyString(),anyString(),any(java.util.function.BooleanSupplier.class),any(NftProof.Gen.class))).thenAnswer(c->{guards.add(c.getArgument(3));generated.add(c.getArgument(4));return null;});
            call("maybeRefreshProof",new Class<?>[]{JSONObject.class,String.class},item,"0x30786F776E6572");assertEquals(1,generated.size());assertTrue(guards.get(0).getAsBoolean());generated.get(0).ok(new JSONObject(item.toString()).put("coinproof","new-proof"));assertEquals(1,replies.size());
            SalonStore.put(activity,"tokenid","0xchanged");assertFalse(guards.get(0).getAsBoolean());replies.get(0).onResult(new JSONObject().put("status",true).put("response",new JSONArray()));assertEquals(2,replies.size());replies.get(1).onResult(new JSONObject().put("status",true).put("response",new JSONArray()));idle();assertEquals(before,SalonStore.arr(activity,"nfts").toString());
        }
    }
    @Test public void explicitProofRejectsDuplicateRequestsAndDropsChangedDraftResults()throws Exception{
        JSONObject item=holding();String before=SalonStore.arr(activity,"nfts").toString();List<NftProof.Gen> generated=new ArrayList<>();List<java.util.function.BooleanSupplier> guards=new ArrayList<>();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.generate(eq(node),anyString(),anyString(),any(java.util.function.BooleanSupplier.class),any(NftProof.Gen.class))).thenAnswer(c->{guards.add(c.getArgument(3));generated.add(c.getArgument(4));return null;});
            call("proveNft",new Class<?>[]{JSONObject.class},item);call("proveNft",new Class<?>[]{JSONObject.class},item);assertEquals(1,generated.size());assertEquals(Language.msg(l10n_salon_holdings_request_busy).toString(),ShadowToast.getTextOfLatestToast());assertTrue(guards.get(0).getAsBoolean());
            item.put("name","Changed name");assertFalse(guards.get(0).getAsBoolean());generated.get(0).ok(new JSONObject().put("tokenid","0xabc123"));assertEquals(before,SalonStore.arr(activity,"nfts").toString());assertFalse((Boolean)field("provingHolding").get(activity));verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
        }
    }
    @Test public void unchangedDraftStillSavesProofAcrossAllFourLanguages()throws Exception{
        screen("FEED");JSONObject asset=holding();List<NftProof.Gen> generated=new ArrayList<>();List<java.util.function.BooleanSupplier> guards=new ArrayList<>();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.generate(eq(node),anyString(),anyString(),any(java.util.function.BooleanSupplier.class),any(NftProof.Gen.class))).thenAnswer(c->{guards.add(c.getArgument(3));generated.add(c.getArgument(4));return null;});
            call("proveNft",new Class<?>[]{JSONObject.class},asset);
            for(String code:CODES){Language.select(activity,code);assertTrue(guards.get(0).getAsBoolean());}
            generated.get(0).ok(new JSONObject().put("tokenid",asset.getString("tokenid")).put("coinid","0xnewcoin").put("coinproof","new proof").put("sig","signature"));idle();
            JSONArray items=SalonStore.arr(activity,"nfts");assertEquals(1,items.length());JSONObject saved=items.getJSONObject(0);assertEquals(asset.getString("name"),saved.getString("name"));assertEquals(asset.getString("image"),saved.getString("image"));assertEquals("new proof",saved.getString("coinproof"));assertEquals("signature",saved.getString("sig"));assertFalse((Boolean)field("provingHolding").get(activity));assertEquals(Language.msg(l10n_salon_holdings_added).toString(),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void unchangedAutomaticRefreshReplacesOnlyTheOriginalHolding()throws Exception{
        JSONObject item=holding(),other=new JSONObject().put("tokenid","0xanother").put("name","Keep me");SalonStore.setArr(activity,"nfts",new JSONArray().put(item).put(other));List<NftProof.Gen> generated=new ArrayList<>();
        try(MockedStatic<NftProof> proofs=mockStatic(NftProof.class,CALLS_REAL_METHODS)){
            proofs.when(()->NftProof.generate(eq(node),anyString(),anyString(),any(java.util.function.BooleanSupplier.class),any(NftProof.Gen.class))).thenAnswer(c->{generated.add(c.getArgument(4));return null;});
            call("maybeRefreshProof",new Class<?>[]{JSONObject.class,String.class},item,"0x30786F776E6572");generated.get(0).ok(new JSONObject(item.toString()).put("coinproof","fresh proof"));idle();JSONArray saved=SalonStore.arr(activity,"nfts");assertEquals(2,saved.length());assertEquals("fresh proof",saved.getJSONObject(0).getString("coinproof"));assertEquals(item.getString("name"),saved.getJSONObject(0).getString("name"));assertEquals(other.toString(),saved.getJSONObject(1).toString());
            call("maybeRefreshProof",new Class<?>[]{JSONObject.class,String.class},saved.getJSONObject(0),"0x30786F776E6572");assertEquals(1,generated.size());
        }
    }
}
