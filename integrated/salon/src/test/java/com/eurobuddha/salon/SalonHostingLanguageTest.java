package com.eurobuddha.salon;

import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real hosting views and trust dialog, isolated from node/network and owner credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonHostingLanguageTest {
    private MainActivity activity;
    private LinearLayout body, appbar;
    private ExecutorService worker;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private void invoke(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);
    }
    @Before public void setup() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);
        field("body").set(activity,body);field("appbar").set(activity,appbar);
        ((ExecutorService)field("io").get(activity)).shutdownNow();
        worker=mock(ExecutorService.class);field("io").set(activity,worker);
        for(Hosting.Profile p:HostingStore.list(activity))HostingStore.remove(activity,p.id());
    }
    @After public void cleanup() throws Exception {
        android.app.Dialog dialog=ShadowDialog.getLatestDialog();if(dialog!=null)dialog.dismiss();
        Language.select(RuntimeEnvironment.getApplication(),"en");worker.shutdownNow();
    }
    private static List<EditText> edits(View root) {
        List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(edits(((ViewGroup)root).getChildAt(i)));
        return out;
    }
    private static boolean contains(View root,String value) {
        if(root instanceof TextView && value.equals(((TextView)root).getText().toString()))return true;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(contains(((ViewGroup)root).getChildAt(i),value))return true;
        return false;
    }
    private static void fits(View root) {
        if(root instanceof TextView && !(root instanceof EditText)) {
            TextView t=(TextView)root;
            if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());
        }
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));
    }
    private void layout() {
        for(int width:new int[]{320,480,900}) {
            body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            body.layout(0,0,width,body.getMeasuredHeight());fits(body);
            appbar.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            appbar.layout(0,0,width,appbar.getMeasuredHeight());fits(appbar);
        }
    }
    @Test public void deviceHostingExplainsAvailabilityAndLimitInEveryLanguage() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh(Hosting.TYPE_KEETS);field("hostEdit").set(activity,p);
        invoke("renderHostingEdit",new Class<?>[]{});
        String snapshot=p.j.toString();
        for(String code:CODES) {
            Language.select(activity,code);layout();
            assertTrue(contains(body,Language.text(activity,l10n_salon_keets_help)));
            Method error=MainActivity.class.getDeclaredMethod("mediaError",Exception.class);error.setAccessible(true);
            assertEquals(Language.text(activity,l10n_salon_keets_full),error.invoke(activity,new Exception(KeetsRef.FULL)).toString());
            assertEquals(snapshot,p.j.toString());
        }
        verifyNoInteractions(worker);
    }

    @Test public void allDestinationEditorsKeepDraftsAndCursorWhileLabelsChange() throws Exception {
        String[] types={"sftp","webdav","kubo","pinata","github","blossom","maxima","sftp"};
        int[] labels={l10n_creative_hosting_host,l10n_creative_hosting_write_endpoint,l10n_creative_hosting_api_user,l10n_creative_hosting_gateway,l10n_creative_hosting_owner,l10n_creative_hosting_blossom_server,l10n_creative_hosting_save_test,l10n_creative_hosting_private_key_pem};
        try(MockedStatic<NostrKeys> nostr=mockStatic(NostrKeys.class)) {
            nostr.when(NostrKeys::pubkeyHex).thenReturn("aabbcc");nostr.when(NostrKeys::npub).thenReturn("npub1example");
            for(int i=0;i<types.length;i++) {
                body.removeAllViews();Hosting.Profile p=Hosting.Profile.fresh(types[i]);field("hostEdit").set(activity,p);
                if(i==7)Hosting.put(p.cfg(),"auth","key");
                invoke("renderHostingEdit",new Class<?>[]{});
                List<EditText> inputs=edits(body);EditText name=inputs.get(0);
                name.setText("draft 中文 Русский Українська");name.setSelection(3);
                if(i==0){inputs.get(1).setText("literal-server.example");inputs.get(1).setSelection(4);}
                String config=p.j.toString();
                for(String code:CODES) {
                    Language.select(activity,code);layout();
                    assertTrue(code+types[i],contains(body,Language.upper(Language.msg(labels[i])).toString()));
                    assertTrue(contains(appbar,Language.upper(Language.msg(l10n_creative_hosting_edit_destination)).toString()));
                    assertEquals(Language.text(activity,l10n_creative_hosting_server_hint),name.getHint().toString());
                    assertEquals(config,p.j.toString());assertEquals(3,name.getSelectionStart());
                    if(i==0){assertEquals("literal-server.example",inputs.get(1).getText().toString());assertEquals(4,inputs.get(1).getSelectionStart());}
                    List<EditText> retained=edits(body);assertEquals(inputs.size(),retained.size());
                    for(int j=0;j<inputs.size();j++)assertSame(inputs.get(j),retained.get(j));
                }
            }
        }
        verifyNoInteractions(worker);
    }
    @Test public void destinationListAndEmptyStateTranslateWithoutChangingProfileNames() throws Exception {
        invoke("renderHosting",new Class<?>[]{});
        for(String code:CODES){Language.select(activity,code);assertTrue(contains(body,Language.text(activity,l10n_creative_hosting_empty)));layout();}
        Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.j,"name","Private destination 中文");Hosting.put(p.j,"def",true);HostingStore.upsert(activity,p);
        Hosting.Profile second=Hosting.Profile.fresh("webdav");Hosting.put(second.j,"id","second");HostingStore.upsert(activity,second);
        body.removeAllViews();invoke("renderHosting",new Class<?>[]{});
        for(String code:CODES) {
            Language.select(activity,code);layout();assertTrue(contains(body,p.name()));
            assertTrue(contains(body,Language.upper(Language.msg(l10n_creative_hosting_default)).toString()));
            assertTrue(contains(body,Language.upper(Language.msg(l10n_creative_hosting_test)).toString()));
            assertTrue(contains(body,Language.upper(Language.msg(l10n_creative_hosting_make_default)).toString()));
            assertEquals(p.j.toString(),HostingStore.list(activity).get(0).j.toString());
        }
    }
    @Test public void trustDialogRetainsHostAndFingerprintAndCancelNeverPins() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.cfg(),"host","my-host.example");
        TextView status=new TextView(activity);String fp="SHA256:abc123+/=";
        HostingStore.upsert(activity,p);
        invoke("promptTrustHostKey",new Class<?>[]{Hosting.Profile.class,String.class,TextView.class},p,fp,status);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(dialog);
        for(String code:CODES) {
            Language.select(activity,code);
            assertEquals(Language.msg(l10n_creative_hosting_trust_body,"my-host.example",fp).toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());
            assertEquals(Language.text(activity,l10n_creative_hosting_trust_pin),dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("",p.cfgStr("hostKeyFp"));
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        for(String code:CODES){Language.select(activity,code);assertEquals(Language.text(activity,l10n_creative_hosting_not_trusted),status.getText().toString());}
        assertEquals("",p.cfgStr("hostKeyFp"));verifyNoInteractions(worker);
    }
    @Test public void trustingPinsExactFingerprintThenSchedulesExistingTestPath() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.cfg(),"host","my-host.example");TextView status=new TextView(activity);
        HostingStore.upsert(activity,p);
        invoke("promptTrustHostKey",new Class<?>[]{Hosting.Profile.class,String.class,TextView.class},p,"SHA256:literal+/=",status);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();Language.select(activity,"uk");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("SHA256:literal+/=",p.cfgStr("hostKeyFp"));
        assertEquals("SHA256:literal+/=",HostingStore.list(activity).get(0).cfgStr("hostKeyFp"));
        verify(worker).execute(any(Runnable.class));
        for(String code:CODES){Language.select(activity,code);assertEquals(Language.text(activity,l10n_creative_hosting_testing),status.getText().toString());}
    }
    @Test public void uploadSuccessAndFailureMessagesRetainLiteralDetailsAcrossLanguages() throws Exception {
        Hosting.Profile p=Hosting.Profile.fresh("webdav");TextView status=new TextView(activity);
        String url="https://files.example/中文/test.txt", error="provider error 403: literal";
        HostingStore.upsert(activity,p);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(worker).execute(any(Runnable.class));
        try(MockedStatic<Hosting> hosting=mockStatic(Hosting.class,CALLS_REAL_METHODS)) {
            Hosting.Uploader uploader=mock(Hosting.Uploader.class);
            hosting.when(()->Hosting.verifyUrl(anyString(),any(Hosting.Profile.class))).thenAnswer(call->null);
            hosting.when(()->Hosting.forProfile(any(Hosting.Profile.class))).thenReturn(uploader);
            when(uploader.putFile(any(byte[].class),anyString(),eq("text/plain"))).thenReturn(url);
            invoke("testProfile",new Class<?>[]{Hosting.Profile.class,TextView.class},p,status);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_creative_hosting_tested,url).toString(),status.getText().toString());}
            verify(uploader).close();hosting.verify(()->Hosting.verifyUrl(eq(url),argThat(copy->copy.j.toString().equals(p.j.toString()))));
            hosting.when(()->Hosting.forProfile(any(Hosting.Profile.class))).thenThrow(new Hosting.HostingException(error));
            invoke("testProfile",new Class<?>[]{Hosting.Profile.class,TextView.class},p,status);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_creative_hosting_failed,error).toString(),status.getText().toString());}
        }
    }

}
