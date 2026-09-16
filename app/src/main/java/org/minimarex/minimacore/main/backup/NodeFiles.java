package org.minimarex.minimacore.main.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The node's base folder, and moving bytes between it and Android storage.
 *
 * THE POINT OF THIS CLASS: MinimaReceiver's FILE bridge enforced two rules on every companion
 * app - a path must resolve inside the base folder, and a write must never land in the live
 * "databases" folder. Running in-process bypasses that bridge completely, which means bypassing
 * its guardrails too. They are re-implemented here, deliberately matching MinimaReceiver's
 * resolveInBase/checkWriteAllowed, so the node's own UI is held to the rules it holds every
 * other app to rather than being trusted because it is "inside".
 *
 * The base folder is getFilesDir(): MinimaService starts the node with
 * "-data <getFilesDir()>", so GeneralParams.DATA_FOLDER and getFilesDir() are the same place,
 * and that is where backup / archive export / txnexport write.
 */
public final class NodeFiles {

    /** Writes must never touch the node's live databases. Mirrors MinimaReceiver. */
    private static final String[] WRITE_PROTECTED_DIRS = {"databases"};

    private NodeFiles() {}

    public static File base(Context context) {
        return context.getFilesDir();
    }

    /**
     * Resolve a relative path inside the base folder, or throw.
     *
     * This is what stops a crafted name ("../../shared_prefs/x") from reaching outside the
     * folder the node owns - canonicalise first, then require the result to still be under base.
     */
    public static File resolveInBase(File base, String rel) throws IOException {
        String path = rel == null ? "/" : rel;
        File file = new File(base, path).getCanonicalFile();
        String basePath = base.getCanonicalPath();
        if (!file.getPath().equals(basePath) && !file.getPath().startsWith(basePath + File.separator)) {
            throw new IOException("Path outside base folder");
        }
        return file;
    }

    /** Throws if the target sits inside a protected directory. Mirrors MinimaReceiver. */
    public static void checkWriteAllowed(File base, File target) throws IOException {
        String basePath = base.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        String rel = targetPath.substring(Math.min(basePath.length(), targetPath.length()));
        while (rel.startsWith(File.separator)) {
            rel = rel.substring(1);
        }
        String first = rel.contains(File.separator) ? rel.substring(0, rel.indexOf(File.separator)) : rel;
        for (String protectedDir : WRITE_PROTECTED_DIRS) {
            if (first.equals(protectedDir)) {
                throw new IOException("'" + protectedDir
                        + "' is protected - the node's live data cannot be modified");
            }
        }
    }

    /**
     * A filename safe to create in the base folder.
     *
     * Strips every path separator so a SAF display name cannot carry a directory with it, then
     * keeps only characters the node's command tokeniser survives - an imported file is only
     * useful if it can be named in `restore file:...`, and ResyncJob.validFilename is the rule
     * that decides whether it can be.
     */
    public static String safeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 128) name = name.substring(name.length() - 128);
        return name;
    }

    /** The name Android reports for a picked document, already made safe. Never trusted raw. */
    public static String displayName(ContentResolver resolver, Uri uri) {
        String name = "";
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {
            // A provider that will not answer is not a reason to fail the import.
        }
        String safe = safeName(name);
        return safe.isEmpty() ? "import_" + System.currentTimeMillis() : safe;
    }

    /** Copies and closes both sides. Returns the byte count so the caller can verify it. */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) throw new IOException("Could not open the file");
        long total = 0;
        try (InputStream source = in; OutputStream sink = out) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = source.read(buffer)) > 0) {
                sink.write(buffer, 0, read);
                total += read;
            }
            sink.flush();
        }
        return total;
    }

    /**
     * Node base folder -> a document the user picked. The .bak carries the wallet, so the
     * caller is responsible for having warned them before this runs.
     */
    public static long exportTo(Context context, File source, Uri destination) throws IOException {
        if (!source.isFile()) throw new IOException("The backup file is no longer there");
        long copied = copy(new FileInputStream(source),
                context.getContentResolver().openOutputStream(destination));
        if (copied != source.length()) {
            throw new IOException("Expected " + source.length() + " bytes but wrote " + copied);
        }
        return copied;
    }

    /**
     * A document the user picked -> the node base folder, landing in the ROOT.
     *
     * The root matters: MiniFile.createBaseFile resolves a bare filename against the data
     * folder, so a file imported here can immediately be named in `restore file:<name>` or
     * `megammrsync ... file:<name>` without the user typing a path.
     */
    public static File importFrom(Context context, Uri source, String name) throws IOException {
        File base = base(context);
        File destination = resolveInBase(base, name);
        checkWriteAllowed(base, destination);
        if (destination.isDirectory()) throw new IOException("A folder of that name already exists");
        copy(context.getContentResolver().openInputStream(source), new FileOutputStream(destination));
        return destination;
    }

    /**
     * Delete one file from the base folder root.
     *
     * Deliberately narrow. It refuses a directory, because every root-level entry the node
     * creates is one (databases, mds, ssl, backup, restore, archiverestore) and none of them is
     * ever something a user means to remove from a backup list. checkWriteAllowed still runs, so
     * the live databases folder is refused twice over rather than relying on the isFile() check.
     *
     * Irreversible: a .bak is a whole wallet, and this may be its only copy. The CALLER is
     * responsible for having confirmed that with the user first.
     */
    public static void delete(Context context, String name) throws IOException {
        delete(base(context), name);
    }

    /** Base-folder-explicit form, so the guardrails can be tested without an Android Context. */
    public static void delete(File base, String name) throws IOException {
        File target = resolveInBase(base, name);
        checkWriteAllowed(base, target);
        if (target.isDirectory()) {
            throw new IOException("That is a folder, not a backup file");
        }
        if (!target.isFile()) {
            throw new IOException("That file is no longer there");
        }
        if (!target.delete()) {
            throw new IOException("The file could not be deleted");
        }
    }

    /** A name that does not collide with an existing file, so an import never silently overwrites. */
    public static String uniqueName(File base, String name) {
        File candidate = new File(base, name);
        if (!candidate.exists()) return name;
        String stem = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            String attempt = stem + "-" + i + extension;
            if (!new File(base, attempt).exists()) return attempt;
        }
        return stem + "-" + System.currentTimeMillis() + extension;
    }

    /**
     * Files in the base folder ROOT that a restore command could actually name.
     *
     * Filtered by ResyncJob.validFilename rather than by extension: the list is only useful if
     * every row can be put into `restore file:<name>` verbatim, and a name the tokeniser would
     * mangle is not a row the user should be offered. Newest first - the backup someone just
     * made or imported is the one they are looking for.
     */
    public static java.util.List<File> restorable(Context context) {
        File[] files = base(context).listFiles();
        java.util.List<File> out = new java.util.ArrayList<>();
        if (files == null) return out;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (!org.minimarex.minimacore.main.ResyncJob.validFilename(file.getName())) continue;
            out.add(file);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.UK, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.UK, "%.1f MB", bytes / (1024f * 1024f));
        return String.format(java.util.Locale.UK, "%.2f GB", bytes / (1024f * 1024f * 1024f));
    }
}
