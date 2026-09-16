package org.minimarex.minimacore.main.backup;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.SeedSyncActivity;

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

        addRow("Private keys",
                "Seed phrase, lock and unlock, wipe and restore, or rebuild this wallet from a phrase.",
                v -> startActivity(new Intent(this, VaultActivity.class)));
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

}
