package com.eurobuddha.vestr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal async loader for token icons: handles data: URIs, http(s), and ipfs:// URLs, with a
 * byte-bounded in-memory cache. All decoding (including data: URIs) runs on a worker thread.
 */
public final class ImageLoader {

    // Bounded by total bitmap bytes so a few large icons can't grow without limit.
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private ImageLoader() {}

    public static void load(final MainActivity act, final String url, final ImageView iv, int fallbackRes) {
        iv.setTag(url);
        if (url == null || url.isEmpty()) { iv.setImageResource(fallbackRes); return; }

        Bitmap cached = CACHE.get(url);
        if (cached != null) { iv.setImageBitmap(cached); return; }

        iv.setImageResource(fallbackRes);
        new Thread(() -> {
            final Bitmap b = decode(url);
            if (b == null) return;
            CACHE.put(url, b);
            act.runOnUiThread(() -> {
                // Only apply if this ImageView is still showing the same url.
                if (url.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        }).start();
    }

    private static Bitmap decode(String url) {
        try {
            if (url.startsWith("data:")) return decodeDataUri(url);
            String fetch = url.startsWith("ipfs://")
                    ? "https://ipfs.io/ipfs/" + url.substring("ipfs://".length()) : url;
            HttpURLConnection con = (HttpURLConnection) new URL(fetch).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            InputStream in = con.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            con.disconnect();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeDataUri(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            if (dataUri.substring(0, comma).contains("base64")) {
                byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
