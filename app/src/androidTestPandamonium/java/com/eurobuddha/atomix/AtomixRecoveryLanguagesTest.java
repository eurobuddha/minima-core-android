package com.eurobuddha.atomix;
import android.content.*;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.atomix.integratedcomms.NodeApi;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Exercises only the real local pause marker. No node dispatch, seed, signing or balance mutation. */
public class AtomixRecoveryLanguagesTest extends AtomixMarketLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 static Object safety(String name,Class<?>[]types,Object...args){try{java.lang.reflect.Method m=Class.forName("com.eurobuddha.atomix.integratedcomms.WriteSafety").getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}catch(Exception e){throw new AssertionError(e);}}
 static SharedPreferences prefs(MainActivity a){return a.getSharedPreferences("atomix_write_safety",0);}
 static void mark(MainActivity a,String reason){safety("resetForTest",new Class<?>[]{});assertTrue(prefs(a).edit().clear().putString("pending","public-fixture").putString("pending_cmd","txnpost").putLong("pending_at",System.currentTimeMillis()-120000).putString("pending_why",reason).commit());set(a,"currentTab",1);call(a,"render");stopTicks(a);}
 @After public void resetMarker(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->safety("resetForTest",new Class<?>[]{}));}
 @Test public void eachCauseTranslatesInPlaceAndCancelKeepsAllEvidence()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();run.onActivity(a->{freeze(a);publicWallet(a);});
   for(String reason:new String[]{"TIMEOUT","INCOMPLETE","CALLBACK",""}){
    AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<String>detail=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    run.onActivity(a->{mark(a,reason);detail.set(((NodeApi)get(a,"node")).interruptedWriteDetail());saved.set(new HashMap<>(prefs(a).getAll()));exact(page(a),Language.msg(l10n_atomix_recovery_resolve)).performClick();d.set(dialog(a));});
    for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals(saved.get(),prefs(a).getAll());assertTrue(((NodeApi)get(a,"node")).hasInterruptedWrite());exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_recovery_body,RecoveryMessages.message(detail.get())));assertEquals(Language.msg(l10n_atomix_recovery_checked).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(Language.msg(l10n_atomix_recovery_keep).toString(),d.get().getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());});}
    screenshot("atomix-recovery-"+(reason.isEmpty()?"stopped":reason.toLowerCase(Locale.ROOT)));run.onActivity(a->d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();run.onActivity(a->{assertEquals(saved.get(),prefs(a).getAll());assertTrue(((NodeApi)get(a,"node")).hasInterruptedWrite());stopTicks(a);});
   }
  }
 }
 @Test public void explicitConsentClearsOnlyPauseEvidenceAndNeverSends()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<Map<String,?>>saved=new AtomicReference<>();run.onActivity(a->{freeze(a);publicWallet(a);mark(a,"INCOMPLETE");prefs(a).edit().putString("unrelated","retain 中文 {0}").commit();saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));exact(page(a),Language.msg(l10n_atomix_recovery_resolve)).performClick();});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();}
   run.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();run.onActivity(a->{assertFalse(((NodeApi)get(a,"node")).hasInterruptedWrite());assertEquals(Collections.singletonMap("unrelated","retain 中文 {0}"),prefs(a).getAll());assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());assertTrue(((java.util.concurrent.ExecutorService)get(a,"io")).isShutdown());stopTicks(a);});
  }
 }
 @Test public void staleConsentCannotClearANewerActiveWrite()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<Map<String,?>>saved=new AtomicReference<>();run.onActivity(a->{freeze(a);publicWallet(a);mark(a,"TIMEOUT");exact(page(a),Language.msg(l10n_atomix_recovery_resolve)).performClick();safety("returned",new Class<?>[]{SharedPreferences.class,String.class,boolean.class},prefs(a),"public-fixture",true);assertEquals(true,safety("begin",new Class<?>[]{SharedPreferences.class,String.class,String.class},prefs(a),"new-public-fixture","send amount:1"));saved.set(new HashMap<>(prefs(a).getAll()));});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->assertEquals(saved.get(),prefs(a).getAll()));}
   run.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();run.onActivity(a->{assertEquals(saved.get(),prefs(a).getAll());safety("uncertain",new Class<?>[]{SharedPreferences.class,String.class},prefs(a),"new-public-fixture");assertTrue(((NodeApi)get(a,"node")).hasInterruptedWrite());stopTicks(a);});
  }
 }
 @Test public void staleConsentCannotClearANewerInterruptedWrite()throws Exception{
  try(ActivityScenario<MainActivity>run=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<Map<String,?>>saved=new AtomicReference<>();run.onActivity(a->{freeze(a);publicWallet(a);mark(a,"TIMEOUT");exact(page(a),Language.msg(l10n_atomix_recovery_resolve)).performClick();safety("returned",new Class<?>[]{SharedPreferences.class,String.class,boolean.class},prefs(a),"public-fixture",true);assertEquals(true,safety("begin",new Class<?>[]{SharedPreferences.class,String.class,String.class},prefs(a),"new-public-fixture","send amount:1"));safety("uncertain",new Class<?>[]{SharedPreferences.class,String.class},prefs(a),"new-public-fixture");saved.set(new HashMap<>(prefs(a).getAll()));});
   for(String lang:LANGS){run.onActivity(a->Language.select(a,lang));settle();run.onActivity(a->assertEquals(saved.get(),prefs(a).getAll()));}
   run.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();run.onActivity(a->{assertEquals(saved.get(),prefs(a).getAll());assertTrue(((NodeApi)get(a,"node")).hasInterruptedWrite());stopTicks(a);});
  }
 }
}
