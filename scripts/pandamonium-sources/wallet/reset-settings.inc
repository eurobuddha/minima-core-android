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
