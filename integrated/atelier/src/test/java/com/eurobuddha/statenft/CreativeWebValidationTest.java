package com.eurobuddha.statenft;

import android.app.Activity;
import android.view.View;
import com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.Result;
import java.util.*;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** The view owns the callback; the shared verifier holds only a weak subscription. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class CreativeWebValidationTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private MockedStatic<com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate> core;
    private final List<Consumer<Result>> callbacks=new ArrayList<>();
    private static final String TOKEN="0x"+"ab".repeat(32),A="https://example.invalid/a",B="https://example.invalid/b";
    private static final int TAG=com.eurobuddha.minimaapi.R.id.embedded_web_validation_callback;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class).setup();activity=controller.get();
        core=mockStatic(com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.class);
        core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.needsCheck(anyString(),anyString())).thenReturn(true);
        core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.ensure(anyString(),anyString(),any())).thenAnswer(c->{callbacks.add(c.getArgument(2));return null;});
    }
    @After public void cleanup(){core.close();if(!activity.isDestroyed())controller.pause().stop().destroy();}
    @Test public void delegatesExactTokenAndUrlAndRetainsCallbackOnOwningView(){
        View owner=new View(activity);List<String> painted=new ArrayList<>();WebValidate.ensure(activity,owner,TOKEN,A,()->painted.add("first"));
        assertSame(callbacks.get(0),owner.getTag(TAG));core.verify(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.ensure(eq(TOKEN),eq(A),same(callbacks.get(0))));
        callbacks.get(0).accept(Result.VERIFIED);assertEquals(Arrays.asList("first"),painted);assertNull(owner.getTag(TAG));
        callbacks.get(0).accept(Result.VERIFIED);assertEquals(1,painted.size());
    }
    @Test public void recycledViewCannotPaintAnOlderUrlResult(){
        View owner=new View(activity);List<String> painted=new ArrayList<>();WebValidate.ensure(activity,owner,TOKEN,A,()->painted.add("old"));WebValidate.ensure(activity,owner,TOKEN,B,()->painted.add("new"));
        callbacks.get(0).accept(Result.VERIFIED);assertTrue(painted.isEmpty());assertSame(callbacks.get(1),owner.getTag(TAG));callbacks.get(1).accept(Result.NOT_VERIFIED);assertEquals(Arrays.asList("new"),painted);assertNull(owner.getTag(TAG));
    }
    @Test public void cachedReplacementInvalidatesEarlierCallback(){
        View owner=new View(activity);WebValidate.ensure(activity,owner,TOKEN,A,()->fail("old URL repainted cached replacement"));
        core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.needsCheck(TOKEN,B)).thenReturn(false);
        WebValidate.ensure(activity,owner,TOKEN,B,()->fail("cached result rescheduled"));assertNull(owner.getTag(TAG));assertEquals(1,callbacks.size());callbacks.get(0).accept(Result.VERIFIED);
    }
    @Test public void simultaneousBadgesKeepSeparateSubscriptions(){
        View a=new View(activity),b=new View(activity);List<String> painted=new ArrayList<>();WebValidate.ensure(activity,a,TOKEN,A,()->painted.add("avatar"));WebValidate.ensure(activity,b,TOKEN,A,()->painted.add("shield"));
        assertSame(callbacks.get(0),a.getTag(TAG));assertSame(callbacks.get(1),b.getTag(TAG));callbacks.get(0).accept(Result.VERIFIED);callbacks.get(1).accept(Result.VERIFIED);assertEquals(Arrays.asList("avatar","shield"),painted);
    }
    @Test public void cachedResultAndDestroyedOwnerDoNotScheduleOrPaint(){
        View owner=new View(activity);core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.needsCheck(TOKEN,A)).thenReturn(false);
        WebValidate.ensure(activity,owner,TOKEN,A,()->fail("cached result rescheduled"));assertTrue(callbacks.isEmpty());assertNull(owner.getTag(TAG));
        WebValidate.ensure(activity,owner,TOKEN,B,()->fail("destroyed Activity painted"));controller.pause().stop().destroy();callbacks.get(0).accept(Result.VERIFIED);assertNull(owner.getTag(TAG));WebValidate.ensure(activity,owner,TOKEN,A,()->fail());assertEquals(1,callbacks.size());
    }
    @Test public void statusIncludesUrlAndAllowsUnknownState(){
        core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.status(TOKEN,A)).thenReturn(true);
        core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.status(TOKEN,B)).thenReturn(false);
        core.when(()->com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.status(TOKEN,"https://example.invalid/unknown")).thenReturn(null);
        assertEquals(Boolean.TRUE,WebValidate.status(TOKEN,A));assertEquals(Boolean.FALSE,WebValidate.status(TOKEN,B));assertNull(WebValidate.status(TOKEN,"https://example.invalid/unknown"));
    }
}
