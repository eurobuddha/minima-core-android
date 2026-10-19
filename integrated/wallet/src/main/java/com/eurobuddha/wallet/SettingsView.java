package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.wallet.comms.NodeLink;

/**
 * Settings tab: the explicit keyuses meter, seed reveal (auth-gated), encrypted backup / restore, the
 * fail-safe controls when keyuses is untrusted, and node / debug info.
 */
public class SettingsView extends BaseView {

    private final LinearLayout container;
    private final java.util.function.BooleanSupplier session;
    private boolean currentSession() {
        if (session.getAsBoolean()) return true;
        Toast.makeText(act, Language.msg(l10n_wallet_session_changed), Toast.LENGTH_SHORT).show();
        return false;
    }

    public SettingsView(MainActivity a) {
        super(a, makeRoot(a));
        session = a.sessionAuthorization();
        ScrollView sv = (ScrollView) root;
        container = (LinearLayout) sv.getChildAt(0);
        refresh();
    }

    private static View makeRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 24));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        return sv;
    }

    @Override
    public void refresh() {
        if (!session.getAsBoolean()) return;
        container.removeAllViews();

        // --- Security / keyuses -------------------------------------------------------------------
        container.addView(sectionTitle(Language.msg(l10n_wallet_security)));

        CharSequence usage;
        try { usage = act.keyUses().currentUses(0) + " / " + Util.WOTS_MAX_USES; }
        catch (RuntimeException unavailable) { usage = Language.msg(l10n_wallet_counter_unavailable); }
        container.addView(kv(Language.msg(l10n_wallet_signatures_used), usage));
        container.addView(hint(Language.msg(l10n_wallet_signature_help)));

        container.addView(outlineButton(Language.msg(l10n_wallet_set_floor), v -> setUsesManually()));
        boolean trusted = act.vault().isKeyUsesTrusted();
        if (!trusted) {
            container.addView(warnBox(Language.msg(l10n_wallet_untrusted_title),
                    Language.msg(l10n_wallet_untrusted_body)));

            container.addView(dangerButton(Language.msg(l10n_wallet_brand_new_seed), v -> confirmBrandNew()));
            container.addView(dangerButton(Language.msg(l10n_wallet_latest_backup), v -> attestRestored()));
            container.addView(outlineButton(Language.msg(l10n_wallet_restore_key_uses), v -> restore()));
        }

        container.addView(sectionTitle(Language.msg(l10n_sweep_title)));
        container.addView(outlineButton(Language.msg(l10n_sweep_settings),v->{if(currentSession())new SweepFlow(act).start();}));
        container.addView(hint(Language.msg(l10n_sweep_help)));

        container.addView(dangerButton(Language.msg(l10n_wallet_reset), v -> resetWallet()));

        // --- Seed backup --------------------------------------------------------------------------
        container.addView(sectionTitle(Language.msg(l10n_wallet_seed_backup_heading)));
        container.addView(outlineButton(Language.join(Language.msg(l10n_wallet_change_seed), "…"), v -> act.changeSeed()));
        container.addView(outlineButton(Language.msg(l10n_wallet_reveal_seed), v -> revealSeed()));
        container.addView(outlineButton(Language.join(Language.msg(l10n_wallet_export_backup), "…"), v -> exportBackup()));
        container.addView(outlineButton(Language.join(Language.msg(l10n_wallet_restore_backup), "…"), v -> restore()));
        container.addView(hint(Language.msg(l10n_wallet_backup_help)));

        // --- Unlock & session ---------------------------------------------------------------------
        container.addView(sectionTitle(Language.msg(l10n_wallet_session_heading)));

        // Biometric unlock toggle (convenience only — the passphrase is always the fallback).
        boolean bioAvailable = BiometricUnlock.isAvailable(act);
        boolean bioEnabled   = BiometricUnlock.isEnabled(act);
        if (!bioAvailable && !bioEnabled) {
            container.addView(kv(Language.msg(l10n_wallet_bio_label), Language.msg(l10n_wallet_bio_unavailable)));
        } else if (bioEnabled) {
            container.addView(kv(Language.msg(l10n_wallet_bio_label), Language.msg(l10n_wallet_bio_enabled)));
            container.addView(outlineButton(Language.msg(l10n_wallet_bio_disable), v -> disableBiometric()));
        } else {
            container.addView(outlineButton(Language.msg(l10n_biometric_enable), v -> enableBiometric()));
        }
        container.addView(hint(Language.msg(l10n_wallet_bio_help)));

        // Auto-lock timeout.
        long timeout = act.session().timeoutMs();
        int tIdx = SessionPolicy.indexOfTimeout(timeout);
        container.addView(kv(Language.msg(l10n_lock_auto_lock), timeoutLabels()[tIdx]));
        container.addView(outlineButton(Language.msg(l10n_wallet_change_timeout), v -> pickAutoLock()));
        container.addView(hint(Language.msg(l10n_wallet_timeout_help)));

        // Change passphrase.
        container.addView(outlineButton(Language.msg(l10n_wallet_change_passphrase), v -> changePassphrase()));

        // Lock now.
        container.addView(outlineButton(Language.msg(l10n_wallet_lock_now), v -> {
            act.session().lock();
            act.recreate();
        }));

        // --- Node ---------------------------------------------------------------------------------
        container.addView(sectionTitle(Language.msg(l10n_tab_node)));
        container.addView(kv(Language.msg(l10n_wallet_node_connection), act.isPaired() ? Language.msg(l10n_wallet_connected) : Language.msg(l10n_wallet_not_connected)));
        container.addView(kv(Language.msg(l10n_wallet_chain_tip), act.blockLabel()));
        container.addView(kvMono(Language.msg(l10n_wallet_your_address), act.address0x()));

        // --- Restore historic coins ---------------------------------------------------------------
        addRestoreHistoricSection();

        // --- Diagnostics --------------------------------------------------------------------------
        container.addView(sectionTitle(Language.msg(l10n_wallet_diagnostics)));
        container.addView(outlineButton(Language.msg(l10n_wallet_run_diagnostics), v -> runDiagnostics()));
        container.addView(hint(Language.msg(l10n_wallet_diagnostics_help)));

        // --- App ----------------------------------------------------------------------------------
        container.addView(sectionTitle(Language.msg(l10n_wallet_app_heading)));
        container.addView(kv(Language.msg(l10n_wallet_design), Design.mode() == Design.Mode.ORIGINAL_LIGHT ? Language.msg(l10n_wallet_design_original_light)
                : Design.mode() == Design.Mode.ORIGINAL_DARK ? Language.msg(l10n_wallet_design_original_dark) : Language.msg(l10n_wallet_design_current)));
        container.addView(outlineButton(Language.msg(l10n_wallet_toggle_theme), v -> {
            Design.set(act, Design.next());
            act.recreate();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    /** Standalone reset UX, using the integrated worker and existing fail-closed authentication. */
    private void resetWallet() {
        if (!currentSession()) return;
        final EditText pass = passwordField(Language.msg(l10n_wallet_wallet_passphrase));
        new WalletDialog(act).sensitive()
                .setTitle(Language.msg(l10n_wallet_reset))
                .setMessage(Language.msg(l10n_wallet_reset_auth))
                .setView(wrap(pass))
                .setPositiveButton(Language.msg(l10n_wallet_continue), (d,w) -> {
                    final String password = pass.getText().toString();
                    pass.setText("");
                    authenticate(password, this::confirmReset);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null).show();
    }

    private void confirmReset() {
        if (!currentSession()) return;
        final EditText typed = new EditText(act);
        Language.hint(typed, Language.msg(l10n_wallet_reset_hint));
        typed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        typed.setTextColor(Design.text());
        typed.setHintTextColor(Design.dim());
        final androidx.appcompat.app.AlertDialog dialog = new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_reset_confirm))
                .setMessage(Language.msg(l10n_wallet_reset_body))
                .setView(wrap(typed))
                .setPositiveButton(Language.msg(l10n_wallet_reset_erase), (d,w) -> {
                    if (currentSession() && "RESET".equals(typed.getText().toString().trim())) act.resetWallet();
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null).create();
        dialog.show();
        final Button erase = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        erase.setEnabled(false);
        typed.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                erase.setEnabled("RESET".equals(s.toString().trim()));
            }
            public void afterTextChanged(android.text.Editable value) {}
        });
    }

    /**
     * Resolve an untrusted vault by ENTERING the number of one-time signatures key 0 has already made
     * (the common case for a restored wallet, which is never really at 0). Raises the counter (MAX-safe)
     * and enables signing. Over-estimating is safe; under-estimating risks a WOTS reuse, so the copy says
     * so. Twin of the numeric import path.
     */
    private void setUsesManually() {
        final EditText field = new EditText(act);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        Language.hint(field, Language.msg(l10n_wallet_floor_hint));
        field.setTextColor(Design.text());
        field.setHintTextColor(Design.dim());
        final int current;
        try { current = act.keyUses().currentUses(0); }
        catch (RuntimeException unavailable) {
            Toast.makeText(act, Language.msg(l10n_wallet_counter_unavailable), Toast.LENGTH_LONG).show();
            return;
        }
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_floor_title))
                .setMessage(Language.msg(l10n_wallet_floor_body, current, Util.WOTS_MAX_USES))
                .setView(wrap(field))
                .setPositiveButton(Language.msg(l10n_wallet_floor_enable), (d, w) -> {
                    if (!currentSession()) return;
                    String s = field.getText().toString().trim();
                    if (s.isEmpty()) {
                        Toast.makeText(act, Language.msg(l10n_wallet_enter_number), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final int n;
                    try {
                        n = Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        Toast.makeText(act, Language.msg(l10n_wallet_whole_number), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (n < 0 || n >= Util.WOTS_MAX_USES) {
                        Toast.makeText(act, Language.msg(l10n_wallet_floor_range, Util.WOTS_MAX_USES - 1),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final SeedVault vault = act.vault();
                    act.runAuthenticatedWalletWork(() -> { vault.setStartingKeyUses(n); return null; }, ignored -> {
                        Toast.makeText(act, Language.msg(l10n_wallet_floor_saved), Toast.LENGTH_LONG).show();
                        refresh();
                    }, error -> Toast.makeText(act, Language.msg(l10n_wallet_counter_update_failed), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void confirmBrandNew() {
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_confirm_new_seed))
                .setMessage(Language.msg(l10n_wallet_confirm_new_seed_body))
                .setPositiveButton(Language.msg(l10n_wallet_yes_unused), (d, w) -> {
                    if (!currentSession()) return;
                    final SeedVault vault = act.vault();
                    act.runAuthenticatedWalletWork(() -> { vault.confirmBrandNewSeed(); return null; }, ignored -> {
                        Toast.makeText(act, Language.msg(l10n_wallet_signing_enabled), Toast.LENGTH_LONG).show();
                        refresh();
                    }, error -> Toast.makeText(act, Language.msg(l10n_wallet_counter_update_failed), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    /**
     * C1: the LOUD attestation that turns a just-restored (untrusted) vault back into a signing wallet.
     * A backup can be STALE — if the seed signed anything after the backup was made, the restored WOTS
     * uses counter is too low, and signing now would REUSE a one-time key and expose it. Only the user
     * knows whether the backup is their most-recent state, so we make them say so explicitly (or sweep).
     */
    private void attestRestored() {
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_backup_current))
                .setMessage(Language.msg(l10n_wallet_backup_current_body))
                .setPositiveButton(Language.msg(l10n_wallet_confirm_latest), (d, w) -> {
                    if (!currentSession()) return;
                    final SeedVault vault = act.vault();
                    act.runAuthenticatedWalletWork(() -> { vault.attestKeyUsesUpToDate(); return null; }, ignored -> {
                        Toast.makeText(act, Language.msg(l10n_wallet_signing_restored), Toast.LENGTH_LONG).show();
                        refresh();
                    }, error -> Toast.makeText(act, Language.msg(l10n_wallet_counter_update_failed), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void revealSeed() {
        // Auth gate: re-enter the wallet passphrase before the seed is shown.
        final EditText pass = passwordField(Language.msg(l10n_wallet_wallet_passphrase));
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_reveal_seed))
                .setMessage(Language.msg(l10n_wallet_reveal_seed_warning))
                .setView(wrap(pass))
                .setPositiveButton(Language.msg(l10n_wallet_reveal), (d, w) -> {
                    if (!currentSession()) return;
                    authenticate(pass.getText().toString(), () -> showSeedDialog(act.vault().phrase()));
                    pass.setText("");
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void showSeedDialog(String phrase) {
        TextView tv = new TextView(act);
        tv.setText(phrase);
        tv.setTextIsSelectable(false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Design.text());
        tv.setTextSize(15f);
        tv.setPadding(dp(act, 8), dp(act, 8), dp(act, 8), dp(act, 8));
        // SECURITY (M2): the seed is deliberately NOT copyable to the system clipboard — a clipboard
        // entry is readable by other apps and easily leaked. Write it down by hand. The text is
        // displayed without selection so the platform selection menu cannot copy it.
        new WalletDialog(act).sensitive()
                .setTitle(Language.msg(l10n_wallet_your_seed))
                .setMessage(Language.msg(l10n_wallet_write_seed))
                .setView(wrap(tv))
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .show();
    }

    private void exportBackup() {
        final EditText pass = passwordField(Language.msg(l10n_wallet_backup_passphrase_hint));
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_export_backup))
                .setMessage(Language.msg(l10n_wallet_export_backup_body))
                .setView(wrap(pass))
                .setPositiveButton(Language.msg(l10n_wallet_choose_file), (d, w) -> {
                    if (!currentSession()) return;
                    String p = pass.getText().toString();
                    if (p.length() < 12) {
                        Toast.makeText(act, Language.msg(l10n_wallet_new_passphrase_short, 12), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pass.setText("");
                    final SeedVault vault = act.vault();
                    act.createBackupFile("minima-wallet-backup.mvlt", uri -> {
                        if (!currentSession()) return;
                        act.runAuthenticatedWalletWork(() -> {
                            byte[] bytes = vault.exportBytes(p);
                            act.writeBytes(uri, bytes);
                            return bytes.length;
                        }, size -> Toast.makeText(act, Language.msg(l10n_wallet_backup_saved, size), Toast.LENGTH_LONG).show(),
                           error -> Toast.makeText(act, Language.msg(l10n_wallet_backup_failed, WalletMessages.error(error)), Toast.LENGTH_LONG).show());
                    });
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void restore() {
        if (!currentSession()) return;
        act.openBackupFile(uri -> {
            if (!currentSession()) return;
            act.runAuthenticatedWalletWork(() -> act.readBytes(uri), bytes -> {
                final EditText pass = passwordField(Language.msg(l10n_wallet_backup_passphrase));
                new WalletDialog(act)
                        .setTitle(Language.msg(l10n_wallet_restore_backup))
                        .setMessage(Language.msg(l10n_wallet_restore_backup_body))
                        .setView(wrap(pass))
                        .setPositiveButton(Language.msg(l10n_wallet_restore), (d,w) -> {
                            doRestore(bytes, pass.getText().toString()); pass.setText("");
                        })
                        .setNegativeButton(Language.msg(l10n_common_cancel), null).show();
            }, error -> Toast.makeText(act, Language.msg(l10n_wallet_read_backup_failed, WalletMessages.error(error)), Toast.LENGTH_LONG).show());
        });
    }

    private void doRestore(byte[] bytes, String filePass) {
        if (!currentSession()) return;
        final SeedVault vault = act.vault();
        final android.content.Context app = act.getApplicationContext();
        act.runAuthenticatedWalletWork(() -> {
            SeedVault.peekImport(bytes, filePass);
            vault.importVault(bytes, filePass, filePass);
            // Same replacement rule as MainActivity's imported/generated seed paths.
            BiometricUnlock.disable(app);
            return null;
        }, ignored -> {
            Toast.makeText(act, Language.msg(l10n_wallet_restored_untrusted), Toast.LENGTH_LONG).show();
            act.recreate();
        }, error -> Toast.makeText(act, Language.msg(l10n_wallet_restore_failed), Toast.LENGTH_LONG).show());
    }

    /** Verify on the session worker; read/reveal the phrase only during foreground completion. */
    private void authenticate(String passphrase, Runnable approved) {
        if (!currentSession()) return;
        final SeedVault vault = act.vault();
        act.runAuthenticatedWalletWork(() -> vault.verifyPassphrase(passphrase), valid -> {
            if (!valid) Toast.makeText(act, Language.msg(l10n_wallet_wrong_passphrase), Toast.LENGTH_SHORT).show();
            else if (currentSession()) approved.run();
        }, error -> Toast.makeText(act, error instanceof SeedVault.VaultCorruptException
                ? Language.msg(l10n_wallet_vault_damaged, WalletMessages.error(error))
                : Language.msg(l10n_wallet_verify_failed), Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------------------------------------
    // Unlock & session actions
    // ---------------------------------------------------------------------------------------------

    /**
     * Enable biometric unlock. We require the passphrase here (defence-in-depth) and, crucially, obtain
     * the plaintext passphrase to hand to the Keystore — the vault holds it in memory only while open, so
     * re-confirming it is the clean way to capture it. On success it is stored encrypted by a
     * user-auth-required Keystore key.
     */
    private void enableBiometric() {
        final EditText pass = passwordField(Language.msg(l10n_wallet_wallet_passphrase));
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_biometric_enable))
                .setMessage(Language.msg(l10n_wallet_bio_confirm))
                .setView(wrap(pass))
                .setPositiveButton(Language.msg(l10n_wallet_continue), (d, w) -> {
                    if (!currentSession()) return;
                    final String p = pass.getText().toString();
                    pass.setText("");
                    authenticate(p, () -> BiometricUnlock.enable(act, p, act.sessionAuthorization(), new BiometricUnlock.Callback() {
                        @Override public void onSuccess() {
                            Toast.makeText(act, Language.msg(l10n_wallet_bio_success), Toast.LENGTH_LONG).show();
                            refresh();
                        }
                        @Override public void onError(String message, boolean invalidated) {
                            Toast.makeText(act, Language.msg(l10n_wallet_bio_failed, WalletMessages.message(message)), Toast.LENGTH_LONG).show();
                            refresh();
                        }
                        @Override public void onCancelled() { if (currentSession()) refresh(); }
                    }));
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void disableBiometric() {
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_bio_disable))
                .setMessage(Language.msg(l10n_wallet_bio_disable_body))
                .setPositiveButton(Language.msg(l10n_wallet_disable), (d, w) -> {
                    if (!currentSession()) return;
                    BiometricUnlock.disable(act);
                    Toast.makeText(act, Language.msg(l10n_wallet_bio_disabled), Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private static CharSequence[] timeoutLabels() {
        return new CharSequence[] {Language.msg(l10n_lock_immediately), Language.msg(l10n_lock_one_minute), Language.msg(l10n_lock_five_minutes), Language.msg(l10n_lock_fifteen_minutes), Language.msg(l10n_wallet_thirty_minutes), Language.msg(l10n_lock_one_hour)};
    }

    private void pickAutoLock() {
        int current = SessionPolicy.indexOfTimeout(act.session().timeoutMs());
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_timeout_title))
                .setSingleChoiceItems(timeoutLabels(), current, (d, which) -> {
                    act.session().setTimeoutMs(SessionPolicy.TIMEOUT_CHOICES_MS[which]);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    /**
     * Change the wallet passphrase: require the current passphrase, then a new one (min 12, matching the
     * export minimum) entered twice. Re-encrypts the vault atomically (verify-before-replace) and, if
     * biometric unlock is enabled, re-stores the new passphrase behind the Keystore key.
     */
    private void changePassphrase() {
        final EditText cur = passwordField(Language.msg(l10n_wallet_current_passphrase));
        final EditText n1  = passwordField(Language.msg(l10n_wallet_new_passphrase));
        final EditText n2  = passwordField(Language.msg(l10n_wallet_confirm_new_passphrase));

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 8));
        box.addView(cur, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        box.addView(n1, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        box.addView(n2, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));

        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_change_passphrase))
                .setMessage(Language.msg(l10n_wallet_change_passphrase_body))
                .setView(box)
                .setPositiveButton(Language.msg(l10n_wallet_change), (d, w) -> {
                    if (!currentSession()) return;
                    String c = cur.getText().toString();
                    String a = n1.getText().toString();
                    String b = n2.getText().toString();
                    if (!SessionPolicy.passphraseMeetsMin(a)) {
                        Toast.makeText(act, Language.msg(l10n_wallet_new_passphrase_short, SessionPolicy.MIN_PASSPHRASE), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!a.equals(b)) {
                        Toast.makeText(act, Language.msg(l10n_wallet_new_passphrase_mismatch), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final SeedVault vault = act.vault();
                    final android.content.Context app = act.getApplicationContext();
                    final boolean hadBiometric = BiometricUnlock.isEnabled(app);
                    cur.setText(""); n1.setText(""); n2.setText("");
                    act.runAuthenticatedWalletWork(() -> {
                        if (!vault.verifyPassphrase(c)) return false;
                        vault.changePassphrase(a);
                        BiometricUnlock.disable(app);
                        return true;
                    }, changed -> {
                        if (!changed) {
                            Toast.makeText(act, Language.msg(l10n_wallet_current_passphrase_wrong), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    // If biometric is on, re-store the NEW passphrase behind the Keystore key.
                    if (hadBiometric) {
                        BiometricUnlock.enable(act, a, act.sessionAuthorization(), new BiometricUnlock.Callback() {
                            @Override public void onSuccess() {
                                Toast.makeText(act, Language.msg(l10n_wallet_passphrase_bio_updated),
                                        Toast.LENGTH_LONG).show();
                            }
                            @Override public void onError(String message, boolean invalidated) {
                                // Vault passphrase already changed successfully; just the biometric copy
                                // is now stale — disable it so the user re-enables with the new one.
                                BiometricUnlock.disable(act);
                                Toast.makeText(act, Language.msg(l10n_wallet_passphrase_bio_off), Toast.LENGTH_LONG).show();
                                refresh();
                            }
                            @Override public void onCancelled() {
                                BiometricUnlock.disable(act);
                                Toast.makeText(act, Language.msg(l10n_wallet_passphrase_bio_off),
                                        Toast.LENGTH_LONG).show();
                                refresh();
                            }
                        });
                    } else {
                        Toast.makeText(act, Language.msg(l10n_wallet_passphrase_changed), Toast.LENGTH_LONG).show();
                    }
                    }, error -> Toast.makeText(act, error instanceof SeedVault.VaultCorruptException
                            ? Language.msg(l10n_wallet_vault_damaged, WalletMessages.error(error))
                            : Language.msg(l10n_wallet_passphrase_change_failed, WalletMessages.error(error)), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics — read-only wallet↔node visibility (non-admin commands only)
    // ---------------------------------------------------------------------------------------------

    private static final String DIAG_TAG = "walletdiag";

    private Language.TextBuilder mDiag;
    private TextView mDiagText;
    private ScrollView mDiagScroll;

    /**
     * Run a fixed sequence of READ-ONLY node queries and show every raw result in a scrollable,
     * selectable dialog (and mirror each line to logcat under {@code walletdiag}). This exists to make
     * the wallet↔node interaction visible when a balance stays empty while funds are confirmed on-chain.
     * No admin command is ever issued.
     */
    private void runDiagnostics() {
        mDiag = new Language.TextBuilder();

        // Scrollable, selectable report body.
        mDiagText = new TextView(act);
        mDiagText.setTextIsSelectable(true);
        mDiagText.setTypeface(Typeface.MONOSPACE);
        mDiagText.setTextColor(Design.text());
        mDiagText.setTextSize(11f);
        mDiagText.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));

        mDiagScroll = new ScrollView(act);
        mDiagScroll.addView(mDiagText);

        final androidx.appcompat.app.AlertDialog dialog = new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_wallet_diagnostics))
                .setView(mDiagScroll)
                .setPositiveButton(Language.msg(l10n_wallet_copy), null)   // wired below so it does NOT dismiss
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .create();
        dialog.show();
        // Keep the dialog open when Copy is tapped (results may still be arriving).
        Button copy = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        if (copy != null) copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                // The diagnostic text is NOT the seed — clipboard is acceptable here.
                cm.setPrimaryClip(ClipData.newPlainText("Minima wallet diagnostics", mDiag.build().toString()));
                Toast.makeText(act, Language.msg(l10n_wallet_diagnostics_copied), Toast.LENGTH_SHORT).show();
            }
        });

        // Step 1 — pairing state (synchronous).
        diagLine(Language.join("=== ", Language.msg(l10n_wallet_wallet_diagnostics), " ==="));
        diagLine("");
        diagLine(Language.join("[1] ", Language.msg(l10n_wallet_node_connection)));
        boolean paired = act.isPaired();
        diagLine("  isPaired(): " + paired);
        diagLine((paired ? Language.msg(l10n_wallet_node_connected_help) : Language.msg(l10n_wallet_node_disconnected_help)));
        diagLine("");

        // Step 2 — our address (both forms), from the same derivation the wallet uses.
        diagLine(Language.join("[2] ", Language.msg(l10n_wallet_your_address)));
        final String addr0x = act.address0x();
        diagLine("  Mx: " + act.defaultAddress());
        diagLine("  0x: " + addr0x);
        diagLine(Language.msg(l10n_wallet_address_hex_query));
        diagLine("");

        final NodeLink node = act.node();
        if (node == null) {
            diagLine(Language.msg(l10n_wallet_node_link_unavailable));
            return;
        }

        // Steps 3-6 run sequentially, chaining the callbacks.
        diagStatus(node, addr0x);
    }

    /** Step 3 — status: block height, sync state, MegaMMR flag + raw. */
    private void diagStatus(final NodeLink node, final String addr0x) {
        diagLine("[3] status");
        node.raw("status", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String height = r.optString("length", r.optString("block", "?"));
                    JSONObject chain = r.optJSONObject("chain");
                    if (chain != null) {
                        height = chain.optString("block", chain.optString("length", height));
                    }
                    diagLine("  status: " + json.optBoolean("status", false));
                    diagLine("  block/height: " + height);
                    diagLine("  syncing/root: " + r.optString("root", r.optString("syncing", "?")));
                    // MegaMMR flag can appear at response root or nested — surface whatever we find.
                    diagLine(Language.join("  megammr: ", megammrFlag(r)));
                }
                diagRaw(json);
                diagScripts(node, addr0x);
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagScripts(node, addr0x);
            }
        });
    }

    /** Step 4 — scripts address:<ours> (is our RETURN SIGNEDBY script TRACKED / relevant?). */
    private void diagScripts(final NodeLink node, final String addr0x) {
        final String cmd = "scripts";
        diagLine(Language.join("[4] ", cmd, " — ", Language.msg(l10n_wallet_check_base_addresses, AddressBook.SIZE)));
        node.raw(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                diagLine("  status: " + json.optBoolean("status", false));
                AddressBook book = act.addressBook();
                org.json.JSONArray arr = json.optJSONArray("response");
                java.util.HashSet<String> tracked = new java.util.HashSet<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject row = arr.optJSONObject(i);
                        if (row != null && row.optBoolean("track", row.optBoolean("trackall", true))) {
                            tracked.add(row.optString("address", ""));
                        }
                    }
                }
                int derived = book.derivedCount();
                StringBuilder missing = new StringBuilder();
                int hits = 0;
                for (int i = 0; i < derived; i++) {
                    if (tracked.contains(book.addr0x(i))) hits++;
                    else missing.append(missing.length() == 0 ? "" : ", ").append(i);
                }
                diagLine(Language.join("  ", Language.msg(l10n_wallet_derived_addresses, derived, AddressBook.SIZE)));
                diagLine(Language.join("  ", Language.msg(l10n_wallet_tracked_addresses, hits, derived)));
                if (missing.length() > 0) {
                    diagLine(Language.msg(l10n_wallet_not_tracked, missing.toString()));
                    diagLine(Language.msg(l10n_wallet_tracking_help));
                }
                diagCheckAddress(node, addr0x);
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagCheckAddress(node, addr0x);
            }
        });
    }

    /** Step 5 — checkaddress address:<ours> (confirm the address is valid / recognised). */
    private void diagCheckAddress(final NodeLink node, final String addr0x) {
        final String cmd = "checkaddress address:" + addr0x;
        diagLine("[5] " + cmd);
        node.raw(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                diagLine("  status: " + json.optBoolean("status", false));
                diagRaw(json);
                diagCoins(node, addr0x);
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagCoins(node, addr0x);
            }
        });
    }

    /** Step 6 — coins relevant:true address:<ours> (the exact query the wallet uses for balances). */
    private void diagCoins(final NodeLink node, final String addr0x) {
        final String cmd = "coins relevant:true address:" + addr0x;
        diagLine("[6] " + cmd);
        node.raw(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                diagLine("  status: " + json.optBoolean("status", false));
                diagCoinsSummary(json);
                diagRaw(json);
                diagLine("");
                diagLine(Language.msg(l10n_wallet_end_diagnostics));
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagLine("");
                diagLine(Language.msg(l10n_wallet_end_diagnostics));
            }
        });
    }

    /** Best-effort MegaMMR flag lookup across the likely field names / nesting. */
    private static CharSequence megammrFlag(JSONObject response) {
        if (response.has("megammr"))   return String.valueOf(response.opt("megammr"));
        if (response.has("MEGAMMR"))   return String.valueOf(response.opt("MEGAMMR"));
        JSONObject mmr = response.optJSONObject("megammr");
        if (mmr != null)               return mmr.toString();
        // Some builds nest it under memory/txpow; note absence explicitly rather than silently.
        return Language.msg(l10n_wallet_field_absent);
    }

    /** Append a coins-response one-liner: status + coin count + first coin's amount/coinid. */
    private void diagCoinsSummary(JSONObject json) {
        diagLine("  status: " + json.optBoolean("status", false));
        org.json.JSONArray arr = json.optJSONArray("response");
        int count = arr == null ? 0 : arr.length();
        diagLine(Language.msg(l10n_wallet_diagnostic_coin_count, count));
        if (arr != null && count > 0) {
            JSONObject c0 = arr.optJSONObject(0);
            if (c0 != null) {
                diagLine(Language.join(Language.msg(l10n_wallet_first_coin), ": amount=", c0.optString("amount", "?"), " tokenid=", c0.optString("tokenid", "0x00"), " coinid=", c0.optString("coinid", "?")));
            }
        }
        if (!json.optBoolean("status", false)) {
            String err = json.optString("error", "");
            if (!err.isEmpty()) diagLine(Language.join(Language.msg(l10n_wallet_node_error), ": ", err));
        }
    }

    /** Append a step's raw JSON, pretty-printed where possible. */
    private void diagRaw(JSONObject json) {
        String raw;
        try {
            raw = json.toString(2);
        } catch (Throwable t) {
            raw = json.toString();
        }
        diagLine(Language.join(Language.msg(l10n_wallet_raw_response), ": ", raw));
        diagLine("");
    }

    private void diagErr(String message) {
        diagLine(Language.join(Language.msg(l10n_wallet_node_error), ": ", WalletMessages.message(message)));
        diagLine("");
    }

    /** Append one line to the report: log it, add to the buffer, update the dialog, scroll to bottom. */
    private void diagLine(CharSequence line) {
        Log.i(DIAG_TAG, line.toString());
        if (mDiag == null) return;
        mDiag.append(line).append('\n');
        if (mDiagText != null) Language.setText(mDiagText, mDiag.build());
        if (mDiagScroll != null) mDiagScroll.post(() -> mDiagScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------------------------------------
    // Restore historic coins — recover coins funded BEFORE we began tracking (non-admin)
    // ---------------------------------------------------------------------------------------------

    /**
     * The exact WHOLE-NODE re-sync command. Shown as copyable text ONLY — the wallet is non-admin and
     * MUST NEVER issue this (it re-syncs the entire node from the public MegaMMR). Display-only.
     */
    private static final String MEGAMMR_RESYNC_CMD =
            "megammrsync action:resync host:megammr.minima.global:9001";

    /**
     * Build the RESTORE HISTORIC COINS section. Coins funded at our address BEFORE the wallet ran
     * {@code newscript} (forward-only tracking) are never back-filled, so a regular node can't see them.
     *
     * <p>Primary path (works now, non-admin): paste a {@code coinexport} blob from a MegaMMR node; we
     * {@code coincheck} it against the LOCAL tip MMR, and on a valid proof {@code coinimport track:true}
     * to insert + mark it spendable, then refresh balances. Advanced path: the whole-node
     * {@code megammrsync} re-sync shown as copyable text only (admin — the wallet never runs it).
     */
    private void addRestoreHistoricSection() {
        container.addView(sectionTitle(Language.msg(l10n_wallet_historic_coins)));
        container.addView(hint(Language.msg(l10n_wallet_historic_help)));

        // --- Primary path: import a coin export (non-admin, works now) ---------------------------
        final EditText blob = multilineField(Language.msg(l10n_wallet_coin_export_hint));
        container.addView(blob);
        container.addView(outlineButton(Language.msg(l10n_wallet_import_coin_export), v -> importCoinExport(blob)));
        container.addView(hint(Language.join(Language.msg(l10n_wallet_get_coin_export), "\n\ncoins address:<Mx> megammr:true\ncoinexport coinid:<id>")));
        container.addView(kvMono(Language.msg(l10n_wallet_your_address), act.defaultAddress()));

        // --- Advanced path: whole-node re-sync (DISPLAY ONLY — the wallet never runs it) ---------
        container.addView(hint(Language.msg(l10n_wallet_advanced_resync)));
        container.addView(monoBox(MEGAMMR_RESYNC_CMD));
        container.addView(outlineButton(Language.msg(l10n_wallet_copy_resync),
                v -> copyToClipboard("Minima megammrsync command", MEGAMMR_RESYNC_CMD)));
        container.addView(warnBox(Language.msg(l10n_wallet_resync_warning_title),
                Language.msg(l10n_wallet_resync_warning)));
    }

    private Language.TextBuilder mImportLog;
    private TextView mImportText;

    /**
     * Import flow: validate then insert an exported coin proof. Sanitises the pasted blob (strips
     * whitespace/newlines; requires a {@code 0x…} hex string), then {@code coincheck} → (if valid)
     * {@code coinimport track:true} → refresh balances. Every step's result (and any node error) is shown
     * verbatim in a selectable dialog. Both node commands are non-admin.
     */
    private void importCoinExport(final EditText input) {
        // Sanitise: a paste often carries surrounding whitespace / newlines.
        final String hex = input.getText().toString().replaceAll("\\s+", "");
        if (hex.isEmpty()) {
            Toast.makeText(act, Language.msg(l10n_wallet_paste_coin_export), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hex.matches("0[xX][0-9a-fA-F]+")) {
            Toast.makeText(act, Language.msg(l10n_wallet_invalid_coin_export),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final NodeLink node = act.node();
        if (node == null) {
            Toast.makeText(act, Language.msg(l10n_wallet_node_disconnected_help),
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Selectable, scrollable results dialog; each step appends its outcome.
        mImportLog = new Language.TextBuilder();
        mImportText = new TextView(act);
        mImportText.setTextIsSelectable(true);
        mImportText.setTypeface(Typeface.MONOSPACE);
        mImportText.setTextColor(Design.text());
        mImportText.setTextSize(11f);
        mImportText.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        ScrollView scroll = new ScrollView(act);
        scroll.addView(mImportText);
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_restore_historic_coin))
                .setView(scroll)
                .setPositiveButton(Language.msg(l10n_language_close), null)
                .show();

        // Step 1 — coincheck: validate the proof against the LOCAL tip MMR (read-only, non-admin).
        importLog(Language.msg(l10n_wallet_checking_coin_proof));
        node.coinCheck(hex, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                boolean valid = json.optBoolean("status", false);
                importLog(Language.join(Language.msg(l10n_wallet_proof_valid), ": ", valid));
                String err = json.optString("error", "");
                if (!err.isEmpty()) importLog(Language.join(Language.msg(l10n_wallet_node_error), ": ", err));
                importRaw(json);
                if (!valid) {
                    importLog("");
                    importLog(Language.msg(l10n_wallet_coin_proof_invalid));
                    return;
                }
                // Step 2 — coinimport track:true: insert + mark relevant/spendable (non-admin).
                importLog("");
                importLog(Language.msg(l10n_wallet_importing_coin));
                node.coinImport(hex, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json2) {
                        boolean imported = json2.optBoolean("status", false);
                        importLog(Language.join(Language.msg(l10n_wallet_coin_imported), ": ", imported));
                        String e2 = json2.optString("error", "");
                        if (!e2.isEmpty()) importLog(Language.join(Language.msg(l10n_wallet_node_error), ": ", e2));
                        importRaw(json2);
                        if (imported) {
                            importLog("");
                            importLog(Language.msg(l10n_wallet_coins_imported));
                            // Same path MainActivity uses to re-pull tokens + coins + balances.
                            act.reload();
                        }
                    }
                    @Override public void onError(String message) {
                        importLog(Language.join(Language.msg(l10n_wallet_node_error), ": ", WalletMessages.message(message)));
                    }
                });
            }
            @Override public void onError(String message) {
                importLog(Language.join(Language.msg(l10n_wallet_node_error), ": ", WalletMessages.message(message)));
            }
        });
    }

    /** Append one line to the import results dialog. */
    private void importLog(CharSequence line) {
        if (mImportLog == null) return;
        mImportLog.append(line).append('\n');
        if (mImportText != null) Language.setText(mImportText, mImportLog.build());
    }

    /** Append a step's raw JSON, pretty-printed where possible. */
    private void importRaw(JSONObject json) {
        String raw;
        try { raw = json.toString(2); } catch (Throwable t) { raw = json.toString(); }
        importLog(Language.join(Language.msg(l10n_wallet_raw_response), ": ", raw));
    }

    /** Copy plain text to the system clipboard (never used for the seed — safe for commands only). */
    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(act, Language.msg(l10n_history_copied), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Small view builders
    // ---------------------------------------------------------------------------------------------

    private TextView sectionTitle(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setPadding(0, dp(act, 18), 0, dp(act, 8));
        return t;
    }

    private View kv(CharSequence label, CharSequence value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(act, 5), 0, dp(act, 5));
        TextView l = new TextView(act);
        Language.setText(l, label); l.setTextColor(Design.dim()); l.setTextSize(14f);
        TextView v = new TextView(act);
        Language.setText(v, value); v.setTextColor(Design.text()); v.setTextSize(14f); v.setGravity(Gravity.END);
        v.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroupWC(), 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroupWC(), 1.4f));
        return r;
    }

    private View kvMono(CharSequence label, CharSequence value) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(act, 5), 0, dp(act, 5));
        TextView l = new TextView(act);
        Language.setText(l, label); l.setTextColor(Design.dim()); l.setTextSize(14f);
        TextView v = new TextView(act);
        Language.setText(v, value); v.setTextColor(Design.text()); v.setTextSize(11f);
        v.setTypeface(Typeface.MONOSPACE); v.setTextIsSelectable(true);
        col.addView(l);
        col.addView(v);
        return col;
    }

    private TextView hint(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(Design.dim2()); t.setTextSize(11.5f);
        t.setPadding(0, dp(act, 6), 0, dp(act, 4));
        return t;
    }

    private View warnBox(CharSequence title, CharSequence body) {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.redSoft());
        b.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8); b.setLayoutParams(lp);
        TextView t = new TextView(act);
        Language.setText(t, title); t.setTextColor(Design.red()); t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setTextSize(14f);
        TextView d = new TextView(act);
        Language.setText(d, body); d.setTextColor(Design.text()); d.setTextSize(12f); d.setPadding(0, dp(act, 4), 0, 0);
        b.addView(t); b.addView(d);
        return b;
    }

    private Button outlineButton(CharSequence label, View.OnClickListener l) {
        Button btn = new Button(act);
        btn.setAllCaps(false);
        Language.setText(btn, label);
        btn.setTextColor(Design.accent());
        btn.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8); btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    private Button dangerButton(CharSequence label, View.OnClickListener l) {
        Button btn = outlineButton(label, l);
        btn.setTextColor(Design.onAccent());
        btn.setBackgroundColor(Design.red());
        return btn;
    }

    private EditText passwordField(CharSequence hint) {
        EditText e = new EditText(act);
        Language.hint(e, hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        return e;
    }

    /** A multi-line, monospace text input (for pasting a long hex blob). Editable == selectable. */
    private EditText multilineField(CharSequence hint) {
        EditText e = new EditText(act);
        Language.hint(e, hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(3);
        e.setMaxLines(6);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(12f);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        e.setBackgroundColor(Design.surface());
        e.setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8);
        e.setLayoutParams(lp);
        return e;
    }

    /** A selectable, monospace box for showing a command / hex string the user can copy. */
    private TextView monoBox(String text) {
        TextView t = new TextView(act);
        Language.setText(t, text);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(Design.text());
        t.setTextSize(12f);
        t.setBackgroundColor(Design.surface());
        t.setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private View wrap(View v) {
        LinearLayout w = new LinearLayout(act);
        w.setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 8));
        w.addView(v, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        return w;
    }

    private static int ViewGroupMP() { return LinearLayout.LayoutParams.MATCH_PARENT; }
    private static int ViewGroupWC() { return LinearLayout.LayoutParams.WRAP_CONTENT; }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
