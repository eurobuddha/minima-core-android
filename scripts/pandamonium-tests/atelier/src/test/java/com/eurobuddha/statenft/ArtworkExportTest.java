package com.eurobuddha.statenft;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtworkExportTest {
    Context ctx;ContentResolver resolver;ByteArrayOutputStream bytes;final Uri row=Uri.parse("content://media/external/images/media/42");
    @Before public void setup()throws Exception{
        ctx=mock(Context.class);resolver=mock(ContentResolver.class);when(ctx.getContentResolver()).thenReturn(resolver);bytes=new ByteArrayOutputStream();
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),any(ContentValues.class))).thenReturn(row);when(resolver.openOutputStream(row)).thenReturn(bytes);when(resolver.update(eq(row),any(ContentValues.class),isNull(),isNull())).thenReturn(1);
    }
    @Test public void pendingRowIsPublishedOnlyAfterSuccessfulWrite()throws Exception{
        byte[] expected={1,2,3,4};Uri result=ArtworkExport.save(ctx,"Київ 中文.jpg","image/jpeg",out->{verify(resolver,never()).update(any(),any(),any(),any());out.write(expected);},()->false);
        assertEquals(row,result);assertArrayEquals(expected,bytes.toByteArray());
        ArgumentCaptor<ContentValues> inserted=ArgumentCaptor.forClass(ContentValues.class),published=ArgumentCaptor.forClass(ContentValues.class);verify(resolver).insert(any(),inserted.capture());verify(resolver).update(eq(row),published.capture(),isNull(),isNull());
        assertEquals(Integer.valueOf(1),inserted.getValue().getAsInteger(MediaStore.Images.Media.IS_PENDING));assertEquals("Pictures/Atelier",inserted.getValue().getAsString(MediaStore.Images.Media.RELATIVE_PATH));assertEquals("Київ 中文.jpg",inserted.getValue().getAsString(MediaStore.Images.Media.DISPLAY_NAME));assertEquals(Integer.valueOf(0),published.getValue().getAsInteger(MediaStore.Images.Media.IS_PENDING));verify(resolver,never()).delete(any(),any(),any());
    }
    @Test public void nullStreamWriteFailureAndFailedPublishDeleteTheirRows()throws Exception{
        when(resolver.openOutputStream(row)).thenReturn(null);expectFailure(out->out.write(1));verify(resolver).delete(row,null,null);reset(resolver);setup();
        expectFailure(out->{out.write(1);throw new IOException("disk full");});verify(resolver).delete(row,null,null);verify(resolver,never()).update(any(),any(),any(),any());reset(resolver);setup();
        when(resolver.update(eq(row),any(),isNull(),isNull())).thenReturn(0);expectFailure(out->out.write(1));verify(resolver).delete(row,null,null);
    }
    void expectFailure(ArtworkExport.Writer writer)throws Exception{try{ArtworkExport.save(ctx,"test.jpg","image/jpeg",writer,()->false);fail();}catch(IOException expected){}}
    @Test public void cancellationBeforeAndDuringWriteNeverPublishes()throws Exception{
        try{ArtworkExport.save(ctx,"test.jpg","image/jpeg",out->fail(),()->true);fail();}catch(IOException expected){}verifyNoInteractions(resolver);
        AtomicBoolean cancelled=new AtomicBoolean();try{ArtworkExport.save(ctx,"test.jpg","image/jpeg",out->{out.write(1);cancelled.set(true);},cancelled::get);fail();}catch(IOException expected){}verify(resolver).delete(row,null,null);verify(resolver,never()).update(any(),any(),any(),any());
    }
    @Test @Config(sdk=28) public void legacyPhotosStillCleanUpFailureWithoutPendingColumns()throws Exception{
        expectFailure(out->{throw new IOException("permission/write failed");});ArgumentCaptor<ContentValues> values=ArgumentCaptor.forClass(ContentValues.class);verify(resolver).insert(any(),values.capture());assertFalse(values.getValue().containsKey(MediaStore.Images.Media.IS_PENDING));assertFalse(values.getValue().containsKey(MediaStore.Images.Media.RELATIVE_PATH));verify(resolver).delete(row,null,null);
    }
    @Test public void failedCompressionCannotProduceSuccess()throws Exception{
        Bitmap bitmap=mock(Bitmap.class);when(bitmap.compress(any(),anyInt(),any())).thenReturn(false);expectFailure(out->ArtworkExport.jpeg(bitmap,out));verify(resolver).delete(row,null,null);
    }
    @Test public void shareFilesAreUniqueAndFailedOrCancelledFilesAreRemoved()throws Exception{
        Context app=RuntimeEnvironment.getApplication();File a=ArtworkExport.shareFile(app,out->out.write(new byte[]{1,2}),()->false);File b=ArtworkExport.shareFile(app,out->out.write(new byte[]{3,4}),()->false);
        assertNotEquals(a,b);assertArrayEquals(new byte[]{1,2},java.nio.file.Files.readAllBytes(a.toPath()));int count=a.getParentFile().list().length;
        try{ArtworkExport.shareFile(app,out->{out.write(1);throw new IOException("full");},()->false);fail();}catch(IOException expected){}assertEquals(count,a.getParentFile().list().length);
        AtomicBoolean stop=new AtomicBoolean();try{ArtworkExport.shareFile(app,out->{out.write(1);stop.set(true);},stop::get);fail();}catch(IOException expected){}assertEquals(count,a.getParentFile().list().length);a.delete();b.delete();
    }
    @Test public void filenamesKeepAllLanguagesAndBoundUnsafeLongInput(){
        assertEquals("中文 Русский Українська_2.jpg",ArtworkExport.filename("中文 Русский Українська","_2.jpg"));String name=ArtworkExport.filename("../a\\b/c:*?\"<>|\n"+"中".repeat(200),".png");assertFalse(name.contains("/"));assertFalse(name.contains("\\"));assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<255);assertEquals("Atelier.jpg",ArtworkExport.filename("  ",".jpg"));
    }
    @Test public void filtrRetainsExactBytesAndUsesActualMime()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);for(Bitmap.CompressFormat format:new Bitmap.CompressFormat[]{Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG}){
            ByteArrayOutputStream raw=new ByteArrayOutputStream();assertTrue(bitmap.compress(format,95,raw));ArtworkExport.Encoded encoded=ArtworkExport.filtr(Base64.encodeToString(raw.toByteArray(),Base64.NO_WRAP));assertArrayEquals(raw.toByteArray(),encoded.bytes);assertEquals(format==Bitmap.CompressFormat.PNG?"image/png":"image/jpeg",encoded.mime);
        }bitmap.recycle();for(String bad:new String[]{"", "not an image", Base64.encodeToString(new byte[]{1,2,3},Base64.NO_WRAP)})try{ArtworkExport.filtr(bad);fail();}catch(IOException expected){}
    }
}
