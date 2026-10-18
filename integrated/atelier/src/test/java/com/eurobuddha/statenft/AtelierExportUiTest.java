package com.eurobuddha.statenft;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.eurobuddha.minimacore.i18n.Language;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtelierExportUiTest {
    Activity act;View root;StateNft.Meta meta;List<StateNft.Item> items=new ArrayList<>();List<Runnable> jobs=new ArrayList<>();List<File> shared=new ArrayList<>();
    MockedStatic<ArtworkExport> exports;MockedStatic<ImageLoader> images;MockedStatic<FileProvider> provider;
    @Before public void setup(){
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        act=spy(Robolectric.buildActivity(Activity.class).setup().get());act.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        exports=mockStatic(ArtworkExport.class,CALLS_REAL_METHODS);exports.when(()->ArtworkExport.execute(any())).thenAnswer(i->{jobs.add(i.getArgument(0));return null;});
        images=mockStatic(ImageLoader.class);images.when(()->ImageLoader.decode(any(),anyString(),eq(1800))).thenAnswer(i->{Bitmap b=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);return b;});
        provider=mockStatic(FileProvider.class);provider.when(()->FileProvider.getUriForFile(eq(act),anyString(),any(File.class))).thenAnswer(i->{File f=i.getArgument(2);shared.add(f);return Uri.parse("content://artwork/"+f.getName());});
        meta=new StateNft.Meta();meta.name="中文 Русский Українська";meta.tokenid="0xabc";meta.size=2;
        for(int n=1;n<=2;n++){StateNft.Item it=new StateNft.Item();it.index=n;it.imageUrl="https://example.invalid/"+n;items.add(it);}
        root=ViewerScreen.build(act,new ViewerScreen.Host(){public void onCloseViewer(){act.setContentView(new FrameLayout(act));}public void onTransferItem(StateNft.Item it){}},meta,items,0);act.setContentView(root);idle();
    }
    @After public void cleanup(){for(File f:shared)f.delete();act.finish();exports.close();images.close();provider.close();Language.select(RuntimeEnvironment.getApplication(),"en");}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();idle();}
    static TextView text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return(TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView got=text(((ViewGroup)view).getChildAt(i),label);if(got!=null)return got;}return null;}
    void click(int key){TextView button=text(root,Language.upper(Language.msg(key)).toString());assertNotNull(button);button.performClick();}
    ViewerScreen.ZoomImage image(){return (ViewerScreen.ZoomImage)((ViewGroup)root).getChildAt(0);}
    @Test public void shareKeepsClickedArtworkMetadataAndUsesCompletionLanguage()throws Exception{
        String name=meta.name,token=meta.tokenid;click(l10n_logs_share);meta.name="Changed later";meta.tokenid="changed";items.get(0).imageUrl="changed";Language.select(act,"uk");job();
        Intent chooser=Shadows.shadowOf(act).getNextStartedActivity(),send=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_SEND,send.getAction());assertEquals("image/jpeg",send.getType());assertEquals(Language.msg(l10n_creative_share_lot_caption,name,1,token).toString(),send.getStringExtra(Intent.EXTRA_TEXT));assertEquals(Language.msg(l10n_creative_share_lot).toString(),chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());assertEquals(send.getParcelableExtra(Intent.EXTRA_STREAM),send.getClipData().getItemAt(0).getUri());assertTrue((send.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);images.verify(()->ImageLoader.decode(any(),eq("https://example.invalid/1"),eq(1800)));
        File first=shared.get(0);byte[] before=java.nio.file.Files.readAllBytes(first.toPath());click(l10n_logs_share);job();assertNotEquals(first,shared.get(1));assertArrayEquals(before,java.nio.file.Files.readAllBytes(first.toPath()));
    }
    @Test public void missingArtworkCannotExportPlaceholderOrReportSuccess(){
        images.when(()->ImageLoader.decode(any(),anyString(),eq(1800))).thenReturn(null);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){Language.select(act,code);click(l10n_common_save);job();assertEquals(Language.msg(l10n_creative_save_image_failed).toString(),ShadowToast.getTextOfLatestToast());click(l10n_logs_share);job();assertEquals(Language.msg(l10n_creative_share_image_failed).toString(),ShadowToast.getTextOfLatestToast());}
        assertTrue(shared.isEmpty());assertNull(Shadows.shadowOf(act).getNextStartedActivity());
    }
    @Test public void duplicateClicksAreBoundedAndSwipingOrClosingSuppressesOldShare(){
        click(l10n_logs_share);click(l10n_logs_share);assertEquals(1,jobs.size());image().listener.next();job();assertTrue(shared.isEmpty());assertNull(Shadows.shadowOf(act).getNextStartedActivity());
        click(l10n_logs_share);act.setContentView(new FrameLayout(act));job();assertTrue(shared.isEmpty());assertNull(Shadows.shadowOf(act).getNextStartedActivity());
    }
    @Test public void missingShareHandlerReportsFailureAndDeletesItsFile(){
        doThrow(new ActivityNotFoundException()).when(act).startActivity(any(Intent.class));click(l10n_logs_share);job();assertEquals(1,shared.size());assertFalse(shared.get(0).exists());assertEquals(Language.msg(l10n_creative_share_image_failed).toString(),ShadowToast.getTextOfLatestToast());
    }
    @Test public void unavailableWorkerReportsFailureWithoutCreatingFile(){
        exports.when(()->ArtworkExport.execute(any())).thenThrow(new java.util.concurrent.RejectedExecutionException());click(l10n_logs_share);assertEquals(Language.msg(l10n_creative_share_image_failed).toString(),ShadowToast.getTextOfLatestToast());assertTrue(shared.isEmpty());
    }
    @Test public void oldSheetControlsCannotExportAnotherSelection(){TextView old=text(root,Language.upper(Language.msg(l10n_logs_share)).toString());image().listener.next();old.performClick();assertTrue(jobs.isEmpty());}
}
