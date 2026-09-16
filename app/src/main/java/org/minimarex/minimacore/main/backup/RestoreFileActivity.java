package org.minimarex.minimacore.main.backup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.ResyncJob;
import org.minimarex.minimacore.main.ResyncLauncher;
import org.minimarex.minimacore.main.SeedSyncActivity;
import org.minimarex.minimacore.utils.KeyboardInsets;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.Peers;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Restore this node from a backup file - the route the node's own UI never had.
 *
 * Two commands sit behind one screen, because the user's real question is "is my backup recent
 * enough?", not "which subcommand?":
 *   recent  -> restore file: password:                       (local, no network)
 *   old     -> megammrsync action:resync host: file: password:  (catch up to the tip)
 *
 * Both are destructive and both restart the node, so both go through ResyncLauncher and are
 * watched on SeedSyncActivity. Nothing here runs a command directly.
 */
public class RestoreFileActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout fileRows, hostRow;
    private TextView gate, empty, status;
    private EditText password, host;
    private RadioButton modeLocal, modeResync;
    private Button go, importButton;

    private ActivityResultLauncher<String[]> importLauncher;
    private String selected = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Registered before STARTED, as SendActivity documents.
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onFileChosen);

        // The backup password is the only thing protecting an exported .bak, and it can now be
        // revealed on screen - so this screen opts out of the "Allow Screenshots" setting exactly
        // as VaultActivity does, rather than leaving the password in a recents thumbnail.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.backup_restore);
        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Restore from file");
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());
        KeyboardInsets.install(this, findViewById(R.id.backup_restore_main), tb);

        fileRows = findViewById(R.id.backup_restore_files);
        hostRow = findViewById(R.id.backup_restore_host_row);
        gate = findViewById(R.id.backup_restore_gate);
        empty = findViewById(R.id.backup_restore_empty);
        status = findViewById(R.id.backup_restore_status);
        password = findViewById(R.id.backup_restore_password);
        host = findViewById(R.id.backup_restore_host);
        modeLocal = findViewById(R.id.backup_restore_mode_local);
        modeResync = findViewById(R.id.backup_restore_mode_resync);
        go = findViewById(R.id.backup_restore_go);
        importButton = findViewById(R.id.backup_restore_import);

        PasswordField.attach(password, findViewById(R.id.backup_restore_password_eye));
        host.setText(Peers.getDefaultPeers(this));
        modeResync.setOnCheckedChangeListener((b, checked) ->
                hostRow.setVisibility(checked ? View.VISIBLE : View.GONE));
        importButton.setOnClickListener(v -> importLauncher.launch(new String[]{"*/*"}));
        go.setOnClickListener(v -> attemptRestore());
    }

    @Override protected void onResume() {
        super.onResume();
        renderGate();
        renderFiles();
    }

    private void renderGate() {
        NodeKeys.Status keys = NodeKeys.read();
        gate.setVisibility(keys.ready ? View.GONE : View.VISIBLE);
        if (!keys.ready) gate.setText(keys.message());
        go.setEnabled(keys.ready);
    }

    private void renderFiles() {
        fileRows.removeAllViews();
        List<File> files = NodeFiles.restorable(this);
        empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        SimpleDateFormat when = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK);
        for (File file : files) {
            View row = LayoutInflater.from(this).inflate(R.layout.view_backup_row, fileRows, false);
            TextView title = row.findViewById(R.id.backup_row_title);
            TextView subtitle = row.findViewById(R.id.backup_row_subtitle);
            // Full filename, never elided - it is what goes into the command.
            title.setText(file.getName());
            // The folder also holds archive exports and txn files. They are listed because a
            // restore CAN name them, but they must not look like backups.
            String kind = NodeFiles.isBackup(file) ? "" : " · not a .bak";
            subtitle.setText(NodeFiles.formatBytes(file.length()) + " · "
                    + when.format(new Date(file.lastModified())) + kind);
            row.setOnClickListener(v -> {
                selected = file.getName();
                renderFiles();
            });
            Button action = row.findViewById(R.id.backup_row_action);
            action.setVisibility(View.VISIBLE);
            action.setText("Delete");
            action.setOnClickListener(v -> confirmDelete(file, NodeFiles.backupCount(this)));
            row.setAlpha(file.getName().equals(selected) ? 1f : 0.55f);
            fileRows.addView(row);
        }
        if (!selected.isEmpty()) {
            setTextIfChanged(status, "Selected " + selected);
        }
    }

    /**
     * Deleting a backup is irreversible and the file may be the only copy of a wallet.
     *
     * So the dialog names the file in full and its size, and says plainly what cannot be
     * undone. When it is the last backup left, it says that too - that is the case where
     * someone is one tap from having no way back at all.
     */
    private void confirmDelete(File file, int totalBackups) {
        // Counts .bak files only. Counting every listed file meant one backup sitting beside a
        // txn file looked like "two backups", and this warning silently did not fire.
        String warning = NodeFiles.isBackup(file) && totalBackups <= 1
                ? "\n\nThis is the only backup file on this node. Once it is gone there is nothing here to restore from."
                : "";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this backup?")
                .setMessage(file.getName() + "\n" + NodeFiles.formatBytes(file.length()) + "\n\n"
                        + "This permanently removes the file from the node folder. If you have not saved a "
                        + "copy somewhere else, the wallet inside it cannot be recovered." + warning)
                .setPositiveButton("Delete", (d, w) -> deleteNow(file.getName()))
                .setNegativeButton("Keep", null)
                .show();
    }

    private void deleteNow(String name) {
        String message;
        try {
            NodeFiles.delete(this, name);
            message = "Deleted " + name;
            if (name.equals(selected)) selected = "";
        } catch (Exception exc) {
            message = "Could not delete " + name + ". "
                    + (exc.getMessage() == null ? exc.toString() : exc.getMessage());
        }
        renderFiles();
        setTextIfChanged(status, message);
    }

    private void onFileChosen(Uri source) {
        if (source == null) return;
        setTextIfChanged(status, "Bringing the file in…");
        MinimaCMD.readExecutor().execute(() -> {
            String message;
            String landed = "";
            try {
                // Never trust the provider's display name, and never silently overwrite.
                String name = NodeFiles.uniqueName(NodeFiles.base(this),
                        NodeFiles.displayName(getContentResolver(), source));
                File file = NodeFiles.importFrom(this, source, name);
                landed = file.getName();
                message = "Added " + file.getName() + " (" + NodeFiles.formatBytes(file.length()) + ")";
            } catch (Exception exc) {
                message = "Could not bring the file in. "
                        + (exc.getMessage() == null ? exc.toString() : exc.getMessage());
            }
            final String done = message;
            final String pick = landed;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!pick.isEmpty()) selected = pick;
                renderFiles();
                setTextIfChanged(status, done);
            });
        });
    }

    private void attemptRestore() {
        if (selected.isEmpty()) {
            setTextIfChanged(status, "Choose which backup file to restore.");
            return;
        }
        String pass = password.getText().toString();
        if (!ResyncJob.validPassword(pass)) {
            password.setError("Letters and numbers only");
            return;
        }
        password.setError(null);

        final ResyncJob job;
        if (modeResync.isChecked()) {
            String where = host.getText().toString().trim();
            if (!ResyncJob.validHost(where)) {
                host.setError("Enter a hostname or IPv4 address and a port from 1 to 65535");
                return;
            }
            host.setError(null);
            job = ResyncJob.fileResync(where, selected, pass);
        } else {
            job = ResyncJob.fileRestore(selected, pass);
        }
        if (job == null) {
            setTextIfChanged(status, "Check the file name and password and try again.");
            return;
        }
        confirm(job);
    }

    /**
     * A restore overwrites the wallet, not just the chain - a wrong choice here loses funds in
     * a way a resync never does. So this confirmation names the file, says what goes, and needs
     * the word typed rather than one more tap.
     */
    private void confirm(ResyncJob job) {
        EditText typed = new EditText(this);
        typed.setHint("type RESTORE");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        typed.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Replace this node?")
                .setMessage("Minima will replace its wallet and chain with " + selected + ".\n\n"
                        + "• The keys and coins this node holds now are GONE unless they are also in that backup.\n"
                        + "• Any transaction still waiting to be mined is dropped.\n"
                        + "• The node stops when it finishes and you restart it.\n\n"
                        + "Type RESTORE to confirm.")
                .setView(typed)
                .setPositiveButton("Restore", (d, w) -> {
                    if (!"RESTORE".contentEquals(typed.getText().toString().trim().toUpperCase(Locale.UK))) {
                        setTextIfChanged(status, "Not confirmed - nothing was changed.");
                        return;
                    }
                    launch(job);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launch(ResyncJob job) {
        // Shared with the resync path - see ResyncLauncher for why the pending flag, the alarms
        // and the service binding all have to be handled there and not here.
        ResyncLauncher.Result result = ResyncLauncher.begin(this, job);
        if (!result.started) {
            setTextIfChanged(status, result.error);
            return;
        }
        // Progress, the live node log and the restart button all live on the resync screen.
        startActivity(new Intent(this, SeedSyncActivity.class));
        finish();
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
}
