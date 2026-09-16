package org.minimarex.minimacore.main.backup;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.MinimaApplication;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.SeedSyncActivity;
import org.minimarex.minimacore.utils.Clip;
import org.minimarex.minimacore.utils.Feedback;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;

/**
 * Backup &amp; Recovery - one home for the operations that were scattered across the overflow
 * menu (Resync Node, Show Seed) and the ones the node could never reach from its own UI at all.
 *
 * Rows are added here as each screen lands, so a feature that is not built yet is absent rather
 * than present and dead.
 */
public class BackupActivity extends AppCompatActivity {

    private TextView gate;
    private LinearLayout rows;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.backup_hub);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Backup & Recovery");
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());

        gate = findViewById(R.id.backup_hub_gate);
        rows = findViewById(R.id.backup_hub_rows);
        buildRows();
    }

    @Override protected void onResume() {
        super.onResume();
        // Keys finish creating while this screen is open, so re-read rather than cache.
        renderGate();
    }

    private void buildRows() {
        addRow("Create backup",
                "Write an encrypted .bak of this node, then save a copy off the phone.",
                v -> startActivity(new Intent(this, BackupCreateActivity.class)));

        addRow("Restore from file",
                "Put a backup back on this node, optionally catching up to the chain tip afterwards.",
                v -> startActivity(new Intent(this, RestoreFileActivity.class)));

        addRow("Resync node",
                "Rebuild this node's chain from a MegaMMR node. Your wallet and seed phrase are not touched.",
                v -> startActivity(new Intent(this, SeedSyncActivity.class)));

        addRow("Seed phrase",
                "View the 24 words that can rebuild this wallet anywhere. Anyone who reads them owns your funds.",
                v -> showSeedPhrase());
    }

    private void addRow(String title, String subtitle, View.OnClickListener onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.view_backup_row, rows, false);
        ((TextView) row.findViewById(R.id.backup_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.backup_row_subtitle)).setText(subtitle);
        row.setOnClickListener(onClick);
        rows.addView(row);
    }

    /** Shown once, at the top, instead of as six identical mid-command failures. */
    private void renderGate() {
        NodeKeys.Status status = NodeKeys.read();
        boolean blocked = !status.ready;
        gate.setVisibility(blocked ? View.VISIBLE : View.GONE);
        if (blocked) gate.setText(status.message());
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            row.setEnabled(!blocked);
            row.setAlpha(blocked ? 0.45f : 1f);
        }
    }

    /**
     * Moved here from the overflow menu's "Show Seed".
     *
     * The old version put the phrase in a plain alert message, which cannot be selected or
     * copied - a 24 word phrase that has to be transcribed by eye is how people end up with a
     * wrong word and an unrecoverable wallet. It is shown in full, selectable, with a copy
     * button; FLAG_SECURE is forced on for as long as it is on screen regardless of the
     * "Allow Screenshots" setting, because this one string is the whole wallet.
     */
    private void showSeedPhrase() {
        MinimaCMD.runMinima("vault", new MinimaCMDListener() {
            @Override public void cmdResult(JSONObject zResult) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    String error = Feedback.errorOf(zResult);
                    if (error != null) {
                        new MaterialAlertDialogBuilder(BackupActivity.this)
                                .setTitle("Seed phrase")
                                .setMessage(error)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        return;
                    }
                    Object response = zResult.get("response");
                    String phrase = response instanceof JSONObject
                            ? String.valueOf(((JSONObject) response).get("phrase")) : "";
                    if (phrase.isEmpty() || "null".equals(phrase)) {
                        new MaterialAlertDialogBuilder(BackupActivity.this)
                                .setTitle("Seed phrase")
                                .setMessage("The private keys are locked. Unlock them to read the seed phrase.")
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        return;
                    }
                    showPhraseDialog(phrase);
                });
            }
        });
    }

    private void showPhraseDialog(String phrase) {
        TextView view = new TextView(this);
        view.setTextIsSelectable(true);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextColor(getColor(R.color.core_text));
        view.setLineSpacing(0f, 1.3f);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        view.setText(phrase);

        // The phrase IS the wallet - never let it reach a screenshot or the recents thumbnail,
        // even when the user has turned "Allow Screenshots" on for the rest of the app.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Seed phrase")
                .setView(view)
                .setPositiveButton("Copy", (d, w) ->
                        Clip.copy(this, "Minima seed phrase", phrase, "Seed phrase copied"))
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> {
                    if (!isScreenshotsAllowed()) return;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                })
                .show();
    }

    /**
     * MinimaApplication already sets FLAG_SECURE on every Activity unless the user turned
     * "Allow Screenshots" on, so only put it back the way it was for someone who had allowed it.
     */
    private boolean isScreenshotsAllowed() {
        return MinimaApplication.screenshotsAllowed(this);
    }
}
