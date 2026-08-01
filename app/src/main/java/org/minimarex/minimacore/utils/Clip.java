package org.minimarex.minimacore.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

/** Copy-to-clipboard with a confirmation toast. */
public class Clip {

    private Clip(){}

    public static void copy(Context zContext, String zLabel, String zText, String zToast){
        if(zContext == null || zText == null){
            return;
        }

        ClipboardManager cm = (ClipboardManager) zContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if(cm == null){
            Toast.makeText(zContext, "Clipboard unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        cm.setPrimaryClip(ClipData.newPlainText(zLabel, zText));

        //Android 13+ shows its own clipboard confirmation - ours would just be duplicate noise
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && zToast != null){
            Toast.makeText(zContext, zToast, Toast.LENGTH_SHORT).show();
        }
    }
}
