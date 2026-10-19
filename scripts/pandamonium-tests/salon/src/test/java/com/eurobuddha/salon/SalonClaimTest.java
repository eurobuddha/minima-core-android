package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonClaimTest {
    private MainActivity activity; private TextView status; private ExecutorService worker; private NodeApi node;
    private final List<Runnable> jobs=new ArrayList<>();
    private final List<String> commands=new ArrayList<>();
    private final List<NodeApi.Cb> callbacks=new ArrayList<>();
    private MockedStatic<ProfileHosting> hosting; private MockedStatic<MaximaLink> maxima;
    private MockedStatic<SalonRegistry> registry; private MockedStatic<MailDb> mail;
    private static final String URL="https://example.invalid/artist/profile.json", ID="0x"+"ab".repeat(32);
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void start()throws Exception{call("claimIdentity",new Class<?>[]{String.class,String.class,String.class,TextView.class},"artist","Literal 中文 Русский Українська","Bio",status);}
    private void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();idle();}
    private void reply(int i,JSONObject value){callbacks.get(i).onResult(value);idle();}
    private JSONObject balance(boolean found)throws Exception{
        JSONArray rows=new JSONArray();if(found)rows.put(new JSONObject().put("tokenid",ID).put("token",new JSONObject().put("salon","1").put("handle","artist").put("url",URL)));
        return new JSONObject().put("status",true).put("response",rows);
    }
    private void mint()throws Exception{start();job();reply(0,balance(false));assertTrue(commands.get(1).startsWith("tokencreate "));}
    private void languages(int id){for(String c:new String[]{"en","zh-Hans","ru","uk"}){Language.select(activity,c);assertEquals(Language.msg(id).toString(),status.getText().toString());}}
    @SuppressWarnings({"rawtypes","unchecked"})
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);status=new TextView(activity);
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);
        doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();activity.getSharedPreferences(HostingStore.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        HostingStore.upsert(activity,Hosting.Profile.fresh(Hosting.TYPE_WEBDAV));
        node=mock(NodeApi.class);set("node",node);set("nodeUp",true);set("pubkey","0x1234");
        doAnswer(c->{commands.add(c.getArgument(0));callbacks.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        set("body",new LinearLayout(activity));set("appbar",new LinearLayout(activity));set("navRow",new LinearLayout(activity));set("scroll",new androidx.core.widget.NestedScrollView(activity));set("claimBtn",new TextView(activity));
        set("screen",Enum.valueOf((Class)field("screen").getType(),"ONBOARD"));
        hosting=mockStatic(ProfileHosting.class);hosting.when(()->ProfileHosting.upload(any(),anyString(),anyString(),anyString())).thenReturn(URL);
        maxima=mockStatic(MaximaLink.class);maxima.when(()->MaximaLink.myAddresses(activity)).thenReturn("");
        registry=mockStatic(SalonRegistry.class);mail=mockStatic(MailDb.class);mail.when(()->MailDb.get(activity)).thenReturn(mock(MailDb.class));
    }
    @After public void cleanup()throws Exception{call("cancelIdentityClaim",new Class<?>[]{});hosting.close();maxima.close();registry.close();mail.close();worker.shutdownNow();Language.select(activity,"en");}
    @Test public void changedOwnerBeforeUploadPreventsNetworkAndPreservesReplacement()throws Exception{
        start();SalonStore.put(activity,"tokenid",ID);job();hosting.verifyNoInteractions();assertTrue(commands.isEmpty());assertEquals(ID,SalonStore.get(activity,"tokenid"));assertFalse((boolean)field("claiming").get(activity));
    }
    @Test public void changedAccountDuringUploadCannotSaveUrlOrDispatchLookup()throws Exception{
        start();hosting.when(()->ProfileHosting.upload(any(),anyString(),anyString(),anyString())).thenAnswer(c->{SalonStore.put(activity,"handle","replacement");return URL;});job();assertEquals("replacement",SalonStore.get(activity,"handle"));assertEquals("",SalonStore.get(activity,"profileUrl"));assertTrue(commands.isEmpty());languages(l10n_salon_publish_changed);
    }
    @Test public void successfulAbsenceIsRequiredAndDuplicateRepliesMintOnlyOnce()throws Exception{
        start();job();NodeApi.Cb lookup=callbacks.get(0);reply(0,balance(false));lookup.onResult(balance(false));lookup.onError("late");idle();
        assertEquals(2,commands.size());assertTrue(commands.get(1).contains(" amount:1 decimals:0 signtoken:0x1234"));assertTrue(commands.get(1).contains("Literal 中文 Русский Українська"));assertNotNull(SalonStore.identityRecord(activity).optJSONObject("claimPending"));
        hosting.verify(()->ProfileHosting.upload(any(),eq("artist"),argThat(v->v.contains("Literal 中文 Русский Українська")),anyString()));
    }
    @Test public void malformedOrTimedOutLookupNeverMints()throws Exception{
        for(JSONObject input:new JSONObject[]{null,new JSONObject().put("status",false),new JSONObject().put("status","true").put("response",new JSONArray()),new JSONObject().put("status",true).put("response",new JSONArray().put(JSONObject.NULL))}){
            start();job();int last=callbacks.size()-1;if(input==null)callbacks.get(last).onError("timeout");else reply(last,input);idle();assertFalse((boolean)field("claiming").get(activity));languages(l10n_creative_identity_lookup_failed);
        }
        assertTrue(commands.stream().allMatch("balance"::equals));
    }
    @Test public void lateLookupAfterAccountKeyNodeOrScreenChangeCannotMint()throws Exception{
        for(String change:new String[]{"account","key","node","screen"}){
            start();job();int last=callbacks.size()-1;
            if(change.equals("account"))SalonStore.put(activity,"handle","new");
            if(change.equals("key"))SalonStore.writeIdentity(activity,"wrapped:key","new-public");
            if(change.equals("node"))set("node",mock(NodeApi.class));
            if(change.equals("screen"))set("renderEpoch",99);
            reply(last,balance(false));assertTrue(commands.stream().allMatch("balance"::equals));set("node",node);
        }
    }
    @Test public void uncertainMintPersistsAndNextAttemptOnlyChecksBalance()throws Exception{
        mint();callbacks.get(1).onError("timeout");idle();languages(l10n_salon_claim_pending);assertNotNull(SalonStore.identityRecord(activity).optJSONObject("claimPending"));
        start();assertEquals("balance",commands.get(2));assertTrue(jobs.isEmpty());reply(2,balance(false));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));assertEquals("balance",commands.get(3));assertEquals(1,commands.stream().filter(s->s.startsWith("tokencreate ")).count());
    }
    @Test public void savedPendingAttemptSurvivesActivityReplacementWithoutMinting()throws Exception{
        mint();call("cancelIdentityClaim",new Class<?>[]{});MainActivity old=activity;
        activity=Robolectric.buildActivity(MainActivity.class).get();((ExecutorService)field("io").get(activity)).shutdownNow();set("io",worker);set("node",node);set("nodeUp",true);set("pubkey","0x1234");status=new TextView(activity);
        start();assertEquals("balance",commands.get(2));assertEquals(1,commands.stream().filter(s->s.startsWith("tokencreate ")).count());assertTrue(jobs.isEmpty());old.finish();
    }
    @Test public void malformedMintResultStaysPendingAndCannotClaimSuccess()throws Exception{
        mint();reply(1,new JSONObject().put("status","true"));languages(l10n_salon_claim_pending);assertEquals("",SalonStore.get(activity,"tokenid"));assertNotNull(SalonStore.identityRecord(activity).optJSONObject("claimPending"));
    }
    @Test public void explicitRejectionClearsPendingAndAllowsOwnerRetry()throws Exception{
        mint();reply(1,new JSONObject().put("status",false));languages(l10n_salon_claim_rejected);assertFalse(SalonStore.identityRecord(activity).has("claimPending"));start();assertEquals(1,jobs.size());
    }
    @Test public void pendingWriteFailurePreventsMintDispatch()throws Exception{
        start();job();try(MockedStatic<SalonStore> store=mockStatic(SalonStore.class,CALLS_REAL_METHODS)){
            store.when(()->SalonStore.saveClaim(eq(activity),anyString(),argThat(c->c.has("claimPending")))).thenReturn(false);
            reply(0,balance(false));assertEquals(Collections.singletonList("balance"),commands);languages(l10n_salon_publish_storage);
        }
    }
    @Test public void cancellingPollingRemovesTimerAndRejectsLateReplies()throws Exception{
        mint();reply(1,new JSONObject().put("status",true));reply(2,balance(false));call("cancelIdentityClaim",new Class<?>[]{});
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));reply(2,balance(true));assertEquals(3,commands.size());assertEquals("",SalonStore.get(activity,"tokenid"));assertNotNull(SalonStore.identityRecord(activity).optJSONObject("claimPending"));
    }
    @Test public void automaticAdoptionCannotOverwriteAnAccountCreatedAfterLookup()throws Exception{
        call("checkIdentityAdoption",new Class<?>[]{});SalonStore.put(activity,"tokenid","0xcd");reply(0,balance(true));assertEquals("0xcd",SalonStore.get(activity,"tokenid"));registry.verifyNoInteractions();
    }
    @Test public void successfulAdoptionIsSavedOnceAndPreservesKeys()throws Exception{
        SalonStore.writeIdentity(activity,"wrapped:owned","owned-public");start();job();reply(0,balance(true));String saved=SalonStore.identityRecord(activity).toString();reply(0,balance(true));
        assertEquals(ID,SalonStore.get(activity,"tokenid"));assertEquals("wrapped:owned",SalonStore.get(activity,"msgseed"));assertEquals(saved,SalonStore.identityRecord(activity).toString());assertFalse(SalonStore.identityRecord(activity).has("claimPending"));
    }
    @Test public void savedClaimRejectsStaleSnapshotAndRollsBackFailedCommit()throws Exception{
        JSONObject before=SalonStore.identityRecord(activity);String snapshot=SalonStore.publicationSnapshot(before);
        SalonStore.put(activity,"name","new draft");assertFalse(SalonStore.saveClaim(activity,snapshot,new JSONObject().put("tokenid",ID)));assertEquals("",SalonStore.get(activity,"tokenid"));
        SalonBackupPersistenceTest.Prefs prefs=new SalonBackupPersistenceTest.Prefs();prefs.seed("me",before.toString());prefs.failures=1;Context fake=mock(Context.class);when(fake.getSharedPreferences("salon_identity",0)).thenReturn(prefs.prefs);
        assertFalse(SalonStore.saveClaim(fake,snapshot,new JSONObject().put("tokenid",ID)));assertEquals(before.toString(),prefs.memory.get("me"));assertEquals(before.toString(),prefs.disk.get("me"));
    }
    @Test public void onboardInputsAndLanguageSurvivePendingSetup()throws Exception{
        call("renderOnboard",new Class<?>[]{});
        EditText handle=(EditText)field("claimHandleField").get(activity),name=(EditText)field("claimNameField").get(activity),bio=(EditText)field("claimBioField").get(activity);
        handle.setText("artist");name.setText("Literal 中文 Русский Українська");bio.setText("Bio");name.setSelection(3);
        ((TextView)field("claimBtn").get(activity)).performClick();
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){
            Language.select(activity,code);assertSame(name,field("claimNameField").get(activity));assertEquals("Literal 中文 Русский Українська",name.getText().toString());assertEquals(3,name.getSelectionStart());assertFalse(name.isEnabled());assertFalse(handle.isEnabled());assertFalse(bio.isEnabled());
        }
        call("cancelIdentityClaim",new Class<?>[]{});assertTrue(name.isEnabled());assertEquals(3,name.getSelectionStart());
    }

    @Test public void confirmedRetryOnlyUnlocksAndRetainsFourLanguageDialog()throws Exception{
        mint();callbacks.get(1).onError("timeout");idle();call("confirmClaimRetry",new Class<?>[]{});idle();
        androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field("claimRetryDialog").get(activity);
        assertNotNull(dialog);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){
            Language.select(activity,code);assertSame(dialog,field("claimRetryDialog").get(activity));assertEquals(Language.msg(l10n_salon_claim_retry_title).toString(),dialog.getButton(-1).getText().toString());
            assertEquals(Language.msg(l10n_salon_claim_retry_warning).toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());
        }
        assertEquals(2,commands.size());dialog.getButton(-1).performClick();idle();assertEquals(2,commands.size());assertFalse(SalonStore.identityRecord(activity).has("claimPending"));assertTrue(jobs.isEmpty());
        start();assertEquals(1,jobs.size());
    }
    @Test public void staleRetryConfirmationCannotUnlockAnotherAccount()throws Exception{
        mint();callbacks.get(1).onError("timeout");idle();call("confirmClaimRetry",new Class<?>[]{});idle();
        androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field("claimRetryDialog").get(activity);
        SalonStore.put(activity,"handle","replacement");String before=SalonStore.identityRecord(activity).toString();dialog.getButton(-1).performClick();idle();assertEquals(before,SalonStore.identityRecord(activity).toString());assertEquals(2,commands.size());
    }
    @Test public void acceptedMintPollsOnceAndClearsPendingOnlyAfterConfirmedBalance()throws Exception{
        mint();reply(1,new JSONObject().put("status",true));reply(1,new JSONObject().put("status",true));assertEquals(3,commands.size());assertNotNull(SalonStore.identityRecord(activity).optJSONObject("claimPending"));
        reply(2,balance(true));assertEquals(ID,SalonStore.get(activity,"tokenid"));assertFalse(SalonStore.identityRecord(activity).has("claimPending"));assertFalse((boolean)field("claiming").get(activity));
    }

    @Test public void accountChangeAfterPendingSaveStillPreventsDispatch()throws Exception{
        start();job();java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        try(MockedStatic<Language> language=mockStatic(Language.class,CALLS_REAL_METHODS)) {
            language.when(()->Language.setText(eq(status),any(CharSequence.class))).thenAnswer(c->{
                if(SalonStore.identityRecord(activity).has("claimPending") && changed.compareAndSet(false,true)) SalonStore.put(activity,"handle","replacement");
                return c.callRealMethod();
            });
            reply(0,balance(false));assertTrue(changed.get());assertEquals(Collections.singletonList("balance"),commands);assertEquals("replacement",SalonStore.get(activity,"handle"));
        }
    }
    @Test public void automaticAdoptionPersistsCompleteIdentityAndConsumesReplyOnce()throws Exception{
        SalonStore.writeIdentity(activity,"wrapped:owned","owned-public");call("checkIdentityAdoption",new Class<?>[]{});reply(0,balance(true));
        assertEquals(ID,SalonStore.get(activity,"tokenid"));assertEquals("artist",SalonStore.get(activity,"handle"));assertEquals(URL,SalonStore.get(activity,"profileUrl"));assertEquals("wrapped:owned",SalonStore.get(activity,"msgseed"));
        SalonStore.put(activity,"name","new edit");reply(0,balance(true));assertEquals("new edit",SalonStore.get(activity,"name"));assertEquals(1,commands.stream().filter("balance"::equals).count());
    }

    @Test public void failedAutomaticLookupCanRetryWithoutAnotherActivity()throws Exception {
        set("wasNodeUp",true);call("onNode",new Class<?>[]{});
        assertFalse((boolean)field("adoptChecked").get(activity));
        callbacks.get(0).onError("timeout");idle();call("onNode",new Class<?>[]{});
        assertEquals(Arrays.asList("balance","balance"),commands);
        reply(1,balance(false));assertTrue((boolean)field("adoptChecked").get(activity));
        call("onNode",new Class<?>[]{});assertEquals(2,commands.size());
    }
    @Test public void malformedAutomaticLookupDoesNotMarkAdoptionChecked()throws Exception {
        for(JSONObject invalid:new JSONObject[]{new JSONObject(),new JSONObject().put("status",false),
                new JSONObject().put("status",true).put("response",new JSONArray().put(JSONObject.NULL))}) {
            call("checkIdentityAdoption",new Class<?>[]{});reply(callbacks.size()-1,invalid);
            assertFalse((boolean)field("adoptChecked").get(activity));
        }
        call("checkIdentityAdoption",new Class<?>[]{});reply(callbacks.size()-1,balance(true));
        assertEquals(ID,SalonStore.get(activity,"tokenid"));assertTrue((boolean)field("adoptChecked").get(activity));
    }

}
