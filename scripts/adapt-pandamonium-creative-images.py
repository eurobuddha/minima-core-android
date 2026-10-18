"""Reuse core wallet request/transport guards while retaining creative offline images."""
from pathlib import Path


def adapt(root):
    for app, package in [('atelier', 'statenft'), ('salon', 'salon')]:
        p = root / app / f'src/main/java/com/eurobuddha/{package}/ImageLoader.java'
        s = p.read_text()
        def change(old, new):
            nonlocal s
            assert s.count(old) == 1, (app, 'image loader source drift', old)
            s = s.replace(old, new)
        change('import java.io.InputStream;', '''import java.io.InputStream;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import com.eurobuddha.minimacore.utils.BackgroundWork;
import com.eurobuddha.minimacore.utils.ExpiringCache;
import com.eurobuddha.minimacore.utils.SharedRequests;
import com.eurobuddha.minimacore.main.views.balance.tokens.NetFetch;''')
        change('''    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(4);''', '''    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SharedRequests<String, Bitmap> REQUESTS =
            new SharedRequests<>(BackgroundWork.pool(4, 64), task -> MAIN.post(task));
    private static final ExpiringCache<String, Boolean> FAILED =
            new ExpiringCache<>(256, SystemClock::elapsedRealtime);''')
        a = s.index('    private static void loadOver(')
        b = s.index('    static Bitmap decode(', a)
        s = s[:a] + '''    // Core wallet's weak subscription: the view owns its current request.
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

''' + s[b:]
        a = s.index('    static Bitmap decode(')
        b = s.index('    private static boolean looksLikeSvg', a)
        relay = '            if (RelayResolver.isMediaRef(url)) return decodeBytes(relayBytes(url), url, reqPx);\n' if app == 'salon' else ''
        s = s[:a] + '''    // Direct callers (exports) own this fresh bitmap; never hand them CACHE entries.
    static Bitmap decode(android.content.Context ctx, String url, int reqPx) {
        if (url == null || url.isEmpty() || reqPx <= 0 || reqPx > FULL_PX) return null;
        try {
''' + relay + '''            if (url.startsWith("data:")) return decodeBytes(dataUriBytes(url), url, reqPx);
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

''' + s[b:]
        if app == 'atelier':
            change('''            com.caverock.androidsvg.SVG svg =
                    com.caverock.androidsvg.SVG.getFromString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));''', '''            String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            String lower = xml.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("<!doctype") || lower.contains("<!entity")) return null;
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromString(xml);''')
        else:
            change('String lower = xml.toLowerCase();', 'String lower = xml.toLowerCase(java.util.Locale.ROOT);')
        change('            int w = reqPx, h = reqPx;', '            // Explicit dimensions without viewBox otherwise leave tiny artwork on a large canvas.\n            android.graphics.RectF viewBox = svg.getDocumentViewBox();\n            if (viewBox == null && dw > 0 && dh > 0) svg.setDocumentViewBox(0, 0, dw, dh);\n            if ((dw <= 0 || dh <= 0) && viewBox != null) { dw = viewBox.width(); dh = viewBox.height(); }\n            int w = reqPx, h = reqPx;')
        a = s.index('    private static byte[] fetchCached(')
        b = s.index('    private static void trimDisk', a)
        s = s[:a] + '''    private static Bitmap fetchCached(android.content.Context ctx, String url, int reqPx) {
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

''' + s[b:]
        change('dir == null ? null : dir.listFiles();', 'dir == null ? null : dir.listFiles(f -> f.isFile() && f.getName().matches("[0-9a-f]{40}"));')
        a = s.index('    private static byte[] fetch(String url)')
        b = s.index('    static boolean isBlockedHost', a)
        s = s[:a] + '''    private static byte[] fetch(String url) throws Exception {
        String f = url.startsWith("ipfs://") ? "https://ipfs.io/ipfs/" + url.substring("ipfs://".length()) : url;
        return NetFetch.get(f, MAX_BYTES, 8000, 15000, false);
    }

''' + s[b:]
        a = s.index('    static boolean isBlockedHost(')
        b = s.index('\n    }', a) + len('\n    }')
        s = s[:a] + '''    static boolean isBlockedHost(String host) { return NetFetch.isBlockedHost(host); }''' + s[b:]
        a = s.index('    private static byte[] dataUriBytes(')
        s = s[:a] + '''    private static byte[] dataUriBytes(String dataUri) {
        int comma = dataUri.indexOf(',');
        if (comma < 0 || comma > 1024) return null;
        if (!dataUri.substring(0, comma).contains("base64")) return null;
        if (dataUri.length() - comma - 1 > ((MAX_BYTES + 2L) / 3) * 4) return null;
        byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
        return bytes.length <= MAX_BYTES ? bytes : null;
    }
}
'''
        if app == 'salon':
            change('''        for (int i = 0; i <= maxHops; i++) {
            try {''', '''        for (int i = 0; i <= maxHops; i++) {
            HttpURLConnection c = null;
            try {''')
            change('HttpURLConnection c = (HttpURLConnection) u.openConnection();', 'c = (HttpURLConnection) u.openConnection();')
            change('''            } catch (Exception e) {
                return null;
            }
        }
        return null;   // too many redirects''', '''            } catch (Exception e) {
                if (c != null) c.disconnect();
                return null;
            }
        }
        return null;   // too many redirects''')
        p.write_text(s)
