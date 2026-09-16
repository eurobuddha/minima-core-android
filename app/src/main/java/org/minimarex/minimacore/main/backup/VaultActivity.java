package org.minimarex.minimacore.main.backup;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.utils.SystemBars;
import org.minimarex.minimacore.main.ResyncJob;
import org.minimarex.minimacore.main.ResyncLauncher;
import org.minimarex.minimacore.main.SeedSyncActivity;
import org.minimarex.minimacore.utils.Clip;
import org.minimarex.minimacore.utils.Feedback;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import org.minimarex.minimacore.utils.Peers;

/**
 * The `vault` surface: read the seed phrase, lock and unlock the private keys, wipe and restore
 * them, and rebuild this wallet somewhere else from the phrase.
 *
 * FLAG_SECURE is forced on for the whole screen and never dropped, unlike the rest of the app
 * where it follows the "Allow Screenshots" setting. Everything here either is the wallet or
 * unlocks it, and a recents-thumbnail of a seed phrase is as good as the phrase itself.
 *
 * Only the last action - rebuilding from a phrase - is destructive and restarts the node, so it
 * alone goes through ResyncLauncher. The rest are ordinary commands run in place.
 */
public class VaultActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView state, status, phrase, seed, seedLabel, phraseHint;
    private Button reveal, copyPhrase;
    private ProgressBar progress;
    private LinearLayout actions;

    /** Held only while the screen is up, so a wipe can hand the node its proof. */
    private String currentPhrase = "";
    private String currentSeed = "";
    private boolean locked;
    private boolean busy;
    /** False until the first `vault` reply lands, so "empty" is not mistaken for "no phrase". */
    private boolean loaded;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Never conditional here - see the class comment.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.vault_keys);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Private keys");
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());
        // targetSdk 35 is edge to edge: without this the toolbar sits under the clock.
        SystemBars.pad(findViewById(R.id.vault_main));

        state = findViewById(R.id.vault_state);
        status = findViewById(R.id.vault_status);
        phrase = findViewById(R.id.vault_phrase);
        seed = findViewById(R.id.vault_seed);
        seedLabel = findViewById(R.id.vault_seed_label);
        phraseHint = findViewById(R.id.vault_phrase_hint);
        reveal = findViewById(R.id.vault_reveal);
        copyPhrase = findViewById(R.id.vault_copy_phrase);
        progress = findViewById(R.id.vault_progress);
        actions = findViewById(R.id.vault_actions);

        reveal.setOnClickListener(v -> toggleReveal());
        copyPhrase.setOnClickListener(v ->
                Clip.copySensitive(this, "Minima seed phrase", currentPhrase, "Seed phrase copied"));
    }

    @Override protected void onResume() {
        super.onResume();
        hidePhrase();
        readVault();
    }

    @Override protected void onPause() {
        // Do not leave the phrase on screen, or in memory, for whoever opens the app next.
        // onResume re-reads it from the node, so nothing is lost by dropping it here.
        hidePhrase();
        currentPhrase = "";
        currentSeed = "";
        loaded = false;
        super.onPause();
    }

    // ---- reading state ----

    private void readVault() {
        MinimaCMD.runMinima(VaultCommands.read(), new MinimaCMDListener() {
            @Override public void cmdResult(JSONObject result) {
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    String error = Feedback.errorOf(result);
                    if (error != null) {
                        state.setText("Could not read the vault. " + error);
                        buildActions();
                        return;
                    }
                    loaded = true;
                    Object response = result.get("response");
                    if (response instanceof JSONObject) {
                        JSONObject json = (JSONObject) response;
                        currentPhrase = text(json.get("phrase"));
                        currentSeed = text(json.get("seed"));
                        locked = Boolean.TRUE.equals(json.get("locked"));
                    }
                    state.setText(locked
                            ? "LOCKED — the private keys are encrypted. This node can verify but cannot spend until you unlock them."
                            : "UNLOCKED — the private keys are on this device and this node can spend.");
                    buildActions();
                });
            }
        });
    }

    private static String text(Object value) {
        String out = value == null ? "" : String.valueOf(value);
        return "null".equals(out) ? "" : out;
    }

    // ---- the seed phrase ----

    private void toggleReveal() {
        if (phrase.getVisibility() == View.VISIBLE) {
            hidePhrase();
            return;
        }
        if (!loaded) {
            // The cache is dropped on pause and refilled asynchronously on resume. Without this,
            // tapping Show in that gap reported "no seed phrase" for what is only a slow read.
            setStatus("Reading from the node…");
            return;
        }
        if (currentPhrase.isEmpty()) {
            setStatus(locked
                    ? "The private keys are locked. Unlock them to read the seed phrase."
                    : "The node did not return a seed phrase.");
            return;
        }
        phrase.setText(currentPhrase);
        // The seed is shown too because wiping the keys requires handing it back.
        seed.setText(currentSeed);
        phrase.setVisibility(View.VISIBLE);
        seed.setVisibility(currentSeed.isEmpty() ? View.GONE : View.VISIBLE);
        seedLabel.setVisibility(currentSeed.isEmpty() ? View.GONE : View.VISIBLE);
        copyPhrase.setVisibility(View.VISIBLE);
        phraseHint.setVisibility(View.GONE);
        reveal.setText("Hide seed phrase");
    }

    private void hidePhrase() {
        // Clear, not just hide: a GONE view still holds the words in the hierarchy.
        phrase.setText("");
        seed.setText("");
        phrase.setVisibility(View.GONE);
        seed.setVisibility(View.GONE);
        seedLabel.setVisibility(View.GONE);
        copyPhrase.setVisibility(View.GONE);
        phraseHint.setVisibility(View.VISIBLE);
        reveal.setText("Show seed phrase");
    }

    // ---- actions ----

    private void buildActions() {
        actions.removeAllViews();
        if (locked) {
            addAction("Unlock private keys",
                    "Decrypt the keys with the password you locked them with, so this node can spend again.",
                    v -> askPassword("Unlock private keys",
                            "Enter the password you used to lock them.",
                            "Unlock",
                            password -> run(VaultCommands.passwordUnlock(password), "Private keys unlocked.")));
        } else {
            addAction("Lock private keys",
                    "Encrypt the keys behind a password. The node keeps running and verifying, but cannot spend until unlocked.",
                    v -> askNewPassword("Lock private keys",
                            "Choose a password and type it twice. Without it the keys cannot be unlocked, and only your seed phrase will bring them back.",
                            "Lock",
                            (password, confirm) -> run(VaultCommands.passwordLock(password, confirm),
                                    "Private keys locked.")));

            addAction("Wipe private keys",
                    "Remove the keys from this device, keeping the public keys. Only your seed phrase can restore them.",
                    v -> confirmWipe());
        }

        addAction("Restore private keys",
                "Recreate the keys on this node from your seed phrase, undoing a wipe.",
                v -> askPhrase("Restore private keys",
                        "Enter the seed phrase for this wallet.",
                        "Restore",
                        words -> run(VaultCommands.restoreKeys(words), "Private keys restored.")));

        addAction("Rebuild wallet from seed phrase",
                "Replace this node's wallet with the one for a seed phrase, then resync from a MegaMMR node. Destructive.",
                v -> confirmSeedRebuild());
    }

    private void addAction(String title, String subtitle, View.OnClickListener onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.view_backup_row, actions, false);
        ((TextView) row.findViewById(R.id.backup_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.backup_row_subtitle)).setText(subtitle);
        row.setOnClickListener(v -> { if (!busy) onClick.onClick(v); });
        actions.addView(row);
    }

    // ---- input dialogs ----

    private interface PasswordAction { void run(String password); }
    private interface NewPasswordAction { void run(String password, String confirm); }
    private interface PhraseAction { void run(String phrase); }

    private EditText field(int inputType, String hint) {
        EditText input = new EditText(this);
        input.setInputType(inputType);
        input.setHint(hint);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    /** One password, for a value the node itself verifies - a wrong one just fails harmlessly. */
    private void askPassword(String title, String message, String confirmText, PasswordAction action) {
        LinearLayout box = column();
        PasswordField entry = PasswordField.inflate(this, box, "letters and numbers only");
        box.addView(entry.view());
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(box)
                .setPositiveButton(confirmText, (d, w) -> {
                    if (!ResyncJob.validPassword(entry.text())) {
                        // Same rule as everywhere else: the node's tokeniser rewrites anything
                        // else, so a password it would mangle must never be sent.
                        setStatus("Letters and numbers only — the node cannot carry other characters.");
                        return;
                    }
                    action.run(entry.text());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Two entries, for a password that is being SET.
     *
     * Nothing verifies it afterwards: whatever is typed here becomes the only way back to the
     * keys short of the seed phrase, so a single mistyped character is unrecoverable. Both boxes
     * reveal, so the value can be read before committing.
     */
    private void askNewPassword(String title, String message, String confirmText, NewPasswordAction action) {
        LinearLayout box = column();
        PasswordField first = PasswordField.inflate(this, box, "letters and numbers only");
        PasswordField second = PasswordField.inflate(this, box, "type it again");
        box.addView(first.view());
        box.addView(second.view());
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(box)
                .setPositiveButton(confirmText, (d, w) -> {
                    if (!ResyncJob.validPassword(first.text())) {
                        setStatus("Letters and numbers only — the node cannot carry other characters.");
                        return;
                    }
                    if (!first.text().equals(second.text())) {
                        setStatus("The two passwords do not match. Nothing was changed.");
                        return;
                    }
                    action.run(first.text(), second.text());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askPhrase(String title, String message, String confirmText, PhraseAction action) {
        EditText input = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                "your 24 words, separated by spaces");
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(confirmText, (d, w) -> {
                    String words = ResyncJob.tidyPhrase(input.getText().toString());
                    if (!ResyncJob.validPhrase(words)) {
                        setStatus("That does not look like a seed phrase — 12 to 24 words, letters only.");
                        return;
                    }
                    action.run(words);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The node requires the seed handed back "TO SHOW THEY KNOW IT" (vault.java), and that is
     * the entire safety mechanism for a wipe. So this asks the user to paste it rather than
     * filling it in for them - autofilling it would satisfy the node while proving nothing, and
     * someone who cannot produce the seed is exactly the person who must not wipe.
     */
    private void confirmWipe() {
        EditText input = field(InputType.TYPE_CLASS_TEXT, "0x…");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Wipe private keys?")
                .setMessage("This removes the private keys from this device. Only your seed phrase can bring them back — "
                        + "if you have not written it down, your funds are gone for good.\n\n"
                        + "Paste this wallet's seed to confirm you have it. You can read it above under Show seed phrase.")
                .setView(input)
                .setPositiveButton("Wipe keys", (d, w) -> {
                    String typed = input.getText().toString().trim();
                    if (!ResyncJob.validSeedHex(typed)) {
                        setStatus("That is not a seed. It starts with 0x followed by hex digits.");
                        return;
                    }
                    // The node checks it really matches; a mismatch is refused there, not here.
                    run(VaultCommands.wipeKeys(typed), "Private keys wiped.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Destructive and restarts the node, so it is the one action here that goes through
     * ResyncLauncher and is watched on the resync screen.
     */
    private void confirmSeedRebuild() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText words = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                "your 24 words, separated by spaces");
        EditText host = field(InputType.TYPE_CLASS_TEXT, "host:port");
        host.setText(Peers.getDefaultPeers(this));
        EditText uses = field(InputType.TYPE_CLASS_NUMBER,
                "key uses, at least " + ResyncJob.MIN_KEY_USES);
        uses.setText("2000");
        box.addView(words);
        box.addView(host);
        box.addView(uses);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Rebuild this wallet?")
                .setMessage("Minima will replace this node's wallet with the one for that seed phrase and resync from a MegaMMR node.\n\n"
                        + "• The keys and coins this node holds now are GONE unless they belong to that phrase.\n"
                        + "• Key uses must be HIGHER than any previous rebuild — Minima signatures are stateful, "
                        + "and reusing a key index is how a wallet loses funds.\n"
                        + "• The node stops when it finishes and you restart it.")
                .setView(box)
                .setPositiveButton("Rebuild", (d, w) -> {
                    String phraseText = ResyncJob.tidyPhrase(words.getText().toString());
                    String hostText = host.getText().toString().trim();
                    int keyUses;
                    try {
                        keyUses = Integer.parseInt(uses.getText().toString().trim());
                    } catch (NumberFormatException exc) {
                        setStatus("Key uses must be a whole number.");
                        return;
                    }
                    ResyncJob job = ResyncJob.seedResync(hostText, phraseText, keyUses);
                    if (job == null) {
                        setStatus("Check the phrase, the host, and the key uses ("
                                + ResyncJob.MIN_KEY_USES + " to " + ResyncJob.MAX_KEY_USES + ").");
                        return;
                    }
                    ResyncLauncher.Result result = ResyncLauncher.begin(this, job);
                    if (!result.started) {
                        setStatus(result.error);
                        return;
                    }
                    startActivity(new Intent(this, SeedSyncActivity.class));
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- running a vault command ----

    private void run(String command, String successMessage) {
        if (command == null) {
            setStatus("Check what you entered and try again.");
            return;
        }
        NodeKeys.Status keys = NodeKeys.read();
        if (!keys.ready) {
            setStatus(keys.message());
            return;
        }
        busy = true;
        progress.setVisibility(View.VISIBLE);
        setStatus("Working…");
        MinimaCMD.runMinima(command, new MinimaCMDListener() {
            @Override public void cmdResult(JSONObject result) {
                handler.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    String error = Feedback.errorOf(result);
                    setStatus(error == null ? successMessage : error);
                    // The lock state and the phrase may both have changed.
                    currentPhrase = "";
                    currentSeed = "";
                    hidePhrase();
                    readVault();
                });
            }
        });
    }

    private void setStatus(String message) {
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        if (!message.contentEquals(status.getText())) status.setText(message);
    }
}
