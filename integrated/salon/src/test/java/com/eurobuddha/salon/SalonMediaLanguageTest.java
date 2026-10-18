package com.eurobuddha.salon;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.io.File;
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

/** Real gallery widgets and retained labels; mock network/media engine, no playback or upload. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonMediaLanguageTest {
    private MainActivity activity;private LinearLayout body;
    private final List<Runnable> jobs=new ArrayList<>();
    private MockedStatic<ImageLoader> images;private MockedStatic<RelayResolver> relay;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @SuppressWarnings({"rawtypes","unchecked"})
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbar",new LinearLayout(activity));set("navRow",new LinearLayout(activity));set("screen",Enum.valueOf((Class)field("screen").getType(),"EDIT"));
        ((ExecutorService)field("io").get(activity)).shutdownNow();ExecutorService executor=mock(ExecutorService.class);set("io",executor);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(executor).execute(any(Runnable.class));
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"tokenid","0xowner");SalonStore.put(activity,"handle","owner");
        images=mockStatic(ImageLoader.class);relay=mockStatic(RelayResolver.class);call("buildMiniPlayer",new Class<?>[]{});
    }
    @After public void cleanup()throws Exception{call("stopAudio",new Class<?>[]{});android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();images.close();relay.close();Language.select(activity,"en");}
    private static TextView find(View root,String text){if(root instanceof TextView&&text.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView v=find(((ViewGroup)root).getChildAt(i),text);if(v!=null)return v;}return null;}
    private static View described(View root,String text){if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=described(((ViewGroup)root).getChildAt(i),text);if(v!=null)return v;}return null;}
    private static List<EditText> inputs(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(inputs(((ViewGroup)root).getChildAt(i)));return out;}
    private JSONObject media(String type,String caption,String url)throws Exception{return new JSONObject().put("type",type).put("caption",caption).put("url",url);}
    private TextView title()throws Exception{return(TextView)field("miniTitle").get(activity);}
    private TextView time()throws Exception{return(TextView)field("miniTime").get(activity);}
    private View player()throws Exception{return(View)field("miniPlayer").get(activity);}
    private void track(String url,CharSequence title)throws Exception{call("playTrack",new Class<?>[]{String.class,CharSequence.class},url,title);}

    @Test public void galleryLabelsAndCarouselSelectionTranslateWithoutChangingContent()throws Exception{
        JSONObject photo=media("image","Original 中文 Русский Українська","https://example.invalid/image"),video=media("video","Original video","https://example.invalid/video"),audio=media("audio","","https://example.invalid/audio");JSONArray gallery=new JSONArray().put(photo).put(video).put(audio);String before=gallery.toString();
        call("renderGallery",new Class<?>[]{LinearLayout.class,JSONArray.class},body,gallery);
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,Language.upper(Language.msg(l10n_salon_media_photos,1)).toString()));assertNotNull(find(body,Language.upper(Language.msg(l10n_salon_media_videos,1)).toString()));assertNotNull(find(body,Language.upper(Language.msg(l10n_salon_media_tracks,1)).toString()));assertNotNull(find(body,Language.msg(l10n_salon_media_untitled).toString()));assertNotNull(find(body,photo.getString("caption")));assertEquals(before,gallery.toString());}
        List<JSONObject> photos=Arrays.asList(photo,media("image","Second original","https://example.invalid/second"));call("openCarousel",new Class<?>[]{List.class,int.class},photos,0);android.app.Dialog d=ShadowDialog.getLatestDialog();View root=d.getWindow().getDecorView();
        described(root,Language.msg(l10n_common_next).toString()).performClick();
        for(String code:CODES){Language.select(activity,code);assertSame(d,ShadowDialog.getLatestDialog());assertNotNull(find(root,"Second original"));assertNotNull(find(root,Language.msg(l10n_salon_media_position,2,2).toString()));assertNotNull(described(root,Language.msg(l10n_salon_media_previous).toString()));assertNotNull(described(root,Language.msg(l10n_language_close).toString()));}
        images.verify(()->ImageLoader.loadFull(eq(activity),eq("https://example.invalid/second"),any(ImageView.class)),times(1));assertEquals(before,gallery.toString());
    }
    @Test public void captionDialogRetainsTypingAndRejectsChangedIdentity()throws Exception{
        call("promptMediaTitle",new Class<?>[]{String.class,String.class},"audio","https://example.invalid/track");idle();AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();EditText input=inputs(d.getWindow().getDecorView()).get(0);input.setText("My title 中文");input.setSelection(3);
        for(String code:CODES){Language.select(activity,code);idle();assertEquals(Language.msg(l10n_salon_media_song_hint).toString(),input.getHint().toString());assertEquals(3,input.getSelectionStart());assertEquals("My title 中文",input.getText().toString());assertNotNull(find(d.getWindow().getDecorView(),Language.msg(l10n_salon_media_add_gallery).toString()));assertEquals(Language.msg(l10n_salon_media_skip_title).toString(),d.getButton(-2).getText().toString());}
        d.getButton(-1).performClick();idle();JSONObject added=SalonStore.arr(activity,"gallery").getJSONObject(0);assertEquals("audio",added.getString("type"));assertEquals("My title 中文",added.getString("caption"));assertEquals("https://example.invalid/track",added.getString("url"));
        call("promptMediaTitle",new Class<?>[]{String.class,String.class},"video","https://example.invalid/video");idle();d=(AlertDialog)ShadowDialog.getLatestDialog();SalonStore.put(activity,"tokenid","replacement");String before=SalonStore.me(activity).toString();d.getButton(-2).performClick();idle();assertEquals(before,SalonStore.me(activity).toString());
    }
    @Test public void languageSwitchRetainsPlayingSourceTitleSeekAndEngine()throws Exception{
        try(MockedConstruction<MediaPlayer> construction=mockConstruction(MediaPlayer.class)){
            track("https://example.invalid/audio",Language.msg(l10n_salon_media_untitled));MediaPlayer mp=construction.constructed().get(0);when(mp.isPlaying()).thenReturn(true);call("updateMiniPlay",new Class<?>[]{});
            SeekBar seek=(SeekBar)field("miniBar").get(activity);seek.setProgress(321);TextView title=title();
            for(String code:CODES){Language.select(activity,code);assertSame(title,title());assertEquals(Language.msg(l10n_salon_media_untitled).toString(),title.getText().toString());assertEquals(321,seek.getProgress());assertSame(mp,field("audio").get(activity));assertNotNull(described(player(),Language.msg(l10n_logs_pause).toString()));assertNotNull(described(player(),Language.msg(l10n_salon_media_back_ten).toString()));assertNotNull(described(player(),Language.msg(l10n_salon_media_forward_ten).toString()));assertNotNull(described(player(),Language.msg(l10n_salon_media_stop).toString()));assertEquals(Language.msg(l10n_salon_media_seek).toString(),seek.getContentDescription().toString());}
            verify(mp,times(1)).setDataSource("https://example.invalid/audio");verify(mp,times(1)).prepareAsync();verify(mp,never()).seekTo(anyInt());verify(mp,never()).release();assertEquals(1,construction.constructed().size());
            track("https://example.invalid/named","Original title 中文");for(String code:CODES){Language.select(activity,code);assertEquals("Original title 中文",title().getText().toString());}
        }
    }
    @Test public void stoppedOrSupersededDownloadsCannotStartOrStopAnotherTrack()throws Exception{
        relay.when(()->RelayResolver.isMediaRef("relay-old")).thenReturn(true);relay.when(()->RelayResolver.resolveToTempFile(activity,"relay-old")).thenReturn(new File("/tmp/old.mp3"));
        try(MockedConstruction<MediaPlayer> construction=mockConstruction(MediaPlayer.class)){
            track("relay-old","Old");Runnable stopped=jobs.remove(0);for(String code:CODES){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_media_decrypting).toString(),time().getText().toString());}
            call("stopAudio",new Class<?>[]{});stopped.run();idle();assertTrue(construction.constructed().isEmpty());assertEquals(View.GONE,player().getVisibility());
            track("relay-old","Old");Runnable late=jobs.remove(0);track("https://example.invalid/new","New");MediaPlayer mp=construction.constructed().get(0);late.run();idle();assertSame(mp,field("audio").get(activity));assertEquals("New",title().getText().toString());assertEquals(1,construction.constructed().size());
            track("relay-old","Old again");late=jobs.remove(0);track("https://example.invalid/newest","Newest");MediaPlayer newest=construction.constructed().get(1);relay.when(()->RelayResolver.resolveToTempFile(activity,"relay-old")).thenThrow(new Exception("late failure"));late.run();idle();assertSame(newest,field("audio").get(activity));verify(newest,never()).release();assertEquals("Newest",title().getText().toString());
        }
    }
    @Test public void callbacksFromReleasedPlayerCannotAffectCurrentPlayer()throws Exception{
        try(MockedConstruction<MediaPlayer> construction=mockConstruction(MediaPlayer.class)){
            track("https://example.invalid/old","Old");MediaPlayer old=construction.constructed().get(0);ArgumentCaptor<MediaPlayer.OnPreparedListener> prepared=ArgumentCaptor.forClass(MediaPlayer.OnPreparedListener.class);ArgumentCaptor<MediaPlayer.OnErrorListener> error=ArgumentCaptor.forClass(MediaPlayer.OnErrorListener.class);ArgumentCaptor<MediaPlayer.OnCompletionListener> complete=ArgumentCaptor.forClass(MediaPlayer.OnCompletionListener.class);verify(old).setOnPreparedListener(prepared.capture());verify(old).setOnErrorListener(error.capture());verify(old).setOnCompletionListener(complete.capture());
            track("https://example.invalid/new","New");MediaPlayer current=construction.constructed().get(1);SeekBar bar=(SeekBar)field("miniBar").get(activity);bar.setProgress(123);prepared.getValue().onPrepared(old);complete.getValue().onCompletion(old);assertTrue(error.getValue().onError(old,1,1));verify(old,never()).start();verify(current,never()).release();assertSame(current,field("audio").get(activity));assertEquals(123,bar.getProgress());
        }
    }
    @Test public void supersededVideoResolutionCannotOpenDialogOrReportFailure()throws Exception{
        relay.when(()->RelayResolver.isMediaRef(anyString())).thenReturn(true);relay.when(()->RelayResolver.resolveToTempFile(activity,"video-old")).thenReturn(new File("/tmp/old.mp4"));
        call("openVideo",new Class<?>[]{String.class},"video-old");Runnable old=jobs.remove(0);call("openVideo",new Class<?>[]{String.class},"video-new");old.run();idle();assertNull(field("videoDialog").get(activity));
        relay.when(()->RelayResolver.resolveToTempFile(activity,"video-new")).thenThrow(new Exception("current failure"));jobs.remove(0).run();idle();assertEquals(Language.msg(l10n_salon_media_video_failed,"current failure").toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void oldVideoDismissalCannotCancelNewVideoDownload()throws Exception{
        call("playVideoUri",new Class<?>[]{android.net.Uri.class},android.net.Uri.parse("https://example.invalid/old.mp4"));android.app.Dialog old=(android.app.Dialog)field("videoDialog").get(activity);assertNotNull(old);
        relay.when(()->RelayResolver.isMediaRef("video-new")).thenReturn(true);relay.when(()->RelayResolver.resolveToTempFile(activity,"video-new")).thenReturn(new File("/tmp/new.mp4"));
        call("openVideo",new Class<?>[]{String.class},"video-new");long request=(Long)field("videoRequest").get(activity);idle();assertFalse(old.isShowing());assertEquals(request,field("videoRequest").get(activity));jobs.remove(0).run();idle();android.app.Dialog next=(android.app.Dialog)field("videoDialog").get(activity);assertNotNull(next);assertNotSame(old,next);assertTrue(next.isShowing());next.dismiss();idle();assertNull(field("videoDialog").get(activity));assertTrue((Long)field("videoRequest").get(activity)>request);
    }
    @Test public void mediaCardsKeepCaptionsAndTranslatedControlsAtNarrowWidths()throws Exception{
        for(String type:new String[]{"audio","video","image"}){
            JSONObject item=media(type,"User caption 中文 Русский Українська","https://example.invalid/"+type);String before=item.toString();View card=(View)call("mediaCard",new Class<?>[]{JSONObject.class},item);
            for(String code:CODES){Language.select(activity,code);assertNotNull(find(card,item.getString("caption")));int label=type.equals("audio")?l10n_salon_editor_music:type.equals("video")?l10n_salon_editor_video:l10n_chat_photo;assertNotNull(find(card,Language.msg(label).toString()));if(type.equals("audio"))assertNotNull(find(card,upper(l10n_salon_media_play_action)));if(type.equals("video"))assertNotNull(find(card,Language.msg(l10n_salon_media_tap_play).toString()));
                for(int width:new int[]{320,480,900}){card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));card.layout(0,0,width,card.getMeasuredHeight());checkFits(card);}
                assertEquals(before,item.toString());
            }
        }
    }
    private static void checkFits(View root){if(root instanceof TextView){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText().toString(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)checkFits(((ViewGroup)root).getChildAt(i));}

}
