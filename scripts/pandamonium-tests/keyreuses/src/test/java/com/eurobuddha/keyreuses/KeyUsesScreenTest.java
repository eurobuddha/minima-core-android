package com.eurobuddha.keyreuses;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.mockito.MockedConstruction;
import android.os.Looper;
import android.widget.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.NodeAuditKeys;
import com.eurobuddha.futurecashnext.AuditApi;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=33)
public class KeyUsesScreenTest {
    static NodeAuditKeys fake;
    static String wallet;
    public static class Screen extends MainActivity {
        @Override NodeAuditKeys auditKeys(){return fake;}
        @Override String walletKeys(){return wallet;}
    }
    static Object field(Object object,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static void run(Screen s)throws Exception{Method m=MainActivity.class.getDeclaredMethod("runAudit");m.setAccessible(true);m.invoke(s);}
    static void drainUntil(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertTrue("worker callback timed out",condition.getAsBoolean());
    }
    static void noPhraseInput(View view){assertFalse(view instanceof EditText);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)noPhraseInput(((ViewGroup)view).getChildAt(i));}
    @Test public void actualScreenRequestsAllThreeSetsAndRejectsWalletChanges()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        wallet=AuditSelectionTest.wallet(false,7);fake=mock(NodeAuditKeys.class);
        Constructor<NodeAuditKeys.Snapshot> ctor=NodeAuditKeys.Snapshot.class.getDeclaredConstructor(String.class,String.class,String.class);ctor.setAccessible(true);
        NodeAuditKeys.Snapshot snapshot=ctor.newInstance(AuditSelectionTest.node(),AuditSelectionTest.derived(),"public-fixture-identity");
        when(fake.prepare(any())).thenReturn(snapshot);when(fake.current(snapshot)).thenReturn(true);
        AtomicReference<AuditApi.Cb> cb=new AtomicReference<>();AtomicReference<List<String>> sent=new AtomicReference<>();
        try(MockedConstruction<AuditApi> mocks=mockConstruction(AuditApi.class,(client,context)->{
            doAnswer(a->{sent.set(new ArrayList<>(a.getArgument(0)));cb.set(a.getArgument(2));return null;}).when(client).audit(anyList(),anyList(),any());
        })){
            var controller=Robolectric.buildActivity(Screen.class);
            controller.get().setTheme(R.style.pm_keyreuses_Theme_KeyUses);
            controller.create().start().resume();Screen screen=controller.get();
            try{
                CheckBox toggle=(CheckBox)field(screen,"walletToggle");toggle.setChecked(true);
                for(String language:new String[]{"en","zh-Hans","ru","uk"}){
                    Language.select(screen,language);toggle=(CheckBox)field(screen,"walletToggle");
                    assertTrue(toggle.isChecked());assertEquals(Copy.includeWallet(),toggle.getText().toString());
                    assertEquals(Copy.run(),((TextView)field(screen,"runLabel")).getText().toString());
                    noPhraseInput(screen.findViewById(android.R.id.content));
                    View menu=screen.getWindow().getDecorView().findViewWithTag("keyuses.menu");
                    assertNotNull(menu);assertTrue(menu.isClickable());
                    assertEquals(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_drawer_open).toString(),menu.getContentDescription().toString());
                }
                run(screen);drainUntil(()->cb.get()!=null);assertEquals(160,sent.get().size());
                wallet=AuditSelectionTest.wallet(false,8);cb.get().onResult(Collections.emptyList(),Collections.emptyList(),123);
                drainUntil(()->{try{return !(Boolean)field(screen,"busy");}catch(Exception e){throw new RuntimeException(e);}});
                assertEquals(Copy.changed(),((TextView)field(screen,"statusBox")).getText().toString());
                assertNull(field(screen,"lastResult"));
                Language.select(screen,"en");assertEquals(Copy.changed(),((TextView)field(screen,"statusBox")).getText().toString());
                // A node counter/seed change invalidates the result independently of the wallet.
                cb.set(null);run(screen);drainUntil(()->cb.get()!=null);
                when(fake.current(snapshot)).thenReturn(false);
                cb.get().onResult(Collections.emptyList(),Collections.emptyList(),123);
                drainUntil(()->{try{return !(Boolean)field(screen,"busy");}catch(Exception e){throw new RuntimeException(e);}});
                assertEquals(Copy.changed(),((TextView)field(screen,"statusBox")).getText().toString());
                when(fake.current(snapshot)).thenReturn(true);
                // Missing archive evidence renders an incomplete audit, never a green verdict.
                cb.set(null);run(screen);drainUntil(()->cb.get()!=null);
                cb.get().onResult(Collections.emptyList(),Collections.emptyList(),123);
                drainUntil(()->{try{return field(screen,"lastResult")!=null;}catch(Exception e){throw new RuntimeException(e);}});
                com.eurobuddha.futurecashnext.KeyAudit.Result result=(com.eurobuddha.futurecashnext.KeyAudit.Result)field(screen,"lastResult");
                assertEquals(160,result.rows.size());assertFalse(result.complete);
                for(String language:new String[]{"en","zh-Hans","ru","uk"}) {
                    Language.select(screen,language);assertSame(result,field(screen,"lastResult"));
                    View root=screen.findViewById(android.R.id.content);
                    for(int group=0;group<3;group++) {
                        ViewGroup section=root.findViewWithTag("keyuses.section."+group);
                        int count=group==1?32:64;
                        assertEquals(count+2,section.getChildCount());
                        assertEquals(Copy.section(group,count),((TextView)section.getChildAt(0)).getText().toString());
                    }
                    assertChip(root,1,Copy.unknown(),Design.WARN);
                    assertEquals("#64",((TextView)root.findViewWithTag("keyuses.number.64")).getText().toString());
                    assertEquals("#1 · 128/4",((TextView)root.findViewWithTag("keyuses.number.65")).getText().toString());
                    assertEquals("#32 · 128/4",((TextView)root.findViewWithTag("keyuses.number.96")).getText().toString());
                    assertEquals("#1",((TextView)root.findViewWithTag("keyuses.number.97")).getText().toString());
                    assertEquals("#64",((TextView)root.findViewWithTag("keyuses.number.160")).getText().toString());
                }
                cb.set(null);run(screen);drainUntil(()->cb.get()!=null);
                AuditSelection chosen=(AuditSelection)field(screen,"selection");
                List<com.eurobuddha.futurecashnext.KeyAudit.Usage> usage=new ArrayList<>();
                List<com.eurobuddha.futurecashnext.KeyAudit.Reuse> reuse=new ArrayList<>();
                for(int i=0;i<chosen.keys.size();i++) {
                    long count=i==2?35:i==96?9:0;
                    usage.add(new com.eurobuddha.futurecashnext.KeyAudit.Usage(chosen.publicKeys.get(i),chosen.hexAddresses.get(i),count,count));
                    reuse.add(new com.eurobuddha.futurecashnext.KeyAudit.Reuse(chosen.hexAddresses.get(i),i<2,i==0?4:i==1?1:0));
                }
                cb.get().onResult(usage,reuse,123);
                drainUntil(()->{try{return field(screen,"lastResult")!=null;}catch(Exception e){throw new RuntimeException(e);}});
                for(String language:new String[]{"en","zh-Hans","ru","uk"}) {
                    Language.select(screen,language);
                    View root=screen.findViewById(android.R.id.content);
                    assertChip(root,1,Copy.reused()+" ×4",Design.RISK);
                    assertChip(root,2,Copy.reused()+" ×1",Design.WARN);
                    assertChip(root,3,Copy.used("35"),Design.OK);
                    assertChip(root,4,Copy.unused(),Design.OK);
                    assertChip(root,65,Copy.unused(),Design.OK);
                    assertChip(root,97,Copy.atRisk(),Design.RISK);
                    noHorizontalScroll(root);
                    // Exercise a narrow phone layout; the last numeric cell must remain inside the row.
                    int width=Math.round(320*screen.getResources().getDisplayMetrics().density);
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
                    root.layout(0,0,width,800);
                    View number=root.findViewWithTag("keyuses.number.65");
                    ViewGroup address=(ViewGroup)number.getParent();ViewGroup row=(ViewGroup)address.getParent();
                    assertEquals(4,row.getChildCount());assertTrue(address.getWidth()>0);
                    assertTrue(row.getChildAt(3).getRight()<=row.getWidth());

                }
                // Backgrounded screens discard late responses and cannot restore stale results.
                cb.set(null);run(screen);drainUntil(()->cb.get()!=null);
                screen.onStop();cb.get().onResult(Collections.emptyList(),Collections.emptyList(),123);
                Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(field(screen,"lastResult"));

            }finally{controller.pause().stop().destroy();}
        } finally{Language.select(RuntimeEnvironment.getApplication(),"en");}
    }
    static void assertChip(View root,int index,String label,int colour) {
        TextView chip=root.findViewWithTag("keyuses.status."+index);
        TextView number=root.findViewWithTag("keyuses.number."+index);
        assertEquals(label,chip.getText().toString());assertEquals(colour,chip.getCurrentTextColor());
        assertSame(number.getParent(),chip.getParent());
    }
    static void noHorizontalScroll(View view) {
        assertFalse(view instanceof HorizontalScrollView);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)noHorizontalScroll(((ViewGroup)view).getChildAt(i));
    }
}
