package com.eurobuddha.minimacore.integrated;

import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.vestr.MainActivity;
import com.eurobuddha.vestr.NodeApi;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual Vestr layout and listener; no node connection or command is allowed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class VestrInsetsTest {
    private static void dispatch(View root,int left,int right) {
        ViewCompat.dispatchApplyWindowInsets(root,new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(left,24,right,16)).build());
        root.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,1200,800);
    }
    @Test public void repeatedAndChangingSideInsetsKeepAllThreeTabsReachable() {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        try(MockedConstruction<NodeApi> nodes=mockConstruction(NodeApi.class)) {
            ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create();
            try {
                MainActivity activity=controller.get();
                View root=activity.findViewById(com.eurobuddha.vestr.R.id.pm_vestr_main);
                LinearLayout nav=activity.findViewById(com.eurobuddha.vestr.R.id.pm_vestr_bottomBar);
                int initialLeft=nav.getPaddingLeft(),initialRight=nav.getPaddingRight();
                for(int i=0;i<20;i++)dispatch(root,96,48);
                assertEquals(initialLeft+96,nav.getPaddingLeft());
                assertEquals(initialRight+48,nav.getPaddingRight());
                assertEquals(3,nav.getChildCount());
                for(int i=0;i<3;i++)assertTrue("Every tab retains usable width",nav.getChildAt(i).getWidth()>100);
                dispatch(root,24,72);
                assertEquals(initialLeft+24,nav.getPaddingLeft());assertEquals(initialRight+72,nav.getPaddingRight());
                dispatch(root,0,0);
                assertEquals(initialLeft,nav.getPaddingLeft());assertEquals(initialRight,nav.getPaddingRight());
                assertEquals(1,nodes.constructed().size());verifyNoInteractions(nodes.constructed().get(0));
            } finally {controller.destroy();}
        }
    }
}
