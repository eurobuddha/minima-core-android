package com.eurobuddha.statenft;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;
import com.eurobuddha.minimacore.i18n.Language;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FiltrExportTest {
    FiltrActivity activity;WebView web;List<Runnable> jobs=new ArrayList<>();MockedStatic<ArtworkExport> exports;String b64;
    void call()throws Exception{Method m=FiltrActivity.class.getDeclaredMethod("saveImage",String.class,String.class);m.setAccessible(true);m.invoke(activity,"中文 Русский Українська.jpg",b64);}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void job(){jobs.remove(0).run();idle();}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(FiltrActivity.class,new Intent().putExtra(FiltrHandoff.ID,UUID.randomUUID().toString())).create().start().resume().get();
        web=mock(WebView.class);when(web.getUrl()).thenReturn("file:///android_asset/filtr/filtr.html");Field f=FiltrActivity.class.getDeclaredField("web");f.setAccessible(true);((WebView)f.get(activity)).destroy();f.set(activity,web);
        exports=mockStatic(ArtworkExport.class,CALLS_REAL_METHODS);exports.when(()->ArtworkExport.execute(any())).thenAnswer(i->{jobs.add(i.getArgument(0));return null;});
        Bitmap image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,out);image.recycle();b64=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    @After public void cleanup(){activity.onDestroy();exports.close();Language.select(RuntimeEnvironment.getApplication(),"en");}
    @Test public void nativeSuccessPreservesPngFilenameBytesAndReportsCurrentLanguage()throws Exception{
        List<byte[]> saved=new ArrayList<>();exports.when(()->ArtworkExport.save(any(),eq("中文 Русский Українська.png"),eq("image/png"),any(ArtworkExport.Writer.class),any(BooleanSupplier.class))).thenAnswer(i->{ByteArrayOutputStream out=new ByteArrayOutputStream();((ArtworkExport.Writer)i.getArgument(3)).write(out);saved.add(out.toByteArray());return Uri.parse("content://saved");});
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){call();call();assertEquals(1,jobs.size());Language.select(activity,code);job();assertEquals(Language.msg(l10n_creative_saved_photos).toString(),ShadowToast.getTextOfLatestToast());assertArrayEquals(Base64.decode(b64,Base64.DEFAULT),saved.get(saved.size()-1));}
        verify(web,times(4)).evaluateJavascript("filtrSaveFinished(true);",null);
    }
    @Test public void failureNeverReportsSavedAndCanBeRetried()throws Exception{
        exports.when(()->ArtworkExport.save(any(),anyString(),anyString(),any(),any())).thenThrow(new IOException("disk full"));call();job();assertEquals(Language.msg(l10n_creative_save_image_failed).toString(),ShadowToast.getTextOfLatestToast());verify(web).evaluateJavascript("filtrSaveFinished(false);",null);call();assertEquals(1,jobs.size());
    }
    @Test public void closedEditorDoesNotWriteOrShowLateStatus()throws Exception{
        call();activity.onDestroy();clearInvocations(web);job();exports.verify(()->ArtworkExport.save(any(),anyString(),anyString(),any(),any()),never());verify(web,never()).evaluateJavascript(anyString(),any());
    }
}
