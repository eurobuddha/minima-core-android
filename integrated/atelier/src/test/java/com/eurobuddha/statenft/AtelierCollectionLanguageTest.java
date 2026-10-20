package com.eurobuddha.statenft;

import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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
public class AtelierCollectionLanguageTest {
    private MainActivity activity;private LinearLayout body;private NodeApi node;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jp1sAAAAASUVORK5CYII=";
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void set(String name,Object value)throws Exception{field(name).set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render()throws Exception{call("renderCreateCollection",new Class<?>[]{});}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);
        for(String name:new String[]{"appbarBox","navBox"})set(name,new LinearLayout(activity));set("content",new ScrollView(activity));node=mock(NodeApi.class);set("node",node);
        HostingStore.list(activity);LocalStore.clearDraft(activity,"collection");new EditText(activity);
    }
    @After public void cleanup(){Language.select(activity,"en");}
    private void draft(boolean embedded)throws Exception{
        set("createName","Collection 中文 Русский Українська");set("createDesc","Original description");set("createSize","2");set("createMode",embedded?"embed":"url");set("createBase","https://example.invalid/typed/");set("createExt",".png");set("createUploadedBase","https://example.invalid/uploaded/");set("createUploadedExt",".png");set("createIcon","https://example.invalid/icon.png");set("createIconB64",PNG);set("createExternal","https://example.invalid/full");set("createWeb","https://example.invalid/validate");set("createImages",new String[]{PNG,PNG});set("collectionItemTraits",new JSONObject().put("1",new JSONArray().put(new JSONObject().put("trait_type","District").put("value","Лівий берег"))));
    }
    private static List<EditText> edits(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(edits(((ViewGroup)root).getChildAt(i)));return out;}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root){if(root instanceof TextView&&!(root instanceof EditText)){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText()+" width="+t.getWidth()+" height="+t.getHeight()+" textHeight="+t.getLayout().getHeight(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private void layout(){for(int w:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,w,body.getMeasuredHeight());fits(body);}}
    @Test public void hostedAndEmbeddedFormsRetainDraftImagesTraitsAndCursorsAcrossAllLanguages()throws Exception{
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            Hosting.Profile profile=Hosting.Profile.fresh("webdav");HostingStore.upsert(activity,profile);set("createWvAuto",true);
            for(boolean embed:new boolean[]{false,true}){
                draft(embed);render();List<EditText> inputs=edits(body);List<String> values=new ArrayList<>();for(EditText e:inputs){values.add(e.getText().toString());e.setSelection(Math.min(3,e.length()));}
                Object imageArray=field("createImages").get(activity);String before=(String)call("mintDraftSnapshot",new Class<?>[]{int.class},2);
                for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,upper(l10n_atelier_collection_mint)));assertEquals(before,call("mintDraftSnapshot",new Class<?>[]{int.class},2));assertSame(imageArray,field("createImages").get(activity));assertEquals(true,field("createWvAuto").get(activity));List<EditText> after=edits(body);for(int i=0;i<inputs.size();i++){assertSame(inputs.get(i),after.get(i));assertEquals(values.get(i),after.get(i).getText().toString());assertEquals(Math.min(3,values.get(i).length()),after.get(i).getSelectionStart());}}
            }
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void studioCardsAndDraftRowsTranslateWithoutChangingUserNames()throws Exception{
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
        draft(false);set("nftName","NFT literal");set("tokName","Token literal");call("renderStudio",new Class<?>[]{});
        for(String code:CODES){Language.select(activity,code);layout();for(int id:new int[]{l10n_atelier_collection_collection_title,l10n_atelier_collection_single_nft,l10n_atelier_collection_custom_token,l10n_atelier_collection_generative,l10n_atelier_collection_filtr})assertNotNull(find(body,upper(id)));assertNotNull(find(body,"Collection 中文 Русский Українська"));assertNotNull(find(body,"NFT literal"));assertNotNull(find(body,"Token literal"));assertNotNull(find(body,Language.upper(Language.msg(l10n_atelier_collection_draft,Language.msg(l10n_nft_collection))).toString()));}
        find(body,upper(l10n_logs_resume)).performClick();assertEquals("CREATE_COLLECTION",field("screen").get(activity).toString());assertEquals("Collection 中文 Русский Українська",field("createName").get(activity));
        }
    }
    private void reject(int id)throws Exception{render();find(body,upper(l10n_atelier_collection_mint)).performClick();assertEquals(Language.msg(id).toString(),ShadowToast.getTextOfLatestToast());}
    @Test public void validationMessagesUseSelectedLanguageBeforeAnyNodeRequest()throws Exception{
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            for(String code:CODES){Language.select(activity,code);draft(false);set("createName","");reject(l10n_atelier_create_name_required);set("createName","Valid");set("createDesc","x".repeat(201));reject(l10n_atelier_collection_description_range);set("createDesc","");set("createSize","1");reject(l10n_atelier_collection_editions_range);set("createSize","2");set("createBase","http://example.invalid/");reject(l10n_atelier_collection_base_https);set("createBase","https://bad.invalid/;quit");reject(l10n_atelier_create_safe_urls);
                set("createMode","embed");set("createBase","");set("createImages",new String[]{"",PNG});render();find(body,upper(l10n_atelier_collection_mint)).performClick();assertEquals(Language.msg(l10n_atelier_collection_missing_image,1).toString(),ShadowToast.getTextOfLatestToast());}
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void foreignBaseConfirmationShowsLiteralUrlAndCancelDoesNotMint()throws Exception{
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            draft(false);render();find(body,upper(l10n_atelier_collection_mint)).performClick();idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:CODES){Language.select(activity,code);assertNotNull(find(dialog.getWindow().getDecorView(),Language.msg(l10n_atelier_collection_base_confirm).toString()));assertEquals(Language.msg(l10n_atelier_collection_confirm_body,"https://example.invalid/typed/1.png").toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());}
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertNull(field("confirmedCollectionDraft").get(activity));assertEquals("https://example.invalid/typed/",field("createBase").get(activity));verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
        }
    }
    @Test public void plateActionsAndResetKeepTheirMeaningAcrossLanguages()throws Exception{
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            draft(true);render();View cell=(View)call("imageSlotCell",new Class<?>[]{int.class},0);cell.performClick();idle();AlertDialog choices=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:CODES){Language.select(activity,code);ListView list=choices.getListView();assertEquals(Language.msg(l10n_atelier_collection_clear_plate).toString(),list.getAdapter().getItem(2).toString());assertNotNull(find(choices.getWindow().getDecorView(),Language.msg(l10n_atelier_collection_plate,"01").toString()));}
            choices.getListView().performItemClick(null,2,2);idle();assertArrayEquals(new String[]{"",PNG},(String[])field("createImages").get(activity));assertEquals("Collection 中文 Русский Українська",field("createName").get(activity));
            find(body,upper(l10n_atelier_collection_reset_plates)).performClick();idle();AlertDialog reset=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_atelier_collection_reset_body).toString(),((TextView)reset.findViewById(android.R.id.message)).getText().toString());}
            reset.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertArrayEquals(new String[]{"",PNG},(String[])field("createImages").get(activity));
            find(body,upper(l10n_atelier_collection_reset_plates)).performClick();idle();reset=(AlertDialog)ShadowDialog.getLatestDialog();reset.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertArrayEquals(new String[]{null,null},(String[])field("createImages").get(activity));assertEquals("Original description",field("createDesc").get(activity));
        }
    }
    @Test public void uploadedBaseRecoveryTranslatesAndNeverOverwritesLaterEdits()throws Exception{
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedStatic<Hosting> hosting=mockStatic(Hosting.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            draft(false);render();call("offerUploadedBaseRecovery",new Class<?>[]{String.class,String.class,String.class},"https://example.invalid/typed/",".png","provider error");job.get().run();idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_atelier_collection_recovery_body,"https://example.invalid/typed/1.png","https://example.invalid/uploaded/1.png").toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());}
            set("createBase","https://example.invalid/new-draft/");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals("https://example.invalid/new-draft/",field("createBase").get(activity));
            call("offerUploadedBaseRecovery",new Class<?>[]{String.class,String.class,String.class},"https://example.invalid/new-draft/",".png","provider error");set("createUploadedBase","https://example.invalid/new-upload/");job.get().run();idle();assertSame(dialog,ShadowDialog.getLatestDialog());assertEquals("https://example.invalid/new-draft/",field("createBase").get(activity));
            call("offerUploadedBaseRecovery",new Class<?>[]{String.class,String.class,String.class},"https://example.invalid/new-draft/",".png","provider error");job.get().run();idle();dialog=(AlertDialog)ShadowDialog.getLatestDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals("https://example.invalid/new-upload/",field("createBase").get(activity));assertEquals(".png",field("createExt").get(activity));assertEquals("Original description",field("createDesc").get(activity));verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
        }
    }
    @Test public void mintEstimateUpdatesWithSizeAndLanguageWithoutSendingCommands()throws Exception {
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)) {
            draft(false);render();EditText size=edits(body).get(2);
            for(String count:new String[]{"2","12","20"}) {
                size.setText(count);int n=Integer.parseInt(count);
                for(String code:CODES) {
                    Language.select(activity,code);layout();
                    String message=Language.join(Language.msg(l10n_atelier_collection_mint_time_total,n,n*3,n*5),"\n",Language.msg(l10n_atelier_collection_mint_time_hint)).toString();
                    assertNotNull(find(body,message));assertEquals(count,size.getText().toString());
                }
            }
            size.setText("1");assertNotNull(find(body,Language.msg(l10n_atelier_collection_mint_time_hint).toString()));
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
}
