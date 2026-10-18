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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierTransferTest {
    static final String DEST="0x"+"34".repeat(32), MX="Mx"+"Ab12".repeat(12);
    private MainActivity activity;
    private LinearLayout body;
    private NodeApi node;
    private StateNft.Meta meta;
    private StateNft.Item item;
    private MockedStatic<MintEngine> mint;
    private Consumer<JSONObject> posted;
    private Consumer<String> failed;
    private NodeApi.Cb callback;
    private final List<String> queries=new ArrayList<>();
    private List<String> commands;
    private int posts;
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render()throws Exception{call("renderTransfer",new Class<?>[]{StateNft.Item.class},item);}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);field("body").set(activity,body);
        for(String name:new String[]{"appbarBox","navBox"})field(name).set(activity,new LinearLayout(activity));
        field("content").set(activity,new ScrollView(activity));node=mock(NodeApi.class);field("node").set(activity,node);
        doAnswer(i->{queries.add(i.getArgument(0));callback=i.getArgument(1);return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        meta=new StateNft.Meta();meta.localId=41;meta.tokenid=TOKEN;meta.name="Literal 中文 Русский Українська";meta.phase="DONE";
        field("openMeta").set(activity,meta);LocalStore.save(activity,new JSONArray());
        item=new StateNft.Item();item.index=7;item.owned=true;item.coin=coin(1);item.imageUrl="";
        mint=mockStatic(MintEngine.class);
        mint.when(()->MintEngine.postTxnWithReceipt(eq(node),anyString(),anyList(),any(),any())).thenAnswer(i->{posts++;commands=i.getArgument(2);posted=i.getArgument(3);failed=i.getArgument(4);return null;});
        render();
    }
    @After public void cleanup()throws Exception{mint.close();((android.os.Handler)field("main").get(activity)).removeCallbacksAndMessages(null);Language.select(activity,"en");LocalStore.save(activity,new JSONArray());}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static EditText input(View root){if(root instanceof EditText)return(EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText t=input(((ViewGroup)root).getChildAt(i));if(t!=null)return t;}return null;}
    private TextView button(){TextView t=find(body,Language.upper(Language.msg(l10n_atelier_transfer_submit)).toString());assertNotNull(t);return t;}
    private void submit(){input(body).setText(DEST);button().performClick();}
    private JSONObject receiptReply()throws Exception{JSONObject r=BurialEvidenceTest.posted(item.coin);txn(r).getJSONArray("outputs").getJSONObject(0).put("address",DEST).put("miniaddress",MX);return r;}
    private JSONObject chain()throws Exception{return output(item.coin).put("address",DEST);}
    private void poll(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));}
    private List<?> watchers()throws Exception{return(List<?>)field("departureWatchers").get(activity);}
    private static void fits(View v){if(v instanceof TextView){TextView t=(TextView)v;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)fits(((ViewGroup)v).getChildAt(i));}
    @Test public void fourLanguagesRetainRecipientCursorArtworkAndControls()throws Exception{
        EditText to=input(body);to.setText(MX);to.setSelection(9);TextView go=button();View frame=body.getChildAt(0);String before=item.coin.toString();
        for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);Language.apply(body);assertSame(to,input(body));assertSame(go,button());assertSame(frame,body.getChildAt(0));assertEquals(MX,to.getText().toString());assertEquals(9,to.getSelectionStart());assertEquals(before,item.coin.toString());assertNotNull(find(body,Language.msg(l10n_atelier_transfer_help).toString()));
            for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}
        assertEquals(0,posts);verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void repeatedClickAndLanguageSwitchCannotSubmitTwice()throws Exception{submit();button().performClick();assertEquals(1,posts);assertFalse(input(body).isEnabled());for(String c:new String[]{"ru","uk","zh-Hans","en"}){Language.select(activity,c);assertNotNull(find(body,Language.msg(l10n_atelier_transfer_building).toString()));button().performClick();}assertEquals(1,posts);}
    @Test public void staleScreenOrFinishingHostCannotSubmit()throws Exception{EditText to=input(body);to.setText(DEST);TextView old=button();render();old.performClick();assertEquals(0,posts);activity.finish();submit();assertEquals(0,posts);}
    @Test public void unsafeCoinOrUnfinishedMintCannotDispatch()throws Exception{
        for(JSONObject bad:new JSONObject[]{copy(coin(1)).put("coinid","0x12;send"),copy(coin(1)).put("tokenid","0xff"),copy(coin(1)).put("state",object("0","0")),copy(coin(1)).put("state",object("0","7 burn:9")),copy(coin(1)).put("state",new JSONArray().put(object("port",0,"data","7")).put(object("port","00","data","7"))),copy(coin(1)).put("tokenamount","-1")}){item.coin=bad;render();submit();assertEquals(0,posts);}
        item.coin=coin(1);for(String phase:new String[]{"MOVE","STAMP","NEEDIMAGES","BURY","UNKNOWN"}){LocalStore.save(activity,new JSONArray().put(object("id",41,"tokenid",TOKEN,"phase",phase)));render();submit();assertEquals(0,posts);}
        LocalStore.save(activity,new JSONArray());item.owned=false;render();submit();assertEquals(0,posts);
    }
    @Test public void malformedStoredRowsFailClosedWithoutRewritingData()throws Exception{
        for(String raw:new String[]{"broken","{}","[null]"}){activity.getSharedPreferences("statenft_suite",0).edit().putString("collections",raw).commit();render();submit();assertEquals(0,posts);assertEquals(raw,activity.getSharedPreferences("statenft_suite",0).getString("collections",""));}
    }
    @Test public void amountAndObjectStateAreReplayedVerbatim()throws Exception{item.coin.put("tokenamount","2.500");render();String before=item.coin.toString();submit();assertTrue(commands.stream().anyMatch(c->c.contains("amount:2.500 address:"+DEST)));assertTrue(commands.stream().anyMatch(c->c.endsWith("port:0 value:7")));assertTrue(commands.stream().anyMatch(c->c.endsWith("port:1 value:[QUJDRA==]")));assertEquals(before,item.coin.toString());}
    @Test public void wrongRecipientOrAcknowledgementCannotStartConfirmation()throws Exception{
        submit();posted.accept(object("status",true));assertTrue(watchers().isEmpty());assertNotNull(find(body,Language.msg(l10n_nft_actions_output_unverified).toString()));
        render();submit();posted.accept(BurialEvidenceTest.posted(item.coin));assertTrue(watchers().isEmpty());assertEquals(2,posts);
    }
    @Test public void latePostReplyCannotStartWatcherAfterNavigation()throws Exception{submit();Consumer<JSONObject> old=posted;render();old.accept(receiptReply());assertTrue(watchers().isEmpty());assertNotNull(find(body,Language.msg(l10n_atelier_transfer_ready).toString()));verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));}
    @Test public void incompleteEvidenceStaysPendingAndOnlyIntendedOutputConfirms()throws Exception{
        JSONObject receipt=BurialEvidence.receipt(receiptReply(),BurialEvidence.intent(item.coin,TOKEN),DEST);int[] result={0,0};
        call("watchDeparture",new Class<?>[]{String.class,JSONObject.class,long.class,Runnable.class,Runnable.class},TOKEN,receipt,field("screenGeneration").getLong(activity),(Runnable)()->result[0]++,(Runnable)()->result[1]++);
        for(JSONObject bad:new JSONObject[]{object("status",true),list(new JSONArray()),object("status",false,"response",new JSONArray()),list(new JSONArray().put(JSONObject.NULL)),list(new JSONArray().put(chain().put("spent",true))),list(new JSONArray().put(chain().put("state",object("0","99")))),list(new JSONArray().put(chain().put("address",ADDRESS)))}){poll();assertEquals("coins coinid:"+receipt.getString("output"),queries.get(queries.size()-1));callback.onResult(bad);assertEquals(0,result[0]);assertEquals(1,watchers().size());}
        poll();callback.onResult(list(new JSONArray().put(chain())));assertArrayEquals(new int[]{1,0},result);assertTrue(watchers().isEmpty());
    }
    @Test public void errorsRemainUnconfirmedAndPollingStopsAtBound()throws Exception{submit();posted.accept(receiptReply());for(int i=0;i<20;i++){poll();callback.onError("Unavailable");}assertTrue(watchers().isEmpty());assertEquals(20,queries.size());for(String c:new String[]{"ru","uk","zh-Hans","en"}){Language.select(activity,c);assertNotNull(find(body,Language.msg(l10n_atelier_transfer_unconfirmed).toString()));}assertFalse(button().isEnabled());}
    @Test public void navigationCancelsInFlightReplyAndPostErrorCannotResubmit()throws Exception{submit();posted.accept(receiptReply());poll();NodeApi.Cb old=callback;render();old.onResult(list(new JSONArray().put(chain())));assertTrue(watchers().isEmpty());assertNotNull(find(body,Language.msg(l10n_atelier_transfer_ready).toString()));submit();failed.accept("timeout");button().performClick();assertEquals(2,posts);for(String c:new String[]{"ru","uk","zh-Hans","en"}){Language.select(activity,c);assertNotNull(find(body,Language.msg(l10n_atelier_transfer_failed,"timeout").toString()));}}
}
