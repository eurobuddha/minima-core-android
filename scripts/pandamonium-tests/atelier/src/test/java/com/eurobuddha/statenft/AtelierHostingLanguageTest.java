package com.eurobuddha.statenft;

import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import org.mockito.*;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierHostingLanguageTest {
    private MainActivity activity;
    private LinearLayout body,appbar;
    private java.util.concurrent.ExecutorService worker;
    private final List<Runnable> hostingJobs=new ArrayList<>();
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f; }
    private void invoke(String name,Class<?>[] types,Object... args) throws Exception { Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args); }
    private static void idle(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    @Before public void setup() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        ((java.util.concurrent.ExecutorService)field("imageIo").get(activity)).shutdownNow();
        worker=mock(java.util.concurrent.ExecutorService.class);field("imageIo").set(activity,worker);
        doAnswer(c->{hostingJobs.add(c.getArgument(0));return null;}).when(worker).execute(any());
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);
        field("body").set(activity,body);field("appbarBox").set(activity,appbar);field("content").set(activity,new ScrollView(activity));
        for(Hosting.Profile p:HostingStore.list(activity))HostingStore.remove(activity,p.id());
    }
    @After public void cleanup() throws Exception { invoke("cancelHostingCheck",new Class<?>[]{});worker.shutdownNow(); android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();Language.select(activity,"en"); }
    private static List<EditText> edits(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(edits(((ViewGroup)root).getChildAt(i)));return out;}
    private static TextView find(View root,String value){if(root instanceof TextView && value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root) {
        if(root instanceof TextView && !(root instanceof EditText)){
            TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());
        }
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));
    }
    private void layout(){for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}
    private void render(Hosting.Profile p)throws Exception{invoke("renderHostingEdit",new Class<?>[]{Hosting.Profile.class},p);}
    @Test public void allBackendEditorsKeepDraftControlsAndProtocolValuesThroughLanguageChanges() throws Exception {
        String[] types={"sftp","webdav","kubo","pinata","github","arweave","sftp"};
        int[] labels={l10n_creative_hosting_host,l10n_atelier_hosting_webdav,l10n_creative_hosting_api_user,l10n_atelier_hosting_gateway_optional,l10n_atelier_hosting_pat,l10n_atelier_hosting_jwk,l10n_atelier_hosting_private_key};
        for(int i=0;i<types.length;i++) {
            Hosting.Profile p=Hosting.Profile.fresh(types[i]);if(i==6)Hosting.put(p.cfg(),"auth","key");render(p);
            List<EditText> inputs=edits(body);inputs.get(0).setText("My draft 中文 Русский Українська");inputs.get(0).setSelection(4);
            if(i==0){inputs.get(1).setText("my-server.example");inputs.get(1).setSelection(3);}
            String config=p.j.toString();
            for(String code:CODES){Language.select(activity,code);layout();assertNotNull(code+types[i],find(body,Language.upper(Language.msg(labels[i])).toString()));assertEquals(config,p.j.toString());assertEquals(4,inputs.get(0).getSelectionStart());assertEquals(Language.text(activity,l10n_atelier_hosting_server_hint),inputs.get(0).getHint().toString());if(i==0)assertEquals(3,inputs.get(1).getSelectionStart());List<EditText> retained=edits(body);assertEquals(inputs.size(),retained.size());for(int j=0;j<inputs.size();j++)assertSame(inputs.get(j),retained.get(j));}
        }
    }
    @Test public void storedSecretHintsFollowLanguageAndNeverRevealStoredValues() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.cfg(),"password","gcm:stored");render(p);
        EditText secret=edits(body).get(4);
        for(String code:CODES){Language.select(activity,code);assertEquals("",secret.getText().toString());assertEquals(Language.text(activity,l10n_atelier_hosting_stored),secret.getHint().toString());}
        Hosting.put(p.cfg(),"auth","key");Hosting.put(p.cfg(),"privateKey","gcm:key");render(p);secret=edits(body).get(4);
        for(String code:CODES){Language.select(activity,code);assertEquals("",secret.getText().toString());assertEquals(Language.text(activity,l10n_atelier_hosting_stored_paste),secret.getHint().toString());}
        assertEquals("gcm:stored",p.cfgStr("password"));assertEquals("gcm:key",p.cfgStr("privateKey"));
    }
    @Test public void destinationDeleteDialogPreservesNameAndRequiresItsExistingConfirmation() throws Exception {
        invoke("renderHosting",new Class<?>[]{});
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.text(activity,l10n_atelier_hosting_empty)));layout();}
        Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.j,"name","Literal 中文");HostingStore.upsert(activity,p);invoke("renderHosting",new Class<?>[]{});
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,p.name()));assertNotNull(find(body,Language.upper(Language.msg(l10n_creative_hosting_make_default)).toString()));}
        find(body,Language.upper(Language.msg(l10n_filez_delete)).toString()).performClick();idle();
        AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(dialog.getWindow().getDecorView(),Language.msg(l10n_atelier_hosting_delete,p.name()).toString()));assertEquals(1,HostingStore.list(activity).size());}
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertEquals(1,HostingStore.list(activity).size());
        find(body,Language.upper(Language.msg(l10n_filez_delete)).toString()).performClick();idle();dialog=(AlertDialog)ShadowDialog.getLatestDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertTrue(HostingStore.list(activity).isEmpty());
    }
    @Test public void hostKeyDialogKeepsFingerprintAndCancelDoesNotTrustIt() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("sftp");HostingStore.upsert(activity,p);String fp="SHA256:literal+/=";
        invoke("confirmHostKey",new Class<?>[]{Hosting.Profile.class,String.class},p,fp);idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
        for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_atelier_hosting_verify_body,fp).toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());assertEquals("",p.cfgStr("hostKeyFp"));}
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertEquals("",p.cfgStr("hostKeyFp"));
        invoke("confirmHostKey",new Class<?>[]{Hosting.Profile.class,String.class},p,fp);idle();dialog=(AlertDialog)ShadowDialog.getLatestDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
        assertEquals(fp,p.cfgStr("hostKeyFp"));assertEquals(fp,HostingStore.list(activity).get(0).cfgStr("hostKeyFp"));assertEquals(1,hostingJobs.size());
    }
    @Test public void testResultsTranslateAndFailedUploadClosesConnection() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.j,"name","Server literal");HostingStore.upsert(activity,p);String url="https://assets.example/中文/file";
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader up=mock(Hosting.Uploader.class,withSettings().extraInterfaces(AutoCloseable.class));hosting.when(()->Hosting.forProfile(any())).thenReturn(up);
            hosting.when(()->Hosting.verifyUrl(eq(url),any())).thenAnswer(c->null);
            when(up.putFile(any(byte[].class),anyString(),eq("text/plain"))).thenReturn(url);
            invoke("testProfile",new Class<?>[]{Hosting.Profile.class},p);hostingJobs.remove(0).run();idle();
            AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_atelier_hosting_tested,url).toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());}
            verify((AutoCloseable)up).close();hosting.verify(()->Hosting.verifyUrl(eq(url),any()));dialog.dismiss();
            when(up.putFile(any(byte[].class),anyString(),eq("text/plain"))).thenThrow(new Hosting.HostingException("provider 403"));
            invoke("testProfile",new Class<?>[]{Hosting.Profile.class},p);hostingJobs.remove(0).run();idle();dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_creative_hosting_failed,"provider 403").toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());}
            verify((AutoCloseable)up,times(2)).close();
        }
    }
    @Test public void walletBackupCopiesOnlyOnTapMarksSensitiveAndRejectsUnreadableData() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("arweave");Hosting.put(p.cfg(),"jwk","gcm:wallet");String fixture="fixture JWK stays literal";
        ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("before","unchanged"));
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class);MockedStatic<Ans104> ans=mockStatic(Ans104.class)) {
            crypt.when(()->Crypt.decrypt("gcm:wallet")).thenReturn(fixture);ans.when(()->Ans104.addressFromJwk(fixture)).thenReturn("a".repeat(43));render(p);
            for(String code:CODES){Language.select(activity,code);layout();assertEquals("unchanged",clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertNotNull(find(body,Language.upper(Language.msg(l10n_atelier_hosting_copy_backup)).toString()));}
            find(body,Language.upper(Language.msg(l10n_atelier_hosting_copy_backup)).toString()).performClick();
            assertEquals(fixture,clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertTrue(clipboard.getPrimaryClip().getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));ans.verify(()->Ans104.parseJwk(fixture));
            clipboard.setPrimaryClip(ClipData.newPlainText("before","unchanged"));crypt.when(()->Crypt.decrypt("gcm:wallet")).thenThrow(new Crypt.Unavailable());
            find(body,Language.upper(Language.msg(l10n_atelier_hosting_copy_backup)).toString()).performClick();assertEquals("unchanged",clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertEquals("gcm:wallet",p.cfgStr("jwk"));
            crypt.when(()->Crypt.decrypt("gcm:wallet")).thenReturn("malformed");ans.when(()->Ans104.parseJwk("malformed")).thenThrow(new IllegalArgumentException());
            find(body,Language.upper(Language.msg(l10n_atelier_hosting_copy_backup)).toString()).performClick();assertEquals("unchanged",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        }
    }
    @Test public void walletGenerationKeepsOriginalDestinationWhenLanguageOrEditorChanges() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("arweave");render(p);TextView generate=find(body,Language.upper(Language.msg(l10n_atelier_hosting_generate_wallet)).toString());
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedConstruction<Thread> threads=mockConstruction(Thread.class,(mock,ctx)->job.set((Runnable)ctx.arguments().get(0)));MockedStatic<Crypt> crypt=mockStatic(Crypt.class);MockedStatic<Ans104> ans=mockStatic(Ans104.class)) {
            ans.when(Ans104::generateJwkJson).thenReturn("fixture JWK");crypt.when(()->Crypt.encrypt("fixture JWK")).thenReturn("gcm:generated");
            generate.performClick();assertNotNull(job.get());
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.text(activity,l10n_atelier_hosting_generating),generate.getText().toString());assertFalse(generate.isEnabled());assertEquals("",p.cfgStr("jwk"));}
            Hosting.Profile other=Hosting.Profile.fresh("webdav");Hosting.put(other.j,"id","other-profile");render(other);
            job.get().run();idle();assertSame(other,field("hostEditProfile").get(activity));assertEquals("",other.cfgStr("jwk"));
            assertEquals("gcm:generated",p.cfgStr("jwk"));assertEquals("Arweave",p.name());assertEquals(1,HostingStore.list(activity).size());assertEquals(p.id(),HostingStore.list(activity).get(0).id());assertEquals("gcm:generated",HostingStore.list(activity).get(0).cfgStr("jwk"));
        }
    }
    @Test public void encryptionFailureMakesWalletGenerationRetryableWithoutSavingPlaintext() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("arweave");render(p);TextView generate=find(body,Language.upper(Language.msg(l10n_atelier_hosting_generate_wallet)).toString());
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedConstruction<Thread> threads=mockConstruction(Thread.class,(mock,ctx)->job.set((Runnable)ctx.arguments().get(0)));MockedStatic<Crypt> crypt=mockStatic(Crypt.class);MockedStatic<Ans104> ans=mockStatic(Ans104.class)) {
            ans.when(Ans104::generateJwkJson).thenReturn("fixture JWK");crypt.when(()->Crypt.encrypt("fixture JWK")).thenThrow(new Crypt.Unavailable());
            generate.performClick();job.get().run();idle();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.text(activity,l10n_atelier_hosting_generate_wallet),generate.getText().toString());assertTrue(generate.isEnabled());}
            assertEquals("",p.cfgStr("jwk"));assertTrue(HostingStore.list(activity).isEmpty());
        }
    }

}
