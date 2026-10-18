package com.eurobuddha.statenft;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Random;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real four-language resources and native graphics; no wallet, device or node. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class CreativeEditorLanguageTest {
    private static <T> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            T value=find(((ViewGroup)root).getChildAt(i),type);if(value!=null)return value;
        }
        return null;
    }
    private static boolean hasText(View root,String text) {
        if(root instanceof TextView && text.contentEquals(((TextView)root).getText())) return true;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            if(hasText(((ViewGroup)root).getChildAt(i),text))return true;
        return false;
    }
    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY));
        root.layout(0,0,480,960);
    }
    @Test public void languageSwitchPreservesEditedCropPixelsAndSelectedFilter() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        Language.select(RuntimeEnvironment.getApplication(),"en");
        try (org.robolectric.android.controller.ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity=controller.get();
            Bitmap image=Bitmap.createBitmap(180,120,Bitmap.Config.ARGB_8888);
            Random random=new Random(17);int[] pixels=new int[180*120];
            for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0xffffff);
            image.setPixels(pixels,0,180,0,0,180,120);
            ByteArrayOutputStream encoded=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,encoded);
            View root=ImageEditor.build(activity,Base64.encodeToString(encoded.toByteArray(),Base64.NO_WRAP),50000,
                    ()->fail("Language change closed the editor"),result->fail("Language change applied the crop"));
            activity.setContentView(root);layout(root);
            ImageEditor.CropStage stage=find(root,ImageEditor.CropStage.class);assertNotNull(stage);
            stage.setAspect(0.8f);stage.look="warm";stage.brightness=0.2f;stage.contrast=1.2f;stage.saturation=0.8f;stage.applyFilter();
            Field field=ImageEditor.CropStage.class.getDeclaredField("matrix");field.setAccessible(true);
            Matrix matrix=(Matrix)field.get(stage);matrix.postScale(1.4f,1.4f,stage.getWidth()/2f,stage.getHeight()/2f);
            Bitmap before=stage.render(256);assertNotNull(before);
            for(String code:new String[]{"zh-Hans","ru","uk","en"}) {
                Language.select(activity,code);layout(root);
                assertSame(stage,find(root,ImageEditor.CropStage.class));
                assertTrue(code+" label missing",hasText(root,Language.text(activity,l10n_creative_plate_room)));
                assertEquals("warm",stage.look);
                Bitmap after=stage.render(256);assertNotNull(after);
                assertTrue(code+" changed the user's crop",before.sameAs(after));
                after.recycle();
            }
            before.recycle();image.recycle();
        } finally {Language.select(RuntimeEnvironment.getApplication(),"en");}
    }
}
