package com.eurobuddha.statenft;

import android.content.Intent;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/** Reuses Salon's actual-consent fixture; uploads and credentials are disposable mocks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierHostingOwnershipTest {
    MainActivity activity;
    Hosting.Profile profile;
    ExecutorService worker;
    List<Runnable> jobs=new ArrayList<>();
    static final String FP="SHA256:test-fingerprint";
    Field field(String n)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    void invoke(String name,Class<?>[] types,Object... args)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);
    }
    static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        ((ExecutorService)field("imageIo").get(activity)).shutdownNow();worker=mock(ExecutorService.class);field("imageIo").set(activity,worker);
        doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any());
        field("body").set(activity,new LinearLayout(activity));field("appbarBox").set(activity,new LinearLayout(activity));field("content").set(activity,new ScrollView(activity));
        activity.getSharedPreferences(HostingStore.PREFS,0).edit().clear().commit();
        profile=Hosting.Profile.fresh("sftp");Hosting.put(profile.cfg(),"host","original.example");HostingStore.upsert(activity,profile);
    }
    @After public void cleanup()throws Exception {invoke("cancelHostingCheck",new Class<?>[]{});worker.shutdownNow();Language.select(activity,"en");}
    void start()throws Exception {invoke("testProfile",new Class<?>[]{Hosting.Profile.class},profile);}
    AlertDialog trust()throws Exception {
        invoke("confirmHostKey",new Class<?>[]{Hosting.Profile.class,String.class},profile,FP);idle();return (AlertDialog)ShadowDialog.getLatestDialog();
    }
    String pin(){return HostingStore.listChecked(activity).get(0).cfgStr("hostKeyFp");}
    void click(AlertDialog d){d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();}
    @Test public void changedSavedHostAndDeletedHostCannotBeRestoredByTrust()throws Exception {
        AlertDialog d=trust();Hosting.Profile changed=new Hosting.Profile(new JSONObject(profile.j.toString()));
        Hosting.put(changed.cfg(),"host","replacement.example");HostingStore.upsert(activity,changed);click(d);
        assertEquals("replacement.example",HostingStore.listChecked(activity).get(0).cfgStr("host"));assertEquals("",pin());
        HostingStore.upsert(activity,profile);d=trust();HostingStore.remove(activity,profile.id());click(d);
        assertTrue(HostingStore.listChecked(activity).isEmpty());assertTrue(jobs.isEmpty());
    }
    @Test public void unsavedEditorChangeInvalidatesConsent()throws Exception {
        AlertDialog d=trust();Hosting.put(profile.cfg(),"user","another-user");click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());assertEquals("another-user",profile.cfgStr("user"));
    }
    @Test public void navigationCancellationAndRepeatedClicksCannotPinOrRetest()throws Exception {
        AlertDialog d=trust();invoke("renderHosting",new Class<?>[]{});assertFalse(d.isShowing());click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());
        d=trust();click(d);click(d);assertEquals(FP,pin());assertEquals(1,jobs.size());
    }
    @Test public void generationChangeOrFinishingActivityInvalidatesConsent()throws Exception {
        AlertDialog d=trust();field("screenGeneration").setLong(activity,field("screenGeneration").getLong(activity)+1);click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());
        d=trust();activity.finish();click(d);assertEquals("",pin());assertTrue(jobs.isEmpty());
    }
    @Test public void stoppedActivityDismissesConsentAndDoesNotRetry()throws Exception {
        // This fixture skips onCreate to avoid services; mock only the uninitialized fragment controller.
        androidx.fragment.app.FragmentController fragments=mock(androidx.fragment.app.FragmentController.class);
        when(fragments.getSupportFragmentManager()).thenReturn(mock(androidx.fragment.app.FragmentManager.class));
        org.robolectric.util.ReflectionHelpers.setField(activity,"mFragments",fragments);
        AlertDialog d=trust();invoke("onStop",new Class<?>[]{});assertFalse(d.isShowing());click(d);assertEquals("",pin());assertTrue(jobs.isEmpty());
        start();invoke("confirmHostKey",new Class<?>[]{Hosting.Profile.class,String.class},profile,FP);
        assertNull(field("hostingTrustDialog").get(activity));assertTrue(jobs.isEmpty());
    }
    @Test public void defaultSelectionIsPreservedWhileOnlyThePinChanges()throws Exception {
        Hosting.Profile other=Hosting.Profile.fresh("webdav");Hosting.put(other.j,"id","other");HostingStore.upsert(activity,other);
        AlertDialog d=trust();HostingStore.setDefault(activity,other.id());click(d);
        assertEquals(FP,pin());assertEquals("other",HostingStore.getDefault(activity).id());assertEquals(1,jobs.size());
    }
    @Test public void queuedTestDoesNotUploadAfterNavigationOrSavedProfileChanges()throws Exception {
        start();invoke("renderHosting",new Class<?>[]{});
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            hosting.when(()->Hosting.forProfile(any())).thenThrow(new AssertionError("Unexpected upload"));
            jobs.remove(0).run();hosting.verify(()->Hosting.forProfile(any()),never());
            start();HostingStore.remove(activity,profile.id());jobs.remove(0).run();hosting.verify(()->Hosting.forProfile(any()),never());
        }
    }
    Hosting.Uploader uploader() {return mock(Hosting.Uploader.class,withSettings().extraInterfaces(AutoCloseable.class));}
    @Test public void uploadUsesFrozenConfigAndLateResultCannotOverwriteEditedScreen()throws Exception {
        start();String original=profile.j.toString();Hosting.put(profile.cfg(),"host","typed-but-unsaved.example");
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader uploader=uploader();
            hosting.when(()->Hosting.forProfile(any())).thenAnswer(c->{Hosting.Profile copy=c.getArgument(0);assertNotSame(profile,copy);assertEquals(original,copy.j.toString());return uploader;});
            when(uploader.putFile(any(),anyString(),eq("text/plain"))).thenReturn("https://original.example/probe");
            hosting.when(()->Hosting.verifyUrl(anyString(),any())).thenAnswer(c->null);
            jobs.remove(0).run();idle();
            assertNull(field("hostingResultDialog").get(activity));assertEquals("typed-but-unsaved.example",profile.cfgStr("host"));verify((AutoCloseable)uploader).close();
        }
    }
    @Test public void lateUnknownHostResultCannotOpenDialogAfterSavedHostChanges()throws Exception {
        start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            hosting.when(()->Hosting.forProfile(any())).thenAnswer(c->{HostingStore.remove(activity,profile.id());throw new SftpUploader.HostKeyUnverified(FP);});
            jobs.remove(0).run();idle();assertNull(field("hostingTrustDialog").get(activity));
        }
    }
    @Test public void untrustedUploadShowsConsentAndDismissalDoesNotRetry()throws Exception {
        start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            hosting.when(()->Hosting.forProfile(any())).thenThrow(new SftpUploader.HostKeyUnverified(FP));
            jobs.remove(0).run();idle();AlertDialog dialog=(AlertDialog)field("hostingTrustDialog").get(activity);
            assertNotNull(dialog);assertTrue(dialog.isShowing());assertEquals("",pin());assertTrue(jobs.isEmpty());
            dialog.dismiss();idle();assertNull(field("hostingCheck").get(activity));assertNull(field("hostingTrustDialog").get(activity));assertTrue(jobs.isEmpty());
        }
    }
    @Test public void hostChangedWhileOpeningUploaderStopsUploadAndClosesIt()throws Exception {
        start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader uploader=uploader();
            hosting.when(()->Hosting.forProfile(any())).thenAnswer(c->{HostingStore.remove(activity,profile.id());return uploader;});
            jobs.remove(0).run();verify((AutoCloseable)uploader).close();verify(uploader,never()).putFile(any(),anyString(),anyString());
            hosting.verify(()->Hosting.verifyUrl(anyString(),any()),never());
        }
    }
    @Test public void failedPinSaveDoesNotChangeEditorOrRetryConnection()throws Exception {
        AlertDialog d=trust();
        try(MockedStatic<HostingStore> store=mockStatic(HostingStore.class,CALLS_REAL_METHODS)) {
            store.when(()->HostingStore.pinHostKey(eq(activity),any(),eq(FP))).thenReturn(false);click(d);
            assertEquals("",profile.cfgStr("hostKeyFp"));assertEquals("",pin());assertTrue(jobs.isEmpty());
        }
    }
    @Test public void rejectedWorkerAndAlreadyPinnedHostCannotStartAnotherTrustFlow()throws Exception {
        doThrow(new RejectedExecutionException()).when(worker).execute(any());start();assertNull(field("hostingCheck").get(activity));
        Hosting.put(profile.cfg(),"hostKeyFp","existing-pin");HostingStore.upsert(activity,profile);
        invoke("confirmHostKey",new Class<?>[]{Hosting.Profile.class,String.class},profile,FP);
        assertNull(field("hostingTrustDialog").get(activity));assertEquals("existing-pin",pin());
    }
    @Test public void arweaveReceiptSkipsFetchBackAndResultClosesOnNavigation()throws Exception {
        Hosting.put(profile.j,"type","arweave");HostingStore.upsert(activity,profile);start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader uploader=uploader();hosting.when(()->Hosting.forProfile(any())).thenReturn(uploader);
            String url="https://arweave.net/fixture";when(uploader.putFile(any(),anyString(),anyString())).thenReturn(url);
            jobs.remove(0).run();idle();hosting.verify(()->Hosting.verifyUrl(anyString(),any()),never());verify((AutoCloseable)uploader).close();
            AlertDialog d=(AlertDialog)field("hostingResultDialog").get(activity);assertTrue(d.isShowing());
            for(String code:new String[]{"en","zh-Hans","ru","uk"}) {
                Language.select(activity,code);assertEquals(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_hosting_turbo_tested,url).toString(),((TextView)d.findViewById(android.R.id.message)).getText().toString());
            }
            invoke("renderHosting",new Class<?>[]{});assertFalse(d.isShowing());
        }
    }
    @Test public void openCoreReusesHostTaskAndCancelsOutstandingConsent()throws Exception {
        AlertDialog d=trust();invoke("openMinimaCore",new Class<?>[]{});assertFalse(d.isShowing());click(d);assertEquals("",pin());assertTrue(jobs.isEmpty());
        Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(activity.getPackageName(),intent.getComponent().getPackageName());assertEquals("com.eurobuddha.minimacore.main.MainActivity",intent.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,intent.getFlags());assertNull(intent.getData());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }
}
