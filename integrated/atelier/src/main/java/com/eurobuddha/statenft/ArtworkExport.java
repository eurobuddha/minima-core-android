package com.eurobuddha.statenft;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import com.eurobuddha.minimacore.utils.BackgroundWork;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/** Shared image exports. Reuses PandaApps ApkExporter's pending/publish/cleanup sequence. */
final class ArtworkExport {
    private static final ExecutorService WORK = BackgroundWork.pool(2,4);
    interface Writer { void write(OutputStream out) throws Exception; }
    private ArtworkExport() {}
    static void execute(Runnable job) { WORK.execute(job); }
    static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Image export cancelled");
    }
    /** Keep names in all four languages; remove only unsafe filename characters and bound length. */
    static String filename(String name,String extension) {
        String clean = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (clean.isEmpty() || clean.matches("[. ]+")) clean="Atelier";
        int count=clean.codePointCount(0,clean.length());
        if(count>48)clean=clean.substring(0,clean.offsetByCodePoints(0,48));
        return clean+extension;
    }
    static void jpeg(Bitmap bitmap,OutputStream out) throws IOException {
        if (bitmap == null || !bitmap.compress(Bitmap.CompressFormat.JPEG,95,out))
            throw new IOException("Could not encode artwork");
    }
    static Uri save(Context ctx,String name,String mime,Writer writer,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        ContentResolver cr=ctx.getContentResolver();
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
        values.put(MediaStore.Images.Media.MIME_TYPE,mime);
        if(Build.VERSION.SDK_INT>=29){
            values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Atelier");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
        }
        Uri row=cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(row==null)throw new IOException("Could not create photo entry");
        try{
            check(cancelled);
            try(OutputStream out=cr.openOutputStream(row)){
                if(out==null)throw new IOException("Could not open photo entry");
                writer.write(out);
            }
            check(cancelled);
            if(Build.VERSION.SDK_INT>=29){
                ContentValues done=new ContentValues();done.put(MediaStore.Images.Media.IS_PENDING,0);
                if(cr.update(row,done,null,null)!=1)throw new IOException("Could not publish photo entry");
            }
            return row;
        }catch(Exception failure){
            try{cr.delete(row,null,null);}catch(Exception ignored){}
            throw failure;
        }
    }
    static File shareFile(Context ctx,Writer writer,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        File dir=new File(ctx.getCacheDir(),"pandamonium_atelier_shared");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Could not create share cache");
        File file=File.createTempFile("lot-",".jpg",dir);
        try{
            try(FileOutputStream out=new FileOutputStream(file)){writer.write(out);out.getFD().sync();}
            check(cancelled);return file;
        }catch(Exception failure){file.delete();throw failure;}
    }
    static final class Encoded {
        final byte[] bytes;final String mime,extension;
        Encoded(byte[] bytes,String mime,String extension){this.bytes=bytes;this.mime=mime;this.extension=extension;}
    }
    /** FILTR exports at most 4096 square. Preserve its exact PNG/JPEG bytes and actual MIME. */
    static Encoded filtr(String b64) throws IOException {
        final int maxBytes=72*1024*1024; // Includes worst-case 4096-square RGBA PNG overhead.
        if(b64==null||b64.isEmpty()||b64.length()>(long)maxBytes*4/3+4)throw new IOException("Invalid export size");
        final byte[] bytes;
        try{bytes=Base64.decode(b64,Base64.DEFAULT);}catch(IllegalArgumentException bad){throw new IOException("Invalid image",bad);}
        if(bytes.length==0||bytes.length>maxBytes)throw new IOException("Invalid export size");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>4096||bounds.outHeight>4096)
            throw new IOException("Invalid export dimensions");
        if("image/png".equals(bounds.outMimeType))return new Encoded(bytes,bounds.outMimeType,".png");
        if("image/jpeg".equals(bounds.outMimeType))return new Encoded(bytes,bounds.outMimeType,".jpg");
        throw new IOException("Unsupported FILTR export");
    }
}
