package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonPublishingTest {
    private MainActivity activity; private TextView status; private Hosting.Profile host;
    private final List<Runnable> jobs = new ArrayList<>(); private ExecutorService worker;
    private MockedStatic<ProfileHosting> transport; private MockedStatic<MaximaLink> maxima;
    private MockedStatic<SalonRegistry> registry;
    private final AtomicInteger completed = new AtomicInteger();
    private static final String URL = "https://example.invalid/owner/profile.json";
    private static final String[] CODES = {"en", "zh-Hans", "ru", "uk"};
    private Field field(String n)throws Exception { Field f=MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f; }
    private void set(String n,Object v)throws Exception { field(n).set(activity,v); }
    private Object call(String n,Class<?>[] types,Object...args)throws Exception { Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void start()throws Exception { call("hostProfile",new Class<?>[]{TextView.class,Runnable.class},status,(Runnable)completed::incrementAndGet); }
    private void job() { assertFalse(jobs.isEmpty());jobs.remove(0).run();idle(); }
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false); Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get(); status=new TextView(activity);
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);
        doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences("salon_publication",Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences(HostingStore.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        SalonStore.put(activity,"handle","owner");SalonStore.put(activity,"tokenid","0xowner");SalonStore.put(activity,"name","Literal 中文 Русский Українська");
        host=Hosting.Profile.fresh(Hosting.TYPE_WEBDAV);Hosting.put(host.j,"def",true);HostingStore.upsert(activity,host);
        transport=mockStatic(ProfileHosting.class); transport.when(()->ProfileHosting.upload(any(),anyString(),anyString(),anyString())).thenReturn(URL);
        maxima=mockStatic(MaximaLink.class); maxima.when(()->MaximaLink.myAddresses(activity)).thenReturn("");
        registry=mockStatic(SalonRegistry.class);
    }
    @After public void cleanup() { transport.close();maxima.close();registry.close();worker.shutdownNow();Language.select(activity,"en"); }
    private void languages(int id,Object...args) { for(String code:CODES) { Language.select(activity,code); assertEquals(Language.msg(id,args).toString(),status.getText().toString()); } }

    @Test public void publicationUsesCapturedPageLanguageAndRetainsLiteralProfileAcrossSwitches()throws Exception {
        Language.select(activity,"uk");start();languages(l10n_salon_publish_uploading);job();
        assertEquals(URL,SalonStore.get(activity,"profileUrl"));assertEquals(1,completed.get());assertNull(field("profilePublish").get(activity));
        transport.verify(()->ProfileHosting.upload(any(),eq("owner"),argThat(p->p.contains("Literal 中文 Русский Українська")),argThat(h->h.contains("data-salon-language=\"uk\""))));
        languages(l10n_salon_publish_uploaded);
    }
    @Test public void duplicatePublishDoesNotStartAnotherUpload()throws Exception {
        start();start();assertEquals(1,jobs.size());languages(l10n_salon_publish_busy);job();assertEquals(1,completed.get());
    }
    @Test public void changedStoredDraftOwnerOrHostStopsQueuedNetworkWork()throws Exception {
        for(String kind:new String[]{"draft","owner","host"}) {
            start();
            if(kind.equals("draft"))SalonStore.put(activity,"bio","new draft");
            if(kind.equals("owner"))SalonStore.put(activity,"tokenid","new owner");
            if(kind.equals("host"))HostingStore.upsert(activity,new Hosting.Profile(new JSONObject(host.j.toString()).put("name","new host")));
            job();assertEquals("",SalonStore.get(activity,"profileUrl"));assertNull(field("profilePublish").get(activity));
        }
        transport.verifyNoInteractions();assertEquals(0,completed.get());
    }
    @Test public void typingDuringUploadCannotApplyOldResultOrAnnounceIt()throws Exception {
        EditText name=new EditText(activity);name.setText("original");set("edName",name);start();
        transport.when(()->ProfileHosting.upload(any(),anyString(),anyString(),anyString())).thenAnswer(c->{name.setText("new typed name");return URL;});
        job();assertEquals("new typed name",name.getText().toString());assertEquals("",SalonStore.get(activity,"profileUrl"));assertEquals(0,completed.get());registry.verifyNoInteractions();
    }
    @Test public void identityChangeDuringUploadCannotApplyResult()throws Exception {
        start();transport.when(()->ProfileHosting.upload(any(),anyString(),anyString(),anyString())).thenAnswer(c->{SalonStore.writeIdentity(activity,"wrapped:new","new-public");return URL;});
        job();assertEquals("new-public",SalonStore.get(activity,"msgpk"));assertEquals("",SalonStore.get(activity,"profileUrl"));assertEquals(0,completed.get());
    }
    @Test public void navigationAndRenderingInvalidateOldResults()throws Exception {
        start();call("cancelPublication",new Class<?>[]{});job();assertEquals(0,completed.get());
        start();set("renderEpoch",1);job();assertEquals(0,completed.get());assertEquals("",SalonStore.get(activity,"profileUrl"));
    }
    @Test public void failedUploadAndRejectedExecutorReleaseBusyStateWithTranslatedMessages()throws Exception {
        transport.when(()->ProfileHosting.upload(any(),anyString(),anyString(),anyString())).thenThrow(new Hosting.HostingException("backend detail"));
        start();job();languages(l10n_salon_publish_failed,"backend detail");assertEquals(0,completed.get());
        doThrow(new java.util.concurrent.RejectedExecutionException("worker unavailable")).when(worker).execute(any(Runnable.class));start();
        assertNull(field("profilePublish").get(activity));languages(l10n_salon_publish_failed,"worker unavailable");
    }
    @Test public void timeoutDoesNotAllowLateEditionReplyToMutateDraftOrStartSecondUpload()throws Exception {
        NodeApi node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        JSONArray original=new JSONArray().put(new JSONObject().put("tokenid","0x"+"a".repeat(64)));
        SalonStore.setArr(activity,"nfts",original);start();assertTrue(jobs.isEmpty());languages(l10n_salon_publish_preparing);
        ArgumentCaptor<NodeApi.Cb> reply=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(anyString(),reply.capture());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13));assertEquals(1,jobs.size());job();
        reply.getValue().onError("late response");idle();assertTrue(jobs.isEmpty());assertEquals(1,completed.get());assertEquals(original.toString(),SalonStore.arr(activity,"nfts").toString());
    }
    @Test public void editionRefreshNeverOverwritesAReplacementAccountsHoldings()throws Exception {
        NodeApi node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        SalonStore.setArr(activity,"nfts",new JSONArray().put(new JSONObject().put("tokenid","0x"+"a".repeat(64))));start();
        ArgumentCaptor<NodeApi.Cb> reply=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(anyString(),reply.capture());
        SalonStore.put(activity,"tokenid","replacement");SalonStore.setArr(activity,"nfts",new JSONArray());reply.getValue().onError("unavailable");idle();
        assertEquals(0,SalonStore.arr(activity,"nfts").length());assertEquals("",SalonStore.get(activity,"profileUrl"));assertTrue(jobs.isEmpty());
    }
    @Test public void announcementChecksDirectoryBeforeSendingAndKeepsGuardAfterAcknowledgement()throws Exception {
        set("nodeUp",true);NodeApi node=mock(NodeApi.class);set("node",node);SalonStore.put(activity,"profileUrl",URL);
        List<NodeApi.Cb> reads=new ArrayList<>();doAnswer(c->{reads.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any());
        List<SalonRegistry.Announced> callbacks=new ArrayList<>();registry.when(()->SalonRegistry.announce(eq(node),anyString(),anyString(),anyString(),any())).thenAnswer(c->{callbacks.add(c.getArgument(4));return null;});
        call("publishSalon",new Class<?>[]{TextView.class},status);languages(l10n_salon_progress_checking);
        reads.get(0).onResult(new JSONObject().put("status",true).put("response",new JSONArray()));languages(l10n_salon_progress_announcing);
        callbacks.get(0).done(true,"");languages(l10n_salon_progress_waiting);
        call("publishSalon",new Class<?>[]{TextView.class},status);assertEquals(1,callbacks.size());
        assertTrue(SalonPublication.pending(activity,"0xowner"));
    }
    @Test public void announcementResultCannotUpdateReplacementIdentity()throws Exception {
        set("nodeUp",true);NodeApi node=mock(NodeApi.class);set("node",node);SalonStore.put(activity,"profileUrl",URL);
        ArgumentCaptor<NodeApi.Cb> callback=ArgumentCaptor.forClass(NodeApi.Cb.class);
        call("publishSalon",new Class<?>[]{TextView.class},status);verify(node).cmd(anyString(),callback.capture());
        SalonStore.put(activity,"tokenid","replacement");callback.getValue().onResult(new JSONObject().put("status",true).put("response",new JSONArray()));
        registry.verifyNoInteractions();languages(l10n_salon_progress_checking);
    }
    private static TextView find(android.view.View view,String text) {
        if(view instanceof TextView && text.equals(((TextView)view).getText().toString())) return (TextView)view;
        if(view instanceof android.view.ViewGroup) for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++) {
            TextView result=find(((android.view.ViewGroup)view).getChildAt(i),text);if(result!=null)return result;
        }
        return null;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test public void actualEditorPublishKeepsDraftVisibleUntilAnnouncementResult()throws Exception {
        activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        LinearLayout body=new LinearLayout(activity);set("body",body);set("appbar",new LinearLayout(activity));set("navRow",new LinearLayout(activity));set("scroll",new ScrollView(activity));
        set("screen",Enum.valueOf((Class)field("screen").getType(),"EDIT"));
        try(MockedStatic<MailDb> mail=mockStatic(MailDb.class)) {
            MailDb db=mock(MailDb.class);mail.when(()->MailDb.get(activity)).thenReturn(db);
            call("render",new Class<?>[]{});
            EditText name=(EditText)field("edName").get(activity);name.setText("Typed Українська 中文");name.setSelection(4);
            NodeApi node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
            List<NodeApi.Cb> reads=new ArrayList<>();doAnswer(c->{reads.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any());
            List<SalonRegistry.Announced> callbacks=new ArrayList<>();registry.when(()->SalonRegistry.announce(eq(node),anyString(),anyString(),anyString(),any())).thenAnswer(c->{callbacks.add(c.getArgument(4));return null;});
            TextView publish=find(body,Language.upper(Language.msg(l10n_salon_editor_publish)).toString());assertNotNull(publish);publish.performClick();
            status=(TextView)field("profStatus").get(activity);languages(l10n_salon_publish_uploading);job();reads.get(0).onResult(new JSONObject().put("status",true).put("response",new JSONArray()));assertEquals(1,callbacks.size());languages(l10n_salon_progress_announcing);
            assertFalse(publish.isEnabled());assertFalse(publish.isClickable());
            android.view.View spinner=(android.view.View)field("publishSpinner").get(activity);assertEquals(android.view.View.VISIBLE,spinner.getVisibility());assertSame(publish.getParent(),spinner.getParent());
            assertEquals("EDIT",field("screen").get(activity).toString());assertSame(name,field("edName").get(activity));assertEquals("Typed Українська 中文",name.getText().toString());assertEquals(4,name.getSelectionStart());
            callbacks.get(0).done(true,"");idle();languages(l10n_salon_progress_waiting);assertEquals("EDIT",field("screen").get(activity).toString());
            // Chain evidence, not acknowledgement, routes to Discover.
            registry.when(()->SalonRegistry.hex(URL)).thenCallRealMethod();
            JSONObject coin=new JSONObject().put("tokenid","0x00").put("state",new JSONObject().put("0","0xowner").put("1",SalonRegistry.hex(URL)));
            reads.get(1).onResult(new JSONObject().put("status",true).put("response",new JSONArray().put(coin)));idle();
            assertEquals("DISCOVER",field("screen").get(activity).toString());
            assertEquals(android.view.View.GONE,spinner.getVisibility());
        }
    }
    @Test public void refreshedEditionsAreSavedOnlyAfterUploadAndLateDuplicateCannotMutateThem()throws Exception {
        NodeApi node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        String token="0x"+"a".repeat(64);JSONArray original=new JSONArray().put(new JSONObject().put("tokenid",token));SalonStore.setArr(activity,"nfts",original);
        List<NodeApi.Cb> callbacks=new ArrayList<>();doAnswer(c->{callbacks.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any());
        start();callbacks.get(0).onResult(new JSONObject().put("response",new JSONArray()));
        JSONObject coin=new JSONObject();
        try(MockedStatic<NftProof> proof=mockStatic(NftProof.class)) {
            proof.when(()->NftProof.editionImageUrl(any(),anyString(),anyString())).thenReturn("https://example.invalid/edition.png");proof.when(()->NftProof.state(any(),eq(0))).thenReturn("7");
            callbacks.get(1).onResult(new JSONObject().put("response",new JSONArray().put(coin)));idle();
            assertEquals(original.toString(),SalonStore.arr(activity,"nfts").toString());assertEquals(1,jobs.size());job();
            String saved=SalonStore.arr(activity,"nfts").toString();assertTrue(saved.contains("edition.png"));assertTrue(saved.contains("Edition #7"));
            callbacks.get(1).onResult(new JSONObject().put("response",new JSONArray()));idle();assertEquals(saved,SalonStore.arr(activity,"nfts").toString());assertTrue(jobs.isEmpty());
        }
    }
}
