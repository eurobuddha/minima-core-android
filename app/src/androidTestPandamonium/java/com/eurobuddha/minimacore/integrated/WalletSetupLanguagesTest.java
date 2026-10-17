package com.eurobuddha.minimacore.integrated;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.wallet.MainActivity;
import com.eurobuddha.wallet.WalletSession;
import java.io.File;
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

/** Disposable form-only fixtures: never imports a seed or starts the node. */
@RunWith(AndroidJUnit4.class)
public class WalletSetupLanguagesTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void prepare() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu", Build.HARDWARE);
        assertNull("Requires a stopped disposable node", MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse("Requires an empty disposable wallet", WalletSession.get(context).vault().exists());
            assertFalse(WalletSession.get(context).work().busy());
            AppLock.disable(context); Language.select(context, "en");
        });
    }
    private static void form(MainActivity a, boolean node) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("onboardImport", boolean.class);
            method.setAccessible(true); method.invoke(a, node);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static <T extends View> List<T> views(View root, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(root)) out.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i=0; i<((ViewGroup)root).getChildCount(); i++)
            out.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return out;
    }
    private static View root(MainActivity a) { return a.findViewById(android.R.id.content); }
    private void settle() {
        android.os.SystemClock.sleep(500);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void screenshot(String name) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "language-tests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(directory,name+".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        } finally { bitmap.recycle(); }
    }
    @Test public void importDraftAndOpenConfirmationSurviveAllLanguages() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> original = new AtomicReference<>();
            List<EditText> fields = new ArrayList<>();
            String[] values = {"disposable language fixture only", "fixture-passphrase", "fixture-passphrase", "137"};
            scenario.onActivity(a -> {
                original.set(a); form(a,false);
                fields.addAll(views(root(a),EditText.class)); assertEquals(4,fields.size());
                for(int i=0;i<4;i++) { fields.get(i).setText(values[i]); fields.get(i).setSelection(2); }
            });
            for(String code : new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> Language.select(a,code)); settle();
                scenario.onActivity(a -> {
                    assertSame(original.get(),a);
                    List<EditText> current=views(root(a),EditText.class);
                    for(int i=0;i<4;i++) {
                        assertSame(fields.get(i),current.get(i));
                        assertEquals(values[i],current.get(i).getText().toString());
                        assertEquals(2,current.get(i).getSelectionStart());
                    }
                    assertEquals(Language.text(a,l10n_wallet_seed_hint),fields.get(0).getHint().toString());
                    assertEquals(Language.text(a,l10n_wallet_used_hint),fields.get(3).getHint().toString());
                    assertFalse(WalletSession.get(a).vault().exists());
                });
                onView(withText(Language.text(context,l10n_wallet_import_seed_title))).check(matches(isDisplayed()));
                screenshot("wallet-import-"+code);
            }
            scenario.onActivity(a -> {
                Button submit=null;
                for(Button b : views(root(a),Button.class)) if(b.getText().toString().equals(Language.text(a,l10n_wallet_import_wallet))) submit=b;
                assertNotNull(submit); assertTrue(submit.performClick());
            });settle();
            for(String code : new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_wallet_confirm_floor))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_wallet_confirm_used,137)+"\n\n"+Language.text(context,l10n_wallet_floor_scope)))
                        .inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_wallet_import))).inRoot(isDialog()).check(matches(isDisplayed()));
                screenshot("wallet-confirm-"+code);
                scenario.onActivity(a -> {
                    assertSame(original.get(),a);assertFalse(WalletSession.get(a).vault().exists());
                    assertEquals("137",fields.get(3).getText().toString());
                });
            }
            pressBack();settle();
            assertNull(MinimaService.minima);
        }
    }
    @Test public void nodeSeedFormKeepsSeedPrivateAndRetainsFloor() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> original=new AtomicReference<>();
            List<EditText> fields=new ArrayList<>();
            scenario.onActivity(a -> {
                original.set(a);form(a,true);fields.addAll(views(root(a),EditText.class));
                assertEquals(3,fields.size());fields.get(2).setText("137");fields.get(2).setSelection(1);
            });
            for(String code : new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_wallet_node_seed_title))).check(matches(isDisplayed()));
                scenario.onActivity(a -> {
                    assertSame(original.get(),a);assertEquals(3,views(root(a),EditText.class).size());
                    assertEquals("137",fields.get(2).getText().toString());assertEquals(1,fields.get(2).getSelectionStart());
                    for(TextView view:views(root(a),TextView.class)) assertNotEquals(Language.text(a,l10n_wallet_seed_hint),String.valueOf(view.getHint()));
                    assertFalse(WalletSession.get(a).vault().exists());assertNull(MinimaService.minima);
                });
            }
        }
    }
    @Test public void floorDialogRetainsInputAndShowsTranslatedLimits() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                try {
                    java.lang.reflect.Field field=MainActivity.class.getDeclaredField("mKeyUses");field.setAccessible(true);
                    field.set(a,new com.eurobuddha.wallet.KeyUses() {
                        public int currentUses(int i) { return 137; }
                        public int reserveNextUse(int i) { throw new AssertionError("Form test must not sign"); }
                        public void recordExternalUses(int i,int uses) { throw new AssertionError("Form test must not change counters"); }
                        public java.util.Map<Integer,Integer> snapshotAllUses() { return java.util.Collections.singletonMap(0,137); }
                    });
                    com.eurobuddha.wallet.SettingsView settings=new com.eurobuddha.wallet.SettingsView(a);
                    Method method=settings.getClass().getDeclaredMethod("setUsesManually");method.setAccessible(true);method.invoke(settings);
                } catch(Exception e) { throw new AssertionError(e); }
            });settle();
            AtomicReference<EditText> original=new AtomicReference<>();
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check((view,error) -> {
                if(error!=null) throw error;
                EditText field=(EditText)view;original.set(field);field.setText("163");field.setSelection(1);
            });
            for(String code:new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_wallet_floor_title))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_wallet_floor_body,137,262144))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_wallet_floor_enable))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check((view,error) -> {
                    if(error!=null) throw error;
                    EditText field=(EditText)view;assertSame(original.get(),field);
                    assertEquals("163",field.getText().toString());assertEquals(1,field.getSelectionStart());
                    assertEquals(Language.text(context,l10n_wallet_floor_hint),field.getHint().toString());
                });
                screenshot("wallet-floor-"+code);
            }
            pressBack();scenario.onActivity(a -> assertFalse(WalletSession.get(a).vault().exists()));
        }
    }
    @Test public void unlockPassphraseSurvivesLanguageChanges() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<EditText> input=new AtomicReference<>();
            scenario.onActivity(a -> {
                try {
                    Method method=MainActivity.class.getDeclaredMethod("showUnlock");method.setAccessible(true);method.invoke(a);
                    List<EditText> fields=views(root(a),EditText.class);assertEquals(1,fields.size());
                    input.set(fields.get(0));input.get().setText("fixture-passphrase");input.get().setSelection(4);
                } catch(Exception e) { throw new AssertionError(e); }
            });
            for(String code:new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_wallet_unlock_title))).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_lock_unlock))).check(matches(isDisplayed()));
                scenario.onActivity(a -> {
                    assertSame(input.get(),views(root(a),EditText.class).get(0));
                    assertEquals("fixture-passphrase",input.get().getText().toString());assertEquals(4,input.get().getSelectionStart());
                    assertFalse(WalletSession.get(a).vault().exists());
                });
            }
        }
    }

}
