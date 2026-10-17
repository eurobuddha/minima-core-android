package com.eurobuddha.wallet;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

/** Form-only fixtures: no vault, seed, signing, backup export/restore or biometric authentication. */
@RunWith(AndroidJUnit4.class)
public class WalletSettingsLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String[] codes={"zh-Hans","ru","uk","en"};
    @Before public void prepare() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(WalletSession.get(context).vault().exists());
            assertFalse(WalletSession.get(context).work().busy());
            AppLock.disable(context);Language.select(context,"en");
        });
    }
    private static Object invoke(Object target,String name) {
        try {Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static <T extends View> List<T> views(View root,Class<T> type) {
        List<T> result=new ArrayList<>();if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            result.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return result;
    }
    private void settle() {
        android.os.SystemClock.sleep(350);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void screenshot(String name) throws Exception {
        java.io.File folder=new java.io.File(context.getExternalFilesDir(null),"language-tests");
        assertTrue(folder.isDirectory()||folder.mkdirs());
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        } finally {bitmap.recycle();}
    }
    private void checkForm(String method,int title,int body,int positive,int... hints) throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> activity=new AtomicReference<>();
            scenario.onActivity(a -> {activity.set(a);invoke(new SettingsView(a),method);});settle();
            List<EditText> original=new ArrayList<>();
            onView(withText(Language.text(context,title))).inRoot(isDialog()).check((view,error) -> {
                if(error!=null)throw error;
                original.addAll(views(view.getRootView(),EditText.class));assertEquals(hints.length,original.size());
                for(int i=0;i<original.size();i++) {
                    original.get(i).setText("fixture-passphrase-"+i);original.get(i).setSelection(i+1);
                }
            });
            for(String code:codes) {
                scenario.onActivity(a -> {assertSame(activity.get(),a);Language.select(a,code);});settle();
                onView(withText(Language.text(context,title))).inRoot(isDialog()).check((view,error) -> {
                    if(error!=null)throw error;
                    List<EditText> current=views(view.getRootView(),EditText.class);assertEquals(original.size(),current.size());
                    for(int i=0;i<current.size();i++) {
                        assertSame(original.get(i),current.get(i));
                        assertEquals("fixture-passphrase-"+i,current.get(i).getText().toString());
                        assertEquals(i+1,current.get(i).getSelectionStart());
                        assertEquals(Language.text(context,hints[i]),current.get(i).getHint().toString());
                    }
                });
                onView(withText(Language.text(context,body))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(Language.text(context,positive))).inRoot(isDialog()).check(matches(isDisplayed()));
                screenshot("wallet-settings-"+method+"-"+code);
                scenario.onActivity(a -> {assertFalse(a.vault().exists());assertFalse(a.vault().isOpen());});
            }
            pressBack();assertNull(MinimaService.minima);
        }
    }
    @Test public void changePassphraseDraftSurvives() throws Exception {
        checkForm("changePassphrase",l10n_wallet_change_passphrase,l10n_wallet_change_passphrase_body,
                l10n_wallet_change,l10n_wallet_current_passphrase,l10n_wallet_new_passphrase,l10n_wallet_confirm_new_passphrase);
    }
    @Test public void backupPasswordDraftSurvives() throws Exception {
        checkForm("exportBackup",l10n_wallet_export_backup,l10n_wallet_export_backup_body,
                l10n_wallet_choose_file,l10n_wallet_backup_passphrase_hint);
    }
    @Test public void biometricAndSeedAuthenticationDraftsSurvive() throws Exception {
        checkForm("enableBiometric",l10n_biometric_enable,l10n_wallet_bio_confirm,l10n_wallet_continue,l10n_wallet_wallet_passphrase);
        checkForm("revealSeed",l10n_wallet_reveal_seed,l10n_wallet_reveal_seed_warning,l10n_wallet_reveal,l10n_wallet_wallet_passphrase);
    }
    @Test public void timeoutSelectionAndValuesRemainStable() throws Exception {
        long old=WalletSession.get(context).timeoutMs();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<SettingsView> settings=new AtomicReference<>();
            scenario.onActivity(a -> {a.session().setTimeoutMs(900000L);settings.set(new SettingsView(a));invoke(settings.get(),"pickAutoLock");});settle();
            int[] labels={l10n_lock_immediately,l10n_lock_one_minute,l10n_lock_five_minutes,l10n_lock_fifteen_minutes,l10n_wallet_thirty_minutes,l10n_lock_one_hour};
            AtomicReference<ListView> original=new AtomicReference<>();
            for(String code:codes) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(isAssignableFrom(ListView.class)).inRoot(isDialog()).check((view,error) -> {
                    if(error!=null)throw error;ListView list=(ListView)view;
                    if(original.get()==null)original.set(list);else assertSame(original.get(),list);
                    assertEquals(3,list.getCheckedItemPosition());assertEquals(6,list.getAdapter().getCount());
                    for(int i=0;i<labels.length;i++)assertEquals(Language.text(context,labels[i]),list.getAdapter().getItem(i).toString());
                    assertEquals(900000L,WalletSession.get(context).timeoutMs());
                });
                screenshot("wallet-settings-timeout-"+code);
            }
            onView(isAssignableFrom(ListView.class)).inRoot(isDialog()).check((view,error) -> {
                if(error!=null)throw error;ListView list=(ListView)view;
                assertTrue(list.performItemClick(list.getChildAt(4),4,list.getAdapter().getItemId(4)));
            });settle();assertEquals(1800000L,WalletSession.get(context).timeoutMs());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> WalletSession.get(context).setTimeoutMs(old));
        }
    }
    @Test public void platformBiometricPromptUsesSelectedLanguage() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            for(String code:codes) {
                scenario.onActivity(a -> {
                    Language.select(a,code);
                    try {
                        Method method=BiometricUnlock.class.getDeclaredMethod("promptInfo",Context.class,String.class,String.class);
                        method.setAccessible(true);
                        androidx.biometric.BiometricPrompt.PromptInfo info=(androidx.biometric.BiometricPrompt.PromptInfo)method.invoke(null,a,
                                Language.text(a,l10n_biometric_enable),Language.text(a,l10n_wallet_bio_protect_passphrase));
                        assertEquals(Language.text(a,l10n_biometric_enable),info.getTitle().toString());
                        assertEquals(Language.text(a,l10n_wallet_bio_protect_passphrase),info.getSubtitle().toString());
                        assertEquals(Language.text(a,l10n_wallet_use_passphrase),info.getNegativeButtonText().toString());
                        assertEquals(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,info.getAllowedAuthenticators());
                    } catch(Exception e){throw new AssertionError(e);}
                });
            }
        }
    }
}
