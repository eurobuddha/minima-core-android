package com.eurobuddha.statenft;

import android.content.*;
import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierManageLanguageTest {
    private MainActivity activity;
    private LinearLayout body;
    private NodeApi node;
    private List<NodeApi.Cb> callbacks;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}
    private void render(String name)throws Exception{invoke(name,new Class<?>[]{});}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);field("body").set(activity,body);
        for(String name:new String[]{"appbarBox","navBox"}){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);field(name).set(activity,box);}
        field("content").set(activity,new ScrollView(activity));node=mock(NodeApi.class);field("node").set(activity,node);
        callbacks=new ArrayList<>();doAnswer(call->{callbacks.add(call.getArgument(1));return null;}).when(node).cmd(eq("balance"),any(NodeApi.Cb.class));
        LocalStore.save(activity,new JSONArray());LocalStore.clearEngineLog(activity);
        for(Hosting.Profile p:HostingStore.list(activity))HostingStore.remove(activity,p.id());
    }
    @After public void cleanup(){Language.select(activity,"en");}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root){if(root instanceof TextView){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private void layout(){for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}
    @Test public void manageControlsTranslateInPlaceAndReconnectKeepsItsCallback()throws Exception{
        Hosting.Profile p=Hosting.Profile.fresh("webdav");Hosting.put(p.j,"name","My literal 中文 destination");HostingStore.upsert(activity,p);
        for(boolean ready:new boolean[]{false,true}){
            when(node.isEnabled()).thenReturn(ready);when(node.lastOkMs()).thenReturn(ready?123L:0L);render("renderManage");
            View first=body.getChildAt(0);TextView reconnect=find(body,Language.upper(Language.msg(l10n_atelier_manage_reconnect)).toString());
            for(String code:CODES){Language.select(activity,code);layout();assertSame(first,body.getChildAt(0));assertSame(reconnect,find(body,Language.upper(Language.msg(l10n_atelier_manage_reconnect)).toString()));assertNotNull(find(body,Language.text(activity,ready?l10n_atelier_manage_connected:l10n_atelier_manage_not_ready)));assertNotNull(find(body,p.name()+" · webdav"));assertNotNull(find(body,Language.text(activity,l10n_atelier_manage_battery_help)));}
            reconnect.performClick();
        }
        verify(node,times(2)).reRegister();verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void engineLogPreservesLiteralRecordsAndCopyWhileSummaryTranslates()throws Exception{
        String literal="ERROR token 0x123 · artwork 中文 Русский Українська";LocalStore.logEvent(activity,literal);LocalStore.recordHeartbeat(activity,"fixture");
        invoke("renderEngineLog",new Class<?>[]{Runnable.class},(Runnable)()->{});View row=body.getChildAt(body.getChildCount()-1);String stored=LocalStore.engineLog(activity).toString();
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,literal));assertSame(row,body.getChildAt(body.getChildCount()-1));assertNotNull(find(body,Language.upper(Language.msg(l10n_atelier_manage_log_intro)).toString()));assertEquals(stored,LocalStore.engineLog(activity).toString());}
        find(body,Language.upper(Language.msg(l10n_atelier_manage_copy_log)).toString()).performClick();ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);assertTrue(clipboard.getPrimaryClip().getItemAt(0).getText().toString().endsWith("  "+literal+"\n"));
        find(body,Language.upper(Language.msg(l10n_logs_clear)).toString()).performClick();assertEquals(0,LocalStore.engineLog(activity).length());
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.text(activity,l10n_atelier_manage_log_empty)));}
    }
    @Test public void scanLabelsAndCompletionRetainViewAndFoundCollections()throws Exception{
        render("renderScan");TextView status=(TextView)field("inlineStatus").get(activity);
        StateNft.Meta collection=new StateNft.Meta();collection.name="Literal collection 中文";
        @SuppressWarnings("unchecked") List<StateNft.Meta> found=(List<StateNft.Meta>)field("scanFound").get(activity);found.add(collection);
        for(String code:CODES){Language.select(activity,code);layout();assertSame(status,field("inlineStatus").get(activity));assertEquals(Language.text(activity,l10n_atelier_manage_reading),status.getText().toString());assertSame(collection,found.get(0));}
        callbacks.get(0).onResult(new JSONObject().put("status",true).put("response",new JSONArray()));
        TextView done=find(body,Language.upper(Language.msg(l10n_chat_done)).toString());assertNotNull(done);
        for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_atelier_manage_catalogue_count,1).toString(),status.getText().toString());assertSame(done,find(body,Language.upper(Language.msg(l10n_chat_done)).toString()));assertSame(collection,found.get(0));}
        verify(node,times(1)).cmd(eq("balance"),any(NodeApi.Cb.class));
    }
    @Test public void rejectedOrMalformedBalanceNeverReportsSuccessfulEmptyScan()throws Exception{
        JSONObject saved=new JSONObject().put("id",7).put("name","Existing collection");LocalStore.save(activity,new JSONArray().put(saved));String before=LocalStore.load(activity).toString();
        for(JSONObject reply:new JSONObject[]{new JSONObject().put("status",false).put("response",new JSONArray()),new JSONObject().put("status",true).put("response",new JSONObject()),new JSONObject().put("response",new JSONArray()),new JSONObject().put("status","true").put("response",new JSONArray()),null}){
            render("renderScan");callbacks.get(callbacks.size()-1).onResult(reply);TextView status=(TextView)field("inlineStatus").get(activity);
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.text(activity,l10n_atelier_manage_scan_failed),status.getText().toString());assertNull(find(body,Language.upper(Language.msg(l10n_chat_done)).toString()));assertEquals(before,LocalStore.load(activity).toString());}
        }
        render("renderScan");callbacks.get(callbacks.size()-1).onError("read timeout");assertEquals(Language.text(activity,l10n_atelier_manage_scan_failed),((TextView)field("inlineStatus").get(activity)).getText().toString());
    }
    @Test public void lateScanReadCannotChangeAnotherScreensStatus()throws Exception{
        render("renderScan");NodeApi.Cb cb=callbacks.get(0);render("renderManage");
        TextView other=new TextView(activity);other.setText("other task status");field("inlineStatus").set(activity,other);
        cb.onError("timeout");cb.onResult(new JSONObject().put("status",true).put("response",new JSONArray()));
        assertEquals("other task status",other.getText().toString());assertNull(find(body,Language.upper(Language.msg(l10n_chat_done)).toString()));
    }
}
