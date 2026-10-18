package com.eurobuddha.salon;

import android.content.*;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
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

/** Retained settings and key dialogs. No real keys, file writes, node calls or publication. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonSettingsLanguageTest {
    private MainActivity activity;private LinearLayout body,appbar;
    private final List<Runnable> jobs=new ArrayList<>();
    private MockedStatic<HostingStore> hosts;private MockedStatic<SalonComms> identity;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private static final String SEED="Ab".repeat(32);
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] t,Object...a)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,a);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @SuppressWarnings({"rawtypes","unchecked"})@Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbar",appbar);set("navRow",new LinearLayout(activity));set("screen",Enum.valueOf((Class)field("screen").getType(),"SETTINGS"));
        ((ExecutorService)field("io").get(activity)).shutdownNow();ExecutorService worker=mock(ExecutorService.class);set("io",worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"tokenid","0xoriginal");SalonStore.put(activity,"handle","Artist 中文");SalonStore.put(activity,"tipaddr","MxOriginalTipAddress");
        hosts=mockStatic(HostingStore.class);hosts.when(()->HostingStore.list(activity)).thenReturn(Collections.emptyList());hosts.when(()->HostingStore.listForBackup(activity)).thenReturn(Collections.emptyList());hosts.when(()->HostingStore.mergeForRestore(eq(activity),any(JSONArray.class))).thenAnswer(c->c.getArgument(1));identity=mockStatic(SalonComms.class);identity.when(()->SalonComms.exportSeed(activity)).thenReturn(SEED);identity.when(()->SalonComms.checkedSeed(anyString())).thenCallRealMethod();identity.when(()->SalonComms.restoreBackup(eq(activity),any(JSONObject.class),any(JSONArray.class),any(java.util.function.BooleanSupplier.class))).thenCallRealMethod();
    }
    @After public void cleanup()throws Exception{android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();hosts.close();identity.close();for(String name:new String[]{"CRYPTO","MSGPK"}){Field f=SalonComms.class.getDeclaredField(name);f.setAccessible(true);f.set(null,name.equals("MSGPK")?"":null);}NostrKeys.invalidate();((ExecutorService)field("io").get(activity)).shutdownNow();Language.select(activity,"en");}
    private static TextView find(View v,String value){if(v instanceof TextView&&value.equals(((TextView)v).getText().toString()))return(TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView f=find(((ViewGroup)v).getChildAt(i),value);if(f!=null)return f;}return null;}
    private static EditText input(View v){if(v instanceof EditText)return(EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText f=input(((ViewGroup)v).getChildAt(i));if(f!=null)return f;}return null;}
    private static void fits(View v){if(v instanceof TextView&&!(v instanceof EditText)){TextView t=(TextView)v;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)fits(((ViewGroup)v).getChildAt(i));}
    private void layout(){for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}}
    private AlertDialog dialog(){idle();return(AlertDialog)ShadowDialog.getLatestDialog();}
    private void next(){jobs.remove(0).run();idle();}
    private void render()throws Exception{call("render",new Class<?>[]{});}

    @Test public void settingsRetainsValuesAndFitsEachLanguage()throws Exception{
        Hosting.Profile host=new Hosting.Profile(new JSONObject().put("name","Моє сховище 中文").put("type","webdav"));hosts.when(()->HostingStore.getDefault(activity)).thenReturn(host);set("nodeUp",true);render();View first=body.getChildAt(0);String stored=SalonStore.me(activity).toString();
        for(String code:CODES){Language.select(activity,code);layout();assertSame(first,body.getChildAt(0));assertNotNull(find(appbar,upper(l10n_wallet_settings)));for(int id:new int[]{l10n_salon_settings_backup,l10n_salon_settings_backup_file,l10n_salon_settings_restore_file,l10n_salon_settings_backup_key,l10n_salon_settings_restore_key,l10n_salon_settings_background_on})assertNotNull(find(body,upper(id)));assertNotNull(find(body,Language.msg(l10n_wallet_connected).toString()));assertNotNull(find(body,"Моє сховище 中文 · webdav"));assertNotNull(find(body,"@Artist 中文"));assertNotNull(find(body,"0xoriginal"));assertEquals(stored,SalonStore.me(activity).toString());}
        find(body,"MxOriginalTipAddress").performClick();assertEquals("MxOriginalTipAddress",((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());identity.verifyNoInteractions();assertTrue(jobs.isEmpty());
    }
    @Test public void settingsEmptyAndDisabledStatesTranslate()throws Exception{
        SalonStore.put(activity,"keepalivebg","off");render();for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,upper(l10n_salon_settings_background_off)));assertNotNull(find(body,Language.msg(l10n_salon_settings_none).toString()));assertNotNull(find(body,Language.msg(l10n_wallet_not_connected).toString()));}
        SalonStore.put(activity,"tokenid","");render();assertNull(find(body,upper(l10n_salon_settings_restore_key)));assertNotNull(find(body,upper(l10n_salon_settings_restore_file)));
    }
    @Test public void backupDialogKeepsLiteralKeyAndMarksBothCopyPathsSensitive()throws Exception{
        call("backupMsgKey",new Class<?>[]{});next();AlertDialog d=dialog();View decor=d.getWindow().getDecorView();TextView key=find(decor,SEED);assertNotNull(key);
        for(String code:CODES){Language.select(activity,code);idle();assertSame(d,dialog());assertSame(key,find(decor,SEED));assertNotNull(find(decor,Language.msg(l10n_salon_settings_backup_key).toString()));assertNotNull(find(decor,Language.msg(l10n_salon_settings_key_help).toString()));assertEquals(Language.msg(l10n_salon_settings_save_file).toString(),d.getButton(-1).getText().toString());assertEquals(Language.msg(l10n_wallet_copy).toString(),d.getButton(-3).getText().toString());assertEquals(Language.msg(l10n_language_close).toString(),d.getButton(-2).getText().toString());}
        ClipboardManager cm=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);key.performClick();checkSecretClip(cm.getPrimaryClip());d.getButton(-3).performClick();idle();checkSecretClip(cm.getPrimaryClip());assertEquals(Language.msg(l10n_salon_settings_copied).toString(),ShadowToast.getTextOfLatestToast());
    }
    private void checkSecretClip(ClipData clip){assertEquals(SEED,clip.getItemAt(0).getText().toString());assertTrue(clip.getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));}
    @Test public void backupSaveKeepsBytesAndFilenameAfterLanguageSwitch()throws Exception{
        call("backupMsgKey",new Class<?>[]{});next();AlertDialog d=dialog();Language.select(activity,"uk");d.getButton(-1).performClick();idle();Intent intent=Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.getAction());assertEquals("salon-messaging-key.txt",intent.getStringExtra(Intent.EXTRA_TITLE));Object operation=field("backupOperation").get(activity);Field content=operation.getClass().getDeclaredField("content");content.setAccessible(true);assertEquals(SEED,content.get(operation));
    }
    @Test public void restoreDialogKeepsInputAndWorkerUsesConfirmedSnapshot()throws Exception{
        call("restoreMsgKey",new Class<?>[]{});AlertDialog d=dialog();EditText input=input(d.getWindow().getDecorView());String original=" 0x"+SEED+" ";input.setText(original);input.setSelection(4);
        for(String code:CODES){Language.select(activity,code);idle();assertSame(input,input(d.getWindow().getDecorView()));assertEquals(original,input.getText().toString());assertEquals(4,input.getSelectionStart());assertNotNull(find(d.getWindow().getDecorView(),upper(l10n_salon_settings_paste_key)));assertEquals(Language.msg(l10n_wallet_restore).toString(),d.getButton(-1).getText().toString());}
        d.getButton(-1).performClick();idle();input.setText("later edit");next();identity.verify(()->SalonComms.importSeed(activity,original));assertEquals(Language.msg(l10n_salon_identity_restore_failed).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void backupSchemaAndOriginalProfileAreIndependentOfLanguage()throws Exception{
        SalonStore.put(activity,"name","Моє ім’я 中文");SalonStore.put(activity,"bio","Original Русский");String profile=SalonStore.me(activity).toString();
        for(String code:CODES){Language.select(activity,code);JSONObject backup=new JSONObject((String)call("buildBackupJson",new Class<?>[]{}));BackupValidation.check(backup);assertEquals(1,backup.getInt("salonBackup"));assertEquals(SEED,backup.getString("msgseed"));assertEquals("Моє ім’я 中文",backup.getJSONObject("me").getString("name"));assertEquals("Original Русский",backup.getJSONObject("me").getString("bio"));assertEquals(profile,SalonStore.me(activity).toString());assertEquals(0,backup.getJSONArray("hosting").length());call("restoreFromBackup",new Class<?>[]{String.class},"{}");idle();assertEquals(Language.msg(l10n_salon_settings_invalid_backup).toString(),ShadowToast.getTextOfLatestToast());}
    }
    @Test public void restoreSummaryTranslatesCountAndKeyOutcome()throws Exception{
        try(MockedStatic<MailDb> mail=mockStatic(MailDb.class)){
            MailDb db=mock(MailDb.class);mail.when(()->MailDb.get(activity)).thenReturn(db);
            JSONObject backup=new JSONObject().put("salonBackup",1).put("msgseed","cd".repeat(32)).put("hosting",new JSONArray().put(new JSONObject().put("id","first").put("type","blossom")).put(new JSONObject().put("id","second").put("type","mesh")));
            identity.when(()->SalonComms.prepareSeed("cd".repeat(32))).thenReturn(new SalonComms.PreparedIdentity("gcm:test", "public-id", mock(LocalEcCryptoProvider.class)));identity.when(()->SalonComms.publicId(activity)).thenReturn("public-id");
            for(boolean keep:new boolean[]{true,false}){
                when(db.contacts()).thenReturn(keep?Arrays.asList(new MailDb.Contact()):Collections.emptyList());
                for(String code:CODES){Language.select(activity,code);call("restoreFromBackup",new Class<?>[]{String.class},backup.toString());idle();assertEquals(Language.join(Language.msg(l10n_salon_settings_restored_destinations,2),Language.msg(keep?l10n_salon_settings_key_kept_suffix:l10n_salon_settings_key_restored_suffix)).toString(),ShadowToast.getTextOfLatestToast());}
            }
        }
    }
    @Test public void finishingActivityDoesNotOpenDelayedKeyDialog()throws Exception{
        call("backupMsgKey",new Class<?>[]{});activity.finish();android.app.Dialog before=ShadowDialog.getLatestDialog();next();assertSame(before,ShadowDialog.getLatestDialog());
    }
    @Test public void tipQrTranslatesCaptionWithoutChangingAddress()throws Exception{
        try(MockedStatic<Qr> qr=mockStatic(Qr.class)){qr.when(()->Qr.bitmap("MxOriginalTipAddress",640)).thenReturn(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888));render();find(body,upper(l10n_salon_settings_tip_qr)).performClick();idle();android.app.Dialog d=ShadowDialog.getLatestDialog();for(String code:CODES){Language.select(activity,code);assertNotNull(find(d.getWindow().getDecorView(),Language.msg(l10n_salon_settings_tip_for,"Artist 中文").toString()));}qr.verify(()->Qr.bitmap("MxOriginalTipAddress",640));}
    }
}
