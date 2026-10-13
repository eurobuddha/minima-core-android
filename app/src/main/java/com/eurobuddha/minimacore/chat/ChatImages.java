package com.eurobuddha.minimacore.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.eurobuddha.minimacore.utils.MinimaCMD;

import org.minima.utils.json.JSONObject;

import java.io.File;
import java.util.function.Consumer;

/**
 * Shared chat photo loader: memory LRU + disk cache under cache/chatimg, fetched
 * P2P once via `keets action:fetch url: file:` (file hand-off — never megabyte
 * hex through the command parser). Used by the bubbles and the full-screen viewer;
 * the Share action hands the cached file out through the app FileProvider.
 */
public final class ChatImages {

    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ChatImages() {}

    /** The disk-cache file for a keets:// ref (exists only after a successful load). */
    public static File file(Context zContext, String zRef) {
        File dir = new File(zContext.getCacheDir(), "chatimg");
        dir.mkdirs();
        return new File(dir, Integer.toHexString(zRef.hashCode()) + ".jpg");
    }

    /** Load (cache-first, else P2P fetch). Callback on the MAIN thread; null = failed. */
    public static void load(Context zContext, String zRef, Consumer<Bitmap> zCb) {
        Bitmap hit = MEM.get(zRef);
        if (hit != null) { zCb.accept(hit); return; }
        Context app = zContext.getApplicationContext();
        new Thread(() -> {
            Bitmap bm = null;
            try {
                File f = file(app, zRef);
                if (!f.exists()) {
                    JSONObject r = MinimaCMD.execute("keets action:fetch url:" + zRef + " file:" + f.getAbsolutePath());
                    if (!(Boolean) r.getOrDefault("status", false)) throw new Exception("" + r.get("error"));
                }
                bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) MEM.put(zRef, bm);
            } catch (Exception exc) { /* cb(null) signals the error state */ }
            final Bitmap fin = bm;
            MAIN.post(() -> zCb.accept(fin));
        }, "chat-image-load").start();
    }
}
