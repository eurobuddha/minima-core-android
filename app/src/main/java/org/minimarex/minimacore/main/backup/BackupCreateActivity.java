package org.minimarex.minimacore.main.backup;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.ResyncJob;
import org.minimarex.minimacore.utils.Clip;
import org.minimarex.minimacore.utils.KeyboardInsets;
import org.minimarex.minimacore.utils.MinimaCMD;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Create one encrypted .bak, then offer to put a copy somewhere off this phone.
 *
 * Backup is not destructive and does not restart the node, so unlike restore it does not go
 * through ResyncLauncher. It IS slow, so the work lives in {@link BackupRun} and this screen
 * only renders it - a rotation mid-backup loses nothing.
 */
public class BackupCreateActivity extends AppCompatActivity {

    private final BackupRun run = BackupRun.get();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText name, password, confirm;
    private Button go, copy, export;
    private TextView status, detail, path;
    private ProgressBar progress;

    private ActivityResultLauncher<String> exportLauncher;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (run.state() == BackupRun.State.RUNNING) handler.postDelayed(this, 400);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be registered before the Activity reaches STARTED - the same rule SendActivity
        // documents for its scanner.
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                this::onExportDestinationChosen);

        // The backup password is the only thing protecting an exported .bak, and it can now be
        // revealed on screen - so this screen opts out of the "Allow Screenshots" setting exactly
        // as VaultActivity does, rather than leaving the password in a recents thumbnail.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.backup_create);
        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Create backup");
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());
        KeyboardInsets.install(this, findViewById(R.id.backup_create_main), tb);

        name = findViewById(R.id.backup_create_name);
        password = findViewById(R.id.backup_create_password);
        confirm = findViewById(R.id.backup_create_confirm);
        go = findViewById(R.id.backup_create_go);
        copy = findViewById(R.id.backup_create_copy);
        export = findViewById(R.id.backup_create_export);
        status = findViewById(R.id.backup_create_status);
        detail = findViewById(R.id.backup_create_detail);
        path = findViewById(R.id.backup_create_path);
        progress = findViewById(R.id.backup_create_progress);

        PasswordField.attach(password, findViewById(R.id.backup_create_password_eye));
        PasswordField.attach(confirm, findViewById(R.id.backup_create_confirm_eye));

        if (TextUtils.isEmpty(name.getText())) name.setText(suggestedName());
        go.setOnClickListener(v -> attemptBackup());
        copy.setOnClickListener(v ->
                Clip.copy(this, "Backup path", run.path(), "Full path copied"));
        export.setOnClickListener(v -> confirmExport());
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(tick);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        super.onStop();
    }

    /** Timestamped, and already valid under ResyncJob's filename rule. */
    private static String suggestedName() {
        return "minima-backup-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(new Date()) + ".bak";
    }

    private void attemptBackup() {
        String file = name.getText().toString().trim();
        String pass = password.getText().toString();
        String again = confirm.getText().toString();

        if (!ResyncJob.validFilename(file)) {
            name.setError("Letters, numbers, dot, dash and underscore only - no folders");
            return;
        }
        name.setError(null);
        if (!ResyncJob.validPassword(pass)) {
            // The node's own help says letters and numbers only, and its command tokeniser
            // silently rewrites anything else - see ResyncJob.
            password.setError("Letters and numbers only");
            return;
        }
        password.setError(null);
        if (!pass.equals(again)) {
            confirm.setError("The two passwords do not match");
            return;
        }
        confirm.setError(null);

        NodeKeys.Status keys = NodeKeys.read();
        if (!keys.ready) {
            setTextIfChanged(status, keys.message());
            return;
        }

        if (!run.start(file, pass, again)) {
            setTextIfChanged(status, "A backup is already running.");
            return;
        }
        handler.post(tick);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        BackupRun.State state = run.state();
        boolean running = state == BackupRun.State.RUNNING;
        boolean done = state == BackupRun.State.SUCCEEDED;

        go.setEnabled(!running);
        setTextIfChanged(go, running ? "Creating…" : done ? "Create another" : "Create backup");
        name.setEnabled(!running);
        password.setEnabled(!running);
        confirm.setEnabled(!running);
        progress.setVisibility(running ? View.VISIBLE : View.GONE);

        if (running) {
            setTextIfChanged(status, "Writing the backup. The node is locked while it copies, so this can take a while.");
        } else if (done) {
            boolean located = !run.path().isEmpty();
            // Exporting needs a path. Without one the backup still exists - say so and point at
            // the list that will show it, rather than claiming a failure the folder disproves.
            setTextIfChanged(status, located
                    ? "Backup created."
                    : "Backup created, but the node did not report the file name. Find it under Restore from file.");
            detail.setVisibility(located ? View.VISIBLE : View.GONE);
            path.setVisibility(located ? View.VISIBLE : View.GONE);
            copy.setVisibility(located ? View.VISIBLE : View.GONE);
            export.setVisibility(located ? View.VISIBLE : View.GONE);
            if (located) {
                setTextIfChanged(detail, "Size " + run.size() + " · block " + run.block() + " · saved in the node folder as");
                setTextIfChanged(path, run.path());
            }
        } else if (state == BackupRun.State.FAILED) {
            setTextIfChanged(status, "Backup failed. " + run.error());
            detail.setVisibility(View.GONE);
            path.setVisibility(View.GONE);
            copy.setVisibility(View.GONE);
            export.setVisibility(View.GONE);
        }
    }

    /**
     * Exporting takes the wallet off this device.
     *
     * The file is encrypted, but it is still every private key this node holds, and the place
     * it is going may well be a cloud-synced Downloads folder. Say that plainly once, here,
     * rather than letting a file picker imply it is routine.
     */
    private void confirmExport() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Save a copy off this phone?")
                .setMessage("This file contains your wallet's private keys, encrypted with the password you just set.\n\n"
                        + "Anyone who gets both the file and that password controls your funds. "
                        + "Think about where you are putting it - a shared or cloud-synced folder is not private.")
                .setPositiveButton("Choose location", (d, w) -> exportLauncher.launch(run.filename()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onExportDestinationChosen(Uri destination) {
        if (destination == null) return;   // the user backed out of the picker
        final File source = new File(run.path());
        setTextIfChanged(status, "Saving a copy…");
        // Off the main thread: this streams the whole backup.
        MinimaCMD.readExecutor().execute(() -> {
            String message;
            try {
                long copied = NodeFiles.exportTo(this, source, destination);
                message = "Saved " + NodeFiles.formatBytes(copied) + " to the location you chose.";
            } catch (Exception exc) {
                message = "Could not save the copy. " + (exc.getMessage() == null ? exc.toString() : exc.getMessage());
            }
            final String done = message;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setTextIfChanged(status, done);
            });
        });
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
}
