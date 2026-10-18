package com.eurobuddha.statenft;

import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
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
public class AtelierAirdropLanguageTest {
    private MainActivity activity;
    private LinearLayout body;
    private StateNft.Meta meta;
    private List<StateNft.Item> items;
    private MockedStatic<MintEngine> mint;
    private MockedStatic<MintService> service;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private static final String A="0x"+"a".repeat(64), B="0x"+"b".repeat(64), C="0x"+"c".repeat(64);
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render()throws Exception{call("renderAirdrop",new Class<?>[]{StateNft.Meta.class,List.class},meta,items);}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);field("body").set(activity,body);
        for(String name:new String[]{"appbarBox","navBox"})field(name).set(activity,new LinearLayout(activity));
        field("content").set(activity,new ScrollView(activity));field("node").set(activity,mock(NodeApi.class));
        meta=new StateNft.Meta();meta.localId=41;meta.tokenid="0x"+"d".repeat(64);meta.name="Literal artwork 中文 Русский Українська";
        AirdropEngine.clearJob(activity,meta.localId);LocalStore.save(activity,new JSONArray());
        items=new ArrayList<>();for(int i=0;i<3;i++){StateNft.Item item=new StateNft.Item();item.index=i+1;item.owned=true;item.coin=new JSONObject().put("coinid","0xcoin"+i).put("tokenid",meta.tokenid).put("state",new JSONArray().put(new JSONObject().put("port",0).put("data","[literal identity "+i+"]")));items.add(item);}
        mint=mockStatic(MintEngine.class);service=mockStatic(MintService.class);render();
    }
    @After public void cleanup(){service.close();mint.close();AirdropEngine.clearJob(activity,meta.localId);Language.select(activity,"en");}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static String upper(int id,Object...args){return Language.upper(Language.msg(id,args)).toString();}
    private TextView button(int id,Object...args){TextView t=find(body,upper(id,args));assertNotNull(t);return t;}
    private List<EditText> fields(){List<EditText> out=new ArrayList<>();collect(body,out);return out;}
    private static void collect(View v,List<EditText> out){if(v instanceof EditText)out.add((EditText)v);else if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
    private void preview(String text){fields().get(1).setText(text);button(l10n_atelier_airdrop_preview).performClick();}
    private static void fits(View v){if(v instanceof TextView){TextView t=(TextView)v;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)fits(((ViewGroup)v).getChildAt(i));}
    private void layout(){for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}

    @Test public void languageSwitchRetainsBothDraftsCursorsAndPreview()throws Exception{
        EditText one=fields().get(0),list=fields().get(1);one.setText(C);one.setSelection(9);preview(A+"\ninvalid\n"+B);list.setSelection(4);
        TextView go=button(l10n_atelier_airdrop_begin);View first=body.getChildAt(0);String coin=items.get(0).coin.toString();
        for(String code:CODES){Language.select(activity,code);Language.apply(body);layout();
            assertSame(first,body.getChildAt(0));assertSame(one,fields().get(0));assertSame(list,fields().get(1));assertSame(go,button(l10n_atelier_airdrop_begin));assertEquals(C,one.getText().toString());assertEquals(A+"\ninvalid\n"+B,list.getText().toString());assertEquals(9,one.getSelectionStart());assertEquals(4,list.getSelectionStart());
            String summary=Language.join(Language.msg(l10n_atelier_airdrop_ready,2)," · ",Language.msg(l10n_atelier_airdrop_invalid_lines,1)," · ",Language.msg(l10n_atelier_airdrop_remaining,1)).toString();assertNotNull("Translated preview: "+code,find(body,summary));assertNotNull(find(body,meta.name.toUpperCase(Locale.ROOT)));assertEquals(coin,items.get(0).coin.toString());assertNull(AirdropEngine.job(activity,meta.localId));}
        service.verifyNoInteractions();mint.verifyNoInteractions();
    }
    @Test public void editingRecipientsInvalidatesPreviewAndCannotQueueOldDestinations()throws Exception{
        preview(A);TextView go=button(l10n_atelier_airdrop_begin);fields().get(1).setText(B);go.performClick();assertNull(AirdropEngine.job(activity,meta.localId));assertNull(find(body,Util.shorten(A)));service.verifyNoInteractions();
        button(l10n_atelier_airdrop_preview).performClick();go.performClick();JSONObject job=AirdropEngine.job(activity,meta.localId);assertNotNull(job);assertEquals(B,job.getJSONArray("entries").getJSONObject(0).getString("addr"));service.verify(()->MintService.kick(activity));
    }
    @Test public void languageChangesDoNotInvalidateAnUnchangedPreview()throws Exception{
        preview(A+"\n"+B);Language.select(activity,"uk");button(l10n_atelier_airdrop_begin).performClick();JSONArray entries=AirdropEngine.job(activity,meta.localId).getJSONArray("entries");assertEquals(2,entries.length());assertEquals(A,entries.getJSONObject(0).getString("addr"));assertEquals(B,entries.getJSONObject(1).getString("addr"));assertEquals(items.get(1).coin.toString(),entries.getJSONObject(1).getJSONObject("coin").toString());assertEquals("PENDING",entries.getJSONObject(0).getString("status"));service.verify(()->MintService.kick(activity));
    }
    @Test public void excessRecipientsAndInvalidLinesRemainVisibleInAllLanguages(){
        preview(A+"\n"+B+"\ninvalid\n"+C+"\n"+A);
        for(String code:CODES){Language.select(activity,code);String summary=Language.join(Language.msg(l10n_atelier_airdrop_ready,3)," · ",Language.msg(l10n_atelier_airdrop_invalid_lines,1)," · ",Language.msg(l10n_atelier_airdrop_extra_recipients,1)).toString();assertNotNull(find(body,summary));}
    }
    @Test public void wholeCollectionKeepsCoinIdentityAndStartsTheEngine()throws Exception{
        fields().get(0).setText("  "+A+"  ");button(l10n_atelier_airdrop_send_all,3).performClick();JSONArray es=AirdropEngine.job(activity,meta.localId).getJSONArray("entries");assertEquals(3,es.length());for(int i=0;i<3;i++){assertEquals(A,es.getJSONObject(i).getString("addr"));assertEquals(items.get(i).coin.toString(),es.getJSONObject(i).getJSONObject("coin").toString());}service.verify(()->MintService.kick(activity));
    }
    @Test public void invalidSingleAddressDoesNotCreateAJob(){fields().get(0).setText("not an address");button(l10n_atelier_airdrop_send_all,3).performClick();assertNull(AirdropEngine.job(activity,meta.localId));service.verifyNoInteractions();}
    @Test public void emptyPreviewDoesNotCreateAJob(){preview("invalid\n\n");button(l10n_atelier_airdrop_begin).performClick();assertNull(AirdropEngine.job(activity,meta.localId));service.verifyNoInteractions();}
    @Test public void existingJobCannotBeOverwrittenByEitherDeliveryPath()throws Exception{
        JSONObject existing=AirdropEngine.createJob(meta,items,Arrays.asList(C));AirdropEngine.saveJob(activity,existing);String before=AirdropEngine.job(activity,meta.localId).toString();
        fields().get(0).setText(A);button(l10n_atelier_airdrop_send_all,3).performClick();preview(B);button(l10n_atelier_airdrop_begin).performClick();assertEquals(before,AirdropEngine.job(activity,meta.localId).toString());service.verifyNoInteractions();
    }
    @Test public void buttonsFromPreviousVisitCannotQueueAJob()throws Exception{
        fields().get(0).setText(A);preview(B);TextView one=button(l10n_atelier_airdrop_send_all,3),go=button(l10n_atelier_airdrop_begin);render();one.performClick();go.performClick();assertNull(AirdropEngine.job(activity,meta.localId));service.verifyNoInteractions();
    }
    @Test public void finishingActivityCannotQueueAJob(){fields().get(0).setText(A);preview(B);TextView one=button(l10n_atelier_airdrop_send_all,3),go=button(l10n_atelier_airdrop_begin);activity.finish();one.performClick();go.performClick();assertNull(AirdropEngine.job(activity,meta.localId));service.verifyNoInteractions();}
}
