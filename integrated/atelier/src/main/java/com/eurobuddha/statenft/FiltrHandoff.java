package com.eurobuddha.statenft;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Private, bounded image handoff. Android results contain only the request ID and destination. */
final class FiltrHandoff {
    static final int REQUEST = 7010;
    static final String ID = "atelier.filtr.request", MODE = "atelier.filtr.mode";
    static final String MINT = "mint", PHOTO = "photo";
    private FiltrHandoff() {}

    static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    static int limit(String mode) throws IOException {
        if (MINT.equals(mode)) return ImageTools.ARTIMAGE_BUDGET;
        if (PHOTO.equals(mode)) return 2 * 1024 * 1024; // lossless 512 x 512 RGBA PNG, in base64
        throw new IOException("Invalid FILTR destination");
    }
    private static File file(Context ctx, String id) throws IOException {
        if (!validId(id)) throw new IOException("Invalid FILTR request");
        return new File(new File(ctx.getCacheDir(), "atelier-filtr"), id);
    }
    static void validate(String mode, String b64) throws IOException {
        if (b64 == null || b64.isEmpty() || b64.length() > limit(mode)) throw new IOException("Invalid FILTR image size");
        final byte[] raw;
        try { raw = Base64.decode(b64, Base64.DEFAULT); }
        catch (IllegalArgumentException bad) { throw new IOException("Invalid FILTR image", bad); }
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        int edge = PHOTO.equals(mode) ? 512 : 4096;
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > edge || options.outHeight > edge)
            throw new IOException("Invalid FILTR image dimensions");
        if (PHOTO.equals(mode) && (options.outWidth != 512 || options.outHeight != 512))
            throw new IOException("FILTR photo must be 512 square");
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 512);
        Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (decoded == null) throw new IOException("Could not decode FILTR image");
        decoded.recycle();
    }
    static void write(Context ctx, String id, String mode, String b64) throws IOException {
        validate(mode, b64);
        File target = file(ctx, id), parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create FILTR cache");
        // NodeFiles.replaceFrom pattern: private sibling, sync, atomic move, cleanup on failure.
        File pending = File.createTempFile(".filtr-", ".part", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(b64.getBytes(StandardCharsets.US_ASCII)); out.getFD().sync();
            }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { pending.delete(); }
    }
    static String take(Context ctx, String id, String mode) throws IOException {
        File source = file(ctx, id);
        try {
            int cap = limit(mode);
            try (InputStream in = new FileInputStream(source); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n > cap - out.size()) throw new IOException("FILTR image exceeds limit");
                    out.write(buffer, 0, n);
                }
                String b64 = new String(out.toByteArray(), StandardCharsets.US_ASCII);
                validate(mode, b64); return b64;
            }
        } finally { source.delete(); }
    }
    static void discard(Context ctx, String id) {
        if (!validId(id)) return;
        try { file(ctx, id).delete(); } catch (IOException ignored) {}
    }
}
