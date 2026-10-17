package com.eurobuddha.wallet;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.IdentityHashMap;
import java.util.Map;

/** Activity-owned dialogs, cleared at the same boundary as the wallet connection/session. */
final class WalletDialogs {
    private final Map<Dialog,Boolean> dialogs=new IdentityHashMap<>();
    void track(Dialog dialog,boolean sensitive) {
        dialogs.put(dialog,sensitive);
        View decor=dialog.getWindow().getDecorView();
        if(sensitive)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // Preserve existing on-show/on-dismiss handlers (NFT batch cancellation uses them).
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View view){dialogs.put(dialog,sensitive);}
            @Override public void onViewDetachedFromWindow(View view){
                dialogs.remove(dialog);
                if(sensitive)clearText(view);
            }
        });
    }
    void dismissAll(){dismiss(false);}
    void dismissSensitive(){dismiss(true);}
    private void dismiss(boolean sensitiveOnly){
        for(Map.Entry<Dialog,Boolean> entry:new IdentityHashMap<>(dialogs).entrySet()){
            if(sensitiveOnly&&!entry.getValue())continue;
            Dialog dialog=entry.getKey();dialogs.remove(dialog);
            if(entry.getValue())clearText(dialog.getWindow().getDecorView());
            dialog.dismiss();
        }
    }
    private static void clearText(View view){
        if(view instanceof TextView)((TextView)view).setText("");
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearText(group.getChildAt(i));}
    }
}
