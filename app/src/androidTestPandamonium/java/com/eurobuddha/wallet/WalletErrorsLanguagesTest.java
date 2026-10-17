package com.eurobuddha.wallet;

import android.content.Context;
import android.os.Build;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Error display only: no seed, node, transaction, import or counter writes. */
@RunWith(AndroidJUnit4.class)
public class WalletErrorsLanguagesTest {
    @Test public void errorsSwitchInPlaceAndKeepDraftsAmountsAndLiteralDiagnostics() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertFalse(WalletSession.get(context).vault().exists());assertFalse(WalletSession.get(context).work().busy());
            AppLock.disable(context);Language.select(context,"en");
        });
        final String literal="Copy 中文 Русский Українська {0} $1 <img>";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> original=new AtomicReference<>();AtomicReference<AlertDialog> dialog=new AtomicReference<>();
            AtomicReference<EditText> draft=new AtomicReference<>();
            scenario.onActivity(a->{
                original.set(a);EditText input=new EditText(a);input.setText(literal);input.setSelection(4);draft.set(input);
                dialog.set(new WalletDialog(a).setTitle(Language.msg(l10n_wallet_wallet_diagnostics))
                    .setMessage(Language.join(WalletMessages.error(new SeedVault.SigningNotAllowedException("Wallet is locked")),"\n",
                        WalletMessages.message("Insufficient funds: have 12.34000 need 20.0000"),"\n",
                        WalletMessages.message("Coin parse failed: empty coinexport reply"),"\n",
                        WalletMessages.message("txnpost failed: "+literal)))
                    .setView(input).setNegativeButton(Language.msg(l10n_language_close),null).show());
            });
            for(String code:new String[]{"zh-Hans","ru","uk","en"})scenario.onActivity(a->{
                Language.select(a,code);assertSame(original.get(),a);assertTrue(dialog.get().isShowing());
                assertEquals(literal,draft.get().getText().toString());assertEquals(4,draft.get().getSelectionStart());
                String text=((TextView)dialog.get().findViewById(android.R.id.message)).getText().toString();
                assertTrue(text.contains(Language.text(a,l10n_wallet_error_locked)));
                assertTrue(text.contains(Language.text(a,l10n_wallet_error_funds,"12.34000","20.0000")));
                assertTrue(text.contains(Language.text(a,l10n_wallet_error_coin_parse,Language.text(a,l10n_wallet_error_coin_export_empty))));
                assertTrue(text.contains(Language.text(a,l10n_wallet_error_command_failed,"txnpost",literal)));
            });
            scenario.onActivity(a->{dialog.get().dismiss();assertFalse(WalletSession.get(a).vault().exists());assertNull(a.node());});
        }
        assertNull(MinimaService.minima);
    }
}
