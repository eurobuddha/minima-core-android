package com.eurobuddha.filez;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Filez — a native file manager for the Minima Core node's base folder.
 *
 * Lets you EXPORT files the node terminal creates (backups, archive exports, .txn files)
 * to the Android file system via SAF, and IMPORT documents/files from the phone into the
 * node's base folder so terminal commands (restore, archive import, txnimport…) can use them.
 *
 * File access uses the embedded node's guarded file implementation.
 */
public class MainActivity extends AppCompatActivity {

    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private final java.util.Map<PopupMenu, java.util.Map<android.view.MenuItem, CharSequence>> languageMenus = new java.util.LinkedHashMap<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog value = super.create();
                languageDialogs.add(value);
                value.setOnDismissListener(d -> languageDialogs.remove(value));
                return value;
            }
        };
    }
    private void showLanguageMenu(PopupMenu menu) {
        java.util.Map<android.view.MenuItem, CharSequence> labels = new java.util.LinkedHashMap<>();
        for (int i=0;i<menu.getMenu().size();i++) {
            android.view.MenuItem item = menu.getMenu().getItem(i);
            labels.put(item,item.getTitle());
        }
        languageMenus.put(menu,labels);
        menu.setOnDismissListener(m -> languageMenus.remove(m));
        menu.show();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        for (PopupMenu menu : new java.util.ArrayList<>(languageMenus.keySet())) {
            java.util.Map<android.view.MenuItem, CharSequence> labels = languageMenus.get(menu);
            // Recreate only the popup window so its cached width follows the new labels.
            // Keep the menu items, action listeners and original translation descriptors.
            menu.setOnDismissListener(null);
            menu.dismiss();
            for (java.util.Map.Entry<android.view.MenuItem, CharSequence> entry : labels.entrySet())
                entry.getKey().setTitle(entry.getValue().toString());
            menu.setOnDismissListener(m -> languageMenus.remove(m));
            menu.show();
        }
    }

    public static final String NODE_PKG = "com.eurobuddha.pandamonium";
    public static final String PANDAAPPS_PKG = "com.eurobuddha.pandaapps";
    private static final String IMPORT_DIR = "imports";
    private static final CharSequence MSG_NO_NODE = Language.msg(l10n_terminal_ide_timeout);

    private NodeApi node;

    private LinearLayout header;
    private View pairingBanner;
    private TextView pairingTitle, pairingText;
    private TextView title, status, backBtn;
    private RecyclerView recycler;
    private FileAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    private String currentPath = "/";
    private String pendingExportPath = null;   // node path awaiting a SAF destination
    private boolean loading = false;
    // Absolute path of the node's base folder (from `status` -> response.data). Terminal
    // commands treat any file: arg containing "/" as a literal filesystem path, so only
    // <nodeRoot> + <relative path> is paste-able into the terminal for subfolder files.
    private String nodeRoot = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.pm_filez_activity_main);
        Language.apply(findViewById(R.id.pm_filez_main));
        header = findViewById(R.id.pm_filez_header);
        title = findViewById(R.id.pm_filez_title);
        status = findViewById(R.id.pm_filez_status);
        backBtn = findViewById(R.id.pm_filez_backBtn);
        recycler = findViewById(R.id.pm_filez_recycler);
        pairingBanner = findViewById(R.id.pm_filez_pairingBanner);
        pairingTitle = findViewById(R.id.pm_filez_pairingTitle);
        pairingText = findViewById(R.id.pm_filez_pairingText);

        applyInsets();

        adapter = new FileAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        findViewById(R.id.pm_filez_refreshBtn).setOnClickListener(v -> loadDirectory(currentPath));
        findViewById(R.id.pm_filez_menuBtn).setOnClickListener(this::showMainMenu);
        ((Button) findViewById(R.id.pm_filez_openNodeBtn)).setOnClickListener(v -> openMinimaCore());
        backBtn.setOnClickListener(v -> navigateUp());

        // The SAF destination is chosen FIRST, then the node hands us a content:// of the source.
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> { if (uri != null && pendingExportPath != null) doExport(pendingExportPath, uri); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) doImport(uri); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!currentPath.equals("/")) navigateUp();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        node = new NodeApi(this, this::onPaired);

        // A crash/rotation mid-import can strand a staged copy - never let the cache grow
        io.execute(() -> {
            File dir = new File(getCacheDir(), IMPORT_DIR);
            File[] leftovers = dir.listFiles();
            if (leftovers != null) for (File f : leftovers) f.delete();
        });

        loadDirectory("/");
    }

    private void applyInsets() {
        final View root = findViewById(R.id.pm_filez_main);
        final int headerTop = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top, header.getPaddingRight(), header.getPaddingBottom());
            recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(), recycler.getPaddingRight(), bars.bottom + dp(8));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- pairing ----

    private void onPaired(boolean enabled) {
        if (!enabled) {
            // Reset the wording - showAdminBanner/showOldNodeBanner may have rewritten it earlier
            Language.setText(pairingTitle, Language.msg(l10n_filez_not_enabled));
            Language.setText(pairingText, Language.msg(l10n_explorer_node_retry));
            resetBannerButton();
        }
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) loadDirectory(currentPath);
    }

    /** FILE verbs need the Admin toggle too — the node tells us in the error text. */
    private void showAdminBanner() {
        Language.setText(pairingTitle, Language.msg(l10n_filez_admin));
        Language.setText(pairingText, Language.msg(l10n_explorer_node_retry));
        resetBannerButton();
        pairingBanner.setVisibility(View.VISIBLE);
    }

    /** The node answers normal commands but not FILE — it's alive but pre-1.3.1 (no file bridge). */
    private void showOldNodeBanner() {
        Language.setText(pairingTitle, Language.msg(l10n_filez_update));
        Language.setText(pairingText, Language.msg(l10n_filez_old_node));
        Button btn = findViewById(R.id.pm_filez_openNodeBtn);
        Language.setText(btn, Language.msg(l10n_filez_panda_apps));
        btn.setOnClickListener(v -> openPandaApps());
        pairingBanner.setVisibility(View.VISIBLE);
    }

    private void resetBannerButton() {
        Button btn = findViewById(R.id.pm_filez_openNodeBtn);
        Language.setText(btn, Language.msg(l10n_filez_open_core));
        btn.setOnClickListener(v -> openMinimaCore());
    }

    /**
     * A FILE call timed out. An old node (no FILE bridge) NEVER answers FILE but answers
     * normal commands fine — probe with a cheap `block` to tell the two cases apart.
     */
    private void onFileTimeout() {
        Language.setText(status, Language.msg(l10n_filez_checking_version));
        node.cmd("block", 8000, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                showOldNodeBanner();
                Language.setText(status, Language.msg(l10n_filez_update_files));
            }
            @Override public void onError(String m) {
                // ERR_NOT_ENABLED -> the pairing listener already showed the enable banner
                if (!NodeApi.ERR_NOT_ENABLED.equals(m)) Language.setText(status, MSG_NO_NODE);
            }
        });
    }

    /** Shared error routing for every FILE call. */
    private void handleFileCallError(String m) {
        if (NodeApi.ERR_NOT_ENABLED.equals(m)) return;      // banner shown via pairing listener
        if (NodeApi.ERR_TIMEOUT.equals(m)) { onFileTimeout(); return; }
        Language.setText(status, m);
        toast(m);
    }

    // ---- directory listing ----

    private void fetchNodeRoot() {
        if (!nodeRoot.isEmpty()) return;
        node.cmd("status", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) nodeRoot = r.optString("data", "");
            }
            @Override public void onError(String m) {}   // cosmetic only - retried on next load
        });
    }

    /** The full filesystem path a terminal `file:` argument needs for this entry. */
    private String terminalPathFor(String relPath) {
        if (nodeRoot.isEmpty()) return relPath;
        return nodeRoot + relPath;
    }

    private void copyToClipboard(String label, String value) {
        // Deferred: a write fired while a popup/dialog is dismissing can be dropped during the
        // window-focus transition on some OEMs. Then READ BACK and report truthfully — Samsung /
        // Knox can swallow setPrimaryClip silently (toast used to lie about it).
        ui.postDelayed(() -> {
            boolean ok = false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
                android.content.ClipData clip = cm.getPrimaryClip();
                ok = clip != null && clip.getItemCount() > 0
                        && value.contentEquals(String.valueOf(clip.getItemAt(0).coerceToText(this)));
            } catch (Exception ignored) {}
            toast(ok ? Language.msg(l10n_filez_copied) : Language.msg(l10n_filez_copy_blocked));
        }, 200);
    }


    private void loadDirectory(String path) {
        if (loading) return;
        loading = true;
        fetchNodeRoot();
        Language.setText(status, Language.msg(l10n_filez_loading));
        node.file("list", path, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                loading = false;
                if (!j.optBoolean("status", false)) { onFileError(j); return; }
                pairingBanner.setVisibility(View.GONE);
                // Root for displayed Locations. NOT status->data: Minima appends "1.1" to
                // -data (ParamConfigurer), so that points INSIDE the version folder, one level
                // below the file area (-basefolder / getFilesDir) these listings come from.
                // The bridge reports its own canonical base; status->data is only the fallback
                // for a 1.3.1 node and is one folder off there.
                String base = j.optString("base", "");
                if (!base.isEmpty()) nodeRoot = base;
                currentPath = j.optString("path", path);
                List<FileEntry> entries = new ArrayList<>();
                JSONArray arr = j.optJSONArray("list");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) entries.add(new FileEntry(o));
                }
                entries.sort(Comparator.comparing((FileEntry e) -> !e.isdir)
                        .thenComparing(e -> e.name.toLowerCase()));
                adapter.setData(entries);
                updateHeader(entries.size());
            }
            @Override public void onError(String m) {
                loading = false;
                handleFileCallError(m);
            }
        });
    }

    private void updateHeader(int count) {
        boolean root = currentPath.equals("/");
        Language.setText(title, root ? Language.msg(l10n_filez_root) : currentPath);
        backBtn.setVisibility(root ? View.GONE : View.VISIBLE);
        Language.setText(status, Language.join(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_filez_items, count), root ? Language.msg(l10n_filez_root_hint) : ""));
    }

    private void navigateUp() {
        String parent = Util.parentOf(currentPath);
        if (parent != null) loadDirectory(parent);
    }

    /** A failed FILE response — admin gating gets the banner, the rest a toast + status line. */
    private void onFileError(JSONObject j) {
        String err = j.optString("error", j.optString("response", null));
        CharSequence display = err == null ? Language.msg(l10n_filez_file_failed) : err;
        if (err != null && err.contains("ADMIN")) { showAdminBanner(); Language.setText(status, Language.msg(l10n_filez_waiting_admin)); return; }
        Language.setText(status, display);
        Toast.makeText(this, display, Toast.LENGTH_LONG).show();
    }

    // ---- menus ----

    private void showMainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, Language.msg(l10n_filez_import_here));
        m.getMenu().add(0, 2, 1, Language.msg(l10n_filez_new_folder));
        m.getMenu().add(0, 3, 2, Language.msg(l10n_filez_create_backup));
        m.getMenu().add(0, 4, 3, Language.msg(l10n_filez_refresh));
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: importLauncher.launch(new String[]{"*/*"}); return true;
                case 2: promptNewFolder(); return true;
                case 3: createBackup(); return true;
                case 4: loadDirectory(currentPath); return true;
                default: return false;
            }
        });
        showLanguageMenu(m);
    }

    private void showEntryMenu(View anchor, FileEntry e) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (!e.isdir) m.getMenu().add(0, 1, 0, Language.msg(l10n_filez_export_phone));
        m.getMenu().add(0, 4, 1, Language.msg(l10n_filez_copy_path));
        m.getMenu().add(0, 2, 2, Language.msg(l10n_filez_rename));
        m.getMenu().add(0, 3, 3, Language.msg(l10n_filez_delete));
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startExport(e); return true;
                case 4: copyToClipboard(Language.text(this, l10n_filez_path_label), terminalPathFor(Util.joinPath(currentPath, e.name))); return true;
                case 2: promptRename(e); return true;
                case 3: confirmDelete(e); return true;
                default: return false;
            }
        });
        showLanguageMenu(m);
    }

    /** Mirrors the web Filez File page exactly: Name / Size / File Type / Location sections
     *  and a "Copy path" button that flips to "Copied to clipboard" (2.5s), where
     *  Location = fullPath + location. */
    private void showDetail(FileEntry e) {
        final String location = terminalPathFor(Util.joinPath(currentPath, e.name));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));

        detailSection(box, Language.msg(l10n_filez_size), Util.formatBytes(e.size));
        detailSection(box, Language.msg(l10n_filez_type), e.name.lastIndexOf('.') < 0 || e.name.endsWith(".") ? Language.msg(l10n_filez_generic_type) : Util.getExtension(e.name));
        detailSection(box, Language.msg(l10n_filez_location), location);

        // Live self-check: ask the node to resolve the EXACT Location string the way a
        // terminal file: argument resolves, and print the verdict right in the dialog.
        TextView check = new TextView(this);
        check.setTextSize(12f);
        check.setPadding(0, dp(6), 0, dp(6));
        check.setTextColor(FilezDesign.DIM);
        Language.setText(check, Language.msg(l10n_filez_checking_path));
        box.addView(check);
        node.file("stat", location, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { Language.setText(check, ""); return; }
                if (j.optBoolean("exists", false)) {
                    check.setTextColor(FilezDesign.SUCCESS);
                    Language.setText(check, Language.msg(l10n_filez_path_found, Util.formatBytes(j.optLong("size", 0))));
                } else {
                    check.setTextColor(FilezDesign.ERROR);
                    Language.TextBuilder sb = new Language.TextBuilder().append(Language.msg(l10n_filez_path_missing));
                    org.json.JSONArray kids = j.optJSONArray("parentlist");
                    if (!j.optBoolean("parentexists", false)) {
                        sb.append(Language.msg(l10n_filez_parent_missing));
                    } else if (kids != null) {
                        sb.append(Language.msg(l10n_filez_parent_contents));
                        for (int i = 0; i < kids.length(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append('"').append(kids.optString(i)).append('"');
                        }
                    }
                    Language.setText(check, sb.build());
                }
            }
            @Override public void onError(String m) { Language.setText(check, ""); }
        });

        Button copy = new Button(this);
        Language.setText(copy, Language.msg(l10n_filez_copy_path));
        copy.setOnClickListener(v -> {
            copyToClipboard(Language.text(this, l10n_filez_path_label), location);
            Language.setText(copy, Language.msg(l10n_filez_clipboard));
            ui.postDelayed(() -> Language.setText(copy, Language.msg(l10n_filez_copy_path)), 2500);
        });
        box.addView(copy);

        languageDialog()
                .setTitle(Language.join(e.name))
                .setView(box)
                .setPositiveButton(Language.msg(l10n_filez_export_phone), (d, w) -> startExport(e))
                .setNeutralButton(Language.msg(l10n_filez_close), null)
                .setNegativeButton(Language.msg(l10n_filez_delete), (d, w) -> confirmDelete(e))
                .show();
    }

    private void detailSection(LinearLayout parent, CharSequence label, CharSequence value) {
        TextView l = new TextView(this);
        Language.setText(l, label);
        l.setTextSize(12f);
        l.setTextColor(FilezDesign.DIM);
        l.setPadding(0, dp(8), 0, dp(2));
        parent.addView(l);
        TextView v = new TextView(this);
        Language.setText(v, value);
        v.setTextSize(14f);
        v.setTextIsSelectable(true);
        parent.addView(v);
    }

    // ---- EXPORT: node base folder -> Android file system (SAF) ----

    private void startExport(FileEntry e) {
        pendingExportPath = Util.joinPath(currentPath, e.name);
        exportLauncher.launch(e.name);
    }

    private void doExport(String nodePath, Uri dest) {
        pendingExportPath = null;
        Language.setText(status, Language.msg(l10n_filez_exporting));
        node.file("get", nodePath, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { onFileError(j); return; }
                final String srcUri = j.optString("uri", "");
                final long size = j.optLong("size", 0);
                io.execute(() -> {
                    try (InputStream input = getContentResolver().openInputStream(Uri.parse(srcUri));
                         OutputStream output = getContentResolver().openOutputStream(dest)) {
                        long copied = copyStream(input, output);
                        final CharSequence doneMsg = Language.msg(l10n_filez_exported, Util.formatBytes(copied));
                        ui.post(() -> { Language.setText(status, doneMsg); toast(doneMsg); updateHeaderSoon(); });
                        if (size > 0 && copied != size) {
                            ui.post(() -> toast(Language.msg(l10n_filez_size_warning, size, copied)));
                        }
                    } catch (Exception exc) {
                        ui.post(() -> { Language.setText(status, Language.msg(l10n_filez_export_failed)); toast(Language.msg(l10n_filez_export_error, FilezMessages.error(exc))); });
                    }
                });
            }
            @Override public void onError(String m) { handleFileCallError(m); }
        });
    }

    // ---- IMPORT: Android file system -> node base folder ----

    private void doImport(Uri src) {
        Language.setText(status, Language.msg(l10n_filez_importing));
        final String directory = currentPath;
        io.execute(() -> {
            try {
                String name = Util.safeName(displayNameOf(src));
                if (name.isEmpty()) name = "import_" + System.currentTimeMillis();
                final String dest = Util.joinPath(directory, name);
                // Same UID: the embedded node can stream the selected SAF document directly.
                ui.post(() -> node.file("put", dest, null, src, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        if (!j.optBoolean("status", false)) { onFileError(j); return; }
                        CharSequence msg = Language.msg(l10n_filez_imported, Util.formatBytes(j.optLong("size", 0)));
                        Language.setText(status, msg);
                        toast(msg);
                        loadDirectory(currentPath);
                    }
                    @Override public void onError(String m) { handleFileCallError(m); }
                }));
            } catch (Exception exc) {
                ui.post(() -> { Language.setText(status, Language.msg(l10n_filez_import_failed)); toast(Language.msg(l10n_filez_import_error, FilezMessages.error(exc))); });
            }
        });
    }

    private String displayNameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    // ---- mkdir / rename / delete / backup ----

    private void promptNewFolder() {
        promptText(Language.msg(l10n_filez_new_folder), "", name -> {
            String safe = Util.safeName(name);
            if (safe.isEmpty()) return;
            node.file("mkdir", Util.joinPath(currentPath, safe), null, null, simpleRefreshCb(Language.msg(l10n_filez_folder_created)));
        });
    }

    private void promptRename(FileEntry e) {
        promptText(Language.msg(l10n_filez_rename_title, e.name), e.name, name -> {
            String safe = Util.safeName(name);
            if (safe.isEmpty() || safe.equals(e.name)) return;
            node.file("move", Util.joinPath(currentPath, e.name), Util.joinPath(currentPath, safe), null,
                    simpleRefreshCb(Language.msg(l10n_filez_renamed)));
        });
    }

    private void confirmDelete(FileEntry e) {
        CharSequence warn = e.isdir
                ? Language.msg(l10n_filez_delete_folder, e.name)
                : Language.msg(l10n_filez_delete_file, e.name);
        languageDialog()
                .setTitle(Language.msg(l10n_filez_delete))
                .setMessage(warn)
                .setPositiveButton(Language.msg(l10n_filez_delete), (d, w) ->
                        node.file("delete", Util.joinPath(currentPath, e.name), null, null, simpleRefreshCb(Language.msg(l10n_filez_deleted))))
                .setNegativeButton(Language.msg(l10n_filez_cancel), null)
                .show();
    }

    /** Runs the node `backup` terminal command — the .bak lands in the base folder root. */
    private void createBackup() {
        Language.setText(status, Language.msg(l10n_filez_creating_backup));
        node.cmd("backup", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) {
                    String raw = j.optString("error", j.optString("message", null));
                    CharSequence err = raw == null ? Language.msg(l10n_filez_backup_failed) : raw;
                    Language.setText(status, err); toast(err); return;
                }
                String file = "";
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    JSONObject bk = r.optJSONObject("backup");
                    if (bk != null) file = bk.optString("file", "");
                }
                toast(file.isEmpty() ? Language.msg(l10n_filez_backup_created) : Language.msg(l10n_filez_backup_file, new File(file).getName()));
                loadDirectory("/");
            }
            @Override public void onError(String m) {
                // Plain CMD — an old node answers this fine, so a timeout means unreachable
                CharSequence msg = NodeApi.ERR_TIMEOUT.equals(m) ? MSG_NO_NODE : m;
                Language.setText(status, msg);
                toast(msg);
            }
        });
    }

    private NodeApi.Cb simpleRefreshCb(CharSequence okMsg) {
        return new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { onFileError(j); return; }
                toast(okMsg);
                loadDirectory(currentPath);
            }
            @Override public void onError(String m) { handleFileCallError(m); }
        };
    }

    private void promptText(CharSequence titleText, String prefill, TextCb cb) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        Language.setText(input, prefill);
        input.setSelection(prefill.length());
        // Native dialog surfaces follow Android light/dark; inherit their matching input colour.
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        languageDialog()
                .setTitle(titleText)
                .setView(wrap)
                .setPositiveButton(Language.msg(l10n_filez_ok), (d, w) -> cb.text(input.getText().toString()))
                .setNegativeButton(Language.msg(l10n_filez_cancel), null)
                .show();
    }

    private interface TextCb { void text(String value); }

    // ---- adapter ----

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        private List<FileEntry> data = new ArrayList<>();
        void setData(List<FileEntry> d) { data = d; notifyDataSetChanged(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView glyph, name, meta, more;
            VH(LinearLayout row, TextView g, TextView n, TextView m, TextView mo) {
                super(row); glyph = g; name = n; meta = m; more = mo;
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(8), dp(12));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView glyph = new TextView(MainActivity.this);
            glyph.setTextSize(18f);
            glyph.setWidth(dp(32));
            row.addView(glyph);

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(6), 0, dp(6), 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(MainActivity.this);
            name.setTextSize(15f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(FilezDesign.TEXT);
            TextView meta = new TextView(MainActivity.this);
            meta.setTextSize(12f);
            meta.setTextColor(FilezDesign.DIM);
            mid.addView(name); mid.addView(meta);
            row.addView(mid);

            TextView more = new TextView(MainActivity.this);
            Language.setText(more, "⋮");
            more.setTextSize(18f);
            more.setTextColor(FilezDesign.DIM);
            more.setPadding(dp(14), dp(6), dp(14), dp(6));
            row.addView(more);

            return new VH(row, glyph, name, meta, more);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            FileEntry e = data.get(pos);
            Language.setText(h.glyph, e.isdir ? "📁" : "📄");
            Language.setText(h.name, e.name);
            Language.setText(h.meta, e.isdir ? Language.msg(l10n_filez_folder) : Language.join(Util.formatBytes(e.size), e.modified > 0 ? Language.join("  ·  ", Language.stamp(e.modified, "yMMMdHm")) : ""));
            h.itemView.setOnClickListener(v -> {
                if (e.isdir) loadDirectory(Util.joinPath(currentPath, e.name));
                else showDetail(e);
            });
            Language.description(h.more, Language.msg(l10n_filez_entry_menu, e.name));
            h.more.setOnClickListener(v -> showEntryMenu(v, e));
        }

        @Override public int getItemCount() { return data.size(); }
    }

    // ---- helpers ----

    private static long copyStream(InputStream is, OutputStream os) throws Exception {
        if (is == null || os == null) throw new IllegalStateException("Could not open stream");
        try {
            long total = 0;
            byte[] buf = new byte[65536];
            int read;
            while ((read = is.read(buf)) != -1) { os.write(buf, 0, read); total += read; }
            os.flush();
            return total;
        } finally {
            try { is.close(); } catch (Exception ignored) {}
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    private void updateHeaderSoon() { ui.postDelayed(() -> updateHeader(adapter.getItemCount()), 1500); }

    private void openMinimaCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, Language.msg(l10n_filez_no_core), Toast.LENGTH_LONG).show();
    }

    private void openPandaApps() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PANDAAPPS_PKG);
        if (launch != null) startActivity(launch);
        else openMinimaCore();   // no store installed — at least surface the node
    }

    private void toast(CharSequence msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onResume() { super.onResume(); if (node != null) loadDirectory(currentPath); }

    @Override
    protected void onDestroy() {
        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();
        languageDialogs.clear();
        for (PopupMenu value : new java.util.ArrayList<>(languageMenus.keySet())) value.dismiss();
        languageMenus.clear();
        super.onDestroy();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }
}
