package com.eurobuddha.salon;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.Base64;
import android.widget.ImageView;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.main.views.balance.tokens.NetFetch;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CreativeImageLoaderTest {
    static final int CAP=8*1024*1024;
    Context ctx;
    @Before public void setup(){ctx=RuntimeEnvironment.getApplication();Language.initialize(RuntimeEnvironment.getApplication(),()->false);}
    static byte[] png(int color){
        Bitmap bitmap=Bitmap.createBitmap(24,12,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));bitmap.recycle();return out.toByteArray();
    }
    static String uri(byte[] bytes){return "data:image/png;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP);}
    static void red(Bitmap b){assertNotNull(b);assertEquals(24,b.getWidth());assertEquals(12,b.getHeight());assertEquals(Color.RED,b.getPixel(4,4));}
    File cache(String url)throws Exception{Method m=ImageLoader.class.getDeclaredMethod("diskFile",Context.class,String.class);m.setAccessible(true);return (File)m.invoke(null,ctx,url);}
    @Test public void badDownloadCannotPoisonLastGoodOfflineImage()throws Exception{
        String url="https://8.8.8.8/art";byte[] good=png(Color.RED);
        try(MockedStatic<NetFetch> net=mockStatic(NetFetch.class)){
            net.when(()->NetFetch.get(url,CAP,8000,15000,false)).thenReturn(good,"<html>unavailable</html>".getBytes(),null);
            red(ImageLoader.decode(ctx,url,420));assertArrayEquals(good,Files.readAllBytes(cache(url).toPath()));
            red(ImageLoader.decode(ctx,url,420));assertArrayEquals(good,Files.readAllBytes(cache(url).toPath()));
            red(ImageLoader.decode(ctx,url,420));assertEquals(1,cache(url).getParentFile().listFiles().length);
        }
    }
    @Test public void goodReplacementIsSavedAndOfflineDecodeReturnsOwnedBitmaps()throws Exception{
        String url="https://8.8.8.8/new-art";byte[] good=png(Color.RED);Files.write(cache(url).toPath(),"old invalid response".getBytes());
        try(MockedStatic<NetFetch> net=mockStatic(NetFetch.class)){
            net.when(()->NetFetch.get(url,CAP,8000,15000,false)).thenReturn(good,(byte[])null);
            Bitmap first=ImageLoader.decode(ctx,url,420);red(first);first.recycle();
            Bitmap second=ImageLoader.decode(ctx,url,420);red(second);assertNotSame(first,second);assertArrayEquals(good,Files.readAllBytes(cache(url).toPath()));
        }
    }
    @Test public void oversizedCacheAndInlineImageAreRejected()throws Exception{
        String url="http://127.0.0.1/unreachable";
        try(RandomAccessFile f=new RandomAccessFile(cache(url),"rw")){f.setLength(CAP+1L);}
        assertNull(ImageLoader.decode(ctx,url,420));
        assertNull(ImageLoader.decode(ctx,"data:image/png;base64,"+"A".repeat(((CAP+2)/3)*4+1),420));
        assertNull(ImageLoader.decode(ctx,"data:"+"A".repeat(1025)+";base64,AAAA",420));
    }
    @Test public void imagesSurviveEveryLanguageAndInvalidSizesFail() {
        String url=uri(png(Color.RED));
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){Language.select(ctx,code);red(ImageLoader.decode(ctx,url,420));}
        assertNull(ImageLoader.decode(ctx,url,0));assertNull(ImageLoader.decode(ctx,url,-1));assertNull(ImageLoader.decode(ctx,url,1801));assertNull(ImageLoader.decode(ctx,null,420));
    }
    @Test public void rasterContentWinsOverMisleadingSvgFilename()throws Exception{
        String url="https://8.8.8.8/actually-png.svg";
        try(MockedStatic<NetFetch> net=mockStatic(NetFetch.class)){
            net.when(()->NetFetch.get(url,CAP,8000,15000,false)).thenReturn(png(Color.RED));red(ImageLoader.decode(ctx,url,420));
        }
    }
    @Test public void svgRendersButEntityDeclarationsAreRejected(){
        String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"12\"><rect width=\"24\" height=\"12\" fill=\"red\"/></svg>";
        Bitmap b=ImageLoader.decode(ctx,uri(svg.getBytes()),420);assertNotNull(b);assertEquals(420,b.getWidth());assertEquals(210,b.getHeight());assertEquals(Color.RED,b.getPixel(100,100));
        assertNull(ImageLoader.decode(ctx,uri(("<!DOCTYPE svg [<!ENTITY x 'bad'>]>"+svg).getBytes()),420));
    }
    @Test public void viewBoxOnlySvgKeepsItsAspectRatio(){
        String svg="<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 12'><rect width='24' height='12' fill='red'/></svg>";
        Bitmap b=ImageLoader.decode(ctx,uri(svg.getBytes()),420);assertNotNull(b);assertEquals(420,b.getWidth());assertEquals(210,b.getHeight());assertEquals(Color.RED,b.getPixel(100,100));
    }
    @Test public void ipfsUsesTheCoreWalletFetcher()throws Exception{
        try(MockedStatic<NetFetch> net=mockStatic(NetFetch.class)){
            net.when(()->NetFetch.get("https://ipfs.io/ipfs/cid/image",CAP,8000,15000,false)).thenReturn(png(Color.RED));
            red(ImageLoader.decode(ctx,"ipfs://cid/image",420));
            net.verify(()->NetFetch.get("https://ipfs.io/ipfs/cid/image",CAP,8000,15000,false));
        }
    }
    @Test public void allPrivateAddressClassesUseSharedPolicy(){
        for(String host:new String[]{"127.0.0.1","10.0.0.1","169.254.169.254","fc00::1","100.64.0.1","0.1.2.3","224.0.0.1"})assertTrue(host,ImageLoader.isBlockedHost(host));
        assertNull(ImageLoader.decode(ctx,"file:///private/image",420));
    }
    @Test public void javaHashCollisionsAndDifferentImageSizesHaveDifferentCacheKeys()throws Exception{
        String a="https://example.invalid/Aa",b="https://example.invalid/BB";assertEquals(a.hashCode(),b.hashCode());
        Method m=ImageLoader.class.getDeclaredMethod("cacheKey",String.class,int.class);m.setAccessible(true);
        assertNotEquals(m.invoke(null,a,420),m.invoke(null,b,420));assertNotEquals(m.invoke(null,a,420),m.invoke(null,a,1800));
        assertTrue(m.invoke(null,uri(new byte[100000]),420).toString().length()<80);
    }
    @SuppressWarnings("unchecked")
    @Test public void staleAndFinishingTargetsCannotPaintButCurrentTargetCan()throws Exception{
        Activity act=Robolectric.buildActivity(Activity.class).setup().get();ImageView view=new ImageView(act);
        Class<?> type=Class.forName(ImageLoader.class.getName()+"$Target");Constructor<?> ctor=type.getDeclaredConstructor(Activity.class,ImageView.class,String.class);ctor.setAccessible(true);
        Consumer<Bitmap> old=(Consumer<Bitmap>)ctor.newInstance(act,view,"old"),current=(Consumer<Bitmap>)ctor.newInstance(act,view,"current");
        Bitmap b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        view.setTag(current);old.accept(b);assertNull(view.getDrawable());current.accept(b);assertSame(b,((BitmapDrawable)view.getDrawable()).getBitmap());
        view.setImageDrawable(null);act.finish();current.accept(b);assertNull(view.getDrawable());
    }
    @Test public void emptyOrOversizedRequestInvalidatesOldViewTag(){
        Activity act=Robolectric.buildActivity(Activity.class).setup().get();ImageView view=new ImageView(act);view.setTag("old");
        ImageLoader.loadOver(act,"",view);assertEquals("",view.getTag());view.setTag("old");
        ImageLoader.loadOver(act,"A".repeat(((CAP+2)/3)*4+1025),view);assertNull(view.getTag());
    }
    @Test public void cacheTrimmingPreservesTemporaryWrites()throws Exception{
        File dir=cache("cache-test").getParentFile();
        File pending=new File(dir,".image-test.part");Files.write(pending.toPath(),new byte[]{1});pending.setLastModified(1);
        for(int i=0;i<301;i++)Files.write(new File(dir,String.format("%040x",i)).toPath(),new byte[]{1});
        Method m=ImageLoader.class.getDeclaredMethod("trimDisk",File.class);m.setAccessible(true);m.invoke(null,dir);
        assertTrue(pending.isFile());assertEquals(300,dir.listFiles(f->f.getName().matches("[0-9a-f]{40}")).length);
    }
    @Test public void encryptedRelayImagesStillUseTheirExistingResolver()throws Exception{
        try(MockedStatic<RelayResolver> relay=mockStatic(RelayResolver.class)){
            String ref="mx1:unchanged-reference";
            relay.when(()->RelayResolver.isMediaRef(ref)).thenReturn(true);
            relay.when(()->RelayResolver.resolveBytes(ref)).thenReturn(new RelayResolver.Media(png(Color.RED),"image/png"));
            red(ImageLoader.decode(ctx,ref,420));relay.verify(()->RelayResolver.resolveBytes(ref));
        }
    }
}
