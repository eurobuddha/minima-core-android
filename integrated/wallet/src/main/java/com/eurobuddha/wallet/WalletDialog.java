package com.eurobuddha.wallet;

import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.LanguageDialog;

/** Keep the shared live-language builder and add the wallet's existing session boundary. */
public final class WalletDialog extends LanguageDialog {
    private final MainActivity owner;
    private boolean sensitive;
    public WalletDialog(MainActivity activity){super(activity);owner=activity;}
    public WalletDialog sensitive(){sensitive=true;return this;}
    @Override public AlertDialog create(){
        AlertDialog dialog=super.create();
        owner.trackWalletDialog(dialog,sensitive);
        return dialog;
    }
}
