package com.eurobuddha.salon;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real setup widgets/resources; no Activity startup, node, publishing or account creation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonSetupLanguageTest {
    private static List<EditText> edits(View root) {
        List<EditText> found=new ArrayList<>();
        if(root instanceof EditText)found.add((EditText)root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)found.addAll(edits(((ViewGroup)root).getChildAt(i)));
        return found;
    }
    private static boolean contains(View root,String expected) {
        if(root instanceof TextView && expected.equals(((TextView)root).getText().toString()))return true;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(contains(((ViewGroup)root).getChildAt(i),expected))return true;
        return false;
    }
    private static void checkLabelsFit(View root) {
        if(root instanceof TextView && !(root instanceof EditText)) {
            TextView text=(TextView)root;
            if(text.getLayout()!=null)assertTrue(text.getText().toString(),
                    text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom());
        }
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)checkLabelsFit(((ViewGroup)root).getChildAt(i));
    }
    private static Field field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    @Test public void setupLabelsAndHintsFollowSelectionWithoutReplacingDraftFieldsOrCursor() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        Language.select(RuntimeEnvironment.getApplication(),"en");
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);field("body").set(activity,body);
        try {
            Method render=MainActivity.class.getDeclaredMethod("renderOnboard");render.setAccessible(true);render.invoke(activity);
            List<EditText> inputs=edits(body);assertEquals(3,inputs.size());
            String[] drafts={"artist_draft","Name 中文 Русский Українська","My unsaved bio"};
            for(int i=0;i<inputs.size();i++){inputs.get(i).setText(drafts[i]);inputs.get(i).setSelection(2);}
            Object claim=field("claimBtn").get(activity);
            TextView status=new TextView(activity);
            Method validate=MainActivity.class.getDeclaredMethod("claimIdentity",String.class,String.class,String.class,TextView.class);
            validate.setAccessible(true);validate.invoke(activity,"","","",status);
            for(String code:new String[]{"zh-Hans","ru","uk","en"}) {
                Language.select(activity,code);
                assertEquals(Language.text(activity,l10n_salon_setup_required),status.getText().toString());
                for(int width:new int[]{320,480,900}) {
                    body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    body.layout(0,0,width,body.getMeasuredHeight());checkLabelsFit(body);
                }
                assertTrue(code,contains(body,Language.upper(Language.msg(l10n_salon_setup_open)).toString()));
                assertTrue(code,contains(body,Language.upper(Language.msg(l10n_salon_setup_claim)).toString()));
                assertTrue(code,contains(body,Language.text(activity,l10n_salon_setup_intro)));
                assertEquals(Language.text(activity,l10n_salon_setup_handle_example),inputs.get(0).getHint().toString());
                assertEquals(Language.text(activity,l10n_salon_setup_bio_hint),inputs.get(2).getHint().toString());
                assertSame(claim,field("claimBtn").get(activity));
                for(int i=0;i<inputs.size();i++) {
                    assertSame(inputs.get(i),edits(body).get(i));assertEquals(drafts[i],inputs.get(i).getText().toString());
                    assertEquals(2,inputs.get(i).getSelectionStart());
                }
            }
        } finally {
            ((ExecutorService)field("io").get(activity)).shutdownNow();
            Language.select(RuntimeEnvironment.getApplication(),"en");
        }
    }
}
