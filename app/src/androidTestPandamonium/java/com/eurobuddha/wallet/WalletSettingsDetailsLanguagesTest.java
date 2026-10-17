package com.eurobuddha.wallet;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

/** Empty disposable wallet. Tests never enable signing, import coins or start a node. */
@RunWith(AndroidJUnit4.class)
public class WalletSettingsDetailsLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String[] codes={"zh-Hans","ru","uk","en"};
    @Before public void prepare() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(WalletSession.get(context).vault().exists());
            assertFalse(WalletSession.get(context).vault().isOpen());
            assertFalse(WalletSession.get(context).work().busy());
            AppLock.disable(context);Language.select(context,"en");
        });
    }
    private static void set(Object target,String name,Object value) {
        try {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Object get(Object target,String name) {
        try {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void invoke(Object target,String name,Class<?>[] types,Object... values) {
        try {Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,values);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void invoke(Object target,String name){invoke(target,name,new Class<?>[0]);}
    private static <T extends View> List<T> views(View root,Class<T> type) {
        List<T> result=new ArrayList<>();if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return result;
    }
    private void settle(){android.os.SystemClock.sleep(350);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private void screenshot(String name)throws Exception {
        java.io.File folder=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(folder.isDirectory()||folder.mkdirs());
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    @Test public void safetyConfirmationsTranslateWithoutChangingTrust()throws Exception {
        String[] methods={"confirmBrandNew","attestRestored"};
        int[] titles={l10n_wallet_confirm_new_seed,l10n_wallet_backup_current};
        int[] bodies={l10n_wallet_confirm_new_seed_body,l10n_wallet_backup_current_body};
        int[] buttons={l10n_wallet_yes_unused,l10n_wallet_confirm_latest};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            for(int i=0;i<methods.length;i++) {
                final int index=i;
                scenario.onActivity(a -> invoke(new SettingsView(a),methods[index]));settle();
                AtomicReference<View> dialogRoot=new AtomicReference<>();
                for(String code:codes) {
                    scenario.onActivity(a -> Language.select(a,code));settle();
                    onView(withText(Language.text(context,titles[i]))).inRoot(isDialog()).check((view,error) -> {
                        if(error!=null)throw error;
                        if(dialogRoot.get()==null)dialogRoot.set(view.getRootView());else assertSame(dialogRoot.get(),view.getRootView());
                    });
                    onView(withText(Language.text(context,bodies[i]))).inRoot(isDialog()).check(matches(isDisplayed()));
                    onView(withText(Language.text(context,buttons[i]))).inRoot(isDialog()).check(matches(isDisplayed()));
                    scenario.onActivity(a -> {assertFalse(a.vault().isOpen());assertFalse(a.vault().isKeyUsesTrusted());assertFalse(a.vault().exists());});
                    screenshot("wallet-safety-"+methods[i]+"-"+code);
                }
                pressBack();settle();
            }
        }
    }
    @Test public void settingsRetainCoinProofDraftAndLiteralCommand()throws Exception {
        String proof=" 0x0123456789ABCDEF\n 00aabbcc ";
        String command="megammrsync action:resync host:megammr.minima.global:9001";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<SettingsView> settings=new AtomicReference<>();AtomicReference<EditText> input=new AtomicReference<>();
            scenario.onActivity(a -> {
                // Permit rendering only. Real vault remains absent/closed, with a read-only counter fixture.
                set(a,"mUiSession",(java.util.function.BooleanSupplier)()->true);
                set(a,"mKeyUses",new KeyUses(){
                    public int currentUses(int i){return 137;}
                    public int reserveNextUse(int i){throw new AssertionError("No signing");}
                    public void recordExternalUses(int i,int n){throw new AssertionError("No counter changes");}
                    public java.util.Map<Integer,Integer> snapshotAllUses(){return java.util.Collections.singletonMap(0,137);}
                });
                settings.set(new SettingsView(a));a.setContentView(settings.get().getRoot());
                List<EditText> fields=views(settings.get().getRoot(),EditText.class);assertEquals(1,fields.size());
                input.set(fields.get(0));input.get().setText(proof);input.get().setSelection(3);
            });settle();
            for(String code:codes) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                scenario.onActivity(a -> {
                    assertSame(input.get(),views(settings.get().getRoot(),EditText.class).get(0));
                    assertEquals(proof,input.get().getText().toString());assertEquals(3,input.get().getSelectionStart());
                    assertEquals(Language.text(a,l10n_wallet_coin_export_hint),input.get().getHint().toString());
                    List<String> texts=new ArrayList<>();for(TextView v:views(settings.get().getRoot(),TextView.class))texts.add(v.getText().toString());
                    for(int resource:new int[]{l10n_wallet_security,l10n_wallet_signatures_used,l10n_wallet_untrusted_title,
                        l10n_wallet_signature_help,l10n_wallet_historic_coins,l10n_wallet_node_connection,l10n_wallet_run_diagnostics,l10n_wallet_resync_warning})
                        assertTrue("Missing translated resource "+resource,texts.contains(Language.text(a,resource)));
                    assertTrue(texts.contains("137 / 262144"));assertTrue(texts.contains(command));
                    assertFalse(a.vault().isKeyUsesTrusted());assertFalse(a.vault().exists());assertNull(a.node());
                });
            }
        }
    }
    @Test public void diagnosticReportAndClipboardRetainRawJsonAcrossLanguages()throws Exception {
        JSONObject raw=new JSONObject().put("status",false).put("error","fixture_error_exact")
                .put("amount","12.340000").put("tokenid","0x001122").put("name","中文 / Русский / Українська");
        String exact=raw.toString(2);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<SettingsView> settings=new AtomicReference<>();AtomicReference<TextView> report=new AtomicReference<>();
            scenario.onActivity(a -> {
                settings.set(new SettingsView(a));invoke(settings.get(),"runDiagnostics");
                invoke(settings.get(),"diagRaw",new Class<?>[]{JSONObject.class},raw);
                report.set((TextView)get(settings.get(),"mDiagText"));
            });settle();
            for(String code:codes) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_wallet_wallet_diagnostics))).inRoot(isDialog()).check(matches(isDisplayed()));
                scenario.onActivity(a -> {
                    assertSame(report.get(),get(settings.get(),"mDiagText"));String value=report.get().getText().toString();
                    assertTrue(value.contains(exact));assertTrue(value.contains(Language.text(a,l10n_wallet_node_link_unavailable)));
                    assertTrue(value.contains(Language.text(a,l10n_wallet_node_disconnected_help)));
                    assertTrue(value.contains(Language.text(a,l10n_wallet_raw_response)));
                });
                onView(withText(Language.text(context,l10n_wallet_copy))).inRoot(isDialog()).perform(click());settle();
                scenario.onActivity(a -> {
                    ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                    assertNotNull(clipboard.getPrimaryClip());
                    assertEquals(report.get().getText().toString(),clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    assertFalse(a.vault().exists());assertNull(MinimaService.minima);
                });
                screenshot("wallet-diagnostics-"+code);
            }
            pressBack();
        }
    }
}
