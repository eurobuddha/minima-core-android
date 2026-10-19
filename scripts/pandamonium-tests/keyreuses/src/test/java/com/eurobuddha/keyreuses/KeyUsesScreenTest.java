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
                Language.select(screen,"uk");assertSame(result,field(screen,"lastResult"));
                // Backgrounded screens discard late responses and cannot restore stale results.
                cb.set(null);run(screen);drainUntil(()->cb.get()!=null);
                screen.onStop();cb.get().onResult(Collections.emptyList(),Collections.emptyList(),123);
                Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(field(screen,"lastResult"));

            }finally{controller.pause().stop().destroy();}
        } finally{Language.select(RuntimeEnvironment.getApplication(),"en");}
    }
}
