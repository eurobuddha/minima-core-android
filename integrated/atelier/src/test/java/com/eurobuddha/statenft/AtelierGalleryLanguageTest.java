package com.eurobuddha.statenft;

import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierGalleryLanguageTest {
    private MainActivity activity;
    private LinearLayout body;
    private MockedStatic<MintEngine> mint;
    private MockedStatic<ImageLoader> images;
    private final List<Read> reads=new ArrayList<>();
    private final List<MintEngine.Done> recoveries=new ArrayList<>();
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private static final class Read {
        String token; boolean owned; MintEngine.CoinsBounded ok; Consumer<String> fail;
    }
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render(String name)throws Exception{call(name,new Class<?>[]{});}
    private void open(StateNft.Meta m)throws Exception{call("openCollection",new Class<?>[]{StateNft.Meta.class},m);}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);field("body").set(activity,body);
        for(String name:new String[]{"appbarBox","navBox"})field(name).set(activity,new LinearLayout(activity));
        field("content").set(activity,new ScrollView(activity));field("node").set(activity,mock(NodeApi.class));
        LocalStore.save(activity,new JSONArray());LocalStore.clearEngineLog(activity);
        images=mockStatic(ImageLoader.class);
        mint=mockStatic(MintEngine.class,invocation->{
            if(invocation.getMethod().getName().equals("tokenCoinsBounded")){
                Read r=new Read();r.token=invocation.getArgument(1);r.owned=invocation.getArgument(2);r.ok=invocation.getArgument(3);r.fail=invocation.getArgument(4);reads.add(r);return null;
            }
            if(invocation.getMethod().getName().equals("recover")){recoveries.add(invocation.getArgument(4));return null;}
            return invocation.callRealMethod();
        });
    }
    @After public void cleanup(){mint.close();images.close();Language.select(activity,"en");}
    private StateNft.Meta meta(long id)throws Exception{
        StateNft.Meta m=new StateNft.Meta();m.localId=id;m.tokenid="0x"+String.format("%064d",id);m.name="Literal artwork 中文 Русский Українська "+id;m.size=2;m.minted=2;m.phase="DONE";
        LocalStore.upsert(activity,MintEngine.rowFromMeta(m,new JSONArray()));return m;
    }
    private JSONArray coins(StateNft.Meta m,int n)throws Exception{
        JSONArray a=new JSONArray();for(int i=0;i<n;i++)a.put(new JSONObject().put("tokenid",m.tokenid).put("coinid",m.tokenid+i).put("tokenamount","1").put("state",new JSONArray()));return a;
    }
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static String upper(int id){return Language.upper(Language.msg(id)).toString();}
    private static void fits(View root){if(root instanceof TextView){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private static void layout(View root){for(int width:new int[]{320,480,900}){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,width,root.getMeasuredHeight());fits(root);}}
    @Test public void galleryCardRetainsArtworkAndBindingsAcrossAllLanguages()throws Exception{
        StateNft.Meta m=meta(1);m.owned=1;
        View card=(View)call("collectionCard",new Class<?>[]{StateNft.Meta.class},m);String stored=LocalStore.load(activity).toString();
        TextView title=find(card,m.name.toUpperCase(Locale.ROOT));assertNotNull(title);
        TextView status=find(card,upper(l10n_atelier_gallery_sealed));assertNotNull(status);
        for(String code:CODES){Language.select(activity,code);Language.apply(card);layout(card);
            assertSame(title,find(card,m.name.toUpperCase(Locale.ROOT)));assertSame(status,find(card,upper(l10n_atelier_gallery_sealed)));
            assertNotNull(find(card,Language.msg(l10n_atelier_gallery_collection_summary,Util.shorten(m.tokenid),2,2,1).toString()));assertEquals(stored,LocalStore.load(activity).toString());}
    }
    @Test public void activeDetailActionsAndPhaseRailTranslateWithoutRebuilding()throws Exception{
        StateNft.Meta m=meta(2);m.created=true;m.phase="STAMP";open(m);render("renderDetail");
        View hero=body.getChildAt(0);TextView recover=find(body,upper(l10n_atelier_gallery_recover));assertNotNull(recover);String stored=LocalStore.load(activity).toString();
        for(String code:CODES){Language.select(activity,code);layout(body);assertSame(hero,body.getChildAt(0));assertSame(recover,find(body,upper(l10n_atelier_gallery_recover)));assertNotNull(find(body,upper(l10n_atelier_gallery_stamp)));assertNotNull(find(body,upper(l10n_atelier_gallery_resume)));assertEquals(stored,LocalStore.load(activity).toString());}
        assertEquals(1,reads.size());assertTrue(recoveries.isEmpty());
    }
    @Test public void nftAndTokenAmountsAndNamesStayLiteral()throws Exception{
        JSONObject token=new JSONObject().put("name",new JSONObject().put("name","Artwork literal 中文").put("ticker","XYZ"));
        JSONObject row=new JSONObject().put("tokenid","0x1234").put("token",token).put("total","123.4500");String before=row.toString();
        View nft=(View)call("nftCard",new Class<?>[]{JSONObject.class},row), fungible=(View)call("tokenRow",new Class<?>[]{JSONObject.class},row);
        for(String code:CODES){Language.select(activity,code);Language.apply(nft);Language.apply(fungible);layout(nft);layout(fungible);assertNotNull(find(nft,"Artwork literal 中文"));assertNotNull(find(fungible,"Artwork literal 中文 · XYZ"));assertNotNull(find(nft,Language.msg(l10n_atelier_gallery_editions_summary,"123.4500","0x1234").toString()));assertNotNull(find(fungible,Language.msg(l10n_atelier_gallery_supply_summary,"123.4500","0x1234").toString()));assertEquals(before,row.toString());}
    }
    @Test public void knownStatesTranslateAndUnknownStatesStayLiteral()throws Exception{
        String[] states={"PENDING","POSTED","DONE","FAIL"};int[] ids={l10n_atelier_gallery_pending,l10n_atelier_gallery_posted,l10n_atelier_gallery_done,l10n_atelier_gallery_failed};
        List<CharSequence> labels=new ArrayList<>();for(String st:states)labels.add((CharSequence)call("dropStatus",new Class<?>[]{String.class},st));
        for(String code:CODES){Language.select(activity,code);for(int i=0;i<states.length;i++)assertEquals(Language.text(activity,ids[i]),labels.get(i).toString());assertEquals("FUTURE_STATE",call("dropStatus",new Class<?>[]{String.class},"FUTURE_STATE"));assertEquals("FUTURE_PHASE",call("statusWord",new Class<?>[]{String.class},"FUTURE_PHASE"));}
    }
    @Test public void oldOwnedReplyCannotChangeAnotherCollectionOrStartItsSecondRead()throws Exception{
        StateNft.Meta a=meta(10),b=meta(11);open(a);Read old=reads.get(0);open(b);String store=LocalStore.load(activity).toString();View reading=body.getChildAt(0);
        old.ok.ok(coins(a,3),false);old.fail.accept("late failure");assertSame(b,field("openMeta").get(activity));assertEquals(2,reads.size());assertEquals(0,((JSONArray)field("openOwned").get(activity)).length());assertEquals(store,LocalStore.load(activity).toString());assertSame(reading,body.getChildAt(0));
    }
    @Test public void overlappingRefreshesCommitOnlyTheLatestPair()throws Exception{
        StateNft.Meta a=meta(12);open(a);reads.get(0).ok.ok(coins(a,1),false);Read oldAll=reads.get(1);assertFalse(oldAll.owned);
        assertEquals(0,((JSONArray)field("openOwned").get(activity)).length());render("refreshDetail");reads.get(2).ok.ok(coins(a,2),false);Read currentAll=reads.get(3);
        currentAll.ok.ok(coins(a,4),false);String stored=LocalStore.load(activity).toString();View hero=body.getChildAt(0);
        oldAll.ok.ok(coins(a,7),true);oldAll.fail.accept("late error");assertEquals(2,((JSONArray)field("openOwned").get(activity)).length());assertEquals(4,((JSONArray)field("openAll").get(activity)).length());assertFalse(field("detailPartial").getBoolean(activity));assertSame(hero,body.getChildAt(0));assertEquals(stored,LocalStore.load(activity).toString());
    }
    @Test public void sameCollectionReopenedRejectsThePreviousVisit()throws Exception{
        StateNft.Meta a=meta(13);open(a);reads.get(0).ok.ok(coins(a,1),false);Read old=reads.get(1);open(a);View reading=body.getChildAt(0);old.ok.ok(coins(a,3),true);old.fail.accept("late error");assertSame(reading,body.getChildAt(0));assertEquals(0,((JSONArray)field("openOwned").get(activity)).length());assertFalse(field("detailPartial").getBoolean(activity));
    }
    @Test public void allCoinsFailureShowsOwnedCoinsAsPartialInEveryLanguage()throws Exception{
        StateNft.Meta a=meta(14);open(a);reads.get(0).ok.ok(coins(a,1),false);reads.get(1).fail.accept("unavailable");assertTrue(field("detailPartial").getBoolean(activity));assertEquals(1,((JSONArray)field("openAll").get(activity)).length());
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.text(activity,l10n_atelier_gallery_partial)));}
        StateNft.Meta pending=meta(15);pending.tokenid="";open(pending);assertFalse(field("detailPartial").getBoolean(activity));assertNull(find(body,Language.text(activity,l10n_atelier_gallery_partial)));
    }
    @Test public void selfRecoveryCannotReturnToAnOldCollection()throws Exception{
        StateNft.Meta a=meta(16);a.created=true;a.minted=0;open(a);render("renderDetail");assertEquals(1,recoveries.size());StateNft.Meta b=meta(17);open(b);View reading=body.getChildAt(0);recoveries.get(0).done("done");assertSame(b,field("openMeta").get(activity));assertSame(reading,body.getChildAt(0));assertEquals(2,reads.size());
    }
    @Test public void manualRecoveryCannotReturnToAnOldCollection()throws Exception{
        StateNft.Meta a=meta(18);a.created=true;a.phase="STAMP";open(a);render("renderDetail");find(body,upper(l10n_atelier_gallery_recover)).performClick();assertEquals(1,recoveries.size());StateNft.Meta b=meta(19);open(b);View reading=body.getChildAt(0);recoveries.get(0).done("done");assertSame(b,field("openMeta").get(activity));assertSame(reading,body.getChildAt(0));assertEquals(2,reads.size());
    }
    @Test public void leavingOrFinishingStopsPendingDetailUpdates()throws Exception{
        StateNft.Meta a=meta(20);open(a);Read old=reads.get(0);render("renderManage");View other=body.getChildAt(0);old.ok.ok(coins(a,3),false);old.fail.accept("error");assertSame(other,body.getChildAt(0));assertEquals(1,reads.size());
        open(a);Read last=reads.get(1);View reading=body.getChildAt(0);activity.finish();last.ok.ok(coins(a,2),false);last.fail.accept("error");assertSame(reading,body.getChildAt(0));assertEquals(2,reads.size());
    }
    @Test public void periodicRefreshCannotStarveAnInFlightCollectionRead()throws Exception{
        StateNft.Meta a=meta(21);a.created=true;a.phase="STAMP";open(a);
        try(MockedStatic<MintService> service=mockStatic(MintService.class)){
            ((Runnable)field("mintLoop").get(activity)).run();
            assertEquals(1,reads.size());assertSame(a,field("openMeta").get(activity));
            reads.get(0).ok.ok(coins(a,1),false);reads.get(1).ok.ok(coins(a,2),false);
            assertFalse(field("detailLoading").getBoolean(activity));
            ((Runnable)field("mintLoop").get(activity)).run();assertEquals(3,reads.size());
        }
        ((android.os.Handler)field("main").get(activity)).removeCallbacksAndMessages(null);
    }
    @Test public void incompleteOrFailedReadDoesNotAutomaticallyRecoverAMint()throws Exception{
        StateNft.Meta a=meta(22);a.created=true;a.minted=0;open(a);
        reads.get(0).ok.ok(coins(a,1),false);reads.get(1).ok.ok(new JSONArray(),true);
        assertTrue(recoveries.isEmpty());assertTrue(field("detailPartial").getBoolean(activity));
        open(a);reads.get(2).fail.accept("offline");assertTrue(recoveries.isEmpty());assertFalse(field("detailLoading").getBoolean(activity));assertTrue(field("detailPartial").getBoolean(activity));
    }

    @Test public void airdropPanelRetainsProtocolRecordsAndTranslatesTheirPresentation()throws Exception{
        StateNft.Meta m=meta(30);JSONArray entries=new JSONArray();String[] statuses={"PENDING","POSTED","DONE","FAIL","FUTURE_STATE"};
        for(int i=0;i<statuses.length;i++)entries.put(new JSONObject().put("idx",i+1).put("status",statuses[i]).put("addr","MxLiteralDestination"+i).put("error",i==3?"literal backend diagnostic 中文":""));
        JSONObject job=new JSONObject().put("localid",m.localId).put("entries",entries);LocalStore.saveDraft(activity,"airdrop_"+m.localId,job);String stored=AirdropEngine.job(activity,m.localId).toString();
        open(m);render("renderDetail");View hero=body.getChildAt(0);TextView abandon=find(body,upper(l10n_atelier_gallery_abandon));assertNotNull(abandon);
        for(String code:CODES){Language.select(activity,code);layout(body);assertSame(hero,body.getChildAt(0));assertSame(abandon,find(body,upper(l10n_atelier_gallery_abandon)));assertNotNull(find(body,Language.msg(l10n_atelier_gallery_delivered,0,5).toString()));assertNotNull(find(body,Language.text(activity,l10n_atelier_gallery_failed)+" — literal backend diagnostic 中文"));assertNotNull(find(body,Language.msg(l10n_atelier_delivery_unverified)+" → "+Util.shorten("MxLiteralDestination4")));assertEquals(stored,AirdropEngine.job(activity,m.localId).toString());}
        AirdropEngine.clearJob(activity,m.localId);
    }
    @Test public void pendingTokenSummaryRetainsNestedTranslation()throws Exception{
        StateNft.Meta m=meta(31);m.tokenid="";m.size=0;m.minted=0;View card=(View)call("collectionCard",new Class<?>[]{StateNft.Meta.class},m);
        for(String code:CODES){Language.select(activity,code);Language.apply(card);layout(card);assertNotNull(find(card,Language.msg(l10n_atelier_gallery_collection_summary,Language.msg(l10n_atelier_gallery_token_pending),0,0,0).toString()));assertNotNull(find(card,upper(l10n_atelier_gallery_lot_pending)));}
    }

    @Test public void galleryClassifiesSerializedNftMetadataAndRetainsRawRows()throws Exception{
        NodeApi node=(NodeApi)field("node").get(activity);List<NodeApi.Cb> balance=new ArrayList<>();when(node.isEnabled()).thenReturn(true);
        doAnswer(call->{balance.add(call.getArgument(1));return null;}).when(node).cmd(eq("balance"),any(NodeApi.Cb.class));
        JSONObject metadata=new JSONObject().put("name","Literal NFT 中文").put("nft",true).put("url","https://example.invalid/art.png");
        for(Object raw:new Object[]{metadata,metadata.toString(),new JSONObject().put("name",metadata.toString()).toString(),new JSONObject().put("token",metadata.toString())}){
            JSONObject row=new JSONObject().put("tokenid","0x1234").put("token",raw).put("total","1");String before=row.toString();
            render("galleryScan");balance.get(balance.size()-1).onResult(new JSONObject().put("status",true).put("response",new JSONArray().put(row)));
            List<?> nfts=(List<?>)field("nftRows").get(activity),tokens=(List<?>)field("tokenRows").get(activity);assertEquals(1,nfts.size());assertSame(row,nfts.get(0));assertTrue(tokens.isEmpty());assertEquals(before,row.toString());
        }
    }

    private JSONObject receiptJob()throws Exception {
        JSONObject job=AtelierDeliveryTest.newJob(1),e=job.getJSONArray("entries").getJSONObject(0);
        JSONObject receipt=BurialEvidence.receipt(BurialEvidenceTest.posted(BurialEvidenceTest.coin(1)),e.getJSONObject("intent"));
        receipt.put("destination",StateNft.GRAVEYARD);e.put("receipt",receipt).put("status","UNKNOWN").put("checks",20).put("errorKey","unknown");return job;
    }
    @Test public void receiptRecoveryPanelRetainsBindingsAndOnlyRearmsChecks()throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        StateNft.Meta m=meta(41);m.tokenid=BurialEvidenceTest.TOKEN;
        AirdropEngine.clearJob(activity,41);assertTrue(AirdropEngine.saveJob(activity,receiptJob()));
        String saved=AirdropEngine.job(activity,41).toString();open(m);render("renderDetail");TextView check=find(body,upper(l10n_atelier_delivery_check));assertNotNull(check);View hero=body.getChildAt(0);
        for(String code:CODES){Language.select(activity,code);Language.apply(body);layout(body);assertSame(hero,body.getChildAt(0));assertSame(check,find(body,upper(l10n_atelier_delivery_check)));assertNotNull(find(body,Language.msg(l10n_atelier_delivery_help).toString()));assertEquals(saved,AirdropEngine.job(activity,41).toString());}
        try(MockedStatic<MintService> service=mockStatic(MintService.class)){
            check.performClick();JSONObject entry=AirdropEngine.job(activity,41).getJSONArray("entries").getJSONObject(0);assertEquals("POSTED",entry.getString("status"));assertEquals(0,entry.getInt("checks"));assertEquals(receiptJob().getJSONArray("entries").getJSONObject(0).getJSONObject("receipt").toString(),entry.getJSONObject("receipt").toString());service.verify(()->MintService.kick(activity));
        }
        AirdropEngine.clearJob(activity,41);
    }
    @Test public void staleRecoveryAndClearControlsCannotChangeAReplacementJob()throws Exception {
        StateNft.Meta m=meta(41);m.tokenid=BurialEvidenceTest.TOKEN;AirdropEngine.clearJob(activity,41);assertTrue(AirdropEngine.saveJob(activity,receiptJob()));open(m);render("renderDetail");
        TextView check=find(body,upper(l10n_atelier_delivery_check)),clear=find(body,upper(l10n_atelier_gallery_abandon));assertNotNull(check);assertNotNull(clear);
        AirdropEngine.clearJob(activity,41);assertTrue(AirdropEngine.saveJob(activity,AtelierDeliveryTest.newJob(1)));String replacement=AirdropEngine.job(activity,41).toString();
        try(MockedStatic<MintService> service=mockStatic(MintService.class)){check.performClick();clear.performClick();assertEquals(replacement,AirdropEngine.job(activity,41).toString());service.verifyNoInteractions();}
        AirdropEngine.clearJob(activity,41);
    }

}
