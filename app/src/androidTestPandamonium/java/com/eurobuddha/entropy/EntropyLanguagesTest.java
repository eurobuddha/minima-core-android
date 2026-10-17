package com.eurobuddha.entropy;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public deterministic fixtures only. Secure windows stay secure and are never captured. */
@RunWith(AndroidJUnit4.class)
public class EntropyLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private DirectNodeApi.Backend backend;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            backend=(DirectNodeApi.Backend)getStatic(DirectNodeApi.class,"backend");DirectNodeApi.install(null);
            AppLock.disable(context);Language.select(context,"en");Theme.setSound(context,false);
        });
    }
    @After public void reset(){
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");DirectNodeApi.install(backend);});
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    static Object getStatic(Class<?> c,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(null);}catch(Exception e){throw new AssertionError(e);}}
    static Object get(Object o,String n){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
    static Object call(Object o,String n,Class<?>[] types,Object... args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
    static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(100);}
    static void secure(MainActivity a){assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);assertFalse(a.demoMode());assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());}
    static void freeze(MainActivity a){
        ((Handler)get(a,"main")).removeCallbacksAndMessages(null);
        NodeApi node=(NodeApi)get(a,"node");if(node!=null)node.onDestroy();set(a,"node",null);set(a,"engine",new RollEngine(null));set(a,"engineReady",true);
        assertNull(a.selfTestError());secure(a);
    }
    static TextView exact(View root,CharSequence expected){
        for(TextView v:texts(root))if(expected.toString().equals(v.getText().toString()))return v;
        throw new AssertionError("Expected translated display label: "+expected);
    }
    static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
    @Test public void wordDraftCursorAndSecureWindowSurviveLanguageChanges(){
        AtomicReference<MainActivity> owner=new AtomicReference<>();AtomicReference<LastWordView> page=new AtomicReference<>();AtomicReference<EditText> input=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);owner.set(a);LastWordView w=(LastWordView)get(a,"lastWordView");page.set(w);EditText e=((List<EditText>)get(w,"boxes")).get(0);input.set(e);e.setText("ab");e.setSelection(1);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                assertSame(owner.get(),a);assertSame(page.get(),get(a,"lastWordView"));assertEquals("ab",input.get().getText().toString());assertEquals(1,input.get().getSelectionStart());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO,input.get().getImportantForAutofill());secure(a);
                exact(page.get().getRoot(),Language.upper(Language.msg(l10n_entropy_last_calculator)));
                com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_entropy_tabs);
                assertEquals(Language.msg(l10n_entropy_word_tab).toString(),tabs.getTabAt(1).getText().toString());
            });}
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            scenario.onActivity(a->{
                secure(a);assertEquals("ab",input.get().getText().toString());
                android.content.SharedPreferences prefs=a.getSharedPreferences("main_prefs",0);
                boolean existed=prefs.contains("ALLOW_SCREENSHOTS"),prior=prefs.getBoolean("ALLOW_SCREENSHOTS",false);
                try {((com.eurobuddha.minimacore.MinimaApplication)a.getApplication()).setScreenshotsAllowed(true);secure(a);}
                finally {if(existed)prefs.edit().putBoolean("ALLOW_SCREENSHOTS",prior).commit();else prefs.edit().remove("ALLOW_SCREENSHOTS").commit();}
            });
        }
        for(EditText e:(List<EditText>)get(page.get(),"boxes"))assertEquals("",e.getText().toString()); // teardown resets the page
    }
    @Test public void physicalRollHistoryAndProgressStayLiteral(){
        AtomicReference<DiceView> page=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);DiceView d=(DiceView)get(a,"diceView");page.set(d);((View)get(d,"btnPhysical")).performClick();for(int f:new int[]{1,2,6})call(d,"physicalTap",new Class<?>[]{int.class},f);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                DiceView d=page.get();assertSame(d,get(a,"diceView"));assertEquals(Arrays.asList(1,2,6),get(d,"faces"));
                assertEquals("126",((TextView)get(d,"ticker")).getText().toString());assertEquals(3,((List<?>)get(d,"audits")).size());
                exact(d.getRoot(),Language.msg(l10n_entropy_progress,6,128,3));exact(d.getRoot(),Language.msg(l10n_entropy_physical_warning));
                assertEquals(true,get(d,"physical"));assertEquals(false,get(d,"auto"));secure(a);
            });}
            scenario.onActivity(a->page.get().wipe());
            scenario.onActivity(a->{assertTrue(((List<?>)get(page.get(),"faces")).isEmpty());exact(page.get().getRoot(),Language.msg(l10n_entropy_progress,0,128,0));});
        }
    }
    @Test public void checksumCandidatesAndChosenPublicWordSurviveSwitching(){
        AtomicReference<LastWordView> page=new AtomicReference<>();AtomicReference<List<String>> expected=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);LastWordView w=(LastWordView)get(a,"lastWordView");page.set(w);set(w,"muteWatcher",true);for(EditText e:(List<EditText>)get(w,"boxes"))e.setText("abandon");set(w,"muteWatcher",false);call(w,"calc",new Class<?>[]{});expected.set(new ArrayList<>((List<String>)get(w,"candidates")));assertEquals(128,expected.get().size());call(w,"pick",new Class<?>[]{String.class,CharSequence.class},"about",Language.msg(l10n_entropy_manual));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                LastWordView w=page.get();assertEquals(expected.get(),get(w,"candidates"));assertEquals("about",((EditText)get(w,"lastBox")).getText().toString());
                for(EditText e:(List<EditText>)get(w,"boxes"))assertEquals("abandon",e.getText().toString());
                exact(w.getRoot(),Language.msg(l10n_entropy_complete,12,"about",Language.msg(l10n_entropy_manual)));secure(a);
            });}
            scenario.onActivity(a->{page.get().hideAll();assertEquals(View.INVISIBLE,((View)get(page.get(),"status")).getVisibility());page.get().clearAll();assertNull(get(page.get(),"candidates"));});
        }
    }
    @Test public void completedPublicDiceVectorRetainsWordsAndTranslatedAudit(){
        AtomicReference<DiceView> page=new AtomicReference<>();AtomicReference<List<String>> expected=new AtomicReference<>();
        String vector="25262423135241341562253664545432446414112156541463663153452544512632242356432255434125363";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);DiceView d=(DiceView)get(a,"diceView");page.set(d);List<Integer> faces=(List<Integer>)get(d,"faces");List<RollEngine.Audit> audits=(List<RollEngine.Audit>)get(d,"audits");for(char c:vector.toCharArray()){int f=c-'0';faces.add(f);audits.add(new RollEngine.Audit(RollEngine.SRC_PHYSICAL,"","",f-1,f));}call(d,"finish",new Class<?>[]{});expected.set(new ArrayList<>((List<String>)get(d,"phrase")));assertEquals("wedding crawl scout then coast emotion bridge spot demise together holiday tray",String.join(" ",expected.get()));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                DiceView d=page.get();assertEquals(expected.get(),get(d,"phrase"));assertFalse((Boolean)get(d,"revealed"));assertEquals(vector,((TextView)get(d,"verifyRolls")).getText().toString());
                String log=((TextView)get(d,"auditLog")).getText().toString();assertTrue(log.startsWith(Language.msg(l10n_entropy_audit_physical,1,2).toString()));
                assertTrue(((TextView)get(d,"doneSummary")).getText().toString().contains(Language.msg(l10n_entropy_source_physical).toString()));secure(a);
            });}
            scenario.onActivity(a->{page.get().wipe();assertNull(get(page.get(),"phrase"));assertEquals("",((TextView)get(page.get(),"auditLog")).getText().toString());});
        }
    }
    @Test public void selfTestFailureStaysBlockedAndControlsFitAcrossLanguages(){
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{freeze(a);set(a,"selfTestErr","BIP39 vector 2 failed");DiceView d=(DiceView)get(a,"diceView");((View)get(d,"btnThrow")).performClick();LastWordView w=(LastWordView)get(a,"lastWordView");call(w,"calc",new Class<?>[]{});});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                DiceView d=(DiceView)get(a,"diceView");LastWordView w=(LastWordView)get(a,"lastWordView");
                assertEquals(0xFF8A93AD,((TextView)a.findViewById(R.id.pm_entropy_subtitle)).getCurrentTextColor());
                com.google.android.material.tabs.TabLayout tabs=a.findViewById(R.id.pm_entropy_tabs);
                assertEquals(0xFF8A93AD,tabs.getTabTextColors().getColorForState(new int[]{-android.R.attr.state_selected},0));
                CharSequence detail=EntropyMessages.message("BIP39 vector 2 failed");
                exact(d.getRoot(),Language.msg(l10n_entropy_blocked_self,detail));exact(w.getRoot(),Language.msg(l10n_entropy_blocked_startup,detail));
                assertTrue(((List<?>)get(d,"faces")).isEmpty());assertNull(get(d,"phrase"));assertNull(get(w,"candidates"));secure(a);
                for(TextView t:texts(a.findViewById(android.R.id.content))){
                    if(!t.isShown()||t.getLayout()==null||t instanceof EditText)continue;
                    android.text.Layout layout=t.getLayout();
                    for(int line=0;line<layout.getLineCount();line++)assertEquals("Text must not be ellipsized",0,layout.getEllipsisCount(line));
                    assertTrue("Control must fit its text height",layout.getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom()+2);
                }
            });}
        }
    }

}
