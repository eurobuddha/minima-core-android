package com.eurobuddha.salon;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
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
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Full picker/decode/upload callbacks with fake image reader, host and chat transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonMediaUploadTest {
    private MainActivity activity;private Hosting.Profile host;private Hosting.Uploader uploader;
    private final List<Runnable> jobs=new ArrayList<>();private ExecutorService worker;
    private MockedStatic<Hosting> hosts;private MockedStatic<ImageTools> images;private MockedStatic<MaximaLink> maxima;private MockedStatic<MailDb> mail;
    private MailDb db;private final Uri uri=Uri.parse("content://example.invalid/file");
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] t,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,args);}
    @SuppressWarnings({"rawtypes","unchecked"})private void screen(String value)throws Exception{set("screen",Enum.valueOf((Class)field("screen").getType(),value));}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();idle();}
    private void pick(int code)throws Exception{call("pickMedia",new Class<?>[]{int.class,String.class},code,"image/*");}
    private void result(int code){activity.onActivityResult(code,Activity.RESULT_OK,new Intent().setData(uri));idle();}
    private EditText editor(String name)throws Exception{return(EditText)field(name).get(activity);}
    private void render()throws Exception{call("render",new Class<?>[]{});}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbar",new LinearLayout(activity));set("navRow",new LinearLayout(activity));set("scroll",new ScrollView(activity));screen("EDIT");
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();activity.getSharedPreferences(HostingStore.PREFS,Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"tokenid","0xowner");SalonStore.put(activity,"handle","owner");
        host=Hosting.Profile.fresh(Hosting.TYPE_WEBDAV);Hosting.put(host.j,"def",true);Hosting.put(host.j,"name","Original host");HostingStore.upsert(activity,host);
        uploader=mock(Hosting.Uploader.class);when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenReturn("https://example.invalid/uploaded.jpg");
        hosts=mockStatic(Hosting.class);hosts.when(()->Hosting.forProfile(any(Hosting.Profile.class))).thenReturn(uploader);hosts.when(()->Hosting.slug(anyString())).thenCallRealMethod();hosts.when(()->Hosting.put(any(JSONObject.class),anyString(),any())).thenAnswer(c->{c.callRealMethod();return null;});
        images=mockStatic(ImageTools.class);images.when(()->ImageTools.boundedBitmap(eq(activity),eq(uri),anyInt())).thenReturn(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888));maxima=mockStatic(MaximaLink.class);maxima.when(()->MaximaLink.myAddresses(activity)).thenReturn("");
        mail=mockStatic(MailDb.class);db=mock(MailDb.class);mail.when(()->MailDb.get(activity)).thenReturn(db);when(db.messages(anyString(),anyInt())).thenReturn(Collections.emptyList());render();
    }
    @After public void cleanup(){android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();hosts.close();images.close();maxima.close();mail.close();worker.shutdownNow();Language.select(activity,"en");}

    @Test public void pickerSnapshotSurvivesLanguageSwitchAndUploadsToOriginalHost()throws Exception{
        editor("edName").setText("Draft 中文 Русский Українська");pick(41);Object selected=field("pendingMediaPick").get(activity);assertNotNull(selected);
        for(String code:CODES){Language.select(activity,code);assertSame(selected,field("pendingMediaPick").get(activity));}
        result(41);TextView status=(TextView)field("profStatus").get(activity);for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_upload_uploading).toString(),status.getText().toString());}
        job();assertEquals(1,jobs.size());job();assertNull(field("activeMediaUpload").get(activity));assertEquals("https://example.invalid/uploaded.jpg",SalonStore.get(activity,"avatar"));assertEquals("Draft 中文 Русский Українська",SalonStore.get(activity,"name"));verify(uploader).close();
        ArgumentCaptor<Hosting.Profile> target=ArgumentCaptor.forClass(Hosting.Profile.class);hosts.verify(()->Hosting.forProfile(target.capture()));assertEquals(host.j.toString(),target.getValue().j.toString());
        ArgumentCaptor<String> path=ArgumentCaptor.forClass(String.class);verify(uploader).putFile(any(byte[].class),path.capture(),eq("image/jpeg"));assertTrue(path.getValue().startsWith("owner/media/image-"));
        status=(TextView)field("profStatus").get(activity);for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_upload_uploaded).toString(),status.getText().toString());}
    }
    @Test public void cancelledUnknownAndDuplicatePickerResultsCannotStartUploads()throws Exception{
        activity.onActivityResult(999,Activity.RESULT_OK,new Intent().setData(uri));assertTrue(jobs.isEmpty());result(41);assertTrue(jobs.isEmpty());assertEquals(Language.msg(l10n_salon_upload_reselect).toString(),ShadowToast.getTextOfLatestToast());
        pick(41);Object pending=field("pendingMediaPick").get(activity);pick(42);assertSame(pending,field("pendingMediaPick").get(activity));assertEquals(Language.msg(l10n_salon_upload_busy).toString(),ShadowToast.getTextOfLatestToast());activity.onActivityResult(41,Activity.RESULT_CANCELED,null);assertNull(field("pendingMediaPick").get(activity));pick(41);result(41);result(41);assertEquals(1,jobs.size());job();job();verify(uploader,times(1)).putFile(any(byte[].class),anyString(),anyString());
    }
    @Test public void changedPickerIdentityHostOrTypedTargetNeverUploads()throws Exception{
        for(String changed:new String[]{"identity","host","target"}){
            SalonStore.put(activity,"tokenid","0xowner");HostingStore.upsert(activity,host);render();pick(41);
            if(changed.equals("identity"))SalonStore.put(activity,"tokenid","replacement");
            if(changed.equals("host")){Hosting.Profile other=new Hosting.Profile(new JSONObject(host.j.toString()).put("name","Changed host"));HostingStore.upsert(activity,other);}
            if(changed.equals("target"))editor("edAvatar").setText("https://example.invalid/new-draft");
            result(41);assertTrue(jobs.isEmpty());assertEquals(Language.msg(l10n_salon_upload_changed).toString(),ShadowToast.getTextOfLatestToast());
        }
        verifyNoInteractions(uploader);
    }
    @Test public void editsDuringDecodeOrUploadCannotOverwriteAvatarOrNewIdentity()throws Exception{
        pick(41);result(41);editor("edAvatar").setText("typed during decode");job();assertTrue(jobs.isEmpty());verifyNoInteractions(uploader);assertEquals("typed during decode",editor("edAvatar").getText().toString());
        pick(41);result(41);job();when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenAnswer(c->{SalonStore.put(activity,"tokenid","replacement");return "https://example.invalid/old-upload";});String before=SalonStore.get(activity,"avatar");job();verify(uploader).close();assertEquals(before,SalonStore.get(activity,"avatar"));assertEquals("replacement",SalonStore.get(activity,"tokenid"));assertNull(field("activeMediaUpload").get(activity));
    }
    @Test public void changedHostBeforeNetworkDispatchNeverConstructsUploader()throws Exception{
        pick(41);result(41);job();HostingStore.upsert(activity,new Hosting.Profile(new JSONObject(host.j.toString()).put("name","New destination")));job();hosts.verify(()->Hosting.forProfile(any(Hosting.Profile.class)),never());assertNull(field("activeMediaUpload").get(activity));
    }
    @Test public void photoCannotSendToChangedPeerAndUnsentTextSurvivesValidSend()throws Exception{
        try(MockedStatic<SalonComms> identity=mockStatic(SalonComms.class)){identity.when(()->SalonComms.publicId(activity)).thenReturn("owner-public");when(db.enqueue(any(),any())).thenReturn(true);
        screen("THREAD");set("threadPeer","alice");set("threadDraft","Unsent text 中文");MailDb.Contact alice=new MailDb.Contact();alice.peerpk="alice";alice.handle="alice";alice.mxaddr="MAX#route";alice.addr="";when(db.contact("alice")).thenReturn(alice);
        pick(46);result(46);job();set("threadPeer","bob");job();verify(db,never()).enqueue(any(),any());verify(uploader).close();
        set("threadPeer","alice");pick(46);result(46);job();job();verify(db).enqueue(argThat(m->"alice".equals(m.peerpk)&&m.body.isEmpty()&&"https://example.invalid/uploaded.jpg".equals(m.media)&&"image/jpeg".equals(m.mime)),eq(alice));assertEquals("Unsent text 中文",field("threadDraft").get(activity));verify(uploader,times(2)).close();
        }
    }
    @Test public void uploadFailuresCloseTransportAndRetainTranslatedFailureStatus()throws Exception{
        pick(42);result(42);job();when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenThrow(new Hosting.HostingException("backend detail"));job();verify(uploader).close();assertNull(field("activeMediaUpload").get(activity));assertEquals("",SalonStore.get(activity,"banner"));
        TextView status=(TextView)field("profStatus").get(activity);for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_upload_failed,"backend detail").toString(),status.getText().toString());}
    }
    @Test public void galleryUploadKeepsProtocolValuesAndOffersCaptionDialog()throws Exception{
        pick(43);result(43);job();job();assertNotNull(ShadowDialog.getLatestDialog());assertEquals(0,SalonStore.arr(activity,"gallery").length());androidx.appcompat.app.AlertDialog d=(androidx.appcompat.app.AlertDialog)ShadowDialog.getLatestDialog();d.getButton(-2).performClick();idle();JSONObject item=SalonStore.arr(activity,"gallery").getJSONObject(0);assertEquals("image",item.getString("type"));assertEquals("",item.getString("caption"));assertEquals("https://example.invalid/uploaded.jpg",item.getString("url"));
    }
    @Test public void missingHostAndKnownReadErrorsAreLocalized()throws Exception{
        HostingStore.remove(activity,host.id());for(String code:CODES){Language.select(activity,code);pick(41);assertEquals(Language.msg(l10n_salon_upload_host).toString(),ShadowToast.getTextOfLatestToast());assertNull(field("pendingMediaPick").get(activity));assertEquals(Language.msg(l10n_salon_upload_size,60).toString(),call("mediaError",new Class<?>[]{Exception.class},new Exception("file too large (max 60 MB)")).toString());}
    }
    @Test public void navigatingAwayAndBackInvalidatesPickerEvenIfTargetLooksUnchanged()throws Exception{
        pick(41);Class<?> screenType=field("screen").getType();Object edit=field("screen").get(activity);screen("ONBOARD");Object onboard=field("screen").get(activity);screen("EDIT");
        call("go",new Class<?>[]{screenType},onboard);call("go",new Class<?>[]{screenType},edit);result(41);assertTrue(jobs.isEmpty());verifyNoInteractions(uploader);
    }
    @Test public void rejectedWorkerClearsBusyStateAndPreservesDraft()throws Exception{
        pick(41);doThrow(new java.util.concurrent.RejectedExecutionException("worker unavailable")).when(worker).execute(any(Runnable.class));result(41);assertNull(field("activeMediaUpload").get(activity));assertEquals("",SalonStore.get(activity,"avatar"));assertTrue(ShadowToast.getTextOfLatestToast().contains("worker unavailable"));
    }
    @Test public void restoredHandleCannotChooseAnUploadPathOutsideItsFolder()throws Exception{
        SalonStore.put(activity,"handle","../../another/profile");pick(41);result(41);job();job();ArgumentCaptor<String> path=ArgumentCaptor.forClass(String.class);verify(uploader).putFile(any(byte[].class),path.capture(),anyString());assertTrue(path.getValue().startsWith("another-profile/media/"));assertFalse(path.getValue().contains(".."));
    }

    @Test public void fullMeshProfileRejectsMediaBeforeCreatingTransport()throws Exception{
        HostingStore.upsert(activity,new Hosting.Profile(new JSONObject(host.j.toString()).put("type",Hosting.TYPE_MAXIMA)));
        String manifest=java.util.Base64.getUrlEncoder().encodeToString(new JSONObject().put("size",MainActivity.MAX_PROFILE_MESH_BYTES).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));editor("edAvatar").setText(MaximaLink.MEDIA_PREFIX+manifest);maxima.when(()->MaximaLink.isMediaRef(anyString())).thenCallRealMethod();render();pick(43);result(43);job();job();assertTrue(jobs.isEmpty());assertNull(field("activeMediaUpload").get(activity));verifyNoInteractions(uploader);
        TextView status=(TextView)field("profStatus").get(activity);for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_upload_mesh_full,"24 MB",24L).toString(),status.getText().toString());}
    }
    @Test public void emptyHostReferenceIsFailureAndDoesNotEraseExistingAvatar()throws Exception{
        editor("edAvatar").setText("https://example.invalid/original");render();when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenReturn("");pick(41);result(41);job();job();assertEquals("https://example.invalid/original",SalonStore.get(activity,"avatar"));verify(uploader).close();assertEquals(Language.msg(l10n_salon_upload_failed,Language.msg(l10n_salon_upload_no_reference)).toString(),ShadowToast.getTextOfLatestToast());
    }

}
