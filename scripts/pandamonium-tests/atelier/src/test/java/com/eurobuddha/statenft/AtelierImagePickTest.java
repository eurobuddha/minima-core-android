package com.eurobuddha.statenft;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierImagePickTest {
    MainActivity activity; ExecutorService worker; final List<Runnable> jobs=new ArrayList<>();
    MockedStatic<ImageTools> images;MockedStatic<ImageLoader> loader;MockedStatic<Hosting> hosts;MockedStatic<MintService> service;
    Hosting.Profile host; CloseableUploader uploader;final Uri uri=Uri.parse("content://example.invalid/image");
    interface CloseableUploader extends Hosting.Uploader,AutoCloseable { void close(); }
    Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    @SuppressWarnings({"unchecked","rawtypes"}) void screen(String n)throws Exception{set("screen",Enum.valueOf((Class)field("screen").getType(),n));}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();idle();}
    void pick()throws Exception{call("pickImages",new Class<?>[]{});}
    void hostPick()throws Exception{call("pickHostSingle",new Class<?>[]{int.class,String.class},8,"collicon");}
    void result(){activity.onActivityResult(7001,Activity.RESULT_OK,new Intent().setData(uri));idle();}
    String[] slots()throws Exception{return(String[])field("createImages").get(activity);}
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);activity.getWindow().getDecorView();
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbarBox",new LinearLayout(activity));set("navBox",new LinearLayout(activity));set("content",new ScrollView(activity));set("rootFrame",new FrameLayout(activity));set("overlay",new FrameLayout(activity));set("node",mock(NodeApi.class));screen("CREATE_COLLECTION");set("createMode","embed");set("createSize","2");set("createImages",new String[]{"",""});set("createName","Literal 中文 Русский Українська");
        ((ExecutorService)field("imageIo").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("imageIo",worker);doAnswer(i->{jobs.add(i.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("statenft_suite",0).edit().clear().commit();LocalStore.save(activity,new JSONArray());activity.getSharedPreferences(HostingStore.PREFS,0).edit().clear().commit();
        host=Hosting.Profile.fresh(Hosting.TYPE_WEBDAV);host.j.put("name","Original host").put("def",true);HostingStore.upsert(activity,host);
        uploader=mock(CloseableUploader.class);when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenReturn("https://example.invalid/uploaded.webp");
        hosts=mockStatic(Hosting.class,CALLS_REAL_METHODS);hosts.when(()->Hosting.forProfile(any(Hosting.Profile.class))).thenReturn(uploader);hosts.when(()->Hosting.verifyUrl(anyString(),any(Hosting.Profile.class))).thenAnswer(i->null);
        images=mockStatic(ImageTools.class,CALLS_REAL_METHODS);images.when(()->ImageTools.compressUri(eq(activity),eq(uri),anyInt())).thenReturn("QUJDRA==");images.when(()->ImageTools.isSvgUri(activity,uri)).thenReturn(false);images.when(()->ImageTools.squareBitmap(activity,uri,512)).thenReturn(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888));images.when(()->ImageTools.boundedBitmap(activity,uri,2048)).thenReturn(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888));
        loader=mockStatic(ImageLoader.class);service=mockStatic(MintService.class);
    }
    @After public void cleanup()throws Exception {call("cancelImageRequests",new Class<?>[]{});android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();images.close();loader.close();hosts.close();service.close();worker.shutdownNow();((android.os.Handler)field("main").get(activity)).removeCallbacksAndMessages(null);Language.select(activity,"en");}

    @Test public void retainedSelectionSurvivesAllFourLanguagesAndFillsOnlyItsSlots()throws Exception {
        pick();Object request=field("pendingImagePick").get(activity);assertNotNull(request);
        for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);assertSame(request,field("pendingImagePick").get(activity));}
        result();assertEquals(1,jobs.size());job();assertEquals("QUJDRA==",slots()[0]);assertEquals("",slots()[1]);assertNull(field("activeImagePick").get(activity));assertEquals("Literal 中文 Русский Українська",field("createName").get(activity));assertEquals(Language.msg(l10n_atelier_image_loaded,1).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void cancelledDuplicateAndUnownedResultsCannotStartWork()throws Exception {
        result();assertTrue(jobs.isEmpty());assertEquals(Language.msg(l10n_atelier_image_reselect).toString(),ShadowToast.getTextOfLatestToast());pick();Object selected=field("pendingImagePick").get(activity);hostPick();assertSame(selected,field("pendingImagePick").get(activity));activity.onActivityResult(7001,Activity.RESULT_CANCELED,null);assertNull(field("pendingImagePick").get(activity));pick();result();result();assertEquals(1,jobs.size());job();assertEquals("QUJDRA==",slots()[0]);
    }
    @Test public void newerDraftOrNavigationCannotReceiveAnOldDecode()throws Exception {
        pick();result();set("createName","Replacement draft");job();assertEquals("",slots()[0]);assertEquals("Replacement draft",field("createName").get(activity));
        pick();result();call("cancelImageRequests",new Class<?>[]{});set("screenGeneration",field("screenGeneration").getLong(activity)+1);job();assertEquals("",slots()[0]);assertNull(field("activeImagePick").get(activity));
    }
    @Test public void changedHostBeforeResultNeverDecodesOrUploads()throws Exception {
        hostPick();host.j.put("name","Changed host");HostingStore.upsert(activity,host);result();assertTrue(jobs.isEmpty());hosts.verify(()->Hosting.forProfile(any(Hosting.Profile.class)),never());
    }
    @Test public void changedDraftDuringDecodeStopsBeforeTransport()throws Exception {
        images.when(()->ImageTools.squareBitmap(activity,uri,512)).thenAnswer(i->{set("createIcon","typed while decoding");return Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);});hostPick();result();job();hosts.verify(()->Hosting.forProfile(any(Hosting.Profile.class)),never());assertEquals("typed while decoding",field("createIcon").get(activity));assertNull(field("activeImagePick").get(activity));
    }
    @Test public void successfulUploadUsesCapturedProfileAndRetainsTypedMetadata()throws Exception {
        hostPick();result();job();assertEquals("https://example.invalid/uploaded.webp",field("createIcon").get(activity));assertEquals("Literal 中文 Русский Українська",field("createName").get(activity));verify(uploader).close();ArgumentCaptor<Hosting.Profile> profile=ArgumentCaptor.forClass(Hosting.Profile.class);hosts.verify(()->Hosting.forProfile(profile.capture()));assertEquals(host.j.toString(),profile.getValue().j.toString());
    }
    @Test public void lateSuccessCannotReplaceAnEditedFieldAndStillLogsTheUploadedUrl()throws Exception {
        when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenAnswer(i->{set("createIcon","New typed URL");return "https://example.invalid/completed-old-upload.webp";});hostPick();result();job();assertEquals("New typed URL",field("createIcon").get(activity));verify(uploader).close();assertNull(field("activeImagePick").get(activity));assertTrue(activity.getSharedPreferences("statenft_suite",0).getAll().toString().contains("completed-old-upload.webp"));
    }
    @Test public void emptyReferenceAndNullErrorMessageCannotReportSuccess()throws Exception {
        when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenReturn("");hostPick();result();job();assertEquals("",field("createIcon").get(activity));assertEquals(Language.msg(l10n_atelier_image_failed,Language.msg(l10n_atelier_image_empty_reference)).toString(),ShadowToast.getTextOfLatestToast());
        when(uploader.putFile(any(byte[].class),anyString(),anyString())).thenThrow(new Hosting.HostingException(null));hostPick();result();job();assertEquals("",field("createIcon").get(activity));assertTrue(ShadowToast.getTextOfLatestToast().contains("HostingException"));verify(uploader,times(2)).close();
    }
    @Test public void plateCountMismatchAndUndecodableJpegNeverUpload()throws Exception {
        call("pickHostPlates",new Class<?>[]{int.class},2);result();assertTrue(jobs.isEmpty());assertEquals(Language.msg(l10n_atelier_image_exact_count,2,1).toString(),ShadowToast.getTextOfLatestToast());
        call("pickHostPlates",new Class<?>[]{int.class},1);images.when(()->ImageTools.boundedBitmap(activity,uri,2048)).thenReturn(null);result();job();hosts.verify(()->Hosting.forProfile(any(Hosting.Profile.class)),never());assertEquals("",field("createBase").get(activity));
    }
    @Test public void rejectedWorkerAndFinishingActivityPreserveTheDraft()throws Exception {
        pick();doThrow(new java.util.concurrent.RejectedExecutionException()).when(worker).execute(any(Runnable.class));result();assertNull(field("activeImagePick").get(activity));assertEquals("",slots()[0]);
        doAnswer(i->{jobs.add(i.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));pick();activity.finish();result();assertTrue(jobs.isEmpty());
    }
    @Test public void staleEditorCallbacksCannotReplaceDraftOrCloseNewOverlay()throws Exception {
        List<ImageEditor.Done> done=new ArrayList<>();List<Runnable> close=new ArrayList<>();List<String> accepted=new ArrayList<>();
        try(MockedStatic<ImageEditor> editor=mockStatic(ImageEditor.class)) {
            editor.when(()->ImageEditor.build(eq(activity),anyString(),anyInt(),any(),any())).thenAnswer(i->{close.add(i.getArgument(3));done.add(i.getArgument(4));return new FrameLayout(activity);});
            call("showEditor",new Class<?>[]{String.class,int.class,ImageEditor.Done.class},"first",1000,(ImageEditor.Done)accepted::add);call("showEditor",new Class<?>[]{String.class,int.class,ImageEditor.Done.class},"second",1000,(ImageEditor.Done)accepted::add);
            done.get(0).edited("stale");close.get(0).run();assertEquals(1,((FrameLayout)field("overlay").get(activity)).getChildCount());assertTrue(accepted.isEmpty());done.get(1).edited("current");assertEquals(Collections.singletonList("current"),accepted);
        }
    }
    java.util.concurrent.FutureTask<Boolean> costRequest()throws Exception {
        hostPick();result();jobs.clear();Object request=field("activeImagePick").get(activity);
        java.util.concurrent.FutureTask<Boolean> task=new java.util.concurrent.FutureTask<>(()->{
            try(MockedStatic<ArweaveUploader> costs=mockStatic(ArweaveUploader.class)){
                costs.when(()->ArweaveUploader.priceEstimate(200000)).thenReturn("0.25 AR");
                return (Boolean)call("confirmArweaveSpend",new Class<?>[]{request.getClass(),long.class,int.class},request,200000L,1);
            }
        });new Thread(task).start();
        for(int i=0;i<100;i++){idle();android.app.Dialog dialog=ShadowDialog.getLatestDialog();if(dialog!=null&&dialog.isShowing())return task;Thread.sleep(10);}
        fail("Cost dialog did not open");return task;
    }
    @Test public void costDialogRetainsAllLanguagesAndRequiresExplicitApproval()throws Exception {
        java.util.concurrent.FutureTask<Boolean> request=costRequest();androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)ShadowDialog.getLatestDialog();
        for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);assertEquals(Language.msg(l10n_atelier_image_upload).toString(),dialog.getButton(-1).getText().toString());assertEquals(Language.msg(l10n_atelier_image_cost,1,"0.2 MiB",200000L,"0.25 AR").toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());assertFalse(request.isDone());}
        dialog.getButton(-1).performClick();idle();assertTrue(request.get(2,java.util.concurrent.TimeUnit.SECONDS));verifyNoInteractions(uploader);
    }
    @Test public void changedDraftAndNavigationCannotApprovePaidUpload()throws Exception {
        java.util.concurrent.FutureTask<Boolean> request=costRequest();set("createName","New draft");((androidx.appcompat.app.AlertDialog)ShadowDialog.getLatestDialog()).getButton(-1).performClick();idle();assertFalse(request.get(2,java.util.concurrent.TimeUnit.SECONDS));idle();call("cancelImageRequests",new Class<?>[]{});set("activeImagePick",null);
        request=costRequest();call("cancelImageRequests",new Class<?>[]{});assertFalse(request.get(2,java.util.concurrent.TimeUnit.SECONDS));assertFalse(ShadowDialog.getLatestDialog().isShowing());verifyNoInteractions(uploader);
    }
    @Test public void knownImageErrorsUseRetainedTranslationsAndKeepBackendDetailsLiteral()throws Exception {
        List<CharSequence> labels=new ArrayList<>();String[] errors={"could not open the image","image is over 60MB","Empty upload reference","Upload stopped after navigation"};int[] ids={l10n_atelier_image_process_failed,l10n_atelier_image_too_large,l10n_atelier_image_empty_reference,l10n_atelier_image_changed};
        for(String error:errors)labels.add((CharSequence)call("imageError",new Class<?>[]{String.class},error));
        for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);for(int i=0;i<ids.length;i++)assertEquals(Language.msg(ids[i]).toString(),labels.get(i).toString());}
        assertEquals("Backend literal 中文",call("imageError",new Class<?>[]{String.class},"Backend literal 中文"));
    }

    private static TextView text(View root,String label){if(root instanceof TextView&&label.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView result=text(((ViewGroup)root).getChildAt(i),label);if(result!=null)return result;}return null;}
    @Test public void recoveryPanelTranslatesAndCannotChangeABurialInProgress()throws Exception {
        StateNft.Meta meta=new StateNft.Meta();meta.localId=41;meta.tokenid=BurialEvidenceTest.TOKEN;meta.mode="embed";meta.size=2;meta.phase="NEEDIMAGES";set("openMeta",meta);screen("COLLECTION");
        JSONObject row=MintEngine.rowFromMeta(meta,new JSONArray());LocalStore.save(activity,new JSONArray().put(row));
        View panel=(View)call("recoveryPanel",new Class<?>[]{StateNft.Meta.class},meta);TextView choose=text(panel,Language.upper(Language.msg(l10n_atelier_image_choose_missing)).toString());assertNotNull(choose);
        for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);Language.apply(panel);assertSame(choose,text(panel,Language.upper(Language.msg(l10n_atelier_image_choose_missing)).toString()));assertNotNull(text(panel,Language.msg(l10n_atelier_image_missing_count,2).toString()));}
        row.put("phase","BURY");LocalStore.save(activity,new JSONArray().put(row));choose.performClick();assertNull(field("pendingImagePick").get(activity));assertEquals("BURY",LocalStore.findById(activity,41).getString("phase"));service.verifyNoInteractions();
    }

}
