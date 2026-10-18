package com.eurobuddha.salon;

import android.content.Context;
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
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real retained editor and dialogs. No node commands or publishing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonEditorLanguageTest {
    private MainActivity activity;
    private LinearLayout body,appbar;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    @SuppressWarnings({"unchecked","rawtypes"})
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);
        field("body").set(activity,body);field("appbar").set(activity,appbar);field("navRow").set(activity,new LinearLayout(activity));
        field("screen").set(activity,Enum.valueOf((Class)field("screen").getType(),"EDIT"));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();
        SalonStore.put(activity,"handle","artist");SalonStore.put(activity,"tokenid","0xartist");
    }
    @After public void cleanup()throws Exception{
        android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();
        ((ExecutorService)field("io").get(activity)).shutdownNow();Language.select(activity,"en");
    }
    private void render()throws Exception{call("render",new Class<?>[]{});}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static List<EditText> inputs(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(inputs(((ViewGroup)root).getChildAt(i)));return out;}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView v=find(((ViewGroup)root).getChildAt(i),value);if(v!=null)return v;}return null;}
    private static void fits(View root){if(root instanceof TextView&&!(root instanceof EditText)){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText()+" w="+t.getWidth()+" h="+t.getHeight(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    private AlertDialog dialog(){idle();return(AlertDialog)ShadowDialog.getLatestDialog();}
    private View decor(AlertDialog d){return d.getWindow().getDecorView();}
    private JSONObject media(String caption)throws Exception{return new JSONObject().put("type","image").put("url","https://example.invalid/art.png").put("caption",caption);}

    @Test public void editorSwitchesAllLanguagesWithoutChangingDraftsMetadataOrCursors()throws Exception{
        JSONArray links=new JSONArray().put("malformed legacy row").put(new JSONObject().put("label","My original link").put("url","https://example.invalid"));
        JSONArray gallery=new JSONArray().put(media("My original caption")).put(new JSONObject().put("type","audio").put("caption",""));
        SalonStore.setArr(activity,"links",links);SalonStore.setArr(activity,"gallery",gallery);render();
        List<EditText> before=inputs(body);assertEquals(6,before.size());String[] drafts=new String[6];
        for(int i=0;i<6;i++){drafts[i]="Draft "+i+" 中文 Русский Українська";before.get(i).setText(drafts[i]);before.get(i).setSelection(3);}
        String stored=SalonStore.me(activity).toString();
        for(String code:CODES){
            Language.select(activity,code);
            assertNotNull(find(appbar,upper(l10n_salon_editor_title)));assertNotNull(find(body,upper(l10n_salon_editor_about)));assertNotNull(find(body,upper(l10n_salon_editor_publish)));
            assertNotNull(find(body,Language.msg(l10n_salon_editor_verify_help,"artist").toString()));
            assertNotNull(find(body,Language.msg(l10n_salon_editor_untitled).toString()));assertNotNull(find(body,upper(l10n_salon_editor_music)));
            assertNotNull(find(body,"My original link"));assertNotNull(find(body,"My original caption"));
            for(int width:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,width,body.getMeasuredHeight());fits(body);}
            for(int i=0;i<6;i++){assertSame(before.get(i),inputs(body).get(i));assertEquals(drafts[i],before.get(i).getText().toString());assertEquals(3,before.get(i).getSelectionStart());}
            assertEquals(stored,SalonStore.me(activity).toString());
        }
        call("commitEditFields",new Class<?>[]{});String[] keys={"name","bio","about","avatar","banner","webvalidate"};for(int i=0;i<6;i++)assertEquals(drafts[i],SalonStore.get(activity,keys[i]));
        assertEquals(links.toString(),SalonStore.arr(activity,"links").toString());assertEquals(gallery.toString(),SalonStore.arr(activity,"gallery").toString());
    }
    @Test public void linkDialogRetainsInputsAndAddsLiteralContentAfterSwitch()throws Exception{
        render();call("addLinkDialog",new Class<?>[]{});AlertDialog d=dialog();List<EditText> fields=inputs(decor(d));assertEquals(2,fields.size());
        fields.get(0).setText("Мій блог 中文");fields.get(1).setText("https://example.invalid/path?q=ru");for(EditText f:fields)f.setSelection(2);
        for(String code:CODES){Language.select(activity,code);idle();assertSame(d,dialog());assertNotNull(find(decor(d),Language.msg(l10n_salon_editor_add_link).toString()));assertEquals(Language.msg(l10n_common_cancel).toString(),d.getButton(-2).getText().toString());assertEquals(Language.msg(l10n_mail_add).toString(),d.getButton(-1).getText().toString());assertEquals(Language.msg(l10n_salon_editor_blog_hint).toString(),fields.get(0).getHint().toString());for(EditText f:fields)assertEquals(2,f.getSelectionStart());}
        d.getButton(-1).performClick();idle();JSONObject saved=SalonStore.arr(activity,"links").getJSONObject(0);assertEquals("Мій блог 中文",saved.getString("label"));assertEquals("https://example.invalid/path?q=ru",saved.getString("url"));
    }
    @Test public void postDialogKeepsMultilineDraftAndStoresItOnce()throws Exception{
        render();call("newPostDialog",new Class<?>[]{});AlertDialog d=dialog();EditText input=inputs(decor(d)).get(0);String text="My draft\n中文 Русский Українська";input.setText(text);input.setSelection(5);
        for(String code:CODES){Language.select(activity,code);idle();assertNotNull(find(decor(d),upper(l10n_salon_editor_say_something)));assertEquals(Language.msg(l10n_salon_editor_post).toString(),d.getButton(-1).getText().toString());assertSame(input,inputs(decor(d)).get(0));assertEquals(text,input.getText().toString());assertEquals(5,input.getSelectionStart());}
        d.getButton(-1).performClick();idle();JSONArray posts=SalonStore.arr(activity,"posts");assertEquals(1,posts.length());assertEquals(text,posts.getJSONObject(0).getString("text"));assertEquals(Language.msg(l10n_salon_editor_posted).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void renamePreservesMediaAndRejectsChangedGallerySnapshot()throws Exception{
        SalonStore.setArr(activity,"gallery",new JSONArray().put(media("original")));render();call("renameGalleryItem",new Class<?>[]{int.class},0);AlertDialog d=dialog();EditText input=inputs(decor(d)).get(0);input.setText("Нова назва 中文");input.setSelection(4);
        for(String code:CODES){Language.select(activity,code);idle();assertNotNull(find(decor(d),Language.msg(l10n_chat_rename).toString()));assertNotNull(find(decor(d),upper(l10n_salon_editor_caption)));assertEquals(4,input.getSelectionStart());assertEquals(Language.msg(l10n_common_save).toString(),d.getButton(-1).getText().toString());}
        d.getButton(-1).performClick();idle();assertEquals(media("Нова назва 中文").toString(),SalonStore.arr(activity,"gallery").getJSONObject(0).toString());
        call("renameGalleryItem",new Class<?>[]{int.class},0);d=dialog();inputs(decor(d)).get(0).setText("stale title");JSONArray newer=new JSONArray().put(media("newer draft")).put(media("second item"));SalonStore.setArr(activity,"gallery",newer);d.getButton(-1).performClick();idle();assertEquals(newer.toString(),SalonStore.arr(activity,"gallery").toString());assertEquals(Language.msg(l10n_salon_editor_changed).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void changedIdentityRejectsEachPendingEditorDialog()throws Exception{
        for(String method:new String[]{"addLinkDialog","newPostDialog","renameGalleryItem"}){
            SalonStore.put(activity,"tokenid","original");SalonStore.setArr(activity,"gallery",new JSONArray().put(media("original")));render();
            if(method.equals("renameGalleryItem"))call(method,new Class<?>[]{int.class},0);else call(method,new Class<?>[]{});
            AlertDialog d=dialog();for(EditText f:inputs(decor(d)))f.setText("stale");SalonStore.put(activity,"tokenid","replacement");String before=SalonStore.me(activity).toString();d.getButton(-1).performClick();idle();assertEquals(before,SalonStore.me(activity).toString());assertEquals(Language.msg(l10n_salon_editor_changed).toString(),ShadowToast.getTextOfLatestToast());
        }
    }
}
