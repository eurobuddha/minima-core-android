package com.eurobuddha.statenft;
import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.time.Duration;
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
import static com.eurobuddha.statenft.BurialEvidenceTest.*;
import static com.eurobuddha.statenft.AtelierBurialEvidenceTest.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierBurialFlowTest {
    private MainActivity activity;private LinearLayout body;private NodeApi node;private StateNft.Meta meta;private MockedStatic<MintEngine> mint;
    private final Map<String,ArrayDeque<NodeApi.Cb>> reads=new HashMap<>();private final List<List<String>> posts=new ArrayList<>();
    private Consumer<JSONObject> accepted;private Consumer<String> failed;private boolean canPause=true;
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void render()throws Exception{Method m=MainActivity.class.getDeclaredMethod("renderBury");m.setAccessible(true);m.invoke(activity);}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);field("body").set(activity,body);
        for(String n:new String[]{"appbarBox","navBox"})field(n).set(activity,new LinearLayout(activity));field("content").set(activity,new ScrollView(activity));node=mock(NodeApi.class);field("node").set(activity,node);
        doAnswer(i->{reads.computeIfAbsent(i.getArgument(0),k->new ArrayDeque<>()).add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        meta=new StateNft.Meta();meta.localId=41;meta.tokenid=TOKEN;meta.name="Literal 中文 Русский Українська";meta.phase="DONE";meta.creatorPk=ADDRESS;meta.owned=2;field("openMeta").set(activity,meta);
        activity.getSharedPreferences("statenft_suite",0).edit().clear().commit();LocalStore.save(activity,new JSONArray().put(object("id",41,"tokenid",TOKEN,"name",meta.name,"phase","DONE")));AirdropEngine.clearJob(activity,41);
        mint=mockStatic(MintEngine.class);mint.when(()->MintEngine.pauseForBurial(activity,TOKEN)).thenAnswer(i->canPause&&LocalStore.pauseForBurial(activity,TOKEN));
        mint.when(()->MintEngine.postTxnWithReceipt(eq(node),anyString(),anyList(),any(),any())).thenAnswer(i->{assertTrue("Intent persisted before a post",LocalStore.burialJournal(activity,TOKEN).length()>0);posts.add(i.getArgument(2));accepted=i.getArgument(3);failed=i.getArgument(4);return null;});render();
    }
    @After public void cleanup()throws Exception{mint.close();((android.os.Handler)field("main").get(activity)).removeCallbacksAndMessages(null);Language.select(activity,"en");LocalStore.save(activity,new JSONArray());activity.getSharedPreferences("statenft_suite",0).edit().clear().commit();}
    private static TextView find(View v,String value){if(v instanceof TextView&&value.equals(((TextView)v).getText().toString()))return(TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static EditText input(View v){if(v instanceof EditText)return(EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText t=input(((ViewGroup)v).getChildAt(i));if(t!=null)return t;}return null;}
    private TextView button(){TextView t=find(body,Language.upper(Language.msg(l10n_atelier_burial_submit)).toString());if(t==null)t=find(body,Language.upper(Language.msg(l10n_nft_burial_check_retry)).toString());assertNotNull(t);return t;}
    private void submit(){input(body).setText(meta.name);button().performClick();}
    private String owned(){return "coins relevant:true own:true tokenid:"+TOKEN;}
    private void answer(String command,JSONObject reply){ArrayDeque<NodeApi.Cb> q=reads.get(command);assertNotNull("No read: "+command,q);assertFalse(command,q.isEmpty());q.remove().onResult(reply);}
    private void owns(JSONObject...coins){JSONArray a=new JSONArray();for(JSONObject c:coins)a.put(c);answer(owned(),list(a));}
    private String phase(){return LocalStore.findById(activity,41).optString("phase");}
    private void poll(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));}
    private void prove(JSONObject coin,boolean stripped)throws Exception{JSONObject out=stripped?strippedOutput(coin):output(coin);answer("coins coinid:"+out.getString("coinid"),list(new JSONArray().put(out)));}
    private static void fits(View v){if(v instanceof TextView){TextView t=(TextView)v;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)fits(((ViewGroup)v).getChildAt(i));}
    @Test public void fourLanguagesPreserveLiteralNameDraftCursorAndControls(){EditText confirm=input(body);confirm.setText(meta.name);confirm.setSelection(4);TextView go=button();View first=body.getChildAt(0);for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);Language.apply(body);assertSame(confirm,input(body));assertSame(go,button());assertSame(first,body.getChildAt(0));assertEquals(meta.name,confirm.getText().toString());assertEquals(4,confirm.getSelectionStart());assertNotNull(find(body,Language.msg(l10n_atelier_burial_custody).toString()));for(int w:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,w,body.getMeasuredHeight());fits(body);}}assertTrue(posts.isEmpty());assertTrue(reads.isEmpty());}
    @Test public void exactNameBusyMintAndAirdropGuardPreventCommands()throws Exception{input(body).setText("Wrong");button().performClick();assertTrue(reads.isEmpty());canPause=false;submit();assertTrue(reads.isEmpty());assertEquals("DONE",phase());canPause=true;render();AirdropEngine.saveJob(activity,object("localid",41,"tokenid",TOKEN,"entries",new JSONArray()));submit();assertTrue(reads.isEmpty());assertEquals("DONE",phase());}
    @Test public void malformedOrUnknownCoinListCannotStartBurial()throws Exception{for(JSONObject bad:new JSONObject[]{object("status",true),object("status",false,"response",new JSONArray()),list(new JSONArray().put(JSONObject.NULL)),list(new JSONArray().put(object())),list(new JSONArray().put(coin(1)).put(coin(1)))}){render();submit();answer(owned(),bad);assertTrue(posts.isEmpty());assertEquals("BURY",phase());assertEquals(0,LocalStore.burialJournal(activity,TOKEN).length());}}
    @Test public void emptyWalletWithoutReceiptsCannotMarkCollectionBuried(){submit();owns();assertTrue(posts.isEmpty());assertEquals("BURY",phase());assertNotNull(find(body,Language.msg(l10n_nft_actions_no_coins).toString()));}
    @Test public void repeatTapCannotPostTwiceAndEveryIntentIsDurable()throws Exception{submit();button().performClick();assertEquals(1,reads.get(owned()).size());owns(coin(1),coin(2));assertEquals(2,LocalStore.burialJournal(activity,TOKEN).length());assertEquals(1,posts.size());accepted.accept(posted(coin(1)));assertEquals(2,posts.size());assertFalse(LocalStore.burialJournal(activity,TOKEN).getJSONObject(0).getString("output").isEmpty());assertEquals("",LocalStore.burialJournal(activity,TOKEN).getJSONObject(1).getString("output"));failed.accept("timeout");assertEquals("BURY",phase());assertFalse(button().isEnabled());}
    @Test public void unknownPostAndInputAbsenceCannotBecomeSuccess()throws Exception{submit();owns(coin(1));accepted.accept(object("status",true));assertEquals("",LocalStore.burialJournal(activity,TOKEN).getJSONObject(0).getString("output"));render();submit();owns();assertEquals("BURY",phase());assertEquals(1,posts.size());assertNotNull(find(body,Language.msg(l10n_nft_burial_evidence_missing).toString()));}
    @Test public void exactOutputsEmptyWalletAndValidHeightAreAllRequired()throws Exception{submit();owns(coin(1));accepted.accept(posted(coin(1)));poll();answer("coins coinid:"+output(coin(1)).getString("coinid"),list(new JSONArray()));assertEquals("BURY",phase());poll();prove(coin(1),false);owns(coin(1));assertEquals("BURY",phase());poll();prove(coin(1),false);owns();answer("block",object("status",false,"response",object("block",200)));assertEquals("BURY",phase());poll();prove(coin(1),false);owns();answer("block",object("status",true,"response",object("block",200)));assertEquals("BURIED",phase());assertEquals(200,LocalStore.findById(activity,41).getInt("buriedat"));assertEquals(0,LocalStore.burialJournal(activity,TOKEN).length());for(String code:new String[]{"ru","uk","zh-Hans","en"}){Language.select(activity,code);assertNotNull(find(body,Language.msg(l10n_nft_burial_confirmed).toString()));}}
    @Test public void savedReceiptResumesWithoutSigningSameCoinAgain()throws Exception{submit();owns(coin(1));accepted.accept(posted(coin(1)));render();submit();owns(coin(1));assertEquals(1,posts.size());poll();prove(coin(1),false);owns();answer("block",object("status",true,"response",object("block",200)));assertEquals("BURIED",phase());}
    @Test public void acceptedLateReceiptIsSavedButDoesNotContinueAfterNavigation()throws Exception{submit();owns(coin(1),coin(2));Consumer<JSONObject> old=accepted;render();old.accept(posted(coin(1)));assertEquals(1,posts.size());assertFalse(LocalStore.burialJournal(activity,TOKEN).getJSONObject(0).getString("output").isEmpty());assertNotNull(find(body,Language.msg(l10n_nft_burial_paused).toString()));}
    @Test public void staleControlsAndReadCallbacksCannotPost()throws Exception{input(body).setText(meta.name);TextView old=button();render();old.performClick();assertTrue(reads.isEmpty());submit();render();owns(coin(1));assertTrue(posts.isEmpty());activity.finish();submit();assertTrue(posts.isEmpty());}
    @Test public void oversizedFallbackPersistsStrippingIntentBeforeItsPost()throws Exception{submit();owns(coin(1));failed.accept("size too large");assertEquals(2,posts.size());JSONObject intent=LocalStore.burialJournal(activity,TOKEN).getJSONObject(0);assertFalse(BurialEvidence.preserves(intent));assertTrue(posts.get(1).stream().anyMatch(s->s.contains("storestate:false")));assertTrue(posts.get(1).stream().anyMatch(s->s.endsWith("publickey:"+ADDRESS)));accepted.accept(strippedPost(coin(1)));poll();prove(coin(1),true);owns();answer("block",object("status",true,"response",object("block",200)));assertEquals("BURIED",phase());}
    @Test public void fallbackWithoutCreatorKeyCannotPostAnotherTransaction(){meta.creatorPk="";submit();owns(coin(1));failed.accept("size too large");assertEquals(1,posts.size());assertEquals("BURY",phase());}
    @Test public void alteredJournalAndNavigationCannotCompleteOldConfirmation()throws Exception{submit();owns(coin(1));accepted.accept(posted(coin(1)));poll();prove(coin(1),false);JSONArray j=LocalStore.burialJournal(activity,TOKEN);j.put(BurialEvidence.intent(coin(2),TOKEN));LocalStore.saveBurialJournal(activity,TOKEN,j);owns();assertEquals("BURY",phase());assertFalse(reads.containsKey("block"));render();submit();owns();assertEquals("BURY",phase());}
}
