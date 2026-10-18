package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
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

/** Real conversation widgets and transport choice; fake database/transport, no sends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonChatLanguageTest {
    private MainActivity activity;private LinearLayout body,appbar;private MailDb db;private NodeApi node;
    private final List<Runnable> jobs=new ArrayList<>();private MailDb.Contact alice;
    private MockedStatic<MailDb> mail;private MockedStatic<MaximaLink> maxima;private MockedStatic<ImageLoader> images;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] t,Object...a)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,a);}
    @SuppressWarnings({"rawtypes","unchecked"})private void screen(String name)throws Exception{set("screen",Enum.valueOf((Class)field("screen").getType(),name));}
    private void render()throws Exception{call("render",new Class<?>[]{});}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbar",appbar);set("navRow",new LinearLayout(activity));set("scroll",new ScrollView(activity));screen("THREAD");set("threadPeer","alice");
        ((ExecutorService)field("io").get(activity)).shutdownNow();ExecutorService worker=mock(ExecutorService.class);set("io",worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));node=mock(NodeApi.class);set("node",node);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"tokenid","0xowner");SalonStore.put(activity,"handle","owner");
        db=mock(MailDb.class);mail=mockStatic(MailDb.class);mail.when(()->MailDb.get(activity)).thenReturn(db);maxima=mockStatic(MaximaLink.class);maxima.when(()->MaximaLink.myAddresses(activity)).thenReturn("");images=mockStatic(ImageLoader.class);
        alice=new MailDb.Contact();alice.peerpk="alice";alice.handle="Alice 中文";alice.avatar="";alice.addr="0xaddress";alice.mxaddr="MAX#alice";alice.lastbody="Original preview Русский Українська";when(db.contact("alice")).thenReturn(alice);when(db.messages(eq("alice"),anyInt())).thenReturn(Collections.emptyList());
    }
    @After public void cleanup()throws Exception{android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();mail.close();maxima.close();images.close();((ExecutorService)field("io").get(activity)).shutdownNow();Language.select(activity,"en");}
    private static TextView find(View v,String value){if(v instanceof TextView&&value.equals(((TextView)v).getText().toString()))return(TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView f=find(((ViewGroup)v).getChildAt(i),value);if(f!=null)return f;}return null;}
    private static EditText input(View v){if(v instanceof EditText)return(EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText f=input(((ViewGroup)v).getChildAt(i));if(f!=null)return f;}return null;}
    private static void fits(View v){if(v instanceof TextView&&!(v instanceof EditText)){TextView t=(TextView)v;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)fits(((ViewGroup)v).getChildAt(i));}
    private void layout(){for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}
    private MailDb.Message message(){MailDb.Message m=new MailDb.Message();m.coinid="message-original";m.peerpk="alice";m.mine=true;m.body="Literal message 中文 Русский Українська";m.media="";m.mime="";m.ts=System.currentTimeMillis()/1000-600;m.status=MailDb.PENDING;m.replyFrom="You";m.replyBody="Literal quoted text";return m;}
    private AlertDialog chooser()throws Exception{call("transportDialog",new Class<?>[]{});idle();return(AlertDialog)ShadowDialog.getLatestDialog();}
    private void choose(AlertDialog d,int index){ListView list=d.getListView();list.performItemClick(list.getAdapter().getView(index,null,list),index,index);idle();}

    @Test public void threadKeepsDraftCursorAndProtocolReplyAcrossLanguages()throws Exception{
        MailDb.Message m=message();when(db.messages(eq("alice"),anyInt())).thenReturn(Arrays.asList(m));when(db.messageCount("alice")).thenReturn(4);set("replyPeer","alice");set("replyFrom","You");set("replyBody","Original quote");render();EditText edit=input(body);edit.setText("Unsent 中文 Русский Українська");edit.setSelection(4);View first=body.getChildAt(0);
        for(String code:CODES){Language.select(activity,code);layout();assertSame(first,body.getChildAt(0));assertSame(edit,input(body));assertEquals(4,edit.getSelectionStart());assertEquals("Unsent 中文 Русский Українська",edit.getText().toString());assertNotNull(find(body,m.body));assertNotNull(find(body,m.replyBody));assertNotNull(find(body,Language.msg(l10n_salon_chat_sending).toString()));assertNotNull(find(body,Language.msg(l10n_salon_chat_replying,Language.msg(l10n_salon_chat_you)).toString()));assertNotNull(find(body,"↩ "+Language.msg(l10n_salon_chat_you)));assertNotNull(find(body,upper(l10n_chat_message)));assertNotNull(find(body,upper(l10n_mail_send)));assertNotNull(find(body,Language.upper(Language.msg(l10n_salon_chat_earlier,3)).toString()));assertEquals("You",field("replyFrom").get(activity));assertEquals("You",m.replyFrom);}
        verify(db,never()).insert(anyString(),anyString(),anyBoolean(),anyString(),anyString(),anyString(),anyLong(),anyBoolean(),anyInt());verifyNoInteractions(node);
    }
    @Test public void conversationListPreservesUserPreviewAndTranslatesMissingContact()throws Exception{
        MailDb.Contact unknown=new MailDb.Contact();unknown.peerpk="unknown";unknown.avatar="";when(db.contacts()).thenReturn(Arrays.asList(alice,unknown));screen("MESSAGES");render();
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(appbar,upper(l10n_creative_messages)));assertNotNull(find(body,"@Alice 中文"));assertNotNull(find(body,alice.lastbody));assertNotNull(find(body,Language.msg(l10n_salon_chat_unknown_contact).toString()));}
        when(db.contacts()).thenReturn(Collections.emptyList());render();for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.msg(l10n_salon_chat_empty).toString()));}
    }
    @Test public void transportChoiceRetainsSelectionAndWritesStableValues()throws Exception{
        SalonStore.put(activity,"xport_alice","maxima");AlertDialog d=chooser();
        for(String code:CODES){Language.select(activity,code);idle();assertSame(d,ShadowDialog.getLatestDialog());assertEquals(1,d.getListView().getCheckedItemPosition());assertEquals(Language.msg(l10n_salon_chat_auto_choice).toString(),d.getListView().getAdapter().getItem(0).toString());assertEquals(Language.msg(l10n_salon_chat_maxima_choice).toString(),d.getListView().getAdapter().getItem(1).toString());assertNotNull(find(d.getWindow().getDecorView(),Language.msg(l10n_salon_chat_transport).toString()));}
        choose(d,2);assertEquals("chain",SalonStore.get(activity,"xport_alice"));assertEquals("",SalonStore.get(activity,"xport_bob"));
        d=chooser();set("threadPeer","bob");choose(d,0);assertEquals("chain",SalonStore.get(activity,"xport_alice"));assertEquals("",SalonStore.get(activity,"xport_bob"));assertEquals(Language.msg(l10n_salon_chat_selection_changed).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void chooserRejectsChangedIdentityAndScreen()throws Exception{
        for(boolean identity:new boolean[]{true,false}){screen("THREAD");SalonStore.put(activity,"tokenid","0xowner");AlertDialog d=chooser();if(identity)SalonStore.put(activity,"tokenid","replacement");else screen("MESSAGES");choose(d,2);assertEquals("",SalonStore.get(activity,"xport_alice"));}
    }
    @Test public void routeChipMatchesQueuePolicyWhenMaximaIsOffline()throws Exception{
        render();for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.upper(Language.join(Language.msg(l10n_salon_chat_maxima_waiting),"  ▾")).toString()));}
        alice.mxaddr="";SalonStore.put(activity,"xport_alice","maxima");render();for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.upper(Language.join(Language.msg(l10n_salon_chat_maxima_no_address),Language.msg(l10n_salon_chat_forced),"  ▾")).toString()));}
        SalonStore.put(activity,"xport_alice","chain");render();for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.upper(Language.join(Language.msg(l10n_salon_chat_chain),Language.msg(l10n_salon_chat_forced),"  ▾")).toString()));}
    }
    @Test public void translatedReplyAuthorNeverChangesStoredOrSentPayload()throws Exception{
        try(MockedStatic<MinimaMail> transport=mockStatic(MinimaMail.class);MockedStatic<SalonComms> comms=mockStatic(SalonComms.class)){
            transport.when(()->MinimaMail.compose(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong())).thenCallRealMethod();LocalEcCryptoProvider crypto=mock(LocalEcCryptoProvider.class);comms.when(()->SalonComms.crypto(activity)).thenReturn(crypto);SalonStore.put(activity,"xport_alice","chain");set("replyPeer","alice");set("replyFrom","You");set("replyBody","Literal quoted text");Language.select(activity,"uk");
            call("sendDm",new Class<?>[]{String.class,String.class,String.class},"Original text 中文","","");ArgumentCaptor<JSONObject> sent=ArgumentCaptor.forClass(JSONObject.class);transport.verify(()->MinimaMail.send(eq(node),eq(crypto),eq(alice.addr),eq(alice.peerpk),sent.capture(),any(MinimaMail.Cb.class)));assertEquals("Original text 中文",sent.getValue().getString("body"));assertEquals("Literal quoted text",sent.getValue().getString("replybody"));assertEquals("@owner",sent.getValue().getString("replyfrom"));verify(db).setReply(anyString(),eq("Literal quoted text"),eq("You"));
        }
    }
    @Test public void maximaOnlyRetryNeverFallsBackToChainWithoutRoute()throws Exception{
        MailDb.Message pending=message();pending.ts=System.currentTimeMillis()/1000-600;when(db.pendingOutbox()).thenReturn(Arrays.asList(pending));alice.mxaddr="";set("nodeUp",true);SalonStore.put(activity,"xport_alice","maxima");
        try(MockedStatic<MinimaMail> transport=mockStatic(MinimaMail.class);MockedStatic<SalonComms> comms=mockStatic(SalonComms.class)){
            transport.when(()->MinimaMail.compose(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong())).thenCallRealMethod();call("retryOutbox",new Class<?>[]{});jobs.remove(0).run();idle();comms.verifyNoInteractions();transport.verify(()->MinimaMail.send(any(),any(),anyString(),anyString(),any(),any()),never());
            LocalEcCryptoProvider crypto=mock(LocalEcCryptoProvider.class);comms.when(()->SalonComms.crypto(activity)).thenReturn(crypto);SalonStore.put(activity,"xport_alice","auto");call("retryOutbox",new Class<?>[]{});jobs.remove(0).run();idle();transport.verify(()->MinimaMail.send(eq(node),eq(crypto),eq(alice.addr),eq(alice.peerpk),any(),any()));
        }
    }
    @Test public void completedMessageTimestampAndValidationErrorsFollowLanguage()throws Exception{
        MailDb.Message m=message();m.status=MailDb.SENT;View bubble=(View)call("bubble",new Class<?>[]{MailDb.Message.class},m);for(String code:CODES){Language.select(activity,code);assertNotNull(find(bubble,Language.since(m.ts*1000).toString()));call("sendDm",new Class<?>[]{String.class,String.class,String.class},"","","");assertEquals(Language.msg(l10n_salon_chat_type_message).toString(),ShadowToast.getTextOfLatestToast());}
    }
}
