package com.eurobuddha.salon;

import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/** Actual consent UI and saved profiles; all uploads mocked, no real credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonHostingOwnershipTest {
    MainActivity activity;
    Hosting.Profile profile;
    TextView status;
    ExecutorService worker;
    List<Runnable> jobs=new ArrayList<>();
    static final String FP="SHA256:test-fingerprint";
    Field field(String n)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    void invoke(String name,Class<?>[] types,Object... args)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);
    }
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);field("io").set(activity,worker);
        doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any());
        activity.getSharedPreferences("salon_identity",0).edit().clear().commit();
        activity.getSharedPreferences(HostingStore.PREFS,0).edit().clear().commit();
        assertTrue(SalonStore.writeIdentity(activity,"gcm:fixture","original-key"));
        profile=Hosting.Profile.fresh("sftp");Hosting.put(profile.cfg(),"host","original.example");
        HostingStore.upsert(activity,profile);status=new TextView(activity);
    }
    @After public void cleanup()throws Exception {invoke("cancelHostingCheck",new Class<?>[]{});worker.shutdownNow();Language.select(activity,"en");}
    void start()throws Exception {invoke("testProfile",new Class<?>[]{Hosting.Profile.class,TextView.class},profile,status);}
    AlertDialog trust()throws Exception {
        invoke("promptTrustHostKey",new Class<?>[]{Hosting.Profile.class,String.class,TextView.class},profile,FP,status);
        Shadows.shadowOf(Looper.getMainLooper()).idle();return (AlertDialog)ShadowDialog.getLatestDialog();
    }
    String pin(){return HostingStore.listForBackup(activity).get(0).cfgStr("hostKeyFp");}
    void click(AlertDialog d){d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void accountReplacementCannotPinAnOldDialog()throws Exception {
        AlertDialog d=trust();assertTrue(SalonStore.writeIdentity(activity,"gcm:replacement","replacement"));click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());assertEquals("",profile.cfgStr("hostKeyFp"));
    }
    @Test public void changedSavedHostAndDeletedHostCannotBeRestoredByTrust()throws Exception {
        AlertDialog d=trust();Hosting.Profile changed=new Hosting.Profile(new org.json.JSONObject(profile.j.toString()));
        Hosting.put(changed.cfg(),"host","replacement.example");HostingStore.upsert(activity,changed);click(d);
        assertEquals("replacement.example",HostingStore.listForBackup(activity).get(0).cfgStr("host"));assertEquals("",pin());
        HostingStore.upsert(activity,profile);d=trust();HostingStore.remove(activity,profile.id());click(d);
        assertTrue(HostingStore.listForBackup(activity).isEmpty());assertTrue(jobs.isEmpty());
    }
    @Test public void unsavedEditorChangeInvalidatesConsent()throws Exception {
        AlertDialog d=trust();Hosting.put(profile.cfg(),"user","another-user");click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());assertEquals("another-user",profile.cfgStr("user"));
    }
    @Test public void navigationCancellationAndRepeatedClicksCannotPinOrRetest()throws Exception {
        AlertDialog d=trust();invoke("cancelHostingCheck",new Class<?>[]{});assertFalse(d.isShowing());click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());
        d=trust();click(d);click(d);assertEquals(FP,pin());assertEquals(1,jobs.size());
    }
    @Test public void epochChangeOrFinishingActivityInvalidatesConsent()throws Exception {
        AlertDialog d=trust();field("renderEpoch").setInt(activity,field("renderEpoch").getInt(activity)+1);click(d);
        assertEquals("",pin());assertTrue(jobs.isEmpty());
        d=trust();activity.finish();click(d);assertEquals("",pin());assertTrue(jobs.isEmpty());
    }
    @Test public void defaultSelectionIsPreservedWhileOnlyThePinChanges()throws Exception {
        Hosting.Profile other=Hosting.Profile.fresh("webdav");Hosting.put(other.j,"id","other");HostingStore.upsert(activity,other);
        AlertDialog d=trust();HostingStore.setDefault(activity,other.id());click(d);
        assertEquals(FP,pin());assertEquals("other",HostingStore.getDefault(activity).id());assertEquals(1,jobs.size());
    }
    @Test public void queuedTestDoesNotUploadAfterAccountOrSavedProfileChanges()throws Exception {
        start();assertTrue(SalonStore.writeIdentity(activity,"gcm:replacement","replacement"));
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            hosting.when(()->Hosting.forProfile(any())).thenThrow(new AssertionError("Unexpected upload"));
            jobs.remove(0).run();hosting.verify(()->Hosting.forProfile(any()),never());
            start();HostingStore.remove(activity,profile.id());jobs.remove(0).run();hosting.verify(()->Hosting.forProfile(any()),never());
        }
    }
    @Test public void uploadUsesFrozenConfigAndLateResultCannotOverwriteEditedScreen()throws Exception {
        start();String original=profile.j.toString();Hosting.put(profile.cfg(),"host","typed-but-unsaved.example");
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader uploader=mock(Hosting.Uploader.class);
            hosting.when(()->Hosting.forProfile(any())).thenAnswer(c->{Hosting.Profile copy=c.getArgument(0);assertNotSame(profile,copy);assertEquals(original,copy.j.toString());return uploader;});
            when(uploader.putFile(any(),anyString(),eq("text/plain"))).thenReturn("https://original.example/probe");
            hosting.when(()->Hosting.verifyUrl(anyString(),any())).thenAnswer(c->null);
            jobs.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_creative_hosting_testing),status.getText().toString());
            assertEquals("typed-but-unsaved.example",profile.cfgStr("host"));verify(uploader).close();
        }
    }
    @Test public void lateUnknownHostResultCannotOpenDialogForReplacedAccount()throws Exception {
        start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            hosting.when(()->Hosting.forProfile(any())).thenAnswer(c->{assertTrue(SalonStore.writeIdentity(activity,"gcm:replacement","replacement"));throw new SftpUploader.HostKeyUnverified(FP);});
            jobs.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(field("hostingTrustDialog").get(activity));assertEquals("",pin());
        }
    }
    @Test public void untrustedUploadShowsConsentAndDismissalDoesNotRetry()throws Exception {
        start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            hosting.when(()->Hosting.forProfile(any())).thenThrow(new SftpUploader.HostKeyUnverified(FP));
            jobs.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog=(AlertDialog)field("hostingTrustDialog").get(activity);
            assertNotNull(dialog);assertTrue(dialog.isShowing());assertEquals("",pin());assertTrue(jobs.isEmpty());
            dialog.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(field("hostingCheck").get(activity));assertNull(field("hostingTrustDialog").get(activity));assertTrue(jobs.isEmpty());
        }
    }
    @Test public void accountChangedWhileOpeningUploaderStopsUploadAndClosesIt()throws Exception {
        start();
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader uploader=mock(Hosting.Uploader.class);
            hosting.when(()->Hosting.forProfile(any())).thenAnswer(c->{assertTrue(SalonStore.writeIdentity(activity,"gcm:replacement","replacement"));return uploader;});
            jobs.remove(0).run();verify(uploader).close();verify(uploader,never()).putFile(any(),anyString(),anyString());
            hosting.verify(()->Hosting.verifyUrl(anyString(),any()),never());
        }
    }
    @Test public void failedPinSaveDoesNotChangeEditorOrRetryConnection()throws Exception {
        AlertDialog d=trust();
        try(MockedStatic<HostingStore> store=mockStatic(HostingStore.class,CALLS_REAL_METHODS)) {
            store.when(()->HostingStore.pinHostKey(eq(activity),any(),eq(FP))).thenReturn(false);click(d);
            assertEquals("",profile.cfgStr("hostKeyFp"));assertEquals("",pin());assertTrue(jobs.isEmpty());
            assertEquals(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage),status.getText().toString());
        }
    }
    @Test public void rejectedWorkerAndAlreadyPinnedHostCannotStartAnotherTrustFlow()throws Exception {
        doThrow(new RejectedExecutionException()).when(worker).execute(any());start();assertNull(field("hostingCheck").get(activity));
        assertEquals(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_storage),status.getText().toString());
        Hosting.put(profile.cfg(),"hostKeyFp","existing-pin");HostingStore.upsert(activity,profile);
        invoke("promptTrustHostKey",new Class<?>[]{Hosting.Profile.class,String.class,TextView.class},profile,FP,status);
        assertNull(field("hostingTrustDialog").get(activity));assertEquals("existing-pin",pin());
    }
}
