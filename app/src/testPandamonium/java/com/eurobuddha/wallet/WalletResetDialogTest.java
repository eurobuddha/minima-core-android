package com.eurobuddha.wallet;

import android.app.Application;
import android.view.*;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class WalletResetDialogTest {
    static EditText entry(View v){
        if(v instanceof EditText)return (EditText)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            EditText found=entry(((ViewGroup)v).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    static void set(Object o,Class<?> type,String name,Object v)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(o,v);
    }
    @Test public void exactTypedConfirmationCancelAndExpiredSessionInAllLanguages()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        MainActivity activity=spy(Robolectric.buildActivity(MainActivity.class).get());
        activity.setTheme(com.eurobuddha.wallet.R.style.pm_wallet_Theme_Utxo);
        doNothing().when(activity).resetWallet();
        SettingsView settings=mock(SettingsView.class,CALLS_REAL_METHODS);AtomicBoolean active=new AtomicBoolean(true);
        set(settings,BaseView.class,"act",activity);set(settings,SettingsView.class,"session",(java.util.function.BooleanSupplier)active::get);
        Method confirm=SettingsView.class.getDeclaredMethod("confirmReset");confirm.setAccessible(true);
        try {
            for(String language:new String[]{"en","zh-Hans","ru","uk"}) {
                Language.select(RuntimeEnvironment.getApplication(),language);
                confirm.invoke(settings);AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();
                EditText input=entry(d.getWindow().getDecorView());assertNotNull(input);
                android.widget.Button erase=d.getButton(AlertDialog.BUTTON_POSITIVE);assertFalse(erase.isEnabled());
                input.setText("reset");assertFalse(erase.isEnabled());input.setText("RESE");assertFalse(erase.isEnabled());
                input.setText("RESET");assertTrue(erase.isEnabled());
                d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertFalse(d.isShowing());verify(activity,never()).resetWallet();
            }
            confirm.invoke(settings);AlertDialog expired=(AlertDialog)ShadowDialog.getLatestDialog();
            entry(expired.getWindow().getDecorView()).setText("RESET");active.set(false);
            expired.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            verify(activity,never()).resetWallet();active.set(true);
            confirm.invoke(settings);AlertDialog approved=(AlertDialog)ShadowDialog.getLatestDialog();
            entry(approved.getWindow().getDecorView()).setText("RESET");approved.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();verify(activity).resetWallet();
        }finally{Language.select(RuntimeEnvironment.getApplication(),"en");}
    }
    @Test public void durableDeleteRemovesOnlyThisVaultAndItsPublicDescriptor() {
        android.content.Context app=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=app.getSharedPreferences("wallet_vault_v1",0);
        SeedVault.PrefsBlobStore store=new SeedVault.PrefsBlobStore(app);
        store.write("0xPUBLIC_FIXTURE");
        prefs.edit().putString(PublicAuditKeys.RECORD,"public fixture descriptor").putString("unrelated","keep").commit();
        app.getSharedPreferences("node_fixture",0).edit().putString("seed_marker","public fixture only").commit();
        store.delete();assertFalse(store.contains());assertFalse(prefs.contains(PublicAuditKeys.RECORD));
        assertEquals("keep",prefs.getString("unrelated",null));
        assertEquals("public fixture only",app.getSharedPreferences("node_fixture",0).getString("seed_marker",null));
    }

}
