package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
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
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonHostedRestoreTest {
    private MainActivity activity;
    private ExecutorService worker;
    private MockedStatic<RelayResolver> transport;
    private final List<Runnable> jobs=new ArrayList<>();
    private static final String URL="mx1:owned-page";
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private Field field(String n)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception {field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void start()throws Exception {call("hydrateFromHosted",new Class<?>[]{String.class,boolean.class},URL,true);}
    private void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void notice(int id){assertEquals(Language.text(activity,id),ShadowToast.getTextOfLatestToast());}
    @SuppressWarnings({"unchecked","rawtypes"})
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);
        doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();activity.getSharedPreferences(HostingStore.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        SalonStore.put(activity,"handle","owner");SalonStore.put(activity,"tokenid","token");SalonStore.put(activity,"profileUrl",URL);SalonStore.put(activity,"name","local");
        set("body",new LinearLayout(activity));set("navRow",new LinearLayout(activity));set("appbar",new LinearLayout(activity));set("scroll",new ScrollView(activity));
        set("screen",Enum.valueOf((Class)field("screen").getType(),"SETTINGS"));
        transport=mockStatic(RelayResolver.class);transport.when(()->RelayResolver.isMediaRef(URL)).thenReturn(true);
        transport.when(()->RelayResolver.resolveJson(URL)).thenReturn(new JSONObject().put("name","Literal 中文 Русский Українська"));
    }
    @After public void cleanup(){transport.close();worker.shutdownNow();Language.select(activity,"en");}
    @Test public void selectedLanguageAtCompletionAndLiteralContentSurviveRestore()throws Exception {
        for(String code:CODES){start();Language.select(activity,code);job();notice(l10n_salon_setup_restored);assertEquals("Literal 中文 Русский Українська",SalonStore.get(activity,"name"));assertNull(field("hostedRestore").get(activity));}
    }
    @Test public void delayedResponseCannotOverwriteChangedAccountDraftOrProfileUrl()throws Exception {
        for(String key:new String[]{"tokenid","name","profileUrl"}){
            SalonStore.put(activity,"profileUrl",URL);start();SalonStore.put(activity,key,"new");String latest=SalonStore.me(activity).toString();job();
            assertEquals(latest,SalonStore.me(activity).toString());notice(l10n_salon_restore_changed);
        }
    }
    @Test public void typingWhileRestoreRunsKeepsSameWidgetTextAndCursor()throws Exception {
        EditText name=new EditText(activity);name.setText("draft");set("edName",name);start();name.setText("new typed text");name.setSelection(4);job();
        assertSame(name,field("edName").get(activity));assertEquals("new typed text",name.getText().toString());assertEquals(4,name.getSelectionStart());assertEquals("local",SalonStore.get(activity,"name"));notice(l10n_salon_restore_changed);
    }
    @Test public void cancelledRequestCannotFinishOrClearNewRequest()throws Exception {
        start();call("cancelHostedRestore",new Class<?>[]{});start();Object current=field("hostedRestore").get(activity);job();assertSame(current,field("hostedRestore").get(activity));assertEquals("local",SalonStore.get(activity,"name"));job();assertNull(field("hostedRestore").get(activity));notice(l10n_salon_setup_restored);
    }
    @Test public void rerenderedScreenRejectsResponseWithoutAnotherRender()throws Exception {
        start();set("renderEpoch",7);job();assertEquals(7,field("renderEpoch").get(activity));assertEquals("local",SalonStore.get(activity,"name"));notice(l10n_salon_restore_changed);
    }
    @Test public void finishingActivityCannotApplyLateResponse()throws Exception {
        start();activity.finish();job();assertEquals("local",SalonStore.get(activity,"name"));assertNull(field("hostedRestore").get(activity));
    }
    @Test public void busyAndRejectedWorkerAreTranslatedAndReleaseState()throws Exception {
        for(String code:CODES){Language.select(activity,code);start();start();notice(l10n_salon_restore_busy);assertEquals(1,jobs.size());call("cancelHostedRestore",new Class<?>[]{});jobs.clear();}
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(worker).execute(any(Runnable.class));
        for(String code:CODES){Language.select(activity,code);start();notice(l10n_salon_restore_unavailable);assertNull(field("hostedRestore").get(activity));}
    }
    @Test public void failedEmptyAndInvalidResponsesKeepDraftWithTranslatedFeedback()throws Exception {
        JSONObject[] replies={null,new JSONObject(),new JSONObject().put("name",new JSONObject())};int[] ids={l10n_salon_setup_unreachable,l10n_salon_setup_empty_hosted,l10n_salon_restore_invalid};
        for(String code:CODES)for(int i=0;i<replies.length;i++){
            Language.select(activity,code);transport.when(()->RelayResolver.resolveJson(URL)).thenReturn(replies[i]);start();job();notice(ids[i]);assertEquals("local",SalonStore.get(activity,"name"));
        }
    }
    @SuppressWarnings("unchecked")
    @Test public void explicitRestoreBypassesPreviouslyCachedPage()throws Exception {
        Map<String,Object[]> cache=(Map<String,Object[]>)field("mProfileCache").get(activity);cache.put(URL,new Object[]{new JSONObject().put("name","old cached page"),System.currentTimeMillis()});
        start();job();transport.verify(()->RelayResolver.resolveJson(URL));assertEquals("Literal 中文 Русский Українська",SalonStore.get(activity,"name"));
    }
    @Test public void durableSaveFailureIsTranslatedAndNeverReportsRestored()throws Exception {
        try(MockedStatic<SalonStore> store=mockStatic(SalonStore.class,CALLS_REAL_METHODS)){
            store.when(()->SalonStore.restoreHosted(eq(activity),anyString(),any())).thenReturn(SalonStore.RestoreResult.FAILED);
            for(String code:CODES){Language.select(activity,code);start();job();notice(l10n_salon_restore_storage);assertEquals("local",SalonStore.get(activity,"name"));}
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test public void openingEditorAfterRestoreCannotReapplyWidgetsRetainedFromEarlierVisit()throws Exception {
        EditText oldName=new EditText(activity);oldName.setText("old editor value");set("edName",oldName);
        start();job();assertNull(field("edName").get(activity));
        try(MockedStatic<MailDb> mail=mockStatic(MailDb.class)) {
            mail.when(()->MailDb.get(activity)).thenReturn(mock(MailDb.class));
            Class type=field("screen").getType();call("go",new Class<?>[]{type},Enum.valueOf(type,"EDIT"));
            EditText current=(EditText)field("edName").get(activity);
            assertNotSame(oldName,current);assertEquals("Literal 中文 Русский Українська",current.getText().toString());
            assertEquals("Literal 中文 Русский Українська",SalonStore.get(activity,"name"));
        }
    }
    @Test public void alternateHttpsRecoveryUsesCheckedTransportAndMovesUrlOnlyAfterSave()throws Exception {
        String url="https://example.invalid/salon/owner/profile.json",token="0x"+"ab".repeat(32);
        SalonStore.put(activity,"tokenid",token);SalonStore.put(activity,"tipaddr","new-node-address");
        JSONObject page=new JSONObject().put("tokenid",token).put("handle","owner").put("name","restored page").put("tipaddr","old-address");
        java.net.HttpURLConnection connection=mock(java.net.HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenAnswer(i->new java.io.ByteArrayInputStream(page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(MockedStatic<ImageLoader> http=mockStatic(ImageLoader.class)) {
            http.when(()->ImageLoader.openChecked(url,3)).thenReturn(connection);
            call("hydrateFromHosted",new Class<?>[]{String.class,boolean.class,boolean.class},url,true,true);
            assertEquals(URL,SalonStore.get(activity,"profileUrl"));job();
            http.verify(()->ImageLoader.openChecked(url,3));
            assertEquals(url,SalonStore.get(activity,"profileUrl"));assertEquals("restored page",SalonStore.get(activity,"name"));
            assertEquals("new-node-address",SalonStore.get(activity,"tipaddr"));notice(l10n_salon_setup_restored);
            verify(connection).disconnect();
        }
    }
    @Test public void recoveryRejectsInsecureUrlBeforeSchedulingWorkInEveryLanguage()throws Exception {
        for(String code:CODES) {
            Language.select(activity,code);
            call("hydrateFromHosted",new Class<?>[]{String.class,boolean.class,boolean.class},"http://example.invalid/page.json",true,true);
            notice(l10n_salon_restore_source_https);assertTrue(jobs.isEmpty());assertEquals(URL,SalonStore.get(activity,"profileUrl"));
        }
    }

}
