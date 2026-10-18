package com.eurobuddha.salon;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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

/** Real picker callbacks and stream handling; in-memory documents and fake keys only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonBackupLifecycleTest {
    private MainActivity activity;private ExecutorService worker;private ShadowContentResolver resolver;
    private final List<Runnable> jobs=new ArrayList<>();private MockedStatic<SalonComms> identity;private MockedStatic<MailDb> mail;
    private final Uri uri=Uri.parse("content://salon-test/backup");private static final String SEED="ab".repeat(32);
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private Object call(String n,Class<?>[] t,Object...a)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,a);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Object operation()throws Exception{return field("backupOperation").get(activity);}
    private String content(Object op)throws Exception{Field f=op.getClass().getDeclaredField("content");f.setAccessible(true);return(String)f.get(op);}
    private void next(){assertFalse(jobs.isEmpty());jobs.remove(0).run();idle();}
    private void save(String text)throws Exception{call("saveTextFile",new Class<?>[]{String.class,String.class},"original.json",text);}
    private void restore()throws Exception{call("restoreFromFile",new Class<?>[]{});}
    private int requestCode(int code){try{Object op=operation();if(op==null)return code;Field kind=op.getClass().getDeclaredField("code");kind.setAccessible(true);if(kind.getInt(op)!=code)return code;Field request=op.getClass().getDeclaredField("requestCode");request.setAccessible(true);return request.getInt(op);}catch(Exception e){throw new AssertionError(e);}}
    private void result(int code,int status,Uri value){activity.onActivityResult(requestCode(code),status,value==null?null:new Intent().setData(value));idle();}
    @SuppressWarnings({"rawtypes","unchecked"})@Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        field("body").set(activity,new LinearLayout(activity));field("appbar").set(activity,new LinearLayout(activity));field("navRow").set(activity,new LinearLayout(activity));field("screen").set(activity,Enum.valueOf((Class)field("screen").getType(),"SETTINGS"));
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);field("io").set(activity,worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();activity.getSharedPreferences(HostingStore.PREFS,Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"tokenid","original");SalonStore.put(activity,"handle","original");
        identity=mockStatic(SalonComms.class);identity.when(()->SalonComms.exportSeed(activity)).thenReturn(SEED);identity.when(()->SalonComms.checkedSeed(anyString())).thenCallRealMethod();mail=mockStatic(MailDb.class);MailDb db=mock(MailDb.class);when(db.contacts()).thenReturn(Collections.emptyList());mail.when(()->MailDb.get(activity)).thenReturn(db);
        resolver=Shadows.shadowOf(activity.getContentResolver());((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip();ShadowToast.reset();
    }
    @After public void cleanup(){android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();identity.close();mail.close();worker.shutdownNow();Language.select(activity,"en");}
    private static class Out extends ByteArrayOutputStream {boolean closed;@Override public void close()throws IOException{closed=true;super.close();}}
    private static class In extends ByteArrayInputStream {boolean closed;In(byte[] b){super(b);}@Override public void close()throws IOException{closed=true;super.close();}}
    private String backup()throws Exception{return new JSONObject().put("salonBackup",1).put("hosting",new JSONArray().put(new JSONObject().put("id","restored").put("type","blossom").put("name","Restored 中文"))).toString();}

    @Test public void cancelledSaveDiscardsPlaintextAndDuplicateResultCannotWrite()throws Exception{
        save("private test content");Object op=operation();assertEquals("private test content",content(op));result(51,Activity.RESULT_CANCELED,null);assertNull(operation());assertNull(content(op));result(51,Activity.RESULT_OK,uri);assertTrue(jobs.isEmpty());assertEquals(Language.msg(l10n_salon_backup_reselect).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void duplicateAndWrongKindResultsNeverConsumeAnotherOperation()throws Exception{
        save("original");Object original=operation();restore();assertSame(original,operation());assertEquals(Language.msg(l10n_salon_backup_busy).toString(),ShadowToast.getTextOfLatestToast());result(52,Activity.RESULT_OK,uri);assertSame(original,operation());assertTrue(jobs.isEmpty());Out out=new Out();resolver.registerOutputStream(uri,out);result(51,Activity.RESULT_OK,uri);result(51,Activity.RESULT_OK,uri);assertEquals(1,jobs.size());next();assertEquals("original",out.toString("UTF-8"));assertNull(operation());
    }
    @Test public void saveKeepsSnapshotAcrossLanguagesAndReportsOnlyAfterClose()throws Exception{
        String original="{\"draft\":\"中文 Русский Українська\"}";Out out=new Out(){@Override public void close()throws IOException{assertNotEquals(Language.msg(l10n_salon_settings_saved).toString(),ShadowToast.getTextOfLatestToast());super.close();}};resolver.registerOutputStream(uri,out);save(original);Object op=operation();for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);assertSame(op,operation());assertEquals(original,content(op));}result(51,Activity.RESULT_OK,uri);assertNull(content(op));next();assertTrue(out.closed);assertEquals(original,out.toString("UTF-8"));assertEquals(Language.msg(l10n_salon_settings_saved).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void closeFailureCannotProduceSavedSuccess()throws Exception{
        Out out=new Out(){@Override public void close()throws IOException{super.close();throw new IOException("provider failed to commit");}};resolver.registerOutputStream(uri,out);save("data");result(51,Activity.RESULT_OK,uri);next();assertTrue(out.closed);assertEquals(Language.msg(l10n_salon_backup_save_incomplete).toString(),ShadowToast.getTextOfLatestToast());assertNull(operation());
    }
    @Test public void forgedFileUriAndUnownedRestoreDoNotReadOrWrite()throws Exception{
        result(52,Activity.RESULT_OK,uri);assertTrue(jobs.isEmpty());restore();result(52,Activity.RESULT_OK,Uri.parse("file:///private/prefs"));assertTrue(jobs.isEmpty());assertNull(operation());assertEquals(Language.msg(l10n_salon_backup_document).toString(),ShadowToast.getTextOfLatestToast());save("x");result(51,Activity.RESULT_OK,Uri.parse("file:///private/prefs"));assertTrue(jobs.isEmpty());identity.verifyNoInteractions();
    }
    @Test public void accountChangeBeforeResultOrWorkerPreventsSave()throws Exception{
        for(boolean after:new boolean[]{false,true}){SalonStore.put(activity,"tokenid","original");Out out=new Out();resolver.registerOutputStream(uri,out);save("original key");if(after)result(51,Activity.RESULT_OK,uri);SalonStore.put(activity,"tokenid","replacement");if(after)next();else result(51,Activity.RESULT_OK,uri);assertEquals(0,out.size());assertEquals(Language.msg(l10n_salon_backup_changed).toString(),ShadowToast.getTextOfLatestToast());assertNull(operation());}
    }
    @Test public void changedAccountWhileProviderOpensCannotReceiveSecret()throws Exception{
        Out out=new Out();resolver.registerOutputStreamSupplier(uri,()->{SalonStore.put(activity,"tokenid","replacement");return out;});save("original key");result(51,Activity.RESULT_OK,uri);next();assertTrue(out.closed);assertEquals(0,out.size());assertEquals(Language.msg(l10n_salon_backup_save_incomplete).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void closureCancelsQueuedSaveAndClearsPendingSecret()throws Exception{
        Out out=new Out();resolver.registerOutputStream(uri,out);save("original key");Object op=operation();result(51,Activity.RESULT_OK,uri);call("cancelBackup",new Class<?>[]{});assertNull(operation());assertNull(content(op));next();assertEquals(0,out.size());assertNull(ShadowToast.getTextOfLatestToast());
    }
    @Test public void restoreReadsOnceAndKeepsLiteralProfileAndHostData()throws Exception{
        String original=SalonStore.me(activity).toString();In in=new In(backup().getBytes(StandardCharsets.UTF_8));resolver.registerInputStream(uri,in);restore();result(52,Activity.RESULT_OK,uri);result(52,Activity.RESULT_OK,uri);assertEquals(1,jobs.size());next();assertTrue(in.closed);assertEquals(original,SalonStore.me(activity).toString());assertEquals("Restored 中文",HostingStore.list(activity).get(0).name());assertNull(operation());
    }
    @Test public void accountChangeDuringReadCannotRestoreIntoReplacementIdentity()throws Exception{
        In in=new In(backup().getBytes(StandardCharsets.UTF_8)){@Override public synchronized int read(byte[] b,int o,int l){SalonStore.put(activity,"tokenid","replacement");return super.read(b,o,l);}};resolver.registerInputStream(uri,in);restore();result(52,Activity.RESULT_OK,uri);next();assertTrue(in.closed);assertTrue(HostingStore.list(activity).isEmpty());assertEquals("replacement",SalonStore.get(activity,"tokenid"));assertEquals(Language.msg(l10n_salon_backup_changed).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void oversizedReadStopsAtLimitAndAlwaysCloses()throws Exception{
        AtomicInteger bytes=new AtomicInteger();AtomicBoolean closed=new AtomicBoolean();InputStream in=new InputStream(){public int read(){bytes.incrementAndGet();return 'x';}public int read(byte[] b,int o,int l){Arrays.fill(b,o,o+l,(byte)'x');bytes.addAndGet(l);return l;}public void close(){closed.set(true);}};resolver.registerInputStream(uri,in);restore();result(52,Activity.RESULT_OK,uri);next();assertTrue(closed.get());assertTrue(bytes.get()>20*1024*1024);assertTrue(bytes.get()<=20*1024*1024+16384);assertTrue(HostingStore.list(activity).isEmpty());assertEquals(Language.msg(l10n_salon_backup_too_large).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void malformedUtf8AndReadOrCloseFailureNeverRestore()throws Exception{
        for(int mode=0;mode<3;mode++){In in=mode==0?new In(new byte[]{(byte)0xc3,(byte)0x28}):mode==1?new In(backup().getBytes(StandardCharsets.UTF_8)){@Override public synchronized int read(byte[] b,int o,int l){throw new IllegalStateException("read failure");}}:new In(backup().getBytes(StandardCharsets.UTF_8)){@Override public void close()throws IOException{super.close();throw new IOException("close failure");}};resolver.registerInputStream(uri,in);restore();result(52,Activity.RESULT_OK,uri);next();assertTrue(in.closed);assertTrue(HostingStore.list(activity).isEmpty());assertEquals(Language.msg(l10n_salon_backup_read_failed).toString(),ShadowToast.getTextOfLatestToast());}
    }
    @Test public void malformedOrUnavailableKeyCannotChangeDraftOrDestinations()throws Exception{
        SalonStore.put(activity,"tokenid","");String original=SalonStore.me(activity).toString();for(Object value:new Object[]{"invalid",new JSONArray(),SEED}){JSONObject b=new JSONObject(backup()).put("me",new JSONObject().put("handle","replacement").put("tokenid","new")).put("msgseed",value);call("restoreFromBackup",new Class<?>[]{String.class},b.toString());idle();assertEquals(original,SalonStore.me(activity).toString());assertTrue(HostingStore.list(activity).isEmpty());}identity.verify(()->SalonComms.importSeed(activity,SEED));assertEquals(Language.msg(l10n_salon_identity_restore_failed).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void staleKeyDialogCannotCopySaveOrImport()throws Exception{
        call("backupMsgKey",new Class<?>[]{});next();AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();SalonStore.put(activity,"tokenid","replacement");d.getButton(-3).performClick();idle();assertNull(((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip());assertEquals(Language.msg(l10n_salon_backup_changed).toString(),ShadowToast.getTextOfLatestToast());
        call("restoreMsgKey",new Class<?>[]{});d=(AlertDialog)ShadowDialog.getLatestDialog();SalonStore.put(activity,"tokenid","third");d.getButton(-1).performClick();idle();assertTrue(jobs.isEmpty());identity.verify(()->SalonComms.importSeed(any(),anyString()),never());
    }
    @Test public void changedAccountBeforeBackupPreparationCannotExportAnotherKey()throws Exception{
        call("backupAccount",new Class<?>[]{});SalonStore.put(activity,"tokenid","replacement");next();identity.verifyNoInteractions();assertNull(operation());assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }
    @Test public void lateResultFromCancelledPickerCannotClaimNewSave()throws Exception{
        save("old content");int oldCode=requestCode(51);result(51,Activity.RESULT_CANCELED,null);save("new content");Object current=operation();assertNotEquals(oldCode,requestCode(51));activity.onActivityResult(oldCode,Activity.RESULT_OK,new Intent().setData(uri));idle();assertSame(current,operation());assertEquals("new content",content(current));assertTrue(jobs.isEmpty());
    }
    @Test public void credentialPreparationDoesNotHoldProfileLockAndRechecksIdentity()throws Exception{
        JSONObject b=new JSONObject(backup());b.getJSONArray("hosting").getJSONObject(0).put("webdav",new JSONObject().put("password","secret"));
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)){
            crypt.when(()->Crypt.encrypt("secret")).thenAnswer(c->{assertFalse(Thread.holdsLock(SalonStore.class));SalonStore.put(activity,"tokenid","replacement");return "gcm:prepared";});
            resolver.registerInputStream(uri,new In(b.toString().getBytes(StandardCharsets.UTF_8)));restore();result(52,Activity.RESULT_OK,uri);next();assertTrue(HostingStore.list(activity).isEmpty());assertEquals(Language.msg(l10n_salon_backup_changed).toString(),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void rejectedWorkerReleasesOperationForRetry()throws Exception{
        doThrow(new RejectedExecutionException()).when(worker).execute(any(Runnable.class));call("backupAccount",new Class<?>[]{});assertNull(operation());assertEquals(Language.msg(l10n_salon_backup_unavailable).toString(),ShadowToast.getTextOfLatestToast());
    }
}
