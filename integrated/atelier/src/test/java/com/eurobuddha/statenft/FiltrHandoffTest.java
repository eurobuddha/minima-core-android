package com.eurobuddha.statenft;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.Base64;
import com.eurobuddha.minimacore.i18n.Language;
import java.io.*;
import java.lang.reflect.*;
import java.util.UUID;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FiltrHandoffTest {
    android.content.Context ctx;
    @Before public void setup(){ctx=RuntimeEnvironment.getApplication();Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(ctx,"en");}
    static String image(int size,boolean noise){
        Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        if(noise){java.util.Random random=new java.util.Random(9);int[] pixels=new int[size*size];for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0xffffff);b.setPixels(pixels,0,size,0,0,size,size);}
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));b.recycle();return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    static Object call(FiltrActivity a,String mode,String b64)throws Exception{Method m=FiltrActivity.class.getDeclaredMethod("sendResult",String.class,String.class);m.setAccessible(true);return m.invoke(a,mode,b64);}
    File file(String id){return new File(ctx.getCacheDir(),"atelier-filtr/"+id);}
    @Test public void exactArtworkIsConsumedOnceWithNoBase64InResult()throws Exception{
        String id=UUID.randomUUID().toString(),b64=image(32,false);
        FiltrActivity a=Robolectric.buildActivity(FiltrActivity.class,new Intent().putExtra(FiltrHandoff.ID,id)).create().start().resume().get();
        for(String lang:new String[]{"zh-Hans","ru","uk","en"})Language.select(ctx,lang);
        call(a,FiltrHandoff.MINT,b64);Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent result=Shadows.shadowOf(a).getResultIntent();assertEquals(Activity.RESULT_OK,Shadows.shadowOf(a).getResultCode());assertEquals(id,result.getStringExtra(FiltrHandoff.ID));assertEquals(FiltrHandoff.MINT,result.getStringExtra(FiltrHandoff.MODE));assertEquals(2,result.getExtras().size());
        call(a,FiltrHandoff.PHOTO,image(512,false));a.onDestroy();
        assertEquals(b64,FiltrHandoff.take(ctx,id,FiltrHandoff.MINT));assertFalse(file(id).exists());
        try{FiltrHandoff.take(ctx,id,FiltrHandoff.MINT);fail();}catch(IOException expected){}
    }
    @Test public void largeLosslessPhotoUsesFileAndPreservesBytes()throws Exception{
        String id=UUID.randomUUID().toString(),b64=image(512,true);assertTrue(b64.length()>1024*1024);
        FiltrHandoff.write(ctx,id,FiltrHandoff.PHOTO,b64);assertEquals(b64,FiltrHandoff.take(ctx,id,FiltrHandoff.PHOTO));assertFalse(file(id).exists());
    }
    @Test public void invalidImagesDimensionsModesAndIdsAreRejectedWithoutFiles()throws Exception{
        String id=UUID.randomUUID().toString();
        String[][] bad={{FiltrHandoff.MINT,"not an image"},{FiltrHandoff.PHOTO,image(32,false)},{FiltrHandoff.MINT,"A".repeat(9001)},{"unknown",image(32,false)}};
        for(String[] entry:bad){try{FiltrHandoff.write(ctx,id,entry[0],entry[1]);fail();}catch(IOException expected){}assertFalse(file(id).exists());}
        try{FiltrHandoff.write(ctx,"../outside",FiltrHandoff.MINT,image(32,false));fail();}catch(IOException expected){}
    }
    @Test public void corruptOrOversizedCachedImageIsRemoved()throws Exception{
        String id=UUID.randomUUID().toString();file(id).getParentFile().mkdirs();
        for(byte[] bytes:new byte[][]{new byte[9001],"invalid".getBytes()}){
            java.nio.file.Files.write(file(id).toPath(),bytes);
            try{FiltrHandoff.take(ctx,id,FiltrHandoff.MINT);fail();}catch(IOException expected){}assertFalse(file(id).exists());
        }
    }
    @Test public void invalidBridgeInputStaysOpenAndCanRetryInCurrentLanguage()throws Exception{
        String id=UUID.randomUUID().toString();FiltrActivity a=Robolectric.buildActivity(FiltrActivity.class,new Intent().putExtra(FiltrHandoff.ID,id)).create().start().resume().get();
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){
            Language.select(ctx,code);call(a,FiltrHandoff.MINT,"invalid");Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(a.isFinishing());assertEquals(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_filtr_failed).toString(),ShadowToast.getTextOfLatestToast());
        }
        call(a,FiltrHandoff.MINT,image(32,false));assertTrue(a.isFinishing());a.onDestroy();FiltrHandoff.discard(ctx,id);
    }
    @Test public void closedEditorCannotCreateHandoffAndUnownedLaunchFinishes()throws Exception{
        FiltrActivity unowned=Robolectric.buildActivity(FiltrActivity.class).create().get();assertTrue(unowned.isFinishing());unowned.onDestroy();
        String id=UUID.randomUUID().toString();FiltrActivity a=Robolectric.buildActivity(FiltrActivity.class,new Intent().putExtra(FiltrHandoff.ID,id)).create().get();a.onDestroy();call(a,FiltrHandoff.MINT,image(32,false));assertFalse(file(id).exists());assertNull(Shadows.shadowOf(a).getResultIntent());
    }
}
