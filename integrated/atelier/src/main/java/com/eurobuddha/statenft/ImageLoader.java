package com.eurobuddha.statenft;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import com.eurobuddha.minimacore.utils.BackgroundWork;
import com.eurobuddha.minimacore.utils.ExpiringCache;
import com.eurobuddha.minimacore.utils.SharedRequests;
import com.eurobuddha.minimacore.main.views.balance.tokens.NetFetch;
import java.net.HttpURLConnection;
import java.net.URL;

public final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final int THUMB_PX = 420;
    private static final int FULL_PX = 1800;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SharedRequests<String, Bitmap> REQUESTS =
            new SharedRequests<>(BackgroundWork.pool(4, 64), task -> MAIN.post(task));
    private static final ExpiringCache<String, Boolean> FAILED =
            new ExpiringCache<>(256, SystemClock::elapsedRealtime);

    private ImageLoader() {}

    public static void loadOver(final android.app.Activity act, final String url, final ImageView iv) {
        loadOver(act, url, iv, THUMB_PX);
    }

    public static void loadFull(final android.app.Activity act, final String url, final ImageView iv) {
        loadOver(act, url, iv, FULL_PX);
    }

    // Core wallet's weak subscription: the view owns its current request.
    private static final class Target implements Consumer<Bitmap> {
        final WeakReference<android.app.Activity> activity;
        final WeakReference<ImageView> view;
        final String key;
        Target(android.app.Activity act, ImageView iv, String key) {
            activity = new WeakReference<>(act); view = new WeakReference<>(iv); this.key = key;
        }
        @Override public void accept(Bitmap bitmap) {
            android.app.Activity act = activity.get(); ImageView iv = view.get();
            if (bitmap == null || act == null || act.isDestroyed() || act.isFinishing()
                    || iv == null || iv.getTag() != this) return;
            iv.setImageBitmap(bitmap);
        }
    }

    // Fixed-size digest keys retain neither giant inline images nor Java hash collisions.
    private static String cacheKey(String url, int reqPx) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return reqPx + "|" + Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void loadOver(final android.app.Activity act, final String url, final ImageView iv, final int reqPx) {
        if (url == null || url.isEmpty()) { iv.setTag(url); return; }
        if (act.isDestroyed() || act.isFinishing() || url.length() > ((MAX_BYTES + 2L) / 3) * 4 + 1024) {
            iv.setTag(null); return;
        }
        String key = cacheKey(url, reqPx);
        Object previous = iv.getTag();
        Target target = previous instanceof Target && key.equals(((Target) previous).key)
                ? (Target) previous : new Target(act, iv, key);
        iv.setTag(target);
        Bitmap cached = CACHE.get(key);
        if (cached != null) { target.accept(cached); return; }
        if (FAILED.get(key) != null) return;
        final android.content.Context ctx = act.getApplicationContext();
        REQUESTS.request(key, () -> {
            Bitmap bitmap = CACHE.get(key);
            if (bitmap == null) bitmap = decode(ctx, url, reqPx);
            if (bitmap != null) CACHE.put(key, bitmap);
            else FAILED.put(key, Boolean.TRUE, 30_000);
            return bitmap;
        }, target);
    }

    // Direct callers (exports) own this fresh bitmap; never hand them CACHE entries.
    static Bitmap decode(android.content.Context ctx, String url, int reqPx) {
        if (url == null || url.isEmpty() || reqPx <= 0 || reqPx > FULL_PX) return null;
        try {
            if (url.startsWith("data:")) return decodeBytes(dataUriBytes(url), url, reqPx);
            return fetchCached(ctx, url, reqPx);
        } catch (Throwable t) { return null; }
    }

    private static Bitmap decodeBytes(byte[] bytes, String url, int reqPx) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqPx);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
        }
        return looksLikeSvg(bytes, url) ? renderSvg(bytes, reqPx) : null;
    }

    private static boolean looksLikeSvg(byte[] bytes, String url) {
        if (url != null && url.toLowerCase().contains(".svg")) return true;
        int n = Math.min(bytes.length, 300);
        String head = new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg")) || head.contains("<svg");
    }

    private static Bitmap renderSvg(byte[] bytes, int reqPx) {
        try {
            String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            String lower = xml.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("<!doctype") || lower.contains("<!entity")) return null;
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromString(xml);
            float dw = svg.getDocumentWidth(), dh = svg.getDocumentHeight();
            // Explicit dimensions without viewBox otherwise leave tiny artwork on a large canvas.
            android.graphics.RectF viewBox = svg.getDocumentViewBox();
            if (viewBox == null && dw > 0 && dh > 0) svg.setDocumentViewBox(0, 0, dw, dh);
            if ((dw <= 0 || dh <= 0) && viewBox != null) { dw = viewBox.width(); dh = viewBox.height(); }
            int w = reqPx, h = reqPx;
            if (dw > 0 && dh > 0) {
                if (dw >= dh) { w = reqPx; h = Math.max(1, Math.round(reqPx * dh / dw)); }
                else { h = reqPx; w = Math.max(1, Math.round(reqPx * dw / dh)); }
            }
            svg.setDocumentWidth(w);
            svg.setDocumentHeight(h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int sampleSize(int w, int h, int reqPx) {
        int s = 1;
        int max = Math.max(w, h);
        while (max / s > reqPx) s <<= 1;
        return Math.max(1, s);
    }

    /* remote icons persist to disk so a wallet full of hosted-image NFTs
     * still shows every once-seen icon offline (the identicon-fallback
     * report: airplane mode turned the whole Singles wall into placeholders) */
    private static final int DISK_MAX = 300;

    private static java.io.File diskFile(android.content.Context ctx, String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "nfticons");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return new java.io.File(dir, sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fetchCached(android.content.Context ctx, String url, int reqPx) {
        java.io.File f = ctx == null ? null : diskFile(ctx, url);
        try {
            byte[] bytes = fetch(url);
            Bitmap bitmap = decodeBytes(bytes, url, reqPx);
            if (bitmap != null) {
                if (f != null) storeImage(f, bytes);
                return bitmap;
            }
        } catch (Exception ignored) { }
        // A failed download/decoder must not replace the last usable offline image.
        if (f != null && f.isFile() && f.length() <= MAX_BYTES) {
            try (InputStream in = new java.io.FileInputStream(f);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (n > MAX_BYTES - out.size()) return null;
                    out.write(buf, 0, n);
                }
                return decodeBytes(out.toByteArray(), url, reqPx);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void storeImage(java.io.File f, byte[] bytes) {
        java.io.File pending = null;
        try {
            // FiltrHandoff/NodeFiles: sync a private sibling, then replace atomically.
            pending = java.io.File.createTempFile(".image-", ".part", f.getParentFile());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(pending)) {
                out.write(bytes); out.getFD().sync();
            }
            java.nio.file.Files.move(pending.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            trimDisk(f.getParentFile());
        } catch (Exception ignored) { }
        finally { if (pending != null) pending.delete(); }
    }

    private static void trimDisk(java.io.File dir) {
        java.io.File[] all = dir == null ? null : dir.listFiles(f -> f.isFile() && f.getName().matches("[0-9a-f]{40}"));
        if (all == null || all.length <= DISK_MAX) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < all.length - DISK_MAX; i++) all[i].delete();
    }

    private static byte[] fetch(String url) throws Exception {
        String f = url.startsWith("ipfs://") ? "https://ipfs.io/ipfs/" + url.substring("ipfs://".length()) : url;
        return NetFetch.get(f, MAX_BYTES, 8000, 15000, false);
    }

    static boolean isBlockedHost(String host) { return NetFetch.isBlockedHost(host); }

    private static byte[] dataUriBytes(String dataUri) {
        int comma = dataUri.indexOf(',');
        if (comma < 0 || comma > 1024) return null;
        if (!dataUri.substring(0, comma).contains("base64")) return null;
        if (dataUri.length() - comma - 1 > ((MAX_BYTES + 2L) / 3) * 4) return null;
        byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
        return bytes.length <= MAX_BYTES ? bytes : null;
    }
}
